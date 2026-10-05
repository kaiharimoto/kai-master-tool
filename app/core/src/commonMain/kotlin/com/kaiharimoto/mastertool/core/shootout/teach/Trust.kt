package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.store.TrustSettings
import kotlin.math.abs
import kotlin.random.Random

/**
 * How far Ai has earned one kind of hand (S.md §6½ "The confidence score"), from the pairs that count: held out, on the
 * person's blind answers, since the kind was last closed by its audits. Pairs on decks other than today's count for
 * [Trust.STALE] of one, and a kind they stand behind must be shown again on today's decks ([Trust.RECHECK] pairs) before
 * it opens.
 */
class KindTrust(
    val kind: HandKind,
    /** Agreements and pairs, weighted. */
    val agree: Double,
    val pairs: Double,
    /** The 80 % Wilson range of the agreement. */
    val range: Range,
    /** The same counted only on the hands Ai said it was sure of (at least [TrustSettings.sure]): what the gate reads. */
    val sureAgree: Double,
    val surePairs: Double,
    val sureRange: Range,
    /** Pairs on today's decks, since the last closing. */
    val current: Int,
    /** Whether the kind holds evidence from decks as they were before a change. */
    val stale: Boolean,
    /** Ai may judge this kind alone. */
    val open: Boolean,
    /** Why not, in words; null when open. */
    val why: String?,
    /** Audits of this kind: all, and the misses among them. */
    val audits: Int,
    val misses: Int,
    /** When the audits last closed it, or null. */
    val closedAt: Long?,
    /** Ai's solo hands of this kind since it was last closed. */
    val solo: Int,
) {
    /** The share it agrees, or null with nothing to count. */
    val share: Double? get() = if (pairs > 0) agree / pairs else null
}

/** How well Ai's stated certainty matches how often it agreed (S.md §6½: "Ai's own certainty is scored too"). */
class Calibration(
    /** Pairs with a stated certainty. */
    val n: Int,
    /** The mean squared gap between the certainty and the outcome (0 perfect; 0.25 for always saying a half). */
    val brier: Double,
    /** The expected calibration error: how far, on average, the certainty sits from the agreement it buys. */
    val ece: Double,
    val bins: List<Bin>,
) {
    class Bin(val from: Double, val to: Double, val n: Int, val said: Double, val agreed: Double)

    /** Enough pairs, and its certainty within a tenth of the truth on average: it can be routed by what it is sure of. */
    val calibrated: Boolean get() = n >= Trust.CALIBRATION_MIN && ece <= Trust.CALIBRATION_ECE
}

/** One audit: an Ai solo hand sent back to the person blind, and whether they agreed. */
class Audit(val kind: String?, val at: Long, val agrees: Boolean, val person: StoredTrial, val ai: StoredTrial?)

/** How the person's answers move once they have seen Ai's (S.md §6½ "Supervised answers are kept, but marked"). */
class SeenDrift(
    /** Seen pairs, and how often the person gave exactly Ai's answer there. */
    val seen: Int,
    val sameSeen: Double?,
    /** The same on held-out blind pairs, where nothing could anchor them. */
    val blind: Int,
    val sameBlind: Double?,
) {
    /** How much more often the person matches Ai after seeing it: the anchoring, in points. */
    val drift: Double? get() = if (sameSeen != null && sameBlind != null) 100 * (sameSeen - sameBlind) else null
}

/** Everything the trust panel shows that the trials alone decide. */
class TrustState(
    val settings: TrustSettings,
    val kinds: List<KindTrust>,
    val calibration: Calibration,
    val audits: List<Audit>,
    val seen: SeenDrift,
    /** Every pair, and how many count. */
    val pairs: Int,
    val counted: Int,
    /** Ai's solo hands, and how many of its answers there are in all. */
    val solo: Int,
    val aiAnswers: Int,
    /** The person's own consistency on repeats (within one band), and how many repeats: the ceiling any judge meets. */
    val selfAgree: Double?,
    val repeats: Int,
) {
    fun kind(key: String?): KindTrust? = kinds.firstOrNull { it.kind.key == key }
    val open: List<KindTrust> get() = kinds.filter { it.open }
}

/** Where a hand goes (S.md §6½ "The gate"): to the person, to Ai alone, or to Ai and then back to the person blind. */
enum class Route { PERSON, SOLO, AUDIT }

