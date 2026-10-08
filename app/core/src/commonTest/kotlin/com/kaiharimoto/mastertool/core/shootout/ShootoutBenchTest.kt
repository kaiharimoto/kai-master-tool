package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutPin
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Compared
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrint
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrints
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.JudgeBrief
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase S stage 2: the Shootout built from the app's decks — cards by canonical passcode, roles from the groups,
 * strata waiting for plans, kept trials read back — and a session run end to end with every answer kept.
 */
class ShootoutBenchTest {

    /** Ash Blossom and its alternate artwork: one card. */
    private val ash = Card(CardId(14558127), "Ash Blossom & Joyous Spring", "Effect Monster", "effect", alternateIds = listOf(CardId(14558128)))
    private val pool: Map<Int, Card> = buildMap {
        put(ash.id.value, ash)
        put(14558128, ash)
        for (i in 1..40) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect"))
        for (i in 1..40) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
        put(3001, Card(CardId(3001), "A Link", "Link Monster", "link"))
    }
    private val lookup: (CardId) -> Card? = { pool[it.value] }

    /** 40 cards: two Ash and one alternate Ash, then three-ofs and one-ofs. */
    private val mine: Deck = Deck(
        main = (listOf(14558127, 14558127, 14558128) + (1..9).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (10..19).map { 1000 + it }).map(::CardId),
        extra = listOf(CardId(3001)),
        side = listOf(1031, 1031, 1032).map(::CardId),
    )
    private val theirs: Deck = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))

    private val groups = DeckGroups(
        groups = listOf(DeckGroup("g-hand", "Handtraps", 2, 1), DeckGroup("g-start", "Starters", 3, 0)),
        assignments = mapOf(CardId(14558128) to "g-hand", CardId(1001) to "g-start", CardId(1002) to "g-start"),
    )

    @Test
    fun anAlternateArtworkIsTheSameCard() {
        val bench = Bench.of(BenchInput(mine, lookup))
        assertEquals(14558127, bench.own.first())
        assertEquals(3, bench.copies(14558127, Stratum.ALONE_FIRST), "two Ash and an alternate Ash are three")
        assertFalse(14558128 in bench.own)
        assertEquals(Bench.ALONE, bench.strata)
        assertTrue(3001 !in bench.own, "the Extra Deck never opens in a hand")
        // A kept trial naming the alternate reads as Ash.
        val t = StoredTrial("t", stratum = "ALONE_FIRST", hand = listOf(14558128, 1001, 1002, 1003, 1004), answer = "LEAN_WIN")
        val read = assertNotNull(bench.trial(t))
        assertEquals(Stratum.ALONE_FIRST, read.stratum)
    }

    @Test
    fun rolesComeFromTheGroupsInTheirOrder() {
        val bench = Bench.of(BenchInput(mine, lookup, groups))
        assertEquals(listOf("Starters", "Handtraps", Bench.UNGROUPED), bench.roleNames)
        assertEquals("Handtraps", bench.roleOf(14558127), "a group given to the alternate holds the card")
        assertEquals("Starters", bench.roleOf(1001))
        assertEquals(Bench.UNGROUPED, bench.roleOf(1005))
        assertEquals(listOf(Bench.ALL_CARDS), Bench.of(BenchInput(mine, lookup)).roleNames, "no groups, one role")
        assertTrue(bench.spec.pairs.size == Bench.PAIRS)
    }

    @Test
    fun aSidedStratumWaitsForBothPlans() {
        val opp = Opponent("opp", "Yubel", theirs)
        val none = Bench.of(BenchInput(mine, lookup, opponent = opp))
        assertEquals(listOf(Stratum.G1_FIRST, Stratum.G1_SECOND), none.strata)
        assertEquals(setOf(Stratum.SIDED_FIRST, Stratum.SIDED_SECOND), none.waiting.keys)

        val myFirst = SidePlan(out = listOf(CardId(1003), CardId(1003)), into = listOf(CardId(1031), CardId(1031)))
        // Only mine going first: their going-second plan is still missing, so sided-first waits — never game-one hands.
        val half = Bench.of(BenchInput(mine, lookup, opponent = opp, mine = mapOf(Turn.FIRST to myFirst)))
        assertTrue(Stratum.SIDED_FIRST in half.waiting && "Yubel" in half.waiting.getValue(Stratum.SIDED_FIRST))

        val theirSecond = SidePlan(out = listOf(CardId(2001)), into = listOf(CardId(2040)))
        val both = Bench.of(BenchInput(mine, lookup, opponent = opp, mine = mapOf(Turn.FIRST to myFirst), theirs = mapOf(Turn.SECOND to theirSecond)))
        assertEquals(listOf(Stratum.G1_FIRST, Stratum.G1_SECOND, Stratum.SIDED_FIRST), both.strata)
        assertEquals(1, both.copies(1003, Stratum.SIDED_FIRST))
        assertEquals(2, both.copies(1031, Stratum.SIDED_FIRST))
        assertEquals(0, both.copies(1031, Stratum.G1_FIRST))
        assertEquals("-1003x2 +1031x2", both.prints.getValue(Stratum.SIDED_FIRST).mine)
        assertEquals("-2001 +2040", both.prints.getValue(Stratum.SIDED_FIRST).theirs)
        // A trial kept under another plan is labelled, and still read.
        val old = StoredTrial("t", stratum = "SIDED_FIRST", hand = listOf(1001, 1002, 1003, 1004, 1005), opponent = listOf(2001, 2002, 2003, 2004, 2005, 2006), answer = "COIN_FLIP", plans = PlanPrints("=", "="))
        assertTrue(both.underOlderPlan(old))
        assertNotNull(both.trial(old))
    }

    @Test
    fun aPlansFingerprintIsItsCardsNotItsOrderOrPrinting() {
        val a = SidePlan(out = listOf(CardId(2), CardId(1), CardId(2)), into = listOf(CardId(14558128)), note = "why")
        val b = SidePlan(out = listOf(CardId(1), CardId(2), CardId(2)), into = listOf(CardId(14558127)))
        val canon: (CardId) -> CardId = { if (it.value == 14558128) CardId(14558127) else it }
        assertEquals(PlanPrint.of(a, canon), PlanPrint.of(b, canon))
        assertEquals("=", PlanPrint.of(SidePlan(note = "nothing changes")))
    }

    @Test
    fun everyAnswerIsFittedAsItsOwnJudge() {
        val bench = Bench.of(BenchInput(mine, lookup))
        val hand = listOf(1001, 1002, 1003, 1004, 1005)
        assertEquals(Bench.PERSON, bench.trial(StoredTrial("a", stratum = "ALONE_FIRST", hand = hand, answer = "CLEAR_WIN"))?.judge)
        // Stage 3: the person after seeing Ai, and Ai, are judges of their own (S.md §6½), never the reference.
        assertEquals(Bench.SEEN, bench.trial(StoredTrial("b", stratum = "ALONE_FIRST", hand = hand, answer = "CLEAR_WIN", sawAi = true))?.judge)
        assertEquals(Bench.AI, bench.trial(StoredTrial("c", stratum = "ALONE_FIRST", hand = hand, answer = "CLEAR_WIN", judge = StoredTrial.AI))?.judge)
        assertNull(bench.trial(StoredTrial("d", stratum = "G1_FIRST", hand = hand, answer = "CLEAR_WIN")), "a matchup's trial is not the deck alone's")
        assertNull(bench.trial(StoredTrial("e", stratum = "SOMETHING_NEW", hand = hand, answer = "CLEAR_WIN")))
        assertNull(bench.trial(StoredTrial("f", stratum = "ALONE_FIRST", hand = hand, answer = "MAYBE")))
    }

    @Test
    fun aSessionKeepsEveryAnswerAndReadsBackTheSame() {
        val opp = Opponent("opp", "Yubel", theirs)
        val bench = Bench.of(BenchInput(mine, lookup, groups, opponent = opp))
        val run = ShootoutRun(bench, ShootoutLog(deck = "me", opponent = "opp", opponentName = "Yubel"), seed = 3)
        // A judge who likes the first three-of and hates the last one.
        val random = Random(11)
        repeat(40) { i ->
            when (val p = run.next()) {
                is Proposal.Rate -> {
                    val ids = bench.ids(p.hand)
                    val v = 0.9 * ids.count { it == 1001 } - 0.9 * ids.count { it == 1009 } + 0.4 * random.nextDouble()
                    val answer = ShootoutWords.SCALE.minByOrNull { a -> kotlin.math.abs(a.score - Logistic.of(v)) }!!
                    run.answer(p, answer, "s1-$i", at = 1_000L + i, ms = 2_000, session = "s1")
                }
                is Proposal.Compare -> run.prefer(p, bench.ids(p.left).count { it == 1001 } >= bench.ids(p.right).count { it == 1001 }, "s1-$i", at = 1_000L + i, session = "s1")
            }
        }
        assertEquals(40, run.log.trials.size)
        assertEquals(40, run.fitted)
        val results = run.results()
        assertEquals(listOf(Stratum.G1_FIRST, Stratum.G1_SECOND), results.strata)
        assertEquals(40, results.kept)
        val starter = results.cards.first { it.card == 1001 }
        val brick = results.cards.first { it.card == 1009 }
        assertEquals("Starters", starter.role)
        val s = Stratum.G1_FIRST
        assertTrue(starter.cells.getValue(s).estimate.value > brick.cells.getValue(s).estimate.value, "the starter rates above the brick")
        val cell = starter.cells.getValue(s)
        assertTrue(cell.estimate.range95.start < cell.estimate.range80.start && cell.estimate.range80.endInclusive < cell.estimate.range95.endInclusive)
        assertTrue(cell.drawShare in 0.3..0.4, "a three-of in five cards: ${cell.drawShare}")
        // Every number opens its trials.
        val behind = ShootoutResults.trialsBehind(run.log.trials, Behind.Card(1001, s))
        assertEquals(cell.trials, behind.size)
        assertTrue(behind.all { 1001 in it.hands.flatten() && it.stratum == s.name })

        // Written and read back, the log fits to the same numbers (a fresh fit and one warmed answer by answer agree to
        // within a few hundredths of a point: the noise's rounds stop at a hundredth).
        val text = ShootoutCodec.encode(run.log)
        val again = ShootoutRun(bench, assertNotNull(ShootoutCodec.decode(text)), seed = 3)
        assertEquals(run.fitted, again.fitted)
        val e1 = run.results().cards.first { it.card == 1001 }.cells.getValue(s).estimate.value
        val e2 = again.results().cards.first { it.card == 1001 }.cells.getValue(s).estimate.value
        assertEquals(e1, e2, 0.5)
    }

    @Test
    fun theTurnsDrawIsKeptAndReadBackAsTheDraw() {
        // 2026-10, kai: the sixth card counts towards the card as the draw only. A hand going second names its draw, the
        // trial keeps it, and reading the trial back gives the same hand, draw and all.
        val bench = Bench.of(BenchInput(mine, lookup))
        val run = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_SECOND)
        repeat(8) { i ->
            when (val p = run.next()) {
                is Proposal.Rate -> {
                    assertTrue(p.hand.draw != Hand.NONE, "a hand going second names its draw")
                    val kept = run.answer(p, Answer.COIN_FLIP, "d$i", at = i.toLong())
                    assertEquals(bench.drawId(p.hand), kept.sixth)
                    assertEquals(p.hand, (bench.trial(kept) as Rated).hand)
                    val shown = bench.shown(p.hand)
                    assertEquals(5, shown.opening.size)
                    assertEquals(kept.sixth, shown.draw)
                }
                is Proposal.Compare -> {
                    val kept = run.prefer(p, true, "d$i", at = i.toLong())
                    assertEquals(bench.drawId(p.left), kept.leftSixth)
                    assertEquals(bench.drawId(p.right), kept.rightSixth)
                    val read = bench.trial(kept) as Compared
                    assertEquals(p.left, read.left)
                    assertEquals(p.right, read.right)
                }
            }
        }
        // Going first there is none.
        val first = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_FIRST).next()
        assertTrue(first is Proposal.Rate && first.hand.draw == Hand.NONE)
    }

    @Test
    fun aTrialKeptBeforeTheSixthWasReadsItsDrawWhereItCan() {
        val bench = Bench.of(BenchInput(mine, lookup))
        val six = listOf(1001, 1002, 1003, 1004, 1005, 1006)
        fun drawOf(t: StoredTrial) = (bench.trial(t) as Rated).hand.let { h -> bench.drawId(h) }
        val base = StoredTrial("o", stratum = "ALONE_SECOND", hand = six, answer = "LEAN_WIN")
        // 1.1.4: nothing kept, read as unknown.
        assertNull(drawOf(base))
        // 1.1.5–1.1.6: the turn's draw is the marked sixth, draws by effects from under it.
        assertEquals(1004, drawOf(base.copy(turnDraw = 1004)))
        assertEquals(1004, drawOf(base.copy(turnDraw = 1004, drew = listOf(1010))))
        // 1.1.7: an effect's draw takes the marked sixth first; the turn's draw is the next card down.
        assertEquals(1006, drawOf(base.copy(turnDraw = 1010, drew = listOf(1006))))
        // Both in the hand: it cannot say, so unknown.
        assertNull(drawOf(base.copy(turnDraw = 1001, drew = listOf(1002))))
        // From now: kept as it is.
        assertEquals(1002, drawOf(base.copy(sixth = 1002, turnDraw = 1004)))
    }

    @Test
    fun aiIsToldWhichCardIsTheDraw() {
        val opp = Opponent("opp", "Yubel", theirs)
        val bench = Bench.of(BenchInput(mine, lookup, groups, opponent = opp))
        val run = ShootoutRun(bench, ShootoutLog(deck = "me", opponent = "opp", opponentName = "Yubel"), pinned = Stratum.G1_SECOND)
        val p = generateSequence { run.next() }.filterIsInstance<Proposal.Rate>().first()
        val brief = JudgeBrief.of(bench, p, null, null, emptyList(), "Lab", { pool[it]?.name ?: "#$it" }, asked = 1, fitted = 0)
        val drawn = pool.getValue(bench.drawId(p.hand)!!).name
        assertTrue(brief.text.contains("drawn for your turn: $drawn"), brief.text)
        assertTrue(brief.text.contains("not in hand before that turn"))
    }

    @Test
    fun pinningASessionDealsOnlyThatStratum() {
        val bench = Bench.of(BenchInput(mine, lookup))
        val run = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_SECOND)
        repeat(6) { i ->
            val p = run.next()
            assertEquals(Stratum.ALONE_SECOND, p.stratum)
            if (p is Proposal.Rate) {
                assertEquals(6, p.hand.size, "going second, six cards")
                run.answer(p, Answer.COIN_FLIP, "x$i", at = i.toLong())
            }
        }
        // A pin the model cannot deal keeps its turn: a sided pin on the deck alone deals going first alone, never both turns.
        assertEquals(Stratum.ALONE_FIRST, ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.SIDED_FIRST).pinned)
    }

    @Test
    fun aPinMovedToAnotherTargetKeepsItsTurnAndSaysSo() {
        // "G1 second" against Yubel, then the deck alone (another deck chosen): going second alone, said.
        val alone = Bench.of(BenchInput(mine, lookup))
        val toAlone = ShootoutPin.carry(Stratum.G1_SECOND, alone)
        assertEquals(Stratum.ALONE_SECOND, toAlone.pin)
        assertNotNull(toAlone.said)
        val run = ShootoutRun(alone, ShootoutLog(deck = "me"), pinned = Stratum.G1_SECOND)
        repeat(8) { assertEquals(Stratum.ALONE_SECOND, run.next().stratum, "never a going-first hand") }

        // "Sided second" when the siding plans went away in a sync: game 1 going second, with why.
        val opp = Opponent("opp", "Yubel", theirs)
        val noPlans = Bench.of(BenchInput(mine, lookup, opponent = opp))
        assertTrue(Stratum.SIDED_SECOND in noPlans.waiting)
        val sided = ShootoutPin.carry(Stratum.SIDED_SECOND, noPlans)
        assertEquals(Stratum.G1_SECOND, sided.pin)
        assertTrue(sided.said!!.contains("Sided · going second") && sided.said!!.contains("Game 1 · going second"), sided.said)

        // The deck alone's "Going second" against an opponent: game 1 going second.
        assertEquals(Stratum.G1_SECOND, ShootoutPin.carry(Stratum.ALONE_SECOND, noPlans).pin)

        // A pin the bench can deal stays, unsaid; no pin stays none.
        assertEquals(ShootoutPin.Carried(Stratum.G1_SECOND, null), ShootoutPin.carry(Stratum.G1_SECOND, noPlans))
        assertEquals(ShootoutPin.Carried(null, null), ShootoutPin.carry(null, noPlans))

        // No stratum on that turn at all: the pin is off, and that is said too.
        val off = ShootoutPin.carry(Stratum.G1_SECOND, listOf(Stratum.G1_FIRST))
        assertNull(off.pin)
        assertNotNull(off.said)
    }

    @Test
    fun aPinnedSessionsResultsAgreeWithItsOwnLine() {
        // The red team (2026-10): after a going-second session, Results read every stratum and said "too early" over the
        // going-first column the person chose not to train, while the session said "enough to stop".
        val bench = Bench.of(BenchInput(mine, lookup))
        val run = ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.ALONE_SECOND)
        repeat(12) { i ->
            when (val p = run.next()) {
                is Proposal.Rate -> run.answer(p, Answer.LEAN_WIN, "p$i", at = i.toLong())
                is Proposal.Compare -> run.prefer(p, true, "p$i", at = i.toLong())
            }
        }
        val results = run.results()
        assertEquals(listOf(Stratum.ALONE_SECOND), results.inPlay)
        assertEquals(run.settled(), results.settled)
        // Unpinned, the same log reads both turns.
        assertEquals(Bench.ALONE, ShootoutRun(bench, run.log).results().inPlay)
    }

    @Test
    fun aNumbersHandsAreTheHandsItLists() {
        // The red team (2026-10): a card's "N hands" counted blind answers while its list showed every judge's, and a
        // trial kept under an alternate artwork was left out of the count.
        val bench = Bench.of(BenchInput(mine, lookup))
        val five = listOf(1001, 1002, 1003, 1004, 1005)
        val trials = listOf(
            StoredTrial("blind", stratum = "ALONE_FIRST", hand = five, answer = "LEAN_WIN"),
            StoredTrial("seen", stratum = "ALONE_FIRST", hand = five, answer = "LEAN_WIN", sawAi = true),
            StoredTrial("ai", stratum = "ALONE_FIRST", hand = five, answer = "COIN_FLIP", judge = StoredTrial.AI),
            // Ash kept under its alternate artwork's passcode.
            StoredTrial("alt", stratum = "ALONE_FIRST", hand = listOf(14558128, 1002, 1003, 1004, 1005), answer = "LEAN_LOSS"),
        )
        val run = ShootoutRun(bench, ShootoutLog(deck = "me", trials = trials))
        val results = run.results()
        val s = Stratum.ALONE_FIRST
        val card = results.cards.first { it.card == 1001 }.cells.getValue(s)
        assertEquals(3, card.trials)
        assertEquals(card.trials, ShootoutResults.trialsBehind(trials, Behind.Card(1001, s), canon = bench::canonical).size)
        val ash = results.cards.first { it.card == 14558127 }.cells.getValue(s)
        assertEquals(1, ash.trials, "the alternate artwork counts as Ash")
        assertEquals(1, ShootoutResults.trialsBehind(trials, Behind.Card(14558127, s), canon = bench::canonical).size)
        // "F of K hands read": hands, so a 1.1.2 trial holding Ai's verdict beside the person's answer is one hand.
        val old = StoredTrial("old", stratum = "ALONE_FIRST", hand = five, answer = "LEAN_WIN", ai = AiVerdict(answer = "COIN_FLIP"))
        val withOld = ShootoutRun(bench, ShootoutLog(deck = "me", trials = trials + old)).results()
        assertEquals(5, withOld.kept)
        assertEquals(5, withOld.fitted)
    }

    @Test
    fun tooSmallADeckSaysSo() {
        val tiny = Deck(main = listOf(CardId(1001), CardId(1002)))
        assertNotNull(Bench.problem(BenchInput(tiny, lookup)))
        assertNotNull(Bench.problem(BenchInput(mine, lookup, opponent = Opponent("o", "Tiny", tiny))))
        assertNull(Bench.problem(BenchInput(mine, lookup)))
    }

    @Test
    fun theScaleAndTheSwipe() {
        assertEquals(Answer.CLEAR_WIN, ShootoutWords.byKey(1))
        assertEquals(Answer.CLEAR_LOSS, ShootoutWords.byKey(5))
        assertNull(ShootoutWords.byKey(6))
        assertEquals(3, ShootoutWords.keyOf(Answer.COIN_FLIP))
        assertEquals(Answer.CLEAR_WIN, ShootoutWords.swipe(0.5f, 0.05f))
        assertEquals(Answer.LEAN_WIN, ShootoutWords.swipe(0.2f, 0f))
        assertEquals(Answer.LEAN_LOSS, ShootoutWords.swipe(-0.2f, 0.02f))
        assertEquals(Answer.CLEAR_LOSS, ShootoutWords.swipe(-0.4f, 0f))
        assertEquals(Answer.COIN_FLIP, ShootoutWords.swipe(0.02f, -0.3f))
        assertNull(ShootoutWords.swipe(0.05f, 0f), "too short is nothing")
        assertNull(ShootoutWords.swipe(0.02f, 0.3f), "down is nothing")
        assertEquals("+4.2", ShootoutWords.points(4.21))
        assertEquals("−1.0", ShootoutWords.points(-0.98))
        assertEquals("0.0", ShootoutWords.points(0.01))
    }

    @Test
    fun theLogReadsForgivingly() {
        val text = """{"version":7,"deck":"d1","opponent":"o1","future":{"x":1},"trials":[
            {"id":"a","stratum":"G1_FIRST","hand":[1,2,3,4,5],"opponent":[6,7,8,9,10,11],"answer":"LEAN_WIN","newKey":true},
            {"id":"b","stratum":"G1_FIRST","hand":"not a list"},
            {"id":"c","stratum":"G1_SECOND","kind":"compare","left":[1],"right":[2],"prefer":"left","judge":"ai","ai":{"answer":"CLEAR_WIN","sure":0.8}}
        ]}"""
        val log = assertNotNull(ShootoutCodec.decode(text))
        assertEquals(7, log.version)
        assertEquals(listOf("a", "c"), log.trials.map { it.id }, "a trial that will not read is dropped alone")
        assertEquals(0.8, log.trials[1].ai?.sure)
        assertNull(ShootoutCodec.decode("not json"))
        assertNull(ShootoutCodec.decode("""{"trials":[]}"""), "a log names its deck")
        val round = assertNotNull(ShootoutCodec.decode(ShootoutCodec.encode(log)))
        assertEquals(log.trials, round.trials)
    }

    @Test
    fun theFilesStayInTheirFolder() {
        assertEquals("shootout/d-1/alone.json", ShootoutPaths.file("d-1", null))
        assertEquals("shootout/d-1/o_2.json", ShootoutPaths.file("d-1", "o_2"))
        assertEquals("shootout/d-1/~alone.json", ShootoutPaths.file("d-1", "alone"))
        assertEquals("shootout/~2e~2e~2fx", ShootoutPaths.folder("../x"))
    }
}
