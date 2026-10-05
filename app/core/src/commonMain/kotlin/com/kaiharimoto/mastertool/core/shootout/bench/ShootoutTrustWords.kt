package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.Calibration
import com.kaiharimoto.mastertool.core.shootout.teach.KindTrust
import com.kaiharimoto.mastertool.core.shootout.teach.Rubric
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import com.kaiharimoto.mastertool.core.shootout.teach.TrustState
import kotlin.math.ceil
import kotlin.math.round

/**
 * The trust panel in words (Phase S stage 3, S.md §6½ "What the person sees"): what the page shows, as lines, and what
 * Ai reads through `shootout_state`. Every number here is one the panel shows; none is made up for the words.
 */
object ShootoutTrustWords {

    /** "Agrees with you 93 % (87–97 %) on going first with a starter", or "Not yet …". */
    fun kind(k: KindTrust): String {
        val share = k.share
        val what = k.kind.words
        return when {
            share == null -> "No hands yet $what."
            k.open -> "Agrees with you ${pct(share)} (${pct(k.range.lower)}–${pct(k.range.upper)}) $what, ${n(k.pairs)} hands; when sure ${pct(k.sureRange.lower)}–${pct(k.sureRange.upper)}: may judge alone."
            else -> "Not yet $what: ${pct(share)} of ${n(k.pairs)} hands (${pct(k.range.lower)}–${pct(k.range.upper)}) — ${k.why ?: "not earned"}."
        }
    }

    /** "38% of answers are Ai's, weighted to 27% by its accuracy." For Ai's own reading ([describe]). */
    fun share(r: TrustReport): String =
        if (r.aiShare == 0.0) "No answers of Ai's are in the ratings yet."
        else "${pct(r.aiShare)} of the answers are Ai's, weighted to ${pct(r.aiWeighted)} by its measured accuracy (one of its answers counts ${dec(r.aiWeight)} of one of yours)."

    // ---- the trust panel, as the person reads it (design review, 1.1.6) --------------------------------------------

    /** The kinds as the panel lists them: open first, then not yet, then closed by audits; the panel's own order within. */
    fun ordered(kinds: List<KindTrust>): List<KindTrust> = kinds.sortedBy { k -> if (k.open) 0 else if (k.closedAt != null) 2 else 1 }

    /** A kind's state in a word, sentence case (the page sets it in micro caps): Open, Not yet, Closed. */
    fun status(k: KindTrust): String = when {
        k.open -> "Open"
        k.closedAt != null -> "Closed"
        else -> "Not yet"
    }

    /** Hands it was sure of a kind still needs before it can open: what [Trust.MIN_SURE] asks, less what it has. */
    fun sureNeeded(k: KindTrust): Int = if (k.open) 0 else ceil((Trust.MIN_SURE - k.surePairs).coerceAtLeast(0.0)).toInt()

    /** Why a kind is not open, short enough to stand on its row beside the state: "needs 6 more sure hands". */
    fun reason(k: KindTrust, bar: Double): String? {
        if (k.open) return if (k.solo > 0) "${k.solo} judged alone" else null
        val need = sureNeeded(k)
        val closed = if (k.closedAt != null) "two audits missed; " else ""
        return closed + when {
            need > 0 -> "needs $need more sure hand${if (need == 1) "" else "s"}"
            k.sureRange.lower < bar -> "its range starts at ${pct(k.sureRange.lower)}, under your bar"
            else -> k.why ?: "not earned yet"
        }
    }

    /**
     * The panel's headline: "Ai judges 2 of 8 kinds of hand for you. The other 6 need at least 25 more hands it is sure
     * of." With judging alone off, what it has earned and that the person judges every hand.
     */
    fun headline(state: TrustState, name: String): String {
        val total = state.kinds.size
        val open = state.kinds.count { it.open }
        val rest = total - open
        val first = when {
            !state.settings.solo -> "$name has earned ${if (open == 0) "none" else "$open"} of $total kinds of hand. Judging alone is off, so you judge every hand."
            open == 0 -> "$name judges none of the $total kinds of hand for you yet."
            else -> "$name judges $open of $total kinds of hand for you."
        }
        if (rest == 0) return first
        val need = state.kinds.sumOf { sureNeeded(it) }
        val other = if (open == 0) "Every kind" else "The other $rest"
        val verb = if (open == 0 || rest > 1) "need" else "needs"
        return "$first " + if (need > 0) "$other $verb at least $need more hands it is sure of." else "$other $verb more agreement before your bar is cleared."
    }

    /**
     * The person's own consistency on hands shown again, as a sentence; a warning when it is under the bar, since no judge
     * can agree with the person more often than they agree with themselves (S.md §6¾ "The person is the ceiling").
     */
    fun ceiling(selfAgree: Double, repeats: Int, bar: Double): String {
        val said = "Shown a hand again, you answer within one step of your first answer about ${ShootoutWords.inTen(selfAgree)} times (${ShootoutWords.hands(repeats)})."
        return if (selfAgree < bar) "$said No judge can agree with you more often than that, so a bar of ${pct(bar)} may never open."
        else "$said That is the most any judge can agree with you."
    }

