package com.kaiharimoto.mastertool.core.world.desk

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeskPlacerTest {
    private fun near(a: Double, b: Double) = assertTrue(abs(a - b) < 1e-6, "$a ≠ $b")
    private fun inside(r: DeskRect, a: DeskRect) =
        assertTrue(r.x >= a.x - 1e-6 && r.y >= a.y - 1e-6 && r.right <= a.right + 1e-6 && r.bottom <= a.bottom + 1e-6, "$r is not inside $a")

    @Test
    fun comfortSizesAreFractionsOfTheWorkArea() {
        val work = DeskRect(0.0, 0.0, 2000.0, 1000.0)
        BuiltInApp.entries.forEach { app ->
            val s = DeskPlacer.comfort(app.ref, work)
            near(app.comfortW * 2000, s.w)
            near(app.comfortH * 1000, s.h)
        }
        // The spec's numbers, as written.
        assertEquals(0.64 to 0.86, BuiltInApp.BROWSER.comfortW to BuiltInApp.BROWSER.comfortH)
        assertEquals(0.52 to 0.72, BuiltInApp.EDITOR.comfortW to BuiltInApp.EDITOR.comfortH)
        assertEquals(0.48 to 0.38, BuiltInApp.TERMINAL.comfortW to BuiltInApp.TERMINAL.comfortH)
        assertEquals(0.26 to 0.62, BuiltInApp.FILES.comfortW to BuiltInApp.FILES.comfortH)
        assertEquals(0.32 to 0.86, BuiltInApp.THOUGHTS.comfortW to BuiltInApp.THOUGHTS.comfortH)
        assertEquals(0.44 to 0.66, BuiltInApp.INSTRUMENTS.comfortW to BuiltInApp.INSTRUMENTS.comfortH)
        assertEquals(0.64 to 0.86, BuiltInApp.LIBRARY.comfortW to BuiltInApp.LIBRARY.comfortH)
    }

    @Test
    fun anAppsOwnSizeIsHeldToItsBoundsAndTheWorkArea() {
        val big = DeskRect(0.0, 0.0, 4000.0, 2000.0)
        assertEquals(DeskSize(1200.0, 900.0), DeskPlacer.comfort(AppRef.Made("x"), big, DeskSize(5000.0, 5000.0)))
        assertEquals(DeskSize(320.0, 240.0), DeskPlacer.comfort(AppRef.Made("x"), big, DeskSize(10.0, 10.0)))
        assertEquals(DeskSize(600.0, 400.0), DeskPlacer.comfort(AppRef.Made("x"), big, DeskSize(600.0, 400.0)))
        val small = DeskRect(0.0, 0.0, 700.0, 500.0)
        assertEquals(DeskSize(700.0, 500.0), DeskPlacer.comfort(AppRef.Made("x"), small, DeskSize(1000.0, 800.0)))
    }

    @Test
    fun theCascadeStaysInsideTheWorkArea() {
        val area = DeskArea(DeskRect(0.0, 48.0, 1920.0, 990.0), iconColumns = 200.0)
        var last: DeskRect? = null
        for (n in 0 until 20) {
            val r = DeskPlacer.place(BuiltInApp.TERMINAL.ref, area, n)
            inside(r, area.work)
            if (last != null && n % DeskPlacer.SLOTS != 0) {
                near(last.x + DeskPlacer.CASCADE, r.x)
                near(last.y + DeskPlacer.CASCADE, r.y)
            }
            last = r
        }
        // The first slot stands in from the work area's corner, right of the icons.
        val first = DeskPlacer.place(BuiltInApp.FILES.ref, area, 0)
        near(area.work.x + DeskPlacer.INSET, first.x)
    }

    @Test
    fun clampedOnANetbook() {
        // 1024 × 600, less the bar and the taskbar: every window still opens whole, at least its smallest size.
        val area = DeskArea(DeskRect(0.0, 48.0, 1024.0, 508.0), iconColumns = 104.0)
        (BuiltInApp.entries.map { it.ref } + AppRef.Made("x")).forEach { app ->
            for (n in 0 until DeskPlacer.SLOTS) {
                val r = DeskPlacer.place(app, area, n, DeskSize(1200.0, 900.0))
                inside(r, area.work)
                assertTrue(r.w >= DeskPlacer.MIN.w - 1e-6 && r.h >= DeskPlacer.MIN.h - 1e-6, "$app at $n: $r")
            }
        }
    }

    @Test
    fun aFrameRoundTripsThroughFractions() {
        val area = DeskArea(DeskRect(10.0, 48.0, 1600.0, 900.0), iconColumns = 120.0)
        val r = DeskRect(300.0, 200.0, 640.0, 480.0)
        val back = area.rect(area.frame(r))
        near(r.x, back.x)
        near(r.y, back.y)
        near(r.w, back.w)
        near(r.h, back.h)
        // The same frame on a bigger display is the same share of it.
        val big = DeskArea(DeskRect(0.0, 0.0, 3200.0, 1800.0))
        near(area.frame(r).w * 3200, big.rect(area.frame(r)).w)
    }
}
