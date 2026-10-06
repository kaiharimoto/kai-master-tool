package com.kaiharimoto.neue.ai.chessy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.kaiharimoto.mastertool.core.ai.chessy.toys.Rope
import com.kaiharimoto.mastertool.core.ai.chessy.toys.TOY_LIGHT
import com.kaiharimoto.mastertool.core.ai.chessy.toys.toyShade
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.neue.theme.MuColors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chessy's toys drawn as the duel's die and coin are (kai: "the same style and build quality as the coin and dice"):
 * paper, an ink edge, and a step of ink for each way a surface turns from the light ([toyShade], the dice's steps),
 * every surface placed in 3D and turned by its body's own orientation, so a thrown yarn ball tumbles and a mouse flips
 * as solids. Paper and ink only: nothing here is a colour.
 */
internal object PetToysInk {
    private val YARN_BANDS: List<V3> = listOf(
        V3(0.0, 0.0, 1.0), V3(1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.7, 0.7, 0.1),
        V3(-0.6, 0.5, 0.6), V3(0.5, -0.3, 0.8), V3(0.2, 0.9, -0.4),
    ).map { it.normalized() }

    /** Where the yarn's loose end leaves it, in its body: the same point `Yarn.anchor` turns. */
    private val ANCHOR = V3(0.3, 0.95, 0.1).normalized()

    private fun shadeOf(n: V3, c: MuColors): Color? = when (toyShade(n dot TOY_LIGHT)) {
        0 -> null
        1 -> c.ink06
        2 -> c.ink12
        else -> c.ink25
    }

    /**
     * A ball of yarn [r] pixels across its middle at ([x], [y]), turned [q]: a sphere in the dice's steps of shade, wound
     * round with bands of strands that turn with it (only the near halves drawn), and its loose end along [strand].
     */
    fun DrawScope.yarn(x: Float, y: Float, r: Float, q: Quat, c: MuColors, strand: Rope?, s: Float) {
        val anchor = q.rotate(ANCHOR)
        val line = Stroke(width = maxOf(1f, r * .07f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (strand != null && anchor.z < 0) drawPath(ropePath(strand), c.ink, style = line)
        val disc = Path().apply { addOval(Rect(x - r, y - r, x + r, y + r)) }
        drawPath(disc, c.paper)
        clipPath(disc) {
            // the steps of shade: each region the light reaches at least so far, a spherical cap, seen from the front
            val l = TOY_LIGHT
            val lxy = hypot(l.x, l.y).toFloat()
            val angle = (atan2(l.y, l.x) * 180 / PI).toFloat()
            drawPath(disc, c.ink25)
            for ((t, shade) in listOf(.1f to c.ink12, .45f to c.ink06, .8f to null)) {
                val rho = sqrt(1f - t * t)
                val cx = x + t * lxy * r
                val cap = Path().apply {
                    addOval(Rect(cx - rho * l.z.toFloat() * r, y - rho * r, cx + rho * l.z.toFloat() * r, y + rho * r))
                    // past the edge of the ball on the lit side the cap wraps round out of sight: the edge bounds it
                    if (lxy >= t) addRect(Rect(cx, y - r * 1.1f, x + r * 1.1f, y + r * 1.1f))
                }
                rotate(angle, Offset(x, y)) {
                    drawPath(cap, c.paper)
                    if (shade != null) drawPath(cap, shade)
                }
            }
            // the strands: each band a few turns side by side, the near half of each turn
            val strandW = maxOf(.8f, r * .045f)
            for ((k, n) in YARN_BANDS.withIndex()) {
                val a = (if (kotlin.math.abs(n.x) < .9) V3(1.0, 0.0, 0.0) else V3(0.0, 1.0, 0.0)).cross(n).normalized()
                val b = n cross a
                for (lane in -1..1) {
                    val off = lane * .085
                    val rad = sqrt(1.0 - off * off)
                    val path = Path()
                    var drawing = false
                    for (i in 0..40) {
                        val phi = i * 2 * PI / 40 + k * .37
                        val p = q.rotate(a * (cos(phi) * rad) + b * (sin(phi) * rad) + n * off)
                        if (p.z > 0) {
                            val px = x + p.x.toFloat() * r
                            val py = y + p.y.toFloat() * r
                            if (drawing) path.lineTo(px, py) else path.moveTo(px, py)
                            drawing = true
                        } else {
                            drawing = false
                        }
                    }
                    drawPath(path, c.ink.copy(alpha = .55f), style = Stroke(strandW, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        drawPath(disc, c.ink, style = Stroke(1.5f * s))
        if (strand != null && anchor.z >= 0) drawPath(ropePath(strand), c.ink, style = line)
    }

    private fun ropePath(rope: Rope): Path = Path().apply {
        moveTo(rope.x[0], rope.y[0])
        for (i in 1 until rope.n) {
            val mx = (rope.x[i] + rope.x[i + 1]) / 2f
            val my = (rope.y[i] + rope.y[i + 1]) / 2f
            quadraticTo(rope.x[i], rope.y[i], mx, my)
        }
        lineTo(rope.x[rope.n], rope.y[rope.n])
    }

    // the mouse's body: an egg along x (its nose at +x), lying on its belly; y down, z toward the person
    private const val LAT = 10
    private const val LON = 18

    /**
     * A clockwork mouse [length] pixels nose to tail at ([x], [y]), turned [q]: its egg of a body in the dice's steps of
     * shade, its outline where the body turns away, ears, an eye, a nose, whiskers, its key turned [key], and its tail
     * along [tail].
     */
    fun DrawScope.mouse(x: Float, y: Float, length: Float, q: Quat, key: Float, c: MuColors, tail: Rope?, s: Float) {
        val a = length * .5
        val b = length * .21
        val cz = length * .24
        fun local(theta: Double, phi: Double): V3 {
            val u = cos(theta)
            val taper = 1.0 - .38 * maxOf(0.0, u)
            return V3(a * u, -b * sin(theta) * cos(phi) * taper, cz * sin(theta) * sin(phi) * taper)
        }
        fun world(v: V3): Offset {
            val p = q.rotate(v)
            return Offset(x + p.x.toFloat(), y + p.y.toFloat())
        }
        fun normal(v: V3): V3 = q.rotate(V3(v.x / (a * a), v.y / (b * b), v.z / (cz * cz))).normalized()
        val ink = Stroke(1.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // the tail first: it hangs behind
        if (tail != null) drawPath(ropePath(tail), c.ink, style = Stroke(maxOf(1f, length * .035f), cap = StrokeCap.Round))
        val ears = listOf(1.0, -1.0).map { side -> side to q.rotate(V3(0.55, 0.0, 0.83 * side)) }
        fun ear(side: Double) {
            val centre = V3(a * .32, -b * 1.0, cz * .55 * side)
            val n = V3(0.55, 0.0, 0.83 * side).normalized()
            val u = V3(0.0, -1.0, 0.0)
            val v = (n cross u).normalized()
            val re = length * .13
            val rim = Path()
            val inner = Path()
            for (i in 0..16) {
                val t = i * 2 * PI / 16
                val o = world(centre + u * (cos(t) * re * 1.05) + v * (sin(t) * re))
                val o2 = world(centre + u * (cos(t) * re * .62 + re * .1) + v * (sin(t) * re * .58))
                if (i == 0) { rim.moveTo(o.x, o.y); inner.moveTo(o2.x, o2.y) } else { rim.lineTo(o.x, o.y); inner.lineTo(o2.x, o2.y) }
            }
            rim.close(); inner.close()
            val facing = q.rotate(n)
            drawPath(rim, c.paper)
            shadeOf(if (facing.z < 0) -facing else facing, c)?.let { drawPath(rim, it) }
            if (facing.z > 0) drawPath(inner, c.ink12)
            drawPath(rim, c.ink, style = ink)
        }
        ears.filter { it.second.z < 0 }.forEach { ear(it.first) }
        // the body: every quad turned toward the person, filled by its step of shade, one path a step so no seams show
        val verts = Array(LAT + 1) { i -> Array(LON) { j -> local(PI * i / LAT, 2 * PI * j / LON) } }
        val seen = Array(LAT) { BooleanArray(LON) }
        val all = Path()
        val steps = Array(4) { Path() }
        for (i in 0 until LAT) for (j in 0 until LON) {
            val j2 = (j + 1) % LON
            val mid = local(PI * (i + .5) / LAT, 2 * PI * (j + .5) / LON)
            val n = normal(mid)
            if (n.z <= 0) continue
            seen[i][j] = true
            val p0 = world(verts[i][j]); val p1 = world(verts[i][j2]); val p2 = world(verts[i + 1][j2]); val p3 = world(verts[i + 1][j])
            for (path in listOf(all, steps[toyShade(n dot TOY_LIGHT)])) {
                path.moveTo(p0.x, p0.y); path.lineTo(p1.x, p1.y); path.lineTo(p2.x, p2.y); path.lineTo(p3.x, p3.y); path.close()
            }
        }
        drawPath(all, c.paper)
        drawPath(steps[1], c.ink06)
        drawPath(steps[2], c.ink12)
        drawPath(steps[3], c.ink25)
        // its outline: where a quad turned toward the person meets one turned away
        val rim = Path()
        for (i in 0 until LAT) for (j in 0 until LON) {
            if (!seen[i][j]) continue
            val j2 = (j + 1) % LON
            val jl = (j + LON - 1) % LON
            fun edge(v0: V3, v1: V3) { val o0 = world(v0); val o1 = world(v1); rim.moveTo(o0.x, o0.y); rim.lineTo(o1.x, o1.y) }
            if (i > 0 && !seen[i - 1][j]) edge(verts[i][j], verts[i][j2])
            if (i < LAT - 1 && !seen[i + 1][j]) edge(verts[i + 1][j], verts[i + 1][j2])
            if (!seen[i][j2]) edge(verts[i][j2], verts[i + 1][j2])
            if (!seen[i][jl]) edge(verts[i][j], verts[i + 1][j])
        }
        drawPath(rim, c.ink, style = ink)
        // its face: the near eye, the nose, whiskers
        for (side in listOf(1.0, -1.0)) {
            val eyeAt = local(PI * .2, PI * .5 * side - .55 * side)
            if (normal(eyeAt).z > 0.05) {
                val o = world(eyeAt)
                drawCircle(c.ink, length * .035f, o)
                drawCircle(c.paper, length * .012f, o + Offset(-length * .01f, -length * .012f))
            }
        }
        val nose = world(V3(a * 1.0, -b * .05, 0.0))
        drawCircle(c.ink, length * .03f, nose)
        for (side in listOf(1.0, -1.0)) for (k in -1..1) {
            val from = world(V3(a * .9, -b * .05 + k * b * .08, cz * .12 * side))
            val to = world(V3(a * 1.25, -b * .05 + k * b * .3, cz * .9 * side))
            drawLine(c.ink.copy(alpha = .7f), from, to, strokeWidth = .9f * s)
        }
        ears.filter { it.second.z >= 0 }.forEach { ear(it.first) }
        // its key: a stem on its back and a bow of two loops, turning while it runs
        val stem0 = world(V3(-a * .15, -b * .95, 0.0))
        val stemTop = V3(-a * .15, -b * 1.45, 0.0)
        val stem1 = world(stemTop)
        drawLine(c.ink, stem0, stem1, strokeWidth = 1.6f * s, cap = StrokeCap.Round)
        val w = V3(cos(key.toDouble()), 0.0, sin(key.toDouble()))
        for (side in listOf(1.0, -1.0)) {
            val loop = Path()
            for (i in 0..14) {
                val t = i * 2 * PI / 14
                val p = stemTop + w * (side * length * .11 + cos(t) * length * .09) + V3(0.0, -1.0, 0.0) * (sin(t) * length * .07)
                val o = world(p)
                if (i == 0) loop.moveTo(o.x, o.y) else loop.lineTo(o.x, o.y)
            }
            loop.close()
            drawPath(loop, c.paper)
            drawPath(loop, c.ink12)
            drawPath(loop, c.ink, style = ink)
        }
    }

    /**
     * The feather wand: a dowel from ([hx], [hy]) to ([tx], [ty]) with a grip at the handle and a ribbon wound round
     * it, its string along [string], and the feather at the string's end, along its last link, fluttering with [speed].
     */
    fun DrawScope.wand(hx: Float, hy: Float, tx: Float, ty: Float, string: Rope?, speed: Float, featherLen: Float, c: MuColors, s: Float) {
        val len = hypot(tx - hx, ty - hy)
        val thick = maxOf(3f * s, len * .045f)
        // the string first, behind the feather
        if (string != null) drawPath(ropePath(string), c.ink, style = Stroke(1.1f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // the dowel: ink edges, a paper core, the shaded underside, the ribbon's turns
        drawLine(c.ink, Offset(hx, hy), Offset(tx, ty), strokeWidth = thick, cap = StrokeCap.Round)
        drawLine(c.paper, Offset(hx, hy), Offset(tx, ty), strokeWidth = thick - 2.6f * s, cap = StrokeCap.Round)
        val dx = (tx - hx) / maxOf(1f, len)
        val dy = (ty - hy) / maxOf(1f, len)
        val nx = -dy
        val ny = dx
        drawLine(c.ink12, Offset(hx + nx * thick * .2f, hy + ny * thick * .2f), Offset(tx + nx * thick * .2f, ty + ny * thick * .2f), strokeWidth = (thick - 2.6f * s) * .45f)
        for (k in 1..9) {
            val f = .26f + k * .07f
            val cx = hx + (tx - hx) * f
            val cy = hy + (ty - hy) * f
            drawLine(c.ink.copy(alpha = .6f), Offset(cx - nx * thick * .45f - dx * thick * .3f, cy - ny * thick * .45f - dy * thick * .3f), Offset(cx + nx * thick * .45f + dx * thick * .3f, cy + ny * thick * .45f + dy * thick * .3f), strokeWidth = .9f * s)
        }
        // the grip: a sleeve of darker ink at the handle
        val g0 = Offset(hx, hy)
        val g1 = Offset(hx + dx * len * .22f, hy + dy * len * .22f)
        drawLine(c.ink, g0, g1, strokeWidth = thick * 1.35f, cap = StrokeCap.Round)
        drawLine(c.ink25, g0, g1, strokeWidth = thick * 1.35f - 2.6f * s, cap = StrokeCap.Round)
        // the feather, along the string's last link
        if (string != null) {
            val n = string.n
            val lx = string.x[n] - string.x[n - 1]
            val ly = string.y[n] - string.y[n - 1]
            val l = maxOf(1e-3f, hypot(lx, ly))
            feather(string.x[n], string.y[n], lx / l, ly / l, featherLen, speed, c, s)
        } else {
            feather(tx, ty, dx, dy, featherLen, speed, c, s)
        }
    }

    /** A feather from ([x], [y]) along ([ax], [ay]), [len] long: its vane in paper with one side shaded, barbs, the quill. */
    fun DrawScope.feather(x: Float, y: Float, ax: Float, ay: Float, len: Float, speed: Float, c: MuColors, s: Float) {
        val nx = -ay
        val ny = ax
        val flutter = (speed / 900f).coerceIn(0f, 1f)
        fun at(f: Float, side: Float, w: Float): Offset {
            val bend = sin(f * PI.toFloat()) * len * .06f * flutter
            return Offset(x + ax * len * f + nx * (side * w + bend), y + ay * len * f + ny * (side * w + bend))
        }
        fun width(f: Float) = len * .2f * sin((f - .12f).coerceIn(0f, 1f) / .88f * PI.toFloat()).coerceAtLeast(0f) * (1f - .25f * f)
        val vane = Path()
        val steps = 18
        for (i in 0..steps) { val f = .12f + .88f * i / steps; val o = at(f, 1f, width(f)); if (i == 0) vane.moveTo(o.x, o.y) else vane.lineTo(o.x, o.y) }
        for (i in steps downTo 0) { val f = .12f + .88f * i / steps; val o = at(f, -1f, width(f)); vane.lineTo(o.x, o.y) }
        vane.close()
        val shaded = Path()
        for (i in 0..steps) { val f = .12f + .88f * i / steps; val o = at(f, -1f, width(f)); if (i == 0) shaded.moveTo(o.x, o.y) else shaded.lineTo(o.x, o.y) }
        for (i in steps downTo 0) { val f = .12f + .88f * i / steps; val o = at(f, 0f, 0f); shaded.lineTo(o.x, o.y) }
        shaded.close()
        drawPath(vane, c.paper)
        drawPath(shaded, c.ink06)
        // barbs: swept back from the quill to the vane's edge, a split or two in them
        val barb = Path()
        for (k in 0..14) {
            val f = .16f + k * .055f
            if (f > .98f) break
            for (side in listOf(1f, -1f)) {
                if ((k * 3 + side.toInt()) % 7 == 0) continue
                val o0 = at(f, 0f, 0f)
                val o1 = at((f - .06f).coerceAtLeast(.12f), side, width(f) * .96f)
                barb.moveTo(o0.x, o0.y); barb.lineTo(o1.x, o1.y)
            }
        }
        drawPath(barb, c.ink.copy(alpha = .45f), style = Stroke(.8f * s))
        drawPath(vane, c.ink, style = Stroke(1.2f * s, join = StrokeJoin.Round))
        // the quill, through it, and its fluff at the root
        drawLine(c.ink, at(0f, 0f, 0f), at(1f, 0f, 0f), strokeWidth = 1.4f * s, cap = StrokeCap.Round)
        for (k in 0..4) {
            val o0 = at(.08f, 0f, 0f)
            val ang = (k - 2) * .45f
            val ex = o0.x + (ax * cos(ang) - ay * sin(ang)) * len * .1f
            val ey = o0.y + (ay * cos(ang) + ax * sin(ang)) * len * .1f
            drawLine(c.ink.copy(alpha = .5f), o0, Offset(ex, ey), strokeWidth = .8f * s, cap = StrokeCap.Round)
        }
    }

    /**
     * A bag of catnip [size] pixels across at ([x], [y]) (kai, 1.1.30: a bag to pour from), tipped [angle] degrees about
     * its middle: paper lit from above on the left, its far side a step darker, its top rolled down into a cuff, its
     * mouth open and dark when it tips, a few flakes at the rim while [fill] holds any, and a catnip leaf printed on it.
     * [faded] is a bag she has had and that is filling again.
     */
    fun DrawScope.catnip(x: Float, y: Float, size: Float, c: MuColors, s: Float, angle: Float = 0f, fill: Float = 1f, faded: Boolean = false) {
        val alpha = if (faded) .35f else 1f
        withTransform({ rotate(angle, Offset(x, y)) }) {
            val top = y - size * .55f
            val bottom = y + size * .45f
            val tw = size * .36f
            val bw = size * .4f
            val body = Path().apply {
                moveTo(x - tw, top); lineTo(x + tw, top); lineTo(x + bw, bottom); lineTo(x - bw, bottom); close()
            }
            drawPath(body, c.paper.copy(alpha = alpha))
            clipPath(body) {
                // its far side a step darker, its fold down the middle of the near one
                drawRect(c.ink12.copy(alpha = c.ink12.alpha * alpha), Offset(x + bw * .45f, top), Size(bw, size))
                drawLine(c.ink25.copy(alpha = c.ink25.alpha * alpha), Offset(x + bw * .45f, top), Offset(x + bw * .45f, bottom), strokeWidth = .8f * s)
                // the cuff, rolled down
                drawRect(c.ink06.copy(alpha = c.ink06.alpha * alpha), Offset(x - bw, top), Size(bw * 2f, size * .15f))
            }
            drawLine(c.ink.copy(alpha = alpha), Offset(x - tw * 1.02f, top + size * .15f), Offset(x + tw * 1.04f, top + size * .15f), strokeWidth = 1f * s)
            drawPath(body, c.ink.copy(alpha = alpha), style = Stroke(1.4f * s, join = StrokeJoin.Miter))
            // the mouth: open and dark as it tips, flakes at the rim while there are any
            val open = (kotlin.math.abs(angle) / 50f).coerceIn(.18f, 1f)
            val mh = size * .1f * open
            drawOval(c.ink.copy(alpha = .75f * alpha), Offset(x - tw * .9f, top - mh / 2f), Size(tw * 1.8f, mh))
            drawOval(c.ink.copy(alpha = alpha), Offset(x - tw * .9f, top - mh / 2f), Size(tw * 1.8f, mh), style = Stroke(1.1f * s))
            if (fill > 0f) {
                val n = (2 + fill * 5f).toInt()
                for (k in 0 until n) {
                    val fx = x - tw * .7f + tw * 1.4f * (k + .5f) / n
                    val fy = top - mh * .25f + (if (k % 2 == 0) -1f else 1f) * mh * .15f
                    drawLine(c.ink70.copy(alpha = c.ink70.alpha * alpha), Offset(fx - size * .025f, fy), Offset(fx + size * .025f, fy - size * .012f), strokeWidth = 1.6f * s, cap = StrokeCap.Round)
                }
            }
        // the leaf printed on it: a catnip leaf, toothed, with its veins
        withTransform({ rotate(-14f, Offset(x, y + size * .12f)) }) {
            val lx = x - size * .04f
            val ly = y + size * .12f
            val lw = size * .2f
            val ll = size * .32f
            val leaf = Path().apply {
                moveTo(lx, ly - ll / 2f)
                for (k in 0..5) {
                    val f = k / 5f
                    val yy = ly - ll / 2f + ll * f
                    val ww = lw * sin(f * PI.toFloat()) * (if (k % 2 == 0) 1f else .82f)
                    lineTo(lx + ww, yy)
                }
                lineTo(lx, ly + ll / 2f)
                for (k in 5 downTo 0) {
                    val f = k / 5f
                    val yy = ly - ll / 2f + ll * f
                    val ww = lw * sin(f * PI.toFloat()) * (if (k % 2 == 0) 1f else .82f)
                    lineTo(lx - ww, yy)
                }
                close()
            }
            drawPath(leaf, c.ink12.copy(alpha = c.ink12.alpha * alpha))
            drawPath(leaf, c.ink.copy(alpha = alpha), style = Stroke(1f * s, join = StrokeJoin.Round))
            drawLine(c.ink.copy(alpha = alpha), Offset(lx, ly - ll / 2f), Offset(lx, ly + ll / 2f + size * .06f), strokeWidth = 1f * s)
            for (k in 1..3) {
                val yy = ly - ll / 2f + ll * k / 4f
                drawLine(c.ink.copy(alpha = .6f * alpha), Offset(lx, yy), Offset(lx + lw * .55f, yy - ll * .1f), strokeWidth = .8f * s)
                drawLine(c.ink.copy(alpha = .6f * alpha), Offset(lx, yy), Offset(lx - lw * .55f, yy - ll * .1f), strokeWidth = .8f * s)
            }
        }
    }
    }

    /**
     * Catnip flakes ([Flakes]), in the air and on the floor: each a short dark fleck turned its own way, two shades of
     * ink, fading as it goes.
     */
    fun DrawScope.flakes(f: com.kaiharimoto.mastertool.core.ai.chessy.toys.Flakes, c: MuColors, s: Float) {
        val len = 3.2f * s
        for (i in 0 until f.count) {
            val a = f.alpha(i)
            if (a <= 0f) continue
            val r = f.turn[i] * (PI.toFloat() / 180f)
            val dx = cos(r) * len
            val dy = sin(r) * len * (if (f.down[i]) .35f else 1f)
            val ink = if (i % 3 == 0) c.ink45 else c.ink70
            drawLine(ink.copy(alpha = ink.alpha * a), Offset(f.x[i] - dx, f.y[i] - dy), Offset(f.x[i] + dx, f.y[i] + dy), strokeWidth = 1.7f * s, cap = StrokeCap.Round)
        }
    }
}
