package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.present.edit.Align
import com.kaiharimoto.mastertool.core.present.edit.EditHistory
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.edit.RichText
import com.kaiharimoto.mastertool.core.present.edit.Snap
import com.kaiharimoto.mastertool.core.present.edit.SlideClip
import com.kaiharimoto.mastertool.core.present.edit.Transform
import com.kaiharimoto.mastertool.core.present.play.Builds
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.mastertool.core.present.play.StageTween
import com.kaiharimoto.mastertool.core.present.stage.Box
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
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

class PresentTest {

    /** A 40-card deck in three groups, five in the Extra Deck, three in the Side. */
    private val deck = DeckSnapshot(
        deckId = "d1",
        name = "Test",
        main = (1..13).flatMap { listOf(it, it, it) } + listOf(14),
        extra = listOf(101, 102, 103, 104, 105),
        side = listOf(201, 201, 202),
        groups = listOf(SnapGroup("g1", "Starters", 0, 0), SnapGroup("g2", "Extenders", 1, 1), SnapGroup("g3", "Non-engine", 2, 2)),
        assignments = (1..4).associateWith { "g1" } + (5..8).associateWith { "g2" } + (9..14).associateWith { "g3" },
    )

    private val stage = WebcamLayout.safe

    private fun steps() = listOf(
        DeckFocus(all = true),
        DeckFocus(groups = listOf("g1"), note = "Open these"),
        DeckFocus(groups = listOf("g2")),
        DeckFocus(groups = listOf("g3")),
        DeckFocus(cards = listOf(101, 102, 103, 104, 105)),
        DeckFocus(cards = listOf(201, 202)),
    )

    @Test
    fun everyCopyHasAStableKey() {
        val copies = DeckStage.copies(deck)
        assertEquals(48, copies.size)
        assertEquals(copies.size, copies.map { it.key }.toSet().size)
        assertEquals("M:1#2", copies[2].key)
        assertEquals(1, DeckStage.idOf("M:1#2"))
    }

    @Test
    fun spotlightKeepsEveryCardAndLightsTheFocus() {
        val f = DeckStage.frame(deck, steps(), Presentation.STYLE_SPOTLIGHT, 1, stage)
        assertEquals(48, f.cards.size)
        val lit = f.cards.filter { it.emphasis > 0.5f }
        assertEquals(12, lit.size, "four starters, three copies each")
        assertTrue(f.cards.filter { it.emphasis == 0f }.all { it.alpha < 0.5f })
        assertNotNull(f.note)
        // Nothing leaves the stage.
        f.cards.forEach { assertTrue(it.box.x >= stage.x - 0.5f && it.box.right <= stage.right + 0.5f && it.box.bottom <= stage.bottom + 0.5f, "${it.key} ${it.box}") }
        // Nothing overlaps.
        for (i in f.cards.indices) for (j in i + 1 until f.cards.size) {
            val a = f.cards[i].box.inset(0.5f)
            val b = f.cards[j].box.inset(0.5f)
            assertFalse(a.intersects(b), "${f.cards[i].key} over ${f.cards[j].key}")
        }
    }

    @Test
    fun buildUpGrowsAndEndsAsTheWholeDeck() {
        val s = steps().drop(1)
        var last = 0
        var lastWidth = Float.MAX_VALUE
        for (i in s.indices) {
            val f = DeckStage.frame(deck, s, Presentation.STYLE_BUILD_UP, i, stage)
            assertTrue(f.cards.size > last, "step $i adds cards")
            val w = f.cards.filter { it.key.startsWith("M:") }.maxOfOrNull { it.box.w } ?: lastWidth
            assertTrue(w <= lastWidth + 0.5f, "cards only shrink as the deck grows")
            lastWidth = w
            last = f.cards.size
        }
        assertEquals(48, last)
    }

    @Test
    fun slidesShowsOneCardLargeWithItsCount() {
        val s = listOf(DeckFocus(cards = listOf(1)))
        val f = DeckStage.frame(deck, s, Presentation.STYLE_SLIDES, 0, stage)
        assertEquals(1, f.cards.size)
        assertEquals(3, f.cards[0].badge)
        assertTrue(f.cards[0].box.h > stage.h * 0.8f)
        // The overview, on demand, is the whole deck with the card lit.
        val o = DeckStage.frame(deck, s, Presentation.STYLE_SLIDES, 0, stage, overview = true)
        assertEquals(48, o.cards.size)
        assertEquals(3, o.cards.count { it.emphasis > 0.5f })
    }

