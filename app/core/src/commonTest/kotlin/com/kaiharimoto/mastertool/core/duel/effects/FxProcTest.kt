package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
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

/**
 * The summoning procedures (D.md §2.2 `Proc`): Link with its rating sum and arrows, Synchro, Xyz, Fusion and Ritual
 * materials for the effects that make them, inherent summons and "must first be" — each listing its legal material sets
 * and zones, and making ordinary actions the table accepts.
 */
class FxProcTest {
    private fun apply(t: FxTable, vararg a: DuelAction): FxTable {
        val (s, problem) = DuelRules.applyAll(t.state, a.toList())
        return t.copy(state = s ?: error(problem!!))
    }

    private fun after(t: FxTable, p: FxPlay): FxTable {
        val d = assertIs<FxPlay.Done>(p, "$p")
        // Every action list the engine makes is the table's to accept, and committing it carries its tags.
        val (s, problem) = DuelRules.applyAll(t.state, d.actions)
        assertNotNull(s, problem)
        assertEquals(d.state, s)
        assertEquals(d.actions.size, d.tags.size)
        return t.copy(state = d.state, fx = d.fx)
    }

    /** Answers a cards decision with [uids], by their places in its list. */
    private fun cards(vararg uids: Int): (Decision) -> List<Int> = { d -> (d as Decision.Cards).among.let { a -> uids.map { a.indexOf(it) } } }
    private fun zone(z: Place.Zone): (Decision) -> List<Int> = { d -> listOf((d as Decision.Zone).among.indexOf(z)) }
    private val attack: (Decision) -> List<Int> = { d -> listOf(assertIs<Decision.Position>(d).among.indexOf(CardPosition.FACE_UP_ATK)) }
    private val defense: (Decision) -> List<Int> = { d -> listOf(assertIs<Decision.Position>(d).among.indexOf(CardPosition.FACE_UP_DEF)) }
    private fun emz(i: Int) = Place.Zone(0, ZoneKind.EMZ, i)
    private fun m(seat: Int, i: Int) = Place.Zone(seat, ZoneKind.MONSTER, i)

