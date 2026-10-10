package com.kaiharimoto.mastertool.core.duel.search.ladder

import com.kaiharimoto.mastertool.core.duel.mapper.train.Elo
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ladder's arithmetic and its keeping (Phase E, E.4; E.md §4.5). */
class LadderTest {
    private fun games(a: String, b: String, wins: Int, losses: Int, draws: Int = 0) =
        List(wins) { Triple(a, b, 1.0) } + List(losses) { Triple(a, b, 0.0) } + List(draws) { Triple(a, b, 0.5) }

    @Test
    fun twoPlayersFitToTheirShareWithTheAnchorsDrawAndARangeFromTheCurvature() {
        // 30 of 40 against the anchor, and the prior's drawn game: 30½ to 10½.
        val r = BradleyTerry.fit(listOf("b"), games("b", "a", 30, 10), anchor = "a")
        val a = r.first { it.player == "a" }
        val b = r.first { it.player == "b" }
        assertEquals(Elo.BASE, a.elo, 1e-6)
        assertEquals(0.0, a.high - a.low, 1e-9, "the anchor has no range")
        val want = Elo.BASE + 400 * log10(30.5 / 10.5)
        assertEquals(want, b.elo, 0.5)
        // One pair: the standard error is 1 ÷ √(n·p·(1−p)) in strength, n counting the prior's game.
        val p = 30.5 / 41
        val half = 1.96 * (400 / ln(10.0)) / sqrt(41 * p * (1 - p))
        assertEquals(half, (b.high - b.low) / 2, 0.5)
        assertEquals(40, b.games)
        assertEquals(0.75, b.score, 1e-9)
    }

    @Test
    fun aChainOfPlayersIsOrderedAndASweepIsLargeButFinite() {
        val g = games("b", "a", 30, 10) + games("c", "b", 30, 10)
        val r = BradleyTerry.fit(listOf("b", "c"), g, anchor = "a").associateBy { it.player }
        assertTrue(r.getValue("c").elo > r.getValue("b").elo && r.getValue("b").elo > r.getValue("a").elo)
        // c never played the anchor: its range is wider than b's, which did.
        assertTrue(r.getValue("c").high - r.getValue("c").low > r.getValue("b").high - r.getValue("b").low)
        val sweep = BradleyTerry.fit(listOf("s"), games("s", "a", 20, 0), anchor = "a").first { it.player == "s" }
        assertTrue(sweep.elo.isFinite() && sweep.elo > 1400, "${sweep.elo}")
        assertTrue(sweep.high - sweep.low > 300, "a sweep is uncertain: ${sweep.low}–${sweep.high}")
    }

