package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Her half-lids (round two of the rig red team): the lash comes down from where it sits open to where kai's lid rests. */
class ChessyLidsTest {
    private val pic = Pic(0, 0, 10, 10, "x.webp")
    private val lid = HalfLid(pic, pic, x0 = 100, dx = 4, top = listOf(900f, 896f, 894f, 896f, 900f), bottom = listOf(900f, 920f, 934f, 920f, 902f))

    @Test
    fun openTheLashStaysShutItRestsOnTheLid() {
        for (x in listOf(100f, 106f, 108f, 113f, 116f)) {
            assertEquals(0f, ChessyLids.drop(lid, x, 1f), 1e-4f)
            val shut = ChessyLids.cut(lid, x, 0f)
            assertTrue(shut >= ChessyLids.cut(lid, x, 1f), "shut is never above open at $x")
        }
        // shut, the middle's lash lies on kai's lid
        assertEquals(934f, ChessyLids.cut(lid, 108f, 0f), 1e-3f)
        // half way, half way
        assertEquals(894f + 20f, ChessyLids.cut(lid, 108f, .5f), 1e-3f)
    }

    @Test
    fun theLashComesDownSteadilyAndHoldsPastItsEnds() {
        var last = -1f
        for (i in 10 downTo 0) {
            val d = ChessyLids.drop(lid, 108f, i / 10f)
            assertTrue(d >= last, "the drop only grows as she closes")
            last = d
        }
        // between columns it is interpolated; past the ends, the end's
        assertEquals((920f - 896f + 934f - 894f) / 2f, ChessyLids.drop(lid, 106f, 0f), 1e-3f)
        assertEquals(ChessyLids.drop(lid, 100f, 0f), ChessyLids.drop(lid, 40f, 0f), 1e-4f)
        assertEquals(ChessyLids.drop(lid, 116f, 0f), ChessyLids.drop(lid, 400f, 0f), 1e-4f)
    }

    @Test
    fun kaisLidFadesInOnlyNearShut() {
        assertEquals(0f, ChessyLids.lidFade(.5f))
        assertEquals(0f, ChessyLids.lidFade(ChessyLids.FADE_BELOW))
        assertEquals(1f, ChessyLids.lidFade(0f))
        assertTrue(ChessyLids.drawn(.5f) && !ChessyLids.drawn(1f))
    }

    @Test
    fun aPackWithoutHalfLidsReadsAsBeforeAndOneWithThemListsTheirPictures() {
        val old = """{"eyes":{},"mouths":{},"lids":{},"brows":{}}"""
        assertTrue(ChessyParts.read(old).halfLids.isEmpty())
        val now = """{"eyes":{},"mouths":{},"lids":{},"brows":{},"halfLids":{"sly":{"l":{"skin":{"x":1,"y":2,"w":3,"h":4,"file":"s.webp"},
            "lash":{"x":1,"y":2,"w":3,"h":4,"file":"l.webp"},"x0":250,"dx":4,"top":[900.5,901],"bottom":[930,931.5]}}}}"""
        val parts = ChessyParts.read(now)
        assertEquals(listOf("s.webp", "l.webp"), parts.files)
        assertEquals(931.5f, parts.halfLid("sly", "l")!!.bottom[1])
        assertEquals(null, parts.halfLid("wide", "l"))
    }

    @Test
    fun thePupilIsPushedUnderTheLashNotCovered() {
        val slide = PupilSlide(pic, pic, cx = 108f, top = 900f, bottom = 930f, floor = 950f)
        val withPupil = lid.copy(pupil = slide)
        // open: the lash (894 at the middle) is above the pupil's top
        assertEquals(0f, ChessyLids.push(withPupil, 1f))
        // half way the lash is at 914: the pupil's top goes just under it
        assertEquals(914f + ChessyLids.PUPIL_GAP - 900f, ChessyLids.push(withPupil, .5f), 1e-3f)
        // nearly shut, never past its floor
        assertEquals(20f, ChessyLids.push(withPupil, 0f), 1e-3f)
        // without a pupil, nothing is pushed
        assertEquals(0f, ChessyLids.push(lid, 0f))
    }
}
