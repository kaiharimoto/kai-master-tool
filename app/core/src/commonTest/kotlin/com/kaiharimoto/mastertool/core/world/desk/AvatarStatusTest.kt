package com.kaiharimoto.mastertool.core.world.desk

import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.world.desk.AvatarStatus.Kind
import com.kaiharimoto.mastertool.core.world.desk.AvatarStatus.Plate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The avatar's status on the desktop (`DESKTOP.md` §5.7): words, a face and a sign per activity, and when each shows. */
class AvatarStatusTest {
    private val running = AiNow(running = true, tool = "world_run")

    @Test
    fun eachActivityHasItsWordsFaceAndSign() {
        val write = AvatarStatus.of(AiDoes.Write("lib/deck-odds.js"))!!
        assertEquals("Writing deck-odds.js", write.text)
        assertTrue(write.named, "a file's name is set as itself")
        assertEquals(Expression.WORKING, write.expression, "focused")
        assertEquals(AvatarSign.WRITE, write.sign)

        val run = AvatarStatus.of(AiDoes.Run("hands.js"))!!
        assertEquals("Running hands.js", run.text)
        assertEquals(Expression.LISTENING, run.expression, "watching")
        assertEquals(AvatarSign.RUN, run.sign)

        val tool = AvatarStatus.of(AiDoes.Tool("openings", AvatarStatus.instrument("openings", JsonObject(mapOf("trials" to JsonPrimitive(10_000))))))!!
        assertEquals("Running 10,000 hands", tool.text)
        assertFalse(tool.named)
        assertEquals("Running card web", AvatarStatus.of(AiDoes.Tool("card_web"))!!.text)

        val read = AvatarStatus.of(AiDoes.Read(BuiltInApp.LIBRARY.ref, "Labrynth guide"))!!
        assertEquals("Reading Labrynth guide", read.text)
        assertEquals(Expression.READING, read.expression)
        assertEquals(AvatarSign.READ, read.sign)
        assertEquals("Reading the Library", AvatarStatus.of(AiDoes.Read(BuiltInApp.LIBRARY.ref, "Library"))!!.text)
        assertEquals(AvatarSign.BROWSE, AvatarStatus.of(AiDoes.Read(BuiltInApp.BROWSER.ref, "Browser"))!!.sign)

        val show = AvatarStatus.of(AiDoes.Show("t1", "“Opening hands”"))!!
        assertEquals("Showing Opening hands", show.text)
        assertEquals(Expression.FOUND, show.expression, "pleased")
        assertEquals("Showing a page", AvatarStatus.of(AiDoes.Show("t1"))!!.text)

        assertEquals("Making Hand odds", AvatarStatus.of(AiDoes.MakeApp("hand-odds", "Hand odds"))!!.text)
        assertEquals(AvatarSign.PRESS, AvatarStatus.of(AiDoes.Press("hand-odds", "Hand odds", "draw"))!!.sign)
        assertEquals(AvatarStatus.WAITING, AvatarStatus.of(AiDoes.Question))
        assertEquals("Waiting on you", AvatarStatus.WAITING.text, "the taskbar's and the notice's words")
        assertEquals(Expression.WAITING, AvatarStatus.WAITING.expression)
        assertEquals(AvatarSign.ASK, AvatarStatus.WAITING.sign)
        assertEquals(Kind.DONE, AvatarStatus.of(AiDoes.TurnEnd)!!.kind)
        assertNull(AvatarStatus.of(AiDoes.TurnStart), "waking at home says nothing beside it")
        assertEquals(Expression.THINKING, AvatarStatus.THINK.expression)
    }

