package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.Proc
import com.kaiharimoto.mastertool.core.duel.effects.Where
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.Interruptions
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery

/*
 * The starter table (M.md §2.5, kai: "start with starters"): every engine card alone, and every pair of them, mapped beside
 * the deck's own cards that do nothing (its fodder, so a cost that discards can be paid as in a real hand). It is where the
 * board library begins, and it answers the question players ask first — which cards start the deck, and which only work
 * together.
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
        /** The cards dealt beside it, which do nothing; fewer than the hand's room when the deck has fewer. */
        val fodder: List<Int> = emptyList(),
        /** The deck orders it was mapped over: more than one when a script reads the Deck's order. */
        val seeds: Int = 1,
    )

    /** The table and the library it grew. */
    class Result(val rows: List<Row>, val library: BoardLibrary)

    /**
     * The deck's engine cards: those with a trusted script, canonical, sorted. Normal Monsters and inert cards start nothing,
     * and neither does a card that only answers from the hand or a Spell & Trap Zone ([answerOnly]: a hand trap, a Trap, a
     * board breaker) — it waits for the other player.
     */
    fun engine(main: List<Int>, kit: GoldfishKit): List<Int> =
        main.map(kit::canonical).distinct().filter { kit.book.has(it) && !answerOnly(it, kit) }.sorted()

    /**
     * Whether every effect of [code]'s script answers the other player (`Interruptions.answers`) and none is used from the
     * field: it starts nothing. A monster whose answer is used from a Monster Zone is not one — summoned, it is a board.
     */
    fun answerOnly(code: Int, kit: GoldfishKit): Boolean {
        val s = kit.book.script(code) ?: return false
        if (s.summon?.procs.orEmpty().any { it is Proc.Inherent }) return false
        return s.effects.isNotEmpty() && s.effects.all { e ->
            e.kind != Kind.IGNITION && Where.MONSTER_ZONE !in e.from && Where.FIELD_ZONE !in e.from && Interruptions.answers(e)
        }
    }

    /**
     * The fodder dealt beside [starter] from [main]: the deck's cards with no trusted script that are not Normal Monsters
     * (`GoldfishKit.inert`), as many as the opening hand has room for, lowest passcode first. They can be discarded, banished
     * or sent as a cost and do nothing else, so a board never leans on them and a cost that wants a card in hand is payable.
     */
    fun fodder(starter: List<Int>, main: List<Int>, kit: GoldfishKit, first: Boolean): List<Int> {
        val left = main.map(kit::canonical).toMutableList()
        starter.forEach { left.remove(it) }
        val room = (GoldfishHands.size(first) - starter.size).coerceAtLeast(0)
        return left.filter(kit::inert).sorted().take(room)
    }

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
     * same however often and wherever it is run. Each starter is dealt with its [fodder] when [withFodder]; when a script
     * reads the Deck's order it is mapped over [seeds] orders ([seed], [seed] + 1, …) and its row is every order's boards
     * together. [cancelled] stops between engine moves; the rows so far come back, the starter it stopped in marked
     * incomplete.
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
        withFodder: Boolean = true,
        seeds: Int = ORDER_SEEDS,
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
        val alone = HashMap<Int, Set<BoardCards>>()
        val orders = if (ordered) seeds.coerceAtLeast(1) else 1
        if (lib.boards.isEmpty() && lib.first != first) lib = lib.copy(first = first)
        for ((i, cards) in list.withIndex()) {
            if (cancelled()) break
            val fodder = if (withFodder) fodder(cards, canonMain, kit, first) else emptyList()
            val keys = LinkedHashSet<String>()
            val boards = HashMap<String, BoardCards>()
            var complete = true
            var moves = 0
            for (o in 0 until orders) {
                if (cancelled()) { complete = false; break }
                val deal = MapDeal(cards, first, seed + o, fodder)
                val table = deal.table(canonMain, canonExtra, kit)
                val mapped = MapSearch(kit, budget, ordered = ordered, zonesMatter = zones, prior = prior, cancelled = cancelled)
                    .map(table, deal.fodderUids(table))
                lib = lib.add(deal, mapped, run, at)
                mapped.ends.forEach { keys += it.key; boards[it.key] = it.cards }
                complete = complete && mapped.complete
                moves += mapped.moves
            }
            if (cards.size == 1) alone[cards[0]] = boards.values.toSet()
            // A pair's board is the pair's own unless one card alone makes it: as it is, or with the other card left in hand.
            fun madeAlone(a: Int, other: Int, b: BoardCards): Boolean {
                val mine = alone[a].orEmpty()
                if (b in mine) return true
                if (other !in b.hand) return false
                val rest = b.hand.toMutableList().also { it.remove(other) }
                return b.copy(hand = rest) in mine
            }
            val together = if (cards.size != 2) emptyList() else keys.filter { k ->
                val b = boards.getValue(k)
                !madeAlone(cards[0], cards[1], b) && !madeAlone(cards[1], cards[0], b)
            }
            rows += Row(cards, keys.toList(), complete, moves, odds(cards, canonMain, GoldfishHands.size(first), kit), together, fodder, orders)
            progress(i + 1, list.size)
        }
        return Result(rows, lib)
    }

    /** Deck orders a starter is mapped over when a script reads the order: one order's draws are one sample, not the card. */
    const val ORDER_SEEDS = 3
}
