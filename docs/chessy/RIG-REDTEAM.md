# Chessy's rig: red team against current VTuber practice

*October 2026. kai's ask: "research state of the art VTuber model rigging techniques and practices and red team Chessy's
model to see if there are improvements and changes we can make." Round one, the rig, is built as a preview; nothing in it
has shipped. Round two, the art, follows once kai has judged round one.*

Chessy is drawn as follows:
- 15 pictures from kai's approved mockup, each bent as a mesh (`drawVertices`).
- One pure stepper, `core/ai/chessy/ChessyRig.kt`.
- One plain-math warp, `ChessyWarp.kt`, which turns the head as a sphere with per-layer depth.
- Pendulums on the hair, bell and tongue.
- Twenty moods cut from three sheet faces (`ChessyMoods`).

This report:
1. compares that rig with how 2D VTuber models are built in 2025–2026 (Live2D Cubism 5.x, Inochi2D, VTube Studio), with
   ideas borrowed from 3D VRM rigs;
2. lists every gap found, ranked by severity;
3. says what round one changed and what waits for round two.

---

## 1. Current practice, in brief

### Tools
- **Cubism 5.0** added:
  - vowel lip sync (Motion Sync, by CRI);
  - auto-generated facial motion;
  - blend shapes on every element.
- **5.1** builds the rotated X/Y shapes in one step ("Apply 3D Expression") and generates hair sway.
- **5.2** pastes mirrored shapes.
- **5.3** (2026-01) adds 15 colour and 5 alpha blend modes, and per-part offscreen drawing so overlapping parts fade
  together.
- **Inochi2D** (open source, about 0.8) has a SimplePhysics node in two modes, a stiff pendulum and a springy string.

