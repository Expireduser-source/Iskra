"""
Her birth (0.16.0, app/…/ui/Living.kt: Birth, BirthFlare, Aura) frame by frame, with the same formulas, in PIL — to tune it
without a phone. Needs Pillow and numpy; the figure is the neutral frame of RigTest's expressions sheet:

    ISKRA_CORE_ONLY=1 ./gradlew :core:test --tests '*RigTest*'
    python3 -c "from PIL import Image; Image.open('core/build/rig-preview/expressions.png').crop((0,0,330,330)).save('/tmp/rig.png')"
    python3 -I tools/preview/birth_preview.py /tmp/birth.png /tmp/rig.png

Keep it in step with Living.kt when the timing or the colours change.
"""
import math, random, sys
import numpy as np
from PIL import Image, ImageDraw

out = sys.argv[1]
rig = Image.open(sys.argv[2]).convert("RGBA")
# key out the sheet background (E6F0E6) of the RigTest render
a = np.array(rig)
bg = np.array([0xE6, 0xF0, 0xE6])
d = np.abs(a[:, :, :3].astype(int) - bg).sum(axis=2)
a[:, :, 3] = np.clip((d - 6) * 12, 0, 255).astype(np.uint8)
rig = Image.fromarray(a)

DP = 2.0
HERO, FIGURE = 250, 206
W = int(HERO * DP)
GOLD, PINK, LILAC, WHITE = (0xFF, 0xB1, 0x3D), (0xFF, 0x6F, 0xA5), (0x9B, 0x6B, 0xFF), (255, 255, 255)
AURA = (0xB9, 0x8C, 0xFF)
heartY = 0.98 + (850 - 1265) * 0.9 / (1265 - 28)
heart = (0.5 * W, ((HERO - FIGURE) / 2 + heartY * FIGURE) / HERO * W)

def ease_out(x): x = min(max(x, 0), 1); return 1 - (1 - x) ** 3
def back_out(x):
    c1 = 1.70158; c3 = c1 + 1
    return 1 + c3 * (x - 1) ** 3 + c1 * (x - 1) ** 2
def figure(p): return min(max((p - 0.42) / 0.2, 0), 1)
def figure_scale(p): return 0.8 + 0.2 * back_out(min(max((p - 0.42) / 0.36, 0), 1))
def words(p): return min(max((p - 0.62) / 0.25, 0), 1)

yy, xx = np.mgrid[0:W, 0:W].astype(np.float32)

def radial(img, c, r, stops):
    """stops: [(t, (r,g,b), alpha)] like Brush.radialGradient; composited src-over."""
    if r <= 0.5: return img
    dist = np.sqrt((xx - c[0]) ** 2 + (yy - c[1]) ** 2) / r
    ts = [s[0] for s in stops]
    layer = np.zeros((W, W, 4), np.float32)
    for ch in range(3):
        layer[:, :, ch] = np.interp(dist, ts, [s[1][ch] for s in stops])
    layer[:, :, 3] = np.interp(dist, ts, [s[2] for s in stops]) * (dist <= 1)
    return over(img, layer)

def over(img, layer):
    base = np.array(img).astype(np.float32)
    la = layer[:, :, 3:4]
    base[:, :, :3] = base[:, :, :3] * (1 - la) + layer[:, :, :3] * la
    base[:, :, 3:4] = base[:, :, 3:4] + la * (255 - base[:, :, 3:4])
    return Image.fromarray(np.clip(base, 0, 255).astype(np.uint8))

def spark_poly(cx, cy, r, deg):
    pts = []
    seg = [((0, -1), (0.09, -0.42), (0.42, -0.09), (1, 0)), ((1, 0), (0.42, 0.09), (0.09, 0.42), (0, 1)),
           ((0, 1), (-0.09, 0.42), (-0.42, 0.09), (-1, 0)), ((-1, 0), (-0.42, -0.09), (-0.09, -0.42), (0, -1))]
    a = math.radians(deg)
    for p0, p1, p2, p3 in seg:
        for k in range(12):
            t = k / 12
            x = (1-t)**3*p0[0] + 3*(1-t)**2*t*p1[0] + 3*(1-t)*t**2*p2[0] + t**3*p3[0]
            y = (1-t)**3*p0[1] + 3*(1-t)**2*t*p1[1] + 3*(1-t)*t**2*p2[1] + t**3*p3[1]
            pts.append((cx + r * (x * math.cos(a) - y * math.sin(a)), cy + r * (x * math.sin(a) + y * math.cos(a))))
    return pts

