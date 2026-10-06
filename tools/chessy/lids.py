"""Chessy's half-lids: her eyes part-way shut, built from kai's own pictures (round two of the rig red team).

    python3 tools/chessy/lids.py <pack dir> [mockup.html] [--preview DIR]

Her blink was a swap: the open eye, then kai's closed lid. Between them there was nothing, so she could not squint,
look sleepy or close her eyes over a few frames. The irises stay where kai painted them (kai's choice); what moves is
the lid. For each open eye (the Grin's sly eyes, painted in her face layer, and the Fangs' wide ones, laid over it)
and each side, this finds:

    top      the bottom edge of the open eye's upper lash, a line across the eye (sheet px, every DX columns)
    bottom   the bottom edge of kai's closed lid's lash, where the lash comes to rest when the eye is shut
    lash     the open eye's upper lash itself, its lashes and all, cut out with a soft edge (its ink, not its skin)
    skin     kai's closed lid with its ink painted out: the lid's skin, to cover the eye above the lash as it comes down
    pupil    the eye's pupil and the iris without it (bed), so a lid reaching the pupil pushes it down, not covers it

and writes them into moods.json as halfLids.<sly|wide>.<l|r>. The app draws an eye at opening o (1 open, 0 shut) as the
eye, then the skin down to a line between top and bottom, then the lash moved down onto that line; near shut it fades
into kai's own lid (ChessyLids, ChessyAvatar). --preview writes each eye at a few openings, drawn flat, to judge.
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

out = sys.argv[1]
mockup = sys.argv[2] if len(sys.argv) > 2 and not sys.argv[2].startswith('--') else None
preview = sys.argv[sys.argv.index('--preview') + 1] if '--preview' in sys.argv else None
pack = json.load(open(os.path.join(out, 'chessy.json')))
moods_path = os.path.join(out, 'moods.json')
moods = json.load(open(moods_path))
W, H = pack['w'], pack['h']
layers = {l['id']: l for l in pack['layers']}

SPLIT = 632            # between her eyes (parts.py's)
EYES = (840, 1100)     # the band her eyes and lids live in
DARK = 110             # luminance below this is ink: her lashes are dark plum, her skin and irises far lighter
MIN_RUN = 3            # an ink run shorter than this is a stray pixel, not the lash
SMOOTH = 17            # columns in the median that keeps the line to the lash and off an iris's rim
DX = 4                 # the lines are kept every DX sheet columns
INK_GROW = 3           # how far round the lid's ink is painted out before the skin is filled in
LASH_REACH = 48        # how far above its line the lash's lashes reach (sheet px): the skin covers that far up
OPEN_W = 11            # the horizontal opening that keeps the lash band and drops the iris rims and single lashes
OUTER_TAPER = .35      # the share of the eye's width over which the lid's travel grows from its outer corner
INNER_TAPER = .12      # and falls to its inner corner, where it keeps INNER_FLOOR of it
INNER_FLOOR = .5
PUPIL_W = (8, 45)      # a pupil's loop in kai's ink: its inside this wide (sheet px)
PUPIL_H = (15, 70)     # and this tall, taller than wide
PUPIL_DARK = 100       # the pupil's ink is darker than this; her irises are far lighter
PUPIL_RING = 5         # a pupil's ring of ink round its inside (sheet px)
PUPIL_MARGIN = 6 
FLOOR_LINE = 185       # the eye's lower line under the pupil is darker than this      # how far above the eye's lower line the pupil's bottom may come to rest
PUPIL_GAP = 3          # how far under the lash's lower edge a pushed pupil's top sits
EXTEND = 24            # how far past its ends a lid's lines are held (sheet px)
LASH_THICK = 16        # the most a painted lash is thick where the snap may use its edge (sheet px)
SNAP = 10              # how far the painted lash's edge may sit from kai's traced one (sheet px)
LASH_PAINT = 100       # the painted lash is darker than this
LASH_GAP = 40          # the most the lash's two outlines are apart (sheet px)
LASH_DARK = 120        # and the paint between them is darker than this: the lash's own fill
ZONE_FEATHER = 4       # the skin's edge, round the eye, softened over this many pixels


def load(p):
    return Image.open(os.path.join(out, p['file'])).convert('RGBA')


def comp(pics):
    c = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for p in pics:
        if p:
            c.alpha_composite(load(p), (p['x'], p['y']))
    return np.asarray(c).astype(np.float32)


def luma(rgba):
    return rgba[..., 0] * .299 + rgba[..., 1] * .587 + rgba[..., 2] * .114


def ink(rgba):
    """Where a picture is ink: dark and drawn."""
    return (luma(rgba) < DARK) & (rgba[..., 3] > 128)


def side_span(left):
    return (0, SPLIT) if left else (SPLIT, W)


def first_run_end(col, from_top=True):
    """In one column of an ink mask: from the top, where the first ink run ends; from the bottom, where the first begins."""
    ys = np.nonzero(col)[0]
    if ys.size == 0:
        return None
    if from_top:
        start = ys[0]
        y = start
        while y + 1 < col.size and (col[y + 1] or (y + 2 < col.size and col[y + 2])):
            y += 1
        return y if y - start + 1 >= MIN_RUN else None
    return ys[-1]


def line(mask, x0, x1, from_top):
    """A line across columns x0..x1: per column the lash's edge, smoothed by a median so an iris rim never pulls it."""
    ys = np.full(x1 - x0, np.nan)
    for i, x in enumerate(range(x0, x1)):
        v = first_run_end(mask[EYES[0]:EYES[1], x], from_top)
        if v is not None:
            ys[i] = v + EYES[0]
    ok = ~np.isnan(ys)
    if ok.sum() < 10:
        return None
    xs = np.nonzero(ok)[0]
    a, b = xs[0], xs[-1] + 1
    ys = ys[a:b]
    # fill gaps, then a running median
    idx = np.arange(ys.size)
    good = ~np.isnan(ys)
    ys = np.interp(idx, idx[good], ys[good])
    half = SMOOTH // 2
    padded = np.pad(ys, half, mode='edge')
    med = np.array([np.median(padded[i:i + SMOOTH]) for i in range(ys.size)])
    return x0 + a, med


