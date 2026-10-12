package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.lounge.RoomTable
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.net.HelloGate
import com.kaiharimoto.mastertool.core.duel.net.Wire
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the 1.0.85 red team found in the duel's core, each held shut. */
class DuelRedTeamTest {
    private val ash = uid(0, 0)
    private val droll = uid(0, 1)
    private val pot = uid(0, 2)
    private val zeus = uid(0, 40)

    private fun m(seat: Int, i: Int) = Place.Zone(seat, ZoneKind.MONSTER, i)

    @Test
    fun aGuestCannotRevealTheHostsDeckHandOrSetCards() {
        val s = ok(bare(), DuelAction.Draw(0, 2))
        val deckTop = DuelMirror.deckRef(0, 0)
        val (none, why) = DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Reveal(1, listOf(deckTop), to = 1)))
        assertNull(none)
        assertEquals("You can reveal only your own cards", why)
        val handVeil = DuelView.veil(7L, ash, s.epoch[ash] ?: 0)
        assertNull(DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Reveal(1, listOf(handVeil), to = 1))).first)
        // Its own card it may reveal.
        val own = ok(s, DuelAction.Draw(1, 1), by = 1)
        assertEquals(1, DuelHost.resolve(own, 1, 7L, listOf(DuelAction.Reveal(1, listOf(uid(1, 0))))).first?.size)
    }

    @Test
    fun aGuestMovesThePhaseOnlyOnItsOwnTurnAndAnswersOnlyItsOwnWindow() {
        val s = bare()
        assertEquals("It is not your turn: ask them to move on", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Phase(DuelPhase.MAIN1))).second)
        assertEquals("It is not your turn: ask them to move on", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.EndTurn)).second)
        val windowed = s.copy(window = ResponseWindow(1, 0, 0, "Move"))
        assertEquals("That window waits on the other player", DuelHost.resolve(windowed, 1, 7L, listOf(DuelAction.Answer(1, false))).second)
    }

    @Test
    fun aCardLeavingTheHandGivesEveryCardLeftANewVeilAndTheOtherSeatSeesNoOrder() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        val before = DuelView.of(s, 1, 7L).seats[0].hand.map { it.ref }
        s = ok(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_DOWN_ATK))
        val after = DuelView.of(s, 1, 7L).seats[0].hand.map { it.ref }
        assertEquals(2, after.size)
        assertTrue(after.none { it in before }, "the veils left in the hand are all new")
        assertEquals(after.sorted(), after)
        // Its owner still sees its hand as it is, in order.
        assertEquals(listOf(ash, droll), DuelView.of(s, 0, 7L).seats[0].hand.map { it.ref })
    }

    @Test
    fun theTurnTallyNamesNoCardTheViewerCouldNotSee() {
        var g = DuelGame(header(), emptyList(), 0, DuelSetup.initial(header()), 0)
        g = g.act(DuelAction.Draw(0, 3), 0).game
        g = g.act(DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK), 0).game
        // A set card linked face-down: its name is its controller's.
        g = g.act(DuelAction.ChainAdd(0, pot), 0).game
        assertEquals(mapOf("a card" to 1), DuelTally.of(g, catalog, viewer = 1).activations[0])
        assertEquals(mapOf("Pot of Prosperity" to 1), DuelTally.of(g, catalog, viewer = 0).activations[0])
    }

    @Test
    fun aSetCardDroppedOnTheChainIsTurnedFaceUp() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        s = ok(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK))
        val intent = DuelDrop.intent(s, pot, DropSpot.Chain, catalog)
        assertTrue(intent.actions.first() is DuelAction.Position)
        val after = DuelRules.applyAll(s, intent.actions, 0).first!!
        assertTrue(DuelSight.sees(after, pot, 1))
    }

    @Test
    fun aMonsterThatLeavesTheFieldNoLongerAttacks() {
        var s = ok(bare(), DuelAction.Move(zeus, m(0, 0), CardPosition.FACE_UP_ATK))
        s = ok(s, DuelAction.Phase(DuelPhase.BATTLE))
        s = ok(s, DuelAction.Attack(0, zeus, null))
        assertEquals(1, s.attacks.size)
        s = ok(s, DuelAction.Move(zeus, Place.Pile(0, PileKind.EXTRA)))
        assertTrue(s.attacks.isEmpty())
    }

    @Test
    fun aMoveInsertedInsideAGestureLeavesItsTailAGroupOfItsOwn() {
        val r = DuelRecord(
            header(),
            listOf(
                DuelEntry(0, 0L, 0, 0, DuelAction.Draw(0, 1)),
                DuelEntry(1, 0L, 0, 1, DuelAction.Move(ash, Place.Pile(0, PileKind.GY), how = "activate")),
                DuelEntry(2, 0L, 0, 1, DuelAction.ChainAdd(0, ash)),
            ),
            3,
        )
        val out = Replays.insert(r, 2, listOf(DuelAction.Chat(0, "hm")), 0)
        val groups = out.entries.map { it.group }
        assertEquals(4, groups.toSet().size, "draw, the gesture's head, the insert, the gesture's tail: $groups")
        assertNotEquals(groups[2], groups[3])
    }

    @Test
    fun aVeilNeverFallsAmongTheGuestsDeckReferences() {
        repeat(5000) { u ->
            val v = DuelView.veil(123L, u, 0)
            assertTrue(v < 0)
            assertNull(DuelMirror.deckOf(v))
        }
        assertFalse(DuelSight.sees(bare(), ash, 1))
    }

    // ---- the second pass (1.0.85, after the triggers' red team) ----------------------------------------------

    @Test
    fun anArrowAtAHandCardIsGoneWhenTheHandIsReveiled() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        s = ok(s, DuelAction.Target(1, null, listOf(ash)), by = 1)
        assertEquals(1, s.arrows.size)
        s = ok(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK))
        assertTrue(s.arrows.isEmpty(), "an arrow kept would follow the card through its new veil")
    }

    @Test
    fun aGuestCannotTakeFlipOrTargetTheHostsHiddenCards() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        s = ok(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK))
        val setVeil = DuelView.veil(7L, pot, s.epoch[pot] ?: 0)
        assertEquals("That card is not yours to take", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Move(setVeil, Place.Zone(1, ZoneKind.SPELL, 0)))).second)
        assertEquals("That card is not yours to take", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Move(DuelMirror.deckRef(0, 0), Place.Pile(1, PileKind.HAND)))).second)
        assertEquals("Only its controller turns that card face-up", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Position(setVeil, CardPosition.FACE_UP_ATK))).second)
        val handVeil = DuelView.veil(7L, ash, s.epoch[ash] ?: 0)
        assertEquals("A card in their hand or Deck cannot be targeted", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Target(1, null, listOf(handVeil)))).second)
        // Destroying the Set card is still a guest's to do: to its owner's graveyard.
        assertEquals(1, DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Move(setVeil, Place.Pile(1, PileKind.GY)))).first?.size)
    }

    @Test
    fun twoEndTurnsInOneIntentCannotEndTheHostsTurn() {
        val guestsTurn = bare().copy(active = 1)
        assertEquals("It is not your turn: ask them to move on", DuelHost.resolve(guestsTurn, 1, 7L, listOf(DuelAction.EndTurn, DuelAction.EndTurn)).second)
        assertEquals(1, DuelHost.resolve(guestsTurn, 1, 7L, listOf(DuelAction.EndTurn)).first?.size)
    }

    @Test
    fun aGuestDraggingTheHostsSetCardToTheChainDoesNotFlipIt() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        s = ok(s, DuelAction.Move(pot, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK))
        val intent = DuelDrop.intent(s, pot, DropSpot.Chain, catalog, actor = 1)
        assertTrue(intent.actions.none { it is DuelAction.Position })
    }

    @Test
    fun onlyTheTurnPlayerAttacks() {
        var s = ok(bare(), DuelAction.Move(zeus, m(0, 0), CardPosition.FACE_UP_ATK))
        s = ok(s, DuelAction.Phase(DuelPhase.BATTLE))
        assertTrue(DuelVerbs.canAttack(s, 0, zeus))
        // The same monster in the other player's Battle Phase does not attack: an armed attack left over must not fire.
        val theirs = s.copy(active = 1)
        assertFalse(DuelVerbs.canAttack(theirs, 0, zeus))
    }

    // ---- the 2026-10 red team: what a seat over the network may send ---------------------------------------------

    private val guest = Provenance(by = Provenance.GUEST)

    /** A Lounge room's table: its seats' intents go through [DuelHost.resolve], as a LAN guest's do. */
    private fun room() = RoomTable.start(header(), at = 0L)

    private fun lp(t: RoomTable, seat: Int) = t.game.state.seats[seat].lp

    @Test
    fun aSeatOverTheNetworkSetsOnlyItsOwnLifePointsAndChangesTheirsByAnAmount() {
        val t = room()
        val start = lp(t, 0)
        // Seat 1 setting seat 0's life points to 0 would be a duel won by typing it: refused, the table unchanged.
        val zero = t.intent(1, Wire.Intent(1, listOf(DuelAction.Lp(0, set = 0))), 1L, guest)
        assertEquals(DuelHost.NOT_YOUR_LP, zero.refused)
        assertEquals(t.game, zero.table.game)
        assertEquals(start, lp(zero.table, 0))
        // Damage by an amount is a manual table's to deal ("lp opp -1000"): it stands.
        val burn = t.intent(1, Wire.Intent(2, listOf(DuelAction.Lp(0, delta = -1000))), 2L, guest)
        assertNull(burn.refused)
        assertEquals(start - 1000, lp(burn.table, 0))
        // Its own it may set outright.
        val own = burn.table.intent(1, Wire.Intent(3, listOf(DuelAction.Lp(1, set = 4000))), 3L, guest)
        assertNull(own.refused)
        assertEquals(4000, lp(own.table, 1))
        assertEquals(start - 1000, lp(own.table, 0))
    }

    @Test
    fun aSeatOverTheNetworkWritesShortWordsAndAFewHundredMovesAtMost() {
        val s = bare()
        val long = "x".repeat(10_000)
        val said = assertNotNull(DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Chat(1, long), DuelAction.Note(long, 1), DuelAction.Lock(1, long))).first)
        assertEquals(DuelHost.MAX_TEXT, (said[0] as DuelAction.Chat).text.length)
        assertEquals(DuelHost.MAX_TEXT, (said[1] as DuelAction.Note).text.length)
        assertEquals(DuelHost.MAX_TEXT, (said[2] as DuelAction.Lock).text.length)
        // A link's note and a token's name are cut the same way; short words pass as they came.
        val named = assertNotNull(DuelHost.resolve(s, 1, 7L, listOf(DuelAction.ChainAdd(1, note = long), DuelAction.Chat(1, "gg"))).first)
        assertEquals(DuelHost.MAX_TEXT, (named[0] as DuelAction.ChainAdd).note.length)
        assertEquals("gg", (named[1] as DuelAction.Chat).text)
        // A character outside the BMP is never cut in half.
        assertEquals(DuelHost.MAX_TEXT - 1, DuelHost.clip("a".repeat(DuelHost.MAX_TEXT - 1) + "😀").length)

        // A flood of moves is refused whole; the most a gesture needs passes.
        val flood = List(DuelHost.MAX_ACTIONS + 1) { DuelAction.Chat(1, "hi") }
        val (none, why) = DuelHost.resolve(s, 1, 7L, flood)
        assertNull(none)
        assertEquals("That is too many moves at once: at most ${DuelHost.MAX_ACTIONS}", why)
        assertEquals(DuelHost.MAX_ACTIONS, DuelHost.resolve(s, 1, 7L, flood.take(DuelHost.MAX_ACTIONS)).first?.size)
        // A move of another version is no player's: only bytes for the log to keep.
        assertEquals("That move is from another version of the duel", DuelHost.resolve(s, 1, 7L, listOf(DuelAction.Unknown())).second)
        // A list naming more cards than the table holds only repeats itself into the log.
        val own = ok(s, DuelAction.Draw(1, 1), by = 1)
        val again = List(own.cards.size + 1) { uid(1, 0) }
        assertEquals("That names more cards than the table holds", DuelHost.resolve(own, 1, 7L, listOf(DuelAction.Reveal(1, again))).second)

        // The host's own words never pass through here: its table keeps what it says whole.
        val g = DuelGame(header(), emptyList(), 0, bare(), 0)
        val mine = DuelHost.act(g, 0, listOf(DuelAction.Chat(0, long)), windows = emptyMap())
        assertEquals(long, (mine.game.entries.last().action as DuelAction.Chat).text)
    }

    @Test
    fun aDeviceGuessingATablesCodeWaitsAfterFiveWrongOnes() {
        val gate = HelloGate()
        val secret = 40_123
        fun hello(code: Int) = Wire.Hello(name = "Guest", main = List(40) { DuelFixtures.FILLER }, secret = code)
        val phone = "192.168.1.9"
        repeat(5) { assertEquals("The code is not this table's.", gate.judge(hello(it + 1), secret, phone, 0L)) }
        // Five wrong: now even the right code waits, and the wait says nothing of whether it was right.
        val waits = assertNotNull(gate.judge(hello(secret), secret, phone, 1_000L))
        assertTrue(waits.startsWith("Too many wrong codes from this device"), waits)
        assertEquals(waits, gate.judge(hello(6), secret, phone, 1_000L))
        // Another device on the same Wi-Fi is not held up by it.
        assertNull(gate.judge(hello(secret), secret, "192.168.1.20", 1_000L))
        // A minute on, the right code sits, and the count starts afresh.
        assertNull(gate.judge(hello(secret), secret, phone, HelloGate.FIRST_WAIT_MS))
        // An app of another version is told so, and that is no guess.
        repeat(20) { assertTrue("version" in assertNotNull(gate.judge(hello(1).copy(proto = Wire.PROTO + 1), secret, "192.168.1.30", 0L))) }
        assertNull(gate.judge(hello(secret), secret, "192.168.1.30", 0L))

        // A day of guessing as fast as the table answers — a try a second — reads a few dozen codes of the 65,535.
        val tries = HelloGate()
        val guess = hello(1)
        var read = 0
        var t = 0L
        while (t < 24 * 60 * 60_000L) {
            val why = tries.judge(guess, secret, "192.168.1.66", t)
            if (why == "The code is not this table's.") read++
            t += 1_000L
        }
        assertTrue(read < 50, "codes read in a day: $read")
    }

    @Test
    fun aHelloWithADeckPastTheTablesSizesIsTurnedAway() {
        val big = Wire.Hello(main = List(DuelHost.MAX_MAIN + 1) { DuelFixtures.FILLER }, secret = 9)
        assertEquals(DuelHost.tooLarge(DuelHost.MAX_MAIN + 1, 0), DuelHost.knows(big, 9))
        assertNull(DuelHost.knows(big.copy(main = List(DuelHost.MAX_MAIN) { DuelFixtures.FILLER }), 9))
        // Its names are kept in the duel's header and its replay: cut short, and a blank one is a Guest.
        val seat = DuelHost.guestSeat(big.copy(name = "n".repeat(100_000), deckName = "d".repeat(100_000)))
        assertEquals(DuelHost.MAX_NAME, seat.name.length)
        assertEquals(DuelHost.MAX_NAME, seat.deckName.length)
        assertEquals("Guest", DuelHost.guestSeat(big.copy(name = "   ")).name)
    }
}
