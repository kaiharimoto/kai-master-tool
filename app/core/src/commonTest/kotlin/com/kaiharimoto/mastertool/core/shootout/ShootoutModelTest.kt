package com.kaiharimoto.mastertool.core.shootout

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.CardPair
import com.kaiharimoto.mastertool.core.shootout.model.Compared
import com.kaiharimoto.mastertool.core.shootout.model.DeckList
import com.kaiharimoto.mastertool.core.shootout.model.Decks
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.HandValue
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Posterior
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Reporter
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.sim.SyntheticDeck
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The model of Phase S §2: its derivatives, its fit and its reported numbers, each against a plain re-derivation. */
class ShootoutModelTest {

    private val world = SyntheticDeck.matchup(seed = 3)
    private val spec = world.spec

    /** Some answered trials of every kind, answered at random, for checking derivatives (not values). */
    private fun someTrials(n: Int, seed: Int): List<Trial> {
        val r = Random(seed)
        return List(n) { i ->
            val s = spec.strata[i % spec.strata.size]
            val (hand, opp) = world.decks.deal(s, r)
            if (i % 3 == 2) {
                val out = hand.cards.first()
                val into = (0 until spec.cards).first { it != out && world.decks.own(s).rest(hand)[it] > 0 }
                Compared(hand, hand.swap(out, into), opp, s, r.nextBoolean())
            } else {
                Rated(hand, opp, s, Answer.entries[r.nextInt(5)])
            }
        }
    }

    @Test
    fun theGradientAndCurvatureAreTheLogDensitysOwn() {
        val posterior = Posterior(spec, someTrials(60, 1))
        val r = Random(9)
        val theta = DoubleArray(spec.layout.size) { 0.3 * (r.nextDouble() - 0.5) }
        for (j in 0 until spec.judges) theta[spec.layout.precision(j)] = 0.4
        val eval = posterior.evaluate(theta)
        assertEquals(posterior.logDensity(theta), eval.logDensity, 1e-9)
        val h = 1e-5
        for (i in theta.indices) {
            val up = theta.copyOf().also { it[i] += h }
            val down = theta.copyOf().also { it[i] -= h }
            val numeric = (posterior.logDensity(up) - posterior.logDensity(down)) / (2 * h)
            assertEquals(numeric, eval.gradient[i], 1e-5 * max(1.0, abs(numeric)), "∂ log p / ∂θ[$i]")
            val gUp = posterior.evaluate(up).gradient
            val gDown = posterior.evaluate(down).gradient
            for (k in theta.indices) {
                val second = -(gUp[k] - gDown[k]) / (2 * h)
                assertEquals(second, eval.negHessian[k, i], 1e-4 * max(1.0, abs(second)), "−∂² log p / ∂θ[$k]∂θ[$i]")
            }
        }
    }

    @Test
    fun aSecondJudgesCutOffsAndASlipTermHaveTheirOwnDerivatives() {
        // Judge 1 (Ai, say) has cut-offs of its own; a slip term mixes each answer with a random key.
        val two = ModelSpec(spec.cards, spec.strata, spec.roles, spec.opponentCards, spec.pairs, judges = 2, lapse = 0.05)
        val trials = someTrials(40, 6).mapIndexed { i, t ->
            if (i % 2 == 0) t else when (t) {
                is Rated -> t.copy(judge = 1)
                is Compared -> t.copy(judge = 1)
            }
        }
        val posterior = Posterior(two, trials)
        val theta = posterior.start
        val r = Random(13)
        for (i in theta.indices) theta[i] += 0.1 * (r.nextDouble() - 0.5)
        val eval = posterior.evaluate(theta)
        val h = 1e-5
        for (i in theta.indices) {
            val up = theta.copyOf().also { it[i] += h }
            val down = theta.copyOf().also { it[i] -= h }
            val numeric = (posterior.logDensity(up) - posterior.logDensity(down)) / (2 * h)
            assertEquals(numeric, eval.gradient[i], 1e-5 * max(1.0, abs(numeric)), "∂ log p / ∂θ[$i]")
            val gUp = posterior.evaluate(up).gradient
            val gDown = posterior.evaluate(down).gradient
            for (k in theta.indices) {
                val second = -(gUp[k] - gDown[k]) / (2 * h)
                assertEquals(second, eval.negHessian[k, i], 1e-4 * max(1.0, abs(second)), "−∂² log p / ∂θ[$k]∂θ[$i]")
            }
        }
        // Judge 1's cut-offs move; the reference judge's are fixed.
        assertEquals(-1, two.layout.cut(0, 2))
        assertTrue(two.layout.cut(1, 2) >= 0)
    }

    @Test
    fun theNoiseIsNotFittedToNothing() {
        // Fewer answers than parameters: the joint mode would call the judge noiseless. The fitted noise stays near
        // what the answers can support (the prior's middle is 1 / 1.5 ≈ 0.67).
        val fit = Fitter.fit(spec, someTrials(30, 7))
        val noise = exp(-fit.theta[spec.layout.precision(0)])
        assertTrue(noise > 0.3, "the fitted noise collapsed to $noise")
        assertTrue(fit.noiseSd[spec.layout.precision(0)] > 0)
    }

