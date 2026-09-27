package com.kaiharimoto.neue.builder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.core.layout.PieceLayout

/**
 * Where each card of a section is drawn, with the deck broken into pieces by its
 * groups (`GroupPieces`, kai's 1.0.15 picture): flush inside a piece, a [gap]
 * between two. Two dissections can be in play at once and are blended by their
 * own amounts:
 *
 * - the builder's, [builder], by the lens, at [crack] — and the fitter has
 *   reserved its width and height, so at rest the pieces fill the grid's box;
 * - zen's, [zen], by the Roles groups, when the pieces are asked for in deep zen.
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
) {
    private val reservedX = builder.spanX * gap * crack
    private val reservedY = builder.spanY * gap * crack

    private fun gx(layout: PieceLayout, p: Int) = layout.shiftX.getOrElse(p) { 0 } - layout.spanX / 2f
    private fun gy(layout: PieceLayout, p: Int) = layout.shiftY.getOrElse(p) { 0 } - layout.spanY / 2f

    fun x(p: Int, deep: Float = 0f, zenAmount: Float = 0f): Float =
        (p % columns) * cardWidth + reservedX / 2f + gx(builder, p) * gap * crack * (1f - deep) + gx(zen, p) * gap * zenAmount * deep

    fun y(p: Int, deep: Float = 0f, zenAmount: Float = 0f): Float =
        (p / columns) * cardHeight + reservedY / 2f + gy(builder, p) * gap * crack * (1f - deep) + gy(zen, p) * gap * zenAmount * deep

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
        canvas.restore()
    }
}
