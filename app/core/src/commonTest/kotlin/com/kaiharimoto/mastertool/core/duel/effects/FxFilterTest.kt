package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Side
import com.kaiharimoto.mastertool.core.duel.effects.FxRef.Slot
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/** The filter and condition evaluators (D.md §2.2), judged against cards on a real table. */
class FxFilterTest {
    private fun apply(t: FxTable, vararg a: DuelAction): FxTable {
        val (s, problem) = DuelRules.applyAll(t.state, a.toList())
        return t.copy(state = s ?: error(problem!!))
    }

    private val table = FxRef.table(
        you = Side(
            hand = listOf(FxRef.SCOUT_ALT, FxRef.LAMP, FxRef.SPRITE),
            field = listOf(Slot(FxRef.PAWN, 0, CardPosition.FACE_DOWN_DEF), Slot(FxRef.TINKER, 1), Slot(FxRef.SNARE, 2, CardPosition.FACE_DOWN_DEF, ZoneKind.SPELL), Slot(FxRef.REGENT, 2)),
            gy = listOf(FxRef.COLOSSUS),
            banished = listOf(FxRef.KNIGHT, FxRef.WARDEN),
            deck = listOf(FxRef.SCOUT, FxRef.PAWN, FxRef.LAMP),
            extra = listOf(FxRef.ARCH),
        ),
        them = Side(field = listOf(Slot(FxRef.SCOUT, 0), Slot(FxRef.LAMP, 1), Slot(FxRef.PAWN, 2, CardPosition.FACE_UP_DEF), Slot(FxRef.BRIDGE, 0, kind = ZoneKind.EMZ))),
    )

    private fun scope(t: FxTable = table, self: Int? = null, bound: Map<String, List<Int>> = emptyMap(), declared: Map<String, Declared> = emptyMap()) =
        FxScope(t, 0, self, bound, declared)

    private fun m(f: Filter, uid: Int, s: FxScope = scope()) = FxFilters.matches(f, uid, s)

    @Test
    fun namesByIdentityAndArchetypesByWord() {
        val alt = FxRef.uid(table, FxRef.SCOUT_ALT)
        assertEquals(FxRef.SCOUT_ALT, table.inst(alt)!!.code)
        assertTrue(m(Filter.Name(FxRef.SCOUT), alt), "an alternate artwork is the same card")
        assertTrue(m(Filter.Name(FxRef.SCOUT_ALT), alt))
        assertTrue(m(Filter.NameHas("example"), alt), "ignoring case")
        assertTrue(m(Filter.NameHas("Example Scout"), alt))
        assertFalse(m(Filter.NameHas("Examp"), alt), "whole words only")
        val sprite = FxRef.uid(table, FxRef.SPRITE)
        assertFalse(m(Filter.NameHas("Example"), sprite))
        // "Always treated as an Example card".
        val treated = table.copy(book = ScriptBook.all(FxRef.scripts + CardScript(FxRef.SPRITE, name = "Plain Sprite", alsoNamed = listOf("Example")), FxRef.facts::canonical))
        assertTrue(FxFilters.matches(Filter.NameHas("Example"), sprite, scope(treated)))
    }

    @Test
    fun aFaceDownCardShowsOnlyWhereItIsAndWhose() {
        val pawn = FxRef.uids(table, FxRef.PAWN).first { table.state.placeOf(it) is Place.Zone }
        assertTrue(m(Filter.FaceDown, pawn))
        assertTrue(m(Filter.Kind(CardType.MONSTER), pawn))
        assertTrue(m(Filter.Controller(Rel.YOU), pawn))
        listOf(Filter.Name(FxRef.PAWN), Filter.NameHas("Example"), Filter.Level(Span(1, 12)), Filter.Atk(Span(0)), Filter.Frame(CardFrame.NORMAL),
            Filter.Attribute(setOf(CardAttribute.EARTH)), Filter.Race(setOf("Warrior")), Filter.Kind(CardType.SPELL)).forEach {
            assertFalse(m(it, pawn), "$it of a face-down monster")
        }
        val snare = FxRef.uid(table, FxRef.SNARE)
        assertTrue(m(Filter.Kind(CardType.TRAP), snare) && m(Filter.Kind(CardType.SPELL), snare), "a set card is a Spell or a Trap to the eye")
        assertFalse(m(Filter.Kind(CardType.TRAP, "Normal"), snare), "its kind as printed is hidden")
        assertFalse(m(Filter.Kind(CardType.MONSTER), snare))
        // In the Deck or the hand a card is read: its owner searches it.
        val inDeck = FxRef.uids(table, FxRef.PAWN).first { (table.state.placeOf(it) as? Place.Pile)?.kind == PileKind.DECK }
        assertTrue(m(Filter.Frame(CardFrame.NORMAL), inDeck))
    }