    @Test
    fun theFitReachesTheModeAndItsCurvature() {
        val trials = someTrials(120, 2)
        val fit = Fitter.fit(spec, trials)
        assertTrue(fit.converged, "converged in ${fit.iterations}")
        val eval = Posterior(spec, trials).evaluate(fit.theta)
        val noise = setOf(spec.layout.precision(0), spec.layout.comparePrecision(0))
        assertTrue(eval.gradient.indices.all { it in noise || abs(eval.gradient[it]) < 1e-4 }, "the gradient vanishes at the mode, noise aside")
        // A warm start from the mode stays there.
        val again = Fitter.fit(spec, trials, fit.theta)
        assertTrue(again.iterations <= 2)
        for (i in fit.theta.indices) assertEquals(fit.theta[i], again.theta[i], 1e-6)
    }

    @Test
    fun aSwappedValueIsTheValueOfTheSwappedHand() {
        val value = HandValue(spec)
        val r = Random(4)
        val theta = DoubleArray(spec.layout.size) { r.nextDouble(-1.0, 1.0) }
        repeat(200) {
            val s = it % 2
            val (hand, opp) = world.decks.deal(spec.strata[s], r)
            val out = hand.cards[r.nextInt(hand.cards.size)]
            val into = r.nextInt(spec.cards)
            val other = if (hand.draw != Hand.NONE && it % 4 == 3) hand.swapDraw(into) else hand.swap(out, into)
            val base = value.of(theta, s, hand, opp)
            assertEquals(value.of(theta, s, other, opp), value.swapped(theta, s, hand, base, other), 1e-12)
            val g = DoubleArray(theta.size).also { g -> value.addFeatures(g, 1.0, s, hand, opp) }
            value.addSwapFeatures(g, 1.0, s, hand, other)
            val direct = DoubleArray(theta.size).also { d -> value.addFeatures(d, 1.0, s, other, opp) }
            for (i in g.indices) assertEquals(direct[i], g[i], 1e-12)
            val x = value.features(s, hand, opp)
            assertEquals(base, x.dot(theta), 1e-12)
            assertTrue((1 until x.index.size).all { k -> x.index[k] > x.index[k - 1] }, "features sorted")
        }
    }

    @Test
    fun theTurnsDrawIsRatedApartFromTheFive() {
        // kai (2026-10): "the data from the 6th card should only count towards the card as a 6th draw and not muddy the
        // data of 5 card hands". A card drawn for the turn reads its own worth as the draw, never its worth in the five.
        val second = spec.strata.indexOfFirst { !it.goingFirst }
        val first = spec.strata.indexOfFirst { it.goingFirst }
        val l = spec.layout
        val value = HandValue(spec)
        val drawnOnly = Hand.of(spec.cards, 1, 2, 3, 4, 5, 0).withDraw(0)
        val x = value.features(second, drawnOnly, null)
        assertTrue(l.card(0) !in x.index && l.deviation(0, second) !in x.index, "a card held only as the draw is not in the five")
        assertEquals(1.0, x.value[x.index.indexOf(l.drawn(0))])
        // A second copy drawn adds what a second copy adds, as the draw; the opened copy is the five's.
        val again = value.features(second, Hand.of(spec.cards, 0, 1, 2, 3, 4, 0).withDraw(0), null)
        assertEquals(1.0, again.value[again.index.indexOf(l.card(0))])
        assertEquals(spec.copies(2) - spec.copies(1), again.value[again.index.indexOf(l.drawn(0))])
        // Going first there is no draw, and the five are read as before.
        val five = value.features(first, Hand.of(spec.cards, 0, 1, 2, 3, 4), null)
        assertTrue((0 until spec.cards).none { l.drawn(it) in five.index })
        // A hand of six kept without its draw is each card the draw by its share: the six readings averaged.
        val r = Random(9)
        val theta = DoubleArray(l.size) { r.nextDouble(-1.0, 1.0) }
        val unknown = Hand.of(spec.cards, 0, 0, 1, 2, 3, 4)
        val average = unknown.cards.sumOf { d -> unknown[d] / 6.0 * value.of(theta, second, unknown.withDraw(d), null) }
        assertEquals(average, value.of(theta, second, unknown, null), 1e-12)
    }

