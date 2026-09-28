> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

<!-- Written by a survey of rendering literature, mapped onto this renderer.
     Generated once; edited by hand from here on. `AAA.md` numbering is
     authoritative and this cites it rather than renumbering it. -->

# The play stage: ranked backlog

Ranked by visual yield per unit of risk. Every item names what a person would see change; anything that could not be given that line was dropped. AAA.md numbering is authoritative — items below cite it and do not renumber it.

Three groups: **first light** (cheap, independent, shippable as-is), **foundations** (unlock several others, and cost more), **asks** (need a decision from kai, or argue with a rule that is currently written down).

---

## First light

Nothing here is blocked on anything else on this list. Roughly in the order I would ship them.

### F1. The foil highlight becomes a streak — AAA #21

**You see:** an extra-deck card stops having a round white pool on it and gets a bright line that runs across the card, sweeping as you tilt it. The one change AAA.md already calls "the single change that would make foils read as foil."

Ward anisotropy drops into the slot `alignment.pow(material.shininess)` occupies in `Shading.of` (Shading.kt:356) with **no new vectors**: `Rot3.right(pose) * side` and `Rot3.down(pose)` are already computed four lines below for the hotspot, and `depth = max(half dot visible, MIN_FACING)` is already the clamped `hn` the exponential wants. Hoist that block above the specular. `CardMaterial` gains `alphaT`/`alphaB`; the grain angle is derived from `card.id` in `CardStock.of`, which already argues for passcode-determinism in its own KDoc ("a foil that lands on a different card every time you reopen the table is a particle effect wearing a material's clothes").

`Shade` gains `grooveDegrees`. The painter's per-frame `Brush.radialGradient` becomes one hoisted unit brush under `withTransform { translate(hotspot); rotate(grooveDegrees); scale(aspect, 1f) }` — the ellipse *is* the transform, so this delivers half of F9 for free. Cap the on-screen aspect near 6:1: cards are ~104 px wide on the reference stage, and an 18:1 streak is longer than the card.

**Cost:** `GoldenStageTest` re-records the `spec` lines for Foil only, if and only if the isotropic path takes an early return on `anisotropy == 0f` and keeps today's `pow()` bit-for-bit. Take that branch — it is the same discipline `CardSolid.slab`'s trailing `backScale: Float = 1f` and `Light.position == null` already keep, and DESIGN.md §11 states as a rule.

**Test:** the highlight is longer across the grooves than along them; the long axis stays fixed in the card's own frame as the camera orbits, so orbiting a resting foil sweeps the streak's brightness without rotating the streak. Two different cards get two different grain angles; the same card gets the same angle twice.

---

### F2. The shadow's soft edge stops having a lip

**You see:** the feathered edge of every card's shadow gets lighter smoothly instead of stepping. Today a resting card takes the two-ring branch and its entire penumbra is one band at alpha 0.4169 where linear coverage wants 0.33 — 22/255 wrong, over the whole soft edge of every card on the felt.

`StageRender.kt:269` is `val step = 1f - (1f - shadow.alpha).pow(1f / rings)`, one alpha for every ring, so ring *k* composites to `1-(1-A)^(k/N)` instead of `(k/N)·A`. Solve the cumulative coverages in core — `Shadows.bandAlphas()` on `CardShadow`, with the exact disc-edge profile `V(t) = (acos t − t·sqrt(1−t²))/π` beside it — and draw band *k* at `a_k = (C_k − C_{k−1}) / (1 − C_{k−1})`. The existing outermost-inward loop is already the order source-over needs. It deletes the `pow` and its import.

**Cost:** none. `GoldenStageTest` never reaches `StageRender`.

**Test:** folding the solved band alphas through source-over returns the intended cumulative coverages to 1e-6, and the band straddling the geometric edge lands at A/2 rather than at 1−(1−A)^(1/2). The profile straddles: V(−1)=1, V(0)=0.5, V(1)=0, V(t)+V(−t)=1.

---

### F3. A shadow's penumbra is the same width on all four sides

**You see:** a card's shadow stops being fatter above and below than it is left and right. The current asymmetry is 1.46:1 and has no physical cause.

`StageRender.polygon(corners, centre, grow)` moves each corner **radially from the shape's centre**, not along its edge normal. For the fixed 59:86 card the corners sit at r = 52.1 half-units while the long edges sit at 43 and the short at 29.5, so the top and bottom edges move out by 0.825·w and the sides by 0.566·w. Same line, same bug family: `half = corners.minOf { (it - middle).getDistance() }` is the half-**diagonal**, so `inset = min(shadow.umbra, half * 0.9f)` lets the umbra eat 90% of the *short* axis before the guard fires.

Replace with a bisector-normal offset builder (`w / cos(half the interior angle)`) in core, tested. This defect is larger than the profile error F2 fixes and larger than the true 1.19–1.35× anisotropy the surveys correctly defer, so it must land before either.

**Test:** for a 59:86 card the outer ring's short edge and long edge are both exactly `spread` from the geometric edge. For any convex polygon and any w up to its inradius, the offset rings stay simple — no self-intersection, signed area strictly increasing outward.

---

### F4. Blue-noise TPDF dither over the felt and the desk

**You see:** the concentric rings in the lamp's pool on the felt and on the desk top disappear.

Four separate surveys proposed this; they are one item. The banding is real and has five named sources, all on near-black: `feltPool` (9 stops from `StageRig.POOL_STOPS`), the sheen radial, the `look.falloff` vignette, `poolBrush` on the desk, and sixty card specular pools.

Core gets `render/BlueNoise.kt` — a void-and-cluster generator returning a `FloatArray`, pure arithmetic with a defining property, testable. UI bakes it offline to `composeResources/files/bluenoise64.png` (following `tools/sounds/make_lift.py`), decodes with `Res.readBytes(...).decodeToImageBitmap()` — both already used in this repo — and wraps it in `ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))`. All common API on CMP 1.11.1; **no expect/actual seam, so this is not AAA #99.**

