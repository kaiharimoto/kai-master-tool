package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Auto align (1.0.89): the crop finds the art in a whole card, or the art itself inside plain margins. */
class ArtAutoAlignTest {
    private val window = ArtWindow(0.12f, 0.18f, 0.88f, 0.70f)
    private val aspect = window.aspect(813f, 1185f)
    private val card = 813f / 1185f

    private fun near(a: Float, b: Float, by: Float = 2f) = assertTrue(abs(a - b) <= by, "$a vs $b")

    @Test
    fun aWholeCardIsCroppedToItsArtBox() {
        val box = ArtCrop.auto(813f, 1185f, aspect, window, card)
        near(box.x, 0.12f * 813f)
        near(box.y, 0.18f * 1185f)
        near(box.width / box.height, aspect, 0.01f)
        assertTrue(box.right <= 813f && box.bottom <= 1185f)
    }

    @Test
    fun aCardInsideAScreenshotIsFoundThroughItsMargins() {
        // An 813 × 1185 card at (300, 100) on a 1400 × 1400 white screenshot.
        val bounds = ContentBounds.of(1400, 1400, step = 4) { x, y -> if (x in 300 until 1113 && y in 100 until 1285) 0xFF336699.toInt() else 0xFFFFFFFF.toInt() }
        assertNotNull(bounds)
        near(bounds.x, 300f, 4f)
        near(bounds.y, 100f, 4f)
        near(bounds.width, 813f, 8f)
        val box = ArtCrop.auto(1400f, 1400f, aspect, window, card, bounds)
        near(box.x, 300f + 0.12f * 813f, 6f)
        near(box.y, 100f + 0.18f * 1185f, 6f)
    }

    @Test
    fun artAloneFillsItsLargestBox() {
        val box = ArtCrop.auto(1920f, 1080f, aspect, window, card)
        near(box.height, 1080f, 1f)
        near(box.x + box.width / 2f, 960f, 1f)
        assertEquals(ArtCrop.initial(1920f, 1080f, aspect), box)
    }
}
