package com.kaiharimoto.mastertool.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GridStepTest {
    @Test
    fun alongARowAndThroughItsEnds() {
        assertEquals(4, GridStep.move(3, 40, 10, StepDirection.RIGHT))
        assertEquals(10, GridStep.move(9, 40, 10, StepDirection.RIGHT))
        assertEquals(9, GridStep.move(10, 40, 10, StepDirection.LEFT))
        assertNull(GridStep.move(0, 40, 10, StepDirection.LEFT))
        assertNull(GridStep.move(39, 40, 10, StepDirection.RIGHT))
    }

    @Test
    fun upAndDownARow() {
        assertEquals(13, GridStep.move(3, 40, 10, StepDirection.DOWN))
        assertEquals(3, GridStep.move(13, 40, 10, StepDirection.UP))
        assertNull(GridStep.move(3, 40, 10, StepDirection.UP))
        assertNull(GridStep.move(33, 40, 10, StepDirection.DOWN))
    }

    @Test
    fun downIntoAShortLastRowLandsOnItsLastCard() {
        assertEquals(41, GridStep.move(38, 42, 10, StepDirection.DOWN))
        assertEquals(40, GridStep.move(30, 42, 10, StepDirection.DOWN))
    }
}
