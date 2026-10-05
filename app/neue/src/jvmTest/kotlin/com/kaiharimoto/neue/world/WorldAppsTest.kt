package com.kaiharimoto.neue.world

import com.kaiharimoto.mastertool.core.ai.library.LibraryKind
import com.kaiharimoto.mastertool.core.ai.library.Shelf
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.world.RunLog
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldPrefs
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.apps.AppPaths
import com.kaiharimoto.mastertool.core.world.apps.UiEvent
import com.kaiharimoto.mastertool.core.world.apps.UiNode
import com.kaiharimoto.mastertool.core.world.desk.DeskCodec
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.sync.NeueSyncLocal
import com.kaiharimoto.neue.world.apps.AppHost
import com.kaiharimoto.neue.world.apps.UiWords
import com.kaiharimoto.neue.world.apps.WorldApps
import com.kaiharimoto.neue.world.apps.thoughts
import com.kaiharimoto.neue.world.apps.ThoughtsFilter
import com.kaiharimoto.neue.world.apps.Thought
import com.kaiharimoto.neue.world.browser.csvTable
import com.kaiharimoto.neue.world.browser.key
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.neue.world.library.WorldLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What runs inside the World's windows (`docs/world/DESKTOP.md` §13, agent C), headless: an app's events round trip
 * through `JsApp` off the main thread and its state reaches the disk; a throw is shown in words with its line and the
 * state kept; broken and unknown widgets are said in their place; an app's pages open as tabs; the Browser's tabs are kept
 * in `desk.json`; a long run's whole output reaches its log; the Library finds what Ai knows; and sync carries the desk.
 */