    @Test
    fun aRunsEndIsPleasedWorriedOrWaitingOnThePerson() {
        val ok = AvatarStatus.ran("lib/hands.js", ok = true)
        assertEquals("Ran hands.js", ok.text)
        assertEquals(Expression.DONE, ok.expression)
        val failed = AvatarStatus.ran("hands.js", ok = false, error = "ReferenceError: starters is not defined")
        assertEquals("Failed hands.js", failed.text)
        assertEquals(Expression.OOPS, failed.expression, "worried")
        assertEquals(AvatarSign.FAILED, failed.sign)
        val python = AvatarStatus.ran("odds.py", ok = false, error = "Python is off on this computer: the person allows it in Settings › Ai World.")
        assertEquals(Kind.BLOCKED, python.kind)
        assertTrue(python.pulses, "it holds the waiting pose: only the person can turn Python on")
        assertTrue(AvatarStatus.WAITING.pulses)
        assertFalse(failed.pulses)
    }

    @Test
    fun everyKindIsAnExistingFaceAndHasASignIcon() {
        Kind.entries.forEach { k ->
            assertTrue(k.expression in Expression.entries)
            assertNotNull(WorldIcons.sign(k.sign))
        }
        AvatarSign.entries.forEach { assertTrue(WorldIcons.sign(it) in WorldIcons.ALL, "$it is drawn from the icon grid") }
    }

    @Test
    fun theOrderOfWhatItSays() {
        val writing = AvatarStatus.of(AiDoes.Write("a.js"))!!
        val ranOk = AvatarStatus.ran("a.js", true)
        val failed = AvatarStatus.ran("a.js", false, "boom")
        // A question beats everything.
        assertEquals(AvatarStatus.WAITING, AvatarStatus.resolve(writing, 0, failed, 10, AiNow(running = true, asking = true), 20))
        // The tool it went for, while it runs.
        assertEquals(writing, AvatarStatus.resolve(writing, 0, null, 0, AiNow(running = true, tool = "world_write"), 20))
        assertEquals(writing, AvatarStatus.resolve(writing, 0, null, 0, AiNow(running = true, tool = "mcp__neue__world_write"), 20))
        // Between tools it thinks: what it went to do is done.
        assertEquals(AvatarStatus.THINK, AvatarStatus.resolve(writing, 0, null, 0, AiNow(running = true), 20))
        assertEquals(AvatarStatus.ANSWER, AvatarStatus.resolve(writing, 0, null, 0, AiNow(running = true, answering = true), 20))
        // Another tool than the World's says itself.
        assertEquals("Reading cards", AvatarStatus.resolve(writing, 0, null, 0, AiNow(running = true, tool = "card_info"), 20)?.text)
        assertEquals("Searching the web", AvatarStatus.resolve(writing, 0, null, 0, AiNow(running = true, tool = "mcp__neue__web_search"), 20)?.text)
        // A run's end, for its while.
        assertEquals(ranOk, AvatarStatus.resolve(writing, 0, ranOk, 100, AiNow(running = true), 100 + AvatarStatus.SUCCEEDED_MS - 1))
        assertEquals(AvatarStatus.THINK, AvatarStatus.resolve(writing, 0, ranOk, 100, AiNow(running = true), 100 + AvatarStatus.SUCCEEDED_MS))
        assertEquals(failed, AvatarStatus.resolve(writing, 0, failed, 100, running, 100 + AvatarStatus.SUCCEEDED_MS))
        assertEquals(AvatarStatus.FAILED_MS, AvatarStatus.expires(failed, 0))
        // ...until it goes to do something new.
        assertEquals(writing, AvatarStatus.resolve(writing, 200, failed, 100, AiNow(running = true, tool = "world_write"), 300))
        // The turn's Done takes over a success, never a failure.
        assertEquals(AvatarStatus.DONE, AvatarStatus.resolve(AvatarStatus.DONE, 200, ranOk, 100, AiNow(), 300))
        assertEquals(failed, AvatarStatus.resolve(AvatarStatus.DONE, 200, failed, 100, AiNow(), 300))
        // A question answered is no longer waited on.
        assertEquals(AvatarStatus.THINK, AvatarStatus.resolve(AvatarStatus.WAITING, 0, null, 0, AiNow(running = true), 10))
        assertNull(AvatarStatus.resolve(AvatarStatus.WAITING, 0, null, 0, AiNow(), 10))
        // Done stays Done while the reply's last words land.
        assertEquals(AvatarStatus.DONE, AvatarStatus.resolve(AvatarStatus.DONE, 0, null, 0, AiNow(running = true), 10))
        // The walk home says nothing.
        assertNull(AvatarStatus.resolve(null, 0, null, 0, AiNow(), 10))
    }

