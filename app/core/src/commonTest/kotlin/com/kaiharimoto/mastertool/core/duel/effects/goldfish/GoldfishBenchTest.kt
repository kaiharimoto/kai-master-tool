package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.CardFrame
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The goldfish's speed (D.md §5.7, step 4's `GoldfishBenchTest`) on the fixture deck: the step-1 reference cards — searchers,
 * triggers, a Ritual, a Fusion, Synchro, Xyz and Link Monsters, a Trap to Set — and seven blanks, against "an Extra Deck
 * monster and an interruption". Hands a second and engine moves a second are printed for the release notes (the test's
 * standard output in its report).
 *
 * **The floor is not the target**, as `FxBenchTest`'s is not: the best of two passes must clear [FLOOR] hands a second on one
 * worker — far below what any machine CI runs on makes — so a busy runner never fails it, and a search gone badly wrong does.
 */
class GoldfishBenchTest {
    private val engine = FxRef.scripts.map { it.card }.toSet() + listOf(FxRef.ECHO, FxRef.MANDATE, FxRef.EMBER, FxRef.BEACON, FxRef.OFFERING)

    private fun fixture(): Pair<GoldfishSetup, GoldfishKit> {
        val main = listOf(
            FxRef.SCOUT to 3, FxRef.LAMP to 2, FxRef.CALL to 2, FxRef.ECHO to 3, FxRef.MANDATE to 2, FxRef.EMBER to 2, FxRef.PAWN to 3,
            FxRef.SPRITE to 2, FxRef.BEACON to 2, FxRef.TINKER to 2, FxRef.WARDEN to 2, FxRef.OFFERING to 2, FxRef.RALLY to 2,
            FxRef.SEEDS to 1, FxRef.SNARE to 2, FxRef.FLASH to 1, STONE to 7,
        ).flatMap { (c, n) -> List(n) { c } }
        check(main.size == 40) { "the fixture deck is 40 cards: ${main.size}" }
        val extra = listOf(FxRef.BRIDGE, FxRef.ARCH, FxRef.SPIDER, FxRef.PALADIN, FxRef.REGENT, FxRef.LORD, FxRef.CHIMERA)
        val scripts = FxRef.book.cards.mapNotNull { FxRef.book.script(it) }.filter { it.card in engine || it.card in extra }
        val trust = GoldfishFixtures.trust(scripts)
        val target = EndBoard(
            "bench", "An Extra Deck monster and an interruption", "fixture",
            listOf(
                BoardCond.Controls(Filter.AnyOf(listOf(Filter.Frame(CardFrame.SYNCHRO), Filter.Frame(CardFrame.XYZ), Filter.Frame(CardFrame.LINK))), 1),
                BoardCond.Interruptions(1),
            ),
        )
        return GoldfishSetup(GoldfishDeck(main, extra, "fixture", name = "Fixture"), target, hands = HANDS, seed = 7, budget = BUDGET) to GoldfishFixtures.kit(trust)
    }

    @Test
    fun handsASecondOnTheFixtureDeck() {
        val (setup, kit) = fixture()
        // A warm-up, so the JIT's first pass is not counted.
        Goldfish.runHere(setup.copy(hands = 20, seed = 1), kit)
        val passes = (1..2).map {
            val mark = TimeSource.Monotonic.markNow()
            val r = Goldfish.runHere(setup, kit)
            val ms = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
            Triple(r, ms, r.outcomes.distinctBy { it.reduced }.sumOf { it.moves.toLong() })
        }
        val (r, ms, moves) = passes.minByOrNull { it.second }!!
        val handsPerSecond = r.hands * 1000.0 / ms
        val movesPerSecond = moves * 1000 / ms
        println(
            "GoldfishBenchTest: ${r.hands} hands in $ms ms on one worker (best of ${passes.map { it.second }} ms, budget ${setup.budget} moves a hand): " +
                "${(handsPerSecond * 10).toLong() / 10.0} hands a second; $moves engine moves searched, $movesPerSecond a second; " +
                "${r.outcomes.map { it.reduced }.distinct().size} distinct engine parts; reached ${r.reached}, no line ${r.noLine}, undecided ${r.undecided}",
        )
        println("GoldfishBenchTest: ${GoldfishWords.headline(r, name = { kit.name(it) })}")
        println("GoldfishBenchTest: ${GoldfishWords.lines(r)}")
        // Every pass the same answer.
        passes.forEach { (p, _, _) -> assertEquals(r.outcomes.map { it.end }, p.outcomes.map { it.end }) }
        // The parallel run, for the notes: the same counts, faster on a machine with cores to spare.
        val workers = Goldfish.workers()
        val mark = TimeSource.Monotonic.markNow()
        val par = Goldfish.runBlocking(setup, kit, workers)
        val parMs = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1)
        println("GoldfishBenchTest: the same ${par.hands} hands on $workers workers in $parMs ms: ${par.hands * 1000L / parMs} hands a second")
        assertEquals(r.outcomes.map { it.end }, par.outcomes.map { it.end })
        assertTrue(handsPerSecond >= FLOOR, "$handsPerSecond hands a second (best of two) is below the floor of $FLOOR")
    }

    companion object {
        const val HANDS = 120
        const val BUDGET = 5_000

        /** Hands a second on one worker below which something is badly wrong. */
        const val FLOOR = 0.5
    }
}
