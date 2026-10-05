package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.CardCell
import com.kaiharimoto.mastertool.core.shootout.bench.CardResult
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.HandKind
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The results (Phase S §5, a first cut): each stratum's win rate over real hands; how much is settled and how noisy the
 * person's answers are; each card's worth per copy in every stratum side by side, with its 80 % and 95 % ranges drawn
 * in ink, its draw rate and the trials behind it; and the pairs whose 95 % range excludes zero. Every number opens its
 * trials ([Behind]).
 */
@Composable
internal fun ResultsView(h: NeueHolders, phone: Boolean) {
    val s = h.shootout
    val r = s.results
    if (r == null) {
        Row(Modifier.padding(32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.thinking) {
                Breathe()
                Small("Reading every trial")
            } else {
                Small(s.problem ?: "No results yet.")
            }
        }
        return
    }
    if (r.kept == 0) {
        EmptyState("No hands judged yet.", "Begin a session: a few minutes of hands, and the first ratings stand here with their ranges.")
        return
    }
    val c = Mu.colors
    val scroll = rememberScrollState()
    val scale = scaleOf(r)
    val alone = s.bench?.alone ?: true
    // A phone shows one situation at a time (design review, 1.1.6): four stacked made a card's row a screen tall.
    var one by remember(r) { mutableStateOf(r.strata.firstOrNull()) }
    val shown = if (phone) listOfNotNull(one) else r.strata
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SoFar(h, r)
        // The situations, side by side: each one's win rate over real hands, or why it waits.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(if (alone) "How often a real hand does what the deck wants" else "Win rate over real hands, by their real odds", color = c.ink45)
            val tiles = r.strata + r.waiting.keys
            val rows = if (phone) tiles.chunked(2) else listOf(tiles)
            rows.forEachIndexed { i, row ->
                key(i) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { stratum -> key(stratum) { StratumTile(h, r, stratum, Modifier.weight(1f).fillMaxHeight()) } }
                    }
                }
            }
            Small(
                listOfNotNull(
                    "${r.settled.known} of ${r.settled.of} cards known within ±${r.settled.halfWidth.toInt()} points" + if (r.settled.enough) ", enough to stop" else "",
                    r.steadiness?.let(ShootoutWords::steadiness),
                    "${r.fitted} of ${ShootoutWords.hands(r.kept)} read",
                ).joinToString(" · ") + if (r.olderPlans > 0) ". ${ShootoutWords.hands(r.olderPlans)} after siding were dealt under an older plan: kept, labelled, and pooled." else "",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SectionTitle(null, "Cards, per copy")
            Help(
                "A card's worth is in points of win chance: what one more copy in the opening hand adds, against the card the deck would have dealt instead. The thick line is the 80% range, the thin one the 95%; a press on a number lists the hands behind it.",
                Modifier.padding(top = 8.dp, bottom = 12.dp).widthIn(max = 900.dp),
            )
            if (phone && r.strata.size > 1) {
                MuSelect(one, r.strata, { st -> st?.let { ShootoutWords.situation(it, null) } ?: "" }, { one = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), small = true)
            }
            if (!phone) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Micro("Card", Modifier.width(CARD_COLUMN), color = c.ink45)
                    r.strata.forEach { stratum -> key(stratum) { Micro(ShootoutWords.stratum(stratum), Modifier.weight(1f), color = c.ink45) } }
                }
                HRule(strong = true)
            }
            var role: String? = null
            r.cards.forEach { row ->
                key(row.card) {
                    if (row.role != role) {
                        Micro(row.role, Modifier.padding(top = 16.dp, bottom = 4.dp), color = c.ink70)
                        HRule()
                    }
                    CardRow(h, r, row, scale, phone, shown)
                    HRule()
                }
                role = row.role
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(null, "Pairs")
            if (r.pairs.isEmpty()) {
                Help("No pair yet whose 95% range excludes zero. A pair must earn its place: it is shown once the hands make it plain.")
            } else {
                Help("The extra win chance from holding both, beyond the two cards' own, in points.")
                r.pairs.forEach { p ->
                    key(p.a, p.b, p.stratum) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RowText("${s.card(p.a)?.name ?: p.a} + ${s.card(p.b)?.name ?: p.b}", Modifier.weight(1f))
                            Small(ShootoutWords.stratum(p.stratum), color = c.ink45, maxLines = 1)
                            Number(ShootoutWords.points(p.estimate.value), ShootoutWords.hands(p.trials)) { s.behind = Behind.Pair(p.a, p.b, p.stratum) }
                            Box(Modifier.width(160.dp).height(14.dp)) { RangeBar(p.estimate, scale) }
                        }
                        HRule()
                    }
                }
            }
        }
    }
}

