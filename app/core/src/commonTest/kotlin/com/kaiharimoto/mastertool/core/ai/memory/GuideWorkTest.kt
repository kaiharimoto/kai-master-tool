package com.kaiharimoto.mastertool.core.ai.memory

import com.kaiharimoto.mastertool.core.ai.TuneIntensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A run's room in the guide (kai: "if the study run is deep let it add up to 20k"), and Refactor guide's rewrite. */
class GuideWorkTest {
    @Test
    fun deepRunsMayAddSixtyThousand() {
        // 20,000 from 1.0.66 (kai: "let it add up to 20k"); 60,000 from mastery (1.1.43), the playbook beside it unbounded.
        assertEquals(60_000, TuneIntensity.DEEP.guideBudget)
        assertTrue(TuneIntensity.QUICK.guideBudget < TuneIntensity.STANDARD.guideBudget)
        assertTrue(TuneIntensity.STANDARD.guideBudget < TuneIntensity.DEEP.guideBudget)
        assertTrue("60,000" in GuideBudget.brief(TuneIntensity.DEEP))
    }

    @Test
    fun aRunIsHeldToItsRoomNotTheGuidesSize() {
        // A guide already 30,000 long may still take a Deep run's 20,000 more.
        assertNull(GuideBudget.refusal(startUsed = 30_000, nextUsed = 50_000, budget = 20_000, intensity = "Deep"))
        val refused = GuideBudget.refusal(startUsed = 30_000, nextUsed = 50_001, budget = 20_000, intensity = "Deep")
        assertNotNull(refused)
        assertTrue("20,000" in refused && "Deep" in refused)
        // Shrinking is always allowed.
        assertNull(GuideBudget.refusal(10_000, 2_000, 5_000, "Quick"))
        assertEquals("1,234,567", GuideBudget.grouped(1_234_567))
        assertEquals("500", GuideBudget.grouped(500))
    }

    private val guide = AiMemory.parse(
        """
        # How Labrynth plays

        A note the person wrote.

        - Card roles: [[Arianna the Labrynth Servant]] — starter.
        - Card roles: Arianna is a starter.
        - Insights: hand traps are good.
        - Lines: 1. [[Arianna the Labrynth Servant]] 2. add [[Big Welcome Labrynth]].
        """.trimIndent(),
    )

    @Test
    fun aRewriteReplacesEveryEntryAndKeepsTheTitle() {
        val write = GuideRewrite.rewrite(
            guide,
            """
            ## Card roles
            - Card roles: [[Arianna the Labrynth Servant]] — one-card starter; searches [[Big Welcome Labrynth]].
            - Lines: 1. Normal Summon [[Arianna the Labrynth Servant]]
              2. add [[Big Welcome Labrynth]]; set it.
            """.trimIndent(),
            entryLimit = 5000,
        )
        assertIs<MemoryWrite.Done>(write)
        assertEquals(2, write.doc.entries.size)
        assertTrue(write.doc.entries[1].endsWith("set it."), "an indented line continues its entry")
        assertEquals(guide.preamble, write.doc.preamble)
        assertTrue("4 entries" in write.message && "2 entries" in write.message)
    }

    @Test
    fun aRewriteNeverWipesTheGuide() {
        assertIs<MemoryWrite.Refused>(GuideRewrite.rewrite(guide, "## Nothing\n\n", 5000))
        val long = guide.copy(entries = List(40) { "Lines: step $it of a long line, written out at length so the guide is big." })
        assertIs<MemoryWrite.Refused>(GuideRewrite.rewrite(long, "- Lines: one.", 5000))
        assertIs<MemoryWrite.Refused>(GuideRewrite.rewrite(guide, "- " + "x".repeat(5001), 5000))
    }

    @Test
    fun linesWithoutBulletsAreEntriesToo() {
        assertEquals(listOf("Goals: win.", "Lines: one."), GuideRewrite.entries("Goals: win.\n\n- Lines: one."))
    }
}
