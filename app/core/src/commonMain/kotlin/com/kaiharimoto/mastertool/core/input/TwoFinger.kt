package com.kaiharimoto.mastertool.core.input

import kotlin.math.abs

/**
 * What two fingers on the deck mean (touch swarm, rec 21), decided over their
 * first [DECIDE_DP] of travel and then held for the gesture.
 *
 * - Apart or together faster than they move together is a **zoom**.
 * - Moving together, up or down, with Groups on, sets the **gap** between the
 *   groups — the desk's Shift-wheel.
 * - A pinch *out* at full size asks for more deck than there is: it hides the
 *   pool and the inspector ([Kind.HIDE_PANES]). A pinch *in* at full size with
 *   them hidden brings them back ([Kind.SHOW_PANES]). A re-fit the finger asked
 *   for, as the panes' own hide buttons already are.
 */
object TwoFinger {
    const val DECIDE_DP = 12f
    const val HIDE_RATIO = 1.2f
    const val SHOW_RATIO = 0.85f

    /** The gap's step per [GAP_STEP_DP] of a two-finger slide: the wheel's step. */
    const val GAP_STEP = 0.15f
    const val GAP_STEP_DP = 24f

    enum class Kind { ZOOM, GAP, HIDE_PANES, SHOW_PANES, NONE }

    /**
     * [startApart] and [apart] are the fingers' distance at the start and now,
     * [centroidDx]/[centroidDy] how far their middle has moved, all in dp.
     */
    fun classify(
        startApart: Float,
        apart: Float,
        centroidDx: Float,
        centroidDy: Float,
        groupsOn: Boolean,
        zoomAtOne: Boolean,
        panesHidden: Boolean,
    ): Kind {
        val spread = abs(apart - startApart)
        val slide = maxOf(abs(centroidDx), abs(centroidDy))
        if (spread < DECIDE_DP && slide < DECIDE_DP) return Kind.NONE
        if (spread > slide) {
            val ratio = if (startApart > 0f) apart / startApart else 1f
            return when {
                zoomAtOne && !panesHidden && ratio > HIDE_RATIO -> Kind.HIDE_PANES
                zoomAtOne && panesHidden && ratio < SHOW_RATIO -> Kind.SHOW_PANES
                else -> Kind.ZOOM
            }
        }
        return if (groupsOn && abs(centroidDy) >= abs(centroidDx)) Kind.GAP else Kind.NONE
    }
}

/**
 * Undo by two fingers and redo by three (touch swarm, rec 22): every finger down
 * within [DOWN_SPREAD_MS] of the first, none travelling past the slop, all up
 * within [HOLD_LIMIT_MS] of the first down. A pinch is not a tap: it travels.
 */
object MultiTap {
    const val DOWN_SPREAD_MS = 150L
    const val HOLD_LIMIT_MS = 350L

    /** [downs] and [ups] in ms per finger, [travel] the most any finger moved, in the same unit as [slop]. */
    fun classify(downs: List<Long>, ups: List<Long>, travel: Float, slop: Float): TouchGesture? {
        if (downs.size != ups.size || downs.size !in 2..3) return null
        val first = downs.min()
        if (downs.max() - first > DOWN_SPREAD_MS) return null
        if (ups.max() - first > HOLD_LIMIT_MS) return null
        if (travel > slop) return null
        return if (downs.size == 2) TouchGesture.TWO_FINGER_TAP else TouchGesture.THREE_FINGER_TAP
    }
}
