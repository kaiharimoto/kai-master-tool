package com.kaiharimoto.neue.ai.chessy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import com.kaiharimoto.mastertool.core.ai.avatar.MarkInk
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape

/**
 * Chessy's manga marks in her own colours (kai, 2026-10): the violet of her bows, the pink of her tongue, and her
 * line's dark plum, with the same sticker border Ai's marks wear so they read on paper and on ink. The marks are
 * Ai's shapes ([MarkShape]) placed round her by `core/ai/chessy`'s ChessyMarks. One of the files `MasterUiLawTest`
 * allows colour, with `AiAvatar.kt`: a character's art is content.
 */
internal object ChessyInk {
    private val VIOLET = Color(0xFF9A76DA)
    private val PINK = Color(0xFFF08DB8)
    private val PLUM = Color(0xFF3B2C4D)
    private val PAPER = Color(0xFFFFFFFF)

    private val paths: Map<MarkShape, List<Pair<Path, Float>>> = MarkShape.entries.associateWith { shape ->
        shape.parts.map { addPathNodes(it.d).toPath() to it.stroke }
    }

    /**
     * A run of marks in sheet px, drawn [u] screen px per sheet px: all of them white and wide, then plum, then in
     * their colours. The border's widths are in screen px, so a small Chessy keeps a readable sticker.
     */
    fun DrawScope.marks(list: MarkList, u: Float) {
        if (list.size == 0 || u <= 0f) return
        val white = (5.5f * u * 3f).coerceIn(2.5f, 7f)
        val dark = white * .45f
        for (pass in 0..2) {
            val border = when (pass) {
                0 -> white
                1 -> dark
                else -> 0f
            }
            for (i in 0 until list.size) {
                val m = list[i]
                val colour = when (pass) {
                    0 -> PAPER
                    1 -> PLUM
                    else -> if (m.shape.ink == MarkInk.EYE) VIOLET else PINK
                }
                withTransform({
                    translate(m.x, m.y)
                    if (m.rot != 0f) rotate(m.rot, Offset.Zero)
                    scale(m.scale, m.scale, Offset.Zero)
                }) {
                    // Widths in screen pixels become the mark's own units: over the sheet's scale, then the mark's.
                    val k = u * m.scale
                    paths.getValue(m.shape).forEach { (p, stroke) ->
                        if (stroke == 0f) {
                            drawPath(p, colour, alpha = m.alpha)
                            if (border > 0f) drawPath(p, colour, alpha = m.alpha, style = Stroke(border / k, join = StrokeJoin.Round))
                        } else {
                            drawPath(p, colour, alpha = m.alpha, style = Stroke((stroke * 3f * u + border) / k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        }
    }
}
