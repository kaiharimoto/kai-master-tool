package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplaysTest {
    /** A short duel: the deal, a phase, a summon, a mill of two, the end of the turn, the other seat's draw. */
    private fun duel(): DuelGame {
        var g = DuelGame.start(header())
        g = g.act(DuelAction.Phase(DuelPhase.MAIN1), 0).game
        g = g.act(DuelAction.Move(g.state.seats[0].hand[0], Place.Zone(0, ZoneKind.MONSTER, 2)), 0).game
        val top = g.state.seats[0].deck.take(2)
        g = g.act(top.map { DuelAction.Move(it, Place.Pile(0, PileKind.GY)) }, 0).game
        g = g.act(DuelAction.EndTurn, 0).game
        g = g.act(DuelAction.Draw(1), 1).game
        return g
    }

    @Test
    fun stepsGoAGestureAPhaseOrATurnBothWays() {
        val e = duel().entries
        val deal = DuelGame.start(header()).cursor
        // A mill of two is one step.
        val atMill = deal + 2
        assertEquals(atMill + 2, Replays.next(e, atMill, ReplayUnit.GROUP))
        assertEquals(atMill, Replays.previous(e, atMill + 2, ReplayUnit.GROUP))
        // The next turn starts after the End Turn.
        val turn = Replays.next(e, deal, ReplayUnit.TURN)
        assertEquals(DuelAction.EndTurn, e[turn - 1].action)
        assertEquals(0, Replays.previous(e, turn, ReplayUnit.TURN))
        assertEquals(deal + 1, Replays.next(e, deal, ReplayUnit.PHASE))
        assertEquals(e.size, Replays.next(e, e.size, ReplayUnit.GROUP))
        assertEquals(0, Replays.previous(e, 0, ReplayUnit.TURN))
    }

    @Test
    fun anEditKeepsTheRestAndStrikesWhatNoLongerFits() {
        val g = duel()
        val r = g.record("Test")
        val deal = DuelGame.start(header()).cursor
        // Take out the summon: the rest of the duel still plays.
        val noSummon = Replays.deleteGroup(r, deal + 1)
        assertEquals(r.entries.size - 1, noSummon.entries.size)
        assertTrue(Replays.refused(noSummon).isEmpty())
        // Put a second summon in the same zone before the first: only the first is struck.
        val hand = g.stateAt(deal).seats[0].hand
        val clash = Replays.insert(r, deal + 1, listOf(DuelAction.Move(hand[1], Place.Zone(0, ZoneKind.MONSTER, 2))), 0)
        assertEquals(setOf(deal + 2), Replays.refused(clash))
        // Groups stay one gesture each.
        assertEquals(clash.entries.size, clash.entries.map { it.i }.distinct().size)
        assertTrue(clash.entries.zipWithNext().all { (a, b) -> b.group >= a.group })
        // A note changes nothing on the table.
        val noted = Replays.annotate(r, deal + 2, "Ash here?")
        assertEquals(DuelSetup.fold(r.header, r.entries).first, DuelSetup.fold(noted.header, noted.entries).first)
    }

    @Test
    fun aWhatIfPlaysOnFromAnyPoint() {
        val r = duel().record("Test")
        val deal = DuelGame.start(header()).cursor
        val branch = Replays.branch(r, deal + 2)
        assertEquals(deal + 2, branch.cursor)
        assertEquals(1, branch.state.onField().size)
        val next = branch.act(DuelAction.Draw(0), 0)
        assertTrue(next.ok)
        // The record keeps where it came from, through a file.
        val saved = next.game.record("What if", parent = "r1", parentAt = deal + 2)
        val back = DuelCodec.decode(DuelCodec.encode(saved))!!
        assertEquals("r1", back.parent)
        assertEquals(deal + 2, back.parentAt)
    }
}
