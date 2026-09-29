package com.kaiharimoto.mastertool.core.layout

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * How much of a tall window the card pool is taking.
 *
 * Three stops rather than a free height, because a dock you can leave at any
 * height is a dock you have to *aim*, and the whole point of putting it under
 * the thumbs is that it can be worked without looking.
 */
@Serializable
enum class PoolStop {
    /** The search field and its row of chips, and nothing else. */
    PEEK,

    /** Half the window: results above, deck still visible above them. */
    HALF,

    /** The pool takes the screen. What typing means. */
    FULL,
}

/**
 * The pool dock's geometry, in pixels, for one window.
 *
 * [chrome] is everything in the dock that is not results — the grabber, the
 * chip row and the search field. It is passed in rather than assumed for the
 * same reason [DeckFitter] takes a chrome height: only the UI knows how tall
 * its own furniture is, and a stop computed against a guess is a stop that
 * clips the field it exists to keep reachable.
 *
 * [minDeck] is how much of the deck must survive at [PoolStop.HALF]. Without
 * it, half of a short window is less than one row of cards, and "half" stops
 * meaning anything.
 */
data class DockMetrics(
    val windowHeight: Float,
    val chrome: Float,
    val minDeck: Float,
    /**
     * The deck's own height, when it has one (v1.3.6: a phone's deck is drawn 10×4 at
     * the width of the screen, so it is as tall as it is and no taller). Then
     * [PoolStop.HALF] is everything under the deck — the room it does not use is the
     * pool's — rather than half the window.
     */
    val deck: Float? = null,
)

/**
 * Where the card pool sits in the tall builder, and what moves it.
 *
 * ## The one design claim in here
 *
 * *Typing means the pool.* Somebody who has put a finger in the search field
 * has stopped looking at their deck and started looking for a card, so the dock
 * goes to [PoolStop.FULL] and the deck goes away — which on a phone is the
 * difference between four results visible above the keyboard and twenty.
 *
 * The return trip is the half that is easy to get wrong. Dropping straight back
 * to wherever the dock was would land on [PoolStop.PEEK] for anybody who opened
 * it by tapping the field, hiding the results the search just produced at the
 * exact moment they became useful. So [afterTyping] never returns below
 * [PoolStop.HALF].
 */
object PoolDock {

    /** What [PoolStop.HALF] means, before the two clamps get at it. */
    const val HALF_FRACTION = 0.5f

    fun height(stop: PoolStop, metrics: DockMetrics): Float {
        val full = metrics.windowHeight.coerceAtLeast(0f)
        val peek = metrics.chrome.coerceIn(0f, full)
        return when (stop) {
            PoolStop.PEEK -> peek
            PoolStop.FULL -> full
            // Half the window, but never so much that the deck is squeezed out
            // of existence and never less than the dock's own furniture.
            PoolStop.HALF -> (metrics.deck?.let { full - it } ?: (full * HALF_FRACTION))
                .coerceAtMost((full - metrics.minDeck).coerceAtLeast(peek))
                .coerceIn(peek, full)
        }
    }

    /** The stop a released drag settles on: whichever one it is nearest. */
    fun nearest(heightPx: Float, metrics: DockMetrics): PoolStop {
        var best = PoolStop.PEEK
        var bestGap = Float.MAX_VALUE
        for (stop in PoolStop.entries) {
            val gap = abs(height(stop, metrics) - heightPx)
            if (gap < bestGap) {
                bestGap = gap
                best = stop
            }
        }
        return best
    }

    /** What a finger landing in the search field does. */
    fun whileTyping(): PoolStop = PoolStop.FULL

    /**
     * Where the dock goes when the field is let go of.
     *
     * Never [PoolStop.PEEK]: the results of the search that was just typed are
     * the reason the dock was opened, and a dock that closes over them the
     * moment the keyboard goes down has thrown the answer away.
     */
    fun afterTyping(before: PoolStop): PoolStop =
        if (before == PoolStop.PEEK) PoolStop.HALF else before

    /**
     * How much room is left above the dock.
     *
     * Read at a settled stop rather than during a drag, which is what keeps the
     * deck from re-solving its whole layout sixty times a second while a thumb
     * is moving the dock.
     */
    fun deckHeight(stop: PoolStop, metrics: DockMetrics): Float =
        (metrics.windowHeight - height(stop, metrics)).coerceAtLeast(0f)
}