    @Test
    fun statsKindsAndControllers() {
        val tinker = FxRef.uid(table, FxRef.TINKER)
        assertTrue(m(Filter.Level(Span(3, 3)), tinker))
        assertTrue(m(Filter.Attribute(setOf(CardAttribute.FIRE, CardAttribute.DARK)), tinker))
        assertTrue(m(Filter.Race(setOf("machine")), tinker))
        assertTrue(m(Filter.Atk(Span(1200, 1200)), tinker) && m(Filter.Def(Span(max = 800)), tinker))
        assertTrue(m(Filter.Frame(CardFrame.EFFECT), tinker))
        val regent = FxRef.uid(table, FxRef.REGENT)
        assertTrue(m(Filter.Rank(Span(4, 4)), regent))
        assertFalse(m(Filter.Level(Span(1, 12)), regent), "an Xyz Monster has no Level")
        val bridge = FxRef.uid(table, FxRef.BRIDGE, seat = 1)
        assertTrue(m(Filter.LinkRating(Span(2, 2)), bridge))
        assertTrue(m(Filter.Controller(Rel.THEM), bridge), "an Extra Monster Zone card is its controller's")
        assertFalse(m(Filter.Controller(Rel.YOU), bridge))
        assertFalse(m(Filter.Kind(CardType.SPELL, "Quick-Play"), tinker))
        // A Level as it is now.
        val raised = table.copy(fx = table.fx.copy(levels = listOf(LevelChange(tinker, table.fx.life(tinker), by = 2))))
        assertEquals(5, raised.level(tinker))
        assertTrue(FxFilters.matches(Filter.Level(Span(5, 5)), tinker, scope(raised)))
        // A new instance forgets it.
        val gone = raised.copy(fx = raised.fx.moved(tinker, Place.Pile(0, PileKind.GY)))
        assertEquals(3, gone.level(tinker))
        val set = raised.copy(fx = raised.fx.copy(levels = raised.fx.levels + LevelChange(tinker, 0, to = 8)))
        assertEquals(8, set.level(tinker))
    }

    @Test
    fun lowestHighestAndTheSame() {
        val theirs = FxFilters.area(Area.MONSTERS, 1, table.state)
        assertEquals(4, theirs.size, "their Main Monster Zones and their Extra Monster Zone")
        val faceUpLowest = FxFilters.among(Filter.All(listOf(Filter.Kind(CardType.MONSTER), Filter.Lowest(Stat.ATK))), theirs, scope())
        assertEquals(listOf(FxRef.uid(table, FxRef.LAMP, 1)), faceUpLowest, "Lamp's 1000 is the lowest")
        val highest = FxFilters.among(Filter.Highest(Stat.ATK), theirs, scope())
        assertEquals(listOf(FxRef.uid(table, FxRef.SCOUT, 1)), highest, "Scout's 1600 is the highest")
        assertEquals(listOf(FxRef.uid(table, FxRef.LAMP, 1)), FxFilters.among(Filter.Lowest(Stat.LEVEL), theirs, scope()), "a Link has no Level to count")
        // Ties all match: the chooser picks.
        val pawn = FxRef.uid(table, FxRef.PAWN, 1)
        val tie = table.copy(fx = table.fx.copy(levels = listOf(LevelChange(pawn, 0, to = 3))))
        assertEquals(2, FxFilters.among(Filter.Lowest(Stat.LEVEL), theirs, scope(tie)).size)
        // "With the same name as" the bound card.
        val scout = FxRef.uid(table, FxRef.SCOUT, 1)
        val mine = FxRef.uid(table, FxRef.SCOUT_ALT)
        assertTrue(m(Filter.Same(Stat.NAME, "x"), mine, scope(bound = mapOf("x" to listOf(scout)))))
        assertTrue(m(Filter.Same(Stat.ATTRIBUTE, "x"), FxRef.uid(table, FxRef.LAMP), scope(bound = mapOf("x" to listOf(scout)))))
        assertFalse(m(Filter.Same(Stat.LEVEL, "x"), FxRef.uid(table, FxRef.LAMP), scope(bound = mapOf("x" to listOf(scout)))))
        assertFalse(m(Filter.Same(Stat.NAME, "x"), scout, scope(bound = mapOf("x" to listOf(scout)))), "never itself")
    }

