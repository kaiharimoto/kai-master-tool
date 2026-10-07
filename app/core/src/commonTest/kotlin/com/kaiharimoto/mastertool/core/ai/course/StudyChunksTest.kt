package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.exam.ExamAnswer
import com.kaiharimoto.mastertool.core.ai.exam.ExamLog
import com.kaiharimoto.mastertool.core.ai.exam.ExamRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A study in parts (1.1.46): each part saved as it ends, a stopped one taken again from its start, a limit waited out. */
class StudyChunksTest {
    private val start = "https://metafy.gg/@joe/guides/x"

    private fun section(n: Int, words: Int) = "## Section $n\n\n" + (1..words).joinToString(" ") { "word$it" }

    @Test
    fun aLongChapterIsNotedInPartsOfWholeSections() {
        val text = (1..7).joinToString("\n\n") { section(it, 1_200) }
        val parts = StudyChunks.parts(text)
        // About three thousand words a part, never a section split: 1–3, 4–6, 7.
        assertEquals(listOf(StudyChunks.Part(1, 3, 7), StudyChunks.Part(4, 6, 7), StudyChunks.Part(7, 7, 7)), parts)
        assertEquals(StudyChunks.Part(4, 6, 7), StudyChunks.next(text, through = 3))
        assertNull(StudyChunks.next(text, through = 7))
        // A part reads only its own sections, numbered as the whole numbers them.
        val only = Sections.only(text, 4, 6)
        assertTrue(only.startsWith("## §4 Section 4"), only.take(40))
        assertTrue("§3" !in only && "§7" !in only)
        // A short chapter is one part.
        assertEquals(listOf(StudyChunks.Part(1, 2, 2)), StudyChunks.parts(section(1, 300) + "\n\n" + section(2, 300)))
    }

    @Test
    fun aStoppedPartIsSetBackToWhereItBegan() {
        val before = "- one (ch. 1 §1)\n- two (ch. 1 §2)\n"
        val half = before + "\n- three, half wri"
        assertEquals(before, StudyChunks.setBack(half, before.length))
        assertEquals("", StudyChunks.setBack(half, 0))
        // A mark past the end sets nothing back.
        assertEquals(before, StudyChunks.setBack(before, before.length + 10))
    }

    @Test
    fun theQueueSaysWhichPartAndPartsOfThePlaybookAndGuide() {
        val c = Chapter(1, "Combos", "$start/1", state = Chapter.State.READ, scanned = true, videoChecked = true, watched = true, notedThrough = 3, sections = 7)
        val course = Course("c", start, listed = true, chapters = listOf(c))
        assertEquals("Taking notes on chapter 1 of 1, from §4 of 7", StudyQueue.line(course))
        val noted = (1..13).map { Chapter(it, "Ch $it", "$start/$it", state = Chapter.State.NOTED, scanned = true, depth = CourseDepth.CURRENT, videoChecked = true, watched = true) }
        val replays = (1..14).map { ReplayRef(it, "https://www.duelingbook.com/replay?id=$it", 1, state = Chapter.State.NOTED, exam = it == 2, depth = CourseDepth.CURRENT) }
        val all = Course("c", start, listed = true, chapters = noted, replays = replays, examDrawn = true)
        // The playbook a kind at a time, then whole; the course stays on Consolidate until every part is done.
        assertEquals("line", StudyChunks.nextConsolidate(all))
        assertEquals(StudyQueue.Step.Consolidate, StudyQueue.next(all.copy(consolidateDone = listOf("line", "decision"))))
        assertEquals("Putting the playbook together: its cards", StudyQueue.line(all.copy(consolidateDone = listOf("line", "decision"))))
        // The guide: six chapters at a time, twelve replays (the exam's never: 1, 3–13 is twelve), then whole.
        assertEquals(listOf("ch:1-6", "ch:7-12", "ch:13-13", "replays:1-13", "replays:14-14", "whole"), StudyChunks.distilParts(all))
        val distilling = all.copy(consolidated = true, distilDone = listOf("ch:1-6"))
        assertEquals(StudyQueue.Step.Distil, StudyQueue.next(distilling))
        assertEquals("Writing what it learned into the guide: chapters 7–12", StudyQueue.line(distilling))
        // New notes set both back to their first part.
        val again = distilling.renoted()
        assertEquals(emptyList(), again.consolidateDone)
        assertEquals(emptyList(), again.distilDone)
        assertTrue(!again.consolidated)
    }

