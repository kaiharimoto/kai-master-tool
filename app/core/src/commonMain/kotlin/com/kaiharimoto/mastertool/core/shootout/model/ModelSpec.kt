package com.kaiharimoto.mastertool.core.shootout.model

import com.kaiharimoto.mastertool.core.shootout.math.Logistic
import kotlin.math.ln
import kotlin.math.pow

/** Two of your cards whose being held together may be worth more (a combo) or less (redundancy) than apart. */
data class CardPair(val a: Int, val b: Int) {
    init {
        require(a < b) { "a pair names its lower card first ($a, $b)" }
    }
}

/**
 * Where the ratings start (Phase S §2): weak priors for cards, tight ones for pairs, all in log-odds of a win.
 * They are shown to the person, so each is a plain number with a plain meaning.
 *
 * The defaults were tuned by the simulation (`shootout/sim`, S.md "Simulation results").
 */
data class Priors(
    /** Each role's average card, before any trial: Ai's or the groups' reading of the deck. Missing roles are 0. */
    val roleMeans: List<Double> = emptyList(),
    /** How far a role's average may plausibly sit from [roleMeans]. */
    val roleSd: Double = 0.8,
    /** How far one card may sit from its role's average. */
    val cardSd: Double = 0.6,
    /** How far a card's worth in one stratum may sit from its worth across the matchup (§1½'s pooling). */
    val deviationSd: Double = 0.25,
    /** How far a pair is held at zero: tight, so a pair effect must earn its place. */
    val pairSd: Double = 0.5,
    /** How far an opponent's card may sit from their cards' average. */
    val opponentSd: Double = 0.6,
    /** How far a stratum's starting point may sit from an even game. */
    val interceptSd: Double = 1.5,
    /**
     * How far a judge other than the reference may sit from the bands they name (a lean toward win or loss). Wide (stage
     * 3): a judge a whole band optimistic must be measured as leaning, not read as every card being better (the
     * simulation's biased judge moved the win rate 8 points at 0.25).
     */
    val cutSd: Double = 0.8,
    /**
     * How far a judge other than the reference may read one card differently from the person, per copy, in log-odds
     * (stage 3, S.md §6½): a blind spot — Ai rating a brick as a starter — is that judge's own, measured where it overlaps
     * the person's answers, and never moves the card's rating, which stays the person's.
     */
    val judgeCardSd: Double = 0.5,
    /** A judge's precision on the five-point scale (1 / noise), as its log: the middle and the spread. */
    val precisionLog: Double = ln(1.5),
    val precisionLogSd: Double = 0.5,
    /** A judge's precision between two hands, as its log. */
    val comparePrecisionLog: Double = ln(1.2),
    val comparePrecisionLogSd: Double = 0.5,
    /**
     * How much less precise a judge other than the reference (Ai, the person after seeing Ai) is taken to be before its
     * answers are measured, as a log (stage 3, S.md §6½): its answers count for little until where it overlaps the
     * person's blind answers shows they deserve more — "an Ai answer counts only as much as Ai has shown it can be trusted".
     */
    val otherPrecisionDrop: Double = ln(2.0),
) {
    /** Judge [judge]'s prior middle for its five-point precision, as a log. */
    fun precisionMean(judge: Int): Double = if (judge == 0) precisionLog else precisionLog - otherPrecisionDrop

    /** Judge [judge]'s prior middle for its precision between two hands, as a log. */
    fun comparePrecisionMean(judge: Int): Double = if (judge == 0) comparePrecisionLog else comparePrecisionLog - otherPrecisionDrop
}

/**
 * What one model instance holds (Phase S §2): the deck's cards and their roles, the opponent's cards (none for
 * the deck alone), the pairs in play, the strata, and how many judges.
 *
 * A pair is in play only when named: about twenty-five cards make three hundred pairs, most of them nothing. The
 * pairs come from the deck's groups and Ai's reading of the cards (§6), and each one named costs trials.
 */
