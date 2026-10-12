package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey
import com.kaiharimoto.mastertool.core.duel.mapper.MapperVocab
import com.kaiharimoto.mastertool.core.duel.mapper.MovePrior
import com.kaiharimoto.mastertool.core.duel.mapper.TrainingExport

/*
 * The trained network as the search's move prior (M.md §4.1, step M3). The search asks it for an order and nothing else: the
 * moves it is given come back every one, once, so a complete map's boards are the same whatever it says (`MapSearchTest`
 * holds the search to that; [NetPrior] never drops or adds a move). It reads a position as the trainer did
 * (`tools/mapper-train/README.md`): [TrainingExport]'s tokens and moves, the tokens cut to 128 with every non-DECK token
 * kept before any DECK token, the first 64 moves scored, `step` the moves made so far in the line.
 *
 * The network itself is a seam ([PriorNet]): ONNX Runtime on the desk, a fake in the tests, nothing on a phone yet.
 */

/** One position as the network reads it: tokens cut as the trainer cuts them, the moves it scores, and the step. */
class NetPosition(
    /** `[card, zone, owner, face, pos]` rows, at most [NetInput.MAX_TOKENS]. */
    val tokens: List<IntArray>,
    /** `[kind, card, effect, zone]` rows: the first [NetInput.MAX_MOVES] of the moves, in their order. */
    val moves: List<IntArray>,
    val step: Int,
)

/**
 * A batch, padded to its longest position and move list (at least one of each), as the ONNX graph's five inputs, row-major:
 * `tokens` [size × tokenCount × 5], `tokenMask` [size × tokenCount], `step` [size], `moves` [size × moveCount × 4],
 * `moveMask` [size × moveCount]. Padding rows are zeros with their mask false: the trainer's `collate`.
 */
class NetBatch(
    val size: Int,
    val tokenCount: Int,
    val moveCount: Int,
    val tokens: LongArray,
    val tokenMask: BooleanArray,
    val step: FloatArray,
    val moves: LongArray,
    val moveMask: BooleanArray,
)

/** What the network said of a batch: `policy_logits` [size × moveCount] and `value` [size × heads], in raw units. */
class NetOut(val policy: FloatArray, val value: FloatArray)

/**
 * A trained network (the ONNX file, M.md §4.4): what it was trained on, read from its metadata, and the graph to run. Its
 * [run] may be called from several map workers at once.
 */
interface PriorNet {
    /** Its place on the ladder ([TrainLadder]). */
    val version: Int

    /** The `vocab_hash` it was trained on: it is refused with any other vocabulary. */
    val vocabHash: String

    /** The value heads, in the order of its `value` output (`heads.json`). */
    val heads: List<String>

    /** Each head's training mean (`head_mean`): what predicting the mean says, version 0's value. */
    val headMean: List<Double>

    fun run(batch: NetBatch): NetOut

    /** Lets go of what it holds (ONNX Runtime's session); it is not run again after. */
    fun close() {}
}

object NetInput {
    const val MAX_TOKENS = 128
    const val MAX_MOVES = 64

    /** The token rows of a position, cut as the trainer cuts them: every non-DECK token, in order, before any DECK token. */
    fun cut(tokens: List<List<Int>>): List<List<Int>> {
        if (tokens.size <= MAX_TOKENS) return tokens
        val deck = TrainingExport.Zone.DECK.ordinal
        return (tokens.filter { it[1] != deck } + tokens.filter { it[1] == deck }).take(MAX_TOKENS)
    }

    /** Seat [seat]'s position on [t] with [moves] to choose from, [step] engine moves into the line. */
    fun encode(t: FxTable, seat: Int, step: Int, moves: List<FxMove>, vocab: MapperVocab): NetPosition = NetPosition(
        cut(TrainingExport.tokens(t, seat, vocab)).map { it.toIntArray() },
        moves.take(MAX_MOVES).map { TrainingExport.move(t, it, vocab).toIntArray() },
        step,
    )

