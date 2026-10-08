package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery

/*
 * The starter table (M.md §2.5, kai: "start with starters"): every engine card alone, and every pair of them, mapped with
 * nothing else in hand. It is where the board library begins, and it answers the question players ask first — which cards
 * start the deck, and which only work together.
 */

object StarterTable {
    /**
     * One starter mapped: its [cards] (sorted), the boards it reaches ([ends], library keys), whether its map was complete,
     * the engine moves it cost, the chance of opening it ([odds], the cards at least, in a hand of the run's size), and for a
     * pair the boards neither card reaches alone ([together]: what makes it an extender pair).
     */
    data class Row(
        val cards: List<Int>,
        val ends: List<String>,
        val complete: Boolean,
        val moves: Int,
        val odds: Double,
        val together: List<String> = emptyList(),
    )

    /** The table and the library it grew. */
    class Result(val rows: List<Row>, val library: BoardLibrary)

    /** The deck's engine cards: those with a trusted script, canonical, sorted. Normal Monsters and inert cards start nothing. */
    fun engine(main: List<Int>, kit: GoldfishKit): List<Int> = main.map(kit::canonical).distinct().filter { kit.book.has(it) }.sorted()

    /** Every one-card starter, then every pair (a card with itself only when the deck holds two copies), in a fixed order. */
    fun starters(main: List<Int>, kit: GoldfishKit, pairs: Boolean = true): List<List<Int>> {
        val e = engine(main, kit)
        val copies = main.map(kit::canonical).groupingBy { it }.eachCount()
        val out = e.map { listOf(it) }.toMutableList()
        if (pairs) {
            for (i in e.indices) for (j in i until e.size) {
                if (i == j && (copies[e[i]] ?: 0) < 2) continue
                out += listOf(e[i], e[j])
            }
        }
        return out
    }

    /** The chance an opening hand of [handSize] holds [cards] (each as many times as listed) from [main]. */
    fun odds(cards: List<Int>, main: List<Int>, handSize: Int, kit: GoldfishKit): Double {
        val canon = main.map(kit::canonical)
        val need = cards.groupingBy { it }.eachCount()
        val sizes = need.keys.associate { it.toString() to canon.count { c -> c == it } }
        val query = HandQuery(need.map { (c, n) -> HandConstraint(c.toString(), n, handSize) })
        return HandOdds.probability(sizes, canon.size, handSize, query)
    }

    /**
     * Maps every starter of [main] into [library] (run [run]), one after another in [starters]' order, so the library is the
     * same however often and wherever it is run. [cancelled] stops between engine moves; the rows so far come back, the
     * starter it stopped in marked incomplete.
     */
    fun run(
        main: List<Int>,
        extra: List<Int>,
        kit: GoldfishKit,
        library: BoardLibrary,
        first: Boolean = true,
        seed: Long = 1L,
        budget: Int = MapSearch.DEFAULT_BUDGET,
        pairs: Boolean = true,
        run: Int = library.runs + 1,
        prior: MovePrior = MovePrior.NONE,
        at: Long = 0L,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val canonMain = main.map(kit::canonical)
        val canonExtra = extra.map(kit::canonical)
        val ordered = !MapperDeck.orderFree(canonMain, canonExtra, kit)
        val zones = MapperDeck.zonesMatter(canonMain, canonExtra, kit)
        val list = starters(canonMain, kit, pairs)
        var lib = library
        val rows = ArrayList<Row>()
        val alone = HashMap<Int, Set<String>>()
        for ((i, cards) in list.withIndex()) {
            if (cancelled()) break
            val deal = MapDeal(cards, first, seed)
            val mapped = MapSearch(kit, budget, ordered = ordered, zonesMatter = zones, prior = prior, cancelled = cancelled)
                .map(deal.table(canonMain, canonExtra, kit))
            lib = lib.add(deal, mapped, run, at)
            val keys = mapped.ends.map { it.key }
            if (cards.size == 1) alone[cards[0]] = keys.toSet()
            val together = if (cards.size == 2) keys.filter { k -> cards.none { c -> k in alone[c].orEmpty() } } else emptyList()
            rows += Row(cards, keys, mapped.complete, mapped.moves, odds(cards, canonMain, GoldfishHands.size(first), kit), together)
            progress(i + 1, list.size)
        }
        return Result(rows, lib)
    }
}
