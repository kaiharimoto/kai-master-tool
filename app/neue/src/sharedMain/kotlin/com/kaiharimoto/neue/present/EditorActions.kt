package com.kaiharimoto.neue.present

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.RunStyle
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.edit.EditorEdits
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.stage.Box as CanvasBox
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
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
        val p = present.open ?: return
        // The slides picked in the sorter, or the one in view.
        val slides = present.pickedSlides.mapNotNull { p.slide(it) }.ifEmpty { listOfNotNull(present.slide) }
        if (slides.isEmpty()) return
        present.clipboardSlides = slides
        present.clipboard = emptyList()
        Platform.copy(SlideClip.slides(slides))
        h.neue.note = Note(if (slides.size == 1) "Copied the slide" else "Copied ${slides.size} slides")
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
        // A finger's Alt drag: the copy is selected, and the next drag carries it.
        present.justDuplicated = true
    } else {
        val picked = present.pickedSlides.toSet().ifEmpty { setOf(slide.id) }
        val next = PresentEdits.duplicateSlides(p, picked)
        present.commit(next, if (picked.size == 1) "Duplicate slide" else "Duplicate ${picked.size} slides")
        present.slideId = next.slides.getOrNull(next.indexOf(slide.id) + 1)?.id
        present.slidesPicked = emptySet()
    }
}

/**
 * Delete: the selection on the slide; the camera picked (it is hidden on this slide); or, only when the
 * sorter was pressed last, the slides picked there (the editor's audit, R1: one Backspace after a click
 * on the canvas used to take the whole slide).
 */
internal fun deleteSelection(h: NeueHolders) {
    val present = h.present
    val p = present.open ?: return
    val slide = present.slide ?: return
    when {
        present.selection.isNotEmpty() -> {
            present.commit(PresentEdits.removeElements(p, slide.id, present.selection), "Delete")
            present.selection = emptySet()
        }
        present.cameraPicked -> {
            present.commit(PresentEdits.updateSlide(p, slide.id) { it.copy(camera = Slide.CAMERA_HIDDEN, cameraBox = null) }, "Hide the camera here")
            present.cameraPicked = false
            h.neue.note = Note("The camera is hidden on this slide: the Slide tab brings it back")
        }
        present.sorterFocused -> deleteSlides(h)
        else -> h.neue.note = Note("Select something to delete, or a slide in the list")
    }
}

/** The slides picked in the sorter, or the one in view; a presentation keeps one slide. */
internal fun deleteSlides(h: NeueHolders) {
    val present = h.present
    val p = present.open ?: return
    val ids = present.pickedSlides.toSet().ifEmpty { setOfNotNull(present.slide?.id) }
    if (ids.isEmpty()) return
    if (ids.size >= p.slides.size) {
        h.neue.note = Note("A presentation keeps one slide")
        return
    }
    val at = present.slideIndex
    val next = PresentEdits.removeSlides(p, ids)
    present.commit(next, if (ids.size == 1) "Delete slide" else "Delete ${ids.size} slides")
    present.slideId = next.slides.getOrNull(at.coerceAtMost(next.slides.lastIndex))?.id
    present.slidesPicked = emptySet()
}

/**
 * Where something [w] × [h] (canvas units) is put on the slide in view: on its stage, the room the
 * camera leaves, centred and shrunk to fit (the editor's audit, I5) — never under the camera.
 */
internal fun insertBox(h: NeueHolders, w: Float, hh: Float): CanvasBox {
    val present = h.present
    val p = present.open ?: return CanvasBox(0f, 0f, w, hh)
    return EditorEdits.placeIn(CompiledShow(p).stage(present.slideIndex), w, hh)
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
    if (present.selection.isEmpty() && present.cameraPicked) {
        val zone = SlideCamera.zone(p, slide) ?: return
        present.commit(PresentEdits.updateSlide(p, slide.id) { SlideCamera.moved(it, zone.copy(x = zone.x + dx, y = zone.y + dy)) }, "Move the camera", coalesce = "nudge")
        return
    }
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
