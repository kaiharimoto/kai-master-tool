package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.world.Bound
import com.kaiharimoto.mastertool.core.world.HandCounter

/*
 * Exact starter odds (Phase G, G5): the starter table already says which cards and pairs reach a board that passes the ask,
 * so the chance of opening one of them is counted exactly — every hand, no sampling, no seed — by the hand counter the
 * instruments use. It is a lower bound: a hand of three cards that only open together is not in the table.
 */
object StarterOdds {
    /** The chance of opening a mapped starter going [first] and going [second], and the starters it counts. */
    data class Odds(val first: Double, val second: Double, val starters: List<List<Int>>)

    /** The starters of [rows] whose boards in [library] pass [passes]: each a card or a pair, canonical. */
    fun passing(rows: List<StarterTable.Row>, library: BoardLibrary, passes: (BoardTraits) -> Boolean): List<List<Int>> {
        val byKey = library.byKey
        return rows.filter { r -> r.ends.any { k -> byKey[k]?.let { passes(it.traits) } == true } }.map { it.cards.sorted() }
    }

    /**
     * The exact chance a hand of [hand] cards from [main] (canonical) holds at least one of [starters] — each card as many
     * times as listed. Null when there are too many distinct cards to count exactly.
     */
    fun probability(starters: List<List<Int>>, main: List<Int>, hand: Int): Double? {
        if (starters.isEmpty()) return 0.0
        // A pair is redundant beside one of its cards that opens alone: the union is the same, and the counter is smaller.
        val singles = starters.filter { it.size == 1 }.map { it[0] }.toSet()
        val needed = starters.filter { s -> s.size == 1 || s.none { it in singles } }
        val cards = needed.flatten().distinct().sorted()
        if (cards.size > HandCounter.MAX_SETS) return null
        val index = cards.withIndex().associate { it.value to it.index }
        val deck = main.map { c -> if (c in index) c.toString() else "" }
        val counter = HandCounter.of(deck, cards.map { setOf(it.toString()) })
        val goal = needed.map { s -> s.groupingBy { it }.eachCount().map { (c, n) -> Bound(index.getValue(c), n, Int.MAX_VALUE) } }
        return runCatching { counter.probability(goal, hand) }.getOrNull()
    }

    /** [probability] going first and going second. */
    fun of(rows: List<StarterTable.Row>, library: BoardLibrary, main: List<Int>, passes: (BoardTraits) -> Boolean): Odds? {
        val s = passing(rows, library, passes)
        val first = probability(s, main, GoldfishHands.size(true)) ?: return null
        val second = probability(s, main, GoldfishHands.size(false)) ?: return null
        return Odds(first, second, s)
    }

    /**
     * [card] swept from none to [limit] copies with the deck's size held (each copy taking or giving the place of a card that
     * opens nothing): the chance going first and going second at each count.
     */
    fun sweep(starters: List<List<Int>>, main: List<Int>, card: Int, limit: Int): List<Triple<Int, Double, Double>> {
        val without = main.filter { it != card }
        return (0..limit).mapNotNull { n ->
            val deck = without + List(n) { card }
            val pad = main.size - deck.size
            val held = if (pad >= 0) deck + List(pad) { FILLER } else deck.take(main.size)
            val f = probability(starters, held, GoldfishHands.size(true)) ?: return@mapNotNull null
            val s = probability(starters, held, GoldfishHands.size(false)) ?: return@mapNotNull null
            Triple(n, f, s)
        }
    }

    /** A card that opens nothing, to hold a deck's size in [sweep]. */
    private const val FILLER = -7

    /**
     * What the table says about the person's groups (G5): a card that opens a board alone but is in no group, and a card in a
     * group named for starters that opens nothing alone. [groupOf] names a card's group, or null.
     */
    fun audit(rows: List<StarterTable.Row>, library: BoardLibrary, passes: (BoardTraits) -> Boolean, groupOf: (Int) -> String?, name: (Int) -> String): List<String> {
        val byKey = library.byKey
        val alone = rows.filter { it.cards.size == 1 }
        val opens = alone.filter { r -> r.ends.any { k -> byKey[k]?.let { passes(it.traits) } == true } }.map { it.cards[0] }.toSet()
        val starterGroup = { g: String? -> g != null && g.contains("starter", ignoreCase = true) }
        return buildList {
            opens.filter { groupOf(it) == null }.sorted().forEach { add("${name(it)} reaches a board alone but is in no group.") }
            alone.map { it.cards[0] }.filter { it !in opens && starterGroup(groupOf(it)) }.sorted().forEach {
                add("${name(it)} is in ${groupOf(it)} and reaches nothing alone.")
            }
        }
    }
}
