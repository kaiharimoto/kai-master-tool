package com.kaiharimoto.mastertool.core.input

/**
 * A finger's taps, counted the platform's way (touch swarm, rec 11).
 *
 * Each card used to count its own taps from the first *press*, so a double-tap
 * that drifted onto the neighbouring card was two single taps on two cards, a
 * slow first tap ate the window, and a third tap in the pool did nothing. Here
 * one burst is owned by a *surface* — the pool's grid, a deck section — and a
 * second press within [DeskTouch.DOUBLE_TAP_MS] of the first **lift**, at least
 * [MIN_GAP_MS] after it and within [SLOP_DP] of the first press, is a
 * double-tap on the **first** card, whatever card it landed on. In the pool
 * ([repeats]) each further tap is another add; in the deck a burst ends at its
 * double-tap, which removes a copy, and the next tap starts afresh.
 *
 * Positions are in dp; the caller converts.
 */
class TapBurst(
    private val repeats: Boolean,
    private val windowMs: Long = DeskTouch.DOUBLE_TAP_MS,
) {
    enum class Kind { TAP, DOUBLE_TAP, REPEAT }

    data class Result(val kind: Kind, val key: Int)

    private var anchorKey: Int? = null
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastUp = 0L
    private var taps = 0

    private var downAt = 0L
    private var downKey = 0
    private var downX = 0f
    private var downY = 0f

    fun press(atMs: Long, x: Float, y: Float, key: Int) {
        downAt = atMs
        downKey = key
        downX = x
        downY = y
    }

    /** The tap this lift completes. */
    fun release(atMs: Long): Result {
        val anchor = anchorKey
        val gap = downAt - lastUp
        val dx = downX - anchorX
        val dy = downY - anchorY
        val near = dx * dx + dy * dy <= SLOP_DP * SLOP_DP
        val continues = anchor != null && gap in MIN_GAP_MS..windowMs && near && (taps == 1 || repeats && taps >= 2)
        if (continues) {
            taps++
            lastUp = atMs
            val kind = if (taps == 2) Kind.DOUBLE_TAP else Kind.REPEAT
            if (!repeats) anchorKey = null
            return Result(kind, anchor!!)
        }
        anchorKey = downKey
        anchorX = downX
        anchorY = downY
        taps = 1
        lastUp = atMs
        return Result(Kind.TAP, downKey)
    }

    /** Forget the burst: a drag, a hold or a second finger ended it. */
    fun reset() {
        anchorKey = null
        taps = 0
    }

    companion object {
        /** A second press sooner than this after a lift is a bounce, not a tap. */
        const val MIN_GAP_MS = 40L

        /** How far a double-tap's second press may land from its first, in dp. */
        const val SLOP_DP = 24f
    }
}
