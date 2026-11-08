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
        self.audit(name)

    def idle_cost(self, name, seconds=10):
        """Frames drawn and processor time taken while the app only shows itself (animations on): the battery question
        of 0.16.0's living header and of her on the home screen. The emulator draws in software, so the processor share
        is far above a phone's; the frame rate is the app's own. Written to idle.txt and as a notice."""
        pid = sh(f"pidof {PKG}").strip().split(" ")[0]
        if not pid:
            return
        def ticks():
            st = sh(f"cat /proc/{pid}/stat") or sh(f"run-as {PKG} cat /proc/{pid}/stat")
            f = st[st.rfind(")") + 2:].split() if ")" in st else []
            return int(f[11]) + int(f[12]) if len(f) > 12 else None  # utime + stime, in clock ticks (100 a second)
        sh(f"dumpsys gfxinfo {PKG} reset")
        t0, c0 = time.time(), ticks()
        time.sleep(seconds)
        c1, took = ticks(), time.time() - t0
        g = sh(f"dumpsys gfxinfo {PKG}")
        frames = re.search(r"Total frames rendered:\s*(\d+)", g)
        jank = re.search(r"Janky frames:\s*(\d+)", g)
        line = f"{name}: " + (f"{int(frames.group(1)) / took:.1f} frames/s" if frames else "frames unknown") + \
            (f" ({jank.group(1)} janky)" if jank else "") + \
            (f", processor {100.0 * (c1 - c0) / 100 / took:.0f}% of one core" if c0 is not None and c1 is not None else "")
        print(line, flush=True)
        with open(os.path.join(self.out, "idle.txt"), "a") as f:
            f.write(line + "\n")
        self.idle = getattr(self, "idle", []) + [line]

    def density(self):
        if not getattr(self, "_density", 0):
            found = re.findall(r"density:\s*(\d+)", sh("wm density"))
            self._density = int(found[-1]) if found else 160
        return self._density

    def audit(self, name):
        """What TalkBack and a finger meet on this screen (0.16.1): every control of the app has a name to be read out,
        and is at least 48 dp across (the size Android's Accessibility Scanner asks for). Written to a11y.txt; the
        summary is a warning, never a failure."""
        nodes = self.nodes()
        if not nodes:
            return
        root = nodes[0]
        parent = {}
        for n in nodes:
            for c in n.findall("node"):
                parent[c] = n
        def bounds(n):
            return list(map(int, re.findall(r"-?\d+", n.get("bounds", "[0,0][0,0]"))))
        def named(n):
            return any((d.get("text") or "").strip() or (d.get("content-desc") or "").strip() for d in n.iter("node"))
        def clipped(n):
            # Cut by the edge of a list it scrolls in, or of the screen: its size on screen is not its size, and what
            # it says may lie in the part that isn't shown (the dump leaves invisible text out).
            x1, y1, x2, y2 = bounds(n)
            p = parent.get(n)
            while p is not None:
                if p.get("scrollable") == "true" or p is root:
                    a1, b1, a2, b2 = bounds(p)
                    if y1 <= b1 or y2 >= b2 or x1 <= a1 or x2 >= a2:
                        return True
                p = parent.get(p)
            return False
        def said(n):
            # What identifies it in the report: its id, its own words or the first words inside it.
            for d in n.iter("node"):
                t = (d.get("text") or d.get("content-desc") or "").strip()
                if t:
                    return t[:40]
            return ""
        found = []
        dp = 160.0 / self.density()
        for n in nodes:
            if n.get("package") != PKG or "true" not in (n.get("clickable"), n.get("long-clickable"), n.get("checkable")):
                continue
            x1, y1, x2, y2 = bounds(n)
            if x2 <= x1 or y2 <= y1:
                continue
            what = n.get("resource-id", "").split("/")[-1] or said(n) or n.get("class", "").split(".")[-1]
            cut = clipped(n)
            w, h = (x2 - x1) * dp, (y2 - y1) * dp
            if not named(n) and not cut:
                found.append(f"{name}: a control without a name for TalkBack ({what}, {w:.0f}×{h:.0f} dp at {n.get('bounds')})")
            if (w < 47.5 or h < 47.5) and not cut:
                found.append(f"{name}: «{what}» is {w:.0f}×{h:.0f} dp — smaller than 48 dp to touch ({n.get('bounds')})")
        if not hasattr(self, "a11y"):
            self.a11y = []
        self.a11y += found
        if found:  # the screen's whole tree, to see where each finding sits
            with open(os.path.join(self.out, f"a11y-{self.n:02d}-{name}.xml"), "w") as f:
                f.write(ET.tostring(root, encoding="unicode"))
        with open(os.path.join(self.out, "a11y.txt"), "a") as f:
            f.write(f"## {name}: {sum(1 for n in nodes if n.get('package') == PKG and n.get('clickable') == 'true')} controls, "
                    f"{len(found)} findings\n" + "".join(f"- {x}\n" for x in found))

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

    def animations(self, on):
        v = "1" if on else "0"
        sh(f"settings put global window_animation_scale {v}; settings put global transition_animation_scale {v}; settings put global animator_duration_scale {v}")

    def record(self, name, seconds):
        """Starts a screen recording (best effort: not every emulator image has an encoder); returns a handle for save()."""
        try:
            proc = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", str(seconds), "--size", "720x1600",
                                     "--bit-rate", "4000000", f"/sdcard/{name}.mp4"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            return None
        time.sleep(1)
        return (name, proc, seconds)

    def save(self, rec):
        if rec is None:
            return
        name, proc, seconds = rec
        try:
            proc.wait(timeout=seconds + 15)
        except subprocess.TimeoutExpired:
            proc.kill()
        time.sleep(1)
        adb("pull", f"/sdcard/{name}.mp4", os.path.join(self.out, f"{name}.mp4"), timeout=120)
        sh(f"rm /sdcard/{name}.mp4")

    def restart(self):
        sh(f"am force-stop {PKG}")
        time.sleep(1)
        sh(f"am start -W -n {MAIN}", timeout=120)
        time.sleep(4)

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
    # Into the app's internal folder through run-as (debug build): works on every Android version, unlike the
    # shared-storage folder, which Android 11 hides from files that adb put there.
    adb("push", path, "/data/local/tmp/iskra-model.gguf", timeout=900)
    sh(f"am force-stop {PKG}")
    sh(f"cat /data/local/tmp/iskra-model.gguf | run-as {PKG} sh -c 'mkdir -p files/llm && cat > files/llm/custom.gguf'", timeout=600)
    size_in_app = sh(f"run-as {PKG} sh -c 'wc -c < files/llm/custom.gguf'").strip()
    print(f"model in the app's folder: {size_in_app} bytes", flush=True)
    if size_in_app != str(got):
        annotate("error", f"On-phone model, API {api}", f"copy into the app failed: {size_in_app!r} of {got} bytes")
        return
    sh("rm /data/local/tmp/iskra-model.gguf")
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
    # x86 emulators have no AVX2 for llama.cpp: the first reply (reading the whole prompt on 2 threads) can take minutes.
    while time.time() - t < 360:
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
        annotate("error", f"On-phone model, API {api}", f"no reply in 6 minutes; {loaded or 'the model was not loaded'}")
        return

    # «Check the speed»: the app measures this device itself and picks threads for reading and for writing.
    if not s.tap_id("tab_2", quiet=True):
        return
    for _ in range(10):
        if s.find("local_brain_speed") is not None:
            break
        sh("input swipe 540 1700 540 700 400")
        time.sleep(1.5)
    if not s.tap_id("local_brain_speed", wait=5):
        return
    t = time.time()
    tuned = checked = None
    while time.time() - t < 420:
        journal = sh(f"run-as {PKG} cat files/diag.log", timeout=60)
        m = re.search(r"(?:Подбор потоков|Thread tuning) [^\n]+", journal)
        tuned = m.group(0) if m else tuned
        m = re.search(r"(?:Проверка скорости|Speed check) [^\n]+", journal)
        checked = m.group(0) if m else checked
        if checked:
            break
        time.sleep(5)
    s.shot("local-brain-speed")
    if checked:
        annotate("notice", f"On-phone model speed, API {api}", f"{tuned or 'no tuning line'}\n{checked}")
    else:
        annotate("warning", f"On-phone model speed, API {api}", f"no speed check in 7 minutes; {tuned or 'no tuning line'}")


