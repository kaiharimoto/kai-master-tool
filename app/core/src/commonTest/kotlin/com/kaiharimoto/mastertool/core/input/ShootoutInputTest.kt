package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertTrue

class ShootoutInputTest {
    @Test
    fun everyMouseActionHasAFingersForm() {
        val mouse = ShootoutMouse.all.map { it.action }.toSet()
        val finger = ShootoutTouch.all.map { it.action }.toSet()
        assertTrue((mouse - finger).isEmpty(), "No finger's form for ${mouse - finger}")
        // Answering, choosing and reading are reached on every target the mouse reaches them on.
        val mouseTargets = ShootoutMouse.all.map { it.target to it.action }.toSet()
        val fingerTargets = ShootoutTouch.all.map { it.target to it.action }.toSet()
        assertTrue((mouseTargets - fingerTargets).isEmpty(), "No finger's form for ${mouseTargets - fingerTargets}")
    }

    @Test
    fun noGestureMeansTwoThings() {
        for (table in listOf(ShootoutMouse.all, ShootoutTouch.all)) {
            table.groupBy { it.target to it.gesture }.forEach { (key, rows) ->
                assertTrue(rows.size == 1, "$key is bound ${rows.size} times")
            }
        }
    }

    @Test
    fun theKeysAnswerEveryWayAMouseDoes() {
        val keys = DeskShortcuts.all.filter { it.scope == DeskScope.SHOOTOUT }.map { it.action }.toSet()
        listOf(
            DeskAction.SHOOTOUT_ANSWER_1, DeskAction.SHOOTOUT_ANSWER_2, DeskAction.SHOOTOUT_ANSWER_3,
            DeskAction.SHOOTOUT_ANSWER_4, DeskAction.SHOOTOUT_ANSWER_5, DeskAction.SHOOTOUT_LEFT, DeskAction.SHOOTOUT_RIGHT,
        ).forEach { assertTrue(it in keys, "$it has no key") }
    }
}
