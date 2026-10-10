package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.world.MatchMath
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Your odds at the event: the cut, the roll, the rest of the room, the clock and what to practise (Phase G, G.5). */
class EventOddsTest {
    private fun close(a: Double, b: Double, tol: Double = 1e-9) = assertTrue(abs(a - b) < tol, "$a against $b")

    private fun row(
        key: String,
        preFirst: TestStats.Rate = TestStats.Rate.NONE,
        preSecond: TestStats.Rate = TestStats.Rate.NONE,
        postFirst: TestStats.Rate = TestStats.Rate.NONE,
        postSecond: TestStats.Rate = TestStats.Rate.NONE,
        minutes: Double? = null,
    ): TestStats.Row {
        val first = preFirst + postFirst
        val second = preSecond + postSecond
        return TestStats.Row(key, key, first, second, preFirst + preSecond, postFirst + postSecond, first + second, minutes, preFirst, preSecond, postFirst, postSecond)
    }

    @Test
    fun theCutChanceIsTheBinomialToTheCutRecord() {
        // 64 players, Tier 2: six rounds, Top 8; 5-1 or better makes it.
        val swiss = Policy.swiss(2, 64)
        assertEquals(6, swiss.rounds)
        assertTrue(Policy.cutRecord(swiss, 64).startsWith("5-1 or better"))
        for (p in listOf(0.4, 0.5, 0.55, 0.62)) close(p.pow(6) + 6 * p.pow(5) * (1 - p), Policy.cutChance(p, swiss, 64)!!)
        close(7.0 / 64, Policy.cutChance(0.5, swiss, 64)!!)
        assertNull(Policy.cutChance(0.5, Policy.swiss(1, 8), 8), "no cut")
    }

    @Test
    fun theRollIsCalledFromGameOnesTwoRates() {
        val even = TestStats.turnCall(row("y", preFirst = TestStats.Rate(5, 10), preSecond = TestStats.Rate(5, 10)))
        close(0.5, even.secondBetter, 1e-6)
        assertNull(even.choice())
        val second = TestStats.turnCall(row("y", preFirst = TestStats.Rate(3, 10), preSecond = TestStats.Rate(9, 10)))
        assertTrue(second.secondBetter > 0.95, "${second.secondBetter}")
        assertEquals(false, second.choice())
        assertEquals(20, second.games)
    }

    @Test
    fun theRestOfTheRoomAndTheClockCountAsTheyShould() {
        // Half the room is a deck never played (50 %), half the rest at 30 %.
        close(0.5 * 0.5 + 0.5 * 0.3, TestStats.expected(emptyList(), mapOf("a" to 50), other = TestStats.Other(50, 0.3)))
        close(0.5, TestStats.expected(emptyList(), mapOf("a" to 50)), 1e-12)
        // Three games fit: the walk itself. Two: a match that goes to three is unfinished, a loss. None: every match is.
        val r = doubleArrayOf(0.6, 0.5, 0.55, 0.45)
        close(TestStats.matchWin(r[0], r[1], r[2], r[3]), TestStats.matchWinTimed(r[0], r[1], r[2], r[3], 3))
        val twoNil = 0.5 * r[0] * r[3] + 0.5 * r[1] * r[3]
        close(twoNil, TestStats.matchWinTimed(r[0], r[1], r[2], r[3], 2))
        close(0.0, TestStats.matchWinTimed(r[0], r[1], r[2], r[3], 1))
        assertEquals(2, TestStats.gamesThatFit(20.0))
        assertEquals(3, TestStats.gamesThatFit(null))
        val long = row("slow", preFirst = TestStats.Rate(6, 10), preSecond = TestStats.Rate(6, 10), minutes = 22.0)
        assertTrue(TestStats.expected(listOf(long), mapOf("slow" to 100), timed = true) < TestStats.expected(listOf(long), mapOf("slow" to 100)))
    }

