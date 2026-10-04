package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutTrustWords
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.bench.TrustReport
import com.kaiharimoto.mastertool.core.shootout.teach.KindTrust
import com.kaiharimoto.mastertool.core.shootout.teach.Range
import com.kaiharimoto.mastertool.core.shootout.teach.RubricNotes
import com.kaiharimoto.mastertool.core.shootout.teach.Trust
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.startRubricInterview
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

// The trust panel and the rubric (Phase S stage 3, S.md §6½ "What the person sees"). Every number opens its trials.

/** How far Ai is trusted on this matchup: the kinds it may judge alone, the audits, how much of the data is its, what it moved. */
@Composable
internal fun TrustDialog(h: NeueHolders) {
    val s = h.shootout
    val t = s.teach
    val c = Mu.colors
    val r = t.trust
    val phone = LocalPhone.current
    val name = h.ai.name
    MuDialog(
        title = "Trust",
        onDismiss = { t.trustOpen = false },
        width = 880.dp,
        description = "How far $name is trusted on ${s.deckName} ${s.bench?.opponentName?.let { "against $it" } ?: "on its own"}: per kind of hand, measured only on hands it never learned from.",
    ) {
        val settings = t.settings
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuSwitch(settings.solo, { t.setSettings(settings.copy(solo = it)) })
            Small("$name judges the open kinds alone", Modifier.weight(1f, fill = false))
            Small("Bar")
            MuSelect(settings.bar, Trust.BARS, { ShootoutTrustWords.pct(it) }, { t.setSettings(settings.copy(bar = it)) }, Modifier.width(110.dp), small = true)
            if (!phone) {
                Small("Sure from")
                MuSelect(settings.sure, SURE, { ShootoutTrustWords.pct(it) }, { t.setSettings(settings.copy(sure = it)) }, Modifier.width(110.dp), small = true)
            }
        }
        if (r == null) {
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Breathe()
                Small("Reading every answer kept")
            }
        } else {
            TrustBody(h, r, settings.bar, phone)
        }
    }
}

@Composable
private fun TrustBody(h: NeueHolders, r: TrustReport, bar: Double, phone: Boolean) {
    val c = Mu.colors
    val name = h.ai.name
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer16()
        Micro("Kinds of hand", color = c.ink45)
        Help("Agreement is within one step of your blind answer, on hands $name answered from only what came before. A kind opens when the bottom of its range on the hands it was sure of clears your bar.")
        r.state.kinds.forEach { k -> key(k.kind.key) { KindRow(h, k, bar, phone) } }
        r.state.selfAgree?.let {
            Small("You agree with yourself ${ShootoutTrustWords.pct(it)} on ${r.state.repeats} hands shown again: no judge agrees with you more steadily than that.", color = c.ink70)
        }
        Spacer16()
        Micro("Audits", color = c.ink45)
        val missed = r.state.audits.count { !it.agrees }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Small("${r.state.audits.size} of $name's solo hands came back to you blind; $missed missed. Two misses beyond what a kind's range allows close it until it is earned again.", Modifier.weight(1f))
        }
        r.state.kinds.filter { it.closedAt != null }.forEach { k -> Small("Closed by its audits: ${k.kind.words}.", color = c.ink) }
        Spacer16()
        Micro("The data", color = c.ink45)
        Small(ShootoutTrustWords.share(r), color = c.ink)
        Small("$name: ${ShootoutTrustWords.lean(r.aiLean)}.", color = c.ink70)
        r.seenLean?.let { Small("You after seeing $name's answer: ${ShootoutTrustWords.lean(it)}; one such answer counts ${"%.1f".format(r.seenWeight ?: 0.0)} of a blind one.", color = c.ink70) }
        r.state.seen.drift?.let { Small("Seeing its answer first, you give that very answer ${"%.0f".format(it)} points more often than blind.", color = c.ink70) }
        val cal = r.state.calibration
        if (cal.n > 0) {
            Spacer16()
            Micro("Its certainty", color = c.ink45)
            Small("Scored on ${cal.n} hands: off by ${ShootoutTrustWords.pct(cal.ece)} on average (Brier ${"%.2f".format(cal.brier)})${if (cal.calibrated) ", calibrated: what it is sure of, it gets right" else ""}.", color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                cal.bins.filter { it.n > 0 }.forEach { b ->
                    key(b.from) { Mono("said ${ShootoutTrustWords.pct(b.said)} · agreed ${ShootoutTrustWords.pct(b.agreed)} · ${b.n}", color = c.ink70) }
                }
            }
        }
        Spacer16()
        Moved(h, r)
    }
}

/** A kind's row: its words, its agreement with the range drawn in ink, the hands behind it, open or why not. */
@Composable
private fun KindRow(h: NeueHolders, k: KindTrust, bar: Double, phone: Boolean) {
    val s = h.shootout
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small(k.kind.words.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), color = c.ink, maxLines = if (phone) 2 else 1)
            val share = k.share
            if (share != null) {
                Number("${ShootoutTrustWords.pct(share)} of ${k.pairs.toInt()}", "The hands behind it") { s.behind = Behind.Kind(k.kind.key) }
            } else {
                Mono("–", color = c.ink45)
            }
            if (!phone) Box(Modifier.width(160.dp).height(14.dp)) { AgreementBar(k.range, k.sureRange, bar) }
            Mono(if (k.open) "OPEN" else if (k.closedAt != null) "CLOSED" else "NOT YET", color = if (k.open) c.ink else c.ink45)
        }
        if (!k.open && k.why != null && share(k)) Small(k.why!!.replaceFirstChar { it.uppercase() } + ".", color = c.ink45, maxLines = 2)
        if (k.audits > 0) MicroLink("${k.audits} audit${if (k.audits == 1) "" else "s"} · ${k.misses} missed", { s.behind = Behind.Kind(k.kind.key, audits = true) })
    }
    HRule()
}

