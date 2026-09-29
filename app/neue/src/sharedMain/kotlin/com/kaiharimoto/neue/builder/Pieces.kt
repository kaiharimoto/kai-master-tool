package com.kaiharimoto.neue.builder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.kaiharimoto.mastertool.core.layout.LabelEdge
import com.kaiharimoto.mastertool.core.layout.PieceLayout

/**
 * Where each card of a section is drawn, with the deck broken into pieces by its
 * groups (`GroupPieces`, kai's 1.0.15 picture): flush inside a piece, a [gap]
 * between two. Two dissections can be in play at once and are blended by their
 * own amounts:
 *
 * - the builder's, [builder], by the lens, at [crack] — and the fitter has
 *   reserved its width and height, so at rest the pieces fill the grid's box;
 * - zen's, [zen], by the Roles groups, when the pieces are asked for in deep zen,
 *   by [zenGap] times the wheel's amount (1.0.17), opening downward below [zenAbove].
 *
 * As zen deepens the builder's gives way ([deep]) and zen's takes over ([zenAmount]);
 * whichever is showing is centred in the box the fitter reserved, so the deck
 * does not slide sideways when one gives way to the other. Everything is in the
 * grid's own pixels, before zen's transform.
 */
internal class PiecePlacer(
    val columns: Int,
    val cardWidth: Float,
    val cardHeight: Float,
    val gap: Float,
    val builder: PieceLayout,
    val crack: Float,
    val zen: PieceLayout,
    /** Zen's own standard gap: the wheel scales it there, through the amount. */
    val zenGap: Float = gap,
    /** How far down this section's zen pieces start: the growth of the sections above it, at one gap. */
    val zenAbove: Float = 0f,
    /** Room over the grid for the name tabs of the pieces in its top row (1.0.18), at full crack. */
    val labelRoom: Float = 0f,
    /**
     * The builder's gap down, where it is not [gap] (1.0.37): fitted group blocks touch
     * across, a hairline for their outlines, and keep room between bands for their names.
     */
    val gapY: Float = gap,
) {
    private val reservedX = builder.spanX * gap * crack
    private val reservedY = builder.spanY * gapY * crack

    private fun gx(layout: PieceLayout, p: Int) = layout.shiftX.getOrElse(p) { 0 } - layout.spanX / 2f
    private fun gy(layout: PieceLayout, p: Int) = layout.shiftY.getOrElse(p) { 0 } - layout.spanY / 2f

    /**
     * How far card [p] stands from its index's column in [layout]: nothing, except in a
     * last row slid under its groups (1.0.33, `StragglerSlide`) — which slides with the
     * gaps, so the stragglers go home as the pieces close.
     */
    private fun slide(layout: PieceLayout, p: Int) = (layout.column.getOrNull(p) ?: (p % columns)) - p % columns

    /**
     * How far card [p] stands from its index's row in [layout]: nothing, except in a deck
     * laid out in bands of group blocks (1.0.37, `GroupBands`), where a card stands in its
     * group's block — and goes back to its index's row as the groups close.
     */
    private fun drop(layout: PieceLayout, p: Int) = if (layout.rowOf == null) 0 else layout.row(p) - p / columns

    /**
     * How far zen's cells hold: with its pieces, as they open — except a deck in bands, whose
     * blocks are its shape in zen too, flush until zen's pieces open them.
     */
    private fun zenCells(deep: Float, zenAmount: Float) = if (zen.rowOf != null) deep else zenAmount * deep

    fun x(p: Int, deep: Float = 0f, zenAmount: Float = 0f): Float =
        (p % columns + slide(builder, p) * crack * (1f - deep) + slide(zen, p) * zenCells(deep, zenAmount)) * cardWidth +
            reservedX / 2f + gx(builder, p) * gap * crack * (1f - deep) + gx(zen, p) * zenGap * zenAmount * deep

    // Zen's pieces open downward rather than about the middle, and each section starts
    // below the growth of those above it, so the sections never open into each other.
    fun y(p: Int, deep: Float = 0f, zenAmount: Float = 0f): Float =
        (p / columns + drop(builder, p) * crack * (1f - deep) + drop(zen, p) * zenCells(deep, zenAmount)) * cardHeight +
            reservedY / 2f + labelRoom * crack * (1f - deep) + gy(builder, p) * gapY * crack * (1f - deep) +
            (zen.shiftY.getOrElse(p) { 0 } * zenGap + zenAbove) * zenAmount * deep

    /** Card [p] at rest, in the builder. */
    fun rest(p: Int): Offset = Offset(x(p), y(p))

    /** How far card [p] is from its rest place once zen is deep: the part that does not depend on zen's pieces… */
    fun zenBase(p: Int): Offset = Offset(x(p, 1f, 0f) - x(p), y(p, 1f, 0f) - y(p))

    /** …and the part that does, all the way in. */
    fun zenPieces(p: Int): Offset = Offset(x(p, 1f, 1f) - x(p, 1f, 0f), y(p, 1f, 1f) - y(p, 1f, 0f))

    /** The card at [point] (grid pixels, at rest), or -1. */
    fun at(point: Offset, count: Int): Int {
        for (p in 0 until count) {
            val o = rest(p)
            if (point.x >= o.x && point.x < o.x + cardWidth && point.y >= o.y && point.y < o.y + cardHeight) return p
        }
        return -1
    }
}

