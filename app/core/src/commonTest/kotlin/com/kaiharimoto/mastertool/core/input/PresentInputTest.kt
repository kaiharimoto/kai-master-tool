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

    /**
     * The gesture's words as the facts of a press: what a person doing what the help dialog says would
     * produce. A row whose words this cannot read fails, so a new row needs a new reading here and a
     * case in [PresentGestures.classify] — which the canvas and the sorter act on.
     */
    private fun facts(b: PresentBinding, finger: Boolean): PresentPress {
        val g = b.gesture.lowercase()
        var p = PresentPress(b.target, finger = finger)
        var understood = false
        fun has(word: String) = (word in g).also { if (it) understood = true }
        if (has("right-click")) p = p.copy(secondary = true)
        if (has("shift")) p = p.copy(shift = true)
        if (has("ctrl")) p = p.copy(ctrl = true)
        if (has("alt")) p = p.copy(alt = true)
        if (has("double")) p = p.copy(taps = 2)
        if (has("press and hold")) p = p.copy(held = true)
        if (has("drag") || has("move")) p = p.copy(moved = true)
        if (has("wheel")) p = p.copy(wheel = true)
        if (has("pinch")) p = p.copy(fingers = 2, spread = true, moved = true)
        if (has("two-finger")) p = p.copy(fingers = 2)
        if (has("round handle")) p = p.copy(roundHandle = true)
        if (has("corner")) p = p.copy(corner = true)
        if (has("select several on")) p = p.copy(selectSeveral = true)
        if (has("keep shape on")) p = p.copy(keepShape = true)
        if (has("duplicate, then")) p = p.copy(afterDuplicate = true)
        if (has("by its number")) p = p.copy(onGrip = true)
        if (has("laser on")) p = p.copy(laser = true)
        if (has("left half")) p = p.copy(leftHalf = true)
        if (has("right half")) p = p.copy(leftHalf = false)
        if (g == "click" || g == "tap") understood = true
        assertTrue(understood, "No reading of \"${b.gesture}\"")
        return p
    }

    @Test
    fun theCanvasDoesWhatEveryRowSays() {
        for ((table, finger) in listOf(PresentMouse.all to false, PresentTouch.all to true)) {
            for (b in table) {
                val got = PresentGestures.classify(facts(b, finger))
                assertTrue(got == b.action, "${if (finger) "finger" else "mouse"}: ${b.target} \"${b.gesture}\" does $got, the table says ${b.action}")
            }
        }
    }

    @Test
    fun aFingerDraggingTheSorterScrollsItUnlessItHoldsTheGrip() {
        assertTrue(PresentGestures.classify(PresentPress(PresentTarget.SORTER, finger = true, moved = true)) == null)
        assertTrue(PresentGestures.classify(PresentPress(PresentTarget.SORTER, finger = true, moved = true, onGrip = true)) == PresentAction.REORDER)
        assertTrue(PresentGestures.classify(PresentPress(PresentTarget.SORTER, moved = true)) == PresentAction.REORDER)
        // A click on the bare slide does nothing of its own: the selection is let go.
        assertTrue(PresentGestures.classify(PresentPress(PresentTarget.CANVAS)) == null)
    }

    @Test
    fun theMenuIsReachableEverywhereItIsOffered() {
        val mouseMenus = PresentMouse.all.filter { it.action == PresentAction.MENU }.map { it.target }.toSet()
        val fingerMenus = PresentTouch.all.filter { it.action == PresentAction.MENU }.map { it.target }.toSet()
        assertTrue(mouseMenus == fingerMenus)
    }
}
