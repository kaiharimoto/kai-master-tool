package com.kaiharimoto.mastertool.core.ai.report.book

import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.hand.HandOdds
import kotlin.random.Random

/**
 * The numbers a reader's guide draws, worked out rather than written (1.0.67): Ai writes the words,
 * the arithmetic is the app's. The deck is the guide's own roles — every card with its copies — so a
 * guide carries what it needs; the first role is the starters, and a role named "going second" is
 * the cards for that turn.
 */
data class GuideFacts(
    val deckSize: Int,
    val roles: List<Role>,
    /** The deck as role indexes, one a copy, in role order: the unit chart's cells. */
    val cells: List<Int>,
    /** The chance to open at least one starter: five cards going first, six going second. */
    val startFirst: Double,
    val startSecond: Double,
    /** The chance of a five-card hand with no starter at all. */
    val brick: Double,
    /** Sample opening hands, one of each kind there is, dealt from a fixed seed. */
    val hands: List<Hand>,
    val sides: List<Side>,
) {
    /** A role's count and how its cells are drawn. */
    data class Role(val name: String, val count: Int, val fill: Fill)

    enum class Fill { SOLID, HATCH, OUTLINE, CROSS, DOT }

    enum class Verdict(val label: String) { STARTS("Starts"), SECOND("Going second only"), BRICK("Brick") }

    data class Hand(val cards: List<String>, val verdict: Verdict, val starter: String?)

    /** A side plan as signed counts: each card once, with how many. */
    data class Side(val matchup: String, val ins: List<Pair<String, Int>>, val outs: List<Pair<String, Int>>) {
        val moved: Int get() = ins.sumOf { it.second }
    }

    companion object {
        fun of(guide: ReaderGuide, seed: Long = 711878): GuideFacts = of(guide.roles, null, guide.siding, seed)

        /**
         * The facts of a deck known by its [roles] — every card with its copies — or, when [deck] is
         * given (the main deck as it is, a name a copy), of that deck read through the roles: a card
         * in no role is counted under "Other", so the cells always add up to the deck.
         */
        fun of(roles: List<ReaderGuide.Role>, deck: List<String>?, sides: List<ReaderGuide.Side> = emptyList(), seed: Long = 711878): GuideFacts {
            val roleOf = HashMap<String, Int>()
            roles.forEachIndexed { i, r -> r.cards.forEach { c -> c.card.lowercase().let { k -> if (k !in roleOf) roleOf[k] = i } } }
            val cards: List<Pair<Int, String>> = if (deck != null) {
                deck.map { (roleOf[it.lowercase()] ?: roles.size) to it }.sortedBy { it.first }
            } else {
                roles.flatMapIndexed { i, r -> r.cards.flatMap { c -> List(c.copies.coerceAtLeast(1)) { i to c.card } } }
            }
            val other = cards.count { it.first == roles.size }
            val roleFacts = roles.mapIndexed { i, r -> Role(r.name, cards.count { it.first == i }, Fill.entries[minOf(i, Fill.entries.lastIndex)]) } +
                if (other > 0) listOf(Role("Other", other, Fill.DOT)) else emptyList()
            val size = cards.size
            val starters = roleFacts.firstOrNull()?.count ?: 0
            val second = roles.indexOfFirst { it.name.contains("second", ignoreCase = true) }
            fun hands(): List<Hand> {
                val random = Random(seed)
                val found = LinkedHashMap<Verdict, Hand>()
                repeat(400) {
                    if (found.size == Verdict.entries.size || size < 5) return@repeat
                    val hand = cards.shuffled(random).take(5)
                    val starter = hand.firstOrNull { it.first == 0 }?.second
                    val verdict = when {
                        starter != null -> Verdict.STARTS
                        second >= 0 && hand.any { it.first == second } -> Verdict.SECOND
                        else -> Verdict.BRICK
                    }
                    found.getOrPut(verdict) { Hand(hand.sortedBy { it.first }.map { it.second }, verdict, starter) }
                }
                return Verdict.entries.mapNotNull { found[it] }
            }
            return GuideFacts(
                deckSize = size,
                roles = roleFacts,
                cells = cards.map { it.first },
                startFirst = atLeastOne(starters, size, 5),
                startSecond = atLeastOne(starters, size, 6),
                brick = 1 - atLeastOne(starters, size, 5),
                hands = hands(),
                sides = sides.map { s -> Side(s.matchup, counted(s.sideIn), counted(s.sideOut)) },
            )
        }

        /** The chance to open at least one of [cards] (each named as often as it is run) in [hand] cards of [deck]. */
        fun odds(cards: Int, deck: Int, hand: Int): Double = atLeastOne(cards, deck, hand)

        /** The chance that [hand] cards from [deck] hold at least one of [copies]. */
        fun atLeastOne(copies: Int, deck: Int, hand: Int): Double {
            if (deck <= 0 || copies <= 0) return 0.0
            if (hand >= deck) return 1.0
            return 1 - HandOdds.binomial(deck - copies, hand) / HandOdds.binomial(deck, hand)
        }

        /** "74%": a share as a whole percentage. */
        fun percent(p: Double): String = "${kotlin.math.round(p * 100).toInt()}%"

        /** Each name once, with how many times it was listed, first appearance first. */
        fun counted(names: List<String>): List<Pair<String, Int>> = names.groupingBy { it }.eachCount().toList()

        /** How many of a line's steps happen on the opponent's turn, by its phases. */
        fun theirTurn(line: ReaderGuide.Line): Int = line.steps.count { it.phase.startsWith("Their", ignoreCase = true) }
    }
}