private fun share(k: KindTrust) = k.share != null

/**
 * Agreement in ink: 50 % to 100 % across; the range thin, the range on the hands Ai was sure of thick, the person's bar a
 * tick that the thick range's left end must clear.
 */
@Composable
private fun AgreementBar(all: Range, sure: Range, bar: Double) {
    val c = Mu.colors
    Box(
        Modifier.fillMaxWidth().height(14.dp).drawBehind {
            val w = size.width
            val mid = size.height / 2
            fun x(v: Double) = (((v - 0.5) / 0.5).coerceIn(0.0, 1.0) * w).toFloat()
            drawLine(c.ink12, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
            drawLine(c.ink45, Offset(x(all.lower), mid), Offset(x(all.upper), mid), 1.dp.toPx())
            if (sure.upper > sure.lower) drawLine(c.ink, Offset(x(sure.lower), mid), Offset(x(sure.upper), mid), 3.dp.toPx())
            val b = x(bar)
            drawRect(c.ink, Offset(b - 0.5.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
        },
    )
}

/** What Ai's answers moved: each card's worth with them and without, the largest first; a large change is flagged. */
@Composable
private fun Moved(h: NeueHolders, r: TrustReport) {
    val s = h.shootout
    val c = Mu.colors
    Micro("What ${h.ai.name}'s answers moved", color = c.ink45)
    if (r.moved.isEmpty()) {
        Small("Nothing yet: no answer of ${h.ai.name}'s is in the ratings.", color = c.ink70)
        return
    }
    Help("Each card's worth per copy with ${h.ai.name}'s answers and without them, in points. A change past the card's own range without them is flagged for a look.")
    r.moved.take(8).forEach { m ->
        key(m.card, m.stratum) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small(s.card(m.card)?.name ?: "#${m.card}", Modifier.weight(1f), color = c.ink, maxLines = 1)
                Mono(ShootoutWords.stratum(m.stratum), color = c.ink45)
                Number(ShootoutWords.points(m.with.value), "Its trials") { s.behind = Behind.Card(m.card, m.stratum) }
                Mono("without ${ShootoutWords.points(m.without.value)}", color = c.ink45)
                Mono(if (m.flagged) "LOOK" else "", Modifier.width(36.dp), color = c.ink)
            }
        }
    }
    MicroLink("Every answer of ${h.ai.name}'s", { s.behind = Behind.Ai })
}

@Composable
private fun Spacer16() = Box(Modifier.height(8.dp))

private val SURE = listOf(0.7, 0.8, 0.9)

/** The rubric: how the person judges this matchup, the notes that keep coming back, and the interview that writes it. */
@Composable
internal fun RubricDialog(h: NeueHolders) {
    val s = h.shootout
    val t = s.teach
    val c = Mu.colors
    val target = s.rubricTarget()
    val entries = remember(s.rubricText) { s.rubricText?.let { AiMemory.parse(it).entries }.orEmpty() }
    val themes = remember(s.log?.notes) { RubricNotes.recurring(s.log?.notes.orEmpty()) }
    var draft by remember { mutableStateOf("") }
    MuDialog(
        title = "Rubric",
        onDismiss = { t.rubricOpen = false },
        width = 720.dp,
        description = "How you judge ${s.deckName} ${target?.opponentName?.let { "against $it" } ?: "on its own"}, in rules ${h.ai.name} is handed with every hand. Written in the interview and from your notes; yours to change.",
        footer = {
            MuButton("Interview", { t.rubricOpen = false; h.ai.startRubricInterview() }, variant = BtnVariant.PRIMARY)
            MuButton("Done", { t.rubricOpen = false }, variant = BtnVariant.GHOST)
        },
    ) {
        if (entries.isEmpty()) Small("No rules yet. The interview writes them with you; a note that keeps coming back can become one.", color = c.ink70)
        entries.forEach { e ->
            key(e) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Small(e, Modifier.weight(1f), color = c.ink)
                    MicroLink("Remove", { s.removeRubricEntry(e) })
                }
                HRule()
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuInput(draft, { draft = it }, Modifier.weight(1f), placeholder = "A rule of yours, in a line", onSubmit = { if (draft.isNotBlank()) { s.addRubricEntry(draft.trim()); draft = "" } })
            MuButton("Add", { s.addRubricEntry(draft.trim()); draft = "" }, size = BtnSize.SM, enabled = draft.isNotBlank())
        }
        if (themes.isNotEmpty()) {
            Spacer16()
            Micro("From your notes", color = c.ink45)
            themes.forEach { theme ->
                key(theme.word) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono("“${theme.word}” · ${theme.notes.size} hands", Modifier.weight(1f), color = c.ink)
                            MicroLink("Make the latest a rule", { s.addRubricEntry(theme.notes.maxBy { it.at }.text) })
                        }
                        theme.notes.takeLast(3).forEach { n -> Small(n.text, color = c.ink70, maxLines = 2) }
                    }
                }
            }
        }
    }
}
