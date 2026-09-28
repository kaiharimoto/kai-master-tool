#!/usr/bin/env python3
"""Zoom in on one object in a shot.

    tools/crop.py shots/before/desk-night-table.png 1260,290,1500,520 out.png [scale]

`docs/classic/LOOP.md` iteration 3: a contact sheet says whether the room reads; a 4x
crop of one object is what says whether the object does. Two of the first three
defects the loop found were invisible at 1:1.
"""
import sys
from PIL import Image

def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    src, box, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    scale = int(sys.argv[4]) if len(sys.argv) > 4 else 4
    left, top, right, bottom = (int(n) for n in box.split(","))
    image = Image.open(src).convert("RGB").crop((left, top, right, bottom))
    image = image.resize(
        (image.width * scale, image.height * scale), Image.NEAREST
    )
    image.save(dst)
    print(f"{dst}  {image.width}x{image.height}  from {src} at {box} x{scale}")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
