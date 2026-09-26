"""
Prototypes of ways to take the Fibonacci garden further, for kai to choose from,
drawn with patterns.py's gravel under the real deck:

  A  endless bloom — a golden spiral turned is a golden spiral zoomed, so it flows out forever
  B  phyllotaxis seeding — seed n at r = c√n, θ = n·137.5°; the spirals emerge, never drawn
  C  travelling light — the sun circles the garden
  D  a finger in the sand — the pointer parts the grooves and leaves a wake
  E  A, C and D together

    python3 tools/zen/ideas.py A out/dir 10     # ten seconds of A, 24 frames a second
"""
import numpy as np, sys, os
from multiprocessing import Pool
import patterns as P
from PIL import Image

PHI = (1 + 5 ** 0.5) / 2
GA = 2 * np.pi * (1 - 1 / PHI)
B = np.pi / (2 * np.log(PHI))
ARC = np.sqrt(1 + B * B)
CX, CY = P.CX, P.CY
x, y = P.x, P.y
FPS = 24

def spiral_phase(px, py, F=34, hand=1, turn=0.0, zoom=0.0):
    dx, dy = px - CX, py - CY
    r = np.maximum(np.hypot(dx, dy), 1)
    th = np.arctan2(dy, dx)
    return F * (th - hand * B * (np.log(r) - zoom) - turn) / (2 * np.pi), 2 * np.pi * r / (F * ARC)

def light(h, az=np.deg2rad(233), elev_z=0.56, relief=0.45):
    hy, hx = np.gradient(h)
    R = relief * 32 / 10
    n = np.stack([-hx * 2 * R, -hy * 2 * R, np.ones_like(h)], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    g = P.NORMAL * 2 - 1; g[..., 1] = -g[..., 1]
    n = np.stack([n[..., 0] + g[..., 0] * 0.12, n[..., 1] + g[..., 1] * 0.12, n[..., 2]], -1)
    n /= np.linalg.norm(n, axis=-1, keepdims=True)
    horiz = np.hypot(0.5, 0.66)
    L = np.array([np.cos(az) * horiz, np.sin(az) * horiz, elev_z]); L /= np.linalg.norm(L)
    diff = np.maximum(n @ L, 0) / L[2]
    a = 0.93 * 0.65 + P.ALBEDO * 0.35
    lum = a * (0.74 + 0.42 * 0.56 * diff) * (0.96 + 0.04 * h)
    return np.nan_to_num(np.minimum(lum, 1), nan=0.9)

# --- B: the seeds of a sunflower -------------------------------------------------
C_SEED = 26.0
N_SEEDS = int((1200 / C_SEED) ** 2)
_seed = None
def seeds():
    global _seed
    if _seed is None:
        from scipy.spatial import cKDTree
        i = np.arange(N_SEEDS)
        sx = CX + C_SEED * np.sqrt(i) * np.cos(i * GA)
        sy = CY + C_SEED * np.sqrt(i) * np.sin(i * GA)
        d, idx = cKDTree(np.stack([sx, sy], 1)).query(np.stack([x.ravel(), y.ravel()], 1))
        _seed = (d.reshape(x.shape), idx.reshape(x.shape))
    return _seed
FIRST = int((250 / C_SEED) ** 2)
RATE = 150.0

def frame(args):
    kind, t, out = args
    if kind == 'A':
        ph, sp = spiral_phase(x, y, 34, 1, 0.0, zoom=0.045 * t)
        lum = light(P.height(ph, sp))
    elif kind == 'B':
        d, idx = seeds()
        base = P.height(*spiral_phase(x, y, 34, 1, 0.0))
        pressed = np.clip((t - (idx - FIRST) / RATE) / 0.5, 0, 1)
        pressed = pressed * pressed * (3 - 2 * pressed)
        # Each seed is a shallow round pit pressed into the gravel; its cell's edge is left as a ridge.
        pit = np.clip(d / (0.55 * C_SEED), 0, 1)
        pit = 0.5 - 0.5 * np.cos(np.pi * pit)
        lum = light(base * (1 - pressed) + pit * pressed, relief=0.55)
    elif kind == 'C':
        ph, sp = spiral_phase(x, y, 34, 1, 0.0)
        lum = light(P.height(ph, sp), az=np.deg2rad(233) + 2 * np.pi * t / 10.0)
    else:
        # D and E: a pointer drawn along a slow figure beside the deck; its last half-second leaves a wake.
        def pointer(s):
            return 1640 + 170 * np.sin(0.9 * s), 540 + 330 * np.sin(0.63 * s + 0.4)
        ux = np.zeros_like(x); uy = np.zeros_like(y)
        for k in range(8):
            s = t - k * 0.07
            px, py = pointer(s)
            dx, dy = x - px, y - py
            # A smooth bump: zero at the fingertip, pushing the gravel aside most a little way out.
            a = 70 * (1 - k / 8) ** 1.5 * np.exp(-(dx * dx + dy * dy) / 95 ** 2) / 95
            ux += a * dx; uy += a * dy
        zoom = 0.045 * t if kind == 'E' else 0.0
        ph, sp = spiral_phase(x - ux, y - uy, 34, 1, 0.0, zoom=zoom)
        az = np.deg2rad(233) + (2 * np.pi * t / 40.0 if kind == 'E' else 0)
        lum = light(P.height(ph, sp), az=az)
    P.compose(lum).resize((1280, 720), Image.LANCZOS).save(out)

if __name__ == '__main__':
    kind, out, seconds = sys.argv[1], sys.argv[2], float(sys.argv[3])
    os.makedirs(out, exist_ok=True)
    if kind == 'B': seeds()
    jobs = [(kind, i / FPS, f'{out}/{i:04d}.png') for i in range(int(seconds * FPS))]
    with Pool(4) as pool:
        pool.map(frame, jobs, chunksize=4)
    print('done', kind, len(jobs))
