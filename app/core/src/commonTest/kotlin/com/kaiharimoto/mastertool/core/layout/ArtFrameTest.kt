package com.kaiharimoto.mastertool.core.layout

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArtFrameTest {

    private val cardRatio = 813f / 1185f

    @Test
    fun everyFrameTypeFindsItsTemplate() {
        listOf("normal", "effect", "ritual", "fusion", "synchro", "xyz", "spell", "trap", "token")
            .forEach { assertSame(ArtFrame.STANDARD, ArtFrame.of(it), it) }
        listOf("normal_pendulum", "effect_pendulum", "ritual_pendulum", "fusion_pendulum", "synchro_pendulum", "xyz_pendulum")
            .forEach { assertSame(ArtFrame.PENDULUM, ArtFrame.of(it), it) }
        assertSame(ArtFrame.LINK, ArtFrame.of("link"))
        assertNull(ArtFrame.of("skill"))
        // Anything new falls back to the frame nearly every card has.
        assertSame(ArtFrame.STANDARD, ArtFrame.of("something_new"))
    }

    @Test
    fun theStandardFrameIsASquareOnTheCardsCentreline() {
        val f = ArtFrame.STANDARD
        // Square in pixels: width in card-widths equals height in card-heights times H/W.
        val widthPx = f.width * 813f
        val heightPx = f.height * 1185f
        assertTrue(abs(widthPx - heightPx) <= 2f, "$widthPx x $heightPx")
        assertTrue(abs((f.left + f.right) / 2f - 0.5f) < 0.003f)
    }

    @Test
    fun thePendulumFrameIsWiderAndReachesFurtherDown() {
        val s = ArtFrame.STANDARD
        val p = ArtFrame.PENDULUM
        assertTrue(p.left < s.left && p.right > s.right)
        assertTrue(p.bottom > s.bottom)
        assertTrue(abs(p.top - s.top) < 0.002f, "both frames start under the name bar")
        assertTrue(abs((p.left + p.right) / 2f - 0.5f) < 0.003f)
    }

    @Test
    fun onlyTheLinkFrameIsInterrupted() {
        assertTrue(ArtFrame.LINK.interrupted)
        assertEquals(ArtFrame.STANDARD, ArtFrame.LINK.copy(interrupted = false))
        assertTrue(!ArtFrame.STANDARD.interrupted && !ArtFrame.PENDULUM.interrupted)
        // The sockets must not swallow the frame: some of every edge survives.
        val edge = ArtFrame.LINK.width
        assertTrue(2 * ArtFrame.LINK_CORNER + 2 * ArtFrame.LINK_EDGE_HALF < edge)
    }

    @Test
    fun framesSitInsideTheCardAndInsideTheFourPerCentBorder() {
        for (f in listOf(ArtFrame.STANDARD, ArtFrame.PENDULUM, ArtFrame.LINK)) {
            assertTrue(f.left > 0.04f && f.right < 0.96f, "$f")
            assertTrue(f.top > 0.04f * cardRatio && f.bottom < 1f - 0.04f * cardRatio, "$f")
            assertTrue(f.bevel in 0.005f..0.02f)
        }
    }
}
