"""Chessy's asset pack, built from kai's mockup in one go (the rig red team's #23, `docs/chessy/RIG-REDTEAM.md`).

    python3 tools/chessy/build.py <mockup.html> [--out DIR] [--check] [--install]

<mockup.html> is the Chessy mockup page (kai's approved model, https://claude.ai/artifact/Q42YHqjvNELnJLV3qmax9U),
read with the Artifact tool and saved locally; it is 8 MB and is not kept in the repository. Its script holds every
picture of her as data (`const LD = {...}`): per layer a packed mask (`png`: red the ink, green the fill, blue the fill
hidden under other layers), the paint (`paint_live`, the restored sheet at twice its size) and the paint with its rim
filled (`paint_live_r`); per face its features, brows and tongue with their soft alpha (`live_a`); and the parts (the
blink, the closed and talking mouths, each by face).

`export.js` used to drive the page's `window.__chessyExport()`, which the published mockup no longer has. This reads the
data instead and composites each picture exactly as the mockup's Live look does (`paintView`: the paint over all the
piece covers, alpha `ink + fill * (1 - ink)`; a face piece over its own alpha), then runs the pack's other steps in their
order:

    1. the pictures and chessy.json      (this file; what export.js wrote)
    2. parts.py                          her mood parts and moods.json (reads layer-face's alpha for the chin, so before 3)
    3. lids.py                           her half-lids (the eye opening, its lash and lid skin) into moods.json
    4. defringe.py                       the white halo off every layer and rim
    5. ears.py                           the ears' outline (ChessyEars.kt), from the final alpha, so last

It builds into --out (a scratch folder by default) and touches nothing else. --check compares what it built with the
pack in the app, picture by picture (box, silhouette, colour where both show) and the JSON, and fails over tolerance;
--install copies the build into the app and regenerates ChessyEars.kt, and refuses unless --check passed. kai's
pictures are never replaced by a build that does not match them, except on her word.

SOURCE.json beside the pack records the mockup the pack was built from (artifact version, the sha-256 of its data).
"""
import argparse
import base64
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, '..', '..'))
PACK = os.path.join(REPO, 'app/neue/src/commonMain/composeResources/files/chessy')
EARS_KT = os.path.join(REPO, 'app/core/src/commonMain/kotlin/com/kaiharimoto/mastertool/core/ai/chessy/ChessyEars.kt')

# kai's closed-mouth setting (the mockup's st.clLen and st.clDy, chosen on its stage and kept in its code)
CLOSED_LENGTH = .89
CLOSED_DY = -5


def mockup_data(path):
    """The mockup's LD object, and the sha-256 of its text (what a later build can be checked against)."""
    text = open(path, encoding='utf8').read()
    at = text.find('const LD = ')
    if at < 0:
        sys.exit(f'{path}: no "const LD = " in it; is this the Chessy mockup?')
    start = text.index('{', at)
    ld, end = json.JSONDecoder().raw_decode(text[start:])
    return ld, hashlib.sha256(text[start:start + end].encode('utf8')).hexdigest()


def image(uri, mode):
    return np.asarray(Image.open(io.BytesIO(base64.b64decode(uri.split(',', 1)[1]))).convert(mode))


def placed(q, p, mode):
    """A paint or alpha stored with its own box [q] (a few pixels of margin), cut to the piece's box [p]."""
    img = Image.open(io.BytesIO(base64.b64decode(q['src'].split(',', 1)[1]))).convert(mode)
    if img.size != (q['w'], q['h']):
        img = img.resize((q['w'], q['h']), Image.LANCZOS)  # an `orig` look is kept at half size; the live ones are whole
    a = np.asarray(img)
    ox, oy = p['x'] - q['x'], p['y'] - q['y']
    return a[oy:oy + p['h'], ox:ox + p['w']]


