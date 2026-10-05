package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeskTest {
    private val area = DeskArea.LAPTOP
    private val files = BuiltInApp.FILES.ref
    private val editor = BuiltInApp.EDITOR.ref
    private val terminal = BuiltInApp.TERMINAL.ref

    private fun Desk.op(op: DeskOp) = reduce(op, area)

    /** The rules every desk keeps, whatever was done to it. */
    private fun Desk.holds() {
        if (front != null) {
            val f = assertNotNull(window(front), "the front window is open")
            assertFalse(f.minimised, "a minimised window is never in front")
            assertEquals(front, visible.last().app, "the front window is on top")
        }
        assertTrue(windows.size <= Desk.MAX_WINDOWS)
        assertEquals(windows.size, windows.map { it.app }.toSet().size, "one window an app")
    }

    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-6, "$a ≠ $b")

    @Test
    fun aFreshDesktopHasNoWindows() {
        val d = DeskCodec.decode(null)
        assertTrue(d.windows.isEmpty())
        assertNull(d.front)
    }

    @Test
    fun openingPutsItInFrontAndOpeningAgainBringsItForward() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.Open(editor, at = 2))
        d.holds()
        assertEquals("editor", d.front)
        d = d.op(DeskOp.Open(files, at = 3))
        assertEquals("files", d.front)
        assertEquals(2, d.windows.size)
        d.holds()
    }

    @Test
    fun aPressBringsAWindowForwardAndTheDesktopLeavesNoneInFront() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.Open(editor, at = 2))
        d = d.op(DeskOp.Focus("files", at = 3))
        assertEquals("files", d.front)
        assertTrue(d.window("files")!!.touched)
        d = d.op(DeskOp.Unfocus)
        assertNull(d.front)
        d.holds()
    }

    @Test
    fun minimisingTheFrontWindowHandsTheFrontOn() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.Open(editor, at = 2))
        d = d.op(DeskOp.Minimise("editor"))
        d.holds()
        assertEquals("files", d.front)
        assertTrue(d.window("editor")!!.minimised)
        // A press cannot bring a minimised window forward; opening it again restores it.
        assertEquals("files", d.op(DeskOp.Focus("editor", at = 3)).front)
        d = d.op(DeskOp.Open(editor, at = 4))
        assertFalse(d.window("editor")!!.minimised)
        assertEquals("editor", d.front)
        d = d.op(DeskOp.Minimise("editor")).op(DeskOp.Minimise("files"))
        assertNull(d.front)
        d.holds()
    }

    @Test
    fun aDragMovesTheWindowByTheHandsDistance() {
        var d = Desk().op(DeskOp.Open(files, at = 1))
        val before = d.window("files")!!.rect(area)
        d = d.op(DeskOp.DragStart("files", before.center, at = 2)).op(DeskOp.Drag("files", 40.0, 25.0))
        val after = d.window("files")!!.rect(area)
        near(before.x + 40, after.x)
        near(before.y + 25, after.y)
        near(before.w, after.w)
    }

    @Test
    fun aDragNeverLosesTheTitleBar() {
        var d = Desk().op(DeskOp.Open(files, at = 1))
        d = d.op(DeskOp.Drag("files", 10_000.0, 10_000.0))
        val r = d.window("files")!!.rect(area)
        assertTrue(r.x < area.full.right)
        assertTrue(r.y < area.full.bottom - 16)
        d = d.op(DeskOp.Drag("files", -20_000.0, -20_000.0))
        val r2 = d.window("files")!!.rect(area)
        assertTrue(r2.right > area.full.x)
        assertTrue(r2.y >= area.full.y)
    }

    @Test
    fun lettingGoAtAnEdgeSnapsAndADragOffRestoresTheFrameItCameFrom() {
        var d = Desk().op(DeskOp.Open(editor, at = 1))
        val normal = d.window("editor")!!.rect(area)
        d = d.op(DeskOp.DragEnd("editor", DeskPoint(area.full.x + 2, 300.0)))
        assertEquals(WindowMode.SNAPPED, d.window("editor")!!.mode)
        assertEquals(SnapZones.frame(Snap.LEFT, area.full), d.window("editor")!!.rect(area))
        // Off the snap: its own size again, under the hand.
        d = d.op(DeskOp.DragStart("editor", DeskPoint(200.0, 10.0), at = 2))
        val back = d.window("editor")!!
        assertEquals(WindowMode.NORMAL, back.mode)
        near(normal.w, back.rect(area).w)
        near(normal.h, back.rect(area).h)
        assertTrue(back.rect(area).contains(DeskPoint(200.0, 10.0 + 1)))
    }

    @Test
    fun theTopEdgeMaximisesAndACornerQuarters() {
        var d = Desk().op(DeskOp.Open(editor, at = 1))
        d = d.op(DeskOp.DragEnd("editor", DeskPoint(600.0, area.full.y + 3)))
        assertEquals(WindowMode.MAXIMISED, d.window("editor")!!.mode)
        assertEquals(area.full, d.window("editor")!!.rect(area))
        d = d.op(DeskOp.DragStart("editor", DeskPoint(600.0, 10.0), at = 2))
        d = d.op(DeskOp.DragEnd("editor", DeskPoint(area.full.right - 1, area.full.bottom - 1)))
        assertEquals(Snap.BOTTOM_RIGHT, d.window("editor")!!.snap)
        assertEquals(SnapZones.frame(Snap.BOTTOM_RIGHT, area.full), d.window("editor")!!.rect(area))
    }

    @Test
    fun resizingHoldsTheSmallestWindow() {
        var d = Desk().op(DeskOp.Open(editor, at = 1))
        d = d.op(DeskOp.Resize("editor", Edge.BOTTOM_RIGHT, -5_000.0, -5_000.0))
        val r = d.window("editor")!!.rect(area)
        near(DeskPlacer.MIN.w, r.w)
        near(DeskPlacer.MIN.h, r.h)
        val r0 = r
        d = d.op(DeskOp.Resize("editor", Edge.LEFT, -50.0, 0.0))
        val r1 = d.window("editor")!!.rect(area)
        near(r0.x - 50, r1.x)
        near(r0.right, r1.right)
    }

    @Test
    fun maximiseTogglesAndRemembersTheFrame() {
        var d = Desk().op(DeskOp.Open(editor, at = 1))
        val normal = d.window("editor")!!.rect(area)
        d = d.op(DeskOp.ToggleMaximise("editor", at = 2))
        assertEquals(area.full, d.window("editor")!!.rect(area))
        d = d.op(DeskOp.ToggleMaximise("editor", at = 3))
        assertEquals(normal, d.window("editor")!!.rect(area))
    }

    @Test
    fun theSnapKeysDoWhatTheTableSays() {
        var d = Desk().op(DeskOp.Open(editor, at = 1))
        d = d.op(DeskOp.SnapKey("editor", DeskOp.Direction.LEFT, at = 2))
        assertEquals(Snap.LEFT, d.window("editor")!!.snap)
        d = d.op(DeskOp.SnapKey("editor", DeskOp.Direction.RIGHT, at = 3))
        assertEquals(WindowMode.NORMAL, d.window("editor")!!.mode, "from the left half, → restores")
        d = d.op(DeskOp.SnapKey("editor", DeskOp.Direction.UP, at = 4))
        assertEquals(WindowMode.MAXIMISED, d.window("editor")!!.mode)
        d = d.op(DeskOp.SnapKey("editor", DeskOp.Direction.DOWN, at = 5))
        assertEquals(WindowMode.NORMAL, d.window("editor")!!.mode)
        d = d.op(DeskOp.SnapKey("editor", DeskOp.Direction.DOWN, at = 6))
        assertTrue(d.window("editor")!!.minimised)
        d.holds()
    }

    @Test
    fun closingHandsTheFrontOnAndIsRememberedDuringATurn() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.Open(editor, at = 2))
        d = d.op(DeskOp.TurnStart(at = 3)).op(DeskOp.Close("editor"))
        assertEquals("files", d.front)
        assertTrue("editor" in d.closedThisTurn)
        d.holds()
        // Outside a turn a close is not remembered.
        val e = Desk().op(DeskOp.Open(editor, at = 1)).op(DeskOp.Close("editor"))
        assertTrue(e.closedThisTurn.isEmpty())
    }

    @Test
    fun twelveWindowsAtMostAndTheThirteenthClosesTheLeastRecentlyUsed() {
        var d = Desk()
        // Eleven of Ai's apps would pass its own eight: open built-ins and a few apps, the oldest kept.
        val refs = BuiltInApp.entries.map { it.ref } + (1..5).map { AppRef.Made("a$it") }
        refs.forEachIndexed { i, r -> d = d.op(DeskOp.Open(r, at = 10L + i)) }
        assertEquals(12, d.windows.size)
        d = d.op(DeskOp.Keep("files", true))
        val step = d.step(DeskOp.Open(AppRef.Made("a6"), at = 100), area)
        step.desk.holds()
        assertEquals(12, step.desk.windows.size)
        // Files is the oldest but kept: the editor, next oldest, goes.
        assertEquals(listOf("editor"), step.evicted)
        assertTrue(step.desk.isOpen("files"))
    }

    @Test
    fun atMostEightOfAisAppsAreOpen() {
        var d = Desk()
        (1..8).forEach { d = d.op(DeskOp.Open(AppRef.Made("app$it"), at = it.toLong())) }
        val step = d.step(DeskOp.Open(AppRef.Made("app9"), at = 50), area)
        assertEquals(listOf("app:app1"), step.evicted)
        assertEquals(8, step.desk.windows.count { it.ref is AppRef.Made })
    }

    @Test
    fun theSwitcherStepsThroughTheMostRecent() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.Open(editor, at = 2)).op(DeskOp.Open(terminal, at = 3))
        d = d.op(DeskOp.Cycle(forward = true, at = 4))
        assertEquals("editor", d.front)
        d = d.op(DeskOp.Cycle(forward = true, at = 5))
        assertEquals("terminal", d.front, "one press each way swaps the two most recent")
        d = d.op(DeskOp.Minimise("terminal")).op(DeskOp.Cycle(forward = true, at = 6))
        d.holds()
    }

    @Test
    fun showDesktopAndPutAway() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.Open(editor, at = 2)).op(DeskOp.Keep("files", true))
        val shown = d.op(DeskOp.MinimiseAll)
        assertNull(shown.front)
        assertTrue(shown.windows.all { it.minimised })
        d = d.op(DeskOp.PutAway)
        assertEquals(listOf("files"), d.windows.map { it.app })
        d.holds()
    }

    @Test
    fun anArrivalBehindOpensUnderTheFrontWindow() {
        var d = Desk().op(DeskOp.Open(files, at = 1)).op(DeskOp.TurnStart(2))
        d = d.op(DeskOp.Arrive(terminal, FocusDecision.BEHIND, at = 3))
        assertEquals("files", d.front)
        assertEquals(listOf("terminal", "files"), d.windows.map { it.app })
        assertEquals("terminal", d.ai)
        assertEquals(WorldEvent.AI, d.window("terminal")!!.by)
        assertFalse(d.window("terminal")!!.touched)
        d = d.op(DeskOp.Arrive(editor, FocusDecision.MARK, at = 4))
        assertFalse(d.isOpen("editor"), "a mark opens nothing")
        assertEquals("editor", d.ai)
        d = d.op(DeskOp.Arrive(editor, FocusDecision.RAISE, at = 5))
        assertEquals("editor", d.front)
        d.holds()
    }

    @Test
    fun iconsMoveToCellsAndSwap() {
        var d = Desk().op(DeskOp.MoveIcon("files", 0, 0)).op(DeskOp.MoveIcon("editor", 0, 1))
        d = d.op(DeskOp.MoveIcon("files", 0, 1))
        assertEquals(IconCell("files", 0, 1), d.icons.first { it.app == "files" })
        assertEquals(IconCell("editor", 0, 0), d.icons.first { it.app == "editor" })
    }

    @Test
    fun aRandomWalkKeepsEveryRule() {
        val rnd = kotlin.random.Random(7)
        val apps = BuiltInApp.entries.map { it.ref } + (1..10).map { AppRef.Made("m$it") }
        var d = Desk()
        repeat(3_000) { t ->
            val a = apps[rnd.nextInt(apps.size)]
            val op = when (rnd.nextInt(14)) {
                0, 1, 2 -> DeskOp.Open(a, at = t.toLong(), by = if (rnd.nextBoolean()) WorldEvent.AI else WorldEvent.YOU, behind = rnd.nextInt(4) == 0)
                3 -> DeskOp.Focus(a.key, at = t.toLong())
                4 -> DeskOp.Minimise(a.key)
                5 -> DeskOp.Close(a.key)
                6 -> DeskOp.ToggleMaximise(a.key, t.toLong())
                7 -> DeskOp.DragEnd(a.key, DeskPoint(rnd.nextDouble() * 1400, rnd.nextDouble() * 700))
                8 -> DeskOp.Cycle(rnd.nextBoolean(), t.toLong())
                9 -> DeskOp.Unfocus
                10 -> DeskOp.Arrive(a, FocusDecision.entries[rnd.nextInt(3)], t.toLong())
                11 -> if (rnd.nextBoolean()) DeskOp.TurnStart(t.toLong()) else DeskOp.TurnEnd(t.toLong())
                12 -> DeskOp.SnapKey(a.key, DeskOp.Direction.entries[rnd.nextInt(4)], t.toLong())
                else -> DeskOp.Resize(a.key, Edge.entries[rnd.nextInt(8)], rnd.nextDouble() * 400 - 200, rnd.nextDouble() * 400 - 200)
            }
            d = d.op(op)
            d.holds()
            d.windows.forEach { w ->
                val r = w.rect(area)
                assertTrue(r.w >= DeskPlacer.MIN.w - 1e-6 && r.h >= DeskPlacer.MIN.h * 0.99 - 1e-6 || w.mode != WindowMode.NORMAL, "$w is too small: $r")
            }
        }
    }
}
