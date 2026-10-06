package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.NET
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.deck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.pondMonster
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cards with no effect yet (D.md §5.5, step 4's `GoldfishUnknownTest`): a card the goldfish does not trust is inert — never
 * activated, summoned, Set, material or a Tribute — while known effects still move it; a target or a recorded line that
 * needs one is not computable, and the headline reads "at least" with the exact share of hands holding one.
 */
class GoldfishUnknownTest {
    private val kit = GoldfishFixtures.kit()

    @Test
    fun anUnknownCardIsNeverActivatedSummonedOrSet() {
        // Pond Elder has no script: in hand it offers nothing — no Normal Summon, no Set — though a Level 4 monster could be.
        val g = GoldfishHands.game(deck(ELDER to 40), emptyList(), 1, 0, true)
        val t = GoldfishHands.table(g, kit)
        assertTrue(FxEngine.moves(t, 0).all { it is FxMove.Phase }, FxEngine.moves(t, 0).toString())
        // And a known effect never Special Summons it: Callers with only Elders to call never get a Pond monster out.
        val callers = GoldfishDeck(deck(CALLER to 3, ELDER to 3, STONE to 34))
        val r = Goldfish.runHere(GoldfishSetup(callers, target(BoardCond.Controls(pondMonster, 1)), hands = 300, seed = 3), kit)
        assertEquals(0, r.reached)
        assertEquals(r.hands, r.noLine)
    }

    @Test
    fun knownEffectsStillMoveItAndTheLineSaysSo() {
        // Pond Net adds any Pond card from the Deck: the Elder too, as an object. Holding an Elder needs no effect.
        val nets = GoldfishDeck(deck(NET to 3, ELDER to 3, STONE to 34))
        val hold = target(BoardCond.Holds(Filter.Name(ELDER), 1), name = "hold an Elder")
        assertNull(Goldfish.refusal(GoldfishSetup(nets, hold), kit), "Holds may name an unknown card")
        val r = Goldfish.runHere(GoldfishSetup(nets, hold, hands = 300, seed = 8), kit)
        r.outcomes.forEach { o ->
            val elders = o.hand.count { it == ELDER }
            val reach = elders >= 1 || (o.hand.any { it == NET } && elders < 3)
            assertEquals(reach, o.end == HandEnd.REACHED, "hand ${o.hand}")
            // A hand that searched its Elder reached through a line that moves an unknown card; one that held it did not.
            if (o.end == HandEnd.REACHED) assertEquals(elders == 0, o.touchedUnknown, "hand ${o.hand}")
        }
        assertTrue(r.touchedUnknown > 0)
        assertTrue(r.lines.any { ELDER in it.touches })
        assertTrue("reached the board through a line that moves an unknown card" in GoldfishWords.headline(r))
    }

    @Test
    fun aTargetThatNeedsAnUnknownCardOnTheBoardIsNotComputable() {
        val callers = GoldfishDeck(deck(CALLER to 3, ELDER to 3, STONE to 34))
        listOf(
            BoardCond.Controls(Filter.Name(ELDER), 1),
            BoardCond.InGy(Filter.Name(ELDER), 1),
            BoardCond.Banished(Filter.All(listOf(Filter.Name(ELDER))), 1),
        ).forEach { c ->
            val nc = assertNotNull(Goldfish.refusal(GoldfishSetup(callers, target(c)), kit), "$c")
            assertEquals(listOf(ELDER), nc.cards)
            assertTrue("Pond Elder" in nc.why && "write" in nc.why, nc.why)
        }
        // A newer build's condition cannot be judged either.
        val newer = target(BoardCond.Unknown())
        assertNotNull(Goldfish.refusal(GoldfishSetup(callers, newer), kit))
    }

    @Test
    fun aRecordedLineThatNeedsAnUnknownCardIsNotComputableAndTheSearchRuns() {
        val deck = GoldfishDeck(deck(FROG to 3, CALLER to 3, ELDER to 3, STONE to 31))
        val combo = Combo("c-elder", "Elder line", needs = listOf("Pond Elder"), steps = listOf("summon Pond Elder to m1"))
        val r = Goldfish.runHere(GoldfishSetup(deck, target(BoardCond.Controls(pondMonster, 1)), hands = 200, seed = 4, combo = combo), kit)
        assertEquals(null, r.combo, "the search ran instead")
        val nc = r.notComputable.single()
        assertEquals("c-elder", nc.what)
        assertEquals(listOf(ELDER), nc.cards)
        assertTrue(r.reached > 0, "never reported as 0 %: the search found its own lines")
    }

    @Test
    fun aDeckWithOneUnknownStarterReadsAtLeastWithTheExactShareOfHandsHoldingIt() {
        // One Elder (an unknown starter) among Frogs: the share of hands holding it is counted exactly, and it is 5 in 40.
        val deck = GoldfishDeck(deck(FROG to 39, ELDER to 1))
        val r = Goldfish.runHere(GoldfishSetup(deck, target(BoardCond.Controls(pondMonster, 1)), hands = 2000, seed = 12), kit)
        val elder = r.outcomes.count { o -> ELDER in o.hand }
        assertEquals(elder, r.heldUnknown)
        assertEquals(listOf(ELDER), r.unknown)
        val p = 5.0 / 40
        assertTrue(kotlin.math.abs(elder.toDouble() / r.hands - p) < 4 * kotlin.math.sqrt(p * (1 - p) / r.hands), "$elder of ${r.hands}")
        val h = GoldfishWords.headline(r, name = { kit.name(it) }, copies = { c -> deck.main.count { it == c } })
        assertTrue(h.startsWith("Gets there in at least 100.0 % of 2,000 hands"), h)
        assertTrue("${GoldfishWords.pct(elder.toDouble() / r.hands)} of hands held a card with no trusted effect (Pond Elder ×1)" in h, h)
        assertTrue("played as inert: the true number may be higher." in h, h)
    }
}
