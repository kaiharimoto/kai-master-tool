package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.StepState
import com.kaiharimoto.mastertool.core.shootout.teach.TeachAction
import com.kaiharimoto.mastertool.core.shootout.teach.TeachGate
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.mastertool.core.shootout.teach.TeachSteps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shootout's teaching, offered when it can be used (kai's choices 9 and 10, 1.1.8): the controls hidden until the person
 * has judged [TeachGate.HANDS] hands for the deck, unless Ai was taught already; and teaching as four numbered steps,
 * each with its state, read from the store alone.
 */
class ShootoutTeachStepsTest {

    private val pool: Map<Int, Card> = buildMap {
        for (i in 1..20) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect"))
        put(2001, Card(CardId(2001), "Their Ash", "Effect Monster", "effect", description = "Quick Effect: You can discard this card; negate that effect."))
        for (i in 2..20) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
    }
    private val mine = Deck(main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId))
    private val theirs = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))
    private val groups = DeckGroups(
        groups = listOf(DeckGroup("g-start", "Starters", 3, 0)),
        assignments = mapOf(CardId(1001) to "g-start", CardId(1002) to "g-start"),
    )
    private val bench = Bench.of(BenchInput(mine, { pool[it.value] }, groups, opponent = Opponent("o", "Yubel", theirs)))
    private val theirHand = listOf(2001, 2002, 2003, 2004, 2005, 2006)
    private val kind = "first·starter·interaction"

    private fun person(id: String, at: Long, answer: Answer = Answer.LEAN_WIN, mode: String? = null, sawAi: Boolean = false, session: String? = null) =
        StoredTrial(id, at = at, stratum = "G1_FIRST", hand = listOf(1001, 1003, 1004, 1005, 1006), opponent = theirHand, answer = answer.name, mode = mode, sawAi = sawAi, session = session)

    private fun ai(of: String?, at: Long, answer: Answer = Answer.LEAN_WIN, mode: String, sure: Double = 0.9, print: String = bench.print) =
        StoredTrial(
            "a-$of-$at", at = at + 1, stratum = "G1_FIRST", hand = listOf(1001, 1003, 1004, 1005, 1006), opponent = theirHand, answer = answer.name,
            judge = StoredTrial.AI, of = of, mode = mode,
            ai = AiVerdict(answer = answer.name, sure = sure, asked = at - 1, kind = kind, print = print),
        )

    private fun log(trials: List<StoredTrial>, notes: List<TrialNote> = emptyList(), trust: TrustSettings? = null) =
        ShootoutLog(deck = "d", opponent = "o", trials = trials, notes = notes, trust = trust)

    private fun steps(l: ShootoutLog) = TeachSteps.of(bench, l, "Ai")

    // ---- the gate: when teaching is offered --------------------------------------------------------------------------

    @Test
    fun theControlsAppearAtThirtyHandsJudgedForTheDeck() {
        assertEquals(30, TeachGate.HANDS, "about one session: kai's choice")
        val few = TeachGate.tally(log((0 until 29).map { person("p$it", it.toLong()) }))
        assertEquals(29, few.hands)
        assertFalse(TeachGate.shown(few))
        assertEquals(1, TeachGate.toGo(few))
        assertTrue(TeachGate.shown(TeachGate.tally(log((0 until 30).map { person("p$it", it.toLong()) }))))
        // Per deck: hands judged alone and against an opponent add up.
        val alone = TeachGate.tally(ShootoutLog(deck = "d", trials = (0 until 20).map { person("a$it", it.toLong()) }))
        assertTrue(TeachGate.shown(alone + few), "20 alone and 29 against Yubel are 49 hands of the deck")
        // Ai's own answers are not the person's hands.
        val aiOnly = TeachGate.tally(log((0 until 40).map { ai(null, it.toLong(), mode = TeachModes.SOLO) }))
        assertEquals(0, aiOnly.hands)
    }

    @Test
    fun someoneWhoHasTaughtAiAlwaysSeesThem() {
        assertFalse(TeachGate.taught(log(listOf(person("p", 1)))))
        assertFalse(TeachGate.taught(log(listOf(person("p", 1)), notes = listOf(TrialNote("p", "a note of mine")))), "a note alone is not teaching")
        assertTrue(TeachGate.taught(log(listOf(person("p", 1), ai("p", 1, mode = TeachModes.APPRENTICE)))), "an answer of Ai's")
        assertTrue(TeachGate.taught(log(listOf(person("p", 1, mode = TeachModes.CALIBRATION, session = "cal")))), "a calibration set begun")
        assertTrue(TeachGate.taught(log(listOf(person("p", 1).copy(ai = AiVerdict(answer = "COIN_FLIP"))))), "a 1.1.2 verdict on the person's trial")
        assertTrue(TeachGate.taught(log(emptyList(), trust = TrustSettings(bar = 0.85))), "the gate's settings chosen")
        val taught = TeachGate.tally(log(listOf(ai(null, 1, mode = TeachModes.SOLO))))
        assertTrue(TeachGate.shown(taught), "with two hands judged")
        assertTrue(TeachGate.shown(TeachGate.Tally(hands = 2), rubric = true), "a rubric kept")
    }

    @Test
    fun theLineUnderSetupSaysWhenTeachingComes() {
        assertEquals("After your first session you can teach Ai to judge with you.", TeachGate.line(TeachGate.Tally(), "Ai"))
        assertEquals("12 hands more and you can teach Mirai to judge with you.", TeachGate.line(TeachGate.Tally(hands = 18), "Mirai"))
        assertEquals("1 hand more and you can teach Ai to judge with you.", TeachGate.line(TeachGate.Tally(hands = 29), "Ai"))
    }

    // ---- the steps ---------------------------------------------------------------------------------------------------

    @Test
    fun aFreshMatchupHasEveryStepNotStartedAndTheSetNext() {
        val p = steps(log(emptyList()))
        assertEquals(listOf(1, 2, 3, 4), p.steps.map { it.number })
        assertEquals(listOf("Calibration set", "Apprentice", "Supervised", "Ai judges alone"), p.steps.map { it.name })
        assertTrue(p.steps.all { it.state == StepState.NOT_STARTED })
        assertEquals(listOf("Not started", "Not started", "Not started", "Not yet"), p.steps.map { it.status })
        assertEquals(1, p.next?.number)
        assertEquals(TeachAction.CALIBRATION, p.next?.action)
        assertEquals("Begin the calibration set", p.next?.label)
        // With nothing earned, step 4's way forward is more apprentice hands.
        assertEquals(TeachAction.APPRENTICE, p.step(4).action)
        assertTrue(p.step(4).words.startsWith("No kind of hand earned yet"), p.step(4).words)
    }

    @Test
    fun aCalibrationSetIsInProgressUntilAiHasSatItsExam() {
        val set = (0 until 32).map { person("c$it", 10L * it, mode = TeachModes.CALIBRATION, session = "cal1") }
        val judged = steps(log(set)).step(1)
        assertEquals(StepState.IN_PROGRESS, judged.state)
        assertEquals("32 hands judged · Ai has not sat its exam", judged.words)
        assertEquals(TeachAction.EXAM, judged.action)
        assertEquals("Start the exam", judged.label)

        val half = set + (0 until 13).map { ai("c$it", 10L * it + 1_000, mode = TeachModes.EXAM) }
        assertEquals("32 hands judged · Ai's exam: 13 of 32", steps(log(half)).step(1).words)
        assertEquals("Finish the exam", steps(log(half)).step(1).label)

        // Whole: done, its agreement within one step counted.
        val whole = set + (0 until 32).map { ai("c$it", 10L * it + 1_000, if (it < 30) Answer.CLEAR_WIN else Answer.CLEAR_LOSS, mode = TeachModes.EXAM) }
        val done = steps(log(whole))
        assertEquals(StepState.DONE, done.step(1).state)
        assertEquals("Ai agreed 30 of 32 in its exam", done.step(1).words)
        assertEquals("Done", done.step(1).status)
        assertEquals(2, done.next?.number, "apprentice is next")
        assertEquals("Begin an apprentice session", done.next?.label)
    }

    @Test
    fun aDeckChangeSinceTheExamAsksForAShortSet() {
        val set = (0 until 24).map { person("c$it", 10L * it, mode = TeachModes.CALIBRATION, session = "cal1") }
        val exam = (0 until 24).map { ai("c$it", 10L * it + 1_000, mode = TeachModes.EXAM, print = "older-decks") }
        val s = steps(log(set + exam)).step(1)
        assertEquals(StepState.IN_PROGRESS, s.state)
        assertTrue(s.words.contains("decks changed"), s.words)
        assertEquals(TeachAction.CALIBRATION, s.action)
        // A newer set after the change, examined, is done again.
        val short = (0 until 6).map { person("s$it", 5_000L + it, mode = TeachModes.CALIBRATION, session = "cal2") }
        val shortExam = (0 until 6).map { ai("s$it", 6_000L + it, mode = TeachModes.EXAM) }
        assertEquals(StepState.DONE, steps(log(set + exam + short + shortExam)).step(1).state)
    }

    @Test
    fun apprenticeAndSupervisedCountTheirHands() {
        val app = (0 until 12).map { person("p$it", 10L * it, mode = TeachModes.APPRENTICE) }
        val notes = listOf(TrialNote("p1", "Imperm on the starter", question = "Why a loss?"), TrialNote("p2", "my own"))
        val p = steps(log(app, notes))
        assertEquals(StepState.IN_PROGRESS, p.step(2).state)
        assertEquals("12 of ${TeachSteps.APPRENTICE_HANDS} hands · 1 question asked", p.step(2).words)
        val enough = (0 until TeachSteps.APPRENTICE_HANDS).map { person("p$it", 10L * it, mode = TeachModes.APPRENTICE) }
        assertEquals(StepState.DONE, steps(log(enough)).step(2).state)
        // Seen answers are supervised's, never apprentice's.
        val sup = (0 until 6).flatMap { i ->
            listOf(person("u$i", 1_000L + 10 * i, if (i < 4) Answer.LEAN_WIN else Answer.CLEAR_LOSS, mode = TeachModes.SUPERVISED, sawAi = true), ai("u$i", 1_000L + 10 * i - 5, mode = TeachModes.SUPERVISED))
        }
        val q = steps(log(app + sup))
        assertEquals("12 of ${TeachSteps.APPRENTICE_HANDS} hands · no questions yet", q.step(2).words)
        assertEquals(StepState.IN_PROGRESS, q.step(3).state)
        assertEquals("6 of ${TeachSteps.SUPERVISED_HANDS} hands · you took its answer on 4", q.step(3).words)
        assertEquals(TeachAction.SUPERVISED, q.step(3).action)
    }

    /** [n] held-out apprentice pairs that agree, enough to open the kind they are of. */
    private fun earned(n: Int = 40) = (0 until n).flatMap { i ->
        val at = 100_000L + 10L * (i + 1)
        listOf(person("e$i", at, mode = TeachModes.APPRENTICE), ai("e$i", at - 1, Answer.LEAN_WIN, mode = TeachModes.APPRENTICE))
    }

    @Test
    fun aloneSaysWhetherTheGateIsOpenForAnyKind() {
        val off = steps(log(earned())).step(4)
        assertEquals(StepState.IN_PROGRESS, off.state)
        assertEquals("1 of 8 kinds of hand earned · judging alone is off", off.words)
        assertEquals("Off", off.status)
        assertEquals(TeachAction.TRUST, off.action)
        assertEquals("Let Ai judge alone", off.label)
        val on = steps(log(earned() + listOf(ai(null, 900_000, mode = TeachModes.SOLO)), trust = TrustSettings(solo = true))).step(4)
        assertEquals(StepState.DONE, on.state)
        assertEquals("On", on.status)
        assertEquals("1 of 8 kinds of hand · 1 hand judged alone", on.words)
        val allowedNone = steps(log(emptyList(), trust = TrustSettings(solo = true))).step(4)
        assertEquals(StepState.IN_PROGRESS, allowedNone.state)
        assertEquals("Allowed", allowedNone.status)
        assertTrue(allowedNone.words.startsWith("No kind of hand is earned now"), allowedNone.words)
    }

    @Test
    fun theNextStepIsTheFirstNotDone() {
        val set = (0 until 32).flatMap { listOf(person("c$it", 10L * it, mode = TeachModes.CALIBRATION, session = "cal1"), ai("c$it", 10L * it + 1, mode = TeachModes.EXAM)) }
        val sup = (0 until TeachSteps.SUPERVISED_HANDS).map { person("u$it", 50_000L + it, mode = TeachModes.SUPERVISED, sawAi = true) }
        // Calibration done, apprentice and supervised done, Ai earned a kind but is not allowed: the trust panel is next.
        val p = steps(log(set + sup + earned()))
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.IN_PROGRESS), p.steps.map { it.state })
        assertEquals(4, p.next?.number)
        assertEquals(TeachAction.TRUST, p.next?.action)
        // Everything done: no next step, and the page's primary is a session again.
        assertNull(steps(log(set + sup + earned(), trust = TrustSettings(solo = true))).next)
    }
}
