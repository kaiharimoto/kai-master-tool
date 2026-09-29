package com.kaiharimoto.mastertool.core.layout

import com.kaiharimoto.mastertool.core.layout.PaneBudget.DECK_FLOOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaneBudgetTest {

    private fun touch(window: Float = 1280f, groups: Boolean = false, pool: Boolean = true, inspector: Boolean = true) =
        PaneBudget.solve(window, touch = true, railOut = true, groupsOn = groups, poolVisible = pool, inspectorVisible = inspector, poolPref = 440f, inspectorPref = 400f)

    @Test
    fun theDeskIsWhatItWas() {
        // 1920 wide, rail pinned: today's widths, untouched.
        val p = PaneBudget.solve(1920f, touch = false, railOut = true, groupsOn = false, poolVisible = true, inspectorVisible = true, poolPref = 440f, inspectorPref = 400f)
        assertEquals(232f, p.rail)
        assertEquals(440f, p.pool)
        assertEquals(400f, p.inspector)
        assertEquals(1920f - 232f - 440f - 400f - 14f, p.deck)
        // With Groups on the desk keeps all three panes: the panel stands in the deck's row.
        val g = PaneBudget.solve(1920f, touch = false, railOut = true, groupsOn = true, poolVisible = true, inspectorVisible = true, poolPref = 440f, inspectorPref = 400f)
        assertEquals(400f, g.inspector)
        assertEquals(p.deck - PaneBudget.GROUPS, g.deck)
    }

    @Test
    fun theTabletDeckIsNoLongerAStrip() {
        val p = touch()
        assertEquals(56f, p.rail)
        assertEquals(320f, p.pool)
        assertEquals(320f, p.inspector)
        assertEquals(1280f - 56f - 320f - 320f - 14f, p.deck) // 570, against 194 before
        assertTrue(p.deck >= DECK_FLOOR)
    }

    @Test
    fun groupsTakeTheInspectorsPlaceOnTouch() {
        val p = touch(groups = true)
        assertEquals(0f, p.inspector)
        assertTrue(p.inspectorYielded)
        assertEquals(PaneBudget.GROUPS, p.groups)
        assertEquals(1280f - 56f - 320f - 7f - 288f, p.deck) // 609
        assertTrue(p.deck >= DECK_FLOOR)
    }

    @Test
    fun theFloorHoldsOnASmallerTablet() {
        // An 11-inch at 1024dp: the inspector yields, then the pool narrows.
        val p = touch(window = 1024f)
        assertEquals(0f, p.inspector)
        assertTrue(p.deck >= DECK_FLOOR)
        assertTrue(p.pool >= PaneBudget.POOL_MIN)
        val g = touch(window = 1024f, groups = true)
        assertTrue(g.pool >= PaneBudget.POOL_MIN)
        // At 1024 with Groups on the floor cannot be met in full; the pool gives all it can.
        assertEquals(PaneBudget.POOL_MIN, g.pool)
    }

    @Test
    fun hiddenPanesGiveTheirRoomAndTheirRule() {
        val p = touch(pool = false, inspector = false)
        assertEquals(1280f - 56f, p.deck)
        assertEquals(false, p.inspectorYielded)
    }

    private fun phone(window: Float, pool: Boolean = true, rail: Boolean = true) =
        PaneBudget.solve(window, touch = true, railOut = rail, groupsOn = true, poolVisible = pool, inspectorVisible = true, poolPref = 440f, inspectorPref = 400f, phone = true)

    @Test
    fun aPhoneLyingDownHasNoInspectorAndNoFloor() {
        for (w in 640..960 step 40) {
            val p = phone(w.toFloat())
            assertEquals(0f, p.inspector, "at $w")
            assertEquals(0f, p.groups, "at $w: groups are a sheet on a phone")
            assertEquals(PaneBudget.TOUCH_RAIL, p.rail)
            assertTrue(p.pool >= PaneBudget.PHONE_POOL_MIN || p.deck <= PaneBudget.PHONE_DECK_MIN + 0.01f, "at $w")
            assertTrue(p.deck >= PaneBudget.PHONE_DECK_MIN, "at $w the deck is ${p.deck}")
            assertEquals(w.toFloat(), p.rail + p.pool + PaneBudget.RULE + p.deck, 0.01f)
        }
        // A 915dp phone: the pool takes 40% of what the strip leaves.
        val p = phone(915f)
        assertEquals((915f - 56f) * 0.4f, p.pool, 0.01f)
        // The pool hidden, the deck has it all.
        assertEquals(915f - 56f, phone(915f, pool = false).deck, 0.01f)
    }

    @Test
    fun thePhoneBranchLeavesTheTabletAlone() {
        assertEquals(touch(), PaneBudget.solve(1280f, touch = true, railOut = true, groupsOn = false, poolVisible = true, inspectorVisible = true, poolPref = 440f, inspectorPref = 400f, phone = false))
    }
}