/**
 * The builder's pieces, outlined: a [frame]-wide line in each group's colour
 * round every piece, in the gap outside it, so a piece reads as one shape with
 * its colour on it. Drawn under the cards, so the ends that reach under a
 * neighbour are hidden and only the outline shows. Cards in no group get none.
 *
 * Each group is drawn opaque into a layer of its own and laid down at [alphaOf]
 * once, so the corners where two strips meet do not darken.
 */
internal fun DrawScope.drawPieces(
    keys: List<String?>,
    pieces: PieceLayout,
    at: (Int) -> Offset,
    cardWidth: Float,
    cardHeight: Float,
    frame: Float,
    colorOf: (String) -> Color,
    alphaOf: (String) -> Float,
    /** A group's name, written once on a tab of its largest piece's border (1.0.18); null draws no names. */
    labels: Labels? = null,
) {
    if (keys.none { it != null } || pieces.piece.size != keys.size) return
    keys.filterNotNull().distinct().forEach { key ->
        val alpha = alphaOf(key)
        if (alpha <= 0.001f) return@forEach
        val color = colorOf(key)
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset(-frame * 4, -frame * 4), Size(size.width + frame * 8, size.height + frame * 8)), Paint().apply { this.alpha = alpha })
        keys.forEachIndexed { p, k ->
            if (k != key) return@forEachIndexed
            val o = at(p)
            val (left, top, right, bottom) = pieces.outerSides(p).let { listOf(it[0], it[1], it[2], it[3]) }
            if (left) drawRect(color, Offset(o.x - frame, o.y - frame), Size(frame, cardHeight + frame * 2))
            if (right) drawRect(color, Offset(o.x + cardWidth, o.y - frame), Size(frame, cardHeight + frame * 2))
            if (top) drawRect(color, Offset(o.x - frame, o.y - frame), Size(cardWidth + frame * 2, frame))
            if (bottom) drawRect(color, Offset(o.x - frame, o.y + cardHeight), Size(cardWidth + frame * 2, frame))
        }
        labels?.let { drawLabel(key, keys, pieces, at, cardWidth, frame, color, it) }
        canvas.restore()
    }
}

/** What the name tabs need: how to measure text, in what style, how tall a tab is, and each key's name. */
internal class Labels(val measurer: TextMeasurer, val style: TextStyle, val tab: Float, val nameOf: (String) -> String)

/**
 * [key]'s name on a tab rising from a top edge of its pieces — the longest there
 * is, or any the name fits on whole (`PieceLayout.labelEdge`) — as wide as the
 * name, or as the edge, whichever is less, in the group's colour with the
 * lettering in black or white, whichever reads on it. Once per group: a name on
 * every card was noise (kai, 1.0.18).
 */
private fun DrawScope.drawLabel(
    key: String,
    keys: List<String?>,
    pieces: PieceLayout,
    at: (Int) -> Offset,
    cardWidth: Float,
    frame: Float,
    color: Color,
    labels: Labels,
) = drawGroupLabel(key, keys, pieces, at, cardWidth, frame, color, labels)

/**
 * The tab itself, for the builder and for zen (1.0.24). Zen chooses the edge
 * itself ([edgeOf], given how many card widths the name needs: the cards still in
 * their piece) and how tall the tab can stand there ([tabOf]; 0 draws nothing),
 * and fades it by [alpha]; the builder takes the defaults.
 */
internal fun DrawScope.drawGroupLabel(
    key: String,
    keys: List<String?>,
    pieces: PieceLayout,
    at: (Int) -> Offset,
    cardWidth: Float,
    frame: Float,
    color: Color,
    labels: Labels,
    alpha: Float = 1f,
    edgeOf: (Float) -> LabelEdge? = { need -> pieces.labelEdge(keys, key, need) },
    tabOf: (LabelEdge) -> Float = { labels.tab },
) {
    val pad = labels.tab * 0.4f
    val name = labels.nameOf(key).uppercase()
    val style = labels.style.copy(color = if (color.luminance() > 0.5f) Color.Black else Color.White)
    val whole = labels.measurer.measure(name, style, maxLines = 1).size.width + pad * 2 - frame * 2
    val edge = edgeOf(whole / cardWidth.coerceAtLeast(1f)) ?: return
    val tab = tabOf(edge)
    if (tab <= 0f) return
    val o = at(edge.first)
    val room = edge.cells * cardWidth + frame * 2
    val text = labels.measurer.measure(
        name,
        style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = (room - pad * 2).toInt().coerceAtLeast(1)),
    )
    val width = (text.size.width + pad * 2).coerceAtMost(room)
    drawRect(color, Offset(o.x - frame, o.y - tab), Size(width, tab), alpha = alpha)
    drawText(text, topLeft = Offset(o.x - frame + pad, o.y - tab + (tab - text.size.height) / 2f), alpha = alpha)
}
