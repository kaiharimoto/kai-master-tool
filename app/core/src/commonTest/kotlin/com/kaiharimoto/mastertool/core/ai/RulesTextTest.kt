package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.rules.GameRulesSkill
import com.kaiharimoto.mastertool.core.ai.rules.RulesPrimer
import com.kaiharimoto.mastertool.core.ai.skills.DeckSkills
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reference texts Ai reads: the rules primer, the game-rules skill, the deck
 * skills and the demo script. American English, sizes that fit the prompt, only
 * tools that exist, and charts that parse.
 */
class RulesTextTest {
    private val tools = setOf(
        "get_deck", "analyze_deck", "card_info", "search_cards", "memory", "memory_read", "ask_user", "rulings",
        "archetype_guide", "ygopro_tournament_decks", "ygopro_deck", "ygopro_field_snapshot", "web_search", "web_fetch",
        "delegate", "todo_write", "calculate", "hand_odds", "skill_view", "get_web", "list_webs", "get_siding",
        "set_siding_plan", "prep_state", "log_game", "matchup_matrix", "expected_winrate", "set_event", "drill",
    )

    private val skillBodies: Map<String, String> = mapOf(
        "primer" to RulesPrimer.TEXT,
        GameRulesSkill.NAME to GameRulesSkill.BODY,
        DeckSkills.FINE_TUNING_NAME to DeckSkills.FINE_TUNING,
        DeckSkills.SELF_STUDY_NAME to DeckSkills.SELF_STUDY,
        DeckSkills.TOURNAMENT_PREP_NAME to DeckSkills.TOURNAMENT_PREP,
    )

    private val allTexts: List<String>
        get() = skillBodies.values.toList() + listOf(
            GameRulesSkill.DESCRIPTION, DeckSkills.FINE_TUNING_DESCRIPTION, DeckSkills.SELF_STUDY_DESCRIPTION,
            DeckSkills.TOURNAMENT_PREP_DESCRIPTION,
        ) + AiDemo.scenes.flatMap { listOf(it.title, it.caption, it.person, it.reply) + it.activity }

    @Test
    fun americanEnglish() {
        allTexts.forEach { text ->
            assertFalse(text.contains("duell", ignoreCase = true), "British spelling in: ${text.take(60)}")
            assertFalse(text.contains("colour", ignoreCase = true), "British spelling in: ${text.take(60)}")
        }
    }

    @Test
    fun sizesFitThePrompt() {
        assertTrue(RulesPrimer.TEXT.length in 4000..6500, "primer is ${RulesPrimer.TEXT.length} characters")
        assertTrue(GameRulesSkill.BODY.length in 7000..15000, "game-rules is ${GameRulesSkill.BODY.length} characters")
        assertTrue(RulesPrimer.TEXT.contains(RulesPrimer.OFFICIAL))
    }

    @Test
    fun skillsNameOnlyRealTools() {
        val backticked = Regex("`([a-z][a-z0-9_]*)`")
        skillBodies.forEach { (name, body) ->
            backticked.findAll(body).map { it.groupValues[1] }.forEach { tool ->
                assertTrue(tool in tools, "$name names `$tool`, which is not a tool")
            }
        }
        assertTrue("`rulings`" in RulesPrimer.TEXT)
        assertTrue("`rulings`" in GameRulesSkill.BODY)
    }

    @Test
    fun demoHasSevenScenes() {
        assertEquals(7, AiDemo.scenes.size)
        AiDemo.scenes.forEach { scene ->
            assertTrue(scene.activity.isNotEmpty(), scene.title)
            assertTrue(scene.reply.length in 400..900, "${scene.title}: reply is ${scene.reply.length} characters")
        }
    }

    @Test
    fun demoChartsParse() {
        val fence = Regex("```chart\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL)
        val charts = AiDemo.scenes.flatMap { scene -> fence.findAll(scene.reply).map { it.groupValues[1] }.toList() }
        assertTrue(charts.size >= 2, "the odds and the meta scenes each draw a chart")
        charts.forEach { raw ->
            val chart = try {
                Json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                fail("chart does not parse: $raw (${e.message})")
            }
            assertTrue(chart["type"]?.jsonPrimitive?.content in setOf("bar", "hbar"), raw)
            val labels = chart["labels"]?.jsonArray ?: fail("no labels: $raw")
            val series = chart["series"] as? JsonArray ?: fail("no series: $raw")
            assertTrue(series.isNotEmpty(), raw)
            series.forEach { s ->
                val values = s.jsonObject["values"]?.jsonArray ?: fail("series without values: $raw")
                assertEquals(labels.size, values.size, "values and labels differ in $raw")
            }
        }
    }
}
