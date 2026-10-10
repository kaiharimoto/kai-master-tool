package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.BenchInput
import com.kaiharimoto.mastertool.core.shootout.bench.CardSwap
import com.kaiharimoto.mastertool.core.shootout.bench.Opponent
import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPaths
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.VersusPick
import com.kaiharimoto.mastertool.core.shootout.versus.VersusRun
import com.kaiharimoto.mastertool.core.shootout.versus.VersusWords
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Card against card (2026-10, kai): the deck dealt with a card or its substitute, one model over both, and what it says —
 * which card is better on its own, beside which cards, and which deck does better overall.
 */
class VersusTest {

    private val pool: Map<Int, Card> = buildMap {
        for (i in 1..40) put(1000 + i, Card(CardId(1000 + i), "Card $i", "Effect Monster", "effect"))
        for (i in 1..30) put(2000 + i, Card(CardId(2000 + i), "Theirs $i", "Spell Card", "spell"))
        put(3001, Card(CardId(3001), "A Link", "Link Monster", "link"))
    }
    private val lookup: (CardId) -> Card? = { pool[it.value] }

    /** Card 1 three times (the card compared), cards 2–9 three times, cards 10–25 once: 43 cards. */
    private val deck = Deck(main = ((1..9).flatMap { listOf(1000 + it, 1000 + it, 1000 + it) } + (10..25).map { 1000 + it }).map(::CardId))
    private val card = 1001
    private val substitute = 1031
    private val partner = 1002
    private val swap = CardSwap(CardId(card), CardId(substitute))

    private fun bench(opponent: Opponent? = null) = Bench.of(BenchInput(deck, lookup, opponent = opponent, swap = swap))

    @Test
    fun theTwoDecksAreTheSameButForTheCard() {
        val b = bench()
        val s = assertNotNull(b.swap)
        assertEquals(card, b.own[s.card])
        assertEquals(substitute, b.own[s.substitute])
        for (st in Bench.ALONE) {
            val a = b.decks.own(st)
            val other = s.decks.own(st)
            assertEquals(a.size, other.size)
            assertEquals(3, a[s.card]); assertEquals(0, a[s.substitute])
            assertEquals(0, other[s.card]); assertEquals(3, other[s.substitute])
            for (c in 0 until a.universe) if (c != s.card && c != s.substitute) assertEquals(a[c], other[c])
        }
        // A pair for each of the two cards beside every other card, so a partner has a number of its own.
        val others = b.own.indices.count { it != s.card && it != s.substitute }
        assertEquals(2 * others, b.spec.pairs.size)
        assertTrue(b.spec.pairs.all { it.a == s.card || it.b == s.card || it.a == s.substitute || it.b == s.substitute })
    }

    @Test
    fun aMatchupComparesInGameOneOnly() {
        val theirs = Deck(main = ((1..10).flatMap { listOf(2000 + it, 2000 + it, 2000 + it) } + (11..20).map { 2000 + it }).map(::CardId))
        val b = bench(Opponent("them", "Them", theirs))
        assertEquals(listOf(Stratum.G1_FIRST, Stratum.G1_SECOND), b.strata)
        assertTrue(b.waiting.isEmpty())
    }

    @Test
    fun problemsAreSaid() {
        assertNull(Bench.problem(BenchInput(deck, lookup, swap = swap)))
        assertEquals("Card 40 is not in the main deck.", Bench.problem(BenchInput(deck, lookup, swap = CardSwap(CardId(1040), CardId(1031)))))
        assertEquals("Choose a different card to compare it with.", Bench.problem(BenchInput(deck, lookup, swap = CardSwap(CardId(card), CardId(card)))))
        assertEquals("An Extra Deck card never opens in a hand.", Bench.problem(BenchInput(deck, lookup, swap = CardSwap(CardId(card), CardId(3001)))))
    }

    @Test
    fun aPinDealsOnlyItsTurnAndBothDecksAboutHalfEach() {
        val b = bench()
        val run = VersusRun(b, ShootoutLog(deck = "d"), pinned = Stratum.ALONE_SECOND, seed = 9)
        val s = b.swap!!
        var substituted = 0
        var holding = 0
        repeat(200) { i ->
            val deal = run.next()
            assertEquals(Stratum.ALONE_SECOND, deal.proposal.stratum)
            assertEquals(6, deal.proposal.hand.size)
            // A hand of one deck never holds the other deck's card.
            if (deal.substituted) assertTrue(!deal.proposal.hand.has(s.card)) else assertTrue(!deal.proposal.hand.has(s.substitute))
            if (deal.substituted) substituted++
            if (deal.proposal.hand.has(if (deal.substituted) s.substitute else s.card)) holding++
            run.answer(deal, Answer.COIN_FLIP, "t$i", at = i.toLong())
        }
        assertTrue(substituted in 80..120, "about half the hands from each deck: $substituted of 200")
        assertTrue(holding >= 150, "most hands hold the card compared: $holding of 200")
        assertTrue(run.log.trials.all { it.stratum == Stratum.ALONE_SECOND.name })
    }

