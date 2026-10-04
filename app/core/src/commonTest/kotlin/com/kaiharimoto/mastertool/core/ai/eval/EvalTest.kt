package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.ai.check.FactCheck
import com.kaiharimoto.mastertool.core.cards.BanlistFixture
import com.kaiharimoto.mastertool.core.cards.LimitationParser
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Format
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Phase A (1.0.99): the keys are right, and the graders read answers as a person would. */
class EvalTest {
    @Test
    fun theSetsAreTheSizesThePlanSays() {
        assertEquals(40, EvalSets.handOdds().items.size)
        assertEquals(30, EvalSets.rulings().items.size)
        assertEquals(20, EvalSets.decklists().items.size)
        assertEquals(24, EvalSets.planted().items.size)
        assertEquals(32, EvalSets.cardTruth().items.size)
        assertEquals(5, EvalSets.all.size)
        assertEquals(12, EvalSets.planted().items.count { (it.grader as Grader.Planted).hasError })
        val ids = EvalSets.all.flatMap { s -> s.items.map { it.id } }
        assertEquals(ids.size, ids.toSet().size, "every item has its own id")
    }

    @Test
    fun cardTruthCoversEveryKindWithBothAnswersAndASourceEach() {
        val set = EvalSets.cardTruth()
        assertEquals(32, set.items.size)
        assertEquals(set, EvalSets.byId(EvalSets.CARD_TRUTH), "in the list Trust shows")
        assertFalse(set.checker)
        val kinds = set.items.groupBy { it.id.substringBefore('-') }
        assertEquals(mapOf("ban" to 14, "release" to 8, "copies" to 5, "genesys" to 5), kinds.mapValues { it.value.size })
        for (kind in listOf("ban", "release", "copies")) {
            val answers = kinds.getValue(kind).map { (it.grader as Grader.YesNo).expected }.toSet()
            assertEquals(setOf(true, false), answers, "$kind has both yes and no answers")
        }
        kinds.getValue("genesys").forEach { assertTrue(it.grader is Grader.Number, it.id) }
        set.items.forEach { item ->
            assertTrue(item.source.isNotBlank(), "${item.id} names its source")
            assertTrue("ANSWER:" in item.prompt, "${item.id} says the form of its last line")
        }
        // A banlist question states its region and its day, and cites the list page it was read from.
        kinds.getValue("ban").forEach { item ->
            assertTrue("TCG" in item.prompt || "OCG" in item.prompt, item.id)
            assertTrue(Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(item.prompt), item.id)
            assertTrue("Yugipedia" in item.source && "Lists" in item.source, item.id)
        }
        // Both regions are asked about.
        assertTrue(kinds.getValue("ban").any { "OCG" in it.prompt } && kinds.getValue("ban").any { "TCG" in it.prompt })
    }

    @Test
    fun aCardTruthKeyAgreesWithACapturedList() {
        // copies-05: 83764719 and 83764718 are Monster Reborn, Limited on the TCG list in force on 2026-10-01.
        val list = assertNotNull(LimitationParser.parse("September 2026 Lists (TCG)", BanlistFixture.SEPTEMBER_2026_TCG, Format.TCG))
        assertTrue(list.start <= "2026-10-01" && (list.end == null || "2026-10-01" <= list.end!!), "${list.start}..${list.end}")
        assertEquals(BanStatus.LIMITED, list.statusOf("Monster Reborn"))
        val item = EvalSets.cardTruth().items.first { it.id == "copies-05" }
        assertEquals(Grader.YesNo(false), item.grader, "two copies of a Limited card")
        assertTrue("September 2026 Lists (TCG)" in item.source)
    }

    @Test
    fun aWholeNumberIsGradedExactly() {
        val g = Grader.Number(50)
        assertTrue(Grading.grade(g, "Maxx \"C\" costs 50 points.\nANSWER: 50").pass)
        assertTrue(Grading.grade(g, "ANSWER: 50 points of the 100 allowed").pass, "the first number on the line")
        assertTrue(Grading.grade(g, "ANSWER: 50.0").pass)
        assertFalse(Grading.grade(g, "ANSWER: 50.5").pass)
        assertFalse(Grading.grade(g, "ANSWER: 30").pass)
        assertFalse(Grading.grade(g, "ANSWER: 500").pass)
        assertFalse(Grading.grade(g, "ANSWER: fifty").pass, "a number, written as one")
        assertTrue(Grading.grade(g, "It costs 50").pass, "no ANSWER line: the answer's last number")
        assertEquals("30 (expected 50)", Grading.grade(g, "ANSWER: 30").read)
        // A key read off the set is graded the same way.
        val maxx = EvalSets.cardTruth().items.first { it.id == "genesys-01" }
        assertTrue(Grading.grade(maxx.grader, "ANSWER: 50").pass)
        assertTrue(Grading.grade(EvalSets.cardTruth().items.first { it.id == "ban-01" }.grader, "Forbidden since October 2005.\nANSWER: yes").pass)
        assertFalse(Grading.grade(EvalSets.cardTruth().items.first { it.id == "copies-01" }.grader, "Two and two.\nANSWER: yes").pass)
    }

    @Test
    fun aNumberGraderSurvivesTheWire() {
        // Graders are serialised with their items (a set could be stored or sent); the new kind round-trips beside the old.
        val json = Json
        val items = listOf(EvalItem("a", "q", Grader.Number(7), "s"), EvalItem("b", "q", Grader.YesNo(true)))
        val back = json.decodeFromString(ListSerializer(EvalItem.serializer()), json.encodeToString(ListSerializer(EvalItem.serializer()), items))
        assertEquals(items, back)
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
