package com.kaiharimoto.neue.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.book.EngineLayout
import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.GraphLayout
import com.kaiharimoto.mastertool.core.world.WorldChart
import com.kaiharimoto.mastertool.core.world.WorldGraph
import com.kaiharimoto.mastertool.core.world.WorldStat
import com.kaiharimoto.mastertool.core.world.WorldTable
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.ChatCard
import com.kaiharimoto.neue.ai.MarkdownBlock
import com.kaiharimoto.neue.ai.cardNamed
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * What Ai pins to a world's boards, drawn (1.0.95). This file is allowed colour, on kai's word — "ink, with colour as
 * content": a chart's series, a web's groups and a heatmap's shades read far better in colour, and they are what Ai
 * wants to show, not chrome. Everything else here keeps the family's laws — square, flat, no shadows — and the frame
 * round a board, its title and its note are the page's ink (`WorldPanes.kt`).
 *
 * Each painter reads its payload back through the type `ShowSpec` checked it with, so what was checked is what is
 * drawn; a payload that no longer reads says why in words instead of drawing nothing.
 */

/** The series and groups, in order: mid-tones that read on paper and on ink alike. */
private val PALETTE = listOf(
    Color(0xFF2F6FDB),
    Color(0xFFE07A2F),
    Color(0xFF1C9C86),
    Color(0xFFC8457E),
    Color(0xFF8E9A1C),
    Color(0xFF7B57D4),
    Color(0xFF3E9BC7),
    Color(0xFFB8562E),
)

private fun series(i: Int): Color = PALETTE[((i % PALETTE.size) + PALETTE.size) % PALETTE.size]

/** One hue, light to deep on paper and deep to light on ink: the larger the number, the further from the page. */
private fun shade(t: Float, ink: Boolean): Color {
    val k = t.coerceIn(0f, 1f)
    return if (ink) lerp(Color(0xFF13233D), Color(0xFF9CC2FF), k) else lerp(Color(0xFFEAF1FC), Color(0xFF1A4CA3), k)
}

/** Words that read on [fill]: black on a light one, white on a deep one. */
private fun inkOn(fill: Color): Color = if (fill.luminance() > 0.42f) Color.Black else Color.White

/**
 * A board's kind as the painters read it: null is a kind this build does not know — a board a newer version made,
 * shown in words rather than drawn. (At the merge with the stored-word model this is `board.type`.)
 */
internal val Board.typed: BoardKind? get() = kind

/** A board's body, by its kind. */
@Composable
internal fun BoardBody(h: NeueHolders, board: Board, worldId: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        when (board.typed) {
            null -> Box(Modifier.fillMaxSize().padding(12.dp)) {
                Small("Made by a newer version of the app: update to see this board.", color = Mu.colors.ink70)
            }
            BoardKind.CHART -> WorldChart.parse(board.payload).fold(
                { chart ->
                    when (chart) {
                        is WorldChart.Bars -> BarsPaint(chart.chart)
                        is WorldChart.Scatter -> ScatterPaint(chart)
                        is WorldChart.Heatmap -> HeatmapPaint(chart)
                    }
                },
                { Unreadable(it) },
            )
            BoardKind.GRAPH -> WorldGraph.parse(board.payload).fold({ GraphPaint(h, it) }, { Unreadable(it) })
            BoardKind.FLOW -> WorldGraph.parse(board.payload).fold({ FlowPaint(h, it) }, { Unreadable(it) })
            BoardKind.TABLE -> WorldTable.parse(board.payload).fold({ TablePaint(it) }, { Unreadable(it) })
            BoardKind.STAT -> WorldStat.parse(board.payload).fold({ StatPaint(it) }, { Unreadable(it) })
            BoardKind.MARKDOWN -> MarkdownPaint(h, board.payload)
            BoardKind.CARDS -> MarkdownPaint(h, "```cards\n${board.payload}\n```")
            BoardKind.BOARD -> MarkdownPaint(h, "```board\n${board.payload}\n```")
            BoardKind.LINE -> MarkdownPaint(h, "```line\n${board.payload}\n```")
            BoardKind.IMAGE -> ImagePaint(File(h.world.dir, "$worldId/files/${board.payload}"))
        }
    }
}

