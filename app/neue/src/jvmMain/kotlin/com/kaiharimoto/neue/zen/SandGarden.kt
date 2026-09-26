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
import com.kaiharimoto.mastertool.core.layout.SandPaths
import com.kaiharimoto.mastertool.core.layout.SandTrack
import org.jetbrains.skia.Image
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathDirection
import org.jetbrains.skia.RRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.FilterTileMode
import kotlin.math.max
import kotlin.math.min

/**
 * The sand garden zen mode rakes around the deck: white sand on paper, black on
 * ink, with the deck as the stone.
 *
 * The garden is a **height field**, drawn into as it is raked. At the start of a
 * session it is raked in straight lines across the window and in rings round the
 * stone — a karesansui. Then two steel balls, one either side of the deck, roll
 * through it on the programs in `SandPaths` — a spiral out that rakes a disk
 * smooth, a spiral that breathes back in, a rose — and every inch they cover is
 * pressed into a groove with a ridge pushed up either side, over whatever was
 * there, the way a kinetic sand table draws.
 *
 * The field is shaded per pixel by a runtime shader: normals from the field's
 * slope, a low light from the upper left so the grooves read, and under that the
 * grain of real sand — a normal map and an albedo baked in Blender
 * (`tools/zen/garden.py`), tiled. The ball is Blender's too.
 *
 * Grey throughout. It is content, like card art, and Master UI's colour rule is
 * not suspended for it.
 */
