package com.kaiharimoto.mastertool.core.duel.mapper.compare

import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishHands
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapPlan
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressDeal
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSearch
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSet
import com.kaiharimoto.mastertool.core.duel.mapper.stress.StressSuite
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ratios searched against the field's interruptions (Phase G part two, G2.4) on the goldfish's Pond cards, with our own
 * Ash-like Example Ward in the other seat's hand: every hand's answer is held to a map and a stress test made by hand.
 */
class RatioSearchTest {
    private val kit = GoldfishFixtures.kit(GoldfishFixtures.trust(GoldfishFixtures.scripts + FxRef.script(FxRef.WARD)))
    private val main: List<Int> = GoldfishFixtures.deck(CALLER to 2, NET to 3, SAGE to 1, WALL to 3, FROG to 3) + List(28) { STONE }
    private val deck: GoldfishDeck = GoldfishDeck(main, id = "pond", name = "Pond")
    private val two = CompareAsk.interruptions(2)

    @Test
    fun theHeldInterruptionIsTheSeedsAndTheHandsAloneAtItsShare() {
        val hold = FieldHold(listOf(FieldHold.Held(FxRef.WARD, 0.3, 0.9, 3.0), FieldHold.Held(FxRef.VEIL, 0.1, 0.3, 3.0)))
        assertEquals(0.4, hold.chance, 1e-9)
        val n = 20_000
        val drawn = (0 until n).map { hold.on(7L, it) }
        // The same seed and hand: the same draw, for either version and on every device.
        assertEquals(drawn, (0 until n).map { hold.on(7L, it) })
        assertTrue(abs(drawn.count { it?.card == FxRef.WARD } / n.toDouble() - 0.3) < 0.015)
        assertTrue(abs(drawn.count { it?.card == FxRef.VEIL } / n.toDouble() - 0.1) < 0.01)
        assertTrue(abs(drawn.count { it == null } / n.toDouble() - 0.6) < 0.015)
        // Another seed deals other draws.
        assertTrue(drawn != (0 until n).map { hold.on(8L, it) })
        // More than certain is scaled down, never past every hand.
        val over = FieldHold(listOf(FieldHold.Held(FxRef.WARD, 0.8, 1.0, 3.0), FieldHold.Held(FxRef.VEIL, 0.8, 1.0, 3.0)))
        assertEquals(1.0, over.chance, 1e-9)
        assertTrue((0 until 1000).all { over.on(1L, it) != null })
    }

    @Test
    fun theSuitesFieldCardsAreHeldAsOftenAsTheFieldOpensThem() {
        val suite = StressSuite(sets = 
            listOf(
                StressSet(listOf(FxRef.WARD), share = 0.8),
                StressSet(listOf(FxRef.VEIL), share = 0.5),
                StressSet(listOf(FxRef.WARD, FxRef.VEIL), share = 0.4),
                StressSet(listOf(FROG)),
            ),
        )
        val hold = assertNotNull(FieldHold.of(suite, written = { it == FxRef.WARD }, copies = { if (it == FxRef.VEIL) 2.0 else null }))
        // Three copies open in five from forty a third of the time; Ward is in 80 % of lists.
        assertEquals(0.3375, FieldHold.opens(3.0), 1e-3)
        assertEquals(listOf(FxRef.WARD), hold.sets.map { it.card })
        assertEquals(0.8 * FieldHold.opens(3.0), hold.sets.single().chance, 1e-9)
        // Veil is not written: said with the hands it would have been held on, never moved onto Ward.
        assertEquals(listOf(FxRef.VEIL), hold.unwritten.map { it.card })
        assertEquals(0.5 * FieldHold.opens(2.0), hold.unwritten.single().chance, 1e-9)
        // A suite with none of the field's cards holds nothing.
        assertNull(FieldHold.of(StressSuite(sets = listOf(StressSet(listOf(FROG)))), written = { true }))
    }

