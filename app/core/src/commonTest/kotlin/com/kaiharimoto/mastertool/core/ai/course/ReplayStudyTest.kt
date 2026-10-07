package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.CourseTools
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A course's DuelingBook replays (1.1.41): found in its chapters, read from DuelingBook's own document, studied, counted. */
class ReplayStudyTest {
    private val start = "https://metafy.gg/@joe/guides/branded-masterclass"

    private fun ch(n: Int, state: Chapter.State = Chapter.State.NOTED, scanned: Boolean = true) =
        Chapter(n, "Chapter $n", "$start/chapter-$n", state = state, scanned = scanned, depth = CourseDepth.CURRENT, videoChecked = true, watched = true)

    private fun course(vararg chapters: Chapter, replays: List<ReplayRef> = emptyList(), distilled: Boolean = false) =
        Course(id = "c1", start = start, deckId = "d1", deckName = "Branded", author = "Joe", chapters = chapters.toList(), listed = true, replays = replays, distilled = distilled)

    @Test
    fun aReplayIsKnownByItsPageAndItsId() {
        assertTrue(DbReplays.isReplay("https://www.duelingbook.com/replay?id=123-4567"))
        assertTrue(DbReplays.isReplay("https://duelingbook.com/replay?id=4567&game=2"))
        assertFalse(DbReplays.isReplay("https://www.duelingbook.com/deck?id=4567"))
        assertFalse(DbReplays.isReplay("https://www.duelingbook.com.evil.com/replay?id=1"))
        assertFalse(DbReplays.isReplay("https://www.duelingbook.com/replay"))
        assertEquals("https://www.duelingbook.com/replay?id=123-4567&game=2", DbReplays.normal("http://duelingbook.com/replay?game=2&id=123-4567&utm=x"))
        // Links and bare addresses in a chapter's words, each once.
        val found = DbReplays.found(
            listOf("Game 1" to "https://www.duelingbook.com/replay?id=1-11", "Shop" to "https://metafy.gg/shop", "again" to "https://duelingbook.com/replay?id=1-11"),
            "Watch this one too: https://www.duelingbook.com/replay?id=1-12.",
        )
        assertEquals(listOf("https://www.duelingbook.com/replay?id=1-11", "https://www.duelingbook.com/replay?id=1-12"), found)
    }

    @Test
    fun theStudyMayOpenAReplayPageAndNothingElseOnThatSite() {
        val c = course(ch(1))
        assertNull(BrowseGuard.openRefusal("https://www.duelingbook.com/replay?id=1-11", c))
        assertNotNull(BrowseGuard.openRefusal("https://www.duelingbook.com/", c))
        assertNotNull(BrowseGuard.openRefusal("https://www.duelingbook.com/deck?id=5", c))
    }

