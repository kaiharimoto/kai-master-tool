package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * What the trainer learns from (M.md §4.3): the traced maps written as the data contract `tools/mapper-train/README.md`
 * reads — `vocab.json`, `heads.json` and `records-*.jsonl`. The Kotlin side owns the game; the trainer only ever sees
 * these numbers, so a change to either side is a change to this contract and to its version.
 *
 * Both targets are measurements of the map, never anyone's ranking:
 * - **value**: for each trait, the most of it any end board still reachable from the position has;
 * - **policy**: for each move, how many distinct cells of the MAP-Elites grid ([EliteArchive]) its ends reach, normalised.
 *   A move that opens more kinds of board is a better move to try first, whatever the person's weights turn out to be.
 */

/** The cards the network knows, by index: 0 is padding, 1 an unknown card or a token, then the deck's cards sorted. */
class MapperVocab(cards: Collection<Int>) {
    val cards: List<Int> = cards.distinct().sorted()
    private val index: Map<Int, Int> = this.cards.withIndex().associate { it.value to it.index + FIRST }

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
    }
}

object TrainingExport {
    /** The record format's version (the `v` of each line). */
    const val VERSION = 1

    /** The zones of a token, in the contract's order. */
    enum class Zone { HAND, DECK, EXTRA, GY, BANISHED, MONSTER, SPELL, FIELD, EMZ, MATERIAL }

    /** The kinds of a move, in the contract's order. */
    enum class MoveKind { ACTIVATE, NORMAL_SUMMON, SET_MONSTER, PROCEDURE, PHASE_END, PASS_OR_RESOLVE }

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

    /** A move as `[kind, card, effect]`: the effect's place in its script, or the procedure's, from 1; 0 for none. */
    fun move(t: FxTable, m: FxMove, vocab: MapperVocab): List<Int> = when (m) {
        is FxMove.Activate -> listOf(
            MoveKind.ACTIVATE.ordinal, vocab.of(t.code(m.uid)),
            (t.script(m.uid)?.effects?.indexOfFirst { it.id == m.effect } ?: -1) + 1,
        )
        is FxMove.NormalSummon -> listOf((if (m.set) MoveKind.SET_MONSTER else MoveKind.NORMAL_SUMMON).ordinal, vocab.of(t.code(m.uid)), 0)
        is FxMove.Procedure -> listOf(MoveKind.PROCEDURE.ordinal, vocab.of(t.code(m.uid)), m.proc + 1)
        is FxMove.Phase -> listOf(if (m.to == DuelPhase.END) MoveKind.PHASE_END.ordinal else MoveKind.PASS_OR_RESOLVE.ordinal, 0, 0)
        FxMove.Pass, FxMove.Resolve -> listOf(MoveKind.PASS_OR_RESOLVE.ordinal, 0, 0)
    }

    /** The stable id of a deal, which the trainer hashes to keep a hand on one side of its train/validation split. */
    fun handId(deal: MapDeal): String = "${deal.hand.sorted().joinToString(".")}/${if (deal.first) "1st" else "2nd"}/${deal.seed}"

    /**
     * The records of one traced map ([MapSearch] with `trace = true`): a line a decision table whose moves reach any end, at
     * most [most] of them, in the order the search met them.
     */
    fun records(
        deal: MapDeal,
        mapped: MapSearch.Mapped,
        vocab: MapperVocab,
        heads: List<String> = BoardTraits.HEADS,
        axes: List<EliteAxis> = EliteArchive.DEFAULT_AXES,
        most: Int = MOST_RECORDS,
    ): List<String> {
        val cells = mapped.ends.map { e -> axes.map { it.bin(e.traits) ?: -1 } }
        val hand = handId(deal)
        val out = ArrayList<String>()
        for (n in mapped.nodes) {
            if (out.size >= most) break
            val all = n.reach.flatten().toSet()
            if (all.isEmpty()) continue
            val counts = n.reach.map { r -> r.map { cells[it] }.toSet().size.toDouble() }
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
