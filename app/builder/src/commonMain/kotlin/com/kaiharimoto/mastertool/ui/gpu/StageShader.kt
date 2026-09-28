package com.kaiharimoto.mastertool.ui.gpu

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect

/**
 * A runtime shader, where the platform has one.
 *
 * ## Why this exists, and why it did not before
 *
 * The standing rule was that the stage draws with paths and gradients and
 * nothing else, because a shader is not common code and the two platforms spell
 * it differently. `AAA.md` #99 is that rule with a question mark on it: Android
 * 13 has `RuntimeShader`, desktop Skia has `RuntimeEffect`, and a thin seam with
 * a plain-draw fallback is reachable — "the one place the no-engine rule
 * deserves re-reading rather than restating".
 *
 * It is re-read now because the target moved. A surface stops reading as a fill
 * colour when its *normal varies per pixel* and the light is asked about it
 * there, and there is no arrangement of paths and gradients that does that. Felt
 * with a weave, lacquer with a clear coat, foil as a diffraction grating, a
 * shadow that darkens what is under it rather than painting over it — all of
 * them are per-pixel or they are pretend.
 *
 * The no-**engine** rule is untouched and still right. Nothing here draws
 * geometry, nothing here holds a scene, and every vertex on this stage is still
 * solved by tested arithmetic in `:core`. This is a way of colouring a rectangle.
 *
 * ## The contract
 *
 * [compile] returns null rather than throwing, on every platform, for every
 * reason: too old an Android, a driver that refuses, a typo in the SkSL. **Every
 * caller must have a drawing that works without it**, and that fallback is not a
 * degraded mode to be tolerated — it is what a third of Android devices in the
 * wild will actually see, and it is what shipped before this file existed.
 *
 * A shader is compiled once and re-used. [brush] sets uniforms and hands back
 * something a `DrawScope` can fill with, which is the only shape of use this
 * stage has: there is no vertex program here, no render target, no pass.
 */
expect class StageShader

/**
 * Compiles [sksl], or answers null where this platform cannot.
 *
 * Call it once and remember it. Compiling costs milliseconds and the result is
 * immutable; doing it inside a draw is how a shader becomes the most expensive
 * thing on the stage.
 */
expect fun compileStageShader(sksl: String): StageShader?

/** Sets [uniforms] and returns a brush that fills with the result. */
expect fun StageShader.brush(uniforms: ShaderUniforms.() -> Unit): Brush

/**
 * A shader that can read what is already drawn underneath it.
 *
 * ## Why this is a second seam and not a flag on the first
 *
 * [StageShader] colours a rectangle from its coordinates alone. That is enough
 * for a weave, a grain, a ruling, a highlight — everything whose answer is a
 * function of *where*. It is not enough for anything whose answer is a function
 * of *what is already there*: a vignette that dims the picture, a tonemap, a
 * grain whose amplitude follows the tone it lands on, a bloom that finds its own
 * highlights. Those need the composited pixel, which on both platforms arrives
 * through a completely different object — a `RenderEffect` hung on a layer,
 * not a `Brush` handed to a fill.
 *
 * Two types rather than one boolean, because the *call sites* are different
 * shapes. A brush goes into a `DrawScope`; an effect goes into
 * `Modifier.graphicsLayer { renderEffect = … }` and forces the subtree onto an
 * offscreen layer. That cost is the whole reason the seam is worth keeping
 * narrow: one full-screen pass is a twelfth of a tablet's memory bus and worth
 * it, and **one per card is sixty of them and is not**. A single seam would
 * make those look like the same decision.
 *
 * ## What the two platforms disagree about
 *
 * The shader declares `uniform shader content;` and Android binds exactly that
 * one input implicitly, while Skia takes named children. More importantly they
 * disagree about what coordinates `content.eval()` expects — RenderNode-local
 * on Android, device space on Skia — so **pass the resolution in as a uniform
 * and never infer it**, and write the shader so it only ever evaluates at
 * coordinates it derived from its own `main(float2 p)`.
 *
 * Null for the same reasons and with the same contract as [compileStageShader]:
 * every caller keeps a drawing that works without one, and on Android below 33
 * that is a third of the devices in the wild rather than an edge case.
 */
