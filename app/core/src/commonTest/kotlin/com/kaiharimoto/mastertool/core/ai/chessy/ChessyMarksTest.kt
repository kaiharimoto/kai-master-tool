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

    @Test
    fun asleepHerZzzDriftsAtItsOwnPaceOnSlowSteps() {
        // asleep she is stepped about every 115 ms: her marks' clock must keep time all the same
        val every = ChessyMarks().apply { show(Expression.SLEEPING) }
        val slow = ChessyMarks().apply { show(Expression.SLEEPING) }
        repeat(6 * 60) { every.step(1 / 60f) }
        repeat(6000 / 115) { slow.step(.115f) }
        slow.step((6f - 6000 / 115 * .115f))
        val a = every.top
        val b = slow.top
        assertTrue(a.size == b.size && a.size > 0, "zzz ${a.size} against ${b.size}")
        for (i in 0 until a.size) assertTrue(abs(a[i].y - b[i].y) < 8f, "zed $i at ${a[i].y} against ${b[i].y}")
    }
}
