> **Classic.** This document is about the tablet app and its play stage, retired when Neue Master Tool became the app. The code it describes is at commit `c2fc8d8` — the tag `neue-v1.0.19` — and in git history. Kept for play mode's rebuild inside Neue.

# The road to a photographic card table

<!-- A staged plan, written after the shader seam shipped (LOOP.md iteration 5,
     AAA.md #99) and after kai moved the target to driving-simulator fidelity.
     It supersedes parts of docs/classic/FIDELITY.md; §"What this does to FIDELITY.md"
     says exactly which. AAA.md numbering stays authoritative. -->

Nine stages. Each ends in a signed release that a person can name the difference in. Every stage says what you would see, what it costs a frame, what Android 26–32 gets, and the specific way it could come out worse than what ships today.

Read §0 first even if you skip to a stage — three of the assumptions everything downstream rests on are wrong in the obvious way, and one shipped defect is already on the tablet.

---

## The two numbers that decide the whole plan

Measured against the Tab S11's own metrics — 2960×1848, 120 Hz, 8.33 ms — and the reference stage `tools/shoot.sh` shoots at, 1600×1000.

**ALU is free. Render passes are not.**

| | pixels | at 120 Hz |
|---|---|---|
| Whole frame | 5.47 Mpx | — |
| Sixty cards (192×280 device px each) | 3.23 Mpx, 59% of the frame | — |
| 100 ALU over the whole frame | 0.55 GFLOP/frame | 66 GFLOP/s, ~4% of an Immortalis-class GPU |
| One full-screen RGBA8 offscreen | 21.9 MB write + 21.9 MB read | 5.25 GB/s, ~8% of ~68 GB/s LPDDR5X |
| **120 per-face card layers** (`CardFace` composes both faces, always) | 25.8 MB each way | 6.2 GB/s **plus 120 tile flush/resolve pairs** |

So: a two-hundred-instruction BRDF on every card costs about 8% of one core's worth of the GPU and nobody will ever see it in a frame graph. One full-screen finishing pass costs a twelfth of the memory bus and is worth it. **A `renderEffect` on each `CardFace` is the one shape of change that kills this stage**, and it is exactly the shape the obvious implementation of stage 4 takes. Stages 4 and 7 are built around avoiding it.

**And the studio cannot price shader work.** LOOP.md iteration 5 records the felt weave taking `tools/shoot.sh --budget` from 53.6 ms to 165 ms — +111 ms for a ~40-ALU shader over the mat, because `:studio` has no GPU and runs every SkSL pixel on the CPU while a device skips the felt entirely when the camera is still. Treat **+111 ms per 40 ALU over ~0.7 Mpx** as the studio's local calibration constant, use the ratio only to catch order-of-magnitude mistakes, and get `FrameProbe` numbers off the tablet (three taps on the life-point total, AAA #96) before any stage from 3 onward enters a signed build. LOOP.md §4's affordability gate has no honest local answer for this class of change and that should be written into §4.

---

## Stage 0 — The seam, widened, and three things nobody has proved

**Nothing visible. Say so in the release notes, or ship it inside stage 1.**

### First, correct the record

Three premises that keep reappearing in planning are false:

- **`:ui` already has both platform source sets.** `app/ui/src/androidMain/` and `app/ui/src/desktopMain/` each carry five actuals: `ui/fx/{Display,Feedback,Haptics,Hour}` and `ui/gpu/StageShader`. `app/ui/build.gradle.kts` declares only `commonMain.dependencies` and gets the rest from the default KMP hierarchy template. **Adding an actual needs no build-file change** on either side, and no change to `settings.gradle.kts`.
- **A runtime shader is already shipping.** `ui/play/FeltWeave.kt` compiles plain-weave SkSL once per screen (`remember { FeltWeave.compile() }`, PlayScreen.kt:582) and `drawFelt` composites it in `BlendMode.Overlay`. AAA #99 is Done, DESIGN.md §6 carries the seam and its four rules.
- **A card's shader gets card-local coordinates for free.** `drawCardSurface` runs inside `CardFace`'s `drawBehind`, which is inside `StagedCard`'s `withTransform({ transform(Matrix(values)) }) { drawContent() }` (PlayScreen.kt:1432). Skia composes the local matrix with the CTM and inverts the total per pixel, and `Homography.writeComposeMatrix` fills the perspective row (indices 3, 7, 15). So `main(float2 p)` receives the card's own rectangle, perspective-correctly, with no inverse homography and no divide of your own. **This is unproved and is spike (a) below** — the projective path is far less travelled than the affine one, and a divide that silently degrades to affine is invisible overhead and obvious seated.

### What the seam cannot do today

`ShaderUniforms` is `float`/`float2`/`float3`/`float4`. No children, no colour uniform, no matrix, no `RenderEffect`. Four additions, and the two actuals diverge in ways worth writing into the KDoc:

```kotlin
// added to ShaderUniforms
fun image(name: String, bitmap: ImageBitmap, repeat: Boolean = false, raw: Boolean = false)
fun colour(name: String, colour: Color)
fun matrix3(name: String, columnMajor: FloatArray)   // 9 floats
```

| | Android (API 33 gate already in place) | Desktop (Skiko) |
|---|---|---|
| `image` | `shader.setInputShader(n, BitmapShader(bmp.asAndroidBitmap(), tx, ty).apply { setFilterMode(FILTER_MODE_LINEAR) })`; `setInputBuffer` when `raw`. **`FILTER_MODE_DEFAULT` on an input shader is NEAREST and ignores `Paint#isFilterBitmap`** — miss this and every probe is blocky. `maxAnisotropy` is API 34, inside the shader's own floor. | `builder.child(n, Image.makeFromBitmap(bmp.asSkiaBitmap()).makeShader(tile, tile, FilterMipmap(LINEAR, LINEAR), null))`. **No `setInputBuffer` equivalent**, so every data map ships alpha = 255 and sRGB-tagged, making premultiply and the transform both the identity. |
| `colour` | `setColorUniform(n, argb)` — applies the destination colour transform | no equivalent; the actual computes unpremultiplied sRGB floats itself |
| `matrix3` | ~~`setFloatUniforms(n, FloatArray)` — the **plural** method~~ — **that method does not exist.** Checked against `platforms/android-36/android.jar`: `RuntimeShader` has `setFloatUniform(String, float[])` and no plural at all. The scalar overloads do cap at four, so the array overload is the only way nine floats get in. | ~~`builder.uniform(n, Matrix33(...))`~~ — **use `builder.uniform(n, FloatArray)` instead.** `Matrix33` is row-major and an SkSL `mat3` is column-major, so going through it asserts which side transposes; the wrong answer compiles, runs, and is silently transposed. The flat array makes both platforms agree by construction. |

Children are sampled in **bitmap pixel** coordinates on both. Cap children at 4 — ES2 guarantees 8 fragment texture units and half of them are already spoken for.

A second, separate seam for anything that must read what is underneath it — bloom, the finishing pass, tone-enveloped grain:

```kotlin
expect class StageEffect
expect fun compileStageEffect(sksl: String): StageEffect?
expect fun StageEffect.effect(uniforms: ShaderUniforms.() -> Unit): RenderEffect?
```
Android: `RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()`, same API 33 gate. Desktop: `ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()`. **This is where the AGSL/SkSL twins are most likely to diverge**: Android's takes one input plus whatever `setInputShader` binds, desktop's takes several named children in one call, and `eval()` expects RenderNode-local coordinates on Android and device space on Skia. Pass `uRes` explicitly; never infer it.

And one boolean, because LOOP.md iteration 5's bug — a shader emitting overlay's identity everywhere is indistinguishable from one that failed to load — was diagnosed only by a one-line `compile() != null` probe:

```kotlin
expect val runtimeShadersAvailable: Boolean
```

### Core arithmetic, all pure, all commonTest

Three functions that four later stages are otherwise guessing at:

```kotlin
fun StagePlane.jacobian(x: Float, y: Float): Mat2   // FIDELITY N3, exact not fitted
fun Homography.jacobianScaleAt(x: Float, y: Float): Float   // = sqrt(|det J|), AAA #97
fun Homography.inverse(): Homography?               // adjugate/det, null on singular
```

`StagePlane.project` is closed form at z = 0 in the `sinTilt`/`cosTilt`/`sinYaw`/`cosYaw` privates the class already caches (StagePlane.kt:88–117), so the Jacobian is exact. With `u = x − centreX`, `v = y − centreY`, `L = zoom·(v·cosYaw − u·sinYaw)`, `M = zoom·(u·cosYaw + v·sinYaw)`, `D = d − sinTilt·L`:

```
sx − cx = d·M/D                     sy − cy = d·cosTilt·L/D
M_u = zoom·cosYaw   M_v = zoom·sinYaw
L_u = −zoom·sinYaw  L_v = zoom·cosYaw
D_u = −sinTilt·L_u  D_v = −sinTilt·L_v

J = (d/D²) · [[ M_u·D − M·D_u ,           M_v·D − M·D_v ],
              [ cosTilt·(L_u·D − L·D_u) , cosTilt·(L_v·D − L·D_v) ]]
```

14 mads and one reciprocal. Return the full 2×2, not a scalar — the anisotropy is the condition number `1/(cosTilt·scale)`, which is 0.88 near / 1.21 far at the shipped 21° but **1.34 near / 2.44 far at the envelope's 58° ceiling** (`CameraEnvelope(minPitch = 4f, maxPitch = 58f)`). A scalar is wrong by 2.4× exactly where it matters, and warp and weft must fade at different rates.

`Homography.inverse` is only needed if a card ever stops being a transformed subtree; build it anyway, because it is eleven lines and spike (a) may go the other way.

### The three spikes, in `:studio`

`app/studio/src/jvmMain/.../ShaderSpike.kt` and `./gradlew -Pmastertool.studio=true :studio:spikeShader` already exist and already proved that SkSL rasters with `skiko.renderApi=SOFTWARE` and no GL context. Extend it:

- **(a) Does a `ShaderBrush` survive a projective CTM?** Draw a 1 px checker under a known projective matrix; confirm the vanishing point lands where `StagePlane.project` puts it. `FeltWeave` has never done this — it draws inside the mat's `graphicsLayer` where the canvas is flat and the projection happens to the layer afterwards.
- **(b) Does `ImageFilter.makeRuntimeShader` / `createRuntimeShaderEffect` work on both?** Proven for `ShaderBrush`, unproven for `RenderEffect`. Stages 7 and 8 are blocked on this.
- **(c) Does a child image shader honour LINEAR sampling when evaluated at a computed coordinate inside a runtime effect?** Undocumented on both backends. If it silently drops to base-level nearest you get no error, just aliasing.

### The parity harness, and the discipline it replaces

FIDELITY A2 named this as one of three real costs of taking the seam, and it is now a shipped cost: **the shader text is unreachable from `commonTest` on either side**, inverting the discipline `GoldenStageTest` establishes for every other lighting change. `:ui` has no test source set at all.

The mitigation is structural, and it must exist *before* the first shader that carries real lighting:

1. Every shading function has a Kotlin definition in `:core` with a `commonTest` naming a claim, **and** an `sksl()` generator that emits the same expression from the same named constants. Transcription drift becomes a diff a person can read.
2. A second `:studio` task shoots the same seeded deal twice, once with the shader forced null, and diffs with `tools/compare.py`. That is stronger than a grid-of-normals unit test, it is the loop's own instrument, and it means the fallback is a picture somebody looks at every iteration rather than a branch nobody runs.

**Add a fifth rule to DESIGN.md §6:** *pick `half` everywhere or `float` everywhere in one shader string.* `half` is 16-bit on a Mali and 32-bit on desktop GL core (SkSL emits precision modifiers only when `usesPrecisionModifiers()` is true, which desktop is not), so a mixed-precision shader gives the loop's own eye a different picture from the tablet — the exact failure `tools/compare.py` exists to make impossible.

**What could make it worse:** nothing renders differently. The risk is silent: a widened seam cannot be compile-checked locally (`settings.gradle.kts` auto-skips `:ui` without Google Maven), so grep the CI output for `BUILD SUCCESSFUL` and never trust a piped exit code.

**Android 26–32:** unchanged. `compileStageShader` already returns null below 33; every new member sits inside that floor.

---

## Stage 1 — "The felt stops crawling and the deck stops looking corrugated"

Two of the four defects LOOP.md §6 has already seen, and one of them is **shipped and broken right now**.

### The live defect

`FeltWeave.brushFor` sets `uPitch = cardWidth / 34`. At the reference 104 px card that is 3.06 mat px per thread, or 0.327 cycles per mat pixel. Screen density is `cosTilt · scale²` along y. At the shipped 21° with a ~430 mat-px half-span:

| seat | near y-density | far y-density | thread freq far |
|---|---|---|---|
| Table (21°) | 1.17 | 0.76 | 0.43 c/px |
| Envelope ceiling (58°) | 0.95 | 0.34 | **0.97 c/px — twice Nyquist** |

So the far half of the mat aliases at low seats today, and it crawls as the camera orbits, which breaks *"nothing idles"* by accident. FIDELITY N7 flagged this as "blocked on N3" and the weave landed anyway. **Fix it before the next release regardless of what else ships.**

### What lands

Replace `FeltWeave`'s four `cloth()` calls at `e = 0.30` (≈6 transcendentals for a slope it could have in closed form) with an analytic derivative and a band-limit:

```glsl
uniform float2 uOrigin;
uniform float  uPitch;    // mat px per thread
uniform float3 uLight;    // toward the lamp, mat frame, unit — negated at the call site
uniform float  uDepth;
uniform float4 uJ;        // [dsx/dx, dsx/dy, dsy/dx, dsy/dy] at this surface, from StagePlane.jacobian

const float TAU = 6.2831853;

// height and both partials, in thread units, no derivative builtins
float3 weave(float2 q) {
    float2 c   = floor(q);
    float2 f   = fract(q) - 0.5;
    float  par = mod(c.x + c.y, 2.0);
    float2 s   = cos(TAU * f) * 0.5 + 0.5;
    float2 d   = -TAU * 0.5 * sin(TAU * f);
    return float3(mix(s.y, s.x, par),
                  mix(0.0, d.x, par),
                  mix(d.y, 0.0, par));
}

half4 main(float2 p) {
    float2 uv = (p - uOrigin) / uPitch;
    float3 hg = weave(uv);

    // Nyquist, per axis, from the plane's own Jacobian
    float2 pxPerCycle = float2(length(uJ.xz), length(uJ.yw)) * uPitch;
    float  cyc  = max(1.0 / pxPerCycle.x, 1.0 / pxPerCycle.y);
    float  fade = 1.0 - smoothstep(0.30, 0.50, cyc);

    float3 n = normalize(float3(-hg.y * fade, -hg.z * fade, 1.0));
    float3 l = normalize(uLight);
    float  delta = (max(dot(n, l), 0.0) - max(l.z, 0.0)) * uDepth;

    // the energy the fade removed, returned as a broadened lobe rather than lost
    float3 hv = normalize(l + float3(0.0, 0.0, 1.0));
    float  sharp = 1.0 - fade * fade;
    delta += pow(max(dot(n, hv), 0.0), mix(60.0, 8.0, sharp)) * 0.22 * (1.0 - 0.5 * sharp);

    float g = clamp(0.5 + delta, 0.0, 1.0);
    return half4(half3(g), 1.0);
}
```

~35 ALU against ~60, and it returns a slope, which nothing else on the list can get without it.

Move `FELT_ROUGHNESS = 0.36f` out of `StageRender.kt:656` into core beside `StageRig.sheenRadius` — its own KDoc says it was "solved rather than chosen", i.e. a surface property wearing a renderer's clothes — and give `sheenRadius` a trailing `weave: Float = 0f` that widens Pythagoreanly, `sqrt(r² + s²)`. Default 0f is bit-identical, the same trailing-inert-default discipline `CardSolid.slab`'s `backScale: Float = 1f` and `Light.position == null` already keep.

### And the pile's ruled side

LOOP.md §6, first entry: *"a 35-card deck is drawn as about seven thick slabs… the biggest object on the table and the most obviously wrong thing left in the frame."* The cause is `CardSolid.layerLines(count, edgePixels)` capping at `edgePixels / LAYER_MIN_SPACING` with `LAYER_MIN_SPACING = 1.7f`, so a 35-card deck with a ~12 px side gets 7 `drawLine`s at strokeWidth 1.

A shader draws the ruling continuously at sub-pixel spacing and fades **contrast with density instead of dropping lines**. The side quad is drawn in the mat's frame, so `main` receives mat pixels and needs `Homography.inverse` to recover (u, v) — three `float3` uniforms, 11 ALU:

```glsl
float3 q = uM0 * p.x + uM1 * p.y + uM2;
float2 uv = q.xy / q.z;
```

The face's corner order is documented and load-bearing: front, front, back, back, so 0→3 and 1→2 run down through the body — that is v, the stacking axis.

Write the property claim before the code: *the ruling's spacing tracks the count until the count is unreadable, then the contrast falls rather than the lines disappearing.*

**What you would see:** the mat holds still while the camera orbits, and the deck reads as a stack of thin cards rather than as seven slabs of corrugated board.

**Cost:** the weave is a wash (fewer transcendentals, one more uniform). The pile side is one `drawPath` per pile replacing 7 `drawLine`s — cheaper. `StagePlane.jacobian` is 15 float ops per surface per frame.

**Android 26–32:** the flat/pooled mat that shipped before v1.2.31, unchanged, and the existing `layerLines` integer ruling. This is the one stage where the fallback genuinely loses nothing it was ever going to have — there is no high frequency to alias.

**What could make it worse:** setting the fade knee at 0.5 instead of 0.30/0.50 leaves a visible ring across the mat where the fade completes. And if the ruling shader gets its `v` axis from the wrong corner pair the deck's side is ruled *across* rather than *through*, which is a worse picture than seven slabs.

---

## Stage 2 — "The light lands where it says it does"

Entirely Kotlin. No shader, no seam, no new asset. This is the stage that makes every later one worth tuning, and skipping it means tuning a per-pixel BRDF against constants that are about to move ninefold.

Four FIDELITY items, one release, **one golden re-record** — which answers A4.

- **N1**, the half that is missing: `StageRig.lit` returns ambient + lambert + graze with **no specular term at all**. Give `StageRig` a `shade(face, eye, lighting): Shade` beside `face`, reusing the existing `Shade` type, and replace `Color.shaded(lit)` (SceneRender.kt:301) with `Color.lit(shade)` computing `toSrgb(toLinear(albedo)·diffuse + lampLinear·specular)` per channel, drawn opaque. Then `Tone.gloss(add, over = MID_TONE)` as the additive twin of `Tone.veil`.
- **N5**, one commit: hoist `eye.normalised()`, add `loh` and 5th-power Schlick, multiply the specular by `F/f0` and the diffuse by `(1−F)` **before** the `coerceIn` (because `drawCardSurface` calls `Tone.veil(shade.diffuse)` and nothing else reads it). `CardMaterial.rim` (0.5 / 0.75 / 0.35 — a slider with no referent) becomes `f0` (0.0400 / 0.0387 / 0.0400), a rename plus new values. Delete `StageRender.kt`'s `0.09f` rim floor in the same commit or the card gets a double edge.
- **F1**, the foil streak, which FIDELITY A2 correctly says the shader does **not** buy: Ward anisotropy into the slot `alignment.pow(material.shininess)` occupies, with no new vectors (`Rot3.right(pose)·side` and `Rot3.down(pose)` are already computed four lines below for the hotspot — hoist the block). The per-frame `Brush.radialGradient` becomes one hoisted unit brush under `withTransform { translate(hotspot); rotate(grooveDegrees); scale(aspect, 1f) }`. Cap the on-screen aspect near 6:1; an 18:1 streak is longer than a 104 px card.
- **F9**, the allocation ledger, because everything from here on spends it.

Also land **exposure and the gated soft clip together or neither**: `StageLighting` gains `exposure: Float = 1f` applied to the summed directional terms *before* `key.ambient` so `NIGHT_FLOOR` stays a guarantee, and the terminal `coerceIn(0f, 1f)` becomes `if (b + s <= 1f) b + s else soft(b, s)` — bit-identical today, and it stops being invisible the moment stage 4 composes a specular in linear. Ship the value at 1.0 in commit one with a literal early return, prove the golden stays green, then move it.

Two traps that are already written down and will be walked into anyway:

- **Exposure applies in two places, not one.** `StageRig.lit` shades solids, the room and the felt pool; `Shading.of` computes its own lambert from scratch for card faces and never calls it. Exposure on the rig alone makes every card face disagree with its own ruled edge, which is the single failure the one-rig rule exists to prevent.
- **Do not follow exposure down into the ambient.** `Tone.veil`'s KDoc is an explicit warning: the single-black-overlay approximation is 1.19× too bright at diffuse 0.72, 1.44× at 0.5, 1.87× at 0.3. `NIGHT_FLOOR = 0.55` cannot fall until stage 4 kills the veil.

One discrepancy to settle deliberately before anybody pastes the standard expression: **the shipped specular is *gated* on `lambert > 0f`, never *multiplied* by it** (Shading.kt:353). Adding `· NoL` is a further dimming of every highlight on the table.

**What you would see:** every faint white arrives at the brightness it was tuned for. The headline is arithmetic: `rim = 0.09f + …` over true black puts **0.853%** of white's light on screen, not 9% — `toLinear(0.09) = ((0.09+0.055)/1.055)^2.4 = 0.00853`. Every card's cut edge is currently nine times dimmer than its constant claims. And extra-deck cards get a streak instead of a round pool.

**Cost:** negative. F9 removes hundreds of `Path` and `Brush` allocations per frame; F1's ellipse-as-transform removes one gradient per card.

**Android 26–32:** identical. All of it.

**What could make it worse — and this is a real risk, flagged as FIDELITY A5.** Converting the rim to a light fraction without re-choosing the constant makes **every card's outline about four times brighter**. That spends the Swiss restraint. It is cheap to judge (`GoldenStageTest` records no `Color` and no alpha) but it must be judged on the tablet, not in the studio. Same for the two `sheen` alphas, whose KDoc already says they are mis-tuned and waiting. Batch the taste call with the re-record.

---

## Stage 3 — "Cards sit on the table"

The third of LOOP.md §6's defects: *"a shadow on true black has nowhere to go — the hand's cast shadows are drawn and correct and invisible."*

### The arithmetic split, first, in its own commit

Today the ring shadow darkens against `Shadows.DARKEST = 0.66`, and `Light.ambient` (0.72 in Minimal, 0.55 at night) is **unoccluded everywhere**. That 72% being unoccluded is literally why cards float, and a perfect penumbra on a 0.66 black hole is a perfect hole.

FIDELITY N4's function, and it is a one-liner: `StageRig.shadowed(normal, eye, lighting, at)` is `lit` with the key's `direct` term dropped and everything else kept. Under the shipped rig — `StageRig.Key` normalised is (0.3003, 0.4504, −0.8408), direct = 0.23542, fill = 0.0780 on a flat mat — the true umbra alpha is **0.202 against 0.66**. `DARKEST` and `FADE_OVER` are deleted; `CardShadow.alpha` becomes coverage.

Then the rule that stops two terms fighting forever, stated once and tested: **the cast shadow occludes `key.intensity` only; contact occlusion occludes `key.ambient` only; their sum is the surface's full irradiance.** Get that explicit or you will tune two numbers against each other and ship a black halo.

### The penumbra, as one draw

`StageRender.drawCardShadow` currently does 2–5 `drawPath(Outset.of(flat, grow))` calls per card at `step = 1 − (1−A)^(1/rings)`, which puts a resting card's whole penumbra at alpha 0.4169 where linear coverage wants 0.33. Replace the loop with **one** draw and a four-half-plane convex SDF. Do not use `sdRoundBox` — the cast shadow is a general convex quad, sheared by the tilt and diverging under a point lamp, so an axis-aligned box is correct only for the case that needs it least. `Outset` already computes the edge normals; promote `Outset.normal(a, b, winding)` as `Outset.edges(corners)` and test it.

```glsl
uniform float4 uNx, uNy, uC;   // outward unit edge normals and offsets, 4 edges
uniform float  uPen;           // = CardShadow.spread
uniform float4 uCol;           // premultiplied shadowed-felt colour

const float INV_PI = 0.3183098862;

half4 main(float2 p) {
    float4 d4 = uNx * p.x + uNy * p.y - uC;
    float  d  = max(max(d4.x, d4.y), max(d4.z, d4.w));   // signed, convex

    // the exact disc-edge profile: V(-1)=1, V(0)=0.5, V(1)=0, V(t)+V(-t)=1
    float t = clamp(d / uPen, -1.0, 1.0);
    float a = (acos(t) - t * sqrt(max(0.0, 1.0 - t * t))) * INV_PI;
    return half4(uCol * a);
}
```

That is **FIDELITY F2 evaluated per pixel** rather than solved into five bands — same profile, one `acos`, ~14 ALU, and it deletes the ring loop. `pen = shadow.spread`, and keep `inset = min(shadow.umbra, Outset.inradius(flat) * 0.9f)` exactly as iteration 4 left it.

### Contact occlusion, analytically

For a receiver at z = 0 with n = +z and an occluder quad above it, the polygon form factor is closed form and needs no depth buffer and no ray march:

```glsl
float edgeTerm(float3 a, float3 b) {
    float3 c = cross(a, b);
    return acos(clamp(dot(a, b), -1.0, 1.0)) * c.z * inversesqrt(max(dot(c, c), 1e-8));
}
float quadOcclusion(float3 p, float3 q0, float3 q1, float3 q2, float3 q3) {
    float3 v0 = normalize(q0 - p), v1 = normalize(q1 - p);
    float3 v2 = normalize(q2 - p), v3 = normalize(q3 - p);
    float s = edgeTerm(v0,v1) + edgeTerm(v1,v2) + edgeTerm(v2,v3) + edgeTerm(v3,v0);
    return clamp(abs(s) * 0.15915494, 0.0, 1.0);   // 1/(2pi)
}
```

**The `clamp` inside `acos` is not optional** — it is where this goes NaN, and `Tone.unit`'s KDoc already records that a NaN is not light: it passes every clamp and draws nothing.

Use `max()` over the four nearest occluders, not `1 − Π(1 − occ_i)`. For a card table max is the better lie; the product over-darkens where two occluders overlap in solid angle.

**The four nearest occluders are already being sorted.** PlayScreen.kt:531's `ordered` `derivedStateOf` projects every seat and sorts by depth with a structural-equality policy. `Seat` gains the ids of the cards overlapping it from above, computed there. **FIDELITY N8 wants the identical list** for card-on-card shadows — build it once.

**There is no full-screen pass available for this**, and that is architecture rather than laziness: the mat's `Canvas` (PlayScreen.kt:744) is drawn before all sixty card composables, so an AO pass there could never darken felt under a near card and above a far one. It draws in `StagedCard`'s `drawWithContent`, immediately before `drawCardShadow`, where the CTM is still the identity and the draw scope *is* the mat's frame.

**What you would see:** a shadow becomes the felt, darker and a little cooler, instead of a near-black hole punched in a true-black stage; the feathered edge gets lighter smoothly instead of stepping; and there is a tight dark line where a card actually touches the table. Two overlapping cards stop reading as two stickers.

**Cost:** a 1.6× card rect at 192×280 device px is 86 kpx; 60 cards is 5.2 Mpx of AO, 95% of a frame — but 4 occluders × ~85 ALU is 340 ALU/px, 1.75 GFLOP/frame, 210 GFLOP/s at 120 Hz. That is real. Halve it twice: render the AO at half resolution, and **only when a card moves** — `StageCard.parked` and the frame loop's `moving` count (PlayScreen.kt:685) already know. On a still table it costs nothing, which is most frames.

**Android 26–32:** the most graceful degradation on the whole list, because the ring stack was already the right topology. `quadOcclusion` runs in Kotlin at 8 points around the card's outline and drives `Outset.of`'s existing loop with F2's `Shadows.bandAlphas()`. Soft contact darkening with the right shape and the right falloff, three to five more `drawPath` calls.

**What could make it worse:** double-darkening. If the cast shadow and the AO both occlude the ambient, every card grows a black halo — and it will look like the shadows got *stronger* rather than wrong, which is the hardest kind of bug to name. Ship `StageRig.shadowed` and its test one commit before the drawing. Second risk: the golden re-record. `GoldenStageTest` records `dark ${unit(shadow.alpha)}` for every object at three seats, and LOOP.md §3 forbids re-recording and changing behaviour in one release.

---

## Stage 4 — "A card is made of something"

The hero surface, and the stage with the two hardest engineering problems on the list.

### Kill the veil first (FIDELITY N2)

A card's art is a Coil 3 `AsyncImage` composable. There is no `drawImage` to hang a `ColorFilter` on, and `colorFilter` is a *composition* parameter while `shade.diffuse` changes every frame a card tilts. Swap for `rememberAsyncImagePainter` and draw inside a draw lambda via `with(painter) { draw(size, alpha, colorFilter) }`; the `SurfaceRaised` background and the name `Text` are known `Color`s and take `Color.shaded(...)` exactly.

This removes up to 120 `drawRoundRect`s per frame, lets `NIGHT_FLOOR` fall, and — the reason it is a hard prerequisite rather than an optimisation — **it is the only way a card face can be brightened.** Every tonemap, every exposure change and every environment term in stages 5–7 needs that.

Its own blocker, and it is the one item here that would ship as a visibly wrong Android build: Compose `ColorMatrix`'s fifth column has different units on Android (−255..255) and Skiko (−1..1), JetBrains/compose-multiplatform#3461. Check on the device before a signed build.

### Then the per-pixel material

**Do not put a `renderEffect` on `CardFace`.** Both faces are always composed so a flip never recomposes, so that is 120 offscreen layers and 120 tile flushes — see the budget table. Instead, replace only the specular block in `drawCardSurface` (StageRender.kt:489–539) with a shader `Brush`, still `BlendMode.Plus`, still inside the existing `drawBehind`. That gets the per-pixel highlight, the stock's grain breaking it up, Schlick, and clear coat — everything except the *diffuse* being per-pixel — for **zero layers**, and it composites additively so a bug cannot blank a card. It is also exactly the composite DESIGN.md §6 asks for.

Filament's fp16-safe forms, because these run on a Mali:

```glsl
const float PI_INV = 0.3183098862;

float D_GGX(float NoH, float a, float3 n, float3 h) {
    float3 NxH = cross(n, h);
    float  a2  = NoH * a;
    float  k   = a / (dot(NxH, NxH) + a2 * a2);
    return min(k * k * PI_INV, 32767.0);
}
float V_SmithFast(float NoV, float NoL, float a) {
    return 0.5 / mix(2.0 * NoL * NoV, NoL + NoV, a);
}
float pow5(float x) { float x2 = x * x; return x2 * x2 * x; }   // never pow(x, 5.0)
float3 F_Schlick(float3 f0, float u) { return f0 + (float3(1.0) - f0) * pow5(1.0 - u); }
float V_Kelemen(float LoH) { return 0.25 / max(LoH * LoH, 1e-4); }
```

`alpha = perceptualRoughness²`; clamp perceptual to [0.089, 1.0] anywhere `half` is used.

**Derive the roughness from the finish table, don't invent it.** `alpha = sqrt(2/(shininess+2))`, `pr = sqrt(alpha)` puts Gloss (s = 26) at 0.517, Foil (s = 44) at 0.457, Sleeve (s = 10) at 0.639. That derivation belongs in `CardMaterial`'s KDoc in the same register as `CardSolid.PILE_EXAGGERATION`, and it is also what sizes stage 5's probe: **no card on this table is a mirror, so five prefiltered levels is right and eight is waste.**

**Clear coat is what a sleeved card is** (AAA #26), and it is the one thing a gradient cannot do, because the coat has its own normal:

```glsl
float2 w  = uv * float2(1.7, 2.3) + uSleevePhase;
float2 g  = cos(6.2831853 * w) * uSleeveAmp;      // 0.010–0.026 → ±0.5–1.5° at 15–25 mm
float3 nc = normalize(float3(-g.x, -g.y, 1.0));
```
against a card-fibre amplitude of ±2–4° at 0.15–0.4 mm. **The sleeve is flatter and smoother than the card, and the contrast between those two scales is the entire read.** A matte sleeve is a *rough coat*, not a missing one.

`uSleevePhase` must come from the passcode, not from a random number — `CardStock.of`'s own KDoc already makes this argument for the finish ("a foil that lands on a different card every time you reopen the table is a particle effect wearing a material's clothes"), and `core/motion/Settle.kt` already established the hash-of-instance-id pattern for the same reason. A crease that moves when you reopen the table is a bug this codebase has already avoided once.

**Do not bake card-stock fibre as a texture.** At 104 px a card the fibre is entirely sub-pixel. It belongs in `CardMaterial.micro` through stage 1's Toksvig transfer with an early return at `micro <= 0f` — two floats and a `sqrt`, the only item on this list that reaches all sixty cards that cheaply.

**Do not take Filament's clear-coat base-f0 remap** — `(1−5√f0)²/(5−√f0)²` collapses to ~0.0017 — unless the probe (stage 5) is in the same release. With almost no base reflectance and no environment term the card face goes dead. It is currently on FIDELITY's refused list and the refusal has to be overturned on the record first.

**What you would see:** the highlight on a card stops being a soft circular gradient and becomes a shaped reflection that the paper grain breaks up; a sleeved card grows a hard streak inside a broad varnish sheen; matte sleeves read as *clear but dull* rather than blurry. Cards stop being sixty copies of one sprite. And the night desk can finally go dark.

**Cost:** ~200 ALU over 3.23 Mpx of card = 0.65 GFLOP/frame, 78 GFLOP/s at 120 Hz, ~5%. **Zero new render passes.** Desktop mints a new immutable `Shader` per `brush {}` call and its KDoc licenses that for "one surface per frame" — sixty is a different claim; hoist per *material* (there are three) rather than per card, which is legal because `StageLook` is `@Immutable`.

**Android 26–32:** N2's exact affine shade *does* reach them — it is a `ColorFilter`, not a shader — so 26–32 gets the correct diffuse, the retuned f0 and roughness, and N5's coupled Fresnel. It loses only the per-pixel view vector and the coat normal, and keeps `Shade.hotspot`/`Shade.specular` as its interface. That is a genuinely good picture, which is required rather than nice: the same `StageLook` feeds both.

**What could make it worse:** three ways, all of them real.
1. **`content.eval()` returns premultiplied colour and a card face is not opaque** — the outer Box is clipped to a 4 dp rounded rect. If any future version does read the composited face, unpremultiply before shading or the corners fringe. (The additive-Plus version sidesteps this entirely, which is most of why it is the version to ship.)
2. **Re-tune, don't port.** `shininess = 26` is nowhere near any perceptual roughness. Normalising by `(s+8)/(8π)` is ×1.353 Gloss, ×2.069 Foil, ×0.716 Sleeve, and against ambient 0.72 the foil **clips** unless the specular constants come down in the same change.
3. **Three lamps per pixel will make cards brighter than the CPU path**, and you cannot compensate by lowering the ambient until N2 has landed. Sequence N2 first, not after.

---

## Stage 5 — "The desk is wood and the mat is rubber"

The largest area on screen after the felt, and the cheapest per pixel.

`SceneRender.drawSolid` already has the insertion point *and* its guard: a conditional second `drawPath` behind `pool != null && emission == null && face.normal.z > 0.99f && abs(face.centre.z) < 0.5f`. The KDoc five lines above spells out why the guard is not decoration — a shape drawn in mat coordinates is correct only where `StagePlane.flatten` is the identity. **The desk top qualifies; the desk's sides, the wall and the floor do not.**

Wood grain, procedural, one sibling file to `FeltWeave` with the same shape:

```glsl
float ring = sign(s) * pow(abs(s), 0.45);        // s = sin(TAU * R * (v + warp))
```
The `pow(·, 0.45)` hard early/latewood boundary is the whole difference between oak and a gradient. Put the ring centre far outside the tile (`cx = −6·width`) so it reads flat-sawn rather than as a tree stump — FIDELITY F10 already says so.

**Grain direction falls out of the geometry rather than being typed.** The desk is a `SceneBox.standing(...)`, axis-aligned by construction ("nothing here shears, so the six normals are the six unit axes"), long axis +x — so `Tw = Vec3(1,0,0)`, which is what an anisotropic highlight needs, because a 2° error in the tangent moves the streak visibly. Keep the `max` in `sqrt(max(0.0, 1.0 − tdh*tdh))`: half-precision `tdh` can exceed 1 on Android and `sqrt` of a negative is a single-pixel dropout you will chase for an hour.

Two supporting pieces:

- **Hex-tiling (Mikkelsen 2022)** if a baked tile is ever used on the desk. `DESK_SPAN = 1.6f` of the stage width plus `FLOOR_MARGIN = 8.0f` card widths means the desk deliberately runs out of both sides, and a repeated knot across a large flat surface is spotted faster than a missing normal map. The paper's `SampleGrad` is unavailable in AGSL and unnecessary — the per-hex transform is piecewise constant. **Do not wrap the coordinate in `fract()`**; let `TileMode.Repeated` on the child do it, or you plant a derivative discontinuity mid-tile.
- **The felt moves wholly into the shader here**, not in stage 1. That fixes FIDELITY N1's first blocker — the diffuse pool is centred on the lamp's *foot* and the sheen on its *mirror image*, and two non-concentric radial falloffs are not one Compose `Brush`; they coincide only at the overhead seat. But this is a shader that *replaces* a surface, which DESIGN.md §6 says "has to earn that separately". **Amend the handbook in its own commit first** (LOOP.md gate 4).

**AAA #61c, the cut daylight patch, is reachable here and only here — and it is not a shader change.** The patch was drawn as a multiply, which is correct, but `DeskDay.key` already lights the whole desk from the window's direction, so the surface is at full brightness before the patch goes on and every ring came out darker than the wood. *A stain, not light.* The fix is in `StageLighting`: split `DeskDay.key` into a **sky** (broad, ambient-bearing, `radius = cardWidth·12.9`, `distance = cardWidth·34` as now) and a **sun** (`Light.at` through the window aperture `Scenery` already computes and `Shadows.landOn` already projects). Base is `albedo·(ambient + sky·ndl)`, patch is `+ sun·ndl·mask`, additive, so it cannot come out darker than the wood. That changes the brightness of the entire day room, so it is its own release and it wants the tablet — AAA #61c says both.

**What you would see:** the desk stops reading as a flat brown fill and reads as a sawn plank with a highlight that runs along the grain, and the mat stops being a colour with a pattern over it.

**Cost:** ~50 ALU over the desk's ~1.5 Mpx = 0.075 GFLOP/frame, ~1%.

**Android 26–32:** F10 verbatim, and it is a good fallback here specifically because the desk at `0xFF2E2419` (b ≈ 0.10–0.18) is the one surface bright enough for a multiplicative detail pass — `BlendMode.Overlay` is exactly 2× modulate there and preserves the mean. On the felt at ≈0.02 linear it moves the output by a tenth of one code, which is why F4 exists instead. `ImageShader` + `Overlay` are portable to 26 (F7's PorterDuff-safe set: Plus, Screen, Modulate, SrcOver, Overlay). What is lost is the anisotropy, which is the biggest single loss in any fallback on this list.

**What could make it worse:** moiré at the far end. The desk runs to the back of the room and minifies harder than the mat. It cannot be filtered in the shader — there are no derivatives — so it needs stage 0's `StagePlane.jacobian` and depth-banded LOD **by contrast**, not by blur. And a depth band on this stage is not a `clipRect`: depth is `localY·sinTilt` in the spun frame, so at yaw ≠ 0 a band is a rotated strip whose quad must come from core, because `StagePlane` owns the sign of the yaw in exactly one private place by design.

---

## Stage 6 — "There is a room in the card"

The stage where day and night stop being two colour grades.

**The argument is specific to this codebase.** `StageLook.DeskDay` and `StageLook.DeskNight` share every hex value — `table = 0xFF2E2419`, `wall`, `floor`, `glass`, `shade`, `gold` are literally identical, and the KDoc says so on purpose ("a room does not repaint itself at dusk"). The two rooms differ **only in the rig**. From a card's point of view they currently differ by one specular dot's colour and position. A window-shaped soft rectangle sliding across a sleeve versus a small hot ellipse over a dark ceiling is the cheapest thing that makes them two *places*.

Three pieces, in increasing cost:

**(a) L2 spherical-harmonic irradiance — nine `float3`, no texture, and it needs no seam change at all.** Project the SH in Kotlin from `StageLighting`'s three `Light`s plus the room's flat albedos (`StageLook.colourOf` already maps the six `Surface` values, `Scenery` already solves the ten pieces), fold Filament's c1..c5 constants in at bake time, and replace the constant `key.ambient` term in `StageRig.lit` with `irradianceSH(n)`. **Nothing downstream notices** — every surface on this stage already funnels through `StageRig.lit → Lit → Color.shaded`. Ship this before anything else in the stage; it is the one item where 26–32 and a Tab S11 are bit-for-bit identical.

The `max(0)` clamp is load-bearing: L2 of a one-window room goes negative on the far side and you get a black patch. And **get the basis right** — this stage is z-up and `Light.direction` is the way light *travels*, where Filament's form is y-up. Wrong swizzle lights the room 90° round, which looks plausible and is wrong; it is the same class of bug as the un-negated `uLight` that shipped a flat felt. Pin it: project a single delta light along `StageRig.Key.toLight` and assert `eval` peaks within 1e-3 of that direction.

What actually moves: pile sides, the room's boxes, the leaned hand cards' edges. **Not** card faces — every face-up card's normal is +z. Say that out loud in the release note; the picture-line is *"the side of the deck facing the window is warm and the side facing the dark corner is cool"*, not *"the cards change"*.

**(b) The specular probe.** Bake in `:core` from the same ten `ScenePiece` boxes `ScenePainter` sorts — 4096 directions × 10 ray-slab tests is ~0.8 Mflop, about a millisecond, fully testable in commonTest. Octahedral, five levels of 64×64 stacked vertically with a 1-texel duplicated gutter at each end (there is no `textureLod`; blend two taps manually). Two probes, ~30 KiB, hung on `StageLook` — whose header already states exactly the right lifetime: *"changes when the user picks a different room, or when the hour crosses dusk, and on no other frame ever."*

```glsl
float2 octEncode(float3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    float2 o = n.xy;
    if (n.z < 0.0) o = (1.0 - abs(n.yx)) * sign(n.xy);
    return o * 0.5 + 0.5;
}
```
The fold must be **baked in**, not clamped away, or there is a visible diagonal crease across every reflection. `commonTest`: `octDecode(octEncode(d)) == d` to 1e-6 over a Fibonacci sphere of 4096 directions including the four seams.

Use the **analytic env-BRDF** (Lazarov/Karis), not a 512² RG16F LUT: 6 ALU, `f0 = 0.04` folded in, no second child, no dependent fetch. Its known error is at pr > 0.9 and NoV < 0.1, and the roughest card here is pr 0.639.

**(c) Box-parallax correction (Lagarde) — the cheapest visual upgrade per line on the whole plan, ~15 ALU and nine floats.**

```glsl
float3 sg  = step(float3(0.0), R) * 2.0 - 1.0;        // ±1, never 0
float3 inv = sg / max(abs(R), float3(1e-4));
float3 t1  = (uBoxMax - P) * inv;
float3 t2  = (uBoxMin - P) * inv;
float3 tm  = max(t1, t2);
float  t   = max(min(min(tm.x, tm.y), tm.z), 0.0);    // the clamp matters — see below
float3 Rc  = normalize(P + R * t - uProbePos);
```
The box is a one-line fold of `SceneModel.pieces` (`SceneBox` already stores min/max as corners), and **the approximation is exact here**, because the room literally is ten axis-aligned boxes. `uProbePos` is the middle of the *mat*, not the middle of the room, because that is where the cards are.

Without it every card reflects the window at the same place on its face, which reads as sixty stickers. With it the card near the window shows it near its edge and the card across the table shows it near the middle — **that difference across the table is what says they are all in one room**, and it is what a person notices when a card is dragged. **Ship it in the same release as (b)**, both to make the reflection worth having and because pairing them pays the golden re-record once.

**`Scene.MINIMAL` gets no baked probe.** DESIGN.md §11's opening line is that Minimal is what this app is and does not change; a probe of a true-black room is a black probe that kills every card standing on it, and a reflected room on a stage with no room is decoration. Give it a two-lobe sky/ground analytic gradient in ~15 FLOP instead, or nothing.

**What you would see:** the day room and the night room become two places rather than two colour grades. A sleeve tilted toward the window catches a soft rectangle of it; the same sleeve at night catches a small hot ellipse. Move the card across the table and the reflection stays where the window is.

**Cost:** SH is nine MADs. The probe is one bilinear fetch plus ~30 ALU per card pixel — 0.1 GFLOP/frame, immeasurable. The bake is offline.

**Android 26–32:** the SH half in full, exactly. For the specular half, evaluate the probe on the **CPU, per card, per frame**: `R = reflect(−V, N)` at four corners and the centre, parallax-corrected (9 flops, so **the slide survives into the fallback**), octEncoded, five bilinear taps into the decoded `FloatArray`, multiplied by the same `envBrdfNonmetal` polynomial — then fit a five-stop `Brush.linearGradient` between the two most-different corners, composited `Plus`. 300 taps and ~7 kflop a frame for sixty cards; immeasurable. Because R varies only 4.1° across a flat card at the reference camera distance (`atan(104/1450)`), that fit is within a couple of per cent everywhere except across a hard window edge. **One card in sixty.**

**What could make it worse — three, and the first is a rule violation:**
1. **`NIGHT_FLOOR = 0.55` is a guarantee with a test on it**, tied to `Tone.veil`'s measured error. A physically-baked night probe has a minimum over the sphere below it and violates it on its own. Either the bake clamps `min(sh) >= NIGHT_FLOOR` with a test, or N2 has landed and the veil is gone. Findable by the tablet, not by CI.
2. **The probe is silently stale.** If `WALL_HEIGHT`, `WINDOW_SPAN`, `LAMP_OUT` or `DESK_SPAN` moves, the probes are wrong and nothing fails. Generate at startup rather than shipping PNGs — a kilobyte and microseconds — or pin the bake inputs in `SceneryTest`, which already pins the lamp's mast compression for the same class of reason.
3. **Without stage 4's varying normal the probe is a tint** and you have spent a golden re-record on a flat wash. Do not ship it before stage 4.

---

## Stage 7 — "It looks photographed"

One screen-space finishing pass, everything folded into it, above the mat's `graphicsLayer`.

**This answers FIDELITY A1, and the brief is the answer.** A new untransformed `Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = post })` wrapping the mat Box — inside the stage Box, sibling to `MatInput` (which draws nothing), under `PlayTopBar`. **It cannot go on the mat's own layer**: that layer carries `rotationZ = −yaw; rotationX = tilt; cameraDistance`, so a vignette drawn there is an ellipse whose centre walks off the optical axis as the table turns. **Amend DESIGN.md §6 and §10 first, in their own commit.**

One real side effect to verify rather than assume: an offscreen layer is bounded by its own size, so the room's overflow onto the chrome stops being drawn. LOOP.md iteration 1 measured that overflow precisely (at 1600×1000 the pane occupies rows 0..36 against a 44-tall bar) and the same iteration made `PlayTopBar` stand on opaque Ink. So the clip is invisible-to-better — but check with `tools/compare.py`.

### What goes in it

```glsl
uniform shader content;
uniform float2 uRes;
uniform float  uTanHalf;   // (width/2) / plane.cameraDistance
uniform float  uVig;       // 0..1
uniform float  uGrain;     // sigma at mid grey, in units of 1/255
uniform float  uCa;        // magnification difference, ~0.0003

float hash12(float2 p) {                     // integer-free; ES2-safe on both
    float3 q = fract(float3(p.xyx) * float3(0.1031, 0.1030, 0.0973));
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

half4 main(float2 p) {
    float2 c2 = uRes * 0.5;

    // lateral CA as a magnification difference, never a constant offset
    float3 rgb = float3(
        content.eval(c2 + (p - c2) * (1.0 + uCa)).r,
        content.eval(p).g,
        content.eval(c2 + (p - c2) * (1.0 - uCa)).b);

    // cos^4, about the true optical axis, in linear
    float  r = length((p - c2) / (uRes.x * 0.5));
    float  t = r * uTanHalf;
    float  q = 1.0 + t * t;
    float  v = mix(1.0, 1.0 / (q * q), uVig);
    rgb = fromLinearSrgb(toLinearSrgb(half3(rgb)) * v);

    // TPDF dither / film grain, last, in the space being quantised
    float  u  = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    float  sg = uGrain * 2.0 * sqrt(max(u * (1.0 - u), 0.0));
    rgb += (hash12(p) - hash12(p + 17.31)) * sg;

    return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);
}
```

**Vignette, and the honest number is bigger than any survey budgeted.** With `cameraDistance = 1.45·max(h, w·0.55)`, at 1600×1000 the horizontal edge is `t = 800/1450 = 0.552` → cos⁴ = 0.588 = **0.77 EV**, and the corner is `t = 943/1450 = 0.651` → cos⁴ = 0.494 = **1.02 EV**. So parameter-free cos⁴ costs a full stop at the corner. Ship it at `uVig = 1.0` and it is stronger than advertised; scale it and it stops being parameter-free, which was its whole appeal. That is a tablet judgement of the same class as `sheenCore`/`sheenEdge` — batch them.

**Grain envelope: σ(u) = σ_max · 2√(u(1−u)), σ_max ≈ 3 codes.** Exactly zero at code 0 and at code 255 **by construction**, which is the only property that lets grain coexist with a true-black design. Code 4 → 0.75; code 20 (the felt) → 1.61; code 128 → 3.00; code 200 → 2.47. Uniform-amplitude grain turns `#000000` into speckle and destroys the identity.

Grain at code 4 is already σ 0.75, more than a 1-LSB dither, so it **supersedes FIDELITY F4's five named banding sources as a side effect** — `feltPool`'s nine `POOL_STOPS`, the sheen radial, the `look.falloff` vignette, `poolBrush` on the desk, and sixty card specular pools. Below code 3 there is nothing left to band. Build F4's `core/render/BlueNoise.kt` for the 26–32 path and let the shader path use `hash12`.

**Grain must be bigger than a pixel** (Selwyn's law: RMS granularity scales as 1/√A). One-pixel grain is sensor noise; the same RMS at 2–3 px is film. On a Tab S11 at 0.094 mm/px, 10–30 µm clumps are 2–3 px.

**Chromatic aberration, and the whole acceptable range is sub-pixel.** DXOMark measures lateral CA in µm on a 24×36 frame: 5 µm "noticeable", 10 µm "a typical level of acceptability", 20 µm a bad kit zoom. `uCa = 0.0003` gives 0.45 px at the corner of 2560×1600. Pin it in `:core` **with the DXOMark figure and the conversion arithmetic in the KDoc**, the way `FELT_ROUGHNESS`, the lamp's 5:1 mast and `Lit.TEMPERATURE` are pinned, or the next person reaches for the slider. And keep it out of `Prismatic.kt`'s namespace: the app's existing fringes are a design device that is *deliberately visible*; this is a lens artefact that is *deliberately invisible*. Letting one become the other loses both.

This partly overturns FIDELITY's Dropped entry, which is right about the per-object rim version it was refusing and wrong about the inference: 0.4 px of radial fringing is not disposable *precisely because* you cannot see it as an effect.

### The tonemap, and the one refusal that should be narrowed

FIDELITY's Dropped table lists "Reinhard / ACES / Khronos PBR Neutral" as "not work", with N1's deciding number: at L = 0.002 — a shadow edge, a pile side in the dark half of the mat — plain sRGB gives code 6.16 and Narkowicz ACES gives **1.63**. That refusal is correct and should stand.

**It is wrong about AgX**, and the numbers are worth putting in the table:

| linear L | plain sRGB | AgX (default, 16.5 stops) | ACES |
|---|---|---|---|
| 0.002 (shadow edge, pile side) | 6.16 | **6.56** | 1.63 |
| 0.0018 (`#060608`, the stage) | 6.0 | 6.4 | ~1.5 |
| 1.0 (a lit card face) | 255 | **200.7** | ~230 |
| 16.3 | — | 255 | — |

AgX is within half a code of the identity in the bottom hundredth of the range, which is exactly where `Tone.KNEE_LINEAR`'s KDoc says this stage's shadows, pile edges, felt falloff and every veil are decided. Its whole cost is at the top: **white lands at code 201**, and the fix — +2 EV with a post-power of 1.2 — brings white back to 235 and simultaneously doubles the felt from code 10 to code 19. *That* is the decision, and it is DESIGN.md §2's, not the renderer's.

Three obstructions, all of them structural:

1. **`Tone.shade`'s cliff.** Line 112 short-circuits `if (amount >= 1f) return base`. With AgX underneath, `shade(0xE8, 1.000)` = 232 and `shade(0xE8, 0.9999)` = 193 — a 39-code discontinuity on `CardStockColour`, the brightest surface on the stage, at exactly the light a resting card's front face sits near (the golden records `front 1.000`).
2. **`Tone.veil` goes negative.** The veil AgX wants at diffuse 0.955 is −0.039; under AgX + 2 EV it is negative at *every* light in the app's range. `veil()` coerces to [0,1] and returns 0, so the card face gets no shading while its ruled edge does. **A black overlay at any alpha can only darken.** This is not tunable; N2 is the only fix, which is why stage 4 comes first.
3. **The refusal has to be amended in writing** before the code, and DESIGN.md §2 has to explicitly accept the felt moving from code 10 to 19 — or the exposure is chosen to hold it.

One correction worth having: **`GoldenStageTest` does not move for a tonemap.** It never calls `Tone` — it dumps `Lit.amount`/`warmth`, `Shade` fields and geometry. `ToneTest` is what goes red, and it is the better gate.

**And bloom, if it lands, is a child of this pass, not a sibling** — otherwise the tonemap crushes the halo you just added. Its natural source is `Scenery.shadeLight(time)`: the lampshade already carries an `emission: Lit` and is drawn unshaded for the right physical reason. Threshold **in linear** with Unity's quadratic soft knee placed *above* the brightest diffuse surface. The felt is code 20 (L 0.0070) and a card's white bar code 230 (L 0.791) — a factor of 113 in light but only 11.5 in code, so a threshold of "0.8" applied to code values cuts at L = 0.604, *one stop below the specular*, and eats exactly the highlight you wanted. Four blurs at 4× spacing approximate the full 2× chain to within noise (measured log-log slope −3.34..−1.41 against −3.52..−1.12); 8× spacing shelves at −0.48 and shows a ring. **Karis average on the first downsample only** — sixty specular pools recomputed from `camera.eye` every frame is a real firefly source here, not a theoretical one.

### Depth of field, which belongs in this stage and needs no shader at all

`rememberGraphicsLayer` / `record` / `layer.renderEffect` / `drawLayer` / `BlurEffect` / `BlurredEdgeTreatment.Unbounded` / `RenderEffect.isSupported()` are all common Compose API at the pinned 1.11.1 (verify against the resolved jars). **This reaches API 31, two Android versions below a runtime shader.**

The depth is already computed: PlayScreen.kt:550 calls `plane.project(pose.position).depth` for all sixty cards every frame the camera moves. CoC is a pure function of that and a focus depth.

**Why there is no halo:** the screen-space DoF halo is a *gather* artefact, and everything that fixes it exists to reconstruct scatter from gather. Layers give scatter free — the blur runs on premultiplied RGBA including alpha, so a near card's alpha feathers outward over the sharp background. Invariant: **blur premultiplied, composite after.** What layers cannot do is reveal what is behind a blurred foreground, and here the missing information is only the mat, which the Canvas already drew sharp underneath.

Depth span across the mat at z = 0 is `height · sin(tilt)` — 343 mat px at 1600×1000. **Ship a restrained gradient**: `g ≈ 0.0125` px of CoC per mat px puts the far edge at 4.4 px, about 4% of a card width, which is what a photographer shooting a card table at f/8 actually gets. `g = 0.036` (f/2.8-equivalent) is 12% of a card and will be read as phone portrait mode.

**Gate hard on ~0.7 px of CoC** and cross-fade the radius from 0 over 0.5→1.0 px, so a card is already on the layer path before its blur is visible — otherwise it pops as the camera orbits, because offscreen compositing rounds differently. And the layer must be ~3σ larger than the card (`BlurredEdgeTreatment.Unbounded`) or the card's own edge clips its blur and you get a hard rectangle around a soft card, which is the exact artefact this exists to avoid.

LoCA belongs here, not in the CA term: multiply the near-field layer by ~(1.02, 1.00, 1.02) and the far by ~(1.00, 1.02, 1.00) via `layer.colorFilter`. FIDELITY's Dropped table already prescribes exactly this.

**What you would see:** the picture stops looking like a diagram of a table and starts looking like a photograph of one. The corners fall away, the concentric rings in the lamp's pool disappear, there is a fine tooth to the whole image, and from the seated chair the far edge of the mat goes soft.

**Cost:** one full-screen pass, 5.25 GB/s at 120 Hz, ~8% of the memory bus, ~0.5 ms. Two extra dependent reads for CA fold into it and take it to ~7 GB/s. DoF is a handful of card-sized layers above the threshold, not sixty.

**Android 26–32:** better than it sounds, because most of it is not a shader.
- **26–30:** no `RenderEffect` at all. Vignette as a 9-stop `Brush.radialGradient` in the same screen-space Canvas, sampled from cos⁴ — same curve to within a code, and because it is already screen-space it is *correct*, not degraded. F4's blue-noise `ImageShader` tile at ~1.2% alpha for the banding. **No grain** (a plain brush cannot read the pixel underneath, so the tone envelope is impossible, and un-enveloped grain is what ruins true black). No CA, no bloom, no DoF, and their absence is invisible by design.
- **31–32:** all of the above **plus depth of field at full quality** and the four-blur bloom approximation, because `BlurEffect` is API 31.

**What could make it worse — the four most likely ways:**
1. **Grain fixed in screen space stays still while the whole table moves under it.** That is what photographing a moving subject on film does and what looking at a table does not. It will look odd for ten seconds. The alternative is worse: grain in mat space is a texture printed on the felt that shears with the perspective. Show kai a contact sheet before the release.
2. **The panel may be 10-bit.** If the Tab S11's AMOLED composites 10-bit, 1/255 of noise is four times too much and you have added visible grain to fix a problem the panel did not have. Gate the amplitude on the actual surface depth; `ui/fx/Display.kt` is the natural home — it already carries `rememberRefreshHz` and already has both actuals.
3. **A dither or grain floor that lifts a true 0 off zero** is a grey rectangle where the room ends. Clamp it.
4. **An animated tile is banned in three places** — AAA #60 [guardrail], DESIGN.md §11, and FIDELITY's Dropped table. Refuse it in writing in the same commit. The "advance only while the camera moves" escape hatch is defensible and belongs in the handbook first, not smuggled in with the dither.

---

## Stage 8 — "Foil is a diffraction grating" (optional, and gated on data)

The anisotropic half already shipped in stage 2 as F1, which FIDELITY A2 correctly says is most of AAA #21. What is left is the hue sweep, and it is the one stage that can slip indefinitely without hurting anything.

Zucconi's grating with `u = abs(dot(L,T) − dot(V,T))`, four orders, `d` 1000–3000 nm starting at 1600, added to the specular and **gated by the anisotropic D so the rainbow only appears where the streak is**. `CardMaterial.iridescence` must be **deleted**, not layered under — two rainbows disagreeing is worse than one.

```glsl
for (int m = 1; m <= 4; ++m) { ... }   // the literal bound is mandatory
```
A `uOrders` uniform bound will fail to compile under strict ES2 on **both** platforms — exactly the class of thing you write on desktop and discover on the tablet.

**Two things gate it, and neither is code.**

- **AAA #25.** Rarity is not read from YGOPRODeck's `card_sets`, so `CardStock.of` foils *every* extra-deck card and *no* main-deck card. #25 also carries the argument this needs to pass the palette rule: reading rarity "makes the finish **meaning**, which is the only licence the palette gives colour." Without it, a hue sweep on all fifteen extra-deck cards spends AAA #89's guardrail — *"the prismatic ring stays rare… a summon that fringes everything spends the whole visual identity in a single turn."*
- **DESIGN.md §7** says foil "is the only one that splits its highlight into the prismatic ramp — colour as light, inside the specular term or nowhere." Replacing a prismatic-ramp tint with a physical grating argues with that. The grating is unambiguously *light*, which is the argument that wins it — but LOOP.md gate 4 requires the amendment first, in its own commit.

The one place the two platforms diverge visibly, on the one surface a user will photograph: **`pow(ndh, 400)` underflows in `half` on Android** (mediump's smallest normal is ~6.1e-5, crossed at ndh ≈ 0.976) and does not on desktop. The tight glint is a hard 2-pixel spark on the tablet and a smooth blob on the desktop. Either clamp both to the Android behaviour with an explicit `step(0.976, ndh)`, or drop the second `pow` — at 104 px a glint that exists for 2.4% of the angular range is two pixels.

The grating term is **additive and unbounded**: without a clip a foil blows to white the moment `u` lands in the sweet spot. That clip is stage 2's gated soft clip, not ACES.

**Android 26–32:** F1's streak from stage 2, plus today's seven-stop Prism ramp. A foil that reads as foil.

---

## The ceiling: what "truck sim fidelity" does not reach here, and the nearest honest substitute

Say these out loud rather than discovering them at stage 6.

| Not reachable | Why | Nearest honest substitute |
|---|---|---|
| **Global illumination / bounce light** | No scene traversal at runtime, no light transport, no engine — and the no-engine rule is not the thing to spend here | The L2 SH probe (stage 6a). One bounce off ten flat boxes, baked. It is genuinely most of the read for a room this simple. |
| **Shadow maps; anything in the room casting a shadow** | No depth buffer and no place to put one. Paint order in the room is `ScenePainter`'s **separating axis**, chosen because a nearest-corner depth sort put a 511 px wall over a 241 px lamp for fifty pixels at the seated seat. A depth buffer that disagrees with the axis choice makes that a contradiction — a bug class with no test that can catch it. | `Shadows.landOn` already projects any corners onto any plane. AAA #61d says this is a decision to revisit *as a set*, and it should stay that. |
| **Screen-space reflections** | Same missing depth buffer, and the upside is a sliver: cards are `THICKNESS_RATIO = 0.00508` of a card width apart, **0.53 px at the reference size**. 3–5 ms of an 8.33 ms budget for a dark line at each card edge, and Schlick at f0 = 0.04 makes the reflection 6× stronger at 75° — exactly where SSR's thickness heuristic and screen-edge fade both fail. | Exact planar mirrors by homography: every card *is* a plane, `Homography.squareToQuad` is already Heckbert's closed form with the edge-on degeneracy guard written, and the four-nearest-card list is already computed for stage 3's AO. Four `mat3` uniforms, four taps, ~80 ALU, exact at all angles, cannot walk off screen. Record the SSR refusal in FIDELITY's Dropped table. |
| **Volumetrics, god rays through the window** | Needs a march and a depth buffer, and the window is 36 px tall inside a 44 px chrome bar | Nothing. Do not attempt it. |
| **Motion blur** | No velocity buffer; and the only thing that moves is a card under a finger, where DESIGN.md's last anti-pattern is a spring between a finger and the thing it is dragging. Blur is lag by another name. | `CardDynamics`' existing bank-into-the-sweep. Already shipped and already the right answer. |
| **TAA, temporal specular AA, temporal upsampling** | Two `:studio` runs being bit-identical is a project contract — `tools/compare.py` and the seeded deal are the loop's whole eye. Temporal accumulation ends it. | Analytic band-limiting from `StagePlane.jacobian` and Toksvig roughness transfer (stages 0, 1). Zero runtime cost, deterministic, and testable in commonTest, which temporal AA never is. |
| **Anisotropic texture filtering, mip LOD, `dFdx`** | AGSL and SkSL runtime effects have no derivatives on either platform, and Skia's anisotropic path is GPU-only so `:studio` cannot see it. **A golden recorded on CPU cannot prove the GPU path**, and LOOP.md §3's "never ship a picture you have not looked at" has no answer here. | Everything LOD is analytic and comes from core. Write the no-derivatives constraint into DESIGN.md §6 as design rather than as a platform quirk — it already is. |
| **The room** | LOOP.md iteration 1 measured it: at 1600×1000 the back wall gets **6 px of open air** at the table seat, 25 seated, 26 overhead, and the entire window is inside the top bar. **Truck-sim fidelity is 70% environment, and this stage's environment is a 6 px hairline.** Defocusing six pixels of wall is the most expensive way to change nothing. | This is the ceiling, and it is a *framing* decision blocked on kai, not a rendering one. Giving the room more means re-framing a stage he has tuned. Until it is answered the fidelity work belongs on the felt, the cards and the light — which is where every stage above points. |
| **The hero asset** | The single largest gap, and no survey weighted it. A card face is `art.imageUrlSmall` — a **168×246 network JPEG** — drawn at ~104 logical px, with JPEG ringing in the name bar. ETS2's dashboard is a hand-authored 2K albedo + normal + roughness. No BRDF fixes a low-res lossy thumbnail. | F5 (`FilterQuality.Medium`, one argument, 1.6:1 minification, one level) plus the full-size fetch on peek that already exists. Beyond that: AAA #34, render card faces once to a texture — listed as "the cost that will stop everything else on this list from fitting in the budget", and it is also the only thing that would let a card face be *authored* rather than fetched. |

Two smaller ones worth writing down:

- **A matcap/probe flattens parallax against translation.** A reflection that should slide as a card *translates* will not, only as it rotates. On a card table nobody notices; write it down so nobody rediscovers it.
- **Charlie sheen on the felt stays refused, and the measurement is the reason.** `StageRig.Key` normalised gives N·L = 0.841 on the mat and `DeskDay` gives 0.740 — nowhere near grazing, so the forward-scatter lobe is under one level of 255 in two of three rooms. Only `DeskNight`'s placed lamp ever grazes the far corner. And `StageRig.Rim`'s KDoc records that a light low and behind — Charlie's premise — was tried, changed exactly one face in a whole board, and was moved to the player's side.

---

## What this does to `docs/classic/FIDELITY.md`

FIDELITY was written for a canvas-only renderer. Most of it survives; some of it changes rank; four entries are now wrong.

**Superseded — the shader does it better, and the FIDELITY version becomes the permanent 26–32 fallback rather than the plan:**

- **F2** (band alphas). The per-pixel `V(t) = (acos t − t√(1−t²))/π` in stage 3 *is* F2's profile, evaluated rather than solved into bands. Ship F2 first anyway: it is a change to one `pow`, it needs no shader, and it is what a third of Android sees forever.
- **F10** (wood grain by `ImageShader` + `Overlay`). Superseded by the procedural grain of stage 5 on 33+; unchanged as the fallback. Its "measure the moiré" caveat is discharged by `StagePlane.jacobian`.
- **N8** (card-on-card shadows by Sutherland–Hodgman). The *method* is superseded by stage 3's analytic `quadOcclusion` — ~85 ALU against a convex clipper and an AABB broadphase. The clipper is still the 26–32 answer, and N8's candidate-list observation is now shared with stage 3.

**Elevated — these were foundations and are now blockers:**

- **N3** (`StagePlane.density`) is the **most urgent item in the document**, and its status is wrong: the felt weave shipped without it and the far half of the mat is aliasing on the tablet now. Move it to first light.
- **N2** (exact affine shade) is now load-bearing in a new way. Every tonemap, every exposure change and every environment term needs a card face that can be *brightened*, and a black `drawRoundRect` can only darken. It gates stages 4, 6 and 7.
- **N1** and **N5** gate everything per-pixel. Tuning a BRDF against `rim = 0.09f` — which delivers 0.853% of white's light, not 9% — is wasted work.

**Half-done, and the entry should say so:**

- **N7** (the felt has a weave) shipped in LOOP.md iteration 5. What remains is the band-limit and the Toksvig transfer, which is stage 1. Its "do not bake paper fibre" ruling stands and should be repeated in `CardMaterial`'s KDoc.

**Answered:**

- **A1** — *yes.* The brief grants a second drawing surface. Amend DESIGN.md §6 and §10 in their own commit; it unblocks the vignette, 1:1 dither, grain and the whole finishing pass.
- **A2** — *taken, and shipped.* AAA #99 is Done. Its three costs are live and stage 0 addresses two of them; the third (the shader text is unreachable from commonTest) is mitigated by generated SkSL plus a `:studio` parity task, not eliminated.
- **A4** — batch F1 + N4 + N5 + the per-corner spread into **one** re-record, at the end of stage 2, with the taste calls from A5 judged on the tablet in the same pass.
- **A9** — partly. `BlurEffect` is API 31 and is now in scope, and its "steady state" objection is answered by keying the DoF layers on `StageCard.parked`. `drawVertices` stays refused: `Vertices` re-encodes `List`s and is a silent no-op below API 29 against minSdk 26.

**Refusals to narrow or amend, on the record:**

- **ACES / Reinhard / PBR Neutral** — the refusal is correct and the numbers hold (ACES turns code 6.16 into 1.63 at L = 0.002). **It is wrong about AgX**, which is within half a code of the identity through the whole bottom hundredth of the range and costs its accuracy at the top instead (white at code 201). Amend the table with both numbers and the three obstructions in stage 7.
- **Lateral chromatic aberration** — the refusal is right about the per-object rim version it was refusing and wrong about the inference. 0.4 px of radial fringing in a screen-space pass is a different thing, and it is not disposable precisely because you cannot see it.
- **Disc-light sampling on the Vogel spiral** — stays dropped, for a better reason: the analytic SDF penumbra of stage 3 gets the shape without a layer per card.
- **Charlie sheen, `frac(sin(dot(...)))` hashing, animated grain, `drawVertices`, Tanner Helland Kelvin→RGB** — all stay dropped, unchanged. Add **screen-space reflection** to the table with the 0.53 px number.

**Untouched and still worth shipping as written:** F5, F6, F7, F8, N6, A3, A6, A7, A8.

---

## The order, in one table

| Stage | Ships | Shader? | 26–32 loses | Golden |
|---|---|---|---|---|
| 0 | seam widened, three spikes, `jacobian`/`inverse`, parity harness | seam only | nothing | no |
| 1 | weave band-limit, Toksvig, continuous pile ruling | yes | nothing it would have had | no |
| 2 | N1 + N5 + F1 + F9, exposure at 1.0 then moved | **no** | nothing | **yes — the batch** |
| 3 | N4 split, SDF penumbra, contact occlusion | yes | per-pixel edge only | yes (or batch with 2) |
| 4 | N2, per-pixel GGX + clear coat | yes | per-pixel view vector, coat normal | yes |
| 5 | desk grain, felt into the shader, AAA #61c | yes | anisotropy | no |
| 6 | SH irradiance, octahedral probe, parallax | (a) no, (b)(c) yes | per-pixel reflection detail | yes |
| 7 | A1 canvas, vignette, dither, grain, AgX?, bloom, DoF | mixed — DoF reaches **API 31** | grain, CA, bloom; keeps vignette + dither | ToneTest, not the golden |
| 8 | foil grating | yes | the hue sweep | Foil `spec` lines only |

### The phases those stages are shipped in

Written when the three questions above were answered. The stages are still the
engineering; **the phases are the releases**, ordered for how much of the difference a
person can name per session rather than for how the arithmetic stacks. One phase is one
session and at least one signed release. A session picking this up cold starts at the
first phase that is not marked done and reads that stage in full before writing anything.

| Phase | The sentence that will be true of the picture afterwards | Is | Needs |
|---|---|---|---|
| 0 | *(nothing visible — ships folded into 1)* | stage 0, plus the studio learning the POV seat | — |
| 1 | "I am sitting at the desk, and the room is around me." | **done** — `CameraTune` → `StageSeat.POV`, and the room closed at the sides. Not above: at eighty degrees of pitch the top third is still void, which is a ceiling and so phase 8's | 0 |
| 2 | "Every faint white arrives at the brightness it was tuned for." | stage 2 | — |
| 3 | "Cards stop being stickers laid on a picture of a desk." | stage 3, **plus stage 1's pile ruling** | 2 |
| 4 | "It stops looking like a diagram of a table and starts looking like a photograph of one." | stage 7 **minus the tonemap** | 0 |
| 5 | "Cards stop being sixty copies of one sprite." | stage 4. **AgX shipped early, in phase 4's lens** — see below | 2, 4 |
| 6 | "The wall is plaster, the glass is glass, and daylight lands on the desk." | stage 5, and AAA #64 | 2 |
| 7 | "Everything in the room is standing on the floor." | AAA #61d, as a set | 6 |
| 8 | "It is a bedroom with a desk in it, not a desk with a wall behind it." | AAA #92 + #93, then the furniture | 1, 7 |
| 9 | "The card near the window shows the window; the one across the table doesn't." | stage 6 | 5, 8 |
| 10 | "Foil is foil, the desk has a near edge, and I can photograph the board." | stage 8, AAA #66, #61's last corner, #69 | 9 |

Three of those orderings are deliberate and each was argued rather than assumed.

- **Stage 7 is split, and its bigger half comes fourth.** The vignette, the grain, the
  chromatic aberration, the bloom and the depth of field are the single largest
  cheap-versus-photographed lever on this list and **none of them needs `Tone.veil`
  dead** — only the tonemap does. So they ship as soon as the `RenderEffect` seam exists,
  and AgX waits for N2 in phase 5, which is where §"The tonemap" already puts it.
- **Stage 1's weave band-limit is already shipped** (LOOP.md iteration 6); what is left of
  that stage is the pile's ruled side, and it belongs with the shadows because it is the
  same complaint — *the biggest object on the table is the most obviously wrong thing in
  the frame*.
- **AgX did not need `Tone.veil` dead, and that ordering was wrong.** This document made
  the tonemap wait for N2 because it assumed the curve would be applied *per surface* —
  where a veil that has to compensate for it goes negative, and a black overlay can only
  darken. Put over the finished picture, in the same `RenderEffect` as the vignette and
  the grain, it never meets `Tone` at all. So it shipped with phase 4's lens.
  `core/render/AgX.kt` is the curve, tested, and it generates its own SkSL so there is no
  second copy to drift. Two things were measured on the way and both corrected this plan:
  the **post-power of 1.2** is the ACES mistake in miniature — it halves a shadow edge,
  6.56 codes to 3.15 — so the power is one and **+1.5 EV** carries the whole grade,
  landing within three codes of every midtone this plan asked for. N2 is still owed; it
  is a correctness debt rather than a blocker.
- **The room is built before it is reflected.** Stage 6 bakes a probe of `SceneModel`'s
  own boxes, and a probe of an empty room is a flat wash on sixty cards. Building the
  furniture first is also what makes the golden re-record that stage costs worth paying
  once.

Three things must be answered by kai before the stages that depend on them start, and none of them is a code question: **the room's framing** (LOOP.md iteration 1 — blocks anything about the wall or the window), **whether the felt may move from code 10 to code 19** (blocks AgX), and **whether the Desk scenes may claim a camera at all** (blocks lens dirt, which I recommend never building — it is the one item that can make the stage worse in a way that is obvious and permanent, and a version number once published is spent forever).

---

## All three are answered, and a fourth was asked back

*Added when kai asked for the play stage to reach a AAA first-person game.*

**The room's framing — answered by moving the seat, not the room.** `StageSeat.POV`
now becomes the seat the stage opens at: thirty-two degrees of elevation, where the
room is about half the picture rather than the fifth `ROOM_ABOVE` bought it. That
retires the ceiling this document's last table describes as *"a 6 px hairline"* and
with it the reason the wall and the window were parked. It costs a card about a third
of its drawn height to `cos(pitch)`, which is what looking at a table from a chair
does; the hold-to-read card reader carries legibility instead.

**The felt may move from code 10 to code 19 — the whole grade is authorised.** AgX
with the +2 EV and the 1.2 post-power, the cos⁴ vignette at its honest full stop in
the corner, tone-enveloped grain, the sub-pixel lateral CA, bloom and depth of field.
`Scene.MINIMAL` is untouched and stays the control. So stage 7's three obstructions
are now engineering rather than permission: `Tone.shade`'s `amount >= 1f` cliff,
`Tone.veil` going negative at every light in the app's range, and this document's own
amendment to FIDELITY's Dropped table.

**The Desk scenes may claim a camera** — and the recommendation against **lens dirt**
stands unchanged and unasked. It is refused here rather than scheduled.

**And the fourth, asked back, because no survey had weighted it:** the hero asset. Given
the choice between fetching the full-size art, fetching it *and* rendering each face to a
texture, or leaving it alone, kai chose to leave it alone and spend the effort on the
stock, the foil, the sleeve, the cut edge, the shadow and the light. So the last row of
the ceiling table is now a **decision**: `AAA.md` #34 is refused, `AAA.md` #25 and #26 and
the whole of stage 4 are where a card gets better, and F5's `FilterQuality.Medium` is the
end of what happens to the picture itself.

One thing this document did not anticipate follows from the first answer. It assumed the
room stayed at eighteen pieces, and kai has asked for the rest of it — a bed, a
bookshelf with books in it, a ceiling, side walls, clutter on the desk, and shadows in
the room as a set. That is not a stage here; it is `AAA.md` #92 and #93 first, because
`ScenePainter.order` is quadratic twice over and run every draw, and because
`docs/classic/DESIGN.md` §11 forbids anything standing over the mat until they exist. The
stages below are still the right order for the *surfaces*; the room's own build sits
after stage 5 and before stage 6, since a probe of an empty room is a flat wash.
