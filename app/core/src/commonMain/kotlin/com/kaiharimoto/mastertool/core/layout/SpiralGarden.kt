package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Zen mode's garden: Fibonacci spirals, drawn over each other forever.
 *
 * **The spirals are a sunflower's.** A sunflower head shows two families of
 * spirals turning opposite ways, and their counts are consecutive Fibonacci
 * numbers — 34 one way, 55 the other in a large head. Each spiral is a golden
 * spiral: logarithmic, growing by φ every quarter turn, so θ = b·ln r with
 * b = π / (2 ln φ). The garden is centred on the floating deck, which sits where
 * the flower's head would be, and its arms come out from under the cards.
 *
 * **A groove is an arm.** Layer n's arms are the whole-and-a-half contours of
 * one phase, `arms · (θ − hand·b·ln r − turn) / 2π` ([phase]); a spiral's own
 * spacing grows with its radius (2πr / (arms·√(1 + b²)), [spacing]), and 34
 * and 55 arms keep it between about 20 and 60 px across a large window, which
 * reads as a grain rather than as stripes.
 *
 * **How it is drawn — the algorithm.** Each arm is drawn outward from under the
 * deck by its own rake, at [SPEED] along the curve. The arms do not set off
 * together: arm k starts at [STAGGER]·frac(k/φ) — the golden-ratio sequence,
 * which has the three-gap property, so however many arms have started they are
 * spread evenly round the circle and never bunch (`SpiralGardenTest` holds it).
 * A new groove settles in over [FADE] seconds behind its rake, and where an
 * arm that is drawn lies beside one that is not, the two strips meet at the
 * mean of their weights, so nothing is ever cut with a step.
 *
 * **Forever.** When every arm has reached the far corner and a moment has
 * passed ([HOLD]), the next layer is drawn on top: the other family — 55 arms
 * after 34, the opposite way round — turned by the golden angle from the last,
 * so it never lies along the one it covers. Its arms cross the old ones as they
 * grow and replace them. There is no wipe; the garden is always the newest
 * layer over the one before, and the first is drawn over straight lines.
 *
 * Everything is a pure function of position and time, and the garden shader is
 * a line-for-line copy of [phase], [spacing], [start] and [weight].
 */
class SpiralGarden(val width: Float, val height: Float, val centreX: Float, val centreY: Float) {

    /** One family of arms: how many, which way they turn (±1), and how far round it is set (radians). */
    data class Layer(val index: Int, val arms: Int, val hand: Float, val turn: Float)

    /** Where the garden is at a moment: the layer being drawn and how many seconds into it. */
    data class Moment(val layer: Int, val tau: Float)

    fun layer(n: Int): Layer {
        // The golden angle, n times over, taken as whole turns first so it stays exact however long zen runs.
        val turns = n * GOLDEN_TURN
        return Layer(n, ARMS[n % ARMS.size], if (n % 2 == 0) 1f else -1f, ((turns - floor(turns)) * TAU).toFloat())
    }

    /** How far the furthest corner of the window is from the centre: every arm is drawn that far. */
    val reach: Float = maxOf(
        hypot(centreX, centreY), hypot(width - centreX, centreY),
        hypot(centreX, height - centreY), hypot(width - centreX, height - centreY),
    )

    /** Seconds from one layer's first arm setting off to the next's. */
    val layerTime: Float = STAGGER + max(0f, reach - R_IN) * ARC / SPEED + HOLD

    fun at(t: Float): Moment {
        val n = floor(max(0f, t) / layerTime).toInt()
        return Moment(n, max(0f, t) - n * layerTime)
    }

    private fun radius(x: Float, y: Float): Float = max(1f, hypot(x - centreX, y - centreY))

    /** Which groove of [layer] is at ([x], [y]): an arm's trough is wherever this is a whole number and a half. */
    fun phase(layer: Layer, x: Float, y: Float): Float {
        val theta = atan2(y - centreY, x - centreX)
        return layer.arms * (theta - layer.hand * B * ln(radius(x, y)) - layer.turn) / TAU.toFloat()
    }

    /** How far apart [layer]'s grooves are at ([x], [y]), in pixels. */
    fun spacing(layer: Layer, x: Float, y: Float): Float = (TAU * radius(x, y) / (layer.arms * ARC)).toFloat()

    /** When arm [strip] of [layer] sets off: the golden-ratio sequence, so the arms under way are always spread. */
    fun start(layer: Layer, strip: Int): Float {
        val k = ((strip % layer.arms) + layer.arms) % layer.arms
        val f = k / PHI
        return (STAGGER * (f - floor(f))).toFloat()
    }

    /** When [strip]'s rake passes the radius of ([x], [y]). */
    fun reveal(layer: Layer, strip: Int, x: Float, y: Float): Float =
        start(layer, strip) + max(0f, radius(x, y) - R_IN) * ARC / SPEED

    /** Where [strip]'s rake is, as a radius, [tau] seconds into [layer]; null before it sets off. */
    fun tip(layer: Layer, strip: Int, tau: Float): Float? {
        val going = tau - start(layer, strip)
        return if (going < 0f) null else R_IN + going * SPEED / ARC
    }

    /** How far [strip]'s groove has settled in at ([x], [y]), 0..1, [tau] seconds into [layer]. */
    private fun settled(layer: Layer, strip: Int, x: Float, y: Float, tau: Float): Float {
        val u = ((tau - reveal(layer, strip, x, y)) / FADE).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /**
     * How much of [layer] shows at ([x], [y]) over the layer beneath, [tau] seconds
     * in. Each arm's strip runs from ridge to ridge round its trough; within a
     * fifth of a groove of a strip's edge the weight moves toward the mean of the
     * two strips', which both sides agree on, so a drawn arm beside an undrawn
     * one meets it without a step.
     */
    fun weight(layer: Layer, x: Float, y: Float, tau: Float): Float {
        val p = phase(layer, x, y)
        val k = floor(p)
        val u = p - k - 0.5f
        val own = settled(layer, k.toInt(), x, y, tau)
        val next = settled(layer, k.toInt() + if (u >= 0f) 1 else -1, x, y, tau)
        val e = ((kotlin.math.abs(u) - 0.3f) / 0.2f).coerceIn(0f, 1f)
        return own * (1f - e) + e * (own + next) / 2f
    }

    companion object {
        private const val TAU = 2 * PI

        val PHI: Double = (1 + sqrt(5.0)) / 2

        /** The golden angle as a fraction of a turn: 1 − 1/φ. */
        val GOLDEN_TURN: Double = 1 - 1 / PHI

        /** The golden spiral's winding: θ = b·ln r grows r by φ every quarter turn. */
        val B: Float = (PI / (2 * ln(PHI))).toFloat()

        /** Length along a golden spiral per pixel of radius. */
        val ARC: Float = sqrt(1f + B * B)

        /** The two families of a large sunflower head, drawn in turn. */
        val ARMS = listOf(34, 55)

        /** How fast a rake draws its arm, px/s along the curve. */
        const val SPEED = 260f

        /** Seconds over which a layer's arms set off. */
        const val STAGGER = 8f

        /** Where an arm is first drawn from: inside this the deck hides it. */
        const val R_IN = 260f

        /** Seconds a finished layer rests before the next is drawn over it. */
        const val HOLD = 5f

        /** Seconds a new groove takes to settle in behind its rake. */
        const val FADE = 0.6f

        /** The pitch of the straight lines the first layer is drawn over. */
        const val PITCH = 32f
    }
}
