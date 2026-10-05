package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.Lock
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * Each step of the vocabulary against the table (D.md §2.2, §10 `FxVocabularyTest`): the ordinary actions it makes, with
 * their `how` words, what it binds, whether it happened in full, and what it leaves in `FxState` — and the joins between
 * steps.
 */
class FxVocabularyTest {
    private fun you(a: Area) = Spot(Rel.YOU, a)
    private fun them(a: Area) = Spot(Rel.THEM, a)
    private fun s(vararg op: Op) = op.map { Step(it) }

    /** [steps] run for [uid]'s effect on the table [g] makes, by seat 0; every action applied and folded on the way. */
    private fun run(g: Pair<*, FxTable>, uid: Int, steps: List<Step>, bound: Map<String, List<Int>> = emptyMap(), link: Int? = null, answer: (Decision) -> List<Int>? = { null }): FxRun.Done {
        val t = g.second
        val act = FxAct(0, uid, t.code(uid) ?: 0, "e1", FxTag.RESOLVE, link, bound)
        val r = FxSteps.run(t, act, steps, Chooser { d -> answer(d) ?: Chooser.FIRST.choose(d) })
        val d = assertIs<FxRun.Done>(r, "$r")
        val (st, problem) = DuelRules.applyAll(t.state, d.actions)
        assertNotNull(st, problem)
        assertEquals(d.state, st)
        assertTrue(d.tags.all { it.part == FxTag.RESOLVE && it.uid == uid && it.memo?.batch != null })
        return d
    }

    private fun moves(d: FxRun.Done) = d.actions.filterIsInstance<DuelAction.Move>()

