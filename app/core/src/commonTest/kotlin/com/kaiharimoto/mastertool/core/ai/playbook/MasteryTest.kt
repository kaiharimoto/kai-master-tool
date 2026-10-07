package com.kaiharimoto.mastertool.core.ai.playbook

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.AiTools
import com.kaiharimoto.mastertool.core.ai.CourseTools
import com.kaiharimoto.mastertool.core.ai.LearnTools
import com.kaiharimoto.mastertool.core.ai.course.CardMentions
import com.kaiharimoto.mastertool.core.ai.course.Chapter
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.CourseCodec
import com.kaiharimoto.mastertool.core.ai.course.CourseDepth
import com.kaiharimoto.mastertool.core.ai.course.CourseSearch
import com.kaiharimoto.mastertool.core.ai.course.ReplayExam
import com.kaiharimoto.mastertool.core.ai.course.ReplayRef
import com.kaiharimoto.mastertool.core.ai.course.Sections
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mastery (1.1.42): the playbook, close reading of a course, the exam held out, and the table's strength. */
class MasteryTest {
    private val aluber = PlaybookEdits.Draft(
        kind = Play.Kind.LINE, title = "Aluber into Mirrorjade",
        needs = listOf("Aluber the Jester of Despia"),
        steps = listOf(Step("Aluber the Jester of Despia", "Normal Summon; search Branded Fusion"), Step("Branded Fusion", "fuse Albaz with a Despia from Deck", "Mirrorjade")),
        endBoard = "Mirrorjade the Iceblade Dragon", through = listOf("Ash on Aluber: still has Branded Fusion if drawn"),
        sources = listOf(Source("ch. 4 §2")), going = Play.Going.FIRST,
    )

    @Test
    fun anEntryIsKeptOnlyWhenItSaysEnoughToPlayFrom() {
        val book = Playbook("d1")
        val out = PlaybookEdits.add(book, listOf(
            aluber,
            PlaybookEdits.Draft(kind = Play.Kind.LINE, title = "Half a line", steps = listOf(Step("A", "summon")), sources = listOf(Source("ch. 1"))),
            PlaybookEdits.Draft(kind = Play.Kind.DECISION, title = "Ash target", situation = "They Normal Summon Aluber", choice = "Ash it", sources = listOf(Source("ch. 2"))),
            PlaybookEdits.Draft(kind = Play.Kind.PRINCIPLE, title = "Unsourced", body = "Go first."),
        ), now = 1)
        assertEquals(listOf("line-1"), out.changed)
        assertTrue(out.message.contains("at least two"), out.message)
        assertTrue(out.message.contains("situation, its choice and why"), out.message)
        assertTrue(out.message.contains("where it was learned"), out.message)
        // The same entry again is refused with the one it repeats; an update adds the source and loses none.
        val again = PlaybookEdits.add(out.book, listOf(aluber.copy(title = "aluber into  MIRRORJADE!")), now = 2)
        assertTrue(again.changed.isEmpty() && again.message.contains("line-1"), again.message)
        val upd = PlaybookEdits.update(out.book, "line-1", PlaybookEdits.Draft(sources = listOf(Source("replay 12, game 1, turn 1")), confidence = Play.Confidence.SHOWN), now = 3)
        val line = assertNotNull(upd.book.entry("line-1"))
        assertEquals(listOf("ch. 4 §2", "replay 12, game 1, turn 1"), line.sources.map { it.ref })
        assertEquals(Play.Confidence.SHOWN, line.confidence)
        assertEquals(2, line.steps.size)
        // A weaker confidence never lowers a stronger one.
        assertEquals(Play.Confidence.SHOWN, PlaybookEdits.update(upd.book, "line-1", PlaybookEdits.Draft(confidence = Play.Confidence.INFERRED), now = 4).book.entry("line-1")!!.confidence)
    }

    @Test
    fun twoEntriesForOneThingBecomeOneWithEverySource() {
        var b = PlaybookEdits.add(Playbook("d1"), listOf(aluber), now = 1).book
        b = PlaybookEdits.add(b, listOf(aluber.copy(title = "Aluber line (replays)", sources = listOf(Source("replay 3")), body = "Seen twice.")), now = 2).book
        val merged = PlaybookEdits.merge(b, "line-1", listOf("line-2"), now = 3)
        assertEquals(1, merged.book.size)
        assertEquals(setOf("ch. 4 §2", "replay 3"), merged.book.entry("line-1")!!.sources.map { it.ref }.toSet())
        assertTrue("Seen twice." in merged.book.entry("line-1")!!.body)
        // Ids are never reused: a source naming line-2 can never come to mean another entry.
        assertEquals(3, merged.book.next)
    }

