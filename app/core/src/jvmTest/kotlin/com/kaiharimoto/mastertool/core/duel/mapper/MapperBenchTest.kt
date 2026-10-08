package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The mapper's speed (M.md §8, step M1's `MapperBenchTest`) on the goldfish bench's fixture deck: the step-1 reference cards —
 * searchers, triggers, a Ritual, a Fusion, Synchro, Xyz and Link Monsters, a Trap to Set — and seven blanks. The starter
 * table's one-card rows and a run over dealt hands are timed on one worker and on every worker; maps, engine moves and
 * boards a second are printed for the release notes (the test's standard output in its report).
 *
 * **The floor is not the target**: it fails only far below what any machine CI runs on makes, so a busy runner never fails
 * it and a search gone badly wrong does.
 */
class MapperBenchTest {
    private val engine = FxRef.scripts.map { it.card }.toSet() + listOf(FxRef.ECHO, FxRef.MANDATE, FxRef.EMBER, FxRef.BEACON, FxRef.OFFERING)
    private val main = listOf(
        FxRef.SCOUT to 3, FxRef.LAMP to 2, FxRef.CALL to 2, FxRef.ECHO to 3, FxRef.MANDATE to 2, FxRef.EMBER to 2, FxRef.PAWN to 3,
        FxRef.SPRITE to 2, FxRef.BEACON to 2, FxRef.TINKER to 2, FxRef.WARDEN to 2, FxRef.OFFERING to 2, FxRef.RALLY to 2,
        FxRef.SEEDS to 1, FxRef.SNARE to 2, FxRef.FLASH to 1, STONE to 7,
    ).flatMap { (c, n) -> List(n) { c } }
    private val extra = listOf(FxRef.BRIDGE, FxRef.ARCH, FxRef.SPIDER, FxRef.PALADIN, FxRef.REGENT, FxRef.LORD, FxRef.CHIMERA)
    private val kit: GoldfishKit = GoldfishFixtures.kit(
        GoldfishFixtures.trust(FxRef.book.cards.mapNotNull { FxRef.book.script(it) }.filter { it.card in engine || it.card in extra }),
    )

    @Test
    fun mapsASecondOnTheFixtureDeck() {
        check(main.size == 40)
        // A warm-up, so the JIT's first pass is not counted.
        StarterTable.run(main, extra, kit, BoardLibrary(), pairs = false, budget = BUDGET)
        val mark = TimeSource.Monotonic.markNow()
        val table = StarterTable.run(main, extra, kit, BoardLibrary(), pairs = false, budget = BUDGET)
        val ms = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        val moves = table.rows.sumOf { it.moves.toLong() }
        println(
            "MapperBenchTest: ${table.rows.size} one-card starters on one worker in $ms ms (budget $BUDGET a map): " +
                "${moves * 1000 / ms} engine moves a second, ${table.library.boards.size} boards, " +
                "${table.rows.count { it.complete }} of ${table.rows.size} maps complete",
        )
        val setup = MapperSetup(GoldfishDeck(main, extra, fingerprint = "bench"), hands = HANDS, seed = 7, budget = BUDGET)
        val runMark = TimeSource.Monotonic.markNow()
        val (one, lib) = Mapper.runHere(setup, kit, BoardLibrary(deck = "bench"))
        val runMs = runMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        println(
            "MapperBenchTest: ${one.hands} dealt hands, ${one.parts.size} maps, on one worker in $runMs ms: " +
                "${one.hands * 1000.0 / runMs} hands a second, ${one.moves * 1000 / runMs} engine moves a second; " +
                "${lib.boards.size} boards, ${one.incomplete} hands incomplete",
        )
        val workers = MapWork.workers()
        val parMark = TimeSource.Monotonic.markNow()
        val (par, parLib) = runBlocking { Mapper.run(setup, kit, BoardLibrary(deck = "bench"), workers) }
        val parMs = parMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        println("MapperBenchTest: the same ${par.hands} hands on $workers workers in $parMs ms: ${par.hands * 1000.0 / parMs} hands a second")
        assertEquals(lib, parLib)
        val rate = moves * 1000 / ms
        assertTrue(rate >= FLOOR, "$rate engine moves a second mapping starters is below the floor of $FLOOR")
    }

    companion object {
        const val HANDS = 24
        const val BUDGET = 20_000

        /** Engine moves a second on one worker below which something is badly wrong (FxBenchTest's floor). */
        const val FLOOR = 2_000L
    }
}
