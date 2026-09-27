package com.kaiharimoto.neue.builder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.core.layout.GroupBlocks

/**
 * The deck's groups as tetris blocks (`GroupBlocks`): under the cards, each
 * group fills the seams inside itself with its colour and reaches [frame] past
 * its outer edge; the seams between two groups are left as paper. The cards on
 * top never move and never change size.
 *
 * Each group is drawn opaque into a layer of its own and laid down at [alphaOf]
 * once, so the overlaps where a cell and its seams meet do not darken.
 */
internal fun DrawScope.drawBlocks(
    keys: List<String?>,
    columns: Int,
    cardWidth: Float,
    cardHeight: Float,
    seam: Float,
    frame: Float,
    colorOf: (String) -> Color,
    alphaOf: (String) -> Float,
) {
    if (columns <= 0 || keys.none { it != null }) return
    val joins = GroupBlocks.joins(keys, columns)
    val pitchX = cardWidth + seam
    val pitchY = cardHeight + seam
    keys.filterNotNull().distinct().forEach { key ->
        val alpha = alphaOf(key)
        if (alpha <= 0.001f) return@forEach
        val color = colorOf(key)
        val canvas = drawContext.canvas
        canvas.saveLayer(Rect(Offset(-frame, -frame), Size(size.width + frame * 2, size.height + frame * 2)), Paint().apply { this.alpha = alpha })
        keys.forEachIndexed { p, k ->
            if (k != key) return@forEachIndexed
            val x = (p % columns) * pitchX
            val y = (p / columns) * pitchY
            val j = joins[p]
            drawRect(color, Offset(x - frame, y - frame), Size(cardWidth + frame * 2, cardHeight + frame * 2))
            if (j.right) drawRect(color, Offset(x + cardWidth, y - frame), Size(seam, cardHeight + frame * 2))
            if (j.down) drawRect(color, Offset(x - frame, y + cardHeight), Size(cardWidth + frame * 2, seam))
            if (j.corner) drawRect(color, Offset(x + cardWidth, y + cardHeight), Size(seam, seam))
        }
        canvas.restore()
    }
}
