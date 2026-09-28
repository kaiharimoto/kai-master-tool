package com.kaiharimoto.mastertool.core.library

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck

/**
 * A deck's tags, read off its cards (kai, 1.0.18: "a tagging system based on the
 * card type in the deck"). Nothing to maintain, because nothing is typed in: the
 * archetypes it is built on, the ways it summons, and the kind of card it leans
 * on. Ordered that way, most telling first, so the first two or three are the
 * ones a row has room for.
 */
object DeckTags {
    /** An archetype names a deck when it holds this many of its main deck's cards. */
    const val ARCHETYPE_FLOOR = 6

    fun of(deck: Deck, card: (CardId) -> Card?): List<String> {
        val main = deck.main.mapNotNull(card)
        val extra = deck.extra.mapNotNull(card)
        val archetypes = main.mapNotNull { it.archetype?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount()
            .filterValues { it >= ARCHETYPE_FLOOR }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(2).map { it.key }
        val frames = (main + extra).map { it.frameType.lowercase() }
        val mechanics = listOf(
            "Fusion" to "fusion", "Synchro" to "synchro", "Xyz" to "xyz",
            "Link" to "link", "Ritual" to "ritual", "Pendulum" to "pendulum",
        ).filter { (_, frame) -> frames.count { frame in it } >= 2 }.map { it.first }
        val n = main.size.coerceAtLeast(1)
        fun share(k: CardCategory) = main.count { it.category == k }.toDouble() / n
        val lean = buildList {
            if (share(CardCategory.MONSTER) >= 0.6) add("Monster-heavy")
            if (share(CardCategory.SPELL) >= 0.4) add("Spell-heavy")
            if (share(CardCategory.TRAP) >= 0.3) add("Trap-heavy")
        }
        return archetypes + mechanics + lean
    }
}

/**
 * Whether a deck answers a search in the library (kai, 1.0.18: "I can type the
 * name of a card and it will filter decks by those with the card in it"): by its
 * own name, or by the name of any card in it — the cards it matched on are
 * returned so the row can say why it is there — or by one of its [tags].
 */
object DeckSearch {
    data class Match(val byName: Boolean, val cards: List<String>, val tags: List<String>)

    fun match(name: String, deck: Deck, tags: List<String>, query: String, card: (CardId) -> Card?): Match? {
        val q = query.trim()
        if (q.isEmpty()) return Match(byName = true, cards = emptyList(), tags = emptyList())
        val byName = name.contains(q, ignoreCase = true)
        val cards = (deck.main + deck.extra + deck.side).distinct()
            .mapNotNull { card(it)?.name }
            .filter { it.contains(q, ignoreCase = true) }
        val tagged = tags.filter { it.contains(q, ignoreCase = true) }
        return if (byName || cards.isNotEmpty() || tagged.isNotEmpty()) Match(byName, cards, tagged) else null
    }
}
