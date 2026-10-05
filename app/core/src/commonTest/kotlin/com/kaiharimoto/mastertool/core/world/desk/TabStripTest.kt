package com.kaiharimoto.mastertool.core.world.desk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabStripTest {
    @Test
    fun aFewTabsShareTheRoomUpToTheirWidest() {
        val f = TabStrip.fit(1160.0, 3)
        assertEquals(TabStrip.MAX_W, f.width)
        assertEquals(3, f.shown)
        assertFalse(f.overflows)
    }

    @Test
    fun moreTabsShrinkButNeverPastAReadableTitle() {
        val f = TabStrip.fit(1160.0, 6)
        assertEquals((1160.0 - TabStrip.NEW_W) / 6, f.width, 1e-9)
        assertTrue(f.width >= TabStrip.MIN_W)
        assertFalse(f.overflows)
    }

    @Test
    fun pastTheRoomWholeTabsFillItAndTheRestAreCounted() {
        // The studio's twelve tabs in a 1160 dp window: 96 dp each was a glyph and a square.
        val f = TabStrip.fit(1160.0, 12)
        assertTrue(f.width >= TabStrip.MIN_W, "a title has room: ${f.width}")
        assertEquals(12, f.shown + f.hidden)
        assertTrue(f.overflows)
        // Whole tabs, the + and the count fill the strip exactly: no tab is cut at the edge.
        assertEquals(1160.0, f.shown * f.width + TabStrip.NEW_W + TabStrip.LIST_W, 1e-9)
    }

    @Test
    fun aNarrowWindowStillShowsOneTab() {
        val f = TabStrip.fit(240.0, 5)
        assertEquals(1, f.shown)
        assertEquals(4, f.hidden)
        assertTrue(f.width > 0)
        assertEquals(TabStrip.Fit(TabStrip.MAX_W, 0, 0), TabStrip.fit(800.0, 0))
    }

    @Test
    fun theWindowFollowsTheSelectedTab() {
        assertEquals(0, TabStrip.follow(first = 0, selected = 3, shown = 6, count = 12), "in view: kept")
        assertEquals(4, TabStrip.follow(first = 0, selected = 9, shown = 6, count = 12), "past the end: the least move")
        assertEquals(2, TabStrip.follow(first = 5, selected = 2, shown = 6, count = 12), "before the start")
        assertEquals(6, TabStrip.follow(first = 9, selected = -1, shown = 6, count = 12), "held inside after tabs close")
        assertEquals(0, TabStrip.follow(first = 3, selected = 1, shown = 6, count = 4), "everything fits")
    }
}
