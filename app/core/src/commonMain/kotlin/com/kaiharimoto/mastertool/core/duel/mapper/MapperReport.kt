package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords

/*
 * What Ai is told of a deck's mapper files (`mapper_library`, `mapper_starters`, `mapper_map`): the library as a query ranks
 * it, one board in full, the starter table and a run's counts — in [MapperWords]' sentences, so Ai reads what the page shows.
 */
object MapperReport {
    /**
     * [lib] ranked by [preset], the first [limit] boards with their keys, traits, front marks and shares of [run]'s hands
     * (when the run counted this deck as it is).
     */
    fun library(lib: BoardLibrary, run: MapperRun?, preset: BoardPreset, limit: Int, name: (Int) -> String): String = buildString {
        val side = if (lib.first) "going first" else "going second"
        if (lib.boards.isEmpty()) {
            append("The library $side is empty: map the starter table (mapper_starters run) or deal hands (mapper_map).")
            return@buildString
        }
        val ranked = BoardQuery.rank(lib.boards, preset)
        val stale = lib.boards.count { it.stale }
        append("The library $side: ${GoldfishWords.count(lib.boards.size)} boards from ${lib.runs} runs")
        if (stale > 0) append(", ${GoldfishWords.count(stale)} stale since the deck or its effects changed (left out unless asked)")
        append(". ")
        append(query(preset, name)).append(": ")
        append("${GoldfishWords.count(ranked.size)} pass, ${ranked.count { it.front }} on the Pareto front.")
        val counted = run?.takeIf { it.hands > 0 && it.deck == lib.deck && it.library == lib.library }
        if (counted == null) append(" No run has counted this deck's hands as it is: deal some with mapper_map for the shares.")
        else append(" Shares are of ${GoldfishWords.count(counted.hands)} hands dealt from seed ${counted.seed}" +
            (if (counted.incomplete > 0) ", ${GoldfishWords.count(counted.incomplete)} of them not searched to the end (shares are at least)" else "") +
            // The scripts' print, so a number written from these shares goes stale when a script changes (Evidence.libraryOf).
            "; scripts library ${lib.library}.")
        ranked.take(limit).forEachIndexed { i, r ->
            append("\n").append(i + 1).append(". [").append(r.entry.key).append("]")
            if (r.front) append(" front")
            append(" score ").append(oneDecimal(r.score)).append(" — ").append(MapperWords.traits(r.entry.traits))
            append("\n   ").append(MapperWords.cards(r.entry.cards, name))
            if (counted != null) append("\n   At least this much: ").append(MapperWords.share(counted.atLeast(r.entry.traits)))
            r.entry.lines.firstOrNull()?.let { append("\n   Cheapest line: ").append(MapperWords.line(it, name)) }
            if (r.unmeasured.isNotEmpty()) append("\n   Not measured: ").append(r.unmeasured.joinToString { MapperWords.head(it) })
        }
        if (ranked.size > limit) append("\n… ${ranked.size - limit} more pass.")
    }

    /** The query in words: "Weights: negates ×2, hand ×0.5; at least 2 interruptions; uses A". */
    fun query(preset: BoardPreset, name: (Int) -> String): String {
        val parts = ArrayList<String>()
        val w = preset.weights.filterValues { it != 0.0 && it.isFinite() }
        parts += if (w.isEmpty()) "no weights (the front reads interruptions, negates, removal and hand traps kept)"
        else "weights " + w.entries.joinToString { (h, x) -> "${MapperWords.head(h).lowercase()} ×${oneDecimal(x)}" }
        preset.filters.forEach { f ->
            val h = MapperWords.head(f.head).lowercase()
            parts += when {
                f.min != null && f.max != null -> "$h ${oneDecimal(f.min)}–${oneDecimal(f.max)}"
                f.min != null -> "at least ${oneDecimal(f.min)} $h"
                f.max != null -> "at most ${oneDecimal(f.max)} $h"
                else -> h
            }
        }
        if (preset.uses.isNotEmpty()) parts += "uses " + preset.uses.joinToString { name(it) }
        if (preset.avoids.isNotEmpty()) parts += "avoids " + preset.avoids.joinToString { name(it) }
        if (preset.stale) parts += "stale boards included"
        return (if (preset.name.isNotBlank()) "Preset “${preset.name}”, " else "") + parts.joinToString("; ")
    }

