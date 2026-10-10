package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.playbook.Play
import com.kaiharimoto.mastertool.core.ai.proposals.Expect
import com.kaiharimoto.mastertool.core.ai.proposals.Proposal
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalBook
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalCodec
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalOp
import com.kaiharimoto.mastertool.core.ai.proposals.Proposals
import com.kaiharimoto.mastertool.core.deck.DeckDependents
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.mapper.StarterTable
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ai proposes (Phase G, G.9): a proposal kept, drawn, held to its results, and what a cut breaks. */
class ProposalsTest {
    private val proposal = Proposal(
        id = "p1", deckId = "d", title = "Ash over Droll",
        ops = listOf(ProposalOp("remove", "Droll & Lock Bird", 1), ProposalOp("add", "Ash Blossom & Joyous Spring", 1), ProposalOp("move", "Nibiru, the Primal Being", toSection = "side")),
        why = "Opens with interaction going second rises from 61.0% to 64.2%.",
        evidence = listOf("hand_odds: Hand traps>=1 second 61.0% → 64.2%"),
        expect = listOf(Expect("Opens with interaction going second", 61.0, 64.2, 62.5, 65.9)),
        at = 5,
    )

    @Test
    fun aProposalTravelsOnItsLineAndIsKeptInItsBook() {
        val content = "Proposed: Ash over Droll.\n" + Proposals.embed(proposal)
        assertEquals(proposal, Proposals.read(content))
        assertNull(Proposals.read("No card here"))
        val book = ProposalBook("d").put(proposal).put(proposal.copy(state = Proposal.APPLIED))
        assertEquals(1, book.proposals.size, "one proposal by id")
        assertEquals(Proposal.APPLIED, ProposalCodec.decode(ProposalCodec.encode(book)).byId("p1")?.state)
        assertEquals(ProposalBook(), ProposalCodec.decode("not json"))
        assertEquals("−1 Droll & Lock Bird, +1 Ash Blossom & Joyous Spring, Nibiru, the Primal Being to side", Proposals.opsWords(proposal.ops))
        assertEquals("Opens with interaction going second: 61% → 64.2% (62.5–65.9%)", Proposals.expectWords(proposal.expect.single()))
        assertTrue(Proposals.claims(proposal).contains("64.2%"))
    }

    @Test
    fun anAppliedProposalIsHeldToTheGamesAtTheVersionItMade() {
        val applied = proposal.copy(state = Proposal.APPLIED, fromPrint = "a", toPrint = "b")
        fun g(print: String, result: String, source: String? = null) = TestGame("g$print$result${source.orEmpty()}${(0..999).random()}", 0, "d", "o", "O", TestGame.FIRST, result = result, deckPrint = print, source = source)
        val games = listOf(g("a", TestGame.WIN), g("a", TestGame.LOSS), g("a", TestGame.LOSS), g("b", TestGame.WIN), g("b", TestGame.WIN), g("b", TestGame.WIN, TestGame.SOURCE_AI))
        val o = Proposals.outcome(applied, games)!!
        assertEquals(TestStats.Rate(1, 3), o.before)
        assertEquals(TestStats.Rate(2, 2), o.after, "the people's games only")
        assertTrue(Proposals.outcomeWords(o).startsWith("Since: 100% ("))
        assertNull(Proposals.outcome(proposal, games), "not applied")
        assertNull(Proposals.outcome(applied, emptyList()), "no games yet")
    }

    @Test
    fun aCutNamesWhatUsedTheCard() {
        val engraver = Card(CardId(10), "Fiendsmith Engraver", "Effect Monster", "effect", "")
        val ash = Card(CardId(11), "Ash Blossom & Joyous Spring", "Effect Monster", "effect", "")
        val cards = mapOf(engraver.id to engraver, ash.id to ash)
        val before = Deck(main = listOf(engraver.id, engraver.id, ash.id))
        val after = Deck(main = listOf(engraver.id, ash.id))
        assertEquals(emptyList(), DeckDependents.lastCopiesGone(before, after) { cards[it] }, "a copy is left")
        val gone = DeckDependents.lastCopiesGone(before, Deck(main = listOf(ash.id))) { cards[it] }
        assertEquals(listOf(engraver), gone)
        val combos = listOf(
            Combo("c1", "Fiendsmith line", needs = listOf("Fiendsmith Engraver")),
            Combo("c2", "Into Lacrima", steps = listOf("discard fiendsmith engraver", "summon lacrima")),
            Combo("c3", "Ash line", needs = listOf("Ash Blossom & Joyous Spring")),
        )
        val plays = listOf(Play("p1", Play.Kind.LINE, "Engraver into Requiem", needs = listOf("Fiendsmith Engraver")))
        val starters = listOf(StarterTable.Row(cards = listOf(10), ends = listOf("b1", "b2")), StarterTable.Row(cards = listOf(10, 11), ends = listOf("b2", "b3")), StarterTable.Row(cards = listOf(11), ends = listOf("b9")))
        val d = DeckDependents.of(gone, combos, plays, starters).single()
        assertEquals(listOf("Fiendsmith line", "Into Lacrima"), d.combos)
        assertEquals(3, d.boards)
        assertEquals("− Fiendsmith Engraver: used by 2 combos, 3 mapped boards, 1 playbook line", DeckDependents.words(d))
        assertEquals(emptyList(), DeckDependents.of(listOf(Card(CardId(12), "Nothing", "Spell Card", "spell", "")), combos, plays, starters), "used by nothing: not said")
    }
}
