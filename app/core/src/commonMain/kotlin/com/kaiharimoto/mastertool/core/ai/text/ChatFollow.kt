package com.kaiharimoto.mastertool.core.ai.text

/**
 * Whether the conversation keeps up with Ai as it writes (1.0.61, kai: "on mobile I'm trying to
 * scroll down Ai's chatlog to read the thinking but it keeps jumping me up"). It follows only a
 * reader who is already at the end: a scroll the reader makes and lets go of away from the end
 * stops it, one that comes to rest at the end starts it again, and so does a message sent. Before
 * this, every eighty characters of the answer and two hundred of the thinking put the *top* of the
 * newest item at the top of the panel — back to the start of the thought being read.
 */
class ChatFollow(following: Boolean = true) {
    var following: Boolean = following
        private set

    /** The reader's own scroll came to rest, at the end or not. */
    fun readerScrolled(atEnd: Boolean) {
        following = atEnd
    }

    /** A message was sent: what comes next is wanted. */
    fun sent() {
        following = true
    }

    /** Whether to move to the end now that more was written: only when following, and never under the reader's hand. */
    fun shouldFollow(readerScrolling: Boolean): Boolean = following && !readerScrolling
}
