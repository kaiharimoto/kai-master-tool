package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck

/**
 * Konami's Genesys format (September 2025): no Forbidden & Limited list, no Link or Pendulum monsters, and a
 * points cap on the whole deck — main, extra and side, each copy paying its card's points — of 100 unless the
 * event sets another. The points are the pool's (`Card.genesysPoints`, YGOPRODeck's copy of Konami's list).
 *
 * Kept in core and tested so Ai, the World, the validator and the builder (`DeckRules`, 1.1.1) agree.
 */
object GenesysRules {
    const val CAP = 100

    data class Result(
        /** The deck's points, every copy counted. */
        val points: Int,
        val cap: Int,
        /** What breaks the format, in words. */
        val problems: List<String>,
        /** Passcodes whose points the pool does not know: counted as 0, and said. */
        val unknown: List<CardId>,
        /** The Link and Pendulum monsters in it, each once: a printing of it and its name. */
        val barred: List<Pair<CardId, String>> = emptyList(),
        /** What each card costs the deck, every copy counted, the dearest first; cards worth no points left out. */
        val costs: List<Cost> = emptyList(),
    ) {
        val legal: Boolean get() = problems.isEmpty()

        /** Points past the cap, 0 when under it. */
        val over: Int get() = (points - cap).coerceAtLeast(0)
    }

    /** One card's share of the points: [copies] of it at [each], shown by a printing of it the deck holds ([id]). */
    data class Cost(val id: CardId, val name: String, val copies: Int, val each: Int) {
        val total: Int get() = copies * each
    }

    fun isBarred(card: Card): Boolean = barredKind(card) != null

    /** "Link" or "Pendulum" for a monster Genesys bars, else null: the word its issue names. */
    fun barredKind(card: Card): String? = when {
        card.frameType.contains("link", ignoreCase = true) -> "Link"
        card.frameType.contains("pendulum", ignoreCase = true) -> "Pendulum"
        else -> null
    }

    /** "Muckraker From the Underworld is a Link monster, which Genesys does not allow." */
    fun barredWords(name: String, kind: String?): String =
        "$name is a ${kind ?: "Link or Pendulum"} monster, which Genesys does not allow."

    fun check(deck: Deck, cards: (CardId) -> Card?, cap: Int = CAP): Result {
        val all = deck.main + deck.extra + deck.side
        var points = 0
        val unknown = mutableListOf<CardId>()
        val barred = LinkedHashMap<CardId, Pair<CardId, String>>()
        val kinds = HashMap<CardId, String?>()
        val costs = LinkedHashMap<CardId, Cost>()
        for (id in all) {
            val card = cards(id)
            if (card == null) {
                unknown += id
                continue
            }
            val p = card.genesysPoints
            if (p == null) unknown += id else points += p
            // By card, not printing: an alternate artwork is the same card (Phase B).
            if (p != null && p > 0) costs[card.id] = costs[card.id]?.let { it.copy(copies = it.copies + 1) } ?: Cost(id, card.name, 1, p)
            if (isBarred(card) && card.id !in barred) {
                barred[card.id] = id to card.name
                kinds[id] = barredKind(card)
            }
        }
        val problems = buildList {
            if (points > cap) add("The deck costs $points points; the cap is $cap.")
            barred.values.forEach { (id, name) -> add(barredWords(name, kinds[id])) }
        }
        val dearest = costs.values.sortedWith(compareByDescending<Cost> { it.total }.thenByDescending { it.each }.thenBy { it.name })
        return Result(points, cap, problems, unknown.distinct(), barred.values.toList(), dearest)
    }
}