/**
 * The confidence score, the gate and the audits (S.md §6½), read from the trials alone so nothing can drift from them.
 *
 * **Agreement** is measured only on pairs that count ([JudgedPair.counts]): the person's blind answer against what Ai
 * said with only the examples and rubric it had before. **Per kind of hand**, each with an 80 % Wilson range. **The gate**
 * opens a kind when the bottom of the range on the hands Ai was sure of clears the person's bar, with at least
 * [MIN_SURE] such hands — so a well-calibrated Ai keeps what it is sure of and sends the rest, and an Ai whose certainty
 * means nothing never clears it. **Audits**: [auditRate] of Ai's solo hands go back to the person blind; two misses
 * beyond what the range allows close the kind, and only pairs after that count toward opening it again. **A deck
 * change** makes the older pairs count for [STALE] of one, and every kind they stand behind must show [RECHECK] pairs on
 * today's decks — a short calibration set — before it opens again.
 */
object Trust {
    /** Pairs from older decks count for this much of one. */
    const val STALE = 0.5

    /** Pairs on today's decks a kind needs after a change before it opens again. */
    const val RECHECK = 3

    /** Hands Ai was sure of that a kind needs before it can open at all. */
    const val MIN_SURE = 10

    /** Solo hands of a kind audited at the early rate, after each opening. */
    const val EARLY = 20
    const val EARLY_RATE = 1.0 / 3
    const val RATE = 1.0 / 10

    const val CALIBRATION_MIN = 20
    const val CALIBRATION_ECE = 0.1

    /** The bars the person can choose. */
    val BARS = listOf(0.8, 0.85, 0.9, 0.95)

    fun read(
        trials: List<StoredTrial>,
        settings: TrustSettings,
        alone: Boolean,
        print: String?,
        kindOf: (StoredTrial) -> String?,
    ): TrustState {
        val pairs = JudgedPair.all(trials, kindOf)
        val counting = pairs.filter { it.counts }
        val audits = pairs.filter { it.person.mode == TeachModes.AUDIT && !it.seen }
            .map { Audit(it.kind, it.at, it.agrees, it.person, it.aiTrial) }
        val solos = trials.filter { it.judge == StoredTrial.AI && it.mode == TeachModes.SOLO }
        val kinds = HandKind.all(alone).map { kind ->
            val solo = solos.filter { (it.ai?.kind ?: kindOf(it)) == kind.key }.map { it.at }
            kindTrust(kind, counting, audits.filter { it.kind == kind.key }, solo, settings, print)
        }
        return TrustState(
            settings = settings,
            kinds = kinds,
            calibration = calibration(counting),
            audits = audits,
            seen = drift(pairs),
            pairs = pairs.size,
            counted = counting.size,
            solo = solos.size,
            aiAnswers = trials.count { it.judge == StoredTrial.AI || (it.ai?.answer ?: it.ai?.prefer) != null },
            selfAgree = selfAgreement(trials)?.first,
            repeats = selfAgreement(trials)?.second ?: 0,
        )
    }

    private fun kindTrust(kind: HandKind, all: List<JudgedPair>, audits: List<Audit>, solos: List<Long>, settings: TrustSettings, print: String?): KindTrust {
        val mine = all.filter { it.kind == kind.key }
        // The audits, in order: each judged against the range as it stood just before it, since the last closing.
        var epoch = Long.MIN_VALUE
        var n = 0
        var m = 0
        var closedAt: Long? = null
        for (a in audits.sortedBy { it.at }) {
            n++
            if (!a.agrees) m++
            val before = mine.filter { it.at in (epoch + 1) until a.at && it.person.mode != TeachModes.AUDIT }
            val lower = surePart(before, settings, print).third.lower
            if (m >= 2 && m > n * (1 - lower)) {
                epoch = a.at
                closedAt = a.at
                n = 0
                m = 0
            }
        }
        val since = mine.filter { it.at > epoch }
        val (agree, pairs) = weighted(since, print)
        val (sureAgree, surePairs, sureRange) = surePart(since, settings, print)
        val current = since.count { print == null || it.verdict.print == null || it.verdict.print == print }
        val stale = since.any { print != null && it.verdict.print != null && it.verdict.print != print }
        val currentSure = since.filter { (print == null || it.verdict.print == print) && (it.sure ?: 0.0) >= settings.sure }
        val why = when {
            surePairs < MIN_SURE -> "${fmt(surePairs)} hand${if (surePairs == 1.0) "" else "s"} it was sure of; it needs $MIN_SURE"
            sureRange.lower < settings.bar -> "the bottom of its range, ${pct(sureRange.lower)}, is under your bar of ${pct(settings.bar)}"
            stale && currentSure.size < RECHECK -> "the decks changed: ${currentSure.size} of $RECHECK hands on today's decks"
            stale && currentSure.count { it.agrees } < currentSure.size * settings.bar -> "on today's decks it agreed ${currentSure.count { it.agrees }} of ${currentSure.size}"
            else -> null
        }
        return KindTrust(
            kind = kind,
            agree = agree,
            pairs = pairs,
            range = Agreement.wilson(agree, pairs),
            sureAgree = sureAgree,
            surePairs = surePairs,
            sureRange = sureRange,
            current = current,
            stale = stale,
            open = why == null,
            why = why,
            audits = n,
            misses = m,
            closedAt = closedAt,
            solo = solos.count { it > epoch },
        )
    }

