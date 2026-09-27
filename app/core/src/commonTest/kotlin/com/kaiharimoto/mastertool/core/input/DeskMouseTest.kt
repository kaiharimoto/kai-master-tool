package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskMouseTest {

    @Test
    fun noGestureMeansTwoThingsOnOneTarget() {
        DeskMouse.all.groupBy { it.target to it.gesture }.forEach { (key, rows) ->
            assertEquals(1, rows.size, "$key is bound ${rows.size} times")
        }
    }

    @Test
    fun everyTargetCanBeReadPickedUpAndOpened() {
        for (target in MouseTarget.entries) {
            for (action in listOf(MouseAction.INSPECT, MouseAction.SELECT, MouseAction.VIEW, MouseAction.PICK_UP)) {
                assertTrue(DeskMouse.gestureFor(target, action) != null, "$target has no gesture for $action")
            }
        }
    }

    @Test
    fun kaisBrief() {
        // 1.0.10: "hold left click should open the inspector, hold right click
        // duplicates. just right click removes the card from the deck."
        MouseTarget.entries.forEach { assertEquals(MouseAction.VIEW, DeskMouse.resolve(it, MouseGesture.HOLD)) }
        assertEquals(MouseAction.ADD, DeskMouse.resolve(MouseTarget.POOL, MouseGesture.RIGHT_HOLD))
        assertEquals(MouseAction.ADD_COPY, DeskMouse.resolve(MouseTarget.DECK, MouseGesture.RIGHT_HOLD))
        assertEquals(MouseAction.REMOVE, DeskMouse.resolve(MouseTarget.DECK, MouseGesture.RIGHT_CLICK))
        // A right-click from the pool still adds.
        assertEquals(MouseAction.ADD, DeskMouse.resolve(MouseTarget.POOL, MouseGesture.RIGHT_CLICK))
        // Left drag picks up, both places.
        MouseTarget.entries.forEach { assertEquals(MouseAction.PICK_UP, DeskMouse.resolve(it, MouseGesture.DRAG)) }
    }

    @Test
    fun shiftMeansTheSideOrTheOtherWay() {
        assertEquals(MouseAction.ADD_TO_SIDE, DeskMouse.resolve(MouseTarget.POOL, MouseGesture.SHIFT_RIGHT_CLICK))
        assertEquals(MouseAction.ADD_COPY, DeskMouse.resolve(MouseTarget.DECK, MouseGesture.SHIFT_RIGHT_CLICK))
    }

    @Test
    fun aHoldOutlastsADoubleClick() {
        // Otherwise the second press of a double-click could be read as a hold.
        assertTrue(DeskMouse.HOLD_MS > DeskMouse.DOUBLE_CLICK_MS)
    }

    @Test
    fun descriptionsAreSentenceCaseWithoutFullStops() {
        DeskMouse.all.forEach {
            assertTrue(it.description.first().isUpperCase() && !it.description.endsWith("."), it.description)
        }
    }

    @Test
    fun theSideSwitchTradesThePoolsTwoAdds() {
        assertEquals(MouseAction.ADD_TO_SIDE, DeskMouse.forPool(MouseAction.ADD, sideFirst = true))
        assertEquals(MouseAction.ADD, DeskMouse.forPool(MouseAction.ADD_TO_SIDE, sideFirst = true))
        assertEquals(MouseAction.VIEW, DeskMouse.forPool(MouseAction.VIEW, sideFirst = true))
        MouseAction.entries.forEach { assertEquals(it, DeskMouse.forPool(it, sideFirst = false)) }
    }
}
