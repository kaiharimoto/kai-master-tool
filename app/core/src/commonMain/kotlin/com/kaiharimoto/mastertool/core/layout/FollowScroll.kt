package com.kaiharimoto.mastertool.core.layout

/**
 * Whether a list scrolls itself to keep its highlighted row in view — the
 * command palette's (`Ctrl K`).
 *
 * The palette followed its highlight wherever it came from, and the pointer
 * moves the highlight too: a wheel scrolled the rows under a still pointer, the
 * row that arrived under it took the highlight, and the list scrolled back to
 * it — the wheel fought the list (kai, 1.0.24: "when I try to scroll down with
 * the scroll wheel its interrupted by the auto scroll"). So only the keys move
 * the list, and once a hand has scrolled it — a wheel, a touchpad, a finger, the
 * scrollbar — the list is the hand's until the palette closes. A new one, made
 * when it opens again, follows again.
 */
class FollowScroll {

    /** Whether a hand has scrolled the list since it opened: the list no longer follows. */
    var released: Boolean = false
        private set

    /** A wheel, a touchpad, a drag or the scrollbar moved the list. */
    fun scrolledByHand() {
        released = true
    }

    /** Whether a highlight that just moved should bring its row into view: the keys' moves, until a hand scrolls. */
    fun follows(byKeys: Boolean): Boolean = byKeys && !released
}
