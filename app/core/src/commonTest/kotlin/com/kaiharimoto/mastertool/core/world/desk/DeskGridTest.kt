package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskGridTest {
    @Test
    fun theIconColumnsGrowWithAisApps() {
        val h = 940.0
        assertEquals(DeskGrid.INSET + DeskGrid.TILE_W, DeskGrid.width(0, h))
        assertEquals(DeskGrid.INSET + DeskGrid.TILE_W * 2 + DeskGrid.GAP, DeskGrid.width(3, h))
        val rows = DeskGrid.rows(h)
        assertEquals(3, DeskGrid.columns(rows + 1, rows), "a full column starts another")
        assertEquals(1, DeskGrid.rows(10.0), "never no rows")
    }

    @Test
    fun aWindowOpenedOnANewAppStandsClearOfItsColumn() {
        // The page last measured one column; Ai has since made an app. Measured from the apps there are now, the window
        // opens right of the new column, never on it.
        val stale = DeskArea(DeskRect(0.0, 0.0, 1872.0, 940.0), DeskGrid.width(0, 940.0))
        val now = DeskGrid.area(stale, apps = 1)
        val r = DeskPlacer.place(AppRef.Made("hand-odds"), now, cascade = 0, manifest = DeskSize(520.0, 620.0))
        assertTrue(r.x >= DeskGrid.width(1, 940.0), "x ${r.x}")
        val d = Desk().step(DeskOp.Open(AppRef.Made("hand-odds"), 1L, WorldEvent.YOU, size = DeskSize(520.0, 620.0)), now).desk
        assertTrue(d.window(AppRef.Made("hand-odds").key)!!.rect(now).x >= DeskGrid.width(1, 940.0))
    }
}
