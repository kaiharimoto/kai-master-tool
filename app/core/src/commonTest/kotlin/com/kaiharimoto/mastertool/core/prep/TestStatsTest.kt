package com.kaiharimoto.mastertool.core.prep

import com.kaiharimoto.mastertool.core.prep.TestStats.Rate
import com.kaiharimoto.mastertool.core.web.WebEntry
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestStatsTest {

    private fun near(expected: Double, actual: Double, message: String = "") =
        assertTrue(abs(expected - actual) < 1e-9, "$message expected $expected, got $actual")

    private var at = 0L

    private fun game(
        opponent: String,
        turn: String,
        result: String,
        game: Int = 1,
        minutes: Int? = null,
        deck: String? = "mine",
        name: String = opponent.uppercase(),
    ) = TestGame("g${at}", at++, deck, opponent, name, turn, game, result, minutes = minutes)

    @Test
    fun aMatchOfEvenGamesIsEven() {
        near(0.5, TestStats.matchWin(0.5, 0.5))
        near(1.0, TestStats.matchWin(1.0, 1.0))
        near(0.0, TestStats.matchWin(0.0, 0.0))
        near(0.5, TestStats.matchWin(0.5, 0.5, firstChoice = false))
    }

    @Test
    fun anAsymmetricMatchWalksTheTree() {
        // By hand: game 1 is won (0.7 + 0.4) / 2 = 0.55. After a win you go
        // second (0.4), after a loss first (0.7).
        // Won G1: 0.4 + 0.6 × 0.7 = 0.82.  Lost G1: 0.7 × 0.4 = 0.28.
        // 0.55 × 0.82 + 0.45 × 0.28 = 0.451 + 0.126 = 0.577.
        near(0.577, TestStats.matchWin(0.7, 0.4))
        // Choosing second swaps the turns after Game 1: after a win you go
        // first (0.7), after a loss second (0.4).
        near(0.55 * (0.7 + 0.3 * 0.4) + 0.45 * 0.4 * 0.7, TestStats.matchWin(0.7, 0.4, firstChoice = false))
        // A deck that only ever wins going first: whoever wins Game 1 loses
        // Game 2 going second and wins Game 3 going first — so Game 1 decides.
        near(0.5, TestStats.matchWin(1.0, 0.0))
        // Unequal and uneven: 0.8 first, 0.6 second. G1 = 0.7.
        // Won G1: 0.6 + 0.4 × 0.8 = 0.92. Lost G1: 0.8 × 0.6 = 0.48.
        near(0.7 * 0.92 + 0.3 * 0.48, TestStats.matchWin(0.8, 0.6))
    }

    @Test
    fun wilsonStaysInsideZeroAndOne() {
        listOf(Rate(0, 0), Rate(0, 3), Rate(3, 3), Rate(7, 10), Rate(1, 1), Rate(50, 100)).forEach { r ->
            val (low, high) = r.wilson()
            assertTrue(low in 0.0..1.0 && high in 0.0..1.0 && low <= high, "$r: $low..$high")
            if (r.games > 0) assertTrue(r.pct in low..high, "$r contains its own rate")
        }
        assertEquals(0.0 to 1.0, Rate(0, 0).wilson())
        val (low, high) = Rate(3, 3).wilson()
        assertTrue(low < 0.5 && high > 0.99, "three from three says little: $low..$high")
        // Fifty from a hundred: about 0.40 to 0.60.
        val (l, h) = Rate(50, 100).wilson()
        assertTrue(abs(l - 0.4038) < 0.001 && abs(h - 0.5962) < 0.001, "$l..$h")
    }

    @Test
    fun theMatrixSplitsByTurnAndSiding() {
        val games = listOf(
            game("yubel", TestGame.FIRST, TestGame.WIN, minutes = 10),
            game("yubel", TestGame.FIRST, TestGame.LOSS, game = 2, minutes = 14),
            game("yubel", TestGame.SECOND, TestGame.WIN, game = 3),
            game("yubel", TestGame.SECOND, TestGame.DRAW, game = 2),
            game("ryzeal", TestGame.SECOND, TestGame.LOSS, name = "Ryzeal Mitsurugi"),
            game("ryzeal", TestGame.FIRST, TestGame.WIN, deck = "other"),
        )
        val rows = TestStats.matrix(games, deckId = "mine")
        assertEquals(listOf("yubel", "ryzeal"), rows.map { it.opponent })
        val yubel = rows[0]
        assertEquals("YUBEL", yubel.name)
        assertEquals(Rate(1, 2), yubel.first)
        assertEquals(Rate(1, 1), yubel.second) // the draw is in no rate
        assertEquals(Rate(1, 1), yubel.preSide)
        assertEquals(Rate(1, 2), yubel.postSide)
        assertEquals(Rate(2, 3), yubel.all)
        near(12.0, yubel.avgMinutes!!)
        val ryzeal = rows[1]
        assertEquals("Ryzeal Mitsurugi", ryzeal.name)
        assertEquals(Rate(0, 1), ryzeal.all)
        assertNull(ryzeal.avgMinutes)
        // Without a deck, every deck's games count.
        assertEquals(Rate(1, 2), TestStats.matrix(games).first { it.opponent == "ryzeal" }.all)
    }

    @Test
    fun theFieldIsWeighedAndSmoothed() {
        val rows = TestStats.matrix(
            listOf(
                game("a", TestGame.FIRST, TestGame.WIN),
                game("a", TestGame.FIRST, TestGame.WIN),
                game("a", TestGame.SECOND, TestGame.WIN),
                game("a", TestGame.SECOND, TestGame.WIN),
            ),
        )
        // Two from two each way, smoothed by four games at 0.5: 4 / 6.
        val p = 4.0 / 6
        val a = TestStats.matchWin(p, p)
        near(a, TestStats.expected(rows, mapOf("a" to 100)))
        // Half the field is an opponent never tested: it counts at the prior.
        near(0.5 * a + 0.5 * 0.5, TestStats.expected(rows, mapOf("a" to 30, "b" to 30)))
        // No shares at all is the prior's match.
        near(0.5, TestStats.expected(rows, emptyMap()))
        // No smoothing: two from two is certain.
        near(1.0, TestStats.expected(rows, mapOf("a" to 10), priorWeight = 0))
    }

    @Test
    fun gameOneIsPlayedAtItsOwnRatesAndTheSidedGamesAtTheirs() {
        // Equal rates throughout: the four-rate walk is the two-rate one.
        near(TestStats.matchWin(0.7, 0.4), TestStats.matchWin(0.7, 0.4, 0.7, 0.4))
        // By hand: Game 1 at (0.3 first, 0.2 second), so won (0.3 + 0.2) / 2 = 0.25. Sided at 0.8 first, 0.6 second.
        // Won G1, you go second: 0.6 + 0.4 × 0.8 = 0.92. Lost G1, you go first: 0.8 × 0.6 = 0.48.
        near(0.25 * 0.92 + 0.75 * 0.48, TestStats.matchWin(0.3, 0.2, 0.8, 0.6))
        // A deck that only wins after siding loses Game 1 and must win both of the others.
        near(0.8 * 0.6, TestStats.matchWin(0.0, 0.0, 0.8, 0.6))
    }

    @Test
    fun theMatrixKeepsGameOneAndTheSidedGamesApartByTurn() {
        val rows = TestStats.matrix(
            listOf(
                game("a", TestGame.FIRST, TestGame.LOSS),
                game("a", TestGame.SECOND, TestGame.LOSS),
                game("a", TestGame.FIRST, TestGame.WIN, game = 2),
                game("a", TestGame.SECOND, TestGame.WIN, game = 3),
                game("a", TestGame.SECOND, TestGame.WIN, game = 2),
            ),
        )
        val a = rows.single()
        assertEquals(Rate(0, 1), a.preFirst)
        assertEquals(Rate(0, 1), a.preSecond)
        assertEquals(Rate(1, 1), a.postFirst)
        assertEquals(Rate(2, 2), a.postSecond)
        // Each smoothed by four games at 0.5, Game 1 at its own rates, the sided games at theirs.
        fun s(w: Int, n: Int) = (w + 2.0) / (n + 4)
        near(TestStats.matchWin(s(0, 1), s(0, 1), s(1, 1), s(2, 2)), TestStats.expected(rows, mapOf("a" to 1)))
        // Pooled, the old reading is another number: 55.4 % against 58.7 %.
        assertTrue(abs(TestStats.expected(rows, mapOf("a" to 1)) - TestStats.matchWin(s(1, 2), s(2, 3))) > 0.03)
        // A row with no splits — as an older caller builds it — reads exactly as before.
        val old = TestStats.Row("b", "B", Rate(3, 4), Rate(1, 4), Rate.NONE, Rate.NONE, Rate(4, 8), null)
        near(TestStats.matchWin(s(3, 4), s(1, 4)), TestStats.expected(listOf(old), mapOf("b" to 1)))
        // A split with no games falls back to its turn's pooled rate: only Game 1 played, the sided games use it too.
        val g1 = TestStats.matrix(listOf(game("c", TestGame.FIRST, TestGame.WIN), game("c", TestGame.SECOND, TestGame.LOSS)))
        near(TestStats.matchWin(s(1, 1), s(0, 1)), TestStats.expected(g1, mapOf("c" to 1)))
    }

    @Test
    fun theMirrorStaysInTheFieldAtItsShare() {
        val web = listOf(
            WebEntry("mine", mine = true, share = 20),
            WebEntry("snake", share = 80),
            WebEntry("unknown"),
        )
        val shares = TestStats.field(web)
        assertEquals(mapOf("mine" to 20, "snake" to 80), shares)
        // Snake-Eye always beaten; the mirror never played counts at 50 %.
        val rows = TestStats.matrix(List(6) { game("snake", if (it % 2 == 0) TestGame.FIRST else TestGame.SECOND, TestGame.WIN) })
        val snake = TestStats.expected(rows, mapOf("snake" to 1))
        near(0.8 * snake + 0.2 * 0.5, TestStats.expected(rows, shares))
        // Dropping the mirror and renormalising read the field as all Snake-Eye: too high.
        assertTrue(TestStats.expected(rows, mapOf("snake" to 80)) > TestStats.expected(rows, shares))
        // The mirror's games — logged by the deck's id, or typed by its name — are its rate.
        val logged = listOf(
            game("mine", TestGame.FIRST, TestGame.LOSS),
            game("My Deck", TestGame.SECOND, TestGame.LOSS, name = "My Deck"),
            game("snake", TestGame.FIRST, TestGame.WIN),
        )
        val folded = TestStats.matrix(TestStats.mirrored(logged, "mine", listOf("my deck")))
        assertEquals(Rate(0, 2), folded.single { it.opponent == "mine" }.all)
        assertTrue(folded.none { it.opponent == "My Deck" })
        assertEquals(logged, TestStats.mirrored(logged, null, listOf("my deck")))
    }

    @Test
    fun longMatchupsAreATimeRisk() {
        val rows = TestStats.matrix(
            listOf(
                game("slow", TestGame.FIRST, TestGame.WIN, minutes = 18),
                game("fast", TestGame.FIRST, TestGame.WIN, minutes = 12),
                game("edge", TestGame.FIRST, TestGame.WIN, minutes = 16),
                game("untimed", TestGame.FIRST, TestGame.WIN),
            ),
        )
        assertEquals(listOf("slow"), TestStats.timeRisk(rows).sorted())
        assertEquals(listOf("edge", "slow"), TestStats.timeRisk(rows, roundMinutes = 45).sorted())
    }
}