def runs(col):
    """The runs of a column of a mask: (first, last) of each."""
    ys = np.nonzero(col)[0]
    if ys.size == 0:
        return []
    cuts = np.nonzero(np.diff(ys) > 1)[0]
    starts = np.concatenate([[ys[0]], ys[cuts + 1]])
    ends = np.concatenate([ys[cuts], [ys[-1]]])
    return list(zip(starts, ends))


def lash_line(lines, lum, x0, x1):
    """The open lash's lower edge from kai's ink: in each column, the first two strokes that enclose dark paint between
    them are the lash's top and bottom (her crease above encloses skin; her iris below has no stroke across)."""
    ys = np.full(x1 - x0, np.nan)
    for i, x in enumerate(range(x0, x1)):
        rs = runs(lines[EYES[0]:EYES[1], x])
        for (a0, a1), (b0, b1) in zip(rs, rs[1:]):
            gap = lum[EYES[0] + a1 + 1:EYES[0] + b0, x]
            if 2 <= b0 - a1 <= LASH_GAP and gap.size and np.median(gap) < LASH_DARK:
                ys[i] = EYES[0] + b1
                break
    ok = ~np.isnan(ys)
    if ok.sum() < 10:
        return None
    xs = np.nonzero(ok)[0]
    a, b = xs[0], xs[-1] + 1
    ys = ys[a:b]
    idx = np.arange(ys.size)
    good = ~np.isnan(ys)
    ys = np.interp(idx, idx[good], ys[good])
    half = SMOOTH // 2
    padded = np.pad(ys, half, mode='edge')
    return x0 + a, np.array([np.median(padded[i:i + SMOOTH]) for i in range(ys.size)])


def snap_to_paint(lum, x0, ys):
    """The lash's lower edge as painted: kai's traced ink can sit a few pixels off the paint, so from where the ink puts
    it, each column moves to the end of the painted lash's dark run (within SNAP pixels), then a light median."""
    out = ys.copy()
    for i, y in enumerate(ys):
        x = x0 + i
        y = int(round(y))
        col = lum[y - SNAP:y + SNAP + 1, x] < LASH_PAINT
        if not col.any():
            continue
        # the dark run nearest the ink's line, and where it ends
        idx = np.nonzero(col)[0]
        k = idx[np.argmin(np.abs(idx - SNAP))]
        start = k
        while start > 0 and col[start - 1]:
            start -= 1
        while k + 1 < col.size and col[k + 1]:
            k += 1
        # only a lash's run: one that runs on (into a pupil touching the lash) keeps the ink's line
        if k - start <= LASH_THICK:
            out[i] = y - SNAP + k
    half = 4
    padded = np.pad(out, half, mode='edge')
    return np.array([np.median(padded[i:i + 2 * half + 1]) for i in range(out.size)])


