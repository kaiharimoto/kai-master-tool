# Ai's face: where its shape comes from

`docs/NEUE.md` §4k′ has the design. This folder rebuilds its geometry.

- `geometry.json` is the head as kai approved it, in pixels of the 465 × 352 reference.
  It holds the silhouette, the net, the cheeks, the eyes and the fit's residuals.
- `gen.py` writes `app/core/.../ai/avatar/AvatarGeometry.kt` from it:
  `python3 tools/avatar/gen.py`.
- `trace3.js`, `design4.js` and `lib.js` are how `geometry.json` was made. They need
  node, Playwright, and the reference picture, which is not committed:
  1. `AVATAR_REF=/path/to/reference.png node trace3.js` measures the reference and
     writes `trace/geometry3.json`.
  2. `node design4.js --ylo=150 --yhi=250` redraws it as clean shapes and writes
     `trace/geometry4.json`.
  3. Scale the eyes to 95 % and make both 40.8 px tall (the approved `geometry.json`),
     then run `gen.py`.
