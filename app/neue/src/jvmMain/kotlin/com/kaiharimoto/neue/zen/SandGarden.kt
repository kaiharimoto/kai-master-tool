package com.kaiharimoto.neue.zen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.kaiharimoto.mastertool.core.layout.SpiralGarden
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * The garden zen mode draws under the deck: Fibonacci spirals, drawn over each
 * other forever (`SpiralGarden`).
 *
 * The whole window is gravel and the floating deck sits where a sunflower's head
 * would be. The sand blooms outward from under it forever — a golden spiral
 * zoomed is a golden spiral turned — and the sun goes slowly round it. Into the
 * flowing sand, arms of golden spiral are drawn outward one by one,
 * setting off in golden-ratio order so they are always evenly spread; when every
 * arm has reached the far corner, the other family — 55 after 34, turning the
 * other way, set round by the golden angle — is drawn over the top, its arms
 * crossing the old ones and replacing them as they grow. It never stops and
 * never wipes. It is shallow on purpose: a grain in the gravel, not a figure.
 *
 * Nothing is remembered between frames: the garden at any moment is a function
 * of the time. The shader evaluates, per pixel, a line-for-line copy of
 * `SpiralGarden.phase`, `spacing`, `start`, `past` and `weight`. The grain of the sand
 * was made in Blender (`tools/zen/garden.py`). White gravel on paper, black on
 * ink, and never a colour.
 */
@Composable
fun SandGarden(zen: ZenLayer, ink: Boolean, modifier: Modifier = Modifier) {
    // The garden's own clock starts when it does, so every zen opens on straight lines.
    val start = remember { zen.time }
    val garden = remember { Garden() }
    Canvas(modifier.graphicsLayer { alpha = zen.deep.coerceIn(0f, 1f) }) {
        val matte = zen.gardenMatte
        if (matte != null) {
            drawRect(if (matte) Color.White else Color.Black)
            return@Canvas
        }
        val deck = zen.deckInZen
        val centre = if (deck.width > 0f) deck.center else center
        garden.prepare(size.width, size.height, centre.x, centre.y)
        garden.draw(this, zen.time - start, ink, zen.gardenLook)
    }
}

/** How the gravel is lit: everything about the garden's look that is not the pattern. */
data class GardenLook(
    /** How deep a groove is: shallow, so the garden is a texture and not a figure. */
    val relief: Float = 0.45f,
    /** The light's height (the z of its direction before normalising). */
    val lightZ: Float = 0.56f,
    /** How much darker a groove's bottom is than its crest. */
    val cavity: Float = 0.04f,
    /** How big the grains of sand are drawn against the baked texture. */
    val texScale: Float = 2f,
    /** How much the grains of sand tilt the light. */
    val grainRelief: Float = 0.12f,
)

internal class Garden {
    private var garden: SpiralGarden? = null

    fun prepare(w: Float, h: Float, centreX: Float, centreY: Float) {
        val g = garden
        if (g != null && g.width == w && g.height == h && g.centreX == centreX && g.centreY == centreY) return
        garden = SpiralGarden(w, h, centreX, centreY)
    }

    fun draw(scope: DrawScope, t: Float, ink: Boolean, look: GardenLook = GardenLook()) {
        val g = garden ?: return
        val shader = Textures.shade(g, t, ink, look)
        if (shader != null) scope.drawRect(ShaderBrush(shader.asComposeShader()))
    }
}

/** Blender's gravel, and the shader that draws the spirals in it. */
internal object Textures {
    private fun load(name: String): Image? = runCatching {
        Garden::class.java.getResourceAsStream("/zen/$name")?.use { Image.makeFromEncoded(it.readBytes()) }
    }.getOrNull()

    private val normal: Image? by lazy { load("sand_normal.png") }
    private val albedo: Image? by lazy { load("sand_albedo.png") }

