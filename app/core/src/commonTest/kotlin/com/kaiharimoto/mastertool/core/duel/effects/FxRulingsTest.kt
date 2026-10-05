package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D.md §8 part A, the rulings set: cases with known answers on our fictional reference cards, each graded by the table and
 * `FxState` after it. Every case names its source:
 * - **Rule**: a general rule of the game as its rulebook states it (spell speeds, SEGOC, missing the timing, costs and
 *   effects, targets at resolution), in our own words. No YGOrg Q&A number is cited here: none was looked up for this
 *   step, so where a case leans on a Q&A's reading rather than the rulebook it is marked as a house ruling instead.
 * - **House ruling**: a decision this engine makes where the rulebook is silent or the case is subtle, named as one so a
 *   later Q&A can overturn it (the test is the authority, D.md §2.3).
 *
 * The summoning cases of part A — Extra Monster Zones and linked zones, inherent summons, "must first be", Tributes and
 * once-per-turn by name across printings for procedures — are agent (a)'s, in `FxProcTest`, `FxRulesTest` and `FxOptTest`.
 */
class FxRulingsTest {
    private fun set(code: Int, i: Int) = Slot(code, i, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)

    /** Rule: a Normal Spell (spell speed 1) starts a chain and never answers one. */
    @Test
    fun a01_speedOneNeverAnswers() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.RALLY), field = listOf(set(FxRef.FLASH, 0))), them = Side(field = listOf(set(FxRef.SNARE, 0), Slot(FxRef.PAWN, 0)))))
        p.activate(0, p.uid(FxRef.FLASH)) // the Flash on the chain, at speed 2
        p.pass(1)
        assertEquals("Spell speed 1 starts a chain, never answers one.", p.refused(0, FxMove.Activate(p.uid(FxRef.RALLY), "e1")))
    }

    /** Rule: only a Counter Trap (spell speed 3) answers spell speed 3. */
    @Test
    fun a02_speedThreeOnlyBySpeedThree() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.CALL), field = listOf(set(FxRef.FLASH, 0)), deck = listOf(FxRef.SCOUT)), them = Side(field = listOf(set(FxRef.DENIAL, 0)))))
        p.activate(0, p.uid(FxRef.CALL))
        p.activate(1, p.uid(FxRef.DENIAL, 1))
        assertEquals("Spell speed 2 cannot answer spell speed 3.", p.refused(0, FxMove.Activate(p.uid(FxRef.FLASH), "e1")))
    }

    /** Rule: a Quick-Play Spell is activated from the hand only on your own turn; set, it answers on theirs. */
    @Test
    fun a03_quickPlayFromTheHandOnYourOwnTurn() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.FLASH), field = listOf(set(FxRef.FLASH, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0))), active = 1))
        val (inHand, isSet) = p.uids(FxRef.FLASH).partition { p.t.state.placeOf(it) is Place.Pile }
        assertEquals(FxRules.QUICK_PLAY_HAND, p.refused(0, FxMove.Activate(inHand.single(), "e1")))
        assertTrue(FxMove.Activate(isSet.single(), "e1") in FxEngine.moves(p.t, 0))
    }

    /** Rule: a Trap, or a set Quick-Play Spell, is not activated the turn it was Set. */
    @Test
    fun a04_notTheTurnItWasSet() {
        val (g, t) = FxRef.game(Side(hand = listOf(FxRef.SNARE)), them = Side(field = listOf(Slot(FxRef.PAWN, 0))))
        val snare = FxRef.uid(t, FxRef.SNARE)
        val set = g.act(DuelAction.Move(snare, Place.Zone(0, ZoneKind.SPELL, 0), CardPosition.FACE_DOWN_ATK, "set"), 0).game
        val now = FxTable(set.state, FxFold.fold(set.header, set.played, FxRef.book, FxRef.facts, set.state), FxRef.book, FxRef.facts)
        assertEquals(FxRules.SET_TURN, FxEngine.refusal(now, 0, snare, "e1"))
    }

    /** Rule: after a card is activated the other player may respond first; then priority alternates, and two passes resolve. */
    @Test
    fun a05_theOtherPlayerRespondsFirst() {
        val p = FxPlays(FxRef.game(Side(field = listOf(set(FxRef.SNARE, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0)))))
        p.activate(0, p.uid(FxRef.SNARE))
        assertEquals(1, FxEngine.next(p.t))
        p.pass(1)
        assertEquals(0, FxEngine.next(p.t), "the activating player may chain to their own")
        p.pass(0)
        assertTrue(p.t.state.chain.isEmpty())
    }

    /** Rule: costs are paid as the card is activated, and stay paid when it is negated. */
    @Test
    fun a06_costsArePaidAtActivation() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.CALL), deck = listOf(FxRef.SCOUT)), them = Side(field = listOf(set(FxRef.DENIAL, 0)))))
        p.activate(0, p.uid(FxRef.CALL))
        p.activate(1, p.uid(FxRef.DENIAL, 1))
        assertEquals(7000, p.t.state.seats[1].lp, "paid before anything resolves")
        p.passBoth(0)
        p.resolve()
        assertEquals(7000, p.t.state.seats[1].lp)
    }

    /** Rule: a once-per-turn effect is used when it is activated, so a negated activation has still used it. */
    @Test
    fun a07_aNegatedActivationStillUsedItsOncePerTurn() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.CALL, FxRef.CALL), deck = listOf(FxRef.SCOUT)), them = Side(field = listOf(set(FxRef.DENIAL, 0)))))
        val (one, two) = p.uids(FxRef.CALL)
        p.activate(0, one)
        p.activate(1, p.uid(FxRef.DENIAL, 1))
        p.passBoth(0)
        p.resolve()
        assertEquals(FxRules.OPT_USED, p.refused(0, FxMove.Activate(two, "e1")))
    }

    /** Rule: "once per turn by name" counts every copy and every printing (an alternate artwork is the same card). */
    @Test
    fun a08_byNameAcrossPrintings() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.SCOUT, FxRef.CALL), deck = listOf(FxRef.SCOUT_ALT, FxRef.LAMP, FxRef.PAWN))))
        val scout = p.uid(FxRef.SCOUT)
        val alt = FxRef.uids(p.t, FxRef.SCOUT).single { it != scout }
        val lamp = p.uid(FxRef.LAMP)
        p.go(0, FxMove.NormalSummon(scout))
        p.pass(1)
        p.go(0, FxMove.Pass) { d -> if (d is Decision.Cards && lamp in d.among) listOf(d.among.indexOf(lamp)) else null } // the search: Lamp
        p.activate(0, p.uid(FxRef.CALL))
        p.pass(1)
        // The alternate artwork Special Summoned: its search is spent.
        p.go(0, FxMove.Pass) { d -> if (d is Decision.Cards && alt in d.among) listOf(d.among.indexOf(alt)) else null }
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 1), p.t.state.placeOf(alt))
        assertTrue(p.notes().any { it == "Example Scout's Search: once per turn: used." }, p.notes().toString())
        assertTrue(p.t.state.chain.isEmpty())
    }

    /** Rule: triggers set off at once form a chain: the turn player's mandatory, optional, then the other player's. */
    @Test
    fun a09_segocOrder() {
        val p = FxPlays(
            FxRef.game(
                Side(hand = listOf(FxRef.PURGE), field = listOf(Slot(FxRef.ECHO, 0), Slot(FxRef.MANDATE, 1)), deck = listOf(FxRef.PAWN)),
                them = Side(field = listOf(Slot(FxRef.ECHO, 0), Slot(FxRef.MANDATE, 1)), deck = listOf(FxRef.PAWN)),
            ),
        )
        p.activate(0, p.uid(FxRef.PURGE))
        p.passBoth(1)
        assertEquals(
            listOf(p.uid(FxRef.MANDATE), p.uid(FxRef.ECHO), p.uid(FxRef.MANDATE, 1), p.uid(FxRef.ECHO, 1)),
            p.t.fx.links.sortedBy { it.link }.map { it.uid },
        )
    }

    /** Rule: an optional "when" trigger whose event was not the last thing to happen misses the timing. */
    @Test
    fun a10_whenMissesTheTiming() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.MILL), deck = listOf(FxRef.ECHO, FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.MILL))
        p.passBoth(1)
        assertTrue(p.t.state.chain.isEmpty())
        assertTrue("Example Echo's Draw: missed the timing." in p.notes())
    }

    /** Rule: an optional "if" trigger never misses the timing. */
    @Test
    fun a11_ifNeverMisses() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.MILL), deck = listOf(FxRef.EMBER, FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.MILL))
        p.passBoth(1)
        assertEquals(listOf(p.uid(FxRef.EMBER)), p.t.fx.links.map { it.uid })
    }

    /** Rule: a mandatory trigger never misses the timing. */
    @Test
    fun a12_mandatoryNeverMisses() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.MILL), deck = listOf(FxRef.MANDATE, FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.MILL))
        p.passBoth(1)
        assertEquals(listOf(p.uid(FxRef.MANDATE)), p.t.fx.links.map { it.uid })
    }

    /** Rule: what happens "and" at the same time is all last: a "when" trigger on it is not missed. */
    @Test
    fun a13_atTheSameTimeIsLast() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.SIEVE), deck = listOf(FxRef.ECHO, FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.SIEVE))
        p.passBoth(1)
        assertEquals(listOf(p.uid(FxRef.ECHO)), p.t.fx.links.map { it.uid })
    }

    /**
     * House ruling (the classic reading of a cost at Chain Link 1, held here until a YGOrg Q&A is cited): a card sent as a
     * cost waits for the chain, and an optional "when" trigger on it misses the timing once the chain's resolution has
     * happened after it.
     */
    @Test
    fun a14_aWhenTriggerSentAsACostMissesAfterTheChain() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.OFFERING, FxRef.ECHO), deck = listOf(FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.OFFERING))
        assertEquals(listOf(p.uid(FxRef.ECHO)), p.t.fx.pending.map { it.uid }, "it waits while the chain stands")
        assertTrue(FxEngine.refusal(p.t, 0, p.uid(FxRef.ECHO), "e1")!!.contains("waits for the chain"))
        p.passBoth(1)
        assertTrue("Example Echo's Draw: missed the timing." in p.notes())
    }

    /** Rule: a cost is not an effect: "sent by a card effect" is not set off by paying a cost. */
    @Test
    fun a15_aCostIsNotAnEffect() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.OFFERING, FxRef.EMBER), deck = listOf(FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.OFFERING))
        assertTrue(p.t.fx.pending.isEmpty())
        p.passBoth(1)
        assertTrue(p.t.state.chain.isEmpty())
    }

    /** Rule: targets are chosen as the card is activated, and a target that has left by resolution is not affected. */
    @Test
    fun a16_aTargetGoneAtResolution() {
        val p = FxPlays(FxRef.game(Side(field = listOf(set(FxRef.SNARE, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0), set(FxRef.FLASH, 0)))))
        val pawn = p.uid(FxRef.PAWN, 1)
        p.activate(0, p.uid(FxRef.SNARE))
        p.activate(1, p.uid(FxRef.FLASH, 1)) { d -> if (d is Decision.Cards) listOf(d.among.indexOf(pawn)) else null }
        p.passBoth(0)
        p.resolve()
        assertEquals(PileKind.GY, (p.t.state.placeOf(pawn) as Place.Pile).kind, "destroyed by the Flash, not banished by the Snare")
    }

    /** Rule: a card that left the field and came back is a new card: an old target no longer means it. */
    @Test
    fun a17_aTargetThatLeftAndCameBackIsANewCard() {
        val (g, t) = FxRef.game(Side(field = listOf(set(FxRef.SNARE, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0))))
        val pawn = FxRef.uid(t, FxRef.PAWN, 1)
        val a = FxEngine.play(t, 0, FxMove.Activate(FxRef.uid(t, FxRef.SNARE), "e1"), Chooser.FIRST) as FxPlay.Done
        var game = g.act(a.actions, 0, fx = a.tags).game
        // By hand: their monster bounced and played again while the Snare waits.
        game = game.act(DuelAction.Move(pawn, Place.Pile(1, PileKind.HAND), how = "return"), 1).game
        game = game.act(DuelAction.Move(pawn, Place.Zone(1, ZoneKind.MONSTER, 0), CardPosition.FACE_UP_ATK, "special"), 1).game
        val now = FxTable(game.state, FxFold.fold(game.header, game.played, FxRef.book, FxRef.facts, game.state), FxRef.book, FxRef.facts)
        val r = FxEngine.play(now, 0, FxMove.Resolve, Chooser.FIRST) as FxPlay.Done
        assertFalse(r.actions.any { it is DuelAction.Move && it.uid == pawn }, "the new card is not the one targeted")
    }

    /** Rule: an effect negated resolves doing nothing, and the card that has it stays where it is. */
    @Test
    fun a18_aNegatedEffectLeavesItsCard() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.LAMP)), them = Side(hand = listOf(FxRef.WARD))))
        val scout = p.uid(FxRef.SCOUT)
        p.go(0, FxMove.NormalSummon(scout))
        p.activate(1, p.uid(FxRef.WARD, 1))
        p.passBoth(0)
        p.resolve()
        assertEquals(Place.Zone(0, ZoneKind.MONSTER, 0), p.t.state.placeOf(scout))
        assertEquals(1, p.t.state.seats[0].deck.size)
    }

    /** Rule: a Normal Spell and a Quick-Play Spell stay on the field until the whole chain has resolved, then go together. */
    @Test
    fun a19_theChainsSpellsGoToTheGyTogether() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.CALL), field = listOf(set(FxRef.FLASH, 0)), deck = listOf(FxRef.SCOUT)), them = Side(field = listOf(Slot(FxRef.PAWN, 0)))))
        val call = p.uid(FxRef.CALL)
        val flash = p.uid(FxRef.FLASH)
        p.activate(0, call)
        p.pass(1)
        p.activate(0, flash)
        p.passBoth(1)
        assertTrue(p.t.state.placeOf(flash) is Place.Zone, "Chain Link 2 resolved; its Quick-Play waits")
        val r = p.resolve()
        assertEquals(
            listOf(DuelAction.Move(flash, Place.Pile(0, PileKind.GY), how = "resolve"), DuelAction.Move(call, Place.Pile(0, PileKind.GY), how = "resolve")),
            r.actions.filter { it is DuelAction.Move && it.how == "resolve" },
        )
    }

    /** Rule: a Normal Summon starts no chain; the trigger it sets off goes on a chain afterwards. */
    @Test
    fun a20_aSummonStartsNoChain() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.SCOUT), deck = listOf(FxRef.LAMP))))
        val d = p.go(0, FxMove.NormalSummon(p.uid(FxRef.SCOUT)))
        assertEquals("normal", (d.actions.first() as DuelAction.Move).how)
        assertEquals(1, p.t.state.chain.size, "the trigger's chain")
        assertEquals("e1", p.t.fx.links.single().effect)
    }

    /** Rule: the materials and Tributes of a summon go at the same time as the summon: a "when" trigger on them is last. */
    @Test
    fun a21_aRitualsTributesAreLastWithTheSummon() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.RITE, FxRef.ORACLE, FxRef.ECHO), field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.PAWN))))
        val echo = p.uid(FxRef.ECHO)
        val lamp = p.uid(FxRef.LAMP)
        p.activate(0, p.uid(FxRef.RITE))
        p.go(1, FxMove.Pass)
        p.go(0, FxMove.Pass) { d -> if (d is Decision.Cards && echo in d.among) listOf(d.among.indexOf(echo), d.among.indexOf(lamp)) else null }
        assertEquals(ProcKind.RITUAL, p.t.fx.summoned[p.uid(FxRef.ORACLE)])
        assertEquals(listOf(echo), p.t.fx.links.map { it.uid })
    }

    /** Rule: "you can Normal Summon 1 more" is a second Normal Summon that turn, of what it allows. */
    @Test
    fun a22_aSecondNormalSummon() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.RALLY, FxRef.PAWN, FxRef.TINKER, FxRef.SPRITE))))
        p.go(0, FxMove.NormalSummon(p.uid(FxRef.PAWN)))
        assertFalse(FxEngine.moves(p.t, 0).any { it is FxMove.NormalSummon }, "the turn's own is spent")
        p.activate(0, p.uid(FxRef.RALLY))
        p.passBoth(1)
        assertTrue(FxMove.NormalSummon(p.uid(FxRef.TINKER)) in FxEngine.moves(p.t, 0), "an Example monster")
        assertFalse(FxMove.NormalSummon(p.uid(FxRef.SPRITE)) in FxEngine.moves(p.t, 0), "not a monster it allows")
        p.go(0, FxMove.NormalSummon(p.uid(FxRef.TINKER)))
        assertEquals(2, p.t.fx.normalsUsed(0))
        assertTrue(p.t.fx.grants.single().used)
    }

    /** Rule (the card's own text): a lock a card leaves binds for the rest of the turn, except what it allows. */
    @Test
    fun a23_aLockBindsTheTurn() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.CALL), field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.PAWN), extra = listOf(FxRef.BRIDGE, FxRef.PALADIN))))
        p.activate(0, p.uid(FxRef.CALL))
        p.passBoth(1)
        assertTrue(p.t.state.locks.single().text.startsWith("No Special Summons from the Extra Deck"))
        assertTrue(FxProcs.options(p.t, 0, p.uid(FxRef.BRIDGE)).isEmpty(), "no Link Summon under it")
        assertTrue(FxProcs.options(p.t, 0, p.uid(FxRef.PALADIN)).isNotEmpty(), "a Synchro Summon is allowed")
        assertNull(FxRules.restricted(p.t.copy(fx = p.t.fx.forTurn(3), state = p.t.state.copy(turn = 3)), 0, Ban.SPECIAL_SUMMON_FROM_EXTRA, p.uid(FxRef.BRIDGE)))
    }

    /** Rule: a Quick Effect that answers an activation is used only while that activation is the newest link. */
    @Test
    fun a24_aHandTrapAnswersOnlyWhatItAnswers() {
        val p = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.SCOUT, FxRef.PAWN)), them = Side(hand = listOf(FxRef.WARD))))
        p.activate(0, p.uid(FxRef.LAMP)) // a send from the Deck, no search
        assertTrue(FxEngine.refusal(p.t, 1, p.uid(FxRef.WARD, 1), "e1")!!.contains("does not include"))
    }

    /** House ruling: after a chain of triggers is built, the player who did not make its newest link may respond first. */
    @Test
    fun h01_afterSegocTheOtherSeatOfTheNewestLinkResponds() {
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.MILL), deck = listOf(FxRef.MANDATE, FxRef.PAWN))))
        p.activate(0, p.uid(FxRef.MILL))
        p.passBoth(1)
        assertEquals(1, p.t.fx.priority)
    }

    /** House ruling: the other player may use a Quick Effect in an open state too (at a real table, as the phase moves on). */
    @Test
    fun h02_theOtherSeatInAnOpenState() {
        val p = FxPlays(FxRef.game(Side(field = listOf(Slot(FxRef.PAWN, 0))), them = Side(field = listOf(set(FxRef.FLASH, 0)))))
        assertTrue(p.t.state.chain.isEmpty() && p.t.state.active == 0)
        assertTrue(FxMove.Activate(p.uid(FxRef.FLASH, 1), "e1") in FxEngine.moves(p.t, 1))
    }
}
