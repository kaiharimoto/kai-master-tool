package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Her moods change without a pop (the rig red team): parts cross-fade, brows and ears travel. */
class ChessyMoodBlendTest {
    @Test
    fun noMoodChangeJumpsAndEveryOneArrives() {
        for (a in Expression.entries) for (b in Expression.entries) {
            if (a == b) continue
            val m = ChessyMoodBlend().apply { show(a); step(.016f) }
            m.show(b)
            var tilt = m.browTilt
            var lift = m.browLiftL
            var ears = m.ears
            var faded = -1
            var lastFrom = m.fromAlpha
            var lastTo = m.toAlpha
            repeat(60) { i ->
                m.step(.016f)
                // a frame moves the brows a few degrees or pixels at most, never the whole way
                assertTrue(abs(m.browTilt - tilt) < 4.5f, "${a.id}→${b.id} tilt ${tilt}→${m.browTilt}")
                assertTrue(abs(m.browLiftL - lift) < 6.5f, "${a.id}→${b.id} lift ${lift}→${m.browLiftL}")
                assertTrue(abs(m.ears - ears) < 9f, "${a.id}→${b.id} ears ${ears}→${m.ears}")
                assertTrue(abs(m.fromAlpha - lastFrom) <= .3f && abs(m.toAlpha - lastTo) <= .3f, "${a.id}→${b.id} a part popped")
                tilt = m.browTilt; lift = m.browLiftL; ears = m.ears; lastFrom = m.fromAlpha; lastTo = m.toAlpha
                if (faded < 0 && m.mix >= 1f) faded = i
            }
            assertTrue(faded in 0..9, "${a.id}→${b.id} the fade took ${faded + 1} frames")
            val goal = ChessyMoods.of(b)
            assertEquals(goal.browTilt, m.browTilt, .1f)
            assertEquals(goal.ears, m.ears, .1f)
            assertTrue(!m.busy, "${a.id}→${b.id} still busy after a second")
        }
    }

    @Test
    fun theEarsOvershootAHairAsTheyDrop() {
        val m = ChessyMoodBlend().apply { show(Expression.SURPRISED); step(.016f) }
        m.show(Expression.CRYING)
        var low = 0f
        repeat(60) { m.step(.016f); low = minOf(low, m.ears) }
        val goal = ChessyMoods.of(Expression.CRYING).ears
        assertTrue(low < goal && low > goal - 2.5f, "lowest $low against $goal")
    }

    @Test
    fun aChangeMidFadeNeverJumpsAndStillChangesAtOnce() {
        val m = ChessyMoodBlend().apply { show(Expression.IDLE); step(.016f) }
        m.show(Expression.ANGRY)
        repeat(3) { m.step(.016f) }
        val tilt = m.browTilt
        m.show(Expression.SAD)
        m.step(.016f)
        assertTrue(abs(m.browTilt - tilt) < 4.5f)
        val still = ChessyMoodBlend(still = true).apply { show(Expression.IDLE); show(Expression.ANGRY) }
        assertEquals(1f, still.mix)
        assertEquals(ChessyMoods.of(Expression.ANGRY).browTilt, still.browTilt)
    }
}