expect class StageEffect

/** Compiles [sksl] as an effect, or answers null where this platform cannot. */
expect fun compileStageEffect(sksl: String): StageEffect?

/**
 * Sets [uniforms] and returns the effect, ready for `renderEffect =`.
 *
 * The input the shader reads must be declared `uniform shader content;` — the
 * name is fixed on Android and matched to it here so one shader string works on
 * both.
 */
expect fun StageEffect.effect(uniforms: ShaderUniforms.() -> Unit): RenderEffect?

/**
 * Whether this device has one at all.
 *
 * [compile] answering null is indistinguishable from a shader that compiled and
 * then drew its own blend mode's identity everywhere, and that is not a
 * hypothetical: the first weave shipped handing `Light.direction` — the way
 * light *travels* — to `max(dot(n, l), 0)`, which is zero across the whole mat,
 * and it cost a full pass and changed nothing. What told the two apart in the
 * end was a one-line `compile() != null` probe. This is that probe, named.
 *
 * Read it to *report*, never to branch: a caller that asks this instead of
 * checking its own `StageShader?` has two ways to be wrong and will one day
 * disagree with itself.
 */
expect val runtimeShadersAvailable: Boolean

/**
 * The uniform types this stage actually sets.
 *
 * Deliberately not the full set either platform offers. A seam that exposes
 * everything is a seam that has to be kept in step with two APIs forever; this
 * one covers scalars, points, colours, the odd matrix and — since a probe and a
 * noise tile became reachable — an image.
 */
interface ShaderUniforms {
    fun float(name: String, value: Float)
    fun float2(name: String, x: Float, y: Float)
    fun float3(name: String, x: Float, y: Float, z: Float)
    fun float4(name: String, x: Float, y: Float, z: Float, w: Float)

    /**
     * A colour, unpremultiplied, in the shader's own space.
     *
     * Android has `setColorUniform`, which applies the destination colour
     * transform; desktop has no equivalent and the actual computes the four
     * floats itself. They agree on sRGB, which is the only space this app draws
     * in, and the method exists rather than four `float`s at every call site
     * because forgetting to unpremultiply is silent.
     */
    fun colour(name: String, red: Float, green: Float, blue: Float, alpha: Float = 1f)

    /**
     * Nine floats, **column-major**, which is what a `mat3` is on both.
     *
     * Both actuals take the flat array rather than a matrix type, and that is
     * the point: Skia has a `Matrix33` overload, `Matrix33` documents itself as
     * row-major, and an SkSL `mat3` is column-major — so routing through it
     * means asserting which side transposes, and the wrong answer is a shader
     * that compiles, runs, and is silently transposed. Nine floats straight
     * through makes the two platforms agree by construction.
     */
    fun matrix3(name: String, columnMajor: FloatArray)

    /**
     * An image, sampled in **bitmap pixel** coordinates on both platforms.
     *
     * Not normalised. `child.eval(uv * uSize)` is the idiom, and the size has to
     * be passed as its own uniform because a shader cannot ask.
     *
     * [repeat] tiles rather than clamping. Leave it false for a probe — a
     * repeating octahedral map wraps the fold onto the wrong hemisphere — and
     * set it for a noise tile, where letting the platform wrap is the only way
     * to avoid planting a derivative discontinuity mid-tile by calling
     * `fract()` yourself.
     *
     * **Linear sampling is asked for explicitly**, because Android's default
     * filter mode on an input shader is NEAREST and ignores `Paint`'s own
     * filtering flag. Miss that and every tap is blocky, with no error.
     */
    fun image(name: String, bitmap: ImageBitmap, repeat: Boolean = false)
}