/** What a payload that no longer reads says instead of a picture. */
@Composable
private fun Unreadable(e: Throwable) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro("Could not draw this board", color = Mu.colors.ink)
        Small(e.message ?: e.toString(), color = Mu.colors.ink70)
    }
}

// ---- Words and pictures the chat already draws -------------------------------------------------------------------

@Composable
private fun MarkdownPaint(h: NeueHolders, text: String) {
    val blocks = remember(text) { ChatMarkdown.parse(text) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { MarkdownBlock(h.ai, it) }
    }
}

@Composable
private fun ImagePaint(file: File) {
    var bitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    var missing by remember(file.path) { mutableStateOf(false) }
    LaunchedEffect(file.path, file.lastModified()) {
        val read = withContext(Dispatchers.IO) { if (file.isFile) runCatching { decodePicture(file.readBytes()) }.getOrNull() else null }
        bitmap = read
        missing = read == null
    }
    val b = bitmap
    when {
        b != null -> Image(b, contentDescription = file.name, modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
        missing -> Box(Modifier.fillMaxSize().padding(12.dp)) { Small("No picture at ${file.name}: the run that saved it may have failed.", color = Mu.colors.ink70) }
    }
}

// ---- One number ---------------------------------------------------------------------------------------------------

@Composable
private fun StatPaint(stat: WorldStat) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // The display size where the board has room, a step down where it does not.
            val style = if (maxWidth > 260.dp && stat.value.length <= 8) MuType.displayXl(f) else MuType.display(f)
            MuText(stat.value, style = style, color = c.ink, maxLines = 1)
        }
        if (stat.label.isNotBlank()) MuText(stat.label, style = MuType.h2(f), color = c.ink, maxLines = 2)
        if (stat.detail.isNotBlank()) Small(stat.detail, color = c.ink70, maxLines = 4)
    }
}

// ---- Bars, lines and stacks -------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarsPaint(chart: ChatChart.Chart) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val measurer = rememberTextMeasurer()
    val label = MuType.mono(f, 10.sp).copy(color = c.ink70)
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            if (chart.labels.isEmpty() || chart.series.isEmpty()) return@Canvas
            when (chart.type) {
                ChatChart.Type.HBAR -> horizontalBars(chart, c.ink12, label, measurer)
                else -> verticalBars(chart, c.ink12, c.paper, label, measurer)
            }
        }
        if (chart.series.size > 1 || chart.series.firstOrNull()?.name?.isNotBlank() == true) Legend(chart.series.map { it.name })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).background(series(i)))
                Small(name.ifBlank { "Series ${i + 1}" }, color = Mu.colors.ink70, maxLines = 1)
            }
        }
    }
}