    /** One board in full: its cards, every trait, its lines and its starters. */
    fun board(e: BoardEntry, run: MapperRun?, name: (Int) -> String): String = buildString {
        append("Board [").append(e.key).append("]")
        if (e.stale) append(" (stale: the deck or its effects changed and no run has reached it since)")
        append("\n").append(MapperWords.cards(e.cards, name))
        append("\n").append(MapperWords.traits(e.traits)).append("; ").append(e.traits.gy).append(" in the GY, ").append(e.traits.banished).append(" banished")
        if (run != null && run.hands > 0) append("\nAt least this much: ").append(MapperWords.share(run.atLeast(e.traits)))
        append("\nLines (cheapest first):")
        e.lines.forEachIndexed { i, l -> append("\n").append(i + 1).append(". ").append(MapperWords.line(l, name)) }
        if (e.lines.isEmpty()) append(" none play on the deck as it is.")
        append("\nStarters that reach it: ").append(e.starters.take(STARTERS_SAID).joinToString("; ") { s -> s.joinToString(" + ") { name(it) } })
        if (e.starters.size > STARTERS_SAID) append("; … ${e.starters.size - STARTERS_SAID} more")
    }

    /** The starter table: one-card starters, then the pairs with boards of their own, best first by interruptions. */
    fun starters(t: StarterRun, lib: BoardLibrary, card: Int?, name: (Int) -> String): String = buildString {
        val side = if (t.first) "going first" else "going second"
        val rows = t.rows.filter { card == null || card in it.cards }
        append("The starter table $side: ${t.rows.count { it.cards.size == 1 }} cards alone and ${t.rows.count { it.cards.size == 2 }} pairs")
        if (t.stopped) append(" (stopped before the end)")
        append(".")
        fun best(keys: List<String>): BoardEntry? = keys.mapNotNull { lib.byKey[it] }.maxWithOrNull(compareBy({ it.traits.interruptions }, { it.traits.negates }, { it.traits.hand }))
        val shown = rows.sortedWith(compareBy({ it.cards.size }, { -(best(it.ends)?.traits?.interruptions ?: -1) }, { it.cards.joinToString(",") }))
            .filter { it.cards.size == 1 || it.together.isNotEmpty() || card != null }
        shown.take(ROWS_SAID).forEach { r ->
            append("\n- ").append(r.cards.joinToString(" + ") { name(it) }).append(": ")
            append(GoldfishWords.count(r.ends.size)).append(if (r.ends.size == 1) " board" else " boards")
            if (r.cards.size == 2) append(", ${r.together.size} neither card makes alone")
            append(", opened in ").append(GoldfishWords.pct(r.odds)).append(" of hands")
            if (!r.complete) append(", not searched to the end")
            best(if (r.cards.size == 2 && r.together.isNotEmpty()) r.together else r.ends)?.let { append("; best: ").append(MapperWords.traits(it.traits)) }
        }
        if (shown.size > ROWS_SAID) append("\n… ${shown.size - ROWS_SAID} more rows.")
        val idle = t.rows.count { it.cards.size == 2 && it.together.isEmpty() }
        if (card == null && idle > 0) append("\n${GoldfishWords.count(idle)} pairs make nothing either card does not make alone.")
    }

    /** A run in a few lines: hands, maps, kinds of board, what joined the library and how long it took. */
    fun run(r: MapperRun): String = buildString {
        append("Dealt ${GoldfishWords.count(r.hands)} hands ${if (r.first) "going first" else "going second"} from seed ${r.seed}")
        append(": ${GoldfishWords.count(r.parts.size)} maps, ${GoldfishWords.count(r.traits.size)} kinds of board, ")
        append("${GoldfishWords.count(r.added.size)} boards new to the library")
        append(", ${GoldfishWords.count(r.moves.toInt())} engine moves in ${oneDecimal(r.ms / 1000.0)} s.")
        if (r.stopped) append(" Stopped before the end: the hands not mapped are left out.")
        if (r.incomplete > 0) append(" ${GoldfishWords.count(r.incomplete)} hands were not searched to the end: their shares are at least what they say.")
    }

    private fun oneDecimal(x: Double): String {
        val t = kotlin.math.round(x * 10).toLong()
        return if (t % 10 == 0L) (t / 10).toString() else "${t / 10}.${kotlin.math.abs(t % 10)}".let { if (t < 0 && t / 10 == 0L) "-$it" else it }
    }

    private const val STARTERS_SAID = 6
    private const val ROWS_SAID = 30
}
