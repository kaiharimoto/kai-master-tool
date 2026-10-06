#!/usr/bin/env python3
"""
Chessy's layers without the white rim (kai, 2026-10: "there's a slight white border around her too that isn't supposed
to be there"). The layers were cut from a sheet drawn on white, so their soft outer edge carries a little of that white:
invisible on paper, a bright line round her on ink.

Two things, layer by layer:
- **The halo**: on some layers (the left ear, lock and side, the neck) the cut runs up to nine sheet pixels outside her
  dark outline, through the sheet's white, so a band of solid white stands round her line. It is the near-white
  reached from outside before any darker pixel, and never more than HALO pixels in: it becomes transparent, its colour
  bled from what it touches (so smoothing never pulls white back in).
- **The soft rim**: a pixel of the soft edge (within REACH of transparent) brighter than the layer just inside it takes
  that inside colour, keeping its alpha.
Nothing past her outline is touched, and a second run changes nothing.

    python3 tools/chessy/defringe.py            # rewrites app/neue/.../files/chessy/layer-*.webp and rim-*.webp in place, lossless (a lossy pass would bring a little white back each run)
    python3 tools/chessy/defringe.py --check    # says how many rim pixels each layer still has, changes nothing
"""
import glob
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "..", "..", "app", "neue", "src", "commonMain", "composeResources", "files", "chessy")
REACH = 3.0     # how far in from the transparent outside the rim can be, in sheet pixels
CLEAR = 24      # alpha below this is outside
SOLID = 230     # alpha from this up is the layer's inside
BRIGHTER = 28   # how much brighter than its inside a rim pixel must be to be the sheet's white
HALO = 14       # the furthest in the white halo is ever cleared, in sheet pixels
WHITE = 246     # a halo pixel's darkest channel is at least this: the sheet's own white
TINT = 8        # and its channels differ by less than this, where her lit hair is always a little lavender


def rim(rgba: np.ndarray):
    """The rim pixels, and for each pixel of the layer the colour of the nearest inside pixel beyond the rim."""
    a = rgba[..., 3].astype(np.float32)
    outside = a < CLEAR
    from_out = ndimage.distance_transform_edt(~outside)
    inside = (a >= SOLID) & (from_out > REACH)
    if not inside.any():
        return np.zeros(a.shape, bool), rgba[..., :3]
    _, (iy, ix) = ndimage.distance_transform_edt(~inside, return_indices=True)
    near = rgba[iy, ix, :3].astype(np.float32)
    lum = rgba[..., :3].astype(np.float32).mean(-1)
    found = (~outside) & (from_out <= REACH) & (~inside) & (lum > near.mean(-1) + BRIGHTER)
    return found, near


def halo(rgba: np.ndarray) -> np.ndarray:
    """The band of solid sheet-white between the cut and her outline: near-white reached from outside, near the edge."""
    a = rgba[..., 3]
    outside = a < CLEAR
    rgb = rgba[..., :3].astype(np.int16)
    white = (rgb.min(-1) >= WHITE) & (rgb.max(-1) - rgb.min(-1) < TINT) & ~outside
    near_edge = ndimage.distance_transform_edt(~outside) <= HALO
    seeds = white & ndimage.binary_dilation(outside, structure=np.ones((3, 3), bool))
    return ndimage.binary_propagation(seeds, mask=white & near_edge)


def clean(rgba: np.ndarray) -> np.ndarray:
    out = rgba.copy()
    band = np.zeros(out.shape[:2], bool)
    # cleared until nothing more is found: a piece of halo hidden behind another only touches the outside once that goes
    for _ in range(6):
        more = halo(out)
        if not more.any():
            break
        out[..., 3][more] = 0
        band |= more
    if band.any():
        # the cleared band takes the colour of what it touches, so a smoothed edge never pulls white back
        kept = out[..., 3] >= CLEAR
        _, (iy, ix) = ndimage.distance_transform_edt(~kept, return_indices=True)
        out[..., :3][band] = out[iy, ix, :3][band]
    found, near = rim(out)
    out[..., :3][found] = near[found].round().clip(0, 255).astype(np.uint8)
    return out


def main():
    check = "--check" in sys.argv
    # each layer, and its rim-lit twin (drawn over it while she swings): both were cut the same way
    for path in sorted(glob.glob(os.path.join(PACK, "layer-*.webp")) + glob.glob(os.path.join(PACK, "rim-*.webp"))):
        rgba = np.array(Image.open(path).convert("RGBA"))
        band, (found, _) = halo(rgba), rim(rgba)
        name = os.path.basename(path)
        if check or not (band.any() or found.any()):
            print(f"{name}: {int(band.sum())} halo, {int(found.sum())} rim pixels")
            continue
        Image.fromarray(clean(rgba)).save(path, "WEBP", lossless=True, quality=100, method=6)
        print(f"{name}: {int(band.sum())} halo pixels cleared, {int(found.sum())} rim pixels recoloured")


if __name__ == "__main__":
    main()
