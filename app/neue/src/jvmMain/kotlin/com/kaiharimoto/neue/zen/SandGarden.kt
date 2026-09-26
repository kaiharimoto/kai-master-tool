package com.kaiharimoto.neue.zen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
 * The whole window is gravel, and the floating deck is its great stone. It
 * opens raked in straight lines. Then a composition is raked over them — one of
 * the classical samon (`RakeGarden`): ripples round the deck and three small
 * stones, flowing water, the blue-sea waves, whirlpools, the checkerboard —
 * by several six-tine rakes at once, from different places, whose work meets
 * across the window until the garden is whole. It is left a moment to be looked
 * at; then a wide rake is drawn across from edge to edge in one sweep, leaving
 * straight lines again, and the next composition begins, never the one just
 * wiped.
 *
 * The rakes are never drawn, on kai's instruction: the pattern draws itself.
 * All that shows of a rake is the gravel it pushes up just ahead of its tines.
 *
 * Nothing is remembered between frames: the garden at any moment is a function
 * of the time. The shader evaluates, per pixel, which groove of which pattern
 * is there and whether a rake has reached it yet — a line-for-line copy of
 * `RakeLayer.phase` and `RakeLayer.reveal`. Gravel just ahead of the fresh
 * edge is heaped a little, the way a rake pushes it. The grain was made in
 * Blender (`tools/zen/garden.py`).
 * White gravel on paper, black on ink, and never a colour.
 */
/**
 * How the garden is raked and lit: everything about its look that is not which
 * pattern it is. [grain] is how coarse the raking is (core's `RakeGrain`, which
 * every pattern is measured in); the rest is how a groove is shaped and lit.
 */
data class GardenLook(
    val grain: RakeGrain = RakeGrain(),
    /** How deep a groove is, against its pitch. */
    val relief: Float = 1.6f,
    /** 0 a round sine; 1 a tine's furrow, a narrow trough under a broad rounded ridge. */
    val profile: Float = 0f,
    /** The light's height (the z of its direction before normalising): lower rakes across the grooves. */
    val lightZ: Float = 0.56f,
    /** How much darker a groove's bottom is than its crest. */
    val cavity: Float = 0.1f,
    /** How big the grains of sand are drawn against the baked texture. */
    val texScale: Float = 1f,
) {
    companion object {
        /** The looks put to kai to choose between: the same garden, raked and lit six ways. */
        val MOCKUPS: Map<String, GardenLook> = linkedMapOf(
            "a-fine" to GardenLook(),
            "b-double" to GardenLook(RakeGrain(spacing = 20f), texScale = 1.4f),
            "c-bold" to GardenLook(RakeGrain(spacing = 32f, tines = 5), texScale = 2f),
            "d-furrow" to GardenLook(RakeGrain(spacing = 20f), relief = 1.8f, profile = 1f, cavity = 0.18f, texScale = 1.4f),
            "e-raking-light" to GardenLook(RakeGrain(spacing = 32f, tines = 5), profile = 1f, lightZ = 0.34f, cavity = 0.2f, texScale = 2f),
            "f-grand" to GardenLook(RakeGrain(spacing = 48f, tines = 4), relief = 1.6f, profile = 1f, lightZ = 0.42f, cavity = 0.2f, texScale = 2.8f),
        )
    }
}

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
        val shader = Textures.shade(frame, stone, ink, look)
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
uniform float uKind;      // the composition's samon: 1 ripples, 2 flowing, 3 waves, 4 whirlpools, 5 checkerboard
uniform float uVariant;
uniform float4 uStone;    // the deck in zen: left, top, right, bottom
uniform float uStoneFirst;// ripples: source 0 is the deck, measured to its edge
uniform float2 uC0;
uniform float2 uC1;
uniform float2 uC2;
uniform float2 uC3;
uniform float uCount;
uniform float uMode;      // 0 straight, composition being raked; 1 composition, sweep; 2 composition; 3 straight
uniform float uT;         // seconds into the layer being raked
uniform float uInk;
uniform float uRelief;    // how deep a groove is, against its pitch
uniform float uProfile;   // 0 a round sine; 1 a tine's furrow: a narrow trough and a broad, rounded ridge
uniform float uLightZ;    // the light's height: lower is a raking light, and deeper shadows in the grooves
uniform float uCavity;    // how much darker the bottom of a groove is than its crest
uniform float uTexScale;  // how big the grains of sand are drawn, against the baked texture

// RakeGrain: the pitch, a pass's band, the rakes' speed, the sweep's, and a checkerboard block.
uniform float S;
uniform float B;
uniform float V;
uniform float VS;
uniform float CELL;

const float PI = 3.14159265;

float boxDist(float2 p, float4 r) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 h = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - c) - h;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
}

float2 centre(int i) {
    if (i == 0) return uC0;
    if (i == 1) return uC1;
    if (i == 2) return uC2;
    return uC3;
}

float dirOf(int i) { return (i - (i / 2) * 2) == 0 ? 1.0 : -1.0; }

float turnOf(float2 d, int i) {
    float a = atan(d.y, d.x) / (2.0 * PI);
    float t = dirOf(i) > 0.0 ? a : -a;
    return t - floor(t);
}

