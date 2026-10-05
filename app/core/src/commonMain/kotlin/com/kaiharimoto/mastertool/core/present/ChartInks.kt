package com.kaiharimoto.mastertool.core.present

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How one series (or one slice) of a chart is drawn: a colour, and whether it is hatched. */
data class ChartInk(val argb: Long, val hatched: Boolean = false)

/**
 * A chart's own palette (1.1.x, the audit's B8). Charts took `@accent` … `@accent4`, and in Master UI
 * `accent == accent3` (ink), so two series drew the same black and the legend repeated itself. Here:
 *
 * - **Master UI** (a [Theme.flat] theme): three steps of the theme's own ramp, from its text toward its
 *   background, solid; then the same steps hatched — the fourth series is the first step, hatched.
 * - **Every other theme**: its four accents, each kept only if it can be told from the ones before it and
 *   seen on the background; the ramp fills what the accents could not; then the same, hatched.
 *
 * Worked from the theme's colours, so the person's own recolouring is honoured, and [apart] is the one
 * test of "can be told apart" that the painter and `ChartInksTest` share.
 */
object ChartInks {
    /** Two solid inks a chart may stand side by side: this far apart in contrast, or in colour. */
    const val MIN_CONTRAST = 1.5
    const val MIN_DISTANCE = 90.0

    /** An ink must stand out from the background at least this much. */
    const val MIN_ON_BACKGROUND = 1.4

    /** Where the ramp's steps stand between the text (0) and the background (1). */
    private val RAMP = listOf(0f, 0.42f, 0.66f)

    /** The inks of [theme], in the order series take them: solid first, then hatched. */
    fun of(theme: Theme): List<ChartInk> {
        val bg = SlideColor.argb("@bg", theme) ?: 0xFF000000
        val text = SlideColor.argb("@text", theme) ?: 0xFFFFFFFF
        val ramp = RAMP.map { mix(text, bg, it) }
        val candidates = if (theme.flat) ramp else listOf("@accent", "@accent2", "@accent3", "@accent4").mapNotNull { SlideColor.argb(it, theme) } + ramp
        val solid = ArrayList<Long>()
        for (c in candidates) {
            val opaque = c or 0xFF000000
            if (!visible(opaque, bg)) continue
            if (solid.any { !apartSolid(it, opaque) }) continue
            solid += opaque
            if (solid.size == if (theme.flat) 3 else 4) break
        }
        if (solid.isEmpty()) solid += text or 0xFF000000
        return solid.map { ChartInk(it) } + solid.map { ChartInk(it, hatched = true) }
    }

    /** The ink of series [i] of [inks], round again once every ink is taken. */
    fun at(inks: List<ChartInk>, i: Int): ChartInk = inks[((i % inks.size) + inks.size) % inks.size]

    /** Whether [a] and [b] can be told apart on a chart: one hatched and the other not, or far enough apart. */
    fun apart(a: ChartInk, b: ChartInk): Boolean = a.hatched != b.hatched || apartSolid(a.argb, b.argb)

    /** Whether [ink] can be seen on the background [bg]. */
    fun visible(ink: Long, bg: Long): Boolean = SlideColor.contrast(ink, bg) >= MIN_ON_BACKGROUND || distance(ink, bg) >= MIN_DISTANCE

    private fun apartSolid(a: Long, b: Long): Boolean = SlideColor.contrast(a, b) >= MIN_CONTRAST || distance(a, b) >= MIN_DISTANCE

    /** How far apart two colours are in RGB, 0..441. */
    fun distance(a: Long, b: Long): Double {
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toDouble()
        val dr = ch(a, 16) - ch(b, 16)
        val dg = ch(a, 8) - ch(b, 8)
        val db = ch(a, 0) - ch(b, 0)
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** [a] moved [t] of the way to [b], opaque. */
    fun mix(a: Long, b: Long, t: Float): Long {
        fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toFloat()
        fun m(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000 or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /**
     * The inks for [labels] slices or bars when each is a deck group ([groups], colour indices) and
     * [groupColor] reads an index as a colour: each group in its own colour, unless that colour cannot be
     * told from one already used or seen on [bg] — then the chart's own next ink instead.
     */
    fun forGroups(labels: Int, groups: List<Int>?, groupColor: (Int) -> Long, inks: List<ChartInk>, bg: Long): List<ChartInk> {
        val out = ArrayList<ChartInk>(labels)
        var next = 0
        for (i in 0 until labels) {
            val g = groups?.getOrNull(i)?.let { ChartInk(groupColor(it) or 0xFF000000) }
            val ok = g != null && visible(g.argb, bg) && out.all { apart(it, g) }
            if (ok) {
                out += g!!
            } else {
                // The chart's own: the first of its inks not yet used and apart from every one used.
                var pick = at(inks, next)
                for (k in inks.indices) {
                    val candidate = at(inks, next + k)
                    if (out.all { apart(it, candidate) }) { pick = candidate; next += k; break }
                }
                next++
                out += pick
            }
        }
        return out
    }
}
