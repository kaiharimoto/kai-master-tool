package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.border
import com.kaiharimoto.mastertool.core.shootout.bench.Call
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.HandKind
import com.kaiharimoto.mastertool.core.shootout.teach.TeachModes
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Body
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
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

/**
 * The results (Phase S §5; Phase G, G.4 "Shootout you can read", mockup A): what the hands have called, each with a next
 * step; each situation's win rate in one strip with the roll's call and how much is settled; then every card on one shared
 * axis — a 26 dp row a card, the zero rule unbroken — with one more copy's worth beside it, and the pairs beside the cards
 * where the window is wide. Every number opens its trials ([Behind]).
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
    val axis = remember(r) { Axis.of(r) }
    val calls = remember(r) { r.calls() }
    val called = remember(calls) { calls.map { it.card to it.stratum }.toSet() }
    // A phone shows one situation at a time (design review, 1.1.6): four side by side leave no room for a plot.
    var one by remember(r) { mutableStateOf(r.strata.firstOrNull()) }
    val shown = if (phone) listOfNotNull(one) else r.strata
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Pairs stand beside the cards only when there are some: an empty column would take the plots' room.
        val wide = !phone && maxWidth >= 1560.dp && r.pairs.isNotEmpty()
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            SoFar(h, r, calls)
            StrataStrip(h, r, phone)
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f)) {
                    SectionTitle(null, "Cards, per copy")
                    Help(
                        "Each card's worth in points of win chance: what one more copy in the opening hand adds, against the card the deck would have dealt instead. " +
                            "Every card is drawn on the same axis: the square is the number, the thick line its 80% range and the thin one its 95%. " +
                            (if (r.strata.any { !it.goingFirst }) "Going second, the hollow square is the card as the turn's draw, rated apart from the five. " else "") +
                            "“1 more” is a further copy in the place of any other card alike. A press on a number lists the hands behind it.",
                        Modifier.padding(top = 8.dp, bottom = 12.dp).widthIn(max = 900.dp),
                    )
                    if (phone && r.strata.size > 1) {
                        MuSelect(one, r.strata, { st -> st?.let { ShootoutWords.situation(it, null) } ?: "" }, { one = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp), small = true)
                    }
                    CardsPlot(s, r, shown, axis, if (phone) PlotColumns(name = null, value = 48.dp, next = 40.dp) else PlotColumns(name = 240.dp), called)
                    if (!wide) Pairs(s, r, axis, Modifier.padding(top = 24.dp), phone)
                }
                if (wide) Pairs(s, r, axis, Modifier.width(360.dp), phone)
            }
        }
    }
}

/**
 * "So far", first (design review, 1.1.6; S.md §5: "a verdict only where the range supports one"): up to three cards the
 * hands have called — clear of zero at 95 % with every card counted (Phase G, D4) — each with its next step: a card that
 * costs is tried at −1 in the builder or sided out in the turn it was called in; one that gains is tried at +1.
 */
