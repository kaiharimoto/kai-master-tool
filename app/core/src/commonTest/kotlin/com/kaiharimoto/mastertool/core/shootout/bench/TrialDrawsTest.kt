package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun anEffectsDrawTakesTheMarkedSixthAndTheTurnDrawsTheNextCard() {
        // kai (1.1.7): "If a card draws for effect, it would draw the 6th card, and the next card would be the next top card."
        val hand = TrialDraws.ordered(six, TrialDraws.seed("s-4", TrialDraws.MINE))
        val rest = (100 until 134).toList()
        val seed = TrialDraws.seed("s-4", TrialDraws.MY_DRAWS)
        val under = TrialDraws.drawn(rest, rest.size, seed)

        val none = TrialDraws.shown(hand, rest, 0, seed)
        assertEquals(hand.draw, none.draw)
        assertEquals(emptyList(), none.drawn)
        assertFalse(none.shifted)

        val one = TrialDraws.shown(hand, rest, 1, seed)
        assertEquals(listOf(hand.draw!!), one.drawn)
        assertEquals(under[0], one.draw)
        assertTrue(one.shifted)

        val three = TrialDraws.shown(hand, rest, 3, seed)
        assertEquals(listOf(hand.draw!!, under[0], under[1]), three.drawn)
        assertEquals(under[2], three.draw)
        assertEquals(hand.opening, three.opening)
    }

    @Test
    fun theCardsInHandOnceTheTurnIsDrawnAreTheSameSixAndMore() {
        val hand = TrialDraws.ordered(six, TrialDraws.seed("s-5", TrialDraws.THEIRS))
        val rest = (100 until 134).toList()
        val seed = TrialDraws.seed("s-5", TrialDraws.THEIR_DRAWS)
        repeat(4) { n ->
            val s = TrialDraws.shown(hand, rest, n, seed)
            val held = s.opening + s.drawn + listOfNotNull(s.draw)
            assertTrue(held.containsAll(six), "after $n: $held")
            assertEquals(six.size + n, held.size)
        }
    }

    @Test
    fun theFirstPlayersDrawsComeOffTheRestWithNoTurnDraw() {
        val hand = TrialDraws.ordered(six.take(5), 7)
        val rest = (100 until 135).toList()
        val seed = TrialDraws.seed("s-6", TrialDraws.MY_DRAWS)
        val s = TrialDraws.shown(hand, rest, 2, seed)
        assertEquals(TrialDraws.drawn(rest, 2, seed), s.drawn)
        assertNull(s.draw)
        assertFalse(s.shifted)
        assertEquals(rest.size, TrialDraws.deckSize(hand, rest))
    }

    @Test
    fun aSpentDeckLeavesNoTurnDraw() {
        val hand = TrialDraws.ordered(six, 3)
        val rest = listOf(100, 101)
        assertEquals(3, TrialDraws.deckSize(hand, rest))
        val s = TrialDraws.shown(hand, rest, 99, 1)
        assertEquals(3, s.drawn.size)
        assertNull(s.draw)
    }

    @Test
    fun aRepeatShowsTheSameSituation() {
        // The red team (2026-10): a repeat measures steadiness, so the same hands must show the same marked sixth of
        // theirs and the same cards off the top, whatever id the showing is kept under.
        val mine = Hand(intArrayOf(2, 1, 1, 1, 0, 0), draw = 1)
        val theirs = Hand(intArrayOf(1, 1, 1, 1, 1, 1))
        val first = Proposal.Rate(mine, theirs, Stratum.G1_SECOND, Reason.CHOSEN)
        val again = Proposal.Rate(Hand(intArrayOf(2, 1, 1, 1, 0, 0), draw = 1), Hand(intArrayOf(1, 1, 1, 1, 1, 1)), Stratum.G1_SECOND, Reason.REPEAT)
        for (what in listOf(TrialDraws.THEIRS, TrialDraws.MY_DRAWS, TrialDraws.THEIR_DRAWS)) {
            assertEquals(TrialDraws.seed(first, what), TrialDraws.seed(again, what), what)
        }
        // Another situation is another shuffle: their side reads their hand and the stratum, yours your hand.
        val sided = first.copy(stratum = Stratum.SIDED_SECOND)
        assertTrue(TrialDraws.seed(first, TrialDraws.THEIRS) != TrialDraws.seed(sided, TrialDraws.THEIRS))
        val otherMine = first.copy(hand = Hand(intArrayOf(1, 1, 1, 1, 1, 0), draw = 0))
        assertEquals(TrialDraws.seed(first, TrialDraws.THEIRS), TrialDraws.seed(otherMine, TrialDraws.THEIRS))
        assertTrue(TrialDraws.seed(first, TrialDraws.MY_DRAWS) != TrialDraws.seed(otherMine, TrialDraws.MY_DRAWS))
    }
}
