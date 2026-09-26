package com.kaiharimoto.mastertool.core.prefs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeuePreferencesTest {

    @Test
    fun defaultsAreAlreadySanitised() {
        assertEquals(NeuePreferences.DEFAULT, NeuePreferences.DEFAULT.sanitised())
    }

    @Test
    fun paperIsTheDefaultTheme() {
        // Master UI §1 law 7: light is default; dark is the exact inversion.
        assertEquals(NeueTheme.PAPER, NeuePreferences.DEFAULT.theme)
    }

    @Test
    fun zoomStepsAlongTheScaleAndStopsAtTheEnds() {
        val start = NeuePreferences.DEFAULT
        assertEquals(1.125f, start.zoomedIn().scale)
        assertEquals(0.875f, start.zoomedOut().scale)
        assertEquals(0.875f, start.zoomedOut().zoomedOut().scale)
        var up = start
        repeat(20) { up = up.zoomedIn() }
        assertEquals(NeuePreferences.SCALES.last(), up.scale)
    }

    @Test
    fun anOffStepScaleSnapsToItsNeighbour() {
        val odd = NeuePreferences.DEFAULT.copy(scale = 1.3f)
        assertEquals(1.5f, odd.zoomedIn().scale)
        assertEquals(1.25f, odd.zoomedOut().scale)
    }

    @Test
    fun sanitisingRepairsWhatADiskCanHoldAndALayoutCannotUse() {
        val broken = NeuePreferences(
            scale = Float.NaN,
            poolWidth = 5f,
            inspectorWidth = Float.POSITIVE_INFINITY,
            poolColumns = -3,
            foil = " ",
            window = WindowBounds(0f, 0f, 200f, 100f),
        ).sanitised()
        assertEquals(1f, broken.scale)
        assertEquals(NeuePreferences.MIN_POOL_WIDTH, broken.poolWidth)
        assertEquals(NeuePreferences.DEFAULT_INSPECTOR_WIDTH, broken.inspectorWidth)
        assertEquals(0, broken.poolColumns)
        assertEquals(NeuePreferences.DEFAULT_FOIL, broken.foil)
        // A window smaller than the minimum is a window that cannot be used, so it is forgotten.
        assertNull(broken.window)
    }
}
