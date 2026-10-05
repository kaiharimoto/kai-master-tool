package com.kaiharimoto.mastertool.core.world.desk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldNoticesTest {
    @Test
    fun eachNoticesRule() {
        assertEquals("Open", WorldNotices.appMade("Hand odds", "hand-odds").action)
        assertEquals("world://apps/hand-odds", WorldNotices.appMade("Hand odds", "hand-odds").address)
        // A run that ends with the Terminal in front is no news, unless it took over three seconds.
        assertNull(WorldNotices.runFinished("openings.js", 1_200, 3, 5, terminalInFront = true))
        assertEquals("openings.js · 1.2 s · 3 pages", WorldNotices.runFinished("openings.js", 1_200, 3, 5, terminalInFront = false)!!.text)
        assertNotNull(WorldNotices.runFinished("long.js", 3_500, 0, 5, terminalInFront = true))
        assertEquals("Show in Terminal", WorldNotices.runFailed("x.js", "TypeError: x is undefined\n  at line 3", 9).action)
        assertEquals("x.js: TypeError: x is undefined", WorldNotices.runFailed("x.js", "TypeError: x is undefined\n  at line 3", 9).text)
        assertNull(WorldNotices.newPages(3, browserInFront = true))
        assertEquals("3 new pages", WorldNotices.newPages(3, browserInFront = false)!!.title)
        assertEquals("Ai is in the Terminal", WorldNotices.aiIsIn("terminal", "Terminal").title)
        assertEquals("Answer", WorldNotices.waiting().action)
        assertEquals("Hand odds: on(press draw) failed at line 41", WorldNotices.appFailed("Hand odds", "hand-odds", "on(press draw)", 41).text)
        assertEquals("Show code", WorldNotices.appFailed("Hand odds", "hand-odds", "on(press draw)", 41).action)
        assertEquals("412 ms", WorldNotices.seconds(412))
    }

    @Test
    fun newPagesCoalesceWhileUnread() {
        var n = WorldNotices()
        n = n.post(WorldNotices.newPages(1, false)!!, 0)
        n = n.post(WorldNotices.newPages(2, false)!!, 100)
        assertEquals(1, n.tray.size)
        assertEquals("3 new pages", n.tray[0].title)
        assertEquals(3, n.tray[0].count)
        assertEquals("3 new pages", n.toast!!.title, "the toast on screen says the new count")
        n = n.readAll().post(WorldNotices.newPages(1, false)!!, 10_000)
        assertEquals(2, n.tray.size, "once read, the next pages are news of their own")
        assertEquals("1 new page", n.tray[0].title)
    }

    @Test
    fun oneToastAtATimeAndAtMostOneEveryTwoSeconds() {
        var n = WorldNotices()
        n = n.post(WorldNotices.appMade("A", "a"), 0)
        assertEquals("“A”", n.toast!!.text)
        n = n.post(WorldNotices.appMade("B", "b"), 500)
        assertEquals("“A”", n.toast!!.text, "one at a time: B goes to the count")
        assertEquals(2, n.unread)
        n = n.tick(WorldNotices.TOAST_MS)
        assertNull(n.toast)
        n = n.post(WorldNotices.appMade("C", "c"), WorldNotices.TOAST_MS + 100)
        assertEquals("“C”", n.toast!!.text)
        n = n.dismiss().post(WorldNotices.appMade("D", "d"), WorldNotices.TOAST_MS + 900)
        assertNull(n.toast, "under two seconds after the last toast")
        assertEquals(4, n.unread)
    }

    @Test
    fun aHeldToastStays() {
        var n = WorldNotices().post(WorldNotices.waiting(), 0).hold(true, 1_000)
        n = n.tick(60_000)
        assertNotNull(n.toast)
        n = n.hold(false, 60_000).tick(60_000 + WorldNotices.TOAST_MS - 1)
        assertNotNull(n.toast)
        assertNull(n.tick(60_000 + WorldNotices.TOAST_MS).toast)
    }

    @Test
    fun theTrayKeepsFiftyNewestFirst() {
        var n = WorldNotices()
        repeat(80) { n = n.post(WorldNotices.appMade("A$it", "a$it"), it * 10_000L) }
        assertEquals(WorldNotices.MAX_TRAY, n.tray.size)
        assertEquals("“A79”", n.tray.first().text)
        n = n.acted(n.tray.first().id)
        assertEquals(49, n.unread)
        assertTrue(n.clear().tray.isEmpty())
    }

    @Test
    fun theAppANoticePointsAtAnswersItWhenItComesToTheFront() {
        var n = WorldNotices().post(WorldNotices.newPages(6, browserInFront = false)!!, 0)
        n = n.post(WorldNotices.appMade("Hand odds", "hand-odds"), 10_000)
        assertNotNull(n.toast)
        assertEquals(2, n.unread)
        // The Terminal in front answers neither.
        assertEquals(n, n.looked(BuiltInApp.TERMINAL.id))
        // The Browser in front: its pages are seen, and the toast over it — "6 new pages" — goes.
        n = WorldNotices().post(WorldNotices.newPages(6, browserInFront = false)!!, 0)
        n = n.looked(BuiltInApp.BROWSER.id)
        assertNull(n.toast)
        assertEquals(0, n.unread)
        // An app Ai made is answered by its own window, and only that one.
        n = WorldNotices().post(WorldNotices.appMade("Hand odds", "hand-odds"), 0)
        assertEquals(1, n.looked(AppRef.Made("combo-lines").key).unread)
        assertEquals(0, n.looked(AppRef.Made("hand-odds").key).unread)
        // A failed app still wants its code read.
        n = WorldNotices().post(WorldNotices.appFailed("Hand odds", "hand-odds", "on(press draw)", 41), 0)
        assertEquals(1, n.looked(AppRef.Made("hand-odds").key).unread)
    }
}
