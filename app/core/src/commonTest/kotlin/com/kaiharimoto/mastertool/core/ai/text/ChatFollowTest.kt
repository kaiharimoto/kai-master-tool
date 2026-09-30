package com.kaiharimoto.mastertool.core.ai.text

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The conversation keeps up with Ai only for a reader at the end (1.0.61). */
class ChatFollowTest {
    @Test
    fun aReaderWhoScrollsAwayIsLeftToRead() {
        val f = ChatFollow()
        assertTrue(f.shouldFollow(readerScrolling = false), "a new conversation follows")
        assertFalse(f.shouldFollow(readerScrolling = true), "never under the reader's hand")
        f.readerScrolled(atEnd = false)
        assertFalse(f.shouldFollow(readerScrolling = false), "reading the thinking, left alone")
        f.readerScrolled(atEnd = true)
        assertTrue(f.shouldFollow(readerScrolling = false), "back at the end, it follows again")
    }

    @Test
    fun sendingFollowsAgain() {
        val f = ChatFollow()
        f.readerScrolled(atEnd = false)
        f.sent()
        assertTrue(f.shouldFollow(readerScrolling = false))
    }
}