    @Test
    fun spotlightKeepsWhatItHasRevealed() {
        // kai (1.0.71): "the already revealed cards should stay revealed".
        val f = DeckStage.frame(deck, steps(), Presentation.STYLE_SPOTLIGHT, 2, stage)
        val starters = f.cards.filter { DeckStage.idOf(it.key) in 1..4 }
        val extenders = f.cards.filter { DeckStage.idOf(it.key) in 5..8 }
        val rest = f.cards.filter { DeckStage.idOf(it.key) in 9..14 }
        assertTrue(starters.all { it.alpha == 1f && it.emphasis == 0f }, "talked about before: bright, not lifted")
        assertTrue(extenders.all { it.alpha == 1f && it.emphasis == 1f }, "talked about now: lit and lifted")
        assertTrue(rest.all { it.alpha < 0.5f }, "not yet: dim")
        assertEquals(1f, f.labels.first { it.group == "g1" }.alpha)
        assertTrue(f.labels.first { it.group == "g3" }.alpha < 0.5f)
        // Going back takes the light back with it.
        val back = DeckStage.frame(deck, steps(), Presentation.STYLE_SPOTLIGHT, 1, stage)
        assertTrue(back.cards.filter { DeckStage.idOf(it.key) in 5..8 }.all { it.alpha < 0.5f })
        // The whole deck at the end is all bright, as before.
        val end = DeckStage.frame(deck, steps() + DeckFocus(all = true), Presentation.STYLE_SPOTLIGHT, 6, stage)
        assertTrue(end.cards.all { it.alpha == 1f && it.emphasis == 0f })
    }

    @Test
    fun masterUiIsTheDefaultFlatAndEasyToRead() {
        // kai (1.0.72): "default to Master UI … high contrast, easy to read, Inter only".
        val master = Themes.of(null)
        assertEquals(Themes.MASTER, master.id)
        assertEquals("Master UI", master.name)
        listOf(Themes.of(Themes.MASTER), Themes.of(Themes.MASTER_DARK)).forEach { t ->
            assertTrue(t.flat, t.name)
            assertEquals(SlideFonts.INTER, t.headingFont)
            assertEquals(SlideFonts.INTER, t.bodyFont)
            assertNull(t.backgroundTo)
            assertEquals(Theme.HIGHLIGHT_OUTLINE, t.highlight)
        }
        // Every word token reads at 4.5 : 1 on the background and the surface, in both.
        for (t in listOf(Themes.of(Themes.MASTER), Themes.of(Themes.MASTER_DARK))) for (under in listOf("bg", "surface")) {
            for (token in listOf("text", "muted", "accent", "accent2", "accent3")) {
                val ratio = SlideColor.contrast(SlideColor.hex(t.color(token))!!, SlideColor.hex(t.color(under))!!)
                assertTrue(ratio >= 4.5, "${t.name}: $token on $under is ${"%.2f".format(ratio)}")
            }
        }
        // Softened by the person, or Ai, without leaving the theme.
        assertFalse(master.with(ThemeOverride(flat = false)).flat)
        assertFalse(Themes.of(Themes.ARENA).flat)
        assertEquals(Themes.MASTER_DARK, Themes.named("Master UI Dark")?.id)
        assertEquals(Themes.MASTER, Themes.named("master")?.id)
        assertEquals(Themes.NEON, Themes.named("Neon")?.id)
        assertNull(Themes.named("vaporwave"))
    }

    @Test
    fun theTweenMatchesCopiesByKey() {
        val a = DeckStage.frame(deck, steps(), Presentation.STYLE_SPOTLIGHT, 0, stage)
        val b = DeckStage.frame(deck, listOf(DeckFocus(cards = listOf(1))), Presentation.STYLE_SLIDES, 0, stage)
        val mid = StageTween.between(a, b, 0.5f)
        assertEquals(48, mid.cards.size, "the others fade where they stand")
        assertEquals(b, StageTween.between(a, b, 1f))
        val moving = mid.cards.first { it.key == "M:1#0" }
        val from = a.card("M:1#0")!!.box
        val to = b.card("M:1#0")!!.box
        assertTrue(abs(moving.box.w - (from.w + to.w) / 2f) < 1f)
    }

