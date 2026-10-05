package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.RunLog
import com.kaiharimoto.mastertool.core.world.RunRecord
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldLimits
import com.kaiharimoto.mastertool.core.world.WorldPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeskCodecTest {
    private val area = DeskArea.LAPTOP

    @Test
    fun aDeskRoundTripsAndForgetsTheTurn() {
        var d = Desk().reduce(DeskOp.Open(BuiltInApp.EDITOR.ref, at = 1), area).reduce(DeskOp.Open(AppRef.Made("hand-odds"), at = 2), area)
        d = d.reduce(DeskOp.ToggleMaximise("editor", 3), area).reduce(DeskOp.Keep("app:hand-odds", true), area)
        d = d.reduce(DeskOp.Tabs(d.tabs.open(WorldAddress.HOME, 4).show("b1", 5, raise = false)), area)
        d = d.reduce(DeskOp.MoveIcon("files", 0, 3), area).reduce(DeskOp.TurnStart(6), area).reduce(DeskOp.Arrive(BuiltInApp.TERMINAL.ref, FocusDecision.BEHIND, 7), area)
        val back = DeskCodec.decode(DeskCodec.encode(d))
        assertEquals(d.windows, back.windows)
        assertEquals(d.front, back.front)
        assertEquals(d.tabs, back.tabs)
        assertEquals(d.icons, back.icons)
        assertEquals(d.turn, back.turn)
        assertFalse(back.working, "a turn is never stored")
        assertNull(back.ai)
    }

    @Test
    fun aDeskThatWillNotReadIsAnEmptyDesktop() {
        assertEquals(Desk(), DeskCodec.decode(null))
        assertEquals(Desk(), DeskCodec.decode("not json"))
        assertEquals(Desk(), DeskCodec.decode("[]"))
    }

    @Test
    fun aBrokenWindowOrTabIsDroppedAlone() {
        val d = DeskCodec.decode(
            """{"windows":[{"app":"editor","frame":{"x":0.1,"y":0.1,"w":0.5,"h":0.6}},{"app":"terminal","frame":"oops"},{"app":"files","minimised":true}],
               "front":"files","tabs":{"tabs":[{"id":"t1","address":"world://home"},{"address":"world://home"}],"selected":"t9","next":1},"turn":"x"}""",
        )
        assertEquals(listOf("editor", "files"), d.windows.map { it.app })
        assertNull(d.front, "a minimised window is never in front")
        assertEquals(listOf("t1"), d.tabs.tabs.map { it.id })
        assertEquals("t1", d.tabs.selected)
        assertEquals(2, d.tabs.next, "past every id it holds")
    }

    @Test
    fun theWorldHomeGroupsPagesByTheRunThatMadeThem() {
        val w = World(
            "w1",
            boards = listOf(
                Board("a", "Opens a starter", "stat", updated = 10, source = "openings.js"),
                Board("b", "By hand size", "chart", updated = 11, source = "openings.js"),
                Board("c", "Notes", "markdown", updated = 50, note = "hand traps"),
            ),
        )
        val log = listOf(WorldEvent(9, WorldEvent.Kind.RUN, path = "openings.js", text = "Ran openings.js", run = RunRecord(boards = listOf("a", "b"))))
        val g = WorldHome.groups(w, log)
        assertEquals(listOf("Shown by Ai", "openings.js"), g.map { it.title })
        assertEquals(listOf("b", "a"), g[1].boards.map { it.id })
        assertEquals(9L, g[1].run)
        assertEquals(listOf("c"), WorldHome.groups(w, log, "traps").flatMap { it.boards }.map { it.id })
        assertEquals("16 pages · 6 files · 1 app", WorldHome.summary(16, 6, 1))
        assertEquals("1 page", WorldHome.summary(1, 0, 0))
        assertEquals("", WorldHome.summary(0, 0, 0))
    }

    @Test
    fun aFileIsReadInPagesThatEndAtALine() {
        val text = (1..5_000).joinToString("\n") { "line $it of the file" }
        val first = WorldPage.of(text)
        assertTrue(first.text.length <= WorldLimits.READ_PAGE)
        assertTrue(first.text.endsWith("\n"), "a page ends at a line break")
        var at: Int? = 0
        val all = StringBuilder()
        while (at != null) {
            val p = WorldPage.of(text, at)
            all.append(p.text)
            at = p.next
        }
        assertEquals(text, all.toString(), "the pages are the file")
        assertTrue("world_read with from: ${first.next}" in first.footer("big.txt"))
        assertEquals("", WorldPage.of("short").footer("s.txt"))
        assertNull(WorldPage.of("short").next)
        // A line longer than a page is cut where it must be.
        val one = "x".repeat(40_000)
        assertEquals(WorldLimits.READ_PAGE, WorldPage.of(one).text.length)
    }

    @Test
    fun aLongRunsWholeOutputGoesToItsLog() {
        assertEquals("out/${1_000L.toString(36)}.log", RunLog.path(1_000))
        val c = RunLog.Collector()
        repeat(10) { c.line("short") }
        assertFalse(c.needed, "under 64k nothing needs a log")
        repeat(10_000) { c.line("a line of output that is long enough to add up") }
        assertTrue(c.needed)
        assertEquals(10_010, c.text().lines().count { it.isNotEmpty() })
        assertEquals(16 * 1024 * 1024, WorldLimits.MAX_FILE)
    }
}