@Composable
private fun SoFar(h: NeueHolders, r: ShootoutResults, calls: List<Call>) {
    val s = h.shootout
    val c = Mu.colors
    val best = calls.filter { it.gains }.maxByOrNull { it.estimate.value }
    Column(Modifier.widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("So far", color = c.ink45)
        if (calls.isEmpty()) {
            Body(ShootoutWords.tooEarly(r.settled, r.handsToSettle()), color = c.ink)
        } else {
            // One line a card, its clearest situation: three cards, not one card three times.
            val lines = calls.distinctBy { it.card }.take(3)
            lines.forEach { call ->
                key(call.card, call.stratum) {
                    val copies = r.cards.firstOrNull { it.card == call.card }?.copies ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Body(ShootoutWords.call(s.card(call.card)?.name ?: "#${call.card}", call, best = call === best), Modifier.weight(1f, fill = false), color = c.ink)
                        if (call.gains) {
                            if (copies in 1..2) MuButton("Try +1", { s.tryInBuilder(call.card) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                        } else {
                            MuButton("Try −1", { s.tryInBuilder(call.card) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                            if (s.canSide) MuButton("Side it out", { s.sideOut(call.card, call.stratum.goingFirst) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                        }
                    }
                }
            }
            // The rest, by name and where: another situation of a card above is a call too, not another card.
            val rest = calls.filter { it !in lines }
            if (rest.isNotEmpty()) {
                Help(
                    "Also called: " + rest.take(4).joinToString("; ") { "${s.card(it.card)?.name ?: "#${it.card}"} ${ShootoutWords.where(it.stratum)} (${ShootoutWords.points(it.estimate.value)})" } +
                        (if (rest.size > 4) "; and ${rest.size - 4} more" else "") + ". A called card's name is set heavier below.",
                )
            }
        }
    }
}

/**
 * The situations as one strip (mockup A): each one's win rate over real hands with its range on 0–100, or why it waits;
 * the roll's call under it; and how much is settled, as progress with the hands left.
 */
@Composable
private fun StrataStrip(h: NeueHolders, r: ShootoutResults, phone: Boolean) {
    val c = Mu.colors
    val alone = h.shootout.bench?.alone ?: true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro(if (alone) "How often a real hand does what the deck wants" else "Win rate over real hands, by their real odds", color = c.ink45)
        val tiles = r.strata + r.waiting.keys
        val rows = if (phone) tiles.chunked(2) else listOf(tiles)
        Column(Modifier.border(1.dp, c.ink25)) {
            rows.forEachIndexed { i, row ->
                key(i) {
                    if (i > 0) HRule()
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        row.forEachIndexed { j, stratum ->
                            key(stratum) {
                                if (j > 0) Box(Modifier.width(1.dp).fillMaxHeight().drawBehind { drawRect(c.ink25) })
                                StratumCell(h, r, stratum, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        // A phone's last row of one keeps its cell the width of the others.
                        if (row.size < (rows.firstOrNull()?.size ?: 0)) Spacer(Modifier.weight((rows.first().size - row.size).toFloat()))
                    }
                }
            }
        }
        r.roll()?.let { Body(ShootoutWords.roll(it), color = c.ink) }
        Settled(r)
        val more = listOfNotNull(
            r.steadiness?.let(ShootoutWords::steadiness),
            "${r.fitted} of ${ShootoutWords.hands(r.kept)} read",
        ).joinToString(" · ") + if (r.olderPlans > 0) ". ${ShootoutWords.hands(r.olderPlans)} after siding were dealt under an older plan: kept, labelled, and pooled." else ""
        Small(more, color = c.ink45)
    }
}

/** How much is settled, as progress: the share of cards known within the stop rule's width, and about how many hands are left. */
@Composable
private fun Settled(r: ShootoutResults) {
    val c = Mu.colors
    val k = r.settled
    val share = if (k.of == 0) 0f else (k.known.toFloat() / k.of).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.width(160.dp).height(6.dp).drawBehind {
                drawRect(c.ink12)
                drawRect(c.ink, size = Size(size.width * share, size.height))
            },
        )
        val left = r.handsToSettle()?.takeIf { it > 0 && !k.enough }?.let { " · about ${ShootoutWords.roundHands(it)} more hands" } ?: ""
        Small(
            "${k.known} of ${k.of} cards known within ±${k.halfWidth.toInt()} points" +
                (r.inPlay.singleOrNull()?.takeIf { r.strata.size > 1 }?.let { " (${ShootoutWords.situation(it, null)})" } ?: "") +
                (if (k.enough) ", enough to stop" else left),
            color = c.ink,
        )
    }
}

@Composable
private fun StratumCell(h: NeueHolders, r: ShootoutResults, stratum: Stratum, modifier: Modifier) {
    val c = Mu.colors
    val s = h.shootout
    val why = r.waiting[stratum]
    val n = r.counts[stratum] ?: 0
    Column(modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro(ShootoutWords.stratum(stratum), color = c.ink70)
        val e = r.winRates[stratum]
        if (why != null || e == null) {
            Micro("Waiting", color = c.ink45, size = 14.sp)
            Small(why ?: "No hands dealt here yet.", maxLines = 3)
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Number("${"%.0f".format(e.value)}%", null, textSize = 20) { s.behind = Behind.WinRate(stratum) }
                Mono("±${"%.0f".format(e.halfWidth95)} · ${ShootoutWords.hands(n)}", color = c.ink45)
            }
            RateBar(e, Modifier.fillMaxWidth().height(10.dp))
            r.checks[stratum]?.takeIf { it.plainTrials >= 2 }?.let { k ->
                Help(ShootoutWords.randomCheck(k), maxLines = 2)
            }
        }
    }
}

/** A win rate on 0–100 % in ink: a faint rule at 50, the 95 % range thin, the 80 % thick, the value a square. */
@Composable
private fun RateBar(e: Estimate, modifier: Modifier) {
    val c = Mu.colors
    Box(
        modifier.drawBehind {
            val w = size.width
            val mid = size.height / 2
            fun x(v: Double) = (v / 100).coerceIn(0.0, 1.0).toFloat() * w
            drawLine(c.ink12, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
            drawLine(c.ink25, Offset(x(50.0), 0f), Offset(x(50.0), size.height), 1.dp.toPx())
            drawLine(c.ink45, Offset(x(e.range95.start), mid), Offset(x(e.range95.endInclusive), mid), 1.dp.toPx())
            drawLine(c.ink, Offset(x(e.range80.start), mid), Offset(x(e.range80.endInclusive), mid), 3.dp.toPx())
            val d = 6.dp.toPx()
            drawRect(c.ink, Offset(x(e.value) - d / 2, mid - d / 2), Size(d, d))
        },
    )
}

/** The pairs whose 95 % range excludes zero, on the cards' own axis: beside the cards when the window is wide, else under them. */
@Composable
private fun Pairs(s: Shootouts, r: ShootoutResults, axis: Axis, modifier: Modifier, phone: Boolean) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(null, "Pairs")
        if (r.pairs.isEmpty()) {
            Help("No pair yet whose 95% range excludes zero. A pair must earn its place: it is shown once the hands make it plain.")
        } else {
            Help("The extra win chance from holding both, beyond the two cards' own, in points, on the cards' axis.")
            Column {
                HRule()
                r.pairs.forEach { p ->
                    key(p.a, p.b, p.stratum) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RowText("${s.card(p.a)?.name ?: p.a} + ${s.card(p.b)?.name ?: p.b}", maxLines = 2)
                            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Micro(ShootoutWords.stratum(p.stratum), Modifier.width(112.dp), color = c.ink45)
                                Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
                                    Number(ShootoutWords.points(p.estimate.value), ShootoutWords.hands(p.trials)) { s.behind = Behind.Pair(p.a, p.b, p.stratum) }
                                }
                                PlotCell(axis, p.estimate, null, Modifier.weight(1f).fillMaxHeight(), onOpen = { s.behind = Behind.Pair(p.a, p.b, p.stratum) }, openCaption = ShootoutWords.hands(p.trials))
                            }
                        }
                        HRule()
                    }
                }
            }
        }
    }
}