# Models to compare in the evaluation: the catalog's and candidates for it (a missing repo or file is skipped).
EVAL_CANDIDATES = [
    # (name, repo, file patterns in order of preference, chat format if the file's own template can't tell)
    # Gemma 4 for phones (2026): about 2B / 4B of computation per token plus per-layer embeddings that are read from the
    # file only as needed. Q4_0 from Google's quantization-aware training. In 0.12.1 a 5 GB file of E2B "thought aloud"
    # instead of answering; the thought channel is closed now. The current catalog (Gemma 3 QAT) was measured on the same
    # code in the previous run; since 0.12.8 E2B and E4B are in the catalog.
    ("gemma-4-e2b", "ggml-org/gemma-4-E2B-it-GGUF", [r"(?i)^gemma-4-E2B-it-Q4_0\.gguf$"], "GEMMA4"),
    ("gemma-4-e4b", "ggml-org/gemma-4-E4B-it-GGUF", [r"(?i)^gemma-4-E4B-it-Q4_0\.gguf$"], "GEMMA4"),
    # Quantization-aware trained by Google for 4-bit (Q4_0): usually closer to the full model at the same size.
    ("gemma-3-1b-qat", "ggml-org/gemma-3-1b-it-qat-GGUF", [r"(?i)q4_0\.gguf$"], "GEMMA"),
    # An own file of the Llama 3 family (0.13.0 writes the dialogue its way, not as ChatML): the format on real weights.
    ("llama-3.2-1b", "bartowski/Llama-3.2-1B-Instruct-GGUF", [r"(?i)^Llama-3\.2-1B-Instruct-Q4_K_M\.gguf$"], "LLAMA3"),
    # The smart model before 0.12.8, for a comparison on the same code (last: the first one skipped when time runs out).
    ("gemma-3-4b-qat", "ggml-org/gemma-3-4b-it-qat-GGUF", [r"(?i)q4_0\.gguf$"], "GEMMA"),
]