    @Test
    fun declarationsAndLogic() {
        val tinker = FxRef.uid(table, FxRef.TINKER)
        assertTrue(m(Filter.Declared("d"), tinker, scope(declared = mapOf("d" to Declared(DeclareKind.LEVEL, 3)))))
        assertTrue(m(Filter.Declared("d"), tinker, scope(declared = mapOf("d" to Declared(DeclareKind.TYPE, word = "Machine")))))
        assertTrue(m(Filter.Declared("d"), tinker, scope(declared = mapOf("d" to Declared(DeclareKind.ATTRIBUTE, word = "FIRE")))))
        assertTrue(m(Filter.Declared("d"), FxRef.uid(table, FxRef.SCOUT_ALT), scope(declared = mapOf("d" to Declared(DeclareKind.NAME, FxRef.SCOUT)))))
        assertFalse(m(Filter.Declared("d"), tinker, scope(declared = mapOf("d" to Declared(DeclareKind.NAME, FxRef.SCOUT)))))
        assertFalse(m(Filter.Declared("none"), tinker))
        assertTrue(m(Filter.All(listOf(Filter.FaceUp, Filter.NameHas("Example"))), tinker))
        assertTrue(m(Filter.AnyOf(listOf(Filter.FaceDown, Filter.Self)), tinker, scope(self = tinker)))
        assertTrue(m(Filter.NotSelf, tinker) && !m(Filter.NotSelf, tinker, scope(self = tinker)))
        assertFalse(m(Filter.Unknown(JsonObject(emptyMap())), tinker))
        assertFalse(m(Filter.Not(Filter.Unknown()), tinker), "an unread word never matches, negated or not")
    }

    @Test
    fun picksReachEveryPlaceAndSeeFaceUpBanishmentOnly() {
        val s = scope()
        // Both seats' GY and banishment, the field, the hand.
        val gyBoth = FxFilters.candidates(Pick(from = listOf(Spot(Rel.ANY, Area.GY), Spot(Rel.ANY, Area.BANISHED))), s)
        assertEquals(setOf(FxRef.COLOSSUS, FxRef.KNIGHT, FxRef.WARDEN), gyBoth.map { table.inst(it)!!.code }.toSet())
        val warden = FxRef.uid(table, FxRef.WARDEN)
        val down = apply(table, DuelAction.Move(warden, Place.Pile(0, PileKind.BANISHED), CardPosition.FACE_DOWN_ATK))
        val seen = FxFilters.candidates(Pick(from = listOf(Spot(Rel.ANY, Area.BANISHED))), scope(down))
        assertFalse(warden in seen, "a face-down banished card is no candidate")
        assertTrue(warden in FxFilters.candidates(Pick(from = listOf(Spot(Rel.ANY, Area.BANISHED)), faceDown = true), scope(down)))
        // Xyz materials are a place.
        val regent = FxRef.uid(table, FxRef.REGENT)
        val hand = FxRef.uid(table, FxRef.LAMP)
        val stacked = apply(table, DuelAction.Move(hand, Place.Under(regent)))
        assertEquals(listOf(hand), FxFilters.candidates(Pick(from = listOf(Spot(Rel.YOU, Area.MATERIALS))), scope(stacked)))
        assertEquals(emptyList(), FxFilters.candidates(Pick(from = listOf(Spot(Rel.THEM, Area.MATERIALS))), scope(stacked)))
        // The top of the Deck, a reference, an "all".
        assertEquals(listOf(FxRef.SCOUT, FxRef.PAWN), FxFilters.candidates(Pick(n = 2, top = true, from = listOf(Spot(Rel.YOU, Area.DECK))), s).map { table.inst(it)!!.code })
        val tinker = FxRef.uid(table, FxRef.TINKER)
        assertEquals(listOf(tinker), FxFilters.candidates(Pick(ref = "t", where = Filter.FaceUp), scope(bound = mapOf("t" to listOf(tinker, 999_999)))))
        val all = Pick(all = true, from = listOf(Spot(Rel.THEM, Area.MONSTERS)))
        assertEquals(4, FxFilters.candidates(all, s).size)
        assertEquals(4..4, FxFilters.bounds(all, 4))
        assertEquals(0..2, FxFilters.bounds(Pick(n = 2, upTo = true), 5))
        assertEquals(1..1, FxFilters.bounds(Pick(), 5))
        assertEquals(setOf(FxRef.SCOUT, FxRef.LAMP, FxRef.PAWN, FxRef.BRIDGE), FxFilters.cards(listOf(Spot(Rel.THEM, Area.FIELD)), s).map { table.inst(it)!!.code }.toSet())
    }