/** A number that opens its trials. */
@Composable
internal fun Number(text: String, caption: String?, textSize: Int = 14, color: Color = Mu.colors.ink, onClick: () -> Unit) {
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
    ) { Mono(text, color = color, size = textSize.sp) }
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
        is Behind.Drawn -> "${s.card(behind.card)?.name ?: behind.card} as your draw · ${ShootoutWords.stratum(behind.stratum)}"
        is Behind.Pair -> "${s.card(behind.a)?.name ?: behind.a} + ${s.card(behind.b)?.name ?: behind.b}"
        is Behind.WinRate -> "Every hand · ${ShootoutWords.stratum(behind.stratum)}"
        Behind.All -> "Every hand"
        Behind.Ai -> "Every answer of ${h.ai.name}'s"
        is Behind.Kind -> (if (behind.audits) "Audits · " else "${h.ai.name} beside you · ") + (HandKind.parse(behind.key)?.words?.replaceFirstChar { it.uppercase() } ?: behind.key)
    }
    MuDialog(
        title, { s.behind = null }, width = 760.dp,
        description = "${ShootoutWords.hands(trials.size)} behind this number, newest first. Change an answer you gave, or erase a hand: the ratings are read again.",
    ) {
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
            val verdict = verdictWords(t.kind, t.given, alone)
            Micro(verdict, Modifier.weight(1f), color = c.ink)
            val marks = listOfNotNull(
                t.reason.takeIf { it == "plain" && t.judge == StoredTrial.PERSON }?.let { "shuffled" },
                t.reason.takeIf { it == "repeat" }?.let { "shown again" },
                "saw $ai first".takeIf { t.sawAi },
                (if (t.mode == TeachModes.SOLO) "$ai alone" else ai).takeIf { t.judge == StoredTrial.AI },
                t.mode?.takeIf { it != TeachModes.SOLO }?.let(::modeWords),
                "older plan".takeIf { s.bench?.underOlderPlan(t) == true },
                t.first?.takeIf { t.adjusted != null }?.let { "changed from ${verdictWords(t.kind, it, alone).lowercase()}" },
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
        TrialEdits(s, t, alone)
    }
}

/** A trial's answer in words: a rating's band, or which of a comparison's hands was chosen. */
private fun verdictWords(kind: String, given: String?, alone: Boolean): String = when (kind) {
    StoredTrial.COMPARE -> if (given == StoredTrial.LEFT) "Chose the first" else "Chose the second"
    else -> Answer.entries.firstOrNull { it.name == given }?.let { ShootoutWords.label(it, alone) } ?: (given ?: "")
}

/**
 * A kept trial's own actions (2026-10, kai: "a way to adjust trials and erase them"): the person's answer changed in
 * place — the answers offered as the trial was, the one given marked — and any trial erased, asked once before it goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrialEdits(s: Shootouts, t: StoredTrial, alone: Boolean) {
    var changing by remember(t.id) { mutableStateOf(false) }
    var erasing by remember(t.id) { mutableStateOf(false) }
    val mine = t.judge == StoredTrial.PERSON
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            erasing -> {
                Small(
                    if (mine) "Erase this hand? Its notes, and any answer of Ai's to it, go too." else "Erase this answer?",
                    Modifier.align(Alignment.CenterVertically), maxLines = 2,
                )
                MuButton("Erase", { erasing = false; s.erase(t) }, size = BtnSize.SM)
                MuButton("Keep", { erasing = false }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            changing -> {
                val options = if (t.kind == StoredTrial.COMPARE) {
                    listOf(StoredTrial.LEFT to "The first", StoredTrial.RIGHT to "The second")
                } else {
                    ShootoutWords.SCALE.map { it.name to ShootoutWords.label(it, alone) }
                }
                options.forEach { (value, words) ->
                    MuButton(words, {
                        changing = false
                        if (value != t.given) s.adjust(t, value)
                    }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, toggled = value == t.given)
                }
                MuButton("Cancel", { changing = false }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            else -> {
                if (mine) MuButton("Change answer", { changing = true }, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton("Erase", { erasing = true }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
        }
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
