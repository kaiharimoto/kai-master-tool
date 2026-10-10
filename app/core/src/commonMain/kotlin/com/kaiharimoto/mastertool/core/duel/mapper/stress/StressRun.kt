package com.kaiharimoto.mastertool.core.duel.mapper.stress

import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.LineStep
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapStep
import com.kaiharimoto.mastertool.core.duel.mapper.MapperWords
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable

/**
 * A stress run (E.md §4.1, M.md §3): one set of the suite against a deck's library and its starters, in a fixed order, so the
 * same run gives the same numbers on every device.
 *
 * - **Boards**: each board's kept lines (at most three, `BoardLibrary.LINES`) answered at their worst point
 *   ([StressSearch.through]); the board keeps the best of them, written into `BoardTraits.through` under the set's key.
 *   Only a complete answer is written: one cut short is counted as unsettled, never stored as a number.
 * - **Starters**: each starter row's hand tested whole ([StressSearch.test]): the most it can guarantee, its line and what
 *   playing around costs, its ceiling the best board the row reaches unopposed.
 */
object StressRun {
    /** A starter's stress test: its [starter] and [fodder], and what the test found. */
    class Hand(val starter: List<Int>, val fodder: List<Int>, val result: StressResult)

    /** A run's report: the [set], the starters' [hands], and how many boards were [tested], [settled] and [stopped] short. */
    class Report(
        val set: String,
        val hands: List<Hand>,
        val boards: Int,
        val tested: Int,
        val settled: Int,
        val moves: Long,
        val stopped: Boolean,
        /** The set's cards the engine cannot play: no trusted script, so nothing was tested ("Write these"). */
        val missing: List<Int>,
    )

    /** [set]'s cards with no trusted script ([GoldfishKit.known]): never tested until they are written, on the person's go. */
    fun missing(set: StressSet, kit: GoldfishKit): List<Int> = set.cards.filterNot { kit.book.has(it) }.distinct()

    /**
     * [set] against [library]'s boards [boards] (keys, in the order to test them; others are left as they are) and the
     * [starters]. [budget]: engine moves for each test. [stop] is asked between tests and inside them.
     */
    fun run(
        library: BoardLibrary,
        starters: List<StarterTable.Row>,
        deck: GoldfishDeck,
        set: StressSet,
        kit: GoldfishKit,
        boards: List<String>,
        budget: Int = BOARD_BUDGET,
        seed: Long = 1L,
        stop: () -> Boolean = { false },
        progress: (done: Int, of: Int) -> Unit = { _, _ -> },
    ): Pair<BoardLibrary, Report> {
        val missing = missing(set, kit)
        if (missing.isNotEmpty()) return library to Report(set.key, emptyList(), boards.size, 0, 0, 0, false, missing)
        val total = boards.size + starters.size
        var done = 0
        var lib = library
        var tested = 0
        var settled = 0
        var moves = 0L
        var stopped = false
        // One search for each deal: boards found from the same hand share the positions their lines pass through.
        val searches = HashMap<MapDeal, StressSearch>()
        fun searchFor(deal: MapDeal) = searches.getOrPut(deal) { StressSearch(kit, budget = budget, cancelled = stop) }
        for (key in boards) {
            if (stop()) { stopped = true; break }
            val entry = library.byKey[key]
            done++
            if (entry == null || entry.stale || entry.lines.isEmpty()) { progress(done, total); continue }
            val outcomes = entry.lines.map { line ->
                val search = searchFor(line.deal)
                val before = search.spentMoves
                val t = StressDeal(line.deal, set.cards).table(deck.main, deck.extra, kit)
                val fodder = line.deal.fodderUids(line.deal.table(deck.main, deck.extra, kit))
                search.through(t, line, fodder).also { moves += search.spentMoves - before }
            }.filter { it.replayed }
            if (outcomes.isNotEmpty()) {
                tested++
                // The board keeps the best of its kept lines, once every one of them is settled.
                if (outcomes.all { it.complete }) {
                    settled++
                    lib = lib.through(key, set.key, outcomes.maxOf { it.keeps })
                }
            }
            progress(done, total)
        }
        val hands = ArrayList<Hand>()
        for (row in starters) {
            if (stopped || stop()) { stopped = true; break }
            val deal = MapDeal(row.cards, library.first, seed, row.fodder)
            val ceiling = row.ends.mapNotNull { library.byKey[it]?.traits?.interruptions }.maxOrNull()
            val search = StressSearch(kit, budget = budget, cancelled = stop)
            val r = search.test(StressDeal(deal, set.cards).table(deck.main, deck.extra, kit), deal.fodderUids(deal.table(deck.main, deck.extra, kit)), ceiling)
            moves += r.moves
            hands += Hand(row.cards, row.fodder, r)
            done++
            progress(done, total)
        }
        return lib to Report(set.key, hands, boards.size, tested, settled, moves, stopped, emptyList())
    }

    /** Engine moves for one board's lines or one starter's test: about two seconds on the desk. */
    const val BOARD_BUDGET = 50_000

    /** The boards a run tests by default: the first of the library in the order on screen. */
    const val MOST_BOARDS = 60
}

/** A stress test in words: what the page writes beside a starter, and what Ai is told — the same sentences. */
object StressWords {
    /** "Through Ward: 2 of 2 guaranteed" (with "at least" when the test was cut short). */
    fun guarantee(r: StressResult, set: String): String =
        "Through $set: " + (if (r.complete) "" else "at least ") + "${r.guarantee}" +
            (r.unopposed?.let { " of $it" } ?: "") + " ${if (r.guarantee == 1) MapperWords.head(r.trait).lowercase().removeSuffix("s") else MapperWords.head(r.trait).lowercase()} guaranteed"

    /** The line, its moves in order: "Activate Pond Caller · Activate Pond Net · They answer: Example Ward · To the End Phase". */
    fun line(r: StressResult, name: (Int) -> String): List<String> = r.line.mapNotNull { s ->
        val card = s.card?.let(name) ?: "a card"
        when {
            s.choke -> "They answer with $card"
            s.seat == StressSearch.THEM -> null
            s.move == FxMove.Pass || s.move == FxMove.Resolve -> null
            else -> MapperWords.step(MapStep.of(LineStep(s.seat, s.move, s.answers, s.card)), name)
        }
    }

    /** Where they answer: "Example Ward on Activate Pond Net"; empty when they never need to. */
    fun chokes(r: StressResult, name: (Int) -> String): List<String> = r.chokes.map { i ->
        val before = r.line.subList(0, i).lastOrNull { it.seat == StressSearch.YOU && it.move != FxMove.Pass && it.move != FxMove.Resolve }
        val what = r.line[i].card?.let(name) ?: "an answer"
        if (before == null) what else "$what on ${before.card?.let(name) ?: "your move"}"
    }

    /** What playing around costs, in a sentence; null when it is not known. */
    fun cost(r: StressResult): String? = r.cost?.let { c ->
        if (c == 0) "Playing around it costs nothing when they do not have it."
        else "Playing around it costs $c when they do not have it (${r.ifTheyPass} against ${r.unopposed})."
    }
}
