package com.kaiharimoto.neue.cards

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.min

/**
 * The back of a card, as the classic app's goldfish table drew it (1.0.88, kai: "use the cardback we used for the
 * initial fishbowl in the old version of the app pre-master UI" — `ui/components/CardBack.kt` at `7e09ed16^`, its
 * default oval). Card stock, not chrome: a warm brown lit from the top left, a dark embossed oval, a printed rule just
 * inside the edge. Drawn, never shipped as artwork — the official back is Konami's and this repository is public — so
 * it is instant and exact at any size, dozens at a time, with no network.
 *
 * Like the foil, it is a card's own face, so it is one of the files Master UI's law allows colour and a gradient
 * (`MasterUiLawTest.colourAllowed`); its corners stay square, as every card in Neue is drawn.
 */
@Composable
fun ClassicCardBack(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawStock()
        drawOvalMark()
        drawPrintedEdge()
    }
}

/** The card stock: a warm brown, lit slightly from the top left — the one warm surface on the table. */
private fun DrawScope.drawStock() {
    drawRect(brush = Brush.linearGradient(listOf(FIELD_LIGHT, FIELD, FIELD_DEEP), start = Offset.Zero, end = Offset(size.width, size.height)))
}

/** A thin darker rule just inside the edge, the way printing sits on card stock. */
private fun DrawScope.drawPrintedEdge() {
    val inset = min(size.width, size.height) * 0.045f
    drawRect(
        color = EDGE,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        style = Stroke(width = min(size.width, size.height) * 0.018f),
    )
}

/**
 * One dark oval, centred, proportioned off the card so it is the same shape at any size; a soft halo so it sits in
 * the card, and one light rim, because a back is embossed.
 */
private fun DrawScope.drawOvalMark() {
    val w = size.width * 0.62f
    val h = size.height * 0.42f
    val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
    drawOval(color = HALO, topLeft = Offset(topLeft.x - w * 0.05f, topLeft.y - h * 0.05f), size = Size(w * 1.10f, h * 1.10f))
    drawOval(color = INK, topLeft = topLeft, size = Size(w, h))
    drawOval(color = RIM, topLeft = topLeft, size = Size(w, h), style = Stroke(width = min(size.width, size.height) * 0.012f))
}

// Card stock, not the app's surface: the classic app's own values.
private val FIELD_LIGHT = Color(0xFF9C6B3C)
private val FIELD = Color(0xFF8A5A2E)
private val FIELD_DEEP = Color(0xFF6E4523)
private val EDGE = Color(0x55341F0E)
private val HALO = Color(0x33241505)
private val INK = Color(0xFF17110A)
private val RIM = Color(0x33C79A63)