    /** How the bar is read, in a line. */
    fun barHelp(name: String) = "How often $name must agree with you, within one step, before it judges a kind of hand alone. Nine times in ten the truth is at least this."

    /** How "sure" is read, in a line. */
    fun sureHelp(name: String) = "Only hands $name says it is at least this sure of count toward opening a kind, and only those it judges alone."

    /** What a kind of hand is, said once. */
    fun kindHelp(name: String) = "A kind of hand is who goes first, whether you hold a starter, and whether they hold interaction. Agreement is counted within one step of your blind answer, on hands $name answered from only what came before."

    /** Ai's certainty, scored, in words (no Brier): how far its "sure" sits from how often it agreed. */
    fun certainty(c: Calibration): String =
        "Scored on ${ShootoutWords.hands(c.n)}: what it says about how sure it is sits about ${round(c.ece * 100).toInt()} points from how often it agrees" +
            if (c.calibrated) ", close enough to send you only what it is unsure of." else "."

    /** One certainty bin in words: "Said about 8 in 10, agreed 9 in 10 (18 hands)". */
    fun certaintyBin(b: Calibration.Bin): String =
        "Said about ${ShootoutWords.inTen(b.said)}, agreed ${ShootoutWords.inTen(b.agreed)} (${ShootoutWords.hands(b.n)})"

    /** How much of the data is Ai's, in words for the person. */
    fun shareWords(r: TrustReport, name: String): String =
        if (r.aiShare == 0.0) "No answers of $name's are in the ratings yet."
        else "${pct(r.aiShare)} of the answers are $name's. Weighed by how well it agrees with you, they count as ${pct(r.aiWeighted)}: one of its answers is worth ${dec(r.aiWeight)} of one of yours."

    /** How Ai leans against the person's bands: "a little optimistic: +4 points at an even hand". */
    fun lean(points: Double): String = when {
        kotlin.math.abs(points) < 1.0 -> "no measurable lean"
        points > 0 -> "optimistic by ${dec(points)} points at an even hand"
        else -> "pessimistic by ${dec(-points)} points at an even hand"
    }

    /** The whole panel as lines, for Ai. */
    fun describe(deckName: String, bench: Bench, log: ShootoutLog, rubric: String?, r: TrustReport, name: (Int) -> String = { "#$it" }): String = buildString {
        val person = log.trials.count { it.judge == StoredTrial.PERSON && !it.sawAi }
        val seen = log.trials.count { it.judge == StoredTrial.PERSON && it.sawAi }
        val ai = log.trials.count { it.judge == StoredTrial.AI }
        appendLine("Shootout: “$deckName” ${bench.opponentName?.let { "against “$it”" } ?: "on its own"}.")
        appendLine("Trials kept: $person by the person blind, $seen by the person after seeing Ai's answer, $ai by Ai (${r.state.solo} of them alone).")
        appendLine("Notes on trials: ${log.notes.size}.")
        appendLine()
        appendLine("The rubric:")
        appendLine(Rubric.forPrompt(rubric))
        appendLine()
        appendLine("Trust (the person's bar ${pct(r.state.settings.bar)}, sure from ${pct(r.state.settings.sure)}; Ai alone ${if (r.state.settings.solo) "allowed" else "not allowed"}):")
        r.state.kinds.forEach { appendLine("- " + kind(it)) }
        r.state.selfAgree?.let { appendLine("The person agrees with themselves ${pct(it)} on ${r.state.repeats} hands shown again: no judge agrees with them more steadily.") }
        val c = r.state.calibration
        if (c.n > 0) appendLine("Ai's certainty, scored on ${c.n} hands: Brier ${dec2(c.brier)}, off by ${pct(c.ece)} on average${if (c.calibrated) " (calibrated)" else ""}.")
        appendLine("Audits: ${r.state.audits.size}, ${r.state.audits.count { !it.agrees }} missed.")
        appendLine(share(r))
        appendLine("Ai leans: ${lean(r.aiLean)}.")
        r.seenLean?.let { appendLine("The person after seeing Ai's answer: ${lean(it)}; one such answer counts ${dec(r.seenWeight ?: 0.0)} of a blind one.") }
        r.state.seen.drift?.let { appendLine("Seeing Ai's answer first makes the person give that very answer ${dec(it)} points more often than blind.") }
        val flagged = r.flagged
        if (flagged.isNotEmpty()) {
            appendLine("Cards Ai's answers moved most (worth with them, without them, in points):")
            flagged.take(6).forEach { m -> appendLine("- ${name(m.card)} ${ShootoutWords.stratum(m.stratum)}: ${ShootoutWords.points(m.with.value)} against ${ShootoutWords.points(m.without.value)}") }
        }
    }.trim()

    fun pct(x: Double): String = "${round(x * 100).toInt()}%"
    private fun n(x: Double): String = if (x == round(x)) x.toInt().toString() else dec(x)
    private fun dec(x: Double): String = (round(x * 10) / 10).toString()
    private fun dec2(x: Double): String = (round(x * 100) / 100).toString()
}
