package com.kaiharimoto.mastertool.core.ai.exam

import com.kaiharimoto.mastertool.core.ai.course.Chapter
import com.kaiharimoto.mastertool.core.ai.course.Course
import com.kaiharimoto.mastertool.core.ai.course.CourseCodec
import com.kaiharimoto.mastertool.core.ai.course.CourseDepth
import com.kaiharimoto.mastertool.core.ai.course.DbReplays
import com.kaiharimoto.mastertool.core.ai.course.StudyQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The exam (1.1.44): the author's turns on the held-out replays, asked as the author saw them, graded by the app. */
class AuthorExamTest {
    private val replay = """
        {"player1":{"username":"Joe"},"player2":{"username":"Ann"},
         "plays":[
          {"play":"Enter M1","username":"Ann"},
          {"play":"Set","username":"Ann","log":{"public_log":"Set a card in S-2","private_log":"Set \"Infinite Impermanence\" in S-2"}},
          {"play":"End turn","username":"Ann"},
          {"play":"Enter DP","username":"Joe"},
          {"play":"Draw card","username":"Joe","log":{"public_log":"Drew a card","private_log":"Drew \"Branded Fusion\""}},
          {"play":"Duel message","username":"Joe","message":"Imperm is likely, so I bait it first"},
          {"play":"Normal Summon","username":"Joe","log":{"public_log":"Normal Summoned \"Aluber the Jester of Despia\""}},
          {"play":"Activate","username":"Joe","log":{"public_log":"Activated \"Branded Fusion\""}},
          {"play":"End turn","username":"Joe"},
          {"play":"Enter DP","username":"Ann"},
          {"play":"Normal Summon","username":"Ann","log":{"public_log":"Normal Summoned \"Snake-Eye Ash\""}}
         ]}
    """.trimIndent()

    @Test
    fun theAuthorsTurnIsAskedAsTheySawItAndGradedOnTheirPlays() {
        val r = assertNotNull(DbReplays.parse(replay))
        val points = AuthorExam.points(7, r, "Joe")
        assertEquals(1, points.size)
        val p = points.single()
        assertEquals("r7-g1-t2", p.id)
        // The answer key: Joe's plays this turn, his draw not among them.
        assertEquals(listOf("Aluber the Jester of Despia", "Branded Fusion"), p.cards)
        // The other player's set card is as Joe saw it — face down — and Joe's own draw and words are his.
        assertTrue("Ann: Set a card in S-2" in p.context, p.context)
        assertFalse("Infinite Impermanence" in p.context, p.context)
        assertTrue("Drew \"Branded Fusion\"" in p.context, p.context)
        assertTrue("You said: “Imperm is likely, so I bait it first”" in p.context, p.context)
        // Nothing of the turn's own plays, nor of what came after, is in the position.
        assertFalse("Aluber" in p.context, p.context)
        assertFalse("Snake-Eye" in p.context, p.context)
        // Graded card by card: the first play, and how much of the turn the plan holds.
        val right = AuthorExam.grade(p.cards, listOf("aluber the jester of despia", "Branded Fusion", "Albion"))
        assertTrue(right.first)
        assertEquals(1.0, right.recall)
        assertEquals(2.0 / 3, right.precision, 1e-9)
        val wrong = AuthorExam.grade(p.cards, listOf("Branded Fusion"))
        assertFalse(wrong.first)
        assertEquals(0.5, wrong.recall)
        // A position of a player who is not in the replay is no position.
        assertTrue(AuthorExam.points(7, r, "Bob").isEmpty())
    }

    @Test
    fun theSamePositionsAreAskedEveryRunSoRunsCompare() {
        val r = assertNotNull(DbReplays.parse(replay))
        val many = (1..30).flatMap { AuthorExam.points(it, r, "Joe") }
        val a = AuthorExam.pick(many, 10)
        assertEquals(10, a.size)
        assertEquals(a, AuthorExam.pick(many.shuffled(), 10))
        val run = ExamRun(1, "d1", answers = listOf(
            ExamAnswer("a", 1, 1, 1, listOf("A"), listOf("A"), first = true, recall = 1.0, precision = 1.0),
            ExamAnswer("b", 1, 1, 3, listOf("A", "B"), listOf("B"), first = false, recall = 0.5, precision = 1.0),
            ExamAnswer("c", 2, 1, 1, listOf("C"), missed = true),
        ))
        assertTrue(run.words().startsWith("Played the author's first play in 1 of 3 turns (33%"), run.words())
        val runs = ExamLog.read(ExamLog.write(listOf(run)))
        assertEquals(run, runs.single())
        assertTrue("Last time: 33%" in ExamLog.compare(run.copy(playbook = 9), run), ExamLog.compare(run, run))
    }

    @Test
    fun everyChaptersVideoIsWatchedAndOneReadBeforeIsLookedOverOnce() {
        val start = "https://metafy.gg/@joe/guides/x"
        fun ch(n: Int, checked: Boolean = true, has: Boolean = false, watched: Boolean = true) =
            Chapter(n, "Ch $n", "$start/$n", state = Chapter.State.NOTED, scanned = true, depth = CourseDepth.CURRENT, videoChecked = checked, hasVideo = has, watched = watched)
        val base = Course("c", start, listed = true, consolidated = true, distilled = true, distilDepth = CourseDepth.CURRENT, examDrawn = true)
        // Read before 1.1.44: looked over once for a video.
        assertEquals(StudyQueue.Step.Watch(1), StudyQueue.next(base.copy(chapters = listOf(ch(1, checked = false)))))
        // A video waiting for the voice model is watched once this build can listen, and never loops before.
        val waiting = base.copy(chapters = listOf(ch(1, has = true, watched = false)))
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(waiting, canWatch = false))
        assertEquals(StudyQueue.Step.Watch(1), StudyQueue.next(waiting, canWatch = true))
        assertEquals(StudyQueue.Step.Done, StudyQueue.next(base.copy(chapters = listOf(ch(1, has = true)))))
        // An older build's chapter reads with nothing checked.
        val older = assertNotNull(CourseCodec.read("""{"id":"c","start":"$start","chapters":[{"n":1,"title":"A","url":"$start/1","state":"NOTED"}]}"""))
        assertFalse(older.chapters.single().videoChecked || older.chapters.single().watched)
    }
}
