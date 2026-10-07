package com.kaiharimoto.neue.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kaiharimoto.mastertool.core.layout.NameInk
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.ui.theme.drawPrismaticInset
import com.kaiharimoto.mastertool.ui.theme.foilAngleFor

/**
 * The foil on a card's face — one of the two places this app may use colour,
 * because foil is part of the card, not part of the chrome (§17: content keeps
 * its colour). `MasterUiLawTest` allows colour in this file and in
 * `GroupMarkers.kt`, and nowhere else.
 *
 * [feel] is where the pointer is across the card, −1..1, or null when it is
 * elsewhere: on a desk the mouse stands in for the tilt a tablet would give.
 */
data class FoilStyle(val id: String, val label: String, val description: String)

object Foils {
    const val HOLO = "holo"
    const val CLASSIC = "classic"
    const val OFF = "off"

    val all = listOf(
        FoilStyle(HOLO, "Holographic", "Silver with a diffraction grating; the rainbow follows the pointer"),
        FoilStyle(CLASSIC, "Classic", "The original two-hue band"),
        FoilStyle(OFF, "Off", "No foil"),
    )

    fun label(id: String): String = all.firstOrNull { it.id == id }?.label ?: id
}

fun DrawScope.drawFoil(style: String, feel: Offset?, frame: ArtFrame? = null, cache: HoloCache? = null) {
    when (style) {
        Foils.OFF -> Unit
        // Where there is no runtime shader, the classic band stands in (DESIGN.md §6:
        // every shader keeps a drawing that works without one).
        Foils.HOLO -> with(Holo) { if (!drawHolo(feel ?: Offset.Zero, frame, cache)) drawClassic(feel) }
        else -> drawClassic(feel)
    }
}

// Square corners: nothing in the family is rounded, not even foil.
private fun DrawScope.drawClassic(feel: Offset?) = drawPrismaticInset(
    angleDegrees = foilAngleFor(feel ?: Offset.Zero),
    cornerRadiusPx = 0f,
    highlight = feel,
)

/**
 * The card's printed name, stamped in the same foil as its border — kai's pick
 * of the exploration (`NEUE.md` §2c). The foil is drawn as a sheet over the name
 * bar and kept only where [mask] has a letter; [outlined] lays the letters,
 * grown by a hair, in ink underneath first. Only with the holographic foil,
 * which is the one that has a stamp to draw.
 */
fun DrawScope.drawFoilName(mask: NameMask, light: Offset, outlined: Boolean, cache: HoloCache? = null) {
    if (!Holo.available) return
    val bar = Rect(
        NameInk.LEFT * size.width,
        NameInk.TOP * size.height,
        NameInk.RIGHT * size.width,
        NameInk.BOTTOM * size.height,
    )
    val at = IntOffset(bar.left.roundToInt(), bar.top.roundToInt())
    val span = IntSize(bar.width.roundToInt().coerceAtLeast(1), bar.height.roundToInt().coerceAtLeast(1))
    if (outlined) {
        drawImage(mask.outline, dstOffset = at, dstSize = span, colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.92f), BlendMode.SrcIn), filterQuality = FilterQuality.High)
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(bar, Paint())
        with(Holo) { drawHoloSheet(bar, light, cache) }
        drawImage(mask.letters, dstOffset = at, dstSize = span, blendMode = BlendMode.DstIn, filterQuality = FilterQuality.High)
        canvas.restore()
    }
}

/**
 * The foil switch's symbol (kai, 1.0.15: "a foil border toggle by the groups
 * button with a shiny symbol in the same foil texture"): a small card, paper
 * inside an ink edge, with the very foil a card face wears round it when [on] —
 * its light following [feel], the pointer over the button — and a bare outline
 * when off.
 */
@androidx.compose.runtime.Composable
fun FoilGlyph(on: Boolean, feel: Offset?, ink: Color, paper: Color, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        drawRect(paper)
        // The whole glyph is foil — the sheet the stamped names are cut from — because a
        // card's border band is a pixel wide on a card this small.
        if (on) with(Holo) { if (!drawHoloSheet(Rect(Offset.Zero, size), feel ?: Offset(-0.4f, -0.6f))) drawFoil(Foils.CLASSIC, feel) }
        // A 1 dp edge, inside the glyph.
        drawRect(ink, topLeft = Offset(density / 2f, density / 2f), size = androidx.compose.ui.geometry.Size(size.width - density, size.height - density), style = androidx.compose.ui.graphics.drawscope.Stroke(density))
    }
}

/**
 * A glint of foil in the shape of a four-point star — a plus drawn to a point (kai, 1.0.95: "a holographic glimmer in the
 * same texture as the foiling in a star + shape when a card is activating"): the holographic sheet the stamped names are
 * cut from, kept inside the star centred on [center], its points [radius] out, its light at [light]. Nothing where there is
 * no runtime shader: a glint is a flourish, never information.
 */
fun DrawScope.drawFoilStar(center: Offset, radius: Float, light: Offset, cache: HoloCache? = null) {
    if (!Holo.available || radius < 1f) return
    val waist = radius * 0.16f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + waist, center.y - waist)
        lineTo(center.x + radius, center.y)
        lineTo(center.x + waist, center.y + waist)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - waist, center.y + waist)
        lineTo(center.x - radius, center.y)
        lineTo(center.x - waist, center.y - waist)
        close()
    }
    clipPath(path) {
        with(Holo) { drawHoloSheet(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), light, cache) }
    }
}