class WorldAppsTest {
    private val cards = listOf(Card(CardId(1), "Starter", "Effect Monster", "effect", "Add 1 card."), Card(CardId(2), "Brick", "Normal Monster", "normal", ""))
    private val deck = DeckEntry("d1", "Test", Deck(main = List(9) { CardId(1) } + List(31) { CardId(2) }), 0, 0)
    private val host = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, true) }
        override fun search(query: String, limit: Int) = cards.filter { query.lowercase() in it.name.lowercase() }
        override fun deck(id: String?) = deck
        override fun decks() = listOf(deck)
    }

    private fun worlds(): Worlds = Worlds(Files.createTempDirectory("world").toFile()).also { w ->
        w.host = { host }
        w.prefs = { WorldPrefs(typing = 0, follow = false) }
    }

    private suspend fun <T> main(f: suspend () -> T): T = withContext(Dispatchers.Main) { f() }

    private suspend fun until(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!main { ok() } && System.currentTimeMillis() < end) delay(20)
        assertTrue(main { ok() }, "waited for $what")
    }

    private val counter = """
        function init() { return { n: 0, label: 'Count' }; }
        function view(s) {
          return ui.col([
            ui.stat({ value: String(s.n), label: s.label }),
            ui.button({ id: 'add', label: 'Add one', kind: 'primary' }),
            ui.input({ id: 'label', label: 'Label', value: s.label })
          ]);
        }
        function on(s, e) {
          if (e.id === 'add') s.n = s.n + 1;
          else if (e.id === 'label') s.label = e.value;
          else if (e.id === 'boom') {
            var x = null;
            x.y = 1;
          }
          else if (e.id === 'show') ygo.show.stat({ value: String(s.n), label: 'Counted' }, { id: 'counted' });
          return s;
        }
    """.trimIndent()

    private suspend fun made(w: Worlds, slug: String = "counter", code: String = counter): AppHost {
        main { w.create("Apps", null) }
        val m = main { w.apps.make(AppManifest(slug, name = "Counter", kind = "tracker"), code, WorldEvent.AI) }
        assertTrue(m.isSuccess, m.exceptionOrNull()?.message)
        val h = main { w.apps.host(slug) }
        until("the first screen") { h.tree != null || h.failure != null }
        assertNull(h.failure, h.failure?.words)
        return h
    }

    private fun stat(h: AppHost): String = ((h.tree!!.root as UiNode.Col).children.first() as UiNode.Stat).value

    @Test
    fun anEventRoundTripsAndItsStateReachesTheDisk() = runBlocking {
        val w = worlds()
        val h = made(w)
        assertEquals("0", stat(h))
        repeat(3) { main { w.apps.send(h, "add", UiEvent.PRESS, JsonPrimitive(true)) } }
        until("three presses answered") { h.answered == 3 && h.idle }
        assertEquals("3", stat(h))
        main { w.apps.send(h, "label", UiEvent.CHANGE, JsonPrimitive("Hands")) }
        until("the label changed") { h.answered == 4 }
        assertEquals("Hands", ((h.tree!!.root as UiNode.Col).children.first() as UiNode.Stat).label)
        // Written 500 ms after the last event, atomically, under the app's own folder.
        val file = File(w.dir, "${w.open!!.id}/${AppPaths.state("counter")}")
        until("the state on disk", 5_000) { file.isFile && "\"n\":3" in file.readText().replace(" ", "") }
        // Logged, so the person sees every app change.
        assertTrue(w.activity.any { it.kind == WorldEvent.Kind.APP && it.app == "counter" })
        // Ai reads the screen in words, ids and all.
        val words = UiWords.describe(h.tree!!.root)
        assertTrue("button “Add one” [add]" in words, words)
        assertTrue("stat: 3" in words, words)
    }

    @Test
    fun aThrowIsShownWithItsLineAndTheStateIsKept() = runBlocking {
        val w = worlds()
        val h = made(w)
        main { w.apps.send(h, "add", UiEvent.PRESS, JsonPrimitive(true)) }
        until("one press") { h.answered == 1 }
        main { w.apps.send(h, "boom", UiEvent.PRESS, JsonPrimitive(true)) }
        until("the throw") { h.failure != null && h.idle }
        val f = h.failure!!
        assertTrue(f.words.startsWith("on(press boom) failed"), f.words)
        assertEquals(14, f.line, f.words)
        assertEquals("1", stat(h), "the state is as it was")
        assertTrue(w.activity.any { it.kind == WorldEvent.Kind.APP && "failed" in it.text }, w.activity.toString())
        // Ai hears of it at its next look, once.
        assertTrue(main { w.describe() }.contains("Apps that threw"))
        assertFalse(main { w.describe() }.contains("Apps that threw"))
        // The next good event clears it.
        main { w.apps.send(h, "add", UiEvent.PRESS, JsonPrimitive(true)) }
        until("the next press") { h.answered == 2 }
        assertNull(h.failure)
    }

    @Test
    fun aViewThatThrowsKeepsTheLastScreenAndAnAppThatWillNotRunIsNotMade() = runBlocking {
        val w = worlds()
        main { w.create("Apps", null) }
        val bad = main { w.apps.make(AppManifest("bad"), "function init() { return {}; }\nfunction view(s) { return nope(); }\nfunction on(s, e) { return s; }", WorldEvent.AI) }
        assertTrue(bad.isFailure)
        assertTrue("line 2" in bad.exceptionOrNull()!!.message.orEmpty(), bad.exceptionOrNull()!!.message)
        assertFalse(File(w.dir, "${w.open!!.id}/apps/bad").exists(), "nothing written for an app that did not run")

        val flaky = """
            function init() { return { broken: false }; }
            function view(s) { if (s.broken) throw new Error('drawn wrong'); return ui.text('fine'); }
            function on(s, e) { s.broken = true; return s; }
        """.trimIndent()
        val made = main { w.apps.make(AppManifest("flaky"), flaky, WorldEvent.AI) }
        assertTrue(made.isSuccess)
        val h = main { w.apps.host("flaky") }
        until("its screen") { h.tree != null }
        main { w.apps.send(h, "x", UiEvent.PRESS, JsonPrimitive(true)) }
        until("the view's throw") { h.failure != null && h.idle }
        assertTrue(h.stale, "the last good screen stays, dimmed")
        assertTrue(h.tree!!.root is UiNode.Text)
        assertTrue("drawn wrong" in h.failure!!.words, h.failure!!.words)
    }

    @Test
    fun brokenAndUnknownWidgetsAreSaidInTheirPlace() = runBlocking {
        val w = worlds()
        val code = """
            function init() { return {}; }
            function view(s) { return ui.col([ui.text('above'), { ui: 'hologram' }, ui.stepper({ label: 'no id' }), ui.text('below')]); }
            function on(s, e) { return s; }
        """.trimIndent()
        val h = made(w, "odd", code)
        val kids = (h.tree!!.root as UiNode.Col).children
        assertTrue(kids[1] is UiNode.Unknown, kids.toString())
        assertTrue(kids[2] is UiNode.Broken, kids.toString())
        assertTrue(kids[3] is UiNode.Text, "the rest of the screen is drawn")
        val words = UiWords.describe(h.tree!!.root)
        assertTrue("unknown widget “hologram”" in words && "BROKEN" in words, words)
    }

    @Test
    fun anAppsPagesOpenAsTabsAndANewVersionKeepsTheState() = runBlocking {
        val w = worlds()
        val h = made(w)
        main { w.apps.send(h, "add", UiEvent.PRESS, JsonPrimitive(true)) }
        main { w.apps.send(h, "show", UiEvent.PRESS, JsonPrimitive(true)) }
        until("both answered") { h.answered == 2 && h.idle }
        assertNotNull(w.open!!.board("counted"))
        assertEquals("apps/counter", w.open!!.board("counted")!!.source)
        assertEquals(WorldAddress.Board("counted").format(), w.browser.tabs.current?.address, "the person's press opens its page")

        // A new version: the state kept, the screen drawn by the new code.
        val v2 = counter.replace("label: s.label })", "label: s.label + ' (v2)' })")
        val changed = main { w.apps.change("counter", v2, by = WorldEvent.AI) }
        assertEquals(2, changed.getOrThrow().version)
        until("the new screen") { ((h.tree!!.root as UiNode.Col).children.first() as UiNode.Stat).label.endsWith("(v2)") }
        assertEquals("1", stat(h))
        assertEquals(listOf(1), main { w.apps.versions("counter") })
        // Back to v1 is v3.
        assertEquals(3, main { w.apps.back("counter", 1) }.getOrThrow().version)
        // The person's save from the Editor is a version too.
        val saved = main { w.write(WorldApps.codePath("counter"), counter.replace("'Count'", "'Tally'"), WorldEvent.YOU) }
        assertTrue(saved.isSuccess, saved.exceptionOrNull()?.message)
        assertEquals(4, w.apps.manifest("counter")!!.version)
        assertEquals(counter.replace("'Count'", "'Tally'"), w.read(WorldApps.codePath("counter")))
        // Ai's state reads come enveloped: the person's typing is data, never instructions.
        assertTrue(main { w.apps.stateForAi("counter") }!!.startsWith("<untrusted"), "enveloped")
    }

    @Test
    fun aiPressesItsAppAndReadsTheScreenInWords() = runBlocking {
        val w = worlds()
        made(w)
        val r = main { w.apps.press("counter", "add", UiEvent.PRESS, JsonPrimitive(true)) }.getOrThrow()
        assertTrue("stat: 1" in r, r)
        val bad = main { w.apps.press("counter", "boom", UiEvent.PRESS, JsonPrimitive(true)) }.getOrThrow()
        assertTrue("It went wrong" in bad && "line 14" in bad, bad)
    }

    @Test
    fun theBrowsersTabsAreKeptInTheDeskAndItsKeysMoveThem() = runBlocking {
        val w = worlds()
        val made = main { w.create("Tabs", null) }
        main { w.putBoard("one", "stat", "One", """{"value":"1","label":"one"}""", null, WorldEvent.YOU) }.getOrThrow()
        main { w.putBoard("two", "stat", "Two", """{"value":"2","label":"two"}""", null, WorldEvent.AI) }.getOrThrow()
        // Ai's page opens beside the person's, unselected and marked; pinned again it updates in place.
        assertEquals(2, w.browser.tabs.tabs.size)
        assertEquals("world://boards/one", w.browser.tabs.current?.address)
        assertTrue(w.browser.tabs.tabs.single { it.address == "world://boards/two" }.mark)
        main { w.putBoard("two", "stat", "Two again", """{"value":"2","label":"two"}""", null, WorldEvent.AI) }.getOrThrow()
        assertEquals(2, w.browser.tabs.tabs.size)
        // world_show with open: false pins only.
        main { w.putBoard("three", "stat", "Three", """{"value":"3","label":"three"}""", null, WorldEvent.AI, tab = false) }.getOrThrow()
        assertEquals(2, w.browser.tabs.tabs.size)
        // The keys: next tab, back and forward, a new tab, close.
        main { w.browser.key(DeskAction.WORLD_TAB_NEXT) }
        assertEquals("world://boards/two", w.browser.tabs.current?.address)
        main { w.browser.go("world://home?q=one") }
        main { w.browser.key(DeskAction.WORLD_TAB_BACK) }
        assertEquals("world://boards/two", w.browser.tabs.current?.address)
        main { w.browser.key(DeskAction.WORLD_TAB_FORWARD) }
        assertEquals("world://home?q=one", w.browser.tabs.current?.address)
        main { w.browser.key(DeskAction.WORLD_TAB_NEW) }
        assertEquals(3, w.browser.tabs.tabs.size)
        assertTrue(main { w.browser.key(DeskAction.WORLD_CLOSE) })
        assertEquals(2, w.browser.tabs.tabs.size)
        // An app's address opens its window, never a tab.
        main { w.browser.go("world://apps/nothing-here") }
        assertEquals(2, w.browser.tabs.tabs.size)

        // Kept in desk.json, and read back when the world opens again.
        val desk = File(w.dir, "${made.id}/${DeskCodec.FILE}")
        until("desk.json written", 5_000) { desk.isFile && DeskCodec.decode(desk.readText()).tabs.tabs.size == 2 }
        val again = worlds().let { Worlds(w.dir).also { x -> x.host = { host }; x.prefs = { WorldPrefs(typing = 0, follow = false) } } }
        main { again.load() }
        until("the world listed") { again.list.isNotEmpty() }
        main { again.openWorld(made.id) }
        until("the tabs read back") { again.browser.tabs.tabs.size == 2 }
        assertEquals(w.browser.tabs.selected, again.browser.tabs.selected)
    }

    @Test
    fun aLongRunsWholeOutputGoesToItsLog() = runBlocking {
        val w = worlds()
        main { w.create("Long", null) }
        // About 120 KB printed: past the 64 KB the Terminal keeps.
        val run = main { w.run(null, "for (var i = 0; i < 4000; i++) print('line ' + i + ' ' + 'x'.repeat(24));", "js") }.getOrThrow()
        assertTrue(run.record.cut, "the record keeps what fits")
        val log = File(w.dir, "${w.open!!.id}/files/" + RunLog.path(w.activity.last { it.kind == WorldEvent.Kind.RUN }.t))
        assertTrue(log.isFile, "the whole output is at ${log.path}")
        val lines = log.readLines()
        assertEquals("line 0 " + "x".repeat(24), lines.first())
        assertEquals("line 3999 " + "x".repeat(24), lines.last { it.isNotBlank() })
        assertTrue(run.record.out.contains(RunLog.path(w.activity.last { it.kind == WorldEvent.Kind.RUN }.t)), "the record says where the rest is")
    }

    @Test
    fun theLibraryListsOpensAndSearchesWhatAiKnows() = runBlocking {
        val data = Files.createTempDirectory("data").toFile()
        File(data, "ai/guides").mkdirs()
        File(data, "ai/guides/d1.md").writeText("# Openings\n\nGoing first, open Starter.\n\n## Lines\n\nStarter searches the engine.\n")
        File(data, "ai/MEMORY.md").writeText("Lessons: seed every simulation.\n")
        val lib = WorldLibrary(data)
        main { lib.refresh(mapOf("d1" to "Test"), emptyMap()) }
        lib.deck = "d1"
        assertEquals(listOf(LibraryKind.GUIDE), lib.catalog.shelf(Shelf.THIS_DECK, "d1").map { it.kind })
        assertEquals(listOf(LibraryKind.LESSONS), lib.catalog.shelf(Shelf.AI).map { it.kind })
        main { lib.open(lib.catalog.shelf(Shelf.THIS_DECK, "d1").single()) }
        until("opened") { lib.opened != null }
        assertEquals(listOf("Openings", "Lines"), lib.opened!!.sections.map { it.title })
        assertTrue(lib.opened!!.count.startsWith("Guide · "), lib.opened!!.count)
        main { lib.search("starter search") }
        until("found") { !lib.searching && lib.hits.isNotEmpty() }
        assertEquals("Starter searches the engine.", lib.hits.single().line)
        // ygo.knowledge reads the same catalogue, read-only.
        assertEquals(2, lib.knowledge.list(null).size)
    }

    @Test
    fun thoughtsIsOneStreamWithItsActionsLinked() {
        val turns = listOf(
            ChatTurn.user("How often does it open?"),
            ChatTurn(Role.ASSISTANT, listOf(Part.Reasoning("Simulate."), Part.ToolUse("t1", "world_show", JsonObject(mapOf("id" to JsonPrimitive("b1")))))),
            ChatTurn(Role.USER, listOf(Part.ToolResult("t1", "world_show", "ok", summary = "Pinned “Opens”"))),
            ChatTurn(Role.ASSISTANT, listOf(Part.Text("63 %."))),
        )
        val all = thoughts(turns)
        assertEquals(4, all.size)
        val did = all.filterIsInstance<Thought.Did>().single()
        assertEquals("◧", did.glyph)
        assertEquals(com.kaiharimoto.neue.world.apps.Goes.Page("world://boards/b1"), did.goes)
        assertEquals(2, thoughts(turns, ThoughtsFilter.WORDS).size)
        assertEquals(1, thoughts(turns, ThoughtsFilter.ACTIONS).size)
    }

    @Test
    fun aCsvFileIsATable() {
        val t = csvTable("Card,Copies\n\"Ash, Blossom\",3\nNibiru,1\n")
        assertTrue("\"Ash, Blossom\"" in t, t)
        assertEquals(2, com.kaiharimoto.mastertool.core.world.WorldTable.parse(t).getOrThrow().rows.size)
    }

    @Test
    fun syncAndBackupsCarryTheDeskAndTheApps() {
        listOf("w1/desk.json", "w1/apps/hand-odds/app.json", "w1/apps/hand-odds/main.js", "w1/apps/hand-odds/state.json", "w1/apps/hand-odds/versions/2.js", "w1/apps/hand-odds/state.broken.json", "w1/files/out/k3.log")
            .forEach { assertTrue(NeueSyncLocal.worldSyncs(it), it) }
        listOf("w1/desk.json.tmp", "w1/apps/hand-odds/state.json.tmp", "w1/.py/ygo.py").forEach { assertFalse(NeueSyncLocal.worldSyncs(it), it) }
    }
}
