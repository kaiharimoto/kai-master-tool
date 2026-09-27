package com.kaiharimoto.mastertool.core.library

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry

/**
 * The deck the builder opens with (kai, 1.0.14: "allow me to set a deck as the
 * default deck to load in with — if there is no deck loaded, pick one
 * automatically from the library").
 *
 * The default when it is still in the library, else the deck worked on last —
 * the most recently saved — else nothing, and the builder opens empty. A default
 * that has been deleted is not an error: it is a preference about a deck that is
 * gone, and the next best answer is the one a library with no default gives.
 */
object StartingDeck {
    fun pick(decks: List<DeckEntry>, defaultId: String?): String? {
        if (decks.isEmpty()) return null
        defaultId?.let { id -> decks.firstOrNull { it.id == id }?.let { return it.id } }
        return decks.maxWithOrNull(compareBy<DeckEntry> { it.updatedAtEpochMs }.thenBy { it.createdAtEpochMs })?.id
    }
}

/**
 * The pictures a deck is known by in the library: up to [MAX] cards the person
 * chose (kai, 1.0.14), in the order they were chosen.
 *
 * Choosing a fourth lets go of the first, so "make this a cover" always does
 * something visible rather than refusing. A cover that has left the deck is
 * not drawn — a deck's face should be a card that is in it — and a deck with no
 * cover chosen, or none still in it, shows its most-played main-deck card, which
 * is what every deck showed before there was a choice.
 */
object DeckCovers {
    const val MAX = 3

    /** [card] made a cover of [current], or taken off it if it already is one. */
    fun toggle(current: List<Int>, card: Int): List<Int> =
        if (card in current) current - card else (current + card).takeLast(MAX)

    /** What to draw for [deck], given the covers chosen for it: never empty for a deck with a main deck. */
    fun shown(chosen: List<Int>, deck: Deck): List<CardId> {
        val inDeck = (deck.main + deck.extra + deck.side).map { it.value }.toSet()
        val kept = chosen.distinct().filter { it in inDeck }.take(MAX).map(::CardId)
        if (kept.isNotEmpty()) return kept
        return listOfNotNull(deck.main.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
    }
}