    @Test
    fun atTheTableTheLineTheHandCanStartComesFirst() {
        var b = PlaybookEdits.add(Playbook("d1"), listOf(
            aluber,
            PlaybookEdits.Draft(kind = Play.Kind.LINE, title = "Guardian Chimera line", needs = listOf("Branded Fusion", "Guardian Chimera"),
                steps = listOf(Step("Branded Fusion", "fuse"), Step("Guardian Chimera", "draw")), endBoard = "Chimera", sources = listOf(Source("ch. 5"))),
            PlaybookEdits.Draft(kind = Play.Kind.MATCHUP, title = "Into Snake-Eye", against = "Snake-Eye", body = "Hold Ash for Ash.", cards = listOf("Snake-Eye Ash"), sources = listOf(Source("ch. 9"))),
            PlaybookEdits.Draft(kind = Play.Kind.PRINCIPLE, title = "Going second", body = "Break first.", going = Play.Going.SECOND, sources = listOf(Source("ch. 1"))),
        ), now = 1).book
        val (text, left) = PlaybookSearch.relevant(b, PlaybookSearch.Position(hand = listOf("Aluber the Jester of Despia"), theirs = listOf("Snake-Eye Ash"), first = true), 10_000)
        assertTrue(text.startsWith("[line-1]"), text)
        assertTrue("[matchup-3]" in text, text)
        // Going first, the going-second principle is not this position's.
        assertFalse("[principle-4]" in text, text)
        assertTrue(left >= 1)
        // Searches by words and by cards.
        assertEquals("matchup-3", PlaybookSearch.search(b, "snake eye").first().play.id)
        assertEquals(listOf("line-1", "line-2"), PlaybookSearch.search(b, "", cards = listOf("Branded Fusion")).map { it.play.id }.sorted())
        // A playbook that cannot be read is never taken for an empty one.
        assertNull(PlaybookCodec.read("{not json", "d1"))
        assertEquals(0, PlaybookCodec.read(null, "d1")!!.size)
        assertEquals(b, PlaybookCodec.read(PlaybookCodec.write(b), "d1"))
    }

    @Test
    fun aChapterIsReadInNumberedSectionsAndTheNotesMustCiteEach() {
        val chapter = "Intro words.\n\n# Going first\n" + "word ".repeat(60) + "\n\n## Choke points\n" + "word ".repeat(60) + "\n\n## Tiny\nok"
        val parts = Sections.of(chapter)
        assertEquals(listOf("Opening", "Going first", "Choke points", "Tiny"), parts.map { it.title })
        assertTrue(Sections.numbered(chapter).contains("## §2 Going first"))
        val notes = "- Open with Aluber (ch. 4 §2)."
        assertEquals(listOf(3), Sections.uncovered(chapter, notes).map { it.n })
        assertEquals(1 to 2, Sections.coverage(chapter, notes))
        // Without headings, the chapter is cut into parts at blank lines.
        val plain = (1..12).joinToString("\n\n") { "Paragraph $it " + "word ".repeat(80) }
        assertTrue(Sections.of(plain).size >= 2)
    }

    @Test
    fun theCardsAChapterNamesAreFoundWholeAndLongestFirst() {
        val names = listOf("Branded Fusion", "Fusion", "Aluber the Jester of Despia", "Ash Blossom & Joyous Spring", "Pot")
        val text = "Open with Aluber the Jester of Despia, then Branded Fusion. Watch for Ash Blossom & Joyous Spring; Pot of greed is not a card here."
        assertEquals(listOf("Aluber the Jester of Despia", "Branded Fusion", "Ash Blossom & Joyous Spring"), CardMentions.find(text, names))
    }

    @Test
    fun theCourseIsSearchedAsOneWithQuotedWordsTogether() {
        val docs = listOf(
            CourseSearch.Doc("ch. 4", "Going first", "Normal Summon Aluber.\nThen Branded Fusion for Mirrorjade.\nEnd."),
            CourseSearch.Doc("replay 3 notes", "Joe vs Ann", "- Joe used Branded Fusion going second (replay 3 §4)."),
        )
        val hits = CourseSearch.search(docs, "\"branded fusion\" mirrorjade")
        assertEquals("ch. 4", hits.first().ref)
        assertEquals(2, hits.first().line)
        assertEquals(2, CourseSearch.search(docs, "\"Branded Fusion\"").size)
    }