    @Test
    fun thePlanPicksTheGamesThatNarrowTheRangeMost() {
        // Yubel is half the room and Game 1 going second against it has two games; everything else has twenty.
        val many = TestStats.Rate(11, 20)
        val rows = listOf(
            row("yubel", preFirst = many, preSecond = TestStats.Rate(1, 2), postFirst = many, postSecond = many),
            row("k9", preFirst = many, preSecond = many, postFirst = many, postSecond = many),
        )
        val shares = mapOf("yubel" to 50, "k9" to 30)
        val next = PracticePlan.next(rows, shares)!!
        assertEquals("yubel", next.opponent)
        assertEquals(PracticePlan.Kind.G1_SECOND, next.kind)
        assertTrue(next.gain > 0)
        // In simulation: five more Game 1s second against Yubel, at its rate, narrow the field's interval more than five
        // more of any of K9's.
        fun width(rs: List<TestStats.Row>) = MatchMath.field(rs, shares, draws = 20000, seed = 3).let { it.high - it.low }
        val before = width(rows)
        val planned = width(listOf(row("yubel", preFirst = many, preSecond = TestStats.Rate(3, 7), postFirst = many, postSecond = many), rows[1]))
        val other = width(listOf(rows[0], row("k9", preFirst = many, preSecond = many, postFirst = many, postSecond = TestStats.Rate(14, 25))))
        assertTrue(before - planned > before - other, "planned ${before - planned} against ${before - other}")
        assertTrue(PracticePlan.words(next).startsWith("5 Game 1s going second against yubel"))
    }

    @Test
    fun theFieldsIntervalCoversTheTruthInSimulation() {
        // Two opponents with true game rates; games logged at those rates; the field's 95 % interval holds the true expected
        // match win in most of the logs.
        val truth = mapOf("a" to doubleArrayOf(0.6, 0.45, 0.65, 0.5), "b" to doubleArrayOf(0.4, 0.55, 0.45, 0.6))
        val shares = mapOf("a" to 60, "b" to 40)
        val target = truth.entries.sumOf { (k, r) -> shares.getValue(k) / 100.0 * TestStats.matchWin(r[0], r[1], r[2], r[3]) }
        val random = kotlin.random.Random(11)
        var covered = 0
        val runs = 120
        repeat(runs) { run ->
            fun rate(p: Double, n: Int) = TestStats.Rate((0 until n).count { random.nextDouble() < p }, n)
            val rows = truth.map { (k, r) -> row(k, rate(r[0], 12), rate(r[1], 12), rate(r[2], 12), rate(r[3], 12)) }
            val i = MatchMath.field(rows, shares, draws = 1500, seed = run.toLong())
            if (target in i.low..i.high) covered++
        }
        println("[event] the field's 95 % interval held the truth in $covered of $runs logs")
        assertTrue(covered >= runs * 0.85, "$covered of $runs")
    }
}

class EventReadingTest {
    @Test
    fun theEventsReadingIsTheSameNumbersEverywhere() {
        fun g(id: Int, opp: String, turn: String, result: String, post: Boolean = false) =
            TestGame("g$id", id.toLong(), "mine", opp, opp, turn, if (post) 2 else 1, result)
        val games = (1..6).map { g(it, "yubel", TestGame.FIRST, if (it <= 3) TestGame.WIN else TestGame.LOSS) } +
            (7..10).map { g(it, "yubel", TestGame.SECOND, if (it <= 9) TestGame.WIN else TestGame.LOSS) } +
            g(11, "k9", TestGame.FIRST, TestGame.DRAW)
        val entries = listOf(
            com.kaiharimoto.mastertool.core.web.WebEntry("yubel", share = 40),
            com.kaiharimoto.mastertool.core.web.WebEntry("k9", share = 40),
            com.kaiharimoto.mastertool.core.web.WebEntry("mine", mine = true, share = 20),
        )
        val event = PrepEvent("e", "Regional", "2026-11-01", tier = 2, attendance = 64, otherShare = 10, otherWin = 40)
        val r = EventOdds.read(games, entries, event, "mine", "Lab")
        val i = r.interval!!
        assertTrue(i.low <= i.point && i.point <= i.high)
        assertEquals(10, r.games, "a draw decides nothing")
        assertEquals(TestStats.expected(r.rows, r.shares, other = TestStats.Other(10, 0.4)), i.point, 1e-12)
        assertEquals(Policy.cutChance(i.point, Policy.swiss(2, 64), 64), r.cut)
        assertTrue(r.next != null)
        assertTrue(EventOdds.words(i, r.games).endsWith("10 games"))
        assertTrue("yubel" in r.calls)
    }
}
