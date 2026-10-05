package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.ShowSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageLeadTest {
    private fun board(kind: String, body: String): Board {
        val (k, payload) = ShowSpec.parse(kind, body).getOrThrow()
        return Board("b", "t", k.id, payload)
    }

    @Test
    fun aChartOfCardsIsLedByItsLargestBar() {
        val b = board("chart", """{"type":"hbar","cards":true,"labels":["Ash","Maxx","Imperm"],"series":[{"values":[20,38,12]}]}""")
        assertEquals("Maxx", PageLead.card(b))
        assertNull(PageLead.card(board("chart", """{"labels":["Ash","Maxx"],"series":[{"values":[20,38]}]}""")), "unsaid: not cards")
    }

    @Test
    fun aTableByItsCardColumnsFirstRow() {
        assertEquals("Ash", PageLead.card(board("table", """{"columns":["Card","Copies"],"rows":[["Ash","3"],["Maxx","2"]],"cards":["Card"]}""")))
        assertNull(PageLead.card(board("table", """{"columns":["Card","Copies"],"rows":[["Ash","3"]]}""")))
    }

    @Test
    fun aWebByItsHub() {
        val web = """{"nodes":[{"id":"a","label":"Ash","card":"true"},{"id":"b","label":"Maxx","card":"true"},{"id":"c","label":"Imperm","card":"true"},{"id":"d","label":"End"}],"edges":[["a","b"],["b","c"],["b","d"]]}"""
        assertEquals("Maxx", PageLead.card(board("graph", web)))
    }

    @Test
    fun cardsLinesBoardsAndWords() {
        assertEquals("Ash Blossom & Joyous Spring", PageLead.card(board("cards", "Starters: 3 Ash Blossom & Joyous Spring, 2 Maxx \"C\"")))
        assertEquals("Ash", PageLead.card(board("line", "1. [[Ash]] Normal Summon\n2. [[Maxx]] searched")))
        assertEquals("Maxx", PageLead.card(board("markdown", "The deck wants [[Maxx]] most, then [[Ash]].")))
        assertNull(PageLead.card(board("markdown", "No cards named here.")))
        assertNull(PageLead.card(board("stat", """{"value":"63%","label":"Opens"}""")))
    }
}
