package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.edit.EditorEdits
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.SlidePicks
import com.kaiharimoto.mastertool.core.present.edit.SlideZoom
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.stage.Box
import com.kaiharimoto.mastertool.core.present.stage.SlideCamera
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's audit, track B: what the editor does to a presentation, held in core. */
class PresentEditorTest {

    private val deck = DeckSnapshot(
        deckId = "d1", name = "Test",
        main = (1..10).flatMap { listOf(it, it, it) } + listOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
        groups = listOf(SnapGroup("g1", "Starters", 0, 0), SnapGroup("g2", "Extenders", 1, 1)),
        assignments = (1..5).associateWith { "g1" } + (6..10).associateWith { "g2" },
    )

    private fun profile(webcam: WebcamZone = WebcamZone(enabled = true), style: String = Presentation.STYLE_SPOTLIGHT, creator: String = "kai") =
        PresentEdits.newProfile("p", "Profile", deck, style, Themes.MASTER, webcam, creator, 0L, Random(3))

    // ---- B5, B6: one camera, moved by the slide -----------------------------------------

    @Test
    fun theBigCameraLayoutMovesTheCameraAndDrawsNoSecondFrame() {
        val p = profile().let { PresentEdits.addSlide(it, SlideLayouts.slide(SlideLayouts.CAMERA_BIG, Random(1))) }
        val i = p.slides.lastIndex
        val slide = p.slides[i]
        assertTrue(slide.elements.none { it.type == Element.CAMERA }, "no Camera element: the zone itself moves")
        assertEquals(SlideCamera.BIG, CompiledShow(p).zone(i), "the zone is the big box")
        // The words beside it stay off the face.
        slide.elements.forEach { e -> assertFalse(Geometry.box(e, CompiledShow(p).stage(i)).intersects(SlideCamera.BIG), "${e.plainText} under the camera") }
        // With the webcam off, nothing is drawn for it at all.
        assertNull(CompiledShow(p.copy(webcam = WebcamZone(enabled = false))).zone(i))
    }

    @Test
    fun aCameraElementFromAnOlderBuildBecomesTheSlidesOwnCamera() {
        val p = profile()
        val s = p.slides.first()
        val withElement = PresentEdits.addElements(p, s.id, listOf(Element("cam", Element.CAMERA, 100f, 100f, 640f, 360f)))
        val folded = SlideCamera.fold(withElement)
        val slide = folded.slides.first()
        assertTrue(slide.elements.none { it.type == Element.CAMERA })
        assertEquals(Slide.CAMERA_CUSTOM, slide.camera)
        assertEquals(Box(100f, 100f, 640f, 360f), CompiledShow(folded).zone(0), "the zone stands where the element stood")
        // The stage moves out of its way, so the slide's words re-flow round it.
        assertFalse(CompiledShow(folded).stage(0).intersects(Box(100f, 100f, 640f, 360f)))
        assertTrue(SlideCamera.fold(p) === p, "nothing to fold is left alone")
    }

    @Test
    fun aSlidesOwnCameraBoxIsKeptOnTheCanvas() {
        val z = WebcamZone(enabled = true)
        val off = WebcamLayout.zone(z, Slide.CAMERA_CUSTOM, Box(1800f, 1000f, 50f, 50f))!!
        assertTrue(off.right <= Presentation.WIDTH && off.bottom <= Presentation.HEIGHT)
        assertTrue(off.w >= SlideCamera.MIN && off.h >= SlideCamera.MIN)
        assertEquals(SlideCamera.clamp(Box(-50f, 0f, 400f, 300f)).x, 0f)
        // A slide on CUSTOM with no box of its own falls back to the presentation's.
        assertEquals(WebcamLayout.zone(z.copy(preset = WebcamZone.CUSTOM, box = Box(10f, 10f, 300f, 300f))), WebcamLayout.zone(z.copy(preset = WebcamZone.CUSTOM, box = Box(10f, 10f, 300f, 300f)), Slide.CAMERA_CUSTOM))
    }

    // ---- I5: inserted things land beside the camera --------------------------------------

