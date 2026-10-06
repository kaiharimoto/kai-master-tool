"""Chessy's mood parts (Phase 3): the pieces her twenty moods are built from, cut from kai's three sheet faces.

    python3 tools/chessy/parts.py app/neue/src/commonMain/composeResources/files/chessy

Reads the pack export.js wrote (chessy.json and its pictures) and writes moods.json beside it, with a WebP per
piece. Her face layer is the Grin, eyes and mouth painted in; every other eye and mouth is a patch laid over it:

  eye-<face>-<l|r>     the Fangs' or the Tongue's eye on one side, with the skin round it, so one side can wink
  mouth-<face>         the Fangs' or the Tongue's mouth (the Tongue's tongue stays its own layer, over the bell)
  lid-<face>-<l|r>     the blink, split per side: a shut lid over that face's open eye (Grin and Fangs)
  brow-<face>-<l|r>    each brow alone, so a mood can tilt it (anger, worry)

  frown                a small smile turned over, without the closed smile's fangs (kai: the frown's own shape)

A patch is the face composited whole, kept where it differs from the Grin and feathered out over its skin, so it
lands on the Grin without a seam. Nothing else is drawn new: kai builds from parts only.

It also trims, in place, every mouth piece export.js wrote (the closed mouths, the open talking mouths) to stop
just above her chin line: they reached over it, and the open mouth carried a strip of the bell, so a turned head
showed a second chin. The face layer's own chin line draws a mouth's bottom edge. Run it after export.js.
"""
import json
import os
import sys

import cv2
import numpy as np
from PIL import Image

out = sys.argv[1]
pack = json.load(open(os.path.join(out, 'chessy.json')))
W, H = pack['w'], pack['h']
layers = {l['id']: l for l in pack['layers']}
SPLIT = 632            # between her eyes and between her brows, on the sheet
EYES = (840, 1092)     # the band her eyes and lids live in (brows are their own layer)
MOUTH = (1060, 1350)   # and her mouth's
GROW, FEATHER = 22, 9  # how far a patch reaches past what changed, and how softly it ends


def load(p):
    return Image.open(os.path.join(out, p['file'])).convert('RGBA')


def comp(pics):
    c = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    for p in pics:
        if p:
            c.alpha_composite(load(p), (p['x'], p['y']))
    return np.asarray(c).astype(np.float32)


def save(rgba, alpha, name):
    """The picture where alpha shows, cropped to it, as a WebP and a box on the sheet."""
    a = np.clip(alpha, 0, 1)
    ys, xs = np.nonzero(a > 1 / 255)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    px = rgba[y0:y1, x0:x1].copy()
    px[..., 3] = px[..., 3] * a[y0:y1, x0:x1]
    Image.fromarray(np.clip(px + .5, 0, 255).astype(np.uint8), 'RGBA').save(os.path.join(out, name + '.webp'), quality=90, method=6)
    return {'x': int(x0), 'y': int(y0), 'w': int(x1 - x0), 'h': int(y1 - y0), 'file': name + '.webp'}


def soft(mask):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * GROW + 1, 2 * GROW + 1))
    grown = cv2.dilate(mask.astype(np.uint8), k).astype(np.float32)
    # what changed stays whole; the grown rim fades out over the Grin's skin
    return np.maximum(np.clip(cv2.GaussianBlur(grown, (0, 0), FEATHER) * 1.6 - .3, 0, 1), mask.astype(np.float32))


def mouth(mask):
    """What changed round her mouth alone: the pieces touching its middle, never a lower lash beside it."""
    m = region(mask, 360, 906, *MOUTH).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
    n, labels = cv2.connectedComponents(m)
    keep = set(np.unique(labels[1110:1290, 470:790])) - {0}
    return np.isin(labels, list(keep))


def region(mask, x0, x1, y0, y1):
    r = np.zeros_like(mask)
    r[y0:y1, x0:x1] = mask[y0:y1, x0:x1]
    return r


base = layers['face']['pic']

# her chin line: the bottom 5 px of the face layer, per column; a mouth piece fades out just above it
face_alpha = np.asarray(load(base))[..., 3]
CHIN = np.full(W, -1)
for x in range(base['w']):
    ys = np.nonzero(face_alpha[:, x] > 128)[0]
    if len(ys):
        CHIN[base['x'] + x] = base['y'] + ys.max()
rows = np.arange(H, dtype=np.float32)[:, None]
chin = np.where(CHIN[None, :] < 0, 1.0, np.clip(((CHIN[None, :] - 4) - rows) / 4, 0, 1)).astype(np.float32)


