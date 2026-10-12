package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapMoves
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapperVocab
import com.kaiharimoto.mastertool.core.duel.mapper.MovePrior
import com.kaiharimoto.mastertool.core.duel.mapper.TrainingExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A network made up for the tests: a move's logit from its row (`[kind, card, effect, zone]`), each head's value from its
 * index, and the runs it was asked for counted.
 */
internal class FakeNet(
    override val vocabHash: String,
    override val heads: List<String> = BoardTraits.HEADS,
    override val version: Int = 1,
    override val headMean: List<Double> = heads.map { 1.0 },
    private val value: (Int) -> Float = { 1f },
    private val score: (IntArray) -> Float = { -it[0].toFloat() },
) : PriorNet {
    var runs = 0
    var steps = ArrayList<Float>()
    var closed = false

    override fun run(batch: NetBatch): NetOut {
        runs++
        repeat(batch.size) { steps += batch.step[it] }
        val policy = FloatArray(batch.size * batch.moveCount) { i ->
            if (!batch.moveMask[i]) -1e9f else score(IntArray(4) { k -> batch.moves[i * 4 + k].toInt() })
        }
        return NetOut(policy, FloatArray(batch.size * heads.size) { value(it % heads.size) })
    }

    override fun close() {
        closed = true
    }
}

/** The network as the search's move prior (M3): read as the trainer reads, never a move dropped or added, cached. */
class NetPriorTest {
    private val kit = GoldfishFixtures.kit()
    private val main = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }
    private val vocab = MapperVocab.of(main)

    private fun map(hand: List<Int>, prior: MovePrior = MovePrior.NONE, budget: Int = MapSearch.DEFAULT_BUDGET) =
        MapSearch(kit, budget = budget, prior = prior).map(MapDeal(hand).table(main, emptyList(), kit))

    @Test
    fun tokensAreCutAsTheTrainerCutsThem() {
        val deck = TrainingExport.Zone.DECK.ordinal
        val hand = TrainingExport.Zone.HAND.ordinal
        // 100 Deck cards first, then 40 in hand: the hand's are all kept, then the Deck's to 128.
        val rows = List(100) { listOf(2, deck, 0, 0, 0) } + List(40) { listOf(3, hand, 0, 1, 0) }
        val cut = NetInput.cut(rows)
        assertEquals(NetInput.MAX_TOKENS, cut.size)
        assertEquals(List(40) { listOf(3, hand, 0, 1, 0) }, cut.take(40))
        assertTrue(cut.drop(40).all { it[1] == deck })
        assertEquals(rows.take(10), NetInput.cut(rows.take(10)), "a short position is kept as it is")
    }

    @Test
    fun aBatchIsPaddedAsTheTrainersCollate() {
        val a = NetPosition(listOf(intArrayOf(2, 0, 0, 1, 0), intArrayOf(3, 5, 0, 1, 1)), listOf(intArrayOf(0, 2, 1, 0)), 3)
        val b = NetPosition(emptyList(), listOf(intArrayOf(4, 0, 0, 0), intArrayOf(1, 3, 0, 0), intArrayOf(5, 0, 0, 0)), 0)
        val batch = NetInput.batch(listOf(a, b))
        assertEquals(2, batch.size)
        assertEquals(2, batch.tokenCount)
        assertEquals(3, batch.moveCount)
        assertEquals(listOf(true, true, false, false), batch.tokenMask.toList())
        assertEquals(listOf(true, false, false, true, true, true), batch.moveMask.toList())
        assertEquals(listOf(3f, 0f), batch.step.toList())
        assertEquals(listOf(3L, 5L, 0L, 1L, 1L), batch.tokens.slice(5 until 10))
        assertTrue(batch.tokens.slice(10 until 20).all { it == 0L }, "padding is zeros")
        // No position has a token: still one column, masked.
        val empty = NetInput.batch(listOf(NetPosition(emptyList(), listOf(intArrayOf(5, 0, 0, 0)), 1)))
        assertEquals(1, empty.tokenCount)
        assertEquals(listOf(false), empty.tokenMask.toList())
    }

    @Test
    fun movesAreRankedByLogitTiesKeptAndTheRestAfter() {
        assertEquals(listOf(2, 0, 3, 1), NetPrior.rank(floatArrayOf(1f, 0f, 2f, 1f), 4).toList(), "ties keep their place")
        assertEquals(listOf(1, 0, 2), NetPrior.rank(floatArrayOf(0f, 3f, Float.NaN), 3).toList(), "a NaN goes last")
        // Seventy moves: the network scores the first 64, the other six follow in their own order.
        val logits = FloatArray(NetInput.MAX_MOVES) { it.toFloat() }
        val order = NetPrior.rank(logits, 70).toList()
        assertEquals((63 downTo 0).toList() + (64 until 70).toList(), order)
    }

    @Test
    fun theNetworkOrdersButNeverChangesACompleteMapsBoards() {
        val hand = listOf(CALLER, CALLER, WALL)
        val plain = map(hand)
        // Two orders: the End Phase first (kind 4 scores highest) and the End Phase last.
        listOf<(IntArray) -> Float>({ it[0].toFloat() }, { -it[0].toFloat() }, { (it[1] * 7 + it[2]).toFloat() % 5 }).forEach { score ->
            val net = FakeNet(vocab.hash(), score = score)
            val ordered = map(hand, NetPrior(net, vocab))
            assertTrue(plain.complete && ordered.complete)
            assertEquals(0, ordered.priorRefused, "the network's order is always the same moves")
            assertEquals(plain.ends.map { it.key }, ordered.ends.map { it.key })
            assertEquals(plain.ends.map { it.traits }, ordered.ends.map { it.traits })
            assertTrue(net.runs > 0)
        }
    }

    @Test
    fun theStepIsTheSearchsDepth() {
        val net = FakeNet(vocab.hash())
        map(listOf(CALLER, CALLER, FROG, WALL), NetPrior(net, vocab))
        assertEquals(0f, net.steps.first(), "the deal's own table is step 0")
        assertTrue(net.steps.any { it > 0f })
    }

    @Test
    fun anOrderIsKeptForATableAskedAgain() {
        val net = FakeNet(vocab.hash())
        val prior = NetPrior(net, vocab)
        val t = MapDeal(listOf(CALLER, WALL)).table(main, emptyList(), kit)
        val moves = MapMoves.distinct(t, 0, true)
        val first = prior.order(t, 0, moves, 0)
        assertEquals(1, net.runs)
        assertEquals(first, prior.order(t, 0, moves, 0))
        assertEquals(1, net.runs, "the same table, seat and step: kept")
        prior.order(t, 0, moves, 1)
        assertEquals(2, net.runs, "another step is another input")
        assertEquals(moves.toSet(), first.toSet())
        assertEquals(moves.size, first.size)
    }

    @Test
    fun aNetworkOfAnotherVocabularyIsRefused() {
        val other = FakeNet("0123456789abcdef")
        assertFailsWith<IllegalArgumentException> { NetPrior(other, vocab) }
        assertNotNull(NetPrior.refusal(other, vocab.hash(), BoardTraits.HEADS))
        assertNotNull(NetPrior.refusal(FakeNet(vocab.hash(), heads = listOf("interruptions")), vocab.hash(), BoardTraits.HEADS))
        assertNull(NetPrior.refusal(FakeNet(vocab.hash()), vocab.hash(), BoardTraits.HEADS))
        assertEquals("net-3-${vocab.hash()}", NetPrior(FakeNet(vocab.hash(), version = 3), vocab).fingerprint)
    }
}
