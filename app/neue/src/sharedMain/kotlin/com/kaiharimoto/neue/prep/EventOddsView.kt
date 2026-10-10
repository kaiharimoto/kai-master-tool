package com.kaiharimoto.neue.prep

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.prep.EventOdds
import com.kaiharimoto.mastertool.core.prep.PracticePlan
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.mastertool.core.prep.TestStats
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Your odds at the event (Phase G, G.5; the red team's M1, M3, M4; mockup B): the expected match win with its range and the
 * games behind it, the chance of making the cut at that rate, the games to practise next, and each matchup's rates as dots
 * with their ranges. One reading ([EventOdds]) feeds the plan, the practice tab and Format, so a number is the same wherever
 * it is shown.
 */

/** The event's reading, made off the frame thread whenever the games, the field or the event change; null until then. */
@Composable
internal fun rememberEventOdds(prep: Prep, event: PrepEvent, web: DeckWeb?, mine: StoredDeck?): State<EventOdds.Reading?> =
    produceState<EventOdds.Reading?>(null, prep.doc.games, web?.entries, event, mine?.entry?.id) {
        val games = prep.doc.games
        val entries = web?.entries.orEmpty()
        value = withContext(Dispatchers.Default) { EventOdds.read(games, entries, event, mine?.entry?.id, mine?.entry?.name) }
    }

/** "Your odds at this event", beside the countdown: the match win, the cut, and the next games. */
@Composable
internal fun OddsBox(r: EventOdds.Reading?, event: PrepEvent) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Micro("Your odds at this event", color = c.ink70)
        val i = r?.interval
        if (r == null || i == null) {
            Small("Give the field's decks their shares on Format, and the match win to expect against it stands here with its range.", color = c.ink45)
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mono(EventOdds.pct(i.point), color = c.ink, size = 28.sp)
                Small("${EventOdds.pct(i.low)}–${EventOdds.pct(i.high)} · ${r.games} ${if (r.games == 1) "game" else "games"}", color = c.ink70)
            }
            RangeLine(i.low, i.point, i.high, Modifier.fillMaxWidth().height(10.dp))
            val cut = r.cut
            when {
                cut != null -> Small("Top ${r.swiss?.topCut}: ${EventOdds.pct(cut)} at this rate. ${r.cutRecord}.", color = c.ink)
                event.attendance == 0 -> Small("Give the expected players for the chance of making the cut.", color = c.ink45)
            }
            r.next?.takeIf { it.gain >= 0.5 }?.let { Small("Practise next: ${PracticePlan.words(it)}.", color = c.ink) }
            r.other?.let { Help("${it.share}% of the room is decks the web does not hold, at ${EventOdds.pct(it.matchWin)}.") }
            if (event.countTime) Help("A match too long for the round counts as the loss it is.")
            Help("Rounds are read as independent at one rate, which pairing by record is not quite: a planning number.")
        }
    }
}

/** A rate's range on 0–100 % in ink: a faint rule at 50, the range a line, the point a square. */
@Composable
internal fun RangeLine(low: Double, point: Double, high: Double, modifier: Modifier, faint: Boolean = false) {
    val c = Mu.colors
    Box(
        modifier.drawBehind {
            val w = size.width
            val mid = size.height / 2
            fun x(v: Double) = v.coerceIn(0.0, 1.0).toFloat() * w
            drawLine(c.ink12, Offset(0f, mid), Offset(w, mid), 1.dp.toPx())
            drawLine(c.ink25, Offset(x(0.5), 0f), Offset(x(0.5), size.height), 1.dp.toPx())
            val ink = if (faint) c.ink25 else c.ink
            drawLine(ink, Offset(x(low), mid), Offset(x(high), mid), 2.dp.toPx())
            val d = 6.dp.toPx()
            drawRect(ink, Offset(x(point) - d / 2, mid - d / 2), Size(d, d))
        },
    )
}

/** A rate as a dot with its Wilson range; hollow and faint under [FEW] games, a dash with none. */
@Composable
private fun RateDot(r: TestStats.Rate, modifier: Modifier) {
    val c = Mu.colors
    val few = r.games < FEW
    Box(
        modifier.drawBehind {
            val w = size.width
            val mid = size.height / 2
            fun x(v: Double) = v.coerceIn(0.0, 1.0).toFloat() * w
            drawLine(c.ink25, Offset(x(0.5), 0f), Offset(x(0.5), size.height), 1.dp.toPx())
            if (r.games == 0) return@drawBehind
            val (lo, hi) = r.wilson()
            val ink = if (few) c.ink25 else c.ink
            drawLine(ink, Offset(x(lo), mid), Offset(x(hi), mid), 1.5.dp.toPx())
            val d = 6.dp.toPx()
            val at = Offset(x(r.pct) - d / 2, mid - d / 2)
            if (few) {
                drawRect(c.paper, at, Size(d, d))
                drawRect(c.ink45, at, Size(d, d), style = Stroke(1.dp.toPx()))
            } else {
                drawRect(ink, at, Size(d, d))
            }
        },
    )
}

/** Fewer games than this, and a rate is drawn faint: it says little yet. */
private const val FEW = 5

/**
 * Each matchup's rates by turn as dots with their 95 % ranges (the red team: Prep drew flat bars), the games under each, the
 * cells under five games faint, and the roll's call beside them.
 */
@Composable
internal fun RateDots(rows: List<TestStats.Row>, calls: Map<String, TestStats.TurnCall>, phone: Boolean) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("Against", Modifier.weight(1f), color = c.ink45)
            Micro(if (phone) "First" else "Going first", Modifier.width(if (phone) 96.dp else 160.dp), color = c.ink45)
            Micro(if (phone) "Second" else "Going second", Modifier.width(if (phone) 96.dp else 160.dp), color = c.ink45)
            if (!phone) Micro("Win the roll", Modifier.width(200.dp), color = c.ink45)
        }
        rows.forEach { r ->
            key(r.opponent) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RowText(r.name, Modifier.weight(1f), color = c.ink)
                        Cell(r.first, Modifier.width(if (phone) 96.dp else 160.dp))
                        Cell(r.second, Modifier.width(if (phone) 96.dp else 160.dp))
                        if (!phone) Small(calls[r.opponent]?.let(EventOdds::callWords) ?: "", Modifier.width(200.dp), color = c.ink70, maxLines = 2)
                    }
                    if (phone) calls[r.opponent]?.let { Help("Win the roll: ${EventOdds.callWords(it)}") }
                }
            }
        }
        Help("Each dot is the rate with its 95% range; faint under $FEW games. Only Game 1's turn is yours to choose: after it the loser chooses.")
    }
}

@Composable
private fun Cell(r: TestStats.Rate, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RateDot(r, Modifier.fillMaxWidth().height(10.dp))
        Mono(if (r.games == 0) "--" else "${EventOdds.pct(r.pct)} · ${r.games}", color = if (r.games < FEW) c.ink45 else c.ink70)
    }
}
