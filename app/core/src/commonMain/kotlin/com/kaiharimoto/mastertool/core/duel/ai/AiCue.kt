package com.kaiharimoto.mastertool.core.duel.ai

/**
 * What the foot of the duel's log offers Ai now, and so what its one key does (1.0.86, kai: playing against Ai without
 * reaching for the mouse). The buttons and the key read the same answer, so they cannot drift: the key does what the
 * button drawn first does.
 */
enum class AiCue {
    /** Ai is answering a trigger, or a phase change waits on it: go on without its answer. */
    DONT_WAIT,

    /** Ai is answering something else: Stop is the only button, and Esc is its key. */
    BUSY,

    /** The person said Respond and has responded on the table. */
    DONE,

    /** Ai's link tops the chain: the person lets it resolve. */
    NO_RESPONSE,

    /** The person's own link tops the chain: priority passes to Ai. */
    PASS,

    /** Nothing open: Ai's move (respond to what they did, or play its turn). */
    YOUR_MOVE,
    ;

    companion object {
        /**
         * The cue for the table as it stands: [waiting] (Ai answers a watch, or a held phase change waits on it) before
         * [running] (Ai busy with anything else), before [responding], before the chain — whose top link is [topSeat]'s,
         * against Ai's [aiSeat]; a [solo] table has no other side to pass to.
         */
        fun primary(waiting: Boolean, running: Boolean, responding: Boolean, topSeat: Int?, aiSeat: Int, solo: Boolean): AiCue = when {
            waiting -> DONT_WAIT
            running -> BUSY
            responding -> DONE
            topSeat != null && !solo && topSeat == aiSeat -> NO_RESPONSE
            topSeat != null && !solo -> PASS
            else -> YOUR_MOVE
        }
    }
}
