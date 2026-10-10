package com.kaiharimoto.neue.shootout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.shootout.bench.Behind
import com.kaiharimoto.mastertool.core.shootout.bench.CardResult
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutResults
import com.kaiharimoto.mastertool.core.shootout.bench.ShootoutWords
import com.kaiharimoto.mastertool.core.shootout.model.Estimate
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/*
 * Shootout's cards on one shared axis (Phase G, G.4; mockup A). Every card's worth in every situation is drawn against the
 * same scale, one 26 dp row a card, with the zero rule running unbroken down each column — so a +19.5 and a −8.7 are read
 * against each other at a glance, and a 40-card deck stands on one screen instead of four. The opening hand is a filled
 * square with its 80 % range thick and its 95 % range thin; going second, the card as the turn's draw is a hollow square
 * on the same row.
 */

/** The scale every plot on the page shares: [half] points either side of zero, ticks every [step]. */
internal class Axis(val half: Double) {
    val step: Double = when {
        half <= 15 -> 5.0
        half <= 30 -> 10.0
        else -> 20.0
    }
    val ticks: List<Double> = run {
        val start = -floor(half / step) * step
        generateSequence(start) { it + step }.takeWhile { it <= half + 1e-9 }.toList()
    }

    /** Where [v] stands across the plot, 0 to 1 (clipped at the ends). */
    fun x(v: Double): Float = ((v / half).coerceIn(-1.0, 1.0) * 0.5 + 0.5).toFloat()

    companion object {
        /**
         * The widest value or 80 % range end any card, draw or pair reaches, rounded up to five and at least ten: the thin
         * 95 % lines run off the edge sooner than the page shrinks every card to fit the noisiest one.
         */
        fun of(r: ShootoutResults): Axis = from(
            r.cards.flatMap { row -> (row.cells.values + row.drawn.values).filter { it.trials > 0 }.map { it.estimate } } +
                r.pairs.map { it.estimate },
        )

        /** The axis for [estimates] alike: card against card's numbers are drawn on one too. */
        fun from(estimates: List<Estimate>): Axis {
            var m = 10.0
            estimates.forEach { e -> m = max(m, max(abs(e.value), max(abs(e.range80.start), abs(e.range80.endInclusive)))) }
            return Axis(ceil(m / 5) * 5)
        }
    }
}

