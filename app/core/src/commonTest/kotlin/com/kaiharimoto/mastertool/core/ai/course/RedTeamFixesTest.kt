package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.evidence.Proof
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.exam.AuthorExam
import com.kaiharimoto.mastertool.core.ai.exam.ExamAnswer
import com.kaiharimoto.mastertool.core.ai.exam.ExamLog
import com.kaiharimoto.mastertool.core.ai.exam.ExamRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The course study's red team (2026-10): each finding held by a test. */
class RedTeamFixesTest {
    private val start = "https://metafy.gg/@joe/guides/branded"
    private val course = Course("c", start)

    private fun replay(id: Int) = "https://www.duelingbook.com/replay?id=$id"

    /** The first replay ids from 1 that the exam does, or does not, hold out. */
    private fun ids(held: Boolean, count: Int) = (1..10_000).filter { ReplayExam.held(replay(it)) == held }.take(count)

    // ---- 1. the browser's guard ------------------------------------------------------------

    @Test
    fun anAddressThatHidesItsHostHasNone() {
        // Chrome reads "\" as "/": this is evil.com, whatever follows the "@".
        assertEquals("", BrowseGuard.host("https://evil.com\\@metafy.gg/x"))
        assertNotNull(BrowseGuard.openRefusal("https://evil.com\\@metafy.gg/x", course))
        assertEquals("", BrowseGuard.host("https://joe@metafy.gg/x"))
        assertEquals("", BrowseGuard.host("https://meta fy.gg/x"))
        assertEquals("", BrowseGuard.host("https://metafy.gg\t.evil.com/x"))
        assertEquals("", BrowseGuard.host("ht tps://metafy.gg/x"))
        assertEquals("", BrowseGuard.host("https:/\\metafy.gg/x"))
        assertEquals("", BrowseGuard.host("https://evil.com\\metafy.gg/x"))
        assertNotNull(BrowseGuard.openRefusal("https://evil.com\\metafy.gg/x", course))
        // An ordinary address is as before: port aside, lowercase.
        assertEquals("metafy.gg", BrowseGuard.host("https://Metafy.gg:443/@joe?x=1#y"))
        assertNull(BrowseGuard.openRefusal("$start/chapter-1", course))
        // A replay page is known by its real host only.
        assertTrue(DbReplays.isReplay("https://www.duelingbook.com/replay?id=12"))
        assertFalse(DbReplays.isReplay("https://evil.com\\@www.duelingbook.com/replay?id=12"))
        assertFalse(DbReplays.isReplay("https://x@www.duelingbook.com/replay?id=12"))
        assertFalse(BrowseGuard.inGuide("https://evil.com\\@metafy.gg/@joe/guides/branded/1", course))
    }

    @Test
    fun wherePageLandedIsCheckedAfterTheFact() {
        assertNull(BrowseGuard.landedRefusal("$start/chapter-2", course))
        assertNull(BrowseGuard.landedRefusal("https://www.metafy.gg/@joe", course))
        val away = assertNotNull(BrowseGuard.landedRefusal("https://evil.example/phish", course))
        assertTrue("evil.example" in away && "stays on the course" in away, away)
        assertNotNull(BrowseGuard.landedRefusal("http://metafy.gg/x", course))
        assertNotNull(BrowseGuard.landedRefusal("https://evil.com\\@metafy.gg/x", course))
        // A replay page only while a replay is read.
        assertNotNull(BrowseGuard.landedRefusal(replay(5), course))
        assertNull(BrowseGuard.landedRefusal(replay(5), course, replay = true))
        assertNotNull(BrowseGuard.landedRefusal("https://www.duelingbook.com/", course, replay = true))
    }

