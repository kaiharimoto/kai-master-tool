package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LoungeMatchTest {
    private fun Lounge.then(ask: LoungeAsk): Lounge = when (val r = LoungeRules.apply(this, ask)) {
        is LoungeResult.Ok -> r.lounge
        is LoungeResult.No -> fail("refused: ${r.why} ($ask)")
    }

    private fun Lounge.refuses(ask: LoungeAsk): String = when (val r = LoungeRules.apply(this, ask)) {
        is LoungeResult.Ok -> fail("allowed: $ask")
        is LoungeResult.No -> r.why
    }

    /** Ash and Mira seated at Ash's room, ready; kai watching. */
    private fun seated(): Lounge = Lounge()
        .then(LoungeAsk.Join("kai", "kai", host = true))
        .then(LoungeAsk.Join("ash", "Ash"))
        .then(LoungeAsk.Join("mira", "Mira"))
        .then(LoungeAsk.Create("ash", "r1", "Locals"))
        .then(LoungeAsk.Enter("kai", "r1"))
        .then(LoungeAsk.Enter("mira", "r1"))
        .then(LoungeAsk.Sit("ash", 0, 0))
        .then(LoungeAsk.Sit("mira", 1, 0))

    @Test
    fun theLoserChoosesAndTwoWinsTakeTheMatch() {
        var m = LoungeMatch.start(3)
        assertFalse(m.over)
        m = LoungeMatch.after(m, winner = 0, wentFirst = 0)
        assertEquals(listOf(1, 0), m.wins)
        assertTrue(m.siding)
        assertEquals(1, m.chooser)
        assertEquals(2, m.game)
        // The chooser says go second: seat 0 goes first. Ash's side alone is not enough.
        m = LoungeMatch.sided(m, 1, first = false)
        assertFalse(LoungeMatch.ready(m))
        m = LoungeMatch.sided(m, 0, first = true)
        assertTrue(LoungeMatch.ready(m))
        assertEquals(0, LoungeMatch.firstNext(m), "only the chooser's word counts")
        // A draw: the player who went second chooses.
        m = LoungeMatch.after(m.copy(siding = false), winner = null, wentFirst = 0)
        assertEquals(1, m.chooser)
        assertEquals(listOf(1, 0), m.wins)
        m = LoungeMatch.after(m.copy(siding = false), winner = 0, wentFirst = 1)
        assertTrue(m.over)
        assertEquals(0, m.winner)
        assertFalse(m.siding)
        assertEquals("Ash won the match, Ash 2 – 0 Mira", LoungeMatch.words(m, listOf("Ash", "Mira")))
        // One game is a match of one: over at once, no siding.
        val single = LoungeMatch.after(LoungeMatch.start(1), winner = 1, wentFirst = 0)
        assertTrue(single.over)
        assertFalse(single.siding)
        // A chooser who never says goes first.
        assertEquals(1, LoungeMatch.firstNext(LoungeMatch.after(LoungeMatch.start(3), 0, 0)))
    }

    @Test
    fun sidingIsCardForCard() {
        val c = { n: Int -> CardId(n) }
        val extra = setOf(900, 901)
        val isExtra = { id: CardId -> if (id.value == 0) null else id.value in extra }
        val kept = Deck(main = List(40) { c(1 + it % 10) }, extra = listOf(c(900)), side = listOf(c(50), c(51), c(901)))
        // A card for a card: one main card out, a side card in.
        val swapped = Deck(main = kept.main.drop(1) + c(50), extra = kept.extra, side = listOf(c(1), c(51), c(901)))
        assertNull(LoungeMatch.check(kept, swapped, isExtra))
        // An Extra Deck card from the side for one in the Extra Deck.
        assertNull(LoungeMatch.check(kept, Deck(kept.main, listOf(c(901)), listOf(c(50), c(51), c(900))), isExtra))
        assertTrue("come in" in LoungeMatch.check(kept, swapped.copy(main = swapped.main + c(77)), isExtra)!!)
        assertTrue("stays at 3" in LoungeMatch.check(kept, Deck(kept.main + c(50), kept.extra, listOf(c(51), c(901))), isExtra)!!)
        assertTrue("at least 40" in LoungeMatch.check(kept, Deck(kept.main.drop(1), kept.extra + c(1), kept.side), isExtra)!!)
        assertTrue("Only Extra Deck cards" in LoungeMatch.check(kept, Deck(kept.main, listOf(c(50)), listOf(c(900), c(51), c(901))), isExtra)!!)
        assertTrue("Extra Deck card cannot" in LoungeMatch.check(kept, Deck(kept.main.drop(1) + c(901), kept.extra, listOf(c(1), c(50), c(51))), isExtra)!!)
        // A card the pool does not know is not judged.
        val unknown = Deck(main = List(40) { c(0) }, side = listOf(c(5)))
        assertNull(LoungeMatch.check(unknown, Deck(main = List(39) { c(0) } + c(5), side = listOf(c(0))), isExtra))
    }

    @Test
    fun aRoomPlaysTheBestOfThreeItsMakerChose() {
        var l = seated()
        assertTrue("maker" in l.refuses(LoungeAsk.SetRoom("mira", "r1", bestOf = 3)))
        assertTrue("one game or" in l.refuses(LoungeAsk.SetRoom("ash", "r1", bestOf = 5)))
        assertTrue("Only kai" in l.refuses(LoungeAsk.SetRoom("ash", "r1", legalOnly = true)))
        l = l.then(LoungeAsk.SetRoom("ash", "r1", bestOf = 3)).then(LoungeAsk.SetRoom("kai", "r1", legalOnly = true))
        assertTrue(l.room("r1")!!.legalOnly)
        l = l.then(LoungeAsk.Ready("ash", "d1", "Ash's")).then(LoungeAsk.Ready("mira", "d2", "Mira's")).then(LoungeAsk.Playing("r1", true))
        val m = assertNotNull(l.room("r1")!!.match)
        assertEquals(3, m.bestOf)
        assertTrue("A match is on" in l.refuses(LoungeAsk.SetRoom("ash", "r1", bestOf = 1)))
        // Game one over: the host's word, the table stops, and the players side.
        l = l.then(LoungeAsk.Match("r1", LoungeMatch.after(m, winner = 1, wentFirst = 1))).then(LoungeAsk.Playing("r1", false))
        val r = l.room("r1")!!
        assertTrue(r.siding)
        assertFalse(r.canStart)
        assertTrue("Side your deck" in l.refuses(LoungeAsk.Ready("ash", "d1", "Ash's")))
        assertTrue("Siding is between" in seated().refuses(LoungeAsk.Sided("ash")))
        // Ash dropping while siding keeps the seat, as in a duel.
        val dropped = l.then(LoungeAsk.Drop("ash", 1_000))
        assertNotNull(dropped.room("r1")!!.seats[0].heldUntil)
        assertNotNull(dropped.room("r1")!!.match)
        l = l.then(LoungeAsk.Sided("ash", first = true)).then(LoungeAsk.Sided("mira", first = true))
        assertTrue(LoungeMatch.ready(l.room("r1")!!.match!!))
        assertEquals(0, LoungeMatch.firstNext(l.room("r1")!!.match!!), "Ash lost, chose, and goes first")
        // The next deal keeps the match going.
        l = l.then(LoungeAsk.Match("r1", l.room("r1")!!.match!!.copy(siding = false))).then(LoungeAsk.Playing("r1", true))
        assertEquals(listOf(0, 1), l.room("r1")!!.match!!.wins)
        // Someone else at a seat gives the match up.
        l = l.then(LoungeAsk.Playing("r1", false)).then(LoungeAsk.Stand("mira"))
        assertNull(l.room("r1")!!.match)
    }

    @Test
    fun aiKeepsItsDeckBetweenGames() {
        var l = seated().then(LoungeAsk.SetRoom("kai", "r1", ai = true)).then(LoungeAsk.SetRoom("ash", "r1", bestOf = 3))
            .then(LoungeAsk.Stand("mira")).then(LoungeAsk.SeatAi("ash", 1, true, "d1", "Ash's"))
            .then(LoungeAsk.Ready("ash", "d1", "Ash's")).then(LoungeAsk.Playing("r1", true))
        l = l.then(LoungeAsk.Match("r1", LoungeMatch.after(l.room("r1")!!.match!!, winner = 0, wentFirst = 0))).then(LoungeAsk.Playing("r1", false))
            .then(LoungeAsk.AiSided("r1"))
        val m = l.room("r1")!!.match!!
        assertEquals(listOf(false, true), m.sided)
        assertEquals(1, LoungeMatch.firstNext(m), "Ai lost and chooses to go first")
    }
}
