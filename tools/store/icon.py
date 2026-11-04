#!/usr/bin/env python3
"""Launcher icon foreground and the 512 px store icon from the head of iskra_stand.webp (needs Pillow)."""
from PIL import Image, ImageDraw, ImageFilter

src = Image.open("app/src/main/res/drawable-nodpi/iskra_stand.webp").convert("RGBA")
head = src.crop((0, 0, src.width, int(src.height * 0.555)))
# Soft edge at the shoulders instead of a hard cut.
a = head.getchannel("A")
fade = Image.new("L", head.size, 255)
d = ImageDraw.Draw(fade)
for i in range(24):
    y = head.height - 24 + i
    d.line([(0, y), (head.width, y)], fill=int(255 * (1 - i / 24)))
head.putalpha(Image.composite(a, Image.new("L", head.size, 0), fade))
head = head.crop(head.getchannel("A").point(lambda v: 255 if v > 16 else 0).getbbox())


def fit(img, box):
    s = min(box / img.width, box / img.height)
    return img.resize((max(1, round(img.width * s)), max(1, round(img.height * s))), Image.LANCZOS)


# Adaptive icon foreground: 108 dp = 432 px, the portrait inside the 66 dp safe zone.
fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
art = fit(head, 296)
fg.alpha_composite(art, ((432 - art.width) // 2, (432 - art.height) // 2 + 8))
fg.save("app/src/main/res/drawable-nodpi/ic_launcher_art.webp", "WEBP", lossless=True, quality=100)

# Store icon: brand background with a soft glow, full bleed (stores round the corners themselves).
bg = Image.new("RGBA", (512, 512), (42, 31, 58, 255))
glow = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
ImageDraw.Draw(glow).ellipse((86, 86, 426, 426), fill=(122, 79, 176, 170))
bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(40)))
art = fit(head, 392)
bg.alpha_composite(art, ((512 - art.width) // 2, (512 - art.height) // 2 + 10))
for lang in ("en-US", "ru-RU"):
    bg.convert("RGB").save(f"fastlane/metadata/android/{lang}/images/icon.png")
print("ok")