    @Test
    fun aLoginPageIsKnown() {
        assertTrue(BrowseGuard.loginPage("https://metafy.gg/@joe/guides/x", hasPassword = true))
        listOf(
            "https://metafy.gg/login", "https://metafy.gg/auth/callback", "https://metafy.gg/sign-in?x=1", "https://metafy.gg/Sessions/new",
            "https://accounts.example/sso/start", "https://metafy.gg/register",
            "https://metafy.gg/oauth/authorize?redirect_uri=https://metafy.gg/", "https://metafy.gg/u?next=%2Flogin",
        ).forEach { assertTrue(BrowseGuard.loginPage(it, hasPassword = false), it) }
        listOf(
            "https://metafy.gg/@joe/guides/branded/chapter-3", "https://metafy.gg/@joe/guides/x?next=2", "https://metafy.gg/author/joe",
        ).forEach { assertFalse(BrowseGuard.loginPage(it, hasPassword = false), it) }
    }

    // ---- 2. the course ---------------------------------------------------------------------

    @Test
    fun notesTakenAfterTheDistilReachTheGuide() {
        val ch = Chapter(1, "A", "$start/1", state = Chapter.State.NOTED, scanned = true, saved = true, depth = CourseDepth.CURRENT, videoChecked = true)
        val done = Course(
            "c", start, chapters = listOf(ch), listed = true, consolidated = true, distilled = true,
            distilDepth = CourseDepth.CURRENT, examDrawn = true, replaysDistilled = true,
        )
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(done))
        val renoted = done.renoted()
        assertFalse(renoted.distilled)
        assertEquals(CourseDepth.CURRENT, renoted.distilDepth)
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(renoted))
        assertEquals(StudyQueue.Step.Distil, StudyQueue.next(renoted.copy(consolidated = true)))
    }

    @Test
    fun aDuelIsOneReplayAndOneReadElsewhereIsNeverHeldOut() {
        val (a, b) = ids(held = true, 2)
        val free = ids(held = false, 1).single()
        val c = course.copy(chapters = listOf(Chapter(1, "A", "$start/1"), Chapter(2, "B", "$start/2")))
            .found(1, listOf(replay(a), replay(a) + "&game=2", replay(free)))
        assertEquals(listOf(replay(a), replay(free)), c.replays.map { it.url })
        assertEquals(listOf(true, false), c.replays.map { it.exam })
        // The same duel from another chapter is not a second replay; one Ai read elsewhere is studied, not held out.
        val more = c.found(2, listOf(replay(a) + "&game=3", replay(b)), studied = setOf(b.toString()))
        assertEquals(3, more.replays.size)
        assertEquals(listOf(1, 2, 3), more.replays.map { it.n })
        assertFalse(more.replays.last().exam)
        // The exam drawn for a course begun before skips those too.
        val old = Course("o", start, replays = listOf(ReplayRef(1, replay(a), 1), ReplayRef(2, replay(b), 1)))
        assertEquals(listOf(false, true), old.drawExam(studied = setOf(a.toString())).replays.map { it.exam })
    }

    // ---- 3. one answer to "may a study read this replay" -------------------------------------

    @Test
    fun aReplayIsHeldOutByEveryCourseThatRecordsIt() {
        val held = ids(held = true, 1).single()
        val free = ids(held = false, 1).single()
        assertFalse(ReplayExam.heldFor("https://metafy.gg/x", emptyList()))
        // Recorded by no course: the exam's draw, unless Ai read it elsewhere.
        assertTrue(ReplayExam.heldFor(replay(held), emptyList()))
        assertFalse(ReplayExam.heldFor(replay(held), emptyList(), studied = setOf(held.toString())))
        assertFalse(ReplayExam.heldFor(replay(free), emptyList()))
        // Recorded: what the courses say, any of them holding it out, whatever its address's game.
        val one = Course("1", start, replays = listOf(ReplayRef(1, replay(free), 1, exam = false)))
        val two = Course("2", start, replays = listOf(ReplayRef(1, replay(free) + "&game=2", 1, exam = true)))
        assertFalse(ReplayExam.heldFor(replay(free), listOf(one)))
        assertTrue(ReplayExam.heldFor(replay(free), listOf(one, two)))
        assertFalse(ReplayExam.heldFor(replay(held), listOf(one.copy(replays = listOf(ReplayRef(1, replay(held), 1, exam = false))))))
    }

    // ---- 4. sections never longer than a part -------------------------------------------------

    @Test
    fun aTwoHourTranscriptIsManySections() {
        val lines = (0 until 720).map { k -> Transcript.Line(k * 10_000L, (1..40).joinToString(" ") { "w${k}x$it" } + ".") }
        val text = Transcript(lines).render("Locals: Branded, two hours")
        val sections = Sections.of(text)
        assertTrue(sections.size >= 9, "${sections.size} sections")
        assertEquals((1..sections.size).toList(), sections.map { it.n })
        assertTrue(sections.all { it.words <= Sections.LONGEST }, sections.map { it.words }.toString())
        // Cut at line breaks: every line whole, nothing lost.
        assertEquals(CourseText.words(text), sections.sumOf { it.words })
        assertTrue(sections.all { it.text.lines().all { l -> l.startsWith("[") || l.startsWith("#") || l.isBlank() } })
        assertTrue(sections.drop(1).all { it.text.startsWith("[") && it.text.trimEnd().endsWith(".") })
        // The parts and the coverage read the same numbers.
        val parts = StudyChunks.parts(text)
        assertEquals(sections.size, parts.last().last)
        assertEquals(1, parts.first().first)
        parts.zipWithNext().forEach { (x, y) -> assertEquals(x.last + 1, y.first) }
        val notes = sections.take(3).joinToString("\n") { "- line (§${it.n})" }
        assertEquals(3 to sections.size, Sections.coverage(text, notes))
        assertEquals(sections.drop(3).map { it.n }, Sections.uncovered(text, notes).map { it.n })
    }

    @Test
    fun aLongSectionUnderAHeadingIsCutAtABlankLineThenALineThenASpace() {
        val para = (1..500).joinToString(" ") { "p$it" }
        val long = (1..10).joinToString("\n\n") { para }
        val text = "## One\n\n$long\n\n## Two\n\nshort words here"
        val s = Sections.of(text)
        assertEquals(listOf("One", "One, part 2", "Two"), s.map { it.title })
        assertEquals(listOf(1, 2, 3), s.map { it.n })
        assertEquals(3_000, s[0].words) // six paragraphs: the last blank line before the limit
        assertEquals(2_000, s[1].words)
        // One line with no break: cut at the space after the limit's last word.
        val oneLine = (1..7_000).joinToString(" ") { "x$it" }
        val cut = Sections.of("## A\n\n$oneLine\n\n## B\n\nend")
        assertEquals(listOf(3_000, 3_000, 1_000, 1), cut.map { it.words })
        assertEquals(listOf("A", "A, part 2", "A, part 3", "B"), cut.map { it.title })
        // A short chapter is as before.
        assertEquals(listOf("Part 1"), Sections.of("a few words\nand more").map { it.title })
    }

    // ---- 5. a long video's pictures come from all of it ---------------------------------------

    @Test
    fun aLongVideosFramesAreSpreadOverItsWholeLength() {
        // Three hours, a frame every 5 s, the scene changing every 30 s throughout: far more candidates than kept.
        val frames = (0 until 2_160).map { i -> i * 5_000L to ByteArray(16) { ((i / 6) % 2 * 200).toByte() } }
        val kept = KeyFrames.pick(frames)
        assertEquals(KeyFrames.MAX, kept.size)
        assertEquals(0, kept.first())
        assertEquals(kept.sorted(), kept)
        assertEquals(kept.size, kept.distinct().size)
        val last = frames[kept.last()].first
        assertTrue(last > 2.9 * 3_600_000, "last kept at $last ms")
        // Evenly: no gap between two kept frames is more than twice the even share.
        val even = frames.last().first / (KeyFrames.MAX - 1)
        kept.zipWithNext().forEach { (a, b) -> assertTrue(frames[b].first - frames[a].first <= 2 * even) }
        // Fewer candidates than the most: every one, as before.
        val short = frames.take(60)
        assertEquals(listOf(0, 6, 12, 18, 24, 30, 36, 42, 48, 54), KeyFrames.pick(short))
    }

    // ---- 6. the answer key is the author's own cards ------------------------------------------

    @Test
    fun aCardTheAuthorTargetsIsNeverTheirPlay() {
        val raw = """
            {"player1":{"username":"Joe"},"player2":{"username":"Ann"},
             "plays":[
              {"play":"Enter M1","username":"Ann"},
              {"play":"Normal Summon","username":"Ann","card":{"name":"Snake-Eye Ash"},"log":{"public_log":"Normal Summoned \"Snake-Eye Ash\""}},
              {"play":"End turn","username":"Ann"},
              {"play":"Enter DP","username":"Joe"},
              {"play":"Target card","username":"Joe","card":{"name":"Snake-Eye Ash"},"log":{"public_log":"Targeted \"Snake-Eye Ash\""}},
              {"play":"Reveal","username":"Joe","card":{"name":"Fallen of Albaz"},"log":{"public_log":"Revealed \"Fallen of Albaz\""}},
              {"play":"Activate","username":"Joe","card":{"name":"Branded Fusion"},"log":{"public_log":"Activated \"Branded Fusion\""}},
              {"play":"Declare","username":"Joe","card":{"name":"Snake-Eye Ash"},"log":{"public_log":"Declared \"Snake-Eye Ash\""}},
              {"play":"Attack","username":"Joe","card":{"name":"Snake-Eye Ash"},"log":{"public_log":"Attacked \"Snake-Eye Ash\""}},
              {"play":"Life points","username":"Joe","card":{"name":"Snake-Eye Ash"},"log":{"public_log":"Ann's \"Snake-Eye Ash\" took 500"}},
              {"play":"Normal Summon","username":"Joe","card":{"name":"Aluber the Jester of Despia"},"log":{"public_log":"Normal Summoned \"Aluber the Jester of Despia\""}},
              {"play":"End turn","username":"Joe"}
             ]}
        """.trimIndent()
        val r = assertNotNull(DbReplays.parse(raw))
        val p = AuthorExam.points(1, r, "Joe").single()
        assertEquals(listOf("Branded Fusion", "Aluber the Jester of Despia"), p.cards)
        assertTrue(p.target.none { it.play == "Target card" || it.play == "Attack" })
        // A turn of nothing but asides asks nothing.
        val asides = raw.replace(""""play":"Activate"""", """"play":"Coin"""").replace(""""play":"Normal Summon","username":"Joe"""", """"play":"Die","username":"Joe"""")
        assertTrue(AuthorExam.points(1, assertNotNull(DbReplays.parse(asides)), "Joe").isEmpty())
    }

    // ---- 7. one unfinished sitting a course ----------------------------------------------------

    @Test
    fun twoCoursesOfOneDeckKeepTheirOwnSittings() {
        assertEquals("exams/d1.c1.sitting.json", ExamLog.sitting("d1", "c1"))
        assertEquals("exams/d1.c2.sitting.json", ExamLog.sitting("d1", "c2"))
        assertEquals("exams/d1.sitting.json", ExamLog.sitting("d1"))
        assertEquals(ExamLog.oldSitting("d1"), ExamLog.sitting("d1"))
        val a = ExamAnswer("r1-g1-t1", 1, 1, 1, listOf("A"), listOf("A"), first = true, recall = 1.0, precision = 1.0)
        val mine = ExamRun(1, "d1", course = "c1", model = "m", effort = "high", answers = listOf(a))
        val old = ExamRun(1, "d1", course = "c2", model = "m", effort = "high", answers = listOf(a))
        // The course's own file first; the deck's old one when it has none — resumed only by the course it names.
        assertEquals(mine, ExamLog.readSitting(ExamLog.writeSitting(mine), ExamLog.writeSitting(old)))
        val fallback = ExamLog.readSitting(null, ExamLog.writeSitting(old))
        assertEquals(old, fallback)
        assertEquals(emptyList(), ExamLog.resume(fallback, "d1", "m", "high", listOf(a.id), "c1"))
        assertEquals(listOf(a), ExamLog.resume(fallback, "d1", "m", "high", listOf(a.id), "c2"))
    }

    // ---- 8. the library's search ---------------------------------------------------------------

    @Test
    fun theLibraryIsSearchedByTheStartsOfWords() {
        val ash = ReplayLibrary.Entry("4512", replay(4512), players = "Joe vs Ann", note = "Ash Blossom on Fusion")
        val flash = ReplayLibrary.Entry("1088", replay(1088), players = "Kai vs Flash", chapter = 3)
        val both = listOf(ash, flash)
        assertEquals(listOf(ash), ReplayLibrary.search(both, "ash"))
        assertEquals(listOf(flash), ReplayLibrary.search(both, "fla"))
        // Digits find an id, whole or its start — never inside it.
        assertEquals(listOf(ash), ReplayLibrary.search(both, "45"))
        assertEquals(listOf(flash), ReplayLibrary.search(both, "1088"))
        assertEquals(emptyList(), ReplayLibrary.search(both, "12"))
        assertEquals(listOf(flash), ReplayLibrary.search(both, "ch 3"))
        assertEquals(listOf(ash), ReplayLibrary.search(both, "joe blos"))
        // A card played in it.
        assertEquals(listOf(flash), ReplayLibrary.search(both, "maxx") { e -> if (e.id == "1088") listOf("Maxx \"C\"") else emptyList() })
        assertEquals(both, ReplayLibrary.search(both, " "))
    }

    // ---- 9. the contents page ------------------------------------------------------------------

    @Test
    fun aChapterCardIsNamedByItsFirstLineAndContinueNeverNamesOne() {
        val description = "In this chapter we go through every line of the deck in depth, from the one-card starters to the full combo, " +
            "with the choices at each step and why."
        val links = listOf(
            "Continue" to "/@joe/guides/branded/opening",
            "Opening\n$description\n12 min" to "/@joe/guides/branded/opening",
            "Lines and choices\n$description\n40 min" to "/@joe/guides/branded/lines",
            "Comments" to "/@joe/guides/branded/comments",
            "Reviews (12)" to "/@joe/guides/branded/reviews",
            "Buy" to "/@joe/guides/branded/checkout",
            "Next lesson →" to "/@joe/guides/branded/siding",
            "Siding" to "/@joe/guides/branded/siding",
        )
        val found = Chapters.fromLinks(links, start)
        assertEquals(listOf("Opening", "Lines and choices", "Siding"), found.map { it.title })
        assertEquals(listOf(1, 2, 3), found.map { it.n })
        assertEquals("$start/lines", found[1].url)
        // A long first line is cut at a word.
        val long = Chapters.title("A".repeat(10) + " " + description + " " + description)
        assertTrue(long.length <= Chapters.TITLE_CUT + 1 && long.endsWith("…"), long)
        // Only navigation words for an address: the first is kept, as before.
        assertEquals(listOf("Continue", "Two"), Chapters.fromLinks(listOf("Continue" to "$start/1", "Two" to "$start/2"), start).map { it.title })
    }

    // ---- 10. what a failure waits for --------------------------------------------------------

    @Test
    fun aLoginTheNetworkAndTheBrowserAreNeverAChaptersFailure() {
        assertEquals(StudyRetry.Kind.BLOCK, StudyRetry.kind(StudyRetry.LOGGED_OUT))
        assertEquals(StudyRetry.Kind.BLOCK, StudyRetry.kind("${StudyRetry.LOGGED_OUT}: https://metafy.gg/login"))
        listOf(
            "The page did not load (net::ERR_INTERNET_DISCONNECTED): https://metafy.gg/@joe/guides/x",
            "The page did not load (net::ERR_NAME_NOT_RESOLVED): https://metafy.gg/login?next=/x",
            "net::ERR_CONNECTION_RESET",
        ).forEach { assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind(it), it) }
        listOf(
            "The browser closed as it started (is another window of it using this profile?).",
            "The browser did not start.",
            "The browser did not open in time.",
            "No Chrome, Edge or Chromium was found on this computer. Install one, or name yours in Settings.",
            "No Chrome or Edge",
        ).forEach { m ->
            assertEquals(StudyRetry.Kind.BLOCK, StudyRetry.kind(m), m)
            assertFalse(StudyRetry.unitGivesUp(StudyRetry.kind(m), StudyRetry.TRIES + 5, m))
        }
    }

    @Test
    fun anOfflineComputerIsWaitedOutAndNeverChargedToAChapter() {
        val tries = StudyRetry.TRIES + 3
        listOf("INTERNET_DISCONNECTED", "NETWORK_CHANGED", "NETWORK_ACCESS_DENIED", "PROXY_CONNECTION_FAILED").forEach { e ->
            val m = "The page did not load (net::ERR_$e): https://metafy.gg/@joe/guides/x"
            assertTrue(StudyRetry.offline(m), m)
            assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind(m))
            assertFalse(StudyRetry.unitGivesUp(StudyRetry.kind(m), tries, m), m)
            assertFalse(StudyRetry.givesUp(StudyRetry.kind(m), tries))
        }
        // One dead site is passed over after its tries, as before.
        listOf("NAME_NOT_RESOLVED", "CONNECTION_REFUSED").forEach { e ->
            val m = "The page did not load (net::ERR_$e): https://metafy.gg/x"
            assertFalse(StudyRetry.offline(m))
            assertTrue(StudyRetry.unitGivesUp(StudyRetry.kind(m), tries, m))
        }
        assertTrue(StudyRetry.unitGivesUp(StudyRetry.Kind.RETRY, tries))
        assertFalse(StudyRetry.unitGivesUp(StudyRetry.Kind.RETRY, StudyRetry.TRIES))
    }

    // ---- 11. a number read back is never laundered ---------------------------------------------

    @Test
    fun aNumberReadBackFromMemoryIsNoStrongerThanItsRecord() {
        val guide = "- Opens Branded Fusion 83.4% of the time going first (per Joe, ch. 3)."
        val back = listOf(Evidence.Source("memory_read", """{"scope":"guide"}""", guide))
        // Re-read with no attribution: refused, as a quoted number would be.
        val bare = Evidence.judge("Branded Fusion opens 83.4% of the time.", back, "deck", 1)
        assertTrue(assertIs<Evidence.Verdict.Refused>(bare).message.contains("(per"))
        // With it: quoted, never checked.
        val said = Evidence.judge("Branded Fusion opens 83.4% of the time (per Joe).", back, "deck", 1)
        assertEquals(Proven.Status.QUOTED, assertIs<Evidence.Verdict.Proved>(said).proven.status)
        // The guide as it was, carried, the same — its record quoted.
        val carried = listOf(Evidence.Source(Evidence.CARRIED, "", guide))
        val record = Proven(guide.removePrefix("- "), listOf(Proof("course_read", "{}")), Proven.Status.QUOTED)
        assertIs<Evidence.Verdict.Refused>(Evidence.judge("Branded Fusion opens 83.4% of the time.", carried, "deck", 1, listOf(record)))
        assertIs<Evidence.Verdict.Refused>(Evidence.judge("Branded Fusion opens 83.4% of the time.", carried, "deck", 1))
        // A check computing it here too makes it ours.
        val computed = back + Evidence.Source("hand_odds", "{}", "Branded Fusion: 83.4%")
        assertEquals(Proven.Status.CHECKED, assertIs<Evidence.Verdict.Proved>(Evidence.judge("Branded Fusion opens 83.4% of the time.", computed, "deck", 1)).proven.status)
        // A number the guide's record computed keeps its proof and its status when it is carried.
        val odds = Proof("hand_odds", """{"cards":["Branded Fusion"]}""", "83.4%", 1, "deck")
        val checked = Proven("Opens Branded Fusion 83.4% going first.", listOf(odds), Proven.Status.CHECKED)
        val kept = assertIs<Evidence.Verdict.Proved>(
            Evidence.judge("Going first, Branded Fusion opens 83.4%.", listOf(Evidence.Source(Evidence.CARRIED, "", checked.entry)), "deck", 2, listOf(checked)),
        ).proven
        assertEquals(Proven.Status.CHECKED, kept.status)
        assertEquals(listOf(odds), kept.proofs)
        val stale = assertIs<Evidence.Verdict.Proved>(
            Evidence.judge("Going first, Branded Fusion opens 83.4%.", back.map { it.copy(content = checked.entry) }, "deck", 2, listOf(checked.copy(status = Proven.Status.STALE))),
        ).proven
        assertEquals(Proven.Status.STALE, stale.status)
    }
}