def live(p, paint_key='paint_live'):
    """A piece as the mockup's Live look draws it (paintView, at the default lines: kai's ink is not laid over)."""
    if not p.get(paint_key):
        return None
    paint = placed(p[paint_key], p, 'RGB').astype(np.float32)
    if p.get('live_a'):
        # a face piece: the paint over its own soft alpha
        a = placed(p['live_a'], p, 'L').astype(np.float32) / 255
    else:
        src = image(p['png'], 'RGBA').astype(np.float32) / 255
        ink, fill = src[..., 0], np.maximum(src[..., 1], src[..., 2])
        a = np.where(src[..., 3] > 0, ink + fill * (1 - ink), 0)
    rgba = np.dstack([paint, a * 255])
    rgba[a <= 0] = 0
    return np.clip(rgba + .5, 0, 255).astype(np.uint8)


def part(q):
    """A part (the blink, a mouth): its paint and its alpha, each the box's size."""
    c = image(q['src'], 'RGB')
    a = image(q['a'], 'L')
    return np.dstack([c, a])


def save(px, name, out, lossless):
    Image.fromarray(px, 'RGBA').save(os.path.join(out, name + '.webp'), lossless=lossless, quality=100 if lossless else 90, method=6)


def box(p, name):
    return {'x': p['x'], 'y': p['y'], 'w': p['w'], 'h': p['h'], 'file': name + '.webp'}


def pictures(ld, out):
    """Step 1: every picture and chessy.json, as export.js wrote them."""
    layers = []
    for l in ld['layers']:
        if l['id'] == 'marks':
            continue  # the mockup's manga marks; the app draws its own (ChessyMarks)
        entry = {'id': l['id'], 'z': l['z'], 'rig': l['rig'], 'anchor': l.get('anchor'), 'order': l['order'],
                 'perface': bool(l.get('perface')), 'pic': None, 'rim': None}
        p = l.get('img')
        if p:
            save(live(p), 'layer-' + l['id'], out, lossless=True)
            entry['pic'] = box(p, 'layer-' + l['id'])
            if p.get('paint_live_r'):
                save(live(p, 'paint_live_r'), 'rim-' + l['id'], out, lossless=True)
                entry['rim'] = box(p, 'rim-' + l['id'])
        layers.append(entry)
    faces = {}
    for f in ld['faces']:
        face = {'kao': f.get('kao'), 'name': f.get('name'), 'features': None, 'brows': None, 'tongue': None,
                'tongue_root': f.get('tongue_root')}
        for k in ('features', 'brows', 'tongue'):
            p = f.get(k)
            if not p:
                continue
            name = f'{k}-{f["id"]}'
            px = live(p)
            if px is None:
                # the Grin's features have no paint of their own (her eyes and mouth are the face layer's): empty
                px = np.zeros((p['h'], p['w'], 4), np.uint8)
            save(px, name, out, lossless=False)
            face[k] = box(p, name)
        faces[f['id']] = face
    P = ld['parts']['live']
    parts = {}

    def one(q, name):
        save(part(q), name, out, lossless=False)
        return box(q, name)
    parts['blink'] = one(P['blink'], 'part-blink')
    parts['closed'] = one(P['closed'], 'part-closed')
    parts['cline'] = one(P['closed_line'], 'part-cline')
    parts['talk'] = [one(t, f'part-talk{i}') for i, t in enumerate(P['talk'])]
    for g in ('closedBy', 'openBy', 'blinkBy'):
        parts[g] = {f: one(q, f'part-{g}-{f}') for f, q in P[g].items()}
    pack = {'w': ld['w'], 'h': ld['h'], 'sphere': ld['sphere'], 'closed_cx': ld['closed_cx'], 'opens': ld.get('opens', []),
            'layers': layers, 'faces': faces, 'parts': parts, 'closedLength': CLOSED_LENGTH, 'closedDy': CLOSED_DY}
    with open(os.path.join(out, 'chessy.json'), 'w') as fh:
        json.dump(pack, fh, separators=(',', ':'))


def step(script, *args):
    print(f'[build] {script} {" ".join(args)}')
    subprocess.run([sys.executable, '-I', os.path.join(HERE, script), *args], check=True)


# ---- the check -------------------------------------------------------------------------------------------------

def rgba(path):
    return np.asarray(Image.open(path).convert('RGBA')).astype(np.float32)


