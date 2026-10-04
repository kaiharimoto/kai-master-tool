package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.skills.BuiltInSkills
import com.kaiharimoto.mastertool.core.ai.skills.ShootoutSkills
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase S stage 3: Shootout's tools, modes and skills, held to the catalogue's rules. */
class ShootoutAiTest {

    @Test
    fun theToolsAreInTheCatalogueAndDescribed() {
        listOf("shootout_state", "shootout_judge", "shootout_rubric").forEach { name ->
            val tool = assertNotNull(AiTools.named(name), name)
            assertTrue(tool in AiTools.offered(3))
            assertTrue(tool.description.length in 20..1200)
        }
        assertTrue("shootout_state" in AiTools.readOnly)
        assertTrue("shootout_judge" !in AiTools.readOnly && "shootout_rubric" !in AiTools.readOnly)
        // A judging request offers the answer and the look-ups, nothing that changes the app.
        assertTrue(ShootoutTools.JUDGING.all { AiTools.named(it) != null })
        assertTrue(ShootoutTools.JUDGING.none { it in AiTools.DECK_CHANGING })
    }

    @Test
    fun theInterviewAndAJudgedHandChangeNoDeck() {
        listOf(AiSession.MODE_RUBRIC, AiSession.MODE_SHOOTOUT).forEach { mode ->
            assertTrue(AiTools.barredIn(mode).containsAll(AiTools.DECK_CHANGING), mode)
            assertTrue("open_deck" in AiTools.barredIn(mode), mode)
            assertNull(AiTools.barredWhy(mode, "shootout_rubric"), mode)
            assertNull(AiTools.barredWhy(mode, "card_info"), mode)
        }
    }

    @Test
    fun eachModeHasItsSkill() {
        val names = BuiltInSkills.upTo(3).map { it.name }
        assertTrue(ShootoutSkills.JUDGE_NAME in names && ShootoutSkills.INTERVIEW_NAME in names)
        assertTrue("shootout_judge" in ShootoutSkills.JUDGE && "shootout_rubric" in ShootoutSkills.INTERVIEW)
    }

    @Test
    fun certaintyIsReadLeniently() {
        fun sure(v: String) = ShootoutTools.sure(JsonObject(mapOf("sure" to JsonPrimitive(v))))
        assertEquals(0.8, sure("0.8"))
        assertEquals(0.8, sure("80%"))
        assertEquals(1.0, sure("150"), "a share, held to one")
        assertEquals(0.7, ShootoutTools.sure(JsonObject(mapOf("sure" to JsonPrimitive(70)))))
        assertNull(sure("very"))
    }
}
