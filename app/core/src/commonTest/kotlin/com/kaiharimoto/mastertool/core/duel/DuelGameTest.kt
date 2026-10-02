package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DuelGameTest {

    @Test
    fun aNewDuelIsDealtFromItsSeedAndTheSameSeedDealsTheSameHands() {
        val a = DuelGame.start(header(seed = 9))
        val b = DuelGame.start(header(seed = 9))
        val c = DuelGame.start(header(seed = 10))
        assertEquals(5, a.state.seats[0].hand.size)
        assertEquals(5, a.state.seats[1].hand.size)
        assertEquals(a.state.seats[0].hand, b.state.seats[0].hand)
        assertTrue(a.state.seats[0].deck != c.state.seats[0].deck || a.state.seats[0].hand != c.state.seats[0].hand)
        assertFalse(a.canUndo)
    }

    @Test
    fun aSoloDuelDealsOneHand() {
        val g = DuelGame.start(header(solo = true))
        assertEquals(5, g.state.seats[0].hand.size)
        assertTrue(g.state.seats[1].hand.isEmpty())
    }

    @Test
    fun undoTakesBackAWholeGroupAndRedoPutsItBack() {
        var g = DuelGame.start(header())
        val hand = g.state.seats[0].hand
        val top3 = g.state.seats[0].deck.take(3)
        g = g.act(top3.map { DuelAction.Move(it, Place.Pile(0, PileKind.GY)) }, 0).game
        assertEquals(3, g.state.seats[0].gy.size)
        g = g.act(DuelAction.Move(hand[0], Place.Zone(0, ZoneKind.MONSTER, 2)), 0).game
        g = g.undo()
        assertEquals(hand, g.state.seats[0].hand)
        assertEquals(3, g.state.seats[0].gy.size)
        g = g.undo()
        assertTrue(g.state.seats[0].gy.isEmpty())
        assertFalse(g.canUndo)
        g = g.redo()
        assertEquals(3, g.state.seats[0].gy.size)
        assertTrue(g.canRedo)
        // Acting after an undo starts a new future.
        g = g.act(DuelAction.Lp(0, delta = -100), 0).game
        assertFalse(g.canRedo)
    }

    @Test
    fun aGroupIsAllOrNothing() {
        val g = DuelGame.start(header())
        val hand = g.state.seats[0].hand
        val zone = Place.Zone(0, ZoneKind.MONSTER, 0)
        val r = g.act(listOf(DuelAction.Move(hand[0], zone), DuelAction.Move(hand[1], zone)), 0)
        assertNotNull(r.problem)
        assertTrue(r.problem!!.startsWith("Step 2"))
        assertEquals(g, r.game)
    }

    @Test
    fun anUndoneShuffleShuffledAgainComesOutTheSame() {
        var g = DuelGame.start(header())
        g = g.act(DuelAction.Shuffle(0), 0).game
        val first = g.state.seats[0].deck
        g = g.undo().act(DuelAction.Shuffle(0), 0).game
        assertEquals(first, g.state.seats[0].deck)
    }

    @Test
    fun aDuelSurvivesItsFileAndUnknownActionsPassThrough() {
        var g = DuelGame.start(header())
        val hand = g.state.seats[0].hand
        g = g.act(DuelAction.Move(hand[0], Place.Zone(0, ZoneKind.MONSTER, 1), CardPosition.FACE_DOWN_DEF, "set"), 0).game
        g = g.act(DuelAction.Coin(0), 0).game
        g = g.act(DuelAction.Lp(1, delta = -800), 1).game.undo()
        val text = DuelCodec.encode(g.record("Test"))
        val back = DuelGame.of(DuelCodec.decode(text)!!)
        assertEquals(g.state, back.state)
        assertEquals(g.entries, back.entries)
        assertEquals(g.cursor, back.cursor)
        assertTrue(back.canRedo)

        // A newer build's action survives being read and written by this one.
        val newer = text.replace("\"t\":\"coin\"", "\"t\":\"hologram\",\"glow\":3")
        val read = DuelCodec.decode(newer)!!
        val unknown = read.entries.map { it.action }.filterIsInstance<DuelAction.Unknown>().single()
        assertEquals("hologram", unknown.raw["t"].toString().trim('"'))
        assertTrue(DuelCodec.encode(read).contains("\"glow\":3"))
        assertIs<DuelGame>(DuelGame.of(read))
    }

    @Test
    fun theTimelineFoldsToTheSameTableFromItsSnapshots() {
        var g = DuelGame.start(header())
        repeat(80) { i ->
            g = g.act(if (i % 2 == 0) DuelAction.Lp(0, delta = -10) else DuelAction.Lp(1, delta = -10), i % 2).game
        }
        val timeline = DuelTimeline(g.header, g.entries)
        listOf(0, 1, 31, 32, 33, 64, 70, g.entries.size).forEach { n ->
            assertEquals(g.stateAt(n), timeline.at(n).first, "at $n")
        }
        // Scrubbing backwards after forwards uses the snapshots and agrees.
        assertEquals(g.stateAt(40), timeline.at(40).first)
    }

    @Test
    fun anEditedLogSkipsWhatNoLongerFits() {
        var g = DuelGame.start(header())
        val hand = g.state.seats[0].hand
        val zone = Place.Zone(0, ZoneKind.MONSTER, 0)
        g = g.act(DuelAction.Move(hand[0], zone), 0).game
        g = g.act(DuelAction.Move(hand[0], Place.Pile(0, PileKind.GY)), 0).game
        // Delete the summon: the send still fits (from the hand), so nothing is refused.
        val edited = g.entries.filterIndexed { i, _ -> i != g.cursor - 2 }.mapIndexed { i, e -> e.copy(i = i) }
        assertTrue(DuelSetup.fold(g.header, edited).second.isEmpty())
        // Insert a second summon into the same zone before the first: that one is refused, not the duel.
        val clash = g.entries.toMutableList().apply {
            add(g.cursor - 2, DuelEntry(0, 0, 0, 99, DuelAction.Move(hand[1], zone)))
        }.mapIndexed { i, e -> e.copy(i = i) }
        val (_, refused) = DuelSetup.fold(g.header, clash)
        assertEquals(setOf(g.cursor - 1), refused)
    }
}
