package com.kaiharimoto.mastertool.core.layout

/** Which folded-away bars of the desktop window are showing. */
data class Revealed(
    val left: Boolean = false,
    val top: Boolean = false,
    val bottom: Boolean = false,
) {
    companion object {
        val NONE = Revealed()
    }
}

/**
 * The window's hidden bars, and when they come out: the index rail on the left
 * always, and — in immersive mode — the title bar with the builder's header
 * along the top and the builder's footer along the bottom.
 *
 * A bar comes out when the pointer reaches the window's edge, **over** the
 * page rather than pushing it, because a bar that pushed would re-fit the deck
 * every time the pointer went near an edge and every card would jump. It goes
 * back only once the pointer is clear of the bar by [SLACK], so a hand resting
 * on its edge does not flicker it.
 *
 * All lengths are in the same unit (the window's pixels).
 */
object EdgeReveal {

    /** How close to an edge counts as reaching for it. */
    const val EDGE = 8f

    /** How far past a shown bar the pointer may wander before it folds away. */
    const val SLACK = 24f

    /**
     * The next state, given where the pointer is (null when it has left the
     * window) and how big each bar is when shown.
     *
     * [holdTop] keeps the top bars out, once out, whatever the pointer does — a
     * deck name being typed in the header must not vanish under the caret. [suppress]
     * stops anything new coming out (a card being carried toward an edge is
     * aiming at the deck, not at a bar).
     */
    fun next(
        current: Revealed,
        x: Float?,
        y: Float?,
        height: Float,
        railWidth: Float,
        topHeight: Float,
        bottomHeight: Float,
        immersive: Boolean,
        holdTop: Boolean = false,
        suppress: Boolean = false,
    ): Revealed {
        if (x == null || y == null) {
            // Out of the window: keep what was out (the pointer usually comes
            // straight back), except that nothing new appears.
            return current.copy(top = immersive && current.top, bottom = immersive && current.bottom)
        }
        val left = when {
            current.left -> x <= railWidth + SLACK
            suppress -> false
            else -> x <= EDGE
        }
        val top = immersive && when {
            holdTop && current.top -> true
            current.top -> y <= topHeight + SLACK
            suppress -> false
            else -> y <= EDGE
        }
        val bottom = immersive && when {
            current.bottom -> y >= height - bottomHeight - SLACK
            suppress -> false
            else -> y >= height - EDGE
        }
        return Revealed(left = left, top = top, bottom = bottom)
    }
}