private val CARD_COLUMN = 280.dp

/**
 * "So far", first (design review, 1.1.6; S.md §5: "a verdict only where the range supports one"): up to three cards the
 * hands have called, each where its 80% range lies wholly on one side of zero, else how far there is to go.
 */
@Composable
private fun SoFar(h: NeueHolders, r: ShootoutResults) {
    val s = h.shootout
    val c = Mu.colors
    val calls = remember(r) { r.calls() }
    val best = calls.filter { it.gains }.maxByOrNull { it.estimate.value }
    Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("So far", color = c.ink45)
        if (calls.isEmpty()) {
            Body(ShootoutWords.tooEarly(r.settled, r.handsToSettle()), color = c.ink)
        } else {
            // One line a card, its clearest situation: three cards, not one card three times.
            val lines = calls.distinctBy { it.card }.take(3)
            lines.forEach { call ->
                key(call.card, call.stratum) {
                    Body(ShootoutWords.call(s.card(call.card)?.name ?: "#${call.card}", call, best = call === best), color = c.ink)
                }
            }
            if (calls.size > lines.size) Help("And ${calls.size - lines.size} more below, each where its range is clear of zero.")
        }
    }
}

/** The bars' half-width in points: the widest 95 % range, rounded up to five, at least ten. */
private fun scaleOf(r: ShootoutResults): Double {
    var m = 10.0
    r.cards.forEach { row -> row.cells.values.forEach { m = max(m, max(abs(it.estimate.range95.start), abs(it.estimate.range95.endInclusive))) } }
    r.pairs.forEach { m = max(m, max(abs(it.estimate.range95.start), abs(it.estimate.range95.endInclusive))) }
    return ceil(m / 5) * 5
}

@Composable
private fun StratumTile(h: NeueHolders, r: ShootoutResults, stratum: Stratum, modifier: Modifier) {
    val c = Mu.colors
    val s = h.shootout
    val why = r.waiting[stratum]
    val n = r.counts[stratum] ?: 0
    Column(modifier.border(1.dp, if (why == null) c.ink else c.ink25).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro(ShootoutWords.stratum(stratum), color = c.ink70)
        val e = r.winRates[stratum]
        if (why != null || e == null) {
            Micro("Waiting", color = c.ink45, size = 14.sp)
            Small(why ?: "No hands dealt here yet.", maxLines = 3)
        } else {
            Number("${"%.0f".format(e.value)}%", null, textSize = 22) { s.behind = Behind.WinRate(stratum) }
            Small("± ${"%.0f".format(e.halfWidth95)} points · ${ShootoutWords.hands(n)}")
            r.checks[stratum]?.takeIf { it.plainTrials >= 2 }?.let { k ->
                Small("Random hands only: ${"%.0f".format(k.corrected)}% (${ShootoutWords.hands(k.plainTrials)})", color = c.ink45)
            }
        }
    }
}