    /*
     * SpiralGarden's phase, spacing, start and weight, in SkSL. Keep the two in
     * step: every function here has its twin there, and SpiralGardenTest pins the
     * Kotlin side. A layer travels as (arms, hand, turn).
     */
    private const val SKSL = """
uniform shader grain;
uniform shader albedo;
uniform float2 uCentre;     // the middle of the deck: the flower's head
uniform float3 uNew;        // the layer being drawn: arms, hand, turn
uniform float3 uOld;        // the layer beneath it
uniform float uOldStraight; // 1 while the first layer is drawn, over straight lines
uniform float uTau;         // seconds into the layer being drawn
uniform float2 uZoom;       // how far the sand has flowed since each layer began: new, old
uniform float4 uSpiral;     // B, ARC, GROWTH, R_IN
uniform float uFlow;        // FLOW
uniform float uSun;         // the sun's azimuth: the direction the gravel is lit from
uniform float3 uTiming;     // STAGGER, FADE, PHI
uniform float uPitch;       // the straight lines' pitch
uniform float uInk;
uniform float uRelief;      // how deep a groove is
uniform float uLightZ;      // the light's height
uniform float uCavity;      // how much darker the bottom of a groove is than its crest
uniform float uTexScale;    // how big the grains of sand are drawn, against the baked texture
uniform float uGrainRelief; // how much the grains of sand tilt the light

const float TAU = 6.2831853;

float radius(float2 p) { return max(1.0, length(p - uCentre)); }

float phaseOf(float3 l, float2 p, float zoom) {
    float theta = atan(p.y - uCentre.y, p.x - uCentre.x);
    return l.x * (theta - l.y * uSpiral.x * (log(radius(p)) - zoom) - l.z) / TAU;
}

float spacingOf(float3 l, float2 p) { return TAU * radius(p) / (l.x * uSpiral.y); }

float startOf(float3 l, float strip) {
    float f = mod(strip, l.x) / uTiming.z;
    return uTiming.x * (f - floor(f));
}

// SpiralGarden.past, measured in the flowing sand.
float settled(float3 l, float strip, float2 p) {
    float past = uTau - startOf(l, strip) - (log(radius(p)) - uFlow * uTau - log(uSpiral.w)) / uSpiral.z;
    float u = clamp(past / uTiming.y, 0.0, 1.0);
    return u * u * (3.0 - 2.0 * u);
}

float weightOf(float3 l, float2 p) {
    float ph = phaseOf(l, p, uZoom.x);
    float k = floor(ph);
    float u = ph - k - 0.5;
    float own = settled(l, k, p);
    float next = settled(l, k + (u >= 0.0 ? 1.0 : -1.0), p);
    float e = clamp((abs(u) - 0.3) / 0.2, 0.0, 1.0);
    return own * (1.0 - e) + e * (own + next) * 0.5;
}

// A trough at every half line; smoothed where the grooves crowd below a few pixels,
// as they do toward the flower's head, rather than drawn as a shimmer.
float heightOf(float phase, float spacing) {
    float amp = clamp((spacing - 12.0) / 14.0, 0.0, 1.0);
    float h = pow(0.5 + 0.5 * cos(TAU * phase), 0.8);
    return 0.5 + (h - 0.5) * amp;
}

float heightAt(float2 p) {
    float fresh = heightOf(phaseOf(uNew, p, uZoom.x), spacingOf(uNew, p));
    float old = uOldStraight > 0.5 ? heightOf(p.y / uPitch, uPitch) : heightOf(phaseOf(uOld, p, uZoom.y), spacingOf(uOld, p));
    return mix(old, fresh, weightOf(uNew, p));
}

half4 main(float2 p) {
    float h = heightAt(p);
    float hx = heightAt(p + float2(1.0, 0.0)) - heightAt(p - float2(1.0, 0.0));
    float hy = heightAt(p + float2(0.0, 1.0)) - heightAt(p - float2(0.0, 1.0));
    float3 n = normalize(float3(-hx * uRelief, -hy * uRelief, 1.0));
    float3 g = grain.eval(p / uTexScale).rgb * 2.0 - 1.0;
    g.y = -g.y;
    n = normalize(float3(n.xy + g.xy * uGrainRelief, n.z));
    // The sun, at the height the gravel has always been lit from, going round.
    float3 L = normalize(float3(0.828 * cos(uSun), 0.828 * sin(uSun), uLightZ));
    // Lit so flat sand is the same brightness whatever the light's height: only slopes change.
    float diff = max(dot(n, L), 0.0) / L.z;
    float a = mix(0.93, albedo.eval(p / uTexScale).r, 0.35);
    float lum = a * (0.74 + 0.42 * 0.56 * diff) * mix(1.0 - uCavity, 1.0, h);
    lum = min(lum, 1.0);
    // Ink: black gravel, with the contrast lifted a little so the grooves survive the dark.
    if (uInk > 0.5) lum = pow(lum, 1.8) * 0.3;
    return half4(half3(lum), 1.0);
}
"""

    /** Why the shader will not compile, or null when it does: a test holds it to null, since the app only logs it. */
    fun compileError(): String? = runCatching { RuntimeEffect.makeForShader(SKSL) }.exceptionOrNull()?.message

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(SKSL) }.onFailure { println("[zen] garden shader: ${it.message}") }.getOrNull()
    }

    fun shade(garden: SpiralGarden, t: Float, ink: Boolean, look: GardenLook): Shader? {
        val moment = garden.at(t)
        val e = effect ?: return null
        val n = normal ?: return null
        val a = albedo ?: return null
        val fresh = garden.layer(moment.layer)
        val old = garden.layer(maxOf(0, moment.layer - 1))
        val linear = SamplingMode.LINEAR
        return RuntimeShaderBuilder(e).apply {
            child("grain", n.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            child("albedo", a.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            uniform("uCentre", garden.centreX, garden.centreY)
            uniform("uNew", fresh.arms.toFloat(), fresh.hand, fresh.turn)
            uniform("uOld", old.arms.toFloat(), old.hand, old.turn)
            uniform("uOldStraight", if (moment.layer == 0) 1f else 0f)
            uniform("uTau", moment.tau)
            uniform("uZoom", SpiralGarden.FLOW * moment.tau, SpiralGarden.FLOW * (moment.tau + garden.layerTime))
            uniform("uSpiral", SpiralGarden.B, SpiralGarden.ARC, SpiralGarden.GROWTH, SpiralGarden.R_IN)
            uniform("uFlow", SpiralGarden.FLOW)
            uniform("uSun", garden.sunAzimuth(t))
            uniform("uTiming", SpiralGarden.STAGGER, SpiralGarden.FADE, SpiralGarden.PHI.toFloat())
            uniform("uPitch", SpiralGarden.PITCH)
            uniform("uInk", if (ink) 1f else 0f)
            // As deep for its pitch as the look says, measured against a ten-pixel groove.
            uniform("uRelief", look.relief * SpiralGarden.PITCH / 10f)
            uniform("uLightZ", look.lightZ)
            uniform("uCavity", look.cavity)
            uniform("uTexScale", look.texScale)
            uniform("uGrainRelief", look.grainRelief)
        }.makeShader()
    }
}
