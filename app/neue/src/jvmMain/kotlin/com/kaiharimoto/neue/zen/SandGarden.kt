package com.kaiharimoto.neue.zen

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.layout.SandFigure
import com.kaiharimoto.mastertool.core.layout.SandPaths
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The sand zen mode draws in, either side of the floating deck.
 *
 * It is meant to be looked past, not at. The sand is plain and smooth; a ball
 * either side draws one figure after another (`SandPaths`: roses, spirograph
 * stars and flowers, Lissajous weaves, breathing spirals, turning loops), slowly,
 * as a shallow groove — and **every trail fades back into the sand as it goes**,
 * so the garden never fills up and never stops, and what is on the sand at any
 * moment is the last half a minute of drawing, fainter the older it is. No
 * figure family comes twice in a row, and each is turned and sized afresh.
 *
 * The cards keep their distance: the figures are placed clear of the deck, and
 * the shader smooths the sand out entirely in a margin round it, so a groove
 * never runs up against a card.
 *
 * How it is made: a height field in half-float (so a fade of a fraction of a
 * percent a frame is not rounded away), grooves pressed into it with a soft
 * ridge either side, and a runtime shader that lights it from its own slope,
 * low, from the upper left, over the grain of real sand — a normal map and an
 * albedo baked in Blender (`tools/zen/garden.py`), tiled. The ball is Blender's
 * too, and half there. White sand on paper, black on ink, and never a colour.
 */
@Composable
fun SandGarden(zen: ZenLayer, ink: Boolean, modifier: Modifier = Modifier) {
    val garden = remember { Garden() }
    // The field is native memory: a garden starts smooth each time zen begins, and is let go when it ends.
    DisposableEffect(garden) { onDispose { garden.close() } }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(garden) {
        var last = 0L
        while (zen.deep > 0f) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                garden.step(dt)
                frame++
            }
        }
    }
    Canvas(modifier.graphicsLayer { alpha = zen.deep.coerceIn(0f, 1f) }) {
        frame.let { }
        garden.prepare(size.width.toInt(), size.height.toInt(), zen.deckInZen)
        garden.draw(this, ink)
    }
}

private class Garden {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var stone = Rect.Zero
    private val tracers = mutableListOf<Tracer>()
    private var snapshot: Image? = null
    private var dirty = true
    private var sinceFade = 0f

    fun prepare(w: Int, h: Int, deck: Rect) {
        if (w <= 0 || h <= 0) return
        if (surface != null && w == width && h == height) return
        width = w
        height = h
        stone = deck
        val hw = max(1, (w * SCALE).toInt())
        val hh = max(1, (h * SCALE).toInt())
        close()
        surface = Surface.makeRaster(ImageInfo(hw, hh, ColorType.RGBA_F16, ColorAlphaType.PREMUL, ColorSpace.sRGB)).also {
            it.canvas.clear(Color4f(FLAT, FLAT, FLAT, 1f).toColor())
        }
        tracers.clear()
        placeTracers()
        dirty = true
    }

    /** A ball either side of the deck, each in a disk that keeps its distance from the cards. */
    private fun placeTracers() {
        // Into the start of the feather, where the shader is already calming the sand,
        // so a figure's inner edge softens away as it nears the cards.
        val clear = BREATHING + FEATHER * 0.4f
        fun add(left: Float, right: Float, seed: Int) {
            val room = right - left
            val radius = min(room / 2f, height * 0.36f)
            if (radius >= MIN_RADIUS) tracers += Tracer(Offset((left + right) / 2f, height / 2f), radius, seed)
        }
        if (stone.width <= 0f) {
            add(0f, width * 0.5f, 0)
            add(width * 0.5f, width.toFloat(), 3)
        } else {
            add(EDGE, stone.left - clear, 0)
            add(stone.right + clear, width - EDGE, 3)
        }
    }

    fun close() {
        snapshot?.close()
        snapshot = null
        surface?.close()
        surface = null
    }

