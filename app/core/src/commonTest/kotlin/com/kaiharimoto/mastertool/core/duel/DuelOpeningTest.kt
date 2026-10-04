package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.DieFaces
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opening roll (1.0.87): two dice a seat, the higher chooses to go first or second, a tie throws again. */
class DuelOpeningTest {

    private fun start(seed: Long = 42L) = DuelGame.start(header(seed = seed).copy(openingRoll = true))

    private fun roll(g: DuelGame, seat: Int, toss: DiceThrow? = null): DuelGame {
        val r = g.act(DuelAction.OpeningRoll(seat, toss = toss), seat)
        assertTrue(r.ok, r.problem)
        return r.game
    }

    /** A duel whose first round goes to [want]: a winner, or a tie when null. */
    private fun firstRoundTo(want: Int?): DuelGame {
        for (seed in 1L..500L) {
            val g = roll(roll(start(seed), 0), 1)
            val o = g.state.opening!!
            if (want == null && o.tied) return g
            if (want != null && o.winner == want) return g
        }
        error("No seed gave $want")
    }

    @Test
    fun aDuelWithTheRollWaitsForIt() {
        val g = start()
        val o = assertNotNull(g.state.opening)
        assertFalse(o.decided)
        assertTrue(g.state.beforeTurnOne)
        assertNull(TurnStart.next(g))
        // The turn waits: no phase, no End Turn.
        assertFalse(g.act(DuelAction.Phase(DuelPhase.STANDBY), 0).ok)
        assertFalse(g.act(DuelAction.EndTurn, 0).ok)
        // The deal is done all the same: both hands drawn.
        assertEquals(5, g.state.seats[0].hand.size)
        assertEquals(5, g.state.seats[1].hand.size)
    }

    @Test
    fun aDuelWithoutItStartsAsBefore() {
        val g = DuelGame.start(header())
        assertNull(g.state.opening)
        assertTrue(g.act(DuelAction.Phase(DuelPhase.STANDBY), 0).ok)
        // A one-player table never rolls.
        assertNull(DuelGame.start(header(solo = true).copy(openingRoll = true)).state.opening)
    }