private fun DrawScope.verticalBars(chart: ChatChart.Chart, grid: Color, paper: Color, label: TextStyle, measurer: TextMeasurer) {
    val ticks = ChatChart.ticks(chart.max)
    val top = ticks.last().takeIf { it > 0 } ?: 1.0
    val axisWidth = ticks.maxOf { measurer.measure(ChatChart.label(it, chart.unit), label).size.width } + 6.dp.toPx()
    val bottomRoom = 16.dp.toPx()
    val headRoom = 14.dp.toPx()
    val plotH = (size.height - bottomRoom - headRoom).coerceAtLeast(1f)
    val plotW = size.width - axisWidth
    fun y(v: Double) = headRoom + (plotH * (1 - v / top)).toFloat()
    ticks.forEach { t ->
        val yy = y(t)
        drawLine(grid, Offset(axisWidth, yy), Offset(size.width, yy), 1.dp.toPx())
        val tl = measurer.measure(ChatChart.label(t, chart.unit), label)
        drawText(tl, topLeft = Offset(axisWidth - tl.size.width - 6.dp.toPx(), yy - tl.size.height / 2f))
    }
    val n = chart.labels.size
    val slot = plotW / n
    chart.labels.forEachIndexed { i, name ->
        val tl = measurer.measure(name, label, maxLines = 1, constraints = Constraints(maxWidth = slot.toInt().coerceAtLeast(1)))
        drawText(tl, topLeft = Offset(axisWidth + slot * i + (slot - tl.size.width) / 2f, size.height - tl.size.height))
    }
    when (chart.type) {
        ChatChart.Type.LINE -> chart.series.forEachIndexed { s, line ->
            val path = Path()
            line.values.forEachIndexed { i, v ->
                val p = Offset(axisWidth + slot * i + slot / 2f, y(v))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, series(s), style = Stroke(2.dp.toPx()))
            line.values.forEachIndexed { i, v ->
                val p = Offset(axisWidth + slot * i + slot / 2f, y(v))
                val d = 6.dp.toPx()
                drawRect(paper, Offset(p.x - d / 2, p.y - d / 2), Size(d, d))
                drawRect(series(s), Offset(p.x - d / 2, p.y - d / 2), Size(d, d), style = Stroke(2.dp.toPx()))
            }
        }
        ChatChart.Type.STACKED -> chart.labels.indices.forEach { i ->
            var base = 0.0
            val w = slot * 0.6f
            chart.series.forEachIndexed { s, line ->
                val v = line.values.getOrElse(i) { 0.0 }.coerceAtLeast(0.0)
                val y0 = y(base + v)
                drawRect(series(s), Offset(axisWidth + slot * i + (slot - w) / 2f, y0), Size(w, y(base) - y0))
                base += v
            }
        }
        else -> {
            val groupW = slot * 0.72f
            val w = groupW / chart.series.size
            chart.series.forEachIndexed { s, line ->
                line.values.forEachIndexed { i, v ->
                    val x = axisWidth + slot * i + (slot - groupW) / 2f + w * s
                    val y0 = y(maxOf(v, 0.0))
                    val y1 = y(minOf(v, 0.0).coerceAtLeast(0.0))
                    if (y1 - y0 > 0f) drawRect(series(s), Offset(x + 1f, y0), Size((w - 2f).coerceAtLeast(1f), y1 - y0))
                    if (chart.series.size == 1 && n <= 16) {
                        val tl = measurer.measure(ChatChart.label(v, chart.unit), label)
                        drawText(tl, topLeft = Offset(x + (w - tl.size.width) / 2f, y0 - tl.size.height - 2.dp.toPx()))
                    }
                }
            }
        }
    }
}

private fun DrawScope.horizontalBars(chart: ChatChart.Chart, grid: Color, label: TextStyle, measurer: TextMeasurer) {
    val top = chart.max.takeIf { it > 0 } ?: 1.0
    val labelW = (chart.labels.maxOf { measurer.measure(it, label, maxLines = 1).size.width } + 8.dp.toPx()).coerceAtMost(size.width * 0.4f)
    val valueRoom = 44.dp.toPx()
    val plotW = size.width - labelW - valueRoom
    val rowH = size.height / chart.labels.size
    chart.labels.forEachIndexed { i, name ->
        val y = rowH * i
        val tl = measurer.measure(name, label, maxLines = 1, constraints = Constraints(maxWidth = (labelW - 8.dp.toPx()).toInt().coerceAtLeast(1)))
        drawText(tl, topLeft = Offset(0f, y + (rowH - tl.size.height) / 2f))
        val barH = ((rowH - 6.dp.toPx()) / chart.series.size).coerceAtMost(28.dp.toPx())
        val block = barH * chart.series.size
        chart.series.forEachIndexed { s, line ->
            val v = line.values.getOrElse(i) { 0.0 }
            val w = (plotW * (v.coerceAtLeast(0.0) / top)).toFloat()
            val by = y + (rowH - block) / 2f + barH * s
            drawRect(series(s), Offset(labelW, by), Size(w, (barH - 1f).coerceAtLeast(1f)))
            val vl = measurer.measure(ChatChart.label(v, chart.unit), label)
            drawText(vl, topLeft = Offset(labelW + w + 4.dp.toPx(), by + (barH - vl.size.height) / 2f))
        }
    }
    drawLine(grid, Offset(labelW, 0f), Offset(labelW, size.height), 1.dp.toPx())
}

// ---- Points -----------------------------------------------------------------------------------------------------

