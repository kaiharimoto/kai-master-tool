package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.remote.TournamentDeck

/** One strategy among the results: its lists, its share of top cuts, its best finishes, and a list to stand for it. */
data class FieldCluster(
    val name: String,
    /**
     * Its share of the weighted top-cut results, in percent — not of the players at the
     * tables ([FieldBuilder.SHARE_CAVEAT]).
     */
    val share: Int,
    val decks: List<TournamentDeck>,
    /** The list most like the others in it — the one to import to stand for the strategy. */
    val representative: TournamentDeck,
    /** The cards every list of it plays, most of them the engine. */
    val core: List<CardId>,
) {
    val best: List<TournamentDeck> get() = decks.sortedByDescending { it.weight }.take(3)
}

/**
 * The field, from results (kai: "know intimately how to create format webs on its own
 * out of the box"). Tournament lists are grouped into strategies by what they play,
 * not what they are called — two "Mitsurugi" lists with different engines are two
 * strategies, and "Azamina Mitsurugi" beside "Mitsurugi" one — then each strategy's
 * share is its weight of results: a win counts more than a top 8, a big event more
 * than a small one (`TournamentDeck.weight`).
 *
 * The lists are **top cuts**, so a share is of the decks that topped, not of the decks
 * played (red team): a strong deck tops more often than it is played and looks bigger
 * here than at the tables. Every answer that shows a share says so ([SHARE_CAVEAT]).
 *
 * Similarity is **weighted by rarity** (an IDF weight per card): a card in nearly every
 * list — a hand trap, a board breaker — says what the format plays, not what a deck
 * is, and counts for little; a card in few lists counts for much. A fixed "staple"
 * cut-off would not do: a strategy that is 40% of the field would lose its own engine
 * as staples.
 *
 * Phase B (`docs/phases/B.md` §4):
 * - **Copies are counted by card**, not printing: every list is read through a lookup that
 *   resolves an alternate artwork to its card ([CardIdentity]), so one strategy's Ash is one
 *   column of the similarity, the staples and the core, whichever artwork each list plays.
 * - **Average linkage**: a list joins a strategy only if it is like the strategy *as a whole*
 *   (its mean similarity to the members clears [SAME_STRATEGY]), and two strategies merge
 *   only on their mean similarity across members. Joining on the most alike member chained
 *   hybrids — engine A, A with a little B, half and half, B with a little A, engine B — into
 *   one strategy, two engines and their hybrids sharing one share.
 */
object FieldBuilder {
    /** What a strategy's share is, and is not, in words for Ai: shown with every field snapshot. */
    const val SHARE_CAVEAT =
        "These shares are of top cuts (results weighted by placement and event size), not of the whole field: " +
            "a strong deck tops more often than it is played, so it looks bigger here than at the tables, and a deck " +
            "many people play but few top looks smaller. Where the field's shares matter (a web's shares, the expected " +
            "win rate, what to practise), ask the person what their event's field looks like, or estimate it separately."

    /** A card in at least this share of the lists is a staple: left out of a strategy's core. */
    const val STAPLE_SHARE = 0.6

    /** How alike two lists must be to be one strategy (weighted Jaccard over distinct cards). */
    const val SAME_STRATEGY = 0.4

    /** A lookup that leaves every passcode as it is: for callers with no pool to resolve printings. */
    val AS_PRINTED: (CardId) -> Card? = { null }