    @Test
    fun aLinkSummonSendsItsMaterialsAndTakesAnExtraMonsterZone() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.SCOUT, 0), Slot(FxRef.TINKER, 1)), extra = listOf(FxRef.BRIDGE)))
        val bridge = FxRef.uid(t, FxRef.BRIDGE)
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val tinker = FxRef.uid(t, FxRef.TINKER)
        val option = FxProcs.options(t, 0, bridge).single()
        assertEquals(listOf(listOf(scout, tinker)), option.sets)
        assertEquals(listOf(emz(0), emz(1)), option.zones.single(), "a Link Monster: the Extra Monster Zones, nothing linked yet")
        assertTrue(FxMove.Procedure(bridge, 0) in FxEngine.moves(t, 0))
        val p = FxEngine.play(t, 0, FxMove.Procedure(bridge, 0), FxRef.Answers(zone(emz(0))))
        val d = p as FxPlay.Done
        assertEquals(
            listOf(
                DuelAction.Move(scout, Place.Pile(0, PileKind.GY), how = "material"),
                DuelAction.Move(tinker, Place.Pile(0, PileKind.GY), how = "material"),
                DuelAction.Move(bridge, emz(0), CardPosition.FACE_UP_ATK, "special"),
            ),
            d.actions,
        )
        // One batch, carried in each tag's memo (agent (c)'s fold reads it); otherwise the procedure's tag.
        assertTrue(d.tags.all { it.copy(memo = null) == FxTag(bridge, FxTag.PROC, FxTag.PROC, script = FxCodec.hash(FxRef.book.script(FxRef.BRIDGE)!!)) })
        assertEquals(1, d.tags.map { it.memo?.batch }.toSet().size)
        val n = after(t, p)
        assertEquals(ProcKind.LINK, n.fx.summoned[bridge])
        assertTrue(bridge in n.fx.proper && n.fx.sent == setOf(scout, tinker))
        assertEquals(
            listOf(Event.MATERIAL, Event.SENT_TO_GY, Event.LEFT_FIELD, Event.MATERIAL, Event.SENT_TO_GY, Event.LEFT_FIELD, Event.SUMMONED, Event.SPECIAL_SUMMONED),
            d.events.map { it.event },
        )
        assertTrue(d.events.filter { it.event == Event.MATERIAL }.all { it.cause == Cause.MATERIAL && it.summon == ProcKind.LINK })
        assertEquals(setOf(FxRef.BRIDGE), n.fx.specials[0])
        // Its arrows point down-left and down-right: Main Monster Zones 1 and 3 (indexes 0 and 2).
        assertEquals(setOf(m(0, 0), m(0, 2)), FxRules.linkedZones(n, 0))
    }

    @Test
    fun aLinkMaterialCountsOneOrItsRating() {
        // Arch for "2+ monsters" (any), rating 3: Bridge (2) and one more, or three of rating 1.
        val arch = CardScript(FxRef.ARCH, name = "Example Arch", summon = SummonRule(normal = false, procs = listOf(Proc.Link(2, 3))))
        val book = ScriptBook.all(FxRef.scripts.filter { it.card != FxRef.ARCH } + arch, FxRef.facts::canonical)
        val t = FxRef.table(
            Side(field = listOf(Slot(FxRef.BRIDGE, 0, kind = ZoneKind.EMZ), Slot(FxRef.SCOUT, 0), Slot(FxRef.TINKER, 2)), extra = listOf(FxRef.ARCH)),
            book = book,
        )
        val bridge = FxRef.uid(t, FxRef.BRIDGE)
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val tinker = FxRef.uid(t, FxRef.TINKER)
        val sets = FxProcs.linkSets(t, 0, FxRef.uid(t, FxRef.ARCH), book.script(FxRef.ARCH)!!.summon!!.procs.single() as Proc.Link).map { it.toSet() }
        assertEquals(setOf(setOf(bridge, scout), setOf(bridge, tinker), setOf(bridge, scout, tinker)), sets.toSet(), "Bridge as 2 with one more, or as 1 with two more")
        assertFalse(setOf(scout, tinker) in sets, "1 + 1 is not 3")
        // Arch's own script: "2+ Effect monsters, including an Example": the plain Bridge is no Effect Monster.
        val own = FxProcs.linkSets(t.copy(book = FxRef.book), 0, FxRef.uid(t, FxRef.ARCH), FxRef.book.script(FxRef.ARCH)!!.summon!!.procs.single() as Proc.Link)
        assertTrue(own.isEmpty(), "two Effect monsters make 2, never 3")
    }

    @Test
    fun theArrowsPointAsTheirControllerFacesTheTable() {
        // Seat 0's Arch in Main Monster Zone 2 (index 1): Top is the left Extra Monster Zone, Left and Right its neighbours.
        val t = FxRef.table(
            Side(field = listOf(Slot(FxRef.ARCH, 1))),
            them = Side(field = listOf(Slot(FxRef.BRIDGE, 1, kind = ZoneKind.EMZ), Slot(FxRef.ARCH, 3))),
        )
        val mine = FxRef.uid(t, FxRef.ARCH)
        assertEquals(setOf(Place.Zone(0, ZoneKind.EMZ, 0), m(0, 0), m(0, 2)), FxRules.pointsTo(t, mine).toSet())
        // Their Bridge in the right Extra Monster Zone (their left) points down towards their own row: their indexes 0 and 2.
        val theirs = FxRef.uid(t, FxRef.BRIDGE, 1)
        assertEquals(setOf(m(1, 0), m(1, 2)), FxRules.pointsTo(t, theirs).toSet())
        // Their Arch in their Main Monster Zone 4 (index 3): its Top is the right Extra Monster Zone as they face it.
        val theirArch = FxRef.uid(t, FxRef.ARCH, 1)
        assertEquals(setOf(Place.Zone(0, ZoneKind.EMZ, 0), m(1, 2), m(1, 4)), FxRules.pointsTo(t, theirArch).toSet())
        assertEquals(setOf(m(0, 0), m(0, 2)), FxRules.linkedZones(t, 0))
        assertEquals(setOf(m(1, 0), m(1, 2), m(1, 4)), FxRules.linkedZones(t, 1))
        // A face-down Link points nowhere.
        assertEquals(emptyList(), FxRules.pointsTo(apply(t, DuelAction.Position(mine, CardPosition.FACE_DOWN_DEF)), mine))
    }

    @Test
    fun aSeatUsesOneExtraMonsterZoneAndALinkNeedsALinkedZoneOtherwise() {
        val t = FxRef.table(
            Side(field = listOf(Slot(FxRef.BRIDGE, 0, kind = ZoneKind.EMZ), Slot(FxRef.SCOUT, 1), Slot(FxRef.TINKER, 3), Slot(FxRef.LAMP, 4)), extra = listOf(FxRef.SPIDER, FxRef.REGENT, FxRef.SWING)),
        )
        val bridge = FxRef.uid(t, FxRef.BRIDGE)
        val spider = FxRef.uid(t, FxRef.SPIDER)
        // Bridge points at zones 0 and 2 (index); index 0 is free; the right Extra Monster Zone is barred, Bridge holding the left.
        assertEquals(listOf(m(0, 0), m(0, 2)), FxRules.summonZones(t, 0, spider), "zone 2 is free once nothing stands there")
        val scout = FxRef.uid(t, FxRef.SCOUT)
        val opts = FxProcs.options(t, 0, spider).single()
        assertTrue(listOf(scout) in opts.sets)
        // With Bridge itself as material its arrows are gone, and the Extra Monster Zones are free again.
        assertEquals(listOf(emz(0), emz(1)), FxRules.summonZones(t, 0, spider, setOf(bridge)))
        // Xyz, Synchro and Fusion go to any Main Monster Zone, or an Extra Monster Zone the seat may use.
        val regent = FxRef.uid(t, FxRef.REGENT)
        assertEquals(listOf(m(0, 0), m(0, 2)), FxRules.summonZones(t, 0, regent))
        // A Pendulum Monster face-up in the Extra Deck is as a Link.
        val swing = FxRef.uid(t, FxRef.SWING)
        val upInExtra = apply(t, DuelAction.Move(swing, Place.Pile(0, PileKind.EXTRA), CardPosition.FACE_UP_ATK))
        assertEquals(listOf(m(0, 0), m(0, 2)), FxRules.summonZones(upInExtra, 0, swing))
        val noLinks = apply(upInExtra, DuelAction.Move(bridge, Place.Pile(0, PileKind.GY)))
        assertEquals(listOf(emz(0), emz(1)), FxRules.summonZones(noLinks, 0, swing))
        // A monster from the hand or the field: a Main Monster Zone only.
        assertEquals(listOf(m(0, 0), m(0, 2)), FxRules.summonZones(t, 0, swing))
    }

    @Test
    fun aSynchroSummonSumsTheLevelsAsTheyAreNow() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.LAMP, 0), Slot(FxRef.PAWN, 1), Slot(FxRef.TINKER, 2), Slot(FxRef.SPRITE, 3)), extra = listOf(FxRef.PALADIN)))
        val paladin = FxRef.uid(t, FxRef.PALADIN)
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val pawn = FxRef.uid(t, FxRef.PAWN)
        val tinker = FxRef.uid(t, FxRef.TINKER)
        val proc = FxRef.book.script(FxRef.PALADIN)!!.summon!!.procs.single() as Proc.Synchro
        assertEquals(listOf(listOf(lamp, pawn)), FxProcs.synchroSets(t, 0, paladin, proc), "3 + 4; the Sprite is a Tuner, not a non-Tuner")
        val raised = t.copy(fx = t.fx.copy(levels = listOf(LevelChange(tinker, 0, by = 1))))
        assertEquals(setOf(listOf(lamp, pawn), listOf(lamp, tinker)), FxProcs.synchroSets(raised, 0, paladin, proc).toSet(), "Tinker at Level 4 now")
        // Two sets: the chooser picks; a set that is not one of them is refused.
        val p = FxEngine.play(raised, 0, FxMove.Procedure(paladin, 0), FxRef.Answers(cards(lamp, tinker), { listOf(0) }, defense))
        val n = after(raised, p)
        assertEquals(CardPosition.FACE_UP_DEF, n.inst(paladin)!!.pos)
        assertEquals(listOf(lamp, tinker), (p as FxPlay.Done).actions.take(2).map { (it as DuelAction.Move).uid })
        assertTrue(lamp in n.state.seats[0].gy && tinker in n.state.seats[0].gy)
        val wrong = FxEngine.play(raised, 0, FxMove.Procedure(paladin, 0), FxRef.Answers(cards(pawn, tinker)))
        assertTrue((wrong as FxPlay.Refused).why.contains("do not make a Synchro Summon"))
        // No Tuner, no Synchro.
        val noTuner = FxRef.table(Side(field = listOf(Slot(FxRef.PAWN, 0), Slot(FxRef.TINKER, 1)), extra = listOf(FxRef.PALADIN)))
        assertTrue(FxProcs.options(noTuner, 0, FxRef.uid(noTuner, FxRef.PALADIN)).isEmpty())
    }

    @Test
    fun anXyzSummonLaysItsMaterialsBeneathIt() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.SCOUT, 0), Slot(FxRef.PAWN, 1), Slot(FxRef.WARDEN, 2), Slot(FxRef.LAMP, 3)), extra = listOf(FxRef.REGENT)))
        val regent = FxRef.uid(t, FxRef.REGENT)
        val (scout, pawn, warden) = listOf(FxRef.SCOUT, FxRef.PAWN, FxRef.WARDEN).map { FxRef.uid(t, it) }
        val sets = FxProcs.options(t, 0, regent).single().sets
        assertEquals(setOf(listOf(scout, pawn), listOf(scout, warden), listOf(pawn, warden), listOf(scout, pawn, warden)), sets.toSet(), "two or three Level 4s; Lamp is Level 3")
        val p = FxEngine.play(t, 0, FxMove.Procedure(regent, 0), FxRef.Answers(cards(scout, pawn, warden), zone(m(0, 1)), attack))
        val d = p as FxPlay.Done
        assertEquals(DuelAction.Move(regent, m(0, 1), CardPosition.FACE_UP_ATK, "special", over = true), d.actions.first(), "on top of the material there")
        val n = after(t, p)
        assertEquals(regent, n.state.seats[0].monsters[1])
        assertEquals(setOf(scout, pawn, warden), n.inst(regent)!!.under.toSet())
        assertTrue(n.fx.sent.isEmpty(), "Xyz materials are not sent")
        assertFalse(d.events.any { it.event == Event.SENT_TO_GY })
        assertEquals(3, d.events.count { it.event == Event.DETACHED || it.event == Event.MATERIAL })
        // Tokens are no Xyz material.
        val tokens = apply(FxRef.table(Side(extra = listOf(FxRef.REGENT))), DuelAction.Token(0, m(0, 0), code = 0, name = "A", uid = 100_000), DuelAction.Token(0, m(0, 1), code = 0, name = "B", uid = 100_001))
        val withLevels = tokens.copy(fx = tokens.fx.copy(tokens = mapOf(100_000 to FxFacts.token(tokens.inst(100_000)!!).copy(level = 4), 100_001 to FxFacts.token(tokens.inst(100_001)!!).copy(level = 4))))
        assertEquals(4, withLevels.level(100_000), "a token's Level as its op gave it")
        assertTrue(FxProcs.options(withLevels, 0, FxRef.uid(tokens, FxRef.REGENT)).isEmpty())
    }

    @Test
    fun fusionAndRitualMaterialsForTheEffectsThatMakeThem() {
        val t = FxRef.table(Side(hand = listOf(FxRef.SCOUT_ALT, FxRef.LAMP, FxRef.SPRITE, FxRef.ORACLE), field = listOf(Slot(FxRef.TINKER, 0), Slot(FxRef.PAWN, 1), Slot(FxRef.SCOUT, 2)), extra = listOf(FxRef.CHIMERA)))
        val chimera = FxRef.uid(t, FxRef.CHIMERA)
        val (alt, scout) = FxRef.uids(t, FxRef.SCOUT)
        val lamp = FxRef.uid(t, FxRef.LAMP)
        val proc = FxRef.book.script(FxRef.CHIMERA)!!.summon!!.procs.single() as Proc.Fusion
        val from = FxFilters.cards(listOf(Spot(Rel.YOU, Area.HAND), Spot(Rel.YOU, Area.MONSTERS)), FxScope(t, 0))
        val sets = FxProcs.fusionSets(t, 0, chimera, proc, from).map { it.toSet() }.toSet()
        val oracle = FxRef.uid(t, FxRef.ORACLE)
        assertEquals(
            setOf(setOf(alt, lamp), setOf(alt, scout), setOf(alt, oracle), setOf(scout, lamp), setOf(scout, oracle)), sets,
            "\"Example Scout\" by any printing, and a LIGHT monster (another Scout, or the Ritual Monster in the hand)",
        )
        // Ritual: Levels reaching 6 with none to spare, or exactly 6.
        val (tinker, pawn, sprite) = listOf(FxRef.TINKER, FxRef.PAWN, FxRef.SPRITE).map { FxRef.uid(t, it) }
        val pool = listOf(lamp, tinker, pawn, sprite)
        assertEquals(
            setOf(setOf(lamp, tinker), setOf(pawn, sprite), setOf(lamp, pawn), setOf(tinker, pawn)),
            FxProcs.ritualSets(t, 0, oracle, pool, LevelRule.AT_LEAST).map { it.toSet() }.toSet(),
        )
        assertEquals(setOf(setOf(lamp, tinker), setOf(pawn, sprite)), FxProcs.ritualSets(t, 0, oracle, pool, LevelRule.EQUAL).map { it.toSet() }.toSet())
        // Neither is a procedure of its own.
        assertTrue(FxProcs.options(t, 0, chimera).isEmpty())
        assertTrue(FxProcs.options(t, 0, oracle).isEmpty())
    }

    @Test
    fun anInherentSummonWhileItsConditionHoldsOncePerTurn() {
        val t = FxRef.table(Side(hand = listOf(FxRef.LAMP, FxRef.LAMP, FxRef.PAWN)))
        val (one, two) = FxRef.uids(t, FxRef.LAMP)
        assertTrue(FxMove.Procedure(one, 0) in FxEngine.moves(t, 0), "you control no monsters")
        val p = FxEngine.play(t, 0, FxMove.Procedure(one, 0), FxRef.Answers({ listOf(2) }, attack))
        assertEquals(listOf(DuelAction.Move(one, m(0, 2), CardPosition.FACE_UP_ATK, "special")), (p as FxPlay.Done).actions)
        val n = after(t, p)
        assertEquals(ProcKind.INHERENT, n.fx.summoned[one])
        assertEquals(listOf("name:${FxRef.LAMP}:proc"), n.fx.uses.map { it.key })
        // Once by name, and its condition no longer holds anyway.
        val cleared = apply(n, DuelAction.Move(one, Place.Pile(0, PileKind.GY)))
        assertEquals(FxRules.OPT_USED, FxRules.optRefusal(cleared, 0, two, FxTag.PROC, Opt.ByName()))
        assertTrue(FxProcs.options(cleared, 0, two).isEmpty())
        assertTrue(FxProcs.options(n.copy(fx = n.fx.forTurn(3), state = n.state.copy(turn = 3)), 0, two).isEmpty(), "they control a monster now")
        // Refused in words, never silently.
        assertIs<FxPlay.Refused>(FxEngine.play(cleared, 0, FxMove.Procedure(two, 0), Chooser.FIRST))
        // A procedure starts no chain, not on the opponent's turn, and not outside the Main Phase.
        assertIs<FxPlay.Refused>(FxEngine.play(t.copy(state = t.state.copy(active = 1)), 0, FxMove.Procedure(one, 0), Chooser.FIRST))
    }

    @Test
    fun mustFirstBeAndProperlySummoned() {
        val t = FxRef.table(Side(hand = listOf(FxRef.WARDEN, FxRef.WARDEN), field = listOf(Slot(FxRef.TINKER, 0)), gy = listOf(FxRef.WARDEN, FxRef.BRIDGE, FxRef.ORACLE)))
        val wardens = FxRef.uids(t, FxRef.WARDEN)
        val inHand = wardens.filter { (t.state.placeOf(it) as Place.Pile).kind == PileKind.HAND }
        val inGy = wardens.single { it !in inHand }
        // Cannot be Normal Summoned; its own summon from the hand, while it controls an Example card, in Defense Position.
        assertEquals(FxRules.CANNOT_NORMAL, FxRules.normalSummonRefusal(t, 0, inHand[0]))
        val p = FxEngine.play(t, 0, FxMove.Procedure(inHand[0], 0), FxRef.Answers({ listOf(0) }))
        assertEquals(CardPosition.FACE_UP_DEF, (p as FxPlay.Done).actions.single().let { (it as DuelAction.Move).pos })
        val n = after(t, p)
        // Only once per turn.
        assertEquals(FxRules.ONCE_SPECIAL, FxRules.specialRefusal(n, 0, inHand[1], ProcKind.INHERENT))
        // Never properly summoned, it stays in the GY; properly summoned and sent there, it may come back.
        assertEquals("It must first be Special Summoned.", FxRules.specialRefusal(t, 0, inGy, ProcKind.SPECIAL))
        val sent = after(n, FxPlay.Done(listOf(DuelAction.Move(inHand[0], Place.Pile(0, PileKind.GY))), listOf(FxTag(0, "x", "y")), apply(n, DuelAction.Move(inHand[0], Place.Pile(0, PileKind.GY))).state, n.fx.moved(inHand[0], Place.Pile(0, PileKind.GY))))
        assertNull(FxRules.specialRefusal(sent.copy(fx = sent.fx.forTurn(3), state = sent.state.copy(turn = 3)), 0, inHand[0], ProcKind.SPECIAL))
        // Back in the hand it must be summoned properly again.
        assertFalse(inHand[0] in sent.fx.moved(inHand[0], Place.Pile(0, PileKind.HAND)).proper)
        // An Extra Deck or Ritual monster never properly summoned stays in the GY.
        val bridge = FxRef.uid(t, FxRef.BRIDGE)
        assertTrue(FxRules.specialRefusal(t, 0, bridge, ProcKind.SPECIAL)!!.contains("not properly summoned"))
        assertNull(FxRules.specialRefusal(t.copy(fx = t.fx.copy(proper = setOf(bridge))), 0, bridge, ProcKind.SPECIAL))
        assertEquals("It must first be Ritual Summoned.", FxRules.specialRefusal(t, 0, FxRef.uid(t, FxRef.ORACLE), ProcKind.SPECIAL))
    }

    @Test
    fun tagsRideIntoTheLogAndReadBack() {
        val t = FxRef.table(Side(field = listOf(Slot(FxRef.SCOUT, 0), Slot(FxRef.TINKER, 1)), extra = listOf(FxRef.BRIDGE)))
        val bridge = FxRef.uid(t, FxRef.BRIDGE)
        val d = FxEngine.play(t, 0, FxMove.Procedure(bridge, 0), FxRef.Answers(zone(emz(1)))) as FxPlay.Done
        val game = DuelGame(DuelHeader(), emptyList(), 0, t.state, 0)
        val r = game.act(d.actions, 0, fx = d.tags)
        assertTrue(r.ok, r.problem)
        assertEquals(d.tags, r.game.entries.map { it.fx })
        assertEquals(d.state, r.game.state)
        val back = DuelCodec.decode(DuelCodec.encode(r.game.record()))!!
        assertEquals(d.tags, back.entries.map { it.fx })
        // A move by hand has none.
        val hand = game.act(listOf(DuelAction.Move(bridge, emz(0), CardPosition.FACE_UP_ATK, "special")), 0)
        assertEquals(listOf(null), hand.game.entries.map { it.fx })
        assertFalse("\"fx\"" in DuelCodec.encode(hand.game.record()), "no key written for a hand's move")
    }
}
