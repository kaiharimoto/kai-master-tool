package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.CardIdentity
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import com.kaiharimoto.mastertool.core.search.EffectKind
import com.kaiharimoto.mastertool.core.search.EffectKinds

/**
 * What the field interrupts with, and what it sides (Phase G, G.5; the red team's F1). Every list's Side Deck was read
 * and then dropped; this keeps it. Per strategy, weighted as its share is:
 *
 * - the Main Deck's hand traps and negates ([EffectKinds]: the card's own text), each with the share of lists playing it
 *   and its mean copies there;
 * - the chance the strategy opens at least one and at least two of them, in five cards and in six, exact per list
 *   (hypergeometric over that list's own count and size) and averaged by weight;
 * - its Side Deck: the share of lists siding each card, and their mean copies.
 *
 * Counts only: what lists play, never why they placed.
 */
class FieldProfile(val strategies: List<StrategyProfile>, val field: Opens) {
    /** The field's hand traps and negates over every strategy, weighted by share: the cards to expect, most common first. */
    fun interaction(): List<CardCopies> = merge(strategies.map { it.share.toDouble() to (it.handTraps + it.negates).distinctBy { c -> c.card } })

    /** The field's side cards over every strategy, weighted by share. */
    fun sided(): List<CardCopies> = merge(strategies.map { it.share.toDouble() to it.side })

    private fun merge(parts: List<Pair<Double, List<CardCopies>>>): List<CardCopies> {
        val total = parts.sumOf { it.first }.takeIf { it > 0 } ?: return emptyList()
        val share = HashMap<CardId, Double>()
        val copies = HashMap<CardId, Double>()
        for ((w, list) in parts) for (c in list) {
            share[c.card] = (share[c.card] ?: 0.0) + w / total * c.share
            copies[c.card] = (copies[c.card] ?: 0.0) + w / total * c.share * c.mean
        }
        return share.map { (card, s) -> CardCopies(card, s, if (s > 0) copies.getValue(card) / s else 0.0) }.sortedByDescending { it.share }
    }
}

/** One strategy's interaction and side deck ([FieldProfile]). */
class StrategyProfile(
    val name: String,
    /** Its share of the weighted results, in percent. */
    val share: Int,
    val lists: Int,
    val handTraps: List<CardCopies>,
    val negates: List<CardCopies>,
    val opens: Opens,
    val side: List<CardCopies>,
)

/** A card across lists: the weighted [share] of lists playing it (0–1), and its [mean] copies where it is played. */
data class CardCopies(val card: CardId, val share: Double, val mean: Double)

/** The chance of opening at least one, and at least two, interruptions: in five cards (going first) and six (second). */
data class Opens(val one5: Double, val two5: Double, val one6: Double, val two6: Double) {
    companion object {
        val NONE = Opens(0.0, 0.0, 0.0, 0.0)
    }
}

object FieldProfiles {
    /** The interruptions a card is, by its own text: a hand trap, or a card that negates. */
    fun interrupts(card: Card): Boolean = EffectKinds.of(card).let { EffectKind.HAND_TRAP in it || EffectKind.NEGATE in it }

    /**
     * Each of [clusters]' profiles and the field's chance of opening interruptions, the strategies weighted by their
     * shares. [weigh] is each list's weight (its result's, by default); [cards] resolves a passcode to its card — a
     * card it cannot read is no interruption.
     */
    fun of(
        clusters: List<FieldCluster>,
        cards: (CardId) -> Card?,
        weigh: (TournamentDeck) -> Double = { it.weight },
    ): FieldProfile {
        val strategies = clusters.map { profile(it, cards, weigh) }
        val total = strategies.sumOf { it.share }.toDouble()
        val field = if (total == 0.0) Opens.NONE else Opens(
            strategies.sumOf { it.share / total * it.opens.one5 },
            strategies.sumOf { it.share / total * it.opens.two5 },
            strategies.sumOf { it.share / total * it.opens.one6 },
            strategies.sumOf { it.share / total * it.opens.two6 },
        )
        return FieldProfile(strategies, field)
    }

