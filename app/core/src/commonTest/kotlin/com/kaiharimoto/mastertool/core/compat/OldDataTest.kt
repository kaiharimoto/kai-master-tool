package com.kaiharimoto.mastertool.core.compat

import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.CourseCodec
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import com.kaiharimoto.mastertool.core.ai.exam.ExamLog
import com.kaiharimoto.mastertool.core.ai.report.book.BookFreshness
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.backup.BackupManifest
import com.kaiharimoto.mastertool.core.backup.Backups
import com.kaiharimoto.mastertool.core.cards.BanlistCodec
import com.kaiharimoto.mastertool.core.data.PoolRecord
import com.kaiharimoto.mastertool.core.duel.DuelCodec
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.effects.FxAsked
import com.kaiharimoto.mastertool.core.duel.effects.FxAsks
import com.kaiharimoto.mastertool.core.duel.effects.FxCodec
import com.kaiharimoto.mastertool.core.duel.effects.FxRead
import com.kaiharimoto.mastertool.core.duel.effects.FxRequest
import com.kaiharimoto.mastertool.core.duel.effects.FxReviews
import com.kaiharimoto.mastertool.core.duel.effects.FxShelf
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.FxVocab
import com.kaiharimoto.mastertool.core.duel.effects.Opt
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeDecks
import com.kaiharimoto.mastertool.core.duel.mapper.BoardLibrary
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPresets
import com.kaiharimoto.mastertool.core.duel.mapper.MapperRun
import com.kaiharimoto.mastertool.core.duel.mapper.StarterRun
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResultCodec
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.sync.Manifest
import com.kaiharimoto.mastertool.core.sync.Sync
import com.kaiharimoto.mastertool.core.sync.SyncPrefs
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.apps.AppCodec
import com.kaiharimoto.mastertool.core.world.apps.AppKind
import com.kaiharimoto.mastertool.core.world.apps.StateRead
import com.kaiharimoto.mastertool.core.world.apps.UiNode
import com.kaiharimoto.mastertool.core.world.apps.UiTree
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskCodec
import com.kaiharimoto.mastertool.core.world.desk.IconCell
import com.kaiharimoto.mastertool.core.world.desk.Snap
import com.kaiharimoto.mastertool.core.world.desk.WindowMode
import com.kaiharimoto.mastertool.core.world.desk.WorldHome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * What older versions wrote still reads, and what newer ones write does not break an older reader
 * (1.0.69, kai: "progress in my current version … might be outdated by changes we make in the future").
 * Each stored shape is held here as an older build left it; a change that stops one reading fails here
 * before it reaches a device. A release that changes what is stored adds its old shape to this file.
 */