    @Test
    fun thePlateKeepsToTheSettingsAndTheRecede() {
        val writing = AvatarStatus.of(AiDoes.Write("a.js"))!!
        assertEquals(Plate.FULL, AvatarStatus.plate(writing, avatarOn = true, asleep = false, atHome = false, recede = true, personTookOver = false))
        assertEquals(Plate.NONE, AvatarStatus.plate(writing, avatarOn = false, asleep = false, atHome = false, recede = true, personTookOver = false), "avatar off: nothing")
        assertEquals(Plate.NONE, AvatarStatus.plate(writing, avatarOn = true, asleep = true, atHome = false, recede = true, personTookOver = false))
        assertEquals(Plate.NONE, AvatarStatus.plate(writing, avatarOn = true, asleep = false, atHome = true, recede = true, personTookOver = false), "home: the taskbar's line says it")
        assertEquals(Plate.NONE, AvatarStatus.plate(null, avatarOn = true, asleep = false, atHome = false, recede = true, personTookOver = false), "idle: it fades")
        // The person's hands on the page quiet it to its sign, as the windows stop receding.
        assertEquals(Plate.SIGN, AvatarStatus.plate(writing, avatarOn = true, asleep = false, atHome = false, recede = true, personTookOver = true))
        assertEquals(Plate.FULL, AvatarStatus.plate(writing, avatarOn = true, asleep = false, atHome = false, recede = false, personTookOver = true), "recede off: nothing steps back")
        // ...but what wants the person still says so.
        assertEquals(Plate.FULL, AvatarStatus.plate(AvatarStatus.WAITING, avatarOn = true, asleep = false, atHome = false, recede = true, personTookOver = true))
        assertEquals(Plate.FULL, AvatarStatus.plate(AvatarStatus.ran("a.js", false, "x"), avatarOn = true, asleep = false, atHome = false, recede = true, personTookOver = true))
    }

    @Test
    fun captionsShortenByWordsNeverToAStub() {
        val long = AvatarStatus(Kind.WRITING, "Writing", "matchup-labrynth-vs-snake-eye-v2.js", named = true)
        val w = long.words(32)
        assertEquals("matchup-labrynth-…-v2.js", w.subject)
        assertTrue(w.text.length <= 32, w.text)
        assertEquals("matchup-…-v2.js", long.words(30).subject)
        assertEquals("Writing deck-odds.js", AvatarStatus.of(AiDoes.Write("deck-odds.js"))!!.words(AvatarStatus.CAPTION_PHONE).text)
        val reading = AvatarStatus(Kind.READING, "Reading", "What each card is worth to “Starters ≥ 1” in the Labrynth deck")
        val r = reading.words(AvatarStatus.CAPTION_DESK)
        assertTrue(r.text.length <= AvatarStatus.CAPTION_DESK, r.text)
        assertTrue(r.subject!!.startsWith("Card worth"), r.subject)
        // However narrow, a subject keeps the floor.
        listOf(1, 8, 14, 20).forEach { max ->
            val s = long.words(max).subject!!
            assertTrue(s.length >= TabTitles.FLOOR, "$max: $s")
            assertTrue(s.endsWith(".js"), "the extension stays: $s")
        }
        assertEquals("Waiting on you", AvatarStatus.WAITING.words(4).text, "a verb alone is never cut")
        assertEquals("one-…-seven", AvatarStatus.shortName("one-two-three-four-five-six-seven", 12))
        assertEquals("onetwothree…", AvatarStatus.shortName("onetwothreefourfive", 12))
        assertEquals("opening-…-v3.py", AvatarStatus.shortName("opening-hands-against-the-field-v3.py", 20))
        assertTrue(AvatarStatus.looksLikeFile("snippet.js"))
        assertFalse(AvatarStatus.looksLikeFile("the guide"))
    }

