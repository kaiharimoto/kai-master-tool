package com.kaiharimoto.mastertool.ui.gpu

import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Android's half of the seam.
 *
 * `RuntimeShader` arrived in API 33 and this app's `minSdk` is 26, so the
 * version gate is not a formality — it is the majority of the fallback's reason
 * to exist. The Tab S11 this is tuned on is far past it; a five-year-old phone
 * is not, and `AAA.md` #98 is explicit that the app should not discover a
 * device's limits by dropping frames on somebody.
 *
 * `RuntimeShader` *is* an `android.graphics.Shader`, and `Shader` is what
 * `androidx.compose.ui.graphics.Shader` is a typealias for here — so the brush
 * is one constructor call with nothing to bridge. Desktop is the awkward side.
 */
actual class StageShader internal constructor(internal val shader: RuntimeShader)

actual fun compileStageShader(sksl: String): StageShader? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    // Compilation failure is a runtime throw here rather than a returned error,
    // and it is caught rather than allowed out: a shader that will not build is
    // a scene drawn the old way, never a crash on somebody's tablet.
    return runCatching { StageShader(RuntimeShader(sksl)) }.getOrNull()
}

actual fun StageShader.brush(uniforms: ShaderUniforms.() -> Unit): Brush {
    // Uniforms are set on the shader itself, which is mutable and re-usable —
    // so a compiled shader is allocated once for the life of the screen and
    // every frame only writes floats into it.
    AndroidUniforms(shader).uniforms()
    return ShaderBrush(shader)
}

actual val runtimeShadersAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

actual class StageEffect internal constructor(internal val shader: RuntimeShader)

actual fun compileStageEffect(sksl: String): StageEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    return runCatching { StageEffect(RuntimeShader(sksl)) }
        // Loud, once, and never per frame. See the desktop actual: a shader
        // that fails to compile is indistinguishable from one that compiled and
        // drew the identity, and the silence has cost this project two
        // debugging rounds. The version gate above returns before this, so a
        // device that simply has no runtime shaders says nothing.
        .onFailure { Log.w("StageShader", "shader did not compile", it) }
        .getOrNull()
}

actual fun StageEffect.effect(uniforms: ShaderUniforms.() -> Unit): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    AndroidUniforms(shader).uniforms()
    // The name is the contract, and it is Android's that is fixed: this binds
    // whatever the layer would have drawn to the shader's `uniform shader
    // content`. Desktop names its children explicitly and is matched to it.
    return runCatching {
        android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }.getOrNull()
}

private class AndroidUniforms(private val shader: RuntimeShader) : ShaderUniforms {
    override fun float(name: String, value: Float) = shader.setFloatUniform(name, value)
    override fun float2(name: String, x: Float, y: Float) = shader.setFloatUniform(name, x, y)
    override fun float3(name: String, x: Float, y: Float, z: Float) =
        shader.setFloatUniform(name, x, y, z)
    override fun float4(name: String, x: Float, y: Float, z: Float, w: Float) =
        shader.setFloatUniform(name, x, y, z, w)

    override fun colour(name: String, red: Float, green: Float, blue: Float, alpha: Float) =
        shader.setFloatUniform(name, red, green, blue, alpha)

    override fun matrix3(name: String, columnMajor: FloatArray) {
        require(columnMajor.size == 9) { "a mat3 is nine floats, not ${columnMajor.size}" }
        // The `FloatArray` overload. `setFloatUniform`'s scalar overloads stop at
        // four, so nine of them individually is not reachable at all — and the
        // `setFloatUniforms` this was first written against, which
        // `docs/classic/PHOTOREAL.md` §0 names as "the plural method", **does not exist**
        // on `RuntimeShader` at compileSdk 36. It was a plan's guess and it did
        // not survive a compiler. Checked against the platform jar rather than
        // remembered.
        shader.setFloatUniform(name, columnMajor)
    }

    override fun image(name: String, bitmap: ImageBitmap, repeat: Boolean) {
        val tile = if (repeat) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
        shader.setInputShader(
            name,
            BitmapShader(bitmap.asAndroidBitmap(), tile, tile).apply {
                // Not optional, and the failure is silent. An input shader's
                // default filter is NEAREST and it does not consult the Paint's
                // own filtering flag, so a probe sampled at a computed
                // coordinate comes back blocky with no error anywhere.
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            },
        )
    }
}
