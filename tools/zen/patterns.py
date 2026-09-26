"""
Mock-ups of mathematically defined groove patterns for Neue's zen garden, drawn
the way the garden shader draws gravel (a numpy port of its lighting, at the look
kai chose: 32 px pitch, shallow), under the real deck.

A groove pattern is a field whose contours are the grooves — a groove wherever
the phase is a whole number and a half — so any family of curves given by one
equation can be tried here in a few lines, and all of them render in seconds
rather than a studio minute each.

The deck is lifted off two studio frames, one over a white garden and one over
a black one; where they differ is garden, and the difference is the alpha:

    tools/shoot.sh --neue --page=builder --zen=deep --immersive --name=matte --garden-mattes
    python3 tools/zen/patterns.py [key ...]     # writes shots/patterns/<key>.png
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import os, sys

SHOTS = '/home/user/kaihari-s-master-tool/shots'
ZEN = '/home/user/kaihari-s-master-tool/app/neue/src/jvmMain/resources/zen'
OUT = os.environ.get('OUT', os.path.join(SHOTS, 'patterns'))
os.makedirs(OUT, exist_ok=True)
W, H = 1920, 1080
S = 32.0          # the chosen pitch
PHI = (1 + 5 ** 0.5) / 2

# --- the deck, lifted off white and black --------------------------------------
white = np.asarray(Image.open(f'{SHOTS}/matte-white.png').convert('RGB')).astype(np.float32) / 255
black = np.asarray(Image.open(f'{SHOTS}/matte-black.png').convert('RGB')).astype(np.float32) / 255
alpha = np.clip(1 - (white - black).mean(axis=2), 0, 1)
colour = np.where(alpha[..., None] > 1e-3, black / np.maximum(alpha[..., None], 1e-3), 0)
ys, xs = np.nonzero(alpha > 0.5)
DL, DT, DR, DB = xs.min(), ys.min(), xs.max(), ys.max()
CX, CY = (DL + DR) / 2, (DT + DB) / 2
print('deck', DL, DT, DR, DB)

y, x = np.mgrid[0:H, 0:W].astype(np.float64)
x += 0.5; y += 0.5
dx, dy = x - CX, y - CY
r = np.hypot(dx, dy)
th = np.arctan2(dy, dx)

# --- the gravel: SandGarden's lighting, in numpy --------------------------------
def tex(name):
    im = Image.open(f'{ZEN}/{name}').convert('RGB')
    im = im.resize((im.width * 2, im.height * 2), Image.BILINEAR)      # uTexScale = 2
    a = np.asarray(im).astype(np.float32) / 255
    return np.tile(a, (H // a.shape[0] + 1, W // a.shape[1] + 1, 1))[:H, :W]
NORMAL = tex('sand_normal.png')
ALBEDO = tex('sand_albedo.png')[..., 0]

def shade(phase, relief=0.8, ink=False):
    phase = np.clip(np.nan_to_num(phase, nan=0.0, posinf=1e5, neginf=-1e5), -1e5, 1e5)
    # How far apart the grooves are here, in pixels: where they crowd below a few
    # pixels the gravel is smoothed rather than drawn as noise (a rake could not either).
    gy, gx = np.gradient(phase)
    # A groove is a groove whichever whole number it is: a phase that jumps by whole
    # lines (a spiral's angle wrapping round) is continuous in the gravel.
    gx = gx - np.round(gx)
    gy = gy - np.round(gy)
    spacing = 1 / np.maximum(np.hypot(gx, gy), 1e-6)
    amp = np.clip((spacing - 7) / 12, 0, 1)
    h = np.power(0.5 + 0.5 * np.cos(2 * np.pi * phase), 0.8)
    h = 0.5 + (h - 0.5) * amp
    hy, hx = np.gradient(h)
    R = relief * S / 10
    n = np.stack([-hx * 2 * R, -hy * 2 * R, np.ones_like(h)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    g = NORMAL * 2 - 1
    g[..., 1] = -g[..., 1]
    n = np.stack([n[..., 0] + g[..., 0] * 0.12, n[..., 1] + g[..., 1] * 0.12, n[..., 2]], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    L = np.array([-0.5, -0.66, 0.56]); L /= np.linalg.norm(L)
    diff = np.maximum(n @ L, 0) / L[2]
    a = 0.93 * 0.65 + ALBEDO * 0.35
    lum = a * (0.74 + 0.42 * 0.56 * diff) * (0.96 + 0.04 * h)
    lum = np.minimum(lum, 1)
    lum = np.nan_to_num(lum, nan=0.9)
    if ink:
        lum = lum ** 1.8 * 0.3
    return lum

def compose(lum):
    bg = np.repeat(lum[..., None], 3, -1).astype(np.float32)
    out = colour * alpha[..., None] + bg * (1 - alpha[..., None])
    return Image.fromarray((np.clip(out, 0, 1) * 255).astype(np.uint8))

# --- the patterns ---------------------------------------------------------------
P = {}

def pattern(key, title, maths):
    def wrap(f):
        P[key] = (title, maths, f)
        return f
    return wrap

@pattern('01-confocal-ellipses', 'Confocal ellipses', 'r₁ + r₂ = const — two foci inside the deck')
def _():
    f = 330
    r1 = np.hypot(dx + f, dy); r2 = np.hypot(dx - f, dy)
    return (r1 + r2) / (2 * S)

@pattern('02-lame-curves', 'Lamé curves (squircles)', '|x/a|⁴ + |y/b|⁴ = const — Piet Hein\'s superellipse')
def _():
    n = 4.0; k = 1.25
    rho = (np.abs(dx) ** n + np.abs(dy * k) ** n) ** (1 / n)
    return rho / S

@pattern('03-potential-flow', 'Potential flow past the deck', 'Im[U/2 (ζ + R²/ζ)], z = ½(ζ + c²/ζ) — ideal flow round an ellipse')
def _():
    pad = 60
    a = (DR - DL) / 2 + pad; b = (DB - DT) / 2 + pad
    if a < b: a, b = b, a
    c = np.sqrt(a * a - b * b)
    z = dx + 1j * dy
    zeta = z + np.sqrt(z - c) * np.sqrt(z + c)
    w = 0.5 * (zeta + (a + b) ** 2 / zeta)
    return w.imag / S

@pattern('04-apollonian-circles', 'Apollonian circles', 'r₁ / r₂ = const — bipolar coordinates, foci beside the deck')
def _():
    d = 700
    r1 = np.hypot(dx + d, dy); r2 = np.hypot(dx - d, dy)
    return np.log(r1 / r2) * d / (2 * S)

@pattern('05-dipole-lines', 'Dipole field lines', 'θ₁ − θ₂ = const — every circle through two points')
def _():
    d = 700
    t1 = np.arctan2(dy, dx + d); t2 = np.arctan2(dy, dx - d)
    v = np.mod(t1 - t2, 2 * np.pi)
    return v * d / (2 * S)

@pattern('06-archimedean-spiral', 'Archimedean spiral', 'r = S·θ/2π — one groove, constant pitch, from under the deck')
def _():
    return r / S - th / (2 * np.pi)

@pattern('07-fibonacci-spirals', 'Fibonacci spirals', '55 equiangular spirals — the parastichies of a sunflower head')
def _():
    arms = 55
    beta = np.sqrt(max((500 / S) ** 2 - (arms / (2 * np.pi)) ** 2, 1))
    return arms * th / (2 * np.pi) + beta * np.log(np.maximum(r, 1))

@pattern('08-guilloche-rose', 'Guilloché rose', 'r + A·sin(kθ + r/λ) — an engine-turned watch dial')
def _():
    k = 18; lam = 160
    A = 0.45 * S * (1 - np.exp(-r / 250))
    return (r + A * np.sin(k * th + r / lam)) / S

@pattern('09-chladni', 'Chladni figure', 'cos(nπx)cos(mπy) − cos(mπx)cos(nπy) — sand on a vibrating plate')
def _():
    u = x / W; v = y / H
    n, m = 5, 2
    f = np.cos(n * np.pi * u) * np.cos(m * np.pi * v) - np.cos(m * np.pi * u) * np.cos(n * np.pi * v)
    return f * 6.5

@pattern('10-golden-arcs', 'Golden-rectangle arcs', 'quarter circles in the squares of φ — the golden spiral\'s own grid')
def _():
    # A golden rectangle as tall as the window, centred; beyond it, straight lines.
    gw = H * PHI
    left = (W - gw) / 2
    ph = y / S                       # outside: straight lines
    # The squares, spiralling in: each one's arc centre is the corner the spiral turns about.
    rx, ry, rw, rh = left, 0.0, gw, float(H)
    out = np.full((H, W), np.nan)
    for i in range(12):
        s = min(rw, rh)
        k = i % 4
        if k == 0:   sq = (rx, ry, s); cen = (rx + s, ry + s); rx += s; rw -= s
        elif k == 1: sq = (rx + rw - s, ry, s); cen = (rx + rw - s, ry + s); ry += s; rh -= s
        elif k == 2: sq = (rx + rw - s, ry + rh - s, s); cen = (rx + rw - s, ry + rh - s); rw -= s
        else:        sq = (rx, ry + rh - s, s); cen = (rx + s, ry + rh - s); rh -= s
        qx, qy, qs = sq
        inside = (x >= qx) & (x < qx + qs) & (y >= qy) & (y < qy + qs) & np.isnan(out)
        out[inside] = np.hypot(x[inside] - cen[0], y[inside] - cen[1]) / S
    return np.where(np.isnan(out), ph, out)

@pattern('11-interference', 'Two-source interference', 'r₁ − r₂ = const — confocal hyperbolae, the nodal lines of two ripples')
def _():
    d = 420
    r1 = np.hypot(dx, dy + d); r2 = np.hypot(dx, dy - d)
    return (r1 - r2) / (2 * S)

if __name__ == '__main__':
    only = sys.argv[1:]
    for key, (title, maths, f) in P.items():
        if only and key not in only: continue
        compose(shade(f())).save(f'{OUT}/{key}.png')
        print('wrote', key)
