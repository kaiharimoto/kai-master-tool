package com.kaiharimoto.mastertool.core.deck

import com.kaiharimoto.mastertool.core.TestCards
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.core.model.Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The chosen rules govern every copy limit (the 1.1.2 design review, finding 1): the editor is handed
 * `DeckRules.banSource`, so a Genesys deck takes three Maxx "C" and a dated list limits a card by that day's status —
 * while an editor handed nothing behaves as it always has.
 */
class ChosenRulesLimitTest {
    private val maxx = TestCards.maxxC
    private val reborn = TestCards.monster(id = 83764718, name = "Monster Reborn", tcg = BanStatus.LIMITED)

    private fun addAll(rules: DeckRules?, card: com.kaiharimoto.mastertool.core.model.Card, times: Int): Pair<Deck, DeckEdit.Rejected?> {
        var deck = Deck.EMPTY
        repeat(times) {
            when (val e = DeckEditor.add(deck, card, DeckSection.MAIN, Format.TCG, rules?.banSource)) {
                is DeckEdit.Applied -> deck = e.deck
                is DeckEdit.Rejected -> return deck to e
            }
        }
        return deck to null
    }

    @Test
    fun byDefaultTodaysListStillHoldsTheCopies() {
        val (deck, refused) = addAll(null, maxx, 1)
        assertEquals(0, deck.main.size)
        assertEquals(RejectionReason.COPY_LIMIT, refused?.reason)
        assertEquals(0, DeckEditor.copyLimit(maxx, Format.TCG))
        // The default rules are the pool's list, the same answer.
        assertEquals(0, DeckRules().copyLimit(maxx))
        assertEquals(0, DeckEditor.remainingCopies(Deck.EMPTY, maxx, Format.TCG, DeckRules().banSource))
        assertTrue(DeckRules().isDefault)
    }

    @Test
    fun genesysAllowsThreeMaxxC() {
        val genesys = DeckRules(genesysCap = 100)
        val (three, refused) = addAll(genesys, maxx, 3)
        assertEquals(null, refused)
        assertEquals(3, three.main.size)
        // A fourth is the deck's own limit, which Genesys keeps.
        val fourth = DeckEditor.add(three, maxx, DeckSection.MAIN, Format.TCG, genesys.banSource)
        assertIs<DeckEdit.Rejected>(fourth)
        assertEquals(3, genesys.copyLimit(maxx))
        assertEquals(BanStatus.UNLIMITED, genesys.statusOf(maxx))
        assertFalse(DeckEditor.isForbidden(maxx, Format.TCG, genesys.banSource))
        // The stepper clamps to the same limit.
        val stepped = DeckEditor.setCount(Deck.EMPTY, maxx, DeckSection.MAIN, 3, Format.TCG, genesys.banSource)
        assertIs<DeckEdit.Applied>(stepped)
        assertEquals(3, stepped.deck.main.size)
        assertEquals(3, DeckEditor.remainingCopies(Deck.EMPTY, maxx, Format.TCG, genesys.banSource))
    }

    @Test
    fun aDatedListLimitsByThatDaysStatus() {
        // On 1 April 2005 Monster Reborn was Forbidden; the pool says Limited today.
        val april2005 = DeckRules(asOf = "2005-04-01", limits = BanSource { if (it.name == "Monster Reborn") BanStatus.FORBIDDEN else BanStatus.UNLIMITED }, listName = "April 2005 Lists (TCG)")
        assertEquals(1, DeckEditor.copyLimit(reborn, Format.TCG))
        assertEquals(0, april2005.copyLimit(reborn))
        val (none, refused) = addAll(april2005, reborn, 1)
        assertTrue(none.main.isEmpty())
        assertEquals(RejectionReason.COPY_LIMIT, refused?.reason)
        // And a card Forbidden today but free on that day goes in three times.
        val (three, _) = addAll(april2005, maxx, 3)
        assertEquals(3, three.main.size)
        val clamped = DeckEditor.setCount(Deck.EMPTY, reborn, DeckSection.MAIN, 2, Format.TCG, april2005.banSource)
        assertIs<DeckEdit.Applied>(clamped)
        assertTrue(clamped.deck.main.isEmpty())
    }

    @Test
    fun aCardsStandingIsOneLine() {
        val today = "2026-10-04"
        val out = maxx.copy(formats = listOf("TCG", "OCG"), tcgDate = "2026-10-08", genesysPoints = 50)
        // Not out yet comes first, so it never also reads "Unlimited".
        assertEquals(DeckRules.Standing("Not in the TCG until 8 Oct 2026", true), DeckRules().standing(out, today))
        val released = out.copy(tcgDate = "2011-02-08")
        assertEquals(DeckRules.Standing("Forbidden · TCG", true), DeckRules().standing(released, today))
        assertEquals(DeckRules.Standing("Genesys · 50 points", false), DeckRules(genesysCap = 100).standing(released, today))
        val dated = DeckRules(asOf = "2012-01-01", limits = BanSource { BanStatus.LIMITED }, listName = "September 2011 Lists (TCG)")
        assertEquals(DeckRules.Standing("Limited · September 2011 list", false), dated.standing(released, today))
        val link = TestCards.accesscode.copy(genesysPoints = 0)
        assertEquals(DeckRules.Standing("Not in Genesys · Link monster", true), DeckRules(genesysCap = 100).standing(link, today))
    }

    @Test
    fun theTagShowsOnlyWhenTheDefaultWasChanged() {
        assertEquals(null, DeckRules().tag())
        assertEquals("1 May 2025", DeckRules(asOf = "2025-05-01").tag())
        assertEquals("Genesys 92/100", DeckRules(genesysCap = 100).tag(92))
        assertEquals("TCG", DeckRules().short())
        assertEquals("TCG · 1 May 2025", DeckRules(asOf = "2025-05-01", listName = "April 2025 Lists (TCG)").short())
        assertEquals("Genesys 100", DeckRules(genesysCap = 100).short())
    }
}
