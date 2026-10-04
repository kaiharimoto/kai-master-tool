package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck

/**
 * Konami's Genesys format (September 2025): no Forbidden & Limited list, no Link or Pendulum monsters, and a
 * points cap on the whole deck — main, extra and side, each copy paying its card's points — of 100 unless the
 * event sets another. The points are the pool's (`Card.genesysPoints`, YGOPRODeck's copy of Konami's list).
 *
 * Kept in core and tested so Ai, the World and the validator agree; the builder's Genesys switch waits for a
 * format choice older builds can read (`docs/phases/B.md` §2).
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
    ) {
        val legal: Boolean get() = problems.isEmpty()
    }

    fun isBarred(card: Card): Boolean =
        card.frameType.contains("link", ignoreCase = true) || card.frameType.contains("pendulum", ignoreCase = true)

    fun check(deck: Deck, cards: (CardId) -> Card?, cap: Int = CAP): Result {
        val all = deck.main + deck.extra + deck.side
        var points = 0
        val unknown = mutableListOf<CardId>()
        val barred = LinkedHashMap<CardId, Pair<CardId, String>>()
        all.forEach { id ->
            val card = cards(id)
            if (card == null) {
                unknown += id
                return@forEach
            }
            val p = card.genesysPoints
            if (p == null) unknown += id else points += p
            if (isBarred(card) && card.id !in barred) barred[card.id] = id to card.name
        }
        val problems = buildList {
            if (points > cap) add("The deck costs $points points; the cap is $cap.")
            barred.values.forEach { (_, name) -> add("$name is a Link or Pendulum monster, which Genesys does not allow.") }
        }
        return Result(points, cap, problems, unknown.distinct(), barred.values.toList())
    }
}