    @Test
    fun theCameraLeavesTheBestBand() {
        val zone = WebcamLayout.zone(WebcamZone(enabled = true, preset = WebcamZone.RIGHT_COLUMN))!!
        val s = WebcamLayout.stage(zone, 1.6f)
        assertTrue(s.right <= zone.x, "a right column leaves the left")
        val corner = WebcamLayout.zone(WebcamZone(enabled = true, preset = WebcamZone.BOTTOM_RIGHT))!!
        val wide = WebcamLayout.stage(corner, 3f)
        assertFalse(wide.intersects(corner))
        assertNull(WebcamLayout.zone(WebcamZone(enabled = false)))
        assertEquals(WebcamLayout.safe, WebcamLayout.stage(null))
        assertNull(WebcamLayout.zone(WebcamZone(enabled = true), "HIDDEN"))
        val moved = WebcamLayout.zone(WebcamZone(enabled = true), WebcamZone.TOP_LEFT)!!
        assertTrue(moved.x < 100f && moved.y < 100f, "a slide may move the camera")
        val custom = WebcamLayout.zone(WebcamZone(enabled = true, preset = WebcamZone.CUSTOM, box = Box(1800f, 1000f, 400f, 300f)))!!
        assertTrue(custom.right <= Presentation.WIDTH && custom.bottom <= Presentation.HEIGHT, "kept on the canvas")
    }

    @Test
    fun buildsRunInClickSteps() {
        val e1 = Element("a", Element.TEXT, animations = listOf(Anim("x", trigger = Anim.WITH_PREVIOUS, order = 0)))
        val e2 = Element("b", Element.TEXT, animations = listOf(Anim("y", order = 1)))
        val e3 = Element("c", Element.TEXT, animations = listOf(Anim("z", trigger = Anim.AFTER_PREVIOUS, durationMs = 300, order = 2)))
        val e4 = Element("d", Element.TEXT)
        val slide = Slide("s", elements = listOf(e1, e2, e3, e4))
        val b = Builds.compile(slide)
        assertEquals(2, b.count, "the arrival and one click")
        assertEquals(450, b.steps[1][1].startMs, "after the click's own build")
        // e2 waits for the click; e1 comes in on arrival; e4 has no builds and is always there.
        assertFalse(Builds.state(b, e2, 0, 10_000).visible)
        assertTrue(Builds.state(b, e1, 0, 10_000).visible)
        assertTrue(Builds.state(b, e4, 0, 0).visible)
        assertTrue(Builds.state(b, e2, 1, 10_000).visible)
        assertFalse(Builds.state(b, e3, 1, 100).visible, "not before the one it follows ends")
        val half = Builds.state(b, e2, 1, 225)
        assertTrue(half.alpha in 0.1f..0.99f)
    }

    @Test
    fun anExitHidesAndAnEmphasisReturns() {
        val e = Element("a", Element.TEXT, animations = listOf(Anim("x", kind = Anim.EXIT, order = 0), Anim("y", kind = Anim.EMPHASIS, effect = Anim.PULSE, order = 1)))
        val b = Builds.compile(Slide("s", elements = listOf(e)))
        assertTrue(Builds.state(b, e, 0, 0).visible)
        assertFalse(Builds.state(b, e, 1, 10_000).visible)
        val p = Element("p", Element.TEXT, animations = listOf(Anim("y", kind = Anim.EMPHASIS, effect = Anim.PULSE)))
        val pb = Builds.compile(Slide("s", elements = listOf(p)))
        assertEquals(1f, Builds.state(pb, p, 1, 10_000).scale)
        assertTrue(Builds.state(pb, p, 1, 225).scale > 1f)
    }

    @Test
    fun theCursorWalksBuildsThenSlidesAndSkipsHidden() {
        val clicky = Slide("a", elements = listOf(Element("e", Element.TEXT, animations = listOf(Anim("x")))))
        val p = Presentation("p", "P", slides = listOf(clicky, Slide("b", hidden = true), Slide("c")))
        val show = CompiledShow(p)
        assertEquals(Cursor(0, 0), show.first)
        assertEquals(Cursor(0, 1), show.next(Cursor(0, 0)))
        assertEquals(Cursor(2, 0), show.next(Cursor(0, 1)))
        assertNull(show.next(Cursor(2, 0)))
        assertEquals(Cursor(0, 1), show.previous(Cursor(2, 0)), "back lands with every build done")
        assertEquals(3, show.clicks)
    }

