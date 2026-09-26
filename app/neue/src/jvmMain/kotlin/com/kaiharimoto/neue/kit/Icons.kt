package com.kaiharimoto.neue.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.draw.paint
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The handful of lucide icons the app uses (§8), at lucide's own stroke of 2
 * on a 24 grid. Text glyphs (`→ ✕ ▼ · ≈`) are preferred wherever they read at
 * 11px; these are for the few places a glyph does not exist.
 */
object Icons {
    private fun icon(name: String, vararg paths: String): ImageVector {
        val builder = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private const val ROUNDED_SQUARE = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"

    val Search = icon("search", circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    val X = icon("x", "M18 6 6 18", "m6 6 12 12")
    val Plus = icon("plus", "M5 12h14", "M12 5v14")
    val Minus = icon("minus", "M5 12h14")
    val More = icon("more-horizontal", circle(12f, 12f, 1f), circle(19f, 12f, 1f), circle(5f, 12f, 1f))
    val Undo = icon("undo-2", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11")
    val Redo = icon("redo-2", "m15 14 5-5-5-5", "M20 9H9.5A5.5 5.5 0 0 0 4 14.5A5.5 5.5 0 0 0 9.5 20H13")
    val Import = icon("upload", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m17 8-5-5-5 5", "M12 3v12")
    val Export = icon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3")
    val Filters = icon(
        "sliders-horizontal",
        "M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4",
    )
    val PanelLeft = icon("panel-left", ROUNDED_SQUARE, "M9 3v18")
    val PanelRight = icon("panel-right", ROUNDED_SQUARE, "M15 3v18")
    val Trash = icon(
        "trash-2",
        "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2", "M10 11v6", "M14 11v6",
    )
    val Grip = icon(
        "grip-vertical",
        circle(9f, 12f, 1f), circle(9f, 5f, 1f), circle(9f, 19f, 1f),
        circle(15f, 12f, 1f), circle(15f, 5f, 1f), circle(15f, 19f, 1f),
    )
    val Copy = icon(
        "copy",
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2z",
        "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
    )
    val Refresh = icon(
        "refresh-cw",
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val Save = icon(
        "save",
        "M15.2 3a2 2 0 0 1 1.4.6l3.8 3.8a2 2 0 0 1 .6 1.4V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M17 21v-7a1 1 0 0 0-1-1H8a1 1 0 0 0-1 1v7", "M7 3v4a1 1 0 0 0 1 1h7",
    )
}

/** A lucide icon in [tint]. Its size is set by the slot (§8), not by the icon. */
@Composable
fun MuIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Box(modifier.paint(rememberVectorPainter(icon), colorFilter = ColorFilter.tint(tint)))
}

/**
 * The mark of Neue Master Tool (§13, `guides/MARK.md`): an ink square carrying
 * a paper diagram of what the app acts on — a deck, three cards stacked up
 * the 45° diagonal. Never a letter.
 *
 * Grammar: 100 × 100, geometry inside the 16–84 field, even coordinates,
 * paper shapes on ink with ink shapes cutting them, one weight, five elements,
 * nothing under 8 units. The cards are 32 × 46, which is 59 : 86 to within
 * a unit. `tools/neue/mark.py` draws the same numbers into the icon files.
 */
object NeueMark {
    /** (x, y, w, h, paper?) in 100-unit space, painted in order. */
    val shapes = listOf(
        Shape(44f, 16f, 32f, 46f, paper = true), // the back card
        Shape(32f, 24f, 36f, 50f, paper = false), // cut
        Shape(34f, 26f, 32f, 46f, paper = true), // the middle card
        Shape(22f, 34f, 36f, 50f, paper = false), // cut
        Shape(24f, 36f, 32f, 46f, paper = true), // the card on top
    )

    data class Shape(val x: Float, val y: Float, val w: Float, val h: Float, val paper: Boolean)
}

@Composable
fun Mark(size: Dp, ink: Color, paper: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val k = this.size.width / 100f
        drawRect(ink)
        NeueMark.shapes.forEach { s ->
            drawRect(if (s.paper) paper else ink, Offset(s.x * k, s.y * k), Size(s.w * k, s.h * k))
        }
    }
}
