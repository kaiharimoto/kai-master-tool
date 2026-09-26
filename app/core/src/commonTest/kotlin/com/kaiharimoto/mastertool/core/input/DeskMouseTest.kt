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
    fun everyTargetCanBeReadPickedUpAndAskedForItsMenu() {
        for (target in MouseTarget.entries) {
            for (action in listOf(MouseAction.INSPECT, MouseAction.SELECT, MouseAction.MENU, MouseAction.PICK_UP)) {
                assertTrue(DeskMouse.gestureFor(target, action) != null, "$target has no gesture for $action")
            }
        }
    }

    @Test
    fun kaisBrief() {
        // Right-click adds, wherever the card is: into the deck from the pool,
        // another copy on a deck card.
        assertEquals(MouseAction.ADD, DeskMouse.resolve(MouseTarget.POOL, MouseGesture.RIGHT_CLICK))
        assertEquals(MouseAction.ADD_COPY, DeskMouse.resolve(MouseTarget.DECK, MouseGesture.RIGHT_CLICK))
        // Holding left opens the menu, on every card.
        MouseTarget.entries.forEach { assertEquals(MouseAction.MENU, DeskMouse.resolve(it, MouseGesture.HOLD)) }
        // Left drag picks up, both places.
        MouseTarget.entries.forEach { assertEquals(MouseAction.PICK_UP, DeskMouse.resolve(it, MouseGesture.DRAG)) }
    }

    @Test
    fun shiftMeansTheSideOrTheOtherWay() {
        assertEquals(MouseAction.ADD_TO_SIDE, DeskMouse.resolve(MouseTarget.POOL, MouseGesture.SHIFT_RIGHT_CLICK))
        assertEquals(MouseAction.REMOVE, DeskMouse.resolve(MouseTarget.DECK, MouseGesture.SHIFT_RIGHT_CLICK))
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
}