    @Test
    fun aFifthOfTheReplaysIsHeldOutAlwaysTheSameOnes() {
        val urls = (1..400).map { "https://www.duelingbook.com/replay?id=1-$it" }
        val held = urls.count(ReplayExam::held)
        assertTrue(held in 55..105, "held $held of 400")
        assertEquals(urls.map(ReplayExam::held), urls.map(ReplayExam::held))
        // Drawing the exam on a course begun before it never holds out a replay already studied.
        val course = Course("c", "https://metafy.gg/x", replays = urls.take(40).mapIndexed { i, u ->
            ReplayRef(i + 1, u, 1, state = if (i < 10) Chapter.State.NOTED else Chapter.State.PENDING)
        })
        val drawn = course.drawExam()
        assertTrue(drawn.examDrawn)
        assertTrue(drawn.replays.take(10).none { it.exam })
        assertEquals(drawn.replays.drop(10).map { ReplayExam.held(it.url) }, drawn.replays.drop(10).map { it.exam })
        assertEquals(drawn, drawn.drawExam())
    }

    @Test
    fun aShallowStudyIsTakenAgainAtMasteryThenPutTogetherThenDistilled() {
        val start = "https://metafy.gg/@joe/guides/x"
        fun ch(n: Int, depth: Int) = Chapter(n, "Ch $n", "$start/$n", state = Chapter.State.NOTED, scanned = true, depth = depth)
        val old = Course("c", start, listed = true, distilled = true, chapters = listOf(ch(1, CourseDepth.FIRST), ch(2, CourseDepth.FIRST)))
        assertEquals(StudyQueue.Step.Notes(1), StudyQueue.next(old))
        assertTrue(StudyQueue.more(old.copy(state = Course.State.DONE)))
        val noted = old.copy(chapters = listOf(ch(1, CourseDepth.CURRENT), ch(2, CourseDepth.CURRENT)))
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(noted))
        assertEquals(StudyQueue.Step.Distil, StudyQueue.next(noted.copy(consolidated = true)))
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(noted.copy(consolidated = true, distilDepth = CourseDepth.CURRENT)))
        // A held-out replay is read but never noted.
        val withExam = noted.copy(consolidated = true, distilDepth = CourseDepth.CURRENT, replays = listOf(ReplayRef(1, "https://www.duelingbook.com/replay?id=9", 1, state = Chapter.State.READ, exam = true)))
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(withExam))
        // An older build's course reads with nothing of this: depth 0, not consolidated, the exam not drawn.
        val older = assertNotNull(CourseCodec.read("""{"id":"c","start":"$start","listed":true,"chapters":[{"n":1,"title":"A","url":"$start/1","state":"NOTED"}]}"""))
        assertEquals(0, older.chapters.single().depth)
        assertFalse(older.consolidated || older.examDrawn)
    }

    @Test
    fun theLearningToolsAreOfferedWhereTheyAreNeededAndNoWhereTheyWrite() {
        assertTrue(LearnTools.reading.all { it in AiTools.DUEL })
        assertFalse("playbook_write" in AiTools.DUEL)
        assertTrue(LearnTools.names.none { it in AiTools.barredIn(AiSession.MODE_CHAT) })
        assertTrue(LearnTools.names.none { it in AiTools.barredIn(AiSession.MODE_STUDY) })
        assertTrue("playbook_write" in CourseTools.forStep(CourseTools.STEP_NOTES))
        assertTrue("notes_coverage" in CourseTools.forStep(CourseTools.STEP_REPLAY_NOTES))
        assertTrue(CourseTools.forStep(CourseTools.STEP_CONSOLIDATE).containsAll(LearnTools.names))
    }

    @Test
    fun theTablePlaysStrongByDefaultAtWhatTheProviderOffers() {
        assertEquals(DuelPrefs.STRONG, DuelPrefs().aiStrength)
        val all = listOf("low", "medium", "high", "xhigh", "max")
        assertEquals("high", DuelPrefs.effort(DuelPrefs.STRONG, all, "medium"))
        assertEquals("low", DuelPrefs.effort(DuelPrefs.FAST, all, "medium"))
        assertEquals("xhigh", DuelPrefs.effort(DuelPrefs.MAX, all, "medium"))
        // A provider without xhigh gets the nearest below; one without efforts, its own default.
        assertEquals("high", DuelPrefs.effort(DuelPrefs.MAX, listOf("low", "medium", "high"), "medium"))
        assertEquals("medium", DuelPrefs.effort(DuelPrefs.STRONG, emptyList(), "medium"))
        assertEquals(48, DuelPrefs.steps(DuelPrefs.STRONG, 24))
    }
}
