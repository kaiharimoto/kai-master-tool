package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * What the ball in zen mode's sand garden draws: a kinetic sand table's
 * program. A steel ball rolls through white sand on one unbroken path, and the
 * groove it leaves is the picture — so every track here starts exactly where the
 * last one ended, and the ball never jumps.
 *
 * Three kinds of pass, all in the unit disk:
 *
 * - **Spiral out**, tight enough that each turn lies against the last: it rakes
 *   the whole disk smooth, the way a table erases its previous drawing.
 * - **Spiral in, breathing**: a looser spiral whose radius swells and narrows in
 *   [SandTrack.waves] lobes, so the rings between the turns ripple.
 * - **Rose** — `r = sin(kθ)`, `k = p/q` — the curve that draws petals, which
 *   starts and ends at the centre and so hands the ball back to a spiral.
 *
 * The angle carries across tracks ([SandTrack.turn]): a spiral in starts at the
 * angle the spiral out finished on, and a rose is turned by it, so no two passes
 * of the program are laid down in the same orientation.
 */
data class SandTrack(
    val kind: Kind,
    /** Turns, for a spiral. */
    val turns: Float = 0f,
    /** Lobes of a breathing spiral. */
    val waves: Int = 0,
    /** A rose's k = p / q. */
    val p: Int = 0,
    val q: Int = 1,
    /** The angle, in radians, the track begins at. */
    val turn: Float = 0f,
) {
    enum class Kind { SPIRAL_OUT, SPIRAL_IN, ROSE }

    /** How far round a rose goes before it closes: qπ when p and q are both odd, else 2qπ. */
    val roseSpan: Double get() = if (p % 2 == 1 && q % 2 == 1) q * PI else 2.0 * q * PI

    /** The point at [s] in 0..1, in the unit disk. */
    fun at(s: Double): Pair<Double, Double> {
        val t = s.coerceIn(0.0, 1.0)
        return when (kind) {
            Kind.SPIRAL_OUT -> {
                val a = turn + 2.0 * PI * turns * t
                t * cos(a) to t * sin(a)
            }
            Kind.SPIRAL_IN -> {
                val a = turn + 2.0 * PI * turns * t
                // The breath is zero at both ends, so the ends meet their neighbours exactly.
                val r = (1.0 - t) * (1.0 + 0.16 * 4.0 * t * (1.0 - t) * sin(waves * a))
                val rr = r.coerceIn(0.0, 1.0)
                rr * cos(a) to rr * sin(a)
            }
            Kind.ROSE -> {
                val theta = roseSpan * t
                val r = sin(p.toDouble() / q * theta)
                r * cos(theta + turn) to r * sin(theta + turn)
            }
        }
    }

    /** The angle the track finishes on, which the next one starts from. */
    val endTurn: Float
        get() = when (kind) {
            Kind.SPIRAL_OUT, Kind.SPIRAL_IN -> (turn + 2.0 * PI * turns).rem(2.0 * PI).toFloat()
            Kind.ROSE -> turn
        }
}

object SandPaths {

    /** The roses the program cycles through: five, fourteen, eight and eleven petals' worth of spirograph. */
    private val ROSES = listOf(5 to 3, 7 to 4, 8 to 5, 11 to 6)

    /**
     * The [n]th track of a program for a disk of [radius] px raked at [spacing]
     * px, after a track that ended on [previous]'s angle. The cycle is: rake it
     * smooth, breathe back in, draw a rose; with the roses taken in turn and
     * [seed] choosing where a garden's program begins.
     */
    fun track(n: Int, radius: Float, spacing: Float, previous: SandTrack?, seed: Int = 0): SandTrack {
        val turn = previous?.endTurn ?: 0f
        val dense = max(4f, radius / spacing)
        return when ((n + seed) % 3) {
            0 -> SandTrack(SandTrack.Kind.SPIRAL_OUT, turns = dense, turn = turn)
            1 -> SandTrack(SandTrack.Kind.SPIRAL_IN, turns = max(3f, dense * 0.45f), waves = 5 + ((n + seed) / 3) % 4, turn = turn)
            else -> {
                val (p, q) = ROSES[((n + seed) / 3) % ROSES.size]
                SandTrack(SandTrack.Kind.ROSE, p = p, q = q, turn = turn + 0.37f * n)
            }
        }
    }

    /**
     * The seed a program actually runs on. A spiral out and a rose start at the
     * centre, but a spiral in starts at the rim, so a seed that would open on one
     * is moved on to the rose after it. Pass this, not the raw seed, to [track].
     */
    fun startSeed(seed: Int): Int = if (seed % 3 == 1) seed + 1 else seed

    /** The first track of the program [startSeed] runs, which begins at the centre. */
    fun first(radius: Float, spacing: Float, seed: Int): SandTrack = track(0, radius, spacing, null, startSeed(seed))

    /**
     * How far along [track] to move so the ball covers [stepPx] on a disk of
     * [radiusPx]: arc length, not parameter, so the ball rolls at one speed
     * through the tight middle of a spiral and round its wide rim alike.
     */
    fun advance(track: SandTrack, s: Double, stepPx: Float, radiusPx: Float): Double {
        val h = 1e-5
        val (x0, y0) = track.at(s)
        val (x1, y1) = track.at((s + h).coerceAtMost(1.0))
        val speed = hypot(x1 - x0, y1 - y0) / h * radiusPx
        return s + if (speed < 1e-6) h else stepPx / speed
    }
}
