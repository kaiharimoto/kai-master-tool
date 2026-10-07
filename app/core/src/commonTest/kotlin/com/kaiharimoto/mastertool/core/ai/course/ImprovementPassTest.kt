package com.kaiharimoto.mastertool.core.ai.course

import com.kaiharimoto.mastertool.core.ai.CourseTools
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.exam.AuthorExam
import com.kaiharimoto.mastertool.core.ai.exam.ExamAnswer
import com.kaiharimoto.mastertool.core.ai.exam.ExamAuthor
import com.kaiharimoto.mastertool.core.ai.exam.ExamBrief
import com.kaiharimoto.mastertool.core.ai.exam.ExamLog
import com.kaiharimoto.mastertool.core.ai.exam.ExamRun
import com.kaiharimoto.mastertool.core.ai.playbook.Play
import com.kaiharimoto.mastertool.core.ai.playbook.Playbook
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookEdits
import com.kaiharimoto.mastertool.core.ai.playbook.PlaybookSearch
import com.kaiharimoto.mastertool.core.ai.playbook.Source
import com.kaiharimoto.mastertool.core.ai.playbook.Step
import com.kaiharimoto.mastertool.core.ai.skills.CourseSkills
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The improvement pass on the course study and mastery (1.1.47): what three reviewers found, held. */
class ImprovementPassTest {
    @Test
    fun everyStepIsGivenItsSkillsWords() {
        // The steps' tools never include skill_view: the skill must be in the system words, or the model never sees it.
        listOf(CourseTools.STEP_NOTES, CourseTools.STEP_REPLAY_NOTES, CourseTools.STEP_CONSOLIDATE, CourseTools.STEP_DISTIL).forEach { kind ->
            assertFalse("skill_view" in CourseTools.forStep(kind), kind)
            val system = CourseBrief.withSkill("You are Ai.", kind)
            assertTrue(system.startsWith("You are Ai.") && "## The skill for this step" in system, kind)
        }
        assertTrue(CourseSkills.STUDY.trim() in CourseBrief.withSkill("x", CourseTools.STEP_NOTES))
        assertTrue(CourseSkills.REPLAY.trim() in CourseBrief.withSkill("x", CourseTools.STEP_REPLAY_NOTES))
        assertEquals("x", CourseBrief.withSkill("x", CourseTools.STEP_LIST))
        // A tool a note step's hints name is one it is offered.
        assertTrue("course_open" in CourseTools.forStep(CourseTools.STEP_NOTES))
    }

    @Test
    fun aCitationOfAnotherChapterNeverCoversThisOne() {
        val replay = (1..3).joinToString("\n\n") { "## Turn $it\n" + "word ".repeat(60) }
        val notes = "- Aluber first (ch. 7 §1). The chapter explains it.\n- Held Ash (replay 4 §2).\n- Went to Mirrorjade (§3)."
        assertEquals(setOf(2, 3), Sections.cited(notes, "replay 4"))
        assertEquals(listOf(1), Sections.uncovered(replay, notes, "replay 4").map { it.n })
        // Another replay's sections are not this one's either; and with no unit named, every § counts as before.
        assertEquals(setOf(3), Sections.cited(notes, "replay 5"))
        assertEquals(setOf(1, 2, 3), Sections.cited(notes))
        assertEquals(setOf(1, 3), Sections.cited(notes, "ch. 7"))
    }

    @Test
    fun thePlaybookIsReadWholeAPageAtATimeAndBySource() {
        val drafts = (1..130).map { k ->
            PlaybookEdits.Draft(kind = Play.Kind.DECISION, title = "Decision $k", situation = "s$k", choice = "c$k", why = "w$k", sources = listOf(Source(if (k % 2 == 0) "ch. 4 §$k" else "ch. 41 §1")))
        }
        val b = PlaybookEdits.add(Playbook("d1"), drafts, now = 1).book
        val first = PlaybookSearch.page(b, "", Play.Kind.DECISION, limit = 100)
        assertEquals(130, first.total)
        assertEquals(100, first.hits.size)
        val rest = PlaybookSearch.page(b, "", Play.Kind.DECISION, from = 100, limit = 100)
        assertEquals(30, rest.hits.size)
        assertEquals(130, (first.hits + rest.hits).map { it.play.id }.toSet().size)
        // "ch. 4" is chapter 4's sections, never chapter 41's.
        assertEquals(65, PlaybookSearch.page(b, "", source = "ch. 4", limit = 100).total)
        assertTrue(PlaybookSearch.learnedFrom("ch. 4 §2", "ch 4"))
        assertFalse(PlaybookSearch.learnedFrom("ch. 41 §1", "ch 4"))
    }