@Composable
private fun CardRow(h: NeueHolders, r: ShootoutResults, row: CardResult, scale: Double, phone: Boolean, strata: List<Stratum>) {
    val s = h.shootout
    val c = Mu.colors
    val card = s.card(row.card)
    val head: @Composable (Modifier) -> Unit = { m ->
        Row(m, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (card != null) NeueCard(card, Modifier.size(28.dp, 41.dp), format = h.builder.format, foil = "off")
            Column(Modifier.weight(1f)) {
                RowText(card?.name ?: row.card.toString(), maxLines = 1)
                Micro("${row.copies} in the deck", color = c.ink45)
            }
        }
    }
    if (phone) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            head(Modifier.fillMaxWidth())
            strata.forEach { stratum ->
                key(stratum) { Cell(s, row, stratum, row.cells[stratum], scale) }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            head(Modifier.width(CARD_COLUMN))
            r.strata.forEach { stratum -> key(stratum) { Box(Modifier.weight(1f)) { Cell(s, row, stratum, row.cells[stratum], scale) } } }
        }
    }
}

@Composable
private fun Cell(s: Shootouts, row: CardResult, stratum: Stratum, cell: CardCell?, scale: Double) {
    val c = Mu.colors
    if (cell == null) {
        Micro("Not dealt here", color = c.ink25)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Number(ShootoutWords.points(cell.estimate.value), ShootoutWords.hands(cell.trials)) { s.behind = Behind.Card(row.card, stratum) }
            Mono("± ${"%.1f".format(cell.estimate.halfWidth95)}", color = c.ink45)
        }
        Box(Modifier.fillMaxWidth().height(12.dp)) { RangeBar(cell.estimate, scale) }
        Micro("${ShootoutWords.hands(cell.trials)} · drawn ${ShootoutWords.percent(cell.drawShare)}", color = c.ink45)
    }
}

/** A number that opens its trials. */
@Composable
internal fun Number(text: String, caption: String?, textSize: Int = 14, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Box(
        Modifier
            .hoverable(source)
            .cursorPointer(caption = caption ?: "Its hands", showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick)
            .drawBehind {
                if (hovered) drawLine(c.ink, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx())
            },
    ) { Mono(text, color = c.ink, size = textSize.sp) }
}

/**
 * A range in ink (S.md §5: "ink only"): zero as a faint rule, the 95 % range a thin line, the 80 % range a thick one,
 * the value a square. [scale] is the half-width in points the bar spans.
 */
@Composable
internal fun RangeBar(e: Estimate, scale: Double) {
    val c = Mu.colors
    Box(
        Modifier.fillMaxSize().drawBehind {
            val w = size.width
            val mid = size.height / 2
            fun x(v: Double) = ((v / scale).coerceIn(-1.0, 1.0).toFloat() * 0.5f + 0.5f) * w
            drawLine(c.ink25, Offset(x(0.0), 0f), Offset(x(0.0), size.height), 1.dp.toPx())
            drawLine(c.ink45, Offset(x(e.range95.start), mid), Offset(x(e.range95.endInclusive), mid), 1.dp.toPx())
            drawLine(c.ink, Offset(x(e.range80.start), mid), Offset(x(e.range80.endInclusive), mid), 3.dp.toPx())
            val d = 5.dp.toPx()
            drawRect(c.ink, Offset(x(e.value) - d / 2, mid - d / 2), Size(d, d))
        },
    )
}

// ---- the trials behind a number ---------------------------------------------------------------------------------

