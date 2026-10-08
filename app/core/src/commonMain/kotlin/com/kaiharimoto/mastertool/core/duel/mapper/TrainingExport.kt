package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.TableKey
import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * What the trainer learns from (M.md §4.3): the traced maps written as the data contract `tools/mapper-train/README.md`
 * reads — `vocab.json`, `heads.json` and `records-*.jsonl`. The Kotlin side owns the game; the trainer only ever sees
 * these numbers, so a change to either side is a change to this contract and to its version.
 *
 * Both targets are measurements of the map, never anyone's ranking:
 * - **policy**: for each move, the share of the position's own Pareto front its ends reach — the boards below the position
 *   that no other board below it beats on every trait where more is plainly better ([BoardTraits.MORE_IS_BETTER] and every
 *   stress key). The best board for any weights a player could set without preferring less of a good thing lies on that
 *   front, so the target needs no weights, and no grid.
 * - **value**: for each trait, the most of it any end board still reachable from the position has. Each head is its own
 *   maximum (the most interruptions and the most cards kept may be two boards): it guides where to look, and nothing is
 *   ever ruled out by it.
 *
 * **An incomplete map writes nothing**, nor one that ever cut a table at the depth bound (`Mapped.reachExact`). Its tables
 * are the subtrees the order it was searched in reached first, and a prior trained on them would learn its own taste back.
 */

/**
 * The cards the network knows, by index: 0 is padding, 1 an unknown card or a token, then the deck's cards. **Only ever
 * appended to** ([of] with the deck's last vocabulary): a card added to the deck takes the next index and no other card's
 * index moves, so the trained weights carry over a deck change (the trainer copies the old rows, M.md §4.4).
 */
class MapperVocab private constructor(val cards: List<Int>) {
    private val index: Map<Int, Int> = cards.withIndex().associate { it.value to it.index + FIRST }

    /** [code]'s index; [UNKNOWN] for a card the deck does not hold (a token, the other seat's). */
    fun of(code: Int?): Int = code?.let { index[it] } ?: UNKNOWN

    /** `vocab.json`. Index i of `cards` is vocabulary index i: 0 for padding, -1 for unknown, then the passcodes. */
    fun json(): String = buildJsonObject {
        put("version", 1)
        putJsonArray("cards") {
            add(JsonPrimitive(0))
            add(JsonPrimitive(-1))
            this@MapperVocab.cards.forEach { add(JsonPrimitive(it)) }
        }
    }.toString()

    /** The hash the trainer stamps into a checkpoint: the first 16 hex of `vocab.json`'s SHA-256. */
    fun hash(): String = Sha256.hex(json()).take(16)

    companion object {
        const val PAD = 0
        const val UNKNOWN = 1
        private const val FIRST = 2

        /** [previous]'s cards in their order (a card that left the deck keeps its index), then [cards] it lacks, sorted. */
        fun of(cards: Collection<Int>, previous: List<Int> = emptyList()): MapperVocab {
            val kept = previous.distinct()
            val known = kept.toSet()
            return MapperVocab(kept + cards.distinct().filterNot { it in known }.sorted())
        }

        /** The cards of a `vocab.json` written before, or null when it is not one. */
        fun read(json: String): List<Int>? = runCatching {
            Json.parseToJsonElement(json).jsonObject.getValue("cards").jsonArray.map { it.jsonPrimitive.int }.drop(FIRST)
        }.getOrNull()
    }
}

object TrainingExport {
    /** The record format's version (the `v` of each line). */
    const val VERSION = 1

    /** The zones of a token, in the contract's order. */
    enum class Zone { HAND, DECK, EXTRA, GY, BANISHED, MONSTER, SPELL, FIELD, EMZ, MATERIAL }

    /** The kinds of a move, in the contract's order. */
    enum class MoveKind { ACTIVATE, NORMAL_SUMMON, SET_MONSTER, PROCEDURE, PHASE_END, PASS_OR_RESOLVE }