    @Test
    fun anInsertNeverLandsUnderTheCamera() {
        for (preset in WebcamZone.PRESETS - WebcamZone.CUSTOM) for (size in listOf(WebcamZone.SIZE_S, WebcamZone.SIZE_M, WebcamZone.SIZE_L)) {
            val z = WebcamZone(enabled = true, preset = preset, size = size)
            val zone = WebcamLayout.zone(z)!!
            val stage = WebcamLayout.stage(zone)
            for ((w, h) in listOf(1600f to 620f, 1200f to 600f, 800f to 160f, 400f to 720f, 1920f to 1080f)) {
                val b = EditorEdits.placeIn(stage, w, h)
                assertFalse(b.intersects(zone), "$preset $size ${w}x$h lands on the camera")
                assertTrue(b.w <= w + 0.01f && abs(b.w / b.h - w / h) < 0.01f, "shrunk, never stretched")
            }
        }
    }

    // ---- B3: Undo restyle puts back the look and keeps the work ----------------------------

    @Test
    fun undoRestyleKeepsEverythingDoneSince() {
        val before = profile()
        val title = before.slides.first()
        val word = title.elements.first()
        // The restyle: a theme, a background, the title's colour and face.
        val restyled = PresentEdits.updateElements(
            PresentEdits.updateSlide(before.copy(theme = Themes.ARENA), title.id) { it.copy(background = Fill.solid("#112233")) },
            title.id, setOf(word.id),
        ) { e -> e.copy(paras = e.paras.map { pa -> pa.copy(runs = pa.runs.map { it.copy(style = it.style.copy(color = "#FF0000", font = SlideFonts.BEBAS)) }) }) }
        // Ten minutes of work after it: notes, a new slide, the title's words retyped.
        var later = PresentEdits.updateSlide(restyled, title.id) { it.copy(notes = "Say hello") }
        later = PresentEdits.addSlide(later, SlideLayouts.slide(SlideLayouts.QUOTE, Random(9)))
        later = PresentEdits.updateElements(later, title.id, setOf(word.id)) { e -> e.copy(paras = e.paras.map { pa -> pa.copy(runs = pa.runs.map { it.copy(text = "My deck") }) }) }

        val undone = EditorEdits.restoreLook(later, before)
        assertEquals(before.theme, undone.theme)
        assertEquals(later.slides.size, undone.slides.size, "the slide added since stays")
        val t = undone.slides.first()
        assertEquals("Say hello", t.notes, "the notes stay")
        assertNull(t.background, "the background is the theme's again")
        val run = t.elements.first().paras.first().runs.first()
        assertEquals("My deck", run.text, "the words typed since stay")
        assertEquals(before.slides.first().elements.first().paras.first().runs.first().style.color, run.style.color)
        assertEquals(before.slides.first().elements.first().paras.first().runs.first().style.font, run.style.font)
    }

    // ---- B4: the creator's name on the slides ---------------------------------------------

    @Test
    fun yourNameIsWrittenOnTheTitleSlide() {
        val p = profile(creator = "kai")
        fun line(q: Presentation) = q.slides.first().elements[1].plainText
        assertEquals("Deck profile · kai", line(p))
        // Typed a letter at a time, the line follows.
        var q = p
        for (name in listOf("kai ", "kai H", "kai Ha")) q = EditorEdits.setCreator(q, name)
        assertEquals("Deck profile · kai Ha", line(q))
        assertEquals("kai Ha", q.creator)
        assertEquals("Deck profile", line(EditorEdits.setCreator(p, "")))
        assertEquals("Deck profile · Sam", line(EditorEdits.setCreator(EditorEdits.setCreator(p, ""), "Sam")))
        // The old name as a word is renamed; inside another word it never is.
        assertEquals("by Sam, thanks", EditorEdits.replaceWord("by kai, thanks", "kai", "Sam"))
        assertEquals("Thanks", EditorEdits.replaceWord("Thanks", "an", "Sam"))
        // Words the person wrote on other slides are theirs.
        val other = PresentEdits.addSlide(p, SlideLayouts.slide(SlideLayouts.QUOTE, Random(2)).let { s -> s.copy(elements = s.elements.map { it.copy(paras = listOf(Para.of("Deck profile · kai"))) }) })
        assertEquals("Deck profile · kai", EditorEdits.setCreator(other, "Sam").slides.last().elements.first().plainText)
    }

    // ---- I9: a style switch moves the whole-deck step ----------------------------------------

