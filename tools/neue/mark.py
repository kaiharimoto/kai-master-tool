#!/usr/bin/env python3
"""Draws the Neue Master Tool mark into the icon files the installers need.

The geometry is `NeueMark.shapes` in app/neue/.../kit/Icons.kt, copied here
number for number: a 100-unit ink square with three paper cards stacked up
the 45-degree diagonal, each cut from the one behind by a 2-unit ink gap.
Exported icons are always the light version (MARK.md): ink square, paper cards.

    python3 tools/neue/mark.py     # writes app/neue/icons/ and the window icon
"""
from pathlib import Path
from PIL import Image, ImageDraw

SHAPES = [  # x, y, w, h, paper
    (44, 16, 32, 46, True),
    (32, 24, 36, 50, False),
    (34, 26, 32, 46, True),
    (22, 34, 36, 50, False),
    (24, 36, 32, 46, True),
]
INK, PAPER = (0, 0, 0, 255), (255, 255, 255, 255)
ROOT = Path(__file__).resolve().parents[2] / "app" / "neue"


def draw(size: int) -> Image.Image:
    # Drawn large and reduced, so edges at 16 px are clean rather than stepped.
    big = size * 8
    k = big / 100
    img = Image.new("RGBA", (big, big), INK)
    d = ImageDraw.Draw(img)
    for x, y, w, h, paper in SHAPES:
        d.rectangle([x * k, y * k, (x + w) * k - 1, (y + h) * k - 1], fill=PAPER if paper else INK)
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    icons = ROOT / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    draw(512).save(icons / "neue.png")
    draw(256).save(icons / "neue.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    draw(1024).save(icons / "neue.icns")
    window = ROOT / "src" / "jvmMain" / "resources" / "icons"
    window.mkdir(parents=True, exist_ok=True)
    draw(256).save(window / "neue.png")
    for s in (16, 32):
        draw(s).resize((s * 8, s * 8), Image.NEAREST).save(Path("/tmp") / f"neue-mark-{s}.png")


if __name__ == "__main__":
    main()