def compare(a_path, a_box, b_path, b_box, w, h):
    """Two pictures laid on the sheet: their silhouettes' IoU and the colour difference where both show."""
    def sheet(path, b):
        s = np.zeros((h, w, 4), np.float32)
        px = rgba(path)
        s[b['y']:b['y'] + px.shape[0], b['x']:b['x'] + px.shape[1]] = px
        return s
    A, B = sheet(a_path, a_box), sheet(b_path, b_box)
    sa, sb = A[..., 3] > 127, B[..., 3] > 127
    union = (sa | sb).sum()
    iou = (sa & sb).sum() / union if union else 1.0
    both = (A[..., 3] > 250) & (B[..., 3] > 250)
    d = (B[..., :3] - A[..., :3])[both] if both.any() else np.zeros((1, 3))
    bias = float(np.abs(d.mean(axis=0)).max())          # a shift of colour, the same way everywhere: a real change
    noise = float(np.abs(d).mean(axis=0).max())         # a difference that cancels out: compression
    p99 = float(np.percentile(np.abs(d).max(axis=-1), 99))
    return iou, bias, noise, p99


# A picture matches when its silhouette is the same (IoU) and its colour is the same where both show: no shift of
# colour (bias, the per-channel mean of the signed difference, 0-255) and no more noise than the old export's lossy
# WebP left (noise, the per-channel mean of the absolute difference; p99, its 99th percentile). The patches parts.py
# cuts (eye-, mouth-) are found where the faces differ by more than a threshold, so that noise moves their feathered
# edge a pixel: their box may move by DERIVED_BOX and their silhouette match a little less.
TOL_IOU = .995
TOL_BIAS = 1.0
TOL_NOISE = 4.0
TOL_P99 = 24.0
DERIVED = ('eye-', 'mouth-')
# pictures the build makes itself rather than cuts from kai's (the half-lids): never held to the pack, since changing
# them is what a change to lids.py is for
GENERATED = ('halflid-',)
DERIVED_BOX = 2
DERIVED_IOU = .95


def check(built, against):
    pb = json.load(open(os.path.join(built, 'chessy.json')))
    pa = json.load(open(os.path.join(against, 'chessy.json')))
    w, h = pa['w'], pa['h']
    rows, bad = [], []

    def pics(pack, moods):
        out = {}
        for l in pack['layers']:
            for k in ('pic', 'rim'):
                if l.get(k):
                    out[l[k]['file']] = l[k]
        for f in pack['faces'].values():
            for k in ('features', 'brows', 'tongue'):
                if f.get(k):
                    out[f[k]['file']] = f[k]
        P = pack['parts']
        for k in ('blink', 'closed', 'cline'):
            out[P[k]['file']] = P[k]
        for t in P['talk']:
            out[t['file']] = t
        for g in ('closedBy', 'openBy', 'blinkBy'):
            for q in P[g].values():
                out[q['file']] = q
        if moods:
            def walk(v):
                if isinstance(v, dict):
                    if 'file' in v and 'x' in v:
                        out[v['file']] = v
                    else:
                        for x in v.values():
                            walk(x)
                elif isinstance(v, list):
                    for x in v:
                        walk(x)
            walk(moods)
        return out
    ma = json.load(open(os.path.join(against, 'moods.json')))
    mb = json.load(open(os.path.join(built, 'moods.json')))
    A, B = pics(pa, ma), pics(pb, {k: v for k, v in mb.items() if k != 'halfLids'})
    for name in sorted(set(A) | set(B)):
        if name.startswith(GENERATED):
            rows.append((name, 'regenerated (the half-lids are the build\'s own; judged with --chessy=eyes)', '')); continue
        if name not in B:
            bad.append(name); rows.append((name, 'missing from the build', '')); continue
        if name not in A:
            rows.append((name, 'new', '')); continue
        derived = name.startswith(DERIVED)
        moved = max(abs(A[name][k] - B[name][k]) for k in ('x', 'y', 'w', 'h'))
        iou, bias, noise, p99 = compare(os.path.join(against, name), A[name], os.path.join(built, name), B[name], w, h)
        ok = moved <= (DERIVED_BOX if derived else 0) and iou >= (DERIVED_IOU if derived else TOL_IOU) and \
            bias <= TOL_BIAS and noise <= TOL_NOISE and p99 <= TOL_P99
        rows.append((name, f'box {"same" if moved == 0 else f"±{moved}"}  IoU {iou:.4f}  bias {bias:4.2f}  noise {noise:4.2f}  p99 {p99:4.1f}', 'ok' if ok else 'DIFFERS'))
        if not ok:
            bad.append(name)
    # the numbers the rig reads
    for k in ('w', 'h', 'sphere', 'closed_cx', 'closedLength', 'closedDy'):
        if json.dumps(pa.get(k), sort_keys=True) != json.dumps(pb.get(k), sort_keys=True) and \
                not _close(pa.get(k), pb.get(k)):
            bad.append(k); rows.append((k, f'{pa.get(k)} -> {pb.get(k)}', 'DIFFERS'))
    for la, lb in zip(pa['layers'], pb['layers']):
        for k in ('id', 'z', 'rig', 'anchor', 'order', 'perface'):
            if not _close(la.get(k), lb.get(k)):
                bad.append(f'{la["id"]}.{k}'); rows.append((f'{la["id"]}.{k}', f'{la.get(k)} -> {lb.get(k)}', 'DIFFERS'))
    for f in pa['faces']:
        if not _close(pa['faces'][f].get('tongue_root'), pb['faces'][f].get('tongue_root')):
            bad.append(f + '.tongue_root')
    width = max(len(r[0]) for r in rows)
    for r in rows:
        print(f'  {r[0]:<{width}}  {r[1]}  {r[2]}')
    print(f'[check] {len(rows)} compared, {len(bad)} differ' + (f': {", ".join(bad)}' if bad else ''))
    return not bad