    @Test
    fun thePlateFlipsSidesAtAnEdgeAndStaysOnThePage() {
        val page = DeskRect(0.0, 0.0, 800.0, 600.0)
        val mid = AvatarStatus.place(DeskPoint(200.0, 300.0), 28.0, 180.0, 24.0, page)
        assertTrue(mid.right)
        assertEquals(200.0 + 14 + AvatarStatus.GAP, mid.x)
        assertEquals(288.0, mid.y, "its middle on the avatar's")
        val edge = AvatarStatus.place(DeskPoint(760.0, 300.0), 28.0, 180.0, 24.0, page)
        assertFalse(edge.right, "near the right edge it goes left")
        assertEquals(760.0 - 14 - AvatarStatus.GAP - 180, edge.x)
        val top = AvatarStatus.place(DeskPoint(200.0, 2.0), 28.0, 180.0, 24.0, page)
        assertEquals(AvatarStatus.MARGIN, top.y, "never over the page's top")
        val bottom = AvatarStatus.place(DeskPoint(200.0, 599.0), 28.0, 180.0, 24.0, page)
        assertEquals(600.0 - AvatarStatus.MARGIN - 24, bottom.y)
        // A phone too narrow for either side: the roomier side, held on the page.
        val phone = DeskRect(0.0, 0.0, 200.0, 600.0)
        val squeezed = AvatarStatus.place(DeskPoint(100.0, 300.0), 24.0, 150.0, 24.0, phone)
        assertTrue(squeezed.x >= AvatarStatus.MARGIN && squeezed.x + 150 <= 200 - AvatarStatus.MARGIN, "$squeezed")
        // A phone's home is the face in Neue's bar, above the page: no plate stands apart from it there.
        assertFalse(AvatarStatus.onPage(DeskPoint(150.0, -24.0), phone, 24.0))
        assertTrue(AvatarStatus.onPage(DeskPoint(150.0, -6.0), phone, 24.0), "standing on a title bar at the page's top")
        assertTrue(AvatarStatus.onPage(DeskPoint(100.0, 300.0), phone))
        assertTrue(AvatarStatus.onPage(DeskPoint(100.0, -300.0), null))
    }

    @Test
    fun theBreathIsSlowSmoothAndPeriodic() {
        assertEquals(0f, AvatarStatus.breath(0), 1e-6f)
        assertEquals(1f, AvatarStatus.breath(AvatarStatus.BREATH_MS / 2), 1e-6f)
        assertEquals(AvatarStatus.breath(300), AvatarStatus.breath(300 + 5 * AvatarStatus.BREATH_MS), 1e-6f)
        // Never a jump: a 50 ms step moves it by little.
        var last = AvatarStatus.breath(0)
        for (t in 50L..10_000L step 50) {
            val b = AvatarStatus.breath(t)
            assertTrue(b in 0f..1f)
            assertTrue(kotlin.math.abs(b - last) < 0.08f, "$t: $last → $b")
            last = b
        }
    }

    @Test
    fun numbersAreGrouped() {
        assertEquals("50,000", AvatarStatus.grouped(50_000))
        assertEquals("1,000,000", AvatarStatus.grouped(1_000_000))
        assertEquals("999", AvatarStatus.grouped(999))
        assertEquals("50,000 hands", AvatarStatus.instrument("openings", null))
        assertEquals("the opening odds", AvatarStatus.instrument("openings", JsonObject(mapOf("trials" to JsonPrimitive(0)))))
        assertEquals("composition", AvatarStatus.instrument("composition", null))
    }

    @Test
    fun theRouteCarriesWhatItWentToDo() {
        val r = AvatarPilot.route(AiDoes.Write("lib/openings.js"), Desk())
        assertTrue(r.all { it.doing?.text == "Writing openings.js" })
        val end = AvatarPilot.route(AiDoes.TurnEnd, Desk())
        assertEquals(Kind.DONE, end[0].doing?.kind)
        assertNull(end[1].doing, "home says nothing")
        val pilot = AvatarPilot()
        assertNull(pilot.doing)
        pilot.on(AiDoes.Run("hands.js"), Desk())
        assertEquals("Running hands.js", pilot.doing?.text)
    }
}
