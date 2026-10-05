package com.kaiharimoto.mastertool.core.present.play

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.stage.Box
import kotlin.math.sqrt

/**
 * The Morph transition (1.1.x, the audit's M7: it was offered on every slide and played as a fade). An element
 * on the slide arriving that has a partner on the slide leaving travels from where its partner stood, growing or
 * shrinking into place; every other element fades — out with the slide leaving, in with the one arriving.
 *
 * Partners, in this order, each element of the leaving slide taken once: the same [Element.morphKey]; the same
 * id (a duplicated slide, or one edited from a copy); the same kind with the same words; a title for a title.
 * The travel is an [ElementState] — a shift and one scale, read in the element's layer — so a morph moves
 * pixels and never lays a slide out again.
 */
object Morph {
    /** The arriving element ids, each with its partner's box (leaving) and its own (arriving), in canvas units. */
    data class Partner(val from: String, val to: String, val fromBox: Box, val toBox: Box)

    fun pairs(leaving: Slide, leavingStage: Box, arriving: Slide, arrivingStage: Box): List<Partner> {
        val free = leaving.elements.toMutableList()
        val out = ArrayList<Partner>()
        fun take(e: Element, match: (Element) -> Boolean) {
            if (out.any { it.to == e.id }) return
            val partner = free.firstOrNull(match) ?: return
            free.remove(partner)
            out += Partner(partner.id, e.id, Geometry.box(partner, leavingStage), Geometry.box(e, arrivingStage))
        }
        val rules: List<(Element, Element) -> Boolean> = listOf(
            { a, b -> a.morphKey != null && a.morphKey == b.morphKey },
            { a, b -> a.id == b.id && a.type == b.type },
            { a, b -> a.type == b.type && a.plainText.isNotBlank() && a.plainText == b.plainText },
            { a, b -> a.type == Element.TEXT && b.type == Element.TEXT && a.role == Element.ROLE_TITLE && b.role == Element.ROLE_TITLE },
        )
        for (rule in rules) for (e in arriving.elements) take(e) { rule(it, e) }
        return out
    }

    /** Where an arriving element stands at eased progress [t] (0 where its partner was, 1 home). */
    fun travel(p: Partner, t: Float): ElementState {
        val k = 1f - t.coerceIn(0f, 1f)
        val a = p.fromBox
        val b = p.toBox
        val ratio = if (b.w > 0f && b.h > 0f && a.w > 0f && a.h > 0f) sqrt((a.w / b.w) * (a.h / b.h)) else 1f
        return ElementState(
            dx = (a.cx - b.cx) * k,
            dy = (a.cy - b.cy) * k,
            scale = 1f + (ratio - 1f) * k,
        )
    }
}