    fun step(dt: Float) {
        val s = surface ?: return
        val c = s.canvas
        // The fade: everything drifts back toward smooth sand, a little each frame.
        sinceFade += dt
        if (sinceFade >= FADE_EVERY) {
            val keep = 0.5f.pow(sinceFade / HALF_LIFE)
            fade.color4f = Color4f(FLAT, FLAT, FLAT, 1f - keep)
            c.drawPaint(fade)
            sinceFade = 0f
            dirty = true
        }
        tracers.forEach { t ->
            groove(c, t.advance(dt))
            dirty = true
        }
    }

    fun draw(scope: DrawScope, ink: Boolean) {
        val s = surface ?: return
        if (dirty || snapshot == null) {
            snapshot?.close()
            snapshot = s.makeImageSnapshot()
            dirty = false
        }
        val field = snapshot ?: return
        val shader = Textures.shade(field, ink, stone) ?: return
        scope.drawRect(ShaderBrush(shader.asComposeShader()))
        val ball = Textures.ball ?: return
        val d = (GROOVE * 1.6f).toInt().coerceAtLeast(4)
        tracers.forEach { t ->
            val at = t.position
            scope.drawImage(
                ball,
                dstOffset = IntOffset((at.x - d / 2f).toInt(), (at.y - d / 2f).toInt()),
                dstSize = IntSize(d, d),
                alpha = BALL_ALPHA,
            )
        }
    }

    companion object {
        /** Height-field pixels per window pixel. */
        const val SCALE = 0.5f

        /** The level of smooth sand. */
        const val FLAT = 0.5f

        /** Window pixels: the groove the ball leaves. */
        const val GROOVE = 5f

        /** Seconds for a trail to fade to half its depth. */
        const val HALF_LIFE = 12f
        private const val FADE_EVERY = 1f / 30f

        /** Smooth sand round the deck, window pixels, and the width of the fade into it. */
        const val BREATHING = 72f
        const val FEATHER = 110f
        private const val EDGE = 36f
        private const val MIN_RADIUS = 80f
        private const val BALL_ALPHA = 0.55f

        private val fade = Paint().apply { blendMode = BlendMode.SRC_OVER }

        private val ridge = Paint().apply {
            mode = PaintMode.STROKE
            strokeWidth = GROOVE * 2.2f * SCALE
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
            isAntiAlias = true
            color4f = Color4f(0.56f, 0.56f, 0.56f, 1f)
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, GROOVE * 0.4f * SCALE)
        }
        private val trough = Paint().apply {
            mode = PaintMode.STROKE
            strokeWidth = GROOVE * SCALE
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
            isAntiAlias = true
            color4f = Color4f(0.38f, 0.38f, 0.38f, 1f)
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, GROOVE * 0.35f * SCALE)
        }

        /** A groove along [path] (height-field pixels): a soft ridge either side, a shallow trough between. */
        fun groove(c: org.jetbrains.skia.Canvas, path: Path?) {
            if (path == null) return
            c.drawPath(path, ridge)
            c.drawPath(path, trough)
        }
    }

    /**
     * One ball, drawing figure after figure in a disk of [radius] about
     * [centre]. Between two figures it glides — a short, eased line from where
     * one ended to where the next begins, which leaves its trace like everything
     * else and fades like everything else.
     */
    private class Tracer(val centre: Offset, val radius: Float, private val seed: Int) {
        private var n = 0
        private var figure: SandFigure = SandPaths.figure(0, seed)
        private var s = 0.0
        private var glide: Pair<Offset, Offset>? = null
        private var g = 0.0

        val position: Offset
            get() = glide?.let { (a, b) -> lerp(a, b, ease(g)) } ?: point(figure.at(s))

        private fun point(p: Pair<Double, Double>) = Offset(centre.x + (p.first * radius).toFloat(), centre.y + (p.second * radius).toFloat())

        fun advance(dt: Float): Path? {
            var budget = SPEED * dt
            if (budget <= 0f) return null
            val start = position
            val path = PathBuilder().moveTo(start.x * SCALE, start.y * SCALE)
            var steps = 0
            while (budget > 0f && steps < 2000) {
                val step = min(STEP, budget)
                val route = glide
                if (route != null) {
                    val length = hypot(route.second.x - route.first.x, route.second.y - route.first.y).coerceAtLeast(1f)
                    g += step / length
                    if (g >= 1.0) glide = null
                } else {
                    s = SandPaths.advance(figure::at, s, step, radius)
                    if (s >= 1.0) {
                        // The figure is done: the next one, and a glide to where it begins.
                        val end = point(figure.at(1.0))
                        n++
                        figure = SandPaths.figure(n, seed)
                        s = 0.0
                        glide = end to point(figure.at(0.0))
                        g = 0.0
                    }
                }
                val p = position
                path.lineTo(p.x * SCALE, p.y * SCALE)
                budget -= step
                steps++
            }
            return path.detach()
        }

        companion object {
            /** Window pixels between the points of a stroke. */
            const val STEP = 1.5f

            /** How fast the ball rolls: slow enough to be looked past. */
            const val SPEED = 70f

            fun ease(t: Double) = (t * t * (3 - 2 * t)).toFloat()
            fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
    }
}

