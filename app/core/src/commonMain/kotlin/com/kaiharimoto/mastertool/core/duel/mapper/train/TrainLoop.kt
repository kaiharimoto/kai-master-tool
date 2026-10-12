@file:OptIn(ExperimentalAtomicApi::class)

package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import com.kaiharimoto.mastertool.core.duel.mapper.MapperVocab
import com.kaiharimoto.mastertool.core.duel.mapper.MovePrior
import com.kaiharimoto.mastertool.core.duel.mapper.TrainingExport
import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/*
 * The self-play loop (M.md §4.3–4.5, step M3): a round deals hands, maps them with the network in use (a fixed share with no
 * prior, so it never trains only on its own echo), writes what the trainer reads, trains, exports, and puts the candidate
 * through the gate — promoted on a pass, kept as a file on a fail. Pure over its seams: the trainer's process
 * ([TrainHelper]), ONNX Runtime ([NetLoader]) and the deck's `train/` folder ([TrainStore]), so the whole round runs in a
 * test with fakes.
 *
 * Every step can stop between two others and carry on from a small state file (`train/loop.json`, [TrainState]): mapping is
 * made again whole, training carries on from its checkpoint (`--resume`), export and the gate are made again.
 */

/** A deck's training files (`<data>/effects/mapper/<deck>/train/`), by their path under it. */
interface TrainStore {
    fun read(rel: String): String?

    /** [text] written whole: a reader never sees half a file. */
    fun write(rel: String, text: String)

    /** A file, or a folder and all it holds. */
    fun delete(rel: String)

    /** The names in a folder; empty when there is none. */
    fun list(rel: String): List<String>

    /** Where the trainer's process finds [rel]. */
    fun path(rel: String): String
}

