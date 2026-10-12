package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.ai.eval.TrustWords.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test scores' verdicts (the design review, finding 7, kai's choice (b)): a word per set, against fixed bars. */
class TrustVerdictTest {
    /** A run of [set] with the first [right] items right on the first try (and every try), the rest wrong. */
    private fun run(set: EvalSet, right: Int, tries: Int = 1, every: Int = right, stopped: Boolean = false) = EvalRun(
        set.id, "c", "m", 1, tries,
        set.items.mapIndexed { i, it -> ItemOutcome(it.id, if (i < every) tries else 0, tries, firstPass = i < right) },
        stoppedEarly = stopped,
    )

    @Test
    fun answerSetsAreReadAt95And80() {
        val rulings = EvalSets.rulings() // 30 questions
        assertEquals(30, rulings.items.size)
        assertEquals(Verdict.RELY, TrustWords.verdict(rulings, run(rulings, 30)))
        assertEquals(Verdict.RELY, TrustWords.verdict(rulings, run(rulings, 29)), "29 of 30 is over 95%")
        assertEquals(Verdict.CHECK, TrustWords.verdict(rulings, run(rulings, 28)))
        assertEquals(Verdict.CHECK, TrustWords.verdict(rulings, run(rulings, 24)), "80% exactly")
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(rulings, run(rulings, 23)))
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(rulings, run(rulings, 0)))
        // The same bars for every set of answers that is not a regression set.
        listOf(EvalSets.rulings(), EvalSets.decklists()).forEach { assertEquals(TrustWords.ANSWERS, TrustWords.bars(it)) }
    }

    @Test
    fun theRegressionSetsAreReadApartAt97And90() {
        // Hand odds and card truth (I.6): answered by the app's own tools against keys it computed.
        listOf(EvalSets.handOdds(), EvalSets.cardTruth()).forEach { assertTrue(it.regression, it.id); assertEquals(TrustWords.REGRESSION, TrustWords.bars(it)) }
        listOf(EvalSets.rulings(), EvalSets.decklists(), EvalSets.planted()).forEach { assertTrue(!it.regression, it.id) }
        val odds = EvalSets.handOdds() // 40 questions
        assertEquals(Verdict.RELY, TrustWords.verdict(odds, run(odds, 39)), "97.5%")
        assertEquals(Verdict.CHECK, TrustWords.verdict(odds, run(odds, 38)), "95% is under a regression set's bar")
        assertEquals(Verdict.CHECK, TrustWords.verdict(odds, run(odds, 36)), "90% exactly")
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(odds, run(odds, 35)))
        // A fall from the run before is said; holding or rising is not, and a capability set's fall is not a regression.
        assertTrue(TrustWords.regressed(odds, run(odds, 40), run(odds, 38))!!.startsWith("Fell from 40 to 38 right"))
        assertNull(TrustWords.regressed(odds, run(odds, 38), run(odds, 38)))
        assertNull(TrustWords.regressed(odds, run(odds, 38), run(odds, 40)))
        assertNull(TrustWords.regressed(odds, null, run(odds, 30)))
        assertNull(TrustWords.regressed(odds, run(odds, 40, stopped = true), run(odds, 30)))
        val rulings = EvalSets.rulings()
        assertNull(TrustWords.regressed(rulings, run(rulings, 30), run(rulings, 20)))
    }

    @Test
    fun withSeveralTriesItMustComeOutTheSameAgain() {
        val rulings = EvalSets.rulings()
        assertEquals(Verdict.RELY, TrustWords.verdict(rulings, run(rulings, 30, tries = 3, every = 29)))
        // Right first time on all 30, but right every time on only 26: checked, not relied on.
        assertEquals(Verdict.CHECK, TrustWords.verdict(rulings, run(rulings, 30, tries = 3, every = 26)))
    }

    @Test
    fun aPartOfASetIsNotTheSet() {
        val odds = EvalSets.handOdds()
        assertNull(TrustWords.verdict(odds, run(odds, 40, stopped = true)))
        assertNull(TrustWords.verdict(odds, EvalRun(odds.id, "c")))
    }

    @Test
    fun theCheckerIsReadOnMistakesCaughtAndHeldBackByFalseAlarms() {
        val planted = EvalSets.planted() // 12 planted, 12 clean
        val mistakes = planted.items.filter { (it.grader as Grader.Planted).hasError }.map { it.id }
        fun checked(caught: Int, alarms: Int): EvalRun {
            var c = 0
            var a = 0
            return EvalRun(planted.id, "c", items = planted.items.map {
                val pass = if (it.id in mistakes) c++ < caught else a++ >= alarms
                ItemOutcome(it.id, if (pass) 1 else 0, 1, firstPass = pass)
            })
        }
        assertEquals(TrustWords.Checker(11, 12, 1, 12), TrustWords.checker(planted, checked(11, 1)))
        assertEquals(Verdict.RELY, TrustWords.verdict(planted, checked(12, 0)))
        assertEquals(Verdict.RELY, TrustWords.verdict(planted, checked(11, 1)), "11 of 12 is over 90%; one alarm in 12 is not more than one in 10")
        assertEquals(Verdict.CHECK, TrustWords.verdict(planted, checked(12, 2)), "two alarms in 12 clean answers: it cries wolf too often to rely on")
        assertEquals(Verdict.CHECK, TrustWords.verdict(planted, checked(9, 0)), "9 of 12 is 75%")
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(planted, checked(8, 0)))
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(planted, checked(12, 4)), "more than one alarm in four")
        assertEquals(TrustWords.CHECKER, TrustWords.bars(planted))
    }

    @Test
    fun puzzlesAreReadAt90And60AndNeverAtTheGreedyBaseline() {
        val puzzles = EvalSets.byId(EvalSets.PUZZLES)!!
        val n = puzzles.items.size
        assertEquals(TrustWords.PUZZLES, TrustWords.bars(puzzles))
        assertEquals(Verdict.RELY, TrustWords.verdict(puzzles, run(puzzles, n)))
        assertEquals(Verdict.CHECK, TrustWords.verdict(puzzles, run(puzzles, (n * 0.6).toInt() + 1)))
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(puzzles, run(puzzles, 2)))
        // Even at a share over the lower bar, a score no better than only attacking is "Do it yourself".
        assertEquals(Verdict.YOURSELF, TrustWords.verdict(puzzles, run(puzzles, n), greedy = n))
    }

    @Test
    fun theBarsAreSaidInWords() {
        assertEquals(
            "Rely on it at 95% and over, check it from 80%, under 80% do it yourself — right first time (with several tries, right every time too).",
            TrustWords.barsWords(EvalSets.rulings()),
        )
        assertTrue(TrustWords.barsWords(EvalSets.handOdds()).startsWith("Rely on it at 97% and over, check it from 90%"))
        assertTrue("regression set" in TrustWords.barsWords(EvalSets.cardTruth()))
        assertTrue(TrustWords.barsWords(EvalSets.planted()).startsWith("Rely on it at 90% and over, check it from 70%"))
        assertTrue("only attacking" in TrustWords.barsWords(EvalSets.byId(EvalSets.PUZZLES)!!))
        assertEquals(listOf("Rely on it", "Check it", "Do it yourself"), Verdict.entries.map { it.words })
    }
}
