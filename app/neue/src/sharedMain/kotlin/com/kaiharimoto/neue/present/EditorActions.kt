package com.kaiharimoto.neue.present

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.RichText
import com.kaiharimoto.mastertool.core.present.edit.SlideClip
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.platform.Platform

/*
 * What the editor's keys, menus and buttons do (1.0.70): one function each, so `Ctrl C`, the
 * right-click menu and (phase 2) Ai do the same thing to the same presentation.
 */

internal fun selected(h: NeueHolders): List<Element> {
    val s = h.present.slide ?: return emptyList()
    return s.elements.filter { it.id in h.present.selection }
}

internal fun copy(h: NeueHolders) {
    val present = h.present
    val els = selected(h)
    if (els.isNotEmpty()) {
        present.clipboard = els
        present.clipboardSlides = emptyList()
        Platform.copy(SlideClip.elements(els))
        h.neue.note = Note(if (els.size == 1) "Copied" else "Copied ${els.size}")
    } else {
        val s = present.slide ?: return
        present.clipboardSlides = listOf(s)
        present.clipboard = emptyList()
        Platform.copy(SlideClip.slides(listOf(s)))
        h.neue.note = Note("Copied the slide")
    }
}

internal fun cut(h: NeueHolders) {
    copy(h)
    if (h.present.selection.isNotEmpty()) deleteSelection(h)
}

/** What was copied, onto the slide in view: elements nudged so they show, or slides after it. */
internal fun paste(h: NeueHolders) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    if (present.clipboard.isNotEmpty()) {
        val groups = HashMap<String, String>()
        val sameSlide = present.clipboard.all { c -> slide.element(c.id) != null }
        val copies = present.clipboard.map { e ->
            PresentEdits.freshElement(e, groups).let {
                if (!sameSlide) it else if (it.anchor == Element.ANCHOR_STAGE) it.copy(x = it.x + 0.02f, y = it.y + 0.02f) else it.copy(x = it.x + 24f, y = it.y + 24f)
            }
        }
        present.commit(PresentEdits.addElements(p, slide.id, copies), "Paste")
        present.selection = copies.map { it.id }.toSet()
        present.clipboard = copies
    } else if (present.clipboardSlides.isNotEmpty()) {
        var next = p
        var after = present.slideIndex
        val fresh = present.clipboardSlides.map { PresentEdits.fresh(it) }
        fresh.forEach { s -> next = PresentEdits.addSlide(next, s, after); after++ }
        present.commit(next, "Paste slides")
        present.slideId = fresh.last().id
    }
}

internal fun duplicate(h: NeueHolders) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    if (present.selection.isNotEmpty()) {
        val (next, ids) = PresentEdits.duplicateElements(p, slide.id, present.selection)
        present.commit(next, "Duplicate")
        present.selection = ids.toSet()
    } else {
        val next = PresentEdits.duplicateSlides(p, setOf(slide.id))
        present.commit(next, "Duplicate slide")
        present.slideId = next.slides.getOrNull(next.indexOf(slide.id) + 1)?.id
    }
}

internal fun deleteSelection(h: NeueHolders) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    if (present.selection.isNotEmpty()) {
        present.commit(PresentEdits.removeElements(p, slide.id, present.selection), "Delete")
        present.selection = emptySet()
    } else if (p.slides.size > 1) {
        val at = present.slideIndex
        val next = PresentEdits.removeSlides(p, setOf(slide.id))
        present.commit(next, "Delete slide")
        present.slideId = next.slides.getOrNull(at.coerceAtMost(next.slides.lastIndex))?.id
    }
}

internal fun selectAll(h: NeueHolders) {
    h.present.selection = h.present.slide?.elements?.map { it.id }?.toSet().orEmpty()
}

internal fun reorder(h: NeueHolders, how: String) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    if (present.selection.isEmpty()) return
    present.commit(PresentEdits.order(p, slide.id, present.selection, how), "Order")
}

internal fun group(h: NeueHolders, on: Boolean) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    if (present.selection.isEmpty()) return
    present.commit(if (on) PresentEdits.group(p, slide.id, present.selection) else PresentEdits.ungroup(p, slide.id, present.selection), if (on) "Group" else "Ungroup")
}

internal fun setLocked(h: NeueHolders, locked: Boolean) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    present.commit(PresentEdits.updateElements(p, slide.id, present.selection) { it.copy(locked = locked) }, if (locked) "Lock" else "Unlock")
}

/** The selection a step along, or with nothing selected the slide before or after. */
internal fun nudge(h: NeueHolders, dx: Float, dy: Float) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    if (present.selection.isEmpty()) {
        val step = if (dx + dy > 0) 1 else -1
        p.slides.getOrNull(present.slideIndex + step)?.let { present.slideId = it.id }
        return
    }
    val show = CompiledShow(p)
    val stage = show.stage(present.slideIndex)
    val next = PresentEdits.updateElements(p, slide.id, present.selection) { e ->
        if (e.locked) e else {
            val b = Geometry.box(e, stage)
            Geometry.place(e, b.copy(x = b.x + dx, y = b.y + dy), stage)
        }
    }
    present.commit(next, "Nudge", coalesce = "nudge")
}

/**
 * Bold, italic or underline: on the words selected while editing in place, else on every word
 * of the selected elements. Already on everywhere it applies, it comes off.
 */
internal fun textStyle(h: NeueHolders, change: (RunStyle, Boolean) -> RunStyle, on: (RunStyle) -> Boolean) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    val editing = present.editingText
    if (editing != null) {
        val e = slide.element(editing) ?: return
        val sel = present.textSelection
        val (start, end) = if (sel.collapsed) 0 to RichText.flatten(e.paras).first.length else sel.min to sel.max
        val already = on(RichText.styleOf(e.paras, start, end))
        present.commit(PresentEdits.updateElements(p, slide.id, setOf(e.id)) { it.copy(paras = RichText.restyle(it.paras, start, end) { st -> change(st, !already) }) }, "Style")
        return
    }
    val targets = selected(h).filter { it.paras.isNotEmpty() }
    if (targets.isEmpty()) return
    val already = targets.all { e -> e.paras.all { pa -> pa.runs.all { on(it.style) } } }
    present.commit(
        PresentEdits.updateElements(p, slide.id, targets.map { it.id }.toSet()) { e ->
            e.copy(paras = e.paras.map { pa -> pa.copy(runs = pa.runs.map { r -> r.copy(style = change(r.style, !already)) }) })
        },
        "Style",
    )
}

internal fun bold(h: NeueHolders) = textStyle(h, { s, v -> s.copy(weight = if (v) 700 else 400) }, { (it.weight ?: 0) >= 700 })
internal fun italic(h: NeueHolders) = textStyle(h, { s, v -> s.copy(italic = v) }, { it.italic })
internal fun underline(h: NeueHolders) = textStyle(h, { s, v -> s.copy(underline = v) }, { it.underline })
