package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowcaseTest {

    private val aspect = 813f / 1185f

    @Test
    fun theWholeCardFitsInTheMiddle() {
        for ((w, h) in listOf(1080f to 2400f, 2400f to 1080f, 1920f to 1080f)) {
            val p = Showcase.card(w, h, aspect)
            assertTrue(p.width <= w * Showcase.CARD_FILL + 0.5f && p.height <= h * Showcase.CARD_FILL + 0.5f, "$p in $w x $h")
            assertEquals(w / 2f, p.left + p.width / 2f, 0.5f)
            assertEquals(h / 2f, p.top + p.height / 2f, 0.5f)
            assertEquals(aspect, p.width / p.height, 0.001f)
        }
    }

    @Test
    fun theArtworkCoversTheScreenHoweverThePhoneIsTipped() {
        val frames = listOf("effect", "xyz", "link", "spell", "pendulum_effect").mapNotNull { ArtFrame.of(it) }
        for (frame in frames) for ((w, h) in listOf(1080f to 2400f, 2400f to 1080f)) {
            for (tx in listOf(-1f, 0f, 1f)) for (ty in listOf(-1f, 0f, 1f)) {
                val p = Showcase.art(frame, w, h, aspect, tx, ty)
                val artLeft = p.left + frame.left * p.width
                val artTop = p.top + frame.top * p.height
                val artRight = p.left + frame.right * p.width
                val artBottom = p.top + frame.bottom * p.height
                assertTrue(artLeft <= 0.5f && artTop <= 0.5f && artRight >= w - 0.5f && artBottom >= h - 0.5f, "$frame at ($tx, $ty) on $w x $h: $p")
            }
        }
    }

    @Test
    fun levelTheArtworksMiddleIsTheScreens() {
        val frame = ArtFrame.of("effect")!!
        val p = Showcase.art(frame, 1080f, 2400f, aspect, 0f, 0f)
        assertEquals(540f, p.left + (frame.left + frame.width / 2f) * p.width, 0.5f)
        assertEquals(1200f, p.top + (frame.top + frame.height / 2f) * p.height, 0.5f)
        // Tipped right, it slides the other way, as a picture behind the glass does.
        val tipped = Showcase.art(frame, 1080f, 2400f, aspect, 1f, 0f)
        assertTrue(tipped.left < p.left)
    }
}
