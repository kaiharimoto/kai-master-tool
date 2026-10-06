package com.kaiharimoto.mastertool.core.ai.chessy

/**
 * Her eyes part-way shut (round two of the rig red team): the irises stay where kai painted them, the lid comes down
 * over them. At opening `open` (1 open, 0 shut) the open eye's lash moves down by [drop] at each column, from where it
 * sits open ([HalfLid.top]) toward where kai's closed lid rests ([HalfLid.bottom]), and the lid's skin covers the eye
 * above [cut], the lash's new lower edge. Near shut the drawing fades into kai's own lid ([lidFade]).
 */
object ChessyLids {
    /** Below this opening kai's closed lid fades in over the half-lid, whole at 0. */
    const val FADE_BELOW = .15f

    /** At or above this the eye is drawn as painted, no lid at all. */
    const val OPEN_FROM = .98f

    /** How far the lash has come down at sheet column [x], at opening [open]. Past either end, the end's. */
    fun drop(h: HalfLid, x: Float, open: Float): Float {
        val o = open.coerceIn(0f, 1f)
        return (1f - o) * (at(h.bottom, h, x) - at(h.top, h, x))
    }

    /** Where the lid's skin ends at sheet column [x]: the lash's lower edge, moved down. */
    fun cut(h: HalfLid, x: Float, open: Float): Float = at(h.top, h, x) + drop(h, x, open)

    /** How far under the lash's lower edge a pushed pupil's top sits (sheet px). */
    const val PUPIL_GAP = 3f

    /**
     * How far the lid pushes the pupil down at [open] (sheet px): none while the lash is above it; then enough to keep
     * its top [PUPIL_GAP] under the lash, never past its floor (from there the lid covers it, as a closing eye does).
     */
    fun push(h: HalfLid, open: Float): Float {
        val p = h.pupil ?: return 0f
        val need = cut(h, p.cx, open) + PUPIL_GAP - p.top
        return need.coerceIn(0f, (p.floor - p.bottom).coerceAtLeast(0f))
    }

    /** How much of kai's closed lid shows over the half-lid at [open]: none above [FADE_BELOW], all at 0. */
    fun lidFade(open: Float): Float = (1f - open / FADE_BELOW).coerceIn(0f, 1f)

    /** Whether the half-lid is drawn at all at [open]. */
    fun drawn(open: Float): Boolean = open < OPEN_FROM

    private fun at(ys: List<Float>, h: HalfLid, x: Float): Float {
        if (ys.isEmpty()) return 0f
        val u = ((x - h.x0) / h.dx).coerceIn(0f, (ys.size - 1).toFloat())
        val i = u.toInt().coerceAtMost(ys.size - 2).coerceAtLeast(0)
        if (ys.size == 1) return ys[0]
        val f = u - i
        return ys[i] + (ys[i + 1] - ys[i]) * f
    }
}
