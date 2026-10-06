package com.kaiharimoto.mastertool.core.ai.chessy

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ChessyMarksTest {
    private fun worn(e: Expression, seconds: Float = 2f): ChessyMarks = ChessyMarks().apply {
        show(e)
        repeat((seconds * 60).toInt()) { step(1 / 60f) }
    }

    private fun MarkList.has(shape: MarkShape) = (0 until size).any { this[it].shape == shape }

    @Test
    fun eachMoodShowsItsMarks() {
        assertTrue(worn(Expression.SLEEPING).top.has(MarkShape.ZED))
        assertTrue(worn(Expression.CRYING).fx.has(MarkShape.DROP))
        assertTrue(worn(Expression.ANGRY).fx.has(MarkShape.ANGER))
        assertTrue(worn(Expression.LOVE).fx.has(MarkShape.HEART))
        assertTrue(worn(Expression.SURPRISED).fx.has(MarkShape.EXCLAIM))
        assertTrue(worn(Expression.WAITING).fx.has(MarkShape.QUESTION))
        assertTrue(worn(Expression.DELIGHTED).top.has(MarkShape.STAR))
        val idle = worn(Expression.IDLE)
        assertTrue(idle.fx.size == 0 && idle.top.size == 0)
    }

    @Test
    fun theMarksStayRoundHerAndTheHeadMovesALittle() {
        for (e in Expression.entries) {
            val m = worn(e, 3f)
            for (list in listOf(m.fx, m.top)) for (i in 0 until list.size) {
                val k = list[i]
                assertTrue(k.x in -100f..1420f && k.y in 0f..1740f, "${e.id} ${k.shape} at ${k.x},${k.y}")
                assertTrue(k.alpha in 0f..1f && k.scale > 0f, e.id)
            }
            assertTrue(abs(m.bx) < 80f && abs(m.by) < 80f && abs(m.rot) < 15f, "${e.id} moves too far")
            assertTrue(m.sx in .9f..1.1f && m.sy in .9f..1.1f, e.id)
        }
    }

    @Test
    fun stillShowsTheStillPose() {
        val m = ChessyMarks(still = true).apply { show(Expression.SLEEPING); step(1 / 60f) }
        val again = ChessyMarks(still = true).apply { show(Expression.SLEEPING); repeat(90) { step(1 / 60f) } }
        assertTrue(m.by == again.by && m.rot == again.rot)
    }
}
