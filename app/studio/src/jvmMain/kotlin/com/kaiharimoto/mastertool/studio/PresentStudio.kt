package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.edit.EditorEdits
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.SlideZoom
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.Box
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.present.PropsTab

/**
 * The Present editor's states the audit's studio could not reach (track B), set on the real holder:
 *
 * - `--present-tab=SLIDE|ELEMENT|ANIMATE|DECK|THEME` the panel's tab;
 * - `--present-select=N` the Nth thing on the slide in view selected (0 the first);
 * - `--present-camera=custom` the slide's camera picked in a box of its own (`=big` is set before, a Big camera slide);
 * - `--present-insert=row|chart|table|number` something inserted as the bar inserts it, on the stage beside the camera;
 * - `--present-zoom=2` the slide zoomed round its middle;
 * - `--present-picks=1,2,3` slides picked in the sorter, the first in view;
 * - `--present-section=N` slide N's section started and being named;
 * - `--present-confirm=groups` "Slides from groups" asking first;
 * - `--present-more=true` the bar's ⋯ menu open.
 */
internal suspend fun studioEditor(h: NeueHolders, map: Map<String, String>, clock: FrameClock) {
    val present = h.present
    fun open() = present.open
    map["present-picks"]?.let { spec ->
        val p = open() ?: return@let
        val ids = spec.split(",").mapNotNull { it.trim().toIntOrNull() }.mapNotNull { p.slides.getOrNull(it)?.id }
        if (ids.isNotEmpty()) {
            present.slideId = ids.first()
            present.slidesPicked = ids.drop(1).toSet()
            present.sorterFocused = true
        }
    }
    map["present-section"]?.toIntOrNull()?.let { n ->
        val p = open() ?: return@let
        val s = p.slides.getOrNull(n) ?: return@let
        present.commit(PresentEdits.updateSlide(p, s.id) { it.copy(section = "Combo lines") }, "Section")
        present.namingSection = s.id
    }
    map["present-insert"]?.let { what ->
        val p = open() ?: return@let
        val slide = present.slide ?: return@let
        val cards = p.deck?.main?.distinct()?.take(5).orEmpty()
        val base = when (what) {
            "row" -> Element(PresentIds.next("e"), Element.CARDS, 0f, 0f, 1600f, 620f, cards = cards, cardLabels = true)
            "chart" -> Element(PresentIds.next("e"), Element.CHART, 0f, 0f, 1200f, 600f, chart = com.kaiharimoto.mastertool.core.present.Placeholders.chart)
            "table" -> Element(PresentIds.next("e"), Element.TABLE, 0f, 0f, 1200f, 420f, table = com.kaiharimoto.mastertool.core.present.Placeholders.table)
            else -> Element(PresentIds.next("e"), Element.STAT, 0f, 0f, 800f, 420f, stat = com.kaiharimoto.mastertool.core.present.Placeholders.stat)
        }
        val b = EditorEdits.placeIn(CompiledShow(p).stage(present.slideIndex), base.w, base.h)
        val e = base.copy(x = b.x, y = b.y, w = b.w, h = b.h)
        present.commit(PresentEdits.addElements(p, slide.id, listOf(e)), "Add")
        present.selection = setOf(e.id)
        present.tab = PropsTab.ELEMENT
        println("[neue-studio] present: inserted $what at ${b.x.toInt()},${b.y.toInt()} ${b.w.toInt()}×${b.h.toInt()}, camera ${CompiledShow(present.open!!).zone(present.slideIndex)}")
    }
    map["present-select"]?.toIntOrNull()?.let { n ->
        val s = present.slide ?: return@let
        s.elements.getOrNull(n)?.let { present.selection = setOf(it.id) }
    }
    if (map["present-camera"] == "custom") {
        val p = open()
        val s = present.slide
        if (p != null && s != null) {
            val box = SlideCamera.zone(p, s) ?: Box(1300f, 600f, 512f, 288f)
            present.commit(PresentEdits.updateSlide(p, s.id) { SlideCamera.moved(it, box.copy(x = 140f, y = 120f)) }, "Camera")
            present.selection = emptySet()
            present.cameraPicked = true
            present.tab = PropsTab.ELEMENT
            println("[neue-studio] present: camera ${present.slide?.camera} at ${present.slide?.cameraBox}")
        }
    }
    map["present-tab"]?.let { t -> PropsTab.entries.firstOrNull { it.name == t.uppercase() }?.let { present.tab = it } }
    map["present-confirm"]?.let { if (it == "groups") present.confirmFromGroups = true }
    clock.run(30)
    map["present-zoom"]?.toFloatOrNull()?.let { z ->
        present.zoom = SlideZoom.FIT.zoomAround(z, present.viewWidth / 2f, present.viewHeight / 2f, present.viewWidth, present.viewHeight)
        println("[neue-studio] present: zoom ${present.zoom.percent}% in a ${present.viewWidth.toInt()}×${present.viewHeight.toInt()} view")
    }
    if (map["present-more"] == "true") present.moreTools = true
    clock.run(40)
    val p = open()
    if (p != null) println("[neue-studio] present: slide ${present.slideIndex + 1} of ${p.slides.size}, camera ${present.slide?.camera ?: Slide.CAMERA_DEFAULT}, picked ${present.pickedSlides.size}, selection ${present.selection.size}")
}