/** What the trainer's export said (`python -m mapper_train.export`'s one JSON line). */
data class Exported(
    val ok: Boolean,
    val vocabHash: String = "",
    val heads: List<String> = emptyList(),
    val tier: String = "",
    val bytes: Long = 0L,
    val error: String = "",
) {
    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The export's result line; an error line or anything else is a failure with its words. */
        fun parse(line: String): Exported {
            val o = runCatching { JSON.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return Exported(false, error = line.trim().take(300))
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (s("event") != "exported") return Exported(false, error = s("message").ifBlank { line.trim().take(300) })
            val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull == true
            return Exported(
                ok, s("vocab_hash"), s("heads").split(',').filter { it.isNotEmpty() }, s("tier"), (o["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L,
                if (ok) "" else "The exported network did not match PyTorch's within its tolerance (fp32 ${(o["fp32_max_abs_diff"] as? JsonPrimitive)?.content}).",
            )
        }
    }
}

/** The trainer's process (`tools/mapper-train`): the person's own Python on the desk; a fake in the tests. */
interface TrainHelper {
    /**
     * Trains on the run folder [data] into [out] at [tier] with [config]; [resume] carries on from [out]'s checkpoint. Each line
     * the trainer prints goes to [event] as it comes; [stop] is asked while it runs, and true pauses it cleanly (the stop file).
     * Returns the last [TrainEvent.Done] or [TrainEvent.Failed], or null when it ended saying neither.
     */
    suspend fun train(data: String, out: String, tier: ModelTier, config: TrainConfig, resume: Boolean, stop: () -> Boolean, event: (TrainEvent) -> Unit): TrainEvent?

    /** [checkpoint] exported to the ONNX file [out], checked against PyTorch. */
    suspend fun export(checkpoint: String, out: String): Exported
}

/** ONNX Runtime: a network file loaded as [version], or why not (on a phone: not in this release). */
fun interface NetLoader {
    fun load(path: String, version: Int): Result<PriorNet>
}

/** A line of the run's log: who did what (the person, Ai, the loop), and when. Every action Ai takes in a run is one. */
@Serializable
data class TrainNote(val at: Long = 0L, val by: String = TrainState.LOOP, val what: String = "")

/** How much a round maps and the gate measures (M3's sizes); a test makes them tiny. */
data class TrainSizes(
    /** Hands dealt a round, half going first and half second. */
    val hands: Int = 200,
    /**
     * Engine moves a round's map may spend: more than the page's own runs' (20,000), since only a map that finished writes a
     * record, and a deck whose every card is written finishes few at that (the bench deck: about one hand in seven at 40,000).
     */
    val budget: Int = 60_000,
    /** Records one map writes, and a round at most. */
    val recordsPerMap: Int = 300,
    val recordsPerRound: Int = 60_000,
    /** Rounds whose records the trainer reads: the newest, so old maps made in an old order fade out. */
    val window: Int = 8,
    val gateHands: Int = GateSet.HANDS,
    val gateBudget: Int = GateSet.GATE_BUDGET,
    val exhaustive: Int = GateSet.EXHAUSTIVE_BUDGET,
)

/**
 * Where the loop stands (`train/loop.json`): the round, its step, the version it makes, the settings Ai may tune within
 * [TrainConfig.bounded], and the run's log. Versioned and read forgivingly, as every stored shape is.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TrainState(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val version: Int = VERSION,
    val deckId: String = "",
    /** The round begun last; the step it is at is [phase]. */
    val round: Int = 0,
    val phase: String = IDLE,
    /** The version this round makes. */
    val candidate: Int = 0,
    /** Training began this round: carrying on is `--resume`. */
    val trained: Boolean = false,
    /** Records this round wrote. */
    val records: Int = 0,
    /** The training settings: Ai's `train_tune` within [TrainConfig.bounded]; the batch and minutes are the machine's plan. */
    val config: TrainConfig = TrainConfig(),
    /** What the last step said. */
    val said: String = "",
    val log: List<TrainNote> = emptyList(),
    val at: Long = 0L,
) {
    fun noted(by: String, what: String, at: Long): TrainState = copy(log = (log + TrainNote(at, by, what)).takeLast(LOG))

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1
        const val IDLE = "idle"
        const val MAPPING = "mapping"
        const val TRAINING = "training"
        const val EXPORTING = "exporting"
        const val GATING = "gating"

        const val PERSON = "person"
        const val AI = "ai"
        const val LOOP = "loop"

        /** Lines the log keeps. */
        const val LOG = 200

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; allowSpecialFloatingPointValues = true }

        fun decode(text: String): TrainState? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()

        /** A step in words. */
        fun words(phase: String): String = when (phase) {
            MAPPING -> "Mapping hands"
            TRAINING -> "Training"
            EXPORTING -> "Exporting the network"
            GATING -> "At the gate"
            else -> "Idle"
        }
    }
}

/**
 * One deck's training (M3). [round] runs a whole round from wherever the last one stopped; [gate] judges the candidate that
 * waits for it. [told] hears what is happening as it happens (from the map workers' threads too).
 */
