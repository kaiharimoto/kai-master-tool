package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldPrefs
import com.kaiharimoto.neue.world.TermLine
import com.kaiharimoto.neue.world.WorldPane
import com.kaiharimoto.neue.world.WorldPython
import com.kaiharimoto.neue.world.Worlds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ai World end to end, headless (1.0.95): a world made, a script typed in and run with its output streamed to the
 * terminal and its boards pinned, an instrument run, Python where this machine has it, and everything kept on disk
 * as the next start reads it.
 */
class WorldsTest {
    private val cards = listOf(
        Card(CardId(1), "Starter", "Effect Monster", "effect", "Add 1 \"Extender\" from your Deck to your hand."),
        Card(CardId(2), "Extender", "Effect Monster", "effect", ""),
        Card(CardId(3), "Brick", "Normal Monster", "normal", ""),
    )
    private val deck = DeckEntry("d1", "Test", Deck(main = List(9) { CardId(1) } + List(3) { CardId(2) } + List(28) { CardId(3) }), 0, 0)

    private val host = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, true) }
        override fun search(query: String, limit: Int) = cards.filter { query.lowercase() in it.name.lowercase() }
        override fun deck(id: String?) = deck
        override fun decks() = listOf(deck)
        override fun groups(deckId: String) = mapOf("Starters" to listOf(1))
    }

    private fun worlds(prefs: WorldPrefs = WorldPrefs(typing = 0, follow = false)): Worlds =
        Worlds(Files.createTempDirectory("world").toFile()).also { w ->
            w.host = { host }
            w.prefs = { prefs }
        }

    /** The terminal fills on the main thread: wait for it to settle. */
    private suspend fun settle() = withContext(Dispatchers.Main) { delay(150) }

    @Test
    fun aScriptIsWrittenRunAndPinned() = runBlocking {
        val w = worlds()
        val made = withContext(Dispatchers.Main) { w.create("Openings", "deck:d1") }
        val code = """
            var d = ygo.deck();
            print('cards', d.main.length);
            var hits = ygo.simulate(5000, 1, function (r) { return ygo.hand(d.main, r).indexOf('Starter') >= 0; });
            var rate = ygo.rate(hits);
            ygo.show.stat({value: Math.round(rate.p * 100) + '%', label: 'Opens a starter'}, {id: 'starter', note: '5,000 hands, seed 1'});
            Math.abs(rate.p - ygo.atLeast(40, 9, 5, 1)) < 0.02
        """.trimIndent()
        val wrote = withContext(Dispatchers.Main) { w.write("openings.js", code) }
        assertTrue(wrote.isSuccess, wrote.exceptionOrNull()?.message)
        assertEquals(code, w.editorText)
        val run = withContext(Dispatchers.Main) { w.run("openings.js", null, null) }.getOrThrow()
        settle()
        assertTrue(run.record.ok, run.record.err)
        assertEquals("true", run.value)
        assertEquals(listOf("starter"), run.boards.map { it.id })
        assertEquals(BoardKind.STAT, w.open!!.boards.single().type)
        assertTrue(w.terminal.any { it.kind == TermLine.Kind.OUT && it.text == "cards 40" }, w.terminal.toString())
        assertTrue(w.terminal.first().kind == TermLine.Kind.COMMAND)
        // A second run with the same id replaces the board in place.
        withContext(Dispatchers.Main) { w.run(null, "ygo.show.stat({value: '1', label: 'x'}, {id: 'starter'})", "js") }.getOrThrow()
        assertEquals(1, w.open!!.boards.size)
        assertEquals("x", w.open!!.boards.single().title)

        // Kept on disk as the next start reads it.
        settle()
        val root = File(w.dir, made.id)
        val back = assertNotNull(WorldCodec.decode(File(root, "world.json").readText()))
        assertEquals(listOf("starter"), back.boards.map { it.id })
        val log = WorldCodec.events(File(root, "log.jsonl").readText())
        assertTrue(log.any { it.kind == WorldEvent.Kind.RUN && it.run?.ok == true }, log.toString())
        assertTrue(log.any { it.kind == WorldEvent.Kind.WRITE && it.path == "openings.js" })
    }

    @Test
    fun aFailureSaysWhereAndPathsStayInside() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) { w.create("Errors", null) }
        val run = withContext(Dispatchers.Main) { w.run(null, "var a = 1;\nnope();", "js") }.getOrThrow()
        assertFalse(run.record.ok)
        assertTrue("line 2" in run.record.err, run.record.err)
        assertTrue(withContext(Dispatchers.Main) { w.write("../escape.js", "x") }.isFailure)
        assertTrue(withContext(Dispatchers.Main) { w.run("missing.js", null, null) }.isFailure)
    }

    @Test
    fun anInstrumentRunsInOneStep() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) { w.create("Tools", null) }
        val args = JsonObject(mapOf("conditions" to JsonArray(listOf(JsonPrimitive("Starters>=1"))), "trials" to JsonPrimitive(5000)))
        val o = withContext(Dispatchers.Main) { w.tool("openings", args) }.getOrThrow()
        settle()
        assertTrue(o.boards.size >= 2)
        assertTrue(w.terminal.any { it.text.startsWith("openings:") }, w.terminal.toString())
        assertTrue("Pinned" in o.words())
    }

    @Test
    fun aiIsSeenWhereItWorks() = runBlocking {
        var forward = 0
        val w = worlds(WorldPrefs(typing = 0, follow = true)).also { it.comeForward = { forward++ } }
        withContext(Dispatchers.Main) {
            w.create("Watch", null)
            w.write("a.js", "1")
        }
        assertEquals(WorldPane.EDITOR, w.aiPane)
        assertTrue(forward > 0)
        w.leave()
        assertEquals(null, w.aiPane)
    }

    @Test
    fun pythonRunsOnlyWhenAllowedAndWhereThereIsOne() = runBlocking {
        val off = worlds()
        withContext(Dispatchers.Main) { off.create("Py", null) }
        val refused = withContext(Dispatchers.Main) { off.run(null, "print(1)", "py") }
        assertTrue(refused.isFailure && "Python is off" in refused.exceptionOrNull()?.message.orEmpty())

        if (WorldPython.find("") == null) return@runBlocking
        val on = worlds(WorldPrefs(typing = 0, follow = false, python = true))
        withContext(Dispatchers.Main) { on.create("Py", null) }
        val code = """
            import ygo
            d = ygo.deck()
            print("cards", len(d["main"]))
            p = ygo.at_least(40, 9, 5, 1)
            ygo.show("stat", {"value": f"{p:.1%}", "label": "Opens a starter"}, id="py-starter")
        """.trimIndent()
        val run = withContext(Dispatchers.Main) { on.run(null, code, "py") }.getOrThrow()
        settle()
        assertTrue(run.record.ok, run.record.err)
        assertTrue("cards 40" in run.record.out, run.record.out)
        assertEquals(listOf("py-starter"), run.boards.map { it.id })
        // Python's exact odds agree with the app's.
        // 1 − C(31,5)/C(40,5) = 74.2 %.
        assertTrue("74.2%" in run.boards.single().payload, run.boards.single().payload)
        val timeout = withContext(Dispatchers.Main) { on.run(null, "while True: pass", "py", seconds = 1) }.getOrThrow()
        assertFalse(timeout.record.ok)
        assertTrue("Stopped" in timeout.record.err, timeout.record.err)
    }
}