    @Test
    fun throughWardEveryHandIsTheMapAndTheStressTestTogether() = runTest {
        val b = assertNotNull(Variants.apply(deck, listOf(DeckChange(out = NET, into = CALLER))) { false })
        val hold = FieldHold(listOf(FieldHold.Held(FxRef.WARD, 0.5, 1.0, 3.0)))
        val n = 60
        val seed = 3L
        val setup = CompareSetup(deck, b, two, seed = seed, batch = n, most = n, recheck = 1, sequential = false, against = hold)
        val c = VersionCompare.run(setup, kit, workers = 1)
        assertEquals(n, c.paired.hands)
        assertEquals((0 until n).count { hold.on(seed, it) != null }, c.held)
        fun reaches(d: GoldfishDeck, k: Int): Boolean {
            val hand = GoldfishHands.hand(d.main, seed, k, true, keyAs = d.keyAs)
            val deal = MapDeal(hand.filterNot(kit::inert).sorted(), true, seed, hand.filter(kit::inert).sorted())
            val t = deal.table(d.main, d.extra, kit)
            val map = MapPlan(d.main, d.extra, kit).map(deal)
            assertTrue(map.complete, "hand $k: $hand")
            if (map.ends.none { it.traits.interruptions >= 2 }) return false
            val held = hold.on(seed, k) ?: return true
            val r = StressSearch(kit, budget = CompareSetup.STRESS_BUDGET).test(StressDeal(deal, held.cards).table(d.main, d.extra, kit), deal.fodderUids(t), ceiling = 2)
            assertTrue(r.complete, "hand $k: $hand")
            return r.guarantee >= 2
        }
        val ya = (0 until n).map { reaches(deck, it) }
        val yb = (0 until n).map { reaches(b, it) }
        assertEquals(ya.indices.count { ya[it] && yb[it] }, c.paired.both)
        assertEquals(ya.indices.count { ya[it] && !yb[it] }, c.paired.onlyA)
        assertEquals(ya.indices.count { !ya[it] && yb[it] }, c.paired.onlyB)
        // Ward bit somewhere: some hand the goldfish reached does not reach it through Ward.
        val goldfish = VersionCompare.run(setup.copy(against = null), kit, workers = 1)
        assertTrue(c.paired.both + c.paired.onlyA < goldfish.paired.both + goldfish.paired.onlyA, "Ward never mattered")
        // A changed hand names what was held on it.
        c.changed.forEach { h -> assertEquals(hold.on(seed, h.k)?.card, h.held) }
    }

    @Test
    fun theSwapsAreTheEnginesRatiosTheClassicQuestionsFirst() {
        val swaps = RatioSearch.swaps(deck, kit)
        // One more of Caller (2) or Sage (1), one fewer of another engine card; never a stone, never Ward.
        assertTrue(swaps.isNotEmpty())
        assertTrue(swaps.all { it.add in setOf(CALLER, SAGE) && it.cut != it.add && it.cut != STONE })
        // The third Net against the second Sage before anything closer.
        val first = swaps.first()
        assertEquals(SAGE, first.add)
        assertTrue(first.cut in setOf(NET, WALL, FROG))
        // A card at its limit gets no more copies.
        assertTrue(RatioSearch.swaps(deck, kit, allowed = { if (it == SAGE) 1 else 3 }).none { it.add == SAGE })
        assertEquals(3, RatioSearch.swaps(deck, kit, most = 3).size)
    }

