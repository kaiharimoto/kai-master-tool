package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum

/**
 * The results in words for Ai (`shootout_results`, Phase G, D1): every number the page shows, with its 95 % range and the
 * hands behind it, and nothing the page does not — a card no hand has shown is "unrated", never a number.
 */
object ShootoutResultsWords {

    fun describe(r: ShootoutResults, deckName: String, opponent: String?, name: (Int) -> String): String = buildString {
        val words = ShootoutWords
        appendLine(
            "Shootout results for “$deckName” " + (opponent?.let { "against “$it”" } ?: "on its own") +
                ": read from the person's own answers (${r.fitted} of ${words.hands(r.kept)} read), every number in points of " +
                (if (opponent == null) "the chance a real hand does what the deck wants" else "win chance") + ", with its 95 % range.",
        )
        appendLine()
        appendLine(if (opponent == null) "How often a real hand does what the deck wants:" else "Win rate over real hands:")
        for (s in r.strata + r.waiting.keys) {
            val why = r.waiting[s]
            val e = r.winRates[s]
            appendLine(
                "- ${words.stratum(s)}: " + when {
                    why != null -> "waiting — $why"
                    e == null -> "no hands dealt here yet"
                    else -> "${pct(e.value)} (±${kotlin.math.round(e.halfWidth95).toInt()} points, ${words.hands(r.counts[s] ?: 0)})"
                },
            )
        }
        r.roll()?.let { appendLine(words.roll(it) + ".") }
        appendLine(
            "Settled: ${r.settled.known} of ${r.settled.of} cards known within ±${r.settled.halfWidth.toInt()} points" +
                (if (r.settled.enough) ", enough to stop." else ".") +
                (r.handsToSettle()?.takeIf { it > 0 && !r.settled.enough }?.let { " About ${words.roundHands(it)} more hands." } ?: ""),
        )
        appendLine()
        val calls = r.calls()
        if (calls.isEmpty()) {
            appendLine("Called: none yet. A call is clear of zero at 95 % with every card and situation counted (Holm).")
        } else {
            appendLine("Called (clear of zero at 95 % with every card and situation counted, Holm):")
            calls.forEach { c -> appendLine("- ${name(c.card)}, ${words.where(c.stratum)}: ${words.range(c.estimate)}") }
        }
        appendLine()
        appendLine("Each card per copy in the opening hand, by situation (as the turn's draw going second; the next copy's worth):")
        for (row in r.cards) {
            appendLine("- ${name(row.card)} ×${row.copies} [${row.role}]")
            for (s in r.strata) {
                val cell = row.cells[s] ?: continue
                val parts = ArrayList<String>()
                parts += if (cell.trials == 0) "unrated" else "${words.range(cell.estimate)}, ${words.hands(cell.trials)}"
                row.drawn[s]?.takeIf { it.trials > 0 }?.let { d -> parts += "as your draw ${words.range(d.estimate)}, ${words.hands(d.trials)}" }
                if (cell.trials > 0) row.next[s]?.let { n -> parts += "one more copy ${words.range(n)}" }
                appendLine("  - ${words.stratum(s)}: ${parts.joinToString("; ")}")
            }
        }
        if (r.pairs.isNotEmpty()) {
            appendLine()
            appendLine("Pairs whose 95 % range excludes zero (the extra from holding both, beyond the two cards' own):")
            r.pairs.forEach { p -> appendLine("- ${name(p.a)} + ${name(p.b)}, ${words.where(p.stratum)}: ${words.range(p.estimate)}, ${words.hands(p.trials)}") }
        }
    }.trimEnd()

    private fun pct(x: Double): String = "${kotlin.math.round(x).toInt()}%"

    /** A what-if's answer (`shootout_whatif`): each situation's change with its range, and what it is read from. */
    fun whatIf(change: String, by: Map<Stratum, Estimate>): String = buildString {
        appendLine("$change, read off the model of the person's answers on the same hands (keyed dealing: only hands holding the copy that changed differ):")
        by.forEach { (s, e) -> appendLine("- ${ShootoutWords.stratum(s)}: ${ShootoutWords.range(e)}") }
        append("The deck keeps its size: Shootout deals from it. Whether the copy is allowed is the builder's to say.")
    }
}
