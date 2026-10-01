package com.kaiharimoto.neue.ai.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.report.book.Drawing
import com.kaiharimoto.mastertool.core.ai.report.book.Pen
import com.kaiharimoto.mastertool.core.ai.report.book.Shape
import com.kaiharimoto.mastertool.core.ai.report.book.Weight
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors
import com.kaiharimoto.neue.theme.MuFonts

/**
 * A guide's picture painted in the app (1.0.67): the same [Drawing] the PDF places on its pages,
 * laid out once in core. Its marks are painted on canvases in ink — grey levels read between the
 * theme's ink and paper, so the picture turns with the theme — and its cards are real cards, in the
 * artwork the person chose. The marks are kept in their order: a run of marks is a canvas, a run of
 * cards a layer over it, so a set card's veil still lies over its art.
 *
 * [z] is how many dp a point is. Its tags are places to touch: [onTag] hears which. A card that
 * moves between two pictures ([from], where each card was, and [t], how far it has come) slides
 * from its old place to its new one; the marks that are new fade in with it.
 */
@Composable
internal fun DrawingView(
    drawing: Drawing,
    z: Float,
    cards: (String) -> Card?,
    modifier: Modifier = Modifier,
    onTag: ((String) -> Unit)? = null,
    from: Map<String, Rect>? = null,
    t: () -> Float = { 1f },
    overlay: (DrawScope.(scale: Float) -> Unit)? = null,
) {
    val c = Mu.colors
    val fonts = LocalMuFonts.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val layers = remember(drawing) { layers(drawing) }
    val tags = remember(drawing) { drawing.tags().sortedByDescending { it.w * it.h } }
    Box(modifier.size((drawing.width * z).dp, (drawing.height * z).dp)) {
        layers.forEachIndexed { k, layer ->
            when (layer) {
                is Layer.Marks -> {
                    // The marks after the first cards are what changed with them: they fade in as the cards land.
                    val fades = from != null && k > 0
                    Canvas(Modifier.fillMaxSize().graphicsLayer { if (fades) alpha = t() }) {
                        val s = z * density
                        layer.marks.forEach { m -> paint(m, s, c, fonts, measurer, this) }
                    }
                }
                is Layer.Tiles -> layer.tiles.forEach { tile -> Tile(tile, z, cards, from, t) }
            }
        }
        if (overlay != null) Canvas(Modifier.fillMaxSize()) { overlay(z * density) }
        if (onTag != null) {
            tags.forEach { tag ->
                val kind = tag.tag.substringBefore(':')
                Box(
                    Modifier
                        .offset((tag.x * z).dp, (tag.top * z).dp)
                        .size((tag.w * z).dp, (tag.h * z).dp)
                        .cursorPointer(
                            caption = when (kind) {
                                "card" -> "Open"
                                "check" -> "Tick"
                                else -> "Show"
                            },
                        )
                        .muClickable { onTag(tag.tag) },
                )
            }
        }
    }
}

/** A drawing's marks in order, as runs: marks to paint, cards to place. */
internal sealed interface Layer {
    class Marks(val marks: List<Mark>) : Layer
    class Tiles(val tiles: List<Tile>) : Layer
}

/** One mark, flattened out of its groups: the alpha of the faded groups round it, the clip of its clips. */
internal class Mark(val op: Drawing.Op, val alpha: Float, val clip: Rect?)

/** A card or an artwork, [id] its name and which copy of it in the picture: what an animation follows. */
internal class Tile(val op: Drawing.Op, val id: String, val alpha: Float, val clip: Rect?)