class ModelSpec(
    val cards: Int,
    val strata: List<Stratum>,
    val roles: IntArray = IntArray(cards),
    val opponentCards: Int = 0,
    val pairs: List<CardPair> = emptyList(),
    val judges: Int = 1,
    val priors: Priors = Priors(),
    /** Each copy beyond the first is worth this fraction of the one before: a third copy is not a first. */
    val copyDecay: Double = 0.5,
    /**
     * The share of answers taken to be slips (a key pressed at random, a hand misread outright). Each answer is
     * then "this, or a slip", so one wild answer on a hand the picker leaned on cannot drag a card far.
     *
     * Off by default: with the noise also fitted, a slip term lets the fit call the judge noiseless and every miss a
     * slip (the simulation's fitted noise fell to a twentieth of the truth). It is a seam for a judge whose noise is
     * fixed, such as a goldfish simulator (S.md §6).
     */
    val lapse: Double = 0.0,
) {
    init {
        require(cards > 0) { "a model needs cards" }
        require(roles.size == cards) { "every card needs a role" }
        require(strata.isNotEmpty() && strata.distinct().size == strata.size) { "strata must be distinct and present" }
        require(strata.all { it.alone } || strata.none { it.alone }) {
            "the deck alone and a matchup are separate models (S.md §1½)"
        }
        require(pairs.all { it.b < cards } && pairs.distinct().size == pairs.size) { "pairs must be distinct cards" }
        require(judges >= 1 && copyDecay in 0.0..1.0 && lapse in 0.0..0.5)
    }

    /** How many roles the cards are sorted into. */
    val roleCount: Int = (roles.maxOrNull() ?: 0) + 1

    /** The worth of [n] copies, in units of the first: 1, 1 + d, 1 + d + d², … */
    fun copies(n: Int): Double = when (n) {
        0 -> 0.0
        1 -> 1.0
        else -> if (copyDecay == 1.0) n.toDouble() else (1.0 - copyDecay.pow(n)) / (1.0 - copyDecay)
    }

    /** Where [stratum] sits in [strata], or -1. */
    fun stratumIndex(stratum: Stratum): Int = strata.indexOf(stratum)

    val layout: Layout by lazy { Layout(this) }

    companion object {
        /**
         * Where the five answers' cut-offs sit for a judge with no lean: the log-odds of 20 %, 40 %, 60 % and 80 %.
         * The answers name bands of win chance, so a hand's value reads straight as a win chance.
         */
        val NOMINAL_CUTS: DoubleArray = doubleArrayOf(0.2, 0.4, 0.6, 0.8).map(Logistic::logit).toDoubleArray()
    }
}

/**
 * Where each parameter sits in the fit's one vector. Blocks in this order, so a hand's parameters come out sorted
 * when walked block by block: role averages, cards, per-stratum deviations (only with more than one stratum),
 * pairs, the opponent's average and cards, the strata's starting points, each judge's two precisions, and the
 * cut-offs of every judge but the first.
 *
 * **Judge 0 (the person, blind) is the reference.** Their five answers *mean* the bands of win chance, so their
 * cut-offs are fixed at [ModelSpec.NOMINAL_CUTS] and only their noise is fitted. Without that anchor the scale is
 * not identified (stretch every value, the cut-offs and the noise together and every answer is as likely), and
 * the priors, which pull card values toward zero, would shrink the whole scale instead of only the doubtful cards.
 * Every other judge (Ai, the person after seeing Ai) gets four cut-offs of their own, read against the reference
 * on the hands they share: their lean is measured and corrected, not averaged in (S.md §6½).
 */
class Layout(spec: ModelSpec) {
    private val k = spec.cards
    private val s = spec.strata.size

    /** Deviations per card: one per stratum when there is more than one, else none (nothing to pool). */
    val deviationsPerCard: Int = if (s > 1) s else 0

    val roles: Int = 0
    val cardBase: Int = roles + spec.roleCount
    val deviationBase: Int = cardBase + k
    val pairBase: Int = deviationBase + k * deviationsPerCard
    val opponentMean: Int = pairBase + spec.pairs.size
    val opponentBase: Int = opponentMean + if (spec.opponentCards > 0) 1 else 0
    val interceptBase: Int = opponentBase + spec.opponentCards
    val judgeBase: Int = interceptBase + s
    private val cutBase: Int = judgeBase + 2 * spec.judges

    /** Each judge but the reference's own reading of each card (stage 3): its blind spots, kept apart. */
    private val judgeCardBase: Int = cutBase + 4 * (spec.judges - 1)

    /** How many parameters the model fits. */
    val size: Int = judgeCardBase + k * (spec.judges - 1)

    /** Judge [judge]'s own reading of card [c], beside the shared value; -1 for the reference judge, who has none. */
    fun judgeCard(judge: Int, c: Int): Int = if (judge == 0) -1 else judgeCardBase + (judge - 1) * k + c

    fun role(r: Int): Int = roles + r
    fun card(c: Int): Int = cardBase + c
    fun deviation(c: Int, stratum: Int): Int = deviationBase + c * deviationsPerCard + stratum
    fun pair(p: Int): Int = pairBase + p
    fun opponent(o: Int): Int = opponentBase + o
    fun intercept(stratum: Int): Int = interceptBase + stratum
    fun precision(judge: Int): Int = judgeBase + 2 * judge
    fun comparePrecision(judge: Int): Int = judgeBase + 2 * judge + 1

    /** Judge [judge]'s offset of cut-off [k] from its band's edge, or -1 for the reference judge, who has none. */
    fun cut(judge: Int, k: Int): Int = if (judge == 0) -1 else cutBase + 4 * (judge - 1) + k

    /** Judge [judge]'s four cut-offs under [theta]. */
    fun cuts(theta: DoubleArray, judge: Int): DoubleArray =
        DoubleArray(4) { ModelSpec.NOMINAL_CUTS[it] + if (judge == 0) 0.0 else theta[cut(judge, it)] }
}
