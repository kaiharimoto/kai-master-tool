package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FollowScrollTest {

    @Test
    fun theKeysMoveTheListAndThePointerDoesNot() {
        val f = FollowScroll()
        assertTrue(f.follows(byKeys = true))
        assertFalse(f.follows(byKeys = false), "a row under the pointer is already in view")
    }

    @Test
    fun aHandThatScrollsKeepsTheListUntilItCloses() {
        val f = FollowScroll()
        f.scrolledByHand()
        assertTrue(f.released)
        assertFalse(f.follows(byKeys = true))
        f.scrolledByHand()
        assertFalse(f.follows(byKeys = true))
        // Opened again, the palette is a new list, and it follows.
        assertTrue(FollowScroll().follows(byKeys = true))
    }
}
