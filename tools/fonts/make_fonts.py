"""
The app's font: static, subset instances of Nunito (variable font, SIL Open Font License 1.1 — see OFL.txt here).

    curl -L -o Nunito.ttf "https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/Nunito%5Bwght%5D.ttf"
    python3 -m venv /tmp/fonts && /tmp/fonts/bin/pip install fonttools
    /tmp/fonts/bin/python tools/fonts/make_fonts.py Nunito.ttf app/src/main/res/font

Four weights (400, 500, 600, 700). Only Latin, Cyrillic and the signs replies use are kept; hints are dropped (Android
draws unhinted outlines smoothly). The names table — with the copyright and the license — stays in every file.
"""
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

src, out = sys.argv[1], sys.argv[2]
# Basic Latin, Latin-1 and Latin Extended-A (names), Cyrillic and its supplement, general punctuation, currency signs,
# letter-like symbols (№), arrows and math operators (×, −, ≈, ≤, ≥), the degree sign, the ellipsis and check marks.
unicodes = (list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + list(range(0x400, 0x530)) +
            list(range(0x2000, 0x2070)) + list(range(0x20A0, 0x20C1)) + list(range(0x2100, 0x2270)) +
            [0x2116, 0x00B0, 0x2026, 0x25CF, 0x2713, 0x2714])
for name, weight in [("regular", 400), ("medium", 500), ("semibold", 600), ("bold", 700)]:
    font = instancer.instantiateVariableFont(TTFont(src), {"wght": weight}, updateFontNames=True)
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.hinting = False
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=unicodes)
    sub.subset(font)
    path = f"{out}/nunito_{name}.ttf"
    font.save(path)
    print(path)