    private fun weight(p: JudgedPair, print: String?): Double =
        if (print != null && p.verdict.print != null && p.verdict.print != print) STALE else 1.0

    private fun weighted(pairs: List<JudgedPair>, print: String?): Pair<Double, Double> =
        pairs.sumOf { if (it.agrees) weight(it, print) else 0.0 } to pairs.sumOf { weight(it, print) }

    private fun surePart(pairs: List<JudgedPair>, settings: TrustSettings, print: String?): Triple<Double, Double, Range> {
        val sure = pairs.filter { (it.sure ?: 0.0) >= settings.sure }
        val (k, n) = weighted(sure, print)
        return Triple(k, n, Agreement.wilson(k, n))
    }

    /** The certainty Ai stated against how often it agreed, in four bins. */
    fun calibration(pairs: List<JudgedPair>): Calibration {
        val rated = pairs.filter { it.sure != null }
        if (rated.isEmpty()) return Calibration(0, 0.0, 0.0, emptyList())
        val brier = rated.sumOf { val s = it.sure!!.coerceIn(0.0, 1.0); val o = if (it.agrees) 1.0 else 0.0; (s - o) * (s - o) } / rated.size
        val edges = listOf(0.0, 0.5, 0.7, 0.85, 1.0000001)
        val bins = edges.zipWithNext().map { (from, to) ->
            val inBin = rated.filter { it.sure!! >= from && it.sure!! < to }
            Calibration.Bin(
                from, to.coerceAtMost(1.0), inBin.size,
                if (inBin.isEmpty()) 0.0 else inBin.sumOf { it.sure!! } / inBin.size,
                if (inBin.isEmpty()) 0.0 else inBin.count { it.agrees }.toDouble() / inBin.size,
            )
        }
        val ece = bins.sumOf { b -> b.n.toDouble() / rated.size * abs(b.said - b.agreed) }
        return Calibration(rated.size, brier, ece, bins)
    }

    /** How the person's answers move toward Ai's once seen: exact matches seen against held-out blind. */
    fun drift(pairs: List<JudgedPair>): SeenDrift {
        val seen = pairs.filter { it.seen }
        val blind = pairs.filter { it.counts }
        return SeenDrift(
            seen.size, seen.takeIf { it.isNotEmpty() }?.let { s -> s.count { it.same }.toDouble() / s.size },
            blind.size, blind.takeIf { it.isNotEmpty() }?.let { b -> b.count { it.same }.toDouble() / b.size },
        )
    }

    /**
     * The person's agreement with themselves (within one band) on the hands shown again unannounced: the ceiling any
     * judge's agreement with them meets, since no one agrees with a person more steadily than their own answers allow.
     */
    fun selfAgreement(trials: List<StoredTrial>): Pair<Double, Int>? {
        val blind = trials.filter { it.blind && it.kind == StoredTrial.RATE && it.answer != null }
        val repeats = blind.filter { it.reason == "repeat" }
        if (repeats.isEmpty()) return null
        var n = 0
        var k = 0
        for (r in repeats) {
            val first = blind.firstOrNull { it.at < r.at && it.stratum == r.stratum && it.hand.sorted() == r.hand.sorted() && it.opponent?.sorted() == r.opponent?.sorted() } ?: continue
            val a = Answer.entries.firstOrNull { it.name == first.answer } ?: continue
            val b = Answer.entries.firstOrNull { it.name == r.answer } ?: continue
            n++
            if (Agreement.agrees(a, b)) k++
        }
        return if (n == 0) null else k.toDouble() / n to n
    }

    /**
     * Where a hand of [kind] goes, given Ai's certainty [sure] about it: to the person unless Ai has earned the kind and is
     * sure; then to Ai alone, but [auditRate] of the time back to the person blind.
     */
    fun route(state: TrustState, kind: String?, sure: Double?, random: Random): Route {
        if (!state.settings.solo) return Route.PERSON
        val k = state.kind(kind) ?: return Route.PERSON
        if (!k.open || (sure ?: 0.0) < state.settings.sure) return Route.PERSON
        return if (random.nextDouble() < auditRate(k.solo + k.audits)) Route.AUDIT else Route.SOLO
    }

    /** The share of solo hands audited: one in three for the first [EARLY] after a kind opens, then one in ten. */
    fun auditRate(soloSoFar: Int): Double = if (soloSoFar < EARLY) EARLY_RATE else RATE

    private fun pct(x: Double) = "${(x * 100).toInt()}%"
    private fun fmt(x: Double) = if (x == x.toInt().toDouble()) x.toInt().toString() else ((x * 10).toInt() / 10.0).toString()
}
