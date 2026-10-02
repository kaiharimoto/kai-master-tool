package com.kaiharimoto.mastertool.core.present

import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.present.ai.PresentBrief
import com.kaiharimoto.mastertool.core.present.ai.PresentReport
import com.kaiharimoto.mastertool.core.present.ai.PresentWriter
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.mastertool.core.present.modules.Modules
import com.kaiharimoto.mastertool.core.present.stage.WebcamZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ai's hands on a presentation (1.0.71): `present_edit`'s writer, `present_view`'s check, and Build with Ai's brief. */
class PresentAiTest {
    private val deck = DeckSnapshot(
        deckId = "d1",
        name = "Test",
        main = (1..13).flatMap { listOf(it, it, it) } + listOf(14),
        extra = listOf(101, 102, 103),
        side = listOf(201),
        groups = listOf(SnapGroup("g1", "Starters", 0, 0), SnapGroup("g2", "Extenders", 1, 1)),
        assignments = (1..4).associateWith { "g1" } + (5..8).associateWith { "g2" },
    )

    /** "Card 7" is passcode 7; anything else is not a card. */
    private val ctx = PresentWriter.Context(
        card = { name -> name.removePrefix("Card ").toIntOrNull()?.takeIf { it in 1..300 } },
        random = Random(3),
    )

    private fun profile(webcam: WebcamZone = WebcamZone(enabled = false)) =
        PresentEdits.newProfile("p1", "Test profile", deck, Presentation.STYLE_SPOTLIGHT, "arena", webcam, "kai", 0L, Random(1))

    private fun ops(json: String): List<JsonObject> = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

    @Test
    fun opsApplyInOrderAndSayWhatTheyDid() {
        val r = PresentWriter.apply(
            profile(),
            ops(
                """[
                {"action":"set_props","theme":"neon","name":"Renamed"},
                {"action":"add_slide","layout":"TITLE_BODY","slots":{"title":"Why it wins","body":"- Card 1 opens\n- Card 5 extends"},"notes":"Say hello"},
                {"action":"set_animation","slide":"2","element":"x","kind":"entrance"}
                ]""",
            ),
            ctx,
        )
        // The third op names an element that is not there: the first two stay, and it says which failed.
        assertFalse(r.ok)
        assertEquals("Renamed", r.presentation.name)
        assertEquals("neon", r.presentation.theme)
        assertTrue(r.lines.last().startsWith("Operation 3"), r.lines.last())
        val added = r.presentation.slides.first { it.title == "Why it wins" }
        assertEquals("Say hello", added.notes)
        val body = added.elements.first { it.role == Element.ROLE_BODY }
        assertEquals(2, body.paras.size)
        assertEquals(Para.LIST_BULLET, body.paras[0].list)
        // A new slide goes before the end card, not after it.
        assertEquals(SlideLayouts.END_CARD, r.presentation.slides.last().layout)
    }

    @Test
    fun stepsAreNamedByCardsAndGroupsAndCheckThem() {
        val good = PresentWriter.apply(
            profile(),
            ops("""[{"action":"set_steps","steps":[{"title":"The engine","groups":["Starters"],"note":"Open these"},{"title":"Extra","cards":["Card 101","Card 102"]}]}]"""),
            ctx,
        )
        assertTrue(good.ok, good.lines.joinToString())
        val decks = good.presentation.slides.mapNotNull { it.deck }
        assertEquals(2, decks.size)
        assertEquals(listOf("g1"), decks[0].groups)
        assertEquals("Open these", decks[0].note)
        assertEquals(listOf(101, 102), decks[1].cards)
        // A card that is not a card stops the op: nothing is guessed onto a slide.
        val bad = PresentWriter.apply(profile(), ops("""[{"action":"set_steps","steps":[{"cards":["Not A Card"]}]}]"""), ctx)
        assertFalse(bad.ok)
        assertEquals(profile().slides.size, bad.presentation.slides.size)
    }

