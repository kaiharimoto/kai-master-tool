package com.kaiharimoto.neue

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import com.kaiharimoto.mastertool.core.layout.ArtCrop
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.layout.ArtWindow
import com.kaiharimoto.neue.art.bake
import com.kaiharimoto.neue.platform.decodePicture
import com.kaiharimoto.neue.platform.encodePng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Your own art, kept (1.0.34): the card's render with the crop drawn into its
 * art box, and nothing of the card outside the box touched — the frame, the
 * text, a link card's corner sockets.
 */
class ArtBakeTest {

    private fun solid(w: Int, h: Int, color: Color): ImageBitmap =
        ImageBitmap(w, h).also { Canvas(it).drawRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), Paint().apply { this.color = color }) }

    private val face = solid(813, 1185, Color.Black)
    private val art = solid(400, 300, Color.White)

    private fun baked(frame: ArtFrame): androidx.compose.ui.graphics.PixelMap {
        val window = ArtWindow.of(frame)
        val crop = ArtCrop.initial(400f, 300f, window.aspect(813f, 1185f))
        return bake(face, art, crop, window).toPixelMap()
    }

    private val white = Color.White.toArgb()
    private val black = Color.Black.toArgb()

    @Test
    fun theArtFillsTheBoxAndOnlyTheBox() {
        val px = baked(ArtFrame.STANDARD)
        assertEquals(white, px[407, 526].toArgb(), "the middle of the art box")
        assertEquals(white, px[100, 220].toArgb(), "just inside its corner")
        assertEquals(black, px[90, 526].toArgb(), "the bevel")
        assertEquals(black, px[407, 150].toArgb(), "the name bar")
        assertEquals(black, px[407, 900].toArgb(), "the text box")
    }

    @Test
    fun aLinkCardKeepsItsCornerSockets() {
        val px = baked(ArtFrame.LINK)
        assertEquals(black, px[98, 217].toArgb(), "inside the top-left socket")
        assertEquals(black, px[716, 834].toArgb(), "inside the bottom-right socket")
        assertEquals(white, px[140, 260].toArgb(), "past the socket")
        assertEquals(white, px[407, 218].toArgb(), "under the top arrow, which sits on the bevel")
    }

    @Test
    fun aPendulumCardStopsAtItsEffectBox() {
        val px = baked(ArtFrame.PENDULUM)
        assertEquals(white, px[60, 700].toArgb(), "above the box")
        assertEquals(black, px[407, 760].toArgb(), "the pendulum-effect box")
    }

    @Test
    fun theBakedCardRoundTripsAsAPng() {
        val window = ArtWindow.of(ArtFrame.STANDARD)
        val bytes = assertNotNull(encodePng(bake(face, art, ArtCrop.initial(400f, 300f, window.aspect(813f, 1185f)), window)))
        val back = assertNotNull(decodePicture(bytes))
        assertEquals(813, back.width)
        assertEquals(1185, back.height)
        assertEquals(white, back.toPixelMap()[407, 526].toArgb())
    }
}
