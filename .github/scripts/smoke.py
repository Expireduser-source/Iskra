#!/usr/bin/env python3
"""
CI emulator check (stdlib only).

Installs the debug APK, walks through onboarding and every tab, puts Iskra over the home screen, checks dark
theme, large font and landscape, and takes a screenshot at every step. Fails when the app crashes.
Problems are printed as GitHub annotations (the cloud session that maintains the app can read those, but
not the logs); screenshots and the app journal go into the output folder.

Buttons are found by Compose test tags (exposed as resource ids), so the walk works in any UI language.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "ru.iskra.app.debug"
MAIN = f"{PKG}/ru.iskra.app.ui.MainActivity"


def run(args, timeout=90, binary=False):
    try:
        r = subprocess.run(args, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return b"" if binary else ""
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def adb(*a, **kw):
    return run(["adb", *a], **kw)


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def annotate(kind, title, text):
    text = text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::{kind} title={title}::{text}", flush=True)


class Smoke:
    def __init__(self, out, api, lang):
        self.out, self.api, self.lang = out, api, lang
        os.makedirs(out, exist_ok=True)
        self.n = 0
        self.problems = []

    def note(self, msg):
        print(msg, flush=True)
        self.problems.append(msg)

    def shot(self, name):
        self.n += 1
        data = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        if data.startswith(b"\x89PNG"):
            with open(os.path.join(self.out, f"{self.n:02d}-{name}.png"), "wb") as f:
                f.write(data)
        else:
            self.note(f"screenshot {name} failed")

    def nodes(self):
        for _ in range(3):
            out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", timeout=60)
            i = out.find("<?xml")
            if i >= 0:
                try:
                    return list(ET.fromstring(out[i:].strip()).iter("node"))
                except ET.ParseError:
                    pass
            time.sleep(2)
        return []

    def find(self, rid):
        for node in self.nodes():
            r = node.get("resource-id", "")
            if r == rid or r.endswith("/" + rid):
                return node
        return None

    def tap(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "[0,0][0,0]")))
        sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")

    def tap_id(self, rid, wait=10, quiet=False):
        end = time.time() + wait
        while time.time() < end:
            node = self.find(rid)
            if node is not None:
                self.tap(node)
                time.sleep(1.5)
                return True
            time.sleep(1)
        if not quiet:
            self.note(f"API {self.api}: button «{rid}» not found")
        return False

    def type_text(self, text):
        # adb "input text" is ASCII only; spaces must be %s.
        sh("input text " + "'" + text.replace(" ", "%s").replace("'", "") + "'")

    def alive(self):
        return bool(sh(f"pidof {PKG}").strip())

    def crashes(self):
        log = adb("logcat", "-d", "-b", "crash", timeout=60)
        blocks, cur = [], []
        for line in log.splitlines():
            if "FATAL EXCEPTION" in line and cur:
                blocks.append("\n".join(cur)); cur = []
            cur.append(line)
        if cur:
            blocks.append("\n".join(cur))
        return [b for b in blocks if PKG in b]

    def save_logs(self):
        with open(os.path.join(self.out, "logcat.txt"), "w") as f:
            f.write(adb("logcat", "-d", "-v", "time", timeout=90))
        # The app's own journal (debug builds are debuggable, so run-as works).
        journal = sh(f"run-as {PKG} cat files/diag.log", timeout=60)
        if journal.strip():
            with open(os.path.join(self.out, "journal.txt"), "w") as f:
                f.write(journal)


LOCAL_BRAIN_KT = "app/src/main/java/ru/iskra/app/data/LocalBrain.kt"


def model_urls():
    with open(LOCAL_BRAIN_KT, encoding="utf-8") as f:
        return re.findall(r'"(https://huggingface\.co/[^"]+\.gguf)"', f.read())


def model_head(url):
    """HTTP status plus the size and SHA-256 Hugging Face announces on its first redirect."""
    import urllib.error
    import urllib.request

    class Stay(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "Iskra-CI"})
    try:
        r = urllib.request.build_opener(Stay).open(req, timeout=30)
        code, h = r.status, r.headers
    except urllib.error.HTTPError as e:
        code, h = e.code, e.headers
    except Exception as e:  # noqa: BLE001 — network trouble is reported, not fatal
        return 0, None, f"{type(e).__name__}"
    return code, h.get("X-Linked-Size"), (h.get("X-Linked-Etag") or "").strip('"')


def local_brain_check(s, api):
    """
    The model on the phone, for real: every catalog link must answer; the light model is downloaded, put into the
    app's folder as custom.gguf and asked a question with no cloud configured. The app journal must show its reply.
    """
    import hashlib
    import urllib.request

    urls = model_urls()
    for u in urls:
        code, size, sha = model_head(u)
        print(f"model link {u.rsplit('/', 1)[-1]}: HTTP {code}, size {size}, sha256 {sha}", flush=True)
        if code not in (200, 301, 302, 307, 308) or not size:
            annotate("error", "On-phone model link", f"{u}: HTTP {code}, size {size}")
    if not urls:
        s.note("no model links found in LocalBrain.kt")
        return
    url = urls[0]
    _, size, sha = model_head(url)
    path = "/tmp/iskra-model.gguf"
    t = time.time()
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Iskra-CI"})
        with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
            while True:
                b = r.read(1 << 20)
                if not b:
                    break
                f.write(b)
    except Exception as e:  # noqa: BLE001
        annotate("error", "On-phone model download", f"{url}: {e}")
        return
    got = os.path.getsize(path)
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    print(f"downloaded {got} bytes in {time.time() - t:.0f} s, sha256 {h.hexdigest()}", flush=True)
    if sha and h.hexdigest() != sha:
        annotate("error", "On-phone model checksum", f"expected {sha}, got {h.hexdigest()}")
        return
    folder = f"/sdcard/Android/data/{PKG}/files/llm"
    sh(f"mkdir -p {folder}")
    print(adb("push", path, f"{folder}/custom.gguf", timeout=900).strip()[-200:], flush=True)
    sh(f"am force-stop {PKG}")
    time.sleep(1)
    sh(f"am start -W -n {MAIN}", timeout=120)
    time.sleep(4)
    if not (s.tap_id("tab_0") and s.tap_id("chat_input")):
        return
    s.type_text("Hi! Say one short sentence about cats.")
    time.sleep(1)
    sh("input keyevent KEYCODE_BACK")
    if not s.tap_id("chat_send"):
        return
    t = time.time()
    reply = loaded = None
    while time.time() - t < 240:
        journal = sh(f"run-as {PKG} cat files/diag.log", timeout=60)
        m = re.search(r"(?:Model loaded in|Модель загружена за) [^\n]+", journal)
        loaded = m.group(0) if m else loaded
        m = re.search(r"(?:LLM reply|LLM ответ): [^\n]+", journal)
        if m:
            reply = m.group(0)
            break
        time.sleep(5)
    time.sleep(2)
    s.shot("local-brain-chat")
    if reply:
        annotate("notice", f"On-phone model, API {api}", f"{reply}; {loaded or 'load time not logged'}; {got // 1_000_000} MB model")
    else:
        annotate("error", f"On-phone model, API {api}", f"no reply in 4 minutes; {loaded or 'the model was not loaded'}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--api", type=int, required=True)
    ap.add_argument("--lang", default="")
    a = ap.parse_args()
    s = Smoke(a.out, a.api, a.lang)

    adb("wait-for-device", timeout=300)
    for _ in range(60):
        if sh("getprop sys.boot_completed").strip() == "1":
            break
        time.sleep(5)
    sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")

    sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")

    out = adb("install", "-r", "-g", a.apk, timeout=300)
    print(out.strip(), flush=True)
    if "Success" not in out:
        annotate("error", f"Install failed on API {a.api}", out[-1500:])
        sys.exit(1)
    sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
    if a.lang and a.api >= 33:
        sh(f"cmd locale set-app-locales {PKG} --locales {a.lang}")
    adb("logcat", "-b", "all", "-c")

    t0 = time.time()
    sh(f"am start -W -n {MAIN}", timeout=120)
    time.sleep(4)
    print(f"first start: {time.time() - t0:.1f} s", flush=True)
    s.shot("onboarding-1")

    # Onboarding: 4 steps. On step 2 the overlay permission is already granted (appops), so the switch is there.
    for step in range(2, 5):
        if not s.tap_id("onb_next"):
            break
        s.shot(f"onboarding-{step}")
        if step == 2 and s.tap_id("overlay_switch"):
            time.sleep(3)
            s.shot("onboarding-iskra-on")
    s.tap_id("onb_next")  # «Начать» / «Start»
    time.sleep(2)

    s.shot("chat")
    if s.tap_id("chat_surprise", quiet=True):
        time.sleep(4)
        s.shot("chat-surprise")
    for i, name in [(1, "memory"), (2, "settings"), (3, "permissions")]:
        if s.tap_id(f"tab_{i}"):
            s.shot(name)
    if s.tap_id("tab_0") and s.tap_id("chat_input"):
        s.type_text("set a timer for 2 minutes")
        time.sleep(1)
        sh("input keyevent KEYCODE_BACK")  # hide the keyboard
        if s.tap_id("chat_send"):
            time.sleep(4)
            s.shot("chat-command")

    # Iskra over the home screen.
    sh("input keyevent KEYCODE_HOME")
    time.sleep(5)
    s.shot("home-with-iskra")

    # "Share → Iskra": shared text comes with actions; "To notes" works offline.
    sh(f"am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'Buy oat milk and bread for Sunday' -n {PKG}/ru.iskra.app.ui.QuickChatActivity")
    time.sleep(4)
    s.shot("share-sheet")
    if s.tap_id("share_note"):
        time.sleep(3)
        s.shot("share-note-saved")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)
    # Launcher shortcut "Reminder": the quick chat opens with the text ready to edit.
    sh(f"am start -a ru.iskra.app.action.REMIND -n {PKG}/ru.iskra.app.ui.QuickChatActivity")
    time.sleep(3)
    s.shot("shortcut-reminder")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)

    # Back to the app: landscape, dark theme, large font.
    sh(f"am start -n {MAIN}")
    time.sleep(3)
    sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
    time.sleep(4)
    s.shot("landscape")
    sh("settings put system user_rotation 0")
    time.sleep(3)
    if a.api >= 29:
        sh("cmd uimode night yes")
        time.sleep(4)
        s.shot("dark")
        sh("cmd uimode night no")
        time.sleep(3)
    sh("settings put system font_scale 1.3")
    time.sleep(4)
    if s.tap_id("tab_3", quiet=True):
        s.shot("large-font-permissions")
    sh("settings put system font_scale 1.0")
    time.sleep(2)

    # Back and forward through the app once more: the process must survive everything above.
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    sh(f"am start -n {MAIN}")
    time.sleep(3)
    s.shot("final")

    # The model on the phone (x86_64 emulators from Android 11; one download per job).
    if a.api >= 30 and a.lang in ("", "en"):
        local_brain_check(s, a.api)

    crashes = s.crashes()
    alive = s.alive()
    s.save_logs()
    for c in crashes:
        annotate("error", f"Iskra crashed on Android API {a.api}", c[:6000])
    if not alive and not crashes:
        annotate("error", f"Iskra is not running on API {a.api}", "The process is gone without a crash report (ANR or killed).")
    for p in s.problems:
        annotate("warning", f"Emulator check, API {a.api}", p)
    print(f"API {a.api}: {s.n} screenshots, crashes: {len(crashes)}, alive: {alive}", flush=True)
    annotate("notice", f"Emulator check, API {a.api}", f"{s.n} screenshots, crashes: {len(crashes)}, process alive: {alive}")
    sys.exit(1 if crashes or not alive else 0)


if __name__ == "__main__":
    main()
