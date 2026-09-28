package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeskTouchTest {

    /**
     * The actions the card viewer — opened by a long press — offers besides its own:
     * the side deck, another copy, reading. So a finger reaches them through it.
     */
    private val throughTheViewer = setOf(MouseAction.ADD_TO_SIDE, MouseAction.ADD_COPY, MouseAction.INSPECT)

    @Test
    fun nothingAMouseCanDoIsOutOfAFingersReach() {
        for (target in MouseTarget.entries) {
            val mouse = DeskMouse.all.filter { it.target == target }.map { it.action }.toSet()
            val touch = DeskTouch.all.filter { it.target == target }.map { it.action }.toSet()
            assertTrue(MouseAction.VIEW in touch, "$target: a long press must open the viewer")
            val missing = mouse - touch - throughTheViewer
            assertTrue(missing.isEmpty(), "$target: no finger does $missing")
        }
    }

    @Test
    fun theDoubleTapIsTheRightClick() {
        for (target in MouseTarget.entries) {
            assertEquals(DeskMouse.resolve(target, MouseGesture.RIGHT_CLICK), DeskTouch.resolve(target, TouchGesture.DOUBLE_TAP))
        }
    }

    @Test
    fun noGestureMeansTwoThings() {
        DeskTouch.all.groupBy { it.target to it.gesture }.forEach { (key, rows) ->
            assertEquals(1, rows.size, "$key is bound twice")
        }
    }

    @Test
    fun thePoolScrollsUnderAFingerThatRunsAlongIt() {
        assertFalse(DeskTouch.picksUp(MouseTarget.POOL, dx = 4f, dy = 20f))
        assertTrue(DeskTouch.picksUp(MouseTarget.POOL, dx = 20f, dy = 4f))
        assertTrue(DeskTouch.picksUp(MouseTarget.DECK, dx = 4f, dy = 20f))
    }

    @Test
    fun draftingNeverResolvesARemoval() {
        assertEquals(MouseAction.REMOVE, DeskTouch.resolve(MouseTarget.DECK, TouchGesture.DOUBLE_TAP))
        TouchGesture.entries.forEach { g ->
            assertTrue(DeskTouch.resolve(MouseTarget.DECK, g, drafting = true) != MouseAction.REMOVE, "$g removes while drafting")
        }
        assertEquals(MouseAction.SELECT, DeskTouch.resolve(MouseTarget.DECK, TouchGesture.DOUBLE_TAP, drafting = true))
        // The pool is not being drafted from: its double-tap still adds.
        assertEquals(MouseAction.ADD, DeskTouch.resolve(MouseTarget.POOL, TouchGesture.DOUBLE_TAP, drafting = true))
    }

    @Test
    fun theChipWaitsOutTheDoubleTap() {
        assertTrue(DeskTouch.CHIP_DELAY_MS > DeskTouch.DOUBLE_TAP_MS)
    }
}