def save(rgba, name):
    a = rgba[..., 3]
    ys, xs = np.nonzero(a > 1)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    px = np.clip(rgba[y0:y1, x0:x1] + .5, 0, 255).astype(np.uint8)
    Image.fromarray(px, 'RGBA').save(os.path.join(out, name + '.webp'), lossless=True, method=6)
    return {'x': int(x0), 'y': int(y0), 'w': int(x1 - x0), 'h': int(y1 - y0), 'file': name + '.webp'}


face = layers['face']['pic']
eyes = moods['eyes']


def pupil_slide(opened, ink_map, lo, hi, kind, s, lash_line):
    """The pupil, so it can slide down under a lid that would cover it (kai: the pupil should never be lost in a blink):
    found in the paint as the dark ring under the lash, cut out with what it holds ([pic]); the iris under it
    with the pupil painted out ([bed], drawn over its old place while it is pushed); its top and bottom, and the floor
    its bottom may slide to (just above the eye's lower line under it)."""
    # found in the paint, which is what is drawn (kai's traced ink can sit a little off it): below the lash's line, a
    # dark ring of a pupil's size that encloses something (its highlight, its lighter middle), nearest the eye's middle
    y0, y1 = EYES
    rows = np.arange(H)[:, None]
    under = rows > np.nan_to_num(lash_line, nan=1e9)[None, :] + 2
    region = np.zeros((H, W), bool)
    region[y0:y1, lo:hi] = True
    dark = ((luma(opened) < PUPIL_DARK) & under & region).astype(np.uint8)
    n, lab, stats, cents = cv2.connectedComponentsWithStats(dark)
    best = None
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        if not (PUPIL_W[0] < w < PUPIL_W[1] + 20 and PUPIL_H[0] < h < PUPIL_H[1] + 20 and h > w * .8):
            continue
        comp = (lab[y:y + h, x:x + w] == i).astype(np.uint8)
        # a pupil touching the lash is a ring open at the top, the lash closing it: close it for the test
        wall = comp.copy()
        if y <= int(np.nanmin(lash_line[x:x + w])) + 6:
            wall[0, :] = 1
        inv = (1 - wall).astype(np.uint8)
        ff = np.zeros((h + 2, w + 2), np.uint8)
        for sx, sy in ((0, h - 1), (w - 1, h - 1), (0, h // 2), (w - 1, h // 2)):
            if inv[sy, sx]:
                cv2.floodFill(inv, ff, (sx, sy), 0)
        enclosed = int(inv.sum())
        if enclosed < area * .15:
            continue  # a stroke, not a ring
        # of the rings, the one nearest the middle of the eye (an iris's rim can enclose a little too)
        centre = (np.nanmin(np.where(np.isnan(lash_line[lo:hi]), np.inf, np.arange(lo, hi))) +
                  np.nanmax(np.where(np.isnan(lash_line[lo:hi]), -np.inf, np.arange(lo, hi)))) / 2
        off = abs(x + w / 2 - centre)
        if best is None or off < best[1]:
            best = (i, off, comp | inv)
    if best is None:
        print(f'{kind}-{s}: no pupil found; it stays under the lid')
        return None
    i, _, filled = best
    x, y, w, h, _ = stats[i]
    mask = np.zeros((H, W), np.uint8)
    mask[y:y + h, x:x + w] = filled
    mask = cv2.dilate(mask, np.ones((3, 3), np.uint8))
    hole = mask.copy()
    ys_, xs_ = np.nonzero(mask)
    x, y, w, h = int(xs_.min()), int(ys_.min()), int(xs_.max() - xs_.min() + 1), int(ys_.max() - ys_.min() + 1)
    cx = x + w / 2
    # the floor: the eye's lower line under the pupil's middle, less a margin
    # (the eye's lower line is dark on the Grin, a light grey on the Fangs: anything clearly darker than the iris)
    col = luma(opened)[y + h + 8:y1, int(cx)] < FLOOR_LINE
    below = np.nonzero(col)[0]
    floor = (y + h + 8 + below[0] - PUPIL_MARGIN) if below.size else y + h
    soft = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), .8)
    pupil = np.zeros_like(opened)
    pupil[..., :3] = opened[..., :3]
    pupil[..., 3] = np.clip(soft * 1.4, 0, 1) * opened[..., 3]
    # the bed: the iris where the pupil was, painted from the iris round it
    hole = cv2.dilate(mask, np.ones((5, 5), np.uint8))
    rgb = np.clip(opened[..., :3], 0, 255).astype(np.uint8)
    filled = cv2.inpaint(rgb, hole, 7, cv2.INPAINT_TELEA).astype(np.float32)
    bed = np.zeros_like(opened)
    bed[..., :3] = filled
    bed[..., 3] = np.clip(cv2.GaussianBlur(hole.astype(np.float32), (0, 0), 1.0) * 1.3, 0, 1) * 255
    print(f'{kind}-{s}: pupil x{x}-{x + w} y{y}-{y + h}, may slide {floor - (y + h):.0f} px')
    return {
        'pic': save(pupil, f'halflid-{kind}-{s}-pupil'),
        'bed': save(bed, f'halflid-{kind}-{s}-bed'),
        'cx': round(float(cx), 1), 'top': float(y), 'bottom': float(y + h), 'floor': float(max(floor, y + h)),
    }


