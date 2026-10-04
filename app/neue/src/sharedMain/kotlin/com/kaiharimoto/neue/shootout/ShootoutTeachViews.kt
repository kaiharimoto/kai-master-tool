package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrustWords
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.teach.CalibrationSet
import com.kaiharimoto.mastertool.core.shootout.teach.HandKind
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.avatar.AiName
import com.kaiharimoto.neue.ai.startRubricInterview
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Kbd
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

// Teaching Ai on Shootout (Phase S stage 3, S.md §6½): the setup's choice of how, the line over a trial, Ai's answer in
// supervised mode, its one question in apprentice mode, and the calibration set's exam. Ink only; nothing moves.

/** The keys a row names, read from the table so the words can never drift from the keys. */
private fun key(action: DeskAction, fallback: String) = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd) ?: fallback

/** How the next session teaches Ai, and how far it is trusted: on the setup, under the hands to deal. */
@Composable
internal fun TeachSetup(h: NeueHolders) {
    val s = h.shootout
    val t = s.teach
    val c = Mu.colors
    if (!h.ai.enabled) return
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("Teach", color = c.ink45)
            AiName(h.ai.name, c.ink)
        }
        Segmented(t.mode, ShootoutTeach.Mode.entries, { it.label }, { t.mode = it }, small = true)
        Body(t.mode.help, color = c.ink70)
        t.problem?.takeIf { t.mode != ShootoutTeach.Mode.JUDGE }?.let { Small(it, color = c.ink) }
        if (t.mode == ShootoutTeach.Mode.CALIBRATION) {
            Help("${CalibrationSet.SIZE} hands, taken in turn from every kind of hand the matchup deals. After a deck changes, a short set of a few per kind earns the kinds back.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton("Interview", { h.ai.startRubricInterview() }, size = BtnSize.SM, reason = "Ai asks how you judge this matchup and writes it down; you review it on Finish")
            MuButton("Rubric", { t.rubricOpen = true }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            MuButton("Trust", t::openTrust, size = BtnSize.SM, variant = BtnVariant.GHOST)
            Kbd(key(DeskAction.SHOOTOUT_TRUST, "T"))
            t.lastSet(null)?.let { MicroLink("${h.ai.name}'s exam on your last calibration set", { t.startExam() }) }
        }
        val settings = t.settings
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuSwitch(settings.solo, { t.setSettings(settings.copy(solo = it)) })
            Small("Let ${h.ai.name} judge the kinds of hand it has earned, a share of them back to you blind", Modifier.weight(1f, fill = false))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("Your bar")
            MuSelect(settings.bar, Trust.BARS, { ShootoutTrustWords.pct(it) }, { t.setSettings(settings.copy(bar = it)) }, small = true)
            Help("A kind opens when nine times in ten ${h.ai.name} agrees with you at least this often.")
        }
    }
}

/** The line over a trial: how this session teaches, and what Ai has done alone. */
@Composable
internal fun TeachBanner(h: NeueHolders) {
    val s = h.shootout
    val t = s.teach
    val c = Mu.colors
    if (!h.ai.enabled) return
    val parts = mutableListOf<String>()
    t.set?.let { set -> parts += "Calibration set · ${(t.setAt + 1).coerceAtMost(set.size)} of ${set.size} · judged blind" }
    if (t.mode == ShootoutTeach.Mode.APPRENTICE) parts += "Apprentice · ${h.ai.name} is predicting silently"
    if (t.mode == ShootoutTeach.Mode.SUPERVISED) parts += "Supervised · your answers are marked as seen"
    if (t.solo > 0 || t.audits > 0) parts += "${h.ai.name} judged ${t.solo} alone · ${t.audits} back to you"
    if (t.routing) parts += "${h.ai.name} is judging a hand of a kind it has earned"
    if (s.proposal != null && s.teach.modeFor(s.proposal!!) == TeachModes.AUDIT) parts += "An audit: ${h.ai.name} judged this hand; you judge it blind"
    if (parts.isEmpty() && t.lastAnswered == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (t.routing) Breathe()
        Small(parts.joinToString(" · "), Modifier.weight(1f), color = c.ink45, maxLines = 2)
        if (t.lastAnswered != null && t.ask == null) MicroLink("Note on the last hand", t::noteOnLast)
    }
}

