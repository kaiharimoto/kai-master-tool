"""Chessy's mood parts (Phase 3): the pieces her twenty moods are built from, cut from kai's three sheet faces.

    python3 tools/chessy/parts.py app/neue/src/commonMain/composeResources/files/chessy

Reads the pack export.js wrote (chessy.json and its pictures) and writes moods.json beside it, with a WebP per
piece. Her face layer is the Grin, eyes and mouth painted in; every other eye and mouth is a patch laid over it:

  eye-<face>-<l|r>     the Fangs' or the Tongue's eye on one side, with the skin round it, so one side can wink
  mouth-<face>         the Fangs' or the Tongue's mouth (the Tongue's tongue stays its own layer, over the bell)
  lid-<face>-<l|r>     the blink, split per side: a shut lid over that face's open eye (Grin and Fangs)
  brow-<face>-<l|r>    each brow alone, so a mood can tilt it (anger, worry)

A patch is the face composited whole, kept where it differs from the Grin and feathered out over its skin, so it
lands on the Grin without a seam. Nothing is drawn new: kai builds from parts only.
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
grin = comp([base, pack['faces']['grin']['features']])
moods = {'eyes': {}, 'mouths': {}, 'lids': {}, 'brows': {}}

for face in ('fangs', 'tongue'):
    whole = comp([base, pack['faces'][face]['features']])
    changed = np.abs(whole[..., :3] - grin[..., :3]).sum(-1) > 22
    moods['eyes'][face] = {
        'l': save(whole, soft(region(changed, 0, SPLIT, *EYES)), f'eye-{face}-l'),
        'r': save(whole, soft(region(changed, SPLIT, W, *EYES)), f'eye-{face}-r'),
    }
    moods['mouths'][face] = save(whole, soft(mouth(changed)), f'mouth-{face}')

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

json.dump(moods, open(os.path.join(out, 'moods.json'), 'w'))
size = sum(os.path.getsize(os.path.join(out, f)) for f in os.listdir(out) if f.split('-')[0] in ('eye', 'mouth', 'lid', 'brow'))
print('mood parts', round(size / 1024), 'KB')
