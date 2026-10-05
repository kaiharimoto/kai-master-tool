package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.Icon
import com.kaiharimoto.mastertool.core.world.desk.IconShape
import com.kaiharimoto.mastertool.core.world.desk.Tile
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu

/*
 * The World's icons (1.1.x, `docs/world/DESKTOP.md` §7): data in core (`WorldIcons`, a 32-unit grid), painted here by one
 * Canvas painter — stroke 2 units, square caps, miter joins, fills in ink only. Ink on paper, paper on ink when
 * inverted: the same data, the colour given. No image assets.
 */

/** [icon] at [size], in [color]. */
@Composable
fun IconView(icon: Icon, size: Dp, modifier: Modifier = Modifier, color: Color = Mu.colors.ink) {
    Canvas(modifier.size(size).semantics { contentDescription = icon.name }) { drawIcon(icon, color) }
}

/**
 * An Ai app's tile (§7.3): the 2-unit frame, the glyph at half size in its top-left, the monogram in JetBrains Mono 700
 * at 10 units right-aligned at 28 on a baseline at 28. A frame and letters mean *made by Ai*.
 */
@Composable
fun TileView(tile: Tile, size: Dp, modifier: Modifier = Modifier, color: Color = Mu.colors.ink) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val mono = LocalMuFonts.current.mono
    Canvas(modifier.size(size).semantics { contentDescription = tile.monogram }) {
        val k = this.size.width / WorldIcons.GRID
        drawShape(WorldIcons.TILE_FRAME, k, color)
        translate(WorldIcons.GLYPH_AT * k, WorldIcons.GLYPH_AT * k) {
            tile.glyph.shapes.forEach { drawShape(it, k * WorldIcons.GLYPH_SCALE, color) }
        }
        val px = WorldIcons.MONO_SIZE * k
        val laid = measurer.measure(
            tile.monogram,
            TextStyle(fontFamily = mono, fontWeight = FontWeight.Bold, fontSize = (px / density / fontScale).sp, color = color),
        )
        val x = WorldIcons.MONO_AT * k - laid.size.width
        val y = WorldIcons.MONO_AT * k - laid.firstBaseline
        drawText(laid, topLeft = Offset(x, y))
    }
}

/** An app's own picture, whichever kind: a built-in's icon, or an Ai app's framed tile. */
@Composable
fun AppIcon(ref: AppRef, size: Dp, apps: List<AppManifest>, modifier: Modifier = Modifier, color: Color = Mu.colors.ink) {
    when (ref) {
        is AppRef.BuiltIn -> IconView(WorldIcons.builtIn(ref.kind), size, modifier, color)
        is AppRef.Made -> {
            val tile = remember(ref.slug, apps) { apps.firstOrNull { it.slug == ref.slug }?.tile ?: WorldIcons.tile(ref.slug, com.kaiharimoto.mastertool.core.world.apps.AppKind.VIEWER, null, null) }
            TileView(tile, size, modifier, color)
        }
    }
}

/** [icon] drawn to fill this scope, its 32 units to the scope's width. */
fun DrawScope.drawIcon(icon: Icon, color: Color) {
    val k = size.width / WorldIcons.GRID
    icon.shapes.forEach { drawShape(it, k, color) }
}

private fun DrawScope.drawShape(s: IconShape, k: Float, color: Color) {
    val stroke = Stroke(width = WorldIcons.STROKE * k, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    when (s) {
        is IconShape.Path -> {
            if (s.points.size < 2) return
            val path = Path().apply {
                moveTo(s.points[0].x * k, s.points[0].y * k)
                for (i in 1 until s.points.size) lineTo(s.points[i].x * k, s.points[i].y * k)
                if (s.closed) close()
            }
            drawPath(path, color, style = stroke)
        }
        is IconShape.Box -> {
            val topLeft = Offset(s.x * k, s.y * k)
            val box = Size(s.w * k, s.h * k)
            if (s.filled) drawRect(color, topLeft, box) else drawRect(color, topLeft, box, style = Stroke(width = WorldIcons.STROKE * k, join = StrokeJoin.Miter))
        }
        is IconShape.Arc -> drawArc(
            color,
            startAngle = s.start.toFloat(),
            sweepAngle = s.sweep.toFloat(),
            useCenter = false,
            topLeft = Offset((s.cx - s.r) * k, (s.cy - s.r) * k),
            size = Size(2f * s.r * k, 2f * s.r * k),
            style = Stroke(width = WorldIcons.STROKE * k, cap = StrokeCap.Butt),
        )
    }
}