def _close(a, b):
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return abs(a - b) < 1e-6
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_close(x, y) for x, y in zip(a, b))
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(_close(a[k], b[k]) for k in a)
    return a == b


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('mockup')
    ap.add_argument('--out', help='where to build (default: a scratch folder)')
    ap.add_argument('--check', action='store_true', help="compare the build with the app's pack")
    ap.add_argument('--install', action='store_true', help='copy a build that passed --check into the app')
    ap.add_argument('--force', action='store_true', help='install although the check differs (kai approved the diff)')
    ap.add_argument('--version', default='', help="the mockup artifact's version id, recorded in SOURCE.json")
    a = ap.parse_args()
    out = a.out or tempfile.mkdtemp(prefix='chessy-')
    if os.path.exists(out) and os.listdir(out):
        shutil.rmtree(out)
    os.makedirs(out, exist_ok=True)
    ld, sha = mockup_data(a.mockup)
    print(f'[build] mockup data sha-256 {sha[:16]}…, into {out}')
    pictures(ld, out)
    step('parts.py', out)
    if os.path.exists(os.path.join(HERE, 'lids.py')):
        step('lids.py', out, a.mockup)
    step('defringe.py', '--pack', out)
    ears = os.path.join(out, 'ChessyEars.kt')
    step('ears.py', out, ears)
    with open(os.path.join(out, 'SOURCE.json'), 'w') as fh:
        json.dump({'mockup': 'https://claude.ai/artifact/Q42YHqjvNELnJLV3qmax9U', 'version': a.version,
                   'data_sha256': sha, 'built_by': 'tools/chessy/build.py'}, fh, indent=1)
    ok = True
    if a.check or a.install:
        ok = check(out, PACK)
    if a.install:
        if not ok and not a.force:
            sys.exit('[install] refused: the build differs from the pack (see the check); --force only on kai\'s word')
        for name in os.listdir(PACK):
            if name.endswith('.webp') or name.endswith('.json'):
                os.remove(os.path.join(PACK, name))
        for name in os.listdir(out):
            if name.endswith('.webp') or name.endswith('.json'):
                shutil.copy(os.path.join(out, name), PACK)
        shutil.copy(ears, EARS_KT)
        print(f'[install] the pack and ChessyEars.kt replaced from {out}')
    elif not ok:
        sys.exit(1)


if __name__ == '__main__':
    main()