/** The plot's rules: a faint one at each tick, zero darker, the whole height of the cell — rows stack into one column. */
private fun DrawScope.rules(axis: Axis, faint: androidx.compose.ui.graphics.Color, zero: androidx.compose.ui.graphics.Color) {
    val w = size.width
    axis.ticks.forEach { t ->
        val x = axis.x(t) * w
        drawLine(if (t == 0.0) zero else faint, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
}

/**
 * One cell of the plot: the rules, [open] (the opening hand: a filled square, the 80 % range thick, the 95 % thin) and
 * [drawn] (as the turn's draw: a hollow square on its 95 % range) below it. A press on the upper half opens [onOpen]'s
 * hands, on the lower half [onDrawn]'s.
 */
@Composable
internal fun PlotCell(
    axis: Axis,
    open: Estimate?,
    drawn: Estimate?,
    modifier: Modifier,
    openCaption: String? = null,
    drawnCaption: String? = null,
    onOpen: (() -> Unit)? = null,
    onDrawn: (() -> Unit)? = null,
) {
    val c = Mu.colors
    Column(
        modifier.drawBehind {
            rules(axis, c.ink06, c.ink45)
            val w = size.width
            val d = 7.dp.toPx()
            open?.let { e ->
                val y = if (drawn != null) size.height * 0.36f else size.height / 2
                drawLine(c.ink25, Offset(axis.x(e.range95.start) * w, y), Offset(axis.x(e.range95.endInclusive) * w, y), 1.dp.toPx())
                drawLine(c.ink, Offset(axis.x(e.range80.start) * w, y), Offset(axis.x(e.range80.endInclusive) * w, y), 3.dp.toPx())
                drawRect(c.ink, Offset(axis.x(e.value) * w - d / 2, y - d / 2), Size(d, d))
            }
            drawn?.let { e ->
                val y = if (open != null) size.height * 0.74f else size.height / 2
                drawLine(c.ink45, Offset(axis.x(e.range95.start) * w, y), Offset(axis.x(e.range95.endInclusive) * w, y), 1.dp.toPx())
                val at = Offset(axis.x(e.value) * w - d / 2, y - d / 2)
                drawRect(c.paper, at, Size(d, d))
                drawRect(c.ink, at, Size(d, d), style = Stroke(1.5.dp.toPx()))
            }
        },
    ) {
        val top = if (onOpen != null) Modifier.cursorPointer(caption = openCaption ?: "Its hands", showsWords = true).muClickable(onClick = onOpen) else Modifier
        Box(Modifier.weight(1f).fillMaxWidth().then(top))
        if (drawn != null) {
            val bottom = if (onDrawn != null) Modifier.cursorPointer(caption = drawnCaption ?: "Its hands", showsWords = true).muClickable(onClick = onDrawn) else Modifier
            Box(Modifier.weight(1f).fillMaxWidth().then(bottom))
        }
    }
}

/** The axis's numbers under a column's head: each tick at its place, "−10", "0", "+10". */
@Composable
internal fun AxisTicks(axis: Axis, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Layout(
        content = { axis.ticks.forEach { t -> key(t) { Mono(ShootoutWords.points(t).removeSuffix(".0"), color = c.ink45, size = 10.sp) } } },
        modifier = modifier.fillMaxWidth().height(14.dp),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val w = constraints.maxWidth
        // A label that would touch the one before it is left out (four situations side by side are narrow); zero always stands.
        val gap = 6.dp.roundToPx()
        val xs = placeables.mapIndexed { i, p -> (axis.x(axis.ticks[i]) * w - p.width / 2f).toInt().coerceIn(0, (w - p.width).coerceAtLeast(0)) }
        val shown = BooleanArray(placeables.size)
        val zero = axis.ticks.indexOf(0.0)
        if (zero >= 0) shown[zero] = true
        fun clear(i: Int) = placeables.indices.none { j -> shown[j] && j != i && xs[i] < xs[j] + placeables[j].width + gap && xs[j] < xs[i] + placeables[i].width + gap }
        // Outward from zero, so the labels nearest it are the ones kept.
        placeables.indices.sortedBy { abs(it - zero) }.forEach { i -> if (!shown[i] && clear(i)) shown[i] = true }
        layout(w, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { i, p -> if (shown[i]) p.place(xs[i], 0) }
        }
    }
}

/** The widths of the plot's columns: the card's name, each situation's number, and one more copy's. */
internal class PlotColumns(val name: Dp?, val value: Dp = 52.dp, val next: Dp = 48.dp, val gap: Dp = 16.dp)

/**
 * Every card on the shared axis (mockup A): a head per situation with its ticks, then the cards by role, each sorted by its
 * worth in the first situation, one 26 dp row a card. A name is set heavier where the hands have called it; a card no hand
 * has shown reads "unrated".
 */
@Composable
internal fun CardsPlot(
    s: Shootouts,
    r: ShootoutResults,
    strata: List<Stratum>,
    axis: Axis,
    columns: PlotColumns,
    called: Set<Pair<Int, Stratum>>,
) {
    val c = Mu.colors
    val first = strata.firstOrNull()
    val roles = remember(r) { r.cards.map { it.role }.distinct() }
    val sorted = remember(r, first) {
        roles.flatMap { role ->
            r.cards.filter { it.role == role }.sortedWith(
                compareBy<CardResult> { row -> if ((row.cells[first]?.trials ?: 0) == 0) 1 else 0 }
                    .thenByDescending { row -> row.cells[first]?.estimate?.value ?: Double.NEGATIVE_INFINITY },
            )
        }
    }
    Column {
        // The heads: the situation over its plot, the ticks under it, and "1 more" over the next copy's column.
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            NameCell(columns) { Micro("Card", color = c.ink45) }
            strata.forEachIndexed { i, stratum ->
                key(stratum) {
                    if (i > 0) Spacer(Modifier.width(columns.gap))
                    Spacer(Modifier.width(columns.value + 8.dp))
                    Column(Modifier.weight(1f)) {
                        Micro(ShootoutWords.stratum(stratum), color = c.ink70)
                        AxisTicks(axis, Modifier.padding(top = 2.dp))
                    }
                    Box(Modifier.width(columns.next + 8.dp), contentAlignment = Alignment.BottomEnd) { Micro("1 more", color = c.ink45) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(c.ink) })
        var role: String? = null
        sorted.forEach { row ->
            if (row.role != role) {
                key("role", row.role) { RoleRow(row.role, strata, axis, columns) }
                role = row.role
            }
            key(row.card) { CardPlotRow(s, row, strata, axis, columns, called) }
        }
    }
}

@Composable
private fun NameCell(columns: PlotColumns, content: @Composable () -> Unit) {
    if (columns.name != null) Box(Modifier.width(columns.name), contentAlignment = Alignment.CenterStart) { content() }
}

/** A role's name over its cards; its plot cells carry the rules on, so the zero rule never breaks. */
@Composable
private fun RoleRow(role: String, strata: List<Stratum>, axis: Axis, columns: PlotColumns) {
    val c = Mu.colors
    if (columns.name == null) {
        Micro(role, Modifier.padding(top = 12.dp, bottom = 4.dp), color = c.ink70)
        return
    }
    Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(columns.name).padding(bottom = 6.dp)) { Micro(role, color = c.ink70) }
        strata.forEachIndexed { i, stratum ->
            key(stratum) {
                if (i > 0) Spacer(Modifier.width(columns.gap))
                Spacer(Modifier.width(columns.value + 8.dp))
                PlotCell(axis, null, null, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(columns.next + 8.dp))
            }
        }
    }
}

@Composable
private fun CardPlotRow(s: Shootouts, row: CardResult, strata: List<Stratum>, axis: Axis, columns: PlotColumns, called: Set<Pair<Int, Stratum>>) {
    val c = Mu.colors
    val name = s.card(row.card)?.name ?: "#${row.card}"
    val isCalled = strata.any { (row.card to it) in called }
    val nameText: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MuText(
                name, Modifier.weight(1f),
                MuType.row(LocalMuFonts.current).let { if (isCalled) it.copy(fontWeight = FontWeight.Medium) else it },
                if (isCalled) c.ink else c.ink70, maxLines = 1,
            )
            Mono("×${row.copies}", color = c.ink45)
        }
    }
    Column {
        if (columns.name == null) nameText(Modifier.fillMaxWidth().padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (columns.name != null) nameText(Modifier.width(columns.name).padding(end = 10.dp))
            strata.forEachIndexed { i, stratum ->
                key(stratum) {
                    if (i > 0) Spacer(Modifier.width(columns.gap))
                    val cell = row.cells[stratum]
                    val drawn = row.drawn[stratum]?.takeIf { it.trials > 0 }
                    val rated = cell != null && cell.trials > 0
                    Box(Modifier.width(columns.value), contentAlignment = Alignment.CenterEnd) {
                        when {
                            cell == null -> Unit
                            !rated -> Mono("unrated", color = c.ink45, size = 10.sp)
                            else -> Number(
                                ShootoutWords.points(cell.estimate.value), ShootoutWords.hands(cell.trials),
                                color = if ((row.card to stratum) in called) c.ink else c.ink70,
                            ) { s.behind = Behind.Card(row.card, stratum) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    PlotCell(
                        axis,
                        open = cell?.takeIf { rated }?.estimate,
                        drawn = if (rated) drawn?.estimate else null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        openCaption = cell?.let { "${ShootoutWords.points(it.estimate.value)} ±${ShootoutWords.points(it.estimate.halfWidth95).removePrefix("+")} · ${ShootoutWords.hands(it.trials)}" },
                        drawnCaption = drawn?.let { "As your draw ${ShootoutWords.points(it.estimate.value)} · ${ShootoutWords.hands(it.trials)}" },
                        onOpen = if (rated) ({ s.behind = Behind.Card(row.card, stratum) }) else null,
                        onDrawn = drawn?.let { { s.behind = Behind.Drawn(row.card, stratum) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(columns.next), contentAlignment = Alignment.CenterEnd) {
                        val next = row.next[stratum]
                        if (rated && next != null) Mono(ShootoutWords.points(next.value), color = c.ink45, align = TextAlign.End)
                    }
                }
            }
        }
    }
}
