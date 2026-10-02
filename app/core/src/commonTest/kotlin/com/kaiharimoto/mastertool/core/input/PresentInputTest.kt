package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertTrue

class PresentInputTest {
    @Test
    fun everyMouseActionHasAFingersForm() {
        val mouse = PresentMouse.all.map { it.target to it.action }.toSet()
        val finger = PresentTouch.all.map { it.target to it.action }.toSet()
        val missing = mouse - finger
        assertTrue(missing.isEmpty(), "No finger's form for $missing")
    }

    @Test
    fun noGestureMeansTwoThings() {
        for (table in listOf(PresentMouse.all, PresentTouch.all)) {
            table.groupBy { it.target to it.gesture }.forEach { (key, rows) ->
                assertTrue(rows.size == 1, "$key is bound ${rows.size} times")
            }
        }
    }

    @Test
    fun theMenuIsReachableEverywhereItIsOffered() {
        val mouseMenus = PresentMouse.all.filter { it.action == PresentAction.MENU }.map { it.target }.toSet()
        val fingerMenus = PresentTouch.all.filter { it.action == PresentAction.MENU }.map { it.target }.toSet()
        assertTrue(mouseMenus == fingerMenus)
    }
}
