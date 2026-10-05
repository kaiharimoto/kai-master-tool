package com.kaiharimoto.neue.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.eval.EvalRun
import com.kaiharimoto.mastertool.core.ai.eval.EvalSet
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.ai.eval.TrustWords
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Trust (1.0.99, Phase A): how far to trust each connection, measured. It opens on a table — a row per set of questions
 * with known answers, the connection's last score, when, and Run — and a row opens out to what the score means, what it
 * cost, and the items it missed, each led by its question (the design review, findings 7, 12–15). For the fact-checker,
 * how many planted mistakes it caught and how often it cried wolf; for the puzzles, a scale from doing nothing to solved.
 * A run is started here and asks first what it will spend. Ink only, as every other score in the app.
 */
@Composable
fun TrustDialog(ai: AiState) {
    if (!ai.trustOpen) return
    val phone = LocalPhone.current
    val connections = ai.prefs.connections
    var chosen by remember { mutableStateOf(ai.prefs.connection ?: connections.firstOrNull()) }
    var tries by remember { mutableStateOf(1) }
    var asking by remember { mutableStateOf<EvalSet?>(null) }
    val running = ai.evalProgress
    MuDialog(
        title = "How far to trust ${ai.name}",
        onDismiss = { ai.trustOpen = false },
        width = 760.dp,
        description = "Questions with known answers, graded by the app, never by a model. A score is a connection's: " +
            "the same questions, asked of another model, give another score.",
        footer = {
            MuButton("Close", { ai.trustOpen = false }, variant = BtnVariant.SECONDARY)
        },
    ) {
        val c = Mu.colors
        val connection = chosen
        if (connections.isEmpty() || connection == null) {
            Help("Set up a connection first: Trust measures one.")
        } else {
            Help(
                "Right first time: the share it got right on its first try. A score is one connection's; each run is a fresh " +
                    "sample, so a few points either way is noise.",
            )
            // The connection whole on a phone — the model is what is being scored — and Tries under it.
            val pick: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    FieldLabel("Connection")
                    MuSelect(connection, connections, { label(it) }, { chosen = it }, Modifier.fillMaxWidth(), small = true)
                }
            }
            val times: @Composable () -> Unit = {
                // As wide as its control: a field label fills what it is given, and took the connection's room.
                Column(Modifier.width(IntrinsicSize.Max)) {
                    FieldLabel("Tries")
                    Segmented(tries, listOf(1, 3), { if (it == 1) "Once" else "3 times" }, { tries = it }, small = true)
                }
            }
            if (phone) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pick(Modifier.fillMaxWidth())
                    times()
                }
            } else {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pick(Modifier.weight(1f))
                    times()
                }
            }
            ai.evalNote?.let { Small(it, Modifier.padding(top = 8.dp), color = c.ink) }
            // Read afresh when a run is kept.
            val runs = remember(connection.id, ai.evalVersion) { ai.evalRuns(connection.id) }
            val latest = runs.groupBy { it.set }.mapValues { (_, r) -> r.maxBy { it.at } }
            Column(Modifier.padding(top = 20.dp)) {
                HRule(strong = true)
                EvalSets.all.forEachIndexed { n, set ->
                    SetRow(
                        ai,
                        n + 1,
                        set,
                        latest[set.id],
                        running = running?.takeIf { it.first == set.id }?.let { it.second to it.third },
                        busy = running != null,
                        confirming = asking == set,
                        tries = tries,
                        open = set.id in ai.trustExpanded,
                        onOpen = { ai.trustExpanded = if (set.id in ai.trustExpanded) ai.trustExpanded - set.id else ai.trustExpanded + set.id },
                        onRun = { asking = set },
                        onConfirm = {
                            asking = null
                            ai.startEval(set, connection, tries)
                        },
                        onCancel = { asking = null },
                        onStop = { ai.stopEval() },
                    )
                    HRule()
                }
            }
        }
    }
}

