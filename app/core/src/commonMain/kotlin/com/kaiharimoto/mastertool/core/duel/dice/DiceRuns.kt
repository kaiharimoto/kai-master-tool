package com.kaiharimoto.mastertool.core.duel.dice

import kotlin.concurrent.Volatile

/**
 * [DiceSim.run] kept for the last few throws (1.0.92): a throw played out is up to [DiceSim.MAX_TIME] of physics, and the
 * table asked for both seats' runs again each time either seat threw. The physics is plain `Double` maths, so a throw run
 * twice is the same run, frame for frame: a run kept is the run that would have been made. A throw can be run ahead, off
 * the thread that draws ([warm]), and the table finds it ready.
 *
 * A list replaced whole, never changed in place: safe to share between the thread that draws and the one that warms, at
 * worst running one throw twice.
 */
object DiceRuns {
    /** Throws kept: both seats' of this round and the round before. */
    const val KEEP = 4

    @Volatile
    private var kept: List<Pair<DiceThrow, DiceSim.Run>> = emptyList()

    /** How many throws were played out here, all told: what the tests count. */
    var simulated: Int = 0
        private set

    /** [toss] played out to rest: [DiceSim.run]'s answer, made once. */
    fun of(toss: DiceThrow): DiceSim.Run {
        kept.firstOrNull { it.first == toss }?.let { return it.second }
        val run = DiceSim.run(toss)
        simulated++
        kept = (listOf(toss to run) + kept.filter { it.first != toss }).take(KEEP)
        return run
    }

    /** Plays [toss] out now, for the table to find kept: call it off the thread that draws. */
    fun warm(toss: DiceThrow) {
        of(toss)
    }

    /** Forgets every run kept: for the tests. */
    internal fun clear() {
        kept = emptyList()
    }
}
