package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lingering negation (E.md §4.1, the first family Phase E adds): "negate the effects of that face-up monster until the end of
 * this turn", as Effect Veiler and Infinite Impermanence say it. Every play goes through [FxPlays], so each holds the fold
 * to the engine's own state too.
 */
class FxNegateEffectsTest {
    private fun steps(vararg op: Op) = op.map { Step(it) }

    @Test
    fun anEffectActivatedOnTheFieldResolvesDoingNothingOnceItsCardIsNegated() {
        val p = FxPlays(
            FxRef.game(
                Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT, FxRef.PAWN)),
                them = Side(hand = listOf(FxRef.VEIL)),
            ),
        )
        val lamp = p.uid(FxRef.LAMP)
        val veil = p.uid(FxRef.VEIL, 1)
        p.activate(0, lamp)
        assertTrue(FxMove.Activate(veil, "e1") in FxEngine.moves(p.t, 1), "Veil answers on their turn")
        p.activate(1, veil)
        assertEquals(Place.Pile(1, PileKind.GY, 0), p.t.state.placeOf(veil), "discarded as its cost")
        p.passBoth(0)
        assertTrue(p.t.negated(lamp), "Veil resolved: Lamp's effects are negated")
        assertTrue(p.notes().any { it.contains("effects are negated this turn") })
        p.resolve()
        assertEquals(2, p.t.state.seats[0].deck.size, "Lamp's link resolved doing nothing: nothing was sent")
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 0), p.t.state.placeOf(lamp), "the monster stays")
    }

    @Test
    fun aNegatedCardsEffectsCannotBeActivatedOnTheField() {
        val p = FxPlays(
            FxRef.game(
                Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT)),
                them = Side(hand = listOf(FxRef.VEIL)),
            ),
        )
        val lamp = p.uid(FxRef.LAMP)
        val veil = p.uid(FxRef.VEIL, 1)
        // The open state: the other seat's Quick Effect, used when asked (the engine's house ruling).
        assertTrue(FxMove.Activate(veil, "e1") in FxEngine.moves(p.t, 1))
        p.activate(1, veil)
        p.passBoth(0)
        assertTrue(p.t.negated(lamp))
        assertEquals(FxChain.NEGATED, p.refused(0, FxMove.Activate(lamp, "e1")))
        assertFalse(FxMove.Activate(lamp, "e1") in FxEngine.moves(p.t, 0))
    }

    @Test
    fun aNegatedContinuousEffectStopsApplying() {
        val book = FxRef.bookWith(
            CardScript(
                FxRef.VEIL, name = "Example Veil",
                effects = listOf(
                    Effect(
                        "e1", "Veil", Kind.QUICK, from = setOf(Where.HAND),
                        cost = steps(Op.Discard(Pick(ref = Pick.SELF))),
                        targets = listOf(Pick(from = listOf(Spot(Rel.THEM, Area.SPELLS)), where = Filter.FaceUp, bind = "t")),
                        does = steps(Op.NegateEffects(Pick(ref = "t"))),
                    ),
                ),
            ),
            // Edict without its exception, so it binds every card of the reference set.
            CardScript(
                FxRef.EDICT, name = "Example Edict",
                effects = listOf(
                    Effect("e1", "Activate", Kind.ACTIVATION, from = setOf(Where.HAND, Where.SPELL_ZONE)),
                    Effect("e2", "No summons", Kind.CONTINUOUS, from = setOf(Where.SPELL_ZONE), leaves = listOf(Restriction(Ban.SPECIAL_SUMMON, seat = Rel.THEM))),
                ),
            ),
        )
        val p = FxPlays(
            FxRef.game(
                Side(hand = listOf(FxRef.VEIL), field = listOf(Slot(FxRef.LAMP, 0))),
                them = Side(field = listOf(Slot(FxRef.EDICT, 0, CardPosition.FACE_UP_ATK, ZoneKind.SPELL))),
                book = book,
            ),
        )
        val veil = p.uid(FxRef.VEIL)
        assertTrue(FxRules.restricted(p.t, 0, Ban.SPECIAL_SUMMON, veil) != null, "Edict binds seat 0")
        p.activate(0, veil)
        p.passBoth(1)
        assertNull(FxRules.restricted(p.t, 0, Ban.SPECIAL_SUMMON, veil), "negated, Edict no longer applies")
    }

    @Test
    fun theNegationEndsWithTheTurnAndWithTheInstance() {
        val p = FxPlays(
            FxRef.game(
                Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT)),
                them = Side(hand = listOf(FxRef.VEIL)),
            ),
        )
        val lamp = p.uid(FxRef.LAMP)
        p.activate(1, p.uid(FxRef.VEIL, 1))
        p.passBoth(0)
        assertTrue(p.t.negated(lamp))
        val next = p.t.copy(state = p.t.state.copy(turn = p.t.state.turn + 1)).current()
        assertFalse(next.negated(lamp), "until the end of this turn")
        val moved = p.t.copy(fx = p.t.fx.moved(lamp, Place.Pile(0, PileKind.HAND)))
        assertFalse(moved.negated(lamp), "a new instance is a new card")
    }

    @Test
    fun theTableKeyTellsANegatedCardApart() {
        val p = FxPlays(
            FxRef.game(
                Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT)),
                them = Side(hand = listOf(FxRef.VEIL)),
            ),
        )
        val lamp = p.uid(FxRef.LAMP)
        p.activate(1, p.uid(FxRef.VEIL, 1))
        p.passBoth(0)
        val without = p.t.copy(fx = p.t.fx.copy(negated = emptyList()))
        assertTrue(p.t.negated(lamp))
        assertFalse(TableKey(p.t, false).key() == TableKey(without, false).key())
        assertTrue(FxWords.text(FxRef.script(FxRef.VEIL)).contains("Negate the effects of the target until the end of the turn"), FxWords.text(FxRef.script(FxRef.VEIL)))
    }
}