internal fun layers(d: Drawing): List<Layer> {
    val out = mutableListOf<Layer>()
    val marks = mutableListOf<Mark>()
    val tiles = mutableListOf<Tile>()
    val seen = HashMap<String, Int>()
    fun flushMarks() { if (marks.isNotEmpty()) { out += Layer.Marks(marks.toList()); marks.clear() } }
    fun flushTiles() { if (tiles.isNotEmpty()) { out += Layer.Tiles(tiles.toList()); tiles.clear() } }
    fun walk(ops: List<Drawing.Op>, alpha: Float, clip: Rect?) {
        ops.forEach { o ->
            when (o) {
                is Drawing.Card, is Drawing.Artwork -> {
                    flushMarks()
                    val name = if (o is Drawing.Card) o.name else (o as Drawing.Artwork).name
                    val n = seen.getOrElse(name) { 0 }
                    seen[name] = n + 1
                    tiles += Tile(o, "$name#$n", alpha, clip)
                }
                is Drawing.Faded -> walk(o.ops, alpha * o.alpha, clip)
                is Drawing.Clip -> {
                    val r = Rect(o.x, o.top, o.x + o.w, o.top + o.h)
                    walk(o.ops, alpha, clip?.intersect(r) ?: r)
                }
                is Drawing.Tag -> Unit
                else -> {
                    flushTiles()
                    marks += Mark(o, alpha, clip)
                }
            }
        }
    }
    walk(d.ops, 1f, null)
    flushMarks()
    flushTiles()
    return out
}

/** Where each card of [d] is, by its id, in points: what the next picture's cards slide from. */
internal fun cardPlaces(d: Drawing): Map<String, Rect> = buildMap {
    layers(d).filterIsInstance<Layer.Tiles>().flatMap { it.tiles }.forEach { t ->
        val o = t.op
        if (o is Drawing.Card) put(t.id, Rect(o.x, o.top, o.x + o.w, o.top + o.h))
    }
}

@Composable
private fun Tile(tile: Tile, z: Float, cards: (String) -> Card?, from: Map<String, Rect>?, t: () -> Float) {
    val c = Mu.colors
    when (val o = tile.op) {
        is Drawing.Card -> {
            val card = remember(o.name) { cards(o.name) }
            val was = from?.get(tile.id)
            val fresh = from != null && was == null
            val clip = tile.clip
            val inner: @Composable () -> Unit = {
                Box(
                    Modifier
                        .offset((o.x * z).dp, (o.top * z).dp)
                        .size((o.w * z).dp, (o.h * z).dp)
                        .graphicsLayer {
                            val p = t()
                            if (was != null && p < 1f) {
                                translationX = (was.left - o.x) * z * density * (1 - p)
                                translationY = (was.top - o.top) * z * density * (1 - p)
                            }
                            alpha = tile.alpha * if (fresh) p else 1f
                            if (fresh) translationY = 10f * density * (1 - p)
                        },
                ) {
                    if (card != null) {
                        NeueCard(card, Modifier.fillMaxSize(), foil = "off")
                    } else {
                        Box(Modifier.fillMaxSize().background(c.ink06).padding(2.dp)) { Help(o.name, color = c.ink45, maxLines = 5) }
                    }
                }
            }
            if (clip != null) {
                Box(Modifier.offset((clip.left * z).dp, (clip.top * z).dp).size((clip.width * z).dp, (clip.height * z).dp).clipToBounds()) {
                    Box(Modifier.offset((-clip.left * z).dp, (-clip.top * z).dp)) { inner() }
                }
            } else {
                inner()
            }
        }
        is Drawing.Artwork -> Box(
            Modifier.offset((o.x * z).dp, (o.top * z).dp).size((o.w * z).dp, (o.h * z).dp).clipToBounds().graphicsLayer { alpha = tile.alpha },
        ) { Artwork(o.name, o.w * z, o.h * z, cards) }
        else -> Unit
    }
}

/** A card's art alone, cut from its frame and cropped to cover a box [w] by [h] dp. */
@Composable
internal fun Artwork(name: String, w: Float, h: Float, cards: (String) -> Card?) {
    val c = Mu.colors
    val card = remember(name) { cards(name) }
    if (card == null) {
        Box(Modifier.size(w.dp, h.dp).background(c.ink06))
        return
    }
    val box = Pen.coverArt(0f, 0f, w, h)
    Box(Modifier.size(w.dp, h.dp).clipToBounds()) {
        // The card is larger than the box it is cut to: measured at its own size, not squeezed into the box's.
        Box(
            Modifier.layout { m, _ ->
                val p = m.measure(Constraints.fixed(box[2].dp.roundToPx(), box[3].dp.roundToPx()))
                layout(w.dp.roundToPx(), h.dp.roundToPx()) { p.place(box[0].dp.roundToPx(), box[1].dp.roundToPx()) }
            },
        ) {
            NeueCard(card, Modifier.fillMaxSize(), foil = "off")
        }
    }
}

