package com.kaiharimoto.mastertool.core.shootout.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kai (1.1.5): the sixth card marked as the turn's draw; draws by effects turned up off the rest of the deck. */
class TrialDrawsTest {
    private val six = listOf(11, 12, 13, 14, 15, 16)

    @Test
    fun aHandOfFiveHasNoTurnDraw() {
        val o = TrialDraws.ordered(six.take(5), 7)
        assertEquals(six.take(5), o.opening)
        assertNull(o.draw)
    }

    @Test
    fun aHandOfSixPutsItsTurnDrawLastTheSameEveryTime() {
        val o = TrialDraws.ordered(six, TrialDraws.seed("s-3", TrialDraws.THEIRS))
        assertEquals(5, o.opening.size)
        assertEquals(six.toSet(), (o.opening + o.draw!!).toSet())
        assertEquals(o, TrialDraws.ordered(six, TrialDraws.seed("s-3", TrialDraws.THEIRS)))
    }

    @Test
    fun theTurnDrawIsAnyOfTheSixAlike() {
        // As a shuffle would make it: over many trials every position is the draw about a sixth of the time.
        val counts = IntArray(6)
        repeat(6000) { counts[six.indexOf(TrialDraws.ordered(six, TrialDraws.seed("s-$it", TrialDraws.MINE)).draw)]++ }
        counts.forEach { assertTrue(it in 850..1150, counts.toList().toString()) }
    }

    @Test
    fun drawsComeOffTheRestAndAskingAgainExtendsThem() {
        val rest = (100 until 135).toList()
        val seed = TrialDraws.seed("s-9", TrialDraws.MY_DRAWS)
        val two = TrialDraws.drawn(rest, 2, seed)
        val three = TrialDraws.drawn(rest, 3, seed)
        assertEquals(two, three.take(2))
        assertTrue(three.all { it in rest } && three.toSet().size == 3)
        assertEquals(emptyList(), TrialDraws.drawn(rest, 0, seed))
        assertEquals(rest.size, TrialDraws.drawn(rest, 99, seed).size)
    }

    @Test
    fun yoursAndTheirsAreDrawnApart() {
        assertTrue(TrialDraws.seed("s-1", TrialDraws.MY_DRAWS) != TrialDraws.seed("s-1", TrialDraws.THEIR_DRAWS))
    }
}