    @Test
    fun theDiceAreStampedAndAThrowWithThem() {
        val g = roll(start(), 0)
        val a = g.entries.last().action as DuelAction.OpeningRoll
        assertEquals(2, a.values.size)
        assertTrue(a.values.all { it in 1..6 })
        assertTrue(assertNotNull(a.toss).valid)
        assertEquals(a.values, g.state.opening!!.dice[0])
        assertEquals(a.toss, g.state.opening!!.throws[0])
        // One throw a seat a round.
        assertFalse(g.act(DuelAction.OpeningRoll(0), 0).ok)
        // A throw made by hand is kept as it was made; the values are still the stamp's.
        val toss = DiceThrow.fromDrag(V3(8.0, 6.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(3.0, -20.0), 2.0)
        val byHand = roll(g, 1, toss)
        assertEquals(toss, (byHand.entries.last().action as DuelAction.OpeningRoll).toss)
    }

    @Test
    fun undoCannotFishForABetterRoll() {
        val g = start()
        val first = roll(g, 0).entries.last().action as DuelAction.OpeningRoll
        val again = roll(roll(g, 0).undo(), 0).entries.last().action as DuelAction.OpeningRoll
        assertEquals(first.values, again.values)
        // Nor does a different throw: the hand decides how the dice fall, never what they read.
        val other = roll(g, 0, DiceThrow.fromDrag(V3(5.0, 7.0), listOf(Quat.IDENTITY, Quat.IDENTITY), V3(-8.0, -30.0), -4.0))
        assertEquals(first.values, (other.entries.last().action as DuelAction.OpeningRoll).values)
    }

    @Test
    fun noHandIsSeenUntilTheChoiceIsMade() {
        // kai, 1.0.93: "have the dice roll first and have both players' hands hidden until a player chooses first or second".
        val dealt = start()
        assertTrue(dealt.state.beforeTurnOne)
        for (seat in 0..1) {
            val hand = dealt.state.seats[seat].hand
            assertEquals(5, hand.size)
            hand.forEach { uid ->
                assertFalse(DuelSight.sees(dealt.state, uid, seat), "its own owner does not look yet")
                assertFalse(DuelSight.sees(dealt.state, uid, null), "nor does the table's all-seeing eye")
            }
        }
        // Rolled, but not yet chosen: still hidden.
        for (winner in 0..1) {
            val rolled = firstRoundTo(winner)
            assertTrue(rolled.state.beforeTurnOne)
            assertFalse(DuelSight.sees(rolled.state, rolled.state.seats[winner].hand[0], winner))
            // Chosen: each seat picks up its own hand, and only its own.
            val chosen = rolled.act(DuelAction.GoFirst(winner, true), winner).game
            for (seat in 0..1) chosen.state.seats[seat].hand.forEach { uid ->
                assertTrue(DuelSight.sees(chosen.state, uid, seat))
                assertFalse(DuelSight.sees(chosen.state, uid, 1 - seat))
            }
        }
        // A duel without the roll deals its hands as it always has.
        val plain = DuelGame.start(header())
        assertTrue(DuelSight.sees(plain.state, plain.state.seats[0].hand[0], 0))
    }

    @Test
    fun theHigherSumChoosesAndSetsWhoGoesFirst() {
        for (winner in 0..1) {
            val g = firstRoundTo(winner)
            val o = g.state.opening!!
            assertTrue(o.sum(winner) > o.sum(1 - winner))
            // The loser does not choose; nor does anyone roll again.
            assertFalse(g.act(DuelAction.GoFirst(1 - winner, true), 1 - winner).ok)
            assertFalse(g.act(DuelAction.OpeningRoll(1 - winner), 1 - winner).ok)
            // Going second hands turn 1 to the other seat, and the turn may start.
            val second = g.act(DuelAction.GoFirst(winner, false), winner).game
            assertEquals(1 - winner, second.state.active)
            assertFalse(second.state.beforeTurnOne)
            // Turn 1 never draws, and the phases are the player's (1.0.93).
            assertNull(TurnStart.next(second))
            val first = g.act(DuelAction.GoFirst(winner, true), winner).game
            assertEquals(winner, first.state.active)
            assertFalse(first.act(DuelAction.GoFirst(winner, false), winner).ok, "decided once")
        }
    }

    @Test
    fun aTieThrowsAgain() {
        val g = firstRoundTo(null)
        val o = g.state.opening!!
        assertEquals(o.sum(0), o.sum(1))
        assertNull(o.winner)
        assertTrue(o.waitsOn(0) && o.waitsOn(1))
        val next = roll(g, 1)
        assertEquals(2, next.state.opening!!.round)
        assertFalse(next.state.opening!!.thrown(0), "a new round: the other seat throws again too")
        assertTrue(next.state.opening!!.thrown(1))
    }

    @Test
    fun theLogSaysIt() {
        val g = firstRoundTo(0)
        val e = g.entries.last()
        val before = g.stateAt(g.cursor - 1)
        val words = DuelWords.say(before, g.state, e, null, catalog)
        val a = e.action as DuelAction.OpeningRoll
        assertTrue(words.startsWith("Rival rolls ${a.values[0]} and ${a.values[1]} (${a.values.sum()})"), words)
        assertTrue(words.endsWith("Kai wins the roll and chooses"), words)
        val chose = g.act(DuelAction.GoFirst(0, true), 0).game
        assertEquals("Kai wins the roll and goes first", DuelWords.say(g.state, chose.state, chose.entries.last(), 1, catalog))
    }

    @Test
    fun theLineThrowsAndChooses() {
        val g = start()
        val p = DuelCommand.parse("roll", g.state, 0, catalog) as DuelCommand.Parsed.Actions
        assertEquals(listOf<DuelAction>(DuelAction.OpeningRoll(0)), p.actions)
        val won = firstRoundTo(1)
        assertEquals(listOf<DuelAction>(DuelAction.GoFirst(1, true)), (DuelCommand.parse("go first", won.state, 1, catalog) as DuelCommand.Parsed.Actions).actions)
        assertEquals(listOf<DuelAction>(DuelAction.GoFirst(1, false)), (DuelCommand.parse("second", won.state, 1, catalog) as DuelCommand.Parsed.Actions).actions)
        // Once the duel is under way, "roll" is a die again.
        val under = won.act(DuelAction.GoFirst(1, true), 1).game
        assertTrue((DuelCommand.parse("roll", under.state, 0, catalog) as DuelCommand.Parsed.Actions).actions.single() is DuelAction.Dice)
    }

    @Test
    fun bothSeatsSeeTheRollAndTheGuestOnlyAsksForIt() {
        val g = roll(start(), 0)
        val view = DuelView.of(g.state, 1, g.header.seed)
        assertEquals(g.state.opening, view.opening)
        assertEquals(g.state.opening, DuelMirror.state(view).opening)
        // The guest's values are never trusted, and it throws as itself.
        val (resolved, why) = DuelHost.resolve(g.state, 1, g.header.seed, listOf(DuelAction.OpeningRoll(0, values = listOf(6, 6))))
        assertNull(why)
        assertEquals(DuelAction.OpeningRoll(1), resolved!!.single())
    }

    @Test
    fun theRollReadsBackFromTheFile() {
        val g = firstRoundTo(0).let { it.act(DuelAction.GoFirst(0, false), 0).game }
        val back = DuelGame.of(assertNotNull(DuelCodec.decode(DuelCodec.encode(g.record()))))
        assertEquals(g.state, back.state)
        assertEquals(1, back.state.active)
    }

    @Test
    fun theStampedValueIsTheFaceOnTop() {
        // The physics picks the face; the labelling puts the stamped number on it, for every throw the log holds.
        val g = roll(roll(start(7), 0), 1)
        val o = g.state.opening!!
        (0..1).forEach { seat ->
            val run = DiceSim.run(o.throws[seat]!!)
            run.rest.forEachIndexed { d, pose ->
                val label = DieFaces.relabel(run.up[d], o.dice[seat][d])
                assertEquals(o.dice[seat][d], label[DieFaces.upFace(pose.q)])
            }
        }
    }
}
