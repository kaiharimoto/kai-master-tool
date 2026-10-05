package com.kaiharimoto.mastertool.core.duel.match

import com.kaiharimoto.mastertool.core.ai.eval.EvalItem
import com.kaiharimoto.mastertool.core.ai.eval.Grader
import com.kaiharimoto.mastertool.core.ai.eval.ItemOutcome
import com.kaiharimoto.mastertool.core.ai.eval.TrustWords
import com.kaiharimoto.mastertool.core.ai.providers.ModelNames
import com.kaiharimoto.mastertool.core.duel.DuelPrefs
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import com.kaiharimoto.mastertool.core.duel.record.ResultSeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The design review of Ai vs Ai, duel records and Trust (its findings 2, 3, 11 and 13): a model named as a person says it,
 * a budget that lets the first match finish, the tally read by engine and deck, and Trust's misses led by the question.
 */
class WatchingWordsTest {
    @Test
    fun aModelIsNamedAsAPersonSaysIt() {
        assertEquals("Opus 5.5", ModelNames.short("claude-opus-5-5"))
        assertEquals("Opus 4.1", ModelNames.short("claude-opus-4-1-20250805"))
        assertEquals("Sonnet 4.5", ModelNames.short("claude-sonnet-4-5"))
        assertEquals("Sonnet 3.5", ModelNames.short("claude-3-5-sonnet-20241022"))
        assertEquals("Haiku 4.5", ModelNames.short("claude-haiku-4-5"))
        assertEquals("Opus 4.1", ModelNames.short("us.anthropic.claude-opus-4-1-20250805-v1:0"))
        assertEquals("GPT-5", ModelNames.short("gpt-5"))
        assertEquals("GPT-4o", ModelNames.short("gpt-4o-2024-08-06"))
        assertEquals("GPT-4.1 mini", ModelNames.short("gpt-4.1-mini"))
        assertEquals("o4-mini", ModelNames.short("o4-mini"))
        assertEquals("Gemini 2.5 Pro", ModelNames.short("gemini-2.5-pro"))
        assertEquals("Gemini 2.0 Flash", ModelNames.short("models/gemini-2.0-flash-001"))
        assertEquals("Llama3.1 8b", ModelNames.short("llama3.1:8b"))
        assertEquals("Mistral Large", ModelNames.short("mistral-large-latest"))
        assertEquals("", ModelNames.short(" "))
        // Short enough for the score column: ten characters or fewer for the models people connect.
        listOf("claude-opus-5-5", "claude-sonnet-4-5", "gpt-5", "gpt-4o").forEach { assertTrue(ModelNames.short(it).length <= 10, it) }
    }

    @Test
    fun theDefaultBudgetLetsTheMatchFinishAndTheCapSaysWhereItStops() {
        // Twelve turns cost about 900k: 1M covers it, where 500k stopped the first match about turn 7.
        assertEquals(1_000_000L, AiMatch.budgetFor(12))
        assertEquals(500_000L, AiMatch.budgetFor(6))
        assertEquals(2_000_000L, AiMatch.budgetFor(20))
        assertEquals(2_000_000L, AiMatch.budgetFor(40), "none covers 40 turns: the largest")
        assertTrue(!AiMatch.cost(MatchRules(turnCap = 12, tokenCap = AiMatch.budgetFor(12))).capped)
        assertEquals(7, AiMatch.stopsAt(500_000L))
        assertTrue(AiMatch.cost(MatchRules(turnCap = 40, tokenCap = 2_000_000L)).capped)
        assertTrue(AiMatch.stopsAt(2_000_000L) in 20..40)
    }

    private fun match(id: String, winner: Int?, first: Int, a: Pair<String, String>, b: Pair<String, String>) = DuelResult(
        id = id,
        seats = listOf(
            ResultSeat("A", deckName = a.second, model = a.first, player = Provenance.AI),
            ResultSeat("B", deckName = b.second, model = b.first, player = Provenance.AI),
        ),
        winner = winner, first = first, kind = DuelResult.AI_VS_AI, how = if (winner == null) DuelResult.LIMIT else DuelResult.LP,
    )

