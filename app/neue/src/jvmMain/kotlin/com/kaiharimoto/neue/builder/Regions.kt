package com.kaiharimoto.neue.builder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.core.layout.GridPoint
import com.kaiharimoto.mastertool.core.layout.GridRegion

/**
 * One solid shape per cluster of cards that share a key — the tablet's
 * breakdown, traced by `GridRegion` and pulled in by [inset]. The colour stands
 * only in the space the crack opened, so a block reads as one object with an
 * edge rather than as cards with a tint.
 */
internal fun DrawScope.drawRegion(
    cells: List<Int>,
    columns: Int,
    pitchX: Float,
    pitchY: Float,
    spacing: Float,
    inset: Float,
    color: Color,
    alpha: Float,
) {
    if (cells.isEmpty() || alpha <= 0f) return
    val rings = GridRegion.outline(cells, columns)
    val path = Path()
    rings.forEach { ring ->
        val corners = ring.corners
        if (corners.size < 4) return@forEach
        corners.indices.forEach { i ->
            val previous = corners[(i - 1 + corners.size) % corners.size]
            val point = corners[i]
            val next = corners[(i + 1) % corners.size]
            val a = normalOf(previous, point)
            val b = normalOf(point, next)
            val x = point.x * pitchX - spacing / 2f + inset * (a.first + b.first)
            val y = point.y * pitchY - spacing / 2f + inset * (a.second + b.second)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
    drawPath(path, color.copy(alpha = color.alpha * alpha))
}

private fun normalOf(from: GridPoint, to: GridPoint): Pair<Float, Float> {
    val dx = (to.x - from.x).coerceIn(-1, 1).toFloat()
    val dy = (to.y - from.y).coerceIn(-1, 1).toFloat()
    return -dy to dx
}

