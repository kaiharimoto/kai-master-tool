package com.kaiharimoto.neue.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.ui.gpu.BrushMemo
import com.kaiharimoto.mastertool.ui.gpu.ShaderUniforms
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
uniform float2 uArtMin;   // the art frame's outer edge, px (ArtFrame, per frame type)
uniform float2 uArtMax;
uniform float uArtBevel;  // its width, px; 0 when the card has no art frame
uniform float uLink;      // 1 when link-arrow sockets interrupt it
uniform float uCorner;    // how far a corner socket reaches along each edge, px
uniform float uEdge;      // half the width of an edge socket, px
uniform float uSheet;     // 1: the stamp everywhere, for a shape the caller masks (the name exploration)

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

// The foil itself at one point of the card: silver, grating, grooves. The
// border and the art frame are one stamp, so they share one set of grooves.
float3 foil(float2 pc, float w) {
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
    return silver + 1.3 * rainbow;
}

// A soft shoulder instead of a clip, so a streak over a rainbow stays a colour.
half4 finish(float3 col) {
    col = col / (1.0 + 0.3 * col);
    col = min(col * 1.15, 1.0);
    return half4(half3(col), 1.0);
}

// Whether a link-arrow socket is printed over this point of the art frame.
bool socket(float2 pa, float2 h) {
    if (uLink < 0.5) return false;
    float2 q = abs(pa);
    bool corner = q.x > h.x - uCorner && q.y > h.y - uCorner;
    bool edge = (q.x < uEdge && q.y > h.y - 2.0 * uArtBevel) || (q.y < uEdge && q.x > h.x - 2.0 * uArtBevel);
    return corner || edge;
}

