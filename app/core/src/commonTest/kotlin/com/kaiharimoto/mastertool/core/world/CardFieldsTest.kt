package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.world.apps.UiNode
import com.kaiharimoto.mastertool.core.world.apps.UiTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cards drawn as pictures in Ai World (kai: "integrate card images where possible/needed for maximum visual pickup"):
 * a board or an app says outright which of its words are cards — a chart's labels, a table's columns, a `ui.card` — and
 * what it said survives the trip through `ShowSpec` to the painter. Nothing is guessed from the words.
 */
class CardFieldsTest {
    @Test
    fun aChartWhoseLabelsAreCardsSaysSoAndKeepsIt() {
        val (kind, payload) = ShowSpec.parse(
            "chart",
            """{"type":"hbar","cards":true,"labels":["Ash Blossom & Joyous Spring","Maxx \"C\""],"series":[{"name":"seen","values":[38.1,31]}],"unit":"%"}""",
        ).getOrThrow()
        assertEquals(BoardKind.CHART, kind)
        val chart = (WorldChart.parse(payload).getOrThrow() as WorldChart.Bars).chart
        assertTrue(chart.cards)
        // Twice through: what was checked is what is drawn.
        assertEquals(payload, WorldChart.encode(WorldChart.parse(payload).getOrThrow()))
        // Unsaid, a chart's labels are words.
        val plain = ShowSpec.parse("chart", """{"labels":["a"],"series":[{"values":[1]}]}""").getOrThrow().second
        assertFalse((WorldChart.parse(plain).getOrThrow() as WorldChart.Bars).chart.cards)
        assertFalse(plain.contains("cards"), "an older build's payload is unchanged")
    }

    @Test
    fun aTableNamesItsCardColumnsByIndexOrName() {
        val byIndex = WorldTable.parse("""{"columns":["Card","Copies"],"rows":[["Ash Blossom & Joyous Spring","3"]],"cards":[0]}""").getOrThrow()
        assertEquals(listOf(0), byIndex.cards)
        val byName = WorldTable.parse("""{"columns":["Copies","Card"],"rows":[["3","Ash"]],"cards":["card"]}""").getOrThrow()
        assertEquals(listOf(1), byName.cards)
        val first = WorldTable.parse("""{"columns":["Card"],"rows":[["Ash"]],"cards":true}""").getOrThrow()
        assertEquals(listOf(0), first.cards)
        // A column that is not there, or nothing said: no cards.
        assertEquals(emptyList(), WorldTable.parse("""{"columns":["Card"],"rows":[["Ash"]],"cards":[4,"Nope"]}""").getOrThrow().cards)
        assertEquals(emptyList(), WorldTable.parse("""{"columns":["Card"],"rows":[["Ash"]]}""").getOrThrow().cards)
        // Round trip.
        val payload = ShowSpec.parse("table", """{"columns":["Card","Copies"],"rows":[["Ash","3"]],"cards":["Card"]}""").getOrThrow().second
        assertEquals(listOf(0), WorldTable.parse(payload).getOrThrow().cards)
        assertEquals(payload, WorldTable.encode(WorldTable.parse(payload).getOrThrow()))
    }

    @Test
    fun anAppDrawsACardByNameOrPasscode() {
        val small = UiTree.parse("""{"ui":"card","card":"Ash Blossom & Joyous Spring","label":"The hand trap"}""").root
        assertIs<UiNode.Card>(small)
        assertEquals("Ash Blossom & Joyous Spring", small.card)
        assertFalse(small.large)
        assertEquals("The hand trap", small.label)
        val large = UiTree.parse("""{"ui":"card","card":14558127,"size":"large","id":"pick","pickable":true}""").root
        assertIs<UiNode.Card>(large)
        assertEquals("14558127", large.card)
        assertTrue(large.large)
        assertTrue(large.pickable)
        // No card: drawn in its place as why.
        assertIs<UiNode.Broken>(UiTree.parse("""{"ui":"card"}""").root)
    }

    @Test
    fun anAppsTableMarksItsCardColumns() {
        val t = UiTree.parse("""{"ui":"table","columns":["Card","Odds"],"rows":[["Ash","38%"]],"cards":["Card"]}""").root
        assertIs<UiNode.Table>(t)
        assertEquals(listOf(0), t.cardColumns)
        val plain = UiTree.parse("""{"ui":"table","columns":["Card","Odds"],"rows":[["Ash","38%"]]}""").root
        assertIs<UiNode.Table>(plain)
        assertTrue(plain.cardColumns.isEmpty(), "a cell is a card only when it is said to be")
    }

    @Test
    fun aChartsValuesShareTheirPlaces() {
        assertEquals(listOf("38.1%", "31.0%", "12.9%"), ChatChart.labels(listOf(38.1, 31.0, 12.9), "%"))
        assertEquals(listOf("33.76", "39.43", "44.78"), ChatChart.labels(listOf(33.76, 39.43, 44.78), ""))
        assertEquals(listOf("5", "60"), ChatChart.labels(listOf(5.0, 60.0), ""))
        assertEquals(listOf("-1.50 ms", "2.25 ms"), ChatChart.labels(listOf(-1.5, 2.25), "ms"))
        assertEquals("0.07", ChatChart.fixed(0.07, 2))
    }
}
