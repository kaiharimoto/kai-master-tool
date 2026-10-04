package com.kaiharimoto.mastertool.core.compat

import com.kaiharimoto.mastertool.core.ai.report.book.BookFreshness
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.backup.BackupManifest
import com.kaiharimoto.mastertool.core.backup.Backups
import com.kaiharimoto.mastertool.core.data.PoolRecord
import com.kaiharimoto.mastertool.core.cards.BanlistCodec
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.prefs.NeuePreferences
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.sync.Manifest
import com.kaiharimoto.mastertool.core.sync.Sync
import com.kaiharimoto.mastertool.core.sync.SyncPrefs
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
}