    /** [positions] padded into one batch. */
    fun batch(positions: List<NetPosition>): NetBatch {
        val b = positions.size
        val t = maxOf(1, positions.maxOfOrNull { it.tokens.size } ?: 0)
        val m = maxOf(1, positions.maxOfOrNull { it.moves.size } ?: 0)
        val tokens = LongArray(b * t * TOKEN_FIELDS)
        val tokenMask = BooleanArray(b * t)
        val step = FloatArray(b)
        val moves = LongArray(b * m * MOVE_FIELDS)
        val moveMask = BooleanArray(b * m)
        positions.forEachIndexed { i, p ->
            p.tokens.forEachIndexed { j, row ->
                row.forEachIndexed { k, v -> tokens[(i * t + j) * TOKEN_FIELDS + k] = v.toLong() }
                tokenMask[i * t + j] = true
            }
            step[i] = p.step.toFloat()
            p.moves.forEachIndexed { j, row ->
                row.forEachIndexed { k, v -> moves[(i * m + j) * MOVE_FIELDS + k] = v.toLong() }
                moveMask[i * m + j] = true
            }
        }
        return NetBatch(b, t, m, tokens, tokenMask, step, moves, moveMask)
    }

    private const val TOKEN_FIELDS = 5
    private const val MOVE_FIELDS = 4
}

/**
 * [net] ordering a map's moves (M3): each position's scored moves by logit, highest first, a tie kept in the hand-written
 * order; moves past the network's 64 after the scored ones, in their own order. The same moves come back, every one, once.
 *
 * Orders are kept in a bounded cache, a slot a table (by [TableKey], the seat and the step), each slot replaced whole and
 * checked against the moves asked about before it is used: safe to share between the map's workers, at worst asking the
 * network twice.
 */
class NetPrior(private val net: PriorNet, private val vocab: MapperVocab, slots: Int = SLOTS) : MovePrior {
    init {
        require(net.vocabHash == vocab.hash()) { "network ${net.version} was trained on vocabulary ${net.vocabHash}, not ${vocab.hash()}" }
    }

    /** What the records say ordered their maps ([TrainingExport.records]' `prior`): the network's version and vocabulary. */
    val fingerprint: String = fingerprint(net)

    private class Slot(val a: Long, val b: Long, val seat: Int, val step: Int, val moves: List<FxMove>, val order: IntArray)

    private val cache = arrayOfNulls<Slot>(slots.coerceAtLeast(1))

    override fun order(t: FxTable, seat: Int, moves: List<FxMove>): List<FxMove> = order(t, seat, moves, 0)

    override fun order(t: FxTable, seat: Int, moves: List<FxMove>, step: Int): List<FxMove> {
        if (moves.size < 2) return moves
        val key = TableKey(t, false).key()
        val at = ((key.a xor (key.b * 31) xor (seat.toLong() shl 20) xor step.toLong()).mod(cache.size.toLong())).toInt()
        cache[at]?.let { s -> if (s.a == key.a && s.b == key.b && s.seat == seat && s.step == step && s.moves == moves) return s.order.map { moves[it] } }
        val out = net.run(NetInput.batch(listOf(NetInput.encode(t, seat, step, moves, vocab))))
        val order = rank(out.policy, moves.size)
        cache[at] = Slot(key.a, key.b, seat, step, moves, order)
        return order.map { moves[it] }
    }

    companion object {
        /** Tables whose order is kept. */
        const val SLOTS = 4096

        /** The `prior` a record names for maps [net] ordered: "net-<version>-<vocab hash>". */
        fun fingerprint(net: PriorNet): String = "net-${net.version}-${net.vocabHash}"

        /**
         * The order of [count] moves from the logits of the first row of [policy]: the first [NetInput.MAX_MOVES] by logit,
         * highest first, ties by their place; the rest after them in their place. Every index once.
         */
        fun rank(policy: FloatArray, count: Int): IntArray {
            val scored = minOf(count, NetInput.MAX_MOVES, policy.size)
            val top = (0 until scored).sortedWith(compareByDescending<Int> { policy[it].let { v -> if (v.isNaN()) Float.NEGATIVE_INFINITY else v } }.thenBy { it })
            return (top + (scored until count)).toIntArray()
        }

        /**
         * Why [net] may not order maps of a run with [vocabHash] and [heads] (the README's rule: a model whose `vocab_hash`
         * or `heads` differ from the run it is used with is refused), or null when it may.
         */
        fun refusal(net: PriorNet, vocabHash: String, heads: List<String>): String? = when {
            net.vocabHash != vocabHash -> "Network ${net.version} was trained on another version of the deck's cards (vocabulary ${net.vocabHash}, now $vocabHash): " +
                "the next round trains one on the deck as it is."
            net.heads != heads -> "Network ${net.version} predicts ${net.heads.joinToString()}, not ${heads.joinToString()}."
            else -> null
        }
    }
}
