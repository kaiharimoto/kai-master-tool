package com.kaiharimoto.mastertool.core.present

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B8: a chart's series can be told apart in every theme, Master UI's above all. */
class ChartInksTest {
    private fun bg(t: Theme) = SlideColor.argb("@bg", t)!!

    @Test
    fun fourSeriesCanBeToldApartInEveryTheme() {
        for (t in Themes.all + Themes.all.map { it.with(ThemeOverride(flat = !it.flat)) }) {
            val inks = ChartInks.of(t)
            val four = (0 until 4).map { ChartInks.at(inks, it) }
            for (i in four.indices) {
                assertTrue(ChartInks.visible(four[i].argb, bg(t)), "${t.name}: series ${i + 1} on the background")
                for (j in i + 1 until four.size) assertTrue(ChartInks.apart(four[i], four[j]), "${t.name}: series ${i + 1} and ${j + 1}")
            }
            // Six apart before any repeats.
            val six = (0 until 6).map { ChartInks.at(inks, it) }
            for (i in six.indices) for (j in i + 1 until six.size) assertTrue(ChartInks.apart(six[i], six[j]) || six[i] == six[j], "${t.name}: ${i + 1}, ${j + 1}")
        }
    }

    @Test
    fun masterUiIsThreeStepsOfItsRampThenHatching() {
        val inks = ChartInks.of(Themes.of(Themes.MASTER))
        assertEquals(listOf(false, false, false, true), inks.take(4).map { it.hatched })
        assertEquals(SlideColor.argb("@text", Themes.of(Themes.MASTER)), inks[0].argb, "the first is the ink itself")
        val dark = ChartInks.of(Themes.of(Themes.MASTER_DARK))
        assertTrue(dark.take(3).zipWithNext().all { (a, b) -> ChartInks.apart(a, b) })
    }

    @Test
    fun accentsThatMatchAreNotUsedTwice() {
        // The person recoloured Accent 3 to Accent's colour: the chart takes the next ink that differs.
        val t = Themes.of(Themes.ARENA).with(ThemeOverride(colors = mapOf("accent3" to "#F5B82E")))
        val inks = ChartInks.of(t).filter { !it.hatched }
        assertTrue(inks.zipWithNext().all { (a, b) -> ChartInks.apart(a, b) })
        assertTrue(inks.size >= 3)
    }

    @Test
    fun groupsKeepTheirColoursUnlessTwoAreTheSame() {
        val t = Themes.of(Themes.MASTER)
        val palette = mapOf(0 to 0xFFE5383BL, 1 to 0xFF3A86FFL)
        val inks = ChartInks.forGroups(3, listOf(0, 1, 0), { palette.getValue(it) }, ChartInks.of(t), bg(t))
        assertEquals(0xFFE5383BL, inks[0].argb)
        assertEquals(0xFF3A86FFL, inks[1].argb)
        assertTrue(ChartInks.apart(inks[2], inks[0]) && ChartInks.apart(inks[2], inks[1]), "the repeat takes an ink of its own")
        // No groups: the chart's own inks.
        assertEquals(ChartInks.of(t).take(2), ChartInks.forGroups(2, null, { 0L }, ChartInks.of(t), bg(t)))
    }
}
