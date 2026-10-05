package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.ai.memory.MemoryWrite
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrust
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrialNote
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.Agreement
import com.kaiharimoto.mastertool.core.shootout.teach.Apprentice
import com.kaiharimoto.mastertool.core.shootout.teach.CalibrationSet
import com.kaiharimoto.mastertool.core.shootout.teach.ExampleBank
import com.kaiharimoto.mastertool.core.shootout.teach.HandKind
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeBrief
import com.kaiharimoto.mastertool.core.shootout.teach.JudgedPair
import com.kaiharimoto.mastertool.core.shootout.teach.Route
import com.kaiharimoto.mastertool.core.shootout.teach.Rubric
import com.kaiharimoto.mastertool.core.shootout.teach.RubricNotes
import com.kaiharimoto.mastertool.core.shootout.teach.Similarity
import com.kaiharimoto.mastertool.core.shootout.teach.Situation
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.mastertool.core.sync.InboundPath
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase S stage 3: what Ai learns (the rubric, the example bank, the model's prediction), its answers kept as their
 * own judge, and the confidence score, the gate and the audits read from the trials alone.
 */
class ShootoutTeachTest {

    private val pool: Map<Int, Card> = buildMap {
        for (i in 1..20) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect"))
        // Their hand trap: a Quick Effect paid for by discarding itself.
        put(2001, Card(CardId(2001), "Their Ash", "Effect Monster", "effect", description = "Quick Effect: You can discard this card; negate that effect."))
        for (i in 2..20) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
    }
    private val lookup: (CardId) -> Card? = { pool[it.value] }
    private val mine = Deck(main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId))
    private val theirs = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))
    private val groups = DeckGroups(
        groups = listOf(DeckGroup("g-start", "Starters", 3, 0), DeckGroup("g-brick", "Bricks", 2, 1)),
        assignments = mapOf(CardId(1001) to "g-start", CardId(1002) to "g-start", CardId(1010) to "g-brick"),
    )
    private val bench = Bench.of(BenchInput(mine, lookup, groups, opponent = Opponent("o", "Yubel", theirs)))

    // ---- kinds -------------------------------------------------------------------------------------------------

    @Test
    fun handsAreSortedIntoKinds() {
        assertEquals(setOf(1001, 1002), bench.kinds.starters, "the Starters group")
        assertEquals(setOf(2001), bench.kinds.interaction, "a hand trap is interaction")
        val k = bench.kinds.of(Stratum.G1_FIRST, listOf(1001, 1003, 1004, 1005, 1006), listOf(2001, 2002, 2003, 2004, 2005, 2006))
        assertEquals(HandKind(first = true, starter = true, interaction = true), k)
        assertEquals("first·starter·interaction", k.key)
        assertEquals(k, HandKind.parse(k.key))
        assertEquals("going second without a starter, no interaction from them", HandKind(false, false, false).words)
        assertNull(HandKind.parse("sideways·starter"))
        assertEquals(8, HandKind.all(alone = false).size)
        assertEquals(4, HandKind.all(alone = true).size)
        assertNull(Bench.of(BenchInput(mine, lookup, groups)).kinds.of(Stratum.ALONE_FIRST, listOf(1001), null).interaction, "the deck alone has no opponent")
    }

    // ---- similarity and the example bank -----------------------------------------------------------------------------

    private val similarity = Similarity(bench::roleOf, bench.kinds.interaction)
    private val theirHand = listOf(2001, 2002, 2003, 2004, 2005, 2006)

    @Test
    fun similarityIsOneForTheSameHandAndSymmetric() {
        val a = Situation(Stratum.G1_FIRST, listOf(1001, 1003, 1004, 1005, 1006), theirHand)
        val b = Situation(Stratum.G1_SECOND, listOf(1001, 1003, 1004, 1007, 1008, 1009), listOf(2002, 2003, 2004, 2005, 2006))
        assertEquals(1.0, similarity.of(a, a), 1e-9)
        assertEquals(similarity.of(a, b), similarity.of(b, a), 1e-12)
        assertTrue(similarity.of(a, b) in 0.0..1.0)
    }

    @Test
    fun aHandSharingMoreCardsTurnAndInteractionIsMoreAlike() {
        val target = Situation(Stratum.G1_FIRST, listOf(1001, 1003, 1004, 1005, 1006), theirHand)
        val fourShared = target.copy(hand = listOf(1001, 1003, 1004, 1005, 1007))
        val twoShared = target.copy(hand = listOf(1001, 1003, 1008, 1009, 1007))
        assertTrue(similarity.of(target, fourShared) > similarity.of(target, twoShared))
        // The same hand on the other turn is less alike; in the other game on the same turn, between the two.
        val otherTurn = target.copy(stratum = Stratum.G1_SECOND)
        val sided = target.copy(stratum = Stratum.SIDED_FIRST)
        assertTrue(similarity.of(target, sided) > similarity.of(target, otherTurn))
        // Their hand trap gone is less alike than a blank of theirs swapped.
        val noTrap = target.copy(opponent = listOf(2007, 2002, 2003, 2004, 2005, 2006))
        val blankSwapped = target.copy(opponent = listOf(2001, 2007, 2003, 2004, 2005, 2006))
        assertTrue(similarity.of(target, blankSwapped) > similarity.of(target, noTrap))
        // Roles: a starter for a starter is more alike than a starter for a brick.
        val starterForStarter = target.copy(hand = listOf(1002, 1003, 1004, 1005, 1006))
        val starterForBrick = target.copy(hand = listOf(1010, 1003, 1004, 1005, 1006))
        assertTrue(similarity.of(target, starterForStarter) > similarity.of(target, starterForBrick))
        assertEquals(0.5, Similarity.jaccard(listOf(1, 1, 2), listOf(1, 2, 2)), 1e-9)
    }

    @Test
    fun theBankHoldsOnlyThePersonsAnswersGivenBefore() {
        fun t(id: String, at: Long, hand: List<Int>, judge: String = StoredTrial.PERSON) =
            StoredTrial(id, at = at, stratum = "G1_FIRST", hand = hand, opponent = theirHand, answer = "LEAN_WIN", judge = judge)
        val target = t("target", 50, listOf(1001, 1003, 1004, 1005, 1006))
        val trials = listOf(
            t("near", 10, listOf(1001, 1003, 1004, 1005, 1007)),
            t("far", 20, listOf(1008, 1009, 1011, 1012, 1013)),
            t("ai", 30, listOf(1001, 1003, 1004, 1005, 1006), judge = StoredTrial.AI),
            target,
            t("later", 60, listOf(1001, 1003, 1004, 1005, 1006)),
        )
        val notes = listOf(TrialNote("near", "only wins if they have no Ash", at = 15), TrialNote("near", "written after", at = 70))
        val bank = ExampleBank.nearest(Situation.of(target)!!, trials, similarity, notes, k = 6, asOf = target.at, exclude = setOf(target.id))
        assertEquals(listOf("near", "far"), bank.map { it.trial.id }, "never Ai's, never the hand itself, never later")
        assertEquals(listOf("only wins if they have no Ash"), bank.first().notes.map { it.text })
        assertEquals(1, ExampleBank.nearest(Situation.of(target)!!, trials, similarity, k = 1, asOf = target.at).size)
    }

    // ---- the rubric ------------------------------------------------------------------------------------------------

    @Test
    fun theRubricIsEntriesWithAStableName() {
        val doc = Rubric.read(null, "Lab", "Yubel")
        assertEquals("# Rubric: Lab against Yubel", doc.preamble.first())
        val written = (Rubric.add(doc, "A starter and a hand trap beats their turn one unless it is Ash-only.") as MemoryWrite.Done).doc.render()
        val h = assertNotNull(Rubric.hash(written))
        assertEquals(h, Rubric.hash(written), "the same rubric, the same name")
        assertNull(Rubric.hash(doc.render()), "no entries, no rubric")
        assertTrue(Rubric.forPrompt(written).startsWith("- A starter"))
        assertTrue(Rubric.forPrompt(null).contains("No rubric"))
        assertEquals("shootout/d1/o~2e2.rubric.md", ShootoutPaths.rubric("d1", "o.2"))
        assertEquals("shootout/d1/alone.rubric.md", ShootoutPaths.rubric("d1", null))
        assertEquals("shootout/d1/~alone.rubric.md", ShootoutPaths.rubric("d1", "alone"))
        // Beside the trials, so it travels with them: synced and backed up, a path any device may write.
        assertNotNull(InboundPath.safe(ShootoutPaths.rubric("deck-1", "o.2")))
        assertTrue(ShootoutPaths.rubric("d", "o").startsWith(ShootoutPaths.folder("d") + "/"), "deleted with the deck's folder")
    }

    @Test
    fun recurringNotesAreOfferedForTheRubric() {
        val notes = listOf(
            TrialNote("t1", "Only wins if they have no Imperm"),
            TrialNote("t2", "imperm on the starter and it's over"),
            TrialNote("t3", "They had Imperm, that's the game"),
            TrialNote("t3", "Imperm again"),
            TrialNote("t4", "bricked, nothing to do"),
            TrialNote("t5", "bricked hard"),
        )
        val themes = RubricNotes.recurring(notes)
        assertEquals(listOf("imperm"), themes.map { it.word })
        assertEquals(3, themes.first().notes.size, "one per trial")
        assertEquals(listOf("imperm", "bricked"), RubricNotes.recurring(notes, min = 2).map { it.word })
    }

    // ---- agreement ---------------------------------------------------------------------------------------------------

    @Test
    fun agreementIsWithinOneBand() {
        assertTrue(Agreement.agrees(Answer.LEAN_WIN, Answer.CLEAR_WIN))
        assertTrue(Agreement.agrees(Answer.COIN_FLIP, Answer.LEAN_LOSS))
        assertFalse(Agreement.agrees(Answer.LEAN_WIN, Answer.LEAN_LOSS))
        assertFalse(Agreement.agrees(Answer.CLEAR_WIN, Answer.COIN_FLIP))
        val compare = StoredTrial("c", stratum = "G1_FIRST", kind = StoredTrial.COMPARE, left = listOf(1), right = listOf(2), prefer = "left")
        assertEquals(true, Agreement.agrees(compare, null, "left"))
        assertEquals(false, Agreement.agrees(compare, null, "right"))
    }

    @Test
    fun theWilsonRangeIsTheTextbooks() {
        val r = Agreement.wilson(9.0, 10.0, z = 1.959963984540054)
        assertEquals(0.596, r.lower, 0.001)
        assertEquals(0.982, r.upper, 0.001)
        assertEquals(0.0, Agreement.wilson(0.0, 0.0).lower)
        assertEquals(1.0, Agreement.wilson(0.0, 0.0).upper)
        val all = Agreement.wilson(30.0, 30.0)
        assertTrue(all.lower > 0.94 && all.upper > 0.999)
    }

    private fun person(id: String, at: Long, answer: Answer = Answer.LEAN_WIN, mode: String? = null, sawAi: Boolean = false, hand: List<Int> = listOf(1001, 1003, 1004, 1005, 1006), opp: List<Int> = theirHand) =
        StoredTrial(id, at = at, stratum = "G1_FIRST", hand = hand, opponent = opp, answer = answer.name, mode = mode, sawAi = sawAi)

    private fun ai(of: String?, at: Long, answer: Answer = Answer.LEAN_WIN, sure: Double = 0.9, examples: List<String> = emptyList(), mode: String = TeachModes.APPRENTICE, print: String = bench.print, kind: String = "first·starter·interaction") =
        StoredTrial(
            "a-$of-$at", at = at + 1, stratum = "G1_FIRST", hand = listOf(1001, 1003, 1004, 1005, 1006), opponent = theirHand, answer = answer.name,
            judge = StoredTrial.AI, of = of, mode = mode,
            ai = AiVerdict(answer = answer.name, sure = sure, examples = examples, asked = at - 1, kind = kind, print = print),
        )

    @Test
    fun aPairCountsOnlyWhenAiNeverSawThePersonsAnswer() {
        val p1 = person("p1", 10)
        val p2 = person("p2", 20)
        val pairs = JudgedPair.all(
            listOf(
                p1, ai("p1", 9),
                p2, ai("p2", 19, examples = listOf("p2")), // shown its own answer
                person("p3", 30), ai("p3", 29, examples = listOf("p4")), // shown one answered after it
                person("p4", 40),
                person("p5", 50, sawAi = true), ai("p5", 49), // the person had seen Ai's
            ),
        )
        assertEquals(listOf(true, false, false, true), pairs.map { it.heldOut })
        assertEquals(1, pairs.count { it.counts })
        assertTrue(pairs.last().seen)
        // A verdict a 1.1.2 log kept on the person's trial is a pair, never held out (nothing says what Ai was shown).
        val old = JudgedPair.all(listOf(person("q", 1).copy(ai = AiVerdict(answer = "COIN_FLIP", sure = 0.6))))
        assertFalse(old.single().heldOut)
        assertTrue(old.single().agrees)
    }

    // ---- the gate, the audits, a deck change ------------------------------------------------------------------------

    /** [n] held-out pairs of one kind, the person and Ai agreeing [agree] of them. */
    private fun pairs(n: Int, agree: Int, from: Long = 0, sure: Double = 0.9, print: String = bench.print, mode: String = TeachModes.APPRENTICE): List<StoredTrial> =
        (0 until n).flatMap { i ->
            val at = from + 10L * (i + 1)
            listOf(person("p$from-$i", at, mode = mode), ai("p$from-$i", at - 1, if (i < agree) Answer.CLEAR_WIN else Answer.CLEAR_LOSS, sure, print = print, mode = mode))
        }

    private fun trust(trials: List<StoredTrial>, settings: TrustSettings = TrustSettings(solo = true)) =
        Trust.read(trials, settings, alone = false, print = bench.print) { bench.kindOf(it)?.key }

    @Test
    fun aKindOpensWhenTheBottomOfItsRangeClearsTheBar() {
        val kind = "first·starter·interaction"
        assertFalse(trust(pairs(8, 8)).kind(kind)!!.open, "eight hands are not enough")
        val earned = trust(pairs(40, 40)).kind(kind)!!
        assertTrue(earned.open, earned.why)
        assertTrue(earned.sureRange.lower >= 0.9)
        val shaky = trust(pairs(40, 34)).kind(kind)!!
        assertFalse(shaky.open, "85 % agreement is not over a 90 % bar")
        assertTrue(shaky.why!!.contains("under your bar"))
        assertTrue(trust(pairs(40, 34), TrustSettings(bar = 0.7, solo = true)).kind(kind)!!.open, "the person's bar")
        // Not sure is not counted for the gate: an Ai that is never sure never runs alone.
        assertFalse(trust(pairs(40, 40, sure = 0.5)).kind(kind)!!.open)
        // Other kinds have nothing yet.
        assertEquals(1, trust(pairs(40, 40)).open.size)
    }

    @Test
    fun theGateRoutesBySurenessAndAuditsAShare() {
        val state = trust(pairs(40, 40))
        val random = Random(3)
        val kind = "first·starter·interaction"
        assertEquals(Route.PERSON, Trust.route(state, kind, 0.5, random), "not sure: to the person")
        assertEquals(Route.PERSON, Trust.route(state, "second·none·clear", 0.99, random), "a kind not earned: to the person")
        assertEquals(Route.PERSON, Trust.route(trust(pairs(40, 40), TrustSettings(solo = false)), kind, 0.99, random), "the person has not let it run alone")
        val routes = (0 until 3000).map { Trust.route(state, kind, 0.95, random) }
        val audited = routes.count { it == Route.AUDIT }.toDouble() / routes.size
        assertEquals(Trust.EARLY_RATE, audited, 0.03, "early on, one in three is audited")
        assertEquals(0.1, Trust.auditRate(Trust.EARLY), 1e-9)
    }

    @Test
    fun twoMissesBeyondTheRangeCloseAKindUntilItIsEarnedAgain() {
        val kind = "first·starter·interaction"
        val earned = pairs(40, 40)
        // Audits: one miss is within what a 95 % agreement allows over a few; two in four is not.
        val audits = pairs(4, 2, from = 1_000, mode = TeachModes.AUDIT)
        val closed = trust(earned + audits).kind(kind)!!
        assertNotNull(closed.closedAt, "closed by its audits")
        assertFalse(closed.open)
        // Only pairs after the closing count toward opening it again.
        val again = trust(earned + audits + pairs(40, 40, from = 2_000)).kind(kind)!!
        assertTrue(again.open, again.why)
        assertEquals(0, again.audits)
        // A single miss in many audits does not close it.
        val fine = trust(earned + pairs(12, 11, from = 1_000, mode = TeachModes.AUDIT)).kind(kind)!!
        assertNull(fine.closedAt)
    }

    @Test
    fun aDeckChangeReEarnsEveryKindFromAShortSet() {
        val kind = "first·starter·interaction"
        val old = pairs(60, 60, print = "older-decks")
        val stale = trust(old).kind(kind)!!
        assertTrue(stale.stale)
        assertFalse(stale.open, "the decks changed: re-earned before it runs alone")
        assertTrue(stale.why!!.contains("decks changed"))
        assertEquals(30.0, stale.pairs, 1e-9, "older pairs count half")
        val rechecked = trust(old + pairs(Trust.RECHECK, Trust.RECHECK, from = 5_000)).kind(kind)!!
        assertTrue(rechecked.open, rechecked.why)
        assertFalse(trust(old + pairs(Trust.RECHECK, 1, from = 5_000)).kind(kind)!!.open, "a short set it misses does not re-earn it")
    }

    @Test
    fun aisCertaintyIsScored() {
        val trials = pairs(20, 20, sure = 0.95) + pairs(20, 10, from = 1_000, sure = 0.95)
        val c = trust(trials).calibration
        assertEquals(40, c.n)
        // Said 95 %, agreed 75 %: the gap is the error; the Brier score is (0.05² × 30 + 0.95² × 10) / 40.
        assertEquals(0.2, c.ece, 1e-9)
        assertEquals((0.0025 * 30 + 0.9025 * 10) / 40, c.brier, 1e-9)
        assertFalse(c.calibrated)
    }

    @Test
    fun theSeenAnswersDriftIsMeasured() {
        val blind = pairs(10, 10)
        // Seen: the person gives Ai's exact answer every time; blind they matched it half the time (CLEAR_WIN against LEAN_WIN).
        val seen = (0 until 10).flatMap { i -> listOf(person("s$i", 5_000L + i * 10, Answer.CLEAR_WIN, sawAi = true), ai("s$i", 5_000L + i * 10 - 1, Answer.CLEAR_WIN)) }
        val d = trust(blind + seen).seen
        assertEquals(10, d.seen)
        assertEquals(1.0, d.sameSeen)
        assertEquals(0.0, d.sameBlind, "blind they gave lean win to its clear win")
        assertEquals(100.0, d.drift!!, 1e-9)
    }

    @Test
    fun theApprenticeAsksOnlyWhereItDisagreedOrWasUnsure() {
        val p = person("p", 10, Answer.LEAN_WIN)
        val asks = AiVerdict(answer = "LEAN_LOSS", sure = 0.9, question = "What beats their Ash here?")
        assertTrue(Apprentice.asks(asks, p, sinceLast = null))
        assertFalse(Apprentice.asks(asks, p, sinceLast = 2), "at most one every few trials")
        assertFalse(Apprentice.asks(asks.copy(answer = "CLEAR_WIN"), p, null), "agreed and sure: nothing to ask")
        assertTrue(Apprentice.asks(asks.copy(answer = "CLEAR_WIN", sure = 0.4), p, null), "agreed but unsure")
        assertFalse(Apprentice.asks(asks.copy(question = null), p, null))
    }

    // ---- what Ai is handed -----------------------------------------------------------------------------------------

    @Test
    fun theBriefNeverHoldsTheAnswerToItsOwnHand() {
        val run = ShootoutRun(bench, ShootoutLog(deck = "me", opponent = "o"), seed = 2)
        val p = run.next()
        val trials = listOf(person("old", 5, Answer.CLEAR_LOSS, hand = listOf(1010, 1011, 1012, 1013, 1014)))
        val examples = ExampleBank.nearest(
            Situation(p.stratum, if (p is Proposal.Rate) bench.ids(p.hand) else bench.ids((p as Proposal.Compare).left), p.opponent?.let(bench::opponentIds)),
            trials, similarity, asOf = 100,
        )
        val brief = JudgeBrief.of(bench, p, run.predict(p), "# Rubric\n\n- Bricks lose.", examples, "Lab", { pool[it]?.name ?: "#$it" }, asked = 100, fitted = run.fitted)
        assertTrue(brief.text.contains("Bricks lose."))
        assertTrue(brief.text.contains("Card 10"), "the example's hand by name")
        assertTrue(brief.text.contains("shootout_judge"))
        assertEquals(listOf("old"), brief.examples)
        val v = brief.verdict(Answer.LEAN_WIN, null, 1.4, "  a starter  ", "model-x")
        assertEquals(1.0, v.sure, "certainty is a share")
        assertEquals("a starter", v.why)
        assertEquals(bench.print, v.print)
        assertEquals(brief.kind.key, v.kind)
        assertEquals(Answer.LEAN_WIN, JudgeBrief.parseAnswer("2"))
        assertEquals(Answer.CLEAR_LOSS, JudgeBrief.parseAnswer("clear_loss"))
        assertEquals(Answer.LEAN_LOSS, JudgeBrief.parseAnswer("Likely not"))
        assertNull(JudgeBrief.parseAnswer("maybe"))
    }

    @Test
    fun theCalibrationSetCoversTheKinds() {
        val set = CalibrationSet.pick(bench, random = Random(5))
        assertEquals(CalibrationSet.SIZE, set.size)
        val kinds = set.map { bench.kindOf(it).key }.toSet()
        assertTrue(kinds.size >= 6, "the set covers $kinds")
        assertEquals(set.size, set.map { it.hand to it.opponent }.toSet().size, "no hand twice")
        assertTrue(set.any { it.stratum.goingFirst } && set.any { !it.stratum.goingFirst })
        val short = CalibrationSet.short(bench, Random(5))
        assertTrue(short.size <= kinds.size * Trust.RECHECK + 2 * Trust.RECHECK && short.isNotEmpty())
        assertEquals(CalibrationSet.MIN, CalibrationSet.pick(bench, size = 3).size, "never under 24")
    }

    // ---- Ai's answers in the model -------------------------------------------------------------------------------------

    @Test
    fun eachKindOfAnswerIsItsOwnJudge() {
        val hand = listOf(1001, 1002, 1003, 1004, 1005)
        val t = StoredTrial("a", stratum = "G1_FIRST", hand = hand, opponent = theirHand, answer = "CLEAR_WIN")
        assertEquals(Bench.PERSON, bench.trial(t)!!.judge)
        assertEquals(Bench.SEEN, bench.trial(t.copy(sawAi = true))!!.judge)
        assertEquals(Bench.AI, bench.trial(t.copy(judge = StoredTrial.AI))!!.judge)
        assertEquals(2, bench.observations(t.copy(ai = AiVerdict(answer = "LEAN_LOSS"))).size, "a 1.1.2 verdict on the person's trial is Ai's answer too")
        assertTrue(bench.observations(t.copy(judge = StoredTrial.AI), withAi = false).isEmpty())
    }

    @Test
    fun theTrustPanelSaysHowMuchIsAisAndWhatItMoved() {
        var run = ShootoutRun(bench, ShootoutLog(deck = "me", opponent = "o"), seed = 4)
        val random = Random(9)
        repeat(60) { i ->
            val p = run.next() as? Proposal.Rate ?: return@repeat
            val ids = bench.ids(p.hand)
            val v = (if (1001 in ids) 1 else 0) + (if (2001 in p.opponent?.let(bench::opponentIds).orEmpty()) -1 else 0) + random.nextInt(2)
            val answer = listOf(Answer.LEAN_LOSS, Answer.COIN_FLIP, Answer.LEAN_WIN, Answer.CLEAR_WIN).getOrElse(v + 1) { Answer.CLEAR_LOSS }
            val kept = run.answer(p, answer, "p$i", at = 1_000L + i * 10)
            // Ai: a band more optimistic than the person.
            val aiAnswer = Answer.entries[(answer.ordinal + 1).coerceAtMost(4)]
            run.record(bench.aiAnswer(p, AiVerdict(answer = aiAnswer.name, sure = 0.9, asked = kept.at - 5, kind = bench.kindOf(p).key, print = bench.print), "a$i", kept.at + 1, of = kept.id, mode = TeachModes.APPRENTICE, session = "s"))
        }
        run = ShootoutRun(bench, ShootoutCodec.decode(ShootoutCodec.encode(run.log))!!, seed = 4)
        val report = ShootoutTrust.read(run)
        assertEquals(0.5, report.aiShare, 0.02, "half the answers are Ai's")
        assertTrue(report.aiWeighted in 0.0..report.aiShare + 0.2)
        assertTrue(report.aiLean > 0, "measured as optimistic: ${report.aiLean}")
        assertTrue(report.moved.isNotEmpty())
        assertTrue(report.state.pairs >= 50)
        assertTrue(report.state.kinds.sumOf { it.pairs } > 0)
    }
}
