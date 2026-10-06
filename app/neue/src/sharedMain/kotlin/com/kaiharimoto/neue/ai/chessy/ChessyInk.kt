package com.kaiharimoto.neue.ai.chessy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import com.kaiharimoto.mastertool.core.ai.avatar.MarkInk
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import com.kaiharimoto.neue.cards.Holo

/**
 * Chessy's manga marks (kai, 2026-10): Ai's shapes ([MarkShape]) placed round her by `core/ai/chessy`'s ChessyMarks,
 * each with the sticker border Ai's marks wear (white and wide, then her line's plum) so it reads on paper and on ink,
 * and **filled with the cards' holographic foil** (kai: "have the effect particles and symbols be the foil texture") —
 * each mark its own small sheet, so every heart and sparkle carries the whole rainbow, its light at [light]. The blush
 * strokes stay pink (they are her cheeks, not a symbol), and where there is no runtime shader the marks keep her flat
 * violet and pink. One of the files `MasterUiLawTest` allows colour, with `AiAvatar.kt`: a character's art is content.
 */
internal object ChessyInk {
    private val VIOLET = Color(0xFF9A76DA)
    private val PINK = Color(0xFFF08DB8)
    private val PLUM = Color(0xFF3B2C4D)
    private val PAPER = Color(0xFFFFFFFF)

    private class Shape(val parts: List<Pair<Path, Float>>, val bounds: Rect)

    private val shapes: Map<MarkShape, Shape> = MarkShape.entries.associateWith { shape ->
        val parts = shape.parts.map { addPathNodes(it.d).toPath() to it.stroke }
        var b = parts.first().first.getBounds()
        parts.forEach { (p, stroke) -> b = Rect(minOf(b.left, p.getBounds().left), minOf(b.top, p.getBounds().top), maxOf(b.right, p.getBounds().right), maxOf(b.bottom, p.getBounds().bottom)).inflate(stroke) }
        Shape(parts, b)
    }

    private val layer = Paint()

    /**
     * A run of marks placed in sheet units ([MarkList]), drawn on this canvas at [s] pixels per sheet unit from
     * ([ox], [oy]): all of them white and wide, then plum, then each filled with foil. Borders are in screen pixels,
     * so a small Chessy keeps a readable sticker.
     */
    fun DrawScope.marks(list: MarkList, s: Float, ox: Float = 0f, oy: Float = 0f, light: Offset = Offset(-.4f, -.6f)) {
        if (list.size == 0 || s <= 0f) return
        val white = (5.5f * s * 3f).coerceIn(2.5f, 7f)
        val dark = white * .45f
        for (pass in 0..2) {
            val border = when (pass) {
                0 -> white
                1 -> dark
                else -> 0f
            }
            for (i in 0 until list.size) {
                val m = list[i]
                val k = s * m.scale
                val cx = ox + m.x * s
                val cy = oy + m.y * s
                val shape = shapes.getValue(m.shape)
                if (pass == 2 && m.shape != MarkShape.BLUSH && foil(shape, cx, cy, k, m.rot, m.alpha, s, light)) continue
                val colour = when (pass) {
                    0 -> PAPER
                    1 -> PLUM
                    else -> if (m.shape.ink == MarkInk.EYE) VIOLET else PINK
                }
                withTransform({
                    translate(cx, cy)
                    if (m.rot != 0f) rotate(m.rot, Offset.Zero)
                    scale(k, k, Offset.Zero)
                }) { fillOf(shape, colour, m.alpha, s, k, border) }
            }
        }
    }

    /** A mark's paths in [colour]; widths in screen pixels over [k], the pixels per mark unit. */
    private fun DrawScope.fillOf(shape: Shape, colour: Color, alpha: Float, s: Float, k: Float, border: Float, blend: BlendMode = BlendMode.SrcOver) {
        shape.parts.forEach { (p, stroke) ->
            if (stroke == 0f) {
                drawPath(p, colour, alpha = alpha, blendMode = blend)
                if (border > 0f) drawPath(p, colour, alpha = alpha, style = Stroke(border / k, join = StrokeJoin.Round), blendMode = blend)
            } else {
                drawPath(p, colour, alpha = alpha, style = Stroke((stroke * 3f * s + border) / k, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
            }
        }
    }

    /**
     * The mark at ([cx], [cy]), [k] pixels per mark unit, turned [rot]: its own foil sheet the size of its bounds, kept
     * only inside the mark. False where there is no runtime shader, and the flat colour is drawn instead.
     */
    private fun DrawScope.foil(shape: Shape, cx: Float, cy: Float, k: Float, rot: Float, alpha: Float, s: Float, light: Offset): Boolean {
        if (!Holo.available) return false
        val r = maxOf(shape.bounds.width, shape.bounds.height) * k * .75f + 2f
        val box = Rect(cx - r, cy - r, cx + r, cy + r)
        var drawn = false
        drawIntoCanvas { canvas ->
            canvas.saveLayer(box, layer)
            // the mark first, then the sheet kept only where the mark is (SrcIn): a sheet masked by a path drawn over
            // it would keep everything the path does not touch
            withTransform({
                translate(cx, cy)
                if (rot != 0f) rotate(rot, Offset.Zero)
                scale(k, k, Offset.Zero)
            }) { fillOf(shape, Color.Black, alpha, s, k, 0f) }
            // the sheet sized to the mark, so its whole rainbow crosses it
            inset(box.left, box.top, size.width - box.right, size.height - box.bottom) {
                drawn = with(Holo) { drawHoloSheet(Rect(Offset.Zero, size), light, blend = BlendMode.SrcIn) }
            }
            canvas.restore()
        }
        return drawn
    }

    /** Foil hearts and sparkles of the petting mode, in canvas pixels: [list]'s marks with scale in pixels. */
    fun DrawScope.particles(list: MarkList, light: Offset) = marks(list, 1f, 0f, 0f, light)
}
