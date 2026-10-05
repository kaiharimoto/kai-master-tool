package com.kaiharimoto.mastertool.core.world.desk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarPilotTest {
    private val area = DeskArea.LAPTOP
    private val empty = Desk()
    private val withEditor = Desk().reduce(DeskOp.Open(BuiltInApp.EDITOR.ref, at = 1), area)

    private fun anchors(r: List<AvatarTarget>) = r.map { it.anchor to it.app }

    @Test
    fun aWriteWithTheEditorClosedGoesByItsIconToTheCaret() {
        val r = AvatarPilot.route(AiDoes.Write("lib/openings.js"), empty)
        assertEquals(listOf(Anchor.LAUNCH to "editor", Anchor.TITLE to "editor", Anchor.CARET to "editor"), anchors(r))
        assertTrue(r[0].press, "a press opens it")
        assertTrue(r[2].follow, "it rides the caret")
        assertTrue(r.all { it.status == "Writing openings.js" })
    }

    @Test
    fun aWriteWithTheEditorOpenGoesStraightToItsTitleAndCaret() {
        assertEquals(listOf(Anchor.TITLE to "editor", Anchor.CARET to "editor"), anchors(AvatarPilot.route(AiDoes.Write("a.js"), withEditor)))
    }

    @Test
    fun eachToolsRoute() {
        assertEquals(listOf(Anchor.LAUNCH to "terminal", Anchor.TITLE to "terminal", Anchor.LINE to "terminal"), anchors(AvatarPilot.route(AiDoes.Run("openings.js"), empty)))
        assertEquals("Running openings", AvatarPilot.route(AiDoes.Tool("openings"), empty).last().status)
        val show = AvatarPilot.route(AiDoes.Show("t3"), empty)
        assertEquals(listOf(Anchor.LAUNCH, Anchor.TITLE, Anchor.TABS, Anchor.TAB, Anchor.HEAD), show.map { it.anchor })
        assertEquals("t3", show[3].key)
        assertTrue(show[3].press)
        val made = AvatarPilot.route(AiDoes.MakeApp("hand-odds", "Hand odds"), empty)
        assertEquals(listOf(Anchor.ICON to "app:hand-odds", Anchor.TITLE to "app:hand-odds"), anchors(made))
        assertTrue(made[0].press)
        val press = AvatarPilot.route(AiDoes.Press("hand-odds", "Hand odds", "draw"), empty)
        assertEquals(Anchor.WIDGET, press.last().anchor)
        assertEquals("draw", press.last().key)
        assertEquals(Anchor.BODY, AvatarPilot.route(AiDoes.Read(BuiltInApp.LIBRARY.ref, "the guide"), empty).last().anchor)
        assertEquals("Reading the guide", AvatarPilot.route(AiDoes.Read(BuiltInApp.LIBRARY.ref, "the guide"), empty).last().status)
        val q = AvatarPilot.route(AiDoes.Question, empty)
        assertEquals(Anchor.COMPOSER, q.last().anchor)
        assertEquals(AvatarPilot.WAITING, q.last().status)
        assertEquals(listOf(Anchor.HOME), AvatarPilot.route(AiDoes.TurnStart, empty).map { it.anchor })
        val end = AvatarPilot.route(AiDoes.TurnEnd, empty)
        assertEquals(listOf(Anchor.HERE, Anchor.HOME), end.map { it.anchor })
        assertEquals(AvatarPilot.DONE_MS, end[0].dwell)
    }

    @Test
    fun aWindowThatWillNotComeForwardIsStoodOnAtItsCell() {
        for (d in listOf(FocusDecision.BEHIND, FocusDecision.MARK)) {
            assertEquals(listOf(Anchor.CELL to "terminal"), anchors(AvatarPilot.route(AiDoes.Run("x.js"), empty, d)))
        }
    }

    @Test
    fun theWaitBeforeAWindowOpensIsOnlyWithFollowAndTheAvatarOn() {
        assertEquals(700L, AvatarPilot.arriveWait(follow = true, shown = true))
        assertEquals(0L, AvatarPilot.arriveWait(follow = false, shown = true))
        assertEquals(0L, AvatarPilot.arriveWait(follow = true, shown = false))
        assertEquals(0L, AvatarPilot.arriveWait(follow = false, shown = false))
    }

    @Test
    fun theQueueDwellsThenMovesOn() {
        val p = AvatarPilot()
        assertTrue(p.asleep)
        assertNull(p.next(0, arrived = true))
        p.on(AiDoes.TurnStart, empty)
        assertEquals(Anchor.HOME, p.current!!.anchor)
        assertEquals(AvatarPilot.WAKING, p.status)
        p.on(AiDoes.Run("x.js"), empty)
        // Arrived at home; waits its beat there, then on.
        assertEquals(Anchor.HOME, p.next(10, arrived = true)!!.anchor)
        assertEquals(Anchor.LAUNCH, p.next(10 + AvatarPilot.PASS_MS, arrived = true)!!.anchor)
        assertEquals("Running x.js", p.status)
        // Not there yet: it keeps heading for it.
        assertEquals(Anchor.LAUNCH, p.next(400, arrived = false)!!.anchor)
        assertEquals(Anchor.LAUNCH, p.next(500, arrived = true)!!.anchor)
        assertEquals(Anchor.TITLE, p.next(500 + AvatarPilot.PASS_MS, arrived = true)!!.anchor)
        assertEquals(Anchor.TITLE, p.next(700, arrived = true)!!.anchor)
        assertEquals(Anchor.LINE, p.next(700 + AvatarPilot.PASS_MS, arrived = true)!!.anchor)
        // The line is followed for as long as nothing is next.
        assertEquals(Anchor.LINE, p.next(10_000, arrived = true)!!.anchor)
        p.on(AiDoes.TurnEnd, empty)
        assertEquals(Anchor.HERE, p.next(10_000 + AvatarPilot.DWELL_MS, arrived = true)!!.anchor)
        assertEquals(AvatarPilot.DONE, p.status)
        assertEquals(Anchor.HERE, p.next(11_000, arrived = true)!!.anchor, "Done stands where it is")
        assertEquals(Anchor.HOME, p.next(11_000 + AvatarPilot.DONE_MS, arrived = true)!!.anchor)
        p.next(20_000, arrived = true)
        p.next(20_000 + AvatarPilot.DWELL_MS, arrived = true)
        assertTrue(p.asleep)
        assertEquals(AvatarPilot.ASLEEP, p.status)
    }

    @Test
    fun neverMoreThanAHopBehind() {
        val p = AvatarPilot()
        p.on(AiDoes.Write("a.js"), empty)
        p.on(AiDoes.Run("a.js"), withEditor)
        assertEquals(1, p.behind)
        p.on(AiDoes.Show("t1"), withEditor)
        // Two were waiting: only the newest is kept, and only its end.
        assertEquals(1, p.behind)
        p.next(0, arrived = true)
        val n = p.next(AvatarPilot.DWELL_MS, arrived = true)!!
        assertEquals(Anchor.HEAD, n.anchor)
    }

    @Test
    fun skipDropsWhatWaits() {
        val p = AvatarPilot()
        p.on(AiDoes.Write("a.js"), empty)
        p.on(AiDoes.Run("a.js"), withEditor)
        p.skip()
        assertEquals(0, p.behind)
        p.next(0, arrived = true)
        assertEquals(Anchor.LINE, p.next(AvatarPilot.DWELL_MS, arrived = true)!!.anchor, "straight to the newest's end")
    }

    @Test
    fun targetsResolveToWhereTheAvatarStands() {
        val t = AvatarTargets()
        t.report("editor", Anchor.CARET, DeskRect(300.0, 200.0, 2.0, 18.0))
        val p = t.point(AvatarTarget(Anchor.CARET, "editor"), DeskPoint.ZERO, size = 28.0)!!
        assertEquals(302.0 + AvatarTargets.CARET_GAP + 14, p.x, 1e-9)
        assertEquals(209.0, p.y, 1e-9)
        // The icon or the cell, whichever is nearer.
        t.report("terminal", Anchor.ICON, DeskRect(16.0, 100.0, 32.0, 32.0))
        t.report("terminal", Anchor.CELL, DeskRect(600.0, 700.0, 44.0, 44.0))
        assertEquals(DeskPoint(32.0, 116.0), t.point(AvatarTarget(Anchor.LAUNCH, "terminal"), DeskPoint(0.0, 0.0)))
        assertEquals(622.0, t.point(AvatarTarget(Anchor.LAUNCH, "terminal"), DeskPoint(700.0, 700.0))!!.x, 1e-9)
        t.forget("terminal")
        assertTrue(t.rect("terminal", Anchor.ICON) != null, "an icon outlives its window")
        assertNull(t.point(AvatarTarget(Anchor.TITLE, "nowhere"), DeskPoint.ZERO))
    }
}
