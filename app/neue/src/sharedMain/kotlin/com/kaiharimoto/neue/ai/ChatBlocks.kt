package com.kaiharimoto.neue.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.text.Align
import com.kaiharimoto.mastertool.core.ai.text.Block
import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.ai.text.Inline
import com.kaiharimoto.mastertool.core.ai.text.TableFit
import com.kaiharimoto.mastertool.core.ai.text.TableLayout
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * The blocks a reply draws beyond words (1.0.46, kai: "I want the AI to draw tables and
 * charts"): a table laid out to the panel's width, a chart in ink (the family has no colour to spare — shades and hatching tell the
 * series apart), and a strip of card art for a list of cards.
 */

/**
 * A table, laid out to the room there is (1.0.65, `TableFit`): each column's width on one line and
 * its longest word are measured in the chat's own type; side by side when they fit, the wide columns
 * wrapped when that is enough, and a row at a time when even the longest words will not sit side by
 * side. Never scrolled out of sight. Numbers are set right.
 */
@Composable
internal fun TableBlock(block: Block.Table, styled: @Composable (List<Inline>) -> androidx.compose.ui.text.AnnotatedString) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val all = listOf(block.header) + block.rows
    val columns = all.maxOf { it.size }
    val plain = all.map { row -> row.map { cell -> cell.joinToString("") { text(it) } } }
    val numeric = (0 until columns).map { col ->
        block.rows.isNotEmpty() && plain.drop(1).all { r -> r.getOrNull(col)?.trim().orEmpty().let { it.isEmpty() || NUMBER.matches(it) } }
    }
    val headStyle = MuType.mono(f, 11.sp).copy(fontWeight = FontWeight.Medium)
    val bodyStyle = MuType.small(f)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // One cell's padding each side; a little over for card names set in medium weight.
    val pad = with(density) { 16.dp.toPx() }
    val (natural, minimum) = remember(plain, f) {
        fun width(t: String, style: TextStyle) = if (t.isBlank()) 0f else measurer.measure(t, style, maxLines = 1, softWrap = false).size.width * 1.04f
        val nat = (0 until columns).map { col -> plain.withIndex().maxOf { (r, row) -> width(row.getOrNull(col).orEmpty(), if (r == 0) headStyle else bodyStyle) } + pad }
        val min = (0 until columns).map { col ->
            plain.withIndex().maxOf { (r, row) -> row.getOrNull(col).orEmpty().split(' ').maxOfOrNull { w -> width(w, if (r == 0) headStyle else bodyStyle) } ?: 0f } + pad
        }
        nat to min
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val full = maxWidth
        val room = with(density) { full.toPx() } - with(density) { 2.dp.toPx() }
        when (val fit = TableFit.fit(natural, minimum, room)) {
            is TableLayout.Widths -> Column(Modifier.border(1.dp, c.ink12)) {
                all.forEachIndexed { r, cells ->
                    Row(
                        Modifier
                            .let { if (r == 0) it.background(c.ink06) else it }
                            .drawBehind { if (r > 0) drawLine(c.ink12, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                            .padding(vertical = 6.dp),
                    ) {
                        (0 until columns).forEach { col ->
                            val align = when (block.align.getOrNull(col)) {
                                Align.RIGHT -> TextAlign.End
                                Align.CENTER -> TextAlign.Center
                                else -> if (numeric[col] && r > 0) TextAlign.End else TextAlign.Start
                            }
                            MuText(
                                styled(cells.getOrNull(col).orEmpty()),
                                Modifier.width(with(density) { fit.widths[col].toDp() }).padding(horizontal = 8.dp),
                                style = (if (r == 0) headStyle else bodyStyle).copy(textAlign = align),
                                color = if (r == 0) c.ink70 else c.ink,
                            )
                        }
                    }
                }
            }
            TableLayout.Stacked -> Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
                // A row at a time: its first cell the title, each other one under its header.
                val label = minOf(full * 0.38f, 132.dp)
                block.rows.forEachIndexed { r, cells ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .drawBehind { if (r > 0) drawLine(c.ink12, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        MuText(styled(cells.getOrNull(0).orEmpty()), style = bodyStyle.copy(fontWeight = FontWeight.Medium), color = c.ink)
                        (1 until columns).forEach { col ->
                            val cell = cells.getOrNull(col).orEmpty()
                            if (cell.isEmpty()) return@forEach
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MuText(styled(block.header.getOrNull(col).orEmpty()), Modifier.width(label), style = headStyle, color = c.ink45)
                                MuText(styled(cell), Modifier.weight(1f), style = bodyStyle, color = c.ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val NUMBER = Regex("^[−+-]?[\\d.,]+\\s*%?$|^[−+-]?[\\d.,]+\\s*(x|×|cards?|games?)$")

private fun text(i: Inline): String = when (i) {
    is Inline.Text -> i.text
    is Inline.Bold -> i.text
    is Inline.Italic -> i.text
    is Inline.Code -> i.text
    is Inline.Card -> i.name
}

/**
 * A chart, drawn on the page's own paper: bars and lines in the ink ramp — the first
 * series solid ink, the next ones lighter and outlined, a fourth hatched — values and
 * labels in mono. No colour: charts are content Ai writes, not the two places the family
 * lets colour in (the foil, the group markers).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartBlock(chart: ChatChart.Chart) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val measurer = rememberTextMeasurer()
    val mono = MuType.mono(f, 10.sp)
    val fills = listOf(c.ink, c.ink45, c.ink12, Color.Transparent)
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chart.title.isNotBlank()) MuText(chart.title, style = MuType.small(f).copy(fontWeight = FontWeight.Medium), color = c.ink)
        val height: Dp = when (chart.type) {
            ChatChart.Type.HBAR -> (chart.labels.size * (14 + 8 * chart.series.size) + 8).coerceAtLeast(60).dp
            else -> 176.dp
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val labelStyle = mono.copy(color = c.ink70)
            when (chart.type) {
                ChatChart.Type.HBAR -> horizontal(chart, fills, c.ink, c.ink12, labelStyle, measurer)
                else -> vertical(chart, fills, c.ink, c.ink12, labelStyle, measurer)
            }
        }
        if (chart.series.size > 1 || chart.series.first().name.isNotBlank()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                chart.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).background(fills[i % fills.size]).border(1.dp, c.ink).drawBehind { if (i % fills.size == 3) hatch(Offset.Zero, size, c.ink) })
                        Small(s.name.ifBlank { "Series ${i + 1}" }, color = c.ink70)
                    }
                }
            }
        }
    }
}

private fun DrawScope.hatch(at: Offset, size: Size, ink: Color) {
    val step = 5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        val x0 = (at.x + x).coerceAtLeast(at.x)
        val y0 = at.y + size.height - (x0 - (at.x + x))
        val x1 = (at.x + x + size.height).coerceAtMost(at.x + size.width)
        val y1 = at.y + size.height - (x1 - (at.x + x))
        drawLine(ink, Offset(x0, y0), Offset(x1, y1), 1.dp.toPx())
        x += step
    }
}

private fun DrawScope.bar(at: Offset, size: Size, fill: Color, ink: Color, index: Int) {
    if (size.width <= 0f || size.height <= 0f) return
    if (fill != Color.Transparent) drawRect(fill, at, size)
    if (index % 4 == 3) hatch(at, size, ink)
    if (index > 0) drawRect(ink, at, size, style = Stroke(1.dp.toPx()))
}

private fun DrawScope.vertical(
    chart: ChatChart.Chart,
    fills: List<Color>,
    ink: Color,
    grid: Color,
    label: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val ticks = ChatChart.ticks(chart.max)
    val top = ticks.last().takeIf { it > 0 } ?: 1.0
    val axisWidth = ticks.maxOf { measurer.measure(ChatChart.label(it, chart.unit), label).size.width } + 6.dp.toPx()
    val bottomRoom = 16.dp.toPx()
    val plotH = size.height - bottomRoom - 6.dp.toPx()
    val plotW = size.width - axisWidth
    fun y(v: Double) = 6.dp.toPx() + (plotH * (1 - v / top)).toFloat()
    ticks.forEach { t ->
        val yy = y(t)
        drawLine(grid, Offset(axisWidth, yy), Offset(size.width, yy), 1.dp.toPx())
        val tl = measurer.measure(ChatChart.label(t, chart.unit), label)
        drawText(tl, topLeft = Offset(axisWidth - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2f))
    }
    val n = chart.labels.size
    val slot = plotW / n
    chart.labels.forEachIndexed { i, name ->
        val tl = measurer.measure(name, label, maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = slot.toInt().coerceAtLeast(1)))
        drawText(tl, topLeft = Offset(axisWidth + slot * i + (slot - tl.size.width) / 2f, size.height - tl.size.height))
    }
    when (chart.type) {
        ChatChart.Type.LINE -> chart.series.forEachIndexed { s, series ->
            val path = Path()
            series.values.forEachIndexed { i, v ->
                val p = Offset(axisWidth + slot * i + slot / 2f, y(v))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val dash = when (s) {
                0 -> null
                1 -> PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
                2 -> PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
                else -> PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 3.dp.toPx(), 2.dp.toPx(), 3.dp.toPx()))
            }
            drawPath(path, ink, style = Stroke(2.dp.toPx(), pathEffect = dash))
            series.values.forEachIndexed { i, v ->
                val p = Offset(axisWidth + slot * i + slot / 2f, y(v))
                val d = 5.dp.toPx()
                drawRect(if (s == 0) ink else fills[1], Offset(p.x - d / 2, p.y - d / 2), Size(d, d))
            }
        }
        ChatChart.Type.STACKED -> chart.labels.indices.forEach { i ->
            var base = 0.0
            val w = slot * 0.6f
            chart.series.forEachIndexed { s, series ->
                val v = series.values[i].coerceAtLeast(0.0)
                val y0 = y(base + v)
                bar(Offset(axisWidth + slot * i + (slot - w) / 2f, y0), Size(w, y(base) - y0), fills[s % fills.size], ink, s)
                base += v
            }
        }
        else -> {
            val groupW = slot * 0.72f
            val w = groupW / chart.series.size
            chart.series.forEachIndexed { s, series ->
                series.values.forEachIndexed { i, v ->
                    val x = axisWidth + slot * i + (slot - groupW) / 2f + w * s
                    val y0 = y(maxOf(v, 0.0))
                    val y1 = y(minOf(v, 0.0).coerceAtLeast(0.0))
                    bar(Offset(x + 1f, y0), Size(w - 2f, y1 - y0), fills[s % fills.size], ink, s)
                    if (chart.series.size == 1 && n <= 12) {
                        val tl = measurer.measure(ChatChart.label(v, chart.unit), label)
                        drawText(tl, topLeft = Offset(x + (w - tl.size.width) / 2f, y0 - tl.size.height - 2.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun DrawScope.horizontal(
    chart: ChatChart.Chart,
    fills: List<Color>,
    ink: Color,
    grid: Color,
    label: TextStyle,
    measurer: androidx.compose.ui.text.TextMeasurer,
) {
    val top = chart.max.takeIf { it > 0 } ?: 1.0
    val labelW = (chart.labels.maxOf { measurer.measure(it, label, maxLines = 1).size.width } + 8.dp.toPx())
        .coerceAtMost(size.width * 0.4f)
    val valueRoom = 44.dp.toPx()
    val plotW = size.width - labelW - valueRoom
    val rowH = size.height / chart.labels.size
    chart.labels.forEachIndexed { i, name ->
        val y = rowH * i
        val tl = measurer.measure(name, label, maxLines = 1, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (labelW - 8.dp.toPx()).toInt().coerceAtLeast(1)))
        drawText(tl, topLeft = Offset(0f, y + (rowH - tl.size.height) / 2f))
        val barH = (rowH - 6.dp.toPx()) / chart.series.size
        chart.series.forEachIndexed { s, series ->
            val v = series.values[i]
            val w = (plotW * (v.coerceAtLeast(0.0) / top)).toFloat()
            val by = y + 3.dp.toPx() + barH * s
            bar(Offset(labelW, by), Size(w, barH - 1f), fills[s % fills.size], ink, s)
            val vl = measurer.measure(ChatChart.label(v, chart.unit), label)
            drawText(vl, topLeft = Offset(labelW + w + 4.dp.toPx(), by + (barH - vl.size.height) / 2f))
        }
    }
    drawLine(grid, Offset(labelW, 0f), Offset(labelW, size.height), 1.dp.toPx())
}

/** A chart or strip still being written: a quiet working line, not raw JSON. */
@Composable
internal fun PendingBlock(block: Block.Pending) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Breathe(running = true)
        Mono(block.what, color = Mu.colors.ink45)
    }
}
