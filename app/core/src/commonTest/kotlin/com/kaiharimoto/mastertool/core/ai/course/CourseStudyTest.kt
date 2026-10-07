package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.CourseTools
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.library.FileStat
import com.kaiharimoto.mastertool.core.ai.library.LibraryCatalog
import com.kaiharimoto.mastertool.core.ai.library.LibraryFiles
import com.kaiharimoto.mastertool.core.ai.library.LibraryKind
import com.kaiharimoto.mastertool.core.ai.library.LibraryReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CourseStudyTest {
    private val start = "https://metafy.gg/@joe/guides/branded-masterclass"
    private fun course(vararg chapters: Chapter, listed: Boolean = true) =
        Course(id = "c1", start = start, deckId = "d1", deckName = "Branded", chapters = chapters.toList(), listed = listed)

    // Looked over for replays already (ReplayStudyTest has the scan): these tests are about the chapters.
    private fun ch(n: Int, state: Chapter.State = Chapter.State.PENDING, attempts: Int = 0) =
        Chapter(n, "Chapter $n", "$start/chapter-$n", state = state, attempts = attempts, scanned = true, depth = CourseDepth.CURRENT, videoChecked = true, watched = true)

    @Test
    fun theStudyGoesOnFromWhereItStopped() {
        assertEquals(StudyQueue.Step.List, StudyQueue.next(course(listed = false)))
        // Notes are taken on a chapter before the next is loaded.
        assertEquals(StudyQueue.Step.Notes(1), StudyQueue.next(course(ch(1, Chapter.State.READ), ch(2))))
        assertEquals(StudyQueue.Step.Read(2), StudyQueue.next(course(ch(1, Chapter.State.NOTED), ch(2))))
        // Every chapter noted: the playbook is put together, then the guide distilled.
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(course(ch(1, Chapter.State.NOTED), ch(2, Chapter.State.NOTED))))
        assertEquals(StudyQueue.Step.Distil, StudyQueue.next(course(ch(1, Chapter.State.NOTED), ch(2, Chapter.State.NOTED)).copy(consolidated = true)))
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(course(ch(1, Chapter.State.NOTED)).copy(distilled = true, consolidated = true, distilDepth = CourseDepth.CURRENT)))
        // Paused, blocked, capped: it waits for the person.
        assertIs<StudyQueue.Step.Waiting>(StudyQueue.next(course(ch(1)).copy(state = Course.State.PAUSED)))
        assertEquals(StudyQueue.Step.Waiting(StudyQueue.CAP_REACHED), StudyQueue.next(course(ch(1)).copy(cap = 100, spent = 100)))
    }

    @Test
    fun aChapterThatKeepsFailingIsPassedOverAndAVideoWaitsForABuildThatCanWatch() {
        var c = course(ch(1), ch(2))
        repeat(StudyQueue.ATTEMPTS) { c = StudyQueue.failed(c, 1, "would not load") }
        assertTrue(c.chapter(1)!!.gaveUp)
        assertEquals(StudyQueue.Step.Read(2), StudyQueue.next(c))
        val video = course(ch(1, Chapter.State.WAITING), ch(2, Chapter.State.NOTED))
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(video))
        assertEquals(StudyQueue.Step.Read(1), StudyQueue.next(video, canWatch = true))
        // Nothing noted at all: there is nothing to distil.
        assertEquals(StudyQueue.Step.Waiting(StudyQueue.NOTHING_READ), StudyQueue.next(course(ch(1, Chapter.State.WAITING))))
    }

    @Test
    fun pagesLoadAtAPersonsPace() {
        assertEquals(0, HumanPace.wait(0, 1_000, 0.5))
        assertEquals(HumanPace.MIN_GAP_MS, HumanPace.wait(10_000, 10_000, 0.0))
        assertEquals(0, HumanPace.wait(10_000, 10_000 + HumanPace.MIN_GAP_MS + HumanPace.JITTER_MS, 1.0))
        val day = 86_400_000L * 20_000
        var c = course()
        repeat(HumanPace.DAILY) { c = HumanPace.loaded(c, day + it) }
        assertTrue(HumanPace.spent(c, day + 5))
        // A new day begins the count again.
        assertTrue(!HumanPace.spent(c, day + 86_400_000L))
        assertEquals(1, HumanPace.loaded(c, day + 86_400_000L).loads)
    }

    @Test
    fun theBrowserStaysOnTheCourseAndPressesNothingThatBuysPostsOrTypes() {
        val c = course()
        assertNull(BrowseGuard.openRefusal("https://metafy.gg/@joe/guides/branded-masterclass/chapter-2", c))
        assertNull(BrowseGuard.openRefusal("https://www.metafy.gg/x", c))
        assertNotNull(BrowseGuard.openRefusal("http://metafy.gg/x", c))
        assertNotNull(BrowseGuard.openRefusal("https://evil.example/metafy.gg", c))
        assertNotNull(BrowseGuard.openRefusal("https://metafy.gg.evil.example/", c))
        val withCdn = c.copy(hosts = listOf("stream.mux.com"))
        assertNull(BrowseGuard.openRefusal("https://stream.mux.com/abc.m3u8", withCdn))

        fun el(tag: String, text: String, href: String = "", type: String = "", inForm: Boolean = false, hints: String = "", download: Boolean = false) =
            PageElement(1, tag, text, href, type, "", inForm, download, hints)
        // Reading the guide is pressing its links, whatever their titles say.
        assertNull(BrowseGuard.clickRefusal(el("a", "Ordering your end board", "$start/chapter-3"), c))
        assertNull(BrowseGuard.clickRefusal(el("button", "Show more"), c))
        assertNull(BrowseGuard.clickRefusal(el("a", "Next chapter", "/@joe/guides/branded-masterclass/chapter-4"), c))
        // Never anything that buys, posts, follows or ends the session.
        listOf("Buy now", "Subscribe", "Tip Joe", "Post comment", "Follow", "Log out", "Send message", "Leave a review").forEach {
            assertNotNull(BrowseGuard.clickRefusal(el("button", it), c), it)
        }
        assertNotNull(BrowseGuard.clickRefusal(el("a", "Checkout", "https://metafy.gg/checkout"), c))
        // Never a field, a form, a download, or a link off the course.
        assertNotNull(BrowseGuard.clickRefusal(el("input", "", type = "password", hints = "password"), c))
        assertNotNull(BrowseGuard.clickRefusal(el("input", "", type = "text"), c))
        assertNotNull(BrowseGuard.clickRefusal(el("button", "Continue", inForm = true), c))
        assertNotNull(BrowseGuard.clickRefusal(el("button", "Go", type = "submit"), c))
        assertNotNull(BrowseGuard.clickRefusal(el("a", "Deck list", "$start/list.ydk", download = true), c))
        assertNotNull(BrowseGuard.clickRefusal(el("a", "My other site", "https://elsewhere.example/"), c))
    }

    @Test
    fun theContentsAreReadOffTheLinksThatGoDeeperIntoTheGuide() {
        val links = listOf(
            "Home" to "/",
            "Joe" to "/@joe",
            "Welcome" to "/@joe/guides/branded-masterclass/welcome",
            "Going first" to "https://metafy.gg/@joe/guides/branded-masterclass/going-first",
            "Going first" to "/@joe/guides/branded-masterclass/going-first#top",
            "Side deck" to "side-deck",
            "Buy the guide" to "https://metafy.gg/checkout?g=1",
            "Elsewhere" to "https://youtube.com/watch?v=x",
        )
        val found = Chapters.fromLinks(links, "$start/")
        assertEquals(listOf("Welcome", "Going first", "Side deck"), found.map { it.title })
        assertEquals(listOf(1, 2, 3), found.map { it.n })
        assertEquals("https://metafy.gg/@joe/guides/branded-masterclass/side-deck", found[2].url)
        // A page that does not list chapters is left to Ai.
        assertTrue(Chapters.fromLinks(listOf("Home" to "/"), start).isEmpty())
    }

    @Test
    fun aChaptersTextIsReadInPartsToTheEnd() {
        val text = (1..400).joinToString("\n") { "Line $it of the chapter, about Branded Fusion." }
        val first = CourseText.part(text, 0, 2_000)
        assertTrue("Characters 0–" in first && "(More: read again from" in first)
        val next = Regex("""read again from (\d+)""").find(first)!!.groupValues[1].toInt()
        assertTrue(next in 1_000..2_000)
        assertTrue("(The end.)" in CourseText.part(text, text.length - 100, 2_000))
        assertEquals(5, CourseText.words("Aluber, the Jester of Despia!"))
    }

    @Test
    fun aCourseRoundTripsAndAnOlderBuildsCourseStillReads() {
        val c = course(ch(1, Chapter.State.NOTED), ch(2, Chapter.State.FAILED, 1)).copy(title = "Branded Masterclass", spent = 1234)
        assertEquals(c, CourseCodec.read(CourseCodec.write(c)))
        // The shape this build writes, read with keys a later build may add.
        val stored = """{"id":"c1","start":"$start","chapters":[{"n":1,"title":"Welcome","url":"$start/welcome","state":"NOTED","later":1}],
            |"listed":true,"state":"STUDYING","somethingNew":{"x":1}}""".trimMargin()
        val read = assertNotNull(CourseCodec.read(stored))
        assertEquals(Chapter.State.NOTED, read.chapters.single().state)
        assertEquals("courses/c1/notes/2.md", CoursePaths.notes("c1", 2))
        assertTrue(CoursePaths.idFor("$start/", 1_700_000_000_000).startsWith("branded-masterclass-"))
    }

    @Test
    fun theCourseToolsAnswerInAStudyAlone() {
        CourseTools.all.forEach { assertTrue(it in AiTools.all, it.name) }
        assertTrue(CourseTools.names.all { it in AiTools.barredIn(AiSession.MODE_CHAT) })
        assertTrue(CourseTools.names.all { it in AiTools.barredIn(AiSession.MODE_STUDY) })
        assertTrue(CourseTools.names.none { it in AiTools.barredIn(AiSession.MODE_COURSE) })
        assertTrue("edit_deck" in AiTools.barredIn(AiSession.MODE_COURSE))
        assertTrue(AiTools.barredWhy(AiSession.MODE_CHAT, "browser_open")!!.contains("course study"))
        // The browser is offered only while a page is read; the guide only when distilling.
        assertTrue("memory" !in CourseTools.forStep(CourseTools.STEP_NOTES))
        assertTrue("browser_open" !in CourseTools.forStep(CourseTools.STEP_DISTIL))
        assertTrue("memory" in CourseTools.forStep(CourseTools.STEP_DISTIL))
        // A video chapter's pictures are looked at while its notes are written.
        assertTrue("course_frames" in CourseTools.forStep(CourseTools.STEP_NOTES))
        assertTrue("course_frames" in Evidence.QUOTED_TOOLS)
        // Every tool a step offers exists.
        val names = AiTools.all.map { it.name }.toSet()
        listOf(CourseTools.STEP_LIST, CourseTools.STEP_READ, CourseTools.STEP_NOTES, CourseTools.STEP_DISTIL).forEach { step ->
            assertTrue(CourseTools.forStep(step).all { it in names }, step)
        }
    }

    @Test
    fun aNumberReadInACourseIsTheAuthorsNeverOurs() {
        val sources = listOf(
            Evidence.Source("course_read", """{"chapter":3}""", "Going first you open Branded Fusion 83.4% of the time."),
            Evidence.Source("hand_odds", """{"cards":["Aluber"]}""", "Opening odds: 41.2%"),
        )
        // Written as ours, it is refused.
        val bare = Evidence.judge("Opens Branded Fusion 83.4% of the time going first.", sources, "deck", 1)
        assertTrue(assertIs<Evidence.Verdict.Refused>(bare).message.contains("(per"))
        // Written as theirs, it is kept as quoted.
        val quoted = Evidence.judge("Opens Branded Fusion 83.4% of the time going first (per Joe, ch. 3).", sources, "deck", 1)
        val kept = assertIs<Evidence.Verdict.Proved>(quoted).proven
        assertEquals(Proven.Status.QUOTED, kept.status)
        assertTrue(Ledger.mark(kept)!!.startsWith("quoted from course_read"))
        // A number one of our checks computed is ours, whoever else said it too.
        val ours = Evidence.judge("Opens Aluber 41.2% of the time.", sources + Evidence.Source("web_fetch", "", "Aluber 41.2%"), "deck", 1)
        assertEquals(Proven.Status.CHECKED, assertIs<Evidence.Verdict.Proved>(ours).proven.status)
        // "Once per turn" is card text, never an attribution.
        assertTrue(!Evidence.attributed("Once per turn it searches; 83.4% of the time it opens."))
        assertTrue(Evidence.attributed("It opens 83.4% (per Joe)."))
    }

    @Test
    fun aStudiedCoursesNotesAreOnTheLibrarysShelf() {
        val files = object : LibraryFiles {
            override fun list(dir: String) = if (dir == "ai") listOf(
                FileStat("ai/courses/c1/notes/2.md", 10, 1),
                FileStat("ai/courses/c1/pages/2.md", 10, 1),
                FileStat("ai/courses/c1/course.json", 10, 1),
            ) else emptyList()

            override fun reader(path: String): LibraryReader? = null
        }
        val docs = LibraryCatalog.build(files, emptyMap()).docs
        assertEquals(listOf(LibraryKind.COURSE), docs.map { it.kind })
        assertEquals("Course · c1 · ch. 2", docs.single().title)
    }
}