    @Test
    fun aDeckSlideFrameComesFromTheShow() {
        val p = PresentEdits.newProfile("p", "P", deck, Presentation.STYLE_BUILD_UP, Themes.ARENA, WebcamZone(enabled = true), "kai", 0L, Random(1))
        val show = CompiledShow(p)
        val deckSlides = p.slides.indices.filter { p.slides[it].deck != null }
        assertTrue(deckSlides.size >= 5, "the whole deck, three groups, extra, side")
        val firstGroup = show.deckFrame(deckSlides[0])!!
        assertTrue(firstGroup.cards.size < 48)
        val zone = show.zone(deckSlides[0])!!
        firstGroup.cards.forEach { assertFalse(it.box.intersects(zone), "cards stay off the camera") }
        assertNotNull(show.slideFor("M:5#0"))
    }

    @Test
    fun theCodecForgivesABrokenElement() {
        val p = Presentation("p", "P", slides = listOf(Slide("s", elements = listOf(Element("e", Element.TEXT, paras = listOf(Para.of("Hi")))))))
        val text = PresentCodec.encode(p)
        assertEquals(p, PresentCodec.decode(text))
        val broken = text.replace("\"Hi\"", "{\"oops\":true}")
        val read = PresentCodec.decode(broken)
        assertNotNull(read)
        assertEquals(1, read.slides.size, "the slide survives")
        assertNull(PresentCodec.decode("not json"))
        val future = """{"id":"p","name":"P","newThing":1,"slides":[{"id":"s","elements":[{"id":"e","type":"HOLOGRAM","glow":3}]}]}"""
        assertEquals("HOLOGRAM", PresentCodec.decode(future)!!.slides[0].elements[0].type)
    }

    @Test
    fun aStageAnchoredElementReflows() {
        val e = Element("e", Element.TEXT, 0.5f, 0.5f, 0.5f, 0.5f, anchor = Element.ANCHOR_STAGE)
        val box = Geometry.box(e, Box(100f, 100f, 1000f, 800f))
        assertEquals(Box(600f, 500f, 500f, 400f), box)
        val back = Geometry.place(e, Box(100f, 100f, 500f, 400f), Box(100f, 100f, 1000f, 800f))
        assertEquals(0f, back.x)
        assertEquals(0.5f, back.w)
    }

    @Test
    fun resizeHoldsTheOppositeCorner() {
        val b = Box(100f, 100f, 200f, 100f)
        val r = Transform.resize(b, 0f, Transform.Handle.BOTTOM_RIGHT, 50f, 20f)
        assertEquals(Box(100f, 100f, 250f, 120f), r)
        val l = Transform.resize(b, 0f, Transform.Handle.TOP_LEFT, 50f, 20f)
        assertEquals(Box(150f, 120f, 150f, 80f), l)
        val keep = Transform.resize(b, 0f, Transform.Handle.BOTTOM_RIGHT, 100f, 0f, keepAspect = true)
        assertEquals(2f, keep.w / keep.h, 0.01f)
        val centre = Transform.resize(b, 0f, Transform.Handle.RIGHT, 50f, 0f, fromCentre = true)
        assertEquals(b.cx, centre.cx, 0.01f)
        // Turned a quarter, dragging right along the canvas grows its height.
        val turned = Transform.resize(b, 90f, Transform.Handle.BOTTOM, -40f, 0f)
        assertEquals(140f, turned.h, 0.01f)
        assertEquals(90f, Transform.rotation(Box(0f, 0f, 100f, 100f), 200f, 50f, snap = true))
        assertTrue(Transform.hit(Box(0f, 0f, 100f, 20f), 90f, 50f, 50f))
        assertFalse(Transform.hit(Box(0f, 0f, 100f, 20f), 0f, 50f, 50f))
    }

    @Test
    fun guidesCatchEdgesAndMiddles() {
        val moving = Box(103f, 400f, 100f, 100f)
        val s = Snap.move(moving, listOf(Box(0f, 0f, 1920f, 1080f), Box(100f, 0f, 50f, 50f)), threshold = 6f)
        assertEquals(-3f, s.dx)
        assertTrue(s.guides.any { it.vertical && it.at == 100f })
        val far = Snap.move(Box(500f, 500f, 10f, 10f), listOf(Box(0f, 0f, 20f, 20f)), threshold = 4f)
        assertEquals(0f, far.dx)
        val aligned = Align.align(listOf(Box(0f, 0f, 10f, 10f), Box(50f, 30f, 20f, 20f)), Align.LEFT)
        assertTrue(aligned.all { it.x == 0f })
        val spread = Align.distribute(listOf(Box(0f, 0f, 10f, 10f), Box(15f, 0f, 10f, 10f), Box(90f, 0f, 10f, 10f)), true)
        assertEquals(45f, spread[1].x)
    }

