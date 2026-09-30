package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** An oval in reference pixels, turned [tilt] degrees about its centre. */
data class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val tilt: Float)

/** One eye as fitted: its oval, its tilt in degrees and its superellipse exponent [n] (2 is an ellipse). */
data class EyeBase(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val tilt: Float, val n: Float)

/** One line of the net: a cubic path, stroked [width] reference pixels wide. */
class Vein(val name: String, val path: FloatArray, val width: Float, val closed: Boolean)

/** A kaomoji eye (顔文字): the face an expression draws instead of the oval. */
enum class EyeGlyph { CHEVRON_RIGHT, CHEVRON_LEFT, T, HEART }

/**
 * What one eye is doing, in the mockup's rig units: [rx] 13 and [ry] 20 are the fitted
 * size, [dx]/[dy] move it in units of [AvatarRig.K] reference pixels, and the lids cut
 * the oval — [lidTop] and [lidBot] as fractions of its height, [slant] tipping the top
 * lid, [tcurve] and [curve] bowing the lids. A [glyph] replaces the oval altogether.
 */
class EyePose {
    var rx = 13f
    var ry = 20f
    var n = 2f
    var tilt = 0f
    var lidTop = 0f
    var slant = 0f
    var tcurve = 0f
    var lidBot = 0f
    var curve = 0f
    var dx = 0f
    var dy = 0f
    var glyph: EyeGlyph? = null

    fun reset() {
        rx = 13f; ry = 20f; n = 2f; tilt = 0f; lidTop = 0f; slant = 0f; tcurve = 0f; lidBot = 0f; curve = 0f; dx = 0f; dy = 0f
        glyph = null
    }

    fun set(o: EyePose) {
        rx = o.rx; ry = o.ry; n = o.n; tilt = o.tilt; lidTop = o.lidTop; slant = o.slant; tcurve = o.tcurve
        lidBot = o.lidBot; curve = o.curve; dx = o.dx; dy = o.dy; glyph = o.glyph
    }

    /** Every number [a] of the way toward [o]; the glyph switches at once, since the outline's points do the melting. */
    fun ease(o: EyePose, a: Float) {
        rx += (o.rx - rx) * a; ry += (o.ry - ry) * a; n += (o.n - n) * a; tilt += (o.tilt - tilt) * a
        lidTop += (o.lidTop - lidTop) * a; slant += (o.slant - slant) * a; tcurve += (o.tcurve - tcurve) * a
        lidBot += (o.lidBot - lidBot) * a; curve += (o.curve - curve) * a; dx += (o.dx - dx) * a; dy += (o.dy - dy) * a
        glyph = o.glyph
    }
}

/**
 * Every eye is [POINTS] points, so any face melts into any other point by point: the
 * fitted superellipse cut by its lids, or a glyph resampled to the same count, both
 * going clockwise on screen from the rightmost point.
 */
object EyeShape {
    const val POINTS = 64

    /** Where an outline sits and how open it is: 1 wide open, 0 shut to a line. */
    class Placed {
        var cx = 0f
        var cy = 0f
        /** Radians. */
        var tilt = 0f
        var open = 1f
    }

    private val sources: Map<EyeGlyph, List<Pair<Double, Double>>> = mapOf(
        EyeGlyph.CHEVRON_RIGHT to listOf(-.8 to -.95, -.3 to -.95, .85 to 0.0, -.3 to .95, -.8 to .95, .3 to 0.0),
        EyeGlyph.CHEVRON_LEFT to listOf(.8 to -.95, .3 to -.95, -.85 to 0.0, .3 to .95, .8 to .95, -.3 to 0.0),
        EyeGlyph.T to listOf(-.95 to -.9, .95 to -.9, .95 to -.5, .24 to -.5, .24 to .95, -.24 to .95, -.24 to -.5, -.95 to -.5),
        EyeGlyph.HEART to heart(),
    )

    /** The glyphs as outlines in the eye's own unit box (±1 across, ±1 down), resampled to [POINTS]. */
    val glyphs: Map<EyeGlyph, FloatArray> = sources.mapValues { resample(it.value) }

