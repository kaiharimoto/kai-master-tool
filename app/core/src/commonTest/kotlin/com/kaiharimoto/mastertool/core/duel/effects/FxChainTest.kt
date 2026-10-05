package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The chain (D.md §2.3, §10 `FxChainTest`): spell speeds and priority, SEGOC, missing the timing, targets checked at
 * resolution, a negated link, the chain's own Spells to the GY, and summons that start no chain.
 */
class FxChainTest {
    private fun set(code: Int, i: Int) = Slot(code, i, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)

    @Test
    fun anActivationIsItsCardsMoveItsCostsItsTargetsAndItsLink() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.FLASH)), them = Side(field = listOf(Slot(FxRef.PAWN, 0), Slot(FxRef.KNIGHT, 1)))))
        val flash = p.uid(FxRef.FLASH)
        val knight = p.uid(FxRef.KNIGHT, 1)
        val d = p.activate(0, flash) { d -> if (d is Decision.Cards) listOf(d.among.indexOf(knight)) else null }
        val zone = (d.actions.first() as DuelAction.Move).to
        assertEquals(listOf(DuelAction.Move(flash, zone, CardPosition.FACE_UP_ATK, "activate"), DuelAction.ChainAdd(0, flash, "Destroy", listOf(knight))), d.actions)
        assertEquals(listOf(FxTag.ACTIVATE, FxTag.ACTIVATE), d.tags.map { it.part })
        assertTrue(d.tags.all { it.uid == flash && it.link == 1 && it.script == FxRef.book.hash(FxRef.FLASH) })
        assertEquals(1, p.t.fx.priority, "the other seat responds first")
        assertEquals(listOf(knight), p.t.fx.links.single().bound[Pick.TARGETS])
        // Two passes in a row and it resolves: the target destroyed, the Quick-Play to the GY with the chain's end.
        p.pass(1)
        assertEquals(0, p.t.fx.priority)
        val r = p.pass(0)
        assertTrue(DuelAction.Move(knight, Place.Pile(1, PileKind.GY), how = "destroy") in r.actions)
        assertTrue(DuelAction.Move(flash, Place.Pile(0, PileKind.GY), how = "resolve") in r.actions)
        assertTrue(p.t.state.chain.isEmpty() && p.t.fx.links.isEmpty() && p.t.fx.priority == null)
    }

    @Test
    fun spellSpeedsAndPriority() {
        val p = FxPlays(
            FxRef.game(
                Side(hand = listOf(FxRef.RALLY, FxRef.CALL), field = listOf(set(FxRef.FLASH, 0)), deck = listOf(FxRef.SCOUT, FxRef.PAWN)),
                them = Side(field = listOf(set(FxRef.DENIAL, 0), Slot(FxRef.PAWN, 0))),
            ),
        )
        val rally = p.uid(FxRef.RALLY)
        val call = p.uid(FxRef.CALL)
        val flash = p.uid(FxRef.FLASH)
        val denial = p.uid(FxRef.DENIAL, 1)
        p.activate(0, call)
        // Not your priority.
        assertTrue(p.refused(0, FxMove.Activate(flash, "e1")).contains("respond first"))
        assertFalse(FxEngine.moves(p.t, 0).any { it is FxMove.Activate })
        // Speed 3 on top of the Call.
        p.activate(1, denial)
        assertEquals(0, p.t.fx.priority)
        assertEquals("Spell speed 2 cannot answer spell speed 3.", p.refused(0, FxMove.Activate(flash, "e1")))
        assertEquals("Spell speed 1 starts a chain, never answers one.", p.refused(0, FxMove.Activate(rally, "e1")))
        assertEquals(listOf(FxMove.Pass), FxEngine.moves(p.t, 0), "only a pass is left")
        // A pass by the seat without priority is refused.
        assertTrue(p.refused(1, FxMove.Pass).contains("not your priority"))
    }

    @Test
    fun simultaneousTriggersFormANewChainBySegoc() {
        val p = FxPlays(
            FxRef.game(
                Side(hand = listOf(FxRef.PURGE), field = listOf(Slot(FxRef.MANDATE, 0), Slot(FxRef.ECHO, 1), Slot(FxRef.MANDATE, 2)), deck = listOf(FxRef.PAWN, FxRef.PAWN)),
                them = Side(field = listOf(Slot(FxRef.ECHO, 0), Slot(FxRef.MANDATE, 1)), deck = listOf(FxRef.PAWN)),
            ),
        )
        val (m1, m2) = p.uids(FxRef.MANDATE)
        val echo = p.uid(FxRef.ECHO)
        val theirEcho = p.uid(FxRef.ECHO, 1)
        val theirMandate = p.uid(FxRef.MANDATE, 1)
        p.activate(0, p.uid(FxRef.PURGE))
        p.pass(1)
        // The Purge resolves; five triggers at once: the turn player orders its two mandatory ones, then is asked of its Echo.
        p.asked.clear()
        p.go(0, FxMove.Pass) { d -> if (d is Decision.Order) listOf(1, 0) else null }
        val order = p.asked.filterIsInstance<Decision.Order>().single()
        assertEquals(setOf(m1, m2), order.triggers.map { it.uid }.toSet(), "a player orders their own")
        assertEquals(2, p.asked.filterIsInstance<Decision.YesNo>().size, "each optional trigger asked once")
        val links = p.t.fx.links.sortedBy { it.link }
        // TCG Rulebook v10 (Yugipedia, "Simultaneous Effects"): both players' mandatory triggers before either's optional ones.
        assertEquals(
            listOf(order.triggers[1].uid, order.triggers[0].uid, theirMandate, echo, theirEcho), links.map { it.uid },
            "the turn player's mandatory, the other's mandatory, the turn player's optional, the other's optional",
        )
        assertEquals(listOf(0, 0, 1, 0, 1), links.map { it.seat })
        assertEquals(0, p.t.fx.priority, "the newest link is theirs: the turn player may respond")
        assertTrue(p.t.fx.pending.isEmpty())
    }

    @Test
    fun anOptionalWhenTriggerMissesTheTimingAndIfOrMandatoryOnesNever() {
        // "Send, then gain LP": the send is no longer the last thing to happen.
        fun mill(code: Int, spell: Int): FxPlays {
            val p = FxPlays(FxRef.game(Side(hand = listOf(spell), deck = listOf(code, FxRef.PAWN, FxRef.PAWN))))
            p.activate(0, p.uid(spell))
            p.passBoth(1)
            return p
        }
        val missed = mill(FxRef.ECHO, FxRef.MILL)
        assertTrue(missed.t.state.chain.isEmpty(), "Echo missed the timing: no chain")
        assertTrue(missed.notes().any { it == "Example Echo's Draw: missed the timing." }, missed.notes().toString())
        // At the same time ("and"): the send is last, Echo is offered.
        val last = mill(FxRef.ECHO, FxRef.SIEVE)
        assertEquals(listOf(last.uid(FxRef.ECHO)), last.t.fx.links.map { it.uid })
        // A mandatory IF trigger never misses.
        val mandate = mill(FxRef.MANDATE, FxRef.MILL)
        assertEquals(listOf(mandate.uid(FxRef.MANDATE)), mandate.t.fx.links.map { it.uid })
    }

    @Test
    fun aCostIsNoEffectAndItsTriggerWaitsForTheChain() {
        fun offer(code: Int): FxPlays {
            val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.OFFERING, code), deck = listOf(FxRef.PAWN, FxRef.PAWN))))
            p.activate(0, p.uid(FxRef.OFFERING))
            // Sent as a cost, it waits while the chain stands.
            if (code != FxRef.EMBER) assertEquals(listOf(p.uid(code)), p.t.fx.pending.map { it.uid })
            p.passBoth(1)
            return p
        }
        // An optional WHEN trigger sent as a cost misses: the chain's resolution happened after it.
        val echo = offer(FxRef.ECHO)
        assertTrue(echo.t.state.chain.isEmpty())
        assertTrue(echo.notes().any { it.endsWith("missed the timing.") })
        // "Sent by a card effect" is not set off by a cost.
        val ember = offer(FxRef.EMBER)
        assertTrue(ember.t.state.chain.isEmpty() && ember.notes().isEmpty())
        // A mandatory IF trigger sent as a cost goes on the chain once it is over.
        val mandate = offer(FxRef.MANDATE)
        assertEquals(listOf(mandate.uid(FxRef.MANDATE)), mandate.t.fx.links.map { it.uid })
    }

    @Test
    fun aTargetThatLeftIsDroppedAtResolution() {
        val p = FxPlays(
            FxRef.game(
                Side(field = listOf(set(FxRef.SNARE, 0))),
                them = Side(field = listOf(Slot(FxRef.PAWN, 0), set(FxRef.FLASH, 0))),
            ),
        )
        val pawn = p.uid(FxRef.PAWN, 1)
        p.activate(0, p.uid(FxRef.SNARE))
        assertEquals(listOf(pawn), p.t.state.chain.single().targets)
        // In answer they destroy their own monster: Snare's target is gone when it resolves.
        p.activate(1, p.uid(FxRef.FLASH, 1)) { d -> if (d is Decision.Cards) listOf(d.among.indexOf(pawn)) else null }
        p.passBoth(0)
        assertEquals(Place.Pile(1, PileKind.GY, 0), p.t.state.placeOf(pawn))
        val r = p.resolve()
        assertFalse(r.actions.any { it is DuelAction.Move && it.how == "banish" }, "nothing is banished")
        assertTrue(p.notes().any { it.startsWith("Chain Link 1: a target is no longer there") }, p.notes().toString())
    }

    /**
     * A negated activation does nothing; "use" wording counts it (YGOrg, Demystifying Rulings Part 10: Nekroz Mirror) — this
     * Call written as "you can only use Example Call once per turn". [FxRulingsTest.a07_aNegatedActivationOfActivateOnceIsGivenBack]
     * has the "activate" wording, given back.
     */
    @Test
    fun aNegatedActivationDoesNothingAndStillUsedItsOncePerTurn() {
        val call0 = FxRef.script(FxRef.CALL)
        val used = FxRef.bookWith(call0.copy(effects = call0.effects.map { it.copy(opt = Opt.ByName()) }))
        val p = FxPlays(
            FxRef.game(
                Side(hand = listOf(FxRef.CALL, FxRef.CALL), deck = listOf(FxRef.SCOUT, FxRef.PAWN)),
                them = Side(field = listOf(set(FxRef.DENIAL, 0))),
                book = used,
            ),
        )
        val (call, call2) = p.uids(FxRef.CALL)
        p.activate(0, call)
        val denial = p.uid(FxRef.DENIAL, 1)
        p.activate(1, denial)
        assertEquals(7000, p.t.state.seats[1].lp, "its cost paid as it was activated")
        p.passBoth(0)
        assertTrue(p.t.state.chain.single().negated)
        assertEquals(Place.Pile(0, PileKind.GY, 0), p.t.state.placeOf(call), "a negated Spell goes to the GY at once")
        val r = p.resolve()
        assertEquals(listOf(DuelAction.ChainResolve, DuelAction.Move(denial, Place.Pile(1, PileKind.GY), how = "resolve")), r.actions, "the link does nothing")
        assertTrue(p.t.state.seats[0].monsters.all { it == null }, "nothing was summoned")
        assertEquals(FxRules.OPT_USED, p.refused(0, FxMove.Activate(call2, "e1")), "a negated activation still used it")
    }

    @Test
    fun aNegatedEffectLeavesTheCardAndDoesNothing() {
        val p = FxPlays(
            FxRef.game(Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.LAMP, FxRef.PAWN)), them = Side(hand = listOf(FxRef.WARD))),
        )
        val scout = p.uid(FxRef.SCOUT)
        val ward = p.uid(FxRef.WARD, 1)
        // A Normal Summon starts no chain; its trigger is asked and chained afterwards, in the same move.
        val ns = p.go(0, FxMove.NormalSummon(scout))
        assertEquals(DuelAction.ChainAdd(0, scout, "Search"), ns.actions.last())
        assertTrue(p.asked.any { it is Decision.YesNo })
        assertTrue(FxMove.Activate(ward, "e1") in FxEngine.moves(p.t, 1), "a Quick Effect from the hand answers the search")
        p.activate(1, ward)
        assertEquals(Place.Pile(1, PileKind.GY, 0), p.t.state.placeOf(ward), "discarded as its cost")
        p.passBoth(0)
        assertTrue(p.t.fx.links.single().effectNegated)
        val r = p.resolve()
        assertEquals(listOf(DuelAction.ChainResolve), r.actions)
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 0), p.t.state.placeOf(scout), "the monster stays")
        assertEquals(2, p.t.state.seats[0].deck.size, "nothing was added")
    }

    @Test
    fun aLinkMadeByHandIsNotResolvedByTheEngine() {
        val p = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.PAWN, 0)))))
        val pawn = p.uid(FxRef.PAWN)
        val game = p.game.act(DuelAction.ChainAdd(0, pawn), 0).game
        val t = FxTable(game.state, FxFold.fold(game.header, game.played, FxRef.book, FxRef.facts, game.state), FxRef.book, FxRef.facts)
        assertTrue(assertIs<FxPlay.Refused>(FxEngine.play(t, 0, FxMove.Resolve, Chooser.FIRST)).why.contains("resolve it by hand"))
    }

    @Test
    fun ignitionsInYourMainPhaseWithNothingPending() {
        val p = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT, FxRef.PAWN)), phase = DuelPhase.BATTLE))
        val lamp = p.uid(FxRef.LAMP)
        assertEquals("Spell speed 1: only in your Main Phase.", p.refused(0, FxMove.Activate(lamp, "e1")))
        val other = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT)), active = 1))
        assertEquals(FxRules.NOT_TURN, other.refused(0, FxMove.Activate(other.uid(FxRef.LAMP), "e1")))
        val main = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT, FxRef.PAWN))))
        main.activate(0, main.uid(FxRef.LAMP))
        main.passBoth(1)
        assertEquals(Place.Pile(0, PileKind.GY, 0), main.t.state.placeOf(main.uid(FxRef.SCOUT)))
        assertEquals(FxRules.OPT_USED, main.refused(0, FxMove.Activate(main.uid(FxRef.LAMP), "e1")))
        assertEquals("It has no effect e9.", FxEngine.refusal(main.t, 0, main.uid(FxRef.LAMP), "e9"))
    }
}
