package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Chessy left alone is calm most of the time (the performance pass, kai: "my hardware was lagging quite badly when
 * chessy was live, especially if left on for long periods"): her frame loop steps every frame only while she is lively,
 * so an idle minute must be mostly calm, and every blink, ear and word must still be lively.
 */
class ChessyPacingTest {
    @Test
    fun leftAloneSheIsMostlyCalmButEveryBlinkIsDrawnWhole() {
        val rig = ChessyRig(seed = 5)
        var lively = 0
        var steps = 0
        var blinkCalm = 0
        repeat(60_000 / 16) {
            val f = rig.step(16f, null, null, talking = false)
            steps++
            if (f.lively) lively++
            if (f.blink && !f.lively) blinkCalm++
        }
        println("CHESSY PACING: lively ${lively * 100 / steps}% idle")
        assertTrue(lively < steps * .3, "idle, lively ${lively * 100 / steps}% of the time")
        assertTrue(blinkCalm == 0, "a blink is always lively")
    }

    @Test
    fun aLookOrWordsAreLively() {
        val rig = ChessyRig(seed = 5)
        repeat(200) { rig.step(16f, null, null, false) }
        // the pointer jumps across her: she turns to it, lively
        assertTrue(rig.step(16f, .9f, 0f, false).lively)
        // talking is lively throughout
        repeat(30) { assertTrue(rig.step(16f, null, null, talking = true).lively) }
    }

    @Test
    fun aCalmStepIsTheSameMotionDrawnLessOften() {
        val every = ChessyRig(seed = 9)
        val calm = ChessyRig(seed = 9)
        // ten seconds left alone: one stepped each 16 ms, one each 112 ms (a sleeping step and its frame)
        repeat(10_000 / 16) { every.step(16f, null, null, false) }
        repeat(10_000 / 112) { calm.step(112f, null, null, false) }
        val a = every.frame
        val b = calm.frame
        assertTrue(kotlin.math.abs(a.yaw - b.yaw) < .01f, "turn ${a.yaw} against ${b.yaw}")
        assertTrue(kotlin.math.abs(a.bob - b.bob) < .2f, "breath ${a.bob} against ${b.bob}")
        for (i in a.swingX.indices) assertTrue(kotlin.math.abs(a.swingX[i] - b.swingX[i]) < .5f, "swing $i ${a.swingX[i]} against ${b.swingX[i]}")
    }
}