    /** The heads to train: [previous]'s in their order, then those of [current] it lacks. Only ever appended to, as the vocabulary is. */
    fun grownHeads(current: List<String>, previous: List<String> = emptyList()): List<String> =
        previous.distinct() + current.distinct().filterNot { it in previous }

    /** `heads.json`: the value heads, [BoardTraits.HEADS] then any stress keys. */
    fun heads(heads: List<String> = BoardTraits.HEADS): String = buildJsonObject {
        put("version", 1)
        putJsonArray("traits") { heads.forEach { add(JsonPrimitive(it)) } }
    }.toString()

    /** A position as tokens `[card, zone, owner, face, pos]` seen by [seat], sorted so the same table writes the same line. */
    fun tokens(t: FxTable, seat: Int, vocab: MapperVocab): List<List<Int>> {
        val s = t.state
        val out = ArrayList<List<Int>>(s.cards.size)
        s.cards.values.forEach { c ->
            val place = s.placeOf(c.uid) ?: return@forEach
            val (zone, side) = when (place) {
                is Place.Zone -> when (place.kind) {
                    ZoneKind.MONSTER -> Zone.MONSTER
                    ZoneKind.SPELL -> Zone.SPELL
                    ZoneKind.FIELD -> Zone.FIELD
                    ZoneKind.EMZ -> Zone.EMZ
                } to (if (place.kind == ZoneKind.EMZ) c.controller else place.seat)
                is Place.Pile -> when (place.kind) {
                    PileKind.HAND -> Zone.HAND
                    PileKind.DECK -> Zone.DECK
                    PileKind.EXTRA -> Zone.EXTRA
                    PileKind.GY -> Zone.GY
                    PileKind.BANISHED -> Zone.BANISHED
                } to place.seat
                is Place.Under -> Zone.MATERIAL to (s.cards[place.host]?.controller ?: c.owner)
                Place.Void -> return@forEach
            }
            val monster = zone == Zone.MONSTER || zone == Zone.EMZ
            val pos = if (!monster) 0 else if (c.pos == CardPosition.FACE_UP_ATK || c.pos == CardPosition.FACE_DOWN_ATK) 1 else 2
            out += listOf(vocab.of(t.code(c.uid)), zone.ordinal, if (side == seat) 0 else 1, if (c.faceUp) 1 else 0, pos)
        }
        return out.sortedWith { a, b -> a.indices.firstNotNullOfOrNull { i -> (a[i] - b[i]).takeIf { it != 0 } } ?: 0 }
    }

    /**
     * A move as `[kind, card, effect, zone]`: the effect's place in its script, or the procedure's, from 1 (0 for none), and
     * where the card is as the move is made — the same effect of a copy in the hand and of one in the GY are two moves.
     */
    fun move(t: FxTable, m: FxMove, vocab: MapperVocab): List<Int> {
        fun zone(uid: Int): Int = zoneOf(t, uid)?.ordinal ?: 0
        return when (m) {
            is FxMove.Activate -> listOf(
                MoveKind.ACTIVATE.ordinal, vocab.of(t.code(m.uid)),
                (t.script(m.uid)?.effects?.indexOfFirst { it.id == m.effect } ?: -1) + 1, zone(m.uid),
            )
            is FxMove.NormalSummon -> listOf((if (m.set) MoveKind.SET_MONSTER else MoveKind.NORMAL_SUMMON).ordinal, vocab.of(t.code(m.uid)), 0, zone(m.uid))
            is FxMove.Procedure -> listOf(MoveKind.PROCEDURE.ordinal, vocab.of(t.code(m.uid)), m.proc + 1, zone(m.uid))
            is FxMove.Phase -> listOf(if (m.to == DuelPhase.END) MoveKind.PHASE_END.ordinal else MoveKind.PASS_OR_RESOLVE.ordinal, 0, 0, 0)
            FxMove.Pass, FxMove.Resolve -> listOf(MoveKind.PASS_OR_RESOLVE.ordinal, 0, 0, 0)
        }
    }