    @Test
    fun aChapterIsLookedOverForReplaysAndEachIsReadThenNotedThenDistilled() {
        // A course read before replays were looked for: each read chapter is scanned first.
        val old = course(ch(1, scanned = false), ch(2, scanned = false), distilled = true)
        assertEquals(StudyQueue.Step.Scan(1), StudyQueue.next(old))
        assertTrue(StudyQueue.more(old.copy(state = Course.State.DONE)))
        var c = old.found(1, listOf("https://www.duelingbook.com/replay?id=1-11", "https://www.duelingbook.com/replay?id=1-12"))
        c = c.found(2, listOf("https://www.duelingbook.com/replay?id=1-12", "https://www.duelingbook.com/replay?id=1-13"))
        assertEquals(listOf(1, 2, 3), c.replays.map { it.n })
        assertEquals(listOf(1, 1, 2), c.replays.map { it.chapter })
        assertEquals(c.replays.map { ReplayExam.held(it.url) }, c.replays.map { it.exam })
        // The exam is MasteryTest's: here every replay is studied.
        c = c.copy(replays = c.replays.map { it.copy(exam = false) }, consolidated = true)
        assertEquals(StudyQueue.Step.Replay(1), StudyQueue.next(c))
        c = c.with(c.replay(1)!!.copy(state = Chapter.State.READ))
        assertEquals(StudyQueue.Step.ReplayNotes(1), StudyQueue.next(c))
        // One that keeps failing is passed over; one that cannot be read at all is given up at once.
        c = c.with(c.replay(1)!!.copy(state = Chapter.State.NOTED, depth = CourseDepth.CURRENT))
        repeat(StudyQueue.ATTEMPTS) { c = StudyQueue.replayFailed(c, 2, "DuelingBook's check did not pass.") }
        c = StudyQueue.replayFailed(c, 3, "Not a replay.", giveUp = true)
        // The chapters were distilled before, at a shallower depth: the whole is distilled again, replays and all.
        assertEquals(StudyQueue.Step.Distil, StudyQueue.next(c))
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(c.copy(distilDepth = CourseDepth.CURRENT)))
        assertFalse(StudyQueue.more(c.copy(distilDepth = CourseDepth.CURRENT, state = Course.State.DONE)))
        // A new course reads its replays, puts its playbook together, then distils once, taking them all.
        val fresh = course(ch(1), replays = listOf(ReplayRef(1, "https://www.duelingbook.com/replay?id=1-11", 1, state = Chapter.State.NOTED, depth = CourseDepth.CURRENT)))
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(fresh))
        assertEquals(StudyQueue.Step.Distil, StudyQueue.next(fresh.copy(consolidated = true)))
        assertEquals("Studied 1 of 1 chapters and 1 of 1 replays", StudyQueue.line(fresh.copy(distilled = true, consolidated = true, distilDepth = CourseDepth.CURRENT)))
    }

    @Test
    fun anOlderBuildsCourseReadsWithNoReplaysAndItsChaptersUnscanned() {
        val written = """{"id":"c1","start":"$start","listed":true,"distilled":true,"state":"DONE",
            "chapters":[{"n":1,"title":"Intro","url":"$start/1","state":"NOTED"}]}"""
        val c = assertNotNull(CourseCodec.read(written))
        assertTrue(c.replays.isEmpty())
        assertFalse(c.chapters.single().scanned)
        assertFalse(c.replaysDistilled)
        val round = assertNotNull(CourseCodec.read(CourseCodec.write(c.found(1, listOf("https://www.duelingbook.com/replay?id=9")))))
        assertEquals("https://www.duelingbook.com/replay?id=9", round.replays.single().url)
        assertTrue(round.chapters.single().scanned)
    }

    @Test
    fun theReplayToolsAnswerInAStudyAloneAndAPlayersWordsAreQuoted() {
        assertTrue(CourseTools.names.containsAll(setOf("replay_read", "replay_notes", "course_replays")))
        assertTrue("replay_read" in AiTools.barredIn(AiSession.MODE_CHAT))
        assertTrue("replay_read" in CourseTools.forStep(CourseTools.STEP_REPLAY_NOTES))
        assertTrue("course_replays" in CourseTools.forStep(CourseTools.STEP_REPLAY_DISTIL))
        assertFalse("browser_open" in CourseTools.forStep(CourseTools.STEP_REPLAY_NOTES))
        assertTrue("replay_read" in Evidence.QUOTED_TOOLS)
        // The app's counts are not anyone's claim.
        assertFalse("course_replays" in Evidence.QUOTED_TOOLS)
    }

    /** A replay in the shape DuelingBook's page reads: players, plays with a play, a username, a log, cards. */
    private val replay = """
        {"action":"Duel","version":"2","format":"ar",
         "player1":{"username":"Joe","user_id":1},"player2":{"username":"Ann","user_id":2},
         "plays":[
          {"play":"RPS","username":"Joe","seconds":1},
          {"play":"Pick first","username":"Joe","seconds":2,"log":{"public_log":"Joe chose to go first"}},
          {"play":"Duel message","username":"Joe","message":"Going first, standard line","seconds":3},
          {"play":"Enter M1","username":"Joe","seconds":4,"log":{"public_log":"Entered Main Phase 1"}},
          {"play":"Normal Summon","username":"Joe","seconds":5,"card":{"name":"Aluber the Jester of Despia"},"log":{"public_log":"Normal Summoned \"Aluber the Jester of Despia\" in Attack Position to M-3"}},
          {"play":"Activate","username":"Ann","seconds":6,"log":{"public_log":"Activated \"Ash Blossom & Joyous Spring\" from hand"}},
          {"play":"Add watcher","username":"Bob","seconds":6},
          {"play":"Enter EP","username":"Joe","seconds":7},
          {"play":"End turn","username":"Joe","seconds":8},
          {"play":"Enter DP","username":"Ann","seconds":9,"log":{"public_log":"Entered Draw Phase"}},
          {"play":"Draw card","username":"Ann","seconds":9,"log":{"public_log":"Drew a card","private_log":"Drew \"Maxx C\""}},
          {"play":"Admit defeat","username":"Ann","seconds":20},
          {"play":"Begin next duel","username":"Joe","seconds":30},
          {"play":"Enter M1","username":"Ann","seconds":31},
          {"play":"Normal Summon","username":"Ann","seconds":32,"log":{"public_log":"Normal Summoned \"Snake-Eye Ash\" to M-2"}},
          {"play":"End turn","username":"Ann","seconds":33},
          {"play":"Enter DP","username":"Joe","seconds":34},
          {"play":"Activate","username":"Joe","seconds":35,"log":{"public_log":"Activated \"Branded Fusion\""}},
          {"play":"Game loss","username":"Ann","seconds":40}
         ]}
    """.trimIndent()

    @Test
    fun aReplayIsReadIntoGamesTurnsAndWords() {
        val r = assertNotNull(DbReplays.parse(replay))
        assertEquals(listOf("Joe", "Ann"), r.players)
        assertEquals(2, r.games.size)
        val g1 = r.games[0]
        assertEquals("Joe", g1.first)
        assertEquals("Ann", g1.loser)
        assertEquals(listOf(0, 1, 2), g1.turns.map { it.n })
        assertEquals(listOf("Joe", "Ann"), g1.turns.drop(1).map { it.player })
        // The interruption on Joe's turn is in Joe's turn; watchers come and go unseen.
        assertTrue(g1.turns[1].actions.any { it.player == "Ann" && "Ash Blossom & Joyous Spring" in it.cards })
        assertTrue(r.games.flatMap { it.turns }.flatMap { it.actions }.none { it.player == "Bob" })
        // What a player drew privately is read where the replay shows it.
        assertTrue(g1.turns[2].actions.any { it.words.startsWith("Drew \"Maxx") })
        val g2 = r.games[1]
        assertEquals("Ann", g2.first)
        assertEquals(listOf("Ann", "Joe"), g2.turns.map { it.player })
        val text = DbReplays.render(r, "Replay 1")
        assertTrue("## Game 1 — Joe went first" in text, text)
        assertTrue("### Turn 1 — Joe" in text, text)
        assertTrue("- Joe says: “Going first, standard line”" in text, text)
        assertTrue("Joe: Normal Summoned \"Aluber the Jester of Despia\"" in text, text)
        assertTrue("Result: Ann lost; Joe won." in text, text)
        // An error, a list of replays or not JSON at all is no replay.
        assertNull(DbReplays.parse("""{"action":"Error","message":"Replay does not exist"}"""))
        assertNull(DbReplays.parse("""{"action":"Load replays","duels":[]}"""))
        assertNull(DbReplays.parse("<html>"))
    }

    @Test
    fun aReplayWhosePlaysCarryNoWordsIsReadFromItsLog() {
        val logged = """
            {"player1":{"username":"Joe"},"player2":{"username":"Ann"},
             "plays":[{"play":"Normal Summon","username":"Joe"}],
             "logs":[[{"username":"Joe","public_log":"Entered Main Phase 1"},{"username":"Joe","public_log":"Normal Summoned \"Aluber the Jester of Despia\""}]]}
        """.trimIndent()
        val r = assertNotNull(DbReplays.parse(logged))
        val words = r.games.flatMap { it.turns }.flatMap { it.actions }.map { it.words }
        assertTrue(words.any { "Aluber" in it }, words.toString())
    }

    @Test
    fun theReplaysTakenTogetherAreCountedForTheirAuthor() {
        val r = assertNotNull(DbReplays.parse(replay))
        val other = DbReplay(listOf("Joe", "Cat"), games = listOf(
            DbReplay.Game(1, listOf(DbReplay.Turn(1, "Joe", listOf(DbReplay.Action("Normal Summon", "Joe", "Normal Summoned \"Aluber the Jester of Despia\"", listOf("Aluber the Jester of Despia"))))), first = "Joe", loser = "Joe"),
        ))
        val entries = listOf(ReplayStats.Entry(1, 1, r), ReplayStats.Entry(2, 3, other))
        assertEquals("Joe", ReplayStats.focus(entries))
        val s = ReplayStats.of(entries)
        assertEquals(3, s.games)
        assertEquals(ReplayStats.Record(3, 3, 2), s.all)
        assertEquals(ReplayStats.Record(2, 2, 1), s.first)
        assertEquals(ReplayStats.Record(1, 1, 1), s.second)
        assertEquals("Aluber the Jester of Despia" to 2, s.openers.first())
        assertTrue(s.faced.any { it.first == "Ash Blossom & Joyous Spring" })
        assertTrue(s.used.none { it.first == "Ash Blossom & Joyous Spring" })
        val words = ReplayStats.words(s, entries)
        assertTrue("Going first: 2 games: won 1, lost 1." in words, words)
        assertTrue("Replay 2 (ch. 3): Joe vs Cat, 1 game, won by Cat" in words, words)
        // Counted for someone else when asked.
        assertEquals("Ann", ReplayStats.of(entries, who = "Ann").focus)
    }
}
