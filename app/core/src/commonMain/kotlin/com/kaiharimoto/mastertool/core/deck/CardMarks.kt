package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection

/**
 * What stands in a card's top-left corner, the slot the limit mark has always used (the 1.1.2 design review,
 * findings 5 and 8). One at most, in this order:
 *
 * 1. [Fails]: a card in the deck that fails a check of its own under the rules in force — not released by the day,
 *    over the day's limit, a Link or Pendulum in Genesys — wears an inverted `✕` (Master UI: emphasis by inversion),
 *    its tip the issue's own words.
 * 2. [Points]: while Genesys is on, the card's points. Genesys has no list, so there are no limit marks then; a card
 *    with no points known, or worth none, shows nothing.
 * 3. [Limit]: the list's `0`, `1`, `2` — Forbidden always, Limited and Semi-Limited when the Limit marks setting asks.
 */
sealed interface CornerMark {
    data class Fails(val why: String) : CornerMark
    data class Points(val points: Int) : CornerMark
    data class Limit(val copies: Int) : CornerMark
}

/**
 * The deck's per-card failures, read off the validation of the rules in force and counted by card ([CardIdentity]:
 * an alternate artwork fails with its card). A card misplaced in a section fails there only; anything else that names
 * a card fails every copy of it.
 */
class CardFailures private constructor(
    private val whole: Map<CardId, List<String>>,
    private val placed: Map<Pair<CardId, DeckSection>, List<String>>,
) {
    val isEmpty: Boolean get() = whole.isEmpty() && placed.isEmpty()

    /** Why [card] fails where it stands in [section], in the issues' words, else null. */
    fun of(card: Card, section: DeckSection?): String? {
        val why = whole[card.id].orEmpty() + (section?.let { placed[card.id to it] }.orEmpty())
        return why.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** The cards (canonical passcodes) that fail anywhere. */
    val cards: Set<CardId> get() = whole.keys + placed.keys.map { it.first }

    // Equal failures are equal, so an edit that changes none of them leaves every card's marks as they were (and
    // the cards drawn with them skip recomposing).
    override fun equals(other: Any?): Boolean = other is CardFailures && whole == other.whole && placed == other.placed

    override fun hashCode(): Int = 31 * whole.hashCode() + placed.hashCode()

    companion object {
        val NONE = CardFailures(emptyMap(), emptyMap())

        /**
         * [validation]'s errors that name a card, by card. Warnings and whole-deck errors (sizes, points over the cap)
         * mark nothing: they are no one card's fault.
         */
        fun of(validation: DeckValidation, cards: (CardId) -> Card?): CardFailures {
            val whole = LinkedHashMap<CardId, MutableList<String>>()
            val placed = LinkedHashMap<Pair<CardId, DeckSection>, MutableList<String>>()
            validation.errors.forEach { issue ->
                val id = issue.cardId ?: return@forEach
                val card = cards(id) ?: return@forEach
                val key = CardIdentity.canonical(id, cards)
                val section = issue.section
                // In a section the card may not stand in: that section's copies only. (A Genesys-barred card names
                // its section for Show, but is barred wherever it is.)
                if (section != null && !DeckEditor.sectionAccepts(card, section)) {
                    placed.getOrPut(key to section) { mutableListOf() }.add(issue.message)
                } else {
                    whole.getOrPut(key) { mutableListOf() }.add(issue.message)
                }
            }
            return if (whole.isEmpty() && placed.isEmpty()) NONE else CardFailures(whole, placed)
        }

        /** [rules] checked on [deck] on [today], by card. */
        fun of(rules: DeckRules, deck: Deck, cards: (CardId) -> Card?, today: String): CardFailures =
            of(rules.validate(deck, cards, today), cards)
    }
}

/**
 * Every corner mark the builder draws, from one place: the rules in force and the deck's failures. Handed to each
 * card the builder draws (deck, pool, search, viewer, the carried card); only a card drawn *in* the deck is given
 * its section, and only that can fail.
 */
data class CardMarks(val rules: DeckRules = DeckRules(), val failures: CardFailures = CardFailures.NONE) {

    /**
     * [card]'s corner mark. [section] is where it stands in the deck, null for a card drawn anywhere else (the pool, a
     * search, the viewer); [limitMarks] is the setting that shows Limited and Semi-Limited too.
     */
    fun of(card: Card, section: DeckSection? = null, limitMarks: Boolean = false): CornerMark? {
        if (section != null) failures.of(card, section)?.let { return CornerMark.Fails(it) }
        if (rules.genesys) return card.genesysPoints?.takeIf { it > 0 }?.let { CornerMark.Points(it) }
        val status = rules.statusOf(card)
        return if (status != BanStatus.UNLIMITED && (status.maxCopies == 0 || limitMarks)) CornerMark.Limit(status.maxCopies) else null
    }
}