    @Test
    fun theInverseInvertsAndASingularMatrixSaysSo() {
        val m = arrayOf(doubleArrayOf(4.0, 1.0), doubleArrayOf(2.0, 3.0))
        val inv = BradleyTerry.invert(m)!!
        for (i in 0..1) for (j in 0..1) {
            val v = (0..1).sumOf { k -> m[i][k] * inv[k][j] }
            assertEquals(if (i == j) 1.0 else 0.0, v, 1e-12)
        }
        assertEquals(null, BradleyTerry.invert(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0))))
    }

    /** [n] seeds of the candidate against the parent, both ways: [outcome] of seed i gives the candidate's two results. */
    private fun paired(n: Int, outcome: (Int) -> Pair<Int?, Int?>): List<LadderGame> = (1..n).flatMap { i ->
        val (asFirst, asSecond) = outcome(i)
        // asFirst: the winning seat when the candidate sits in seat 0; asSecond: when it sits in seat 1.
        listOf(LadderGame(i, listOf("new", "old"), asFirst), LadderGame(i, listOf("old", "new"), asSecond))
    }

    @Test
    fun theGateCountsSeedsBothWaysAndSharesItsChanceAmongTheCandidates() {
        // 20 seeds won both ways, 5 split, 5 lost both ways.
        val g = paired(30) { i -> when { i <= 20 -> 0 to 1; i <= 25 -> 0 to 0; else -> 1 to 0 } }
        val v = LadderGate.judge(g, "new", "old")
        assertEquals(30, v.seeds)
        assertEquals(20, v.wins)
        assertEquals(5, v.losses)
        assertEquals(5, v.ties)
        assertTrue(v.pass, v.why)
        // The same games against a bar shared by a thousand candidates do not pass.
        assertFalse(LadderGate.judge(g, "new", "old", attempts = 1000).pass)
        // Too few seeds is never a verdict, however clean.
        val few = LadderGate.judge(paired(10) { 0 to 1 }, "new", "old")
        assertFalse(few.pass)
        assertEquals("too few deals", few.words)
        assertTrue("10 deals played both ways of the 24" in few.why, few.why)
        assertEquals("clearly ahead", v.words)
        assertTrue(v.why.startsWith("ahead on 20 deals, behind on 5, even on 5"), v.why)
        // A seed with one game is not a pair, and a draw both ways is a tie.
        val half = LadderGate.judge(listOf(LadderGame(1, listOf("new", "old"), 0)) + paired(1) { null to null }.map { it.copy(seed = 2) }, "new", "old")
        assertEquals(1, half.seeds)
        assertEquals(1, half.ties)
    }

    @Test
    fun theBookKeepsEveryGameAndReadsOnlyTheBenchAsItIs() {
        val g = LadderGame(1, listOf(LadderPlayers.FAST, LadderPlayers.GREEDY), 0, "lp", 7, Bench.print, 11L)
        val old = g.copy(bench = "older", winner = 1)
        val book = LadderBook().with(old).with(g).with(g.copy(at = 12L))
        assertEquals(2, book.games.size, "a game of the same seed, players and bench replaces")
        assertEquals(listOf(12L), book.on().map { it.at })
        val back = LadderCodec.decode(LadderCodec.encode(book))
        assertEquals(book, back)
        assertEquals(LadderBook(), LadderCodec.decode("not json"))
        assertEquals(LadderBook(), LadderCodec.decode("{\"games\":[],\"later\":1}").copy(games = emptyList()))
        // Owed: every pair on every seed from both seats, less what is kept on this bench.
        val all = LadderRun.owed(LadderBook())
        assertEquals(LadderPlayers.pairs.sumOf { it.seeds } * 2, all.size)
        assertEquals(all.size - 1, LadderRun.owed(book).size)
        assertEquals(1.0, g.score(LadderPlayers.FAST))
        assertEquals(0.0, g.score(LadderPlayers.GREEDY))
        assertEquals(null, g.score(LadderPlayers.STRONG))
    }

    @Test
    fun theBenchIsWrittenWholeAndBuiltAsDecksAre() {
        assertEquals(1.0, Bench.coverage(), "every card on the bench plays")
        for (d in Bench.decks) {
            assertEquals(40, d.main.size, d.name)
            assertTrue(d.extra.size <= 15, d.name)
            val copies = (d.main + d.extra).groupingBy { it }.eachCount()
            assertTrue(copies.values.all { it <= 3 }, "${d.name}: $copies")
        }
        assertEquals(Bench.print, Bench.print)
        assertEquals(12, Bench.print.length)
        // Every pairing of decks is dealt, mirrors too, and the seeds never repeat.
        assertEquals(9, LadderSeeds.all.take(LadderSeeds.COUNT / 2).map { it.decks }.distinct().size, "Strong's half sees every pairing too")
        assertEquals(LadderSeeds.COUNT, LadderSeeds.all.map { it.seed }.distinct().size)
        // Every player the ladder plays can be made, and the anchor is the greedy player.
        assertTrue(LadderPlayers.all.all { LadderPlayers.make(it.id) != null })
        assertTrue(LadderPlayers.pairs.flatMap { listOf(it.a, it.b) }.toSet() == LadderPlayers.all.map { it.id }.toSet())
        // The verdicts' pairs play every deal; a game past the ladder's deals is kept and never counted.
        assertTrue(LadderPlayers.pairs.filter { LadderPlayers.STRONG !in listOf(it.a, it.b) }.all { it.seeds == LadderSeeds.COUNT })
        val past = LadderBook().with(LadderGame(LadderSeeds.COUNT + 1, listOf(LadderPlayers.FAST, LadderPlayers.GREEDY), 0, bench = Bench.print))
        assertTrue(past.on().isEmpty())
        assertEquals(null, LadderPlayers.make("search-v9/field/fast"))
    }

    @Test
    fun theReadingRatesThePlayersOnTheBenchAndSaysWhatIsOwed() {
        val g = (1..LadderSeeds.COUNT).flatMap { i ->
            listOf(
                LadderGame(i, listOf(LadderPlayers.FAST, LadderPlayers.GREEDY), if (i % 5 == 0) 1 else 0, bench = Bench.print),
                LadderGame(i, listOf(LadderPlayers.GREEDY, LadderPlayers.FAST), 1, bench = Bench.print),
            )
        }
        val r = LadderReading.of(LadderBook(games = g))
        assertEquals(listOf(LadderPlayers.FAST, LadderPlayers.GREEDY), r.ratings.map { it.player })
        assertTrue(r.overGreedy.pass, r.overGreedy.why)
        assertEquals(0, r.overBlank.seeds)
        assertEquals(2 * LadderSeeds.COUNT, r.games)
        assertEquals(LadderRun.owed(LadderBook()).size - 2 * LadderSeeds.COUNT, r.owed)
        assertTrue(abs(r.of(LadderPlayers.GREEDY)!!.elo - Elo.BASE) < 1e-6)
    }
}
