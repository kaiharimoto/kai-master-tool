package com.kaiharimoto.mastertool.core.layout

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Zen mode's garden: Fibonacci spirals in blooming sand, drawn over each other
 * forever, under a sun that goes round.
 *
 * **The spirals are a sunflower's.** A sunflower head shows two families of
 * spirals turning opposite ways, and their counts are consecutive Fibonacci
 * numbers — 34 one way, 55 the other in a large head. Each spiral is a golden
 * spiral: logarithmic, growing by φ every quarter turn, so θ = b·ln r with
 * b = π / (2 ln φ). The garden is centred on the floating deck, which sits where
 * the flower's head would be, and its arms come out from under the cards.
 *
 * **A groove is an arm.** Layer n's arms are the whole-and-a-half contours of
 * one phase, `arms · (θ − hand·b·(ln r − zoom) − turn) / 2π` ([phase]); a
 * spiral's own spacing grows with its radius (2πr / (arms·√(1 + b²)),
 * [spacing]), and 34 and 55 arms keep it between about 20 and 60 px across a
 * large window, which reads as a grain rather than as stripes.
 *
 * **The sand blooms.** A golden spiral zoomed is a golden spiral turned, so the
 * whole garden flows outward from under the deck forever, [FLOW] of its radius a
 * second, without a seam: a zoom of e^(2π/b) is exactly one turn, and the pattern
 * is where it started. Every layer flows, the old one under the new, and since
 * zooming a spiral turns it, each family seems to turn its own way — the new one
 * against the one it is covering.
 *
 * **How it is drawn — the algorithm.** Each arm is drawn outward from under the
 * deck by its own rake, in the flowing sand's own frame (or a groove would
 * flicker as it flowed past the edge of an arm half drawn): the rake gains
 * [GROWTH] in log-radius a second, so like the spiral it follows it opens out as
 * it goes, and on screen it gathers speed. The arms do not set off together:
 * arm k starts at [STAGGER]·frac(k/φ) — the golden-ratio sequence, which has the
 * three-gap property, so however many arms have started they are spread evenly
 * round the circle and never bunch (`SpiralGardenTest` holds it). A new groove
 * settles in over [FADE] seconds behind its rake, and where an arm that is drawn
 * lies beside one that is not, the two strips meet at the mean of their weights,
 * so nothing is ever cut with a step.
 *
 * **Forever.** When every arm has passed the far corner and a moment has passed
 * ([HOLD]), the next layer is drawn on top: the other family — 55 arms after 34,
 * the opposite way round — set round by the golden angle from the last, so it
 * never lies along the one it covers. Its arms cross the old ones as they grow
 * and replace them. There is no wipe; the garden is always the newest layer
 * over the one before, and the first is drawn over straight lines.
 *
 * **And the sun goes round.** The gravel is lit from a direction that circles
 * the garden once every [SUN_TURN] seconds ([sunAzimuth]), so the grooves' light
 * and shade turn slowly with it.
 *
 * Everything is a pure function of position and time, and the garden shader is
 * a line-for-line copy of [phase], [spacing], [start] and [weight]. Long runs stay
 * exact: the flow a layer was born into is folded into its [Layer.turn], so the
 * shader only ever sees the flow since the layer began.
 */
class SpiralGarden(val width: Float, val height: Float, val centreX: Float, val centreY: Float) {

    /** One family of arms: how many, which way they turn (±1), and how far round it is set (radians). */
    data class Layer(val index: Int, val arms: Int, val hand: Float, val turn: Float)

    /** Where the garden is at a moment: the layer being drawn and how many seconds into it. */
    data class Moment(val layer: Int, val tau: Float)

    /** How far the furthest corner of the window is from the centre: every arm is drawn that far. */
    val reach: Float = maxOf(
        hypot(centreX, centreY), hypot(width - centreX, centreY),
        hypot(centreX, height - centreY), hypot(width - centreX, height - centreY),
    )

    /**
     * Seconds into a layer when its last arm has passed the far corner: the last
     * sets off at [STAGGER], and a rake's tip is at R_IN·e^(GROWTH·(τ − start) + FLOW·τ).
     */
    val drawnBy: Float = (GROWTH * STAGGER + ln(reach / R_IN)) / (GROWTH + FLOW)

