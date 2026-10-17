package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PostureTest {

    @Test
    fun `a window taller than it is wide is tall`() {
        assertEquals(Posture.TALL, Posture.of(360f, 780f))
        assertEquals(Posture.TALL, Posture.of(924f, 1480f))
    }

    @Test
    fun `every landscape window keeps the builder it had`() {
        assertEquals(Posture.WIDE, Posture.of(1480f, 924f))
        assertEquals(Posture.WIDE, Posture.of(780f, 360f))
        assertEquals(Posture.WIDE, Posture.of(640f, 360f))
        // Square counts as wide: the side-by-side split still has width to divide.
        assertEquals(Posture.WIDE, Posture.of(800f, 800f))
    }
}

class PoolDockTest {

    /** A 780dp-tall phone under a 44dp top bar, with the dock's own furniture. */
    private val phone = DockMetrics(windowHeight = 736f, chrome = 120f, minDeck = 180f)

    @Test
    fun `peek is exactly the dock's own furniture`() {
        assertEquals(120f, PoolDock.height(PoolStop.PEEK, phone))
    }

    @Test
    fun `full is the whole window`() {
        assertEquals(736f, PoolDock.height(PoolStop.FULL, phone))
    }

    @Test
    fun `half leaves the deck something to be`() {
        val half = PoolDock.height(PoolStop.HALF, phone)
        assertEquals(368f, half)
        assertTrue(PoolDock.deckHeight(PoolStop.HALF, phone) >= phone.minDeck)
    }

    @Test
    fun `a short window gives the deck its minimum before it gives the pool half`() {
        val short = DockMetrics(windowHeight = 300f, chrome = 120f, minDeck = 180f)
        assertEquals(120f, PoolDock.height(PoolStop.HALF, short))
        assertEquals(180f, PoolDock.deckHeight(PoolStop.HALF, short))
    }

    @Test
    fun `a window with no room for the deck still keeps the search field whole`() {
        val tiny = DockMetrics(windowHeight = 140f, chrome = 120f, minDeck = 180f)
        assertEquals(120f, PoolDock.height(PoolStop.HALF, tiny))
        assertEquals(120f, PoolDock.height(PoolStop.PEEK, tiny))
    }

    @Test
    fun `a short phone's half still shows a row of results`() {
        // 360×640 upright (the red team, 2026-10): about 500dp under the bars, the dock's furniture
        // 174, the deck 330 tall. Half was the window less the deck — 170 — clamped up to PEEK.
        val short = DockMetrics(windowHeight = 500f, chrome = 174f, minDeck = 200f, deck = 330f)
        assertEquals(PoolDock.height(PoolStop.PEEK, short), PoolDock.height(PoolStop.HALF, short))
        val rowed = short.copy(halfResults = 120f)
        val half = PoolDock.height(PoolStop.HALF, rowed)
        assertEquals(174f + 120f, half)
        assertTrue(half > PoolDock.height(PoolStop.PEEK, rowed))
        assertTrue(half < PoolDock.height(PoolStop.FULL, rowed))
        // The deck's minimum still has the last word, and the furniture is never cut.
        assertEquals(200f, PoolDock.height(PoolStop.HALF, rowed.copy(minDeck = 300f)))
        assertEquals(174f, PoolDock.height(PoolStop.HALF, rowed.copy(windowHeight = 250f)))
        // A tall phone's half is what it was.
        assertEquals(368f, PoolDock.height(PoolStop.HALF, phone.copy(halfResults = 120f)))
    }

    @Test
    fun `a released drag settles on the nearest stop`() {
        assertEquals(PoolStop.PEEK, PoolDock.nearest(130f, phone))
        assertEquals(PoolStop.HALF, PoolDock.nearest(300f, phone))
        assertEquals(PoolStop.HALF, PoolDock.nearest(420f, phone))
        assertEquals(PoolStop.FULL, PoolDock.nearest(700f, phone))
    }

    @Test
    fun `typing takes the screen and letting go never closes over the answer`() {
        assertEquals(PoolStop.FULL, PoolDock.whileTyping())
        // The one design claim: a search typed from the closed dock leaves its
        // results visible rather than shutting on them.
        assertEquals(PoolStop.HALF, PoolDock.afterTyping(PoolStop.PEEK))
        assertEquals(PoolStop.HALF, PoolDock.afterTyping(PoolStop.HALF))
        assertEquals(PoolStop.FULL, PoolDock.afterTyping(PoolStop.FULL))
    }
}
