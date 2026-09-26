package com.kaiharimoto.neue.zen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.kaiharimoto.mastertool.core.layout.GardenRect
import com.kaiharimoto.mastertool.core.layout.RakeGrain
import com.kaiharimoto.mastertool.core.layout.RakeLayer
import com.kaiharimoto.mastertool.core.layout.RakeProgram
import com.kaiharimoto.mastertool.core.layout.Samon
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * The karesansui zen mode rakes under the deck.
 *
 * The whole window is gravel, and the floating deck lies over it like leaves on
 * water. It opens raked in straight lines. Then a gardener plans a composition
 * (`RakeGarden`): stones set by the rules of ishigumi, and perhaps a stream
 * between them. Its rakes lap outward from every stone and along both banks at
 * once, each groove a fixed pitch from the next — the contours of the distance
 * to the stones, which is all a rake can draw — until the waves meet and fold
 * together. It is left a while to be looked at; then a wide rake is drawn across
 * from edge to edge in one sweep, leaving straight lines again, and the next
 * composition is planned.
 *
 * The rakes are never drawn, on kai's instruction: the pattern draws itself.
 * All that shows of a rake is the gravel it pushes up just ahead of its tines.
 *
 * Nothing is remembered between frames: the garden at any moment is a function
 * of the time. The shader evaluates, per pixel, which groove is there and
 * whether a rake has reached it yet — a line-for-line copy of `RakeLayer.field`,
 * `owner`, `phase` and `reveal`. The grain of the sand was made in Blender
 * (`tools/zen/garden.py`). White gravel on paper, black on ink, and never a colour.
 */
@Composable
fun SandGarden(zen: ZenLayer, ink: Boolean, modifier: Modifier = Modifier) {
    // The garden's own clock starts when it does, so every zen opens on straight lines.
    val start = remember { zen.time }
    val garden = remember { Garden() }
    Canvas(modifier.graphicsLayer { alpha = zen.deep.coerceIn(0f, 1f) }) {
        garden.prepare(size.width, size.height, zen.deckInZen.let { GardenRect(it.left, it.top, it.right, it.bottom) }, zen.gardenSeed, zen.gardenLook)
        garden.draw(this, zen.time - start, ink)
    }
}

/**
 * How the garden is raked and lit: everything about its look that is not which
 * pattern it is. [grain] is how coarse the raking is (core's `RakeGrain`, which
 * every pattern is measured in); the rest is how a groove is shaped and lit.
 */
data class GardenLook(
    val grain: RakeGrain = RakeGrain(),
    /** How deep a groove is, against its pitch: shallow, so the garden is a background and not a figure. */
    val relief: Float = 0.8f,
    /** 0 a round sine; 1 a tine's furrow, a narrow trough under a broad rounded ridge. */
    val profile: Float = 0f,
    /** The light's height (the z of its direction before normalising): lower rakes across the grooves. */
    val lightZ: Float = 0.56f,
    /** How much darker a groove's bottom is than its crest. */
    val cavity: Float = 0.04f,
    /** How big the grains of sand are drawn against the baked texture: grown with the rake, so the gravel is seen as near. */
    val texScale: Float = 2f,
    /** How much the grains of sand tilt the light. */
    val grainRelief: Float = 0.12f,
)

internal class Garden {
    private var program: RakeProgram? = null
    private var width = 0f
    private var height = 0f
    private var stone: GardenRect? = null
    private var look = GardenLook()

    fun prepare(w: Float, h: Float, deck: GardenRect, seed: Int?, look: GardenLook = GardenLook()) {
        if (program != null && w == width && h == height && look == this.look) return
        width = w
        height = h
        this.look = look
        stone = deck.takeIf { it.width > 0f && it.height > 0f }
        program = RakeProgram(w, h, stone, seed = seed ?: (System.nanoTime() and 0xFFFF).toInt(), grain = look.grain)
    }

    fun draw(scope: DrawScope, t: Float, ink: Boolean) {
        val frame = program?.at(t) ?: return
        val shader = Textures.shade(frame, ink, look)
        if (shader != null) scope.drawRect(ShaderBrush(shader.asComposeShader()))
    }
}