    @Test
    fun undoAndRedoWithCoalescedDrags() {
        val h = EditHistory<Int>()
        h.push(1, "Move", "drag")
        h.push(2, "Move", "drag")
        h.push(3, "Move", "drag")
        h.seal()
        h.push(4, "Type")
        assertEquals(4, h.undo(5))
        assertEquals(1, h.undo(4), "the drag was one step")
        assertEquals(4, h.redo(1))
    }

    @Test
    fun styledTextKeepsItsStylesThroughTyping() {
        val bold = RunStyle(weight = 700)
        val paras = listOf(Para(listOf(Run("Hello ", RunStyle()), Run("world", bold))))
        val typed = RichText.edit(paras, "Hello big world")
        assertEquals("Hello big world", typed.joinToString("\n") { it.text })
        assertEquals(bold, typed[0].runs.last().style)
        assertEquals("world", typed[0].runs.last().text)
        val split = RichText.edit(paras, "Hello\n world")
        assertEquals(2, split.size)
        val restyled = RichText.restyle(listOf(Para.of("abcdef")), 2, 4) { it.copy(italic = true) }
        assertEquals(listOf("ab", "cd", "ef"), restyled[0].runs.map { it.text })
        assertTrue(restyled[0].runs[1].style.italic)
        assertEquals(paras, RichText.edit(paras, "Hello world"))
    }

    @Test
    fun editsShareOneVocabulary() {
        val r = Random(3)
        var p = PresentEdits.newProfile("p", "P", deck, Presentation.STYLE_SPOTLIGHT, Themes.PAPER, WebcamZone(), "", 0L, r)
        val title = p.slides.first()
        assertEquals("Test", title.elements.first().plainText)
        assertEquals(SlideLayouts.END_CARD, p.slides.last().layout)
        val count = p.slides.size
        p = PresentEdits.duplicateSlides(p, setOf(title.id), r)
        assertEquals(count + 1, p.slides.size)
        assertTrue(p.slides[1].elements.none { e -> title.elements.any { it.id == e.id } }, "fresh ids")
        p = PresentEdits.moveSlides(p, listOf(p.slides.last().id), 0)
        assertEquals(SlideLayouts.END_CARD, p.slides.first().layout)
        val sid = p.slides[1].id
        val ids = p.slides[1].elements.map { it.id }.toSet()
        p = PresentEdits.group(p, sid, ids, r)
        assertEquals(1, p.slides[1].elements.map { it.group }.toSet().size)
        assertEquals(ids, PresentEdits.withGroups(p.slides[1], setOf(ids.first())))
        p = PresentEdits.order(p, sid, setOf(p.slides[1].elements.first().id), PresentEdits.FRONT)
        assertEquals(ids.first(), p.slides[1].elements.last().id)
        val clip = SlideClip.elements(p.slides[1].elements)
        assertEquals(p.slides[1].elements, SlideClip.readElements(clip))
        assertNull(SlideClip.readSlides(clip))
        val again = PresentEdits.stepsFromGroups(p, r)
        assertEquals(p.slides.count { it.deck != null }, again.slides.count { it.deck != null }, "steps are replaced, not added")
    }

    @Test
    fun colorsReadTokensAndHex() {
        val t = Themes.of(Themes.ARENA)
        assertEquals(0xFFF5B82E, SlideColor.argb("@accent", t))
        assertEquals(0x80FF0000, SlideColor.argb("#80FF0000", t))
        assertEquals(0xFFFFFFFF, SlideColor.argb("#fff", t))
        assertNull(SlideColor.argb("blue", t))
        assertEquals("#F5B82E", SlideColor.toHex(0xFFF5B82E))
        assertTrue(SlideColor.contrast(0xFF000000, 0xFFFFFFFF) > 20.9)
        Themes.all.forEach { theme ->
            val bg = SlideColor.argb("@bg", theme)!!
            val text = SlideColor.argb("@text", theme)!!
            assertTrue(SlideColor.contrast(bg, text) >= 7.0, "${theme.name} reads")
        }
    }

    @Test
    fun everyLayoutMakesASlide() {
        SlideLayouts.all.forEach { l ->
            val s = SlideLayouts.slide(l, Random(1))
            assertEquals(l, s.layout)
            assertEquals(l == SlideLayouts.DECK, s.deck != null)
        }
    }
}
