package com.kaiharimoto.mastertool.core.shootout.sim

import com.kaiharimoto.mastertool.core.shootout.model.Hand
import com.kaiharimoto.mastertool.core.shootout.model.ModelSpec
import com.kaiharimoto.mastertool.core.shootout.model.Rated
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.model.Trial
import com.kaiharimoto.mastertool.core.shootout.select.Proposal
import com.kaiharimoto.mastertool.core.shootout.select.Reason
import com.kaiharimoto.mastertool.core.shootout.store.AiVerdict
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import com.kaiharimoto.mastertool.core.shootout.teach.HandKinds
import com.kaiharimoto.mastertool.core.shootout.teach.Route
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import com.kaiharimoto.mastertool.core.shootout.teach.TrustState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Teaching Ai, simulated (stage 3, S.md §6½): a person and an Ai judge of known accuracy ([SimJudge]s) answering the
 * same hands of a [SyntheticDeck] matchup, kept as the app keeps them — the person's blind trial, and Ai's answer as a
 * trial of its own pointing at it, with what it was shown and how sure it said it was — so the confidence score, the
 * gate and the audits can be proved before any model judges a real hand.
 *
 * Hands are plain shuffles (the kinds of hand come up as often as they really do); cards are their numbers in the model.
 */
class TrustStudy(
    val world: SyntheticDeck,
    /** The person: a [SimJudge] answering as judge 0. */
    val person: SimJudge,
    /** Ai: a [SimJudge] answering as judge 1. */
    var ai: SimJudge,
    seed: Long,
    val settings: TrustSettings = TrustSettings(solo = true),
) {
    private val random = Random(seed)

    /** Your starters (the synthetic deck's role 0) and their interaction (their first four cards). */
    val kinds = HandKinds(
        starters = SyntheticDeck.ROLES.indices.filter { SyntheticDeck.ROLES[it] == 0 }.toSet(),
        interaction = (0 until 4).toSet(),
    )

    /** Every answer as the app keeps it. */
    val stored = ArrayList<StoredTrial>()

    /** Every answer as the model reads it, each with its judge. */
    val model = ArrayList<Trial>()

    private var n = 0
    private var clock = 1_000L

    /** Ai's solo hands and the audits, in order: what the solo phase did. */
    var solos = 0
        private set
    var audits = 0
        private set

    /** Each kind the audits closed, and how many solo hands Ai had judged in all when it was. */
    val closed = LinkedHashMap<String, Int>()

    fun kindOf(t: StoredTrial): String? = Stratum.entries.firstOrNull { it.name == t.stratum }?.let { s ->
        kinds.of(s, if (t.kind == StoredTrial.COMPARE) t.left else t.hand, t.opponent).key
    }

    fun trust(): TrustState = Trust.read(stored, settings, alone = false, print = PRINT, kindOf = ::kindOf)

    /** [count] hands the person judges and Ai predicts silently (apprentice mode, or a calibration set and its exam). */
    fun apprentice(count: Int) = repeat(count) { both(deal(), TeachModes.APPRENTICE) }

    /**
     * [count] hands routed by the gate (S.md §6½): Ai alone where it has earned the kind and is sure, a share of those back
     * to the person blind, the rest to the person with Ai predicting beside them. The state is read again every [every].
     */
    fun solo(count: Int, every: Int = 5) {
        var state = trust()
        repeat(count) { i ->
            if (i % every == 0) state = trust()
            val p = deal()
            val (aiTrial, sure) = aiAnswer(p)
            when (Trust.route(state, kindOf(p), sure, random)) {
                Route.SOLO -> {
                    solos++
                    keepAi(p, aiTrial, sure, of = null, mode = TeachModes.SOLO)
                }
                Route.AUDIT -> {
                    audits++
                    val id = keepPerson(p, TeachModes.AUDIT)
                    keepAi(p, aiTrial, sure, of = id, mode = TeachModes.AUDIT)
                    state = trust()
                    state.kinds.filter { it.closedAt != null }.forEach { k -> closed.getOrPut(k.kind.key) { solos } }
                }
                Route.PERSON -> {
                    val id = keepPerson(p, TeachModes.APPRENTICE)
                    keepAi(p, aiTrial, sure, of = id, mode = TeachModes.APPRENTICE)
                }
            }
        }
    }

    private fun both(p: Proposal.Rate, mode: String) {
        val (aiTrial, sure) = aiAnswer(p)
        val id = keepPerson(p, mode)
        keepAi(p, aiTrial, sure, of = id, mode = mode)
    }

    private fun deal(): Proposal.Rate {
        val s = world.spec.strata[random.nextInt(world.spec.strata.size)]
        val (hand, opp) = world.decks.deal(s, random)
        return Proposal.Rate(hand, opp, s, Reason.PLAIN)
    }

    private fun kindOf(p: Proposal.Rate): String = kinds.of(p.stratum, ids(p.hand), p.opponent?.let(::ids)).key

    /**
     * Ai's answer and how sure it says it is: surer the further its reading sits from the nearest cut-off, as a model
     * reading its own margin would be. It does not know its own noise, so a noisy judge is as sure as a steady one —
     * its certainty is only worth what its agreement shows.
     */
    private fun aiAnswer(p: Proposal.Rate): Pair<Rated, Double> {
        val t = ai.answer(p, n) as Rated
        val margin = ModelSpec.NOMINAL_CUTS.minOf { abs(ai.lastRead - it) }
        val sure = 0.6 + 0.38 * (1 - exp(-margin / 0.25))
        return t to sure
    }

    private fun keepPerson(p: Proposal.Rate, mode: String): String {
        val t = person.answer(p, n++) as Rated
        clock += 5_000
        val id = "p$n"
        model += t
        stored += StoredTrial(
            id = id, at = clock, stratum = p.stratum.name, hand = ids(p.hand), opponent = p.opponent?.let(::ids),
            answer = t.answer.name, reason = "plain", mode = mode,
        )
        return id
    }

    private fun keepAi(p: Proposal.Rate, t: Rated, sure: Double, of: String?, mode: String) {
        clock += 1
        model += t
        stored += StoredTrial(
            id = "a${stored.size}", at = clock, stratum = p.stratum.name, hand = ids(p.hand), opponent = p.opponent?.let(::ids),
            answer = t.answer.name, judge = StoredTrial.AI, of = of, mode = mode,
            // Asked before the person answered, shown none of their answers: held out by construction.
            ai = AiVerdict(answer = t.answer.name, sure = sure, kind = kindOf(p), print = PRINT, asked = clock - 2_000),
        )
    }

    private fun ids(h: Hand): List<Int> = h.cards.flatMap { c -> List(h[c]) { c } }

    companion object {
        const val PRINT = "sim"
    }
}
