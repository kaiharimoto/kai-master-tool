package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the page writes and Ai is told (M.md §6): the same sentences from the same library. */
class MapperReportTest {
    private val kit = GoldfishFixtures.kit()
    private val main: List<Int> = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }
    private val toy: GoldfishDeck = GoldfishDeck(main, id = "toy", fingerprint = "fp")
    private val names = mapOf(CALLER to "Caller", FROG to "Frog", ELDER to "Elder", SAGE to "Sage", STONE to "Stone", WALL to "Wall")
    private val name: (Int) -> String = { names[it] ?: "#$it" }

    @Test
    fun aBoardsTraitsReadAsASentence() {
        assertEquals("3 interruptions (2 negates, 1 removal), 4 bodies, 2 set, 1 kept in hand (1 hand trap)",
            MapperWords.traits(BoardTraits(interruptions = 3, negates = 2, removal = 1, bodies = 4, set = 2, hand = 1, handInterruptions = 1)))
        assertEquals("0 interruptions, 1 body, 0 kept in hand", MapperWords.traits(BoardTraits(bodies = 1)))
        assertEquals("Field: Frog (under it: Caller), Token ×2 · Hand: Sage",
            MapperWords.cards(BoardCards(monsters = listOf(FROG), under = listOf("$FROG:$CALLER"), tokens = listOf("Token", "Token"), hand = listOf(SAGE)), name))
        assertEquals("no run has counted it", MapperWords.share(Share(0, 0)))
        assertTrue(MapperWords.share(Share(41, 100)).startsWith("41.0 % of 100 hands (95 %: "))
    }

    @Test
    fun theLibraryReadsAsThePageRanksIt() {
        val (run, lib) = Mapper.runHere(MapperSetup(toy, true, 40, seed = 5), kit, BoardLibrary(deck = "fp"))
        val text = MapperReport.library(lib, run, BoardPreset(weights = mapOf("negates" to 1.0)), 3, name)
        assertTrue(text.startsWith("The library going first: "), text)
        assertTrue("weights negates ×1" in text, text)
        assertTrue("At least this much: " in text, text)
        assertTrue("\n1. [" in text && "Cheapest line: From " in text, text)
        // The best-ranked board under "negates" is one with the Sage on it.
        val first = BoardQuery.rank(lib.boards, BoardPreset(weights = mapOf("negates" to 1.0))).first().entry
        assertTrue(SAGE in first.cards.monsters)
        assertTrue(MapperReport.board(first, run, name).contains("Sage"))
        // A run of another deck gives no shares.
        val other = MapperReport.library(lib, run.copy(deck = "other"), BoardPreset.DEFAULT, 3, name)
        assertFalse("At least this much" in other)
        assertTrue(MapperReport.library(BoardLibrary(), null, BoardPreset.DEFAULT, 3, name).contains("empty"))
        assertTrue(MapperReport.run(run).startsWith("Dealt 40 hands going first from seed 5: "))
    }

    @Test
    fun theStarterTableReadsBestFirst() {
        val r = StarterTable.run(main, emptyList(), kit, BoardLibrary(deck = "fp"))
        val t = StarterRun(deck = "fp", rows = r.rows)
        val text = MapperReport.starters(t, r.library, null, name)
        assertTrue(text.startsWith("The starter table going first: "), text)
        assertTrue("- Caller + Sage: " in text && "neither card makes alone" in text, text)
        assertTrue(MapperReport.starters(t, r.library, SAGE, name).lines().drop(1).all { "Sage" in it || it.startsWith("…") }, text)
    }
}
