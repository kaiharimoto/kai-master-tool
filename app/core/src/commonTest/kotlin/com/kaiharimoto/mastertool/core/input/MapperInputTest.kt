package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapperInputTest {
    @Test
    fun everyMouseActionHasAFingersFormOnTheSameTarget() {
        val mouse = MapperMouse.all.map { it.target to it.action }.toSet()
        val finger = MapperTouch.all.map { it.target to it.action }.toSet()
        assertTrue((mouse - finger).isEmpty(), "No finger's form for ${mouse - finger}")
    }

    @Test
    fun noGestureMeansTwoThings() {
        for (table in listOf(MapperMouse.all, MapperTouch.all)) {
            table.groupBy { it.target to it.gesture }.forEach { (key, rows) -> assertEquals(1, rows.size, "$key is bound ${rows.size} times") }
        }
    }

    @Test
    fun theKeysReachWhatTheMouseDoesAndPageTenHasItsOwn() {
        val keys = DeskShortcuts.all.filter { it.scope == DeskScope.MAPPER }.map { it.action }.toSet()
        listOf(DeskAction.MAPPER_PREV, DeskAction.MAPPER_NEXT, DeskAction.MAPPER_REPLAY, DeskAction.MAPPER_RUN, DeskAction.MAPPER_STOP).forEach {
            assertTrue(it in keys, "$it has no key")
        }
        val page = DeskContext(onMapper = true, onBuilder = false)
        assertEquals(DeskAction.GO_MAPPER, DeskShortcuts.resolve(KeyChord("m", ctrl = true, shift = true), DeskContext()))
        assertEquals(DeskAction.MAPPER_NEXT, DeskShortcuts.resolve(KeyChord("down"), page))
        assertEquals(null, DeskShortcuts.resolve(KeyChord("r"), page.copy(overlayOpen = true)))
        // On the builder (its rail open over page 10) the mapper's keys are not live.
        assertEquals(null, DeskShortcuts.resolve(KeyChord("r"), page.copy(onBuilder = true)))
    }
}
