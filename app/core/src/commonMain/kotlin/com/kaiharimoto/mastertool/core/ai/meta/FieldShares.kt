package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import com.kaiharimoto.mastertool.core.remote.TournamentDecks
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Honest shares (Phase G, G.5; the red team's F3). A list's weight was its placement × its event's size, summed per list,
 * with no age: an event that publishes a deep cut outweighed a regional that published only its Top 8, and a list from
 * two months ago counted as much as last weekend's. [Weighting.BUDGET] gives each event one budget — its size's weight —
 * shared among the lists it published by placement, and halves a list's weight every [HALF_LIFE_DAYS]. The old weighting
 * stays selectable ([Weighting.RESULTS]).
 *
 * A share is still of top cuts, not of the room ([FieldBuilder.SHARE_CAVEAT]); [presence] says how often a strategy tops
 * apart from how well (its conversion).
 */
object FieldShares {
    enum class Weighting(val words: String) {
        RESULTS("each list by its placement and its event's size, as before"),
        BUDGET("each event one budget by its size, shared among its lists by placement; newer results count more"),
    }

    /** A list's weight halves every this many days. */
    const val HALF_LIFE_DAYS = 30

    /** Each list's weight under [weighting]; [age] is a list's age in days (its own `daysAgo` by default). */
    fun weigher(
        decks: List<TournamentDeck>,
        weighting: Weighting,
        halfLife: Int = HALF_LIFE_DAYS,
        age: (TournamentDeck) -> Int = { it.daysAgo },
    ): (TournamentDeck) -> Double = when (weighting) {
        Weighting.RESULTS -> { d -> d.weight }
        Weighting.BUDGET -> {
            val byEvent = decks.groupBy { eventOf(it) }
            val placements = byEvent.mapValues { (_, lists) -> lists.sumOf { TournamentDecks.placementWeight(it.placement) } }
            val weights = HashMap<Int, Double>()
            for ((event, lists) in byEvent) {
                val budget = TournamentDecks.sizeWeight(lists.firstNotNullOfOrNull { it.players })
                val sum = placements.getValue(event).takeIf { it > 0 } ?: 1.0
                for (d in lists) {
                    val decay = 0.5.pow(age(d).coerceAtLeast(0).toDouble() / halfLife.coerceAtLeast(1))
                    weights[d.number] = budget * TournamentDecks.placementWeight(d.placement) / sum * decay
                }
            }
            byNumber(weights)
        }
    }

    /** A weight looked up by list number; a list not weighed keeps its result's weight. */
    private fun byNumber(weights: Map<Int, Double>): (TournamentDeck) -> Double = { d -> weights[d.number] ?: d.weight }

    /** One event: its name and day (or age when the day is unknown). */
    fun eventOf(d: TournamentDeck): String = d.event.trim().lowercase() + "|" + (d.day ?: "~${d.daysAgo}")

    /** A strategy's [share] of the weighted results and its [presence], the share of lists (each list one), both 0–1. */
    data class Presence(val name: String, val share: Double, val presence: Double, val lists: Int) {
        /** How much more it tops than it shows up: above 1 it converts, below it is played more than it tops. */
        val conversion: Double get() = if (presence > 0) share / presence else 0.0
    }

    /** Each of [clusters]' weighted share and presence among all their lists, under [weigh]. */
    fun presence(clusters: List<FieldCluster>, weigh: (TournamentDeck) -> Double): List<Presence> {
        val all = clusters.flatMap { it.decks }
        val total = all.sumOf(weigh).takeIf { it > 0 } ?: return emptyList()
        return clusters.map { c -> Presence(c.name, c.decks.sumOf(weigh) / total, c.decks.size.toDouble() / all.size, c.decks.size) }
    }

    /** Each cluster's share in whole percent under [weigh]: what a web's shares are set from. */
    fun shares(clusters: List<FieldCluster>, weigh: (TournamentDeck) -> Double): Map<String, Int> =
        presence(clusters, weigh).associate { it.name to round(it.share * 100).toInt() }

    // ---- the trend ---------------------------------------------------------------------------------------------------

    /**
     * One strategy's presence in the older window and the newer ([before] of [beforeOf] lists, [after] of [afterOf]), and
     * the change in points with its 95 % range (Newcombe's hybrid score interval for two independent proportions).
     */
    class TrendRow(val name: String, val before: Int, val beforeOf: Int, val after: Int, val afterOf: Int) {
        val was: Double get() = if (beforeOf == 0) 0.0 else before.toDouble() / beforeOf
        val now: Double get() = if (afterOf == 0) 0.0 else after.toDouble() / afterOf
        val change: Double get() = 100 * (now - was)
        val range: Pair<Double, Double> get() = newcombe(before, beforeOf, after, afterOf).let { (lo, hi) -> 100 * lo to 100 * hi }

        /** Whether the change's range leaves zero: rising or falling, not noise. */
        val moved: Boolean get() = range.let { it.first > 0 || it.second < 0 }
    }

    /** The trend between two windows, and the banlist days that fall between them (marked, never explained). */
    class Trend(val rows: List<TrendRow>, val banlists: List<String>)

    /**
     * [older] and [newer] clustered once, together, so a strategy is the same strategy in both; each one's presence in each
     * window. [banlistDays] (yyyy-MM-dd) are the lists' effective days; those after the older window's last list and up to
     * the newer's last are marked.
     */
    fun trend(
        older: List<TournamentDeck>,
        newer: List<TournamentDeck>,
        cards: (CardId) -> Card?,
        top: Int = 12,
        banlistDays: List<String> = emptyList(),
    ): Trend {
        val clusters = FieldBuilder.build(older + newer, top, cards)
        val olderIds = older.map { it.number }.toSet()
        val newerIds = newer.map { it.number }.toSet()
        val rows = clusters.map { c ->
            TrendRow(c.name, c.decks.count { it.number in olderIds }, older.size, c.decks.count { it.number in newerIds }, newer.size)
        }.sortedByDescending { it.now }
        val from = older.mapNotNull { it.day }.maxOrNull()
        val to = newer.mapNotNull { it.day }.maxOrNull()
        val marked = if (from == null || to == null) emptyList() else banlistDays.filter { it > from && it <= to }.sorted()
        return Trend(rows, marked)
    }

    /** Newcombe's method 10: the difference p2 − p1 of two independent proportions, each by its Wilson limits. */
    fun newcombe(x1: Int, n1: Int, x2: Int, n2: Int, z: Double = 1.96): Pair<Double, Double> {
        if (n1 == 0 || n2 == 0) return -1.0 to 1.0
        val p1 = x1.toDouble() / n1
        val p2 = x2.toDouble() / n2
        val (l1, u1) = TestStats.Rate(x1, n1).wilson(z)
        val (l2, u2) = TestStats.Rate(x2, n2).wilson(z)
        val d = p2 - p1
        val low = d - sqrt((p2 - l2) * (p2 - l2) + (u1 - p1) * (u1 - p1))
        val high = d + sqrt((u2 - p2) * (u2 - p2) + (p1 - l1) * (p1 - l1))
        return low.coerceIn(-1.0, 1.0) to high.coerceIn(-1.0, 1.0)
    }
}
