package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesCoverage
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesDeck
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesReading
import com.kaiharimoto.mastertool.core.duel.match.series.SeriesWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.duel.Series
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/** How a comparison is measured (G2.1): on the same hands by the goldfish, or by simulated matches against the field. */
internal enum class MeasuredBy(val words: String) { HANDS("Same hands"), MATCHES("Simulated matches") }

/**
 * A comparison by simulated matches, set up (G2.1): the field it plays — the other decks of the deck's web with their
 * shares, each side's plans — what the engine plays of every deck, how many seed pairs, what that costs, and Start.
 */
@Composable
internal fun SeriesSetupPanel(
    h: NeueHolders,
    a: SeriesDeck,
    b: SeriesDeck?,
    bDeckId: String?,
    label: String,
    kit: GoldfishKit,
    /** Why the change itself cannot be played fairly (a card it brings in or cuts is not written), or null. */
    unwritten: String?,
    started: (String) -> Unit,
) {
    val c = Mu.colors
    val series = h.series
    LaunchedEffect(Unit) { series.load() }
    val field by produceState<Series.Field?>(null, a, b, kit) { value = b?.let { series.field(a, it, bDeckId, kit) } }
    var pairs by remember { mutableStateOf(PAIRS.first()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Help(
            "Both versions play the same best-of-three matches against each deck of the field — the same seeds, each deck going " +
                "first in game 1 once a pair, the loser choosing after, each side's plan put in before games 2 and 3 — by the " +
                "app's own search at Fast. The difference is read match by match.",
        )
        val f = field
        when {
            b == null -> Small("Choose the change, or the deck.", color = c.ink70)
            f == null -> Small("Reading the field…", color = c.ink45)
            else -> {
                Micro("The field${f.web?.let { " · $it" } ?: ""}", color = c.ink45)
                val total = f.opponents.sumOf { it.share }.takeIf { it > 0 } ?: 1.0
                f.opponents.forEach { o ->
                    val cov = f.coverage.firstOrNull { it.deck == o.deck.id }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Small(o.deck.name, Modifier.weight(1f), color = c.ink, maxLines = 1)
                        Mono("${SeriesWords.pct(o.share / total)} of the field", color = c.ink70)
                        cov?.let { Mono("plays ${SeriesWords.pct(it.share)}${if (it.share < SeriesCoverage.FLOOR) " · mostly blanks" else ""}", color = if (it.share < SeriesCoverage.FLOOR) c.ink else c.ink45) }
                    }
                }
                f.coverage.take(2).forEach { cov -> Small("${cov.name}: the engine plays ${SeriesWords.pct(cov.share)} of its Main Deck.", color = c.ink70) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Micro("Seed pairs", color = c.ink45)
                    Segmented(pairs, PAIRS, { it.toString() }, { pairs = it }, small = true)
                }
                val matches = f.opponents.size * pairs * 2 * 2
                Small("$matches matches, ${matches / 2} each version: about a minute a match on a desk, a few at a time. Stop keeps what is played.", color = c.ink70)
                val why = unwritten ?: series.refusal(f)
                if (why != null) {
                    Column(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(10.dp)) { Small(why, color = c.ink) }
                }
                MuButton(
                    "Play the matches",
                    { series.start(a, b, label, f, pairs, kit)?.let(started) },
                    variant = BtnVariant.PRIMARY,
                    enabled = why == null && series.running == null,
                    reason = why ?: "A series is playing",
                )
            }
        }
    }
}

/** A comparison by simulated matches as far as it has gone: the headline, each opponent, coverage, and Stop. */
@Composable
internal fun SeriesResultPanel(h: NeueHolders, id: String) {
    val c = Mu.colors
    val series = h.series
    val run = series.runs[id] ?: return
    val reading = remember(run) { SeriesReading.of(run) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Small(run.label.ifBlank { "${run.nameA} against ${run.nameB}" }, color = c.ink)
        Small(SeriesWords.headline(reading), color = c.ink)
        reading.opponents.forEach { o ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Small(o.name, Modifier.weight(1f), color = c.ink, maxLines = 1)
                Mono(
                    if (o.paired.hands == 0) "nothing paired yet"
                    else "A ${SeriesWords.pct(o.a)} · B ${SeriesWords.pct(o.b)} · ${SeriesWords.points(o.paired.difference)} (${SeriesWords.points(o.paired.interval.first)} to ${SeriesWords.points(o.paired.interval.second)}) · ${o.paired.hands}",
                    color = c.ink70,
                )
            }
        }
        Help(
            "${run.played} of ${run.total} matches, search ${run.version} at ${run.strength}. What the engine plays: " +
                run.coverage.joinToString(", ") { "${it.name} ${SeriesWords.pct(it.share)}" } +
                ". Unwritten cards are blanks and battle effects inert: the app's measurement under those limits.",
        )
        series.problem?.let { Small(it, color = c.ink) }
        if (series.running == id) MuButton("Stop", { series.stop() }, variant = BtnVariant.SECONDARY, size = BtnSize.SM)
    }
}

/** How many seed pairs a comparison plays: each pair is two matches a version an opponent. */
private val PAIRS = listOf(4, 8, 16)
