package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.store.ShootoutLog
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.KindTrust
import com.kaiharimoto.mastertool.core.shootout.teach.Rubric
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

    /** "38 % of answers are Ai's, weighted to 27 % by its accuracy." */
    fun share(r: TrustReport): String =
        if (r.aiShare == 0.0) "No answers of Ai's are in the ratings yet."
        else "${pct(r.aiShare)} of the answers are Ai's, weighted to ${pct(r.aiWeighted)} by its measured accuracy (one of its answers counts ${dec(r.aiWeight)} of one of yours)."

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
        r.state.seen.drift?.let { appendLine("Seeing Ai's answer first makes the person give exactly it ${dec(it)} points more often than blind.") }
        val flagged = r.flagged
        if (flagged.isNotEmpty()) {
            appendLine("Cards Ai's answers moved most (worth with them, without them, in points):")
            flagged.take(6).forEach { m -> appendLine("- ${name(m.card)} ${ShootoutWords.stratum(m.stratum)}: ${ShootoutWords.points(m.with.value)} against ${ShootoutWords.points(m.without.value)}") }
        }
    }.trim()

    fun pct(x: Double): String = "${round(x * 100).toInt()} %"
    private fun n(x: Double): String = if (x == round(x)) x.toInt().toString() else dec(x)
    private fun dec(x: Double): String = (round(x * 10) / 10).toString()
    private fun dec2(x: Double): String = (round(x * 100) / 100).toString()
}
