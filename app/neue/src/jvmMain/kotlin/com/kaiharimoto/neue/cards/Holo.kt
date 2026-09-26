package com.kaiharimoto.neue.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.ui.gpu.StageShader
import com.kaiharimoto.mastertool.ui.gpu.brush
import com.kaiharimoto.mastertool.ui.gpu.compileStageShader
import kotlin.math.max

/**
 * The holographic foil border — kai's pick of the Blender mockups (variant C),
 * refined as variant G in `tools/foil/`, and this is the same model per pixel.
 *
 * A foil stamp is a mirror with a diffraction grating pressed into it. Here the
 * grooves are concentric rings about the card's centre, so the direction
 * *across* them at any point is radial, `g = normalize(p)`. Four terms:
 *
 * 1. **Silver.** A neutral metal with an anisotropic highlight: tight along the
 *    grooves, loose across them, so the reflection of the key light is a bright
 *    streak that slides round the border as the pointer moves.
 * 2. **Diffraction.** The grating equation for one light: order m shows the
 *    wavelength `λ = d · |(L + V) · g| / m`. Three orders, d = 1600 nm, added
 *    on top of the metal as light — which is why the colour moves rather than
 *    sits, and why at most angles most of the band is plain silver.
 * 3. **Grooves.** Fine concentric rings jitter the diffraction and the
 *    brightness a little, so the rainbow breaks into striations instead of
 *    smearing. Their pitch never drops below three pixels: finer than that is
 *    moiré, not foil.
 * 4. **Rim.** The inner edge of the stamp is a bevel lit by the same key, bright
 *    where it faces the light and shadowed where it faces away, with an ink
 *    hairline where foil meets print.
 *
 * The eye is not at infinity: V is computed per pixel from an eye a couple of
 * card-widths away, moved by the pointer (a mouse stands in for tilting the
 * card). So even at rest the colour varies round the band the way it does on a
 * real card under a lamp.
 *
 * Colour is permitted here and only here (with `GroupMarkers.kt`): the foil is
 * part of the card, not the chrome. `MasterUiLawTest` names this file.
 */
object Holo {

    /** Band width as a fraction of the card's width — the original CSS `padding: 4%`. */
    const val BAND = 0.04f