/** Round steps for an axis from [lo] to [hi]: 1, 2 or 5 times a power of ten, about [count] of them. */
private fun axis(lo: Double, hi: Double, count: Int = 5): List<Double> {
    val a = if (lo == hi) lo - 1 else lo
    val b = if (lo == hi) hi + 1 else hi
    val raw = (b - a) / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val first = floor(a / step) * step
    val last = ceil(b / step) * step
    val n = ((last - first) / step).toInt().coerceIn(1, 20)
    return (0..n).map { first + it * step }
}

@Composable
private fun ScatterPaint(chart: WorldChart.Scatter) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val measurer = rememberTextMeasurer()
    val label = MuType.mono(f, 10.sp).copy(color = c.ink70)
    val xs = remember(chart) { chart.series.flatMap { s -> s.points.map { it.x } } }
    val ys = remember(chart) { chart.series.flatMap { s -> s.points.map { it.y } } }
    val xTicks = remember(chart) { axis(xs.min(), xs.max()) }
    val yTicks = remember(chart) { axis(ys.min(), ys.max()) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val axisW = yTicks.maxOf { measurer.measure(ChatChart.label(it, ""), label).size.width } + 8.dp.toPx()
            val bottom = 30.dp.toPx()
            val plotW = size.width - axisW - 8.dp.toPx()
            val plotH = size.height - bottom - 6.dp.toPx()
            val x0 = xTicks.first()
            val x1 = xTicks.last()
            val y0 = yTicks.first()
            val y1 = yTicks.last()
            fun px(v: Double) = axisW + ((v - x0) / (x1 - x0) * plotW).toFloat()
            fun py(v: Double) = 6.dp.toPx() + ((1 - (v - y0) / (y1 - y0)) * plotH).toFloat()
            yTicks.forEach { t ->
                drawLine(c.ink12, Offset(axisW, py(t)), Offset(axisW + plotW, py(t)), 1.dp.toPx())
                val tl = measurer.measure(ChatChart.label(t, ""), label)
                drawText(tl, topLeft = Offset(axisW - tl.size.width - 6.dp.toPx(), py(t) - tl.size.height / 2f))
            }
            xTicks.forEach { t ->
                drawLine(c.ink12, Offset(px(t), 6.dp.toPx()), Offset(px(t), 6.dp.toPx() + plotH), 1.dp.toPx())
                val tl = measurer.measure(ChatChart.label(t, ""), label)
                drawText(tl, topLeft = Offset(px(t) - tl.size.width / 2f, 8.dp.toPx() + plotH))
            }
            if (chart.x.isNotBlank()) {
                val tl = measurer.measure(chart.x, label, maxLines = 1)
                drawText(tl, topLeft = Offset(axisW + plotW - tl.size.width, size.height - tl.size.height))
            }
            if (chart.y.isNotBlank()) {
                val tl = measurer.measure(chart.y, label, maxLines = 1)
                drawRect(c.paper, Offset(axisW + 4.dp.toPx(), 6.dp.toPx()), Size(tl.size.width.toFloat() + 4.dp.toPx(), tl.size.height.toFloat()))
                drawText(tl, topLeft = Offset(axisW + 6.dp.toPx(), 6.dp.toPx()))
            }
            val d = if (xs.size > 400) 3.dp.toPx() else 6.dp.toPx()
            chart.series.forEachIndexed { s, cloud ->
                val col = series(s).copy(alpha = if (xs.size > 400) 0.6f else 0.9f)
                cloud.points.forEach { p -> drawRect(col, Offset(px(p.x) - d / 2, py(p.y) - d / 2), Size(d, d)) }
            }
            // A few named points say who they are, where they are few enough to read.
            if (xs.size <= 60) {
                chart.series.forEach { cloud ->
                    cloud.points.filter { it.label.isNotBlank() }.forEach { p ->
                        val tl = measurer.measure(p.label, label, maxLines = 1, constraints = Constraints(maxWidth = 160.dp.roundToPx()))
                        drawText(tl, topLeft = Offset(px(p.x) + d, py(p.y) - tl.size.height - 1f))
                    }
                }
            }
        }
        if (chart.series.size > 1 || chart.series.first().name.isNotBlank()) Legend(chart.series.map { it.name })
    }
}

// ---- Shades -------------------------------------------------------------------------------------------------------