    fun profile(cluster: FieldCluster, cards: (CardId) -> Card?, weigh: (TournamentDeck) -> Double = { it.weight }): StrategyProfile {
        val lists = cluster.decks
        val weights = lists.map { weigh(it).coerceAtLeast(0.0) }
        val total = weights.sum().takeIf { it > 0 } ?: lists.size.toDouble().coerceAtLeast(1.0)
        val w = if (weights.sum() > 0) weights else List(lists.size) { 1.0 }
        fun canonical(id: CardId) = CardIdentity.canonical(id, cards)
        val kinds = HashMap<CardId, Set<EffectKind>>()
        fun kindsOf(id: CardId) = kinds.getOrPut(id) { cards(id)?.let(EffectKinds::of).orEmpty() }
        var one5 = 0.0; var two5 = 0.0; var one6 = 0.0; var two6 = 0.0
        lists.forEachIndexed { i, d ->
            val main = d.deck.main.map(::canonical)
            val k = main.count { id -> kindsOf(id).let { EffectKind.HAND_TRAP in it || EffectKind.NEGATE in it } }
            val n = main.size
            one5 += w[i] * atLeast(n, k, 5, 1); two5 += w[i] * atLeast(n, k, 5, 2)
            one6 += w[i] * atLeast(n, k, 6, 1); two6 += w[i] * atLeast(n, k, 6, 2)
        }
        val mains = lists.map { d -> d.deck.main.map(::canonical) }
        val sides = lists.map { d -> d.deck.side.map(::canonical) }
        val handTraps = copies(mains, w) { EffectKind.HAND_TRAP in kindsOf(it) }
        val negates = copies(mains, w) { EffectKind.NEGATE in kindsOf(it) && EffectKind.HAND_TRAP !in kindsOf(it) }
        return StrategyProfile(
            cluster.name, cluster.share, lists.size, handTraps, negates,
            Opens(one5 / total, two5 / total, one6 / total, two6 / total),
            copies(sides, w) { true },
        )
    }

    /** Each card [keep] passes across [lists] (canonical ids): the weighted share playing it and its mean copies there. */
    fun copies(lists: List<List<CardId>>, weights: List<Double>, keep: (CardId) -> Boolean): List<CardCopies> {
        val total = weights.sum().takeIf { it > 0 } ?: return emptyList()
        val share = HashMap<CardId, Double>()
        val copies = HashMap<CardId, Double>()
        lists.forEachIndexed { i, list ->
            list.filter(keep).groupingBy { it }.eachCount().forEach { (card, n) ->
                share[card] = (share[card] ?: 0.0) + weights[i]
                copies[card] = (copies[card] ?: 0.0) + weights[i] * n
            }
        }
        return share.map { (card, s) -> CardCopies(card, s / total, copies.getValue(card) / s) }.sortedWith(compareByDescending<CardCopies> { it.share }.thenByDescending { it.mean })
    }

    /** The chance of at least [k] hits drawing [draw] cards from [size] holding [hits], exactly. */
    fun atLeast(size: Int, hits: Int, draw: Int, k: Int): Double {
        if (size <= 0 || draw > size) return 0.0
        var below = 0.0
        for (x in 0 until k) below += exactly(size, hits.coerceIn(0, size), draw, x)
        return (1 - below).coerceIn(0.0, 1.0)
    }

    private fun exactly(n: Int, k: Int, draw: Int, x: Int): Double {
        if (x > k || draw - x > n - k || x < 0) return 0.0
        return choose(k, x) * choose(n - k, draw - x) / choose(n, draw)
    }

    private fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        for (i in 0 until minOf(k, n - k)) r = r * (n - i) / (i + 1)
        return r
    }
}
