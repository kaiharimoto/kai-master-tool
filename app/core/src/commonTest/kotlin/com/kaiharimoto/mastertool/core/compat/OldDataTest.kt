package com.kaiharimoto.mastertool.core.compat

import com.kaiharimoto.mastertool.core.duel.effects.Negation
import com.kaiharimoto.mastertool.core.duel.effects.FxMemo
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSuite
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSet
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.PrepCodec
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.ai.proposals.Proposals
import com.kaiharimoto.mastertool.core.duel.coach.PriorPrint
import com.kaiharimoto.mastertool.core.duel.coach.ReviewCodec
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.CourseCodec
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import com.kaiharimoto.mastertool.core.ai.exam.ExamLog
import com.kaiharimoto.mastertool.core.ai.report.book.BookFreshness
import com.kaiharimoto.mastertool.core.ai.report.book.BookNotes
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.backup.BackupManifest
import com.kaiharimoto.mastertool.core.backup.Backups
import com.kaiharimoto.mastertool.core.cards.BanlistCodec
import com.kaiharimoto.mastertool.core.data.PoolRecord
import com.kaiharimoto.mastertool.core.deck.DeckGroupsCodec
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.core.siding.SidingMarks
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
import com.kaiharimoto.mastertool.core.prefs.TierCeiling
import com.kaiharimoto.mastertool.core.prefs.TierPrefs
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec
import com.kaiharimoto.mastertool.core.shootout.teach.JudgedPair
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
import kotlinx.serialization.json.JsonObject
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.core.web.WebEntry
import com.kaiharimoto.mastertool.core.web.WebLibrary
import com.kaiharimoto.mastertool.core.deck.DeckVersionCodec
import com.kaiharimoto.mastertool.core.ai.proposals.Proposal
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalBook
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalCodec

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
    fun aiSettingsFromBeforeTheTiersReadAsTheyBehaved() {
        // 1.1.83 and before: deleting asked or not by alwaysAllow alone, and every other tool ran. Read with no tiers, the
        // same: changes and outside allowed, deleting asked unless never asked (H.4).
        val old = """{"ai":{"name":"Ai","alwaysAllow":true,"factCheck":true}}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), old).sanitised()
        assertEquals(TierPrefs(), p.ai.tiers)
        assertTrue(p.ai.alwaysAllow)
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
    fun aGroupsPayloadFromBeforeSetsReadsAsOneSetAndWritesBackTheSame() {
        // 1.0.39's shape: one breakdown under "groups", no "groupSets" (2026-10).
        val old = Json.parseToJsonElement(
            """{"notes":"x","groups":{"defs":[{"id":"g1","name":"Engine","color":2,"order":0}],"cards":{"14558127":"g1"},"lens":"ROLES","fitted":[14558127]}}"""
        ) as JsonObject
        val stored = DeckGroupsCodec.read(old)
        assertTrue(stored.sets.isPlain)
        assertEquals(1, stored.groups.groups.size)
        assertEquals(old.toString(), DeckGroupsCodec.write(old, stored).toString())
    }

    @Test
    fun aQuestionFromBeforeConditionsReadsWithNoneAndWritesBackTheSame() {
        // A deck's goals before Phase G: asks only. Read with no condition, written back byte for byte; a condition, once
        // set, is its own key, which an older build skips.
        val old = Json.parseToJsonElement(
            """{"groups":{"defs":[{"id":"g1","name":"Starters","color":2,"order":0}],"cards":{"14558127":"g1"},"lens":"ROLES","goals":[{"id":"q1","name":"Opens","hand":5,"asks":{"g1":"AT_LEAST_1"}}]}}"""
        ) as JsonObject
        val stored = DeckGroupsCodec.read(old)
        val goal = stored.goals.goals.single()
        assertEquals("", goal.condition)
        assertEquals(old.toString(), DeckGroupsCodec.write(old, stored).toString())
        val withCondition = stored.copy(goals = stored.goals.upsert(goal.copy(condition = "Starters>=1 | Hand traps>=2")))
        val back = DeckGroupsCodec.read(DeckGroupsCodec.write(old, withCondition))
        assertEquals("Starters>=1 | Hand traps>=2", back.goals.goals.single().condition)
    }

    @Test
    fun aSidePlanFromBeforeCopiesWerePickedReadsAndWritesBackTheSame() {
        // 1.0.35's plan: cards only, no "outCopies"/"inCopies" (2026-10); its marks are the first copies, as before.
        val old = Json.parseToJsonElement(
            """{"siding":{"matchups":[{"id":"m-1","name":"Yubel","first":{"out":[14558127,14558127],"in":[9822220],"note":"Why"},"second":{"out":[],"in":[]}}]}}"""
        ) as JsonObject
        val siding = SidingCodec.read(old)
        val first = siding.matchups.single().first
        assertTrue(first.outCopies.isEmpty() && first.inCopies.isEmpty())
        assertEquals(listOf(true, true, false), SidingMarks.of(List(3) { CardId(14558127) }, first.out, first.outCopies))
        assertEquals(old.toString(), SidingCodec.write(old, siding).toString())
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
    fun aTakeMadeOnAPhoneAsI9WritesItReads() {
        // I.9: a take recorded on a phone or tablet names its camera's frames file (the bytes are held in CameraFramesTest)
        // and the device's own H.264 as its encoder; the desk's takes keep "camera.mkv".
        val phone = """{"id":"t1790000000000","presentationId":"p1","name":"Take 2","startedAt":1790000000000,"durationMs":2300,
            "events":[{"at":0,"kind":"GO"},{"at":1100,"kind":"GO","slide":1}],"camera":"camera.frames","audio":"audio.wav",
            "rendered":"Take 2.mp4","renderedCodec":"h264_mediacodec","renderedAt":1790000060000}"""
        val t = assertNotNull(com.kaiharimoto.mastertool.core.present.record.TakeCodec.decode(phone))
        assertEquals(com.kaiharimoto.mastertool.core.present.record.Take.CAMERA_FRAMES, t.camera)
        assertEquals("H.264 MP4", com.kaiharimoto.mastertool.core.present.record.EncoderPick.describe(t.renderedCodec!!))
        assertEquals(1, com.kaiharimoto.mastertool.core.present.record.TakeTimeline.stateAt(t.events, 1_200).cursor.slide)
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
        // Dealt before keyed dealing (Phase G): deal 1, the riffle, which it replays with.
        assertEquals(1, r.deal)
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
        // Dealt before keyed dealing (Phase G): deal 1.
        assertEquals(1, run.deal)
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

    @Test
    fun recordsFromBeforePhaseGReadWithNoPrintAndTheirPrintsReadBack() {
        // Phase G, record now: a game, a duel record's seat, a session report and a Shootout trial kept before carry no
        // deck print and no source, and read with none; written now, they read back.
        val prep = PrepCodec.decode("""{"games":[{"id":"g1","at":1,"deckId":"d1","opponent":"w2","opponentName":"Yubel","turn":"FIRST",
            "result":"WIN","keyCards":[14558127]}]}""")
        val g = prep.games.single()
        assertEquals(null, g.deckPrint)
        assertEquals(null, g.source)
        assertEquals(listOf(14_558_127), g.keyCards)
        val stamped = PrepCodec.decode(PrepCodec.encode(prep.copy(games = listOf(g.copy(deckPrint = "abc", source = TestGame.SOURCE_AI))))).games.single()
        assertEquals("abc", stamped.deckPrint)
        assertEquals(TestGame.SOURCE_AI, stamped.source)

        val record = assertNotNull(DuelResultCodec.decode("""{"id":"d9","duel":"d9","ended":1,"seats":[{"name":"Kai","player":"person"},
            {"name":"Ai","player":"ai"}],"winner":0,"turns":5}"""))
        assertEquals(listOf(null, null), record.seats.map { it.deckPrint })
        val withPrint = record.copy(seats = record.seats.map { it.copy(deckPrint = "p") })
        assertEquals(listOf("p", "p"), assertNotNull(DuelResultCodec.decode(DuelResultCodec.encode(withPrint))).seats.map { it.deckPrint })

        val report = ReportLog.read("""[{"deckId":"d1","deckName":"Labrynth","at":5,"mode":"tune","understanding":60}]""").single()
        assertEquals(null, report.deckPrint)
        assertEquals("q", ReportLog.read(ReportLog.write(listOf(report.copy(deckPrint = "q")))).single().deckPrint)

        val shootout = ShootoutCodec.decode("""{"version":1,"deck":"d","trials":[{"id":"s-1","stratum":"G1_FIRST","hand":[1,2,3,4,5],"answer":"LEAN_WIN"}]}""")!!
        assertEquals(null, shootout.trials.single().deckPrint)
        val kept = shootout.copy(trials = listOf(shootout.trials.single().copy(deckPrint = "r")))
        assertEquals("r", ShootoutCodec.decode(ShootoutCodec.encode(kept))!!.trials.single().deckPrint)

        // A proof kept with the print before 1.1.62 (the Side Deck in it, passcodes as written) is not stale against the
        // same deck: its earlier print is read beside the new one.
        val deck = Deck(main = listOf(CardId(1), CardId(1), CardId(2)), extra = listOf(CardId(3)), side = listOf(CardId(4)))
        val old = Ledger.read("""[{"entry":"Opens 74.2%.","proofs":[{"tool":"hand_odds","input":"{}","deck":"${Ledger.fingerprintV1(deck)}"}],"status":"CHECKED"}]""")
        assertEquals(Proven.Status.CHECKED, Ledger.staleAgainst(old, Ledger.fingerprint(deck), also = Ledger.prints(deck)).single().status)
        assertEquals(Proven.Status.STALE, Ledger.staleAgainst(old, Ledger.fingerprint(deck)).single().status)
    }

    @Test
    fun aWebAndAnEventFromBeforeTheEventReadWithNoSourceAndNoRestOfTheRoom() {
        // Phase G, G.5: a web's entry kept before a share recorded where it came from reads with none; an event kept before
        // the rest of the room and the clock reads with neither counted. Written now, both read back.
        val json = Json { ignoreUnknownKeys = true }
        val web = json.decodeFromString(WebLibrary.serializer(),
            """{"webs":[{"id":"w","name":"Regional","entries":[{"deckId":"d1","mine":true,"share":20},{"deckId":"d2","share":40}]}]}""")
        assertEquals(listOf(null, null), web.webs.single().entries.map { it.shareSource })
        val sourced = web.webs.single().shared("d2", 35, WebEntry.SOURCE_TOPS)
        val back = json.decodeFromString(DeckWeb.serializer(), json.encodeToString(DeckWeb.serializer(), sourced))
        assertEquals(WebEntry.SOURCE_TOPS, back.entry("d2")?.shareSource)
        assertEquals(null, back.shared("d2", null).entry("d2")?.shareSource, "a share cleared takes its source with it")

        val prep = PrepCodec.decode("""{"events":[{"id":"e","name":"Regional","date":"2026-11-01","tier":2,"attendance":64}]}""")
        val e = prep.events.single()
        assertEquals(0, e.otherShare)
        assertEquals(50, e.otherWin)
        assertEquals(false, e.countTime)
        val set = PrepCodec.decode(PrepCodec.encode(prep.copy(events = listOf(e.copy(otherShare = 15, otherWin = 40, countTime = true))))).events.single()
        assertEquals(listOf(15, 40), listOf(set.otherShare, set.otherWin))
        assertEquals(true, set.countTime)
    }

    @Test
    fun gamesAndPrepFromBeforeVersionsReadWithNoOpponentListNoHandAndPeopleOnly() {
        // Phase G, G.8: a game kept before it recorded the opponent's list or the opening hand reads with neither, and a Prep
        // document from before reads as people only, earlier lists counted. Written now, all read back.
        val prep = PrepCodec.decode("""{"games":[{"id":"g1","at":1,"deckId":"d1","opponent":"w2","opponentName":"Yubel","turn":"FIRST","result":"W","deckPrint":"p"}]}""")
        val g = prep.games.single()
        assertEquals(null, g.opponentCards)
        assertEquals(null, g.opening)
        assertEquals(emptyList(), prep.sources)
        assertEquals(true, prep.earlier)
        val kept = PrepCodec.decode(PrepCodec.encode(prep.copy(games = listOf(g.copy(opponentCards = listOf(1, 2), opening = listOf(3, 4, 5))), sources = listOf(TestGame.SOURCE_AI), earlier = false)))
        assertEquals(listOf(1, 2), kept.games.single().opponentCards)
        assertEquals(listOf(3, 4, 5), kept.games.single().opening)
        assertEquals(listOf(TestGame.SOURCE_AI), kept.sources)
        assertEquals(false, kept.earlier)
        // A version file as G.8 writes it, read back; a broken one is no version at all.
        val v = DeckVersionCodec.decode("""{"deckId":"d1","print":"abc","name":"Lab","at":5,"main":[1,1,2],"extra":[3],"side":[4],"parent":"zz","later":"a newer build's key"}""")!!
        assertEquals(listOf(CardId(1), CardId(1), CardId(2)), v.deck.main)
        assertEquals("zz", v.parent)
        assertEquals(null, v.parentDeck)
        assertEquals(null, DeckVersionCodec.decode("not a version"))
        assertEquals(null, DeckVersionCodec.decode("""{"deckId":"d1"}"""), "a version with no print is none")
    }

    @Test
    fun aGameFromBeforeItsLoggerWasKeptReadsAsNotAisAndAReviewFromBeforeItsPriorReadsWithNone() {
        // The red team's R4 (2026-10): a game kept before `by` was recorded reads as no one's in particular — the person's —
        // and marks Ai's suggestions as it did; one Ai logged reads back as Ai's.
        val prep = PrepCodec.decode("""{"games":[{"id":"g1","at":1,"deckId":"d1","opponent":"w2","opponentName":"Yubel","turn":"FIRST",
            "result":"W","deckPrint":"p","source":"person"}]}""")
        val g = prep.games.single()
        assertEquals(null, g.by)
        assertTrue(Proposals.marks(g))
        val logged = PrepCodec.decode(PrepCodec.encode(prep.copy(games = listOf(g.copy(by = TestGame.BY_AI))))).games.single()
        assertEquals(TestGame.BY_AI, logged.by)
        assertFalse(Proposals.marks(logged))

        // R9: a review as F.1–F.3 wrote it, with no prior recorded, reads with none and says nothing of one; written now, it
        // reads back with its prior.
        val review = ReviewCodec.decode("""{"replay":"r1","duel":"d1","version":"search-v0","strength":"Fast","at":5,"played":4,"decisions":[],"complete":true,"steps":3}""")!!
        assertEquals("", review.prior)
        assertEquals("", review.priorPrint)
        assertEquals(null, review.priorDay)
        assertEquals(null, PriorPrint.words(review))
        val under = review.under(Prior.Interaction(mapOf(1 to 0.5)), "2026-10-01")
        assertEquals(under, ReviewCodec.decode(ReviewCodec.encode(under)))
    }

    @Test
    fun aProposalFileAsG9WritesItReadsBackAndANewerOnesKeysPassBy() {
        // Phase G, G.9: <data>/ai/proposals/<deck>.json. A proposal with only its id and ops reads with every default; a
        // newer build's key is skipped; a broken file reads as no proposals, never an error.
        val book = ProposalCodec.decode("""{"deckId":"d1","proposals":[{"id":"p1","ops":[{"op":"remove","card":"Droll & Lock Bird","count":1}],"later":true}]}""")
        val p = book.proposals.single()
        assertEquals(Proposal.OPEN, p.state)
        assertEquals(null, p.toPrint)
        assertEquals(emptyList(), p.expect)
        assertEquals("Droll & Lock Bird", p.ops.single().card)
        assertEquals(ProposalBook(), ProposalCodec.decode("{broken"))
        assertEquals(book, ProposalCodec.decode(ProposalCodec.encode(book)))
    }

    @Test
    fun aStressSuiteAsE1WritesItReadsBackAndANewerOnesKeysPassBy() {
        // Phase E, E.1: <data>/effects/mapper/<deck>/suite.json (StressSuite), and a duel log's engine memo with the lingering
        // negation E.1 adds. A set with only its cards reads with every default; a newer build's key is skipped; a broken
        // file reads as none, never an error.
        val suite = assertNotNull(StressSuite.decode("""{"sets":[{"cards":[97268402,14558127]},{"cards":[14558127],"by":"ai","why":"the field's","share":0.6,"later":1}],"later":true}"""))
        assertEquals(listOf("14558127+97268402", "14558127"), suite.sets.map { it.key })
        assertEquals(StressSet.PERSON, suite.sets[0].by)
        assertEquals(0.6, suite.sets[1].share)
        assertEquals(null, StressSuite.decode("{broken"))
        assertEquals(suite.sets.map { it.key }, StressSuite.decode(suite.encode())?.sets?.map { it.key })
        val memo = Json { ignoreUnknownKeys = true }.decodeFromString(FxMemo.serializer(), """{"batch":3,"negated":{"uid":5,"life":2},"later":1}""")
        assertEquals(Negation(5, 2), memo.negated)
        assertEquals(null, Json { ignoreUnknownKeys = true }.decodeFromString(FxMemo.serializer(), """{"batch":3}""").negated)
    }

    @Test
    fun theDuelsSettingsBeforeE3bLeaveAisSeatToTheModel() {
        // Phase E, E.3b: who plays Ai's seat (`DuelPrefs.aiPlayer`); every setting written before reads as the model's.
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(com.kaiharimoto.mastertool.core.duel.DuelPrefs.serializer(), """{"aiSeat":1,"aiPlays":true,"aiStrength":"max"}""")
        assertEquals(com.kaiharimoto.mastertool.core.duel.DuelPrefs.MODEL, old.aiPlayer)
        val now = json.decodeFromString(com.kaiharimoto.mastertool.core.duel.DuelPrefs.serializer(), """{"aiPlayer":"search","aiPlays":true,"later":1}""")
        assertEquals(com.kaiharimoto.mastertool.core.duel.DuelPrefs.SEARCH, now.aiPlayer)
    }

    @Test
    fun theLaddersBookAsE4WroteItReads() {
        // Phase E, E.4: `<data>/duel/ladder.json`, every game the ladder played; a later build's keys pass by, a broken file is empty.
        val text = """{"version":1,"games":[{"seed":3,"players":["search-v0/field/fast","greedy"],"winner":0,"how":"lp","turns":7,""" +
            """"bench":"0123456789ab","at":1791700000000,"later":true},{"seed":3,"players":["greedy","search-v0/field/fast"],"bench":"0123456789ab"}],"more":1}"""
        val book = com.kaiharimoto.mastertool.core.duel.search.ladder.LadderCodec.decode(text)
        assertEquals(2, book.games.size)
        val g = book.games[0]
        assertEquals(listOf("search-v0/field/fast", "greedy"), g.players)
        assertEquals(1.0, g.score("search-v0/field/fast"))
        assertEquals(0.5, book.games[1].score("greedy"), "no winner written: a draw")
        // Games on another bench are kept and never counted.
        assertTrue(book.on().isEmpty())
        assertTrue(com.kaiharimoto.mastertool.core.duel.search.ladder.LadderCodec.decode("{").games.isEmpty())
    }

    @Test
    fun aSearchVersionAsE5bWroteItReads() {
        // Phase E, E.5b: `<data>/duel/search/value-<n>.json`, a tuned or proposed version with its gate's verdict.
        val text = """{"n":3,"weights":{"version":3,"interruptions":4.5,"negates":1.0,"bodies":1.0,"set":0.5,"hand":0.5,""" +
            """"handTraps":1.0,"damage":1.0,"taken":0.5,"theirBodies":1.5,"theirInterruptions":3.0,"later":2},"hold":0.3,""" +
            """"chance":false,"parent":0,"source":"tuned","step":"interruptions up","note":"v0 with interruptions up",""" +
            """"verdict":{"wins":3,"losses":2,"ties":19,"p":0.5,"bar":0.025,"pass":false,"bench":"d9ee52917657","at":7},"at":5,"more":true}"""
        val v = com.kaiharimoto.mastertool.core.duel.search.SearchVersions.decode(text)!!
        assertEquals(3, v.n)
        assertEquals(4.5, v.weights.interruptions)
        assertEquals("interruptions up", v.step)
        assertEquals(false, v.verdict?.pass)
        // A version written with only its number reads with v0's weights.
        val bare = com.kaiharimoto.mastertool.core.duel.search.SearchVersions.decode("""{"n":4,"parent":0}""")!!
        assertEquals(com.kaiharimoto.mastertool.core.duel.search.ValueWeights(), bare.weights)
        assertEquals(null, bare.verdict)
    }

    @Test
    fun aReplaysReviewAsF1WroteItReads() {
        // Phase F, F.1: `<data>/duel/coach/<replay>.json`, every decision judged or not, with the search that judged it.
        val text = """{"replay":"r1700000000000","duel":"d17","version":"search-v0","strength":"Fast","at":1700000005000,""" +
            """"played":1700000000000,"decisions":[{"at":12,"seat":0,"by":"person","played":"End the turn","best":"Battle Phase",""" +
            """"line":["Battle Phase","Example Knight attacks directly"],"drop":61.4,"regret":999997.5,"mark":"BLUNDER","worlds":8,""" +
            """"complete":true,"why":null,"later":1},{"at":15,"seat":1,"by":"ai","why":"not a moment the search weighs"}],""" +
            """"complete":false,"steps":40,"more":true}"""
        val b = com.kaiharimoto.mastertool.core.duel.coach.ReviewCodec.decode(text)!!
        assertEquals("r1700000000000", b.replay)
        assertEquals(2, b.decisions.size)
        assertEquals(com.kaiharimoto.mastertool.core.duel.coach.Mark.BLUNDER.name, b.decisions[0].mark)
        assertEquals(61.4, b.decisions[0].drop)
        assertEquals(false, b.decisions[1].judged)
        assertEquals(false, b.complete)
        assertEquals(true, b.fits(40))
        assertEquals(false, b.fits(41))
        // A review written with only its replay reads as one with nothing judged, and fits any length.
        val bare = com.kaiharimoto.mastertool.core.duel.coach.ReviewCodec.decode("""{"replay":"r1"}""")!!
        assertEquals(emptyList(), bare.decisions)
        assertEquals(true, bare.fits(7))
    }

    @Test
    fun aReviewWithItsWaysAsF3WroteItReadsAndAMistakesDrillKeepsItsKey() {
        // Phase F, F.3: a judged decision keeps where it was and the ways the search weighed, a drill's choices.
        val text = """{"replay":"r9","decisions":[{"at":12,"seat":0,"by":"person","played":"End the turn","best":"Battle Phase",""" +
            """"drop":38.6,"mark":"BLUNDER","turn":3,"phase":"Main 1","ways":[{"words":"Battle Phase","drop":0.0,"later":1},""" +
            """{"words":"End the turn","drop":38.6}],"later":2}],"steps":30}"""
        val b = com.kaiharimoto.mastertool.core.duel.coach.ReviewCodec.decode(text)!!
        val j = b.decisions.single()
        assertEquals(3, j.turn)
        assertEquals("Main 1", j.phase)
        assertEquals(2, j.ways.size)
        // A drill from a mistake is kept in Prep's drills under its own key, beside the siding plans'.
        val doc = com.kaiharimoto.mastertool.core.prep.PrepCodec.decode(
            """{"drills":{"m1:FIRST":{"seen":2,"correct":1,"lastAt":5,"box":1},"misplay:r9:12":{"seen":1,"correct":1,"lastAt":6,"box":1}}}""",
        )
        assertEquals(2, doc.drills.size)
        assertEquals(1, doc.drills["misplay:r9:12"]?.box)
    }

    @Test
    fun aClaimBeforeF2IsAFactAndAnOpinionReads() {
        // Phase F, F.2: a fact-check claim may carry its kind; one written before has none and is a fact.
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check.serializer(), """{"turn":3,"claims":[{"claim":"Ash negates searches","verdict":"OK","source":"card_info"}]}""")
        assertEquals(false, old.claims.single().opinion)
        val now = json.decodeFromString(com.kaiharimoto.mastertool.core.ai.check.FactCheck.Check.serializer(), """{"turn":3,"claims":[{"claim":"They hold Ash","verdict":"UNSURE","kind":"opinion","later":1}],"more":2}""")
        assertEquals(true, now.claims.single().opinion)
    }

    @Test
    fun aSeriesRunAsG21WroteItReads() {
        // Phase G part two, G2.1: `<data>/duel/series/<id>.json`, two versions' best-of-three matches against the field.
        val text = """{"id":"s1","deck":"d1","label":"Cut 1 Pawn, add 1 Ward","nameA":"Mine","nameB":"Mine with Ward","printA":"aa",""" +
            """"printB":"bb","pairs":4,"version":"search-v0","strength":"Fast","opponents":[{"id":"o1","name":"Giants","share":30.0,""" +
            """"a":[{"seed":11,"opener":0,"games":[{"first":0,"winner":1,"how":"lp","turns":7},{"first":1,"winner":0,"how":"lp","turns":5,""" +
            """"later":1},{"first":0,"winner":0,"how":"concede","turns":4}],"winner":0}],"b":[]}],"coverage":[{"deck":"d1","name":"Mine",""" +
            """"written":30,"copies":40}],"at":5,"complete":false,"more":true}"""
        val r = com.kaiharimoto.mastertool.core.duel.match.series.SeriesCodec.decode(text)!!
        assertEquals(4, r.pairs)
        assertEquals(3, r.opponents.single().a.single().games.size)
        assertEquals(0, r.opponents.single().a.single().winner)
        assertEquals(0.75, r.coverage.single().share)
        assertEquals(1, r.played)
        // A run written with only its id reads as one with nothing played.
        assertEquals(0, com.kaiharimoto.mastertool.core.duel.match.series.SeriesCodec.decode("""{"id":"s2"}""")!!.played)
    }

    @Test
    fun aProposalsExpectationSaysWhatMeasuredItAndOneBeforeG22ReadsWithNothing() {
        // Phase G part two, G2.2: an expectation names what measured it, and with simulated matches the run it was read from.
        val json = Json { ignoreUnknownKeys = true }
        val now = json.decodeFromString(
            com.kaiharimoto.mastertool.core.ai.proposals.ProposalBook.serializer(),
            """{"deckId":"d","proposals":[{"id":"p1","deckId":"d","expect":[{"metric":"Match win against the field","before":48.2,""" +
                """"after":53.1,"low":49.0,"high":57.4,"unit":"%","kind":"series","run":"s17","n":12,"later":1}],"more":2}]}""",
        )
        val e = now.proposals.single().expect.single()
        assertEquals("series", e.kind)
        assertEquals("s17", e.run)
        assertEquals(12, e.n)
        val old = json.decodeFromString(
            com.kaiharimoto.mastertool.core.ai.proposals.ProposalBook.serializer(),
            """{"deckId":"d","proposals":[{"id":"p0","expect":[{"metric":"Opens going first","before":71.2,"after":76.4}]}]}""",
        )
        assertEquals(null, old.proposals.single().expect.single().kind)
        assertEquals(null, old.proposals.single().expect.single().run)
    }

    @Test
    fun aMoveByTheSearchSaysSoAndOneBeforeE3ReadsWithNone() {
        // Phase E, E.3: a move the app's own search made carries its version (`Provenance.engine`); every move before has none.
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(com.kaiharimoto.mastertool.core.duel.Provenance.serializer(), """{"by":"ai","aiSeat":1,"aiKnows":"self","view":"abc"}""")
        assertEquals(null, old.engine)
        val now = json.decodeFromString(com.kaiharimoto.mastertool.core.duel.Provenance.serializer(), """{"by":"ai","aiSeat":0,"aiKnows":"self","engine":"search-v0","later":1}""")
        assertEquals("search-v0", now.engine)
        assertTrue(now.byAi)
    }

    @Test
    fun aDuelHeaderBeforeE2DealsAsItDidAndAForksStartReadsBack() {
        // Phase E, E.2: a fork's header keeps the table it begins from (`DuelHeader.start`), so a solved line opens as a replay.
        // A header with none — every duel before, every dealt duel now — deals from its decks; one with a start begins there.
        val dealt = assertNotNull(DuelCodec.decode("""{"header":{"id":"d","seed":3,"seats":[{"name":"Kai","main":[1,2,3,4,5,6]},{"name":"Rival"}],"solo":true},"entries":[],"cursor":0}"""))
        assertEquals(null, dealt.header.start)
        assertEquals(6, com.kaiharimoto.mastertool.core.duel.DuelGame.of(dealt).state.seats[0].deck.size)
        val forked = assertNotNull(DuelCodec.decode(
            """{"header":{"id":"f","seed":3,"seats":[{"name":"Kai"},{"name":"Rival"}],"start":{"cards":{"7":{"uid":7,"code":1,"owner":0,"pos":"FACE_UP_ATK"}},
            "seats":[{"name":"Kai","lp":4000,"monsters":[7,null,null,null,null]},{"name":"Rival"}],"turn":3,"phase":"MAIN1","later":1}},"entries":[],"cursor":0}""",
        ))
        val s = com.kaiharimoto.mastertool.core.duel.DuelGame.of(forked).state
        assertEquals(4000, s.seats[0].lp)
        assertEquals(3, s.turn)
        assertEquals(listOf(7), s.onField())
    }

    @Test
    fun aPuzzleAsM4WroteItReadsAndANewerOnesKeysPassBy() {
        // Phase M, M4 (H.7): `<data>/duel/puzzles/<id>.json`, a position as the duel's own log with a goal and its last answer.
        val text = """{"version":1,"id":"p5","title":"Lethal through Snare","record":{"header":{"id":"d","seed":3,"seats":""" +
            """[{"name":"Kai","main":[1,2,3]},{"name":"Rival","main":[4,5,6]}]},"entries":[],"cursor":0},"seat":0,"knows":"full",""" +
            """"goal":{"t":"all","all":[{"t":"their-lp","atMost":0},{"t":"interruptions","atLeast":2}]},"from":"The duel in play",""" +
            """"created":5,"answer":{"verdict":"NONE","read":"their LP 900","moves":4100,"positions":380,"complete":true,""" +
            """"effects":"abc123","at":6,"later":1},"later":2}"""
        val p = com.kaiharimoto.mastertool.core.duel.mapper.puzzle.MapPuzzles.decode(text)!!
        assertEquals("Lethal through Snare", p.title)
        assertEquals(com.kaiharimoto.mastertool.core.duel.ai.DuelBrief.FULL, p.knows)
        assertEquals(
            com.kaiharimoto.mastertool.core.duel.mapper.puzzle.BoardGoal.All(listOf(
                com.kaiharimoto.mastertool.core.duel.mapper.puzzle.BoardGoal.TheirLp(0),
                com.kaiharimoto.mastertool.core.duel.mapper.puzzle.BoardGoal.Interruptions(2),
            )),
            p.goal,
        )
        assertEquals("NONE", p.answer!!.verdict)
        assertEquals(true, p.answer!!.complete)
        assertEquals(3, p.record.header.seats[0].main.size)
        assertNotNull(p.game())
    }

    @Test
    fun aGuidesNotesAsH6WroteThemRead() {
        // Phase H, H.6: `<data>/ai/guides/<deck>.notes.json`, the person's highlights and notes, by block id.
        val n = com.kaiharimoto.mastertool.core.ai.report.book.BookNotes.read(
            """{"version":1,"marks":[{"block":"c1.s2.b3","highlight":true,"note":"Ash goes here","at":5,"later":1},{"block":"c2.s1.b1","note":"Ask Ai"}],"later":2}""",
        )
        assertEquals(2, n.marks.size)
        assertEquals(true, n.of("c1.s2.b3")!!.highlight)
        assertEquals("Ash goes here", n.of("c1.s2.b3")!!.note)
        assertEquals(false, n.of("c2.s1.b1")!!.highlight)
        // Nothing, or nothing readable, is no marks.
        assertEquals(0, com.kaiharimoto.mastertool.core.ai.report.book.BookNotes.read(null).marks.size)
        // The interactive book (1.1.87): a mark H.6 wrote has no ticks and no grades; a newer mark's are read.
        assertTrue(n.of("c1.s2.b3")!!.ticks.isEmpty() && n.of("c1.s2.b3")!!.got.isEmpty() && n.of("c1.s2.b3")!!.missed.isEmpty())
        val newer = com.kaiharimoto.mastertool.core.ai.report.book.BookNotes.read("""{"marks":[{"block":"c3.s1.b2","ticks":[0,2],"got":[1],"missed":[0],"later":1}]}""")
        assertEquals(listOf(0, 2), newer.of("c3.s1.b2")!!.ticks)
        assertEquals(false, newer.of("c3.s1.b2")!!.graded(0))
    }

    @Test
    fun aRunTraceAsH8WroteItReads() {
        // Phase H, H.8: `<data>/ai/traces/<day>.jsonl`, one run of Ai a line; a line this build cannot read is passed over.
        val text = """{"version":1,"id":"s1-5","session":"s1","mode":"chat","provider":"anthropic","model":"claude-opus-5-5",""" +
            """"started":5,"ended":905,"rounds":[{"input":900,"output":40,"later":1}],"tools":[{"name":"card_info","ms":120,"chars":300},""" +
            """{"name":"edit_deck","failed":true,"refused":true}],"retries":1,"end":"done","usage":{"input":900,"output":40},""" +
            """"costUsd":0.0044,"later":2}""" + "\nnot a run\n"
        val runs = com.kaiharimoto.mastertool.core.ai.trace.TraceBook.read(text)
        val r = runs.single()
        assertEquals(900, r.ms)
        assertEquals(1, r.refusals)
        assertEquals(0, r.toolErrors)
        assertEquals(0.0044, r.costUsd)
        assertEquals(com.kaiharimoto.mastertool.core.ai.trace.RunTrace.DONE, r.end)
    }

    @Test
    fun aConnectionAsH8WroteItHasNoLimitAndANewerCeilingPassesAnOlderBuild() {
        // Phase H, H.4's ceiling (1.1.87): a connection 1.1.86 wrote has no `ceiling` and reads with no limit; one this build
        // wrote carries it, and an older build ignores the key.
        val old = """{"ai":{"connections":[{"id":"c1","provider":"ollama","label":"Home","model":"qwen3","baseUrl":"http://192.168.1.4:11434","window":32768}],"active":"c1","tiers":{"write":"allow","outward":"allow","destructive":"ask"}}}"""
        val p = prefs.decodeFromString(NeuePreferences.serializer(), old).sanitised()
        val c = p.ai.connection!!
        assertTrue(c.ceiling.none)
        assertEquals(32768, c.window)
        val written = prefs.encodeToString(NeuePreferences.serializer(), p.copy(ai = p.ai.copy(connections = listOf(c.copy(ceiling = TierCeiling.PRESETS.getValue("read"))))))
        val back = prefs.decodeFromString(NeuePreferences.serializer(), written).ai.connection!!
        assertEquals("read", back.ceiling.preset)
        val later = """{"ai":{"connections":[{"id":"c1","provider":"ollama","ceiling":{"write":"off","outward":"off","destructive":"off","later":1}}]}}"""
        assertEquals("read", prefs.decodeFromString(NeuePreferences.serializer(), later).ai.connection!!.ceiling.preset)
    }

    @Test
    fun aShootoutTrialAs1188WroteItReadsWithNoReasonsAndANewerOnesReasonsRead() {
        // Phase S stage 4, S4.1 (1.1.89): a trial may now carry `surprised` (the model's chance of the answer when it asked
        // why), and its `tags` and `decisive` — fields kept since 1.1.2 and filled only now. A trial 1.1.88 wrote reads with
        // none; a newer one's keys read here, and an older build skips the one it does not know.
        val old = com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec.decode(
            """{"version":1,"deck":"d","trials":[{"id":"s-1","stratum":"ALONE_FIRST","hand":[1,2,3,4,5],"answer":"LEAN_WIN","ms":4200,"session":"s1"}]}""",
        )!!.trials.single()
        assertTrue(old.tags.isEmpty())
        assertEquals(null, old.decisive)
        assertEquals(null, old.surprised)
        val newer = com.kaiharimoto.mastertool.core.shootout.store.ShootoutCodec.decode(
            """{"version":1,"deck":"d","trials":[{"id":"s-2","stratum":"ALONE_FIRST","hand":[1,2,3,4,5],"answer":"CLEAR_LOSS","tags":["bricked"],"decisive":3,"surprised":0.03,"later":1}]}""",
        )!!.trials.single()
        assertEquals(listOf("bricked"), newer.tags)
        assertEquals(3, newer.decisive)
        assertEquals(0.03, newer.surprised)
    }

    @Test
    fun aShootoutReadingOfTheDeckAs1191WroteItReads() {
        // Phase S stage 4, S4.3 (1.1.91): Ai's reading of a deck, `<deck>/<target>.priors.json` beside the trials — new in
        // this release. Its shape as written then reads here, a later key skipped; a file that will not read is none.
        val text = """{"version":1,"roles":[{"card":1001,"role":"Starters"}],"leans":[{"role":"Starters","step":2,"why":"engine","on":true},""" +
            """{"role":"Bricks","step":-1,"why":"","on":false}],"pairs":[{"a":1001,"b":1019,"why":"searches it","on":true}],"by":"person",""" +
            """"model":"m","at":5,"deckPrint":"abc","summary":"Combo.","later":{"x":1}}"""
        val p = com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriorsCodec.decode(text)!!
        assertEquals("Starters", p.roles.single().role)
        assertEquals(listOf(2, -1), p.leans.map { it.step })
        assertEquals(false, p.leans[1].on)
        assertEquals(1019, p.pairs.single().b)
        assertEquals("person", p.by)
        assertEquals("abc", p.deckPrint)
        assertEquals(null, com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriorsCodec.decode("{oops"))
    }

    @Test
    fun aShootoutAblationAs1192WroteItReads() {
        // Phase S stage 4, S4.4 (1.1.92): what each thing Ai is handed is worth, `<deck>/<target>.ablation.json` — new in this
        // release, never trials. Its shape as written then reads here, a later key skipped.
        val text = """{"version":1,"deck":"d","opponent":"o","runs":[{"id":"a1","at":5,"model":"m","connection":"Anthropic",""" +
            """"hands":["p1"],"said":[{"trial":"p1","variant":"NO_RUBRIC","answer":"LEAN_WIN","sure":0.8,"agrees":true}],"failed":1,""" +
            """"deckPrint":"abc","later":2}]}"""
        val log = com.kaiharimoto.mastertool.core.shootout.teach.JudgeAblation.decode(text)!!
        val run = log.runs.single()
        assertEquals("o", log.opponent)
        assertEquals(listOf("p1"), run.hands)
        assertEquals("NO_RUBRIC", run.said.single().variant)
        assertEquals(true, run.said.single().agrees)
        assertEquals(1, run.failed)
    }

    @Test
    fun aShootoutVerdictWithoutTheRubricsTimeReadsAsBeforeAndOneWithItReadsBack() {
        // The red team, 2026-10: Ai's answer now keeps when the rubric it was shown last changed (`rubricAt`), so a rubric
        // written since the person answered is never counted as held out. A verdict kept before carries none, reads with
        // none, and its pair is judged as it was (the rubric taken as older than the answer); an older build skips the key.
        val text = """{"version":1,"deck":"d","trials":[
            {"id":"p1","at":1760000000000,"stratum":"G1_FIRST","hand":[1,2,3,4,5],"opponent":[6,7,8,9,10,11],"answer":"LEAN_WIN"},
            {"id":"p1~exam","at":1760000500000,"stratum":"G1_FIRST","hand":[1,2,3,4,5],"opponent":[6,7,8,9,10,11],"answer":"LEAN_WIN",
             "judge":"ai","of":"p1","mode":"exam","ai":{"answer":"LEAN_WIN","sure":0.8,"rubric":"a1b2c3d4e5f6","asked":1760000400000}}]}"""
        val log = assertNotNull(ShootoutCodec.decode(text))
        val verdict = log.trials[1].ai!!
        assertEquals(null, verdict.rubricAt)
        assertEquals("a1b2c3d4e5f6", verdict.rubric)
        assertTrue(JudgedPair.all(log.trials).single().heldOut, "read as before")
        val now = log.copy(trials = listOf(log.trials[0], log.trials[1].copy(ai = verdict.copy(rubricAt = 1760000200000))))
        val back = assertNotNull(ShootoutCodec.decode(ShootoutCodec.encode(now)))
        assertEquals(1760000200000, back.trials[1].ai!!.rubricAt)
        assertEquals(now, back)
        assertFalse(JudgedPair.all(back.trials).single().heldOut, "the rubric changed after the answer")
    }

    @Test
    fun aGuidesNotesWithAMarkThisBuildCannotReadKeepTheRestAndTheMark() {
        // The red team, 2026-10: one mark that would not read (a newer build's shape, a null) read the whole file as no marks,
        // and the next mark wrote over it. The marks H.6 wrote read as they did; the one that will not read is kept, as it was.
        val text = """{"version":1,"marks":[{"block":"c1.s2.b3","highlight":true,"note":"Ash goes here","at":5},""" +
            """{"block":"c2.s1.b1","note":{"rich":"Ask Ai"},"later":1}],"later":2}"""
        val n = BookNotes.read(text)
        assertEquals(listOf("c1.s2.b3"), n.marks.map { it.block })
        val back = BookNotes.write(n)
        assertTrue("\"rich\"" in back && "c2.s1.b1" in back, back)
    }

    /**
     * Phase I, I.4: the stored lists that carry no version of their own — a bare JSON array is version 1 for good, since a
     * list wrapped in an object would read as nothing on an older device and be written over — each held here as the build
     * that first wrote it wrote it. Test scores' log of 1.0.99 (before tokens and an early stop were kept) and the replay
     * library's index of 1.1.51.
     */
    @Test
    fun testScoresLogAndTheReplayLibrarysIndexAsFirstWrittenRead() {
        val runs = com.kaiharimoto.mastertool.core.ai.eval.EvalLog.read(
            """[{"set":"hand-odds","connection":"anthropic","model":"m","at":1760000000000,"tries":2,
               "items":[{"id":"h1","passes":2,"tries":2,"firstPass":true},{"id":"h2","passes":0,"tries":2}]}]""",
        )
        val run = runs.single()
        assertEquals("hand-odds", run.set)
        assertEquals(0L, run.tokensIn)
        assertEquals(false, run.stoppedEarly)
        assertEquals(0.5, run.passAt1)
        assertEquals(0.5, run.passAll)
        assertEquals("", run.items[1].answer)
        val added = com.kaiharimoto.mastertool.core.ai.course.ReplayLibrary.readIndex(
            """[{"id":"123456","url":"https://www.duelingbook.com/replay?id=123456","players":"kai vs Ai","games":2,"addedAt":1760000000000}]""",
        )
        assertEquals("123456", added.single().id)
        assertEquals("", added.single().note)
        // Read back as written: what this build writes, an older one reads.
        assertEquals(added, com.kaiharimoto.mastertool.core.ai.course.ReplayLibrary.readIndex(com.kaiharimoto.mastertool.core.ai.course.ReplayLibrary.writeIndex(added)))
    }

    /**
     * Phase I, I.6: Ai's stated chances, `ai/predictions.json`, as the build that first wrote it wrote them — one settled
     * duel call and one round still open.
     */
    @Test
    fun aisPredictionsAsFirstWrittenRead() {
        val text = """{"version":1,"predictions":[""" +
            """{"id":"p1","at":1760000000000,"target":"duel-1","chance":0.7,"seat":0,"words":"Seat 0 (kai) wins","turn":3,""" +
            """"connection":"c1","model":"m","happened":true,"settled":1760000100000},""" +
            """{"id":"p2","at":1760000000000,"about":"round","target":"e1#2","chance":0.55,"connection":"c1","model":"m"}]}"""
        val ps = com.kaiharimoto.mastertool.core.ai.predict.Predictions.read(text)
        assertEquals(2, ps.size)
        assertEquals("duel", ps[0].about, "a duel's about is the default, left out")
        assertEquals(true, ps[0].happened)
        assertEquals(0, ps[0].seat)
        assertEquals("e1#2", ps[1].target)
        assertEquals(true, ps[1].open)
        assertEquals(ps, com.kaiharimoto.mastertool.core.ai.predict.Predictions.read(com.kaiharimoto.mastertool.core.ai.predict.Predictions.write(ps)))
    }
}
