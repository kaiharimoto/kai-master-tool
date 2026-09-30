package com.kaiharimoto.mastertool.core.ai.vision

import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.search.CardIndex

/**
 * Card names read off a picture, matched to cards (1.0.55, the `resolve_cards` tool). The pool's
 * own search goes first — it knows every name exactly and a prefix well — and [NameMatch] after it,
 * for what a model misread: a letter wrong, words run together, a name cut off at a column's edge.
 */
object ReadCards {
    data class Read(val name: String, val count: Int = 1, val section: String? = null)

    data class Match(
        val read: Read,
        val card: Card?,
        /** 0–1: 1 is the name exactly, [NameMatch.SURE] and above is taken without asking. */
        val sureness: Double,
        /** When not sure: the nearest other names. */
        val nearest: List<String> = emptyList(),
    ) {
        val sure: Boolean get() = card != null && sureness >= NameMatch.SURE
    }

    fun resolve(lines: List<Read>, index: CardIndex): List<Match> = lines.map { resolve(it, index) }

    fun resolve(read: Read, index: CardIndex): Match {
        val name = read.name.trim().removeSurrounding("[[", "]]").trim()
        index.byName(name)?.let { return Match(read, it, 1.0) }
        val searched = when (val r = CardWords.resolve(name, index)) {
            is Resolved.Found -> r.card to (if (r.guessed) NameMatch.score(name, r.card.name) else 1.0)
            is Resolved.Unknown -> null
        }
        // The pool's search is sure: take it. Otherwise weigh every name by how it reads.
        if (searched != null && searched.second >= NameMatch.SURE) return Match(read, searched.first, searched.second)
        val fuzzy = NameMatch.best(name, index.cards.asSequence().map { it.name }.distinct(), limit = 4)
        val best = fuzzy.firstOrNull()
        val pick = when {
            best == null -> searched
            searched != null && searched.second >= best.score -> searched
            else -> index.byName(best.name)?.let { it to best.score }
        }
        val nearest = fuzzy.map { it.name }.filter { it != pick?.first?.name }.take(3)
        return Match(read, pick?.first, pick?.second ?: 0.0, if ((pick?.second ?: 0.0) < NameMatch.SURE) nearest else emptyList())
    }

    /** The tool's answer: one line a card, marked sure, check or not found. */
    fun describe(matches: List<Match>): String = buildString {
        val sure = matches.count { it.sure }
        appendLine("$sure of ${matches.size} read with confidence.")
        matches.forEachIndexed { i, m ->
            val count = if (m.read.count > 1) "${m.read.count} " else ""
            val where = m.read.section?.let { " [$it]" }.orEmpty()
            val line = when {
                m.card == null -> "NOT FOUND \"${m.read.name}\"" + (if (m.nearest.isNotEmpty()) " — nearest: ${m.nearest.joinToString("; ")}" else "")
                m.sure -> "ok $count${m.card.name}$where"
                else -> "CHECK \"${m.read.name}\" → $count${m.card.name}$where (${(m.sureness * 100).toInt()}% sure)" +
                    (if (m.nearest.isNotEmpty()) " — or: ${m.nearest.joinToString("; ")}" else "")
            }
            appendLine("${i + 1}. $line")
        }
    }.trimEnd()
}
