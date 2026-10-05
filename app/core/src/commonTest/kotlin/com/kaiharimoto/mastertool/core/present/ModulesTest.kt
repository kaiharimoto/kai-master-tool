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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ---- the audit's module findings (1.1.x) -----------------------------------------------

    private val two = ModuleInput(
        matchups = listOf(
            SideMatchup("Snake-Eye", SideTurn(listOf(1, 1), listOf(9, 9), "Stop it early"), SideTurn(listOf(2), listOf(8), "Break the board, then out-grind")),
            SideMatchup("Yubel", SideTurn(listOf(3), listOf(7), "Keep it small"), SideTurn(listOf(4), listOf(6), "Out them with removal")),
        ),
    )

    private fun cardsOf(s: Slide, slot: String) = s.elements.first { it.id == "${s.id}-$slot" }.cards

    @Test
    fun aSidingRefreshFollowsItsOwnMatchupNeverTheTitle() {
        // B2: retitled on the Slide tab, the Yubel slide was refreshed into Snake-Eye's.
        val yubel = Modules.generate(Modules.SIDING, two, 0L, r).first { it.title == "Siding vs Yubel" }
        assertEquals("Yubel", yubel.module?.params?.get(Modules.PARAM_MATCHUP))
        val retitled = yubel.copy(title = "The hard one")
        val fresh = Modules.generate(Modules.SIDING, two, 1L, r)
        val done = (Modules.refreshed(retitled, fresh) as Modules.Refreshed.Made).slide
        assertEquals("The hard one", done.title, "the person's title stays")
        assertEquals("vs Yubel", done.elements.first { it.id == "${done.id}-title" }.plainText)
        assertEquals(listOf(3), cardsOf(done, "first-out"))
        assertEquals("Yubel", done.module?.params?.get(Modules.PARAM_MATCHUP), "the link survives a refresh")
    }

    @Test
    fun aSidingSlideWhoseMatchupIsGoneIsLeftAndSaysSo() {
        val yubel = Modules.generate(Modules.SIDING, two, 0L, r).first { it.title == "Siding vs Yubel" }
        val renamed = two.copy(matchups = listOf(two.matchups[0], two.matchups[1].copy(name = "Yubel Fiendsmith")))
        val gone = Modules.refreshed(yubel, Modules.generate(Modules.SIDING, renamed, 1L, r))
        assertTrue(gone is Modules.Refreshed.Gone, "never the first matchup instead")
        assertTrue("Yubel" in gone.why)
    }

    @Test
    fun aSidingSlideMadeBeforeTheLinkFindsItsMatchupByWhatItMade() {
        // A 1.0.71 slide: no matchup param, retitled; its untouched title element still names it.
        val made = Modules.generate(Modules.SIDING, two, 0L, r).first { it.title == "Siding vs Yubel" }
        val old = made.copy(title = "Retitled", module = made.module!!.copy(params = made.module!!.params - Modules.PARAM_MATCHUP))
        assertEquals("Yubel", Modules.matchupOf(old))
        val done = (Modules.refreshed(old, Modules.generate(Modules.SIDING, two, 1L, r)) as Modules.Refreshed.Made).slide
        assertEquals(listOf(4), cardsOf(done, "second-out"))
        // Its title element changed by hand too: the slide's own title as it was made.
        val edited = old.copy(title = "Siding vs Yubel", elements = old.elements.map { if (it.id.endsWith("-title")) it.copy(paras = listOf(Para.of("Mine")), edited = true) else it })
        assertEquals("Yubel", Modules.matchupOf(edited))
    }

    @Test
    fun theNoMatchupsSlideRefreshesIntoTheFirstMatchupThereIs() {
        val empty = Modules.generate(Modules.SIDING, ModuleInput(), 0L, r).single()
        assertNull(Modules.matchupOf(empty))
        assertTrue(Modules.refreshed(empty, Modules.generate(Modules.SIDING, ModuleInput(), 1L, r)) is Modules.Refreshed.Gone)
        val done = Modules.refreshed(empty, Modules.generate(Modules.SIDING, two, 1L, r))
        assertEquals("Snake-Eye", (done as Modules.Refreshed.Made).slide.module?.params?.get(Modules.PARAM_MATCHUP))
    }

    @Test
    fun everyElementOfAClickedTurnBuildsOnItsClick() {
        // B7: the going-second why stood alone under an empty band before its click.
        val withNote = Modules.generate(Modules.SIDING, two, 0L, r).first()
        val noChange = Modules.generate(Modules.SIDING, ModuleInput(matchups = listOf(SideMatchup("A", SideTurn(listOf(1), listOf(2)), SideTurn()))), 0L, r).single()
        for (s in listOf(withNote, noChange)) {
            val builds = Builds.compile(s)
            assertEquals(2, builds.count, "the arrival, then the second turn")
            val second = s.elements.filter { it.id.startsWith("${s.id}-second") }
            assertTrue(second.size >= 2, "the label and something more")
            second.forEach { e ->
                assertFalse(Builds.state(builds, e, 0, Long.MAX_VALUE / 4).visible, "${e.id} waits for its click")
                assertTrue(Builds.state(builds, e, 1, Long.MAX_VALUE / 4).visible, "${e.id} comes in on it")
            }
            s.elements.filter { it.id.startsWith("${s.id}-first") }.forEach { e ->
                assertTrue(Builds.state(builds, e, 0, Long.MAX_VALUE / 4).visible, "${e.id} is there on arrival")
            }
        }
    }

    @Test
    fun aComboArrowComesWithTheCardItPointsAt() {
        val s = Modules.generate(Modules.COMBO, ModuleInput(picks = listOf(Pick(1, "a"), Pick(2, "b"), Pick(3, "c"))), 0L, r).single()
        val builds = Builds.compile(s)
        val arrow = s.elements.first { it.id.endsWith("comboArrow0") }
        assertFalse(Builds.state(builds, arrow, 1, Long.MAX_VALUE / 4).visible, "not with the first card")
        assertTrue(Builds.state(builds, arrow, 2, Long.MAX_VALUE / 4).visible, "with the second")
    }

    @Test
    fun aDataModuleWithNoDataSaysWhatToMakeFirst() {
        assertNotNull(Modules.missing(Modules.MATCHUPS, ModuleInput()))
        assertNotNull(Modules.missing(Modules.SIDING, ModuleInput()))
        assertNotNull(Modules.missing(Modules.ODDS, ModuleInput()))
        assertNotNull(Modules.missing(Modules.RATIOS, ModuleInput(odds = listOf(GroupOdds("Empty", 0.0, 0.0, 0)))))
        assertNull(Modules.missing(Modules.MATCHUPS, ModuleInput(rows = listOf(MatchRow("A", games = 2)))))
        assertNull(Modules.missing(Modules.PERFORMERS, ModuleInput()), "picked by hand: never refused")
    }

    @Test
    fun titlesTheDialogAsksForAreDrawn() {
        // I3: Siding ignored the Title; Decklist listed it but drew none.
        val siding = Modules.generate(Modules.SIDING, two.copy(title = "Regionals"), 0L, r).first()
        assertEquals("Regionals · vs Snake-Eye", siding.elements.first { it.id.endsWith("-title") }.plainText)
        val list = Modules.generate(Modules.DECKLIST, ModuleInput(title = "Labrynth"), 0L, r).single()
        assertEquals("Labrynth", list.elements.first { it.id.endsWith("-title") }.plainText)
    }

    @Test
    fun ratiosCarryTheGroupsColours() {
        val s = Modules.generate(Modules.RATIOS, ModuleInput(odds = listOf(GroupOdds("A", 0.5, 0.6, 12, color = 3), GroupOdds("B", 0.4, 0.5, 9, color = 5))), 0L, r).single()
        assertEquals(listOf(3, 5), s.elements.first { it.type == Element.CHART }.chart?.groups)
    }
}
