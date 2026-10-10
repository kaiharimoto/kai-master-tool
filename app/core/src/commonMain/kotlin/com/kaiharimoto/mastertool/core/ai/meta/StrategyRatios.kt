package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import kotlin.math.round

/**
 * One card of a strategy's lists against yours (Phase G, G.5; the red team's F2): the weighted [share] of lists playing it
 * in [section], its [mean] and [mode] copies where it is played, and how many [yours] holds there. Counts only: a list that
 * placed tells what it played, never that the card is why.
 */
data class CardRatio(
    val card: CardId,
    val section: DeckSection,
    val share: Double,
    val mean: Double,
    val mode: Int,
    val yours: Int,
)

/** A strategy's lists read card by card ([of]), with your list beside them, and what differs in three kinds. */
class StrategyRatios(val name: String, val lists: Int, val rows: List<CardRatio>) {
    /** "The field plays it, you don't": in at least half the lists, and not in yours. */
    val missing: List<CardRatio> get() = rows.filter { it.share >= PLAYED && it.yours == 0 }

    /** "You run more or fewer": in at least half the lists and in yours, at another count than most of them. */
    val counts: List<CardRatio> get() = rows.filter { it.share >= PLAYED && it.yours > 0 && it.yours != it.mode }

    /** "Your techs": in yours and in at most a tenth of the lists. */
    val techs: List<CardRatio> get() = rows.filter { it.yours > 0 && it.share <= TECH }

    /** The row for [card] in [section], by card. */
    fun of(card: CardId, section: DeckSection = DeckSection.MAIN): CardRatio? = rows.firstOrNull { it.card == card && it.section == section }

    /**
     * The strategy's consensus list, the web's stand-in rather than one pilot's: each section's cards played by at least half
     * the lists at their most common count; a Main Deck short of forty is filled from the next most played, a whole modal
     * count at a time; three copies of a card at most across the sections.
     */
    fun consensus(): Deck {
        val used = HashMap<CardId, Int>()
        fun take(section: DeckSection, floor: Int, cap: Int): List<CardId> {
            val out = ArrayList<CardId>()
            val ranked = rows.filter { it.section == section }.sortedWith(compareByDescending<CardRatio> { it.share }.thenByDescending { it.mean })
            for (r in ranked) {
                if (r.share < PLAYED && out.size >= floor) break
                val room = (MAX_COPIES - (used[r.card] ?: 0)).coerceAtLeast(0)
                val n = minOf(r.mode, room, cap - out.size)
                if (n <= 0) continue
                repeat(n) { out += r.card }
                used[r.card] = (used[r.card] ?: 0) + n
                if (out.size >= cap) break
            }
            return out
        }
        val main = take(DeckSection.MAIN, MAIN_FLOOR, MAIN_CAP)
        val extra = take(DeckSection.EXTRA, 0, SIDE_CAP)
        val side = take(DeckSection.SIDE, 0, SIDE_CAP)
        return Deck(main, extra, side)
    }

    companion object {
        /** "Most lists": at least half, weighted. */
        const val PLAYED = 0.5

        /** A tech: in at most a tenth of the lists. */
        const val TECH = 0.10

        private const val MAX_COPIES = 3
        private const val MAIN_FLOOR = 40
        private const val MAIN_CAP = 60
        private const val SIDE_CAP = 15

        /**
         * [lists] of one strategy read card by card and section by section, [yours] beside them (null: none). [weigh] is a
         * list's weight; [cards] resolves printings, so an alternate artwork is the same row.
         */
        fun of(
            name: String,
            lists: List<TournamentDeck>,
            yours: Deck?,
            cards: (CardId) -> Card?,
            weigh: (TournamentDeck) -> Double = { it.weight },
        ): StrategyRatios {
            val w = lists.map { weigh(it).coerceAtLeast(0.0) }.let { ws -> if (ws.sum() > 0) ws else List(lists.size) { 1.0 } }
            val total = w.sum()
            fun canonical(id: CardId) = CardIdentity.canonical(id, cards)
            val rows = ArrayList<CardRatio>()
            for (section in listOf(DeckSection.MAIN, DeckSection.EXTRA, DeckSection.SIDE)) {
                val perList = lists.map { d -> d.deck[section].map(::canonical).groupingBy { it }.eachCount() }
                val mine = yours?.get(section)?.map(::canonical)?.groupingBy { it }?.eachCount().orEmpty()
                val seen = (perList.flatMap { it.keys } + mine.keys).distinct()
                for (card in seen) {
                    var share = 0.0
                    var copies = 0.0
                    val byCount = HashMap<Int, Double>()
                    perList.forEachIndexed { i, m ->
                        val n = m[card] ?: return@forEachIndexed
                        share += w[i]
                        copies += w[i] * n
                        byCount[n] = (byCount[n] ?: 0.0) + w[i]
                    }
                    val mode = byCount.maxWithOrNull(compareBy<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })?.key ?: 0
                    rows += CardRatio(card, section, if (total > 0) share / total else 0.0, if (share > 0) copies / share else 0.0, mode, mine[card] ?: 0)
                }
            }
            rows.sortWith(compareBy<CardRatio> { it.section.ordinal }.thenByDescending { it.share }.thenByDescending { it.yours })
            return StrategyRatios(name, lists.size, rows)
        }

        /** The strategy among [clusters] most like [deck] as a whole (mean weighted similarity to its lists), or null. */
        fun closest(clusters: List<FieldCluster>, deck: Deck, cards: (CardId) -> Card?): Pair<FieldCluster, Double>? {
            val all = clusters.flatMap { it.decks }
            if (all.isEmpty()) return null
            val weights = FieldBuilder.weights(all, cards)
            val mine = CardIdentity.distinct(deck.main + deck.extra, cards)
            return clusters.map { c -> c to c.decks.sumOf { FieldBuilder.similarity(mine, FieldBuilder.cardsOf(it, cards), weights) } / c.decks.size }
                .maxByOrNull { it.second }
        }

        /** "3 in 88% of 41 lists": a row in the inspector's words. */
        fun line(r: CardRatio, lists: Int): String =
            if (r.share == 0.0) "in none of $lists lists" else "${r.mode} in ${round(r.share * 100).toInt()}% of $lists lists"
    }
}
