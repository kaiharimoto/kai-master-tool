package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeskTidyTest {
    private val area = DeskArea.LAPTOP
    private fun Desk.op(op: DeskOp) = reduce(op, area)
    private fun Desk.ai(app: AppRef, at: Long) = op(DeskOp.Arrive(app, FocusDecision.RAISE, at))

    @Test
    fun theAnswerStaysAndUntouchedWindowsOfAisClose() {
        var d = Desk().op(DeskOp.TurnStart(1))
        d = d.ai(BuiltInApp.EDITOR.ref, 2).ai(BuiltInApp.TERMINAL.ref, 3)
        d = d.op(DeskOp.Tabs(d.tabs.show("b1", 4, raise = true, turn = d.turn))).ai(BuiltInApp.BROWSER.ref, 4).op(DeskOp.AiShowed)
        d = d.op(DeskOp.TurnEnd(5))
        assertEquals(listOf("browser"), d.windows.map { it.app })
        assertEquals("browser", d.front)
        assertFalse(d.working)
    }

    @Test
    fun withNoPageOrAppTheLastWindowItWorkedInStays() {
        var d = Desk().op(DeskOp.TurnStart(1))
        d = d.ai(BuiltInApp.EDITOR.ref, 2).ai(BuiltInApp.TERMINAL.ref, 3)
        d = d.op(DeskOp.TurnEnd(4))
        assertEquals(listOf("terminal"), d.windows.map { it.app })
        assertEquals("terminal", d.front)
    }

    @Test
    fun anAppMadeThisTurnIsTheAnswer() {
        var d = Desk().op(DeskOp.TurnStart(1))
        d = d.ai(BuiltInApp.EDITOR.ref, 2).ai(AppRef.Made("hand-odds"), 3).op(DeskOp.AiMadeApp("hand-odds")).ai(BuiltInApp.TERMINAL.ref, 4)
        d = d.op(DeskOp.TurnEnd(5))
        assertEquals(listOf("app:hand-odds"), d.windows.map { it.app })
        assertEquals("app:hand-odds", d.front)
    }

    @Test
    fun nothingOfThePersonsIsTouched() {
        var d = Desk().op(DeskOp.Open(BuiltInApp.FILES.ref, at = 1)).op(DeskOp.TurnStart(2))
        d = d.ai(BuiltInApp.EDITOR.ref, 3).ai(BuiltInApp.TERMINAL.ref, 4).ai(BuiltInApp.INSTRUMENTS.ref, 5)
        // The person pressed in the terminal Ai opened: it is theirs now.
        d = d.op(DeskOp.Focus("terminal", at = 6))
        d = d.op(DeskOp.Keep("editor", true))
        d = d.op(DeskOp.TurnEnd(7))
        assertTrue(d.isOpen("files"), "the person's own window")
        assertTrue(d.isOpen("terminal"), "touched")
        assertTrue(d.isOpen("editor"), "kept")
        assertTrue(d.isOpen("instruments"), "the last window Ai worked in")
        assertEquals(WorldEvent.YOU, d.window("files")!!.by)
    }

    @Test
    fun windowsFromEarlierTurnsStay() {
        var d = Desk().op(DeskOp.TurnStart(1)).ai(BuiltInApp.TERMINAL.ref, 2).op(DeskOp.TurnEnd(3))
        d = d.op(DeskOp.TurnStart(4)).ai(BuiltInApp.EDITOR.ref, 5).op(DeskOp.TurnEnd(6))
        assertTrue(d.isOpen("terminal"))
        assertTrue(d.isOpen("editor"))
    }

    @Test
    fun keptTabsSurviveAndOldTabsOfAisBeyondFiveClose() {
        var tabs = BrowserTabs().open(WorldAddress.HOME, at = 0, by = WorldEvent.YOU)
        // Eight of Ai's tabs from turn 1, one kept; then turn 2.
        for (i in 1..8) tabs = tabs.show("b$i", at = i.toLong(), raise = false, turn = 1)
        val kept = tabs.showing(WorldAddress.Board("b1").format())!!.id
        tabs = tabs.keep(kept, true)
        val after = DeskTidy.tidyTabs(tabs, turn = 2)
        assertTrue(after.tab(kept) != null, "kept")
        assertTrue(after.showing(WorldAddress.HOME) != null, "the person's own")
        val ais = after.tabs.filter { it.byAi && !it.kept }
        assertEquals(5, ais.size)
        assertEquals((4..8).map { "b$it" }.toSet(), ais.map { (it.parsed as WorldAddress.Board).id }.toSet(), "the newest five")
        // Ai's tabs of the turn under way are never tidied.
        assertEquals(tabs.tabs.size, DeskTidy.tidyTabs(tabs, turn = 1).tabs.size)
    }
}
