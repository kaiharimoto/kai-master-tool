package com.kaiharimoto.mastertool.core.ai.evidence

import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.check.FactCheck
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The evidence ledger (1.0.98): a number in the guide is one a check computed, or it is not written. */
class EvidenceTest {
    @Test
    fun theNumbersAClaimStakesItselfOn() {
        val c = Numbers.claimed("Going first you open a starter 74.2% of the time, brick about 1 in 4, and 0.31 of hands hold Ash. Level 4, 2500 ATK, 3 copies.")
        assertEquals(listOf("74.2%", "1 in 4", "0.31"), c.map { it.written })
        assertEquals(0.742, c[0].value, 1e-9)
        assertEquals(0.25, c[1].value, 1e-9)
    }

    @Test
    fun aNumberMatchesAtThePrecisionItWasWritten() {
        val source = Numbers.values("P(at least 1) = 0.7418 (74.18%)")
        assertTrue(Numbers.found(Numbers.claimed("74%")[0], source))
        assertTrue(Numbers.found(Numbers.claimed("74.2%")[0], source))
        assertTrue(!Numbers.found(Numbers.claimed("75%")[0], source), "75 % was not computed")
        assertTrue(!Numbers.found(Numbers.claimed("74.5%")[0], source))
    }

    private val turns = listOf(
        ChatTurn.user("How often do I open a starter?"),
        ChatTurn(Role.ASSISTANT, listOf(Part.ToolUse("c1", "hand_odds", JsonObject(mapOf("cards" to JsonPrimitive("Aluber")))))),
        ChatTurn(Role.USER, listOf(Part.ToolResult("c1", "hand_odds", "At least 1 of 9 starters in 5 cards from 40: 74.2%"))),
        ChatTurn.user("I'd say I go second about 60% of the time."),
    )

    @Test
    fun aComputedNumberIsWrittenWithItsProof() {
        val v = Evidence.judge("Opens a starter 74.2% of the time going first.", Evidence.sources(turns), "deckA", 1)
        assertIs<Evidence.Verdict.Proved>(v)
        val proof = v.proven.proofs.single()
        assertEquals("hand_odds", proof.tool)
        assertEquals("deckA", proof.deck, "hand odds depend on the deck")
        assertTrue("Aluber" in proof.input)
        assertEquals(Proven.Status.CHECKED, v.proven.status)
    }

    @Test
    fun whatThePersonSaidIsASource() {
        val v = Evidence.judge("kai goes second about 60% of the time.", Evidence.sources(turns), "deckA", 1)
        assertIs<Evidence.Verdict.Proved>(v)
        assertEquals(Evidence.PERSON, v.proven.proofs.single().tool)
        assertEquals("", v.proven.proofs.single().deck)
    }

    @Test
    fun aNumberNobodyComputedIsRefusedUnlessItSaysItIsAnEstimate() {
        val refused = Evidence.judge("Bricks 18% of the time.", Evidence.sources(turns), "deckA", 1)
        assertIs<Evidence.Verdict.Refused>(refused)
        assertTrue("18%" in refused.message)
        val estimate = Evidence.judge("Bricks maybe 18% of the time (estimate).", Evidence.sources(turns), "deckA", 1)
        assertIs<Evidence.Verdict.Proved>(estimate)
        assertEquals(Proven.Status.ESTIMATE, estimate.proven.status)
        assertIs<Evidence.Verdict.Words>(Evidence.judge("Hold Ash for their Called by.", Evidence.sources(turns), "deckA", 1))
    }

    @Test
    fun aNumberComputedOnAnotherDeckGoesStaleAndSaysSo() {
        val a = Deck(main = List(40) { CardId(1) })
        val b = Deck(main = List(39) { CardId(1) } + CardId(2))
        assertNotEquals(Ledger.fingerprint(a), Ledger.fingerprint(b))
        assertEquals(Ledger.fingerprint(a), Ledger.fingerprint(Deck(main = a.main.reversed())), "order aside")
        val p = Proven("Opens 74.2%.", listOf(Proof("hand_odds", "{}", deck = Ledger.fingerprint(a))))
        val stale = Ledger.staleAgainst(listOf(p), Ledger.fingerprint(b)).single()
        assertEquals(Proven.Status.STALE, stale.status)
        assertTrue("stale" in Ledger.annotate(listOf("Opens 74.2%.", "Words."), listOf(stale))[0])
        assertEquals("Words.", Ledger.annotate(listOf("Opens 74.2%.", "Words."), listOf(stale))[1])
        assertEquals(listOf(p), Ledger.staleAgainst(listOf(p), Ledger.fingerprint(a)))
        assertEquals(listOf(stale), Ledger.read(Ledger.write(listOf(stale))))
    }

    @Test
    fun aCheckersOkMustRestOnWhatItLookedUp() {
        val claims = listOf(
            FactCheck.Claim("You open a starter 74% of the time", FactCheck.Verdict.OK),
            FactCheck.Claim("You brick 31% of the time", FactCheck.Verdict.OK),
            FactCheck.Claim("Ash Blossom negates a search", FactCheck.Verdict.OK),
            FactCheck.Claim("Maxx C is limited", FactCheck.Verdict.WRONG),
        )
        val grounded = FactCheck.ground(claims, looked = listOf("P = 0.742"), cardText = emptyList())
        assertEquals(FactCheck.Verdict.OK, grounded[0].verdict)
        assertEquals(FactCheck.Verdict.UNSURE, grounded[1].verdict, "31 % was in no look-up")
        assertEquals(FactCheck.Verdict.OK, grounded[2].verdict, "words, and something was looked up")
        assertEquals(FactCheck.Verdict.WRONG, grounded[3].verdict)
        val nothing = FactCheck.ground(listOf(claims[2]), looked = emptyList(), cardText = emptyList())
        assertEquals(FactCheck.Verdict.UNSURE, nothing.single().verdict, "an ok on nothing looked up is not an ok")
        assertEquals(1, FactCheck.unreadable("I think it's all fine.").size, "a check that could not be read is said")
        assertTrue(FactCheck.unreadable("""{"claims": []}""").isEmpty())
    }
}
