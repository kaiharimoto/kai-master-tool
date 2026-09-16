# kai's master tool, on the Nintendo 3DS

A New 3DS port. See **[`docs/PORT.md`](../docs/PORT.md)** for why it is a
rewrite rather than a port, what it deletes, and the phase it is currently in.
The root [`README.md`](../README.md) covers the app this is a port of, and how
to install the `.cia`.

## Build

```sh
make -C 3ds/test test     # the conformance suite - host gcc, no devkitARM
make -C 3ds                # kai-master-tool.3dsx
make -C 3ds cia            # kai-master-tool.cia
```

The first of those is the one that runs anywhere. `3ds/src/core/` links no
libctru precisely so that it can, and adding a `#include <3ds.h>` to anything in
that directory breaks the only cheap check this port has.

The other two need devkitPro (`dkp-pacman -S 3ds-dev`) plus `makerom` and
`bannertool`, which devkitPro does not ship — run `3ds/tools/fetch-cia-tools.sh`.
CI does all of this in the `devkitpro/devkitarm` container; see
`.github/workflows/build-3ds.yml`.

## Install

Copy `kai-master-tool.cia` to the SD card and install it with FBI. The `.3dsx`
goes in `/3ds/` for the Homebrew Launcher — **but test the `.cia`** before
believing a feature works: a `.3dsx` inherits its host title's permissions and a
`.cia` gets only what `app.rsf` grants it.

## Layout

| | |
|---|---|
| `src/core/` | the C port of `:core`. No libctru. Host-compilable. |
| `src/gfx/` | citro3d renderer, stereo rig, lighting LUTs |
| `src/ar/` | camera capture, marker tracking, pose |
| `src/ui/` | the bottom-screen control surface |
| `src/net/` | card art over wi-fi |
| `test/` | the conformance suite and its committed golden vectors |
| `meta/` | HOME-menu icon and banner, and the script that draws them |
| `app.rsf` | the exheader — read its header comment before editing |