    private fun zoneOf(t: FxTable, uid: Int): Zone? = when (val p = t.state.placeOf(uid)) {
        is Place.Zone -> when (p.kind) {
            ZoneKind.MONSTER -> Zone.MONSTER
            ZoneKind.SPELL -> Zone.SPELL
            ZoneKind.FIELD -> Zone.FIELD
            ZoneKind.EMZ -> Zone.EMZ
        }
        is Place.Pile -> when (p.kind) {
            PileKind.HAND -> Zone.HAND
            PileKind.DECK -> Zone.DECK
            PileKind.EXTRA -> Zone.EXTRA
            PileKind.GY -> Zone.GY
            PileKind.BANISHED -> Zone.BANISHED
        }
        is Place.Under -> Zone.MATERIAL
        else -> null
    }

    /**
     * The id the trainer splits on (M.md §4.3): the starting cards, sorted, the fodder dealt with them, and the seat order —
     * never the seed, so one hand dealt over two deck orders lands on one side of the split.
     */
    fun handId(deal: MapDeal): String {
        val fodder = if (deal.fodder.isEmpty()) "" else "+" + deal.fodder.sorted().joinToString(".")
        return "${deal.hand.sorted().joinToString(".")}$fodder/${if (deal.first) "1st" else "2nd"}"
    }

    /** A table's identity, for the trainer to tell positions it trained on from those it did not. */
    private fun position(t: FxTable): String = TableKey(t, false).key().let { it.a.toULong().toString(16) + it.b.toULong().toString(16) }

    /**
     * The records of one traced map ([MapSearch] with `trace = true`): a line a decision table whose moves reach any end, at
     * most [most] of them, in the order the search met them. [prior] names the order the map was searched in ("none" for the
     * hand-written one), so the trainer can tell its own echo. An incomplete map writes nothing.
     */
    fun records(
        deal: MapDeal,
        mapped: MapSearch.Mapped,
        vocab: MapperVocab,
        heads: List<String> = BoardTraits.HEADS,
        prior: String = "none",
        most: Int = MOST_RECORDS,
    ): List<String> {
        if (!mapped.reachExact) return emptyList()
        val dims = BoardTraits.MORE_IS_BETTER + mapped.ends.flatMap { it.traits.through.keys }.distinct().sorted().map { BoardTraits.THROUGH + it }
        val points = mapped.ends.map { e -> DoubleArray(dims.size) { e.traits[dims[it]] ?: Double.NEGATIVE_INFINITY } }
        val hand = handId(deal)
        val out = ArrayList<String>()
        for (n in mapped.nodes) {
            if (out.size >= most) break
            val all = n.reach.flatMap { it.asIterable() }.toSet()
            if (all.isEmpty()) continue
            val front = Pareto.front(all.sorted().map { points[it] }).let { f -> all.sorted().filterIndexed { i, _ -> i in f }.toSet() }
            val counts = n.reach.map { r -> r.count { it in front }.toDouble() }
            val total = counts.sum()
            val value = buildJsonObject {
                heads.forEach { h ->
                    val best = all.mapNotNull { mapped.ends[it].traits[h] }.maxOrNull() ?: return@forEach
                    put(h, best)
                }
            }
            out += buildJsonObject {
                put("v", VERSION)
                put("hand", hand)
                put("pos", position(n.table))
                put("prior", prior)
                put("step", n.step)
                put("tokens", JsonArray(tokens(n.table, n.seat, vocab).map { row -> JsonArray(row.map(::JsonPrimitive)) }))
                put("moves", JsonArray(n.moves.map { m -> JsonArray(move(n.table, m, vocab).map(::JsonPrimitive)) }))
                putJsonArray("policy") { counts.forEach { add(JsonPrimitive(it / total)) } }
                put("value", value)
            }.toString()
        }
        return out
    }

    /** At most this many records from one map: a big map is mostly the same tables reordered. */
    const val MOST_RECORDS = 2_000
}