private fun label(c: AiConnection?): String =
    (c?.label ?: "").ifBlank { c?.provider.orEmpty() } + (c?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

/** One set's row: its number, title, score and Run, when it last ran, and — opened out — what the score means and what it missed. */
@Composable
private fun SetRow(
    ai: AiState,
    n: Int,
    set: EvalSet,
    last: EvalRun?,
    running: Pair<Int, Int>?,
    busy: Boolean,
    confirming: Boolean,
    tries: Int,
    open: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val phone = LocalPhone.current
    val missed = last?.items?.count { !it.firstPass } ?: 0
    val run: @Composable () -> Unit = {
        if (running == null && !confirming) {
            MuButton(
                if (last == null) "Run" else "Run again", onRun,
                variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = !busy, reason = "Another set is running",
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numeral(n)
            MuText(set.title, Modifier.weight(1f), style = MuType.h2(f), color = c.ink, maxLines = 2)
            if (last == null) Small("Not run", color = c.ink45)
            else MuText(headline(set, last), style = MuType.mono(f, 20.sp), color = c.ink, maxLines = 1)
            if (!phone) run()
        }
        // The fact-checker is the one set whose name says nothing to a newcomer: said under its title.
        if (set.checker) Help("The pass that checks ${ai.name}'s answers after it replies (Settings › Ai › Fact-check).", Modifier.padding(start = 30.dp))
        Row(Modifier.padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuText(last?.let { ranWhen(it) }.orEmpty(), Modifier.weight(1f), style = MuType.mono(f), color = c.ink45)
            MicroLink(
                when {
                    open -> "Less ▴"
                    missed > 0 -> "$missed missed ▸"
                    last != null -> "Details ▸"
                    else -> "About ▸"
                },
                onOpen,
            )
            if (phone) run()
        }
        when {
            running != null -> Row(Modifier.padding(start = 30.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Breathe()
                Small("Asking ${running.first} of ${running.second}…", Modifier.weight(1f), color = c.ink)
                MuButton("Stop", onStop, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            confirming -> Row(Modifier.padding(start = 30.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val q = set.items.size * tries
                Mono("$q question${if (q == 1) "" else "s"} · ≈ ${tokens(q.toLong() * tokensEach(set))} tokens on this connection", Modifier.weight(1f), color = c.ink)
                MuButton("Run now", onConfirm, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
                MuButton("Cancel", onCancel, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
        }
        if (open) Details(ai, set, last)
    }
}

/** A set opened out: what it asks, what the score means, the puzzles' scale, what it cost, and what it missed. */
@Composable
private fun Details(ai: AiState, set: EvalSet, last: EvalRun?) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Column(Modifier.padding(start = 30.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Small(set.about, color = c.ink70)
        if (last != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (set.checker) {
                    val (_, _, alarms, clean) = checker(set, last)
                    MuText("$alarms", style = MuType.mono(f, 20.sp), color = c.ink)
                    Small("false alarm${if (alarms == 1) "" else "s"} in $clean clean answers: how often it cries wolf", Modifier.padding(bottom = 3.dp), color = c.ink70)
                } else {
                    MuText("${(last.passAt1 * 100).toInt()}%", style = MuType.mono(f, 20.sp), color = c.ink)
                    Small(
                        "right first time" + if (last.tries > 1) " · right all ${last.tries} times ${(last.passAll * 100).toInt()}%" else "",
                        Modifier.padding(bottom = 3.dp), color = c.ink70,
                    )
                }
            }
        }
        if (set.id == EvalSets.PUZZLES) PuzzleScale(last?.items?.count { it.firstPass })
        if (last != null) MuText(costWords(set, last), style = MuType.mono(f), color = c.ink45)
        if (last != null) Misses(ai, set, last)
    }
}

/**
 * The puzzles' frame of reference as a scale (finding 12): a 1px ink track from 0 to every puzzle, ticks where doing
 * nothing, only attacking and the known solutions land, and the score a 6px ink square on it.
 */
@Composable
private fun PuzzleScale(score: Int?) {
    val c = Mu.colors
    val (nothing, greedy, solved) = PUZZLE_BOUNDS
    val n = PUZZLE_COUNT.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
            val w = maxWidth
            fun x(v: Int) = w * (v.toFloat() / n)
            listOf(nothing, greedy, solved).distinct().forEach { v ->
                val left = (x(v) - 8.dp).coerceIn(0.dp, w - 16.dp)
                Mono("$v", Modifier.offset(x = left).width(16.dp), color = c.ink45, align = TextAlign.Center)
            }
            Canvas(Modifier.fillMaxWidth().height(14.dp).offset(y = 16.dp)) {
                val y = size.height / 2
                drawLine(c.ink, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                listOf(nothing, greedy, solved).forEach { v ->
                    val tx = (size.width * v / n).coerceIn(0.5f, size.width - 0.5f)
                    drawLine(c.ink, Offset(tx, y - 4.dp.toPx()), Offset(tx, y + 4.dp.toPx()), 1.dp.toPx())
                }
                if (score != null) {
                    val s = 6.dp.toPx()
                    val sx = (size.width * score / n).coerceIn(s / 2, size.width - s / 2)
                    drawRect(c.ink, Offset(sx - s / 2, y - s / 2), Size(s, s))
                }
            }
        }
        Help(
            "For scale: a player who does nothing scores $nothing; one who only attacks scores $greedy; the known solutions score $solved." +
                if (score != null) " The square is this connection's $score." else "",
        )
    }
}

/** The score that matters most, large and in mono: right first time of all, or for the checker, mistakes caught. */
private fun headline(set: EvalSet, run: EvalRun): String {
    if (set.checker) {
        val (caught, planted) = checker(set, run)
        return "caught $caught of $planted"
    }
    return "${run.items.count { it.firstPass }} of ${run.items.size}"
}

/** For the checker: mistakes caught of those planted, and false alarms of the clean answers. */
private fun checker(set: EvalSet, run: EvalRun): List<Int> {
    val planted = set.items.filter { (it.grader as? Grader.Planted)?.hasError == true }.map { it.id }.toSet()
    val byId = run.items.associateBy { it.id }
    val caught = planted.count { byId[it]?.firstPass == true }
    val clean = run.items.filter { it.id !in planted }
    val alarms = clean.count { !it.firstPass }
    return listOf(caught, planted.count { it in byId }, alarms, clean.size)
}

/** When it last ran, and on which model: "3 Oct · claude-opus-5-5". */
private fun ranWhen(run: EvalRun): String = listOfNotNull(
    DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(run.at).atZone(ZoneId.systemDefault())),
    run.model.takeIf { it.isNotBlank() },
).joinToString(" · ")

/** What the run cost and when, whole: never cut (finding 14). */
private fun costWords(set: EvalSet, run: EvalRun): String = buildList {
    add("${tokens(run.tokensIn)} in, ${tokens(run.tokensOut)} out")
    add(DateTimeFormatter.ofPattern("d MMM, HH:mm").format(Instant.ofEpochMilli(run.at).atZone(ZoneId.systemDefault())))
    if (run.model.isNotBlank()) add(run.model)
    if (run.stoppedEarly) add("stopped after ${run.items.size} of ${set.items.size}")
}.joinToString(" · ")

/**
 * The first few items it got wrong, each led by its question cut at a word, then what it answered against what is right,
 * and its id last in mono (finding 13); a card named in a question is the card, as in Ai's replies.
 */
@Composable
private fun Misses(ai: AiState, set: EvalSet, run: EvalRun) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val missed = run.items.filter { !it.firstPass }
    if (missed.isEmpty()) return
    val open: (String) -> Unit = remember(ai) { { name -> cardNamed(ai, name)?.let { ai.h.neue.viewing = Viewing(it, null, 0) } } }
    CompositionLocalProvider(LocalCardLink provides open) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            missed.take(5).forEach { m ->
                val miss = TrustWords.miss(set.items.firstOrNull { it.id == m.id }, m)
                val question = styled(ChatMarkdown.inline(miss.question))
                val line = buildAnnotatedString {
                    if (question.isNotEmpty()) {
                        append(question)
                        append(" — ")
                    }
                    append(miss.verdict)
                    withStyle(SpanStyle(fontFamily = MuType.mono(f).fontFamily, color = c.ink45, fontSize = 11.sp)) { append("  ·  ${miss.id}") }
                }
                MuText(line, style = MuType.small(f), color = c.ink70)
            }
            if (missed.size > 5) Help("and ${missed.size - 5} more")
        }
    }
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}
