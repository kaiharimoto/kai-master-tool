package com.kaiharimoto.neue.world.apps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.world.desk.Icon
import com.kaiharimoto.mastertool.core.world.desk.IconShape
import com.kaiharimoto.mastertool.core.world.desk.Tile
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/*
 * The World's icons drawn from their data (`docs/world/DESKTOP.md` §7, `WorldIcons`): lines, rectangles, arcs and filled
 * squares on a 32-unit grid, stroked 2 units with square caps and miter joins, fills in ink only. Ink on paper, or paper on
 * ink when inverted — the caller's colour, the same data. Used by the Browser's tabs and pages and by Ai's apps' tiles.
 */

/** [icon] at [size] (32, 20 or 16 dp), in [color]. */
@Composable
fun WorldIcon(icon: Icon, size: Dp, modifier: Modifier = Modifier, color: Color = Mu.colors.ink) {
    Canvas(modifier.size(size)) { drawIcon(icon, this.size.width / WorldIcons.GRID, color) }
}

/** An app Ai made, as its tile (§7.3): a 2-unit frame, its glyph at half size top left, its monogram bottom right. */
@Composable
fun WorldTile(tile: Tile, size: Dp, modifier: Modifier = Modifier, color: Color = Mu.colors.ink) {
    val measurer = rememberTextMeasurer()
    val style = MuType.mono(LocalMuFonts.current, 10.sp).copy(fontWeight = FontWeight.Bold, color = color)
    Canvas(modifier.size(size)) {
        val u = this.size.width / WorldIcons.GRID
        drawShape(WorldIcons.TILE_FRAME, u, color)
        translate(WorldIcons.GLYPH_AT * u, WorldIcons.GLYPH_AT * u) { drawIcon(tile.glyph, u * WorldIcons.GLYPH_SCALE, color, strokeUnits = WorldIcons.STROKE / WorldIcons.GLYPH_SCALE * 0.75f) }
        val text = measurer.measure(tile.monogram, style.copy(fontSize = (WorldIcons.MONO_SIZE * u).toSp()))
        val right = WorldIcons.MONO_AT * u
        val baseline = WorldIcons.MONO_AT * u
        drawText(text, topLeft = Offset(right - text.size.width, baseline - text.firstBaseline))
    }
}

/** Draws [icon] with [u] pixels a unit; [strokeUnits] is the stroke in the icon's own units (2). */
fun DrawScope.drawIcon(icon: Icon, u: Float, color: Color, strokeUnits: Float = WorldIcons.STROKE.toFloat()) {
    icon.shapes.forEach { drawShape(it, u, color, strokeUnits) }
}

private fun DrawScope.drawShape(s: IconShape, u: Float, color: Color, strokeUnits: Float = WorldIcons.STROKE.toFloat()) {
    val stroke = Stroke(width = strokeUnits * u, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    when (s) {
        is IconShape.Path -> {
            if (s.points.size < 2) return
            val p = Path()
            s.points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x * u, pt.y * u) else p.lineTo(pt.x * u, pt.y * u) }
            if (s.closed) p.close()
            drawPath(p, color, style = stroke)
        }
        is IconShape.Box -> if (s.filled) {
            drawRect(color, Offset(s.x * u, s.y * u), Size(s.w * u, s.h * u))
        } else {
            drawRect(color, Offset(s.x * u, s.y * u), Size(s.w * u, s.h * u), style = stroke)
        }
        is IconShape.Arc -> drawArc(
            color, s.start.toFloat(), s.sweep.toFloat(), useCenter = false,
            topLeft = Offset((s.cx - s.r) * u, (s.cy - s.r) * u), size = Size(2f * s.r * u, 2f * s.r * u), style = stroke,
        )
    }
}

/** The 16 dp a tab's or a page row's glyph is drawn at. */
val GLYPH_SMALL = 16.dp
