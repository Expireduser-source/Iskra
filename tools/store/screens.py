#!/usr/bin/env python3
"""
Store screenshots from the CI emulator check.

    python3 -I tools/store/screens.py <unzipped smoke-api36-en> <unzipped smoke-api36-ru>

Picks the most telling screens, trims the status and navigation bars so the long side is at most twice the
short one (Google Play's rule), and writes fastlane/metadata/android/{en-US,ru-RU}/images/phoneScreenshots/N.png.
"""
import glob
import os
import sys

from PIL import Image

# In this order on the store page; the first that exists for each name is used.
WANTED = ["chat-surprise", "home-with-iskra", "share-sheet", "settings", "share-note-saved", "dark", "permissions", "memory"]


def trim(img: Image.Image) -> Image.Image:
    w, h = img.size
    if h <= 2 * w:
        return img
    cut = h - 2 * w                      # what must go
    top = min(cut // 2, int(h * 0.04))   # most of it from the bottom (gesture bar), a little from the status bar
    return img.crop((0, top, w, top + 2 * w))


def main(en_dir: str, ru_dir: str) -> None:
    for src, lang in ((en_dir, "en-US"), (ru_dir, "ru-RU")):
        out = f"fastlane/metadata/android/{lang}/images/phoneScreenshots"
        os.makedirs(out, exist_ok=True)
        for old in glob.glob(f"{out}/*.png"):
            os.remove(old)
        n = 0
        for name in WANTED:
            found = sorted(glob.glob(f"{src}/*-{name}.png"))
            if not found:
                continue
            n += 1
            trim(Image.open(found[0]).convert("RGB")).save(f"{out}/{n}.png", optimize=True)
        print(lang, n, "screenshots")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