**Draw it inside the mat's Canvas**, after `drawFelt` and `drawScene(ground)`. It is then in mat coordinates and gets sheared with the table — which for isotropic noise costs some of its blueness and none of its correctness, and it keeps the hard constraint of one graphicsLayer plus one Canvas. The 1:1 screen-space version is strictly better and needs a second drawing surface: see **A1**. Bayer is not an option inside the layer — an ordered tile resampled by a perspective transform is a grey haze.

Clamp the tile so it can never lift a true 0 off zero. Refuse the animated variant in writing: a tile that advances per frame is grain, and AAA #60 / DESIGN.md §11 ban it outright.

**Test:** the histogram is triangular, not uniform (that is the whole difference between TPDF and 1-LSB dither); the ranking is a permutation of 0..N−1; the tile is toroidally continuous, so there is no 64 px seam; and for a synthetic ramp quantised to 8 bits, the remap makes the quantisation error's mean **and variance** independent of the signal.

---

### F5. `FilterQuality.Medium` on card art

**You see:** the small print and the name bar on a card stop crawling as the table turns.

There is no `FilterQuality` anywhere in the app — `AsyncImage` in `CardFace` passes none, so Coil's default of `Low` (plain bilinear, no mipmap) applies on both targets. The minification is mild: the table fetches `art.imageUrlSmall` (~168×246) against ~104 px drawn, so 1.6:1, one level. One argument, and it may be the whole fix. The hand-rolled mip pyramid is not worth it at that ratio, and getting a decoded `ImageBitmap` out of coil3 in common code is an unspiked risk — do not start there.

---

### F6. A card corner past the lens draws nothing instead of drawing something wrong

**You see:** at extreme poses and mid-flip, a card stops briefly smearing into a finite but wrong quad.

`SceneRender.drawSolid` already guards with `if (face.corners.any { !stage.reaches(it) }) return@forEach` before flattening. `StagedCard` and `drawSolidEdges` do not, so a corner past the lens gets `flatten`'s `MIN_GAP = 1f` clamp and draws. Same one-line guard, on the `Vec3` corners, before the `.map { plane.flatten(...) }`.

Do **not** add a sign test on the homography's bottom row. That homography maps card space to *mat* space — the perspective divide lives in the mat's `graphicsLayer`, downstream — so `m20·x + m21·y + m22` is not the eye's denominator. A card lying flat on the felt takes `squareToQuad`'s affine short-circuit and returns m20 = m21 = 0 while its true corner depths differ by tens of pixels. `reaches` is the correct and complete check.

**Cost:** this changes *when* a card disappears. If any golden line moves, split it into two commits — LOOP.md forbids re-recording and changing behaviour in one release.

---

### F7. The portable blend set, written down — and one live bug

**You see:** on an API 26–28 device in light mode, the chromatic edge stops painting two opaque warm/cool rings over the thing it was meant to modulate.

`Prismatic.kt`'s light branch of `chromaticEdge` uses `BlendMode.Multiply`, which is outside PorterDuff and silently degrades to `SrcOver` below API 29; minSdk is 26. The fix is one word: `BlendMode.Modulate`. Note honestly that `chromaticEdge` currently has **no call sites** — only `prismaticBorder` and `ChromaticText` are used — so this is a latent bug, and the reason to fix it now is that the next caller will not know.

Then make it a rule in DESIGN.md §12: *a blend mode outside PorterDuff's set is silently SrcOver below API 29.* Everything else in the app (`Plus`, `Screen`, `Modulate`, `SrcOver`) is already portable, which is luck. `BlendMode.isSupported()` does **not** exist — I checked the resolved `ui-graphics-desktop-1.11.1` jar; `BlendMode` is a value class with no such member. The substitution is the whole fix and needs no guard.

**Test:** name the permitted operators as data in core (Light → Plus, Ink → Modulate) and assert that set is a subset of the PorterDuff list, also data. A fourth mode added in five years then fails a line rather than a device nobody has.

---

### F8. Sleep tolerances in card widths, not pixels

**You see:** a resting card stops micro-jittering on a large desktop window, and stops freezing a visible distance short of its target on a small one.

`PosePhysics.settled` defaults to `positionTolerance = 0.5f` / `angleTolerance = 0.25f` and `Springs.settled` to 0.5/0.5 — absolute pixels, against a card size `BoardLayouter` re-solves per window. At ~104 px that velocity tolerance is 0.005 card widths/s, 34× tighter than Box2D's. Take a `cardWidth` and express both as fractions of it, exactly as `Settle.Landing.slipX` and `CardDynamics.SATURATION_SPEED` already do.

Everything else about AAA-#8-style sleep is **already done** and by architecture rather than as an optimisation — `StageCard.parked`, the frame loop's `moving` count, `aimAt` as promotion, the snap-not-lerp demotion. This is the one residual.

**Test:** a card that has settled at a 104 px card size is also settled at a 208 px one, given the same fractional error. False today.

---

### F9. The allocation ledger

