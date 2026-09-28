package com.kaiharimoto.mastertool.ui.gpu

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/**
 * The desktop half of the seam.
 *
 * Skia's own runtime effects, which Skiko exposes and Compose bridges with
 * `asComposeShader()`. No version gate: every desktop build ships the Skia it
 * was compiled against, so if it compiled here it works here.
 *
 * The one thing worth knowing, and it is the reason this was worth proving
 * before anything was built on it: **this rasters without a GPU.** Skia runs
 * SkSL on the CPU backend too, so `:studio` — which draws with
 * `skiko.renderApi=SOFTWARE` in a container with no GL context at all — sees
 * exactly what a tablet sees. A shader the loop's own eye could not look at
 * would have put every per-pixel change back behind a signed release.
 *
 * Unlike Android, uniforms cannot be written into a finished shader: the builder
 * holds them and mints a new immutable `Shader` each time. That is one small
 * allocation per draw of one surface per frame, which is nothing against the
 * hundreds of `Path` objects the shadow pass already makes — but it is the
 * reason [brush] takes the uniforms rather than being a property.
 */
actual class StageShader internal constructor(internal val builder: RuntimeShaderBuilder)

actual fun compileStageShader(sksl: String): StageShader? =
    runCatching { StageShader(RuntimeShaderBuilder(RuntimeEffect.makeForShader(sksl))) }
        .getOrNull()

actual fun StageShader.brush(uniforms: ShaderUniforms.() -> Unit): Brush {
    DesktopUniforms(builder).uniforms()
    return ShaderBrush(builder.makeShader().asComposeShader())
}

actual val runtimeShadersAvailable: Boolean get() = true

actual class StageEffect internal constructor(internal val builder: RuntimeShaderBuilder)

actual fun compileStageEffect(sksl: String): StageEffect? =
    runCatching { StageEffect(RuntimeShaderBuilder(RuntimeEffect.makeForShader(sksl))) }
        // Loud, once, and never per frame. A shader that fails to compile is
        // indistinguishable from one that compiled and drew the identity — the
        // fallback is deliberate and the silence is not. It has now cost two
        // debugging rounds in this project: iteration 5's un-negated light, and
        // a lens whose whole grade was a literal `${'$'}{...}` left in the source,
        // which produced a picture that looked entirely plausible.
        .onFailure { println("[stage-effect] $sksl\n$it") }
        .getOrNull()

actual fun StageEffect.effect(uniforms: ShaderUniforms.() -> Unit): RenderEffect? {
    DesktopUniforms(builder).uniforms()
    // `"content"` rather than a name of our own choosing: Android's
    // `createRuntimeShaderEffect` fixes it, and one shader string has to compile
    // on both. The null crop means "the whole thing", which is what a
    // full-screen finishing pass wants.
    return runCatching {
        ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
    }.getOrNull()
}

private class DesktopUniforms(private val builder: RuntimeShaderBuilder) : ShaderUniforms {
    override fun float(name: String, value: Float) = builder.uniform(name, value)
    override fun float2(name: String, x: Float, y: Float) = builder.uniform(name, x, y)
    override fun float3(name: String, x: Float, y: Float, z: Float) =
        builder.uniform(name, x, y, z)
    override fun float4(name: String, x: Float, y: Float, z: Float, w: Float) =
        builder.uniform(name, x, y, z, w)

    // No `setColorUniform` equivalent here, so the four floats go in as they
    // are. That is also the honest description of what Android's does once the
    // destination transform is the identity, which it is for every surface this
    // app draws.
    override fun colour(name: String, red: Float, green: Float, blue: Float, alpha: Float) =
        builder.uniform(name, red, green, blue, alpha)

    // The `FloatArray` overload rather than the `Matrix33` one, and deliberately.
    // `Matrix33` documents itself as row-major and an SkSL `mat3` is
    // column-major, so going through it means asserting which side transposes —
    // a question whose wrong answer is a shader that works and is silently
    // transposed, which no compile and no null check would catch. Nine floats
    // straight through is the same expression Android's `setFloatUniforms`
    // takes, so both platforms agree by construction instead of by argument.
    override fun matrix3(name: String, columnMajor: FloatArray) {
        require(columnMajor.size == 9) { "a mat3 is nine floats, not ${columnMajor.size}" }
        builder.uniform(name, columnMajor)
    }

    override fun image(name: String, bitmap: ImageBitmap, repeat: Boolean) {
        val tile = if (repeat) FilterTileMode.REPEAT else FilterTileMode.CLAMP
        builder.child(
            name,
            Image.makeFromBitmap(bitmap.asSkiaBitmap())
                .makeShader(tile, tile, SamplingMode.LINEAR, null),
        )
    }
}