/** The app's short date and time, `3 Oct, 23:46` (design review, 1.1.6). */
private val WHEN: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
internal fun TrialsDialog(h: NeueHolders, behind: Behind) {
    val s = h.shootout
    val c = Mu.colors
    val trials = remember(behind, s.log) { s.trialsBehind(behind) }
    val alone = s.bench?.alone ?: true
    val title = when (behind) {
        is Behind.Card -> "${s.card(behind.card)?.name ?: behind.card} · ${ShootoutWords.stratum(behind.stratum)}"
        is Behind.Pair -> "${s.card(behind.a)?.name ?: behind.a} + ${s.card(behind.b)?.name ?: behind.b}"
        is Behind.WinRate -> "Every hand · ${ShootoutWords.stratum(behind.stratum)}"
        Behind.All -> "Every hand"
        Behind.Ai -> "Every answer of ${h.ai.name}'s"
        is Behind.Kind -> (if (behind.audits) "Audits · " else "${h.ai.name} beside you · ") + (HandKind.parse(behind.key)?.words?.replaceFirstChar { it.uppercase() } ?: behind.key)
    }
    MuDialog(title, { s.behind = null }, width = 760.dp, description = "${ShootoutWords.hands(trials.size)} behind this number, newest first.") {
        trials.take(MAX_LISTED).forEach { t ->
            key(t.id) {
                TrialLine(s, t, alone, h.ai.name)
                HRule()
            }
        }
        if (trials.size > MAX_LISTED) Help("And ${trials.size - MAX_LISTED} older.", Modifier.padding(top = 8.dp))
    }
}

private const val MAX_LISTED = 200

@Composable
private fun TrialLine(s: Shootouts, t: StoredTrial, alone: Boolean, ai: String) {
    val c = Mu.colors
    fun names(ids: List<Int>) = ids.joinToString(", ") { s.card(it)?.name ?: it.toString() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono(if (t.at > 0) WHEN.format(Instant.ofEpochMilli(t.at).atZone(ZoneId.systemDefault())) else "", color = c.ink45)
            Small(Stratum.entries.firstOrNull { it.name == t.stratum }?.let(ShootoutWords::stratum) ?: t.stratum, color = c.ink45, maxLines = 1)
            val verdict = when (t.kind) {
                StoredTrial.COMPARE -> if (t.prefer == StoredTrial.LEFT) "Chose the first" else "Chose the second"
                else -> Answer.entries.firstOrNull { it.name == t.answer }?.let { ShootoutWords.label(it, alone) } ?: (t.answer ?: "")
            }
            Micro(verdict, Modifier.weight(1f), color = c.ink)
            val marks = listOfNotNull(
                t.reason.takeIf { it == "plain" && t.judge == StoredTrial.PERSON }?.let { "shuffled" },
                t.reason.takeIf { it == "repeat" }?.let { "shown again" },
                "saw $ai first".takeIf { t.sawAi },
                (if (t.mode == TeachModes.SOLO) "$ai alone" else ai).takeIf { t.judge == StoredTrial.AI },
                t.mode?.takeIf { it != TeachModes.SOLO }?.let(::modeWords),
                "older plan".takeIf { s.bench?.underOlderPlan(t) == true },
            )
            if (marks.isNotEmpty()) Small(marks.joinToString(" · "), color = c.ink45, maxLines = 1)
        }
        // Ai's own line (stage 3): how sure it said it was, and why.
        t.ai?.takeIf { t.judge == StoredTrial.AI }?.let { v ->
            val sure = v.sure?.let(ShootoutWords::certainty)
            Small(listOfNotNull(sure, v.why?.let { "“$it”" }).joinToString(" · "), color = c.ink70, maxLines = 2)
        }
        s.log?.notesOn(t.id)?.forEach { n -> Small("Note: ${n.text}", color = c.ink70, maxLines = 3) }
        if (t.kind == StoredTrial.COMPARE) {
            Small("First: ${names(t.left)}", maxLines = 2)
            Small("Second: ${names(t.right)}", maxLines = 2)
        } else {
            Small("Yours: ${names(t.hand)}", maxLines = 2)
        }
        t.opponent?.let { Small("Theirs: ${names(it)}", color = c.ink45, maxLines = 2) }
    }
}

/** How an answer was given (stage 3), in a word for the trials list. */
internal fun modeWords(mode: String): String = when (mode) {
    TeachModes.CALIBRATION -> "calibration set"
    TeachModes.EXAM -> "exam"
    TeachModes.APPRENTICE -> "apprentice"
    TeachModes.SUPERVISED -> "supervised"
    TeachModes.AUDIT -> "audit"
    else -> mode
}
