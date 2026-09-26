# Foil border mockups

`mockups.py` renders six candidate treatments for the card-face border that
`drawPrismaticInset` (`app/ui/.../theme/Prismatic.kt`) draws today. It uses
Blender Cycles on the CPU, with one card, one light rig and one camera. It also
writes the textures a shader port would sample, and the Blender materials render
from those same files. What you pick from the contact sheet is what the
textures produce.

## Run

```
python3.11 -m venv venv && ./venv/bin/pip install bpy pillow numpy
./venv/bin/python tools/foil/mockups.py --out /some/scratch/foil/out
# or, with a Blender binary (it needs Pillow in Blender's Python):
blender -b -P tools/foil/mockups.py -- --out /some/scratch/foil/out
```

On 4 cores it takes about 25 minutes: roughly 35–60 s per still and 7 s per GIF frame. Useful flags:

- `--variants ACE`: render only some of the variants.
- `--card <passcode|path>`: the default card is Blue-Eyes, 89631139.
- `--still-height`, `--still-samples`, `--gif-*`: size and sample counts.
- `--no-gif`: skip the turntables.
- `--only-present`: rebuild the sheets and HTML from `raw/` without rendering.

Output: `raw/` holds the RGBA renders. `<letter>/` holds the stills on paper and
on ink, a 3× corner detail and the turntable GIF. You also get
`contact_white.png`, `contact_black.png`, `index.html` and `textures/`.

## Setup

- The card is a 59:86 plane with square corners, carrying the full card image.
  The band is the outer 4% of the card's width (`FOIL_INSET`) on all four sides,
  the same as today.
- Stills turn the card −20°, 0° and +20° about its vertical axis, with the
  lights fixed. The GIF sweeps −25°→+25°→−25° in 20 frames.
- Lights:
  - A 1.4 m key softbox, upper left (az −25°, el 35°). It lights the print, and
    it is also the `L` the gratings diffract.
  - Two narrow strips at ±36°. A card turned ±20° mirrors them as a bar of light.
  - A grey studio world with soft panels.
- The film is transparent, so there is no backdrop and no shadow. Pillow
  composites onto `#FFFFFF` and `#000000`.

## Variants and what a port needs

| | Look | Model | Textures |
|---|---|---|---|
| A | Reference | Today's gradient: white/pink/cyan/white, angle = 135 + 25·feel.x, flat emission | `reference_ramp.png` |
| B | Thin-film metal | Mirror metal (roughness 0.16). F0 = `iridescence_ramp(thickness · cos(view)^3 / 1000nm)`. Thickness is 170–450 nm, from `film_noise` | `iridescence_ramp.png`, `film_noise.png` |
| C | Holographic grating | Anisotropic silver with concentric grooves. The rainbow is `spectrum(d·\|(L+V)·g\|/m)` for m = 1..3, with d = 1450 nm and g = radial (analytic) | `spectrum_ramp.png` |
| D | Brushed silver | Anisotropic GGX (0.9, tangent = card x), roughness 0.28, hairline bump. No hue | `brushed_height.png` |
| E | Glitter | Voronoi flakes, each a tilted mirror with its own film colour. A third of them also carry a grating at a random angle (d = 2600 nm) | `flake_normal.png`, `flake_id.png`, `iridescence_ramp.png`, `spectrum_ramp.png` |
| F | Prismatic secret | Diamond cells (45°-rotated checker) of ±45° gratings, d = 3000 nm, with a per-cell period nudge. It covers the whole frame except the art and text boxes. The print multiplies the metal and part of the rainbow, like ink over foil | `secret_cells.png`, `spectrum_ramp.png`, `foil_mask.png` |

All tiled textures are tileable. The ramps are 256×1 and sRGB-encoded. Normal,
height, id and cell maps are non-colour data: sample them with nearest
filtering where noted (flakes, cells). `foil_mask.png` is in card space: R is
the band, G is the frame outside the art and text boxes, and B is the art box.
The boxes were measured on a normal monster frame. Spell, trap, pendulum and
link frames differ.

In a port, the pointer stands in for tilt: `V` becomes a view vector tilted by
feel.x/feel.y, and `L` is a fixed key direction. The strips and studio panels
reduce to one or two analytic highlight bands in view space. Blender
approximates the diffraction as emission, because a BSDF cannot throw light
off-specular. That is exactly what an SkSL port would add on top of the card.
