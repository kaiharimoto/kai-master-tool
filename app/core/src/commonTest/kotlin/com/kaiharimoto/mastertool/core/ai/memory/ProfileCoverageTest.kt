package com.kaiharimoto.mastertool.core.ai.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each interview starts where the profile is thinnest (1.0.65). */
class ProfileCoverageTest {
    private val profile = """
        # What Ai knows about you

        - Goals: top cut the Las Vegas Regional.
        - Goals: a YCS invite this season.
        - Goals: learn to pilot Labrynth going second.
        - How you play: Labrynth for two years; TCG.
        - Preferences: short answers, tables welcome.
    """.trimIndent()

    @Test
    fun eachSectionIsCountedAndJudged() {
        val lines = ProfileCoverage.of(profile)
        assertEquals(listOf("Goals", "Preferences", "Workflow", "How you play", "Decks", "Events"), lines.map { it.section })
        assertEquals(ProfileCoverage.Depth.COVERED, lines.first { it.section == "Goals" }.depth)
        assertEquals(ProfileCoverage.Depth.THIN, lines.first { it.section == "Preferences" }.depth)
        assertEquals(ProfileCoverage.Depth.EMPTY, lines.first { it.section == "Workflow" }.depth)
    }

    @Test
    fun theInterviewStartsWhereLittleIsKnown() {
        val lines = ProfileCoverage.of(profile)
        assertEquals(listOf("Workflow", "Decks", "Events", "Preferences", "How you play"), ProfileCoverage.thinnest(lines))
        val brief = ProfileCoverage.brief(lines)
        assertTrue("Goals: 3 (covered)" in brief)
        assertTrue(brief.endsWith("Start with Workflow and Decks."))
        // A first interview, with nothing known, starts at the beginning.
        assertTrue(ProfileCoverage.brief(ProfileCoverage.of(null)).endsWith("Start with Goals and Preferences."))
    }
}