    private const val SKSL = """
uniform float2 uSize;     // the card, px
uniform float2 uFeel;     // pointer across the card, -1..1 each way; 0 at rest
uniform float uBand;      // band width, px
uniform float uGroove;    // groove pitch, px
uniform float uBevel;     // rim bevel width, px
uniform float uLine;      // ink hairline width, px

// Wavelength (nm) to linear-ish RGB: Zucconi's six-bump fit to the CIE curves,
// faded at the ends so orders beyond the visible go dark rather than clamp.
float3 bump3(float3 x, float3 y) { return saturate(1.0 - x * x - y); }
float3 spectrum(float w) {
    float x = saturate((w - 400.0) / 300.0);
    float3 c1 = float3(3.54585104, 2.93225262, 2.41593945);
    float3 x1 = float3(0.69549072, 0.49228336, 0.27699880);
    float3 y1 = float3(0.02312639, 0.15225084, 0.52607955);
    float3 c2 = float3(3.90307140, 3.21182957, 3.96587128);
    float3 x2 = float3(0.11748627, 0.86755042, 0.66077860);
    float3 y2 = float3(0.84897130, 0.88445281, 0.73949448);
    float edge = smoothstep(380.0, 420.0, w) * (1.0 - smoothstep(680.0, 760.0, w));
    return (bump3(c1 * (x - x1), y1) + bump3(c2 * (x - x2), y2)) * edge;
}

// Ward-style anisotropic highlight: tight along the grooves (t), loose across (g).
float streak(float3 L, float3 V, float3 t, float3 g, float3 N) {
    float3 H = normalize(L + V);
    float ht = dot(H, t) / 0.05;
    float hg = dot(H, g) / 0.38;
    float hn = max(dot(H, N), 0.2);
    return exp(-(ht * ht + hg * hg) / (hn * hn));
}

// The grating equation, orders 1..3, d = 1600 nm: order m shows lambda = d * s / m.
float3 grating(float s) {
    return spectrum(1600.0 * s) + 0.45 * spectrum(800.0 * s) + 0.2 * spectrum(533.33 * s);
}

// Signed distance to a centred box of half-size b: negative inside.
float box(float2 p, float2 b) {
    float2 q = abs(p) - b;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
}

half4 main(float2 at) {
    float w = uSize.x;
    float2 c = uSize * 0.5;
    float2 pc = at - c;                       // px from the centre, y down
    float2 inner = c - uBand;                 // half-size of the print
    float d = box(pc, inner);                 // > 0 on the band, < 0 on the print

    // The print side of the edge: only the ink hairline.
    if (d < 0.0) {
        float a = 1.0 - smoothstep(uLine * 0.5, uLine, -d);
        return half4(0.0, 0.0, 0.0, 0.45 * a);
    }

    // Card space in card-widths: centre origin, x right, y down, z toward the eye.
    float3 P = float3(pc / w, 0.0);
    float3 L = normalize(float3(-0.55, -0.75, 0.95));                 // key, upper left
    float3 eye = float3(uFeel.x * 1.4, uFeel.y * 1.4, 2.0);
    float3 V = normalize(eye - P);
    float3 N = float3(0.0, 0.0, 1.0);

    float r = length(pc);
    float2 g2 = r > 0.5 ? pc / r : float2(1.0, 0.0);
    float3 g = float3(g2, 0.0);               // across the grooves
    float3 t = float3(-g2.y, g2.x, 0.0);      // along them

    // 3. Grooves: fine concentric rings, a shimmer at card size.
    float groove = sin(6.2831853 * r / uGroove);

    // 1. Silver, with an anisotropic streak (Ward): tight along t, loose across g.
    //    Two lights, as a desk has: the key, and a weaker bounce from the lower right.
    float3 L2 = normalize(float3(0.65, 0.45, 0.9));
    float3 silver = float3(0.50, 0.51, 0.54) * (0.55 + 0.30 * dot(reflect(-V, N), normalize(float3(-0.2, -0.6, 0.8))))
        + float3(1.0) * (1.25 * streak(L, V, t, g, N) + 0.45 * streak(L2, V, t, g, N));
    silver *= 1.0 + 0.06 * groove;

    // 2. Diffraction: three orders of the grating equation, d = 1600 nm, per light.
    float3 rainbow = grating(abs(dot(L + V, g)) + 0.012 * groove)
        + 0.6 * grating(abs(dot(L2 + V, g)) + 0.012 * groove);
    float3 col = silver + 1.3 * rainbow;

    // 4. Rim: a bevel at the inner edge, lit by the key.
    if (d < uBevel) {
        float2 e = float2(0.5, 0.0);
        float2 grad = normalize(float2(box(pc + e.xy, inner) - box(pc - e.xy, inner),
                                       box(pc + e.yx, inner) - box(pc - e.yx, inner)) + 1e-5);
        // The bevel falls from the foil down to the print, so it faces inward.
        float3 nb = normalize(float3(-grad * 0.9, 0.45));
        float lit = saturate(dot(nb, L));
        float rim = mix(0.35, 1.0, lit);
        float k = 1.0 - smoothstep(0.0, uBevel, d);
        col = mix(col, float3(rim), 0.75 * k);
    }

    // A soft shoulder instead of a clip, so a streak over a rainbow stays a colour.
    col = col / (1.0 + 0.3 * col);
    col = min(col * 1.15, 1.0);
    return half4(half3(col), 1.0);
}
"""

    /** Compiled once; null where runtime shaders are unavailable, and the caller draws the fallback. */
    private val shader: StageShader? by lazy {
        compileStageShader(SKSL).also { if (it == null) println("[holo] shader did not compile; drawing the fallback") }
    }

    val available: Boolean get() = shader != null

    /**
     * Draws the foil over a card already drawn at this size. [feel] is where the
     * pointer is across the card (−1..1), or zero at rest. Returns false when
     * there is no shader, so the caller can draw its fallback.
     */
    fun DrawScope.drawHolo(feel: Offset): Boolean {
        val program = shader ?: return false
        val w = size.width
        if (w < 8f) return true
        val band = max(1f, w * BAND)
        val line = max(1f, w * 0.0012f)
        val bevel = max(1.5f, w * 0.006f)
        val brush = program.brush {
            float2("uSize", size.width, size.height)
            float2("uFeel", feel.x, feel.y)
            float("uBand", band)
            float("uGroove", max(3f, w * 0.004f))
            float("uBevel", bevel)
            float("uLine", line)
        }
        // Only the band and the hairline inside it are shaded: the print is left alone.
        val region = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(Rect(band + line, band + line, size.width - band - line, size.height - band - line))
        }
        drawPath(region, brush)
        return true
    }
}
