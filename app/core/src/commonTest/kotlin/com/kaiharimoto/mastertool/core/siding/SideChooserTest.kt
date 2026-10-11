package com.kaiharimoto.mastertool.core.siding

import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A side deck chosen for the field (Phase G part two, G2.2): from the evidence there is, never for a number it lacks. */
class SideChooserTest {
    private fun n(code: Int, k: Int) = List(k) { CardId(code) }

    /** Forty cards: three each of thirteen, one more Pawn. */
    private val deck = Deck(
        main = listOf(FxRef.PAWN, FxRef.KNIGHT, FxRef.LAMP, FxRef.SCOUT, FxRef.BEACON, FxRef.WARDEN, FxRef.ECHO, FxRef.CALL, FxRef.RALLY, FxRef.OATH, FxRef.OFFERING, FxRef.FLASH, FxRef.SNARE)
            .flatMap { n(it, 3) } + CardId(FxRef.MILL),
        side = n(FxRef.EMBER, 2) + n(FxRef.SIEVE, 1),
    )
    private val isExtra: (CardId) -> Boolean = { false }
    private val opponents = listOf(SideChooser.Opponent("x", "Giants", 3.0), SideChooser.Opponent("y", "Rites", 1.0), SideChooser.Opponent("z", "Unknown", 1.0))

    private fun w(card: Int, o: String, p: Double, n: Int = 20) = SideWorth(card, o, p, p - 6, p + 6, SideWorth.SHOOTOUT, n)

    @Test
    fun eachPlanBringsInWhatTheEvidenceRatesForWhatItRatesLowCopyForCopy() {
        val worth = listOf(
            w(FxRef.WARD, "x", 12.0), w(FxRef.VEIL, "x", 5.0), w(FxRef.PAWN, "x", -8.0), w(FxRef.KNIGHT, "x", -3.0),
            w(FxRef.COLOSSUS, "y", 10.0), w(FxRef.PAWN, "y", -6.0),
        )
        val s = SideChooser.choose(deck, opponents, worth, listOf(FxRef.WARD, FxRef.VEIL, FxRef.COLOSSUS, FxRef.DENIAL), { 3 }, isExtra)
        val x = s.plans.first { it.opponent == "x" }
        assertEquals(n(FxRef.PAWN, 3) + n(FxRef.KNIGHT, 3), x.first.out)
        assertEquals(n(FxRef.WARD, 3) + n(FxRef.VEIL, 3), x.first.into)
        val y = s.plans.first { it.opponent == "y" }
        assertEquals(n(FxRef.PAWN, 3), y.first.out)
        assertEquals(n(FxRef.COLOSSUS, 3), y.first.into)
        // Unrated is said, never guessed at; a candidate nothing rates is never chosen.
        assertEquals(listOf("z"), s.unrated)
        assertTrue(s.side.none { it.card == FxRef.DENIAL })
        // The fifteen: the field's most valuable first, then the Side Deck as it was.
        assertEquals(listOf(FxRef.WARD, FxRef.VEIL, FxRef.COLOSSUS), s.side.filter { !it.kept }.map { it.card })
        assertEquals(12, s.cards.size)
        assertTrue(s.side.filter { it.kept }.map { it.card }.containsAll(listOf(FxRef.EMBER, FxRef.SIEVE)))
        assertEquals(emptyList(), s.problems)
        val words = SideChooser.words(s, { "#$it" }, { it })
        assertTrue("Against Rites: out" in words && "against z" in words.lowercase(), words)
    }

    @Test
    fun theSideDeckIsFifteenAndEachPlanOnlyWhatItHolds() {
        val many = listOf(FxRef.WARD, FxRef.VEIL, FxRef.COLOSSUS, FxRef.DENIAL, FxRef.PURGE, FxRef.MANDATE)
        val worth = many.mapIndexed { i, c -> w(c, if (i % 2 == 0) "x" else "y", 20.0 - i) } +
            listOf(FxRef.PAWN, FxRef.KNIGHT, FxRef.LAMP).flatMap { listOf(w(it, "x", -5.0), w(it, "y", -5.0)) }
        val s = SideChooser.choose(deck, opponents.take(2), worth, many, { 3 }, isExtra)
        assertEquals(15, s.cards.size)
        s.plans.forEach { p ->
            assertEquals(p.first.out.size, p.first.into.size, "${p.name}")
            assertTrue(p.first.into.size <= SideChooser.PLAN_MAX)
            p.first.into.groupingBy { it }.eachCount().forEach { (c, k) -> assertTrue(k <= s.cards.count { it == c }, "$c in ${p.name}") }
        }
    }

    @Test
    fun aCardsCopiesUnderTheListCountWhatTheDeckAlreadyHolds() {
        val worth = listOf(w(FxRef.PAWN, "x", -8.0), w(FxRef.KNIGHT, "x", -8.0), w(FxRef.WARD, "x", 9.0))
        // Ward limited to one, and none in the deck: one copy comes in.
        val s = SideChooser.choose(deck, opponents.take(1), worth, listOf(FxRef.WARD), { if (it == FxRef.WARD) 1 else 3 }, isExtra)
        assertEquals(listOf(CardId(FxRef.WARD)), s.plans.single().first.into)
        assertEquals(1, s.plans.single().first.out.size)
    }

    @Test
    fun gamesRateACardByTheGamesItOpenedAgainstThoseItDidNot() {
        var k = 0
        fun g(opening: List<Int>, win: Boolean, against: String = "x") =
            TestGame("g${k++}", k.toLong(), "d", against, against, TestGame.FIRST, result = if (win) TestGame.WIN else TestGame.LOSS, opening = opening)
        val games = List(5) { g(listOf(FxRef.WARD, FxRef.PAWN), win = true) } + List(5) { g(listOf(FxRef.PAWN), win = false) } +
            List(3) { g(listOf(FxRef.VEIL), win = true) } + List(4) { g(listOf(FxRef.WARD), win = false, against = "y") } +
            listOf(TestGame("h", 0L, "d", "x", "x", TestGame.FIRST, result = TestGame.WIN))
        val worth = SideWorth.fromGames(games, "x")
        val ward = worth.single { it.card == FxRef.WARD }
        assertEquals(5, ward.n)
        // Won all 5 with it, 3 of 8 without (the Veil hands): +62.5 points; the games against Rites are not these.
        assertEquals(62.5, ward.points, 1e-9)
        assertTrue(ward.low > 0)
        assertEquals(SideWorth.GAMES, ward.source)
        // Three games with Veil are too few to rate it; Pawn won 5 of 10 against 3 of 3 without it.
        assertTrue(worth.none { it.card == FxRef.VEIL })
        assertEquals(-50.0, worth.single { it.card == FxRef.PAWN }.points, 1e-9)
    }
}
