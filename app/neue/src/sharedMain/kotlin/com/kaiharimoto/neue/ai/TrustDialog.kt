package com.kaiharimoto.neue.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.eval.EvalRun
import com.kaiharimoto.mastertool.core.ai.eval.EvalSet
import com.kaiharimoto.mastertool.core.ai.eval.EvalSets
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.prefs.AiConnection
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Trust (1.0.99, Phase A): how far to trust each connection, measured. For each set of questions with known answers,
 * the connection's last score — pass@1, and pass^k when it was run k times — what it cost, when, and the items it
 * missed; for the fact-checker, how many planted mistakes it caught and how often it cried wolf. A run is started
 * here and asks first what it will spend. Ink only, as every other score in the app.
 */
@Composable
fun TrustDialog(ai: AiState) {
    if (!ai.trustOpen) return
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Small("Connection", color = c.ink70)
                MuSelect(connection, connections, { label(it) }, { chosen = it }, Modifier.weight(1f), small = true)
                Small("Tries", color = c.ink70)
                Segmented(tries, listOf(1, 3), { if (it == 1) "Once" else "3 times" }, { tries = it }, small = true)
            }
            ai.evalNote?.let { Small(it, color = c.ink) }
            // Read afresh when a run is kept.
            val runs = remember(connection.id, ai.evalVersion) { ai.evalRuns(connection.id) }
            val latest = runs.groupBy { it.set }.mapValues { (_, r) -> r.maxBy { it.at } }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                for (set in EvalSets.all) {
                    SetRow(
                        set,
                        latest[set.id],
                        running = running?.takeIf { it.first == set.id }?.let { it.second to it.third },
                        busy = running != null,
                        confirming = asking == set,
                        tries = tries,
                        onRun = { asking = set },
                        onConfirm = {
                            asking = null
                            ai.startEval(set, connection, tries)
                        },
                        onCancel = { asking = null },
                        onStop = { ai.stopEval() },
                    )
                }
            }
        }
    }
}

private fun label(c: AiConnection?): String =
    (c?.label ?: "").ifBlank { c?.provider.orEmpty() } + (c?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

@Composable
private fun SetRow(
    set: EvalSet,
    last: EvalRun?,
    running: Pair<Int, Int>?,
    busy: Boolean,
    confirming: Boolean,
    tries: Int,
    onRun: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuText(set.title, Modifier.weight(1f), style = MuType.body(f).copy(fontWeight = FontWeight.Bold, fontSize = 16.sp), color = c.ink)
            MuText(headline(set, last), style = MuType.body(f).copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = c.ink)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.ink))
        Small(set.about, color = c.ink70)
        if (last != null) Mono(details(set, last), color = c.ink45)
        when {
            running != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Small("Asking ${running.first} of ${running.second}…", color = c.ink)
                MuButton("Stop", onStop, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            confirming -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val n = set.items.size * tries
                Small("$n question${if (n == 1) "" else "s"}, about ${tokens(n.toLong() * EVAL_TOKENS_EACH)} tokens on this connection.", color = c.ink)
                MuButton("Run now", onConfirm, variant = BtnVariant.PRIMARY, size = BtnSize.SM)
                MuButton("Cancel", onCancel, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            else -> MuButton(
                if (last == null) "Run" else "Run again", onRun,
                variant = BtnVariant.SUBTLE, size = BtnSize.SM, enabled = !busy, reason = "Another set is running",
            )
        }
        if (last != null) Misses(set, last)
    }
}

/** The score that matters most, large: the share right first time, or for the checker, mistakes caught. */
private fun headline(set: EvalSet, run: EvalRun?): String {
    if (run == null) return "Not run"
    if (set.checker) {
        val (caught, planted, alarms, clean) = checker(set, run)
        return "caught $caught of $planted · ${alarms} false alarm${if (alarms == 1) "" else "s"} in $clean"
    }
    val right = run.items.count { it.firstPass }
    return "$right of ${run.items.size} · ${(run.passAt1 * 100).toInt()}%"
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

private fun details(set: EvalSet, run: EvalRun): String = buildList {
    add("pass@1 ${(run.passAt1 * 100).toInt()}%")
    if (run.tries > 1) add("every one of ${run.tries} tries ${(run.passAll * 100).toInt()}%")
    add("${tokens(run.tokensIn)} in, ${tokens(run.tokensOut)} out")
    add(DateTimeFormatter.ofPattern("d MMM, HH:mm").format(Instant.ofEpochMilli(run.at).atZone(ZoneId.systemDefault())))
    if (run.model.isNotBlank()) add(run.model)
    if (run.stoppedEarly) add("stopped after ${run.items.size} of ${set.items.size}")
}.joinToString(" · ")

/** The first few items it got wrong, with what the grader read, so a score is never only a number. */
@Composable
private fun Misses(set: EvalSet, run: EvalRun) {
    val c = Mu.colors
    val missed = run.items.filter { !it.firstPass }
    if (missed.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        missed.take(5).forEach { m ->
            val q = set.items.firstOrNull { it.id == m.id }
            val gist = q?.prompt?.lines()?.firstOrNull()?.take(90).orEmpty()
            Small("${m.id} — ${m.read}${if (gist.isNotBlank()) " · $gist" else ""}", color = c.ink70, maxLines = 2)
        }
        if (missed.size > 5) Small("and ${missed.size - 5} more", color = c.ink45)
    }
}

private fun tokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}k"
    else -> n.toString()
}
