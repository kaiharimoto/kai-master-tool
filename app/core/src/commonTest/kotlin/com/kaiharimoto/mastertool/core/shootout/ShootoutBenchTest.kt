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
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrint
import com.kaiharimoto.mastertool.core.shootout.store.PlanPrints
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
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
    fun onlyThePersonsBlindAnswersAreFittedForNow() {
        val bench = Bench.of(BenchInput(mine, lookup))
        val hand = listOf(1001, 1002, 1003, 1004, 1005)
        assertNotNull(bench.trial(StoredTrial("a", stratum = "ALONE_FIRST", hand = hand, answer = "CLEAR_WIN")))
        assertNull(bench.trial(StoredTrial("b", stratum = "ALONE_FIRST", hand = hand, answer = "CLEAR_WIN", sawAi = true)))
        assertNull(bench.trial(StoredTrial("c", stratum = "ALONE_FIRST", hand = hand, answer = "CLEAR_WIN", judge = StoredTrial.AI)))
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
        // A pin the model cannot deal is ignored, not refused.
        assertNull(ShootoutRun(bench, ShootoutLog(deck = "me"), pinned = Stratum.SIDED_FIRST).pinned)
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
