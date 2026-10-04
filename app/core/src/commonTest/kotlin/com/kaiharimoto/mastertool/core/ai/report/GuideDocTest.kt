package com.kaiharimoto.mastertool.core.ai.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuideDocTest {
    private val guide = """
        # How lab plays

        - Card roles: [[Arianna the Labrynth Servant]] — one-card starter.
        - Goals: end on [[Lady Labrynth of the Silver Castle]] with a trap set.
        - **Connections:** [[Arianna the Labrynth Servant]] + [[Big Welcome Labrynth]] — the spine.
        - Weaknesses: [[Ash Blossom & Joyous Spring]] on Arianna.
        - Something without a label.
        - Combos: Arianna, then Welcome.
    """.trimIndent()

    @Test
    fun entriesGoUnderTheirSectionsInReadingOrder() {
        val doc = GuideDoc.parse(guide)
        assertEquals("How lab plays", doc.title)
        assertEquals(listOf("Goals", "Lines", "Connections", "Card roles", "Weak points", "Notes"), doc.sections.map { it.name })
        assertEquals("[[Arianna the Labrynth Servant]] + [[Big Welcome Labrynth]] — the spine.", doc.sections.first { it.name == "Connections" }.entries.single())
        assertEquals(6, doc.entryCount)
        assertEquals("Arianna the Labrynth Servant", doc.cardMentions().first(), "the most mentioned card leads")
    }

    @Test
    fun aProfileHasItsOwnSections() {
        val doc = GuideDoc.profile("# What Ai knows about you\n\n- Workflow: brews on DuelingBook first.\n- Goals: top a Regional.\n- Likes: short answers.")
        assertEquals(listOf("Goals", "Preferences", "Workflow"), doc.sections.map { it.name })
        assertTrue(GuideDoc.parse(null).isEmpty)
    }

    @Test
    fun reportsKeepTheirOrderAndTheirChanges() {
        fun r(at: Long, u: Int) = SessionReport("d", "lab", at, SessionReport.PRINCIPLES, understanding = u, playing = u / 2, mirror = 40)
        val log = ReportLog.add(ReportLog.add(emptyList(), r(20, 70)), r(10, 55))
        assertEquals(listOf(10L, 20L), log.map { it.at })
        assertEquals(15, ReportLog.change(log, log.last()) { it.understanding })
        assertNull(ReportLog.change(log, log.first()) { it.understanding }, "the first session has nothing to move from")
        assertEquals(log, ReportLog.read(ReportLog.write(log)))
        assertTrue(ReportLog.read("not json").isEmpty())
        assertEquals("reports/deck_1.json", ReportLog.path("deck/1"))
        assertEquals(72, SessionReport.score(0.72), "a fraction reads as a percentage")
        assertEquals(100, SessionReport.score(140.0))
    }

    @Test
    fun aWholeNumberScoreIsTakenAsGivenAndOnlyAFractionIsAShare() {
        assertEquals(1, SessionReport.score(1.0), "a 1 is a score of 1, not 100")
        assertEquals(0, SessionReport.score(0.0))
        assertEquals(62, SessionReport.score(62.0))
        assertEquals(62, SessionReport.score(0.62))
        assertEquals(29, SessionReport.score(0.29), "rounded, not cut: 0.29 × 100 is 28.999…")
        assertEquals(50, SessionReport.score(0.5))
        assertEquals(100, SessionReport.score(100.0))
        assertEquals(0, SessionReport.score(-5.0))
        assertEquals(0, SessionReport.score(null))
        assertEquals(0, SessionReport.score(Double.NaN))
    }

    @Test
    fun theQuestionsAskedComeOutOfTheSessionWithTheirAnswers() {
        val q = kotlinx.serialization.json.buildJsonObject { put("question", kotlinx.serialization.json.JsonPrimitive("Which is your starter?")) }
        val turns = listOf(
            com.kaiharimoto.mastertool.core.ai.ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.ASSISTANT, listOf(com.kaiharimoto.mastertool.core.ai.Part.ToolUse("a", "ask_user", q))),
            com.kaiharimoto.mastertool.core.ai.ChatTurn(com.kaiharimoto.mastertool.core.ai.Role.USER, listOf(com.kaiharimoto.mastertool.core.ai.Part.ToolResult("a", "ask_user", "The person answered: Arianna"))),
        )
        assertEquals(listOf(SessionReport.Asked("Which is your starter?", "Arianna")), SessionQuestions.of(turns))
        assertTrue(!SessionQuestions.reported(turns))
    }
}