class OldDataTest {
    /** As `PreferencesRepository` reads its documents. */
    private val prefs = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun settingsFromTheFirstDesktopBuildsStillRead() {
        // 1.0.9: a handful of keys, the old autosave name, a field read by nothing since.
        val old = """{"theme":"INK","scale":1.25,"poolVisible":false,"lensKeys":true,"autoSaveOn":false}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), old).sanitised()
        assertEquals(NeueTheme.INK, p.theme)
        assertEquals(1.25f, p.scale)
        assertEquals(false, p.autoSave)
        // Everything added since comes in at its default.
        assertEquals(SyncPrefs.OFF, p.sync.service)
        assertEquals("", p.start.seen)
        assertTrue(p.ai.enabled)
    }

    @Test
    fun settingsFromALaterVersionStillReadHere() {
        val future = """{"theme":"PAPER","somethingNew":{"a":1},"ai":{"name":"Kai","futureField":true},"sync":{"service":"folder","folder":"/x","later":2}}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), future)
        assertEquals("Kai", p.ai.name)
        assertEquals("/x", p.sync.folder)
    }

    @Test
    fun aPoolRecordFrom1099ReadsAsFetchedWithoutReleaseData() {
        // 1.0.99 wrote the pool's record with no `misc`: read as false, so 1.1.0 fetches the pool once more.
        val r = prefs.decodeFromString(PoolRecord.serializer(), """{"version":"147.20","bytes":21336136}""")
        assertEquals("147.20", r.version)
        assertEquals(false, r.misc)
        // And 1.1.0's record reads in 1.0.99's shape too, the new key ignored.
        val back = prefs.decodeFromString(PoolRecord.serializer(), prefs.encodeToString(PoolRecord.serializer(), r.copy(misc = true)))
        assertTrue(back.misc)
    }

    @Test
    fun syncFilesFromALaterVersionStillRead() {
        val m = Sync.json.decodeFromString(Manifest.serializer(), """{"device":"d1","items":{"decks/a.json":{"hash":"ab","at":5,"device":"d1","signature":"x"}},"format":2,"extra":[]}""")
        assertEquals("ab", m.items.getValue("decks/a.json").hash)
        val b = Backups.json.decodeFromString(BackupManifest.serializer(), """{"format":2,"version":"1.0.90","at":1,"reason":"Weekly","encrypted":false}""")
        assertEquals("1.0.90", b.version)
    }

    @Test
    fun aReadersGuideFrom1067StillReads() {
        val old = """{"title":"Labrynth","bigIdea":"Set and pass.","chapters":[{"id":"lessons","title":"Lessons","sections":[
            {"id":"lessons/a","title":"A","blocks":[{"type":"text","text":"Words"},{"type":"lesson","maxim":"Open Welcome","card":"Welcome Labrynth","number":"74%"}]}]}]}"""
        val book = assertNotNull(GuideBook.read(old))
        assertEquals(2, book.chapters.single().sections.single().blocks.size)
    }

    @Test
    fun aReadersGuideFrom1098WithoutItsDeckReadsDeckUnknown() {
        // 1.0.98: the book as `reader_guide` stamped it, the notes' hash only; 1.0.99 records the deck per chapter.
        val old = """{"title":"Labrynth","bigIdea":"Set and pass.","updatedAt":1759000000000,"notesHash":"1x2y3z","chapters":[
            {"id":"lessons","title":"Lessons","summary":"What decides games","sections":[{"id":"lessons/a","title":"A","blocks":[
            {"type":"lesson","id":"lessons/a/1","maxim":"Open Welcome","card":"Welcome Labrynth","number":"74%"}]}]},
            {"id":"lines","title":"Lines"}]}"""
        val book = assertNotNull(GuideBook.read(old))
        assertEquals("", book.deckPrint)
        assertTrue(book.chapters.all { it.deckPrint.isEmpty() && it.notesHash.isEmpty() })
        val status = BookFreshness.of(book, "abc123", "1x2y3z")
        assertEquals(BookFreshness.DeckState.UNKNOWN, status.deck)
        assertEquals(false, status.stale)
        // The new field round-trips; an older build skips it, as `GuideBook.json` ignores unknown keys.
        val again = assertNotNull(GuideBook.read(GuideBook.write(book.copy(deckPrint = "abc123"))))
        assertEquals("abc123", again.deckPrint)
    }

    @Test
    fun aDuelFrom1074StillReads() {
        // 1.0.74: the first duel the Duel page kept in `<data>/duel/current.json`, with an action from a later build.
        val old = """{"header":{"id":"d1","seed":7,"seats":[{"name":"Kai","main":[1,2,3,4,5,6],"extra":[9]},{"name":"Rival"}],"solo":true,"handSize":5},
            "entries":[{"i":0,"group":0,"action":{"t":"shuffle","seat":0,"salt":11}},{"i":1,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":2,"seat":0,"group":1,"action":{"t":"move","uid":1,"to":{"t":"zone","seat":0,"kind":"MONSTER","index":2},"pos":"FACE_UP_ATK","how":"normal"}},
            {"i":3,"seat":0,"group":2,"action":{"t":"hologram","glow":3}}],"cursor":4,"version":1}"""
        val record = assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old))
        val game = com.kaiharimoto.mastertool.core.duel.DuelGame.of(record)
        assertEquals(1, game.state.onField().size)
        assertEquals(4, game.state.seats[0].hand.size)
        assertTrue(game.entries.last().action is com.kaiharimoto.mastertool.core.duel.DuelAction.Unknown)
        assertTrue(com.kaiharimoto.mastertool.core.duel.DuelCodec.encode(game.record()).contains("\"glow\":3"))
        // Its settings, as 1.0.74 wrote them.
        val p = prefs.decodeFromString(NeuePreferences.serializer(), """{"duel":{"twoSided":false,"knowledge":"seat","names":["Kai","Rival"]}}""").duel
        assertEquals(false, p.twoSided)
        assertEquals("seat", p.knowledge)
    }

    @Test
    fun aShootoutTrialFrom114ReadsWithNoDrawsAndATrialWithDrawsReadsBack() {
        // 1.1.4 kept no turn's draw and no draws by effects: read as none.
        val old = ShootoutCodec.decode("""{"version":1,"deck":"d","trials":[{"id":"s-1","stratum":"G1_FIRST","hand":[1,2,3,4,5],"opponent":[6,7,8,9,10,11],"answer":"LEAN_WIN"}]}""")
        val t = old!!.trials.single()
        assertEquals(null, t.theirTurnDraw)
        assertEquals(emptyList(), t.theyDrew)
        val withDraws = t.copy(theirTurnDraw = 11, theyDrew = listOf(40, 41), drew = listOf(30))
        val back = ShootoutCodec.decode(ShootoutCodec.encode(old.copy(trials = listOf(withDraws))))!!.trials.single()
        assertEquals(11, back.theirTurnDraw)
        assertEquals(listOf(40, 41), back.theyDrew)
        assertEquals(listOf(30), back.drew)
    }

    @Test
    fun aShootoutTrialFromBeforeTheSixthReadsWithNoneAndItsSixthReadsBack() {
        // Before 2026-10 a trial kept no sixth (the going-second hand's turn's draw) and a comparison no draws at all.
        val old = ShootoutCodec.decode("""{"version":1,"deck":"d","trials":[{"id":"s-1","stratum":"G1_SECOND","hand":[1,2,3,4,5,6],"opponent":[6,7,8,9,10],"answer":"LEAN_WIN","turnDraw":4},{"id":"s-2","stratum":"G1_SECOND","kind":"compare","left":[1,2,3,4,5,6],"right":[1,2,3,4,5,7],"opponent":[6,7,8,9,10],"prefer":"left"}]}""")!!
        assertEquals(listOf(null, null), old.trials.map { it.sixth })
        assertEquals(null, old.trials[1].leftSixth)
        val now = old.copy(trials = listOf(old.trials[0].copy(sixth = 4), old.trials[1].copy(leftSixth = 6, rightSixth = 7)))
        val back = ShootoutCodec.decode(ShootoutCodec.encode(now))!!
        assertEquals(4, back.trials[0].sixth)
        assertEquals(6, back.trials[1].leftSixth)
        assertEquals(7, back.trials[1].rightSixth)
    }

    @Test
    fun aShootoutFrom112StillReads() {
        // 1.1.2 (Phase S stage 2): a matchup's trials in `<data>/shootout/<deck>/<opponent>.json` — a rating, a comparison, a
        // sided trial with its plans, Ai's answer kept apart — and a key and a trial kind from a later build.
        val old = """{"version":1,"deck":"d-1","opponent":"o-2","opponentName":"Yubel","trials":[
            {"id":"s1-0","at":1760000000000,"stratum":"G1_FIRST","hand":[14558127,1,2,3,4],"opponent":[5,6,7,8,9,10],"answer":"LEAN_WIN","reason":"plain","ms":4100,"session":"s1"},
            {"id":"s1-1","at":1760000004000,"stratum":"G1_SECOND","kind":"compare","left":[1,2,3,4,5,6],"right":[1,2,3,4,5,7],"opponent":[5,6,7,8,9],"prefer":"right","session":"s1"},
            {"id":"s1-2","at":1760000009000,"stratum":"SIDED_FIRST","hand":[1,2,3,4,5],"opponent":[5,6,7,8,9,10],"answer":"CLEAR_LOSS","plans":{"mine":"-3 +11","theirs":"="},"ai":{"answer":"LEAN_LOSS","sure":0.6},"tags":["interrupted"],"decisive":3},
            {"id":"s2-0","stratum":"G1_FIRST","kind":"triple","hands":[[1],[2],[3]],"glow":2}
        ],"fit":{"cached":true}}"""
        val log = assertNotNull(ShootoutCodec.decode(old))
        assertEquals("o-2", log.opponent)
        assertEquals(4, log.trials.size)
        assertEquals("LEAN_WIN", log.trials[0].answer)
        assertEquals("right", log.trials[1].prefer)
        assertEquals("-3 +11", log.trials[2].plans?.mine)
        assertEquals("LEAN_LOSS", log.trials[2].ai?.answer)
        assertTrue(log.trials.take(3).all { it.blind })
        // Written again, it reads the same.
        assertEquals(log, ShootoutCodec.decode(ShootoutCodec.encode(log)))
    }

    @Test
    fun aShootoutTeachingAiStillReads() {
        // Phase S stage 3: Ai's answers as trials of their own (`judge: ai`, `of` the person's trial, `mode`), with what it
        // was shown (examples, rubric, the model's prediction, the kind, the decks' print, when asked) and the question it
        // would ask; the person's notes beside the trials; the gate's settings — and a key from a later build in each.
        val old = """{"version":1,"deck":"d-1","opponent":"o-2","opponentName":"Yubel","trials":[
            {"id":"s1-0","at":1760000000000,"stratum":"G1_FIRST","hand":[1,2,3,4,5],"opponent":[5,6,7,8,9,10],"answer":"LEAN_WIN","mode":"apprentice","session":"s1"},
            {"id":"s1-0a","at":1760000000500,"stratum":"G1_FIRST","hand":[1,2,3,4,5],"opponent":[5,6,7,8,9,10],"answer":"CLEAR_WIN","judge":"ai","of":"s1-0","mode":"apprentice",
             "ai":{"answer":"CLEAR_WIN","sure":0.85,"why":"a starter into one hand trap","model":"m","examples":["s0-3","s0-9"],"rubric":"a1b2c3d4e5f6","predicted":0.66,"kind":"first·starter·interaction","print":"0123456789ab","asked":1759999999000,"question":"Does Ash on the search stop it?","glow":1}},
            {"id":"s1-1","at":1760000004000,"stratum":"G1_SECOND","hand":[1,2,3,4,5,6],"opponent":[5,6,7,8,9],"answer":"LEAN_LOSS","sawAi":true,"mode":"supervised"},
            {"id":"s1-2","at":1760000009000,"stratum":"G1_SECOND","hand":[1,2,3,4,5,6],"opponent":[5,6,7,8,9],"answer":"COIN_FLIP","judge":"ai","mode":"solo","ai":{"answer":"COIN_FLIP","sure":0.92}}
        ],"notes":[{"trial":"s1-0","text":"only wins if they have no Imperm","at":1760000001000,"question":"Does Ash on the search stop it?","mood":"x"},{"broken":true}],
        "trust":{"bar":0.85,"sure":0.75,"solo":true,"later":"yes"}}"""
        val log = assertNotNull(ShootoutCodec.decode(old))
        assertEquals(4, log.trials.size)
        val ai = log.trials[1]
        assertEquals("ai", ai.judge)
        assertEquals("s1-0", ai.of)
        assertEquals("apprentice", ai.mode)
        assertEquals(listOf("s0-3", "s0-9"), ai.ai?.examples)
        assertEquals("first·starter·interaction", ai.ai?.kind)
        assertEquals(1759999999000, ai.ai?.asked)
        assertEquals("Does Ash on the search stop it?", ai.ai?.question)
        assertTrue(log.trials[2].sawAi && !log.trials[2].blind)
        assertEquals("solo", log.trials[3].mode)
        assertEquals(1, log.notes.size, "a note that will not read is dropped alone")
        assertEquals("only wins if they have no Imperm", log.notesOn("s1-0").single().text)
        assertEquals(0.85, log.trusted.bar)
        assertEquals(true, log.trusted.solo)
        // A 1.1.2 log has neither, and reads with the defaults.
        val older = assertNotNull(ShootoutCodec.decode("""{"version":1,"deck":"d","trials":[]}"""))
        assertTrue(older.notes.isEmpty())
        assertEquals(0.9, older.trusted.bar)
        assertEquals(false, older.trusted.solo)
        assertEquals(log, ShootoutCodec.decode(ShootoutCodec.encode(log)))
    }

    @Test
    fun aWorldFrom1097StillReads() {
        // 1.0.97: Ai World's record in `<data>/world/<id>/world.json`, a board from a later build among its own, a line of its
        // log with a kind this build does not know, and its device-only settings.
        val old = """{"id":"wabc","title":"Openings","scope":"deck:d1","created":1,"updated":2,"open":"sim.js",
            "boards":[{"id":"starter","title":"Opens a starter","kind":"stat","payload":"{\"value\":\"74%\",\"label\":\"x\"}","x":0,"y":0,"note":"seed 1"},
            {"id":"later","kind":"hologram","payload":"?"}],"future":{"a":1}}"""
        val w = assertNotNull(com.kaiharimoto.mastertool.core.world.WorldCodec.decode(old))
        assertEquals("Openings", w.title)
        // A board kind from a newer build is kept as it is, so this build's next save does not lose it.
        assertEquals(listOf("starter", "later"), w.boards.map { it.id })
        assertEquals("hologram", w.boards[1].kind)
        assertEquals(null, w.boards[1].type)
        assertTrue("\"hologram\"" in com.kaiharimoto.mastertool.core.world.WorldCodec.encode(w))
        val log = com.kaiharimoto.mastertool.core.world.WorldCodec.events(
            """{"t":5,"kind":"run","by":"ai","path":"sim.js","text":"Ran sim.js","run":{"lang":"js","ok":true,"out":"hi","boards":["starter"]}}
{"t":6,"kind":"teleport","text":"?"}""",
        )
        // The log is only ever appended to: an event of a kind from a newer build reads as a note, and stays in the file.
        assertEquals(2, log.size)
        assertEquals(com.kaiharimoto.mastertool.core.world.WorldEvent.Kind.NOTE, log[1].kind)
        val p = prefs.decodeFromString(NeuePreferences.serializer(), """{"world":{"python":true,"pythonPath":"/usr/bin/python3","typing":0}}""").world
        assertTrue(p.python)
        assertEquals(0, p.typing)
        assertTrue(p.follow)
    }

    @Test
    fun aWorldFrom1097OpensOnADesktop() {
        // 1.1.x (`docs/world/DESKTOP.md` §12.1): a 1.0.97 world has no `desk.json` and no `apps/`. It opens on an empty
        // desktop, its boards are pages at world://home, and their canvas places are written back as they were.
        val old = """{"id":"wabc","title":"Openings","created":1,"updated":2,"open":"sim.js",
            "boards":[{"id":"starter","title":"Opens a starter","kind":"stat","payload":"{\"value\":\"74%\",\"label\":\"x\"}","x":552.0,"y":412.0,"w":600.0,"h":300.0,"updated":5,"source":"sim.js"},
            {"id":"web","title":"Card web","kind":"graph","payload":"{}","x":0,"y":0,"updated":6}]}"""
        val w = assertNotNull(WorldCodec.decode(old))
        val desk = DeskCodec.decode(null)
        assertTrue(desk.windows.isEmpty())
        assertEquals(null, desk.front)
        assertTrue(desk.tabs.tabs.isEmpty())
        val log = WorldCodec.events("""{"t":4,"kind":"run","by":"ai","path":"sim.js","text":"Ran sim.js","run":{"lang":"js","ok":true,"boards":["starter"]}}""")
        val pages = WorldHome.groups(w, log)
        assertEquals(setOf("starter", "web"), pages.flatMap { it.boards }.map { it.id }.toSet())
        // Written back: every place as it was, though nothing draws the canvas now.
        val again = assertNotNull(WorldCodec.decode(WorldCodec.encode(w)))
        assertEquals(w.boards.map { listOf(it.x, it.y, it.w, it.h) }, again.boards.map { listOf(it.x, it.y, it.w, it.h) })
        assertEquals(listOf(552.0, 412.0, 600.0, 300.0), again.board("starter")!!.let { listOf(it.x, it.y, it.w, it.h) })
        // Its device settings from 1.0.97 read with the desktop's defaults: the five pins, the avatar on, recede on.
        val p = prefs.decodeFromString(NeuePreferences.serializer(), """{"world":{"python":false,"typing":600,"follow":false,"open":"wabc"}}""").world
        assertEquals(BuiltInApp.PINNED, p.pinned)
        assertEquals(listOf("files", "editor", "terminal", "browser", "thoughts"), p.pinned)
        assertTrue(p.avatar)
        assertTrue(p.recede)
        assertEquals(false, p.follow)
        // And a 1.1.x log line of an app reads in 1.0.97's shape too: kind and slug a newer build adds, kept.
        val appLine = WorldCodec.events("""{"t":9,"kind":"app","by":"ai","path":"apps/hand-odds/main.js","text":"Made","app":"hand-odds"}""").single()
        assertEquals(WorldEvent.Kind.APP, appLine.kind)
        assertEquals("hand-odds", appLine.app)
    }

    @Test
    fun aDeskFromANewerBuildReads() {
        // A desk.json a later build wrote: keys it added, a window mode it added, a window of an app kind this build
        // does not know, a tab with fields of its own. What this build can draw reads; nothing fails.
        val newer = """{"version":3,"windows":[
              {"app":"editor","mode":"maximised","frame":{"x":0.1,"y":0.1,"w":0.5,"h":0.6,"z":3},"by":"ai","turn":4,"used":9,"glass":true},
              {"app":"terminal","mode":"tiled","frame":{"x":0.2,"y":0.2,"w":0.4,"h":0.3}},
              {"app":"spreadsheet","frame":{"x":0.0,"y":0.0,"w":0.3,"h":0.3}},
              {"app":"app:hand-odds","snap":"left","mode":"snapped","kept":true}],
            "front":"editor","tabs":{"tabs":[{"id":"t4","address":"world://boards/b1","pinnedAt":1,"by":"ai","turn":4,"kept":true}],"selected":"t4","next":5,"groups":[]},
            "icons":[{"app":"files","col":0,"row":0,"label":"x"}],"turn":4,"cascade":3,"wallpaper":"dots"}"""
        val d = DeskCodec.decode(newer)
        assertEquals(listOf("editor", "terminal", "app:hand-odds"), d.windows.map { it.app })
        assertEquals(WindowMode.MAXIMISED, d.window("editor")!!.mode)
        assertEquals(WindowMode.NORMAL, d.window("terminal")!!.mode, "a mode from a newer build reads as normal")
        assertEquals(Snap.LEFT, d.window("app:hand-odds")!!.snap)
        assertEquals("editor", d.front)
        assertEquals("world://boards/b1", d.tabs.current!!.address)
        assertTrue(d.tabs.current!!.kept)
        assertEquals(listOf(IconCell("files", 0, 0)), d.icons)
        assertEquals(4, d.turn)
    }

    @Test
    fun aDesksTabsFromBeforeTheirGroupsRead() {
        // The desk.json 1.1.x wrote before tabs knew what made them (READABILITY.md §8): no `group`. Its tabs read
        // ungrouped — no divider, no overview label — and a tab opened beside them groups as it should.
        val before = """{"windows":[{"app":"browser"}],"front":"browser","tabs":{"tabs":[
              {"id":"t1","address":"world://boards/b1","by":"ai","turn":2,"mark":true},
              {"id":"t2","address":"world://home"}],"selected":"t2","next":3}}"""
        val d = DeskCodec.decode(before)
        assertEquals(listOf(null, null), d.tabs.tabs.map { it.group })
        assertTrue(d.tabs.tabs[0].mark, "a mark an older build set stays until the tab is seen")
        assertFalse(d.tabs.startsGroup(1))
        val t = d.tabs.show("b2", at = 5, raise = false, group = "run@5").show("b3", at = 6, raise = false, group = "run@5")
        assertEquals(listOf("world://boards/b1", "world://home", "world://boards/b2", "world://boards/b3"), t.tabs.map { it.address })
        assertTrue(t.startsGroup(2))
    }

    @Test
    fun anAppFromANewerBuildReads() {
        // An app a later build made: a kind, a glyph and keys this build does not know, and a screen with a widget it
        // cannot draw. The app reads, its kind is kept as written and drawn as a viewer, and the widget says so in its place.
        val m = assertNotNull(
            AppCodec.decode(
                """{"slug":"other-name","name":"Season log","kind":"spreadsheet","glyph":"hologram","monogram":"SL","api":2,"version":7,
                    "size":{"w":800,"h":600},"permissions":["camera"],"by":"ai","created":1,"updated":2}""",
                "season-log",
            ),
        )
        assertEquals("season-log", m.slug, "the folder names the app")
        assertEquals("spreadsheet", m.kind)
        assertEquals(AppKind.VIEWER, m.appKind)
        assertEquals(2, m.api)
        assertEquals(7, m.version)
        assertEquals("SL", m.tile.monogram)
        assertTrue("\"spreadsheet\"" in AppCodec.encode(m), "kept for the build that wrote it")
        val tree = UiTree.parse("""{"ui":"col","children":[{"ui":"text","text":"Games"},{"ui":"spreadsheet","id":"s","cells":[[1]]},{"ui":"stat","value":"12","label":"games","sparkline":[1,2]}]}""")
        val kids = (tree.root as UiNode.Col).children
        assertEquals(UiNode.Unknown("spreadsheet"), kids[1])
        assertEquals("12", (kids[2] as UiNode.Stat).value)
        assertEquals(StateRead.Ok("""{"games":[],"v":2}"""), AppCodec.readState("""{"games":[],"v":2}"""))
    }

    @Test
    fun aReplayFrom1075StillReads() {
        // 1.0.75: a replay in `<data>/duel/replays/`, a what-if of another, with a note in it.
        val old = """{"header":{"id":"d2","seed":3,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},{"i":1,"seat":0,"group":1,"action":{"t":"note","text":"Ash here?","seat":0}}],
            "cursor":2,"name":"Locals R3","parent":"r1","parentAt":12,"saved":1760000000000}"""
        val r = assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old))
        assertEquals("r1", r.parent)
        assertEquals(12, r.parentAt)
        assertEquals("Locals R3", r.name)
        assertEquals(1, com.kaiharimoto.mastertool.core.duel.replay.Replays.marks(r.entries).size)
    }

    @Test
    fun aDuelFrom1077StillReads() {
        // 1.0.77: a duel in play named the old way ("You"/"Opponent"), a token without stats, and duel settings
        // without 1.0.78's facing or 1.0.79's aiBothSeats; and a 1.0.76 combo step to "el", seat 0's left EMZ.
        val old = """{"header":{"id":"d3","seed":5,"seats":[{"name":"You","main":[1,2,3,4,5,6]},{"name":"Opponent","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":0,"group":1,"action":{"t":"token","seat":0,"to":{"t":"zone","seat":0,"kind":"MONSTER","index":2},"name":"Sheep"}},
            {"i":2,"seat":0,"group":2,"action":{"t":"phase","phase":"MAIN1"}}],"cursor":3}"""
        val r = assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old))
        val g = com.kaiharimoto.mastertool.core.duel.DuelGame.of(r)
        val token = g.state.cards.values.single { it.token }
        assertEquals(null, token.atk)
        assertEquals(emptyList(), g.state.locks)
        assertEquals(null, g.state.proposal)
        assertEquals("Player 1", com.kaiharimoto.mastertool.core.duel.text.DuelWords.seatName(g.state, 0))
        val d = prefs.decodeFromString(NeuePreferences.serializer(), """{"duel":{"twoSided":true,"names":["You","Opponent"],"aiSeat":1}}""").duel
        assertEquals(false, d.facing)
        assertEquals(true, d.keysShown)
        assertEquals(false, d.aiBothSeats)
        // 1.0.87: duel settings from before Command mode read with the coordinates off.
        assertEquals(false, d.coordinates)
        assertEquals(
            com.kaiharimoto.mastertool.core.duel.Place.Zone(1, com.kaiharimoto.mastertool.core.duel.ZoneKind.EMZ, 0),
            com.kaiharimoto.mastertool.core.duel.text.DuelCommand.zoneOf("el", 1),
        )
    }

    @Test
    fun aDuelFrom1095WithRollsAndFlipsStillReads() {
        // 1.0.74–1.0.95: a die and a coin written with their values and no throw. 1.0.96 adds `toss`; these lie nowhere.
        val old = """{"header":{"id":"d5","seed":3,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":0,"group":1,"action":{"t":"dice","seat":0,"value":4}},
            {"i":2,"seat":1,"group":2,"action":{"t":"coin","seat":1,"heads":false}}],"cursor":3}"""
        val r = assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old))
        val g = com.kaiharimoto.mastertool.core.duel.DuelGame.of(r)
        assertEquals(4, (g.entries[1].action as com.kaiharimoto.mastertool.core.duel.DuelAction.Dice).value)
        assertEquals(false, (g.entries[2].action as com.kaiharimoto.mastertool.core.duel.DuelAction.Coin).heads)
        assertEquals(emptyList(), g.state.chance)
    }

    @Test
    fun aThrowFrom1096PlaysAsItDidAnd119sStowReadsBack() {
        // 1.0.96–1.1.8: a die thrown by a hand, its throw with no `reach` — it meets the middle row's wall (INNER), so it
        // lands where it always landed. 1.1.9 adds `reach` (the whole table) and the `stow` entry that puts it back.
        val old = """{"header":{"id":"d6","seed":5,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":0,"group":1,"action":{"t":"dice","seat":0,"value":5,"toss":{"start":{"p":{"x":6.0,"y":6.0,"z":1.6},"q":{"w":1.0},"v":{"x":2.0,"y":-40.0,"z":3.0},"w":{"x":68.0,"y":3.4,"z":-1.0}}}}}],"cursor":2}"""
        val r = assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old))
        val g = com.kaiharimoto.mastertool.core.duel.DuelGame.of(r)
        val lying = g.state.chance.single()
        assertEquals(null, lying.toss.reach)
        val shape = com.kaiharimoto.mastertool.core.duel.dice.DiceSim.Shape.DIE
        val run = com.kaiharimoto.mastertool.core.duel.dice.TossRuns.of(shape, lying.toss)
        assertEquals(com.kaiharimoto.mastertool.core.duel.dice.DiceSim.run(shape, lying.toss.start).frames, run.frames)
        assertTrue(run.rest.single().p.y >= -com.kaiharimoto.mastertool.core.duel.dice.DiceSim.INNER)
        // 1.1.9's shape: a throw with its reach, then the stow — read back as written.
        val stowed = g.act(com.kaiharimoto.mastertool.core.duel.DuelAction.Stow(0, coin = false), 0).game
        val text = com.kaiharimoto.mastertool.core.duel.DuelCodec.encode(stowed.record("stow"))
        assertTrue("\"t\":\"stow\"" in text, text)
        val back = com.kaiharimoto.mastertool.core.duel.DuelGame.of(assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(text)))
        assertEquals(com.kaiharimoto.mastertool.core.duel.DuelAction.Stow(0, coin = false), back.entries.last().action)
        assertEquals(emptyList(), back.state.chance)
        val far = """{"t":"dice","seat":0,"value":2,"toss":{"start":{"p":{"x":6.0,"y":6.0,"z":1.6}},"reach":12.3}}"""
        val dice = com.kaiharimoto.mastertool.core.duel.DuelCodec.json.decodeFromString(com.kaiharimoto.mastertool.core.duel.DuelAction.serializer(), far)
        assertEquals(12.3, (dice as com.kaiharimoto.mastertool.core.duel.DuelAction.Dice).toss?.reach)
    }

    @Test
    fun aDuelFrom1085WithUnstampedTokensAndLocksStillReads() {
        // 1.0.79–1.0.85: a token without its uid and a lock without its id — the fold numbered them — then a
        // move of that token and the lock lifted by its number. 1.0.86 stamps both on commit.
        val old = """{"header":{"id":"d4","seed":9,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":0,"group":1,"action":{"t":"phase","phase":"MAIN1"}},
            {"i":2,"seat":0,"group":2,"action":{"t":"lock","seat":0,"text":"Synchro Monsters only","until":"duel"}},
            {"i":3,"seat":0,"group":3,"action":{"t":"token","seat":0,"to":{"t":"zone","seat":0,"kind":"MONSTER","index":0},"name":"Sheep"}},
            {"i":4,"seat":0,"group":4,"action":{"t":"pos","uid":100000,"pos":"FACE_UP_ATK"}},
            {"i":5,"seat":0,"group":5,"action":{"t":"unlock","id":1}}],"cursor":6}"""
        val r = assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old))
        val g = com.kaiharimoto.mastertool.core.duel.DuelGame.of(r)
        val sheep = g.state.cards.getValue(com.kaiharimoto.mastertool.core.duel.DuelState.TOKEN_UIDS)
        assertEquals("Sheep", sheep.name)
        assertEquals(com.kaiharimoto.mastertool.core.board.CardPosition.FACE_UP_ATK, sheep.pos)
        assertEquals(emptyList(), g.state.locks)
        // A token and a lock put in before them take new numbers; the old ones keep theirs.
        val zone = com.kaiharimoto.mastertool.core.duel.Place.Zone(0, com.kaiharimoto.mastertool.core.duel.ZoneKind.MONSTER, 1)
        val inserted = com.kaiharimoto.mastertool.core.duel.replay.Replays.insert(
            r, 2,
            listOf(
                com.kaiharimoto.mastertool.core.duel.DuelAction.Lock(0, "Spells only", "duel"),
                com.kaiharimoto.mastertool.core.duel.DuelAction.Token(0, zone, name = "Ram"),
            ),
            0,
        )
        val after = com.kaiharimoto.mastertool.core.duel.DuelGame.of(inserted).state
        assertEquals(com.kaiharimoto.mastertool.core.board.CardPosition.FACE_UP_ATK, after.cards.getValue(100_000).pos)
        assertEquals("Ram", after.cards.getValue(100_001).name)
        assertEquals(listOf("Spells only"), after.locks.map { it.text })
        // And it writes them down, readable by a build that ignores them.
        assertTrue(com.kaiharimoto.mastertool.core.duel.DuelCodec.encode(inserted).contains("\"name\":\"Sheep\",\"uid\":100000"))
    }

    @Test
    fun aDuelFrom1086BeganWithoutTheDiceAnd1087sOpeningRollReadsBack() {
        // 1.0.86: no opening roll in the header — the first seat went first and turn 1 began at once.
        val old = """{"header":{"id":"d5","seed":4,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},{"i":1,"seat":0,"group":1,"action":{"t":"phase","phase":"MAIN1"}}],"cursor":2}"""
        val g = com.kaiharimoto.mastertool.core.duel.DuelGame.of(assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(old)))
        assertEquals(null, g.state.opening)
        assertEquals(com.kaiharimoto.mastertool.core.board.DuelPhase.MAIN1, g.state.phase)
        // Duel settings from before 1.0.87 read with the opening roll on.
        assertEquals(true, prefs.decodeFromString(NeuePreferences.serializer(), """{"duel":{"twoSided":true,"autoDraw":false}}""").duel.openingRoll)
        // Duel settings from before cards played themselves (Phase D's last step) read with every card played by hand.
        assertEquals(false, prefs.decodeFromString(NeuePreferences.serializer(), """{"duel":{"twoSided":true,"autoDraw":false}}""").duel.autoEffects)
        // 1.0.87: the opening roll as it is written — both throws (a hand's, then a stamped one), then the winner's choice.
        val die = """{"p":{"x":9.4,"y":6.6,"z":1.6},"q":{"w":0.7,"x":0.1,"y":-0.3,"z":0.6},"v":{"x":6.1,"y":-13.6,"z":3.0},"w":{"x":21.7,"y":9.7}}"""
        val now = """{"header":{"id":"d6","seed":4,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival","main":[7,8,9,10,11,12]}],"openingRoll":true},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":0,"group":1,"action":{"t":"opening-roll","seat":0,"values":[6,5],"toss":{"dice":[$die,$die]}}},
            {"i":2,"seat":1,"group":2,"action":{"t":"opening-roll","seat":1,"values":[2,3],"toss":{"dice":[$die,$die]}}},
            {"i":3,"seat":0,"group":3,"action":{"t":"go-first","seat":0,"first":false}}],"cursor":4}"""
        val n = com.kaiharimoto.mastertool.core.duel.DuelGame.of(assertNotNull(com.kaiharimoto.mastertool.core.duel.DuelCodec.decode(now)))
        val o = assertNotNull(n.state.opening)
        assertEquals(0, o.winner)
        assertEquals(1, o.first)
        assertEquals(1, n.state.active)
        assertEquals(listOf(6, 5), o.dice[0])
        assertEquals(2, o.throws[1]?.dice?.size)
    }

    @Test
    fun aPresentationFrom1070StillReads() {
        // 1.0.70: the first shape Present wrote, a deck slide and a freeform one.
        val old = """{"id":"pabc","name":"Labrynth profile","style":"BUILD_UP","theme":"arena",
            "deck":{"deckId":"d1","name":"Labrynth","main":[1,1,2],"groups":[{"id":"g","name":"Engine"}],"assignments":{"1":"g"}},
            "webcam":{"enabled":true,"preset":"RIGHT_COLUMN"},
            "slides":[{"id":"s1","layout":"DECK","deck":{"groups":["g"],"note":"Open these"}},
            {"id":"s2","elements":[{"id":"e","type":"TEXT","x":0.1,"y":0.1,"w":0.8,"h":0.2,"anchor":"STAGE",
              "paras":[{"runs":[{"text":"Hi","style":{"weight":700,"color":"@accent"}}]}],
              "animations":[{"id":"a","effect":"RISE"}]}],"notes":"Say hello"}]}"""
        val p = assertNotNull(PresentCodec.decode(old))
        assertEquals("BUILD_UP", p.style)
        assertEquals("g", p.deck?.groupOf(1))
        assertEquals("Open these", p.slides[0].deck?.note)
        assertEquals("Hi", p.slides[1].elements[0].plainText)
        assertEquals("Say hello", p.slides[1].notes)
        assertTrue(p.webcam.enabled)
    }

    @Test
    fun presentSettingsFrom1071StartInMasterUi() {
        // 1.0.70–1.0.71 wrote Arena, the old default, into every saved setting; 1.0.72 starts in Master UI
        // until the person picks a theme themselves (kai: "default to Master UI").
        val old = """{"present":{"style":"SPOTLIGHT","theme":"arena","webcam":true,"webcamPreset":"BOTTOM_RIGHT","creator":"kai","open":"p1"}}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), old).present
        assertEquals("kai", p.creator)
        assertEquals(false, p.themeChosen)
        assertEquals(com.kaiharimoto.mastertool.core.present.Themes.MASTER, p.startTheme(appDark = false))
        assertEquals(com.kaiharimoto.mastertool.core.present.Themes.MASTER_DARK, p.startTheme(appDark = true))
        assertEquals("neon", p.copy(theme = "neon", themeChosen = true).startTheme(appDark = false))
    }

    @Test
    fun aPresentationBroken1072ReadsRepaired() {
        // 1.0.72: Ai's canvas box over a stage placeholder, saved as 800 stages wide — it crashed the painter.
        val old = """{"id":"pbad","name":"Profile","theme":"paper","slides":[{"id":"s1","elements":[
            {"id":"e","type":"TEXT","x":100,"y":200,"w":800,"h":300,"anchor":"STAGE","paras":[{"runs":[{"text":"Hi"}]}]}]}]}"""
        val e = assertNotNull(PresentCodec.decode(old)).slides.single().elements.single()
        assertEquals("CANVAS", e.anchor)
        assertEquals(800f, e.w)
        assertEquals("Hi", e.plainText)
    }

    @Test
    fun moduleSlidesAndEndCardsFrom1071StillWork() {
        // 1.0.71–1.1.x: a siding slide without its matchup link, a ratios chart without group colours, and the end
        // card's dashed boxes without the guide flag. 1.1.x links each siding slide to its matchup (the audit's B2),
        // gives charts their groups (B8) and leaves guides out of exports (I2): the old shapes must still do right.
        val input = """{\"matchups\":[{\"name\":\"Snake-Eye\",\"first\":{\"out\":[1],\"into\":[9]}},{\"name\":\"Yubel\",\"first\":{\"out\":[3],\"into\":[7]}}]}"""
        val old = """{"id":"pmod","name":"Profile","theme":"paper","slides":[
            {"id":"s1","title":"Siding vs Yubel","module":{"type":"SIDING","params":{"input":"$input"},"generatedAt":1},
             "elements":[{"id":"s1-title","type":"TEXT","anchor":"STAGE","paras":[{"runs":[{"text":"vs Yubel"}]}]}]},
            {"id":"s2","elements":[{"id":"s2-donut","type":"CHART","anchor":"STAGE","chart":{"kind":"DONUT","labels":["A (12)"],"series":[{"name":"Cards","values":[12]}]}}]},
            {"id":"s3","layout":"END_CARD","elements":[{"id":"e1","type":"SHAPE","shape":"ROUNDED","stroke":{"color":"@line","width":3,"dash":"DASHED"},
              "paras":[{"runs":[{"text":"Next video"}]}]},{"id":"e2","type":"TEXT","paras":[{"runs":[{"text":"Thanks for watching"}]}]}]}]}"""
        val p = assertNotNull(PresentCodec.decode(old))
        val siding = p.slides[0].copy(title = "Retitled")
        assertEquals("Yubel", com.kaiharimoto.mastertool.core.present.modules.Modules.matchupOf(siding))
        assertEquals(null, p.slides[1].elements.single().chart?.groups)
        assertTrue(p.slides[2].elements[0].isGuide, "the old dashed box is a guide")
        assertFalse(p.slides[2].elements[1].isGuide, "the words are content")
    }

    @Test
    fun aCameraElementFrom1070MovesTheSlidesCamera() {
        // 1.0.70–1.1.x: the Big camera layout and More ▾ → Camera wrote a Camera element, which drew a second frame
        // and never moved the zone; a Morph transition, which drew a fade. Both read on: the element becomes the
        // slide's own camera box (no element left to draw twice), and Morph stays stored as it was.
        val old = """{"id":"pcam","name":"Profile","theme":"paper","webcam":{"enabled":true},
            "slides":[{"id":"s1","layout":"CAMERA_BIG","transition":{"kind":"MORPH"},"elements":[
              {"id":"c","type":"CAMERA","x":96,"y":140,"w":1100,"h":800},
              {"id":"t","type":"TEXT","x":1260,"y":200,"w":560,"h":200,"paras":[{"runs":[{"text":"Hi, I'm…"}]}]}]}]}"""
        val p = assertNotNull(PresentCodec.decode(old))
        val s = p.slides.single()
        assertEquals(listOf("t"), s.elements.map { it.id })
        assertEquals("CUSTOM", s.camera)
        assertEquals(com.kaiharimoto.mastertool.core.present.stage.Box(96f, 140f, 1100f, 800f), s.cameraBox)
        assertEquals("MORPH", s.transition.kind)
    }

    @Test
    fun aTakeFrom1113StillReadsAndRecordingSettingsAreTheDevicesOwn() {
        // 1.1.13: `<data>/present/<id>/takes/<take>/take.json` as the first build that records writes it, finished and
        // rendered; and one the app closed on mid-take, which says so.
        val old = """{"id":"t1760000000000","presentationId":"p1","name":"Take 1","startedAt":1760000000000,"durationMs":45000,
            "events":[{"at":0,"kind":"GO"},{"at":12000,"kind":"GO","slide":1},{"at":13000,"kind":"LASER","x":640.0,"y":360.0},
            {"at":14000,"kind":"LASER_OFF"},{"at":20000,"kind":"MARK","text":"The engine"}],
            "camera":"camera.mkv","audio":"audio.wav","rendered":"Take 1.webm","renderedCodec":"libvpx-vp9","renderedAt":1760000100000}"""
        val t = assertNotNull(com.kaiharimoto.mastertool.core.present.record.TakeCodec.decode(old))
        assertTrue(t.finished)
        assertEquals(30, t.fps)
        assertEquals("Take 1.webm", t.rendered)
        assertEquals(1, com.kaiharimoto.mastertool.core.present.record.TakeTimeline.stateAt(t.events, 12_500).cursor.slide)
        val cut = assertNotNull(com.kaiharimoto.mastertool.core.present.record.TakeCodec.decode("""{"id":"t2","presentationId":"p1","finished":false,"events":[{"at":0,"kind":"GO"}]}"""))
        assertFalse(cut.finished)
        // Settings written before recording existed read with it off and nothing chosen; it is never synced.
        val prefs = this.prefs.decodeFromString(NeuePreferences.serializer(), """{"theme":"PAPER"}""")
        assertEquals(null, prefs.record.camera)
        assertFalse(prefs.record.chosen)
        assertTrue("record" in com.kaiharimoto.mastertool.core.sync.SyncedPrefs.DEVICE)
    }

    @Test
    fun aBanlistCacheFrom111StillReads() {
        // 1.1.1: `<data>/banlists/tcg.json` as the first build with the banlist history writes it (a cache; never synced).
        val old = """{"version":1,"region":"TCG","lists":[{"region":"TCG","title":"April 2025 Lists (TCG)","start":"2025-04-07",
            "end":"2025-09-14","statuses":{"Abyss Dweller":"FORBIDDEN","Bystial Druiswurm":"LIMITED","Snake-Eye Ash":"SEMI_LIMITED",
            "Cyber Jar":"UNLIMITED"},"prev":"December 2024 Lists (TCG)","next":"September 2025 Lists (TCG)"},{"region":"TCG",
            "title":"September 2026 Lists (TCG)","start":"2026-09-21","end":null,"statuses":{"Maxx \"C\"":"FORBIDDEN"},"prev":null,
            "next":null}],"unreadable":{"X Lists":"X Lists names no cards."},"fetched":{"April 2025 Lists (TCG)":1759000000000},
            "checked":1759000000000}"""
        val doc = assertNotNull(BanlistCodec.decode(old))
        val h = doc.history()
        assertEquals("April 2025 Lists (TCG)", h.asOf("2025-05-01")?.title)
        assertEquals(BanStatus.LIMITED, h.statusOf("Bystial Druiswurm", "2025-05-01"))
        assertEquals(BanStatus.FORBIDDEN, h.statusOf("Maxx \"C\"", "2026-10-01"))
        assertEquals(1759000000000, doc.checked)
        // A later build's fields are skipped, and a broken file is no cache at all, never a crash.
        assertNotNull(BanlistCodec.decode("""{"version":2,"region":"OCG","lists":[],"source":"elsewhere"}"""))
        assertEquals(null, BanlistCodec.decode("{\"version\":1,\"lists\":[{]"))
    }

    @Test
    fun aDuelAndAReplayWithoutProvenanceStillReadAnd112sShapeReadsBack() {
        // Up to 1.1.1: no entry says who made it. Read with no provenance, counted as no one's; a result of it has no Ai.
        val old = """{"header":{"id":"d7","seed":5,"seats":[{"name":"Kai","main":[1,2,3,4,5,6],"deckId":"k"},{"name":"Ai","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},{"i":1,"seat":1,"group":1,"action":{"t":"lp","seat":0,"delta":-8000}}],
            "cursor":2,"name":"Old game","saved":1760000000000}"""
        val g = DuelGame.of(assertNotNull(DuelCodec.decode(old)))
        assertTrue(g.entries.all { it.by == null })
        val r = assertNotNull(DuelResults.of(g, 1L))
        assertEquals(1, r.winner)
        assertEquals(null, r.ai)
        assertEquals(DuelResult.UNKNOWN, r.player(1))
        // 1.1.2 (Phase C): each entry carries `by`; an older build ignores the key, and this one reads it back.
        val now = """{"header":{"id":"d8","seed":5,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Ai","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":1,"group":1,"action":{"t":"lp","seat":0,"delta":-100},"by":{"by":"ai","aiSeat":1,"aiKnows":"auto","eyes":"seat","view":"0123456789abcdef","peeks":1}},
            {"i":2,"seat":0,"group":2,"action":{"t":"chat","seat":0,"text":"hi"},"by":{"aiSeat":1,"aiKnows":"auto","eyes":"seat","later":true}}],"cursor":3}"""
        val n = DuelGame.of(assertNotNull(DuelCodec.decode(now)))
        val ai = assertNotNull(n.entries[1].by)
        assertEquals(Provenance.AI, ai.by)
        assertEquals("0123456789abcdef", ai.view)
        assertEquals(1, ai.peeks)
        // `by` left out is the person; a later build's key is skipped.
        assertEquals(Provenance.PERSON, n.entries[2].by?.by)
        // 1.1.2: a result in `<data>/duel/records/<id>.json`, as it is first written.
        val record = """{"id":"d8","duel":"d8","ended":1760000000000,"seats":[{"name":"Kai","deckId":"k","deckName":"Labrynth","player":"person",
            "moves":{"person":12}},{"name":"Ai","player":"ai","moves":{"ai":14,"table":2}}],"first":1,"firstBy":"roll","rolls":[[7,7],[5,9]],
            "rollWinner":1,"choseFirst":true,"chosenBy":"ai","winner":1,"turns":6,"ai":{"seat":1,"knows":"self","moves":14},"eyes":"seat"}"""
        val res = assertNotNull(DuelResultCodec.decode(record))
        assertEquals(1, res.ai?.seat)
        assertEquals(listOf(listOf(7, 7), listOf(5, 9)), res.rolls)
        assertEquals(
            "Ai won 1 of 1 against Kai. Ai saw only its own hand; Kai saw only theirs; the dice chose who went first (Ai first in 1).",
            DuelResults.summary(listOf(res)),
        )
        // Not a record at all: none, never a crash.
        assertEquals(null, DuelResultCodec.decode("""{"ended":1}"""))
        assertEquals(null, DuelResultCodec.decode("{"))
    }

    @Test
    fun aDuelWithoutEffectTagsStillReadsAnd120sTagsReadBack() {
        // Up to 1.1.x no entry carries `fx`: every entry reads with none, and is written back without the key.
        val old = """{"header":{"id":"d10","seed":3,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Ai","main":[7,8,9,10,11,12]}]},
            "entries":[{"i":0,"group":0,"action":{"t":"draw","seat":0,"n":5}},
            {"i":1,"seat":0,"group":1,"action":{"t":"move","uid":1,"to":{"t":"zone","seat":0,"kind":"MONSTER","index":0},"pos":"FACE_UP_ATK","how":"normal"},"by":{"by":"person"}}],"cursor":2}"""
        val g = DuelGame.of(assertNotNull(DuelCodec.decode(old)))
        assertTrue(g.entries.all { it.fx == null })
        assertTrue("\"fx\"" !in DuelCodec.encode(g.record()))
        // 1.2.0 (Phase D): an entry the engine made carries its tag; an older build skips the key, and a later key in it is skipped here.
        val now = old.replace("\"by\":{\"by\":\"person\"}}", "\"by\":{\"by\":\"person\"},\"fx\":{\"uid\":1,\"effect\":\"rule\",\"part\":\"rule\",\"script\":\"0123456789ab\",\"verified\":true,\"later\":7}}")
        val n = DuelGame.of(assertNotNull(DuelCodec.decode(now)))
        val tag = assertNotNull(n.entries[1].fx)
        assertEquals(FxTag(1, FxTag.RULE, FxTag.RULE, script = "0123456789ab", verified = true), tag)
        assertEquals(null, n.entries[0].fx)
        assertEquals(n.state, g.state, "the tag changes nothing on the table")
    }

    @Test
    fun aVocabularyOneScriptReadsAndANewerOneIsKeptUnread() {
        // 1.2.0 (Phase D): a compiled script as the first vocabulary writes it.
        val v1 = """{"card":900000001,"name":"Example Scout","text":"0123456789ab","effects":[{"id":"e1","label":"Search","kind":"TRIGGER",
            "from":["MONSTER_ZONE"],"trigger":{"on":{"event":"SUMMONED","summon":["NORMAL","SPECIAL"]}},"opt":{"t":"name"},
            "does":[{"op":{"t":"add","pick":{"from":[{"area":"DECK"}],"where":{"t":"all","all":[{"t":"name-has","word":"Example"},{"t":"kind","type":"MONSTER"},{"t":"level","span":{"min":1,"max":4}}]}}}}]}]}"""
        val s = assertNotNull(FxCodec.decode(v1))
        assertEquals(Opt.ByName(), s.effects.single().opt)
        assertEquals(FxCodec.json.parseToJsonElement(v1), FxCodec.json.parseToJsonElement(FxCodec.encode(s)), "written back the same")
        // A later vocabulary's script is never decoded, so never rewritten.
        val later = FxCodec.read(v1.replace("\"card\":900000001,", "\"card\":900000001,\"vocab\":${FxVocab.VERSION + 1},"))
        assertTrue(later is FxRead.Newer)
    }

    @Test
    fun aCompiledScriptWithItsSourceAndAReviewStillReadAndANewerScriptIsNeverCompiledOver() {
        // Phase D step 2: `<data>/effects/<passcode>.json` as the library writes it — its vocabulary written out, and the
        // source's hash beside the printed text's.
        val file = """{"card":900000001,"vocab":1,"name":"Example Scout","text":"0123456789ab","source":"ba9876543210","effects":[{"id":"e1",
            "label":"Search","kind":"TRIGGER","from":["MONSTER_ZONE"],"trigger":{"on":{"event":"SUMMONED","summon":["NORMAL","SPECIAL"]},"optional":true},
            "opt":{"t":"name"},"does":[{"op":{"t":"add","pick":{"from":[{"rel":"YOU","area":"DECK"}],"where":{"t":"name-has","word":"Example"}}}}]}]}"""
        val s = assertNotNull(FxCodec.decode(file))
        assertEquals("ba9876543210", s.source)
        // The source's hash is never part of the script's: a comment in the source re-verifies nothing.
        assertEquals(FxCodec.hash(s.copy(source = "")), FxCodec.hash(s))
        assertFalse(FxShelf.needsCompile("ba9876543210", FxRead.Script(s)))
        // A step-1 file (no source hash) reads and is compiled again from its source once one is there.
        val step1 = assertNotNull(FxCodec.decode(file.replace(",\"source\":\"ba9876543210\"", "")))
        assertTrue(FxShelf.needsCompile("ba9876543210", FxRead.Script(step1)))
        // A newer vocabulary's compiled script is kept unread and untouched: never compiled over from its source.
        val newer = FxCodec.read(file.replace("\"vocab\":1", "\"vocab\":${FxVocab.VERSION + 1}"))
        assertTrue(newer is FxRead.Newer)
        assertFalse(FxShelf.needsCompile("ffffffffffff", newer))
        // `<passcode>.review.json`: the person's accepted warnings, with why; a later build's keys are skipped.
        val review = assertNotNull(FxReviews.decode("""{"card":900000001,"accepted":[{"key":"lint-quick@e2","why":"the Quick Effect is the other card's","at":5}]}"""))
        assertEquals(setOf("lint-quick@e2"), review.keys)
        val later = assertNotNull(FxReviews.decode("""{"version":2,"card":900000001,"accepted":[{"key":"lint-opt-copy","why":"x","at":6,"by":"person"}],"seen":3}"""))
        assertEquals(setOf("lint-opt-copy"), later.keys)
        assertEquals(null, FxReviews.decode("not json"))
    }

    @Test
    fun theAskedListAsStep2WritesItReadsAndALaterOnesKeysAreSkipped() {
        // Phase D step 2: `<data>/effects/asked.json` (FxAsks) — each card asked for, when, from where, its request, and what
        // writing it cost; the requests with their estimates.
        val v1 = """{"asks":[{"card":900000201,"at":5,"from":"viewer","request":"r1","what":"Example Herald's effect","deck":"d1",
            "state":"written","tokens":31000,"usd":0.24,"model":"anthropic/claude-sonnet-5-5"},{"card":900000203,"at":6,"from":"pane",
            "request":"r2"}],"requests":[{"id":"r1","at":5,"from":"viewer","what":"Example Herald's effect","deck":"d1",
            "cards":[900000201],"estimateTokens":30000,"estimateUsd":0.08}]}"""
        val doc = FxAsks.decode(v1)
        assertEquals(listOf(900_000_201, 900_000_203), doc.asks.map { it.card })
        assertEquals(31_000L, doc.of(900_000_201)?.tokens)
        assertEquals(FxAsks.ASKED, doc.of(900_000_203)?.state, "a state left out is asked")
        assertEquals(null, doc.of(900_000_203)?.usd)
        assertEquals(30_000L, doc.requests.single().estimateTokens)
        // A later build's keys (verdicts, a version 2) are skipped and the asks still read.
        val later = FxAsks.decode("""{"version":2,"asks":[{"card":900000201,"state":"verified-later","by":"person","seen":[1]}],"pinned":[1]}""")
        assertEquals(900_000_201, later.asks.single().card)
        assertEquals(2, later.version)
        // A go on a later document keeps its version.
        assertEquals(2, FxAsks.go(later, FxRequest("r3", cards = listOf(900_000_205)), FxReviews.PERSON)?.version)
        assertEquals(FxAsked(), FxAsks.decode("{"))
    }

    @Test
    fun theGoldfishFilesAsStep4WritesThemReadAndALaterOnesKeysAreSkipped() {
        // Phase D step 4: `<data>/effects/played.json` (FxMarks) — the "played by you" marks, per card and script hash.
        val played = com.kaiharimoto.mastertool.core.duel.effects.FxMarks.decode(
            """{"marks":[{"card":900000601,"script":"abcdef012345","uses":2,"at":9,"effects":["e1"]}]}""",
        )
        assertEquals(2, played.of(900_000_601, "abcdef012345")?.uses)
        val laterPlayed = com.kaiharimoto.mastertool.core.duel.effects.FxMarks.decode(
            """{"version":2,"marks":[{"card":900000601,"script":"abcdef012345","uses":1,"by":"person","table":"d9"}],"seen":3}""",
        )
        assertEquals(1, laterPlayed.marks.single().uses)
        // `<data>/effects/goldfish/<deck>.json` (GoldfishDoc): the deck's targets and its kept results, versioned.
        val v1 = """{"deck":"d1","targets":[{"id":"t1","name":"Two Pond monsters","deck":"d1","all":[{"t":"controls","where":{"t":"name-has",
            "word":"Pond"},"n":2},{"t":"any-of","any":[{"t":"interruptions"},{"t":"set","n":2}]}],"by":"ai","at":5}],"results":[{"deck":"fp1",
            "library":"1a2b3c4d5e6f","target":{"id":"t1","name":"Two Pond monsters","deck":"d1","all":[{"t":"holds","where":{"t":"name","card":900000600}}]},
            "first":true,"hands":2000,"seed":7,"budget":20000,"reached":1262,"noLine":608,"undecided":130,"unknown":[900000602],"heldUnknown":800,
            "lines":[{"skeleton":"Pond Frog → Pond Caller","count":1262}],"outcomes":[{"index":0,"hand":[900000600,900000601,900000602,900000602,900000602],
            "reduced":[900000600,900000601],"end":"REACHED","line":0,"moves":12,"heldUnknown":true}],"ms":4100,"used":[900000601],"playedByYou":1,
            "deckId":"d1","depth":60,"at":6,"engine":1}]}"""
        val doc = com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec.decode(v1)
        assertEquals(listOf("t1"), doc.targets.map { it.id })
        assertEquals(com.kaiharimoto.mastertool.core.duel.effects.goldfish.EndBoard.AI, doc.targets.single().by)
        val r = doc.results.single()
        assertEquals(1262, r.reached)
        assertEquals("1a2b3c4d5e6f", r.library)
        assertEquals(com.kaiharimoto.mastertool.core.duel.effects.goldfish.HandEnd.REACHED, r.outcomes.single().end)
        // A line kept before it carried its cards (agent (c)'s `LineCount.cards`) reads with none: the pane reads the names.
        assertEquals(emptyList(), r.lines.single().cards)
        assertEquals("Pond Frog → Pond Caller", r.lines.single().skeleton)
        // A later build's keys are skipped, its new conditions kept as written (and such a target is not computable here).
        val later = com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec.decode(
            """{"version":2,"deck":"d1","targets":[{"id":"t2","name":"x","deck":"d1","all":[{"t":"lp-at-least","n":4000}],"opponent":"Ash"}],"results":[],"pinned":["t2"]}""",
        )
        assertTrue(com.kaiharimoto.mastertool.core.duel.effects.goldfish.BoardCheck.unread(later.targets.single()))
        assertTrue("lp-at-least" in com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishCodec.encode(later))
        // `Proof.library`: a proof written before step 4 has none and reads as it did.
        val ledger = com.kaiharimoto.mastertool.core.ai.evidence.Ledger.read(
            """[{"entry":"Opens 74.2%.","proofs":[{"tool":"hand_odds","input":"{}","deck":"deckA"}],"status":"CHECKED"}]""",
        )
        assertEquals("", ledger.single().proofs.single().library)
    }

    @Test
    fun aDuelRecordWithoutAKindIsATableDuelAndAnAiVsAiRecordIsNeverAGameAgainstAPerson() {
        // Up to 1.1.2 a record has no `kind`: a duel at the table, counted as it was.
        val table = assertNotNull(DuelResultCodec.decode("""{"id":"d9","duel":"d9","ended":1,"seats":[{"name":"Kai","player":"person"},
            {"name":"Ai","player":"ai"}],"winner":0,"turns":5,"ai":{"seat":1,"knows":"self","moves":9}}"""))
        assertEquals(null, table.kind)
        assertEquals(1, DuelResults.aiAgainst(listOf(table)).single().lost)
        // An unreleased build (Phase C stage 3) wrote "self-play" records of Ai World's script tables: never shipped, but
        // read forgivingly — decoded, counted nowhere, neither against a person nor as Ai vs Ai.
        val selfPlay = """{"id":"world-1-0-s7","duel":"world-1-0","ended":2,"seats":[{"name":"Branded","deckName":"Branded","player":"ai",
            "moves":{"ai":30}},{"name":"Snake-Eye","deckName":"Snake-Eye","player":"ai","moves":{"ai":28}}],"winner":0,"turns":7,
            "kind":"self-play","seed":7,"forkOf":"d9"}"""
        val r = assertNotNull(DuelResultCodec.decode(selfPlay))
        assertEquals(DuelResult.SELF_PLAY, r.kind)
        assertEquals(7L, r.seed)
        assertEquals("d9", r.forkOf)
        assertTrue(DuelResults.aiAgainst(listOf(r)).isEmpty())
        assertTrue(DuelResults.aiVsAi(listOf(r)).isEmpty())
        assertEquals("No finished duels against Ai yet.", DuelResults.summary(listOf(r)))
        // An older build skips the keys it does not know: both seats Ai's, it is still no game against a person.
        val older = assertNotNull(DuelResultCodec.decode(selfPlay.replace("\"kind\":\"self-play\",", "")))
        assertTrue(DuelResults.aiAgainst(listOf(older)).isEmpty())
        // Ai vs Ai (two sessions, one a seat): its kind, each seat's connection and model, how the table ended it — and an
        // older build reads it as a record with both seats Ai's, which no summary of its counts against a person.
        val match = """{"id":"m1","duel":"m1","ended":3,"seats":[{"name":"Opus","deckName":"Branded","player":"ai","moves":{"ai":40},
            "connection":"Anthropic","model":"claude-opus-5-5"},{"name":"GPT","deckName":"Snake-Eye","player":"ai","moves":{"ai":35},
            "connection":"OpenAI","model":"gpt-x"}],"first":1,"firstBy":"roll","winner":0,"how":"lp","turns":6,"kind":"ai-vs-ai","seed":42}"""
        val m = assertNotNull(DuelResultCodec.decode(match))
        assertEquals(DuelResult.AI_VS_AI, m.kind)
        assertEquals(listOf("claude-opus-5-5", "gpt-x"), m.seats.map { it.engine })
        assertEquals("Anthropic", m.seats[0].connection)
        assertTrue(DuelResults.aiAgainst(listOf(m)).isEmpty())
        assertEquals("Ai vs Ai: Opus 5.5 (Branded) beat GPT-x (Snake-Eye) 1 of 1; going first won 0.", DuelResults.summary(listOf(m)))
        val limit = assertNotNull(DuelResultCodec.decode(match.replace("\"winner\":0,\"how\":\"lp\"", "\"how\":\"limit\",\"said\":\"A draw by limit: turn 13 reached.\"")))
        assertEquals(null, limit.winner)
        assertEquals(DuelResult.LIMIT, limit.how)
        assertEquals("A draw by limit: turn 13 reached.", limit.said)
        val older2 = assertNotNull(DuelResultCodec.decode(match.replace("\"kind\":\"ai-vs-ai\",", "")))
        assertTrue(DuelResults.aiAgainst(listOf(older2)).isEmpty())
        // 1.1.48 (the Lounge): a duel at one of the Lounge's tables is kept as a record of kind "lounge", the seats named by
        // nickname — counted by who met whom, never as a game against Ai, even with Ai at a seat.
        val lounge = """{"id":"l1","duel":"l1","ended":4,"seats":[{"name":"kai","deckName":"Labrynth","player":"person","moves":{"person":20}},
            {"name":"Mika","deckName":"Snake-Eye","player":"person","moves":{"guest":18}}],"winner":1,"turns":7,"kind":"lounge"}"""
        val l = assertNotNull(DuelResultCodec.decode(lounge))
        assertEquals(DuelResult.LOUNGE, l.kind)
        assertEquals(listOf(0, 1), DuelResults.lounge(listOf(l)).single().wins)
        val withAi = assertNotNull(DuelResultCodec.decode(lounge.replace("\"winner\":1", "\"winner\":1,\"ai\":{\"seat\":1,\"knows\":\"self\",\"moves\":18}")))
        assertTrue(DuelResults.aiAgainst(listOf(withAi)).isEmpty())
        // 1.1.50: a member's kept deck (`<data>/lounge/decks/…`) may name the library deck kai brought it from; one kept
        // before has no such key and reads as a friend's, and a newer key in it is skipped.
        val keptJson = Json { ignoreUnknownKeys = true }
        val oldKept = keptJson.decodeFromString(LoungeDecks.Kept.serializer(), """{"name":"Pasted deck","text":"#main\n1001\n#extra\n!side\n"}""")
        assertEquals(null, oldKept.library)
        val newKept = keptJson.decodeFromString(LoungeDecks.Kept.serializer(), """{"name":"Labrynth","text":"#main\n1\n","library":"lib-7","later":1}""")
        assertEquals("lib-7", newKept.library)
    }

    @Test
    fun aConversationFromBeforeChessysVoiceOpensAsAis() {
        // 1.1.23 keeps the voice a conversation was last given (`voiceShown`); one saved before it has none, and was Ai's.
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            com.kaiharimoto.mastertool.core.ai.AiSession.serializer(),
            """{"id":"s1","title":"Branded","createdAt":1760000000000,"updatedAt":1760000000000,"connection":"c1","system":"You are Ai.",
            "turns":[],"scopeShown":"decks/d1","mode":"chat","guideShown":"d1","context":1200}""",
        )
        assertEquals(null, old.voiceShown)
        assertEquals("d1", old.guideShown)
    }

    @Test
    fun aCourseStudiedBeforeItWentInPartsGoesOn() {
        // 1.1.44 wrote a course with a spending cap and no parts; 1.1.46 has no cap, and notes, the playbook and the guide
        // go a part at a time. A course stopped by its cap goes on, its chapter noted from its first part.
        val text = """{"id":"guide-x","start":"https://metafy.gg/@joe/guides/x","deckId":"d1","deckName":"Branded",
            "chapters":[{"n":1,"title":"Lines","url":"https://metafy.gg/@joe/guides/x/1","kind":"TEXT","state":"READ","words":5000,
              "scanned":true,"depth":1,"videoChecked":true,"hasVideo":false,"watched":true,"videoNote":""}],
            "listed":true,"distilled":false,"state":"BLOCKED","note":"${StudyQueue.CAP_REACHED}",
            "createdAt":1760000000000,"updatedAt":1760000000000,"spent":2000000,"cap":2000000,"loadsDay":20370,"loads":3,
            "guideBefore":"","reviewed":false,"replays":[],"replaysDistilled":false,"consolidated":false,"distilDepth":0,"examDrawn":true}"""
        val c = assertNotNull(CourseCodec.read(text))
        val ch = c.chapters.single()
        assertEquals(0, ch.notedThrough)
        assertEquals(-1, ch.notesMark)
        assertEquals(0L, c.retryAt)
        assertTrue(c.consolidateDone.isEmpty() && c.distilDone.isEmpty() && c.partBegun.isEmpty())
        assertEquals(
            StudyQueue.Step.Save(1), // kept on this computer first (1.1.51), then noted
            StudyQueue.next(c.copy(state = Course.State.STUDYING, note = "")),
        )
        // The exam's log of 1.1.44 reads, and a sitting file beside it is its own.
        val runs = ExamLog.read(
            """[{"at":1760000000000,"deckId":"d1","model":"m","effort":"high","playbook":12,"guide":4000,
               "answers":[{"id":"r1-g1-t2","replay":1,"game":1,"turn":2,"target":["A"],"answer":["A"],"first":true,"recall":1.0,"precision":1.0}]}]""",
        )
        assertEquals(1, runs.single().asked)
        // A sitting 1.1.46–1.1.47 kept one a deck, at exams/<deck>.sitting.json: read when the course's own file has none,
        // and taken up only by the course it names (one of 1.1.46 names none, and begins again).
        assertEquals("exams/d1.sitting.json", ExamLog.oldSitting("d1"))
        val old = """{"at":1760000000000,"deckId":"d1","course":"c1","model":"m","effort":"high","playbook":3,"guide":900,
            "answers":[{"id":"r1-g1-t2","replay":1,"game":1,"turn":2,"target":["A"],"answer":["A"],"first":true,"recall":1.0,"precision":1.0}]}"""
        val sitting = assertNotNull(ExamLog.readSitting(null, old))
        assertEquals(1, ExamLog.resume(sitting, "d1", "m", "high", listOf("r1-g1-t2"), "c1", 3, 900).size)
        assertTrue(ExamLog.resume(sitting, "d1", "m", "high", listOf("r1-g1-t2"), "c2", 3, 900).isEmpty())
        val older = assertNotNull(ExamLog.readSitting("", old.replace(""""course":"c1",""", "")))
        assertTrue(ExamLog.resume(older, "d1", "m", "high", listOf("r1-g1-t2"), "c1", 3, 900).isEmpty())
    }

    @Test
    fun theMappersFilesAsStepM1WritesThemReadAndALaterOnesKeysAreSkipped() {
        // Phase M step M1: `<data>/effects/mapper/<deck>/library.json` (BoardLibrary), run.json (MapperRun), starters.json
        // (StarterRun) and presets.json (MapperPresets), each with a key a later build might add.
        val lib = BoardLibrary.decode(
            """{"deckId":"d1","deck":"fp","library":"1a2b3c4d5e6f","boards":[{"key":"00112233aabbccdd","cards":{"monsters":[900000601],
            "hand":[900000600],"under":["900000601:900000602"],"lp":8000},"traits":{"interruptions":1,"negates":1,"bodies":1,"hand":1,
            "through":{"ash":1}},"lines":[{"deal":{"hand":[900000600,900000601],"seed":3,"fodder":[900000609]},"steps":[{"kind":"n","uid":2,
            "card":900000601},{"kind":"a","uid":2,"what":"e1","answers":[[5]],"card":900000601},{"kind":"f","what":"END"}],"sets":[7],
            "deck":"fp"}],"starters":[[900000600,900000601]],"found":5,"run":1,"seen":2}],"runs":1,"keys":1,"elsewhere":true}""",
        )
        val board = assertNotNull(lib).boards.single()
        assertEquals(listOf(900_000_601), board.cards.monsters)
        assertEquals(mapOf("ash" to 1), board.traits.through)
        assertEquals("e1", board.lines.single().steps[1].what)
        assertEquals(listOf(900_000_609), board.lines.single().deal.fodder)
        assertEquals(1, lib.runs)
        val run = assertNotNull(MapperRun.decode(
            """{"deckId":"d1","deck":"fp","library":"1a2b3c4d5e6f","hands":3,"seed":7,"budget":20000,"keys":1,"traits":[{"interruptions":1,
            "negates":1,"bodies":1},{"bodies":1,"hand":2}],"added":["00112233aabbccdd"],"parts":[{"cards":[900000600,900000601],"fodder":[900000609],
            "hands":2,"ends":[0,1],"moves":120},{"cards":[900000600],"ends":[1],"complete":false,"moves":40}],"dealt":[0,1,0],"moves":160,
            "ms":900,"at":6,"streams":4}""",
        ))
        assertEquals(2, run.share { it.negates >= 1 }.hits)
        assertEquals(1, run.incomplete)
        val table = assertNotNull(StarterRun.decode(
            """{"deckId":"d1","deck":"fp","library":"1a2b3c4d5e6f","seed":1,"budget":100000,"rows":[{"cards":[900000600],"ends":["00112233aabbccdd"],
            "moves":30,"odds":0.33,"fodder":[900000609,900000609,900000609,900000609]},{"cards":[900000600,900000601],"ends":[],"complete":false,
            "moves":9,"odds":0.05,"together":[],"seeds":3,"verdict":"idle"}],"keys":1,"workers":4}""",
        ))
        assertEquals(listOf(true, false), table.rows.map { it.complete })
        assertEquals(3, table.rows[1].seeds)
        val presets = assertNotNull(MapperPresets.decode(
            """{"presets":[{"id":"p1","name":"Negates","filters":[{"head":"interruptions","min":2.0}],"weights":{"negates":2.0,"hand":0.5},
            "uses":[900000601],"by":"ai","why":"Ash-proof","colour":"x"}],"chosen":"p1","shared":false}""",
        ))
        assertEquals(BoardPreset.AI, presets.byId("p1")?.by)
        assertEquals(2.0, presets.byId("p1")?.weights?.get("negates"))
    }
}
