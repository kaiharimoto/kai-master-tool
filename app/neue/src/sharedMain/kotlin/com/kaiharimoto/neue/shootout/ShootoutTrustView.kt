package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import com.kaiharimoto.neue.kit.H2
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

/**
 * How far Ai is trusted on this matchup (design review, 1.1.6: the answer first). A headline sentence and the switch;
 * the kinds of hand, open first, each with its state and reason on its row; the person's own consistency, a warning
 * when it caps the bar; the bar and how sure counts, each explained; the audits; and the details — the data, Ai's
 * certainty, what its answers moved — in words, folded until asked for.
 */
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
        description = "Which kinds of hand $name may judge for you on ${s.deckName} ${s.bench?.opponentName?.let { "against $it" } ?: "on its own"}, measured only on hands it never learned from.",
    ) {
        val settings = t.settings
        if (r == null) {
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Breathe()
                Small("Reading every answer kept")
            }
        } else {
            H2(ShootoutTrustWords.headline(r.state, name), maxLines = 4)
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuSwitch(settings.solo, { t.setSettings(settings.copy(solo = it)) })
            Small("$name judges the open kinds alone, a share of them back to you blind", Modifier.weight(1f, fill = false), color = c.ink)
        }
        if (r != null) TrustBody(h, r, settings.bar, phone)
    }
}

@Composable
private fun TrustBody(h: NeueHolders, r: TrustReport, bar: Double, phone: Boolean) {
    val c = Mu.colors
    val t = h.shootout.teach
    val settings = t.settings
    val name = h.ai.name
    var details by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The person is the ceiling (S.md §6¾): said first when it caps the bar, since then no kind may ever open.
        r.state.selfAgree?.let { self ->
            val capped = self < bar
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().let { if (capped) it.border(1.dp, c.ink).padding(12.dp) else it }) {
                Small(ShootoutTrustWords.ceiling(self, r.state.repeats, bar), color = if (capped) c.ink else c.ink70)
            }
        }
        Spacer16()
        Micro("Kinds of hand", color = c.ink45)
        Help(ShootoutTrustWords.kindHelp(name))
        if (!phone) ScaleHead(bar)
        ShootoutTrustWords.ordered(r.state.kinds).forEach { k -> key(k.kind.key) { KindRow(h, k, bar, phone) } }
        Spacer16()
        Micro("Your bar", color = c.ink45)
        SettingRow(
            control = { MuSelect(settings.bar, Trust.BARS, { ShootoutTrustWords.pct(it) }, { t.setSettings(settings.copy(bar = it)) }, Modifier.width(110.dp), small = true) },
            help = ShootoutTrustWords.barHelp(name),
        )
        Micro("Sure from", Modifier.padding(top = 8.dp), color = c.ink45)
        SettingRow(
            control = { MuSelect(settings.sure, SURE, { ShootoutTrustWords.pct(it) }, { t.setSettings(settings.copy(sure = it)) }, Modifier.width(110.dp), small = true) },
            help = ShootoutTrustWords.sureHelp(name),
        )
        Spacer16()
        Micro("Audits", color = c.ink45)
        val missed = r.state.audits.count { !it.agrees }
        Small("${r.state.audits.size} of $name's solo hands came back to you blind; $missed missed. Two misses beyond what a kind's range allows close it until it is earned again.")
        r.state.kinds.filter { it.closedAt != null }.forEach { k -> Small("Closed by its audits: ${k.kind.words}.", color = c.ink) }
        Spacer16()
        MicroLink(if (details) "Hide the details" else "Details: the data, its certainty, what its answers moved", { details = !details }, color = c.ink70)
        if (details) Details(h, r)
    }
}

/** A setting of the gate, its control and its one line of why, side by side (stacked on a phone). */
@Composable
private fun SettingRow(control: @Composable () -> Unit, help: String) {
    if (LocalPhone.current) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            control()
            Help(help)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            control()
            Help(help, Modifier.weight(1f))
        }
    }
}

/** The data, Ai's certainty and what its answers moved, in words (no Brier, no `said · agreed` rows). */
@Composable
private fun Details(h: NeueHolders, r: TrustReport) {
    val c = Mu.colors
    val name = h.ai.name
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro("The data", color = c.ink45)
        Small(ShootoutTrustWords.shareWords(r, name), color = c.ink)
        Small("$name: ${ShootoutTrustWords.lean(r.aiLean)}.", color = c.ink70)
        r.seenLean?.let { Small("Your answers after seeing $name's: ${ShootoutTrustWords.lean(it)}. One of them is worth ${"%.1f".format(r.seenWeight ?: 0.0)} of a blind one.", color = c.ink70) }
        val seen = r.state.seen
        if (seen.sameSeen != null && seen.sameBlind != null) {
            Small("Seeing its answer first, you give that very answer about ${ShootoutWords.inTen(seen.sameSeen!!)} times; blind, about ${ShootoutWords.inTen(seen.sameBlind!!)}.", color = c.ink70)
        }
        val cal = r.state.calibration
        if (cal.n > 0) {
            Spacer16()
            Micro("Its certainty", color = c.ink45)
            Small(ShootoutTrustWords.certainty(cal), color = c.ink)
            cal.bins.filter { it.n > 0 }.forEach { b -> key(b.from) { Small(ShootoutTrustWords.certaintyBin(b), color = c.ink70) } }
        }
        Spacer16()
        Moved(h, r)
    }
}

