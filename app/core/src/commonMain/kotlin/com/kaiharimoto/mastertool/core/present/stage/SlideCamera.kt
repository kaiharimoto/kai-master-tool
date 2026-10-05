package com.kaiharimoto.mastertool.core.present.stage

import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide

/**
 * The camera on one slide (the editor's audit, B5/B6). There is one camera and one zone: a slide moves
 * it — to a preset, or to a box of its own ([Slide.CAMERA_CUSTOM], [Slide.cameraBox]) — and the room
 * every stage-anchored element and the deck re-flow into follows it.
 *
 * Until now the Big camera layout and More ▾ → Camera added a Camera *element*: a second frame drawn
 * wherever it stood, while the real zone stayed in its corner (two cameras on a Big camera slide, and a
 * frame drawn with the webcam off). [fold] turns such an element into the slide's own box, so what was
 * meant is what is drawn.
 */
object SlideCamera {
    /** The Big camera layout's box: the face given most of the slide, the words beside it. */
    val BIG = Box(96f, 140f, 1100f, 800f)

    /** The smallest box a camera is dragged to, in canvas units. */
    const val MIN = 120f

    /** Where the camera stands on [slide] of [p], or null when it is off or hidden there. */
    fun zone(p: Presentation, slide: Slide): Box? = WebcamLayout.zone(p.webcam, slide.camera, slide.cameraBox)

    /** [slide] with its camera moved to [box], a box of its own. */
    fun moved(slide: Slide, box: Box): Slide = slide.copy(camera = Slide.CAMERA_CUSTOM, cameraBox = clamp(box))

    /** [box] kept on the canvas and no smaller than [MIN] a side. */
    fun clamp(box: Box): Box {
        val w = box.w.coerceIn(MIN, Box.CANVAS.w)
        val h = box.h.coerceIn(MIN, Box.CANVAS.h)
        return Box(box.x.coerceIn(0f, Box.CANVAS.w - w), box.y.coerceIn(0f, Box.CANVAS.h - h), w, h)
    }

    /**
     * Every Camera element of [p] made the slide's own camera box (the topmost one, where several stood),
     * and taken off the slide; [p] itself when there is none. Read on every file opened and every edit
     * (Ai's writes and a paste from an older build included), so a Camera element never draws again.
     */
    fun fold(p: Presentation): Presentation {
        if (p.slides.none { s -> s.elements.any { it.type == Element.CAMERA } }) return p
        return p.copy(slides = p.slides.map { fold(it) })
    }

    fun fold(slide: Slide): Slide {
        val cams = slide.elements.filter { it.type == Element.CAMERA }
        if (cams.isEmpty()) return slide
        // A stage-anchored camera was a fraction of the room beside the zone it was meant to replace:
        // read against the safe area, the whole of what it could have meant.
        val box = Geometry.box(cams.last(), WebcamLayout.safe)
        return moved(slide.copy(elements = slide.elements.filterNot { it.type == Element.CAMERA }), box)
    }
}
