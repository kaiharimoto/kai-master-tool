package com.kaiharimoto.neue.cards

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The cards still waiting on their art, for whoever wants a finished picture (1.1.x, the audit's I2):
 * an export, Ai's look at a slide, a recording's frame. Provided through [LocalArtWaits]; a card drawn
 * under it holds a place while it has nothing to show — its original when the library has one, else
 * its small render — and lets go once a picture arrives or fails. Nothing is counted where it is not
 * provided, so the deck and the pool pay nothing for it.
 */
class ArtWaits {
    private val count = mutableIntStateOf(0)

    /** How many cards are waiting now: read in a snapshot, so a wait can watch it fall to nothing. */
    val pending: Int get() = count.intValue

    internal fun hold() {
        count.intValue++
    }

    internal fun release() {
        count.intValue = (count.intValue - 1).coerceAtLeast(0)
    }
}

val LocalArtWaits = staticCompositionLocalOf<ArtWaits?> { null }
