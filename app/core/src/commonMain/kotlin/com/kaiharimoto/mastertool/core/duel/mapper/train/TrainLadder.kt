package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The ladder of a deck's networks (M.md §4.5, step M3; `train/ladder.json`): every network trained for the deck, its search
 * rating with its range, each gate's verdict with every number it read, which one is in use, and how many candidates were
 * tried since the last promotion (the gate's bar shares its 5 % among them). The hand-written order is version 0 at
 * [Elo.BASE]. A version that fails the gate stays a file and a row; the one in use changes only on a pass.
 *
 * This device's own, under `train/`, as the networks are: a ladder that travelled without its networks would name versions
 * another device does not have.
 */

/** One network trained for the deck: its file, what it was trained on, how it was judged and rated. */
@Serializable
data class NetVersion(
    val version: Int,
    /** Its ONNX file, under the deck's `train/`. */
    val file: String = "",
    val vocabHash: String = "",
    val heads: List<String> = emptyList(),
    val tier: String = "",
    /** The round that trained it, and the records it trained on. */
    val round: Int = 0,
    val records: Int = 0,
    /** Its rating, from its parent's and the gate's measure; null until judged. */
    val rated: Rated? = null,
    val promoted: Boolean = false,
    /** The gate set it was judged on ([GateSet.print]); its [recall] and [valueError] are that set's. */
    val gateSet: String = "",
    /** Its front recall on each gate hand, by the hand's key: kept so the next candidate is judged against it without mapping again. */
    val recall: Map<String, Double> = emptyMap(),
    /** Each value head's mean absolute error on the gate hands. */
    val valueError: Map<String, Double> = emptyMap(),
    val at: Long = 0L,
)

/** A gate's verdict as kept ([GateVerdict], with what it judged against): every number it read, never a hand of the gate. */
@Serializable
data class GateRecord(
    val candidate: Int,
    /** The version it was judged against: the one in use, or 0 (the hand-written order). */
    val against: Int = 0,
    val promote: Boolean = false,
    val wins: Int = 0,
    val losses: Int = 0,
    val ties: Int = 0,
    val p: Double = 1.0,
    val bar: Double = TrainGate.ALPHA,
    val score: Double = 0.5,
    val low: Double = 0.0,
    val high: Double = 1.0,
    val elo: Double = 0.0,
    val eloLow: Double = 0.0,
    val eloHigh: Double = 0.0,
    val regressed: Int = 0,
    val worseHeads: List<String> = emptyList(),
    /** The candidate's front recall over the gate hands (the mean of each hand's area), and the one judged against's. */
    val recall: Double = 0.0,
    val againstRecall: Double = 0.0,
    val valueError: Map<String, Double> = emptyMap(),
    val againstError: Map<String, Double> = emptyMap(),
    val hands: Int = 0,
    val budget: Int = 0,
    /** Candidates tried since the last promotion, this one included: what the bar was shared among. */
    val attempts: Int = 1,
    val why: String = "",
    val at: Long = 0L,
) {
    companion object {
        fun of(
            candidate: Int,
            against: Int,
            v: GateVerdict,
            measured: Measured,
            againstRecall: Map<String, Double>,
            againstError: Map<String, Double>,
            budget: Int,
            attempts: Int,
            at: Long,
        ): GateRecord {
            fun finite(x: Double, d: Double = 0.0) = if (x.isFinite()) x else d
            fun mean(m: Map<String, Double>) = m.values.filter { it.isFinite() }.let { if (it.isEmpty()) 0.0 else it.average() }
            return GateRecord(
                candidate, against, v.promote, v.wins, v.losses, v.ties, finite(v.p, 1.0), finite(v.bar), finite(v.score, 0.5), finite(v.low),
                finite(v.high, 1.0), finite(v.elo), finite(v.eloLow), finite(v.eloHigh), v.regressed.size, v.worseHeads,
                mean(measured.recall), mean(againstRecall),
                measured.valueError.filterValues { it.isFinite() }, againstError.filterValues { it.isFinite() },
                v.wins + v.losses + v.ties, budget, attempts, v.why, at,
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TrainLadder(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION,
    val deckId: String = "",
    /** Every network trained, version 1 on; version 0 is the hand-written order and is never a row. */
    val versions: List<NetVersion> = emptyList(),
    /** The version in use: 0 until a network passes the gate. */
    val inUse: Int = 0,
    /** Candidates judged since the last promotion: the gate's 5 % is shared among them and the next. */
    val attempts: Int = 0,
    /** Every gate's verdict, oldest first. */
    val gates: List<GateRecord> = emptyList(),
) {
    fun byVersion(v: Int): NetVersion? = versions.firstOrNull { it.version == v }

    /** [v]'s rating: version 0 at [Elo.BASE], a judged version at its own, else null. */
    fun rated(v: Int): Rated? = if (v == 0) Rated(0, Elo.BASE, Elo.BASE, Elo.BASE) else byVersion(v)?.rated

    /** The number the next network takes. */
    fun next(): Int = (versions.maxOfOrNull { it.version } ?: 0) + 1

    /** [made] added, not yet judged (its file kept, whatever the gate says later). */
    fun made(made: NetVersion): TrainLadder = copy(versions = versions.filterNot { it.version == made.version } + made)

    /**
     * [candidate] judged by [record]: its rating from the version it was judged against (its parent); in use from now on only
     * when the gate passed, and the count of candidates tried starts again then. A failed candidate stays a row and a file.
     */
    fun judged(candidate: NetVersion, record: GateRecord, verdict: GateVerdict): TrainLadder {
        val parent = rated(record.against) ?: rated(0)!!
        val rated = TrainGate.rate(parent, candidate.version, verdict, record.hands, record.at)
        val kept = candidate.copy(rated = rated, promoted = record.promote)
        return copy(
            versions = versions.filterNot { it.version == kept.version } + kept,
            inUse = if (record.promote) kept.version else inUse,
            attempts = if (record.promote) 0 else attempts + 1,
            gates = gates + record,
        )
    }

    /** The newest gate's verdict, or null. */
    val lastGate: GateRecord? get() = gates.lastOrNull()

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; allowSpecialFloatingPointValues = true }

        /** Read forgivingly; an unreadable file is null (and is never written over by reading it). */
        fun decode(text: String): TrainLadder? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}
