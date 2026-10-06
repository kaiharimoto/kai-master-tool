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

    private fun tool(id: String, name: String, input: JsonObject, content: String) = listOf(
        ChatTurn(Role.ASSISTANT, listOf(Part.ToolUse(id, name, input))),
        ChatTurn(Role.USER, listOf(Part.ToolResult(id, name, content))),
    )

    private val goldfishAnswer = "goldfish: Pond — “two” (2 face-up Pond monsters on your field)\n" +
        "  method: depth-first search over the engine's moves, 2,000 hands from seed 7; library 1a2b3c4d5e6f (engine 1)\n" +
        "  Gets there in 63.1 % of 2,000 hands (95 %: 61.0–65.2 %), seed 7, going first; no line in 30.4 %; undecided in 6.5 %."

    @Test
    fun onlyTheGoldfishCanVouchForALine() {
        // Phase D step 4: "gets there", "goes off", "makes the board" with a percentage is held to a goldfish source.
        assertTrue(Evidence.lineClaims("The Frog line gets there 63.1% of the time going first."))
        assertTrue(Evidence.lineClaims("Going second the deck goes off in 58% of hands."))
        assertTrue(Evidence.lineClaims("Aluber into Mirrorjade makes the board 41% of the time."))
        assertTrue(!Evidence.lineClaims("The line gets there with a negate."), "no number: words")
        assertTrue(!Evidence.lineClaims("You open a starter 74.2% of the time."), "an opening's odds are any check's")
        // Ai's own simulation in a world computed 63.1 too: it cannot vouch for a line.
        val run = tool("r1", "world_run", JsonObject(mapOf("path" to JsonPrimitive("lib/sim.js"))), "simulated 2000 games: gets there 63.1%")
        val refused = Evidence.judge("The Frog line gets there 63.1% of the time.", Evidence.sources(run), "deckA", 1)
        assertIs<Evidence.Verdict.Refused>(refused)
        assertTrue("goldfish" in refused.message)
        // The goldfish through world_tool can, and its proof keeps the library it used.
        val gf = tool("g1", "world_tool", JsonObject(mapOf("name" to JsonPrimitive("goldfish"), "args" to JsonObject(mapOf("target" to JsonPrimitive("two"))))), goldfishAnswer)
        val ok = Evidence.judge("The Frog line gets there 63.1% of the time.", Evidence.sources(run + gf), "deckA", 1)
        val proof = assertIs<Evidence.Verdict.Proved>(ok).proven.proofs.single()
        assertEquals("world_tool", proof.tool)
        assertEquals("1a2b3c4d5e6f", proof.library)
        assertEquals("deckA", proof.deck)
        // Another instrument's 63.1 is no goldfish either.
        val other = tool("o1", "world_tool", JsonObject(mapOf("name" to JsonPrimitive("openings"))), "Starters>=1: first 63.1%")
        assertIs<Evidence.Verdict.Refused>(Evidence.judge("It gets there 63.1% of the time.", Evidence.sources(other), "deckA", 1))
        // An opening's odds stay any check's: the same openings answer proves them.
        assertIs<Evidence.Verdict.Proved>(Evidence.judge("You open a starter 63.1% of the time.", Evidence.sources(other), "deckA", 1))
        // Marked an estimate, it is kept as one.
        assertEquals(Proven.Status.ESTIMATE, (Evidence.judge("It goes off about 60% of the time (estimate).", Evidence.sources(run), "deckA", 1) as Evidence.Verdict.Proved).proven.status)
    }

    @Test
    fun aGoldfishNumberGoesStaleWhenAScriptItUsedChanges() {
        val p = Proven("Gets there 63.1%.", listOf(Proof("world_tool", "{\"name\":\"goldfish\"}", deck = "deckA", library = "1a2b3c4d5e6f")))
        assertEquals(listOf(p), Ledger.staleAgainst(listOf(p), "deckA", library = "1a2b3c4d5e6f"))
        assertEquals(listOf(p), Ledger.staleAgainst(listOf(p), "deckA"), "no library given: only the deck is checked")
        val stale = Ledger.staleAgainst(listOf(p), "deckA", library = "ffffffffffff").single()
        assertEquals(Proven.Status.STALE, stale.status)
        assertEquals(Ledger.LIBRARY_MOVED, stale.note)
        assertTrue("written effect" in Ledger.mark(stale)!!, Ledger.mark(stale)!!)
        // A proof written before step 4 has no library and reads as it did.
        val old = Ledger.read("""[{"entry":"Opens 74.2%.","proofs":[{"tool":"hand_odds","input":"{}","deck":"deckA"}]}]""")
        assertEquals("", old.single().proofs.single().library)
        assertEquals(old, Ledger.staleAgainst(old, "deckA", library = "ffffffffffff"))
        assertEquals("1a2b3c4d5e6f", Evidence.libraryOf(goldfishAnswer))
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