    @Test
    fun buildUpEndsOnTheWholeDeckAndTheOthersOpenWithIt() {
        val p = profile()
        val deckSlides = { q: Presentation -> q.slides.filter { it.deck != null } }
        assertTrue(deckSlides(p).first().deck!!.all)
        val built = EditorEdits.retell(p, Presentation.STYLE_BUILD_UP)
        assertEquals(Presentation.STYLE_BUILD_UP, built.style)
        assertTrue(deckSlides(built).last().deck!!.all, "Build-up ends on it")
        assertFalse(deckSlides(built).first().deck!!.all)
        assertEquals(p.slides.map { it.id }.toSet(), built.slides.map { it.id }.toSet(), "nothing made or lost")
        val back = EditorEdits.retell(built, Presentation.STYLE_SLIDES)
        assertTrue(deckSlides(back).first().deck!!.all, "Slides opens with it")
        assertEquals(p.slides.map { it.id }, back.slides.map { it.id })
        assertEquals(EditorEdits.deckSlides(p), deckSlides(p).size)
    }

    // ---- B1: a number typed is read once --------------------------------------------------

    @Test
    fun aTypedNumberIsReadWhenTheFieldIsLetGo() {
        assertEquals(500f, EditorEdits.number("500", min = 12f))
        assertEquals(12f, EditorEdits.number("5", min = 12f), "clamped only when committed")
        assertNull(EditorEdits.number("", min = 12f))
        assertNull(EditorEdits.number("-", min = 12f))
        assertEquals(12.5f, EditorEdits.number("12,5"))
        assertNull(EditorEdits.number("NaN"))
    }

    // ---- M3: slides picked in the sorter --------------------------------------------------

    @Test
    fun shiftPicksARunAndCtrlPicksOneAtATime() {
        val order = listOf("a", "b", "c", "d", "e")
        val run = SlidePicks("b").click(order, "d", range = true, toggle = false)
        assertEquals(listOf("b", "c", "d"), run.all(order))
        assertEquals("d", run.current)
        val some = SlidePicks("a").click(order, "c", range = false, toggle = true).click(order, "e", range = false, toggle = true)
        assertEquals(listOf("a", "c", "e"), some.all(order))
        val less = some.click(order, "e", range = false, toggle = true)
        assertEquals(listOf("a", "c"), less.all(order))
        assertTrue(less.current in less.all(order))
        assertEquals(listOf("c"), SlidePicks("b").click(order, "c", range = false, toggle = false).all(order))
    }

    // ---- M2: zoom keeps the point under the pointer ---------------------------------------

    @Test
    fun zoomingKeepsWhatIsUnderThePointerUnderIt() {
        val w = 1000f
        val h = 700f
        val z0 = SlideZoom.FIT
        val fx = 700f
        val fy = 300f
        val cx = z0.toCanvasX(fx, w, h)
        val cy = z0.toCanvasY(fy, w, h)
        val z1 = z0.zoomAround(2f, fx, fy, w, h)
        assertEquals(2f, z1.zoom)
        assertTrue(abs(z1.toCanvasX(fx, w, h) - cx) < 0.5f && abs(z1.toCanvasY(fy, w, h) - cy) < 0.5f)
        // Never smaller than the whole slide, never past the most, and a fitted slide is centred.
        assertEquals(SlideZoom.FIT, z1.zoomAround(0.1f, fx, fy, w, h))
        assertEquals(SlideZoom.MAX, z1.zoomAround(100f, fx, fy, w, h).zoom)
        // Panned far away, it is kept in reach.
        val far = z1.panned(1e6f, -1e6f, w, h)
        val s = far.scale(w, h)
        assertTrue(far.originX(w, h) <= SlideZoom.ROOM + 0.5f && far.originY(w, h) + Presentation.HEIGHT * s >= h - SlideZoom.ROOM - 0.5f)
    }

    @Test
    fun anInsertedNumberTableOrChartReadsAsABlank() {
        val p = PresentEdits.addSlide(profile(), SlideLayouts.slide(SlideLayouts.BIG_NUMBER, Random(4)))
        val stat = assertNotNull(p.slides.last().elements.firstOrNull { it.type == Element.STAT })
        assertTrue(Placeholders.untouched(stat), "no made-up 87 %")
        assertFalse(Placeholders.stat.value.any { it.isDigit() })
        assertTrue(Placeholders.table.flatten().none { c -> c.any { it.isDigit() } })
    }
}
