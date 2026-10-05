package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldPrefs
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskCodec
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.NoticeKind
import com.kaiharimoto.mastertool.core.world.desk.PersonState
import com.kaiharimoto.neue.world.TermLine
import com.kaiharimoto.neue.world.Worlds
import com.kaiharimoto.neue.world.desk.WorldDeskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The World's desktop, headless (1.1.x, `docs/world/DESKTOP.md`): Ai's tools land it in windows through the focus policy,
 * its turn's end puts away what nobody touched, the desk is kept in `desk.json`, the person's edit is never overwritten,
 * and the Terminal's own command line runs as the person.
 */
class WorldDeskTest {
    private val cards = listOf(Card(CardId(1), "Starter", "Effect Monster", "effect", ""))
    private val deck = DeckEntry("d1", "Test", Deck(main = List(40) { CardId(1) }), 0, 0)
    private val host = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, true) }
        override fun search(query: String, limit: Int) = cards
        override fun deck(id: String?) = deck
        override fun decks() = listOf(deck)
        override fun groups(deckId: String) = mapOf("Starters" to listOf(1))
    }

    private fun worlds(prefs: WorldPrefs = WorldPrefs(typing = 0, follow = true)): Worlds =
        Worlds(Files.createTempDirectory("desk").toFile()).also { w ->
            w.host = { host }
            w.prefs = { prefs }
        }

    private suspend fun settle() = withContext(Dispatchers.Main) { delay(SETTLE_MS) }

    @Test
    fun aiWritingOpensTheEditorInFrontAndItsTurnsEndPutsAwayWhatNobodyTouched() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) {
            w.create("Desk", null)
            w.write("a.js", "1")
        }
        val desk = w.desk.desk
        assertTrue(desk.working)
        assertEquals(BuiltInApp.EDITOR.id, desk.front)
        assertEquals(BuiltInApp.EDITOR.id, desk.ai)
        assertEquals(WorldEvent.AI, desk.window(BuiltInApp.EDITOR.id)?.by)
        // A run: the Terminal comes forward, the Editor stays open behind it.
        withContext(Dispatchers.Main) { w.run("a.js", null, null) }.getOrThrow()
        assertEquals(BuiltInApp.TERMINAL.id, w.desk.desk.front)
        // The turn ends: the last window Ai worked in is the answer and stays; the Editor, untouched, is put away.
        withContext(Dispatchers.Main) { w.leave() }
        val after = w.desk.desk
        assertFalse(after.working)
        assertNull(after.ai)
        assertTrue(after.isOpen(BuiltInApp.TERMINAL.id), after.windows.toString())
        assertFalse(after.isOpen(BuiltInApp.EDITOR.id), after.windows.toString())
    }

    @Test
    fun whileThePersonTypesAiOpensBehindAndSaysWhere() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) {
            w.create("Quiet", null)
            w.desk.open(BuiltInApp.FILES.ref)
        }
        w.desk.person = { PersonState(typing = true, now = WorldDeskState.now()) }
        withContext(Dispatchers.Main) { w.write("b.js", "2") }
        val desk = w.desk.desk
        assertEquals(BuiltInApp.FILES.id, desk.front, "the window the person is in stays in front")
        assertTrue(desk.isOpen(BuiltInApp.EDITOR.id))
        assertTrue(w.desk.notices.tray.any { it.kind == NoticeKind.AI_IS_IN }, w.desk.notices.tray.toString())
    }

    @Test
    fun withFollowOffNothingIsRaised() = runBlocking {
        val w = worlds(WorldPrefs(typing = 0, follow = false))
        var forward = 0
        w.comeForward = { forward++ }
        withContext(Dispatchers.Main) {
            w.create("Marked", null)
            w.write("c.js", "3")
        }
        assertEquals(0, forward)
        assertFalse(w.desk.desk.isOpen(BuiltInApp.EDITOR.id))
        assertEquals(BuiltInApp.EDITOR.id, w.desk.desk.ai)
    }

    @Test
    fun theDeskIsKeptAndReadBack() = runBlocking {
        val w = worlds()
        val made = withContext(Dispatchers.Main) {
            val m = w.create("Kept", null)
            w.desk.open(BuiltInApp.TERMINAL.ref)
            w.desk.open(BuiltInApp.FILES.ref)
            w.desk.apply(DeskOp.Keep(BuiltInApp.TERMINAL.id, true))
            m
        }
        withContext(Dispatchers.Main) { delay(WorldDeskState.SAVE_AFTER_MS + 300) }
        val file = File(w.dir, "${made.id}/${DeskCodec.FILE}")
        assertTrue(file.isFile)
        val back = DeskCodec.decode(file.readText())
        assertEquals(listOf(BuiltInApp.TERMINAL.id, BuiltInApp.FILES.id), back.windows.map { it.app })
        assertEquals(BuiltInApp.FILES.id, back.front)
        assertTrue(back.window(BuiltInApp.TERMINAL.id)!!.kept)
        // Opened again, the world comes back on the same desk.
        val reopened = Worlds(w.dir).also { it.host = { host }; it.prefs = { WorldPrefs(typing = 0) } }
        withContext(Dispatchers.Main) {
            reopened.load()
            delay(300)
            reopened.openWorld(made.id)
        }
        assertEquals(BuiltInApp.FILES.id, reopened.desk.desk.front)
    }

    @Test
    fun thePersonsEditIsNeverOverwritten() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) {
            w.create("Mine", null)
            w.write("d.js", "ai")
            w.leave()
            w.showFile("d.js")
            w.personEdited("mine")
            w.saveEditor()
        }
        settle()
        val refused = withContext(Dispatchers.Main) { w.write("d.js", "ai again") }
        assertTrue(refused.isFailure && "read it again" in refused.exceptionOrNull()?.message.orEmpty(), refused.toString())
        assertEquals("mine", w.peek("d.js"))
        // Read again, Ai's edit is welcome.
        withContext(Dispatchers.Main) { w.read("d.js") }
        assertTrue(withContext(Dispatchers.Main) { w.write("d.js", "mine, and Ai's") }.isSuccess)
    }

    @Test
    fun theTerminalRunsWhatIsTypedAsThePerson() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) {
            w.create("Typed", null)
            w.write("e.js", "print('hi')")
            w.leave()
        }
        val h = TerminalProbe(w)
        withContext(Dispatchers.Main) {
            h.type("ls")
            h.type("cat e.js")
            h.type("js 40 - 1")
            h.type("nonsense")
        }
        settle()
        val lines = w.terminal.map { it.text }
        assertTrue("e.js" in lines, lines.toString())
        assertTrue("print('hi')" in lines, lines.toString())
        assertTrue(lines.any { it.startsWith("→ 39") }, lines.toString())
        assertTrue(w.terminal.any { it.kind == TermLine.Kind.ERR && "nonsense" in it.text }, lines.toString())
        // The person's own run: Ai was nowhere, so the desk has no turn.
        assertFalse(w.desk.desk.working)
    }

    /** The Terminal's command line without a window: its lines handed to the same runner the prompt uses. */
    private class TerminalProbe(private val w: Worlds) {
        fun type(line: String) = com.kaiharimoto.neue.world.system.runLineOn(w, line)
    }

    @Test
    fun anAppsKeyOpensBringsForwardThenMinimises() = runBlocking {
        val w = worlds()
        withContext(Dispatchers.Main) {
            w.create("Keys", null)
            w.desk.toggle(BuiltInApp.LIBRARY.ref)
        }
        assertEquals(BuiltInApp.LIBRARY.id, w.desk.desk.front)
        withContext(Dispatchers.Main) { w.desk.toggle(BuiltInApp.LIBRARY.ref) }
        assertTrue(w.desk.desk.window(BuiltInApp.LIBRARY.id)!!.minimised)
        assertNull(w.desk.desk.front)
        withContext(Dispatchers.Main) { w.desk.toggle(AppRef.parse("library")!!) }
        assertEquals(BuiltInApp.LIBRARY.id, w.desk.desk.front)
    }

    private companion object {
        const val SETTLE_MS = 200L
    }
}