def gguf_template(path):
    """The chat template stored in a GGUF file (stdlib only), or None."""
    import struct
    sizes = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}
    with open(path, "rb") as f:
        if f.read(4) != b"GGUF":
            return None
        f.read(4)
        _, n_kv = struct.unpack("<QQ", f.read(16))

        def rstr():
            (n,) = struct.unpack("<Q", f.read(8))
            return f.read(n).decode("utf-8", "replace")

        def skip(t):
            if t in sizes:
                f.seek(sizes[t], 1)
            elif t == 8:
                (n,) = struct.unpack("<Q", f.read(8))
                f.seek(n, 1)
            elif t == 9:
                it, n = struct.unpack("<IQ", f.read(12))
                if it in sizes:
                    f.seek(sizes[it] * n, 1)
                else:
                    for _ in range(n):
                        skip(it)
        for _ in range(n_kv):
            key = rstr()
            (t,) = struct.unpack("<I", f.read(4))
            if key == "tokenizer.chat_template" and t == 8:
                return rstr()
            skip(t)
    return None


def chat_format(template, guess):
    if not template:
        return guess
    if "<|turn>" in template:
        return "GEMMA4"
    if "<start_of_turn>" in template:
        return "GEMMA"
    if "<|im_start|>" in template:
        return "CHATML_NO_THINK" if ("enable_thinking" in template or "<think>" in template) else "CHATML"
    return guess
