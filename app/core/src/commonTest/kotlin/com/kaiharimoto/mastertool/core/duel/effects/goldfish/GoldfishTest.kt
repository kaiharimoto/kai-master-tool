package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WELL
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.choose
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.deck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.pondMonster
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.target
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The goldfish against toy decks whose odds are worked out by hand (D.md step 4, `GoldfishTest`): every hand's outcome is
 * held to the rule its cards give, the rate to the exact hypergeometric number, the memo to the plain search hand for hand,
 * and the counts to every number of threads.
 */
class GoldfishTest {
    private val kit = GoldfishFixtures.kit()

    /** Three Frogs (Normal Monsters), three Callers ("Special Summon 1 Pond monster from your Deck"), 34 blanks. */
    private val pond = GoldfishDeck(deck(FROG to 3, CALLER to 3, STONE to 34), id = "pond", name = "Pond")
    private val two = target(BoardCond.Controls(pondMonster, 2), name = "two")

    /** Two Pond monsters on the field: a Frog Normal Summoned and one Called, or two Called — while Frogs are left to Call. */
    private fun twoRule(hand: List<Int>): Boolean {
        val f = hand.count { it == FROG }
        val c = hand.count { it == CALLER }
        return (f in 1..2 && c >= 1) || (f == 0 && c >= 2)
    }

    /** The exact chance of [rule] over a 40-card deck of 3 Frogs, 3 Callers and 34 blanks, [size] cards drawn. */
    private fun exact(size: Int, rule: (Int, Int) -> Boolean): Double {
        var p = 0.0
        for (f in 0..3) for (c in 0..3) {
            val rest = size - f - c
            if (rest < 0) continue
            if (rule(f, c)) p += choose(3, f) * choose(3, c) * choose(34, rest) / choose(40, size)
        }
        return p
    }

    @Test
    fun everyHandIsHeldToTheRuleItsCardsGiveAndTheRateToTheExactOdds() {
        listOf(true, false).forEach { first ->
            val r = Goldfish.runHere(GoldfishSetup(pond, two, first = first, hands = 600, seed = 7), kit)
            assertEquals(600, r.hands)
            r.outcomes.forEach { o ->
                val want = if (twoRule(o.hand)) HandEnd.REACHED else HandEnd.NO_LINE
                assertEquals(want, o.end, "hand ${o.index} ${o.hand} going ${if (first) "first" else "second"}")
            }
            val p = exact(if (first) 5 else 6) { f, c -> (f in 1..2 && c >= 1) || (f == 0 && c >= 2) }
            val sigma = sqrt(p * (1 - p) / r.hands)
            assertTrue(abs(r.rate - p) < 4 * sigma, "rate ${r.rate} against the exact $p")
            assertEquals(0, r.undecided)
            assertEquals(r.hands, r.reached + r.noLine + r.undecided)
            // The lines are named and counted: a Frog and a Call, or two Calls.
            assertTrue(r.lines.isNotEmpty())
            assertEquals(r.reached, r.lines.sumOf { it.count })
            assertTrue(r.lines.all { "Pond Caller" in it.skeleton }, r.lines.toString())
            assertEquals(listOf(CALLER), r.used)
            assertTrue(r.unknown.contains(STONE), "the blanks are played as inert")
        }
    }

    @Test
    fun oneTargetConditionIsTheHypergeometricNumberExactly() {
        // One Pond monster: any Frog or any Caller reaches it.
        val one = target(BoardCond.Controls(pondMonster, 1), name = "one")
        val r = Goldfish.runHere(GoldfishSetup(pond, one, hands = 400, seed = 3), kit)
        r.outcomes.forEach { o -> assertEquals(o.hand.any { it == FROG || it == CALLER }, o.end == HandEnd.REACHED, "hand ${o.hand}") }
        val p = 1 - choose(34, 5) / choose(40, 5)
        assertTrue(abs(r.rate - p) < 4 * sqrt(p * (1 - p) / r.hands), "rate ${r.rate} against $p")
    }

    @Test
    fun theMemoAgreesWithThePlainSearchHandForHand() {
        val setup = GoldfishSetup(pond, two, hands = 300, seed = 11)
        val memo = Goldfish.runHere(setup, kit)
        val plain = Goldfish.runHere(setup.copy(reduce = false), kit)
        assertTrue(!memo.ordered && plain.ordered)
        assertEquals(plain.outcomes.map { it.end }, memo.outcomes.map { it.end })
        assertEquals(plain.outcomes.map { it.line }, memo.outcomes.map { it.line })
        assertEquals(plain.lines, memo.lines)
        // The blanks are what the memo sets aside: Dry Stone is in no engine part.
        assertTrue(memo.outcomes.all { STONE !in it.reduced })
        // Hands with one engine part shared a search.
        assertTrue(memo.outcomes.map { it.reduced }.distinct().size < memo.hands / 4)
    }

    @Test
    fun threadsNeverChangeTheCounts() = runTest {
        val setup = GoldfishSetup(pond, two, hands = 240, seed = 5)
        val here = Goldfish.runHere(setup, kit)
        listOf(1, 3, 6).forEach { w ->
            val r = Goldfish.run(setup, kit, workers = w)
            assertEquals(here.outcomes.map { it.index to it.end }, r.outcomes.map { it.index to it.end }, "$w workers")
            assertEquals(here.lines, r.lines, "$w workers")
            assertEquals(Triple(here.reached, here.noLine, here.undecided), Triple(r.reached, r.noLine, r.undecided))
        }
    }