@Composable
private fun HeatmapPaint(map: WorldChart.Heatmap) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val measurer = rememberTextMeasurer()
    val label = MuType.mono(f, 10.sp).copy(color = c.ink70)
    val lo = map.min
    val hi = map.max
    val ink = c.isInk
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val rowW = (map.rows.maxOf { measurer.measure(it, label, maxLines = 1).size.width } + 8.dp.toPx()).coerceAtMost(size.width * 0.32f)
            val headH = 18.dp.toPx()
            val cellW = (size.width - rowW) / map.cols.size
            val cellH = ((size.height - headH) / map.rows.size).coerceAtMost(cellW * 1.2f).coerceAtLeast(1f)
            map.cols.forEachIndexed { j, name ->
                val tl = measurer.measure(name, label, maxLines = 1, constraints = Constraints(maxWidth = (cellW - 2.dp.toPx()).toInt().coerceAtLeast(1)))
                drawText(tl, topLeft = Offset(rowW + cellW * j + (cellW - tl.size.width) / 2f, headH - tl.size.height - 2.dp.toPx()))
            }
            val valueStyle = label.copy(fontSize = if (cellH < 18.dp.toPx() || cellW < 34.dp.toPx()) 8.sp else 10.sp)
            map.rows.forEachIndexed { i, name ->
                val y = headH + cellH * i
                val tl = measurer.measure(name, label, maxLines = 1, constraints = Constraints(maxWidth = (rowW - 8.dp.toPx()).toInt().coerceAtLeast(1)))
                drawText(tl, topLeft = Offset(0f, y + (cellH - tl.size.height) / 2f))
                map.values[i].forEachIndexed { j, v ->
                    val at = Offset(rowW + cellW * j, y)
                    if (v.isNaN()) {
                        drawRect(c.ink06, at, Size(cellW - 1f, cellH - 1f))
                        return@forEachIndexed
                    }
                    val t = if (hi > lo) ((v - lo) / (hi - lo)).toFloat() else 0.5f
                    val fill = shade(t, ink)
                    drawRect(fill, at, Size(cellW - 1f, cellH - 1f))
                    if (cellW > 22.dp.toPx() && cellH > 12.dp.toPx()) {
                        val vl = measurer.measure(ChatChart.label(v, map.unit), valueStyle.copy(color = inkOn(fill)), maxLines = 1)
                        if (vl.size.width < cellW - 2f) drawText(vl, topLeft = Offset(at.x + (cellW - vl.size.width) / 2f, at.y + (cellH - vl.size.height) / 2f))
                    }
                }
            }
        }
        // The scale, in five steps from the smallest number to the largest.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Mono(ChatChart.label(lo, map.unit), color = c.ink70)
            Row {
                (0..4).forEach { k -> Box(Modifier.size(width = 18.dp, height = 10.dp).background(shade(k / 4f, ink))) }
            }
            Mono(ChatChart.label(hi, map.unit), color = c.ink70)
        }
    }
}

// ---- Webs ---------------------------------------------------------------------------------------------------------

/** A node of a web as it is drawn: its centre in the board, how far its edge is from the centre, its colour. */
private data class Placed(val node: WorldGraph.Node, val at: Offset, val reach: Float)

/** Each group's colour, in the order the groups first appear. */
private fun groupColours(nodes: List<WorldGraph.Node>): Map<String, Color> =
    nodes.map { it.group }.filter { it.isNotBlank() }.distinct().withIndex().associate { (i, g) -> g to series(i) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GraphPaint(h: NeueHolders, graph: WorldGraph) {
    val positions = remember(graph) { GraphLayout.force(graph.nodes.map { it.id }, graph.edges.map { it.from to it.to }) }
    val colours = remember(graph) { groupColours(graph.nodes) }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val tile = (minOf(maxWidth, maxHeight) / (2.4f + sqrt(graph.nodes.size.toFloat()) * 1.1f)).coerceIn(22.dp, 56.dp)
            val pad = tile * 0.75f
            val w = maxWidth
            val ht = maxHeight
            Web(h, graph, colours, { id ->
                val p = positions[id] ?: GraphLayout.Pos(0.5, 0.5)
                Offset((pad + (w - pad * 2) * p.x.toFloat()).value, (pad + (ht - pad * 2) * p.y.toFloat()).value)
            }, tile)
        }
        if (colours.size > 1) Legend(colours.keys.toList())
    }
}

