package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The position glyphs (D.md §5¾.9): line drawings on a 24 grid whose ink lands on whole pixels at 16, 20 and 24, a stroke
 * of 1 px at 16 and 2 at 32 — the hollow window face-up, the solid fill the back.
 */
class PositionGlyphsTest {
    private val positions = CardPosition.entries

    private fun whole(v: Float) = kotlin.math.abs(v - kotlin.math.round(v)) < 1e-4f

    @Test
    fun theInkLandsOnWholePixelsAtSixteenTwentyAndTwentyFour() {
        for (px in listOf(16f, 20f, 24f)) for (p in positions) {
            val marks = PositionGlyphs.pixels(p, px)
            assertTrue(marks.isNotEmpty(), "$p at $px")
            marks.forEach { m ->
                assertTrue(listOf(m.l, m.t, m.r, m.b).all(::whole), "$p at $px: $m is not on whole pixels")
                assertTrue(m.w >= 1f && m.h >= 1f, "$p at $px: $m is at least a pixel")
                assertTrue(m.l >= 0f && m.t >= 0f && m.r <= px && m.b <= px, "$p at $px: $m stays inside the glyph")
            }
        }
    }

    @Test
    fun theStrokeIsOnePixelAtSixteenAndTwoAtThirtyTwo() {
        assertEquals(1f, PositionGlyphs.stroke(16f))
        assertEquals(2f, PositionGlyphs.stroke(32f))
        assertEquals(2f, PositionGlyphs.stroke(24f))
        // Every outline's four edges are the stroke thick.
        val atk = PositionGlyphs.pixels(CardPosition.FACE_UP_ATK, 16f)
        assertEquals(1f, atk.first().h)
    }

    @Test
    fun attackStandsUpAndDefenseAndSetLieAcross() {
        fun bounds(p: CardPosition, px: Float): PositionGlyphs.Px {
            val m = PositionGlyphs.pixels(p, px)
            return PositionGlyphs.Px(m.minOf { it.l }, m.minOf { it.t }, m.maxOf { it.r }, m.maxOf { it.b })
        }
        for (px in listOf(16f, 20f, 24f, 30f)) {
            val atk = bounds(CardPosition.FACE_UP_ATK, px)
            val def = bounds(CardPosition.FACE_UP_DEF, px)
            val set = bounds(CardPosition.FACE_DOWN_DEF, px)
            assertTrue(atk.h > atk.w, "Attack stands up at $px")
            assertTrue(def.w > def.h, "Defense lies across at $px")
            assertTrue(set.w > set.h, "Set lies across at $px")
        }
    }

    @Test
    fun theHollowWindowIsFaceUpAndTheSolidFillIsTheBack() {
        fun fills(p: CardPosition) = PositionGlyphs.of(p).count { it.kind == PositionGlyphs.Kind.FILL }
        assertEquals(0, fills(CardPosition.FACE_UP_ATK))
        assertEquals(0, fills(CardPosition.FACE_UP_DEF))
        assertEquals(1, fills(CardPosition.FACE_DOWN_DEF))
        assertEquals(1, fills(CardPosition.FACE_DOWN_ATK))
        // A glyph never stands alone: each has its word and its key.
        assertEquals(listOf("Attack", "Defense", "Set"), listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF, CardPosition.FACE_DOWN_DEF).map(PositionGlyphs::word))
        assertEquals(listOf("A", "D", "E"), listOf(CardPosition.FACE_UP_ATK, CardPosition.FACE_UP_DEF, CardPosition.FACE_DOWN_DEF).map(PositionGlyphs::key))
    }

    @Test
    fun theGridIsTheMockupsOwn() {
        val atk = PositionGlyphs.of(CardPosition.FACE_UP_ATK)
        assertEquals(PositionGlyphs.Part(PositionGlyphs.Kind.OUTLINE, 6.75f, 2.75f, 10.5f, 18.5f), atk[0])
        val set = PositionGlyphs.of(CardPosition.FACE_DOWN_DEF)
        assertEquals(PositionGlyphs.Part(PositionGlyphs.Kind.FILL, 5f, 9f, 14f, 6f), set[1])
    }
}
