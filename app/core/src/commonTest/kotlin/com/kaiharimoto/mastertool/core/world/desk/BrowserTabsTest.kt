package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTabsTest {
    private val home = WorldAddress.HOME

    @Test
    fun backAndForward() {
        var t = BrowserTabs().open(home, at = 1)
        val id = t.selected!!
        t = t.go(id, "world://boards/a").go(id, "world://boards/b")
        assertEquals("world://boards/b", t.current!!.address)
        t = t.back(id)
        assertEquals("world://boards/a", t.current!!.address)
        t = t.back(id).back(id)
        assertEquals(home, t.current!!.address, "back past the start stays put")
        t = t.forward(id).forward(id)
        assertEquals("world://boards/b", t.current!!.address)
        // A new page clears forward.
        t = t.back(id).go(id, "world://boards/c")
        assertTrue(t.current!!.forward.isEmpty())
        assertEquals(listOf(home, "world://boards/a"), t.current!!.back)
    }

    @Test
    fun historyIsFiftyDeep() {
        var t = BrowserTabs().open(home, at = 1)
        val id = t.selected!!
        repeat(80) { t = t.go(id, "world://boards/b$it") }
        assertEquals(BrowserTabs.MAX_HISTORY, t.current!!.back.size)
        assertEquals("world://boards/b29", t.current!!.back.first())
    }

    @Test
    fun aBoardPinnedAgainUpdatesItsTabInPlace() {
        var t = BrowserTabs().open(home, at = 1)
        t = t.show("starter", at = 2, raise = true, turn = 1)
        assertEquals(2, t.tabs.size)
        assertEquals("world://boards/starter", t.current!!.address)
        t = t.select(t.tabs.first().id)
        t = t.show("starter", at = 3, raise = false, turn = 1)
        assertEquals(2, t.tabs.size, "no new tab")
        assertTrue(t.showing("world://boards/starter")!!.mark, "changed while not selected: marked")
        t = t.show("starter", at = 4, raise = true, turn = 1)
        assertEquals("world://boards/starter", t.current!!.address)
        assertFalse(t.current!!.mark, "selected, the mark goes")
    }

    @Test
    fun aiOpensBesideTheSelectedOneUnselectedUnlessRaised() {
        var t = BrowserTabs().open(home, at = 1).open("world://boards/x", at = 2)
        val first = t.tabs[0].id
        t = t.select(first).show("new", at = 3, raise = false)
        assertEquals(first, t.selected)
        assertEquals(listOf(home, "world://boards/new", "world://boards/x"), t.tabs.map { it.address })
        assertTrue(t.tabs[1].mark)
        assertTrue(t.tabs[1].byAi)
    }

    @Test
    fun aRunsPagesStandTogetherInTheOrderMade() {
        var t = BrowserTabs().open(home, at = 1).open("world://boards/x", at = 2)
        t = t.select(t.tabs[0].id)
        listOf("a", "b", "c").forEachIndexed { i, b -> t = t.show(b, at = 10L + i, raise = false, group = "run:openings.js@10") }
        assertEquals(listOf(home, "world://boards/a", "world://boards/b", "world://boards/c", "world://boards/x"), t.tabs.map { it.address })
        // A later page of the same run joins its group even with another tab selected.
        t = t.select(t.tabs.last().id).show("d", at = 20, raise = false, group = "run:openings.js@10")
        assertEquals("world://boards/d", t.tabs[4].address)
        assertTrue(t.startsGroup(1), "a divider before the run's pages")
        assertFalse(t.startsGroup(2), "none inside them")
        assertTrue(t.startsGroup(5), "and one after")
        assertFalse(t.startsGroup(0))
    }

    @Test
    fun ctrlNumberJumpsToATabAndNineToTheLast() {
        var t = BrowserTabs().open(home, at = 1)
        listOf("a", "b", "c").forEach { t = t.open("world://boards/$it", at = 2) }
        assertEquals(t.tabs[1].id, t.jump(2).selected)
        assertEquals(t.tabs.last().id, t.jump(9).selected)
        assertEquals(t.selected, t.jump(7).selected, "past the tabs there are: nothing")
    }

    @Test
    fun aTabThePersonOpensIsNeverMarked() {
        var t = BrowserTabs().open(home, at = 1)
        t = t.open("world://boards/a", at = 2, by = WorldEvent.YOU, select = false)
        assertFalse(t.showing("world://boards/a")!!.mark, "the person's own tab is not news to them")
        // An instrument the person ran opens its pages the same way: unmarked.
        t = t.show("b", at = 3, raise = false, by = WorldEvent.YOU)
        assertFalse(t.showing("world://boards/b")!!.mark)
        // Ai's, beside the one being read, is.
        t = t.show("c", at = 4, raise = false, by = WorldEvent.AI)
        assertTrue(t.showing("world://boards/c")!!.mark)
    }

    @Test
    fun twentyTabsAndTheOldestOfAisGoesFirst() {
        var t = BrowserTabs().open(home, at = 0, by = WorldEvent.YOU)
        for (i in 1..19) t = t.show("b$i", at = i.toLong(), raise = false, turn = 1)
        assertEquals(20, t.tabs.size)
        t = t.keep(t.showing("world://boards/b1")!!.id, true)
        t = t.show("b20", at = 20, raise = false, turn = 2)
        assertEquals(BrowserTabs.MAX_TABS, t.tabs.size)
        assertNull(t.showing("world://boards/b2"), "Ai's oldest unkept tab closed")
        assertTrue(t.showing("world://boards/b1") != null, "kept")
        assertTrue(t.showing(home) != null, "the person's")
        assertTrue(t.showing("world://boards/b20") != null)
    }

    @Test
    fun closeSelectsANeighbourAndIdsAreNeverReused() {
        var t = BrowserTabs().open(home, at = 1).open("world://boards/a", at = 2).open("world://boards/b", at = 3)
        val ids = t.tabs.map { it.id }
        t = t.select(ids[1]).close(ids[1])
        assertEquals(ids[2], t.selected)
        t = t.close(ids[2])
        assertEquals(ids[0], t.selected)
        t = t.open(home, at = 4)
        assertFalse(t.selected in ids, "a new id")
    }

    @Test
    fun stepAndMove() {
        var t = BrowserTabs().open(home, at = 1).open("world://boards/a", at = 2).open("world://boards/b", at = 3)
        val (a, b, c) = t.tabs.map { it.id }
        assertEquals(c, t.selected)
        t = t.step(1)
        assertEquals(a, t.selected, "wraps")
        t = t.step(-1)
        assertEquals(c, t.selected)
        t = t.move(c, 0)
        assertEquals(listOf(c, a, b), t.tabs.map { it.id })
    }
}
