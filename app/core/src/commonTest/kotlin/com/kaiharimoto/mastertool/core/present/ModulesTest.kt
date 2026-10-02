package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.modules.GroupOdds
import com.kaiharimoto.mastertool.core.present.modules.MatchRow
import com.kaiharimoto.mastertool.core.present.modules.ModuleInput
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.modules.Pick
import com.kaiharimoto.mastertool.core.present.modules.SideMatchup
import com.kaiharimoto.mastertool.core.present.modules.SideTurn
import com.kaiharimoto.mastertool.core.present.play.Builds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModulesTest {
    private val r = Random(5)

    @Test
    fun everyModuleMakesStageAnchoredSlidesItRemembers() {
        val input = ModuleInput(
            matchups = listOf(SideMatchup("Snake-Eye", SideTurn(listOf(1, 1), listOf(9, 9), "Go big"), SideTurn())),
            rows = listOf(MatchRow("Snake-Eye", 0.6, 10, 0.4, 10, 0.5, 8, 0.5, 12, 0.5, 20)),
            expected = 0.55,
            strong = listOf(Pick(1, "Won games")),
            weak = listOf(Pick(2, "Dead")),
            picks = listOf(Pick(1, "Normal"), Pick(2, "Search"), Pick(3, "End")),
            odds = listOf(GroupOdds("Starters", 0.87, 0.91, 12)),
            code = "ydke://abc",
        )
        for (type in Modules.all) {
            val slides = Modules.generate(type, input, 0L, r)
            assertTrue(slides.isNotEmpty(), type)
            slides.forEach { s ->
                assertEquals(type, s.module?.type)
                assertEquals(input, Modules.inputOf(s.module), "$type keeps its input")
                s.elements.forEach { e ->
                    assertEquals(Element.ANCHOR_STAGE, e.anchor, "$type ${e.id}")
                    assertTrue(e.x >= -0.01f && e.y >= -0.01f && e.x + e.w <= 1.01f && e.y + e.h <= 1.01f, "$type ${e.id} stays on the stage")
                    assertTrue(e.id.startsWith(s.id), "$type ids are slotted")
                }
            }
        }
    }

    @Test
    fun nothingOnAModuleSlideSitsOnAnythingElse() {
        // 1.0.71's pictures: a siding note under the OUT and IN tags. Words, cards, tables, charts
        // and numbers each have their own room; only a shape (the arrow) may stand among them.
        val input = ModuleInput(
            matchups = listOf(SideMatchup("Snake-Eye", SideTurn(listOf(1, 2), listOf(9, 8), "Stop it early"), SideTurn(listOf(3), listOf(7), "Out-grind"), "The most played deck")),
            rows = listOf(MatchRow("Snake-Eye", 0.6, 10, 0.4, 10, 0.5, 8, 0.5, 12, 0.5, 20), MatchRow("Yubel", games = 4)),
            expected = 0.55,
            strong = listOf(Pick(1, "Won games"), Pick(4, "Always live")),
            weak = listOf(Pick(2, "Dead"), Pick(5, "Slow")),
            picks = listOf(Pick(1, "Normal"), Pick(2, "Search"), Pick(3, "End")),
            rounds = listOf(com.kaiharimoto.mastertool.core.present.modules.RoundRow(1, "Yubel", "Won")),
            record = "1–0",
            placement = "Top 8",
            shoutouts = listOf(com.kaiharimoto.mastertool.core.present.modules.Shoutout(null, "Crew", "@crew", "Testing")),
            odds = listOf(GroupOdds("Starters", 0.87, 0.91, 12), GroupOdds("Extenders", 0.6, 0.7, 9)),
            code = "ydke://abc",
            title = "Event",
        )
        for (type in Modules.all) {
            Modules.generate(type, input, 0L, r).forEach { s ->
                val solid = s.elements.filter { it.type != Element.SHAPE && it.type != Element.QR }
                for (i in solid.indices) for (j in i + 1 until solid.size) {
                    val a = solid[i]
                    val b = solid[j]
                    val w = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                    val h = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                    assertTrue(w <= 0.005f || h <= 0.005f, "$type: ${a.id} and ${b.id} overlap")
                }
            }
        }
    }

    @Test
    fun aComboComesInACardAtATime() {
        val s = Modules.generate(Modules.COMBO, ModuleInput(picks = listOf(Pick(1), Pick(2), Pick(3))), 0L, r).single()
        assertEquals(4, Builds.compile(s).count, "the arrival and a click per card")
    }

    @Test
    fun aRefreshKeepsWhatWasEditedByHand() {
        val old = Modules.generate(Modules.MATCHUPS, ModuleInput(rows = listOf(MatchRow("A", games = 2))), 0L, r).single()
        val title = old.elements.first { it.id.endsWith("-title") }
        val edited = old.copy(elements = old.elements.map { if (it.id == title.id) it.copy(paras = listOf(Para.of("My words")), edited = true) else it } + Element("mine", Element.TEXT))
        val fresh = Modules.generate(Modules.MATCHUPS, ModuleInput(rows = listOf(MatchRow("A", games = 2), MatchRow("B", games = 5))), 0L, r).single()
        val done = Modules.refresh(edited, fresh)
        assertEquals("My words", done.elements.first { it.id == title.id }.plainText)
        assertTrue(done.elements.any { it.id == "mine" }, "added by hand stays")
        val table = done.elements.first { it.type == Element.TABLE }
        assertEquals(3, table.table.size, "the table is the fresh one")
        assertEquals(done.elements.size, done.elements.map { it.id }.toSet().size)
    }
}
