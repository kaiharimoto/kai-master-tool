package com.kaiharimoto.neue.cards

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
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

fun DrawScope.drawFoil(style: String, feel: Offset?, frame: ArtFrame? = null) {
    when (style) {
        Foils.OFF -> Unit
        // Where there is no runtime shader, the classic band stands in (DESIGN.md §6:
        // every shader keeps a drawing that works without one).
        Foils.HOLO -> with(Holo) { if (!drawHolo(feel ?: Offset.Zero, frame)) drawClassic(feel) }
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
fun DrawScope.drawFoilName(mask: NameMask, light: Offset, outlined: Boolean) {
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
        with(Holo) { drawHoloSheet(bar, light) }
        drawImage(mask.letters, dstOffset = at, dstSize = span, blendMode = BlendMode.DstIn, filterQuality = FilterQuality.High)
        canvas.restore()
    }
}