[5.0](https://docs.live2d.com/en/cubism-editor-manual/new-function5-0/) ·
[5.1](https://docs.live2d.com/en/cubism-editor-manual/new-function5-1/) ·
[5.3](https://docs.live2d.com/en/cubism-editor-manual/new-function5-3/)

### Preparing the art
**Layer stack.** Front hair, then side hair, then the face and its features, then back hair.
- **Eye:** lash, lid, highlight, iris and pupil, eye white.
- **Mouth:** lips with opaque skin round them, teeth, the inside. The tongue is apart.
- Left and right are always separate.

**Paint what is hidden.** Everything that a moving part uncovers is painted: skin under the hair, a whole eye white
under the lids, the neck under the jaw. A moved part must never show a hole.
[separation guide](https://docs.live2d.com/en/cubism-editor-manual/divide-the-material)

**Texture atlas.**
- 2048² is usual, power-of-two so that mipmaps work.
- Gutters between parts.
- Premultiplied alpha set to match the textures. A mismatch gives dark fringes or glowing edges.

[texture troubleshooting](https://docs.live2d.com/en/cubism-sdk-manual/texture-trouble-shooting/)

### Deformers
**Hierarchy.** The usual chain is:
1. body angle warps;
2. neck rotation (Z);
3. head rotation;
4. face warp (X/Y);
5. a warp per part;
6. the meshes.

A parent must contain its children, or the art tears.

**Head turn.** X and Y each get three keys (−30/0/+30), giving a 3×3 keyform grid; the corners are generated. A
convincing turn has three features:
- the far side compresses and the near side widens;
- the centre line (nose, mouth, between the eyes) curves toward the turn;
- near parts move more than far ones (nose tip and front bangs most; eyes and mouth less; outline and ears less still;
  back hair against the turn).

[XY tutorial](https://docs.live2d.com/en/cubism-editor-tutorials/xy/)

**Other tools.**
- Extended interpolation moves keys along curves rather than lines, so nothing shrinks mid-swing.
- Glue binds overlapping vertices, for example lash to lid.

[ext-interp](https://docs.live2d.com/en/cubism-editor-manual/extended-interpolation/)

### Standard parameters
| Parameter | Range |
|---|---|
| AngleX/Y/Z | ±30 |
| BodyAngleX/Y/Z | ±10 |
| EyeL/ROpen | 0–1 (up to 2 wide) |
| EyeL/RSmile | 0–1 |
| EyeBallX/Y | ±1 |
| Brow Y/X/Angle/Form | ±1 |
| MouthForm | ±1 |
| MouthOpenY | 0–1 |
| Cheek | 0–1 |
| Breath | 0–1 |
| HairFront/Side/Back | ±1 |

[standard list](https://docs.live2d.com/en/cubism-editor-manual/standard-parameter-list/)

### Physics
- **Model.** Pendulum chains whose inputs are AngleX/Z and BodyAngleX/Z.
- **Stepping** (`cubismphysics.ts`):
  - fixed rate, 60 a second since 5.0;
  - elapsed time accumulated, with output lerped between the last two steps;
  - a backlog over 5 s is dropped.
- **Practice:**
  - chains of 2–4 stages for hair and tails, with the tips more delayed;
  - separate groups for front, side and back hair with different lengths, so they never swing together;
  - ears and accessories as short stiff pendulums.

[physics](https://docs.live2d.com/en/cubism-editor-manual/physics-operation/) ·
[source](https://raw.githubusercontent.com/Live2D/CubismWebFramework/develop/src/physics/cubismphysics.ts)

### Liveliness
**Blinks.**
- About 17 a minute at rest, 26 while talking, 4.5 while reading.
  [Bentivoglio 1997](https://wicri-demo.istex.fr/Wicri/Sante/explor/MovDisordV3/Site/fr/Main/Exploration/bibRecord.php?hk=005409)
- The gaps are log-normal: many short, a few long.
  [IBI](https://pmc.ncbi.nlm.nih.gov/articles/PMC4015796/)
- The lid closes about twice as fast as it opens: roughly 75–100 ms shut, about twice that to open.
  [kinematics](https://pubmed.ncbi.nlm.nih.gov/18565090/)
- The Cubism SDK's defaults are close 0.10 s, held 0.05 s, open 0.15 s.

**Gaze.**
- Eyes lead and the head follows 20–40 ms later. Below about 20° the eyes do most of the work.
  [eye–head](https://pmc.ncbi.nlm.nih.gov/articles/PMC2937539)
- Saccades are quick snaps; microsaccades come 1–2 a second.
  [Eyes Alive](https://history.siggraph.org/?p=118454)

**Idle.** The Cubism sample drifts on sines of unrelated periods, so the motion rarely repeats:

| Parameter | Period | Amplitude |
|---|---|---|
| AngleX | 6.53 s | 15 |
| AngleY | 3.53 s | 8 |
| AngleZ | 5.53 s | 10 |
| BodyX | 15.53 s | 4 |
| Breath | 3.23 s | — |

[lappmodel.ts](https://raw.githubusercontent.com/Live2D/CubismWebSamples/develop/Samples/TypeScript/Demo/src/lappmodel.ts)

### Lip sync
- **By volume:** the RMS of the audio drives MouthOpenY, gated and smoothed (fast open, slower close).
- **By vowel:** Cubism 5's Motion Sync classifies A/I/U/E/O.
- **From text-to-speech:** viseme timestamps are mapped to the same five vowels.

[Motion Sync](https://blog.criware.com/index.php/2023/11/17/cri-lipsync-in-live2d-cubism-5-0/)

### Expressions
- `.exp3.json` expressions add, multiply or overwrite parameters, with fade-in and fade-out times (0–0.1 s for snappy
  changes), on their own motion manager so they cross-fade.
  [expression](https://docs.live2d.com/en/cubism-sdk-manual/expression)
- VRM's override rules block or reduce the automatic blink, mouth and look-at while an emotion holds, so a closed-eye
  smile never fights the auto-blink.
  [VRM](https://github.com/vrm-c/vrm-specification/blob/master/specification/VRMC_vrm-1.0/expressions.md)

### What reviewers flag
- Flat "paper" turns.
- Eyes sliding off the face.
- Holes behind moved parts.
- Hair moving rigidly with the head, or every group swinging in step.
- Mouth corners popping.
- No closed-eye smile.
- Eyes that never move.
- Jitter, or stiffness from over-smoothing.

---

## 2. Findings

**Severity:** **H** is seen in ordinary use; **M** is seen when looking; **L** is a detail or internal.

**Status:**
- **R1** is fixed in round one (this branch, a preview).
- **R2** is for round two (the art).
- **Later** means not planned yet.

### Motion

| # | Sev | Finding (where) | Practice it misses | Status |
|---|---|---|---|---|
| 1 | H | **Every mood change popped.** Eyes, mouth, brow tilt and lift, and ear angle were read fresh each frame from `ChessyMoods.of(showing)`. Brows jumped up to 20 sheet px; ears up to 32° (`ChessyAvatar.kt`, `drawChessy`). | Expression fade-in and fade-out (`.exp3` FadeInTime) | **R1**: `ChessyMoodBlend` cross-fades the parts over 120 ms and carries brows and ears on a spring (ζ 0.7). |
| 2 | H | **She followed the pointer with her head while asleep, crying, thinking or reading.** `Expression.gazes` was ignored. | Eye–head behaviour by state; gaze aversion while thinking | **R1**: `ChessyMood.follows`. A mood turned inward drifts about its own rest (`restX`/`restY`): up and aside while thinking, down while reading. |
| 3 | M | **The blink was a binary 130 ms swap on a uniform 2–6 s timer.** | Log-normal gaps; about 17/min, more while talking, fewer while reading; close fast, open slow | **R1**: log-normal gaps (median 3.4 s, 1.2–9 s), ×1.45 while talking, the mood's `blinkRate`, 15 % doubles 150–300 ms apart, 110–150 ms long, the lid snapped shut and faded open over its last 60 ms (`lidAlpha`). A true partial lid needs art (#16). |
| 4 | M | **The head followed on a first-order 140 ms ease**, so a look never landed, it only approached. Roll was locked to yaw. | A critically or slightly underdamped follow; tilt with a life of its own | **R1**: a second-order spring (ω 17, ζ 0.8), settling in about a quarter second with about 1 % overshoot. Roll keeps the tilt into the turn, plus its own slow drift and a lean on stressed words. |
| 5 | M | **Two of her drifted in lockstep.** The idle sines had no phase, so the crew copies, built on the same frame, moved as one. The first ear twitch was always at 3 s. | Incommensurate idle cycles per instance | **R1**: seed-derived phases, a random first twitch, and idle **glances**: every 3–8 s her look glides (0.9 s) to a new nearby point and holds. |
| 6 | M | **No secondary motion from her body.** A happy hop (`ChessyMarks`) or a leap in the pet room moved her whole figure rigidly; her hair and bell never felt it. | Physics inputs from BodyAngle/position as well as the head | **R1**: `step(bodyX, bodyY)` and `carry()`. Body velocity throws the hair, bell, ribbons and ears. |
| 7 | M | **The ears had no physics.** A twitch was a scripted 260 ms sine, and the mood's ear angle was static. | Ears as short stiff pendulums | **R1**: `EAR_L`/`EAR_R` angle springs. They lag a turn, flop with a hop, and a twitch is a velocity kick that flicks and settles. |
| 8 | M | **Lip sync was a canned 12 s loop**, the same rhythm whatever she said, flapping through code and tables. | Lip sync from the speech actually produced | **R1**: `SpeechText`, kai's Flap kept and its rhythm taken from the streamed reply. Syllables come from vowel groups; there are pauses at punctuation; code, tables and links are silent; she is at most 1.5 s behind the text and still when it stops. |
| 9 | L | **The ribbons had no follow-through.** `SwingGroup.BOW` was defined but nothing used it; the bows rode `SIDE` alone. | 2–4-stage pendulum chains | **R1**: `BOW` is a second stage thrown by the side lock's swing. |
| 10 | L | **A swing clamped at its limit kept its velocity**, so it could press against the limit and then snap back. | Inelastic limits | **R1**: velocity still pushing outward is spent at the limit. |
| 11 | L | **Breath was one sine, the same asleep as excited.** | Breath by state; a quick inhale and slower exhale | **R1**: `breathPeriod`/`breathDepth` per mood (asleep 6.2 s and deeper; surprised 3 s); an asymmetric curve; depth eased across a change. |
| 12 | L | **The marks' clock ran slow while she was calm or asleep.** `ChessyMarks.step` capped dt at 50 ms while calm steps are about 65 ms apart and sleeping ones about 115, so the zzz drifted at about half speed exactly while she slept. | — (a bug) | **R1**: capped at `ChessyRig.MAX_STEP`. |
| 13 | L | **`ChessyFrame.moving` was always true** (`\|\| (!still)`). Nothing read it. | — (a bug) | **R1**: removed. `lively` is what pacing reads. |
| 14 | L | **The warp recomputed cos and sin of yaw and pitch for every vertex** of every picture, every frame. | — (cost) | **R1**: kept on the frame and set with the angles. The result is identical, held by `theWarpReadsItsTurnOnceAFrameAndLandsWhereItAlwaysDid`. |

### Art and the warp (round two)

| # | Sev | Finding | Practice it misses | Status |
|---|---|---|---|---|
| 15 | L *(was H)* | **Layer edges have five alpha levels** (0, 64, 128, 191, 255). *Corrected in round two:* the levels are the mockup's own: its packed mask keeps kai's ink coverage in two bits, and the Live look's alpha is `ink + fill·(1 − ink)`. She is drawn below her sheet's size almost everywhere, which blends the steps; they show only magnified (the pet room or the takeover on a 4K screen). Several layers also have opaque pixels touching their box edge. | Clean, fully anti-aliased alpha; gutters | **Later** (kai chose not to this round): smooth the edge by supersampling the mask's contour, with a gutter. |
| 16 | H | **The eyes are baked.** There is no separate eye white, iris, pupil or highlight, so the eyes cannot track or saccade, and the blink cannot be a lid moving down. | Eye stack (white / iris / highlight / lids); EyeBallX/Y; EyeOpen 0–1 | **R2, kai's choice**: the irises stay where kai painted them. The lid moves instead: **half-lids** (an opening from 0 to 1 per eye, the lash cut from kai's ink), giving a blink with frames between, squints and a sleepy lid. See §4. |
| 17 | M | **Patches can slip against the face.** The eye and mouth patches sit at z 0.01 over the face at z 0, on their own grids, so a full turn shifts them about 2 sheet px and a feathered patch can show a faint doubled line (the earlier "line under her nose" was this kind). | Parts parented to one face warp, glued | **R2**: give per-face patches the face layer's depth and grid origin, or glue them by warping both through one shared lattice. |
| 18 | L *(was M)* | **Holes behind moved parts.** *Corrected in round two:* the mockup paints what is hidden. Every layer's packed mask carries a hidden fill (its blue channel): skin under the hair, the ear bases under the back hair, the neck under the face and bell. The app's pictures include all of it (checked pixel for pixel). Only the bows have none. | Paint everything a move can uncover | **Later**: only the bows, if a turn ever shows a gap there. |
| 19 | M | **The turn is a sphere, not keyforms.** The height is a paraboloid (1 − r²) rather than a sphere's; yaw and pitch add rather than compose; layer depth applies only below sheet y≈400–660, so ears and bangs get almost none; layer order is fixed, so a turned pose cannot change what overlaps what. | Keyform grids with near/far parallax and a curving centre line | **Later**: a 3×3 grid of hand-tuned warp offsets per layer, AngleX × AngleY, interpolated bilinearly. It needs kai's eye per keyform. The cheap part (depth for the bangs and ears) could be tried in a preview. |
| 20 | M | **Android 8.0–9 (API 26–28) on a hardware canvas draws her flat**, stretched between two corners: no warp, no light, no rotation (`ChessyMesh.android.kt`). | — | **Later**: draw into a software bitmap there, or a `Picture`. It needs an emulator at API 28. |
| 21 | L | **Six talk frames ship unused**, with about 4.9 MB of decoded pictures loaded and never drawn (`part-talk0..5`, `part-blink`, `part-closed`, and an empty `features-grin`). | — | **Later** (kai chose not to this round; Flap stays). |
| 22 | L | **Android mipmaps may not apply to a `BitmapShader` under `drawVertices`**, which would make the 6–12× minification shimmer. | Trilinear filtering when drawn small | **Later**: check on a device with `--chessy` at 112–132 dp. |
| 23 | M *(was L)* | **The pack could not be rebuilt.** `export.js` drives the page's `window.__chessyExport()`, which the published mockup no longer has; the steps' order was manual, and running `export.js` again would undo `parts.py` and `defringe.py`. The sheet coordinates are written in many places (`parts.py`, `ChessyMarks`, `AmieZones`, `ChessyFit.HEAD`). | A reproducible build from the source | **R2, done**: `tools/chessy/build.py` reads the mockup's data and builds the whole pack in order, with a parity check (§4). The landmarks in many places remain. |

### Docs found stale
These are fixed in this round:
- `NEUE.md` said she is her head alone below 80 dp; the code switches below 150 dp (`ChessyFit.HEAD_BELOW_DP`).
- The pack is 2.2 MB since the lossless defringe, not 0.57.
- Mesh cells are 24–96 sheet px by drawn size.
- The studio's comment listed sizes below `ChessySizes.MIN`.

---

## 3. What round one changed, in one place

**Core** (`app/core/.../ai/chessy/`):
- `ChessyRig`:
  - second-order head;
  - seeded drift and gliding glances;
  - log-normal blinks with `lidAlpha`;
  - mood breath;
  - ear, ribbon and body physics;
  - `carry()`;
  - inelastic limits;
  - everything stepped in pieces of at most 20 ms, with separate random sources for blinks, ears and glances, so a
    coarse step and fine steps are the same motion.
- `ChessyMood`: `follows`, `restX`/`restY`, `blinkRate`, `breathPeriod`/`breathDepth`, all with defaults.
- `ChessyMoodBlend`: the fade between moods.
- `SpeechText`: the words' rhythm.
- `ChessyWarp`: trig read once, and the ribbons' own swing.
- `ChessyMarks`: the clock fix.

**Neue:**
- `ChessyAvatar` steps the blend, honours `follows`, reads her words in the frame loop, and feeds her body's place to
  the rig.
- `drawChessy` draws the old mood's parts under the new one's while it fades.
- `ChessyLook` carries the reply (`NeueApp`).
- The pet room carries her (`ChessyAmieLayer`).

**Constants.** Every feel is a named constant in `ChessyRig`'s and `ChessyMoodBlend`'s companions, and in the mood table:

| What | Constants |
|---|---|
| Head spring | `HEAD_W`, `HEAD_Z` |
| Glances | `GLANCE_*` |
| Blinks | `BLINK_*`, `GAP_*`, `TALK_BLINKS`, `DOUBLE*` |
| Ears | `EAR_*` |
| Body | `BODY_*`, `CARRY_SHARE` |
| Ribbons | `BOW_THROW` |
| Mood fade | `FADE`, `W`, `Z` |
| Speech pace | `SpeechText.SYLLABLE`, `LAG`, `HURRY` |

**Pacing** (kai's lag report). Left alone she is lively, meaning every frame is drawn, 9 % of the time against 11 %
before. Glances glide, so they are taken at her calm pace; jumping glances had cost 18 %. A new Chessy appears already
where her drift is, so she does not turn on arriving. `ChessyPacingTest` now holds her under 15 %. `ChessyCostTest`
(132 dp, 1.5 s, unasserted and noisy) measured 18 ms a drawn frame against 30 ms on `main` on this sandbox's software
Skia: no worse, without claiming more.

**Tests:**
- `ChessyRigTest`: blinks, gaps, overshoot, lockstep, limits, ribbons, ears and hops, the warp's same result.
- `ChessyMoodBlendTest`: every pair of the twenty moods without a jump.
- `SpeechTextTest`.
- `ChessyMarksTest`: the zzz on slow steps.
- `ChessyPacingTest`: the roll as well, and the tighter bound.

**Seeing it.** `tools/shoot.sh --chessy=reel` renders a scripted twelve seconds paced in real time, so calm steps are
taken as the app takes them: drift, a sweep and a jump, three mood changes, a streamed reply with a code block, a
hop, and sleep. The same script run on `main` gives the "before".

---

## 4. Round two: a pipeline that rebuilds her, and eyes that live without moving the irises

*A preview, like round one: nothing has shipped. kai chose to keep the irises where they are painted and asked what
the eyes could gain without moving them; round two is the reproducible pipeline, blinks with purpose and half-lids.*

### Reading the mockup
The mockup's script holds every picture as data (`const LD`):
- per layer, a packed mask (`png`: red the ink, green the fill, blue the fill hidden under other layers), the paint
  (`paint_live`, the restored sheet at twice its size, a JPEG with a 3 px margin), and the paint with its rim filled;
- per face, its features, brows and tongue with a soft alpha (`live_a`);
- the parts (the blinks, the closed and talking mouths), each a JPEG and an alpha.

The Live look (`paintView`) lays the paint over all the piece covers. Its alpha is `ink + fill·(1 − ink)`: that is
where the five steps of #15 come from. The masks' hidden fill is why #18 was mostly wrong.

### Blinks with purpose (`ChessyRig`)
- A look that jumps across her (more than 0.35 of her width in a step) blinks with it six times in ten, never within
  600 ms of the last blink. Her drift, her glances and a smooth sweep never do.
- A mood change asks for a blink (`blinkNow`), so the new face arrives behind the lid, as good rigs hide their swaps.
  A face with its eyes shut lets the ask go.

### The pipeline (`tools/chessy/build.py`, `tools/chessy/README.md`)
- It reads the mockup's data and composites every picture exactly as the Live look does, then runs `parts.py`,
  `lids.py`, `defringe.py` and `ears.py` in order.
- `--check` compares the build with the app's pack on the sheet:
  - the box;
  - the silhouette (IoU);
  - colour bias (a shift one way, a real change);
  - colour noise (the old export's lossy WebP).
- **Parity**: 62 of 62 pictures match. Every bias is under 1 / 255. Every box is the same, except `parts.py`'s
  derived eye and mouth patches, whose feathered edge moves a pixel. The ears' traced outline moved under 1 % of
  their width.
- The pack is now the build's (`--install`). `SOURCE.json` records the mockup version and the sha-256 of its data.

### Half-lids (`tools/chessy/lids.py`, `ChessyLids`, `drawChessy`)
- **Built from kai's own art**, for each open eye (the Grin's sly ones, the Fangs' wide ones) and side:
  - the open lash's lower edge, found in kai's ink as the pair of strokes enclosing the lash's dark fill (the crease
    above encloses skin);
  - where the closed lid's lash rests;
  - the lash itself (kai's ink and the paint's dark fill);
  - the closed lid with its lash painted out, inside a band round the eye only, keeping her face's outline.
- **The lid travels most at its middle**, nothing at the outer corner and half at the inner, so the lash's flick never
  doubles.
- **At opening `o`** the app draws, after the eye:
  - the lid's skin down to a cut between the two lines (a mesh cropped column by column);
  - then the lash moved down onto it.

  Below 0.15, kai's own lid fades in, whole when shut.
- **The blink** is an opening: it closes over 45 ms, holds 40–80, and opens over 100, with frames between.
- **Moods carry an opening** (`ChessyMood.openL`/`openR`, eased by `ChessyMoodBlend`):

| Mood | Opening |
|---|---|
| Thinking | 0.75 |
| Working | 0.8 |
| Reading | 0.85 |
| Angry | 0.6 |
| Waking | 0.55 |
| Sad | 0.85 |
| Wink's open eye | 0.8 |

- `tools/shoot.sh --chessy=eyes` draws each eye at 1, 0.75, 0.5, 0.25 and shut through the app's renderer, then those
  moods.
- **Constants to tune**: `lids.py`'s `OUTER_TAPER`, `INNER_TAPER`, `INNER_FLOOR`, `LASH_REACH`, `EXTEND`; `ChessyLids.FADE_BELOW`;
  the blink's `BLINK_CLOSE`, `BLINK_HOLD_*` and `BLINK_OPEN`; the moods' openings.

**Pacing** stays at 10 % idle. The half-lid meshes are four rows of a dozen columns each.
