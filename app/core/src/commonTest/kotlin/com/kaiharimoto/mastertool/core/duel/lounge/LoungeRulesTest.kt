package com.kaiharimoto.mastertool.core.duel.lounge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LoungeRulesTest {

    private fun Lounge.then(ask: LoungeAsk): Lounge = when (val r = LoungeRules.apply(this, ask)) {
        is LoungeResult.Ok -> r.lounge
        is LoungeResult.No -> fail("refused: ${r.why} ($ask)")
    }

    private fun Lounge.refuses(ask: LoungeAsk): String = when (val r = LoungeRules.apply(this, ask)) {
        is LoungeResult.Ok -> fail("allowed: $ask")
        is LoungeResult.No -> r.why
    }

    /** kai, Ash and Mira, in a room Ash made. */
    private fun three(): Lounge = Lounge()
        .then(LoungeAsk.Join("kai", "kai", host = true))
        .then(LoungeAsk.Join("ash", "Ash"))
        .then(LoungeAsk.Join("mira", "Mira"))
        .then(LoungeAsk.Create("ash", "r1", "Locals"))
        .then(LoungeAsk.Enter("kai", "r1"))
        .then(LoungeAsk.Enter("mira", "r1"))

    @Test
    fun nicknamesAreShortPlainAndOneEach() {
        assertEquals("Ash K", LoungeRules.nick("  Ash   K "))
        assertNull(LoungeRules.nick(""))
        assertNull(LoungeRules.nick("a".repeat(21)))
        assertNull(LoungeRules.nick("<script>"))
        val l = Lounge().then(LoungeAsk.Join("a", "Ash"))
        assertTrue("already" in l.refuses(LoungeAsk.Join("b", "ash")))
        // The same member coming back keeps the name.
        assertEquals("Ash", l.then(LoungeAsk.Join("a", "Ash")).member("a")?.nick)
    }

    @Test
    fun aRoomIsMadeEnteredAndClosedByItsMakerOrKai() {
        val l = three()
        assertEquals("r1", l.member("ash")?.room)
        assertTrue("maker" in l.refuses(LoungeAsk.Close("mira", "r1")))
        val closed = l.then(LoungeAsk.Close("kai", "r1"))
        assertTrue(closed.rooms.isEmpty())
        assertTrue(closed.members.all { it.room == null })
    }

    @Test
    fun twoSitAndAThirdWatches() {
        val l = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Sit("mira", 1, 0))
        val r = l.room("r1")!!
        assertEquals("ash", r.seats[0].member)
        assertEquals("mira", r.seats[1].member)
        assertTrue("sits there" in l.refuses(LoungeAsk.Sit("kai", 0, 0)))
        // Moving to the other seat before the duel is just sitting there.
        val moved = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Sit("ash", 1, 0))
        assertEquals(listOf(null, "ash"), moved.room("r1")!!.seats.map { it.member })
    }

    @Test
    fun theDuelBeginsWhenBothAreReady() {
        var l = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Sit("mira", 1, 0))
        assertFalse(l.room("r1")!!.canStart)
        l = l.then(LoungeAsk.Ready("ash", "d1", "Labrynth")).then(LoungeAsk.Ready("mira", "d2", "Snake-Eye"))
        assertTrue(l.room("r1")!!.canStart)
        l = l.then(LoungeAsk.Playing("r1", true))
        assertFalse(l.room("r1")!!.canStart)
        assertTrue("dealt" in l.refuses(LoungeAsk.Ready("ash", "d3", "Other")))
        // Walking across the table mid-duel is a swap, never a sit.
        l = l.then(LoungeAsk.Stand("mira"))
        assertTrue("swap" in l.refuses(LoungeAsk.Sit("ash", 1, 0)))
        // The duel over, both are asked to choose again.
        l = l.then(LoungeAsk.Playing("r1", false))
        assertTrue(l.room("r1")!!.seats.none { it.ready })
    }

    @Test
    fun aSwapIsAskedAndAnswered() {
        var l = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Sit("mira", 1, 0)).then(LoungeAsk.Playing("r1", true))
        l = l.then(LoungeAsk.AskSwap("ash"))
        assertEquals("ash", l.room("r1")!!.swapAsk)
        assertTrue("other player answers" in l.refuses(LoungeAsk.AnswerSwap("ash", true)))
        val no = l.then(LoungeAsk.AnswerSwap("mira", false))
        assertEquals(listOf("ash", "mira"), no.room("r1")!!.seats.map { it.member })
        val yes = l.then(LoungeAsk.AnswerSwap("mira", true))
        assertEquals(listOf("mira", "ash"), yes.room("r1")!!.seats.map { it.member })
        assertNull(yes.room("r1")!!.swapAsk)
    }

    @Test
    fun aDroppedPlayersSeatWaitsThenIsAnyonesWithItsHand() {
        var l = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Sit("mira", 1, 0)).then(LoungeAsk.Playing("r1", true))
        l = l.then(LoungeAsk.Drop("mira", now = 1_000))
        assertEquals(false, l.member("mira")?.online)
        assertTrue("waits for Mira" in l.refuses(LoungeAsk.Sit("kai", 1, 2_000)))
        // Back in time: the seat is theirs, held no longer.
        val back = l.then(LoungeAsk.Join("mira", "Mira"))
        assertNull(back.room("r1")!!.seats[1].heldUntil)
        assertEquals("mira", back.room("r1")!!.seats[1].member)
        // Not back: the hold runs out and kai takes the seat — and the duel's hand with it.
        val late = 1_000 + LoungeRules.HOLD_MS
        assertEquals("kai", l.then(LoungeAsk.Sit("kai", 1, late)).room("r1")!!.seats[1].member)
        assertTrue(l.then(LoungeAsk.Tick(late)).room("r1")!!.seats[1].empty)
    }

    @Test
    fun outOfADuelADropFreesTheSeatAtOnce() {
        val l = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Drop("ash", 5))
        assertTrue(l.room("r1")!!.seats[0].empty)
    }

    @Test
    fun aiSitsOnlyWhereKaiAllowsIt() {
        var l = three()
        assertTrue("kai turns it on" in l.refuses(LoungeAsk.SeatAi("ash", 1, true)))
        assertTrue("Only kai" in l.refuses(LoungeAsk.SetRoom("ash", "r1", ai = true)))
        l = l.then(LoungeAsk.SetRoom("kai", "r1", ai = true))
        assertTrue("deck" in l.refuses(LoungeAsk.SeatAi("ash", 1, true)))
        l = l.then(LoungeAsk.SeatAi("ash", 1, true, deck = "d1", deckName = "Ash's Labrynth"))
        assertEquals("ash", l.room("r1")!!.seats[1].aiDeckOf)
        assertTrue(l.room("r1")!!.seats[1].ai)
        assertTrue("Ai sits there" in l.refuses(LoungeAsk.Sit("mira", 1, 0)))
        // Ai is always ready: one person ready is enough.
        l = l.then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Ready("ash", "d1", "Labrynth"))
        assertTrue(l.room("r1")!!.canStart)
        // Turned off, Ai stands up.
        assertTrue(l.then(LoungeAsk.SetRoom("kai", "r1", ai = false)).room("r1")!!.seats[1].empty)
    }

    @Test
    fun walkingOutGivesUpTheSeat() {
        val l = three().then(LoungeAsk.Sit("ash", 0, 0)).then(LoungeAsk.Enter("ash", null))
        assertNull(l.member("ash")?.room)
        assertTrue(l.room("r1")!!.seats[0].empty)
    }

    @Test
    fun onlyKaiSendsPeopleAway() {
        val l = three().then(LoungeAsk.Sit("mira", 0, 0))
        assertTrue("Only kai" in l.refuses(LoungeAsk.Kick("ash", "mira")))
        val gone = l.then(LoungeAsk.Kick("kai", "mira"))
        assertNull(gone.member("mira"))
        assertTrue(gone.room("r1")!!.seats[0].empty)
    }

    @Test
    fun theLoungeHasLimits() {
        var l = Lounge().then(LoungeAsk.Join("k", "kai", host = true))
        repeat(LoungeRules.MAX_ROOMS) { l = l.then(LoungeAsk.Create("k", "r$it", "Room $it")) }
        assertTrue("close one" in l.refuses(LoungeAsk.Create("k", "rx", "One more")))
        var m = Lounge()
        repeat(LoungeRules.MAX_MEMBERS) { m = m.then(LoungeAsk.Join("m$it", "Member $it")) }
        assertNotNull(m.refuses(LoungeAsk.Join("late", "Late")))
    }
}