def draw_spark(img, cx, cy, r, deg, color, alpha):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).polygon(spark_poly(cx, cy, r, deg), fill=color + (int(255 * alpha),))
    return Image.alpha_composite(img, layer)

rnd = random.Random(11)
embers = [((i * 2 * math.pi / 32 + rnd.uniform(-0.1, 0.1)), 0.28 + rnd.random() * 0.36, 2.6 + rnd.random() * 2.8,
           (1 if rnd.random() < 0.5 else -1) * (0.5 + rnd.random()), i) for i in range(32)]
sparks_c = [GOLD, PINK, LILAC, WHITE]

def frame(p):
    img = Image.new("RGBA", (W, W), (0xFB, 0xF8, 0xFF, 255))
    # aura (alpha = figure(p)), breath 0.5
    fa = figure(p)
    if fa > 0:
        r = W / 2 * 0.95; aa = 0.25 * fa
        img = radial(img, (W / 2, W / 2), r, [(0, AURA, aa), (0.55, AURA, aa * 0.4), (1, AURA, 0)])
    # figure
    if fa > 0:
        s = figure_scale(p)
        fw = int(FIGURE * DP * s)
        fig = rig.resize((fw, fw), Image.LANCZOS)
        fa_arr = np.array(fig); fa_arr[:, :, 3] = (fa_arr[:, :, 3] * fa).astype(np.uint8); fig = Image.fromarray(fa_arr)
        # transform origin (0.5, 0.9) of the figure box
        box = FIGURE * DP; left0 = (W - box) / 2; top0 = (W - box) / 2
        ox, oy = left0 + box * 0.5, top0 + box * 0.9
        left = ox - fw * 0.5; top = oy - fw * 0.9
        img.alpha_composite(fig, (int(left), int(top)))
    unit = W
    c = heart
    base = 7 * DP
    if p < 0.2: core, ca = base * ease_out(p / 0.2), 1
    elif p < 0.38: core, ca = base * (1 + 0.3 * max(0, math.sin((p - 0.2) / 0.18 * 4 * math.pi))), 1
    elif p < 0.5:
        k = (p - 0.38) / 0.12; core, ca = base * (1 + 2.6 * ease_out(k)), 1 - k
    else: core, ca = 0, 0
    if ca > 0 and core > 0:
        glow = core * 6
        img = radial(img, c, glow, [(0, WHITE, ca), (0.18, GOLD, 0.85 * ca), (0.5, PINK, 0.35 * ca), (1, PINK, 0)])
        img = draw_spark(img, c[0], c[1], core * 1.9, p * 90, WHITE, ca)
    if 0.38 <= p <= 0.64:
        k = (p - 0.38) / (0.64 - 0.38)
        r = max(unit * 0.62 * ease_out(k * 2), 1)
        img = radial(img, c, r, [(0, WHITE, 0.92 * (1 - k)), (0.35, PINK, 0.16 * (1 - k)), (1, PINK, 0)])
    if 0.38 <= p <= 0.78:
        k = (p - 0.38) / (0.78 - 0.38)
        r = unit * 0.55 * ease_out(k); w = (3 * (1 - k) + 0.6) * DP
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(layer).ellipse((c[0] - r, c[1] - r, c[0] + r, c[1] + r), outline=PINK + (int(255 * 0.7 * (1 - k)),), width=max(1, int(w)))
        img = Image.alpha_composite(img, layer)
    if 0.38 <= p <= 0.97:
        k = (p - 0.38) / (0.97 - 0.38); far = ease_out(k)
        for ang, dist, size, spin, col in embers:
            x = c[0] + math.cos(ang) * dist * unit * far
            y = c[1] + math.sin(ang) * dist * unit * far - k * unit * 0.06
            img = draw_spark(img, x, y, size * DP * (1 - 0.55 * k), k * 200 * spin, sparks_c[col % 4], (1 - k) ** 1.4)
    return img

ps = [0.08, 0.2, 0.3, 0.39, 0.43, 0.48, 0.55, 0.65, 0.8, 1.0]
tiles = [frame(p) for p in ps]
sheet = Image.new("RGBA", (W * 5 + 40, W * 2 + 10), (255, 255, 255, 255))
for i, t in enumerate(tiles):
    sheet.alpha_composite(t, ((i % 5) * (W + 10), (i // 5) * (W + 10)))
sheet.convert("RGB").resize((sheet.width // 2, sheet.height // 2), Image.LANCZOS).save(out)
print("ok", ps)