    @Test
    fun aiVsAiIsCountedByEngineAndDeck() {
        val opusLab = "claude-opus-5-5" to "lab"
        val gptK9 = "gpt-5" to "K9 Vanquish Soul"
        val gptLab = "gpt-5" to "lab"
        val results = listOf(
            match("1", 0, 0, opusLab, gptK9),
            match("2", 1, 1, gptK9, opusLab),
            match("3", 1, 1, opusLab, gptK9),
            match("4", null, 0, opusLab, gptK9),
            match("5", 0, 0, opusLab, gptK9),
            // The same models with another deck: a pairing of its own, never folded into the first.
            match("6", 1, 1, opusLab, gptLab),
        )
        val scores = DuelResults.aiVsAi(results)
        assertEquals(2, scores.size)
        assertEquals("Opus 5.5 (lab) beat GPT-5 (K9 Vanquish Soul) 3 of 5, 1 drawn; going first won 4.", DuelResults.matchWords(scores[0]))
        assertEquals("GPT-5 (lab) beat Opus 5.5 (lab) 1 of 1; going first won 1.", DuelResults.matchWords(scores[1]))
        // One model, two decks, is no mirror.
        val mirror = DuelResults.aiVsAi(listOf(match("7", 0, 0, opusLab, "claude-opus-5-5" to "Branded")))
        assertTrue(DuelResults.matchWords(mirror.single()).contains(" beat "))
    }

    @Test
    fun aTallyAgainstAPersonReadsInSentencesAndColumns() {
        val score = DuelResults.Score(
            "kai", DuelResults.Settings(DuelBrief.SELF, DuelPrefs.KNOW_SEAT, net = false, peeked = false, clean = true, rolled = true),
            won = 3, lost = 1, drawn = 1, peeks = 0, wentFirst = 2,
        )
        assertEquals(
            "Ai won 3 of 5 against kai, 1 drawn. Ai saw only its own hand; kai saw only theirs; the dice chose who went first (Ai first in 2).",
            DuelResults.words(score),
        )
        val cells = DuelResults.cells(score)
        assertEquals("kai", cells.against)
        assertEquals("Won 3 of 5, 1 drawn", cells.result)
        assertEquals("Its own hand", cells.aiSaw)
        assertEquals("Their own hand", cells.theySaw)
        assertEquals("Ai in 2 of 5, by the dice", cells.first)
    }

    @Test
    fun aMissLeadsWithTheQuestionCutAtAWord() {
        val prompt = "A 40-card Main Deck holds 12 starters (no card counts for two of these), and 28 other cards. What is the chance?\nAnswer as a percentage."
        val item = EvalItem("odds-06", prompt, Grader.Percent(0.742, 1))
        val m = TrustWords.miss(item, ItemOutcome("odds-06", 0, 1, false, "74.5% (expected 74.2%)"))
        assertEquals("answered 74.5%, right is 74.2% (to 0.1%)", m.verdict)
        assertEquals("odds-06", m.id)
        assertTrue(m.question.endsWith("…"), m.question)
        assertTrue(m.question.length <= 91, m.question)
        assertTrue(prompt.startsWith(m.question.removeSuffix("…")), "cut at a word: ${m.question}")
        assertTrue(prompt[m.question.length - 1] == ' ' || prompt[m.question.length - 1] in ",;:", "never mid-word: ${m.question}")
        assertEquals("answered no, right is yes", TrustWords.verdict(Grader.YesNo(true), "no (expected yes)"))
        assertEquals("missed (0 other claims marked wrong)", TrustWords.verdict(Grader.Planted(true), "missed (0 other claims marked wrong)"))
        // A card's name is never cut inside its brackets.
        val card = "Going second with six cards, a Main Phase play is answered by [[Nibiru, the Primal Being]] and nothing else, ever."
        val g = TrustWords.gist(card, 80)
        assertTrue(!g.contains("[[") || g.contains("]]"), g)
        assertEquals("A short one.", TrustWords.gist("A short one.\nMore."))
    }
}