    @Test
    fun aCardCanBeGoodInTheFiveAndBadAsTheDraw() {
        // A deck alone where card 0 is wanted in the opening five but is no use drawn for the turn: the two numbers part.
        val deck = DeckList(intArrayOf(10, 10, 20))
        val decks = Decks.alone(deck)
        val alone = ModelSpec(3, listOf(Stratum.ALONE_FIRST, Stratum.ALONE_SECOND))
        val r = Random(13)
        val trials = List(600) { i ->
            val s = alone.strata[i % 2]
            val hand = deck.draw(s.handSize, r)
            val eta = -0.5 + 1.0 * alone.copies(hand.opened(0)) - 0.4 * alone.copies(hand.opened(2)) -
                (if (hand.draw == 0) 1.5 else 0.0)
            val read = eta + 0.5 * ln(r.nextDouble().let { it / (1 - it) })
            Rated(hand, null, s, Answer.entries[ModelSpec.NOMINAL_CUTS.count { read > it }])
        }
        val fit = Fitter.fit(alone, trials)
        val ratings = Reporter(alone, decks, 300).ratings(fit, trials)
        val five = ratings.cards(Stratum.ALONE_SECOND).associate { it.card to it.estimate.value }
        val drawn = ratings.drawn(Stratum.ALONE_SECOND).associate { it.card to it.estimate.value }
        assertTrue(five.getValue(0) > 0, "in the five: $five")
        assertTrue(drawn.getValue(0) < 0, "as the draw: $drawn")
        assertTrue(ratings.drawn(Stratum.ALONE_FIRST).isEmpty(), "going first has no draw")
    }

    @Test
    fun aReportedNumbersGradientIsItsOwn() {
        val reporter = Reporter(spec, world.decks, poolSize = 60, seed = 5)
        val theta = world.truth
        val base = reporter.contrasts(theta)
        val h = 1e-6
        val probes = listOf(spec.layout.card(0), spec.layout.card(9), spec.layout.pair(0), spec.layout.deviation(3, 1),
            spec.layout.opponent(2), spec.layout.intercept(0), spec.layout.drawn(4))
        for (i in probes) {
            val up = reporter.contrasts(theta.copyOf().also { it[i] += h })
            val down = reporter.contrasts(theta.copyOf().also { it[i] -= h })
            for (k in base.indices) {
                val numeric = (up[k].value - down[k].value) / (2 * h)
                assertEquals(numeric, base[k].gradient[i], 1e-5 * max(1.0, abs(numeric)), "${base[k].target} by θ[$i]")
            }
        }
    }

    @Test
    fun copiesBeyondTheFirstAreWorthLess() {
        assertEquals(0.0, spec.copies(0))
        assertEquals(1.0, spec.copies(1))
        assertEquals(1.5, spec.copies(2))
        assertEquals(1.75, spec.copies(3))
    }

    @Test
    fun aDeckDealsAsAShuffleWould() {
        val deck = DeckList(SyntheticDeck.COPIES)
        val r = Random(11)
        val n = 20000
        val held = IntArray(deck.universe)
        repeat(n) { val h = deck.draw(5, r); for (c in h.cards) held[c]++ }
        val exact = deck.drawShare(5)
        for (c in held.indices) assertEquals(exact[c], held[c].toDouble() / n, 0.012, "card $c")
        // Three of a three-of in 40: 1 − C(37,5)/C(40,5).
        assertEquals(1 - 435897.0 / 658008.0, exact[0], 1e-12)
        // Past five the last card dealt is the turn's draw: any card alike, by its copies.
        val drawnAs = IntArray(deck.universe)
        repeat(n) { val h = deck.draw(6, r); assertTrue(h.draw != Hand.NONE && h.size == 6); drawnAs[h.draw]++ }
        val share = deck.drawnShare()
        for (c in drawnAs.indices) assertEquals(share[c], drawnAs[c].toDouble() / n, 0.01, "card $c as the draw")
        val both = deck.bothShare(0, 1, 5)
        val dealt = (0 until n).count { val h = deck.draw(5, r); h.has(0) && h.has(1) }
        assertEquals(both, dealt.toDouble() / n, 0.01)
    }

    @Test
    fun aSpecRefusesWhatItCannotModel() {
        assertFailsWith<IllegalArgumentException> { ModelSpec(3, listOf(Stratum.ALONE_FIRST, Stratum.G1_FIRST)) }
        assertFailsWith<IllegalArgumentException> { CardPair(2, 1) }
        assertFailsWith<IllegalArgumentException> { Hand.of(3, 0).swap(1, 2) }
    }

    @Test
    fun theDeckAloneIsItsOwnModel() {
        // A tiny deck alone: two cards a hand wants, one it does not. Enough plain answers find which is which.
        val deck = DeckList(intArrayOf(10, 10, 20))
        val decks = Decks.alone(deck)
        val alone = ModelSpec(3, listOf(Stratum.ALONE_FIRST, Stratum.ALONE_SECOND))
        val r = Random(12)
        val trials = List(300) { i ->
            val s = alone.strata[i % 2]
            val hand = deck.draw(s.handSize, r)
            val eta = -1.0 + 0.8 * alone.copies(hand[0]) + 0.5 * alone.copies(hand[1]) - 0.4 * alone.copies(hand[2])
            val read = eta + 0.5 * ln(r.nextDouble().let { it / (1 - it) })
            Rated(hand, null, s, Answer.entries[ModelSpec.NOMINAL_CUTS.count { read > it }])
        }
        val fit = Fitter.fit(alone, trials)
        val ratings = Reporter(alone, decks, 300).ratings(fit, trials)
        for (s in alone.strata) {
            val byCard = ratings.cards(s).associate { it.card to it.estimate.value }
            assertTrue(byCard.getValue(0) > byCard.getValue(1) && byCard.getValue(1) > byCard.getValue(2), "$s: $byCard")
        }
    }
}