    @Test
    fun aLimitIsWaitedOutAndAKeyProblemWaitsForThePerson() {
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("Anthropic is rate-limiting this key; try again in a moment."))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("Claude AI usage limit reached|1760000000"))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("You've hit your limit · resets 5pm"))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("Overloaded (529)"))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("The model stopped without an answer."))
        assertEquals(StudyRetry.Kind.BLOCK, StudyRetry.kind("Anthropic did not accept the key: 401", auth = true))
        assertEquals(StudyRetry.Kind.BLOCK, StudyRetry.kind("Your credit balance is too low to access the Anthropic API."))
        assertEquals(StudyRetry.Kind.BLOCK, StudyRetry.kind("Chessy has no connection set up."))
        assertEquals(StudyRetry.Kind.RETRY, StudyRetry.kind("Something odd"))
        // A limit is waited out for as long as it takes; something unknown a few times; a key problem never.
        assertTrue(!StudyRetry.givesUp(StudyRetry.Kind.WAIT, 1_000))
        assertTrue(!StudyRetry.givesUp(StudyRetry.Kind.RETRY, StudyRetry.TRIES))
        assertTrue(StudyRetry.givesUp(StudyRetry.Kind.RETRY, StudyRetry.TRIES + 1))
        assertTrue(StudyRetry.givesUp(StudyRetry.Kind.BLOCK, 1))
        assertEquals(60_000L, StudyRetry.waitMs(1))
        assertEquals(120_000L, StudyRetry.waitMs(2))
        assertEquals(StudyRetry.LONGEST_MS, StudyRetry.waitMs(30))
    }

    @Test
    fun aCourseFromBeforeReadsWithNothingInParts() {
        val older = assertNotNull(
            CourseCodec.read(
                """{"id":"c","start":"$start","listed":true,"cap":5000000,"spent":6000000,"state":"BLOCKED","note":"${StudyQueue.CAP_REACHED}",
                   "chapters":[{"n":1,"title":"A","url":"$start/1","state":"READ"}],
                   "replays":[{"n":1,"url":"https://www.duelingbook.com/replay?id=1","chapter":1,"state":"READ"}]}""",
            ),
        )
        assertEquals(0, older.chapters.single().notedThrough)
        assertEquals(-1, older.chapters.single().notesMark)
        assertEquals(-1, older.replays.single().notesMark)
        assertEquals(0L, older.retryAt)
        assertEquals(emptyList(), older.distilDone)
        // Its cap is read and ignored: once going again it is not held by it.
        assertEquals(StudyQueue.Step.Notes(1), StudyQueue.next(older.copy(state = Course.State.STUDYING, note = "")))
        val back = assertNotNull(CourseCodec.read(CourseCodec.write(older.copy(retryAt = 7, tries = 2, partBegun = "distil:whole"))))
        assertEquals(7L, back.retryAt)
        assertEquals("distil:whole", back.partBegun)
    }

    @Test
    fun anExamSittingGoesOnFromTheNextPosition() {
        val a = ExamAnswer("r1-g1-t1", 1, 1, 1, listOf("A"), listOf("A"), first = true, recall = 1.0, precision = 1.0)
        val b = ExamAnswer("r1-g1-t3", 1, 1, 3, listOf("B"), missed = true)
        val sitting = ExamRun(1, "d1", model = "m", effort = "high", answers = listOf(a, b))
        val back = ExamLog.readSitting(ExamLog.writeSitting(sitting))
        assertEquals(sitting, back)
        assertEquals(listOf(a, b), ExamLog.resume(back, "d1", "m", "high", listOf("r1-g1-t1", "r1-g1-t3", "r2-g1-t2")))
        // Another model, another thought, another deck: it begins again.
        assertEquals(emptyList(), ExamLog.resume(back, "d1", "other", "high", listOf("r1-g1-t1")))
        assertEquals(emptyList(), ExamLog.resume(back, "d1", "m", "xhigh", listOf("r1-g1-t1")))
        assertEquals(emptyList(), ExamLog.resume(back, "d2", "m", "high", listOf("r1-g1-t1")))
        // A position no longer asked is not kept.
        assertEquals(listOf(a), ExamLog.resume(back, "d1", "m", "high", listOf("r1-g1-t1")))
        assertEquals("exams/d1.sitting.json", ExamLog.sitting("d1"))
    }
}
