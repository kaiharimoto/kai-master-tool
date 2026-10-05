package com.kaiharimoto.mastertool.core.world.desk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapZonesTest {
    private val full = DeskRect(0.0, 48.0, 1600.0, 800.0)

    @Test
    fun eachEdgeAndCorner() {
        assertEquals(Snap.LEFT, SnapZones.zone(DeskPoint(3.0, 400.0), full))
        assertEquals(Snap.RIGHT, SnapZones.zone(DeskPoint(1599.0, 400.0), full))
        assertEquals(Snap.TOP, SnapZones.zone(DeskPoint(800.0, 50.0), full))
        assertEquals(Snap.TOP_LEFT, SnapZones.zone(DeskPoint(1.0, 49.0), full))
        assertEquals(Snap.TOP_RIGHT, SnapZones.zone(DeskPoint(1597.0, 52.0), full))
        assertEquals(Snap.BOTTOM_LEFT, SnapZones.zone(DeskPoint(2.0, 846.0), full))
        assertEquals(Snap.BOTTOM_RIGHT, SnapZones.zone(DeskPoint(1595.0, 845.0), full))
        // The bottom edge alone is no zone: the taskbar is there.
        assertNull(SnapZones.zone(DeskPoint(800.0, 847.0), full))
    }

    @Test
    fun theBandIsEightDp() {
        assertEquals(Snap.LEFT, SnapZones.zone(DeskPoint(8.0, 400.0), full))
        assertNull(SnapZones.zone(DeskPoint(8.5, 400.0), full))
        assertEquals(Snap.RIGHT, SnapZones.zone(DeskPoint(1592.0, 400.0), full))
        assertNull(SnapZones.zone(DeskPoint(1591.5, 400.0), full))
        assertEquals(Snap.TOP, SnapZones.zone(DeskPoint(800.0, 56.0), full))
        assertNull(SnapZones.zone(DeskPoint(800.0, 56.5), full))
        assertNull(SnapZones.zone(DeskPoint(800.0, 400.0), full))
    }

    @Test
    fun theFramesTileTheDesktop() {
        assertEquals(DeskRect(0.0, 48.0, 800.0, 800.0), SnapZones.frame(Snap.LEFT, full))
        assertEquals(DeskRect(800.0, 48.0, 800.0, 800.0), SnapZones.frame(Snap.RIGHT, full))
        assertEquals(full, SnapZones.frame(Snap.TOP, full))
        assertEquals(DeskRect(0.0, 48.0, 800.0, 400.0), SnapZones.frame(Snap.TOP_LEFT, full))
        assertEquals(DeskRect(800.0, 448.0, 800.0, 400.0), SnapZones.frame(Snap.BOTTOM_RIGHT, full))
    }

    @Test
    fun aDragOffASnapRestores() {
        val area = DeskArea(full, 100.0)
        var d = Desk().reduce(DeskOp.Open(BuiltInApp.TERMINAL.ref, at = 1), area)
        val normal = d.window("terminal")!!.frame
        d = d.reduce(DeskOp.DragEnd("terminal", DeskPoint(1600.0, 400.0)), area)
        assertEquals(Snap.RIGHT, d.window("terminal")!!.snap)
        assertEquals(normal, d.window("terminal")!!.frame, "the normal frame is kept while snapped")
        d = d.reduce(DeskOp.DragStart("terminal", DeskPoint(1000.0, 60.0), at = 2), area)
        assertEquals(WindowMode.NORMAL, d.window("terminal")!!.mode)
        assertEquals(normal.w, d.window("terminal")!!.frame.w, 1e-9)
    }
}
