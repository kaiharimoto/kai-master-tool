package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Fatigue
import com.kaiharimoto.mastertool.core.shootout.bench.ReasonCounts
import com.kaiharimoto.mastertool.core.shootout.bench.Reasons
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.Surprise
import com.kaiharimoto.mastertool.core.shootout.bench.TrialMarks
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.Prediction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase S, stage 4, S4.1 (`docs/phases/S.md` §8): why a hand went as it did, in the person's words — the reasons and the
 * deciding card, the surprise question, fatigue, and the reasons counted — and never a rating's input.
 */
class ShootoutReasonsTest {

    private val pool: Map<Int, Card> = (1..40).associate { (1000 + it) to Card(CardId(1000 + it), "Card $it", "Effect Monster", "effect") }
    private val mine = Deck(main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId))
    private val bench = Bench.of(BenchInput(mine, { pool[it.value] }))

    private fun rate(id: String, answer: Answer, hand: List<Int> = listOf(1001, 1002, 1003, 1004, 1005), tags: List<String> = emptyList(), decisive: Int? = null, stratum: Stratum = Stratum.ALONE_FIRST) =
        StoredTrial(id, at = 1, stratum = stratum.name, hand = hand, answer = answer.name, tags = tags, decisive = decisive)

    @Test
    fun theTagsAreKnownOnesInOrderAndTheDeckAloneHasOnlyBricked() {
        assertEquals(listOf(Reasons.BRICKED, Reasons.THEY_BRICKED), Reasons.clean(listOf("opponent-bricked", "made up", "bricked", "bricked")))
        assertEquals(listOf(Reasons.BRICKED), Reasons.toggled(emptyList(), Reasons.BRICKED))
        assertEquals(emptyList(), Reasons.toggled(listOf(Reasons.BRICKED), Reasons.BRICKED))
        assertEquals(listOf(Reasons.BRICKED), Reasons.tagsFor(alone = true))
        assertEquals(Reasons.TAGS, Reasons.tagsFor(alone = false))
        assertEquals("They bricked", Reasons.words(Reasons.THEY_BRICKED))
    }

    @Test
    fun aReasonAfterTheAnswerChangesItsReasonsAndNeverItsAnswer() {
        val ai = rate("a~ai", Answer.CLEAR_WIN).copy(judge = StoredTrial.AI, of = "a")
        val log = ShootoutLog(deck = "me", trials = listOf(rate("a", Answer.CLEAR_LOSS), ai))
        val next = assertNotNull(log.marked("a", listOf(Reasons.INTERRUPTED, "nonsense"), decisive = 1003)).trials.first()
        assertEquals(listOf(Reasons.INTERRUPTED), next.tags)
        assertEquals(1003, next.decisive)
        assertEquals(Answer.CLEAR_LOSS.name, next.answer, "the answer stands as given")
        assertNull(next.adjusted)
        // A card the hand does not hold is no deciding card; Ai's answers take no reasons; nothing to change is no change.
        assertNull(assertNotNull(log.marked("a", emptyList(), decisive = 1039)).trials.first().decisive)
        assertNull(log.marked("a~ai", listOf(Reasons.BRICKED), null))
        assertNull(log.marked("nope", listOf(Reasons.BRICKED), null))
        assertSame(log, log.marked("a", emptyList(), null))
        // Kept on disk as given.
        val read = assertNotNull(ShootoutCodec.decode(ShootoutCodec.encode(log.marked("a", listOf(Reasons.BRICKED), 1002)!!)))
        assertEquals(listOf(Reasons.BRICKED), read.trials.first().tags)
        assertEquals(1002, read.trials.first().decisive)
    }

    @Test
    fun theSurpriseIsAnAnswerTheModelGaveLittleChanceHoweverItsDoubtFalls() {
        val sure = Prediction(win = 0.85, sd = 0.3, likeliest = Answer.CLEAR_WIN, chances = listOf(0.02, 0.05, 0.13, 0.3, 0.5), prefersLeft = null)
        val doubt = sure.copyWith(listOf(0.06, 0.07, 0.17, 0.3, 0.4))
        assertEquals(0.02, Surprise.of(listOf(sure), Answer.CLEAR_LOSS.name, answers = 40))
        assertEquals(0.06, Surprise.of(listOf(sure, doubt), Answer.CLEAR_LOSS.name, answers = 40), "the largest chance of the readings is kept")
        assertEquals(0.07, Surprise.of(listOf(sure, doubt), Answer.LEAN_LOSS.name, answers = 40), "under 8% in every reading")
        val wider = sure.copyWith(listOf(0.09, 0.07, 0.17, 0.3, 0.37))
        assertNull(Surprise.of(listOf(sure, wider), Answer.CLEAR_LOSS.name, answers = 40), "9% in one reading of the hand: not a surprise")
        assertNull(Surprise.of(listOf(sure), Answer.COIN_FLIP.name, answers = 40), "13% is not a surprise")
        assertNull(Surprise.of(listOf(sure), Answer.CLEAR_LOSS.name, answers = Surprise.MIN_ANSWERS - 1), "too early to be surprised")
        assertNull(Surprise.of(listOf(Prediction(0.85, 2.0, Answer.CLEAR_WIN, sure.chances, null)), Answer.CLEAR_LOSS.name, 40), "a hand it knows nothing of")
        // A comparison: the hand the model gave little chance of being preferred.
        val left = Prediction(win = 0.95, sd = 0.2, likeliest = null, chances = emptyList(), prefersLeft = true)
        assertNotNull(Surprise.of(listOf(left), StoredTrial.RIGHT, 40))
        assertNull(Surprise.of(listOf(left), StoredTrial.LEFT, 40))
        // At most one question in five answers.
        assertTrue(Surprise.mayAsk(null))
        assertFalse(Surprise.mayAsk(Surprise.EVERY - 1))
        assertTrue(Surprise.mayAsk(Surprise.EVERY))
        assertEquals("Your answer surprised the model: it gave it 2%. Why?", Surprise.words(0.02))
        assertEquals("Your answer surprised the model: it gave it under 1%. Why?", Surprise.words(0.001))
    }

    private fun Prediction.copyWith(chances: List<Double>) = Prediction(win, sd, likeliest, chances, prefersLeft)

    @Test
    fun aRunKeepsTheMarksAndTheSurpriseAndTheMarksMoveNoRating() {
        fun answers(marked: Boolean): ShootoutRun {
            val run = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_FIRST, seed = 7)
            var i = 0
            while (run.log.trials.count { it.kind == StoredTrial.RATE } < 60) {
                when (val p = run.next()) {
                    is Proposal.Rate -> {
                        val good = 1001 in bench.ids(p.hand)
                        val marks = if (marked && !good) TrialMarks(listOf(Reasons.BRICKED), bench.ids(p.hand).first()) else TrialMarks.NONE
                        run.answer(p, if (good) Answer.CLEAR_WIN else Answer.CLEAR_LOSS, "s-$i", at = 1_000L + i, marks = marks)
                    }
                    is Proposal.Compare -> run.prefer(p, 1001 in bench.ids(p.left), "s-$i", at = 1_000L + i)
                }
                i++
            }
            return run
        }
        val plain = answers(marked = false)
        val marked = answers(marked = true)
        // The same answers with reasons and without: the same ratings to the last digit.
        val worth = { r: ShootoutResults -> r.cards.map { it.card to it.cells.getValue(Stratum.ALONE_FIRST).estimate.value } }
        assertEquals(worth(plain.results()), worth(marked.results()))
        val bricked = marked.log.trials.filter { Reasons.BRICKED in it.tags }
        assertTrue(bricked.isNotEmpty())
        assertTrue(bricked.all { t -> t.decisive != null && t.decisive in t.hand })
        // The answers so far follow one rule, so the model is sure: going against it surprises it, following it does not.
        val p = Proposal.Rate(bench.hand(listOf(1001, 1002, 1003, 1004, 1005))!!, null, Stratum.ALONE_FIRST, Reason.CHOSEN)
        val against = marked.answer(p, Answer.CLEAR_LOSS, "against", at = 9_000)
        assertNotNull(against.surprised, "a clear loss on the hand that always won")
        assertTrue(against.surprised!! < Surprise.CHANCE)
        val q = Proposal.Rate(bench.hand(listOf(1001, 1006, 1007, 1008, 1009))!!, null, Stratum.ALONE_FIRST, Reason.CHOSEN)
        assertNull(marked.answer(q, Answer.CLEAR_WIN, "with", at = 9_001).surprised)
        // The "why?" afterwards goes on the trial, and the fit is not read again for it.
        val fitted = marked.fitted
        marked.mark("against", listOf(Reasons.INTERRUPTED), bench.ids(p.hand).last())
        assertEquals(listOf(Reasons.INTERRUPTED), marked.log.trials.first { it.id == "against" }.tags)
        assertEquals(fitted, marked.fitted)
    }

    @Test
    fun anHonestNoisyJudgeIsRarelyAsked() {
        // The stage-1 judge's noise (S.md §4½: a hand that wins 60% read anywhere from 34% to 82% two times in three) and
        // 3% slips, over 200 answers: the question must stay rare, or it slows every session it should only explain.
        val random = kotlin.random.Random(11)
        val worth = (1001..1020).associateWith { c -> if (c == 1001) 1.2 else (random.nextDouble() - 0.5) * 1.2 }
        fun read(ids: List<Int>): Double = -0.4 + ids.sumOf { worth[it] ?: 0.0 } * 0.6 + 0.6 * kotlin.math.ln(random.nextDouble().let { u -> u / (1 - u) })
        val run = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_FIRST, seed = 5)
        repeat(200) { i ->
            val slip = random.nextDouble() < 0.03
            when (val p = run.next()) {
                is Proposal.Rate -> {
                    val band = ModelSpec.NOMINAL_CUTS.count { read(bench.ids(p.hand)) > it }
                    run.answer(p, Answer.entries[if (slip) random.nextInt(5) else band], "s-$i", at = 1_000L + i)
                }
                is Proposal.Compare -> run.prefer(p, if (slip) random.nextBoolean() else read(bench.ids(p.left)) > read(bench.ids(p.right)), "s-$i", at = 1_000L + i)
            }
        }
        val asked = run.log.trials.count { it.surprised != null }
        assertTrue(asked <= 20, "the model was surprised by $asked of 200 honest answers")
    }

    @Test
    fun fatigueIsSlowingRushingOrSurprisesPilingUpAndAPauseIsNone() {
        val steady = List(30) { 4_000L + (it % 3) * 300 }
        val none = List(30) { false }
        assertNull(Fatigue.read(steady, none))
        assertNull(Fatigue.read(steady.take(Fatigue.MIN_ANSWERS - 1), none.take(Fatigue.MIN_ANSWERS - 1)), "too few answers")
        val slowing = List(15) { 4_000L } + List(15) { 7_500L }
        assertEquals(Fatigue.Kind.SLOWED, Fatigue.read(slowing, none)?.kind)
        assertTrue("slowed by 88 %" in Fatigue.read(slowing, none)!!.words, Fatigue.read(slowing, none)!!.words)
        val rushing = List(15) { 6_000L } + List(15) { 2_000L }
        assertEquals(Fatigue.Kind.RUSHED, Fatigue.read(rushing, none)?.kind)
        // A pause — the person stepped away — is not a slow answer.
        val paused = steady.toMutableList().also { it[25] = 15 * 60_000L; it[26] = 20 * 60_000L }
        assertNull(Fatigue.read(paused, none))
        // Surprises piling up at the end, against a quiet start.
        val drifting = List(18) { false } + List(12) { it % 3 == 0 }
        assertEquals(Fatigue.Kind.DRIFTING, Fatigue.read(steady, drifting)?.kind)
        // As many surprises early as late is not drift.
        val even = List(30) { it % 4 == 0 }
        assertNull(Fatigue.read(steady, even))
        // Times not kept are left out, never read as zero.
        assertNull(Fatigue.read(List(30) { null }, none))
    }

    @Test
    fun theReasonsAreCountedAndEveryCountOpensItsHands() {
        val trials = buildList {
            // Hands holding 1006 bricked 4 of 6; without it, 1 of 6.
            repeat(6) { i -> add(rate("w$i", Answer.CLEAR_LOSS, listOf(1006, 1002, 1003, 1004, 1005), tags = if (i < 4) listOf(Reasons.BRICKED) else listOf(Reasons.INTERRUPTED), decisive = 1006.takeIf { i < 3 })) }
            repeat(6) { i -> add(rate("o$i", Answer.LEAN_WIN, listOf(1001, 1002, 1003, 1004, 1005), tags = if (i == 0) listOf(Reasons.BRICKED) else listOf(Reasons.OUT_RESOURCED))) }
            // Unexplained hands and Ai's are not counted.
            add(rate("x", Answer.CLEAR_LOSS, listOf(1006, 1007, 1008, 1009, 1010)))
            add(rate("ai", Answer.CLEAR_LOSS, listOf(1006, 1007, 1008, 1009, 1010), tags = listOf(Reasons.BRICKED)).copy(judge = StoredTrial.AI))
        }
        val c = ReasonCounts.of(trials)
        val s = Stratum.ALONE_FIRST
        assertEquals(12, c.explained[s])
        assertEquals(5, c.tags[s]?.get(Reasons.BRICKED))
        assertEquals(listOf(1006 to 3), c.deciding[s])
        assertEquals(3, c.named(1006, s))
        val b = assertNotNull(c.bricks(1006, s))
        assertEquals(ReasonCounts.Bricks(4, 6, 1, 6), b)
        assertNull(c.bricks(1040, s), "a card in no explained hand")
        // Each number lists its hands.
        assertEquals(5, ShootoutResults.trialsBehind(trials, Behind.Tagged(Reasons.BRICKED, s)).size)
        assertEquals(4, ShootoutResults.trialsBehind(trials, Behind.Tagged(Reasons.BRICKED, s, card = 1006, holding = true)).size)
        assertEquals(1, ShootoutResults.trialsBehind(trials, Behind.Tagged(Reasons.BRICKED, s, card = 1006, holding = false)).size)
        assertEquals(3, ShootoutResults.trialsBehind(trials, Behind.Deciding(1006, s)).size)
        // An alternate artwork is the same card.
        val alt = ReasonCounts.of(trials) { if (it == 1006) 9006 else it }
        assertEquals(listOf(9006 to 3), alt.deciding[s])
        // In words, for Ai.
        val words = c.words { "Card ${it - 1000}" }
        assertTrue("12 hands explained" in words && "bricked 5" in words && "decided by Card 6 ×3" in words, words)
        assertEquals("", ReasonCounts.of(listOf(rate("x", Answer.CLEAR_LOSS))).words { "" })
        assertFalse(ReasonCounts.of(emptyList()).any)
    }
}