def trim(p):
    """A pack picture faded out above her chin line, written back over itself."""
    img = np.asarray(load(p)).astype(np.float32)
    m = chin[p['y']:p['y'] + p['h'], p['x']:p['x'] + p['w']]
    # a ceiling, not a product: running this again changes nothing
    img[..., 3] = np.minimum(img[..., 3], 255 * m)
    Image.fromarray(np.clip(img + .5, 0, 255).astype(np.uint8), 'RGBA').save(os.path.join(out, p['file']), lossless=True)


P = pack['parts']
for p in [P['closed']] + P['talk'] + list(P['closedBy'].values()) + list(P['openBy'].values()):
    trim(p)
grin = comp([base, pack['faces']['grin']['features']])
moods = {'eyes': {}, 'mouths': {}, 'lids': {}, 'brows': {}}

for face in ('fangs', 'tongue'):
    whole = comp([base, pack['faces'][face]['features']])
    changed = np.abs(whole[..., :3] - grin[..., :3]).sum(-1) > 22
    # an eye stops above this face's own mouth (kai's "line where the nose is": the Fangs' eyes carried the top of
    # their open mouth under her nose); beside the mouth its lower lashes keep the whole band
    lips = mouth(changed)
    top = np.full(W, H, np.float32)
    cols = np.nonzero(lips.any(0))[0]
    for x in cols:
        top[x] = np.nonzero(lips[:, x])[0].min()
    top = np.array([top[max(0, x - 30):x + 31].min() for x in range(W)], np.float32)
    above = np.clip(((top[None, :] - 6) - rows) / 8, 0, 1).astype(np.float32)
    moods['eyes'][face] = {
        'l': save(whole, soft(region(changed, 0, SPLIT, *EYES)) * above, f'eye-{face}-l'),
        'r': save(whole, soft(region(changed, SPLIT, W, *EYES)) * above, f'eye-{face}-r'),
    }
    moods['mouths'][face] = save(whole, soft(mouth(changed)) * chin, f'mouth-{face}')

for face in ('grin', 'fangs'):
    p = pack['parts']['blinkBy'][face]
    lid = comp([p])
    a = lid[..., 3] / 255
    lid[..., 3] = 255
    left = np.zeros((H, W), np.float32)
    left[:, :SPLIT] = 1
    moods['lids'][face] = {'l': save(lid, a * left, f'lid-{face}-l'), 'r': save(lid, a * (1 - left), f'lid-{face}-r')}

for face in ('grin', 'fangs', 'tongue'):
    brow = comp([pack['faces'][face]['brows']])
    a = brow[..., 3] / 255
    brow[..., 3] = 255
    left = np.zeros((H, W), np.float32)
    left[:, :SPLIT] = 1
    moods['brows'][face] = {'l': save(brow, a * left, f'brow-{face}-l'), 'r': save(brow, a * (1 - left), f'brow-{face}-r')}

# the frown: the closed smile's curve without its fangs, smaller and turned over, in its own ink and weight
cl = pack['parts']['cline']
ink = np.asarray(load(cl)).astype(np.float32)
dark = ink[..., 3] > 200
colour = [float(np.median(ink[..., k][dark])) for k in range(3)]
S = 4                                    # drawn four times over, then brought down, for a smooth edge
fw, depth, weight = 128, 13, 4.2         # sheet px: its width, how far its middle rises, its line
canvas = np.zeros(((depth + 12) * S, (fw + 12) * S), np.uint8)
xs = np.linspace(-1, 1, 64)
pts = np.stack([(6 + (xs + 1) / 2 * fw) * S, (6 + depth * xs ** 2) * S], 1).astype(np.int32)
cv2.polylines(canvas, [pts], False, 255, int(round(weight * S)), cv2.LINE_AA)
a = cv2.resize(canvas, (fw + 12, depth + 12), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
fr = np.zeros(a.shape + (4,), np.float32)
fr[..., :3] = colour
fr[..., 3] = a * 255
cx, cy = cl['x'] + cl['w'] / 2, cl['y'] + 26
fx, fy = int(round(cx - (fw + 12) / 2)), int(round(cy - depth / 2 - 6))
Image.fromarray(np.clip(fr + .5, 0, 255).astype(np.uint8), 'RGBA').save(os.path.join(out, 'frown.webp'), lossless=True)
moods['frown'] = {'x': fx, 'y': fy, 'w': fw + 12, 'h': depth + 12, 'file': 'frown.webp'}

json.dump(moods, open(os.path.join(out, 'moods.json'), 'w'))
size = sum(os.path.getsize(os.path.join(out, f)) for f in os.listdir(out) if f.split('-')[0].split('.')[0] in ('eye', 'mouth', 'lid', 'brow', 'frown'))
print('mood parts', round(size / 1024), 'KB')