    @Test
    fun addSearchesRevealsAndShufflesAndSalvagesFromTheGy() {
        val g = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), gy = listOf(FxRef.TINKER), deck = listOf(FxRef.PAWN, FxRef.SCOUT, FxRef.PAWN)))
        val t = g.second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val d = run(g, lamp, s(Op.Add(Pick(from = listOf(you(Area.DECK)), where = Filter.Name(FxRef.SCOUT), bind = "found"))))
        assertEquals(DuelAction.Move(scout, Place.Pile(0, PileKind.HAND), how = "search"), d.actions[0])
        assertEquals(DuelAction.Reveal(0, listOf(scout)), d.actions[1])
        assertIs<DuelAction.Shuffle>(d.actions[2], "a Deck looked through is shuffled")
        assertTrue((d.actions[2] as DuelAction.Shuffle).salt != 0L, "stamped with the duel's dice")
        assertEquals(listOf(scout), d.bound["found"])
        assertTrue(d.whole)
        val tinker = FxRef.uid(t, FxRef.TINKER)
        assertEquals(listOf(DuelAction.Move(tinker, Place.Pile(0, PileKind.HAND), how = "add")), moves(run(g, lamp, s(Op.Add(Pick(from = listOf(you(Area.GY))))))))
        // Nothing to add: it did not happen.
        assertFalse(run(g, lamp, s(Op.Add(Pick(from = listOf(you(Area.BANISHED)))))).whole)
    }

    @Test
    fun sendDiscardDestroyBanishTributeAndReturn() {
        val g = FxRef.game(
            Side(hand = listOf(FxRef.ECHO), field = listOf(Slot(FxRef.LAMP, 0), Slot(FxRef.SWING, 1), Slot(FxRef.BRIDGE, 0, kind = ZoneKind.EMZ)), deck = listOf(FxRef.PAWN)),
            them = Side(field = listOf(Slot(FxRef.PAWN, 0))),
        )
        val t = g.second
        val (lamp, echo, swing, bridge) = listOf(FxRef.LAMP, FxRef.ECHO, FxRef.SWING, FxRef.BRIDGE).map { FxRef.uid(t, it) }
        val pawn = FxRef.uid(t, FxRef.PAWN, 1)
        assertEquals(listOf(DuelAction.Move(pawn, Place.Pile(1, PileKind.GY), how = "send")), moves(run(g, lamp, s(Op.Send(Pick(from = listOf(them(Area.MONSTERS))))))))
        assertEquals(listOf(DuelAction.Move(echo, Place.Pile(0, PileKind.GY), how = "discard")), moves(run(g, lamp, s(Op.Discard(Pick())))), "from your hand unless it says")
        // A Pendulum Monster destroyed on the field goes to the Extra Deck face-up.
        assertEquals(
            listOf(DuelAction.Move(swing, Place.Pile(0, PileKind.EXTRA), CardPosition.FACE_UP_ATK, "destroy")),
            moves(run(g, lamp, s(Op.Destroy(Pick(ref = "x"))), bound = mapOf("x" to listOf(swing)))),
        )
        assertEquals(
            listOf(DuelAction.Move(pawn, Place.Pile(1, PileKind.BANISHED), CardPosition.FACE_DOWN_DEF, "banish")),
            moves(run(g, lamp, s(Op.Banish(Pick(from = listOf(them(Area.MONSTERS))), faceDown = true)))),
        )
        val tribute = run(g, lamp, s(Op.Tribute(Pick(ref = Pick.SELF))))
        assertEquals(listOf(DuelAction.Move(lamp, Place.Pile(0, PileKind.GY), how = "tribute")), moves(tribute))
        assertTrue(lamp in tribute.fx.sent)
        // An Extra Deck monster sent back to the hand goes to the Extra Deck; a card to the bottom of the Deck.
        assertEquals(listOf(DuelAction.Move(bridge, Place.Pile(0, PileKind.EXTRA), how = "return")), moves(run(g, lamp, s(Op.Return(Pick(ref = "x"), Dest.HAND)), bound = mapOf("x" to listOf(bridge)))))
        assertEquals(listOf(DuelAction.Move(pawn, Place.Pile(1, PileKind.DECK, Place.BOTTOM), how = "return")), moves(run(g, lamp, s(Op.Return(Pick(from = listOf(them(Area.MONSTERS))), Dest.DECK_BOTTOM)))))
        val shuffled = run(g, lamp, s(Op.Return(Pick(from = listOf(them(Area.MONSTERS))), Dest.DECK_SHUFFLED)))
        assertEquals(DuelAction.Shuffle::class, shuffled.actions.last()::class)
        // The general move: into a zone, placed.
        val placed = run(g, lamp, s(Op.Move(Pick(from = listOf(you(Area.HAND))), Dest.MONSTER_ZONE, faceDown = true)))
        assertEquals(DuelAction.Move(echo, Place.Zone(0, ZoneKind.MONSTER, 2), CardPosition.FACE_DOWN_DEF, "place"), moves(placed).single())
    }

    @Test
    fun drawShuffleRevealLifePointsAndCounters() {
        val g = FxRef.game(Side(hand = listOf(FxRef.ECHO), field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.PAWN)))
        val t = g.second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val draw = run(g, lamp, s(Op.Draw(2)))
        assertEquals(listOf(DuelAction.Draw(0, 1)), draw.actions, "one card left: drawn, and not in full")
        assertFalse(draw.whole)
        assertIs<DuelAction.Shuffle>(run(g, lamp, s(Op.Shuffle(Rel.YOU, Area.HAND))).actions.single())
        assertEquals(DuelAction.Reveal(0, listOf(FxRef.uid(t, FxRef.ECHO)), 1), run(g, lamp, s(Op.Reveal(Pick(from = listOf(you(Area.HAND)))))).actions.single())
        assertEquals(listOf(DuelAction.Lp(1, -700), DuelAction.Lp(0, 300)), run(g, lamp, s(Op.Lp(Rel.THEM, Num.Const(-700)), Op.Lp(Rel.YOU, Num.Const(300)))).actions)
        assertEquals(listOf(DuelAction.Lp(0, -2000)), run(g, lamp, s(Op.PayLp(Num.Const(2000)))).actions)
        assertFalse(run(g, lamp, s(Op.PayLp(Num.Const(9000)))).whole, "more than you have is never paid")
        assertEquals(listOf(DuelAction.Counter(lamp, 2, "Spell")), run(g, lamp, s(Op.Counter(Pick(ref = Pick.SELF), "Spell", 2))).actions)
        assertFalse(run(g, lamp, s(Op.Counter(Pick(ref = Pick.SELF), "Spell", -1))).whole, "no counter to remove")
    }

    @Test
    fun specialSummonsAskZoneAndPositionAndHonourTheRules() {
        val g = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), gy = listOf(FxRef.SCOUT, FxRef.BRIDGE), deck = listOf(FxRef.PAWN)))
        val t = g.second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val d = run(g, lamp, s(Op.SpecialSummon(Pick(from = listOf(you(Area.GY))))), answer = { d -> if (d is Decision.Position) listOf(1) else null })
        assertEquals(listOf(DuelAction.Move(scout, Place.Zone(0, ZoneKind.MONSTER, 1), CardPosition.FACE_UP_DEF, "special")), d.actions, "Bridge was never properly summoned: not a candidate")
        assertEquals(ProcKind.SPECIAL, d.fx.summoned[scout])
        assertEquals(setOf(FxRef.SCOUT), d.fx.specials[0])
        assertTrue(scout in d.fx.proper, "a Main Deck monster with no rule of its own is properly summoned")
        // Under a lock on Special Summons nothing is summoned.
        val locked = g.first to t.copy(fx = t.fx.copy(restrictions = listOf(InForce(Restriction(Ban.SPECIAL_SUMMON), 0, lamp, 2))))
        assertFalse(run(locked, lamp, s(Op.SpecialSummon(Pick(from = listOf(you(Area.GY)))))).whole)
    }

    @Test
    fun fusionRitualSynchroXyzAndLinkSummonsByEffect() {
        val g = FxRef.game(
            Side(hand = listOf(FxRef.SCOUT, FxRef.LAMP, FxRef.ORACLE), field = listOf(Slot(FxRef.PAWN, 0), Slot(FxRef.TINKER, 1), Slot(FxRef.WARDEN, 2)), extra = listOf(FxRef.CHIMERA, FxRef.PALADIN, FxRef.REGENT, FxRef.BRIDGE)),
        )
        val t = g.second
        val (scout, lamp, oracle, pawn, warden) = listOf(FxRef.SCOUT, FxRef.LAMP, FxRef.ORACLE, FxRef.PAWN, FxRef.WARDEN).map { FxRef.uid(t, it) }
        fun pick(vararg uids: Int): (Decision) -> List<Int>? = { d -> if (d is Decision.Cards && d.among.containsAll(uids.toList()) && d.max >= uids.size) uids.map { d.among.indexOf(it) } else null }
        val fusion = run(g, scout, s(Op.FusionSummon(Filter.Frame(CardFrame.FUSION))), answer = pick(scout, lamp))
        assertEquals(listOf("material", "material", "fusion"), moves(fusion).map { it.how })
        assertEquals(ProcKind.FUSION, fusion.fx.summoned[FxRef.uid(t, FxRef.CHIMERA)])
        assertTrue(FxRef.uid(t, FxRef.CHIMERA) in fusion.fx.proper)
        val ritual = run(g, scout, s(Op.RitualSummon(Filter.Name(FxRef.ORACLE))), answer = pick(lamp, FxRef.uid(t, FxRef.TINKER)))
        assertEquals(listOf("tribute", "tribute", "ritual"), moves(ritual).map { it.how })
        assertEquals(ProcKind.RITUAL, ritual.fx.summoned[oracle])
        val synchro = g.first to FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0), Slot(FxRef.PAWN, 1)), extra = listOf(FxRef.PALADIN))).second
        val sl = FxRef.uid(synchro.second, FxRef.LAMP)
        val sy = run(synchro, sl, s(Op.SynchroSummon()))
        assertEquals("synchro", moves(sy).last().how)
        assertEquals(ProcKind.SYNCHRO, sy.fx.summoned[FxRef.uid(synchro.second, FxRef.PALADIN)])
        val xyz = run(g, pawn, s(Op.XyzSummon()), answer = pick(pawn, warden))
        assertEquals(ProcKind.XYZ, xyz.fx.summoned[FxRef.uid(t, FxRef.REGENT)])
        assertEquals(listOf(warden), xyz.state.cards.getValue(FxRef.uid(t, FxRef.REGENT)).under.filter { it == warden })
        val link = run(g, pawn, s(Op.LinkSummon(Filter.Name(FxRef.BRIDGE))), answer = pick(pawn, warden))
        assertEquals(ProcKind.LINK, link.fx.summoned[FxRef.uid(t, FxRef.BRIDGE)])
        assertEquals(CardPosition.FACE_UP_ATK, link.state.cards.getValue(FxRef.uid(t, FxRef.BRIDGE)).pos)
    }

    @Test
    fun attachDetachAndTokens() {
        val g = FxRef.game(Side(field = listOf(Slot(FxRef.LORD, 0)), gy = listOf(FxRef.SCOUT, FxRef.PAWN)))
        val t = g.second
        val lord = FxRef.uid(t, FxRef.LORD)
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val attached = run(g, lord, s(Op.Attach(Pick(from = listOf(you(Area.GY)), where = Filter.Name(FxRef.SCOUT)))))
        assertEquals(listOf(DuelAction.Move(scout, Place.Under(lord), how = "attach")), attached.actions)
        val g2 = g.first to t.copy(state = attached.state, fx = attached.fx)
        val detached = run(g2, lord, s(Op.Detach(1)))
        assertEquals(listOf(DuelAction.Move(scout, Place.Pile(0, PileKind.GY), how = "detach")), detached.actions)
        assertFalse(run(g, lord, s(Op.Detach(1))).whole, "nothing to detach")
        val tokens = run(g, lord, s(Op.Token("Seed Token", CardAttribute.EARTH, "Plant", 2, 0, 0, n = 2, pos = Pos.DEFENSE)))
        val made = tokens.actions.filterIsInstance<DuelAction.Token>()
        assertEquals(2, made.size)
        assertEquals(listOf(100_000, 100_001), made.map { it.uid }, "numbered as the log will number them")
        assertEquals(2, FxTable(tokens.state, tokens.fx, t.book, t.facts).level(100_000), "the token's Level, kept by the engine")
        assertEquals(ProcKind.SPECIAL, tokens.fx.summoned[100_001])
    }

    @Test
    fun levelsGrantsRestrictionsAndDeclarationsAreWrittenDown() {
        val g = FxRef.game(Side(hand = listOf(FxRef.SCOUT), field = listOf(Slot(FxRef.TINKER, 0)), deck = listOf(FxRef.LAMP, FxRef.SCOUT, FxRef.ECHO)))
        val t = g.second
        val tinker = FxRef.uid(t, FxRef.TINKER)
        val lvl = run(g, tinker, s(Op.ChangeLevel(Pick(ref = Pick.SELF), by = Num.Const(2))))
        assertEquals(DuelAction.Note("Example Tinker is Level 5 this turn.", 0), lvl.actions.single())
        assertEquals(5, FxTable(lvl.state, lvl.fx, t.book, t.facts).level(tinker))
        val grant = run(g, tinker, s(Op.NormalSummonAgain(Filter.NameHas("Example"))))
        assertEquals(listOf(NormalGrant(0, tinker, Filter.NameHas("Example"))), grant.fx.grants)
        val lock = run(g, tinker, s(Op.Restrict(Restriction(Ban.SPECIAL_SUMMON_FROM_EXTRA, except = Filter.Frame(CardFrame.SYNCHRO)))))
        assertEquals(DuelAction.Lock(0, "No Special Summons from the Extra Deck, except as Example Tinker allows", Lock.UNTIL_TURN, id = 1), lock.actions.single())
        assertEquals(listOf(InForce(Restriction(Ban.SPECIAL_SUMMON_FROM_EXTRA, except = Filter.Frame(CardFrame.SYNCHRO)), 0, tinker, 2, lock = 1)), lock.fx.restrictions, "its Lock named, so taking the Lock off lifts it")
        // Declare DARK; the Add reads it.
        val declared = run(
            g, tinker,
            listOf(Step(Op.Declare(DeclareKind.ATTRIBUTE, bind = "a")), Step(Op.Add(Pick(from = listOf(you(Area.DECK)), where = Filter.Declared("a"))), Join.THEN)),
            answer = { d -> if (d is Decision.Declare) listOf(d.among.indexOf("DARK")) else null },
        )
        assertEquals(DuelAction.Note("Example Tinker: declared DARK.", 0), declared.actions.first())
        assertEquals(FxRef.uid(t, FxRef.ECHO), moves(declared).single().uid, "Echo is the DARK one")
        assertEquals(Declared(DeclareKind.ATTRIBUTE, word = "DARK"), declared.declared["a"])
    }

    @Test
    fun chooseIfAndTheJoins() {
        val g = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0)), deck = listOf(FxRef.PAWN, FxRef.PAWN)))
        val t = g.second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val opt = Op.Choose(listOf(s(Op.Draw(1)), s(Op.Lp(Rel.YOU, Num.Const(1000)))), labels = listOf("Draw", "Gain"))
        assertEquals(listOf(DuelAction.Lp(0, 1000)), run(g, lamp, s(opt), answer = { d -> if (d is Decision.Option) listOf(1) else null }).actions)
        assertEquals(
            listOf(DuelAction.Lp(0, -1)),
            run(g, lamp, s(Op.If(Cond.NoMonsters(Rel.THEM), then = s(Op.Lp(Rel.YOU, Num.Const(-1))), otherwise = s(Op.Lp(Rel.YOU, Num.Const(1)))))).actions,
        )
        // AND_IF_YOU_DO and THEN need the step before to have happened in full; WITH ("also") and ALSO ("also, after
        // that") do not; AND ("and") is both or neither (YGOrg, Demystifying Rulings Part 5: "you have to be able to do both
        // A and B at resolution, otherwise you do nothing").
        val nothing = Op.Send(Pick(from = listOf(you(Area.BANISHED))))
        val gain = Op.Lp(Rel.YOU, Num.Const(10))
        assertEquals(0, run(g, lamp, listOf(Step(nothing), Step(gain, Join.AND_IF_YOU_DO))).actions.size)
        assertEquals(0, run(g, lamp, listOf(Step(nothing), Step(gain, Join.THEN))).actions.size)
        assertEquals(0, run(g, lamp, listOf(Step(nothing), Step(gain, Join.AND))).actions.size)
        assertEquals(0, run(g, lamp, listOf(Step(gain), Step(nothing, Join.AND))).actions.size, "neither half when one cannot happen")
        assertEquals(1, run(g, lamp, listOf(Step(nothing), Step(gain, Join.WITH))).actions.size)
        assertEquals(1, run(g, lamp, listOf(Step(nothing), Step(gain, Join.ALSO))).actions.size)
        // AND, AND_IF_YOU_DO and WITH are one batch; THEN and ALSO begin new ones.
        val batches = run(g, lamp, listOf(Step(gain), Step(gain, Join.AND), Step(gain, Join.WITH), Step(gain, Join.THEN), Step(gain, Join.ALSO))).tags.map { it.memo!!.batch!! }
        assertEquals(listOf(0, 0, 0, 1, 2), batches.map { it - batches[0] })
    }

    @Test
    fun negateAndTheUnreadStep() {
        val g = FxRef.game(Side(hand = listOf(FxRef.CALL), field = listOf(Slot(FxRef.DENIAL, 0, CardPosition.FACE_UP_ATK, ZoneKind.SPELL))))
        val t = g.second
        val call = FxRef.uid(t, FxRef.CALL)
        val (onChain, _) = DuelRules.applyAll(t.state, listOf(DuelAction.Move(call, Place.Zone(0, ZoneKind.SPELL, 1), CardPosition.FACE_UP_ATK, "activate"), DuelAction.ChainAdd(0, call)))
        val g2 = g.first to t.copy(state = onChain!!)
        val denial = FxRef.uid(t, FxRef.DENIAL)
        val n = run(g2, denial, s(Op.Negate(NegWhat.ACTIVATION, bind = "n")), link = 2)
        assertEquals(listOf(DuelAction.Negate(0, 1), DuelAction.Move(call, Place.Pile(0, PileKind.GY), how = "negate")), n.actions)
        assertEquals(listOf(call), n.bound["n"])
        val e = run(g2, denial, s(Op.Negate(NegWhat.EFFECT)), link = 2)
        assertEquals(listOf(DuelAction.Negate(0, 1)), e.actions)
        assertTrue(e.tags.single().memo!!.effectOnly)
        // A step this build cannot read refuses the whole run.
        val unread = FxSteps.run(t, FxAct(0, denial, FxRef.DENIAL, "e1", FxTag.RESOLVE), s(Op.Unknown(JsonObject(emptyMap()))), Chooser.FIRST)
        assertIs<FxRun.Refused>(unread)
    }

    @Test
    fun targetsAreChosenAndBoundWithoutAMove() {
        val g = FxRef.game(Side(field = listOf(Slot(FxRef.LAMP, 0))), them = Side(field = listOf(Slot(FxRef.PAWN, 0), Slot(FxRef.KNIGHT, 1))))
        val t = g.second
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val knight = FxRef.uid(t, FxRef.KNIGHT, 1)
        val r = assertIs<FxRun.Done>(
            FxSteps.target(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.ACTIVATE), listOf(Pick(from = listOf(them(Area.MONSTERS)), bind = "t")), Chooser { d -> listOf((d as Decision.Cards).among.indexOf(knight)) }),
        )
        assertTrue(r.actions.isEmpty())
        assertEquals(listOf(knight), r.bound[Pick.TARGETS])
        assertEquals(listOf(knight), r.bound["t"])
        assertIs<FxRun.Refused>(FxSteps.target(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.ACTIVATE), listOf(Pick(n = 3, from = listOf(them(Area.MONSTERS)))), Chooser.FIRST))
        assertIs<FxRun.Cancelled>(FxSteps.target(t, FxAct(0, lamp, FxRef.LAMP, "e1", FxTag.ACTIVATE), listOf(Pick(from = listOf(them(Area.MONSTERS)))), Chooser { Chooser.CANCEL }))
    }
}