    /**
     * A judge who knows the substitute is worth a whole point of log-odds more than the card, and better still beside
     * card 2: the results say so on all three counts — on its own, beside card 2, and the deck overall.
     */
    @Test
    fun theResultsFindTheBetterCardOnItsOwnBesideAPartnerAndOverall() {
        val b = bench()
        val s = b.swap!!
        val p = b.own.indexOf(partner)
        val random = Random(3)
        val worth = DoubleArray(b.own.size) { (it % 5 - 2) * 0.15 }
        worth[s.card] = 0.0
        worth[s.substitute] = 1.0
        fun truth(hand: Hand): Double {
            var v = -0.4
            for (c in hand.cards) v += worth[c] * hand[c].coerceAtMost(1)
            if (hand.has(s.substitute) && hand.has(p)) v += 1.2
            return v
        }
        val cuts = doubleArrayOf(0.2, 0.4, 0.6, 0.8).map(Logistic::logit)
        val deal = VersusRun(b, ShootoutLog(deck = "d"), seed = 4)
        val trials = ArrayList<StoredTrial>()
        repeat(500) { i ->
            val d = deal.next()
            val u = random.nextDouble().coerceIn(1e-6, 1 - 1e-6)
            val latent = truth(d.proposal.hand) + 0.4 * ln(u / (1 - u))
            val answer = Answer.entries[cuts.count { latent > it }]
            trials += b.rated(d.proposal, answer, "t$i", at = i.toLong(), ms = null, session = "s")
        }
        val run = VersusRun(b, ShootoutLog(deck = "d", trials = trials), seed = 4)
        val r = run.results()
        assertEquals(card, r.card)
        assertEquals(substitute, r.substitute)
        assertTrue(r.alone.value > 0 && r.alone.excludesZero, "the substitute is better on its own: ${r.alone}")
        assertEquals(VersusWords.Call.BETTER, VersusWords.call(r.alone))
        assertTrue(r.overall.value > 0 && r.overall.excludesZero, "the deck with the substitute does better: ${r.overall}")
        for (side in r.sides) {
            assertTrue(side.withSubstitute.value > side.withCard.value)
            val beside = assertNotNull(side.partners.firstOrNull { it.partner == partner }, "card 2 is held with the card often enough")
            assertTrue(beside.synergy.value > 0, "card 2 favours the substitute: ${beside.synergy}")
        }
        assertTrue(r.telling.any { it.second.partner == partner }, "card 2 tells the two apart")
        // Going second the card is rated as the turn's draw apart from the five (kai's decision).
        val second = r.sides.first { it.stratum == Stratum.ALONE_SECOND }
        assertNotNull(second.drawn)
        assertTrue(second.drawnHands > 0)
        assertNull(r.sides.first { it.stratum == Stratum.ALONE_FIRST }.drawn)
        assertEquals(trials.count { t -> card in t.hand }, r.withCard)
    }

    /** With no difference between the two, nothing is called better. */
    @Test
    fun twoCardsAlikeAreNeverCalledApart() {
        val b = bench()
        val random = Random(8)
        val deal = VersusRun(b, ShootoutLog(deck = "d"), seed = 2)
        val trials = List(300) { i ->
            val d = deal.next()
            val answer = Answer.entries[random.nextInt(2, 4)]
            b.rated(d.proposal, answer, "t$i", at = i.toLong(), ms = null, session = "s")
        }
        val r = VersusRun(b, ShootoutLog(deck = "d", trials = trials)).results()
        assertTrue(!r.alone.excludesZero, "${r.alone}")
        assertTrue(VersusWords.call(r.alone) != VersusWords.Call.BETTER)
    }

    @Test
    fun theLogKeepsWhatItCompares() {
        val log = ShootoutLog(deck = "d", versus = VersusPick(card, substitute))
        val back = assertNotNull(ShootoutCodec.decode(ShootoutCodec.encode(log)))
        assertEquals(VersusPick(card, substitute), back.versus)
        // An ordinary log reads as before, with no comparison.
        assertNull(ShootoutCodec.decode("""{"deck":"d","trials":[]}""")?.versus)
        assertEquals("shootout/d/versus/alone.1001.1031.json", ShootoutPaths.versus("d", null, card, substitute))
        assertEquals("shootout/d/versus/x~2ey.1001.1031.json", ShootoutPaths.versus("d", "x.y", card, substitute))
    }

    @Test
    fun theWordsSayWhichCardAndHowSure() {
        val sure = Estimate(6.0, 1.5)
        assertEquals("Ash is better by 6 points", VersusWords.verdict(sure, "Droll", "Ash"))
        val leaning = Estimate(-3.0, 2.0)
        assertEquals("Droll leans ahead by 3 points", VersusWords.verdict(leaning, "Droll", "Ash"))
        assertEquals("No real difference", VersusWords.verdict(Estimate(0.3, 0.5), "Droll", "Ash"))
        assertEquals("Too close to call: Ash ahead by 2 points", VersusWords.verdict(Estimate(2.0, 4.0), "Droll", "Ash"))
    }
}
