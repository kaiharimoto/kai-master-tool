package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.apps.AppKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldIconsTest {
    private val lo = WorldIcons.FIELD_MIN
    private val hi = WorldIcons.FIELD_MAX

    /** The points an arc passes through at its ends and at each quarter it crosses. */
    private fun arcExtent(a: IconShape.Arc): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var deg = a.start.toDouble()
        val end = a.start + a.sweep
        while (deg <= end + 1e-9) {
            val r = deg * kotlin.math.PI / 180
            out += (a.cx + a.r * kotlin.math.cos(r)) to (a.cy + a.r * kotlin.math.sin(r))
            deg += 1.0
        }
        return out
    }

    @Test
    fun everyIconIsInsideTheField() {
        WorldIcons.ALL.forEach { icon ->
            icon.shapes.forEach { s ->
                when (s) {
                    is IconShape.Path -> s.points.forEach { p -> assertTrue(p.x in lo..hi && p.y in lo..hi, "${icon.name}: $p") }
                    is IconShape.Box -> assertTrue(s.x >= lo && s.y >= lo && s.x + s.w <= hi && s.y + s.h <= hi, "${icon.name}: $s")
                    is IconShape.Arc -> arcExtent(s).forEach { (x, y) -> assertTrue(x >= lo - 1e-9 && x <= hi + 1e-9 && y >= lo - 1e-9 && y <= hi + 1e-9, "${icon.name}: $x,$y") }
                }
            }
        }
    }

    @Test
    fun nothingUnderTwoUnits() {
        WorldIcons.ALL.forEach { icon ->
            icon.shapes.forEach { s ->
                when (s) {
                    is IconShape.Box -> assertTrue(s.w >= 2 && s.h >= 2, "${icon.name}: $s")
                    is IconShape.Path -> {
                        assertTrue(s.points.size >= 2, "${icon.name}: a path of one point")
                        s.points.zipWithNext().forEach { (a, b) -> assertTrue(a != b, "${icon.name}: a zero-length stroke at $a") }
                    }
                    is IconShape.Arc -> assertTrue(s.r >= 2 && s.sweep in 1..360, "${icon.name}: $s")
                }
            }
        }
    }

    @Test
    fun namesAreUniqueAndEveryKindHasAGlyph() {
        assertEquals(WorldIcons.ALL.size, WorldIcons.ALL.map { it.name }.toSet().size)
        BoardKind.entries.forEach { k -> assertTrue(WorldIcons.page(k) in WorldIcons.ALL, "$k has a page glyph") }
        assertEquals(WorldIcons.PAGE_MARKDOWN, WorldIcons.page(null), "a newer kind draws as markdown")
        AppKind.entries.forEach { k -> assertTrue(WorldIcons.defaultGlyph(k) in WorldIcons.GLYPHS, "$k has a default glyph") }
        BuiltInApp.entries.forEach { assertTrue(WorldIcons.builtIn(it) in WorldIcons.ALL) }
        assertEquals(15, WorldIcons.GLYPHS.size)
        WorldIcons.GLYPHS.values.forEach { assertTrue(it in WorldIcons.ALL) }
    }

    @Test
    fun theSpecsNumbers() {
        // §7.2, number for number: a few that a careless edit would move.
        assertEquals(IconShape.Box(17, 20, 2, 5, filled = true), WorldIcons.EDITOR.shapes.last())
        assertEquals(IconShape.Arc(16, 22, 11, 180, 180), WorldIcons.INSTRUMENTS.shapes.first())
        assertEquals(IconShape.Box(12, 5, 5, 21, filled = true), WorldIcons.LIBRARY.shapes[1])
        assertEquals(IconShape.Box(5, 7, 22, 18), WorldIcons.TERMINAL.shapes.first())
    }

    @Test
    fun aTileIsAGlyphAndAMonogram() {
        assertEquals("HO", WorldIcons.monogram("Hand odds"))
        assertEquals("MT", WorldIcons.monogram("Matchup tracker"))
        assertEquals("CL", WorldIcons.monogram("Combo lines", ""))
        assertEquals("X9", WorldIcons.monogram("Anything", "x9!"))
        assertEquals("AB", WorldIcons.monogram("Anything", "abc"))
        assertEquals("AI", WorldIcons.monogram("—"))
        assertEquals(WorldIcons.GLYPH_VERSUS, WorldIcons.tile("Matchup tracker", AppKind.TRACKER, "versus", "MT").glyph)
        assertEquals(WorldIcons.GLYPH_TALLY, WorldIcons.tile("Tracker", AppKind.TRACKER, "hologram", null).glyph, "an unknown glyph is the kind's")
        assertEquals(WorldIcons.PAGE_STAT, WorldIcons.tile("Hand odds", AppKind.CALCULATOR, null, null).glyph)
    }
}