    /** Seconds from one layer's first arm setting off to the next's. */
    val layerTime: Float = drawnBy + FADE + HOLD

    fun layer(n: Int): Layer {
        val hand = if (n % 2 == 0) 1.0 else -1.0
        // The golden angle n times over, and the turn the flow has given the spiral by the
        // time the layer begins, both taken as whole turns first so they stay exact however
        // long zen runs.
        val turns = n * GOLDEN_TURN - hand * B * FLOW * (n.toDouble() * layerTime) / TAU
        return Layer(n, ARMS[n % ARMS.size], hand.toFloat(), ((turns - floor(turns)) * TAU).toFloat())
    }

    fun at(t: Float): Moment {
        val n = floor(max(0f, t) / layerTime).toInt()
        return Moment(n, max(0f, t) - n * layerTime)
    }

    private fun radius(x: Float, y: Float): Float = max(1f, hypot(x - centreX, y - centreY))

    /**
     * Which groove of [layer] is at ([x], [y]) once the sand has flowed [zoom] in
     * log-radius since the layer began: an arm's trough is wherever this is a
     * whole number and a half.
     */
    fun phase(layer: Layer, x: Float, y: Float, zoom: Float = 0f): Float {
        val theta = atan2(y - centreY, x - centreX)
        return layer.arms * (theta - layer.hand * B * (ln(radius(x, y)) - zoom) - layer.turn) / TAU.toFloat()
    }

    /** How far apart [layer]'s grooves are at ([x], [y]), in pixels: the flow does not change it. */
    fun spacing(layer: Layer, x: Float, y: Float): Float = (TAU * radius(x, y) / (layer.arms * ARC)).toFloat()

    /** When arm [strip] of [layer] sets off: the golden-ratio sequence, so the arms under way are always spread. */
    fun start(layer: Layer, strip: Int): Float {
        val k = ((strip % layer.arms) + layer.arms) % layer.arms
        val f = k / PHI
        return (STAGGER * (f - floor(f))).toFloat()
    }

    /**
     * How long [strip]'s rake has been past ([x], [y]) at [tau] seconds into
     * [layer]; negative before it arrives. Measured in the flowing sand, where the
     * point sits at log-radius ln r − FLOW·τ and the rake at ln R_IN + GROWTH·(τ − start).
     */
    private fun past(layer: Layer, strip: Int, x: Float, y: Float, tau: Float): Float =
        tau - start(layer, strip) - (ln(radius(x, y)) - FLOW * tau - ln(R_IN)) / GROWTH

    /** Where [strip]'s rake is on screen, as a radius, [tau] seconds into [layer]; null before it sets off. */
    fun tip(layer: Layer, strip: Int, tau: Float): Float? {
        val going = tau - start(layer, strip)
        return if (going < 0f) null else R_IN * exp(GROWTH * going + FLOW * tau)
    }

    /** How far [strip]'s groove has settled in at ([x], [y]), 0..1, [tau] seconds into [layer]. */
    private fun settled(layer: Layer, strip: Int, x: Float, y: Float, tau: Float): Float {
        val u = (past(layer, strip, x, y, tau) / FADE).coerceIn(0f, 1f)
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
        val p = phase(layer, x, y, FLOW * tau)
        val k = floor(p)
        val u = p - k - 0.5f
        val own = settled(layer, k.toInt(), x, y, tau)
        val next = settled(layer, k.toInt() + if (u >= 0f) 1 else -1, x, y, tau)
        val e = ((abs(u) - 0.3f) / 0.2f).coerceIn(0f, 1f)
        return own * (1f - e) + e * (own + next) / 2f
    }

    /** Where the sun is, [t] seconds into zen: the direction the gravel is lit from, round the garden (radians). */
    fun sunAzimuth(t: Float): Float {
        val turns = SUN_START + t.toDouble() / SUN_TURN
        return ((turns - floor(turns)) * TAU).toFloat()
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

        /** How fast the sand flows outward: the fraction of its radius it moves a second. */
        const val FLOW = 0.035f

        /** How fast a rake draws its arm, in log-radius a second of the flowing sand. */
        const val GROWTH = 0.085f

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

        /** Seconds the sun takes to go once round the garden. */
        const val SUN_TURN = 90.0

        /** Where the sun starts, in turns: up and to the left, as the gravel has always been lit. */
        const val SUN_START = 0.647
    }
}