    @Test
    fun conditionsReadTheTable() {
        val s = scope()
        fun h(c: Cond, sc: FxScope = s) = FxConds.holds(c, sc)
        assertTrue(h(Cond.Controls(Filter.NameHas("Example"))))
        assertTrue(h(Cond.Controls(Filter.NameHas("Example"), Num.Const(2))), "Tinker and Regent face-up")
        assertFalse(h(Cond.Controls(Filter.NameHas("Example"), Num.Const(3))), "the face-down Pawn hides its name")
        assertTrue(h(Cond.Controls(Filter.Kind(CardType.MONSTER), Num.Const(4), Rel.THEM)))
        assertFalse(h(Cond.NoMonsters(Rel.YOU)))
        assertTrue(h(Cond.Count(listOf(Spot(Rel.YOU, Area.HAND)), cmp = Cmp.EQ, n = Num.Const(3))))
        assertTrue(h(Cond.Count(listOf(Spot(Rel.YOU, Area.GY)), cmp = Cmp.LT, n = Num.Const(2))))
        assertTrue(h(Cond.Phase(setOf(DuelPhase.MAIN1))))
        assertFalse(h(Cond.Phase(setOf(DuelPhase.BATTLE))))
        assertTrue(h(Cond.Turn(Rel.YOU)) && !h(Cond.Turn(Rel.THEM)))
        assertTrue(h(Cond.Turn(Rel.YOU), s) && !h(Cond.Turn(Rel.YOU), FxScope(table, 1)))
        assertTrue(h(Cond.ChainEmpty))
        assertTrue(h(Cond.Lp(Rel.THEM, Cmp.EQ, Num.Const(8000))))
        val tinker = FxRef.uid(table, FxRef.TINKER)
        assertTrue(h(Cond.Compare(Num.Of(Stat.LEVEL, "t"), Cmp.EQ, Num.Const(3)), scope(bound = mapOf("t" to listOf(tinker)))))
        assertFalse(h(Cond.Compare(Num.Of(Stat.RANK, "t"), Cmp.GE, Num.Const(0)), scope(bound = mapOf("t" to listOf(tinker)))), "no Rank: no number")
        assertTrue(h(Cond.All(listOf(Cond.ChainEmpty, Cond.Turn(Rel.YOU)))))
        assertTrue(h(Cond.AnyOf(listOf(Cond.NoMonsters(), Cond.ChainEmpty))))
        assertTrue(h(Cond.Not(Cond.NoMonsters())))
        assertFalse(h(Cond.Unknown()))
        assertFalse(h(Cond.Not(Cond.Unknown())), "an unread word never holds, negated or not")
        assertFalse(h(Cond.Not(Cond.Controls(Filter.Unknown()))))
        // This card summoned or sent this turn.
        val summoned = table.copy(fx = table.fx.copy(summoned = mapOf(tinker to ProcKind.NORMAL), sent = setOf(tinker)))
        val me = FxScope(summoned, 0, tinker)
        assertTrue(h(Cond.ThisTurn(Event.SUMMONED), me) && h(Cond.ThisTurn(Event.NORMAL_SUMMONED), me) && h(Cond.ThisTurn(Event.SENT_TO_GY), me))
        assertFalse(h(Cond.ThisTurn(Event.SPECIAL_SUMMONED), me))
        // The chain's newest link: whose, which card, and what it includes (from the engine's link).
        val scout = FxRef.uid(table, FxRef.SCOUT, 1)
        val chained = apply(table, DuelAction.ChainAdd(1, scout))
        assertFalse(h(Cond.ChainEmpty, scope(chained)))
        assertTrue(h(Cond.Newest(Rel.THEM, Filter.NameHas("Example")), scope(chained)))
        assertFalse(h(Cond.Newest(Rel.YOU), scope(chained)))
        assertFalse(h(Cond.Newest(includes = listOf(Includes.SEARCH)), scope(chained)), "a link made by hand: what it includes is not known")
        val known = chained.copy(fx = chained.fx.copy(links = listOf(FxLink(1, 1, scout, FxRef.SCOUT, "e1", 1))))
        assertTrue(h(Cond.Newest(includes = listOf(Includes.SEARCH)), scope(known)))
        assertFalse(h(Cond.Newest(includes = listOf(Includes.DESTROY)), scope(known)))
    }

    @Test
    fun whatAnEffectIncludesIsReadOffItsSteps() {
        val e = FxRef.book.effect(FxRef.SCOUT, "e1")!!
        assertEquals(setOf(Includes.SEARCH), FxWalk.includes(e))
        assertEquals(setOf(Includes.RETURN), FxWalk.includes(FxRef.book.effect(FxRef.SCOUT, "e2")!!), "its cost is not what it includes")
        assertEquals(setOf(Includes.SEND_FROM_DECK), FxWalk.includes(FxRef.book.effect(FxRef.LAMP, "e1")!!))
        assertEquals(setOf(Includes.SPECIAL_SUMMON), FxWalk.includes(FxRef.book.effect(FxRef.FUSION, "e1")!!))
        assertEquals(setOf(Includes.NEGATE, Includes.DESTROY), FxWalk.includes(FxRef.book.effect(FxRef.DENIAL, "e1")!!))
        assertEquals(setOf(Includes.DRAW), FxWalk.includes(FxRef.book.effect(FxRef.CROSSROADS, "e1")!!), "nested steps too")
        assertEquals(2, FxWalk.depth(FxRef.book.effect(FxRef.CROSSROADS, "e1")!!.does))
        assertEquals(1, FxWalk.depth(e.does))
    }
}