    @Test
    fun askedSwapsAreHeldToTheRulesTheDefaultListKeeps() {
        // The red team's R5: Ai's own swaps went to the search as given. A hand trap of its own (Ward) cut for an engine card
        // has no opponent to answer in the goldfish, so it would always look like a gain — and be called better.
        val withWard = GoldfishDeck(GoldfishFixtures.deck(CALLER to 2, NET to 3, SAGE to 1, WALL to 3, FROG to 3, FxRef.WARD to 1) + List(27) { STONE }, id = "pond", name = "Pond")
        val asked = listOf(
            RatioSearch.Swap(CALLER, NET),
            RatioSearch.Swap(CALLER, FxRef.WARD),
            RatioSearch.Swap(FxRef.WARD, NET),
            RatioSearch.Swap(CALLER, STONE),
            RatioSearch.Swap(NET, SAGE),
            RatioSearch.Swap(CALLER, NET),
            RatioSearch.Swap(SAGE, SAGE),
        )
        val a = RatioSearch.admit(asked, withWard, kit)
        assertEquals(listOf(RatioSearch.Swap(CALLER, NET)), a.swaps)
        assertEquals(
            listOf(
                RatioSearch.Refusal.CUT_NOT_ENGINE, RatioSearch.Refusal.ADD_NOT_ENGINE, RatioSearch.Refusal.CUT_NOT_ENGINE,
                RatioSearch.Refusal.AT_LIMIT, RatioSearch.Refusal.ASKED_TWICE, RatioSearch.Refusal.SAME_CARD,
            ),
            a.refused.map { it.why },
        )
        // Everything admitted is a swap the default list would ask, with no limit on how many.
        val possible = RatioSearch.swaps(withWard, kit, most = Int.MAX_VALUE).toSet()
        assertTrue(a.swaps.all { it in possible })
        // The rules in force: a card at its limit gets no more copies.
        assertEquals(RatioSearch.Refusal.AT_LIMIT, RatioSearch.admit(listOf(RatioSearch.Swap(CALLER, NET)), withWard, kit, allowed = { if (it == CALLER) 2 else 3 }).refused.single().why)
        // At most the cap, however many are asked (never past CAP, whatever is passed).
        assertTrue(possible.size > 3)
        val many = RatioSearch.admit(possible.toList(), withWard, kit, most = 3)
        assertEquals(3, many.swaps.size)
        assertEquals(possible.size - 3, many.refused.size)
        assertTrue(many.refused.all { it.why == RatioSearch.Refusal.PAST_CAP })
        assertEquals(minOf(possible.size, RatioSearch.CAP), RatioSearch.admit(possible.toList(), withWard, kit, most = 1_000).swaps.size)
        val words = RatioSearch.refusedWords(a.refused.first()) { if (it == FxRef.WARD) "Example Ward" else "Card $it" }
        assertTrue("Example Ward is not an engine card" in words && "always look like a gain" in words, words)
    }

    @Test
    fun holmCallsOnlyWhatSurvivesTheFamily() {
        fun step(both: Int, onlyA: Int, onlyB: Int, neither: Int, stopped: Boolean = false) = RatioSearch.Step(
            RatioSearch.Swap(CALLER, NET),
            Comparison(Paired(both, onlyA, onlyB, neither), emptyList(), settled = false, stopped = stopped, 0, 0, 0, 0, 0L, 0L),
        )
        val clear = step(200, 2, 30, 168)
        val worse = step(200, 30, 2, 168)
        // Alone this would pass at 5 %, among four it does not.
        val borderline = step(200, 9, 22, 168)
        assertTrue(borderline.comparison!!.paired.pValue in 0.02..0.05, "${borderline.comparison.paired.pValue}")
        val alike = step(200, 0, 0, 200)
        val cut = step(100, 0, 20, 80, stopped = true)
        val calls = RatioSearch.calls(listOf(clear, worse, borderline, alike, cut))
        assertEquals(RatioSearch.Call.BETTER, calls[0])
        assertEquals(RatioSearch.Call.WORSE, calls[1])
        assertEquals(RatioSearch.Call.UNSURE, calls[2])
        assertEquals(RatioSearch.Call.ALIKE, calls[3])
        assertEquals(RatioSearch.Call.NOT_RUN, calls[4])
        // Alone, the borderline step is called.
        assertEquals(RatioSearch.Call.BETTER, RatioSearch.calls(listOf(borderline)).single())
    }

    @Test
    fun aSearchComparesEverySwapOnTheSameHandsAndSaysWhatItCannot() = runTest {
        val swaps = listOf(RatioSearch.Swap(CALLER, NET), RatioSearch.Swap(SAGE, WALL), RatioSearch.Swap(CALLER, 123))
        val done = ArrayList<Int>()
        val r = RatioSearch.run(deck, swaps, CompareAsk.interruptions(1), kit, hands = 40, workers = 1) { _, i, _ -> done += i }
        assertEquals(listOf(1, 2, 3), done)
        assertEquals(3, r.steps.size)
        assertEquals(40, r.steps[0].comparison!!.paired.hands)
        assertEquals(40, r.steps[1].comparison!!.paired.hands)
        assertNull(r.steps[2].comparison)
        assertEquals("the deck does not hold a copy to cut", r.steps[2].refused)
        assertEquals(RatioSearch.Call.NOT_RUN, r.calls[2])
        assertFalse(r.stopped)
        val words = RatioSearch.words(r, kit::name)
        assertTrue("Holm" in words && "the goldfish alone" in words, words)
        // The same search again is the same numbers: the hands are the seed's.
        val again = RatioSearch.run(deck, swaps.take(1), CompareAsk.interruptions(1), kit, hands = 40, workers = 1)
        assertEquals(r.steps[0].comparison!!.paired, again.steps[0].comparison!!.paired)
    }
}