def mockup_ink():
    """kai's ink from the mockup (each piece's red channel): her face layer's, and the Fangs face's features'. Without
    the mockup, the pictures' darkness stands in."""
    if not mockup:
        return None
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from build import mockup_data, image
    ld, _ = mockup_data(mockup)
    def sheet(p):
        a = np.zeros((H, W), np.float32)
        a[p['y']:p['y'] + p['h'], p['x']:p['x'] + p['w']] = image(p['png'], 'RGBA')[..., 0] / 255
        return a
    face_ink = sheet([l for l in ld['layers'] if l['id'] == 'face'][0]['img'])
    feats = {f['id']: sheet(f['features']) for f in ld['faces'] if f.get('features')}
    # the Grin's eyes are painted in the face layer, their lines in the Grin's features (ink only, no paint)
    return np.maximum(face_ink, feats['grin']), feats['fangs'], face_ink


INK = mockup_ink()
lids = moods['lids']
KINDS = {
    # the open eye drawn over the face layer, and the lid kai drew shut over it
    'sly': (lambda left: [face], 'grin'),
    'wide': (lambda left: [face, eyes['fangs']['l' if left else 'r']], 'fangs'),
}
half_lids = {}
for kind, (open_pics, lid_face) in KINDS.items():
    half_lids[kind] = {}
    for left in (True, False):
        s = 'l' if left else 'r'
        lid = lids[lid_face][s]
        opened = comp(open_pics(left))
        shut = comp(open_pics(left) + [lid])
        lo, hi = side_span(left)
        lx0, lx1 = max(lid['x'], lo), min(lid['x'] + lid['w'], hi)
        if INK is not None:
            # kai's own lines: the face's, and over the Fangs' eye patch the Fangs' (as far as the patch shows)
            k = INK[0].copy()
            if kind == 'wide':
                pa = np.zeros((H, W), np.float32)
                ep = eyes['fangs'][s]
                pa[ep['y']:ep['y'] + ep['h'], ep['x']:ep['x'] + ep['w']] = np.asarray(load(ep))[..., 3] / 255
                k = np.where(pa > .5, INK[1], k)
            lines_open = k > .2
        else:
            lines_open = ink(opened)
        # the lash band alone: a horizontal opening drops what is narrow across (an iris's rim, a single lash)
        band_open = cv2.morphologyEx(lines_open.astype(np.uint8), cv2.MORPH_OPEN, np.ones((1, OPEN_W), np.uint8)) > 0
        band_shut = cv2.morphologyEx(ink(shut).astype(np.uint8), cv2.MORPH_OPEN, np.ones((1, OPEN_W), np.uint8)) > 0
        top = lash_line(band_open, luma(opened), lx0, lx1) if INK is not None else line(band_open, lx0, lx1, from_top=True)
        bottom = line(band_shut, lx0, lx1, from_top=False)
        if top is not None:
            top = (top[0], snap_to_paint(luma(opened), top[0], top[1]))
        if top is None or bottom is None:
            print(f'{kind}-{s}: no lash found; left as the lid swap')
            continue
        # one span both lines cover; each held flat past its own ends
        x0 = min(top[0], bottom[0])
        x1 = max(top[0] + top[1].size, bottom[0] + bottom[1].size)
        # held flat a little past both ends, so a lash's outer flick (which reaches past where its line is found) goes too
        x0, x1 = max(lo, x0 - EXTEND), min(hi, x1 + EXTEND)
        xs = np.arange(x0, x1, DX)
        t = np.interp(xs, np.arange(top[0], top[0] + top[1].size), top[1])
        b = np.interp(xs, np.arange(bottom[0], bottom[0] + bottom[1].size), bottom[1])
        b = np.maximum(b, t)  # shut is never above open
        # a lid travels most at its middle and hardly at its corners: the outer corner (where the lash's flick is) stays
        # put, the inner moves a little, so the flick is never left behind as a second one
        u = (xs - xs[0]) / max(1, xs[-1] - xs[0])
        from_outer = u if left else 1 - u
        ease = lambda v: v * v * (3 - 2 * v)
        travel = ease(np.clip(from_outer / OUTER_TAPER, 0, 1)) * (INNER_FLOOR + (1 - INNER_FLOOR) * ease(np.clip((1 - from_outer) / INNER_TAPER, 0, 1)))
        b = t + (b - t) * travel

        # the lash: kai's ink above its line (its lashes reach up LASH_REACH), coloured as painted, its alpha the ink's
        lash = np.zeros_like(opened)
        cols = np.arange(W)
        tl = np.interp(cols, xs, t, left=np.nan, right=np.nan)
        bl = np.interp(cols, xs, b, left=np.nan, right=np.nan)
        tl[(cols >= x0) & (cols < x1) & np.isnan(tl)] = t[-1]
        bl[(cols >= x0) & (cols < x1) & np.isnan(bl)] = b[-1]
        ys = np.arange(H)[:, None]
        within = (ys <= tl[None, :] + 1.5) & (ys >= tl[None, :] - LASH_REACH) & ~np.isnan(tl)[None, :]
        within &= (cols[None, :] >= lo) & (cols[None, :] < hi)
        dark_fill = np.clip((200 - luma(opened)) / 90, 0, 1)
        coverage = np.maximum(k, dark_fill) if INK is not None else ink(opened).astype(np.float32)
        coverage = coverage * (opened[..., 3] / 255)
        lash[..., :3] = opened[..., :3]
        lash[..., 3] = np.where(within, coverage * 255, 0)
        # only the lash itself: what touches its line, not a stray dark speck above (a brow's tail, a hair)
        n, lab = cv2.connectedComponents((lash[..., 3] > 60).astype(np.uint8))
        keep = set()
        for x, y in zip(xs.astype(int), t.astype(int)):
            for dy in range(-3, 2):
                if 0 <= y + dy < H and lab[y + dy, x] > 0:
                    keep.add(lab[y + dy, x])
        body = np.isin(lab, list(keep))
        body = cv2.dilate(body.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        lash[..., 3] *= body

        # the skin: kai's lid with its lash painted out, only round the eye (from LASH_REACH above the open lash to
        # just under the shut one), so her face's outline and everything else of the lid stay kai's
        zone = (ys >= tl[None, :] - LASH_REACH) & (ys <= bl[None, :] + 6) & ~np.isnan(tl)[None, :]
        zone &= (cols[None, :] >= lo) & (cols[None, :] < hi)
        zone_a = cv2.GaussianBlur(zone.astype(np.float32), (0, 0), ZONE_FEATHER) * zone
        lp = np.zeros((H, W, 4), np.float32)
        lp[lid['y']:lid['y'] + lid['h'], lid['x']:lid['x'] + lid['w']] = np.asarray(load(lid)).astype(np.float32)
        lid_ink = ink(lp) & zone
        if INK is not None:
            lid_ink &= INK[2] < .2  # her face's own outline is not the lid's lash: it stays
        lid_ink = cv2.dilate(lid_ink.astype(np.uint8), np.ones((2 * INK_GROW + 1, 2 * INK_GROW + 1), np.uint8))
        rgb = np.clip(lp[..., :3], 0, 255).astype(np.uint8)
        filled = cv2.inpaint(rgb, lid_ink, 9, cv2.INPAINT_TELEA).astype(np.float32)
        skin = np.zeros((H, W, 4), np.float32)
        skin[..., :3] = filled
        skin[..., 3] = lp[..., 3] * zone_a

        half_lids[kind][s] = {
            'skin': save(skin, f'halflid-{kind}-{s}-skin'),
            'lash': save(lash, f'halflid-{kind}-{s}-lash'),
            'x0': int(x0), 'dx': DX,
            'top': [round(float(v), 1) for v in t],
            'bottom': [round(float(v), 1) for v in b],
        }
        slide = pupil_slide(opened, k if INK is not None else None, lo, hi, kind, s, tl)
        if slide:
            half_lids[kind][s]['pupil'] = slide
        print(f'{kind}-{s}: {xs.size} columns from x {x0}, the lash comes down {float(np.median(b - t)):.0f} px')

moods['halfLids'] = half_lids
with open(moods_path, 'w') as fh:
    json.dump(moods, fh, separators=(',', ':'))


# ---- the preview: each eye at a few openings, drawn flat (no turn), as ChessyAvatar draws it -------------------------

def at(o, kind, s):
    """Her face at opening [o], one eye, the other open: the eye, the skin above the cut, the lash on the cut."""
    open_pics, lid_face = KINDS[kind]
    base = comp(open_pics(s == 'l'))
    h = half_lids[kind][s]
    xs = h['x0'] + np.arange(len(h['top'])) * h['dx']
    t, b = np.array(h['top']), np.array(h['bottom'])
    cut = np.interp(np.arange(W), xs, t + (1 - o) * (b - t))
    drop = np.interp(np.arange(W), xs, (1 - o) * (b - t))
    if o < .98:
        sk = np.zeros((H, W, 4), np.float32)
        p = h['skin']
        sk[p['y']:p['y'] + p['h'], p['x']:p['x'] + p['w']] = np.asarray(load(p)).astype(np.float32)
        sk[..., 3] *= np.arange(H)[:, None] <= cut[None, :]
        la = np.zeros((H, W, 4), np.float32)
        p = h['lash']
        la[p['y']:p['y'] + p['h'], p['x']:p['x'] + p['w']] = np.asarray(load(p)).astype(np.float32)
        moved = np.zeros_like(la)
        for x in range(p['x'], p['x'] + p['w']):
            d = int(round(drop[x]))
            if d >= 0:
                moved[d:, x] = la[:H - d, x]
        for layer in (sk, moved):
            a = layer[..., 3:4] / 255
            base[..., :3] = layer[..., :3] * a + base[..., :3] * (1 - a)
            base[..., 3:4] = np.maximum(base[..., 3:4], layer[..., 3:4])
    def paste(base, p, dy=0):
        layer = np.zeros((H, W, 4), np.float32)
        layer[p['y'] + dy:p['y'] + dy + p['h'], p['x']:p['x'] + p['w']] = np.asarray(load(p)).astype(np.float32)
        a = layer[..., 3:4] / 255
        base[..., :3] = layer[..., :3] * a + base[..., :3] * (1 - a)
    pu = h.get('pupil')
    if pu and o < .98:
        push = min(max(0., np.interp(pu['cx'], xs, t + (1 - o) * (b - t)) + PUPIL_GAP - pu['top']), pu['floor'] - pu['bottom'])
        if push > .3:
            paste(base, pu['bed'])
            paste(base, pu['pic'], int(round(push)))
            # the skin and lash again over it
            for layer in (sk, moved):
                a = layer[..., 3:4] / 255
                base[..., :3] = layer[..., :3] * a + base[..., :3] * (1 - a)
    fade = max(0., 1 - o / .15)
    if fade > 0:
        lp = lids[lid_face][s]
        lid_l = np.zeros((H, W, 4), np.float32)
        lid_l[lp['y']:lp['y'] + lp['h'], lp['x']:lp['x'] + lp['w']] = np.asarray(load(lp)).astype(np.float32)
        a = lid_l[..., 3:4] / 255 * fade
        base[..., :3] = lid_l[..., :3] * a + base[..., :3] * (1 - a)
    return base


if preview:
    os.makedirs(preview, exist_ok=True)
    for kind in half_lids:
        for s in half_lids[kind]:
            frames = []
            for o in (1, .8, .6, .4, .2, .1, 0):
                c = at(o, kind, s)
                x0 = 250 if s == 'l' else 620
                crop = c[840:1100, x0:x0 + 380]
                bg = np.full(crop.shape[:2] + (3,), 255.)
                a = crop[..., 3:4] / 255
                frames.append(np.clip(crop[..., :3] * a + bg * (1 - a), 0, 255).astype(np.uint8))
            Image.fromarray(np.concatenate(frames, axis=1)).save(os.path.join(preview, f'{kind}-{s}.png'))
    print('preview →', preview)