    @Test
    fun aMergeLosesNothingAndNeverFoldsTwoKinds() {
        val chapter = PlaybookEdits.Draft(kind = Play.Kind.DECISION, title = "Ash on Aluber", situation = "They summon Aluber", choice = "Ash it", why = "It searches the engine", sources = listOf(Source("ch. 3 §2")))
        val replay = PlaybookEdits.Draft(kind = Play.Kind.DECISION, title = "Ash the Aluber", situation = "Aluber summoned with two cards in hand", choice = "Ash it", why = "The author: \"it's their only starter here\"", sources = listOf(Source("replay 4 §5")))
        val line = PlaybookEdits.Draft(kind = Play.Kind.LINE, title = "Aluber line", needs = listOf("Aluber"), steps = listOf(Step("Aluber", "summon"), Step("Branded Fusion", "fuse")), endBoard = "Mirrorjade", sources = listOf(Source("ch. 1")))
        val b = PlaybookEdits.add(Playbook("d1"), listOf(chapter, replay, line), now = 1).book
        val merged = PlaybookEdits.merge(b, "decision-1", listOf("decision-2"), now = 2)
        val kept = assertNotNull(merged.book.entry("decision-1"))
        assertTrue("their only starter here" in kept.body, kept.body)
        assertTrue("two cards in hand" in kept.body, kept.body)
        assertEquals(setOf("ch. 3 §2", "replay 4 §5"), kept.sources.map { it.ref }.toSet())
        val refused = PlaybookEdits.merge(b, "decision-1", listOf("line-3"), now = 2)
        assertTrue(refused.changed.isEmpty() && "only entries of one kind" in refused.message, refused.message)
        // A field too long is said, never cut in silence.
        val long = PlaybookEdits.add(Playbook("d1"), listOf(chapter.copy(why = "w".repeat(PlaybookEdits.FIELD_CAP + 10))), now = 1)
        assertTrue("cut: why" in long.message, long.message)
    }

    @Test
    fun aLineIsStartableFromTheHandAloneAtTheTable() {
        val line = PlaybookEdits.Draft(kind = Play.Kind.LINE, title = "Aluber line", needs = listOf("Aluber"), steps = listOf(Step("Aluber", "summon"), Step("Branded Fusion", "fuse")), endBoard = "Mirrorjade", sources = listOf(Source("ch. 1")))
        val principle = PlaybookEdits.Draft(kind = Play.Kind.PRINCIPLE, title = "Count the outs", body = "Always.", sources = listOf(Source("ch. 2")))
        val b = PlaybookEdits.add(Playbook("d1"), listOf(principle, line), now = 1).book
        // Aluber in the GY is not a line the hand can start: the principle comes before it.
        val (inGy, _) = PlaybookSearch.relevant(b, PlaybookSearch.Position(hand = listOf("Ash Blossom"), mine = listOf("Aluber")), 10_000)
        assertTrue(inGy.startsWith("[principle-1]"), inGy)
        val (inHand, _) = PlaybookSearch.relevant(b, PlaybookSearch.Position(hand = listOf("Aluber")), 10_000)
        assertTrue(inHand.startsWith("[line-2]"), inHand)
    }

    @Test
    fun theStudysOwnNotesAndThePlaybookAreTheAuthorsWord() {
        listOf("course_open", "course_search", "playbook_read").forEach { assertTrue(it in Evidence.QUOTED_TOOLS, it) }
        // The exam reads what Ai learned, never the course itself.
        assertFalse("course_search" in ExamBrief.tools || "course_open" in ExamBrief.tools)
    }