/**
 * Nodes at [at] (in dp from the top left), each a card's art when it names one the pool knows, else its label in a
 * box; the edges drawn under them as lines with arrowheads and their verbs at the middle.
 */
@Composable
private fun Web(h: NeueHolders, graph: WorldGraph, colours: Map<String, Color>, at: (String) -> Offset, tile: Dp, hubs: Set<String> = emptySet()) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val measurer = rememberTextMeasurer()
    val verb = MuType.mono(f, 9.sp).copy(color = c.ink70)
    val index = h.builder.index
    val cards = remember(graph, index.size) { graph.nodes.associate { n -> n.id to (if (n.card) cardNamed(h.ai, n.label) else null) } }
    val tileH = tile / CARD_RATIO
    val placed = graph.nodes.map { n ->
        val reach = if (cards[n.id] != null) tileH.value / 2f + 3f else 13f
        Placed(n, at(n.id), reach)
    }
    val byId = placed.associateBy { it.node.id }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            graph.edges.forEach { e ->
                val a = byId[e.from] ?: return@forEach
                val b = byId[e.to] ?: return@forEach
                val from = Offset(a.at.x.dp.toPx(), a.at.y.dp.toPx())
                val to = Offset(b.at.x.dp.toPx(), b.at.y.dp.toPx())
                val d = to - from
                val len = d.getDistance()
                if (len < 1f) return@forEach
                val u = d / len
                val start = from + u * a.reach.dp.toPx()
                val end = to - u * b.reach.dp.toPx()
                if ((end - start).getDistance() < 2f) return@forEach
                val weight = (1f + (e.weight.toFloat() - 1f).coerceIn(0f, 3f)).dp.toPx()
                drawLine(c.ink45, start, end, weight)
                arrow(end, u, c.ink45)
                if (e.label.isNotBlank()) {
                    val tl = measurer.measure(e.label, verb, maxLines = 1, constraints = Constraints(maxWidth = 120.dp.roundToPx()))
                    val mid = (start + end) / 2f
                    val box = Offset(mid.x - tl.size.width / 2f - 2.dp.toPx(), mid.y - tl.size.height / 2f)
                    drawRect(c.paper, box, Size(tl.size.width + 4.dp.toPx(), tl.size.height.toFloat()))
                    drawText(tl, topLeft = Offset(mid.x - tl.size.width / 2f, mid.y - tl.size.height / 2f))
                }
            }
        }
        Centred(placed.map { it.at }) {
            placed.forEach { p ->
                val colour = colours[p.node.group]
                val card = cards[p.node.id]
                val hub = p.node.id in hubs
                if (card != null) {
                    Box(Modifier.border(if (hub) 3.dp else 2.dp, colour ?: c.ink).padding(2.dp)) { ChatCard(h.ai, card.name, tile) }
                } else {
                    NodeLabel(p.node.label, colour, hub, (tile * 2.6f).coerceAtLeast(84.dp))
                }
            }
        }
    }
}

/** A node with no art: its label in a box, its group's colour down its left edge. */
@Composable
private fun NodeLabel(text: String, colour: Color?, hub: Boolean, widest: Dp) {
    val c = Mu.colors
    Row(
        Modifier.widthIn(max = widest).height(IntrinsicSize.Min).background(c.paper).border(if (hub) 2.dp else 1.dp, c.ink),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (colour != null) Box(Modifier.width(4.dp).fillMaxHeight().background(colour))
        MuText(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            style = MuType.help(LocalMuFonts.current).copy(fontWeight = if (hub) FontWeight.Bold else FontWeight.Medium),
            color = c.ink,
            maxLines = 2,
        )
    }
}

/** Each child centred on its point (in dp), wherever that leaves it. */
@Composable
private fun Centred(points: List<Offset>, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val at = points.getOrElse(i) { Offset.Zero }
                p.place((at.x.dp.toPx() - p.width / 2f).toInt(), (at.y.dp.toPx() - p.height / 2f).toInt())
            }
        }
    }
}

