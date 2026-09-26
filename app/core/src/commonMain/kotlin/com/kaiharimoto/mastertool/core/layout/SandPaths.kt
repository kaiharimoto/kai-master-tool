package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One figure a ball draws in zen mode's sand: a closed or nearly closed curve in
 * the unit disk, traced from `s = 0` to `s = 1`.
 *
 * Six families, each a curve people have drawn with a pin and a string for
 * centuries, and each with a handful of parameters that give it a different
 * character:
 *
 * - **Rose** — `r = sin(kθ)`, `k = p/q`: petals.
 * - **Hypotrochoid** — a circle rolling inside another, a pen at [c] from its
 *   centre: the spirograph star.
 * - **Epitrochoid** — rolling outside: rounded lobes, a flower from the side.
 * - **Lissajous** — two sines at a ratio: a woven figure.
 * - **Breathing spiral** — out from the centre and back, its radius swelling in
 *   lobes: a ripple.
 * - **Limaçon** — a loop that turns a little each time round, so the loops lay
 *   down a slow rosette.
 *
 * Every family is normalised by its own true maximum radius, so nothing leaves
 * the disk; [turn] rotates the whole figure and [scale] shrinks it a little, so
 * the same parameters never land in the same place twice.
 */
data class SandFigure(
    val kind: Kind,
    val a: Double,
    val b: Double,
    val c: Double,
    val turn: Double,
    val scale: Double = 1.0,
) {
    enum class Kind { ROSE, HYPOTROCHOID, EPITROCHOID, LISSAJOUS, SPIRAL, LIMACON }

    /** The range of the curve's own parameter that closes it. */
    val span: Double
        get() = when (kind) {
            // a = p, b = q: qπ when both are odd, else 2qπ.
            Kind.ROSE -> if (a.toInt() % 2 == 1 && b.toInt() % 2 == 1) b * PI else 2.0 * b * PI
            // a = R, b = r (integers): closes after r / gcd(R, r) turns.
            Kind.HYPOTROCHOID, Kind.EPITROCHOID -> 2.0 * PI * b / gcd(a.toInt(), b.toInt())
            Kind.LISSAJOUS -> 2.0 * PI
            // a = turns.
            Kind.SPIRAL -> 2.0 * PI * a
            // a = loops.
            Kind.LIMACON -> 2.0 * PI * a
        }

    fun at(s: Double): Pair<Double, Double> {
        val t = s.coerceIn(0.0, 1.0) * span
        val (x, y) = when (kind) {
            Kind.ROSE -> {
                val r = sin(a / b * t)
                r * cos(t) to r * sin(t)
            }
            Kind.HYPOTROCHOID -> {
                val k = (a - b) / b
                val m = a - b + c
                ((a - b) * cos(t) + c * cos(k * t)) / m to ((a - b) * sin(t) - c * sin(k * t)) / m
            }
            Kind.EPITROCHOID -> {
                val k = (a + b) / b
                val m = a + b + c
                ((a + b) * cos(t) - c * cos(k * t)) / m to ((a + b) * sin(t) - c * sin(k * t)) / m
            }
            Kind.LISSAJOUS -> {
                // a and b are the two frequencies, c the phase; 1/√2 keeps the corners in the disk.
                val k = 0.7071
                k * sin(a * t + c) to k * sin(b * t)
            }
            Kind.SPIRAL -> {
                val u = t / span
                // Out and back in, swelling in b lobes by c.
                val r = sin(PI * u) * (1.0 - c + c * (0.5 + 0.5 * sin(b * t)))
                r * cos(t) to r * sin(t)
            }
            Kind.LIMACON -> {
                // A limaçon r = (b + cos φ)/(b + 1), its axis turning once over all a loops.
                val loop = t % (2.0 * PI)
                val r = (b + cos(loop)) / (b + 1.0)
                val axis = t / a
                r * cos(loop + axis) to r * sin(loop + axis)
            }
        }
        val ct = cos(turn)
        val st = sin(turn)
        return scale * (x * ct - y * st) to scale * (x * st + y * ct)
    }

    private fun gcd(x: Int, y: Int): Int = if (y == 0) abs(x).coerceAtLeast(1) else gcd(y, x % y)
}

object SandPaths {