    @Test
    fun aLimitIsWaitedOutButAPromptTooLongIsNot() {
        assertEquals(StudyRetry.Kind.RETRY, StudyRetry.kind("prompt is too long: 215003 tokens > 200000 maximum"))
        assertEquals(StudyRetry.Kind.RETRY, StudyRetry.kind("Notes past 500 characters were refused"))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("Error 529"))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("HTTP 503 from the provider"))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("Something", retryable = true))
        assertEquals(StudyRetry.Kind.WAIT, StudyRetry.kind("The browser did not answer (Runtime.evaluate) in 30 seconds."))
        // A page that always fails is passed over after a few tries, whatever it said; a key problem never is.
        assertFalse(StudyRetry.unitGivesUp(StudyRetry.Kind.WAIT, StudyRetry.TRIES))
        assertTrue(StudyRetry.unitGivesUp(StudyRetry.Kind.WAIT, StudyRetry.TRIES + 1))
        assertFalse(StudyRetry.unitGivesUp(StudyRetry.Kind.BLOCK, 99))
    }

    private val replay = """
        {"player1":{"username":"Joe"},"player2":{"username":"Ann"},
         "plays":[
          {"play":"Enter M1","username":"Ann"},
          {"play":"Normal Summon","username":"Ann","log":{"public_log":"Normal Summoned \"Snake-Eye Ash\""}},
          {"play":"End turn","username":"Ann"},
          {"play":"Enter DP","username":"Joe"},
          {"play":"Draw card","username":"Joe","log":{"public_log":"Joe drew a card","private_log":"Joe drew \"Branded Fusion\""}},
          {"play":"Activate","username":"Joe","log":{"public_log":"Activated \"Maxx \"C\"\""}},
          {"play":"Activate","username":"Joe","log":{"public_log":"Activated \"Branded Fusion\" targeting \"Snake-Eye Ash\""}},
          {"play":"End turn","username":"Joe"}
         ]}
    """.trimIndent()

    @Test
    fun theAnswerKeyIsTheAuthorsOwnCards() {
        val r = assertNotNull(DbReplays.parse(replay))
        val p = AuthorExam.points(2, r, "Joe").single()
        // The draw is not the first play, a name may hold quotes, and the card targeted is not the author's play.
        assertEquals(listOf("Maxx \"C\"", "Branded Fusion"), p.cards)
        assertTrue("Joe drew \"Branded Fusion\"" in p.context, p.context)
        assertTrue(AuthorExam.grade(p.cards, listOf("Maxx \"C\"")).first)
        // Who and which game stay at the top of a long duel.
        assertTrue(p.context.startsWith("You are Joe, playing against Ann. Game 1"), p.context.take(80))
    }

    @Test
    fun aSittingIsOneCourseOneModelAndOneStateOfKnowledge() {
        val a = ExamAnswer("r1-g1-t1", 1, 1, 1, listOf("A"), listOf("A"), first = true, recall = 1.0, precision = 1.0)
        val sitting = ExamRun(1, "d1", course = "c1", model = "m", effort = "high", playbook = 10, guide = 500, answers = listOf(a))
        assertEquals(listOf(a), ExamLog.resume(sitting, "d1", "m", "high", listOf("r1-g1-t1"), "c1", 10, 500))
        assertEquals(emptyList(), ExamLog.resume(sitting, "d1", "m", "high", listOf("r1-g1-t1"), "c2", 10, 500))
        assertEquals(emptyList(), ExamLog.resume(sitting, "d1", "m", "high", listOf("r1-g1-t1"), "c1", 11, 500))
        // Two sittings compare on the positions both asked.
        val now = ExamRun(2, "d1", answers = listOf(a, a.copy(id = "r9-g1-t1", first = false)))
        val then = ExamRun(1, "d1", answers = listOf(a.copy(first = false), a.copy(id = "r2-g1-t1")))
        assertTrue("On the 1 positions both asked: 100% now, 0% last time" in ExamLog.compare(now, then), ExamLog.compare(now, then))
        // The author is the one ahead; a tie is nobody.
        val joe = assertNotNull(DbReplays.parse(replay))
        assertEquals(null, ExamAuthor.lead(listOf(ReplayStats.Entry(1, 1, joe))))
        assertEquals("Joe", ExamAuthor.lead(listOf(ReplayStats.Entry(1, 1, joe), ReplayStats.Entry(2, 1, joe.copy(players = listOf("Joe", "Bob"))))))
    }
}
