package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.ai.text.Block
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.WorldChart
import com.kaiharimoto.mastertool.core.world.WorldGraph
import com.kaiharimoto.mastertool.core.world.WorldTable

/**
 * The card a page is about, or led by (`PageLeadTest`): its art stands on the page's tab beside the kind's glyph, so two
 * tabs of one kind are told apart by their pictures (kai: "a visually intuitive solution to tell which tab is which").
 * Only where the page says outright that it holds cards — a cards page, a chart or table marked `cards`, a web's card
 * nodes, a `[[Card]]` in its words — never guessed from a title.
 */
object PageLead {
    private val LINKED = Regex("""\[\[([^\[\]]{1,80})]]""")

    /** The card [board] is led by, by its name (or passcode), or null. */
    fun card(board: Board): String? = when (board.type) {
        BoardKind.CHART -> (WorldChart.parse(board.payload).getOrNull() as? WorldChart.Bars)?.chart?.takeIf { it.cards }?.let { c ->
            // The card the chart puts first is its largest bar's, read as the chart is read.
            val first = c.series.firstOrNull()?.values ?: return@let c.labels.firstOrNull()
            c.labels.getOrNull(first.indices.maxByOrNull { first[it] } ?: 0)
        }
        BoardKind.TABLE -> WorldTable.parse(board.payload).getOrNull()?.let { t ->
            val col = t.cards.firstOrNull() ?: return@let null
            t.rows.firstNotNullOfOrNull { r -> r.getOrNull(col)?.takeIf { it.isNotBlank() } }
        }
        BoardKind.GRAPH, BoardKind.FLOW -> WorldGraph.parse(board.payload).getOrNull()?.let { g ->
            // The web's hub: the card most edges touch.
            val touches = HashMap<String, Int>()
            g.edges.forEach { e -> touches[e.from] = (touches[e.from] ?: 0) + 1; touches[e.to] = (touches[e.to] ?: 0) + 1 }
            g.nodes.filter { it.card }.maxByOrNull { touches[it.id] ?: 0 }?.label
        }
        BoardKind.CARDS -> fenced("cards", board.payload)
        BoardKind.LINE -> fenced("line", board.payload)
        BoardKind.BOARD -> fenced("board", board.payload)
        BoardKind.MARKDOWN -> linked(board.payload)
        else -> null
    }

    /** The first card of a ```[fence] block holding [body], as the chat's painters read it. */
    private fun fenced(fence: String, body: String): String? {
        val block = ChatMarkdown.parse("```$fence\n$body\n```").firstOrNull()
        return when (block) {
            is Block.Cards -> block.lines.firstOrNull()?.name
            is Block.Line -> block.steps.firstNotNullOfOrNull { it.card }
            is Block.Board -> block.board.let { b -> (b.monsters + b.extraMonsters).firstNotNullOfOrNull { it?.name } ?: b.hand.firstOrNull()?.name }
            else -> null
        } ?: linked(body)
    }

    /** The first `[[Card]]` in [text]. */
    fun linked(text: String): String? =
        LINKED.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}