// --- ripples ---------------------------------------------------------------
float3 ripple(float2 p) {   // distance, source, 0
    float best = 1e9;
    float who = 0.0;
    for (int i = 0; i < 4; i++) {
        if (float(i) >= uCount) break;
        float d = (i == 0 && uStoneFirst > 0.5) ? max(0.0, boxDist(p, uStone)) : max(0.0, length(p - centre(i)) - S);
        if (d < best) { best = d; who = float(i); }
    }
    return float3(best, who, 0.0);
}

float lapTime(float laps, int i) {
    float share = 0.5;
    float ring = 2.0 * PI * B / V * share;
    float perimeter = (i == 0 && uStoneFirst > 0.5) ? 2.0 * ((uStone.z - uStone.x) + (uStone.w - uStone.y)) / V * share : 0.0;
    float start = i > 0 ? 2.0 * PI * S / V * share : 0.0;
    return ring * (laps * laps / 2.0 + laps / 2.0) + (perimeter + start) * laps;
}

// --- flowing water -----------------------------------------------------------
float amp() { return (26.0 + 22.0 * uVariant) * B / 60.0; }
float wlen() { return (460.0 + 260.0 * uVariant) * sqrt(B / 60.0); }
float flow(float2 p) { return p.y + amp() * sin(p.x * 2.0 * PI / wlen() + uVariant * 2.0 * PI); }
float flowBands() { return ceil((uSize.y + 2.0 * amp()) / B); }

float passReveal(float order, float fromLeftF, float x) {
    float progress = fromLeftF > 0.5 ? x / uSize.x : 1.0 - x / uSize.x;
    return (order + progress) * (uSize.x / V);
}

float rowsOrder(float band, float count, out float fromLeft) {
    float mid = floor((count - 1.0) / 2.0);
    float order = band <= mid ? band : count - 1.0 - band;
    fromLeft = (order - 2.0 * floor(order / 2.0)) < 0.5 ? 1.0 : 0.0;
    return order;
}

// --- blue-sea waves ------------------------------------------------------------
float scaleDist(float2 p) {
    float base = floor(p.y / B);
    for (int k = 0; k < 4; k++) {
        float row = base + 2.0 - float(k);
        float cy = row * B;
        float odd = row - 2.0 * floor(row / 2.0);
        float offset = odd < 0.5 ? 0.0 : B;
        float i = floor((p.x - offset) / (2.0 * B) + 0.5);
        float cx = offset + i * 2.0 * B;
        float d = length(p - float2(cx, cy));
        if (d < B) return d;
    }
    return 0.0;
}
float scaleRows() { return ceil(uSize.y / B) + 2.0; }

// --- whirlpools ---------------------------------------------------------------
float3 whirl(float2 p) {   // distance, turn, source
    float best = 1e9;
    int who = 0;
    for (int i = 0; i < 4; i++) {
        if (float(i) >= uCount) break;
        float d = length(p - centre(i));
        if (d < best) { best = d; who = i; }
    }
    return float3(best, turnOf(p - centre(who), who), float(who));
}
float spiralTime(float u) { float sp = 2.0 * PI * B / V; return sp * u * u + 0.8 * u; }

// --- the checkerboard -------------------------------------------------------------
float cellsX() { return ceil(uSize.x / CELL); }
float cellsY() { return ceil(uSize.y / CELL); }
float cellLeft() { return (uSize.x - cellsX() * CELL) / 2.0; }
float cellTop() { return (uSize.y - cellsY() * CELL) / 2.0; }
float2 cellOf(float2 p) {
    return float2(clamp(floor((p.x - cellLeft()) / CELL), 0.0, cellsX() - 1.0), clamp(floor((p.y - cellTop()) / CELL), 0.0, cellsY() - 1.0));
}
bool across(float2 c) { float s = c.x + c.y; return (s - 2.0 * floor(s / 2.0)) < 0.5; }
float checkReveal(float2 p) {
    float2 c = cellOf(p);
    float leftCells = floor((cellsX() + 1.0) / 2.0);
    float topCells = floor((cellsY() + 1.0) / 2.0);
    bool right = c.x >= leftCells;
    bool down = c.y >= topCells;
    float dx = right ? cellsX() - 1.0 - c.x : c.x;
    float dy = down ? cellsY() - 1.0 - c.y : c.y;
    float qa = right ? cellsX() - leftCells : leftCells;
    float qd = down ? cellsY() - topCells : topCells;
    float r = max(dx, dy);
    float before = min(r, qa) * min(r, qd);
    float column = r < qa ? min(r, qd - 1.0) + 1.0 : 0.0;
    float index = dx == r ? dy : column + dx;
    float order = before + index;
    float left = cellLeft() + c.x * CELL;
    float top = cellTop() + c.y * CELL;
    float passes = CELL / B;
    bool ac = across(c);
    float lane = clamp(floor((ac ? p.y - top : p.x - left) / B), 0.0, passes - 1.0);
    float along = clamp((ac ? p.x - left : p.y - top) / CELL, 0.0, 1.0);
    float g = (lane - 2.0 * floor(lane / 2.0)) < 0.5 ? along : 1.0 - along;
    float cellTime = passes * (CELL / V);
    return (order + (lane + g) / passes) * cellTime;
}

