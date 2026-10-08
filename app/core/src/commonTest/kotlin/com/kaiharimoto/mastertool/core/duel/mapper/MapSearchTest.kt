package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.CardScript
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.Event
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.On
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.Spot
import com.kaiharimoto.mastertool.core.duel.effects.Step
import com.kaiharimoto.mastertool.core.duel.effects.Trigger
import com.kaiharimoto.mastertool.core.duel.effects.Where
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mapper's search (M.md §2.2) on the goldfish's toy cards, whose every end board can be listed by hand. */
class MapSearchTest {
    private val kit = GoldfishFixtures.kit()
    private val main = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }

    private fun map(hand: List<Int>, prior: MovePrior = MovePrior.NONE, trace: Boolean = false): MapSearch.Mapped =
        MapSearch(kit, prior = prior, trace = trace).map(MapDeal(hand).table(main, emptyList(), kit))

    @Test
    fun aCallerAloneReachesEveryPondMonsterOrKeepsItself() {
        val m = map(listOf(CALLER))
        assertTrue(m.complete)
        // Kept in hand; or activated for Pond Frog (a Normal Monster) or Pond Sage. Pond Elder has no script, so it is played
        // as inert and never summoned (D.md §5.5): no board holds it.
        val boards = m.ends.map { it.cards.monsters to it.cards.hand }.toSet()
        assertEquals(
            setOf(
                emptyList<Int>() to listOf(CALLER),
                listOf(FROG) to emptyList(),
                listOf(SAGE) to emptyList(),
            ),
            boards,
        )
        assertTrue(m.ends.none { ELDER in it.cards.monsters })
        val sage = m.ends.single { it.cards.monsters == listOf(SAGE) }
        assertEquals(1, sage.traits.interruptions)
        assertEquals(1, sage.traits.negates)
        assertEquals(1, sage.traits.bodies)
    }

    @Test
    fun aTrapIsSetAtTheTurnsEndAndCountsAsRemoval() {
        val m = map(listOf(WALL))
        assertTrue(m.complete)
        val end = m.ends.single()
        assertEquals(listOf(WALL), end.cards.set)
        assertEquals(1, end.traits.set)
        assertEquals(1, end.traits.removal)
        assertEquals(0, end.traits.hand)
    }

    @Test
    fun keysAreTheSameWhicheverCopyWasUsed() {
        val a = map(listOf(CALLER, CALLER))
        val keys = a.ends.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "every end once")
        // Two Callers: nothing, one monster, or two of them (any pair of Frog, Elder, Sage, a copy each or twins).
        assertTrue(a.ends.any { it.cards.monsters == listOf(FROG, SAGE).sorted() })
    }

    @Test
    fun aPriorChangesNoEndOfACompleteMap() {
        val hand = listOf(CALLER, CALLER, WALL)
        val plain = map(hand)
        repeat(5) { seed ->
            val rng = Random(seed)
            val shuffled = map(hand, MovePrior { _, _, moves -> moves.shuffled(rng) })
            assertTrue(plain.complete && shuffled.complete)
            assertEquals(plain.ends.map { it.key }, shuffled.ends.map { it.key })
            assertEquals(plain.ends.map { it.line.size }, shuffled.ends.map { it.line.size })
            assertEquals(plain.ends.map { it.traits }, shuffled.ends.map { it.traits })
        }
    }

    @Test
    fun aBudgetTooSmallIsIncompleteNeverAnAnswer() {
        val m = MapSearch(kit, budget = 2).map(MapDeal(listOf(CALLER, CALLER, WALL)).table(main, emptyList(), kit))
        assertTrue(!m.complete)
    }

    @Test
    fun aLineLongerThanTheDepthMakesTheMapIncomplete() {
        val m = MapSearch(kit, depth = 1).map(MapDeal(listOf(CALLER)).table(main, emptyList(), kit))
        assertTrue(!m.complete, "a line cut by the depth is not a line that does not exist")
    }

    @Test
    fun anEndReachedTwiceIsBelowBothItsMoves() {
        // Two Callers: Frog then Sage, or Sage then Frog, end on one board; both first moves must list it.
        val m = map(listOf(CALLER, CALLER), trace = true)
        val both = m.ends.indexOfFirst { it.cards.monsters == listOf(FROG, SAGE).sorted() }
        assertTrue(both >= 0)
        val above = m.nodes.filter { n -> n.reach.any { both in it } }
        assertTrue(above.isNotEmpty())
        assertTrue(m.nodes.first().reach.any { both in it })
    }

    @Test
    fun aTraceListsTheEndsBelowEveryMove() {
        val m = map(listOf(CALLER), trace = true)
        // The tables come in the order the search met them: the deal's own first.
        val root = m.nodes.first()
        assertEquals(0, root.step)
        assertEquals(m.nodes.map { it.step }.sorted().first(), root.step)
        // Below every move is at least one end, and together they are every end.
        assertTrue(root.reach.all { it.isNotEmpty() })
        assertEquals(m.ends.indices.toSet(), root.reach.flatMap { it.asIterable() }.toSet())
    }

    @Test
    fun aMapCutAndSearchedAgainFromNearerTheStartIsComplete() {
        // The prior is asked to order exactly the tables the search expands. A map held to a depth that still expanded every
        // table the unbounded one did, and found every end, found everything: it may not say "incomplete" because a table was
        // once cut on a longer way to it.
        val deck = main.dropLast(1) + NET
        fun expanded(hand: List<Int>, depth: Int): Pair<MapSearch.Mapped, Set<TableKey.Key>> {
            val keys = HashSet<TableKey.Key>()
            val prior = MovePrior { t, _, ms -> keys += TableKey(t, false).key(); ms }
            return MapSearch(kit, depth = depth, prior = prior).map(MapDeal(hand).table(deck, emptyList(), kit)) to keys
        }
        for (hand in listOf(listOf(CALLER, NET), listOf(CALLER, CALLER, NET))) {
            val (full, all) = expanded(hand, MapSearch.DEFAULT_DEPTH)
            assertTrue(full.complete)
            for (d in 2..10) {
                val (m, keys) = expanded(hand, d)
                if (keys.containsAll(all) && m.ends.map { it.key } == full.ends.map { it.key }) assertTrue(m.complete, "$hand at depth $d")
            }
        }
    }

    @Test
    fun theCardsSetBeforeTheEndPhaseNeverDependOnTheHandsOrder() {
        // Six Traps and five zones: four Walls with the Snare, with the Denial, or three Walls with both — whatever order dealt.
        val k = GoldfishFixtures.kit(GoldfishFixtures.trust(GoldfishFixtures.scripts + FxRef.script(FxRef.SNARE) + FxRef.script(FxRef.DENIAL)))
        val deck = GoldfishFixtures.deck(WALL to 4, FxRef.SNARE to 1, FxRef.DENIAL to 1) + List(34) { ELDER }
        fun ends(hand: List<Int>) = MapSearch(k).map(MapDeal(hand).table(deck, emptyList(), k)).also { assertTrue(it.complete) }.ends
        val a = ends(listOf(WALL, WALL, WALL, WALL, FxRef.SNARE, FxRef.DENIAL))
        val b = ends(listOf(FxRef.SNARE, FxRef.DENIAL, WALL, WALL, WALL, WALL))
        assertEquals(a.map { it.key }, b.map { it.key })
        assertEquals(3, a.size)
        assertTrue(a.all { it.cards.set.size == 5 && it.cards.hand.size == 1 })
    }

    @Test
    fun anEndPhaseTriggerSeesTheCardsSetBeforeIt() {
        // Dry Stone, scripted here: "During the End Phase: discard 1 card." With the Wall Set first, as a player does and as a
        // replay does, the discard can only take the Elder or the Frog — and every kept line replays to its board.
        val stone = CardScript(
            STONE, name = "Dry Stone",
            effects = listOf(
                Effect(
                    "e1", "Weather", Kind.TRIGGER, from = setOf(Where.MONSTER_ZONE),
                    trigger = Trigger(On(Event.END_PHASE), optional = false),
                    does = listOf(Step(Op.Discard(Pick(from = listOf(Spot(Rel.YOU, Area.HAND)))))),
                ),
            ),
        )
        val k = GoldfishFixtures.kit(GoldfishFixtures.trust(GoldfishFixtures.scripts + stone))
        val deck = GoldfishFixtures.deck(STONE to 3, FROG to 3, WALL to 3) + List(31) { ELDER }
        val deal = MapDeal(listOf(STONE, WALL, ELDER, FROG))
        val m = MapSearch(k).map(deal.table(deck, emptyList(), k))
        assertTrue(m.complete)
        assertTrue(m.ends.none { WALL in it.cards.gy }, "the Wall was Set before the End Phase, out of the discard's reach")
        val lib = BoardLibrary().add(deal, m, run = 1)
        lib.boards.forEach { e ->
            e.lines.forEach { line ->
                val r = MapReplay.of(line, deck, emptyList(), k)
                assertNull(r.problem, "replaying to ${e.cards}")
                assertEquals(e.key, r.key, "the replay of a line to ${e.cards}")
            }
        }
    }

    @Test
    fun aDecklistInAnotherOrderDealsTheSameDuel() {
        // A deck's fingerprint counts cards, so the builder reordering it keeps the library: its lines must play the same.
        for (seed in 1L..3L) {
            val deal = MapDeal(listOf(CALLER), seed = seed)
            val lib = BoardLibrary().add(deal, MapSearch(kit).map(deal.table(main, emptyList(), kit)), run = 1)
            val back = MapSearch(kit).map(deal.table(main.reversed(), emptyList(), kit))
            assertEquals(lib.boards.map { it.key }, back.ends.map { it.key })
            lib.boards.forEach { e -> e.lines.forEach { assertEquals(e.key, MapReplay.of(it, main.shuffled(Random(seed)), emptyList(), kit).key) } }
        }
    }

    @Test
    fun theFodderIsLeftOutOfEveryBoard() {
        val deal = MapDeal(listOf(CALLER), fodder = listOf(STONE, STONE, STONE, STONE))
        val t = deal.table(main, emptyList(), kit)
        val fodder = deal.fodderUids(t)
        assertEquals(4, fodder.size)
        val m = MapSearch(kit).map(t, fodder)
        // The same boards as the Caller alone: four Stones in hand are no part of any of them.
        assertEquals(map(listOf(CALLER)).ends.map { it.key }, m.ends.map { it.key })
        assertTrue(m.ends.none { STONE in it.cards.hand })
    }
}
