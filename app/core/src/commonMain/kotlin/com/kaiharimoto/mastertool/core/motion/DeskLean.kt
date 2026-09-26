package com.kaiharimoto.mastertool.core.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** How a card on the desktop builder is leaning: degrees about each axis, and how far it has come up off the page. */
data class LeanPose(
    /** Degrees about the horizontal axis; positive brings the top edge toward the viewer. */
    val rotationX: Float = 0f,
    /** Degrees about the vertical axis; positive brings the right edge toward the viewer. */
    val rotationY: Float = 0f,
    /** Fraction the card is scaled up by: 0.03 is three per cent larger. */
    val lift: Float = 0f,
) {
    /** The lean as a light direction for the foil, -1..1 on each axis, so a card catches light as it turns. */
    fun light(maxDegrees: Float): Pair<Float, Float> =
        (rotationY / maxDegrees).coerceIn(-1f, 1f) to (-rotationX / maxDegrees).coerceIn(-1f, 1f)

    companion object {
        val REST = LeanPose()
    }
}

/**
 * The desktop builder's one piece of card motion: the deck leans toward the
 * pointer, and a carried card leans behind it.
 *
 * Master UI moves nothing but opacity, colour and position, and kai asked for
 * this anyway — *"have the cards animate and react by tilting in a satisfying
 * way"*, and a card that *"slightly tilts and lifts in 3d space"* when it is
 * picked up. It is the third exception, and like the other two it is confined:
 * cards move; chrome never does.
 *
 * ## The picture it is drawing
 *
 * The pointer is a finger pressed up under a cloth the cards lie on. The card
 * over it rises; the cards around it sit on the slope of the bump, so each one's
 * edge nearest the pointer is higher than its far edge. Sweep across the deck
 * and the bump travels with you. It is one picture rather than two rules, which
 * is why the card under the pointer and its neighbours can never disagree about
 * which way is up.
 *
 * Everything is in card widths, so the effect is the same at any interface
 * scale and on any display.
 */
object DeskLean {

    /** The most any card in the deck leans. Past about ten degrees it reads as a flip rather than a lean. */
    const val MAX_DEGREES = 9f

    /** How far the bump reaches, in card widths from the pointer, before a neighbour stops noticing. */
    const val REACH = 1.6f

    /** How far the card under the pointer comes up. */
    const val HOVER_LIFT = 0.035f

    /** How far a carried card comes up: more than a hover, because it has left the page. */
    const val CARRY_LIFT = 0.07f

    /** The most a carried card leans behind the pointer. */
    const val CARRY_DEGREES = 14f

    /**
     * The lean of a card whose centre is ([dx], [dy]) card widths from the
     * pointer (pointer minus centre), with [presence] 0..1 fading the whole bump
     * in and out.
     */
    fun toward(dx: Float, dy: Float, presence: Float = 1f): LeanPose {
        if (presence <= 0f || !dx.isFinite() || !dy.isFinite()) return LeanPose.REST
        val r = sqrt(dx * dx + dy * dy)
        if (r >= REACH * 2.5f) return LeanPose.REST
        // Over the card, the lean grows from flat at its centre to full at its
        // edge — a pointer in the middle of a card is a finger straight under it.
        // Past the edge it falls away like the side of a bump.
        val shape = if (r <= EDGE) r / EDGE else exp(-((r - EDGE) / REACH).pow(2) * 2f)
        val degrees = MAX_DEGREES * shape * presence
        val ux = if (r > 1e-4f) dx / r else 0f
        val uy = if (r > 1e-4f) dy / r else 0f
        // The edge nearest the pointer comes up: pointer to the right (dx > 0)
        // brings the right edge forward; pointer above (dy < 0) brings the top.
        val over = if (r <= EDGE) 1f else exp(-((r - EDGE) / (REACH * 0.5f)).pow(2) * 2f)
        return LeanPose(
            rotationX = -uy * degrees,
            rotationY = ux * degrees,
            lift = HOVER_LIFT * over * presence,
        )
    }

    /**
     * A carried card: it trails the pointer by ([lagX], [lagY]) card widths
     * (pointer minus where the card has caught up to), and leans back against
     * the motion like something dragged through air — its leading edge up.
     * [raise] 0..1 is how far it has been lifted.
     */
    fun carried(lagX: Float, lagY: Float, raise: Float = 1f): LeanPose {
        if (!lagX.isFinite() || !lagY.isFinite()) return LeanPose(lift = CARRY_LIFT * raise)
        fun soft(v: Float) = v / (1f + abs(v))
        val gain = 3f
        return LeanPose(
            rotationX = -soft(lagY * gain) * CARRY_DEGREES,
            rotationY = soft(lagX * gain) * CARRY_DEGREES,
            lift = CARRY_LIFT * raise.coerceIn(0f, 1f),
        )
    }

    /**
     * Frame-rate independent approach: after [halfLife] seconds, [current] has
     * closed half its distance to [target] — at 60 Hz and 144 Hz alike. The
     * family's one easing is a curve over a fixed duration; a thing that
     * *follows* a pointer has no duration, so this is its easing.
     */
    fun approach(current: Float, target: Float, dtSeconds: Float, halfLife: Float): Float {
        if (dtSeconds <= 0f) return current
        if (halfLife <= 0f) return target
        val k = 0.5f.pow(dtSeconds / halfLife)
        return target + (current - target) * k
    }

    /** Half the width of a card, in card widths: where the pointer leaves the card it is over. */
    private const val EDGE = 0.5f
}

/**
 * The deck's bump, smoothed: where it is, and how present it is. One of these
 * per deck, stepped by one frame loop, read by every card.
 *
 * It settles, and says so ([settled]) so the loop that steps it can stop —
 * nothing idles.
 */
class LeanField(
    private val followHalfLife: Float = 0.045f,
    private val fadeInHalfLife: Float = 0.06f,
    private val fadeOutHalfLife: Float = 0.12f,
) {
    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var presence: Float = 0f
        private set

    private var targetX = 0f
    private var targetY = 0f
    private var active = false

    /** The pointer is at ([px], [py]); a bump that was absent appears there rather than sliding in from the last place. */
    fun aim(px: Float, py: Float) {
        if (presence < 0.01f) {
            x = px
            y = py
        }
        targetX = px
        targetY = py
        active = true
    }

    /** The pointer has left. */
    fun release() {
        active = false
    }

    fun step(dtSeconds: Float) {
        x = DeskLean.approach(x, targetX, dtSeconds, followHalfLife)
        y = DeskLean.approach(y, targetY, dtSeconds, followHalfLife)
        presence = DeskLean.approach(presence, if (active) 1f else 0f, dtSeconds, if (active) fadeInHalfLife else fadeOutHalfLife)
        if (settled) {
            x = targetX
            y = targetY
            presence = if (active) 1f else 0f
        }
    }

    /** Close enough to where it is going that another frame would move nothing a person could see. */
    val settled: Boolean
        get() = abs(x - targetX) < 0.25f && abs(y - targetY) < 0.25f &&
            abs(presence - (if (active) 1f else 0f)) < 0.004f
}
