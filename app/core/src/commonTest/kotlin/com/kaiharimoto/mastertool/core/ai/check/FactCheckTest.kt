package com.kaiharimoto.mastertool.core.ai.check

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.check.FactCheck.Verdict
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fact-check pass (1.0.58): what is worth checking, what the checker is told, what it said. */
class FactCheckTest {
    @Test
    fun answersWithCardsOrRulingsAreChecked() {
        assertFalse(FactCheck.worthChecking("Sure, done."))
        assertTrue(FactCheck.worthChecking("Side in [[Infinite Impermanence]] going second: it negates their first effect monster, and it can be activated from the hand if you control no cards."))
        assertTrue(FactCheck.worthChecking("You can chain it in response to their Special Summon, but it misses the timing if it is an 'if' effect that resolves earlier in the chain."))
        assertFalse(FactCheck.worthChecking("I think the deck looks fun and you should enjoy playing it at your locals this weekend, whatever happens there."))
    }

    @Test
    fun theCheckerSeesTheCardsText() {
        val brief = FactCheck.brief("Ash negates searches.", listOf("Ash Blossom & Joyous Spring" to "When a card or effect is activated that includes...\nQuick"))
        assertTrue("<answer>\nAsh negates searches.\n</answer>" in brief)
        assertTrue("- Ash Blossom & Joyous Spring: When a card or effect is activated that includes... Quick" in brief)
    }

    @Test
    fun theCheckersAnswerIsReadForgivingly() {
        val said = """
            Here is my check:
            ```json
            {"claims": [
              {"claim": "Ash negates searches", "verdict": "ok", "source": "card_info"},
              {"claim": "Impermanence can be activated from the hand at any time", "verdict": "Wrong", "correction": "only if you control no cards", "source": "card text"},
              {"claim": "Nibiru needs five summons", "verdict": "maybe"},
              {"verdict": "ok"}
            ]}
            ```
        """.trimIndent()
        val claims = FactCheck.parse(said)
        assertEquals(listOf(Verdict.OK, Verdict.WRONG, Verdict.UNSURE), claims.map { it.verdict })
        assertEquals("only if you control no cards", claims[1].correction)
        assertTrue(FactCheck.parse("no json here").isEmpty())
        assertTrue(FactCheck.parse("{broken").isEmpty())
    }

    @Test
    fun theLineUnderTheAnswerSaysWhatTheCheckFound() {
        val ok = FactCheck.Check(3, listOf(FactCheck.Claim("a", Verdict.OK), FactCheck.Claim("b", Verdict.OK)))
        assertEquals("Checked 2 claims against the card text", FactCheck.summary(ok))
        val unsure = ok.copy(claims = ok.claims + FactCheck.Claim("c", Verdict.UNSURE))
        assertEquals("Checked 3 claims against the card text · 1 could not be confirmed", FactCheck.summary(unsure))
        val wrong = ok.copy(claims = listOf(FactCheck.Claim("Impermanence from hand any time", Verdict.WRONG, "only with no cards", "card text")))
        assertEquals("1 of 1 claim was wrong — corrected below", FactCheck.summary(wrong))
        assertEquals("1 of 2 claims was wrong — corrected below", FactCheck.summary(wrong.copy(claims = wrong.claims + FactCheck.Claim("b", Verdict.OK))))
        val correction = FactCheck.correction(wrong)
        assertTrue("Impermanence from hand any time — in fact: only with no cards (card text)" in correction)
        assertTrue("**Correction:**" in correction)
    }

    @Test
    fun checksTravelWithTheConversationAndOldOnesReadWithout() {
        val s = AiSession("s", checks = listOf(FactCheck.Check(1, listOf(FactCheck.Claim("x", Verdict.OK)))))
        val json = Json { ignoreUnknownKeys = true }
        val back = json.decodeFromString(AiSession.serializer(), json.encodeToString(AiSession.serializer(), s))
        assertEquals(s.checks, back.checks)
        assertTrue(json.decodeFromString(AiSession.serializer(), """{"id":"old"}""").checks.isEmpty())
    }
}
