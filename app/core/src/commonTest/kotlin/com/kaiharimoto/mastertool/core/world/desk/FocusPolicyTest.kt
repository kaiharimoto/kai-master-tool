package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldPrefs
import kotlin.test.Test
import kotlin.test.assertEquals

class FocusPolicyTest {
    private val on = WorldPrefs(follow = true)
    private val off = WorldPrefs(follow = false)
    private val terminal = FocusArrival("terminal")
    private val now = 100_000L

    @Test
    fun withTheHandsStillAiComesForward() {
        assertEquals(FocusDecision.RAISE, FocusPolicy.decide(terminal, PersonState(now = now), on))
        assertEquals(FocusDecision.RAISE, FocusPolicy.decide(terminal, PersonState(lastInput = now - 4_000, now = now), on))
    }

    @Test
    fun neverWhileTyping() {
        assertEquals(FocusDecision.BEHIND, FocusPolicy.decide(terminal, PersonState(typing = true, now = now), on))
        // Typing an hour after the last press is still typing.
        assertEquals(FocusDecision.BEHIND, FocusPolicy.decide(terminal, PersonState(typing = true, lastInput = now - 3_600_000, now = now), on))
    }

    @Test
    fun neverWithinFourSecondsOfThePersonsInput() {
        for (ago in listOf(0L, 1L, 1_000L, 3_999L)) {
            assertEquals(FocusDecision.BEHIND, FocusPolicy.decide(terminal, PersonState(lastInput = now - ago, now = now), on), "$ago ms ago")
        }
        assertEquals(4_000L, FocusPolicy.QUIET_MS)
    }

    @Test
    fun neverOverAMenuTheLauncherOrADialog() {
        assertEquals(FocusDecision.BEHIND, FocusPolicy.decide(terminal, PersonState(overlay = true, now = now), on))
    }

    @Test
    fun onlyAMarkWithFollowOffOrAWindowClosedThisTurn() {
        assertEquals(FocusDecision.MARK, FocusPolicy.decide(terminal, PersonState(now = now), off))
        assertEquals(FocusDecision.MARK, FocusPolicy.decide(terminal.copy(closedThisTurn = true), PersonState(now = now), on))
        // A closed window wins over a busy person: it is never reopened, behind or not.
        assertEquals(FocusDecision.MARK, FocusPolicy.decide(terminal.copy(closedThisTurn = true), PersonState(typing = true, now = now), on))
    }

    @Test
    fun noDeferredRaise() {
        // Behind while the person types; the next arrival, once they stop, is judged afresh — and nothing in between
        // raised the first. The policy keeps no memory, so the desk holds no pending raise.
        var d = Desk().reduce(DeskOp.Open(BuiltInApp.EDITOR.ref, at = 1), DeskArea.LAPTOP).reduce(DeskOp.TurnStart(2), DeskArea.LAPTOP)
        val first = FocusPolicy.decide(FocusArrival("terminal", "terminal" in d.closedThisTurn), PersonState(typing = true, now = 3), on)
        d = d.reduce(DeskOp.Arrive(BuiltInApp.TERMINAL.ref, first, 3), DeskArea.LAPTOP)
        assertEquals("editor", d.front)
        // Time passes, the person stops typing; with no new arrival nothing changes.
        assertEquals("editor", d.front)
        val second = FocusPolicy.decide(FocusArrival("terminal"), PersonState(lastInput = 3, now = 10_000), on)
        assertEquals(FocusDecision.RAISE, second)
        d = d.reduce(DeskOp.Arrive(BuiltInApp.TERMINAL.ref, second, 10_000), DeskArea.LAPTOP)
        assertEquals("terminal", d.front)
    }
}
