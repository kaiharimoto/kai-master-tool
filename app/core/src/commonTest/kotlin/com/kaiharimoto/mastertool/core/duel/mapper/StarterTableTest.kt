package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.choose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The starter table (M.md §2.5) on the toy deck, every row worked out by hand. */
class StarterTableTest {
    private val kit = GoldfishFixtures.kit()
    private val main = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE } +
        listOf(GoldfishFixtures.NET)

    @Test
    fun theEngineIsTheCardsWithATrustedScript() {
        // Frog is a Normal Monster, Elder and Stone have no script: none of them starts anything.
        assertEquals(listOf(CALLER, GoldfishFixtures.NET, WALL, SAGE).sorted(), StarterTable.engine(main, kit))
    }

    @Test
    fun pairsTakeACardTwiceOnlyWhenTheDeckHoldsTwo() {
        val s = StarterTable.starters(main, kit)
        assertTrue(listOf(CALLER, CALLER) in s)
        assertTrue(listOf(GoldfishFixtures.NET, GoldfishFixtures.NET) !in s, "one Pond Net in the deck")
        // 4 alone, then the 6 pairs of different cards and the 3 doubles.
        assertEquals(4 + 6 + 3, s.size)
        assertEquals(s.take(4), StarterTable.engine(main, kit).map { listOf(it) })
    }

    @Test
    fun theOddsAreTheHypergeometricOfTheDeck() {
        val n = main.size
        val one = 1 - choose(n - 3, 5) / choose(n, 5)
        assertEquals(one, StarterTable.odds(listOf(CALLER), main, 5, kit), 1e-12)
        // Two Callers: at least two of the three.
        val two = (choose(3, 2) * choose(n - 3, 3) + choose(3, 3) * choose(n - 3, 2)) / choose(n, 5)
        assertEquals(two, StarterTable.odds(listOf(CALLER, CALLER), main, 5, kit), 1e-12)
    }

    @Test
    fun aPairFindsTheBoardsNeitherCardReachesAlone() {
        val r = StarterTable.run(main, emptyList(), kit, BoardLibrary())
        assertEquals(StarterTable.starters(main, kit), r.rows.map { it.cards })
        assertTrue(r.rows.all { it.complete })
        val callerSage = r.rows.single { it.cards == listOf(CALLER, SAGE) }
        // A Frog called and the Sage summoned: two bodies and a negate, out of reach of either card alone.
        assertTrue(callerSage.together.isNotEmpty())
        val alone = r.rows.filter { it.cards.size == 1 }.flatMap { it.ends }.toSet()
        assertTrue(callerSage.together.none { it in alone })
        val both = callerSage.together.map { r.library.byKey.getValue(it) }
        assertTrue(both.any { it.cards.monsters == listOf(FROG, SAGE).sorted() && it.traits.negates == 1 })
        // Every board the table found is in the library, and every library board came from a row.
        assertEquals(r.rows.flatMap { it.ends }.toSet(), r.library.boards.map { it.key }.toSet())
    }

    @Test
    fun theTableIsTheSameEveryTime() {
        val a = StarterTable.run(main, emptyList(), kit, BoardLibrary())
        val b = StarterTable.run(main, emptyList(), kit, BoardLibrary())
        assertEquals(a.library, b.library)
        assertEquals(a.rows, b.rows)
    }
}
