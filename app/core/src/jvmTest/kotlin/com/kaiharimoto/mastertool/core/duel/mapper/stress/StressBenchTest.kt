package com.kaiharimoto.mastertool.core.duel.mapper.stress

import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The stress tests' cost (E.md §6, `StressBenchTest`) on the mapper bench's fixture deck: every one-card starter stressed
 * against Example Ward (an Ash-like) and against Ward and Example Veil, and every board of the library's line answered at its
 * worst point. Tests, engine moves and seconds are printed for the release notes.
 *
 * **The floor is not the target**: it fails only far below any machine CI runs on, so a busy runner never fails it and a
 * search gone badly wrong does.
 */
class StressBenchTest {
    private val engine = FxRef.scripts.map { it.card }.toSet() + listOf(FxRef.ECHO, FxRef.MANDATE, FxRef.EMBER, FxRef.BEACON, FxRef.OFFERING)
    private val main = listOf(
        FxRef.SCOUT to 3, FxRef.LAMP to 2, FxRef.CALL to 2, FxRef.ECHO to 3, FxRef.MANDATE to 2, FxRef.EMBER to 2, FxRef.PAWN to 3,
        FxRef.SPRITE to 2, FxRef.BEACON to 2, FxRef.TINKER to 2, FxRef.WARDEN to 2, FxRef.OFFERING to 2, FxRef.RALLY to 2,
        FxRef.SEEDS to 1, FxRef.SNARE to 2, FxRef.FLASH to 1, STONE to 7,
    ).flatMap { (c, n) -> List(n) { c } }
    private val extra = listOf(FxRef.BRIDGE, FxRef.ARCH, FxRef.SPIDER, FxRef.PALADIN, FxRef.REGENT, FxRef.LORD, FxRef.CHIMERA)
    private val kit: GoldfishKit = GoldfishFixtures.kit(
        GoldfishFixtures.trust(
            FxRef.book.cards.mapNotNull { FxRef.book.script(it) }
                .filter { it.card in engine || it.card in extra || it.card == FxRef.WARD || it.card == FxRef.VEIL },
        ),
    )

    @Test
    fun stressTestsOnTheFixtureDeck() {
        val table = StarterTable.run(main, extra, kit, BoardLibrary(), pairs = false, budget = 20_000)
        val lib = table.library
        listOf(listOf(FxRef.WARD), listOf(FxRef.WARD, FxRef.VEIL)).forEach { against ->
            val mark = TimeSource.Monotonic.markNow()
            var moves = 0L
            var complete = 0
            val found = ArrayList<String>()
            table.rows.forEach { row ->
                val deal = MapDeal(row.cards, fodder = row.fodder)
                val ceiling = row.ends.mapNotNull { lib.byKey[it]?.traits?.interruptions }.maxOrNull() ?: 0
                val search = StressSearch(kit, budget = BUDGET)
                val r = search.test(StressDeal(deal, against).table(main, extra, kit), deal.fodderUids(deal.table(main, extra, kit)), ceiling)
                moves += r.moves
                if (r.complete) complete++
                found += "${row.cards.joinToString("+") { kit.name(it) }}: ${r.guarantee}/$ceiling" + (if (r.complete) "" else "?")
            }
            val ms = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
            println(
                "StressBenchTest: ${table.rows.size} one-card starters against ${against.joinToString("+") { kit.name(it) }} in $ms ms: " +
                    "$moves engine moves (${moves * 1000 / ms} a second), $complete complete; " + found.joinToString("; "),
            )
            assertTrue(moves * 1000 / ms >= FLOOR || moves < 1_000, "${moves * 1000 / ms} engine moves a second stressing starters is below the floor")
        }
        val mark = TimeSource.Monotonic.markNow()
        var moves = 0L
        val boards = lib.boards.take(BOARDS)
        val keeps = boards.map { b ->
            val line = b.lines.first()
            val search = StressSearch(kit, budget = BUDGET)
            val th = search.through(StressDeal(line.deal, listOf(FxRef.WARD)).table(main, extra, kit), line, line.deal.fodderUids(line.deal.table(main, extra, kit)))
            moves += search.spentMoves
            th
        }
        val ms = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        println(
            "StressBenchTest: ${boards.size} boards' lines answered by Ward in $ms ms, $moves engine moves; " +
                "kept ${keeps.count { it.replayed }} replayed, ${keeps.count { it.complete }} complete, " +
                "through ${keeps.groupingBy { it.keeps }.eachCount().toSortedMap()} against own ${boards.groupingBy { it.traits.interruptions }.eachCount().toSortedMap()}",
        )
        assertTrue(keeps.all { it.replayed }, "every kept line plays on the stress table")
        assertTrue(keeps.zip(boards).all { (k, b) -> k.keeps <= b.traits.interruptions }, "a line never keeps more than its board")
    }

    companion object {
        const val BUDGET = 50_000
        const val BOARDS = 60

        /** Engine moves a second on one worker below which something is badly wrong (FxBenchTest's floor). */
        const val FLOOR = 2_000L
    }
}
