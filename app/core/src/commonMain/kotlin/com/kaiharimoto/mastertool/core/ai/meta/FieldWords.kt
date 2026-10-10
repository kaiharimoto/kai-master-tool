package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckSection
import kotlin.math.round

/** The field's profile and a list against its strategy, in words for Ai (`field_profile`, `field_compare`; Phase G, G.5). */
object FieldWords {
    fun pct(x: Double): String = "${round(x * 100).toInt()}%"

    private fun copies(c: CardCopies, name: (CardId) -> String) = "${name(c.card)} (${pct(c.share)} of lists, ${round(c.mean * 10) / 10} copies)"

    private fun opens(o: Opens) =
        "opens at least one ${pct(o.one5)} going first (five cards), ${pct(o.one6)} going second (six); at least two ${pct(o.two5)} / ${pct(o.two6)}"

    fun profile(p: FieldProfile, name: (CardId) -> String, shownCards: Int = 6): String = buildString {
        appendLine("The field's interaction: hand traps and negates read from each card's text, per strategy, weighted by share. Counts only.")
        appendLine("Over the field: ${opens(p.field)}.")
        p.interaction().take(shownCards + 2).takeIf { it.isNotEmpty() }?.let { appendLine("Most played: " + it.joinToString("; ") { c -> copies(c, name) }) }
        p.sided().take(shownCards + 2).takeIf { it.isNotEmpty() }?.let { appendLine("Most sided: " + it.joinToString("; ") { c -> copies(c, name) }) }
        p.strategies.forEach { s ->
            appendLine()
            appendLine("${s.name} — ${s.share}% (${s.lists} lists): ${opens(s.opens)}.")
            if (s.handTraps.isNotEmpty()) appendLine("  Hand traps: " + s.handTraps.take(shownCards).joinToString("; ") { copies(it, name) })
            if (s.negates.isNotEmpty()) appendLine("  Negates: " + s.negates.take(shownCards).joinToString("; ") { copies(it, name) })
            if (s.side.isNotEmpty()) appendLine("  Side Deck: " + s.side.take(shownCards).joinToString("; ") { copies(it, name) })
        }
    }.trimEnd()

    fun compare(r: StrategyRatios, deckName: String, similarity: Double?, name: (CardId) -> String): String = buildString {
        appendLine(
            "“$deckName” against ${r.lists} ${r.name} lists" + (similarity?.let { " (alike: ${pct(it)} by weighted card overlap)" } ?: "") +
                ". Counts only: a list that placed tells what it played, never that a card is why.",
        )
        fun line(c: CardRatio) = "${name(c.card)}${if (c.section != DeckSection.MAIN) " [${c.section.name.lowercase()}]" else ""}: " +
            "${StrategyRatios.line(c, r.lists)} (mean ${round(c.mean * 10) / 10}), yours ${c.yours}"
        appendLine()
        appendLine("The field plays it, you don't:")
        r.missing.ifEmpty { null }?.forEach { appendLine("- " + line(it)) } ?: appendLine("- nothing")
        appendLine("You run more or fewer than most lists:")
        r.counts.ifEmpty { null }?.forEach { appendLine("- " + line(it)) } ?: appendLine("- nothing")
        appendLine("Your techs (in a tenth of the lists or fewer):")
        r.techs.ifEmpty { null }?.forEach { appendLine("- " + line(it)) } ?: appendLine("- none")
        val consensus = r.consensus()
        appendLine()
        append(
            "The consensus list (each card most lists play, at its most common count): ${consensus.main.size} Main, ${consensus.extra.size} Extra, " +
                "${consensus.side.size} Side.",
        )
    }.trimEnd()

    fun presence(rows: List<FieldShares.Presence>): String = rows.joinToString("\n") { p ->
        "${p.name}: ${pct(p.share)} of the weighted results, ${pct(p.presence)} of the lists (${p.lists})" +
            if (p.presence > 0) ", converts ×${round(p.conversion * 10) / 10}" else ""
    }
}