    @Test
    fun progressIsToldAndCountsUpToTheHands() = runTest {
        val seen = mutableListOf<GoldfishProgress>()
        val r = Goldfish.run(GoldfishSetup(pond, two, hands = 50, seed = 2), kit, workers = 2, progress = { synchronizedAdd(seen, it) })
        assertEquals(50, seen.maxOf { it.done })
        assertEquals(r.reached, seen.maxByOrNull { it.done }!!.reached)
    }

    private fun synchronizedAdd(list: MutableList<GoldfishProgress>, p: GoldfishProgress) {
        // The run tells progress under its own lock: one at a time.
        list += p
    }

    @Test
    fun theDeckOrderReadByADrawIsKeptAndNoHandIsReduced() {
        val wells = GoldfishDeck(deck(FROG to 3, CALLER to 3, WELL to 3, STONE to 31))
        val r = Goldfish.runHere(GoldfishSetup(wells, two, hands = 40, seed = 1), kit)
        assertTrue(r.ordered, "Pond Well draws: every hand searched on its own")
    }

    @Test
    fun aSetTrapIsAnInterruptionAndIsSetAtTheTurnsEnd() {
        val walls = GoldfishDeck(deck(WALL to 3, STONE to 37))
        val t = target(BoardCond.Interruptions(1), name = "one interruption")
        val r = Goldfish.runHere(GoldfishSetup(walls, t, hands = 200, seed = 4), kit)
        r.outcomes.forEach { o -> assertEquals(WALL in o.hand, o.end == HandEnd.REACHED, "hand ${o.hand}") }
        assertEquals(listOf("Set Pond Wall"), r.lines.map { it.skeleton })
        val set = target(BoardCond.SetCards(2), name = "two set")
        val r2 = Goldfish.runHere(GoldfishSetup(walls, set, hands = 200, seed = 4), kit)
        r2.outcomes.forEach { o -> assertEquals(o.hand.count { it == WALL } >= 2, o.end == HandEnd.REACHED, "hand ${o.hand}") }
    }

    @Test
    fun aHandOpensAsAReplayWithItsLineCommittedAndTagged() {
        val setup = GoldfishSetup(pond, two, hands = 100, seed = 7)
        val r = Goldfish.runHere(setup, kit)
        val k = r.outcomes.first { it.end == HandEnd.REACHED }.index
        val replay = GoldfishReplay.of(setup, kit, k)
        assertEquals(null, replay.problem)
        assertEquals(HandEnd.REACHED, replay.end)
        assertEquals(r.lines[r.outcomes[k].line!!].skeleton, replay.skeleton)
        // The deal is behind undo's reach; the line is the engine's, every entry tagged.
        val made = replay.game.entries.drop(replay.game.floor)
        assertTrue(made.isNotEmpty())
        assertTrue(made.all { it.fx != null }, "every move of the line carries the engine's tag")
        assertTrue(made.any { it.fx?.part == FxTag.ACTIVATE })
        // The board the replay ends on meets the target.
        val t = GoldfishHands.table(replay.game, kit)
        assertTrue(BoardCheck.meets(t, 0, two))
        assertEquals(replay.game.state.seats[0].hand.size + 0, t.state.seats[0].hand.size)
        // A hand with no line opens as dealt.
        val none = r.outcomes.first { it.end == HandEnd.NO_LINE }.index
        val dealt = GoldfishReplay.of(setup, kit, none)
        assertEquals(dealt.game.floor, dealt.game.cursor)
    }

    @Test
    fun aRecordedLineGetsThereWhenItsNeedsAreInHandAndItsStepsAreLegal() {
        val combo = Combo("c1", "Frog and Call", "pond", needs = listOf("Pond Frog", "Pond Caller"), steps = listOf("summon Pond Frog to m1", "u Pond Caller e1"))
        val setup = GoldfishSetup(pond, two, hands = 300, seed = 9, combo = combo)
        val r = Goldfish.runHere(setup, kit)
        assertEquals("c1", r.combo)
        assertTrue(r.notComputable.isEmpty(), r.notComputable.toString())
        r.outcomes.forEach { o ->
            val f = o.hand.count { it == FROG }
            val c = o.hand.count { it == CALLER }
            assertEquals(f in 1..2 && c >= 1, o.end == HandEnd.REACHED, "hand ${o.hand}: ${o.end}")
        }
        assertTrue(GoldfishWords.headline(r).startsWith("This line gets there in "))
    }

    @Test
    fun theHeadlineReadsAsTheNoteWritesIt() {
        val r = Goldfish.runHere(GoldfishSetup(pond, two, hands = 200, seed = 7), kit)
        val h = GoldfishWords.headline(r, name = { kit.name(it) })
        assertTrue(h.startsWith("Gets there in at least "), h)
        assertTrue(" of 200 hands (95 %: " in h && ", seed 7, going first; no line in " in h && "; undecided in 0.0 %." in h, h)
        assertTrue("held a card with no trusted effect (Dry Stone)" in h, h)
        // Without cards played as inert there is no "at least".
        val clean = r.copy(unknown = emptyList(), heldUnknown = 0)
        assertTrue(GoldfishWords.headline(clean).startsWith("Gets there in ${GoldfishWords.pct(r.rate)} of 200 hands"))
        assertEquals("2,000", GoldfishWords.count(2000))
        assertEquals("63.1 %", GoldfishWords.pct(0.6312))
    }

    @Test
    fun movesAreNeverOfferedForACardTheBookLacks() {
        val g = GoldfishHands.game(deck(STONE to 40), emptyList(), 1, 0, true)
        val t = GoldfishHands.table(g, kit)
        // Only the phases: a hand of blanks has nothing to do.
        assertTrue(FxEngine.moves(t, 0).all { it is com.kaiharimoto.mastertool.core.duel.effects.FxMove.Phase })
    }
}