**You see:** nothing, at rest. On the frame readout (AAA #96, three taps on the life-point number), p95 falls while orbiting a full board. This is the budget every other item on this list spends, which is why it is here rather than in a performance appendix.

Still being paid, per card per frame: `Homography.rectToQuad` allocates two data-class instances; `drawCardSurface` builds a `listOf` + `Brush.radialGradient` and a seven-`Pair` `arrayOf` + `Brush.linearGradient`; `drawCardShadow` allocates a fresh `Path` per ring; `drawSolidEdges` allocates six `Face`s each holding a four-`Vec3` list, plus a sorted copy, a flatten map and a `Path` per face; `feltPool` and `poolBrush` rebuild a Skia shader every frame.

Three independent edits:
1. `Homography.rectToQuadInto(width, height, quad, out: FloatArray)`, keeping the allocating data class as the readable definition the tests are written against.
2. Hoist the five brushes. **The lamp colour needs no quantising** — `Shade.lamp = Lit(1f, light.warmth)` comes from `look.lighting.key`, so it is the same value for every card in a room, and `StageLook.poolColour` is already literally that colour. `StageLook` is `@Immutable` and rebuilt only when the room or the hour changes, which is exactly the right lifetime.
3. One `Path` per card, `rewind()`ed. **Not** one shared across cards — Skia may retain a Path handed to `drawPath`. Hold it in the same `remember` `StagedCard`'s `FloatArray(16)` already lives in.

AAA #94 is this at a coarser grain. `drawVertices` stays refused and the reason should be written down so it stops being proposed: `Vertices` re-encodes `List`s to primitive arrays, and it is a silent no-op below API 29 against minSdk 26.

**Test:** `rectToQuadInto` writes bit-for-bit the sixteen floats `rectToQuad(...)!!.writeComposeMatrix(...)` writes, for a flat card, a leaned hand card and a card on a forty-card deck.

---

### F10. Wood grain on the desk top — AAA #67's material half

**You see:** the desk stops reading as a flat brown fill and reads as a sawn plank.

The desk is `0xFF2E2419` (b ≈ 0.10–0.18), the one surface on this stage bright enough for a multiplicative detail pass to survive — `BlendMode.Overlay` is exactly 2× modulate there and preserves the mean. On the felt at ≈0.02 linear it moves the output by a tenth of one code, which is why F4 exists instead.

One added `drawPath(path, brush)` inside `SceneRender.drawSolid`'s existing `forEach`, sharing F4's bake pipeline. Two constraints from the file itself: put the ring centre far outside the tile (`cx = −6·width`) so it reads flat-sawn rather than as a tree stump, and apply it **only to faces at z = 0** — the guard five lines away at SceneRender.kt:487-492 already documents why (a shape in mat coordinates is correct only where `StagePlane.flatten` is the identity). The desk top qualifies; the desk's sides and the wall do not.

**Cost:** the desk runs to the back of the room, so it minifies harder than the mat and will moiré at the far end until **N3** exists. Measure before shipping.

---

## Foundations

Each of these unlocks two or more items above or below. They cost more and several force a golden re-record.

### N1. Compose in linear, and give the rig a specular — AAA #15

**You see:** every faint white on the stage arrives at the brightness it was tuned for. The headline is `StageRender.kt:506`, `val rim = 0.09f + shade.fresnel * 0.7f`: over true black that 0.09 puts **0.854%** of white's light on screen, not 9%. The always-there cut edge on every card is currently about nine times dimmer than its constant says.

Half is done and the menu is stale: `Tone.shade` and `Color.shaded` are already the one door every known-colour surface goes through, and every surface AAA #15 names is already opaque and linear-correct for its diffuse term. **Mark #15 Done in the same change.** What is actually missing is new light — `StageRig.lit` returns ambient + lambert + graze with **no specular term at all**, so there is nothing to fold in. Give `StageRig` a `shade(face, eye, lighting): Shade` beside `face`, reusing the `Shade` type that already exists, and replace `Color.shaded(lit)` with a `Color.lit(shade)` computing `toSrgb(toLinear(albedo)·diffuse + lampLinear·specular)` per channel, drawn opaque.

Then the additive twin of `Tone.veil`: **`Tone.gloss(add, over = MID_TONE)`** and `plusAmount(add, over)`, sharing `MID_TONE` so the two approximations are argued in one place. Call sites in order of how wrong they are: the rim; the specular alphas and foil ramp strength (destination is card art, so these take `plusAmount` and keep `Plus`); the zone strokes and the two `sheen` alphas (base is known, so these want the exact `alpha = toSrgb(desired_light)`, or better, no alpha at all); the mat-control chip stroke, the shuffle marks, and the drop indicator.

Land **exposure and the soft clip** in the same release or neither: `StageLighting` gains `val exposure: Float = 1f`, applied to the summed directional terms *before* `key.ambient` is added so `NIGHT_FLOOR` stays a guarantee; the terminal `coerceIn(0f, 1f)` becomes a **gated** soft clip — `if (b + s <= 1f) b + s else soft(b, s)` — which is bit-identical today and stops being invisible the moment a specular is composed in linear. An ungated `1 − (1−b)·exp(−s/(1−b))` moves every golden line for nothing.

Write the refusal of Reinhard, ACES and PBR Neutral as a KDoc, in the register `Tone.MID_TONE` and `Tone.KNEE_LINEAR` already use, with the number that decides it for *this* stage: at L = 0.002 — a shadow edge, a pile side in the dark half of the mat — the untonemapped code is 6.6, ACES gives 0.9, PBR Neutral gives 0.1. Same KDoc refuses gamma 2.2 (0.051× the true light at code 3), the best pure power 2.223 (0.046×), and Unity's cubic (0.250×), all of which are inside 2% at code 128 — which is the point: every published fit is tuned where this app is not.

**Blockers, both real.** (1) The felt cannot go fully opaque: its diffuse pool is centred on the lamp's foot and its sheen on the lamp's mirror image, and two non-concentric radial falloffs are not one Compose `Brush`. They coincide only at the overhead seat. (2) A card's **face** is shaded by the key alone while its own **edges** go through `StageRig.face` and get all three lamps — composing the edge in linear with a new specular while the face keeps a one-light `Plus` overlay widens a disagreement that already exists. **N2 is the fix for that**, so sequence them.

**Unlocks:** N2, N4, F1's brush hoist meaning something, and the entire exposure/Kelvin line.

**Ask attached:** converting the rim to a light fraction without re-choosing 0.09 makes every card outline roughly four times brighter. See **A5**.

**Test:** for a known albedo the composed channel equals `toSrgb(toLinear(a)·d + l·s)` and is strictly brighter than the encoded sum at every destination below code 128 — 40 codes at code 13, 31 at code 26, exactly equal at 128, 6 the other way at 230. A claim about the sign of the error, not a recorded number. And in `ToneTest`, `gloss`'s residual runs **opposite** to `veil`'s at the same channel, so on a face carrying both they partially cancel and a future ambient change must move both together.

---

### N2. Replace the black veil with an exact affine shade

**You see:** the night desk can finally go dark. Card art dims correctly instead of being greyed by a black wash, and `NIGHT_FLOOR = 0.55` — the constant that currently forces `DeskNight` to buy its darkness in `falloff` because it may not buy it in ambient — can be deleted.

`Tone` gains `shadeGain(light) = light.pow(1/GAMMA)` and `shadeLift(light) = OFFSET·(gain − 1)`, both above `KNEE_ENCODED`. `Tone.veil` stays — it is still what a `drawRoundRect` needs — but stops being the only answer.

The wiring is the hard part and has two distinct problems. **(1) There is no `drawImage` to hang a `ColorFilter` on.** Card art is a Coil 3 `AsyncImage` composable; `colorFilter` is a *composition* parameter, and `shade.diffuse` changes every frame a card tilts, so passing it would recompose all sixty cards per frame — the exact failure `StageCameraState`'s KDoc and DESIGN.md §6 exist to prevent. Swap for `rememberAsyncImagePainter` and draw inside a draw lambda via `with(painter) { draw(size, alpha, colorFilter) }`. Same for `CardBack`. The `SurfaceRaised` background and the name `Text` are known `Color`s and take `Color.shaded(...)` directly — exact, and strictly better than what the veil does to them today. **(2) The additive terms stay outside the matrix.** The matrix goes on pigment only; everything from `if (shade.specular > 0.004f)` down composites on top unchanged, because a specular pool is a reflection of the lamp and must not be shaded.

Removes up to **120** `drawRoundRect`s per frame — `drawCardSurface` runs for both `CardFace` composables per card.

**Blockers:** Compose `ColorMatrix`'s fifth column has different units on Android (−255..255) and Skiko (−1..1) (JetBrains/compose-multiplatform#3461). This must be checked on the device before a signed build, or `b` applied as a platform-selected pre-multiplied constant — it is the one item here that would ship as a visibly wrong Android build. And `ColorFilter.colorMatrix()` allocates per call; quantise `diffuse` to 1/256 and cache the filter.

**Unlocks:** N4's shadow composition, the MTF defocus fallback, the honest vignette, and a genuinely dark night room.

**Test:** for every k the rig can produce (0.30 to 1.0) and every channel above `KNEE_ENCODED`, `shadeGain(k)·c + shadeLift(k)` equals `Tone.shade(c, k)` to within half a level of 255 — against `theVeilLeavesDarkChannelsTooBrightAndTheGapGrowsAsTheLightFalls`, which pins 1.19× at 0.72 and 1.87× at 0.3. Plus the two endpoints: exact identity at k = 1, exactly black at k = 0. That last one is what lets this land without re-recording the golden.

---

### N3. `StagePlane.density`, and level of detail — AAA #97

**You see:** on its own, nothing. It is the number four other items are guessing at.

There is **no homography for the mat** — `Homography` is per-card. The mat's projection is `StagePlane.project`, so screen-pixels-per-mat-unit comes from differentiating *that*, which is exact rather than fitted: at z = 0 both partials are closed-form two-liners in the `sinTilt`/`cosTilt`/`sinYaw`/`cosYaw` privates the class already caches, and `Projected.scale` is most of the answer. Add `fun StagePlane.density(x, y): Vec2` beside `liftScale` and `eyePoint`, tested in commonTest like the rest of the file.

A depth *band* on this stage is not a `clipRect` — depth is `localY·sinTilt` in the spun frame, so at yaw ≠ 0 a band is a rotated strip. `StagePlane` owns the sign of the yaw in exactly one private place by design, so band quads must come from a core helper, not be reconstructed in the composable.

**Unlocks:** the felt weave (AAA #65) without moiré, F10's far end, the DoF ranking, the per-card LOD AAA #97 asks for ("a card at the far edge does not need a specular pool, a soft shadow or a foil sweep"), and the mip decision. The cheap companion is `Homography.jacobianScaleAt(x, y) = sqrt(abs(det J))` — fifteen float ops, the free by-product of the per-card map, and exactly the per-card number #97 wants.

**Test:** `density` agrees with a finite difference of `project` at the mat's four corners, at every seat in `CameraEnvelope` and at yaw 0/90/150. And ρ_near/ρ_far is monotonic in pitch, so a band count never oscillates mid-orbit. For the Jacobian: `jacobianScaleAt(centre)²` equals `CardSolid.flatArea(quad) / (width·height)` to within one per cent for a flat card, a leaned hand card and a card on a forty-card deck.

---

### N4. Shadows stop being black — AAA #18 + #21b, with contact AO — AAA #19

**You see:** a shadow becomes the felt, darker and a little cooler, instead of a near-black hole punched in a true-black stage; and the tight dark line where a card actually touches the table gets its own falloff instead of being a hard-edged copy of the card two pixels oversize.

AAA #21b already scheduled this and already made the argument: "`DARKEST` and `FADE_OVER` are now computable rather than chosen… A clean separable release." The arithmetic checks out against the shipped rig — `StageRig.Key` normalised is (0.3003, 0.4504, −0.8408); on a flat mat direct = 0.23542, fill = 0.0780, kick ≈ 0, so `amount` clamps to 1.000 while the shadowed value is 0.7980: **alpha_umbra = 0.202 against `Shadows.DARKEST = 0.66`.** Warmth swings +0.0986 lit to −0.0977 shadowed.

Lands as one function beside `StageRig.lit` — `shadowed(normal, eye, lighting, at): Lit`, which is `lit` with the key's `direct` term dropped and everything else kept. `DARKEST` and `FADE_OVER` are deleted, `CardShadow.alpha` becomes coverage rather than opacity, and `StageRender` writes the shadowed colour at the band's coverage. That is the right composite even over the felt's gradient: the felt beneath is already `look.mat.shaded(lit)`, so coverage-over between two correct colours is a lerp between lit felt and shadowed felt and needs no sampling.

The contact term ships in the same release or the change is reported as "the shadows are gone." `CardShadow.kt:153`'s `contact = 1f / (1f + (lift/CONTACT_OVER)²)` is a scalar with no shape; replace with the closed form `A(e, h) = 0.5·(1 − e/sqrt(e² + h²))` plus a named `CONTACT_FLOOR` (~0.02 card heights) so the deliberate exaggeration is one visible constant. The `h` it needs is already on the type as `CardShadow.height`. Evaluate at the ring offsets so the seam lives in the same geometry as the penumbra at no extra draw. Put the exaggeration here, not in the cast shadow.

**Cost:** forces a `GoldenStageTest` re-record — it records `dark ${unit(shadow.alpha)}` for every object at three seats. AAA #21b has already argued for that as its own separable release, so the blocker is sequencing, not permission. Once the alpha is normal-dependent it cannot be hoisted out of the per-card loop.

**Test:** for a flat mat under `StageLighting.Minimal`, `1 − shadowed.amount / lit.amount` equals the key's directional share and is under 0.25, not 0.66. The shadowed warmth is strictly negative wherever the lit warmth is positive, for every rig with a warm key and a cool fill, so "in shadow means in skylight" is arithmetic rather than a tint somebody chose. And `shadowed.amount > 0` for every normal and every preset — a shadow is not a hole. For the AO: `A(0,h) = 0.5` exactly for every h; `A(e,h) + A(−e,h) = 1` exactly; the 25%-to-75% transition is `1.155·h` wide for every h, so the band collapses to a step as h → 0 with no light term anywhere in the expression.

---

### N5. Fresnel coupling and a normalised lobe — one commit

**You see:** a card tilted from face-on toward grazing loses diffuse light and gains specular *in the same proportion*, instead of the two being independent knobs; and "shinier" stops meaning "brighter".

Both halves in `Shading.of`. Hoist `eye.normalised()` (F1 wants it too), add `loh` and the 5th-power Schlick; multiply line 356's specular by `F / f0` and line 346's `diffuse` by `(1 − F)` **before** the `coerceIn` — `drawCardSurface` calls `Tone.veil(shade.diffuse)`, so the coupling must be inside the number `Shade.diffuse` carries or it does literally nothing. Line 360's `fresnel` becomes the view Schlick, and `CardMaterial.rim` (0.5 / 0.75 / 0.35 — a slider with no referent) becomes `f0` (0.0400 / 0.0387 / 0.0400): a rename plus new values, not a new field. `StageRender.kt:506`'s `0.09f` floor goes in the same commit — it was standing in for exactly the flat base Schlick supplies, and leaving it gives the card a double edge.

Then normalisation: `CardMaterial` gains a precomputed `val normalisation = (shininess + 8f) / (8f·PI)`. Magnitudes at shipped exponents are ×1.353 Gloss, ×2.069 Foil, ×0.716 Sleeve; against an ambient of 0.72 the foil clips unless the specular constants come down in the same change — which is the point, because after this they come down once against a physical target rather than against each other.

**Ship together or neither.** `F/f0` alone clips; `(1−F)` alone is a flat 4% dimming with a red golden and no visible cause. One discrepancy worth naming before anyone pastes the standard expression: the shipped specular is *gated* on `lambert > 0f`, never *multiplied* by it. Adding `· lambert` is a further dimming of every highlight on the table — take it deliberately or leave it out.

**Cost:** re-records every `spec` line in all three seats. Batch it with N4 and F1.

**Unlocks:** the sleeve clear-coat (AAA #26 — a second lobe, `coatShininess` ≈ 90, `coatShare` on `Shade`, five gradient stops instead of three), and any future exponent raise.

**Test:** the sum of what one surface returns to the eye is monotonic in the angle rather than jumping. Raising shininess narrows the highlight without brightening the card. Sanity target as physics, not a recording: a bare gloss card at normal incidence returns ≈0.040 of the key, a sleeved one ≈0.109. And restate `ShadingTest.theRimLightsUpAsACardGoesEdgeOn`, which currently asserts `assertClose(0f, square)` — Schlick deliberately falsifies that; a card facing you reflects a little.

---

### N6. Fixed timestep, accumulated — AAA #37

**You see:** the overshoot on a card landing is identical on a 120 Hz tablet and a 60 Hz desktop. It is not today: one variable `dt` (clamped at 0.05, re-clamped at `Springs.MAX_STEP = 1/30`) feeds `StageCard.step` and `camera.rig.step`, so a spring tuned at 60 Hz genuinely overshoots differently at 120.

New pure `core/motion/FixedStep.kt`: `advance(frameSeconds): Int` (clamp at 0.25, cap at 8 steps so a GC pause dilates time instead of bursting) plus an `alpha`. The loop becomes `repeat(steps) { … }` then one `draw(alpha)`. `StageCard` gains `previous: Pose3` beside `motion` and lerps into `pose`. Three teleports must force `previous = current` or a card slides in from its old place for one frame: `placeAt`, the settle snap, and — critically — **a carried card is excluded from interpolation outright**, not lerped: a frame of interpolation lag between a finger and the card it is holding is DESIGN.md §12's last anti-pattern by another name.

**Drop the substep multiplier.** "n substeps × 1 iteration beats 1 step × n iterations" is a claim about a constraint solver, and there is no solver — `PosePhysics.step` is seven independent scalar springs with no contacts and no iterations. Substepping springs is only a smaller dt, which the accumulator already buys.

Pin the stability bound while here, since nothing does: mass is 1, so ω·dt < 2 with `MAX_STEP = 1/30` caps usable stiffness at 3600 (Catto's stricter rule at 2222). The shipped max is 520 — 4× headroom, and the bound should be written down before anything raises a stiffness. Add `SpringSpec.of(hertz, zeta)` as a reparameterisation (`Snappy` is 3.63 Hz critically damped, `Bouncy` 3.10 Hz at ζ 0.62), keeping the three literal constructors so nothing moves.

Add a `nudge` API on `StageCard` — `PoseMotion` already has the fields, nothing upstream can write them, and it is the same API a thrown card needs (AAA #40; `MatEvent.Dropped(at, velocity)` already carries a velocity `MatInput` discards).

**Unlocks:** every physics item, and it is the precondition for AAA #53's anticipation being honest rather than scripted.

**Test:** the same elapsed time produces the same step count however it is chopped into frames. And: a card aimed at a target and stepped for 500 ms of wall clock at 1/60 lands within half a pixel of the same card at 1/120. False today.

---

### N7. The felt has a weave — AAA #65

**You see:** the mat stops reading as a fill colour.

Generator arithmetic (value noise / PCG / fBm) in `core/render/Weave.kt`, tested; UI decodes a baked `felt128.png` and carries the `ShaderBrush` on `StageLook` — whose own header states exactly the right lifetime ("changes when the user picks a different room, or when the hour crosses dusk, and on no other frame ever"). Insertion is one statement in `drawFelt`, between the base fill and the sheen, reusing the `matTopLeft`/`matSize`/`corner` locals already computed. Shares F4/F10's bake pipeline.

**Blocked on N3, and that is what makes it a foundation item rather than first light.** Under the perspective quad the far half minifies and the weave moirés while the camera orbits — which breaks "nothing idles" *by accident*. Depth-banded LOD by contrast (not by blur) is the fix, and it needs `density` and the spun-frame band quads.

Then **Toksvig transfer** makes the sub-pixel half honest instead of merely faded: `StageRig.sheenRadius` already takes roughness as an RMS slope and already says in its KDoc that it "adds to the source's own angular radius before both are carried down the same mirror path" — the Pythagorean widening `sqrt(r² + s_weave²)` drops in with no restructuring. Move `FELT_ROUGHNESS = 0.36f` from `:ui` into core beside it (its own KDoc says it was "solved rather than chosen" against `sheenRadius`, so it is a surface property wearing a renderer's clothes), and add `s_weave` defaulting to 0f. Energy conservation multiplies `StageLook.sheenCore`/`sheenEdge` by (r/r')² at the draw, not in the palette.

**Do not bake paper fibre.** At 104 px per card it is entirely sub-pixel; it belongs in `CardMaterial.micro` through the same transfer, with an early return on `micro <= 0f` so all three shipped materials stay bit-identical. That is the only item on this list that reaches all sixty cards for two floats and a sqrt.

**Test:** the tile is exactly seamless at every octave — `at(u,v) == at(u+P,v) == at(u,v+P)` bit-for-bit through the whole fBm sum, which is the assertion that catches the octave-period bug. At `micro = 0f` every field of `Shading.of` is bit-identical for all three materials, asserted with `==` on raw floats. And widening the lobe conserves energy: as `micro` rises, `specular × (r')²` stays constant, so a receding mat cannot get brighter.

---

### N8. A card's shadow falls on another card — AAA #19

**You see:** two overlapping cards stop reading as two stickers. Cheapest first slice and the one that pays most: the **carried** card, whose shadow DESIGN.md §10 already promises falls across the cards it crosses and today only reaches the felt.

Step 1 needs no new code — `Shadows.landOn(base, light, surfaceZ = z_B)` already takes the receiving plane as a parameter, already handles a placed lamp per corner, and its KDoc says it was extracted precisely so a second consumer could exist. What is new: a convex Sutherland–Hodgman clipper (write it **once** and share it with the hemisphere clip of the polygon-irradiance oracle), and an AABB broadphase over **clipped** extents — resting cards genuinely sit within a few pixels of each other and an unclipped quad crosses the table.

The hard part is paint order and it is nameable. A shadow cast by A onto B must be drawn in B's pass and *after* `drawContent()`, which `drawWithContent` allows. B needs A's live pose, which is reachable without new state — `StagedCard` already reads its own pose out of the shared `cards` map inside the draw lambda, and reads in a draw lambda do not recompose. What must be added is a candidate list: `Seat` gains the ids of overlapping cards above it, computed once in the `ordered` `derivedStateOf` where the projection already happens. It must **not** become a new item in the painter's sort — that is the failure `PlayScreen`'s four-pass KDoc records. **Reinstating a global shadow pass is not available**; it was removed for a documented reason.

Do the carried card first: it sits at `cardHeight * LIFT_Z`, well clear of everything under it, so the near-coplanar broadphase blowup does not apply and there is exactly one occluder.

The other reachable union is the **fan**: up to sixty coplanar seats at the same pose, so all their shadows are the same quad translated by the same vector and their union is one path per contiguous run — solved in `PileFan` (core, testable) and drawn once. That is where `1−(1−A)² = 0.88` actually appears on screen today.

Clip in core, not with `canvas.clipPath` — a non-rectangular clip inside a `graphicsLayer` with a perspective `cameraDistance` forces a mask allocation on both backends.

**Test:** clipping two convex quads yields a convex polygon of at most 8 vertices whose area matches independent half-plane sampling to 1e-3, and never emits a 0/1/2-vertex spur. A card one pixel above another produces a clipped shadow within 1% of the two cards' overlap, not a table-wide quad — the claim that says the broadphase tests the clipped extent.

---

## Asks

Each of these needs one sentence from kai. Several gate work above.

### A1. May the stage have a second Canvas, above the mat's `graphicsLayer`?

The stated rule is one graphicsLayer and one Canvas. A screen-space sibling Canvas — after the mat Box closes and before `MatInput`, which draws nothing — is what makes three things possible at full quality: **dither at 1:1** (a blue-noise tile resampled by a perspective transform loses most of its blueness; an ordered tile loses all of it), a **true circular vignette** (drawn in the mat layer it becomes an ellipse whose centre walks off the optical axis as the table yaws), and **anything that must cover the cards as well as the felt**. It costs one full-screen alpha-blended fill, 0.2–0.5 ms.

F4 ships the in-layer version, which is most of the value. This decides whether the better version is reachable. **Answer once; three items depend on it.**

### A2. AAA #99 — the shader seam

Correctly deferred and correctly marked [your call]. The version signal checks out against this build: `settings.gradle.kts` pins CMP 1.11.1, and in the resolved `ui-graphics-desktop-1.11.1.jar` `androidx.compose.ui.graphics.Shader` is a wrapper class over `org.jetbrains.skia.Shader` with `asComposeShader()` extensions — no longer a typealias, i.e. this project is pinned to the version the seam broke in.

What it buys **that nothing else on this list reaches**: honest bloom, real blur, and the two-tap emboss. It does **not** buy the foil streak — F1 gets most of AAA #21 without it. Three costs: minSdk 26 means the *fallback* is the shipping path for API 26–32; the shader text is unreachable from commonTest on either side, inverting the discipline `GoldenStageTest` establishes for every other lighting change, with divergence between the AGSL and SkSL twins as the failure mode; and it depends on internals that changed binary-incompatibly in exactly the pinned version.

If taken: everything arithmetic stays in core (a `Foil` object owning axis, hue mapping and strength) so the shader only evaluates a function commonTest already pins; `expect object GpuShaders { val available: Boolean; fun foil(...): Brush? }` returns **null** rather than throwing; the existing gradient stays as the `?:` fallback.

### A3. Does a pile become bodies? — AAA #35, #40, #42

DESIGN.md §10 currently argues the **opposite** position deliberately: "Height is notation, and `sin(tilt)` is its exchange rate." A stack is `PlacedCard.beneath` plus a saturating `pileDepth` curve; nothing rests on anything, there is no penetration to resolve and no contact to solve. So a soft-constraint solver, speculative contacts, Coulomb friction with a Stribeck break, and flat-plate aerodynamics all have **nothing to attach to** — and I would not build any of them until this is answered. This is a position to overturn on the record, not an omission to fill in.

Two pieces are reachable regardless and are worth taking now, unconsumed: **friction as a material property** (AAA #39 — `CardMaterial` gains a `staticFriction`/`kineticFriction` pair and `Friction.combine(a,b) = sqrt(μa·μb)`; `CardStock.of` already returns `Sleeve` for any face-down card, so sleeve-on-sleeve at ratio 1.7 and gloss-on-gloss at 1.25 fall out with no new dispatch, and AAA #73 already says "the material system already knows which two are meeting, it just is not being asked"), and **implicit angular drag** (`v / (1 + dt·(cLin + cQuad·|v|))`, unconditionally stable, one divide — but it must **never** touch `SpringValue`, `PoseMotion` or `StageCard.sweep`: the implicit form always removes energy, and `Bouncy` is *supposed* to overshoot).

### A4. Batch the golden re-records, and say which

F1 (foil `spec` lines), N4 (`dark` for every object at three seats), N5 (every `spec` line), and per-corner shadow spread (reformats the `shadow` row) all force one. AAA #21b's argument is the model: *"re-recording the golden in the same release that gave light a position is how you lose the ability to say which change moved it."* Either each gets its own release, or kai says which to batch. Do not spend a re-record on a small item alone.

### A5. Every card's cut edge gets roughly four times brighter

N1's consequence, stated separately because it is a taste call and not a maths one. The `0.09f` rim floor currently delivers 0.854% of white's light; converting it to a light fraction without re-choosing the constant spends the Swiss restraint. It is cheap to judge — `GoldenStageTest` records no `Color` and no alpha at all, so every one of these constants can be re-picked without touching the recording. The same is true of the two `sheen` alphas, whose KDoc already says out loud that they are mis-tuned and waiting for a tablet pass.

### A6. Honest Kelvin makes the night lampshade go white — AAA #14

A Planckian-locus fit replacing `Lit.TEMPERATURE = 0.16f` is cheaper than what ships (three multiplies against a cached triple, versus three `abs`/`max` expressions) and warmth 0 comes out bit-exactly (1,1,1). Three consequences to accept in one release: (1) it **depends on N1's soft clip**, because von Kries division pushes channels above 1 by construction and `Tone.shade`'s `unit(light)` would silently clip them — ship in that order or the balance is a no-op on the brightest channel; (2) **emissives** — `Scenery.paneLight` and `shadeLight` go through the same path, so balanced to the key the night lampshade comes back neutral white, which is physically correct (the lamp *is* the reference white) and a visible change to the brightest object in frame after a card, per DESIGN.md §11; (3) `ShadingTest.kt:418-427` bounds every channel to `(1 − 2·TEMPERATURE, 1]` and honest values blow through it — rewriting that bound is the first argument this change has to win.

The golden does **not** move if `warmth` keeps its meaning; it records `amount/warmth`, never `red/green/blue`. Ban Tanner Helland's Kelvin→RGB in a KDoc: it outputs sRGB-*encoded* 8-bit values, so dropping it in is the encoded-multiply bug wearing a physics hat.

### A7. AAA #61d — nothing in the room casts a shadow

Already written as a decision to revisit *as a set*, with the lamp, rather than bolted onto one prop. Unchanged; listed so the loop does not add one shadow to one object.

### A8. AAA #33 — edge wear

Already [your call] and leaning no. Two further reasons it should stay no: the hairline it would replace is a `drawRoundRect(style = Stroke)` at a 4dp radius that DESIGN.md fixes twice (§4, §8), so varying alpha round it means 12 short **arcs** over the existing stroke, not 12 `drawLine`s. The sub-JND per-instance scalar jitter (`CardStock.worn`, deterministic from the passcode, three integer multiplies at card-load and zero per frame) is a **different thing** and is not what #33 refuses — it can ship without this decision.

### A9. `drawVertices`, `BlurEffect`, and minSdk 26

Three separate API-31/29 floors against minSdk 26, all with **silent** failure modes rather than degradation. The mesh ribbon (a genuinely better penumbra than any ring stack, and the correct-by-construction version of F3's offset) needs an `expect fun supportsMeshShadows()` beside the four seams `ui/fx/` already carries, plus the ring stack as fallback — and CI never builds on API 27, so the gate cannot be proved by the build. `BlurEffect` for the out-of-focus room (AAA #62's unfinished half, #68) is API 31+, and the steady state it is priced against does not exist here: the room is only redrawn on frames the camera is orbiting, which is exactly when a re-record cannot be skipped. The cheaper thing that is not blocked: the room is ten flat-shaded boxes with no texture, so "out of focus" on them is a soft silhouette edge — the same ramp F2 wants for shadows, with no layer and no API floor.

---

## Dropped

Already shipped, or measured to be worthless, or structurally impossible here. Recorded so the loop does not rediscover them.

| | Why |
|---|---|
| Per-card perspective quad via `Canvas.concat` | **Done**, to the detail — Heckbert closed form about the quad's centre, affine short-circuit, relative degeneracy guard, caller-owned `FloatArray(16)`, pinned by two tests |
| Per-point depth from the homography denominator | **Mechanism is wrong.** That bottom row is the card→mat residual map; the perspective divide lives downstream in the mat's `graphicsLayer`. The thing itself is already available and already computed: `StagePlane.project(point).depth` |
| Sleep / promotion / pile-as-one-body | **Done by architecture**, not as an optimisation. Only residual is F8 |
| Homography area gate, precision recentring, the atlas trap | **Done** (`MIN_DRAWN_AREA = 2f` with `flatArea`; `squareToQuad` solves about the quad's own centre; faces are individual Coil bitmaps, not an atlas) |
| Analytic penumbra widening and the straddle | **Done** — `angle = light.angularRadius(centre)`, `soft = max(reference·SOFT_AT_REST, angle·rays.average())`, and `umbra` is the unfloored form. AAA #17's shipped half |
| Soft-constraint contact solver, speculative contacts, plate aerodynamics | **No contacts and nothing falls.** Every card is spring-driven to a target; `MatEvent.Dropped`'s velocity is discarded. See A3 |
| Disc-light sampling on the Vogel spiral | No-op in two of three rigs — `Light.travelFrom` returns the stored travel verbatim when `position == null`, so all N samples coincide under `StageRig.Key` and `DeskDay`. Needs a layer per card at sixty cards. AAA #17 already says so |
| Charlie sheen on the felt | **Measured invisible.** N·L is 0.84 under `StageRig.Key` and 0.74 under `DeskDay` — nowhere near grazing. Under one level of 255 in two of three rooms, and there is a name collision with the existing `StageRig.sheen` |
| Roughness→exponent temporal ceiling | Does nothing at the shipped exponents (10/26/44 against a 60 Hz ceiling of 81). Revisit only with N5's successors |
| Polygon irradiance at runtime | Use it as the **commonTest oracle** for N4's contact closed form and nowhere else — bilinear interpolation of a band 1.15·h wide across a 104 px card is a fog, not a seam |
| Lateral chromatic aberration on the rim | The most disposable item surveyed, and it spends AAA #89's guardrail for a ≤2 px effect. If DoF ever lands, tint the near blur magenta and the far green instead — one line, no field-height term |
| Stochastic rounding of per-object 8-bit quantities | Superseded by F4, which is cheaper and covers the same banding. The pool stops would sparkle on a motionless table anyway — recomputed from `camera.eye` every frame — which is the one thing DESIGN.md forbids |
| A 256-entry `toLinear` table | Cannot be a fast path inside `toLinear` — `Tone.veil` calls it with `MID_TONE = 0.5f`, and `128/255 = 0.50196` shifts the reference channel and fails `theVeilIsExactAtItsOwnChannelAndWithinTwoLevelsEverywhereElse`. The cheaper win is upstream: cache a linear triple per compile-time constant `Color`, which deletes most of the per-frame `pow` budget outright. And the saving is Android-only — desktop `Math.pow` is a HotSpot intrinsic |
| `drawVertices` for anything | `Vertices` re-encodes `List`s to primitive arrays, and it is a **silent** no-op below API 29 against minSdk 26. Fine for the felt, never for the cards |
| Reinhard / ACES / Khronos PBR Neutral; gamma 2.2; the best pure power; Unity's cubic; Tanner Helland Kelvin→RGB; Filament's clear-coat base remap; `frac(sin(dot(...)))` hashing | Not work. **Refusals to write down** in the KDocs named in N1, A6 and F1, each with the number that decides it at the code values this stage actually lives in |
| Animated grain / temporally advancing dither | Banned outright by AAA #60 and DESIGN.md §11. A static tile is a correctness fix; a moving one is an engine with nothing to say. The "advance while the camera moves" escape hatch is defensible and belongs in the handbook first, not smuggled in with the dither |
