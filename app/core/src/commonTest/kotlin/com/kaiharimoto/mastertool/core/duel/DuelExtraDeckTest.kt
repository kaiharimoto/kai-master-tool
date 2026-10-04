package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.bare
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.ok
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.refused
import com.kaiharimoto.mastertool.core.duel.DuelFixtures.uid
import com.kaiharimoto.mastertool.core.duel.net.DuelMirror
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** kai, 1.0.93: "I am able to put maindeck monsters in the extra deck, which should never happen unless a pendulum monster is in the extra deck face up". */
class DuelExtraDeckTest {
    private val ash = uid(0, 0)
    private val pendy = uid(0, 4)
    private val zeus = uid(0, 40)
    private val m1 = Place.Zone(0, ZoneKind.MONSTER, 0)
    private val extra = Place.Pile(0, PileKind.EXTRA)

    @Test
    fun theDealMarksTheExtraDecksOwnCards() {
        val s = bare()
        assertTrue(s.cards.getValue(zeus).extraDeck)
        assertFalse(s.cards.getValue(ash).extraDeck)
        assertFalse(s.cards.getValue(pendy).extraDeck)
        // A duel read back from its log is dealt again, so one saved before 1.0.93 knows too.
        val g = DuelGame.start(DuelFixtures.header())
        val again = DuelGame.of(g.record())
        assertTrue(again.state.cards.getValue(zeus).extraDeck)
    }

    @Test
    fun aMainDeckCardIsRefusedTheExtraDeckFaceDown() {
        val onField = ok(bare(), DuelAction.Move(ash, m1))
        assertEquals(DuelRules.MAIN_TO_EXTRA, refused(onField, DuelAction.Move(ash, extra)))
        assertEquals(DuelRules.MAIN_TO_EXTRA, refused(onField, DuelAction.Move(ash, extra, CardPosition.FACE_DOWN_DEF)))
        // Its drop target is not there at all.
        assertEquals(DuelDrop.NONE, DuelDrop.intent(onField, ash, DropSpot.Pile(0, PileKind.EXTRA), catalog))
        assertFalse(DuelVerb.EXTRA in DuelVerbs.offered(onField, 0, ash, catalog))
    }

    @Test
    fun aMainDeckPendulumGoesThereFaceUp() {
        val onField = ok(bare(), DuelAction.Move(pendy, m1))
        assertTrue(DuelVerb.EXTRA in DuelVerbs.offered(onField, 0, pendy, catalog))
        val drop = DuelDrop.intent(onField, pendy, DropSpot.Pile(0, PileKind.EXTRA), catalog)
        val after = DuelRules.applyAll(onField, drop.actions, 0).first!!
        assertTrue(pendy in after.seats[0].extra)
        assertTrue(after.cards.getValue(pendy).faceUp)
        // Face-down it is refused, as any Main Deck card is.
        assertEquals(DuelRules.MAIN_TO_EXTRA, refused(onField, DuelAction.Move(pendy, extra, CardPosition.FACE_DOWN_DEF)))
    }

    @Test
    fun theExtraDecksOwnGoBackAsTheyAlwaysDid() {
        val onField = ok(bare(), DuelAction.Move(zeus, m1))
        val back = ok(onField, DuelAction.Move(zeus, extra))
        assertTrue(zeus in back.seats[0].extra)
        assertFalse(back.cards.getValue(zeus).faceUp)
        val drop = DuelDrop.intent(onField, zeus, DropSpot.Pile(0, PileKind.EXTRA), catalog)
        assertNull(DuelRules.applyAll(onField, drop.actions, 0).second)
    }

    @Test
    fun aGuestKnowsOnlyWhatItSees() {
        val onField = ok(ok(bare(), DuelAction.Move(zeus, m1)), DuelAction.Move(ash, Place.Zone(0, ZoneKind.MONSTER, 1)))
        // Seen: the guest's table knows the card is the Extra Deck's, and its own rules let it go back.
        val view = DuelView.of(onField, 1)
        val mirror = DuelMirror.state(view)
        assertTrue(mirror.cards.getValue(zeus).extraDeck)
        assertFalse(mirror.cards.getValue(ash).extraDeck)
        // Unseen (its own face-down Extra Deck, from the other seat): never said.
        assertTrue(view.seats[0].extra.none { it.extraDeck })
    }
}
