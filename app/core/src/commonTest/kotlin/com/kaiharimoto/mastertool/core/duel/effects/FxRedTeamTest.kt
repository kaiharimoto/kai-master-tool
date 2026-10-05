package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The red team on Phase D step 1 (D.md §9): each finding against the engine, held by a case. The rulings each case settles
 * name the page that was fetched for it (D.md §2.3½); everything here is our own words and our own fictional cards.
 */
class FxRedTeamTest {
    private fun you(a: Area) = Spot(Rel.YOU, a)
    private fun steps(vararg op: Op) = op.map { Step(it) }
    private fun set(code: Int, i: Int) = Slot(code, i, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL)

    private fun run(t: FxTable, uid: Int, steps: List<Step>, chooser: Chooser = Chooser.FIRST): FxRun.Done {
        val act = FxAct(0, uid, t.code(uid) ?: 0, "e1", FxTag.RESOLVE)
        val d = assertIs<FxRun.Done>(FxSteps.run(t, act, steps, chooser))
        val (st, problem) = DuelRules.applyAll(t.state, d.actions)
        assertNotNull(st, problem)
        return d
    }

    // ---- lens 2: "and" is both or neither ------------------------------------------------------------------------------

    /**
     * YGOrg, "Demystifying Rulings, Part 5: Conjunctions" (fetched): "you have to be able to do both A and B at resolution,
     * otherwise you do nothing" — the engine did the half it could. And the activation needs the whole "and" run to be
     * able to happen, not only its first step.
     */
    @Test
    fun andIsBothOrNeitherAtResolutionAndAtActivation() {
        val draw = Op.Draw(1)
        val send = Op.Send(Pick(from = listOf(you(Area.DECK)), where = Filter.Name(FxRef.CHIMERA)))
        val sieve = FxRef.script(FxRef.SIEVE)
        val both = sieve.copy(effects = listOf(sieve.effects.single().copy(does = listOf(Step(draw), Step(send, Join.AND)))))
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.SIEVE), deck = listOf(FxRef.PAWN, FxRef.PAWN)), book = FxRef.bookWith(both)))
        val uid = p.uid(FxRef.SIEVE)
        assertEquals("It would do nothing now.", FxEngine.refusal(p.t, 0, uid, "e1"), "no Chimera in the Deck: neither half")
        // At resolution: the Deck's only Pawn is drawn by hand while it waits — then neither half happens.
        val t = p.t
        val r = run(t, uid, listOf(Step(Op.Lp(Rel.YOU, Num.Const(5))), Step(Op.Send(Pick(from = listOf(you(Area.BANISHED)))), Join.AND)))
        assertTrue(r.actions.isEmpty() && !r.whole)
        // "Also" (WITH) does what it can.
        val w = run(t, uid, listOf(Step(Op.Lp(Rel.YOU, Num.Const(5))), Step(Op.Send(Pick(from = listOf(you(Area.BANISHED)))), Join.WITH)))
        assertEquals(listOf(DuelAction.Lp(0, 5)), w.actions)
    }

    // ---- lens 2: a trigger on an activation never answers it ----------------------------------------------------------

    /**
     * Yugipedia, "Spell Speed" (fetched): two Spell Speed 1 effects share a chain only when they go off at the same time. A
     * Trigger Effect set off by an activation waited for nothing: the engine put it on the chain at once, under a Counter
     * Trap even. Now it waits for the chain to be over, as every trigger does.
     */
    @Test
    fun aTriggerOnAnActivationWaitsForTheChain() {
        val echo = FxRef.script(FxRef.ECHO)
        val watcher = echo.copy(
            effects = listOf(
                Effect(
                    "e1", "Watch", Kind.TRIGGER, from = setOf(Where.MONSTER_ZONE),
                    trigger = Trigger(On(Event.ACTIVATED), self = false, about = Filter.Kind(CardType.SPELL), timing = Timing.IF, optional = false),
                    does = steps(Op.Lp(Rel.YOU, Num.Const(100))),
                ),
            ),
        )
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.RALLY), field = listOf(Slot(FxRef.ECHO, 0))), book = FxRef.bookWith(watcher)))
        val e = p.uid(FxRef.ECHO)
        p.activate(0, p.uid(FxRef.RALLY))
        assertEquals(listOf(e), p.t.fx.pending.map { it.uid }, "set off by the activation")
        assertEquals(1, p.t.state.chain.size, "and not on the chain")
        assertTrue(FxEngine.refusal(p.t, 0, e, "e1")!!.contains("waits for the chain"))
        p.passBoth(1)
        assertEquals(listOf(e), p.t.fx.links.map { it.uid }, "a chain of its own once the Rally resolved")
    }

    // ---- lens 2: a name may be any card that exists ------------------------------------------------------------------

    /**
     * Yugipedia, "Declare" (fetched, citing the OCG Perfect Rulebook 2015 p. 47 and Konami's FAQ 12551): any existing card's
     * name may be declared, not a Token's. The engine offered only names the seat had seen.
     */
    @Test
    fun aNameDeclarationTakesAnyCardThatExists() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.LAMP, 0))))
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val declare = Op.Declare(DeclareKind.NAME, among = Filter.Kind(CardType.MONSTER), bind = "n")
        // Asked open, with the seat's own names as a start; answered with a card that is nowhere on the table.
        var asked: Decision.Declare? = null
        val chooser = object : Chooser {
            override fun choose(d: Decision): List<Int> = Chooser.FIRST.choose(d)
            override fun name(d: Decision.Declare): Int = FxRef.COLOSSUS.also { asked = d }
        }
        val r = run(t, lamp, steps(declare), chooser)
        assertTrue(asked!!.open)
        assertTrue("Example Lamp" in asked!!.among)
        assertEquals(Declared(DeclareKind.NAME, FxRef.COLOSSUS, "Example Colossus"), r.declared["n"])
        // A name the effect does not let be declared (a Spell, for "a monster's name") cancels; so does a name no card has.
        fun named(code: Int) = FxSteps.run(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.RESOLVE), steps(declare), object : Chooser {
            override fun choose(d: Decision): List<Int> = Chooser.FIRST.choose(d)
            override fun name(d: Decision.Declare): Int = code
        })
        assertEquals(FxRun.Cancelled, named(FxRef.FLASH))
        assertEquals(FxRun.Cancelled, named(123))
        // A filter on a declared name holds for a card off the table read by its printed facts.
        val any = Op.Declare(DeclareKind.NAME, bind = "n")
        assertTrue(FxSteps.able(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.RESOLVE), any), "a name can always be declared")
    }

    // ---- lens 2: a Pendulum Monster leaving the field ------------------------------------------------------------------

    /**
     * Yugipedia, "Pendulum Monster" (fetched, citing the rulebook): "If a Pendulum Monster would be sent from the field to
     * the Graveyard (… even if it was face-down), it is placed face-up in the Extra Deck instead." The engine moved only a
     * face-up one destroyed.
     */
    @Test
    fun aPendulumMonsterSentFromTheFieldGoesToTheExtraDeckFaceUp() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.LAMP, 0), Slot(FxRef.SWING, 1, CardPosition.FACE_DOWN_DEF))))
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val swing = FxRef.uid(t, FxRef.SWING)
        val sent = run(t, lamp, steps(Op.Send(Pick(from = listOf(you(Area.MONSTERS)), where = Filter.Not(Filter.Self)))))
        assertEquals(DuelAction.Move(swing, Place.Pile(0, PileKind.EXTRA), CardPosition.FACE_UP_ATK, "send"), sent.actions.single())
        val tributed = run(t, lamp, steps(Op.Tribute(Pick(ref = null, from = listOf(you(Area.MONSTERS)), where = Filter.FaceDown))))
        assertEquals(Place.Pile(0, PileKind.EXTRA), (tributed.actions.single() as DuelAction.Move).to)
        assertTrue(tributed.state.cards.getValue(swing).faceUp)
    }

    /** Yugipedia, "Destroy" (fetched): "Cards on the field, hand, Main Deck, and Extra Deck can all be destroyed by card effects." */
    @Test
    fun aCardInTheDeckIsDestroyedWhenThePickReachesThere() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.PAWN), gy = listOf(FxRef.TINKER)))
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val d = run(t, lamp, steps(Op.Destroy(Pick(from = listOf(you(Area.DECK))))))
        assertEquals("destroy", (d.actions.single() as DuelAction.Move).how)
        assertFalse(FxSteps.able(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.RESOLVE), Op.Destroy(Pick(from = listOf(you(Area.GY))))), "never in the GY")
    }

    // ---- lens 2: hidden information in the engine's questions ------------------------------------------------------------

    /**
     * The other seat's optional trigger from its hand was asked of the seat moving as "Your opponent's trigger: use Example
     * Beacon's Answer?" — naming a card in a hand it cannot see. Now the question names no hidden card, and says whose
     * choice it is.
     */
    @Test
    fun theOtherSeatsHiddenTriggerIsNotNamedInTheQuestion() {
        val b = FxRef.script(FxRef.BEACON)
        val beacon = b.copy(effects = b.effects.map { it.copy(trigger = it.trigger!!.copy(about = Filter.All(listOf(Filter.NameHas("Example"), Filter.Kind(CardType.MONSTER))))) })
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.CALL), deck = listOf(FxRef.SCOUT)), them = Side(hand = listOf(FxRef.BEACON)), book = FxRef.bookWith(beacon)))
        p.activate(0, p.uid(FxRef.CALL))
        p.pass(1)
        p.asked.clear()
        p.go(0, FxMove.Pass) { d -> if (d is Decision.YesNo) listOf(0) else null }
        val q = p.asked.filterIsInstance<Decision.YesNo>().single { it.by == 1 }
        assertFalse("Beacon" in q.why, q.why)
        assertEquals("Your opponent's trigger: use a card's effect?", q.why)
    }

    // ---- lens 4: hostile scripts -------------------------------------------------------------------------------------

    /** A script nested far deeper than any card needs is never read — no stack overflow, a refusal in words. */
    @Test
    fun aScriptNestedTooDeepIsNeverRead() {
        var does = steps(Op.Draw(1))
        repeat(5_000) { does = listOf(Step(Op.If(Cond.ChainEmpty, then = does))) }
        var where: Filter = Filter.Any
        repeat(5_000) { where = Filter.Not(where) }
        val deep = CardScript(FxRef.CROSSROADS, name = "Example Crossroads", effects = listOf(Effect("e1", "Deep", Kind.ACTIVATION, from = setOf(Where.HAND), does = does)))
        val wide = CardScript(FxRef.MILL, name = "Example Mill", effects = listOf(Effect("e1", "Wide", Kind.ACTIVATION, from = setOf(Where.HAND), does = steps(Op.Send(Pick(from = listOf(you(Area.DECK)), where = where))))))
        val t = FxRef.table(Side(hand = listOf(FxRef.CROSSROADS, FxRef.MILL), deck = listOf(FxRef.PAWN)), book = FxRef.bookWith(deep, wide))
        assertTrue(FxWalk.unread(deep.effects.single()) && FxWalk.unread(wide.effects.single()))
        assertFalse(FxEngine.moves(t, 0).any { it is FxMove.Activate })
        assertEquals("This effect nests deeper than the engine reads.", FxEngine.refusal(t, 0, FxRef.uid(t, FxRef.CROSSROADS), "e1"))
        // Run by hand past its check, the executor stops at its depth.
        val r = FxSteps.run(t, FxAct(0, FxRef.uid(t, FxRef.CROSSROADS), FxRef.CROSSROADS, "e1", FxTag.RESOLVE), does, Chooser.FIRST)
        assertIs<FxRun.Refused>(r)
    }

    /** A pick asking for more cards than exist, and numbers past any card's, are clamped; nothing hangs or overflows. */
    @Test
    fun hugeNumbersAreClamped() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = List(10) { FxRef.PAWN }))
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val sent = run(t, lamp, steps(Op.Send(Pick(n = Int.MAX_VALUE, from = listOf(you(Area.DECK))))))
        assertEquals(10, sent.actions.size)
        assertFalse(sent.whole, "it asked for more than there were")
        val lp = run(t, lamp, steps(Op.Lp(Rel.YOU, Num.Const(Int.MIN_VALUE))))
        assertEquals(0, lp.state.seats[0].lp)
        val gain = run(t, lamp, steps(Op.Lp(Rel.YOU, Num.Const(Int.MAX_VALUE)), Op.Lp(Rel.YOU, Num.Const(Int.MAX_VALUE))))
        assertEquals(Int.MAX_VALUE, gain.state.seats[0].lp, "a gain past the top stays at the top, never wraps to 0")
        val level = run(t, lamp, steps(Op.ChangeLevel(Pick(ref = Pick.SELF), by = Num.Const(Int.MAX_VALUE))))
        assertTrue(FxTable(level.state, level.fx, t.book, t.facts).level(lamp)!! >= 1)
        assertIs<FxRun.Done>(FxSteps.run(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.RESOLVE), steps(Op.Counter(Pick(ref = Pick.SELF), "x", Int.MAX_VALUE), Op.Counter(Pick(ref = Pick.SELF), "x", Int.MAX_VALUE)), Chooser.FIRST))
    }

    /**
     * A Ritual Summon whose Tributes may come from a 40-card Deck of Level 4 monsters, for a Level 6 no set equals: every
     * subset up to twelve was walked (billions — the moves never came back). Now the search for material sets is bounded
     * ([FxProcs.MOST_TRIED]); a set that exists is still found.
     */
    @Test
    fun aMaterialSearchIsBounded() {
        val rite = FxRef.script(FxRef.RITE)
        val wide = rite.copy(effects = listOf(rite.effects.single().copy(does = steps(Op.RitualSummon(Filter.Any, tributesFrom = listOf(you(Area.DECK)), levels = LevelRule.EQUAL)))))
        val none = FxRef.table(Side(hand = listOf(FxRef.RITE, FxRef.ORACLE), deck = List(40) { FxRef.PAWN }), book = FxRef.bookWith(wide))
        val start = TimeSource.Monotonic.markNow()
        val moves = FxEngine.moves(none, 0)
        val took = start.elapsedNow().inWholeMilliseconds
        assertTrue(took < 5_000, "the moves took $took ms")
        assertFalse(FxMove.Activate(FxRef.uid(none, FxRef.RITE), "e1") in moves, "no Tributes of Level 4 make Level 6")
        val some = FxRef.table(Side(hand = listOf(FxRef.RITE, FxRef.ORACLE), deck = List(40) { FxRef.SPRITE }), book = FxRef.bookWith(wide))
        assertTrue(FxMove.Activate(FxRef.uid(some, FxRef.RITE), "e1") in FxEngine.moves(some, 0), "three Level 2s make Level 6")
    }

    /**
     * Two cards that set each other off forever, with no once-per-turn: every move stops within its bounds, and a driver
     * that keeps going makes no move larger than [FxScribe.MOST_ACTIONS].
     */
    @Test
    fun triggersThatSetEachOtherOffStopWithinEachMove() {
        val echo = FxRef.script(FxRef.ECHO)
        val loop = echo.copy(
            effects = listOf(
                Effect(
                    "e1", "Again", Kind.TRIGGER, from = setOf(Where.MONSTER_ZONE),
                    trigger = Trigger(On(Event.ACTIVATED), self = false, timing = Timing.IF, optional = false),
                    does = steps(Op.Lp(Rel.YOU, Num.Const(1))),
                ),
            ),
        )
        val p = FxPlays(FxRef.game(Side(hand = listOf(FxRef.RALLY), field = listOf(Slot(FxRef.ECHO, 0), Slot(FxRef.ECHO, 1))), book = FxRef.bookWith(loop)))
        p.activate(0, p.uid(FxRef.RALLY))
        repeat(60) {
            val seat = FxEngine.next(p.t)
            val move = FxEngine.moves(p.t, seat).firstOrNull { it is FxMove.Pass } ?: return@repeat
            val d = p.go(seat, move)
            assertTrue(d.actions.size <= FxScribe.MOST_ACTIONS)
        }
        assertTrue(p.t.state.seats[0].lp > 8000, "it kept going, a move at a time")
    }

    // ---- lens 3: determinism ------------------------------------------------------------------------------------------

    /** Longer walks than the physics test's, on more seeds: every action physics, and the log's fold the engine's state. */
    @Test
    fun longWalksStayPhysicsAndFoldExactly() {
        for (seed in 100L..115L) {
            var last: FxWalker.Step? = null
            val g = FxWalker.walk(seed, steps = 200) { st ->
                val p = st.play
                if (p is FxPlay.Done) {
                    val (s, problem) = DuelRules.applyAll(st.before.state, p.actions)
                    assertNotNull(s, "seed $seed: $problem")
                    assertEquals(p.fx, FxFold.fold(st.game.header, st.game.played, st.before.book, st.before.facts, st.game.state), "seed $seed")
                } else {
                    assertEquals(st.game, last?.game ?: st.game, "seed $seed: a refused or cancelled move commits nothing")
                }
                last = st
            }
            assertEquals(g, FxWalker.walk(seed, steps = 200), "seed $seed: the same walk twice")
        }
    }

    /** Undo, then fold the log again: the engine's state is the one it had before the undone move. */
    @Test
    fun refoldingAfterUndoIsTheStateBefore() {
        for (seed in 7L..14L) {
            val before = mutableListOf<Pair<FxState, Int>>()
            val g = FxWalker.walk(seed, steps = 40) { st -> if (st.play is FxPlay.Done) before += st.before.fx.forTurn(st.before.state.turn) to st.game.cursor }
            var game = g
            while (game.cursor > game.floor && before.isNotEmpty()) {
                val (fx, at) = before.removeAt(before.size - 1)
                if (game.cursor != at) break
                game = game.undo()
                val now = FxFold.fold(game.header, game.played, FxRef.book, FxRef.facts, game.state)
                assertEquals(fx, now, "seed $seed: refolded after undo")
            }
        }
    }
}