    /**
     * The strategies among [decks], the [top] biggest. [cards] resolves a printing to its card (the pool's
     * `CardIndex.byId`); without one each passcode stands for itself.
     */
    fun build(
        decks: List<TournamentDeck>,
        top: Int = 12,
        cards: (CardId) -> Card? = AS_PRINTED,
        /** Each list's weight in its strategy's share (Phase G: [FieldShares.weigher]); its result's by default. */
        weigh: (TournamentDeck) -> Double = { it.weight },
    ): List<FieldCluster> {
        if (decks.isEmpty()) return emptyList()
        val weights = weights(decks, cards)
        val staples = staples(decks, cards)
        // Lists by their place in [decks]: two lists may be equal as values, and each is still one result.
        val sets = decks.map { cardsOf(it, cards) }
        val memo = HashMap<Long, Double>()
        fun sim(a: Int, b: Int): Double {
            val key = minOf(a, b).toLong() * decks.size + maxOf(a, b)
            return memo.getOrPut(key) { similarity(sets[a], sets[b], weights) }
        }
        fun mean(deck: Int, members: List<Int>) = members.sumOf { sim(deck, it) } / members.size
        val clusters = mutableListOf<MutableList<Int>>()
        // The strongest results found the strategies; the rest join the one they are most like as a whole.
        decks.indices.sortedByDescending { decks[it].weight }.forEach { deck ->
            val home = clusters.maxByOrNull { mean(deck, it) }
            if (home != null && mean(deck, home) >= SAME_STRATEGY) home += deck else clusters += mutableListOf(deck)
        }
        // A strategy founded before its kin arrived merges with them, but only on the two's mean similarity.
        // Summed similarity between every two strategies' members, kept up to date as they merge.
        val sums = MutableList(clusters.size) { x -> MutableList(clusters.size) { y -> if (x == y) 0.0 else clusters[x].sumOf { a -> clusters[y].sumOf { b -> sim(a, b) } } } }
        while (clusters.size > 1) {
            var best = -1.0
            var pair = 0 to 0
            for (x in clusters.indices) for (y in x + 1 until clusters.size) {
                val m = sums[x][y] / (clusters[x].size * clusters[y].size)
                if (m > best) {
                    best = m
                    pair = x to y
                }
            }
            if (best < SAME_STRATEGY) break
            val (x, y) = pair
            for (z in clusters.indices) if (z != x && z != y) {
                sums[x][z] += sums[y][z]
                sums[z][x] = sums[x][z]
            }
            sums.removeAt(y)
            sums.forEach { it.removeAt(y) }
            clusters[x] += clusters.removeAt(y)
        }
        val total = decks.sumOf(weigh).takeIf { it > 0 } ?: 1.0
        return clusters.map { at ->
            val rep = decks[at.maxByOrNull { m -> at.sumOf { o -> sim(m, o) } + decks[m].weight * 0.01 }!!]
            val members = at.map { decks[it] }
            FieldCluster(
                name = nameOf(members),
                share = (members.sumOf(weigh) / total * 100).let { kotlin.math.round(it).toInt() },
                decks = members.sortedByDescending { it.weight },
                representative = rep,
                core = core(members, staples, cards),
            )
        }.sortedByDescending { c -> c.decks.sumOf(weigh) }.take(top)
    }

    /** Each card's weight: rare cards tell strategies apart, common ones do not. */
    fun weights(decks: List<TournamentDeck>, cards: (CardId) -> Card? = AS_PRINTED): Map<CardId, Double> {
        val df = HashMap<CardId, Int>()
        decks.forEach { d -> cardsOf(d, cards).forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = decks.size.toDouble()
        return df.mapValues { (_, k) -> kotlin.math.ln((n + 1) / (k + 0.5)).coerceAtLeast(0.01) }
    }

    /** The cards in at least [STAPLE_SHARE] of the lists' Main Decks, by card. */
    fun staples(decks: List<TournamentDeck>, cards: (CardId) -> Card? = AS_PRINTED): Set<CardId> {
        val counts = HashMap<CardId, Int>()
        decks.forEach { d -> CardIdentity.distinct(d.deck.main, cards).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val floor = (decks.size * STAPLE_SHARE).coerceAtLeast(3.0)
        return counts.filterValues { it >= floor }.keys
    }

    /** A list's distinct main- and extra-deck cards, by card. */
    fun cardsOf(deck: TournamentDeck, cards: (CardId) -> Card? = AS_PRINTED): Set<CardId> =
        CardIdentity.distinct(deck.deck.main + deck.deck.extra, cards)

    /** Weighted Jaccard: the weight the two share over the weight either has. */
    fun similarity(a: Set<CardId>, b: Set<CardId>, weights: Map<CardId, Double>): Double {
        var shared = 0.0
        var either = 0.0
        (a + b).forEach { c ->
            val w = weights[c] ?: 1.0
            either += w
            if (c in a && c in b) shared += w
        }
        return if (either == 0.0) 1.0 else shared / either
    }

    /** The cards at least four in five of a strategy's lists play, most common first. */
    private fun core(members: List<TournamentDeck>, staples: Set<CardId>, cards: (CardId) -> Card?): List<CardId> {
        val counts = HashMap<CardId, Int>()
        members.forEach { d -> CardIdentity.distinct(d.deck.main, cards).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts.filter { (id, n) -> id !in staples && n >= members.size * 0.8 }.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * A strategy's name: the words its lists' names share, else the most common name.
     * "Branded Light and Darkness Ritual" and "Fiendsmith Light and Darkness Ritual"
     * make "Light and Darkness Ritual"; three "Mitsurugi" lists make "Mitsurugi".
     */
    fun nameOf(members: List<TournamentDeck>): String {
        val names = members.map { it.name.trim() }
        val common = names.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Unnamed"
        if (members.size < 2) return common
        val words = names.map { n -> n.split(' ').filter { it.isNotBlank() } }
        val shared = words.first().filter { w -> words.all { list -> list.any { it.equals(w, ignoreCase = true) } } }
        return if (shared.size >= 1 && shared.joinToString(" ").length >= 4) shared.joinToString(" ") else common
    }
}