/** The bars' scale, once over the rows: 50% at the left, 100% at the right, and the tick that is the person's bar. */
@Composable
private fun ScaleHead(bar: Double) {
    val c = Mu.colors
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f))
        Box(Modifier.width(NUMBER_COLUMN))
        // Two lines, so the bar's word never sits on the ends' figures: 50% and 100% over "your bar" under its tick.
        Box(Modifier.width(BAR_WIDTH).height(30.dp)) {
            Mono("50%", Modifier.align(Alignment.TopStart), color = c.ink45)
            Mono("100%", Modifier.align(Alignment.TopEnd), color = c.ink45)
            val x = BAR_WIDTH * ((bar - 0.5) / 0.5).coerceIn(0.0, 1.0).toFloat()
            Micro("your bar", Modifier.align(Alignment.BottomStart).offset(x = (x - BAR_WORD / 2).coerceIn(0.dp, BAR_WIDTH - BAR_WORD)), color = c.ink70)
        }
        Box(Modifier.width(STATE_COLUMN))
    }
}

private val NUMBER_COLUMN = 96.dp
private val BAR_WIDTH = 160.dp
private val STATE_COLUMN = 250.dp

/** About how wide "your bar" is in micro caps, to centre it on the tick. */
private val BAR_WORD = 56.dp

/** A kind's row: its words, its agreement with the range drawn in ink, the hands behind it, its state and why. */
@Composable
private fun KindRow(h: NeueHolders, k: KindTrust, bar: Double, phone: Boolean) {
    val s = h.shootout
    val c = Mu.colors
    val share = k.share
    val number: @Composable () -> Unit = {
        if (share != null) {
            Number("${ShootoutTrustWords.pct(share)} of ${k.pairs.toInt()}", "The hands behind it") { s.behind = Behind.Kind(k.kind.key) }
        } else {
            Mono("–", color = c.ink45)
        }
    }
    val state: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(ShootoutTrustWords.status(k), color = if (k.open) c.ink else c.ink45)
            ShootoutTrustWords.reason(k, bar)?.let { Small(it, color = c.ink45, maxLines = 1) }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (phone) {
            Small(k.kind.words.replaceFirstChar { it.uppercase() }, color = c.ink, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                number()
                Box(Modifier.weight(1f).height(14.dp)) { AgreementBar(k.range, k.sureRange, bar) }
            }
            state(Modifier)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small(k.kind.words.replaceFirstChar { it.uppercase() }, Modifier.weight(1f), color = c.ink, maxLines = 1)
                Box(Modifier.width(NUMBER_COLUMN)) { number() }
                Box(Modifier.width(BAR_WIDTH).height(14.dp)) { AgreementBar(k.range, k.sureRange, bar) }
                state(Modifier.width(STATE_COLUMN))
            }
        }
        if (k.audits > 0) MicroLink("${k.audits} audit${if (k.audits == 1) "" else "s"} · ${k.misses} missed", { s.behind = Behind.Kind(k.kind.key, audits = true) })
    }
    HRule()
}

/**
 * Agreement in ink: 50% to 100% across (the scale's head names both ends and the bar's tick); the range thin, the range
 * on the hands Ai was sure of thick, the person's bar a tick that the thick range's left end must clear.
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
    Help("Each card's worth per copy with ${h.ai.name}'s answers and without them, in points. A change past the card's own range without them is marked for a look.")
    r.moved.take(8).forEach { m ->
        key(m.card, m.stratum) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small(s.card(m.card)?.name ?: "#${m.card}", Modifier.weight(1f), color = c.ink, maxLines = 1)
                Small(ShootoutWords.stratum(m.stratum), color = c.ink45, maxLines = 1)
                Number(ShootoutWords.points(m.with.value), "Its hands") { s.behind = Behind.Card(m.card, m.stratum) }
                Small("without ${ShootoutWords.points(m.without.value)}", color = c.ink45, maxLines = 1)
                Box(Modifier.width(40.dp)) { if (m.flagged) Micro("Look", color = c.ink) }
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
                            Small("“${theme.word}” · ${ShootoutWords.hands(theme.notes.size)}", Modifier.weight(1f), color = c.ink, maxLines = 1)
                            MicroLink("Turn the latest into a rule", { s.addRubricEntry(theme.notes.maxBy { it.at }.text) })
                        }
                        theme.notes.takeLast(3).forEach { n -> Small(n.text, color = c.ink70, maxLines = 2) }
                    }
                }
            }
        }
    }
}