SMALL_FAMILIES = re.compile(r"(?i)(gemma-?[34]|qwen3|qwen-?3\.5|qwen4|lfm2|phi-?4-?mini|smollm|ministral|llama-?3\.2|granite)")


def hf_json(url):
    import json
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": "Iskra-CI"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def model_eval(out, threads=4, arch="", minutes=24):
    """
    The on-phone models judged on a real conversation: the app's own pipeline (agent, compact prompt, dialogue cache,
    JNI, llama.cpp) on this computer, for every candidate model. Transcripts and timings go to out/eval.
    [arch] (e.g. armv8.2-a+dotprod+fp16 on an ARM computer) builds the bridge exactly like the phone library instead of
    for this computer's own instruction set; with [threads] = 2, like the two fast cores of a mid-range phone.
    """
    import hashlib
    import platform
    import tarfile
    import urllib.request

    t_start = time.time()
    ev = os.path.abspath(os.path.join(out, "eval"))   # absolute: Gradle runs the test from core/
    os.makedirs(ev, exist_ok=True)
    # 1. What small GGUF models exist now (for choosing the catalog; just a list).
    found = []
    for author in ("ggml-org", "unsloth", "Qwen", "bartowski"):
        try:
            for m in hf_json(f"https://huggingface.co/api/models?author={author}&search=GGUF&sort=downloads&direction=-1&limit=300"):
                mid = m.get("id", "")
                if SMALL_FAMILIES.search(mid):
                    found.append(f"{m.get('downloads', 0):>10}  {m.get('lastModified', '')[:10]}  {mid}")
        except Exception as e:  # noqa: BLE001
            found.append(f"({author}: {e})")
    with open(os.path.join(ev, "hf-small-models.txt"), "w") as f:
        f.write("\n".join(found))
    print("\n".join(found[:80]), flush=True)

    # 2. The bridge for this computer, from the same pinned llama.cpp sources as the app.
    gradle = open("app/build.gradle.kts", encoding="utf-8").read()
    version = re.search(r'val llamaVersion = "([^"]+)"', gradle).group(1)
    url = re.search(r'val llamaArchiveUrl = "([^"]+)"', gradle).group(1).replace("$llamaVersion", version)
    sha = re.search(r'val llamaArchiveSha256 = "([0-9a-f]{64})"', gradle).group(1)
    work = "/tmp/iskra-eval"
    os.makedirs(work, exist_ok=True)
    archive = os.path.join(work, "llama.tar.gz")
    urllib.request.urlretrieve(url, archive)
    if hashlib.sha256(open(archive, "rb").read()).hexdigest() != sha:
        annotate("error", "Model evaluation", "llama.cpp sources: SHA-256 mismatch")
        return
    src = os.path.join(work, "llama.cpp")
    skip = {"models", "docs", "media", "examples", "tools", "tests", "gguf-py", "scripts", "pocs", "benches", "app", "skills",
            "ci", "requirements", "grammars", ".github", ".devops"}
    with tarfile.open(archive) as t:
        for m in t:
            i = m.name.find("/vendor/llama.cpp/")
            rel = m.name[i + len("/vendor/llama.cpp/"):] if i >= 0 else ""
            if not rel or not m.isfile() or rel.split("/")[0] in skip:
                continue
            parts = rel.split("/")
            if len(parts) >= 3 and parts[0] == "ggml" and parts[1] == "src" and parts[2].startswith("ggml-") and parts[2] not in ("ggml-cpu",) \
                    and os.path.splitext(parts[2])[1] == "":
                continue
            dest = os.path.join(src, rel)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            with t.extractfile(m) as fi, open(dest, "wb") as fo:
                fo.write(fi.read())
    build = os.path.join(work, "build")
    flags = ["-DISKRA_HOST_NATIVE=OFF", f"-DGGML_CPU_ARM_ARCH={arch}"] if arch else ["-DISKRA_HOST_NATIVE=ON"]
    for cmd in (["cmake", "-S", "app/src/main/cpp", "-B", build, "-DCMAKE_BUILD_TYPE=Release", f"-DLLAMA_SRC={src}", *flags],
                ["cmake", "--build", build, "--target", "iskra_llama", "-j", str(os.cpu_count() or 4)]):
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=1200)
        if r.returncode != 0:
            annotate("error", "Model evaluation", f"{' '.join(cmd[:3])}: {r.stdout[-1500:]} {r.stderr[-1500:]}")
            return
    lib = os.path.join(build, "libiskra_llama.so")
    print(f"bridge built in {time.time() - t_start:.0f} s", flush=True)

    # 3. Every candidate: download, talk, delete (disk space).
    env = dict(os.environ, ISKRA_CORE_ONLY="1", ISKRA_LLAMA_LIB=lib, ISKRA_EVAL_OUT=ev, ISKRA_EVAL_THREADS=str(threads))
    for k in ("JAVA_HOME_17_X64", "JAVA_HOME_21_X64"):
        if os.environ.get(k):
            env["JAVA_HOME"] = os.environ[k]
            break
    summary = []
    done = set()
    for name, repo, patterns, fmt in EVAL_CANDIDATES:
        if name in done:
            continue
        if time.time() - t_start > minutes * 60:
            summary.append(f"| {name} | — | skipped: out of time | | | |")
            continue
        try:
            files = [s_["rfilename"] for s_ in hf_json(f"https://huggingface.co/api/models/{repo}").get("siblings", [])]
        except Exception as e:  # noqa: BLE001
            print(f"{repo}: {e}", flush=True)
            continue
        file = next((f for p_ in patterns for f in files if re.search(p_, f) and "mmproj" not in f), None)
        if not file:
            print(f"{repo}: no file like {patterns}; files: {files[:12]}", flush=True)
            continue
        path = os.path.join(work, "model.gguf")
        t = time.time()
        try:
            urllib.request.urlretrieve(f"https://huggingface.co/{repo}/resolve/main/{file}", path)
        except Exception as e:  # noqa: BLE001
            print(f"{repo}/{file}: {e}", flush=True)
            continue
        tmpl = gguf_template(path)
        fmt = chat_format(tmpl, fmt)
        gen = ""
        if tmpl and "add_generation_prompt" in tmpl:
            i = tmpl.rfind("add_generation_prompt")
            gen = tmpl[i:i + 300].replace("\n", "⏎")
        print(f"{name}: {repo}/{file}, {os.path.getsize(path) // 1_000_000} MB in {time.time() - t:.0f} s, format {fmt}; template end: {gen}", flush=True)
        env["ISKRA_EVAL_MODELS"] = f"{name}|{path}|{fmt}"
        for attempt in range(2):  # a 5xx while Gradle downloads itself is not the model's fault: once more
            r = subprocess.run(["./gradlew", "-q", ":core:test", "--tests", "ru.iskra.core.LocalModelEval", "--rerun"],
                               env=env, capture_output=True, text=True, timeout=1800)
            if r.returncode == 0 or not re.search(r"HTTP response code: 5\d\d|Could not install Gradle", r.stdout + r.stderr):
                break
            time.sleep(30)
        if r.returncode != 0:
            annotate("warning", f"Model evaluation {name}", (r.stdout + r.stderr)[-2000:])
        sp = os.path.join(ev, "summary.md")
        if os.path.exists(sp):
            mb = os.path.getsize(path) // 1_000_000
            summary += [l.replace(f"| {name} |", f"| {name} ({mb} MB) |", 1) for l in open(sp).read().splitlines() if l.startswith(f"| {name} ")]
            os.remove(sp)
        done.add(name)
        os.remove(path)
    table = ("| model | lang | first words, s (median) | first words, s (slowest) | reply, s (median) | pieces/s (median) "
             "| new prompt tokens (median) | prompt tokens (median) | chars/token (median) |\n"
             "|---|---|---|---|---|---|---|---|---|\n" + "\n".join(summary))
    with open(os.path.join(ev, "summary.md"), "w") as f:
        f.write(table + "\n")
    print(table, flush=True)
    where = f"{platform.machine()}, {threads} threads" + (f", built like the phone ({arch})" if arch else "")
    annotate("notice", f"On-phone model evaluation ({where})", table)


