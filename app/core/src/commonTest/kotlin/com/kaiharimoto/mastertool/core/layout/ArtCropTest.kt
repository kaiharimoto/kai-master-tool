package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtCropTest {

    private val w = 813f
    private val h = 1185f

    private fun near(expected: Float, actual: Float, within: Float = 0.6f, what: String = "") =
        assertTrue(abs(expected - actual) <= within, "$what: expected $expected, was $actual")

    private fun inside(box: CropBox, iw: Float, ih: Float) {
        assertTrue(box.x >= -0.001f && box.y >= -0.001f, "$box starts inside")
        assertTrue(box.right <= iw + 0.001f && box.bottom <= ih + 0.001f, "$box ends inside $iw × $ih")
    }

    @Test
    fun theStandardWindowIsTheBevelsInsideMeasuredOnTheRender() {
        val window = ArtWindow.of(ArtFrame.STANDARD)
        near(96f, window.left * w, what = "left")
        near(215f, window.top * h, what = "top")
        near(719f, window.right * w, what = "right")
        near(837f, window.bottom * h, what = "bottom")
        // The picture is square.
        near(1f, window.aspect(w, h), within = 0.01f)
        assertEquals(4, window.outline(w, h).size)
    }

    @Test
    fun thePendulumWindowStopsAtTheEffectBox() {
        val window = ArtWindow.of(ArtFrame.PENDULUM)
        near(52f, window.left * w, what = "left")
        near(212f, window.top * h, what = "top")
        near(736f, window.bottom * h, what = "bottom")
        assertTrue(window.aspect(w, h) > 1.2f, "wider than tall")
    }

    @Test
    fun aLinkWindowLeavesItsCornerSocketsAlone() {
        val window = ArtWindow.of(ArtFrame.LINK)
        val outline = window.outline(w, h)
        assertEquals(8, outline.size)
        // The cut meets the top edge where the socket's diagonal does: x + y = 41 from the outer corner.
        val (x, y) = outline.first()
        near(41f, (x - 86f) + (y - 205f), within = 1f, what = "socket diagonal")
    }

    @Test
    fun aCropStartsAsTheLargestWindowInTheMiddle() {
        val wide = ArtCrop.initial(1920f, 1080f, 1f)
        assertEquals(CropBox(420f, 0f, 1080f, 1080f), wide)
        val tall = ArtCrop.initial(600f, 900f, 1.5f)
        near(600f, tall.width)
        near(400f, tall.height)
        near(250f, tall.y)
    }

    @Test
    fun movingStopsAtTheEdges() {
        val box = CropBox(100f, 100f, 200f, 200f)
        assertEquals(CropBox(0f, 300f, 200f, 200f), ArtCrop.moved(box, -500f, 5000f, 1000f, 500f))
    }

    @Test
    fun scalingKeepsTheShapeAndTheAnchorAndStaysInside() {
        val box = CropBox(400f, 300f, 300f, 200f)
        val smaller = ArtCrop.scaled(box, 0.5f, 550f, 400f, 1000f, 800f)
        near(1.5f, smaller.width / smaller.height, within = 0.001f)
        near(150f, smaller.width)
        // The anchor, the box's middle, is still its middle.
        near(550f, smaller.x + smaller.width / 2)
        near(400f, smaller.y + smaller.height / 2)
        // Growing past the picture stops at the largest crop there is.
        val huge = ArtCrop.scaled(box, 100f, 0f, 0f, 1000f, 800f)
        near(1000f, huge.width)
        inside(huge, 1000f, 800f)
        // And shrinking stops at the smallest.
        near(ArtCrop.MIN_WIDTH, ArtCrop.scaled(box, 0.0001f, 500f, 400f, 1000f, 800f).width)
    }

    @Test
    fun draggingACornerKeepsTheOppositeOneAndTheShape() {
        val box = CropBox(100f, 100f, 200f, 100f)
        val grown = ArtCrop.resized(box, CropCorner.BOTTOM_RIGHT, 500f, 150f, 1000f, 1000f)
        assertEquals(100f, grown.x)
        assertEquals(100f, grown.y)
        near(400f, grown.width)
        near(200f, grown.height)
        // Dragged past the picture's edge, it stops there.
        val clamped = ArtCrop.resized(box, CropCorner.TOP_LEFT, -900f, -900f, 1000f, 1000f)
        near(300f, clamped.right)
        near(200f, clamped.bottom)
        inside(clamped, 1000f, 1000f)
        near(2f, clamped.width / clamped.height, within = 0.001f)
    }

    @Test
    fun theViewFitCentresAndRoundTrips() {
        val fit = ArtCrop.fit(2000f, 1000f, 500f, 500f)
        near(0.25f, fit.scale, within = 0.0001f)
        near(125f, fit.dy)
        val (vx, vy) = fit.toView(800f, 400f)
        val (ix, iy) = fit.toImage(vx, vy)
        near(800f, ix)
        near(400f, iy)
        val box = CropBox(0f, 0f, 400f, 400f)
        assertEquals(CropCorner.BOTTOM_RIGHT, ArtCrop.cornerAt(box, fit, 100f, 225f, 8f))
        assertNull(ArtCrop.cornerAt(box, fit, 50f, 175f, 8f))
    }
}