@Composable
fun SandGarden(zen: ZenLayer, ink: Boolean, modifier: Modifier = Modifier) {
    val garden = remember { Garden() }
    // The field is native memory: a garden is raked fresh each time zen begins, and let go when it ends.
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

/** The garden's height field, its two balls, and how it is shaded. */
private class Garden {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var stone = Rect.Zero
    private val tracers = mutableListOf<Tracer>()
    private var snapshot: Image? = null
    private var dirty = true

    fun prepare(w: Int, h: Int, deck: Rect) {
        if (w <= 0 || h <= 0) return
        if (surface != null && w == width && h == height) return
        width = w
        height = h
        stone = deck
        val hw = max(1, (w * SCALE).toInt())
        val hh = max(1, (h * SCALE).toInt())
        surface?.close()
        surface = Surface.makeRasterN32Premul(hw, hh).also { rake(it, hw, hh) }
        tracers.clear()
        placeTracers()
        dirty = true
    }

    /** Straight lines everywhere, then rings round the stone. */
    private fun rake(s: Surface, hw: Int, hh: Int) {
        val c = s.canvas
        c.clear(level(0.5f))
        val spacing = SPACING * SCALE
        var y = spacing / 2f
        while (y < hh) {
            groove(c, PathBuilder().moveTo(-10f, y).lineTo(hw + 10f, y).detach())
            y += spacing
        }
        if (stone.width > 0f) {
            val r = Rect(stone.left * SCALE, stone.top * SCALE, stone.right * SCALE, stone.bottom * SCALE)
            val band = RINGS * spacing + spacing * 0.6f
            // Level the band the rings go in, so the lines meet them rather than cross them.
            val flat = Paint().apply { color = level(0.5f); isAntiAlias = true }
            c.drawRRect(RRect.makeLTRB(r.left - band, r.top - band, r.right + band, r.bottom + band, band), flat)
            for (k in 1..RINGS) {
                val d = k * spacing - spacing * 0.4f
                groove(c, PathBuilder().addRRect(RRect.makeLTRB(r.left - d, r.top - d, r.right + d, r.bottom + d, d), PathDirection.CLOCKWISE, 0).detach())
            }
        }
    }

    /** A ball each side of the stone, where there is room for one. */
    private fun placeTracers() {
        val margin = SPACING * 3f
        val leftW = stone.left - margin
        val rightW = width - stone.right - margin
        fun add(cx: Float, room: Float, seed: Int) {
            val radius = min(room / 2f, height / 2f) - SPACING * 1.5f
            if (radius >= MIN_RADIUS) tracers += Tracer(Offset(cx, height / 2f), radius, seed)
        }
        if (stone.width <= 0f) {
            add(width * 0.25f, width * 0.5f, 0)
            add(width * 0.75f, width * 0.5f, 2)
        } else {
            add(leftW / 2f, leftW, 0)
            add(stone.right + margin + rightW / 2f, rightW, 2)
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
        tracers.forEach { t ->
            val path = t.advance(dt) ?: return@forEach
            groove(c, path)
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
        val shader = Textures.shade(field, ink) ?: return
        scope.drawRect(ShaderBrush(shader.asComposeShader()))
        val ball = Textures.ball ?: return
        val d = (GROOVE * 1.7f).toInt()
        tracers.forEach { t ->
            val at = t.position
            scope.drawImage(
                ball,
                dstOffset = IntOffset((at.x - d / 2f).toInt(), (at.y - d / 2f).toInt()),
                dstSize = IntSize(d, d),
            )
        }
    }

    companion object {
        /** Height-field pixels per window pixel: half, which the shader's linear filter hides. */
        const val SCALE = 0.5f

        /** Window pixels between the lines of a rake. */
        const val SPACING = 13f

        /** The width of the ball's groove, window pixels. */
        const val GROOVE = 7f

        const val RINGS = 6
        const val MIN_RADIUS = 90f

        fun level(h: Float): Int {
            val v = (h.coerceIn(0f, 1f) * 255f).toInt()
            return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        private val ridge = Paint().apply {
            mode = PaintMode.STROKE
            strokeWidth = GROOVE * 2.1f * SCALE
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
            isAntiAlias = true
            color = level(0.63f)
            maskFilter = MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, GROOVE * 0.32f * SCALE)
        }
        private val trough = Paint().apply {
            mode = PaintMode.STROKE
            strokeWidth = GROOVE * SCALE
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
            isAntiAlias = true
            color = level(0.26f)
            maskFilter = MaskFilter.makeBlur(org.jetbrains.skia.FilterBlurMode.NORMAL, GROOVE * 0.28f * SCALE)
        }

        /** A groove along [path] (height-field pixels): a ridge pushed up either side, the trough pressed down the middle. */
        fun groove(c: org.jetbrains.skia.Canvas, path: Path) {
            c.drawPath(path, ridge)
            c.drawPath(path, trough)
        }
    }

    /** One ball, rolling through its program in a disk of [radius] px about [centre]. */
    private class Tracer(val centre: Offset, val radius: Float, seed: Int) {
        private val run = SandPaths.startSeed(seed)
        private var n = 0
        private var track: SandTrack = SandPaths.first(radius, SPACING, seed)
        private var s = 0.0

        val position: Offset
            get() = point(track.at(s))

        private fun point(p: Pair<Double, Double>) = Offset(centre.x + (p.first * radius).toFloat(), centre.y + (p.second * radius).toFloat())

        /** Rolls on for [dt] seconds; the stretch covered, in height-field pixels, to be pressed into the sand. */
        fun advance(dt: Float): Path? {
            var budget = speed(track.kind) * dt
            if (budget <= 0f) return null
            val start = point(track.at(s))
            val path = PathBuilder().moveTo(start.x * SCALE, start.y * SCALE)
            var steps = 0
            while (budget > 0f && steps < 4000) {
                val step = min(STEP, budget)
                s = SandPaths.advance(track, s, step, radius)
                if (s >= 1.0) {
                    val end = point(track.at(1.0))
                    path.lineTo(end.x * SCALE, end.y * SCALE)
                    n++
                    track = SandPaths.track(n, radius, SPACING, track, run)
                    s = 0.0
                }
                val p = point(track.at(s))
                path.lineTo(p.x * SCALE, p.y * SCALE)
                budget -= step
                steps++
            }
            return path.detach()
        }

        companion object {
            /** Window pixels between the points of a stroke. */
            const val STEP = 1.5f

            /** How fast the ball rolls, px/s: quick through a raking spiral, slower drawing a rose. */
            fun speed(kind: SandTrack.Kind) = when (kind) {
                SandTrack.Kind.SPIRAL_OUT -> 460f
                SandTrack.Kind.SPIRAL_IN -> 300f
                SandTrack.Kind.ROSE -> 250f
            }
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
uniform shader height;   // the raked field, SCALE px per window px
uniform shader grain;    // Blender's sand, tangent-space normals, tiled
uniform shader albedo;   // Blender's sand, colour (grey), tiled
uniform float uScale;
uniform float uInk;
uniform float uRelief;

half4 main(float2 p) {
    float2 q = p * uScale;
    float hc = height.eval(q).r;
    float hx = height.eval(q + float2(1.0, 0.0)).r - height.eval(q - float2(1.0, 0.0)).r;
    float hy = height.eval(q + float2(0.0, 1.0)).r - height.eval(q - float2(0.0, 1.0)).r;
    float3 n = normalize(float3(-hx * uRelief, -hy * uRelief, 1.0));
    // Blender's normals are y-up; the window is y-down.
    float3 g = grain.eval(p).rgb * 2.0 - 1.0;
    g.y = -g.y;
    n = normalize(float3(n.xy + g.xy * 0.7, n.z * max(g.z, 0.2)));
    // A low light from the upper left: grooves read by the side they turn to it.
    float3 L = normalize(float3(-0.55, -0.62, 0.52));
    float diff = max(dot(n, L), 0.0);
    float a = albedo.eval(p).r;
    float lum = a * (0.66 + 0.62 * diff);
    // The floor of a groove sits in its own shade.
    lum *= mix(0.84, 1.0, smoothstep(0.22, 0.5, hc));
    lum = min(lum, 1.0);
    // Black sand on ink, lit low.
    if (uInk > 0.5) lum = lum * 0.24;
    return half4(half3(lum), 1.0);
}
"""

    private val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(SKSL) }.onFailure { println("[zen] sand shader: ${it.message}") }.getOrNull()
    }

    fun shade(field: Image, ink: Boolean): Shader? {
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
            uniform("uRelief", 7f)
        }.makeShader()
    }
}
