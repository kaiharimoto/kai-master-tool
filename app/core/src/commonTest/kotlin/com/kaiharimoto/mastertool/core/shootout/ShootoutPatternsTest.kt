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
import com.kaiharimoto.mastertool.core.shootout.bench.PatternFamily
import com.kaiharimoto.mastertool.core.shootout.bench.PatternSorter
import com.kaiharimoto.mastertool.core.shootout.bench.PatternWords
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutPatterns
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.HandRates
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.siding.SidePlan
import com.kaiharimoto.mastertool.core.siding.Turn
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase S, S4.2: what the hands say — opening patterns read off the same fit as the cards, and siding plans compared on the
 * hands they leave — with every number opening the hands behind it.
 */
class ShootoutPatternsTest {

    private val pool: Map<Int, Card> = buildMap {
        for (i in 1..40) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect"))
        for (i in 1..40) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
    }
    private val lookup: (CardId) -> Card? = { pool[it.value] }

    /** 40 cards: ten three-ofs and ten one-ofs; two side cards. */
    private val mine: Deck = Deck(
        main = ((1..10).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (11..20).map { 1000 + it }).map(::CardId),
        side = listOf(1031, 1031, 1031).map(::CardId),
    )
    private val theirs: Deck = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))

    /** Starters 1001 and 1002; the brick 1009 a group of its own. */
    private val groups = DeckGroups(
        groups = listOf(DeckGroup("g-start", "Starters", 3, 0), DeckGroup("g-brick", "Bricks", 2, 1)),
        assignments = mapOf(CardId(1001) to "g-start", CardId(1002) to "g-start", CardId(1009) to "g-brick"),
    )

    /** A judge who wins with a starter and loses without one, likes 1031 and hates the brick 1009. */
    private fun judged(bench: Bench, trials: Int, seed: Int = 7): ShootoutRun {
        val run = ShootoutRun(bench, ShootoutLog(deck = "me", opponent = "opp", opponentName = "Yubel"), seed = 3)
        val random = Random(seed)
        repeat(trials) { i ->
            when (val p = run.next()) {
                is Proposal.Rate -> {
                    val ids = bench.ids(p.hand)
                    val v = (if (ids.any { it == 1001 || it == 1002 }) 1.4 else -1.4) + 0.9 * ids.count { it == 1031 } -
                        0.9 * ids.count { it == 1009 } + 0.3 * random.nextDouble()
                    val answer = ShootoutWords.SCALE.minByOrNull { a -> abs(a.score - Logistic.of(v)) }!!
                    run.answer(p, answer, "t$i", at = 1_000L + i, ms = 2_000, session = "s1")
                }
                is Proposal.Compare -> {
                    fun score(h: List<Int>) = (if (h.any { it == 1001 || it == 1002 }) 2 else 0) + h.count { it == 1031 } - h.count { it == 1009 }
                    run.prefer(p, score(bench.ids(p.left)) >= score(bench.ids(p.right)), "t$i", at = 1_000L + i, session = "s1")
                }
            }
        }
        return run
    }

    @Test
    fun aHandSortsByItsKindItsRolesAndTheirMix() {
        val bench = Bench.of(BenchInput(mine, lookup, groups))
        val sorter = PatternSorter(bench)
        assertTrue(sorter.roleful)
        assertEquals(listOf("Starters", "Bricks", Bench.UNGROUPED), bench.roleNames)
        val hand = assertNotNull(bench.hand(listOf(1001, 1009, 1003, 1004, 1005)))
        val keys = sorter.keysWith(hand, null)
        assertEquals(
            listOf(PatternSorter.kindKey(true, null), "role:0:1", "role:1:1", "role:2:2", "mix:0,1,2"),
            keys,
        )
        assertEquals("With a starter", sorter.pattern(keys[0])?.words)
        assertEquals("Ungrouped: two or more", sorter.pattern("role:2:2")?.words)
        assertEquals("Starters + Bricks + Ungrouped", sorter.pattern("mix:0,1,2")?.words)
        assertEquals(PatternFamily.MIX, sorter.pattern("mix:0,1,2")?.family)
        // Going second the draw is not the opening five's: a starter drawn for the turn does not make a hand "with a starter".
        val drawn = assertNotNull(bench.hand(listOf(1003, 1004, 1005, 1006, 1007, 1001), draw = 1001))
        assertEquals(PatternSorter.kindKey(false, null), sorter.keysWith(drawn, null).first())
        // No groups, one role: only the kind sorts.
        val plain = PatternSorter(Bench.of(BenchInput(mine, lookup)))
        assertTrue(!plain.roleful)
        assertEquals(1, plain.keysWith(assertNotNull(bench.hand(listOf(1003, 1004, 1005, 1006, 1007))), null).size)
    }

    @Test
    fun hardDataInARateCountsThePoolsOwnDoubt() {
        // Four hands at 40, 60, 40, 60 points: a mean of 50, a spread of 133.3 (n − 1), and so a doubt of 33.3 over four.
        val r = HandRates.Rate(4, 200.0, 40.0 * 40 * 2 + 60.0 * 60 * 2, DoubleArray(3))
        assertEquals(50.0, r.value, 1e-9)
        assertEquals(400.0 / 3 / 4, r.sampling, 1e-9)
        val part = HandRates.Rate(2, 80.0, 40.0 * 40 * 2, DoubleArray(3))
        val rest = r.minus(part)
        assertEquals(2, rest.hands)
        assertEquals(60.0, rest.value, 1e-9)
    }

    @Test
    fun theHandsWithAStarterWinAndTheTableSaysSo() {
        val opp = Opponent("opp", "Yubel", theirs)
        val bench = Bench.of(BenchInput(mine, lookup, groups, opponent = opp))
        val run = judged(bench, 160)
        val results = run.results()
        val s = Stratum.G1_FIRST
        val starts = results.patterns.first { it.pattern.key == PatternSorter.kindKey(true, false) }
        val none = results.patterns.first { it.pattern.key == PatternSorter.kindKey(false, false) }
        val a = starts.cells.getValue(s)
        val b = none.cells.getValue(s)
        assertTrue(a.rate.value > b.rate.value + 20, "a starter wins far more often: ${a.rate} against ${b.rate}")
        assertTrue(a.lift.value > 0 && b.lift.value < 0)
        // The rate and its lift agree with the stratum's whole win rate within the pool's doubt.
        val whole = results.winRates.getValue(s).value
        assertEquals(whole, a.rate.value - a.lift.value, 4.0)
        // The kinds split the hands: their shares add to one (the deck alone's two, a matchup's four).
        val kinds = results.patterns.filter { it.pattern.family == PatternFamily.KIND }
        assertEquals(1.0, kinds.sumOf { it.cells[s]?.share ?: 0.0 }, 0.04)
        // So do a role's counts: none, one, two or more Starters.
        val starters = results.patterns.filter { it.pattern.key.startsWith("role:0:") }
        assertEquals(1.0, starters.sumOf { it.cells[s]?.share ?: 0.0 }, 0.04)
        // A real starter-or-not deck: a hand of five holds one of six copies about 58 % of the time.
        assertEquals(0.58, starters.filter { !it.pattern.key.endsWith(":0") }.sumOf { it.cells.getValue(s).share }, 0.05)
        // Every number opens its trials.
        val sorter = PatternSorter(bench)
        val behind = ShootoutResults.trialsBehind(run.log.trials, Behind.Pattern(starts.pattern.key, s), patterns = sorter)
        assertEquals(a.trials, behind.size)
        assertTrue(behind.isNotEmpty() && behind.all { t -> t.stratum == s.name && t.hands.any { h -> h.any { it == 1001 || it == 1002 } } })
        // The table calls the starter: its lift is clear of zero with every cell counted, and the headline says it.
        val called = ShootoutPatterns.called(results.patterns)
        assertTrue((starts.pattern.key to s) in called)
        val line = assertNotNull(PatternWords.headline(results.patterns, s, called, alone = false))
        assertTrue("wins most" in line && "wins least" in line, line)
    }

    @Test
    fun aPlanIsComparedOnTheHandsItLeaves() {
        val opp = Opponent("opp", "Yubel", theirs)
        // Going first: the brick out, three of the card the judge likes in.
        val myFirst = SidePlan(out = List(3) { CardId(1009) }, into = List(3) { CardId(1031) })
        // Theirs going second: changes nothing in their Main Deck.
        val theirSecond = SidePlan(note = "nothing to side")
        val bench = Bench.of(BenchInput(mine, lookup, groups, opponent = opp, mine = mapOf(Turn.FIRST to myFirst), theirs = mapOf(Turn.SECOND to SidePlan(out = listOf(CardId(2001)), into = listOf(CardId(2040))))))
        assertTrue(Stratum.SIDED_FIRST in bench.strata)
        val run = judged(bench, 200)
        val plan = run.results().plans.single()
        assertEquals(Stratum.SIDED_FIRST, plan.stratum)
        assertEquals(setOf(1009, 1031), plan.yourCards)
        assertEquals(setOf(2001, 2040), plan.theirCards)
        val yours = assertNotNull(plan.yours)
        assertTrue(yours.value > 5, "the brick out and a good card in: $yours")
        assertTrue(yours.range95.start > 0, "and clear of zero: $yours")
        // Their swap is one card for one the judge never looks at: it moves nothing much.
        val theirs = assertNotNull(plan.theirs)
        assertTrue(abs(theirs.value) < yours.value, "their plan: $theirs")
        // The hands behind your number: that turn's, holding a card the plan moves.
        val behind = ShootoutResults.trialsBehind(run.log.trials, Behind.Plan(Stratum.SIDED_FIRST, plan.yourCards, theirs = false))
        assertEquals(plan.yourTrials, behind.size)
        assertTrue(behind.all { t -> !t.stratum.endsWith("SECOND") && t.hands.any { h -> h.any { it == 1009 || it == 1031 } } })
        // A plan that changes no Main Deck card has no number.
        val still = Bench.of(BenchInput(mine, lookup, groups, opponent = opp, mine = mapOf(Turn.FIRST to SidePlan(note = "nothing")), theirs = mapOf(Turn.SECOND to theirSecond)))
        val none = judged(still, 30).results().plans.single()
        assertNull(none.yours)
        assertNull(none.theirs)
        val words = PatternWords.plan(plan, "Yubel")
        assertEquals(2, words.size)
        assertTrue(words[0].startsWith("Your plan going first: +"), words[0])
    }

    @Test
    fun theDeckAloneHasNoInteractionAndNoPlans() {
        val bench = Bench.of(BenchInput(mine, lookup, groups))
        val results = judged(bench, 80).results()
        assertTrue(results.plans.isEmpty())
        val kinds = results.patterns.filter { it.pattern.family == PatternFamily.KIND }.map { it.pattern.key }
        assertEquals(listOf(PatternSorter.kindKey(true, null), PatternSorter.kindKey(false, null)), kinds)
        assertNull(results.patterns.firstOrNull { "interaction" in it.pattern.key })
        // At most six mixes, each common enough somewhere.
        val mixes = results.patterns.filter { it.pattern.family == PatternFamily.MIX }
        assertTrue(mixes.size in 1..ShootoutPatterns.MIXES)
        assertTrue(mixes.all { row -> row.cells.values.all { it.share >= ShootoutPatterns.MIN_SHARE } })
    }
}