    @Test
    fun elementsCanBeWrittenTheEasyWay() {
        val p = profile()
        val slide = p.slides.first().id
        val r = PresentWriter.apply(
            p,
            ops("""[{"action":"add_element","slide":"$slide","element":{"type":"text","text":"Hello","box":[0.1,0.1,0.5,0.2],"size":60,"bold":true,"color":"@accent"}}]"""),
            ctx,
        )
        assertTrue(r.ok, r.lines.joinToString())
        val e = r.presentation.slides.first().elements.last()
        assertEquals(Element.TEXT, e.type)
        // Fractions with no anchor said are of the stage, as the layouts' are.
        assertEquals(Element.ANCHOR_STAGE, e.anchor)
        val style = e.paras.single().runs.single().style
        assertEquals(60f, style.size)
        assertEquals(700, style.weight)
        assertEquals("@accent", style.color)
    }

    @Test
    fun theOutlineNamesEverySlideAndElement() {
        val p = profile(WebcamZone(enabled = true))
        val text = PresentReport.outline(p) { "Card $it" }
        assertTrue("Test profile" in text)
        assertTrue("Starters" in text)
        p.slides.forEach { s -> assertTrue("[${s.id}]" in text, s.id) }
        p.slides.flatMap { it.elements }.forEach { e -> assertTrue(e.id in text, e.id) }
        assertTrue("Webcam: " in text && "off" !in text.lines().first { it.startsWith("Webcam") })
    }

    @Test
    fun theCheckSeesTheCameraTinyWordsAndContrast() {
        val p = profile(WebcamZone(enabled = true, preset = WebcamZone.TOP_RIGHT))
        val slide = p.slides.first().id
        val r = PresentWriter.apply(
            p,
            ops(
                """[
                {"action":"add_element","slide":"$slide","element":{"id":"cam","type":"text","text":"Under the face","anchor":"CANVAS","x":1500,"y":60,"w":380,"h":200}},
                {"action":"add_element","slide":"$slide","element":{"id":"tiny","type":"text","text":"Too small","anchor":"CANVAS","x":100,"y":900,"w":600,"h":60,"size":14}},
                {"action":"add_element","slide":"$slide","element":{"id":"faint","type":"text","text":"Faint","anchor":"CANVAS","x":100,"y":700,"w":600,"h":100,"color":"@bg"}}
                ]""",
            ),
            ctx,
        )
        assertTrue(r.ok, r.lines.joinToString())
        val problems = PresentReport.check(r.presentation, 0)
        assertTrue(problems.any { it.startsWith("cam ") && "camera" in it }, problems.joinToString("\n"))
        assertTrue(problems.any { it.startsWith("tiny") && "smaller" in it }, problems.joinToString("\n"))
        assertTrue(problems.any { it.startsWith("faint") && "contrast" in it }, problems.joinToString("\n"))
        // The layouts' own slots, stage-anchored, never sit on the camera.
        assertTrue(PresentReport.check(p, 0).none { "camera" in it }, PresentReport.check(p, 0).joinToString("\n"))
    }

    @Test
    fun theEditToolOffersEveryWriterActionAndTheAppsOwn() {
        val actions = AiTools.presentEdit.toString()
        (PresentWriter.ACTIONS + listOf("create", "add_module", "refresh_module")).forEach { assertTrue(it in actions, it) }
        assertTrue(AiTools.presentState.name in AiTools.readOnly)
        assertTrue(AiTools.presentView.name in AiTools.readOnly)
        assertFalse(AiTools.presentEdit.name in AiTools.readOnly)
    }

    @Test
    fun theBriefSaysWhatTheLauncherAsked() {
        val b = PresentBrief(presentationId = "p1", length = PresentBrief.SHORT, tone = PresentBrief.TONE_HYPE, modules = listOf(Modules.SIDING, Modules.SHOUTOUTS), script = false)
        val text = b.message()
        assertTrue("id p1" in text)
        assertTrue("3 to 5 minutes" in text)
        assertTrue("Siding" in text && "Shoutouts" in text, text)
        assertTrue("cue words" in text)
        assertTrue("present_view" in text)
        val fresh = PresentBrief(deckId = "d9", deckName = "Snake-Eye", style = Presentation.STYLE_BUILD_UP).message()
        assertTrue("present_edit create" in fresh && "d9" in fresh && "build-up" in fresh.lowercase(), fresh)
        assertNotNull(PresentBrief.OFFERED.firstOrNull { it == Modules.GET_THE_DECK })
    }
}
