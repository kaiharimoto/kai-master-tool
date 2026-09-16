#!/usr/bin/env python3
"""Turn a studio run into the contact strip the README shows.

    tools/contact.py --raw build/shots --out docs/shots \
        --shots desk-night-pov,desk-day-table,minimal-day-table \
        --log build/shoot.log

Three jobs, in this order, because the later ones are worthless if the first
one is wrong:

**Refuse a bad run.** The studio is cheerful about failure. `CardRepository.sync`
*returns* `Failed` rather than throwing, and `Studio.kt` only prints it; a deck
that will not import prints "the table will be empty" and carries on. Either way
the run renders a pretty, empty, perfectly well-formed table and exits 0. A file
size floor never catches that - an empty lit room is still hundreds of kilobytes -
so the log is read as well as the pixels. Two shots that come back *identical*
are the other silent failure: it means the seat keypresses did not land and every
picture is of one camera.

**Resample.** The studio's 1600x1000 is the reference stage every before/after
number in `docs/LOOP.md` is measured against, so a shot is always taken at it and
shrunk afterwards - never rendered small, because layout is dp-driven and a
narrower scene is a differently *laid out* scene. GitHub renders a README about
850px wide, so half size is a 1:1 fit at a quarter of the bytes.

**Write only what moved.** A PNG is already compressed, so git keeps a whole new
blob for every regeneration, forever. An encoder that shifts a byte without
moving a pixel is not worth a megabyte of history.
"""
import argparse
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

# A flat fill of any colour deflates to a few KB at this size; a lit room with
# card art in it is hundreds. This is the crude floor, and it is the one that
# survives without decoding the image.
MIN_BYTES = 100_000
# A uniform image has a standard deviation of exactly zero. Anything with a
# desk, a card and a shadow in it is far above this; the floor is set low
# because a night scene is legitimately dark and flat compared to a day one.
MIN_STDDEV = 5.0
# A gradient alone would pass the deviation test. A real render carries card
# art, which is the thing this actually checks for.
MIN_COLOURS = 2000

# Lines the studio prints when it has failed without failing.
BAD_LOG_LINES = {
    "card pool: Failed": "the card pool never synced - every card on the table is a placeholder",
    "the table will be empty": "the deck did not import - the table has no cards on it",
}


def load(path: Path) -> Image.Image:
    return Image.open(path).convert("RGB")


def inspect(path: Path) -> tuple[list[str], str]:
    """Faults with this one image, and a one-line description of it."""
    faults = []
    size = path.stat().st_size
    if size < MIN_BYTES:
        faults.append(f"{size} bytes is below the {MIN_BYTES}-byte floor")
    image = load(path)
    grey = image.convert("L")
    stddev = ImageStat.Stat(grey).stddev[0]
    mean = ImageStat.Stat(grey).mean[0]
    colours = len(image.getcolors(maxcolors=1 << 24) or [])
    if stddev < MIN_STDDEV:
        faults.append(f"standard deviation {stddev:.2f} - this is very nearly a flat fill")
    if colours < MIN_COLOURS:
        faults.append(f"{colours} distinct colours - the card art probably never arrived")
    line = (
        f"{path.name:28} {image.width}x{image.height}  {size // 1024:5} KiB  "
        f"mean {mean:6.1f}  sd {stddev:6.2f}  {colours:7} colours"
    )
    return faults, line


def identical(a: Image.Image, b: Image.Image) -> bool:
    return a.size == b.size and ImageChops.difference(a, b).getbbox() is None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True, type=Path, help="where the studio wrote its PNGs")
    ap.add_argument("--out", required=True, type=Path, help="the committed contact strip")
    ap.add_argument("--shots", required=True, help="comma-separated names that must be present")
    ap.add_argument("--log", type=Path, help="the studio's stdout, read for silent failures")
    ap.add_argument("--scale", type=int, default=50, help="percent of the rendered size")
    args = ap.parse_args()

    names = [n.strip() for n in args.shots.split(",") if n.strip()]
    faults: list[str] = []

    if args.log and args.log.is_file():
        text = args.log.read_text(errors="replace")
        for needle, why in BAD_LOG_LINES.items():
            if needle in text:
                faults.append(f"{why} (the run printed {needle!r})")

    missing = [n for n in names if not (args.raw / f"{n}.png").is_file()]
    if missing:
        faults.append(f"the studio wrote no PNG for: {', '.join(missing)}")

    present = [n for n in names if (args.raw / f"{n}.png").is_file()]
    print(f"{'shot':28} {'size':>11}  {'bytes':>9}")
    for name in present:
        bad, line = inspect(args.raw / f"{name}.png")
        print(line)
        faults += [f"{name}: {f}" for f in bad]

    # Two shots that came back the same mean the seat presses never landed, and
    # every picture is of one camera. Nothing about a single image reveals that.
    for i, a in enumerate(present):
        for b in present[i + 1:]:
            if identical(load(args.raw / f"{a}.png"), load(args.raw / f"{b}.png")):
                faults.append(f"{a} and {b} are pixel-identical - the seat presses did not land")

    if faults:
        print()
        for fault in faults:
            print(f"::error::{fault}")
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    moved = []
    for name in present:
        image = load(args.raw / f"{name}.png")
        target = image.resize(
            (image.width * args.scale // 100, image.height * args.scale // 100),
            Image.LANCZOS,
        )
        dest = args.out / f"{name}.png"
        if dest.is_file() and identical(load(dest), target):
            print(f"{name}: unchanged")
            continue
        target.save(dest, optimize=True)
        moved.append(name)
        print(f"{name}: written, {dest.stat().st_size // 1024} KiB at {target.width}x{target.height}")

    print()
    print("moved: " + (", ".join(moved) if moved else "nothing"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
