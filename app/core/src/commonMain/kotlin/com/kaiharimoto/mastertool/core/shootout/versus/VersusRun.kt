package com.kaiharimoto.mastertool.core.shootout.versus

import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.bench.SeenDraws
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutPin
import com.kaiharimoto.mastertool.core.shootout.bench.Swap
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Fit
import com.kaiharimoto.mastertool.core.shootout.model.Fitter
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import kotlin.random.Random

/**
 * A hand of card against card: [proposal] dealt from the deck as built, or — [substituted] — from the deck with the
 * substitute in the card's place. Which deck is never shown beyond the card itself; it is kept so a draw by an effect
 * comes off the deck the hand was dealt from.
 */
data class VersusDeal(val proposal: Proposal.Rate, val substituted: Boolean)

/**
 * Card against card (2026-10, kai: "compare two cards … the deck would be the same except one card is drawn with the
 * other"): a Shootout whose hands come from two decks, the deck as built and the deck with a substitute in every copy of
 * one card's place, about half each. Both are read by one model over one numbering, so the two cards are rated on the
 * same scale, each beside every other card ([Bench.swap]'s pairs), and [results] says which is better on its own, beside
 * which cards, and which deck does better overall ([VersusResults]).
 *
 * Plain Kotlin with no clock and no thread, as [com.kaiharimoto.mastertool.core.shootout.bench.ShootoutRun]: the page
 * runs it off the frame thread and keeps the log on disk. A pin is kept as a Shootout's is ([ShootoutPin]): going second
 * only never deals going first.
 */
class VersusRun(
    val bench: Bench,
    log: ShootoutLog,
    pinned: Stratum? = null,
    seed: Long = 7L,
    /** The share of hands dealt holding the card being compared (its deck's version of it); the rest are plain shuffles. */
    private val focus: Double = FOCUS,
) {
    val swap: Swap = requireNotNull(bench.swap) { "card against card needs a bench with a swap" }

    var log: ShootoutLog = log
        private set

    private val model = ArrayList<Trial>()

    var fit: Fit
        private set

    val pinned: Stratum? = ShootoutPin.carry(pinned, bench.spec.strata).pin

    /** The strata this session deals in. */
    val strata: List<Stratum> = pinned?.let { listOf(it) } ?: bench.spec.strata

    private val random = Random(seed)

    init {
        log.trials.forEach(::read)
        fit = Fitter.fit(bench.spec, model)
    }

    private fun read(t: StoredTrial): Boolean {
        val read = bench.trial(t) ?: return false
        model += read
        return true
    }

    /** How many answers the model reads. */
    val fitted: Int get() = model.size

    /** Hands answered that held the card, and that held the substitute, in the strata dealt now. */
    fun held(): Pair<Int, Int> {
        val rated = model.filterIsInstance<Rated>().filter { it.stratum in strata }
        return rated.count { it.hand.has(swap.card) } to rated.count { it.hand.has(swap.substitute) }
    }

    /**
     * The next hand: a stratum (the one fallen behind, else any), a deck (the one whose card has been seen less, else
     * either, about half each), then a shuffle of it — most of the time one that holds that deck's card, since a hand
     * holding neither tells the two apart only through the rest of the deck.
     */
    fun next(): VersusDeal {
        val stratum = laggingStratum()
        val (cards, substitutes) = held()
        val substituted = when {
            substitutes + BALANCE < cards -> true
            cards + BALANCE < substitutes -> false
            else -> random.nextBoolean()
        }
        val decks = if (substituted) swap.decks else bench.decks
        val mine = if (substituted) swap.substitute else swap.card
        val focused = random.nextDouble() < focus
        var dealt = decks.deal(stratum, random)
        if (focused) {
            var tries = 0
            while (!dealt.first.has(mine) && tries++ < MAX_TRIES) dealt = decks.deal(stratum, random)
        }
        val reason = if (focused) Reason.CHOSEN else Reason.PLAIN
        return VersusDeal(Proposal.Rate(dealt.first, dealt.second, stratum, reason), substituted)
    }

    private fun laggingStratum(): Stratum {
        if (strata.size == 1) return strata.first()
        val counts = strata.associateWith { s -> model.count { it.stratum == s } }
        val least = counts.values.min()
        val behind = strata.filter { counts.getValue(it) == least }
        return if (counts.values.max() - least > BALANCE) behind[random.nextInt(behind.size)] else strata[random.nextInt(strata.size)]
    }

    /** The person's answer to [deal], kept and fitted; the kept trial. */
    fun answer(
        deal: VersusDeal, answer: Answer, id: String, at: Long, ms: Long? = null, session: String? = null,
        draws: SeenDraws = SeenDraws.NONE,
    ): StoredTrial = bench.rated(deal.proposal, answer, id, at, ms, session, draws = draws).also(::record)

    /** [trial] kept and fitted. */
    fun record(trial: StoredTrial) {
        log = log.plus(trial)
        if (read(trial)) fit = Fitter.fit(bench.spec, model, fit.theta)
    }

    /** The log with [change] made to its trials (an answer changed, a hand erased), the fit read again from the start. */
    fun rewrite(change: (ShootoutLog) -> ShootoutLog) {
        log = change(log)
        model.clear()
        log.trials.forEach(::read)
        fit = Fitter.fit(bench.spec, model)
    }

    /** What the trials say, over hands of the strata dealt now. */
    fun results(pool: Int = VersusResults.POOL): VersusResults = VersusResults.read(bench, fit, model, strata, pool)

    /** The session's progress: the comparison of the cards on their own, read over fewer hands than the results. */
    fun progress(): VersusResults = results(VersusResults.QUICK_POOL)

    companion object {
        /** Four hands in five hold the card being compared; the fifth is a plain shuffle of its deck. */
        const val FOCUS = 0.8

        /** How far the two decks' cards, or the strata, may drift apart in hands before the lagging one is dealt. */
        const val BALANCE = 3

        /** Shuffles tried for a hand holding the card: a single copy in sixty turns up in about one in twelve. */
        const val MAX_TRIES = 2000

        /** About ten minutes is a session, as a Shootout's. */
        const val SESSION_MS = 10 * 60_000L
    }
}