/** A grey level, 0 ink to 1 paper, in the theme's own ink and paper. */
internal fun MuColors.gray(g: Float, alpha: Float = 1f): Color = lerp(ink, paper, g.coerceIn(0f, 1f)).let { if (alpha < 1f) it.copy(alpha = it.alpha * alpha) else it }

private fun paint(m: Mark, s: Float, c: MuColors, fonts: MuFonts, measurer: TextMeasurer, scope: DrawScope) {
    val clip = m.clip
    if (clip != null) {
        scope.clipRect(clip.left * s, clip.top * s, clip.right * s, clip.bottom * s) { mark(m, s, c, fonts, measurer, this) }
    } else {
        mark(m, s, c, fonts, measurer, scope)
    }
}

private fun mark(m: Mark, s: Float, c: MuColors, fonts: MuFonts, measurer: TextMeasurer, d: DrawScope) {
    val a = m.alpha
    when (val o = m.op) {
        is Drawing.FillRect -> d.drawRect(c.gray(o.gray, a), Offset(o.x * s, o.top * s), Size(o.w * s, o.h * s))
        is Drawing.StrokeRect -> d.drawRect(
            c.gray(o.gray, a), Offset(o.x * s, o.top * s), Size(o.w * s, o.h * s),
            style = Stroke(o.line * s, pathEffect = o.dash?.let { PathEffect.dashPathEffect(floatArrayOf(it * s, it * s)) }),
        )
        is Drawing.Line -> d.drawLine(
            c.gray(o.gray, a), Offset(o.x1 * s, o.top1 * s), Offset(o.x2 * s, o.top2 * s), o.width * s,
            pathEffect = o.dash?.let { PathEffect.dashPathEffect(floatArrayOf(it * s, it * s)) },
        )
        is Drawing.Fill -> d.drawPath(path(o.shape, s), c.gray(o.gray, a))
        is Drawing.Stroke -> d.drawPath(
            path(o.shape, s), c.gray(o.gray, a),
            style = Stroke(
                o.width * s,
                cap = if (o.round) StrokeCap.Round else StrokeCap.Butt,
                join = if (o.round) StrokeJoin.Round else StrokeJoin.Miter,
                pathEffect = o.dash?.let { PathEffect.dashPathEffect(floatArrayOf(it * s, it * s)) },
            ),
        )
        is Drawing.Text -> text(o, s, c.gray(o.gray, a), fonts, measurer, d)
        else -> Unit
    }
}

private fun path(shape: Shape, s: Float): Path = Path().apply {
    shape.segs.forEach { g ->
        when (g) {
            is Shape.Move -> moveTo(g.x * s, g.y * s)
            is Shape.To -> lineTo(g.x * s, g.y * s)
            is Shape.Curve -> cubicTo(g.x1 * s, g.y1 * s, g.x2 * s, g.y2 * s, g.x * s, g.y * s)
            Shape.Close -> close()
        }
    }
}

/** Words set as the PDF sets them, Inter at its three weights or the mono; never at the system's text scale, since the picture was measured without it. */
private fun text(o: Drawing.Text, s: Float, color: Color, fonts: MuFonts, measurer: TextMeasurer, d: DrawScope) {
    val density = Density(d.density, 1f)
    val style = with(density) {
        TextStyle(
            fontFamily = if (o.weight == Weight.MONO) fonts.mono else fonts.sans,
            fontWeight = when (o.weight) {
                Weight.BOLD -> FontWeight.Bold
                Weight.MEDIUM -> FontWeight.Medium
                else -> FontWeight.Normal
            },
            fontSize = (o.size * s).toSp(),
            letterSpacing = (o.tracking * s).toSp(),
        )
    }
    val layout = measurer.measure(o.text, style, softWrap = false, maxLines = 1, density = density)
    d.drawText(layout, color, Offset(o.x * s, o.baseline * s - layout.firstBaseline))
}