class TrainLoop(
    private val deck: GoldfishDeck,
    private val kit: GoldfishKit,
    private val store: TrainStore,
    private val helper: TrainHelper,
    private val loader: NetLoader,
    private val plan: HardwarePlan,
    private val sizes: TrainSizes = TrainSizes(),
    private val confirmed: List<Confirmed> = emptyList(),
    private val now: () -> Long = { 0L },
    private val told: (Progress) -> Unit = {},
) {
    /** What is happening: a step, how far it has got ([done] of [total], 0 when not counted), and the trainer's latest line. */
    class Progress(val phase: String, val done: Int = 0, val total: Int = 0, val event: TrainEvent? = null, val words: String = "")

    /** How a call ended: a verdict, a pause (carried on later), a failure (said), or nothing to do. */
    class Outcome(val kind: Kind, val words: String, val gate: GateRecord? = null) {
        enum class Kind { JUDGED, PAUSED, FAILED, NOTHING }
    }

    private val workers = plan.workers.coerceAtLeast(1)

    /** The state kept, or a fresh one; null when the file is there and this build cannot read it. */
    fun state(): TrainState? = store.read(STATE).let { t -> if (t == null) TrainState(deckId = deck.id.orEmpty()) else TrainState.decode(t) }

    /** The ladder kept, or a fresh one; null when the file is there and this build cannot read it. */
    fun ladder(): TrainLadder? = store.read(LADDER).let { t -> if (t == null) TrainLadder(deckId = deck.id.orEmpty()) else TrainLadder.decode(t) }

    /** The vocabulary: the run's last, with the deck's new cards appended (never one moved). */
    fun vocab(): MapperVocab {
        val previous = store.read(VOCAB)?.let(MapperVocab::read).orEmpty()
        return MapperVocab.of((deck.main + deck.extra).map(kit::canonical), previous)
    }

    /** The value heads: the run's last, with any new one appended. */
    fun heads(): List<String> = TrainingExport.grownHeads(BoardTraits.HEADS, store.read(HEADS)?.let(TrainingExport::readHeads).orEmpty())

    /** The gate set kept when it is the deck's as it is, else made again (and kept). Null when [stop] ended it. */
    suspend fun gateSet(stop: () -> Boolean = { false }): GateSet? {
        val print = GateSet.print(deck, kit, sizes.gateHands, sizes.gateBudget, sizes.exhaustive)
        store.read(GATE)?.let(GateSet::decode)?.takeIf { it.print == print && it.version == GateSet.VERSION }?.let { return it }
        val mapped = AtomicInt(0)
        told(Progress(GATE_BUILD, words = "Mapping the held-out hands, each exhaustively"))
        val set = GateSet.build(deck, kit, sizes.gateHands, sizes.gateBudget, sizes.exhaustive, workers, stop) {
            told(Progress(GATE_BUILD, mapped.incrementAndFetch(), 0, words = "Mapping the held-out hands, each exhaustively"))
        } ?: return null
        store.write(GATE, set.encode())
        return set
    }

    /** [what] written to the run's log as [by]'s. */
    fun note(by: String, what: String) {
        val st = state() ?: return
        store.write(STATE, st.noted(by, what, now()).encode())
    }

    /** The training settings set to [config], held inside [TrainConfig.bounded], as [by]'s (Ai's `train_tune`). */
    fun tune(config: TrainConfig, by: String): TrainConfig? {
        val st = state() ?: return null
        val c = config.bounded()
        store.write(STATE, st.copy(config = c).noted(by, "Set the training: ${c.json()}", now()).encode())
        return c
    }

    /** One round from wherever the last stopped, through the gate. */
    suspend fun round(stop: () -> Boolean = { false }): Outcome {
        val ladder = ladder() ?: return unreadable(LADDER)
        var st = state() ?: return unreadable(STATE)
        if (st.phase == TrainState.IDLE) {
            st = st.copy(round = st.round + 1, phase = TrainState.MAPPING, candidate = ladder.next(), trained = false, records = 0, said = "", at = now())
                .noted(TrainState.LOOP, "Round ${st.round + 1} began: network ${ladder.next()}", now())
            save(st)
        }
        val set = gateSet(stop) ?: return paused(st, "Stopped while the held-out hands were mapped: they are mapped again on Resume.")
        if (st.phase == TrainState.MAPPING) {
            val vocab = vocab()
            val heads = heads()
            writeIfChanged(VOCAB, vocab.json())
            writeIfChanged(HEADS, TrainingExport.heads(heads))
            val (inUse, why) = inUse(ladder, vocab, heads)
            val lines = try {
                mapRound(st.round, set.heldOut, inUse, vocab, heads, stop)
            } finally {
                closeInUse()
            } ?: return paused(st, "Stopped while mapping: the round's hands are mapped again on Resume.")
            // The audit (M.md §4.6): no record of a gate hand is ever written.
            val leaks = set.heldOut.leaks(lines)
            check(leaks.isEmpty()) { "a record of a held-out hand: ${leaks.size}" }
            if (lines.isEmpty()) {
                save(st.copy(phase = TrainState.IDLE, said = "No map of round ${st.round} was complete within its budget: nothing to train on."))
                return Outcome(Outcome.Kind.NOTHING, "No map of round ${st.round} was complete within its budget: nothing to train on.")
            }
            store.write(records(st.round), lines.joinToString("\n", postfix = "\n"))
            store.list(RUN).mapNotNull { n -> ROUND_FILE.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it <= st.round - sizes.window }?.let { n } }
                .forEach { store.delete("$RUN/$it") }
            st = st.copy(phase = TrainState.TRAINING, records = lines.size, said = "Mapped round ${st.round}: ${lines.size} records" + (why?.let { ". $it" } ?: "."))
                .noted(TrainState.LOOP, "Mapped round ${st.round}: ${lines.size} records", now())
            save(st)
        }
        if (stop()) return paused(st, "Paused before training: Resume carries on.")
        if (st.phase == TrainState.TRAINING) {
            val out = roundDir(st.round)
            val resume = st.trained
            if (!resume) store.delete(out)
            st = st.copy(trained = true)
            save(st)
            val config = st.config.copy(batch = plan.batch, maxMinutes = plan.minutes).bounded()
            val end = helper.train(store.path(RUN), store.path(out), plan.tier, config, resume, stop) { e -> told(Progress(TrainState.TRAINING, event = e)) }
            when {
                end is TrainEvent.Failed -> return failed(st, "Training failed: ${end.error}")
                end !is TrainEvent.Done -> return failed(st, "The trainer ended without saying it was done.")
                end.stopped -> return paused(st, "Paused while training: Resume carries on from the checkpoint.")
                !end.finished -> return paused(st, "The round ran out of its ${config.maxMinutes} minutes: Resume carries on from the checkpoint.")
            }
            st = st.copy(phase = TrainState.EXPORTING).noted(TrainState.LOOP, "Trained round ${st.round}", now())
            save(st)
        }
        if (stop()) return paused(st, "Paused before export: Resume carries on.")
        if (st.phase == TrainState.EXPORTING) {
            told(Progress(TrainState.EXPORTING, words = "Exporting network ${st.candidate} and checking it against PyTorch"))
            val file = net(st.candidate)
            val x = helper.export(store.path("${roundDir(st.round)}/$CHECKPOINT"), store.path(file))
            if (!x.ok) return failed(st, "The export failed: ${x.error}")
            // Held to the files the round was trained on, whatever the deck has become since.
            val runVocab = store.read(VOCAB)?.let { Sha256.hex(it).take(16) }
            val runHeads = store.read(HEADS)?.let(TrainingExport::readHeads)
            if (x.vocabHash != runVocab || x.heads != runHeads) {
                return failed(st, "The exported network was trained on another vocabulary or other heads than the run's (${x.vocabHash}, $runVocab).")
            }
            val ladder2 = ladder() ?: return unreadable(LADDER)
            store.write(LADDER, ladder2.made(NetVersion(st.candidate, file, x.vocabHash, x.heads, x.tier, st.round, st.records, at = now())).encode())
            st = st.copy(phase = TrainState.GATING).noted(TrainState.LOOP, "Exported network ${st.candidate} (${x.bytes / 1024} KB)", now())
            save(st)
        }
        if (stop()) return paused(st, "Paused before the gate: Resume or Judge carries on.")
        return gate(stop)
    }

    /**
     * The candidate waiting at the gate judged on the held-out hands (M.md §4.5): against the network in use when it runs on
     * this run, else the hand-written order; promoted only on a pass.
     */
    suspend fun gate(stop: () -> Boolean = { false }): Outcome {
        var st = state() ?: return unreadable(STATE)
        if (st.phase != TrainState.GATING) return Outcome(Outcome.Kind.NOTHING, "No network waits for the gate: a round ends there by itself.")
        val ladder = ladder() ?: return unreadable(LADDER)
        val made = ladder.byVersion(st.candidate) ?: return failed(st, "Network ${st.candidate} is not on the ladder: export it again.")
        val vocab = vocab()
        val heads = heads()
        // The deck changed since the round was mapped: the candidate reads another deck's cards, so it is never judged on
        // this one's hands. It stays a file, and the next round trains on the deck as it is.
        if (made.vocabHash != vocab.hash() || made.heads != heads) {
            val words = "The deck changed since network ${made.version} was trained: it was not judged, and stays a file. The next round trains on the deck as it is."
            save(st.copy(phase = TrainState.IDLE, said = words).noted(TrainState.LOOP, words, now()))
            return Outcome(Outcome.Kind.NOTHING, words)
        }
        val set = gateSet(stop) ?: return paused(st, "Stopped while the held-out hands were mapped.")
        if (set.hands.size < TrainGate.MIN_HANDS) {
            told(Progress(TrainState.GATING, words = "Only ${set.hands.size} held-out hands could be mapped whole"))
        }
        val candidate = loader.load(store.path(made.file), made.version).getOrElse { return failed(st, it.message ?: "The network could not be loaded.") }
        try {
            NetPrior.refusal(candidate, vocab.hash(), heads)?.let { return failed(st, it) }
            val run = GateRun(set, deck, kit, vocab, workers)
            val mapped = AtomicInt(0)
            told(Progress(TrainState.GATING, 0, set.hands.size, words = "Mapping the held-out hands with network ${made.version}"))
            val measured = run.measure(candidate, stop) {
                told(Progress(TrainState.GATING, mapped.incrementAndFetch(), set.hands.size, words = "Mapping the held-out hands with network ${made.version}"))
            }
                ?: return paused(st, "Stopped at the gate: Judge carries on.")
            val judged = set.heads().filter { it in candidate.heads }
            // Against the network in use where it runs on this run (its numbers kept when they are this set's), else version 0.
            val inUse = ladder.byVersion(ladder.inUse)?.takeIf { ladder.inUse > 0 && it.vocabHash == vocab.hash() && it.heads == heads }
            var against = 0
            var againstRecall = set.bare
            var againstError = run.meanError(candidate.heads, candidate.headMean)
            if (inUse != null) {
                if (inUse.gateSet == set.print && inUse.recall.keys == set.bare.keys && judged.all { it in inUse.valueError }) {
                    against = inUse.version
                    againstRecall = inUse.recall
                    againstError = inUse.valueError.filterKeys { it in judged }
                } else {
                    loader.load(store.path(inUse.file), inUse.version).getOrNull()?.let { net ->
                        try {
                            if (NetPrior.refusal(net, vocab.hash(), heads) == null) {
                                val m = run.measure(net, stop) ?: return paused(st, "Stopped at the gate: Judge carries on.")
                                against = inUse.version
                                againstRecall = m.recall
                                againstError = m.valueError.filterKeys { it in judged }
                            }
                        } finally {
                            net.close()
                        }
                    }
                }
            }
            val regressed = run.regressions(NetPrior(candidate, vocab), confirmed, stop) ?: return paused(st, "Stopped at the gate: Judge carries on.")
            val attempts = ladder.attempts + 1
            val verdict = GateRun.verdict(Measured(measured.recall, measured.valueError.filterKeys { it in judged }), againstRecall, againstError.filterKeys { it in judged }, regressed, attempts, set.budget)
            val record = GateRecord.of(made.version, against, verdict, measured, againstRecall, againstError, set.budget, attempts, now())
            val next = ladder.judged(made.copy(gateSet = set.print, recall = measured.recall, valueError = measured.valueError), record, verdict)
            store.write(LADDER, next.encode())
            val words = (if (verdict.promote) "Network ${made.version} passed the gate and is in use. " else "Network ${made.version} did not pass the gate: it stays a file. ") + verdict.why
            st = st.copy(phase = TrainState.IDLE, said = words).noted(TrainState.LOOP, words, now())
            save(st)
            // Only the newest round's checkpoint is kept: a judged round is never carried on.
            store.list(ROUNDS).filter { it != st.round.toString() }.forEach { store.delete("$ROUNDS/$it") }
            return Outcome(Outcome.Kind.JUDGED, words, record)
        } finally {
            candidate.close()
        }
    }

    /** The network in use as this run's prior, or null and why not (none yet, or trained on the deck before it changed). */
    private fun inUse(ladder: TrainLadder, vocab: MapperVocab, heads: List<String>): Pair<NetPrior?, String?> {
        val v = ladder.byVersion(ladder.inUse)?.takeIf { ladder.inUse > 0 } ?: return null to null
        val net = loader.load(store.path(v.file), v.version).getOrElse { return null to "Mapped in the hand-written order: ${it.message}" }
        NetPrior.refusal(net, vocab.hash(), heads)?.let { net.close(); return null to "Mapped in the hand-written order: $it" }
        loaded = net
        return NetPrior(net, vocab) to "Network ${v.version} ordered three in four maps"
    }

    private var loaded: PriorNet? = null

    private fun closeInUse() {
        loaded?.close()
        loaded = null
    }

    /**
     * Round [round]'s hands mapped and written as records (M.md §4.3): dealt from [roundSeed], half a side each, the gate's
     * hands left out; every [NO_PRIOR_EVERY]th map made with no prior. A round's maps are made whole or not at all: null when
     * [stop] ended them.
     */
    private suspend fun mapRound(round: Int, held: HeldOut, prior: NetPrior?, vocab: MapperVocab, heads: List<String>, stop: () -> Boolean): List<String>? {
        val seed = roundSeed(round)
        val deals = listOf(true, false).flatMap { first ->
            Mapper.Plan(MapperSetup(deck, first, if (first) sizes.hands - sizes.hands / 2 else sizes.hands / 2, seed, sizes.budget), kit).deals
        }.filterNot(held::holds)
        val plan = Mapper.Plan(MapperSetup(deck, true, 1, seed, sizes.budget), kit).maps
        val mapped = AtomicInt(0)
        told(Progress(TrainState.MAPPING, 0, deals.size))
        val out = MapWork.each(deals.size, workers, stop) { i, halt ->
            val d = deals[i]
            val bare = prior == null || i % NO_PRIOR_EVERY == 0
            val t = d.table(plan.main, plan.extra, kit)
            val m = MapSearch(
                kit, sizes.budget, plan.depth, ordered = plan.ordered, zonesMatter = plan.zones, prior = if (bare) MovePrior.NONE else prior,
                trace = true, traceMost = sizes.recordsPerMap, cancelled = halt,
            ).map(t, d.fodderUids(t))
            told(Progress(TrainState.MAPPING, mapped.incrementAndFetch(), deals.size))
            TrainingExport.records(d, m, vocab, heads, if (bare) "none" else prior.fingerprint, sizes.recordsPerMap)
        }
        if (stop() || out.any { it == null }) return null
        return out.filterNotNull().flatten().take(sizes.recordsPerRound)
    }

    private fun writeIfChanged(rel: String, text: String) {
        if (store.read(rel) != text) store.write(rel, text)
    }

    private fun save(st: TrainState) = store.write(STATE, st.copy(at = now()).encode())

    private fun paused(st: TrainState, words: String): Outcome {
        save(st.copy(said = words))
        return Outcome(Outcome.Kind.PAUSED, words)
    }

    private fun failed(st: TrainState, words: String): Outcome {
        save(st.copy(said = words).noted(TrainState.LOOP, words, now()))
        return Outcome(Outcome.Kind.FAILED, words)
    }

    private fun unreadable(rel: String) = Outcome(Outcome.Kind.FAILED, "$rel was written by a newer version and could not be read: nothing is written over it. Update the app to train this deck.")

    companion object {
        /** The files under `train/`. */
        const val STATE = "loop.json"
        const val LADDER = "ladder.json"
        const val GATE = "gate.json"
        const val RUN = "run"
        const val VOCAB = "$RUN/vocab.json"
        const val HEADS = "$RUN/heads.json"
        const val ROUNDS = "rounds"
        const val NETS = "nets"
        const val CHECKPOINT = "checkpoint.pt"

        /** The step before a round's first, while the gate's hands are mapped. */
        const val GATE_BUILD = "gate-set"

        /** One map in this many is made with no prior, whatever network is in use (M.md §4.3). */
        const val NO_PRIOR_EVERY = 4

        /** Training rounds' seeds start here: far from [GateSet.GATE_SEED], so no round deals from the gate's seed. */
        const val TRAIN_SEED = 0x7A41_0000L

        private val ROUND_FILE = Regex("""records-(\d+)\.jsonl""")

        fun records(round: Int): String = "$RUN/records-$round.jsonl"

        fun roundDir(round: Int): String = "$ROUNDS/$round"

        fun net(version: Int): String = "$NETS/net-$version.onnx"

        /** Round [round]'s seed: never the gate's. */
        fun roundSeed(round: Int): Long = TRAIN_SEED + round
    }
}