/** Blender's gravel, and the shader that rakes the garden. */
internal object Textures {
    private fun load(name: String): Image? = runCatching {
        Garden::class.java.getResourceAsStream("/zen/$name")?.use { Image.makeFromEncoded(it.readBytes()) }
    }.getOrNull()

    private val normal: Image? by lazy { load("sand_normal.png") }
    private val albedo: Image? by lazy { load("sand_albedo.png") }

    /*
     * RakeLayer.phase and RakeLayer.reveal, in SkSL. Keep the two in step: every
     * branch here has its twin in RakeGarden.kt, in the same order, and
     * RakeGardenTest pins the Kotlin side. Integer arithmetic there is float here
     * with floor, which agrees for the non-negative values both sides use.
     */
    private const val SKSL = """
uniform shader grain;
uniform shader albedo;
uniform float2 uSize;
uniform float uKind;       // the composition: 1 ripples, 2 flowing water, 3 islands
uniform float4 uStones[8]; // each stone: x, y, radius, pace
uniform float uCount;      // how many stones
uniform float4 uRiver;     // the stream: y0, a1, l1, p1
uniform float4 uRiver2;    // a2, l2, p2, and 1 when there is a stream
uniform float uRings;      // islands: bands of rings before the straight lines resume
uniform float uMode;       // 0 straight, composition being raked; 1 composition, sweep; 2 composition; 3 straight
uniform float uT;          // seconds into the layer being raked
uniform float uInk;
uniform float uRelief;     // how deep a groove is, against its pitch
uniform float uProfile;    // 0 a round sine; 1 a tine's furrow: a narrow trough and a broad, rounded ridge
uniform float uLightZ;     // the light's height: lower is a raking light, and deeper shadows in the grooves
uniform float uCavity;     // how much darker the bottom of a groove is than its crest
uniform float uTexScale;   // how big the grains of sand are drawn, against the baked texture
uniform float uGrainRelief; // how much the grains of sand tilt the light

// RakeGrain: the pitch, a pass's band, the rakes' speed, the sweep's, and the fillet where waves meet.
uniform float S;
uniform float B;
uniform float V;
uniform float VS;
uniform float K;

const float PI = 3.14159265;
const float TAU = 6.2831853;

float frac1(float v) { return v - floor(v); }

// RakeLayer.smin: exactly min(a, b) once they differ by k, a rounded fillet within it.
float smin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

// GardenRiver.at, slope and distance.
float riverAt(float x) { return uRiver.x + uRiver.y * sin(TAU * x / uRiver.z + uRiver.w) + uRiver2.x * sin(TAU * x / uRiver2.y + uRiver2.z); }
float riverSlope(float x) { return uRiver.y * TAU / uRiver.z * cos(TAU * x / uRiver.z + uRiver.w) + uRiver2.x * TAU / uRiver2.y * cos(TAU * x / uRiver2.y + uRiver2.z); }
float riverDist(float2 p) { float s = riverSlope(p.x); return (p.y - riverAt(p.x)) / sqrt(1.0 + s * s); }

// RakeLayer.field and RakeLayer.owner at once: (field, owner), the owner 8 below the stream and 9 above.
float2 fieldOwner(float2 p) {
    float f = 0.0;
    float best = 1e9;
    float who = 0.0;
    bool first = true;
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uCount) break;
        float4 s = uStones[i];
        float d = max(0.0, length(p - s.xy) - s.z);
        f = first ? d : smin(f, d, K);
        first = false;
        if (d < best) { best = d; who = float(i); }
    }
    if (uRiver2.w > 0.5) {
        float sd = riverDist(p);
        float d = abs(sd);
        f = first ? d : smin(f, d, K);
        if (d < best) { best = d; who = sd >= 0.0 ? 8.0 : 9.0; }
    }
    return float2(f, who);
}

bool onStone(float2 p) {
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uCount) break;
        if (length(p - uStones[i].xy) < uStones[i].z) return true;
    }
    return false;
}

// uStones[who], by a loop: an index that is not a loop's own may not reach a uniform array.
float4 stoneAt(float who) {
    float4 s = uStones[0];
    for (int i = 0; i < 8; i++) {
        if (float(i) == who) s = uStones[i];
    }
    return s;
}

float dirOf(float i) { return (i - 2.0 * floor(i / 2.0)) < 0.5 ? 1.0 : -1.0; }

float turnOf(float2 d, float i) {
    float a = atan(d.y, d.x) / TAU;
    return frac1(dirOf(i) > 0.0 ? a : -a);
}

float lapTime(float laps, float r) {
    float ring = TAU * B / V * 0.5;
    float start = TAU * r / V * 0.5;
    return ring * (laps * laps / 2.0 + laps / 2.0) + start * laps;
}

bool beyondRings(float f) { return uKind > 2.5 && f >= uRings * B; }

// --- the composition: its groove, and when it is raked -------------------------------
float compPhase(float2 p) {
    float f = fieldOwner(p).x;
    return beyondRings(f) ? p.y / S : f / S;
}

float compReveal(float2 p) {
    float2 fo = fieldOwner(p);
    if (beyondRings(fo.x) || onStone(p)) return 0.0;
    float band = max(0.0, floor(fo.x / B));
    if (fo.y > 7.5) {
        float progress = (band - 2.0 * floor(band / 2.0)) < 0.5 ? p.x / uSize.x : 1.0 - p.x / uSize.x;
        return (band + progress) * (uSize.x / V);
    }
    float4 s = stoneAt(fo.y);
    float turn = turnOf(p - s.xy, fo.y);
    return lapTime(band + frac1(2.0 * turn), s.z) * s.w;
}

// RakeGrain.sweepTime, sweepX and sweepReveal: the wide rake eases in and out.
float sweepTime() { return (uSize.x + 2.0 * B) / VS * 1.5; }
float sweepX(float t) {
    float u = clamp(t / sweepTime(), 0.0, 1.0);
    return -B + (uSize.x + 2.0 * B) * u * u * (3.0 - 2.0 * u);
}
float sweepReveal(float x) {
    float s = clamp((x + B) / (uSize.x + 2.0 * B), 0.0, 1.0);
    return sweepTime() * (0.5 - sin(asin(1.0 - 2.0 * s) / 3.0));
}

// --- the gravel -----------------------------------------------------------------
// A trough at every half line, where a tine runs, and a ridge pushed up at every whole one.
float groove(float phase) {
    float sine = pow(0.5 + 0.5 * cos(2.0 * PI * phase), 0.8);
    // The furrow: steep where the tine cut, rounding over into a broad crest.
    float d = abs(fract(phase) - 0.5) * 2.0;
    float furrow = sqrt(d) * (1.5 - 0.5 * d);
    return mix(sine, furrow, uProfile);
}

// A stone's own ground is left smooth, level with the ridge the first ring leaves round it.
// Where an island's rings end, the straight lines run up into the crest of its last ring
// rather than stopping at a step, the way the gravel is left where a pass turns back.
float compHeight(float2 p) {
    if (onStone(p)) return 1.0;
    float f = fieldOwner(p).x;
    if (beyondRings(f)) return mix(1.0, groove(p.y / S), smoothstep(0.0, 0.6 * S, f - uRings * B));
    return groove(f / S);
}
float straightHeight(float2 p) { return groove(p.y / S); }

// The height at p, and how far a rake is from reaching it (seconds; ≤ 0 once raked).
float heightAt(float2 p, out float ahead) {
    ahead = 1e9;
    if (uMode < 0.5) {
        float r = compReveal(p);
        ahead = r - uT;
        return ahead <= 0.0 ? compHeight(p) : straightHeight(p);
    }
    if (uMode < 1.5) {
        float r = sweepReveal(p.x);
        ahead = r - uT;
        return ahead <= 0.0 ? straightHeight(p) : compHeight(p);
    }
    if (uMode < 2.5) return compHeight(p);
    return straightHeight(p);
}

half4 main(float2 p) {
    float ahead;
    float h = heightAt(p, ahead);
    float a1; float a2; float a3; float a4;
    float hx = heightAt(p + float2(1.0, 0.0), a1) - heightAt(p - float2(1.0, 0.0), a2);
    float hy = heightAt(p + float2(0.0, 1.0), a3) - heightAt(p - float2(0.0, 1.0), a4);
    // Gravel a rake is about to reach is heaped up in front of it.
    // In pixels ahead of the rake; bounded before squaring, since a layer with no
    // rake in it says 1e9, which overflows to NaN on the CPU.
    float reach = (uMode > 0.5 && uMode < 1.5 ? clamp(p.x - sweepX(uT), 0.0, B) : clamp(ahead, 0.0, 1.0) * V) / (0.9 * S);
    float heap = ahead > 0.0 ? exp(-reach * reach) : 0.0;
    float3 n = normalize(float3(-hx * uRelief, -hy * uRelief + heap * 0.6, 1.0));
    float3 g = grain.eval(p / uTexScale).rgb * 2.0 - 1.0;
    g.y = -g.y;
    n = normalize(float3(n.xy + g.xy * uGrainRelief, n.z));
    float3 L = normalize(float3(-0.5, -0.66, uLightZ));
    // Lit so flat sand is the same brightness whatever the light's height: only slopes change.
    float diff = max(dot(n, L), 0.0) / L.z;
    float a = mix(0.93, albedo.eval(p / uTexScale).r, 0.35);
    float lum = a * (0.74 + 0.42 * 0.56 * diff) * mix(1.0 - uCavity, 1.0, h) * (1.0 + 0.05 * heap);
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

    private fun kindOf(samon: Samon): Float = when (samon) {
        Samon.CHOKUSEN -> 0f
        Samon.MIZUMON -> 1f
        Samon.RYUSUI -> 2f
        Samon.SHIMA -> 3f
    }

    fun shade(frame: RakeProgram.Frame, ink: Boolean, look: GardenLook): Shader? {
        val e = effect ?: return null
        val n = normal ?: return null
        val a = albedo ?: return null
        val composition: RakeLayer? = listOf(frame.top, frame.base).firstOrNull { it != null && it.samon != Samon.CHOKUSEN }
        val top = frame.top
        val mode = when {
            top != null && top.samon != Samon.CHOKUSEN -> 0f
            top != null -> 1f
            frame.base.samon != Samon.CHOKUSEN -> 2f
            else -> 3f
        }
        val stones = FloatArray(MAX_STONES * 4)
        composition?.stones?.take(MAX_STONES)?.forEachIndexed { i, s ->
            stones[i * 4] = s.x
            stones[i * 4 + 1] = s.y
            stones[i * 4 + 2] = s.r
            stones[i * 4 + 3] = composition.pace[i]
        }
        val river = composition?.river
        val linear = SamplingMode.LINEAR
        return RuntimeShaderBuilder(e).apply {
            child("grain", n.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            child("albedo", a.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            uniform("uSize", frame.base.width, frame.base.height)
            uniform("uKind", composition?.let { kindOf(it.samon) } ?: 0f)
            uniform("uStones", stones)
            uniform("uCount", minOf(composition?.stones?.size ?: 0, MAX_STONES).toFloat())
            uniform("uRiver", river?.y0 ?: 0f, river?.a1 ?: 0f, river?.l1 ?: 1f, river?.p1 ?: 0f)
            uniform("uRiver2", river?.a2 ?: 0f, river?.l2 ?: 1f, river?.p2 ?: 0f, if (river != null) 1f else 0f)
            uniform("uRings", (composition?.rings ?: 0).toFloat())
            uniform("uMode", mode)
            uniform("uT", frame.topTime)
            uniform("uInk", if (ink) 1f else 0f)
            val g = frame.base.grain
            uniform("S", g.spacing)
            uniform("B", g.band)
            uniform("V", g.speed)
            uniform("VS", g.sweepSpeed)
            uniform("K", g.blend)
            // Depth grows with the pitch, so a coarser garden is as deep for its size, not flatter.
            uniform("uRelief", look.relief * g.spacing / 10f)
            uniform("uProfile", look.profile)
            uniform("uLightZ", look.lightZ)
            uniform("uCavity", look.cavity)
            uniform("uTexScale", look.texScale)
            uniform("uGrainRelief", look.grainRelief)
        }.makeShader()
    }

    /** As many stones as the shader holds; the composer never places more than seven. */
    private const val MAX_STONES = 8
}
