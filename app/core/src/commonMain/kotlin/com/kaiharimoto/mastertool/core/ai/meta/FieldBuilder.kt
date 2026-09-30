package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.remote.TournamentDeck

/** One strategy in the field: its lists, its share of results, its best finishes, and a list to stand for it. */
data class FieldCluster(
    val name: String,
    /** Its share of the weighted results, in percent. */
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
 * Similarity is **weighted by rarity** (an IDF weight per card): a card in nearly every
 * list — a hand trap, a board breaker — says what the format plays, not what a deck
 * is, and counts for little; a card in few lists counts for much. A fixed "staple"
 * cut-off would not do: a strategy that is 40% of the field would lose its own engine
 * as staples.
 */
object FieldBuilder {
    /** A card in at least this share of the lists is a staple: left out of a strategy's core. */
    const val STAPLE_SHARE = 0.6

    /** How alike two lists must be to be one strategy (weighted Jaccard over distinct cards). */
    const val SAME_STRATEGY = 0.4

    fun build(decks: List<TournamentDeck>, top: Int = 12): List<FieldCluster> {
        if (decks.isEmpty()) return emptyList()
        val weights = weights(decks)
        val staples = staples(decks)
        val cards = decks.associateWith { cardsOf(it) }
        fun sim(a: TournamentDeck, b: TournamentDeck) = similarity(cards.getValue(a), cards.getValue(b), weights)
        val clusters = mutableListOf<MutableList<TournamentDeck>>()
        // The strongest results found the strategies; the rest join the one they are most like.
        decks.sortedByDescending { it.weight }.forEach { deck ->
            val home = clusters.maxByOrNull { members -> members.maxOf { sim(deck, it) } }
            val alike = home?.maxOf { sim(deck, it) } ?: 0.0
            if (home != null && alike >= SAME_STRATEGY) home += deck else clusters += mutableListOf(deck)
        }
        val total = decks.sumOf { it.weight }
        return clusters.map { members ->
            val rep = members.maxByOrNull { m -> members.sumOf { o -> sim(m, o) } + m.weight * 0.01 }!!
            FieldCluster(
                name = nameOf(members),
                share = (members.sumOf { it.weight } / total * 100).let { kotlin.math.round(it).toInt() },
                decks = members.sortedByDescending { it.weight },
                representative = rep,
                core = core(members, staples),
            )
        }.sortedByDescending { c -> c.decks.sumOf { it.weight } }.take(top)
    }

    /** Each card's weight: rare cards tell strategies apart, common ones do not. */
    fun weights(decks: List<TournamentDeck>): Map<CardId, Double> {
        val df = HashMap<CardId, Int>()
        decks.forEach { d -> cardsOf(d).forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = decks.size.toDouble()
        return df.mapValues { (_, k) -> kotlin.math.ln((n + 1) / (k + 0.5)).coerceAtLeast(0.01) }
    }

    fun staples(decks: List<TournamentDeck>): Set<CardId> {
        val counts = HashMap<CardId, Int>()
        decks.forEach { d -> d.deck.main.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val floor = (decks.size * STAPLE_SHARE).coerceAtLeast(3.0)
        return counts.filterValues { it >= floor }.keys
    }

    /** A list's distinct main- and extra-deck cards. */
    fun cardsOf(deck: TournamentDeck): Set<CardId> = (deck.deck.main + deck.deck.extra).toSet()

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
    private fun core(members: List<TournamentDeck>, staples: Set<CardId>): List<CardId> {
        val counts = HashMap<CardId, Int>()
        members.forEach { d -> d.deck.main.toSet().forEach { counts[it] = (counts[it] ?: 0) + 1 } }
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