    /** The heart curve, fitted to the unit box. */
    private fun heart(): List<Pair<Double, Double>> {
        val p = (0 until 48).map { i ->
            val t = i / 48.0 * 2 * PI
            16 * sin(t).pow(3) to -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
        }
        val mx = p.maxOf { abs(it.first) }
        val y0 = p.minOf { it.second }
        val y1 = p.maxOf { it.second }
        return p.map { (x, y) -> x / mx to (y - (y0 + y1) / 2) / ((y1 - y0) / 2) }
    }

    /** A closed polygon resampled to [POINTS] by arc length, clockwise on screen, from its rightmost point. */
    fun resample(src: List<Pair<Double, Double>>): FloatArray {
        var p = src
        var area = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            area += a.first * b.second - b.first * a.second
        }
        // Positive area with y down is clockwise on screen.
        if (area < 0) p = p.reversed()
        val n = p.size
        val len = DoubleArray(n + 1)
        for (i in 1..n) len[i] = len[i - 1] + hypot(p[i % n].first - p[i - 1].first, p[i % n].second - p[i - 1].second)
        var s0 = 0
        p.forEachIndexed { i, q ->
            val best = p[s0]
            if (q.first > best.first + 1e-9 || (abs(q.first - best.first) < 1e-9 && abs(q.second) < abs(best.second))) s0 = i
        }
        val total = len[n]
        val out = FloatArray(2 * POINTS)
        for (k in 0 until POINTS) {
            val d = (len[s0] + k.toDouble() / POINTS * total) % total
            var i = 0
            while (len[i + 1] < d) i++
            val span = len[i + 1] - len[i]
            val u = if (span == 0.0) 0.0 else (d - len[i]) / span
            val a = p[i]
            val b = p[(i + 1) % n]
            out[2 * k] = (a.first + (b.first - a.first) * u).toFloat()
            out[2 * k + 1] = (a.second + (b.second - a.second) * u).toFloat()
        }
        return out
    }

    /**
     * The outline [e] asks of the eye fitted as [base], written into [out] as
     * `x0, y0, x1, y1…` in reference pixels, [scale] times its size (the glyph's
     * larger eyes). Returns where it sits and how open it is, in [placed].
     */
    fun target(e: EyePose, base: EyeBase, scale: Float, out: FloatArray, placed: Placed) {
        val ry = max(.05f, base.ry * e.ry / 20f * scale)
        val rx = max(.5f, base.rx * e.rx / 13f * scale)
        val cx = base.cx + AvatarRig.K * e.dx
        val cy = base.cy + AvatarRig.K * e.dy
        val tl = ((base.tilt + e.tilt) * PI / 180).toFloat()
        val ca = cos(tl)
        val sa = sin(tl)
        placed.cx = cx
        placed.cy = cy
        placed.tilt = tl
        val glyph = e.glyph?.let { glyphs[it] }
        if (glyph != null) {
            for (i in 0 until POINTS) {
                val x = glyph[2 * i] * rx
                val y = glyph[2 * i + 1] * ry
                out[2 * i] = cx + x * ca - y * sa
                out[2 * i + 1] = cy + x * sa + y * ca
            }
            placed.open = 1f
            return
        }
        val ex = 2f / max(.3f, base.n + (e.n - 2f))
        fun top(x: Float) = -ry + e.lidTop * 2 * ry + e.slant * (x / rx) * ry * .6f + e.tcurve * (1 - (x / rx) * (x / rx)) * ry
        fun bot(x: Float) = ry - e.lidBot * 2 * ry - e.curve * (1 - (x / rx) * (x / rx)) * ry
        for (i in 0 until POINTS) {
            val th = i.toFloat() / POINTS * 2 * PI.toFloat()
            val c = cos(th)
            val s = sin(th)
            val x = rx * sign(c) * abs(c).pow(ex)
            var y = ry * sign(s) * abs(s).pow(ex)
            val a = top(x)
            val b = bot(x)
            y = if (a > b) (a + b) / 2 else y.coerceIn(a, b)
            out[2 * i] = cx + x * ca - y * sa
            out[2 * i + 1] = cy + x * sa + y * ca
        }
        placed.open = ((min(bot(0f), ry) - max(top(0f), -ry)) / (2 * ry)).coerceIn(0f, 1f)
    }
}
