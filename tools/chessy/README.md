# Chessy's pack

Everything under `app/neue/src/commonMain/composeResources/files/chessy/` (and the generated
`core/ai/chessy/ChessyEars.kt`) is built from kai's approved mockup by one command:

```
pip install -r tools/chessy/requirements.txt
python3 tools/chessy/build.py <mockup.html> --check            # build into a scratch folder, compare with the app's pack
python3 tools/chessy/build.py <mockup.html> --install --version <id>   # and replace the pack, if the check passes
```

**The mockup** is the Chessy artifact (https://claude.ai/artifact/Q42YHqjvNELnJLV3qmax9U). Read it with the Artifact
tool (`action: read`); the result is saved as a local HTML file, which is the argument. It is 8 MB and is not kept here.
`SOURCE.json` in the pack records the artifact version and the sha-256 of the data the pack was built from.

**The steps**, in this order (`build.py` runs them; each also runs alone on a pack folder):

| step | writes | why here |
| --- | --- | --- |
| `build.py` | `layer-*`, `rim-*`, `features-*`, `brows-*`, `tongue-*`, `part-*`, `chessy.json` | the mockup's Live look, composited as its `paintView` does |
| `parts.py` | `eye-*`, `mouth-*`, `lid-*`, `brow-*`, `frown`, `moods.json`; trims the mouths at her chin | reads `layer-face`'s alpha before `defringe` touches it |
| `lids.py` | `halflid-*`, `moods.json`'s `halfLids` | needs `parts.py`'s eye patches and lids; reads kai's ink from the mockup |
| `defringe.py` | `layer-*`, `rim-*` in place, lossless | after everything that reads the raw layers |
| `ears.py` | `ChessyEars.kt` | last: traced from the final alpha |

**`--check`** lays each built picture and the app's on the sheet and compares them: the box, the silhouette (IoU),
and the colour where both show — its bias (a shift one way: a real change) and its noise (the old export's lossy WebP).
The patches `parts.py` derives may move a pixel. `--install` refuses a build that differs unless `--force`, which is
only for a change kai has approved.

`export.js` is the old first step: it drove the mockup page's `window.__chessyExport()`, which the published mockup no
longer has. It is kept for the record; `build.py` replaces it.