/** Supervised: Ai's answer and its reason, taken with a key or corrected by any answer. */
@Composable
internal fun VerdictBox(h: NeueHolders, alone: Boolean) {
    val t = h.shootout.teach
    val c = Mu.colors
    if (t.mode != ShootoutTeach.Mode.SUPERVISED || !h.ai.enabled) return
    val v = t.verdict
    Row(
        Modifier.fillMaxWidth().border(1.dp, c.ink).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            v != null -> {
                val said = v.answer?.let { "${ShootoutWords.keyOf(it)} · ${ShootoutWords.label(it, alone)}" }
                    ?: v.prefersLeft?.let { if (it) "← the left hand" else "the right hand →" } ?: "?"
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AiName(h.ai.name, c.ink)
                        Micro("says $said", color = c.ink)
                        v.verdict.sure?.let { Mono("${(it * 100).toInt()} % sure", color = c.ink45) }
                    }
                    v.verdict.why?.let { Small("“$it”", color = c.ink70, maxLines = 2) }
                }
                MuButton("Accept", t::accept, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                Kbd(key(DeskAction.SHOOTOUT_ACCEPT, "Space"))
            }
            t.judging -> {
                Breathe()
                Small("${h.ai.name} is judging this hand. Answer whenever you like: an answer before it lands is blind.", Modifier.weight(1f))
            }
            else -> Small(t.trouble ?: "${h.ai.name} has no answer for this hand.", Modifier.weight(1f), color = c.ink45)
        }
    }
}

/** Ai's one question about the hand just answered, or the person's own note on it: the answer is a note on that trial. */
@Composable
internal fun AskCard(h: NeueHolders) {
    val t = h.shootout.teach
    val a = t.ask ?: return
    val c = Mu.colors
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (a.question != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AiName(h.ai.name, c.ink)
                Micro("asks about the hand you just answered", color = c.ink45)
            }
            if (a.aiSaid != null && a.youSaid != null) Small("It said ${a.aiSaid}; you said ${a.youSaid}.", color = c.ink70)
            Body(a.question)
        } else {
            Micro("A note on the hand you just answered", color = c.ink45)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuInput(t.draft, { t.draft = it }, Modifier.weight(1f), placeholder = if (a.question != null) "Your answer, in a line" else "What decided it", onSubmit = t::sendNote)
            MuButton("Keep", t::sendNote, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = t.draft.isNotBlank())
            MuButton(if (a.question != null) "Skip" else "Cancel", t::skipAsk, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
        Help("It goes with that hand into the examples ${h.ai.name} is shown; notes that keep coming back are offered for the rubric.")
    }
}

/** A calibration set done: Ai's exam on it, as it goes and when it is done. */
@Composable
internal fun ExamView(h: NeueHolders) {
    val t = h.shootout.teach
    val c = Mu.colors
    val e = t.exam
    val phone = LocalPhone.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AiName(h.ai.name, c.ink)
                H2("The first exam")
            }
            Body("${h.ai.name} answers the calibration set you just judged, each hand blind: shown only the hands you had answered before it, your rubric and the model as it stood before the set. Agreement is counted within one step of your answer.", color = c.ink70)
        }
        when {
            e == null -> Small("No calibration set to answer.")
            e.problem != null -> Small(e.problem, color = c.ink)
            !e.finished -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Breathe()
                Mono("${e.done} of ${e.of} answered" + if (e.failed > 0) " · ${e.failed} not judged" else "", color = c.ink)
            }
            else -> {
                if (e.of > 0) {
                    Mono("Agreed with you on ${e.agreed} of ${e.of} hands (${ShootoutTrustWords.pct(e.agreed.toDouble() / e.of)})" + if (e.failed > 0) " · ${e.failed} not judged" else "", color = c.ink)
                } else {
                    Small(t.trouble ?: "No hand was judged.", color = c.ink)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    e.byKind.forEach { (kind, agreed, n) ->
                        key(kind) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Small(HandKind.parse(kind)?.words?.replaceFirstChar { it.uppercase() } ?: kind, Modifier.widthIn(min = 320.dp))
                                Mono("$agreed of $n", color = c.ink)
                            }
                        }
                    }
                }
                Help("One exam is a first reading, not a verdict: a kind opens only once enough hands agree that the bottom of its range clears your bar. Apprentice sessions add to it without asking anything of you.")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuButton("Trust panel", t::openTrust, variant = BtnVariant.PRIMARY, enabled = e?.finished == true)
            MuButton("Back", { h.shootout.view = Shootouts.View.SETUP }, variant = BtnVariant.GHOST)
        }
    }
}
