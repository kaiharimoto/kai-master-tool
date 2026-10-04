package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.ai.AiTable
import com.kaiharimoto.mastertool.core.duel.ai.ComboRecorder
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.replay.Past
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The duel leads of `docs/AI-INTELLIGENCE.md` §1, verified and held shut (Phase C, stage 1). */
class DuelLeadsTest {
    private val ash = uid(0, 0)
    private val pot = uid(0, 2)

    /** Seat 0 holds Ash, Droll and Pot, with Pot then Set in its S/T 1: Ai sits at seat 1 and sees none of them. */
    private fun table(): DuelState {
        val s = ok(bare(), DuelAction.Draw(0, 3))
        return ok(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK))
    }

    @Test
    fun aisOwnMovesNeverRevealFlipOrTakeThePersonsHiddenCards() {
        val s = table()
        // The lead held: the command line lets a seat name the other seat's hidden cards by coordinate for the verbs that
        // need not know them, and these plan — so Ai's lines are held to what the network's guest is held to.
        val reveal = ComboRunner.plan(s, 1, listOf("reveal oh1"), catalog)
        assertTrue(reveal.ok, reveal.problem)
        assertEquals("Step 1 (“reveal oh1”): ${DuelReach.REVEAL}", ComboRunner.reach(s, 1, reveal))
        val flip = ComboRunner.plan(s, 1, listOf("flip os1"), catalog)
        assertTrue(flip.ok, flip.problem)
        assertEquals("Step 1 (“flip os1”): ${DuelReach.FLIP}", ComboRunner.reach(s, 1, flip))
        // Taking one: to its own side, its hand, or under its card.
        assertEquals(DuelReach.TAKE, DuelReach.check(s, 1, listOf(DuelAction.Move(pot, Place.Zone(1, ZoneKind.SPELL, 0)))))
        assertEquals(DuelReach.TAKE, DuelReach.check(s, 1, listOf(DuelAction.Move(ash, Place.Pile(1, PileKind.HAND)))))
        assertEquals(DuelReach.TARGET, DuelReach.check(s, 1, listOf(DuelAction.ChainAdd(1, null, targets = listOf(ash)))))
        // What a player may do stays Ai's: destroy the Set card, to its owner's GY; target it on the field.
        val destroy = ComboRunner.plan(s, 1, listOf("g os1"), catalog)
        assertTrue(destroy.ok, destroy.problem)
        assertNull(ComboRunner.reach(s, 1, destroy))
        assertNull(DuelReach.check(s, 1, listOf(DuelAction.Target(1, null, listOf(pot)))))
        // Its own seat's cards are its own to show.
        val own = ok(s, DuelAction.Draw(1, 1), by = 1)
        assertNull(DuelReach.check(own, 1, listOf(DuelAction.Reveal(1, listOf(uid(1, 0))))))
    }

    @Test
    fun theGuestsSeatOnANetworkedTableIsTheGuests() {
        // The lead held: `DuelPrefs.aiSeat` is 1 by default and the guest sits at 1, and Ai's tools never asked whether the
        // table was networked — duel_state read the guest's hand, duel_act moved its cards through the host's authority.
        AiTable.TABLE_TOOLS.forEach { assertNotNull(AiTable.refusal(it, null, networked = true), it) }
        assertNotNull(AiTable.refusal("duel_combo", "run", networked = true))
        assertNotNull(AiTable.refusal("duel_combo", "record", networked = true))
        // What touches no seat stays open; a table of its own is Ai's as before.
        assertNull(AiTable.refusal("duel_combo", "list", networked = true))
        assertNull(AiTable.refusal("duel_ruling", "list", networked = true))
        assertNull(AiTable.refusal("duel_records", null, networked = true))
        assertNull(AiTable.refusal("duel_act", null, networked = false))
    }

    @Test
    fun comboRecordsNameNoHiddenCard() {
        val s = table()
        // Seat 1 puts seat 0's Set card back on top of its Deck: it never saw it, before or after.
        val back = DuelEntry(0, 0L, 1, 0, DuelAction.Move(pot, Place.Pile(0, PileKind.DECK, Place.TOP)))
        assertEquals(listOf("Pot of Prosperity to deck"), ComboRecorder.steps(s, listOf(back), catalog), "unscoped, as before")
        assertEquals(listOf("os1 to deck"), ComboRecorder.steps(s, listOf(back), catalog, seat = 1, secret = 42L))
        // Destroyed, it is face-up in the GY for both: named.
        val gone = DuelEntry(0, 0L, 1, 0, DuelAction.Move(pot, Place.Pile(0, PileKind.GY)))
        assertEquals(listOf("Pot of Prosperity to gy"), ComboRecorder.steps(s, listOf(gone), catalog, seat = 1, secret = 42L))
        // A card of its own Deck it knows by the list: named.
        val mine = DuelEntry(0, 0L, 0, 0, DuelAction.Move(uid(0, 4), Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_DOWN_ATK))
        assertEquals(listOf("bfd Pendulum Pal from deck"), ComboRecorder.steps(s, listOf(mine), catalog, seat = 0, secret = 42L))
        // A card hidden and with no place to call it by (the other seat's Deck): the step is left out, never named.
        val theirDeck = DuelEntry(0, 0L, 1, 0, DuelAction.Move(uid(0, 5), Place.Pile(0, PileKind.GY)))
        assertEquals(listOf("Filler from deck to gy"), ComboRecorder.steps(s, listOf(theirDeck), catalog, seat = 1, secret = 42L), "milled face-up: public")
        val banishedDown = DuelEntry(0, 0L, 1, 0, DuelAction.Move(uid(0, 5), Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_DOWN_ATK))
        assertEquals(emptyList(), ComboRecorder.steps(s, listOf(banishedDown), catalog, seat = 1, secret = 42L))
    }

    /** A dealt duel, then three turns, each opening with its draw. */
    private fun played(): DuelGame {
        var g = DuelGame.start(header())
        fun act(a: DuelAction, seat: Int) {
            val r = g.act(a, seat)
            assertTrue(r.ok, r.problem)
            g = r.game
        }
        act(DuelAction.EndTurn, 0)
        act(DuelAction.Draw(1, 1), 1)
        act(DuelAction.EndTurn, 1)
        act(DuelAction.Draw(0, 1), 0)
        return g
    }

    @Test
    fun aMoveInThePastCannotReDealTheDrawsSince() {
        val g = played()
        val at = g.floor
        val before = g.stateAt(at)
        val top = before.seats[0].deck.first()
        val bottom = before.seats[0].deck.last()
        // The lead held: searching the card turn 3's draw took, back in turn 1, made turn 3 draw another.
        val search = listOf(DuelAction.Move(top, Place.Pile(0, PileKind.HAND), how = "search"))
        assertNotNull(Past.redeals(g.header, g.played, at, search, 0))
        // So did a shuffle put into the past.
        val shuffle = Past.stamp(g.header, g.played, at, listOf(DuelAction.Shuffle(0)))
        assertNotNull(Past.redeals(g.header, g.played, at, shuffle, 0))
        // A move that leaves every later draw as it was is a move in a phase gone by, as before.
        assertNull(Past.redeals(g.header, g.played, at, listOf(DuelAction.Lp(0, -500)), 0))
        assertNull(Past.redeals(g.header, g.played, at, listOf(DuelAction.Move(bottom, Place.Pile(0, PileKind.HAND), how = "search")), 0))
        // And what it leaves to chance is the same each time it is put in, chat or no chat after it: nothing to fish with.
        val chatted = g.act(DuelAction.Chat(0, "hm"), 0).game
        assertEquals(shuffle, Past.stamp(chatted.header, chatted.played, at, listOf(DuelAction.Shuffle(0))))
    }
}