half4 main(float2 at) {
    float w = uSize.x;
    float2 c = uSize * 0.5;
    float2 pc = at - c;                       // px from the centre, y down
    float2 inner = c - uBand;                 // half-size of the print
    float d = box(pc, inner);                 // > 0 on the band, < 0 on the print

    if (uSheet > 0.5) return finish(foil(pc, w));

    if (d >= 0.0) {
        float3 col = foil(pc, w);
        // 4. Rim: a bevel at the border's inner edge, lit by the key.
        if (d < uBevel) {
            float3 L = normalize(float3(-0.55, -0.75, 0.95));
            float2 e = float2(0.5, 0.0);
            float2 grad = normalize(float2(box(pc + e.xy, inner) - box(pc - e.xy, inner),
                                           box(pc + e.yx, inner) - box(pc - e.yx, inner)) + 1e-5);
            // The bevel falls from the foil down to the print, so it faces inward.
            float3 nb = normalize(float3(-grad * 0.9, 0.45));
            float rim = mix(0.35, 1.0, saturate(dot(nb, L)));
            float k = 1.0 - smoothstep(0.0, uBevel, d);
            col = mix(col, float3(rim), 0.75 * k);
        }
        return finish(col);
    }

    // The frame round the artwork: the same stamp, where the card prints its bevel.
    if (uArtBevel > 0.0) {
        float2 ac = (uArtMin + uArtMax) * 0.5 - c;
        float2 h = (uArtMax - uArtMin) * 0.5;
        float2 pa = pc - ac;
        float outer = box(pa, h);                  // <= 0 inside the frame's outer edge
        float innerArt = box(pa, h - uArtBevel);   // >= 0 outside the picture
        if (!socket(pa, h)) {
            if (outer <= 0.0 && innerArt >= 0.0) {
                // A frame, not a sheet: its two edges are drawn in, so it reads as a raised ring.
                // On a small card the frame is a pixel or two, and edges that wide would be all of it.
                float k = smoothstep(0.0, min(uLine * 1.5, uArtBevel * 0.25), min(-outer, innerArt));
                return finish(foil(pc, w) * mix(0.55, 1.0, k));
            }
            // Ink hairlines where the frame meets the print and the picture.
            float edge = min(abs(outer), abs(innerArt));
            if (edge < uLine) return half4(0.0, 0.0, 0.0, 0.45 * (1.0 - smoothstep(uLine * 0.5, uLine, edge)));
        }
    }

    // The print side of the border's edge: only the ink hairline.
    float a = 1.0 - smoothstep(uLine * 0.5, uLine, -d);
    return half4(0.0, 0.0, 0.0, 0.45 * a);
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
    fun DrawScope.drawHolo(feel: Offset, frame: ArtFrame? = null, cache: HoloCache? = null): Boolean {
        val program = shader ?: return false
        val w = size.width
        if (w < 8f) return true
        val band = max(1f, w * BAND)
        val line = max(1f, w * 0.0012f)
        val bevel = max(1.5f, w * 0.006f)
        val brush = program.brushOf(cache?.border) {
            float2("uSize", size.width, size.height)
            float2("uFeel", feel.x, feel.y)
            float("uBand", band)
            float("uGroove", max(3f, w * 0.004f))
            float("uBevel", bevel)
            float("uLine", line)
            val art = frame?.let { Rect(it.left * size.width, it.top * size.height, it.right * size.width, it.bottom * size.height) }
            float2("uArtMin", art?.left ?: 0f, art?.top ?: 0f)
            float2("uArtMax", art?.right ?: 0f, art?.bottom ?: 0f)
            float("uArtBevel", frame?.let { max(1f, it.bevel * w) } ?: 0f)
            float("uLink", if (frame?.interrupted == true) 1f else 0f)
            float("uCorner", ArtFrame.LINK_CORNER * w)
            float("uEdge", ArtFrame.LINK_EDGE_HALF * w)
            float("uSheet", 0f)
        }
        // Only the border, the art frame and their hairlines are shaded: the print is
        // left alone. Nested rectangles under even-odd: card, print, frame, picture.
        val region = cache?.region(size, frame) ?: regionOf(size, frame)
        drawPath(region, brush)
        return true
    }

    /** The shaded region of a card [size] wide and high with [frame]: see [drawHolo]. */
    internal fun regionOf(size: Size, frame: ArtFrame?): Path {
        val w = size.width
        val band = max(1f, w * BAND)
        val line = max(1f, w * 0.0012f)
        return Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(Rect(band + line, band + line, size.width - band - line, size.height - band - line))
            if (frame != null) {
                val bevel = max(1f, frame.bevel * w)
                val outer = Rect(frame.left * size.width, frame.top * size.height, frame.right * size.width, frame.bottom * size.height)
                addRect(outer.inflate(line))
                addRect(outer.deflate(bevel + line))
            }
        }
    }

    private fun StageShader.brushOf(memo: BrushMemo?, uniforms: ShaderUniforms.() -> Unit) =
        if (memo == null) brush(uniforms) else brush(memo, uniforms)

    /**
     * The same stamp over all of [area], lit as though it were part of a card
     * this size — for a shape the caller then masks. The foil-name exploration
     * draws it under a mask of the printed letters.
     */
    fun DrawScope.drawHoloSheet(area: Rect, feel: Offset, cache: HoloCache? = null): Boolean {
        val program = shader ?: return false
        val w = size.width
        val brush = program.brushOf(cache?.sheet) {
            float2("uSize", size.width, size.height)
            float2("uFeel", feel.x, feel.y)
            float("uBand", max(1f, w * BAND))
            float("uGroove", max(3f, w * 0.004f))
            float("uBevel", max(1.5f, w * 0.006f))
            float("uLine", max(1f, w * 0.0012f))
            float2("uArtMin", 0f, 0f)
            float2("uArtMax", 0f, 0f)
            float("uArtBevel", 0f)
            float("uLink", 0f)
            float("uCorner", 0f)
            float("uEdge", 0f)
            float("uSheet", 1f)
        }
        drawRect(brush, topLeft = area.topLeft, size = area.size)
        return true
    }
}

/**
 * One card face's foil, kept between its draws (1.0.92): the shaded region for the
 * size and frame it was last drawn at, and the brushes for the border and the
 * stamped name, which are made again only when their light or size changes. The
 * same path and the same uniforms, so the same pixels. One per card, drawn on the
 * thread that draws it.
 */
class HoloCache {
    internal val border = BrushMemo()
    internal val sheet = BrushMemo()
    private var path: Path? = null
    private var pathSize = Size.Unspecified
    private var pathFrame: ArtFrame? = null

    internal fun region(size: Size, frame: ArtFrame?): Path {
        val kept = path
        if (kept != null && size == pathSize && frame == pathFrame) return kept
        return Holo.regionOf(size, frame).also {
            path = it
            pathSize = size
            pathFrame = frame
        }
    }
}
