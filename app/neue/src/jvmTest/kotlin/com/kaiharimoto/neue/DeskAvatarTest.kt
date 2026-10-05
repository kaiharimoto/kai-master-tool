package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.AiNow
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AvatarStatus
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.Desk
import com.kaiharimoto.mastertool.core.world.desk.DeskArea
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.neue.world.desk.DeskAvatarState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The avatar's loop, a frame at a time without a window (`DeskAvatar.kt`, `DESKTOP.md` §5.3–§5.4): it goes where Ai
 * works, waits out a dwell without frames, moves on when the next route comes, and stops asking once settled.
 */
class DeskAvatarTest {
    private val area = DeskArea.LAPTOP

    /** Drives the loop as `travel` does: a frame each 16 ms, a wait skipped over, out when it says so. Returns frames run. */
    private fun run(a: DeskAvatarState, from: Long, until: Long): Pair<Long, Int> {
        var now = from
        var frames = 0
        while (now < until) {
            val r = a.frame(now)
            frames++
            if (r < 0) return now to frames
            now += if (r > 0) minOf(r, 120L) else 16L
        }
        return now to frames
    }

    private fun opened(d: Desk, app: BuiltInApp) = d.reduce(DeskOp.Arrive(app.ref, com.kaiharimoto.mastertool.core.world.desk.FocusDecision.RAISE, 0L), area)

    @Test
    fun itGoesWhereAiWorksAndOnToTheNextAppWhenTheFirstHasGone() {
        val a = DeskAvatarState()
        a.report(null, Anchor.HOME, DeskRect(1200.0, 640.0, 28.0, 28.0))
        a.report(BuiltInApp.TERMINAL.id, Anchor.CELL, DeskRect(100.0, 640.0, 44.0, 44.0))
        a.report(BuiltInApp.EDITOR.id, Anchor.CELL, DeskRect(144.0, 640.0, 44.0, 44.0))
        var desk = Desk().reduce(DeskOp.TurnStart(0L), area)
        a.on(AiDoes.TurnStart, desk)
        a.on(AiDoes.Run("hands.js"), desk)
        desk = opened(desk, BuiltInApp.TERMINAL)
        a.report(BuiltInApp.TERMINAL.id, Anchor.TITLE, DeskRect(300.0, 100.0, 600.0, 32.0))
        a.report(BuiltInApp.TERMINAL.id, Anchor.LINE, DeskRect(320.0, 300.0, 200.0, 20.0))
        var (now, _) = run(a, 0L, 4_000L)
        assertEquals(BuiltInApp.TERMINAL.id, a.at)
        // The Terminal goes off screen (a phone shows the Editor now); Ai writes.
        a.forget(BuiltInApp.TERMINAL.id)
        a.on(AiDoes.Write("a.js"), desk)
        desk = opened(desk, BuiltInApp.EDITOR)
        a.report(BuiltInApp.EDITOR.id, Anchor.TITLE, DeskRect(0.0, 0.0, 360.0, 32.0))
        a.report(BuiltInApp.EDITOR.id, Anchor.CARET, DeskRect(120.0, 60.0, 2.0, 20.0))
        now = run(a, now, now + 6_000L).first
        assertEquals(BuiltInApp.EDITOR.id, a.at, a.describe())
        assertEquals("Writing a.js", a.status)
        // Settled at the caret: the loop says it has nothing more and asks for no frames.
        assertTrue(a.frame(now + 2_000L) < 0 || a.frame(now + 4_000L) < 0, a.describe())
    }

    @Test
    fun itSaysWhatItWentToDoHowTheRunWentAndNothingAtHome() {
        val a = DeskAvatarState()
        a.report(null, Anchor.HOME, DeskRect(1200.0, 640.0, 28.0, 28.0))
        a.report(BuiltInApp.TERMINAL.id, Anchor.CELL, DeskRect(100.0, 640.0, 44.0, 44.0))
        val desk = Desk().reduce(DeskOp.TurnStart(0L), area)
        a.on(AiDoes.TurnStart, desk)
        assertTrue(a.home, "waking at home: the taskbar's line speaks")
        a.on(AiDoes.Run("hands.js"), desk)
        var now = run(a, 0L, 3_000L).first
        assertEquals("Running hands.js", a.doing?.text, a.describe())
        assertFalse(a.home)
        val ai = AiNow(running = true, tool = "world_run")
        assertEquals(AvatarStatus.Kind.RUNNING, AvatarStatus.resolve(a.doing, a.doingSince, a.outcome, a.outcomeAt, ai, a.doingSince + 1)?.kind)
        // The run fails: worried, where it stands, until it goes to do something new.
        a.ran("hands.js", ok = false, error = "ReferenceError: x", at = a.doingSince + 5)
        val failed = AvatarStatus.resolve(a.doing, a.doingSince, a.outcome, a.outcomeAt, AiNow(running = true), a.outcomeAt + 10)
        assertEquals(AvatarStatus.Kind.FAILED, failed?.kind)
        assertEquals(com.kaiharimoto.mastertool.core.ai.avatar.Expression.OOPS, failed?.expression)
        // The turn ends: Done never hides the failure; then home, where it says nothing.
        a.on(AiDoes.TurnEnd, desk)
        now = run(a, now, now + 10_000L).first
        assertTrue(a.asleep && a.home, a.describe())
        assertEquals(
            AvatarStatus.Plate.NONE,
            AvatarStatus.plate(AvatarStatus.resolve(a.doing, a.doingSince, a.outcome, a.outcomeAt, AiNow(), now), avatarOn = true, asleep = a.asleep, atHome = a.home, recede = true, personTookOver = false),
        )
    }

    @Test
    fun theTurnsEndSendsItHomeToSleep() {
        val a = DeskAvatarState()
        a.report(null, Anchor.HOME, DeskRect(1200.0, 640.0, 28.0, 28.0))
        a.report(BuiltInApp.TERMINAL.id, Anchor.CELL, DeskRect(100.0, 640.0, 44.0, 44.0))
        val desk = Desk().reduce(DeskOp.TurnStart(0L), area)
        a.on(AiDoes.TurnStart, desk)
        a.on(AiDoes.Run("x.js"), desk)
        val now = run(a, 0L, 3_000L).first
        a.on(AiDoes.TurnEnd, desk)
        run(a, now, now + 10_000L)
        assertTrue(a.asleep, a.describe())
    }
}
