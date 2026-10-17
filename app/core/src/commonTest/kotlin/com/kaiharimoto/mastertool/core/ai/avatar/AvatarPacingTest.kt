package com.kaiharimoto.mastertool.core.ai.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ai's face left alone steps less often (the performance pass, 2026-10, the red team: it asked for every frame for ever,
 * calm or asleep): [AvatarRig.lively] says when the next frame is wanted soon, and every blink, turn and new face is.
 */
class AvatarPacingTest {
    private val frame = 1f / 60f

    @Test
    fun asleepItSettlesCalm() {
        val rig = AvatarRig(seed = 3)
        rig.show(Expression.SLEEPING)
        repeat((AvatarRig.FACE_SETTLE / frame).toInt() + 2) { rig.step(frame) }
        repeat(600) {
            rig.step(frame)
            assertFalse(rig.lively, "asleep, a step was lively")
        }
    }

    @Test
    fun everyBlinkAndNewFaceIsLively() {
        val rig = AvatarRig(seed = 5)
        var calm = 0
        var steps = 0
        var shut = 0
        repeat(60 * 60) {
            rig.step(frame)
            steps++
            if (!rig.lively) calm++
            if (!rig.lively && rig.blinkShut > 0f) shut++
        }
        assertEquals(0, shut, "a blink was stepped as calm")
        assertTrue(calm > 0, "left alone, never calm ($steps steps)")
        rig.show(Expression.DONE)
        rig.step(frame)
        assertTrue(rig.lively, "a new face eases in every frame")
        // a brisk face stays lively
        repeat(600) { rig.step(frame); assertTrue(rig.lively) }
    }

    @Test
    fun aMovedPointerIsLively() {
        val rig = AvatarRig(seed = 9)
        repeat(300) { rig.step(frame, 400f, 0f, 100f) }
        rig.step(frame, -400f, 50f, 100f)
        assertTrue(rig.lively)
    }
}
