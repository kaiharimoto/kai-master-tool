package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.header
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.net.DuelHost
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import com.kaiharimoto.mastertool.core.duel.replay.Replays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
}