/** Blender's sand and ball, and the shader that lights the field with them. */
private object Textures {
    private fun load(name: String): Image? = runCatching {
        Garden::class.java.getResourceAsStream("/zen/$name")?.use { Image.makeFromEncoded(it.readBytes()) }
    }.getOrNull()

    private val normal: Image? by lazy { load("sand_normal.png") }
    private val albedo: Image? by lazy { load("sand_albedo.png") }
    val ball: ImageBitmap? by lazy { load("ball.png")?.toComposeImageBitmap() }

    private const val SKSL = """
uniform shader height;   // the drawn field, uScale px per window px
uniform shader grain;    // Blender's sand, tangent-space normals, tiled
uniform shader albedo;   // Blender's sand, grey, tiled
uniform float uScale;
uniform float uInk;
uniform float uRelief;
uniform float4 uStone;   // the deck in zen, window px: left, top, right, bottom
uniform float uClear;    // smooth sand this far round it
uniform float uFeather;  // and this far to fade back in

float boxDistance(float2 p, float4 r) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 h = (r.zw - r.xy) * 0.5;
    float2 q = abs(p - c) - h;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
}

half4 main(float2 p) {
    float2 q = p * uScale;
    float hx = height.eval(q + float2(1.0, 0.0)).r - height.eval(q - float2(1.0, 0.0)).r;
    float hy = height.eval(q + float2(0.0, 1.0)).r - height.eval(q - float2(0.0, 1.0)).r;
    // Round the cards the sand lies smooth: whatever was drawn there fades out entirely.
    float calm = 1.0;
    if (uStone.z > uStone.x) calm = smoothstep(uClear, uClear + uFeather, boxDistance(p, uStone));
    float3 n = normalize(float3(-hx * uRelief * calm, -hy * uRelief * calm, 1.0));
    // Blender's normals are y-up; the window is y-down.
    float3 g = grain.eval(p).rgb * 2.0 - 1.0;
    g.y = -g.y;
    n = normalize(float3(n.xy + g.xy * 0.16, n.z));
    float3 L = normalize(float3(-0.55, -0.62, 0.62));
    float diff = max(dot(n, L), 0.0);
    // The grain is there to be felt, not counted: most of its speckle is flattened out.
    float a = mix(0.93, albedo.eval(p).r, 0.3);
    // Soft light and a high floor: the sand is a surface to rest the eye on, not a picture.
    float lum = a * (0.86 + 0.34 * diff);
    lum = min(lum, 1.0);
    if (uInk > 0.5) lum = lum * 0.2;
    return half4(half3(lum), 1.0);
}
"""

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(SKSL) }.onFailure { println("[zen] sand shader: ${it.message}") }.getOrNull()
    }

    fun shade(field: Image, ink: Boolean, stone: Rect): Shader? {
        val e = effect ?: return null
        val n = normal ?: return null
        val a = albedo ?: return null
        val linear = SamplingMode.LINEAR
        return RuntimeShaderBuilder(e).apply {
            child("height", field.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, linear, null))
            child("grain", n.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            child("albedo", a.makeShader(FilterTileMode.REPEAT, FilterTileMode.REPEAT, linear, null))
            uniform("uScale", Garden.SCALE)
            uniform("uInk", if (ink) 1f else 0f)
            uniform("uRelief", 15f)
            uniform("uStone", stone.left, stone.top, stone.right, stone.bottom)
            uniform("uClear", Garden.BREATHING)
            uniform("uFeather", Garden.FEATHER)
        }.makeShader()
    }
}