ARM_PLAN = ".github/scripts/arm-plan.txt"


def arm_probe(out):
    """What the ARM computer offers: hardware virtualization (an Android emulator for the phone's own ABI), CPU, memory,
    and whether the Android SDK has an emulator and an arm64 system image for this host."""
    import platform
    import shutil
    import urllib.request
    import zipfile
    lines = [f"machine: {platform.machine()}, cpus: {os.cpu_count()}"]
    kvm = "/dev/kvm"
    lines.append(f"/dev/kvm exists: {os.path.exists(kvm)}, readable+writable: {os.access(kvm, os.R_OK | os.W_OK) if os.path.exists(kvm) else False}")
    for cmd in (["lscpu"], ["free", "-g"], ["df", "-h", "/"], ["uname", "-a"]):
        r = subprocess.run(cmd, capture_output=True, text=True)
        lines.append("$ " + " ".join(cmd) + "\n" + r.stdout.strip()[:1500])
    lines.append(f"ANDROID_HOME={os.environ.get('ANDROID_HOME')} ANDROID_SDK_ROOT={os.environ.get('ANDROID_SDK_ROOT')}")
    sdk = os.path.abspath(os.path.join(out, "sdk"))
    try:
        os.makedirs(sdk, exist_ok=True)
        z = os.path.join(sdk, "tools.zip")
        urllib.request.urlretrieve("https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip", z)
        zipfile.ZipFile(z).extractall(os.path.join(sdk, "cmdline-tools"))
        os.rename(os.path.join(sdk, "cmdline-tools", "cmdline-tools"), os.path.join(sdk, "cmdline-tools", "latest"))
        sm = os.path.join(sdk, "cmdline-tools", "latest", "bin", "sdkmanager")
        os.chmod(sm, 0o755)
        r = subprocess.run([sm, f"--sdk_root={sdk}", "--list"], capture_output=True, text=True, timeout=300, input="y\n" * 20)
        wanted = [l.strip() for l in r.stdout.splitlines() if re.search(r"^\s*(emulator|system-images;android-3[3-6];(google_apis|default|aosp_atd|google_atd);arm64-v8a)\s", l)]
        lines.append("sdkmanager packages: " + ("; ".join(wanted[:12]) or "(none) " + r.stderr[-400:]))
        r = subprocess.run([sm, f"--sdk_root={sdk}", "emulator"], capture_output=True, text=True, timeout=600, input="y\n" * 20)
        emu = os.path.join(sdk, "emulator", "emulator")
        lines.append(f"emulator installed: {os.path.exists(emu)} " + (r.stdout[-300:] + r.stderr[-300:]).replace("\n", " ")[-400:])
        if os.path.exists(emu):
            r = subprocess.run([emu, "-version"], capture_output=True, text=True, timeout=60)
            lines.append("emulator -version: " + (r.stdout + r.stderr).strip()[:300])
            r = subprocess.run([emu, "-accel-check"], capture_output=True, text=True, timeout=60)
            lines.append("emulator -accel-check: " + (r.stdout + r.stderr).strip()[:400])
    except Exception as e:  # noqa: BLE001
        lines.append(f"SDK probe failed: {type(e).__name__}: {e}")
    finally:
        shutil.rmtree(sdk, ignore_errors=True)
    text = "\n".join(lines)
    print(text, flush=True)
    annotate("notice", "ARM runner probe", text[:3800])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk")
    ap.add_argument("--out", required=True)
    ap.add_argument("--api", type=int, default=0)
    ap.add_argument("--lang", default="")
    # Only the model evaluation, without an emulator (the ARM job): threads and the phone's instruction set.
    ap.add_argument("--eval-only", action="store_true")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--arch", default="")
    a = ap.parse_args()
    if a.eval_only:  # its own job (50 minutes): what to do is listed in arm-plan.txt, so it changes without the workflow
        plan = open(ARM_PLAN).read().split() if os.path.exists(ARM_PLAN) else ["eval"]
        if "probe" in plan:
            arm_probe(a.out)
        if "eval" in plan:
            model_eval(a.out, a.threads, a.arch, minutes=38)
        return
    if not a.apk or not a.api:
        ap.error("--apk and --api are needed for the emulator check")
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

    # 0.16.0: her birth on the first page of the introduction, recorded with animations on (Android 13+ emulators).
    # Then the app starts again with animations off: the rest of the walk sees still pictures.
    video = a.api >= 33
    if video:
        s.animations(True)
    t0 = time.time()
    sh(f"am start -W -n {MAIN}", timeout=120)
    print(f"first start: {time.time() - t0:.1f} s", flush=True)
    if video:
        # The first start after install is slow on the emulator (the birth was over before the window showed): it warms
        # the app up, and the second start — the introduction again, as it was never finished — is recorded.
        time.sleep(6)
        sh(f"am force-stop {PKG}")
        time.sleep(1)
        rec = s.record("birth", 9)
        sh(f"am start -n {MAIN}", timeout=120)
        time.sleep(6)
        s.shot("birth-end")
        s.save(rec)
        s.animations(False)
        s.restart()
    else:
        time.sleep(4)
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
    if video:
        # The welcome (she waves) and a message flying in, her thinking and her reply — with animations on.
        s.animations(True)
        s.restart()
        rec = s.record("chat", 10)
        time.sleep(2)
        tapped = s.tap_id("chat_surprise", quiet=True)
        time.sleep(5)
        s.save(rec)
        # What being alive costs when nothing happens: the chat with her living portrait, then her on the home screen.
        time.sleep(3)
        s.idle_cost("chat, idle")
        sh("input keyevent KEYCODE_HOME")
        time.sleep(5)
        s.idle_cost("home screen with her, idle")
        s.animations(False)
        s.restart()
        if tapped:
            s.shot("chat-surprise")
    elif s.tap_id("chat_surprise", quiet=True):
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

    # The models judged on a real conversation (one job is enough; it doesn't need the emulator).
    if a.lang == "ru":
        try:
            model_eval(a.out)
        except Exception as e:  # noqa: BLE001 — the evaluation never fails the check
            annotate("warning", "Model evaluation", f"{type(e).__name__}: {e}")

    crashes = s.crashes()
    alive = s.alive()
    s.save_logs()
    for c in crashes:
        annotate("error", f"Iskra crashed on Android API {a.api}", c[:6000])
    if not alive and not crashes:
        annotate("error", f"Iskra is not running on API {a.api}", "The process is gone without a crash report (ANR or killed).")
    for p in s.problems:
        annotate("warning", f"Emulator check, API {a.api}", p)
    if getattr(s, "idle", []):
        annotate("notice", f"Idle cost, API {a.api}{' ' + a.lang if a.lang else ''} (software drawing on the emulator)", "\n".join(s.idle))
    a11y = getattr(s, "a11y", [])
    if a11y:
        annotate("warning", f"Accessibility, API {a.api}{' ' + a.lang if a.lang else ''}: {len(a11y)} findings",
                 "\n".join(sorted(set(a11y)))[:6000])
    else:
        annotate("notice", f"Accessibility, API {a.api}", "every control of the app on the screens above has a name and is 48 dp or more")
    print(f"API {a.api}: {s.n} screenshots, crashes: {len(crashes)}, alive: {alive}", flush=True)
    annotate("notice", f"Emulator check, API {a.api}", f"{s.n} screenshots, crashes: {len(crashes)}, process alive: {alive}")
    sys.exit(1 if crashes or not alive else 0)


if __name__ == "__main__":
    main()
