package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.ai.check.FactCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase A (1.0.99): the keys are right, and the graders read answers as a person would. */
class EvalTest {
    @Test
    fun theSetsAreTheSizesThePlanSays() {
        assertEquals(40, EvalSets.handOdds().items.size)
        assertEquals(30, EvalSets.rulings().items.size)
        assertEquals(20, EvalSets.decklists().items.size)
        assertEquals(24, EvalSets.planted().items.size)
        assertEquals(12, EvalSets.planted().items.count { (it.grader as Grader.Planted).hasError })
        val ids = EvalSets.all.flatMap { s -> s.items.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "every item has its own id")
    }

    @Test
    fun aHandOddsKeyAgreesWithPlainArithmetic() {
        // odds-01: 40 cards, 3 starters, 5 cards: 1 − C(37,5)/C(40,5).
        val first = EvalSets.handOdds().items.first()
        val key = (first.grader as Grader.Percent).expected
        assertEquals(1 - Calc.choose(37.0, 5.0) / Calc.choose(40.0, 5.0), key, 1e-12)
        assertTrue("40-card" in first.prompt && "3 starters" in first.prompt && "going first" in first.prompt, first.prompt)
        // Every key is a probability.
        EvalSets.handOdds().items.forEach { assertTrue((it.grader as Grader.Percent).expected in 0.0..1.0, it.id) }
    }

    @Test
    fun aPercentageIsGradedAtThePrecisionAsked() {
        val g = Grader.Percent(0.7418, 1)
        assertTrue(Grading.grade(g, "Working… so about three in four.\nANSWER: 74.2%").pass)
        assertTrue(Grading.grade(g, "ANSWER: 74.18%").pass, "more places, same answer")
        assertFalse(Grading.grade(g, "ANSWER: 74.5%").pass)
        assertFalse(Grading.grade(g, "ANSWER: 75%").pass)
        assertTrue(Grading.grade(g, "It comes to 74.2%.").pass, "no ANSWER line: the answer's last percentage")
        assertFalse(Grading.grade(g, "I can't work it out.").pass)
    }

    @Test
    fun aRulingIsGradedOnItsYesOrNo() {
        val g = Grader.YesNo(false)
        assertTrue(Grading.grade(g, "Discarding is a cost, but timing… \nANSWER: No").pass)
        assertFalse(Grading.grade(g, "ANSWER: yes").pass)
        assertFalse(Grading.grade(g, "ANSWER: it depends").pass)
    }

    @Test
    fun aDecklistIsGradedCardByCard() {
        val g = Grader.Decklist(mapOf("Ash Blossom & Joyous Spring" to 3, "Maxx \"C\"" to 1))
        assertTrue(Grading.grade(g, "Here it is.\nANSWER:\n3 Ash Blossom & Joyous Spring\n1 Maxx \"C\"").pass)
        assertTrue(Grading.grade(g, "ANSWER:\n- Ash Blossom & Joyous Spring x3\n- Maxx C x1").pass, "count after, quotes dropped")
        assertFalse(Grading.grade(g, "ANSWER:\n3 Ash Blossom\n1 Maxx \"C\"").pass, "a name not written out")
        assertFalse(Grading.grade(g, "ANSWER:\n2 Ash Blossom & Joyous Spring\n1 Maxx \"C\"").pass, "a count wrong")
        assertFalse(Grading.grade(g, "ANSWER:\n3 Ash Blossom & Joyous Spring\n1 Maxx \"C\"\n1 Effect Veiler").pass, "a card added")
    }

    @Test
    fun theCheckerIsGradedOnWhatItCaughtAndWhatItLeftAlone() {
        val planted = Grader.Planted(true, listOf("2000", "def"))
        val caught = listOf(FactCheck.Claim("Ash Blossom has 2000 DEF", FactCheck.Verdict.WRONG, "it has 1800 DEF"))
        assertTrue(Grading.planted(planted, caught).pass)
        assertFalse(Grading.planted(planted, listOf(FactCheck.Claim("Ash is Level 3", FactCheck.Verdict.OK))).pass)
        val clean = Grader.Planted(false)
        assertTrue(Grading.planted(clean, listOf(FactCheck.Claim("Ash is Level 3", FactCheck.Verdict.OK), FactCheck.Claim("x", FactCheck.Verdict.UNSURE))).pass)
        assertFalse(Grading.planted(clean, listOf(FactCheck.Claim("Ash is Level 3", FactCheck.Verdict.WRONG))).pass, "a false alarm")
    }

    @Test
    fun aRunIsScoredAndKeptPerConnection() {
        val run = EvalRun(
            "hand-odds", "c1", "model", at = 5, tries = 3,
            items = listOf(
                ItemOutcome("a", passes = 3, tries = 3, firstPass = true),
                ItemOutcome("b", passes = 2, tries = 3, firstPass = true),
                ItemOutcome("c", passes = 0, tries = 3, firstPass = false),
                ItemOutcome("d", passes = 1, tries = 3, firstPass = false),
            ),
        )
        assertEquals(0.5, run.passAt1, 1e-9)
        assertEquals(0.25, run.passAll, 1e-9, "only a passed every try")
        val log = EvalLog.read(EvalLog.write(listOf(run, run.copy(at = 9, set = "rulings"), run.copy(at = 1))))
        assertEquals(3, log.size)
        assertEquals(5L, EvalLog.latest(log)["hand-odds"]!!.at, "each set's newest")
        assertEquals("evals/c1.json", EvalLog.path("c1"))
    }
}
