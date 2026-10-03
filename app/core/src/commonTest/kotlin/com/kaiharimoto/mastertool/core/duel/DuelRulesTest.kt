package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ASH
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.refused
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelRulesTest {
    private val ash = uid(0, 0)
    private val droll = uid(0, 1)
    private val pot = uid(0, 2)
    private val zeus = uid(0, 40)
    private val rivalCard = uid(1, 0)

    private fun m(seat: Int, i: Int) = Place.Zone(seat, ZoneKind.MONSTER, i)
    private fun st(seat: Int, i: Int) = Place.Zone(seat, ZoneKind.SPELL, i)

    @Test
    fun theDealPutsEveryCardInItsOwnersDeckOrExtraDeck() {
        val s = bare()
        assertEquals(40, s.seats[0].deck.size)
        assertEquals(2, s.seats[0].extra.size)
        assertEquals(ASH, s.cards.getValue(s.seats[0].deck.first()).code)
        assertEquals(1, s.cards.getValue(rivalCard).owner)
    }

    @Test
    fun drawingTakesFromTheTopAndAnEmptyDeckRefuses() {
        val s = ok(bare(), DuelAction.Draw(0, 2))
        assertEquals(listOf(ash, droll), s.seats[0].hand)
        assertEquals(38, s.seats[0].deck.size)
        assertTrue(refused(s, DuelAction.Draw(0, 39)).contains("38"))
    }

    @Test
    fun aZoneHoldsOneCard() {
        var s = ok(bare(), DuelAction.Draw(0, 2))
        s = ok(s, DuelAction.Move(ash, m(0, 2), CardPosition.FACE_UP_ATK))
        assertEquals(ash, s.at(m(0, 2)))
        assertEquals("That zone is taken", refused(s, DuelAction.Move(droll, m(0, 2))))
    }

    @Test
    fun theExtraMonsterZonesAreSharedBetweenTheSeats() {
        var s = ok(bare(), DuelAction.Move(zeus, Place.Zone(0, ZoneKind.EMZ, 0)))
        assertEquals(zeus, s.emz[0])
        val rivalZeus = uid(1, 40)
        assertEquals("That zone is taken", refused(s, DuelAction.Move(rivalZeus, Place.Zone(1, ZoneKind.EMZ, 0))))
        s = ok(s, DuelAction.Move(rivalZeus, Place.Zone(1, ZoneKind.EMZ, 1)), by = 1)
        assertEquals(1, s.cards.getValue(rivalZeus).controller)
    }

    @Test
    fun aCardGoesToItsOwnersGraveyardWhoeverControlsIt() {
        var s = ok(bare(), DuelAction.Draw(0, 1))
        s = ok(s, DuelAction.Move(ash, m(1, 0)))
        assertEquals(1, s.cards.getValue(ash).controller)
        s = ok(s, DuelAction.Move(ash, Place.Pile(1, PileKind.GY)))
        assertEquals(listOf(ash), s.seats[0].gy)
        assertTrue(s.seats[1].gy.isEmpty())
        assertEquals(0, s.cards.getValue(ash).controller)
    }

    @Test
    fun aTokenLeavingTheFieldLeavesTheDuel() {
        var s = ok(bare(), DuelAction.Token(0, m(0, 0)))
        val token = s.at(m(0, 0))!!
        assertTrue(s.cards.getValue(token).token)
        s = ok(s, DuelAction.Move(token, Place.Pile(0, PileKind.GY)))
        assertNull(s.cards[token])
        assertTrue(s.seats[0].gy.isEmpty())
    }

    @Test
    fun materialsFollowTheirCardAndGoToTheGraveyardWhenItLeaves() {
        var s = ok(bare(), DuelAction.Draw(0, 2))
        s = ok(s, DuelAction.Move(zeus, m(0, 1)))
        s = ok(s, DuelAction.Move(ash, Place.Under(zeus)))
        s = ok(s, DuelAction.Move(droll, Place.Under(zeus)))
        assertEquals(listOf(ash, droll), s.cards.getValue(zeus).under)
        assertTrue(s.seats[0].hand.isEmpty())
        // Moving the Xyz to another zone keeps its materials.
        s = ok(s, DuelAction.Move(zeus, m(0, 3)))
        assertEquals(listOf(ash, droll), s.cards.getValue(zeus).under)
        // Detaching one is a move out from under it.
        s = ok(s, DuelAction.Move(ash, Place.Pile(0, PileKind.GY)))
        assertEquals(listOf(droll), s.cards.getValue(zeus).under)
        // The Xyz leaves: its last material goes to the graveyard with it.
        s = ok(s, DuelAction.Move(zeus, Place.Pile(0, PileKind.EXTRA)))
        assertEquals(listOf(droll, ash), s.seats[0].gy)
        assertTrue(zeus in s.seats[0].extra)
    }

    @Test
    fun aMaterialNeedsAHostOnTheField() {
        val s = ok(bare(), DuelAction.Draw(0, 2))
        assertEquals("Materials go under a card on the field", refused(s, DuelAction.Move(ash, Place.Under(droll))))
        assertEquals("A card cannot be its own material", refused(s, DuelAction.Move(ash, Place.Under(ash))))
    }

    @Test
    fun positionsAreNormalisedByTheZone() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        s = ok(s, DuelAction.Move(pot, st(0, 1), CardPosition.FACE_DOWN_DEF))
        assertEquals(CardPosition.FACE_DOWN_ATK, s.cards.getValue(pot).pos)
        s = ok(s, DuelAction.Move(ash, m(0, 0)))
        assertEquals(CardPosition.FACE_UP_ATK, s.cards.getValue(ash).pos)
        s = ok(s, DuelAction.Move(ash, Place.Pile(0, PileKind.DECK, Place.BOTTOM)))
        assertEquals(CardPosition.FACE_DOWN_DEF, s.cards.getValue(ash).pos)
        assertEquals(ash, s.seats[0].deck.last())
    }

    @Test
    fun countersLiveOnTheFieldAndGoWithTheCard() {
        var s = ok(bare(), DuelAction.Draw(0, 1))
        assertEquals("Counters sit on cards on the field", refused(s, DuelAction.Counter(ash, 1)))
        s = ok(s, DuelAction.Move(ash, m(0, 0)))
        s = ok(s, DuelAction.Counter(ash, 2, "Spell"))
        assertEquals(2, s.cards.getValue(ash).counters["Spell"])
        s = ok(s, DuelAction.Move(ash, m(0, 1)))
        assertEquals(2, s.cards.getValue(ash).counters["Spell"])
        s = ok(s, DuelAction.Move(ash, Place.Pile(0, PileKind.HAND)))
        assertTrue(s.cards.getValue(ash).counters.isEmpty())
    }

    @Test
    fun lifePointsNeverGoBelowZero() {
        var s = ok(bare(), DuelAction.Lp(1, delta = -3000))
        assertEquals(5000, s.seats[1].lp)
        s = ok(s, DuelAction.Lp(1, delta = -9000))
        assertEquals(0, s.seats[1].lp)
        s = ok(s, DuelAction.Lp(1, set = 4000))
        assertEquals(4000, s.seats[1].lp)
    }

    @Test
    fun endingTheTurnPassesItAndClearsTheChain() {
        var s = ok(bare(), DuelAction.Phase(DuelPhase.MAIN1))
        s = ok(s, DuelAction.ChainAdd(0, null, "something"))
        s = ok(s, DuelAction.EndTurn)
        assertEquals(1, s.active)
        assertEquals(2, s.turn)
        assertEquals(DuelPhase.DRAW, s.phase)
        assertTrue(s.chain.isEmpty())
        val solo = ok(bare(solo = true), DuelAction.EndTurn)
        assertEquals(0, solo.active)
        assertEquals(2, solo.turn)
    }

    @Test
    fun theChainResolvesFromTheNewestLink() {
        var s = ok(bare(), DuelAction.ChainAdd(0, ash, targets = listOf(rivalCard)))
        s = ok(s, DuelAction.ChainAdd(1, rivalCard))
        assertEquals(1, s.arrows.size)
        s = ok(s, DuelAction.ChainResolve)
        assertEquals(listOf(ash), s.chain.map { it.uid })
        s = ok(s, DuelAction.ChainResolve)
        assertTrue(s.chain.isEmpty())
        assertTrue(s.arrows.isEmpty())
        assertEquals("There is no chain to resolve", refused(s, DuelAction.ChainResolve))
    }

    @Test
    fun aSeatThatActsIsNoLongerThinkingButTalkDoesNotCount() {
        var s = ok(bare(), DuelAction.Thinking(0, true))
        assertTrue(0 in s.thinking)
        s = ok(s, DuelAction.Chat(0, "hmm"))
        assertTrue(0 in s.thinking)
        s = ok(s, DuelAction.Draw(0))
        assertFalse(0 in s.thinking)
    }

    @Test
    fun aShuffleForgetsWhatAnyoneSawInThePile() {
        var s = ok(bare(), DuelAction.Reveal(0, listOf(ash)))
        assertTrue(DuelSight.sees(s, ash, 1))
        s = ok(s, DuelAction.Shuffle(0, PileKind.DECK, salt = 7))
        assertFalse(DuelSight.sees(s, ash, 1))
        assertEquals(1, s.epoch[ash])
    }

    @Test
    fun aCardThatLeavesTheFieldForTheHandIsPrivateThere() {
        // 1.0.82, kai: "once it has gone into the hand it is no longer revealed or treated as public knowledge".
        var s = ok(bare(), DuelAction.Draw(0, 1))
        assertFalse(DuelSight.sees(s, ash, 1))
        s = ok(s, DuelAction.Move(ash, m(0, 0)))
        assertTrue(DuelSight.sees(s, ash, 1))
        s = ok(s, DuelAction.Move(ash, Place.Pile(0, PileKind.HAND)))
        assertFalse(DuelSight.sees(s, ash, 1))
        assertTrue(DuelSight.sees(s, ash, 0))
    }

    @Test
    fun aSetCardIsHiddenFromTheOpponentOnly() {
        var s = ok(bare(), DuelAction.Draw(0, 3))
        s = ok(s, DuelAction.Move(pot, st(0, 0), CardPosition.FACE_DOWN_ATK))
        assertTrue(DuelSight.sees(s, pot, 0))
        assertFalse(DuelSight.sees(s, pot, 1))
    }

    @Test
    fun theRiffleIsFisherYatesWrittenOut() {
        // The same loop PlayField uses: fixed for a seed on every platform.
        val list = (1..10).toList()
        val shuffled = DuelRandom.riffle(list, 1234L)
        val expected = list.toMutableList()
        val r = kotlin.random.Random(1234L)
        for (i in expected.indices.reversed()) {
            val j = r.nextInt(i + 1)
            val t = expected[i]; expected[i] = expected[j]; expected[j] = t
        }
        assertEquals(expected, shuffled)
        assertEquals(list.sorted(), shuffled.sorted())
    }
}
