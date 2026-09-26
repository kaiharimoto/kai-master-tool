package com.kaiharimoto.neue.cards

import androidx.compose.ui.geometry.Offset
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