// --- the composition: its groove, and when it is raked -------------------------------
float compPhase(float2 p) {
    if (uKind < 1.5) return (uStoneFirst > 0.5 && boxDist(p, uStone) < 0.0) ? p.y / S : ripple(p).x / S;
    if (uKind < 2.5) return flow(p) / S;
    if (uKind < 3.5) return scaleDist(p) / S;
    if (uKind < 4.5) { float3 w = whirl(p); return (w.x - 2.0 * w.y * B) / S; }
    return across(cellOf(p)) ? p.y / S : p.x / S;
}

// Rake.sweepTime, sweepX and sweepReveal: the wide rake eases in and out.
float sweepTime() { return (uSize.x + 2.0 * B) / VS * 1.5; }
float sweepX(float t) {
    float u = clamp(t / sweepTime(), 0.0, 1.0);
    return -B + (uSize.x + 2.0 * B) * u * u * (3.0 - 2.0 * u);
}
float sweepReveal(float x) {
    float s = clamp((x + B) / (uSize.x + 2.0 * B), 0.0, 1.0);
    return sweepTime() * (0.5 - sin(asin(1.0 - 2.0 * s) / 3.0));
}

float compReveal(float2 p) {
    if (uKind < 1.5) {
        if (uStoneFirst > 0.5 && boxDist(p, uStone) < 0.0) return 0.0;
        float3 r = ripple(p);
        int src = int(r.y);
        float turn = turnOf(p - centre(src), src);
        float band = floor(r.x / B);
        float frac2 = 2.0 * turn - floor(2.0 * turn);
        return lapTime(band + frac2, src);
    }
    if (uKind < 2.5) {
        float n = flowBands();
        float band = clamp(floor((flow(p) + amp()) / B), 0.0, n - 1.0);
        float fromLeft;
        float order = rowsOrder(band, n, fromLeft);
        return passReveal(order, fromLeft, p.x);
    }
    if (uKind < 3.5) {
        float n = scaleRows();
        float band = clamp(floor(p.y / B) + 1.0, 0.0, n - 1.0);
        float fromLeft;
        float order = rowsOrder(band, n, fromLeft);
        return passReveal(order, fromLeft, p.x);
    }
    if (uKind < 4.5) {
        float3 w = whirl(p);
        float pass = max(ceil(-2.0 * w.y), floor(w.x / B - 2.0 * w.y + 0.5));
        return spiralTime((pass + 2.0 * w.y) / 2.0);
    }
    return checkReveal(p);
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

float compHeight(float2 p) { return groove(compPhase(p)); }
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
    n = normalize(float3(n.xy + g.xy * 0.18, n.z));
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
        Samon.SEIGAIHA -> 3f
        Samon.UZUMAKI -> 4f
        Samon.ICHIMATSU -> 5f
    }

    fun shade(frame: RakeProgram.Frame, stone: GardenRect?, ink: Boolean, look: GardenLook): Shader? {
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
        // The same list of centres the layer circles: the deck first for ripples, then the pebbles.
        val sources = buildList {
            if (composition?.samon == Samon.MIZUMON && stone != null) add(Offset((stone.left + stone.right) / 2f, (stone.top + stone.bottom) / 2f))
            composition?.pebbles?.forEach { add(Offset(it.x, it.y)) }
        }.take(4)
        val linear = SamplingMode.LINEAR
        return RuntimeShaderBuilder(e).apply {
            child("grain", n.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            child("albedo", a.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            uniform("uSize", frame.base.width, frame.base.height)
            uniform("uKind", composition?.let { kindOf(it.samon) } ?: 0f)
            uniform("uVariant", composition?.variant ?: 0f)
            val s = stone
            uniform("uStone", s?.left ?: 0f, s?.top ?: 0f, s?.right ?: 0f, s?.bottom ?: 0f)
            uniform("uStoneFirst", if (composition?.samon == Samon.MIZUMON && s != null) 1f else 0f)
            listOf("uC0", "uC1", "uC2", "uC3").forEachIndexed { i, name ->
                val c = sources.getOrNull(i) ?: Offset.Zero
                uniform(name, c.x, c.y)
            }
            uniform("uCount", sources.size.toFloat())
            uniform("uMode", mode)
            uniform("uT", frame.topTime)
            uniform("uInk", if (ink) 1f else 0f)
            val g = frame.base.grain
            uniform("S", g.spacing)
            uniform("B", g.band)
            uniform("V", g.speed)
            uniform("VS", g.sweepSpeed)
            uniform("CELL", g.cell)
            // Depth grows with the pitch, so a coarser garden is as deep for its size, not flatter.
            uniform("uRelief", look.relief * g.spacing / 10f)
            uniform("uProfile", look.profile)
            uniform("uLightZ", look.lightZ)
            uniform("uCavity", look.cavity)
            uniform("uTexScale", look.texScale)
        }.makeShader()
    }
}