    private val ROSES = listOf(5.0 to 3.0, 7.0 to 4.0, 8.0 to 5.0, 11.0 to 6.0, 4.0 to 1.0, 3.0 to 1.0, 7.0 to 3.0)
    private val HYPO = listOf(Triple(5.0, 3.0, 5.0), Triple(7.0, 4.0, 4.0), Triple(8.0, 5.0, 3.0), Triple(9.0, 4.0, 6.0), Triple(10.0, 7.0, 7.0), Triple(11.0, 3.0, 4.0))
    private val EPI = listOf(Triple(3.0, 1.0, 1.0), Triple(5.0, 2.0, 1.5), Triple(4.0, 1.0, 2.0), Triple(7.0, 2.0, 2.0), Triple(6.0, 5.0, 3.0))
    private val LISSA = listOf(Triple(3.0, 2.0, PI / 2), Triple(5.0, 4.0, PI / 4), Triple(3.0, 4.0, PI / 2), Triple(5.0, 6.0, PI / 3), Triple(1.0, 2.0, PI / 4))

    /**
     * The [n]th figure a ball with [seed] draws. Deterministic — the same garden
     * always draws the same things — but it never draws the same family twice
     * running, and it cycles through the families in an order that changes every
     * cycle, so the sequence has no period a person would notice.
     */
    fun figure(n: Int, seed: Int): SandFigure {
        val kind = kindAt(n, seed)
        val h = hash(seed * 31 + 7, n)
        val turn = (h % 3600) / 3600.0 * 2.0 * PI
        val scale = 0.86 + ((h ushr 12) % 140) / 1000.0
        fun <T> pick(list: List<T>) = list[((h ushr 4) % list.size)]
        return when (kind) {
            SandFigure.Kind.ROSE -> pick(ROSES).let { (p, q) -> SandFigure(kind, p, q, 0.0, turn, scale) }
            SandFigure.Kind.HYPOTROCHOID -> pick(HYPO).let { (r1, r2, d) -> SandFigure(kind, r1, r2, d, turn, scale) }
            SandFigure.Kind.EPITROCHOID -> pick(EPI).let { (r1, r2, d) -> SandFigure(kind, r1, r2, d, turn, scale) }
            SandFigure.Kind.LISSAJOUS -> pick(LISSA).let { (fa, fb, ph) -> SandFigure(kind, fa, fb, ph, turn, scale) }
            SandFigure.Kind.SPIRAL -> SandFigure(kind, 7.0 + (h ushr 6) % 7, 5.0 + (h ushr 9) % 5, 0.12 + ((h ushr 3) % 8) / 100.0, turn, scale)
            SandFigure.Kind.LIMACON -> SandFigure(kind, 9.0 + (h ushr 7) % 8, 0.4 + ((h ushr 5) % 5) / 10.0, 0.0, turn, scale)
        }
    }

    /**
     * The family of the [n]th figure: each cycle is a fresh permutation of all
     * six, so nothing repeats inside a cycle, and where two cycles meet, a
     * permutation that would open on the family the last one closed on swaps its
     * first two.
     */
    fun kindAt(n: Int, seed: Int): SandFigure.Kind {
        val kinds = SandFigure.Kind.entries
        val cycle = n / kinds.size
        val order = shuffled(kinds.size, hash(seed, cycle))
        if (cycle > 0 && order[0] == shuffled(kinds.size, hash(seed, cycle - 1)).last()) {
            val t = order[0]; order[0] = order[1]; order[1] = t
        }
        return kinds[order[n % kinds.size]]
    }

    /**
     * How far along a curve to move so the ball covers [stepPx] on a disk of
     * [radiusPx]: arc length, not parameter, so the ball rolls at one speed
     * through a tight petal and round a wide loop alike.
     */
    fun advance(curve: (Double) -> Pair<Double, Double>, s: Double, stepPx: Float, radiusPx: Float): Double {
        val h = 1e-5
        val (x0, y0) = curve(s)
        val (x1, y1) = curve((s + h).coerceAtMost(1.0))
        val speed = hypot(x1 - x0, y1 - y0) / h * radiusPx
        return s + if (speed < 1e-6) h else stepPx / speed
    }

    /** A fixed permutation of 0 until [size] for [key]: Fisher–Yates on an integer hash. */
    private fun shuffled(size: Int, key: Int): IntArray {
        val out = IntArray(size) { it }
        var h = key
        for (i in size - 1 downTo 1) {
            h = hash(h, i)
            val j = (h ushr 1) % (i + 1)
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return out
    }

    private fun hash(a: Int, b: Int): Int {
        var h = a * 0x27D4EB2D xor b * 0x165667B1
        h = h xor (h ushr 15)
        h *= 0x2C1B3C6D
        h = h xor (h ushr 12)
        h *= 0x297A2D39
        h = h xor (h ushr 15)
        return h and 0x7FFFFFFF
    }
}
