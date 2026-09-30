package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.math.abs
import kotlin.math.sign

/**
 * How the face answers a hand (1.0.54, kai: "let the user interact with the Ai avatar in various
 * ways to make it feel like it's really living and there"). The grammar, pure and tested, like the
 * mood table it feeds ([MoodTracker.moment]):
 *
 * - **A tap** is noticed, and the taps that follow are answered differently — surprise, a wink, a
 *   grin — so it never answers the same way twice running. Asleep, a tap wakes it.
 * - **A double tap** is affection: hearts.
 * - **Poking** — five taps within a couple of seconds — annoys it, and it says so; left alone a
 *   moment, it is shy about it.
 * - **Petting** — the pointer or a finger stroked back and forth over the face — pleases it; kept
 *   up, it melts. It even pleases it asleep, without waking it.
 * - **Holding** it down surprises it; held on, it goes shy.
 * - **Resting** the pointer on it a while makes it shy of being looked at.
 *
 * Each answer is a face, how long it is worn, and a short line for the composer beside it. Time is
 * seconds on any clock, as the mood table's is.
 */
class AvatarPlay {
    data class Reaction(val face: Expression, val seconds: Double, val line: String)

    private var taps = mutableListOf<Double>()
    private var tapTurn = 0
    private var lastSign = 0f
    private var travel = 0f
    private var turns = 0
    private var strokeStart = 0.0
    private var lastStroke = 0.0
    private var petted = 0
    private var petAt = -100.0
    private var annoyedAt = -100.0

    /** A single tap, [asleep] when the face was sleeping. */
    fun tap(now: Double, asleep: Boolean): Reaction {
        taps.removeAll { now - it > POKE_WINDOW }
        taps += now
        if (asleep) {
            taps.clear()
            return Reaction(Expression.WAKING, 3.0, "Mm… I'm awake.")
        }
        if (taps.size >= POKES) {
            taps.clear()
            annoyedAt = now
            return Reaction(Expression.ANGRY, 2.2, "Hey. That tickles.")
        }
        // Just annoyed, a gentle tap makes up.
        if (now - annoyedAt < 6.0) {
            annoyedAt = -100.0
            return Reaction(Expression.SHY, 2.2, "…Fine. You're forgiven.")
        }
        val answer = TAPS[tapTurn % TAPS.size]
        tapTurn++
        return answer
    }

    /** Two taps together. */
    fun doubleTap(now: Double): Reaction {
        taps.clear()
        return Reaction(Expression.LOVE, 2.6, "(♡▽♡)")
    }

    /** Pressed and held past [HOLD]; [longer] once it has been held a while more. */
    fun hold(longer: Boolean): Reaction =
        if (longer) Reaction(Expression.SHY, 2.5, "You can let go now…") else Reaction(Expression.SURPRISED, 1.6, "Oh.")

    /**
     * The pointer moved [dx] across the face at [now]: back and forth enough, it is being petted.
     * Null until it is; then the answer, once per stroke, warmer the longer it goes on.
     */
    fun stroke(dx: Float, now: Double, asleep: Boolean): Reaction? {
        if (now - lastStroke > STROKE_GAP) {
            travel = 0f
            turns = 0
            lastSign = 0f
            strokeStart = now
            petted = 0
        }
        lastStroke = now
        travel += abs(dx)
        val s = sign(dx)
        if (s != 0f && lastSign != 0f && s != lastSign) turns++
        if (s != 0f) lastSign = s
        if (turns < PET_TURNS || travel < PET_TRAVEL || now - petAt < PET_EVERY) return null
        turns = 0
        travel = 0f
        petAt = now
        petted++
        return when {
            asleep -> Reaction(Expression.SLEEPING, 3.0, "(－ω－)zzZ… mm.")
            petted >= 3 -> Reaction(Expression.LOVE, 3.0, "I could get used to this.")
            petted == 2 -> Reaction(Expression.DELIGHTED, 2.5, "Mm, that's nice.")
            else -> Reaction(Expression.SHY, 2.2, "Oh — hello.")
        }
    }

    /** The pointer has rested on the face [seconds] without a click. */
    fun dwell(seconds: Double): Reaction? =
        if (seconds >= DWELL) Reaction(Expression.SHY, 2.0, "…You're staring.") else null

    companion object {
        const val POKES = 5
        const val POKE_WINDOW = 2.2
        const val HOLD = 0.6
        const val HOLD_LONGER = 2.0
        const val STROKE_GAP = 0.6
        const val PET_TURNS = 3
        const val PET_TRAVEL = 60f

        /** Petting is answered at most this often, so a long stroke is a warming, not a chatter. */
        const val PET_EVERY = 1.2
        const val DWELL = 4.0

        /** Taps, in turn: never the same answer twice running. */
        val TAPS = listOf(
            Reaction(Expression.SURPRISED, 1.4, "Hm?"),
            Reaction(Expression.WINK, 1.8, "(＾_−)☆"),
            Reaction(Expression.DELIGHTED, 1.8, "Hi there."),
            Reaction(Expression.LISTENING, 1.8, "I'm listening."),
        )
    }
}