private fun DrawScope.arrow(tip: Offset, u: Offset, colour: Color) {
    val long = 8.dp.toPx()
    val wide = 4.dp.toPx()
    val perp = Offset(-u.y, u.x)
    val base = tip - u * long
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perp.x * wide, base.y + perp.y * wide)
        lineTo(base.x - perp.x * wide, base.y - perp.y * wide)
        close()
    }
    drawPath(path, colour)
}

/** A web as a flowchart: each card on the row after the cards that lead to it, top to bottom (`EngineLayout`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowPaint(h: NeueHolders, graph: WorldGraph) {
    val layout = remember(graph) { EngineLayout.of(graph.edges.map { ReaderGuide.Edge(it.from, it.to, it.label) }) }
    // Nodes no edge reaches stand on a row of their own at the foot.
    val rows = remember(layout, graph) {
        val placed = layout.rows.flatten().toSet()
        val loose = graph.nodes.map { it.id }.filter { it !in placed }
        layout.rows + if (loose.isEmpty()) emptyList() else listOf(loose)
    }
    val colours = remember(graph) { groupColours(graph.nodes) }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val widest = rows.maxOfOrNull { it.size } ?: 1
            val tile = (minOf(maxWidth / (widest * 1.6f + 0.4f), maxHeight / (rows.size * 2.1f + 0.2f) * CARD_RATIO)).coerceIn(20.dp, 56.dp)
            val w = maxWidth
            val rowH = maxHeight / rows.size.coerceAtLeast(1)
            val where = HashMap<String, Offset>()
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { k, id ->
                    where[id] = Offset((w * ((k + 0.5f) / row.size)).value, (rowH * (r + 0.5f)).value)
                }
            }
            Web(h, graph, colours, { where[it] ?: Offset(w.value / 2f, 0f) }, tile, layout.hubs)
        }
        if (colours.size > 1) Legend(colours.keys.toList())
    }
}

// ---- Tables -------------------------------------------------------------------------------------------------------

private val NUMERIC = Regex("^[−+-]?[\\d.,]+\\s*%?$")

@Composable
private fun TablePaint(table: WorldTable) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val head = MuType.mono(f, 10.sp).copy(fontWeight = FontWeight.Medium)
    val body = MuType.small(f)
    val mono = MuType.mono(f, 11.sp)
    val n = table.columns.size
    val numeric = remember(table) { (0 until n).map { col -> table.rows.isNotEmpty() && table.rows.all { r -> r[col].isBlank() || NUMERIC.matches(r[col].trim()) } } }
    // Each column as wide as its longest cell in characters, inside sensible bounds.
    val chars = remember(table) {
        (0 until n).map { col -> (listOf(table.columns[col]) + table.rows.map { it[col] }).maxOf { it.length }.coerceIn(3, 36) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val natural = chars.map { (it * 7.4f + 16f).dp }
        val total = natural.fold(0.dp) { a, b -> a + b }
        val widths = if (total < maxWidth) natural.map { it * (maxWidth / total) } else natural
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Row(Modifier.background(c.ink06).padding(vertical = 6.dp)) {
                table.columns.forEachIndexed { col, name ->
                    MuText(
                        name.uppercase(),
                        Modifier.width(widths[col]).padding(horizontal = 8.dp),
                        style = head.copy(textAlign = if (numeric[col]) TextAlign.End else TextAlign.Start),
                        color = c.ink70,
                        maxLines = 1,
                    )
                }
            }
            LazyColumn(Modifier.width(widths.fold(0.dp) { a, b -> a + b })) {
                itemsIndexed(table.rows) { r, row ->
                    Row(
                        Modifier
                            .drawBehind { drawLine(c.ink12, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                            .padding(vertical = 5.dp),
                    ) {
                        row.forEachIndexed { col, cell ->
                            MuText(
                                cell,
                                Modifier.width(widths[col]).padding(horizontal = 8.dp),
                                style = (if (numeric[col]) mono else body).copy(textAlign = if (numeric[col]) TextAlign.End else TextAlign.Start),
                                color = if (col == 0) c.ink else c.ink70,
                                maxLines = 2,
                            )
                        }
                    }
                }
                if (table.rows.isEmpty()) item { Help("No rows.", Modifier.padding(8.dp)) }
            }
        }
    }
}
