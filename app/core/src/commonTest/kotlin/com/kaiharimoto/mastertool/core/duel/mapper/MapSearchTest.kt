package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertTrue(m.nodes.first().reach.flatten().contains(both))
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
        assertEquals(m.ends.indices.toSet(), root.reach.flatten().toSet())
    }
}
