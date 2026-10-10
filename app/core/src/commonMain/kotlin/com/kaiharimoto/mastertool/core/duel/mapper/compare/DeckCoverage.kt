package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit

/**
 * What the engine can play of a deck (Phase G, G4): its distinct cards (Main and Extra Deck, canonical), those it plays —
 * a trusted script, or a Normal Monster with nothing to know — and those it plays as inert. Every share the Mapper and the
 * goldfish show is a share of what the [playing] cards can do: the inert ones never act, so the number is a floor.
 */
data class DeckCoverage(val cards: List<Int>, val playing: List<Int>, val inert: List<Int>, val copies: Map<Int, Int>) {
    /** "24 of 30 cards play": the line beside every share. */
    val words: String
        get() = if (inert.isEmpty()) "Every card of the deck plays" else "${playing.size} of ${cards.size} cards play"

    /** Copies of the deck that are inert. */
    val inertCopies: Int get() = inert.sumOf { copies[it] ?: 0 }

    companion object {
        fun of(deck: GoldfishDeck, kit: GoldfishKit): DeckCoverage {
            val all = (deck.main + deck.extra).map(kit::canonical).filter { it != GoldfishKit.BLANK }
            val copies = all.groupingBy { it }.eachCount()
            val cards = copies.keys.sorted()
            val (playing, inert) = cards.partition(kit::known)
            // The inert cards most copies first: writing one of three copies opens more hands than one of one.
            return DeckCoverage(cards, playing, inert.sortedWith(compareByDescending<Int> { copies[it] ?: 0 }.thenBy { it }), copies)
        }
    }
}
