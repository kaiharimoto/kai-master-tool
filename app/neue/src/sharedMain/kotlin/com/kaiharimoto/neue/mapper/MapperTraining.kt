package com.kaiharimoto.neue.mapper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.async.Background
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.MapWork
import com.kaiharimoto.mastertool.core.duel.mapper.MapperPaths
import com.kaiharimoto.mastertool.core.duel.mapper.MapperVocab
import com.kaiharimoto.mastertool.core.duel.mapper.MovePrior
import com.kaiharimoto.mastertool.core.duel.mapper.TrainingExport
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateSet
import com.kaiharimoto.mastertool.core.duel.mapper.train.HardwarePlan
import com.kaiharimoto.mastertool.core.duel.mapper.train.MapperTrainPrefs
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetLoader
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetPrior
import com.kaiharimoto.mastertool.core.duel.mapper.train.PriorNet
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainCurves
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainEvent
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLadder
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLoop
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainState
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.effects.goldfishDeck
import com.kaiharimoto.neue.effects.goldfishKit
import com.kaiharimoto.neue.platform.DiagnosticLog
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.world.Found
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gameplay Mapper's training on this computer (Phase I, I.10; M.md §4.4–§4.6): the Training tab's state and Ai's
 * `train_*` tools' hands. The loop itself is core's ([TrainLoop]); this holds the person's switch, their Python and its
 * probe, the open deck's run (`<data>/effects/mapper/<deck>/train/`, never synced or backed up), the curves as they come,
 * and the network in use, which the mapper's own runs order their maps by ([prior]).
 *
 * **The switch is the person's**: [switch] is called by the tab's switch alone. [start] refuses while it is off, whoever
 * asks; Ai never sets the plan ([setPlan]) and never promotes — only the gate does.
 */
class MapperTraining(private val h: NeueHolders) {
    private val scope = Background.scope("Gameplay Mapper's training", Dispatchers.Main)
    private val effectsDir = File(Platform.dataDir, FxPaths.FOLDER)

    /** Where the trainer's files are laid out by their hash. */
    private val home = File(effectsDir, "${MapperPaths.DIR}/$TRAINER")

    /** Whether this device trains: the desk. */
    val possible: Boolean get() = MapperTrainer.possible

    /** The person's settings for it, this device's own. */
    val prefs: MapperTrainPrefs get() = h.neue.prefs.mapperTrain

    /** The Python the trainer runs with, once found. */
    var python by mutableStateOf<Found?>(null)
        private set

    /** What that Python lacks of the trainer's modules; null until asked. */
    var missing by mutableStateOf<List<String>?>(null)
        private set

    /** Python is being looked for, asked, or the machine probed. */
    var checking by mutableStateOf<String?>(null)
        private set

    /** What the last look for Python or probe said, when it went wrong. */
    var checkSaid by mutableStateOf<String?>(null)
        private set

    /** The deck whose run is read. */
    var deckId by mutableStateOf<String?>(null)
        private set

    var state by mutableStateOf(TrainState())
        private set

    var ladder by mutableStateOf(TrainLadder())
        private set

    var gateSet by mutableStateOf<GateSet?>(null)
        private set

    /** A round, or a gate, is running. */
    var running by mutableStateOf(false)
        private set

    var progress by mutableStateOf<TrainLoop.Progress?>(null)
        private set

    /** The round's curves, replaced whole when a round begins; [ticks] moves on as lines arrive. */
    var curves by mutableStateOf(TrainCurves())
        private set
    var ticks by mutableStateOf(0)
        private set

    /** What the last round or gate said. */
    var said by mutableStateOf<String?>(null)

    /** The ladder's row whose verdict is open. */
    var chosen by mutableStateOf<Int?>(null)

    @Volatile
    private var pausing = false
    private var job: Job? = null

    /** The round or gate running, to wait on (Ai's `train_gate`). */
    val current: Job? get() = job

    /** The plan in force: the person's own, else the probe's. */
    val plan: HardwarePlan get() = prefs.plan(MapWork.workers() + 1)

    private fun trainDir(id: String) = File(effectsDir, "${MapperPaths.deck(id)}/${MapperPaths.TRAIN}")

    private fun store(id: String) = FileTrainStore(trainDir(id))

    /** The studio's picture is on screen: nothing is read over it ([demo]). */
    private var pictured = false

    /** [id]'s run read (off the frame thread), unless it is the one shown. */
    fun open(id: String?, force: Boolean = false) {
        if (pictured || id == null || (id == deckId && !force)) return
        if (running && id != deckId) return
        deckId = id
        scope.launch {
            val (s, l, g) = withContext(Dispatchers.IO) {
                val st = store(id)
                Triple(
                    st.read(TrainLoop.STATE)?.let(TrainState::decode) ?: TrainState(deckId = id),
                    st.read(TrainLoop.LADDER)?.let(TrainLadder::decode) ?: TrainLadder(deckId = id),
                    st.read(TrainLoop.GATE)?.let(GateSet::decode),
                )
            }
            if (deckId != id) return@launch
            state = s
            ladder = l
            gateSet = g
        }
    }

    /** The person's switch (the Training tab's alone): on, Python is looked for and the machine probed. */
    fun switch(on: Boolean) {
        h.neue.update { it.copy(mapperTrain = it.mapperTrain.copy(on = on)) }
        if (on) check()
    }

    /** The Python to train with, as the person typed it (blank: Ai World's, then the PATH). */
    fun setPython(path: String) {
        h.neue.update { it.copy(mapperTrain = it.mapperTrain.copy(python = path.trim())) }
        python = null
        missing = null
    }

    /** The person's own plan, or null to follow the probe: a new probe never replaces it, and Ai never sets it. */
    fun setPlan(plan: HardwarePlan?) {
        h.neue.update { it.copy(mapperTrain = it.mapperTrain.copy(plan = plan?.copy(personal = true, why = "Yours."))) }
    }

    /** Python looked for and asked what it has; with everything there and [probe], the machine probed. */
    fun check(probe: Boolean = true): Job? {
        if (!possible || checking != null) return null
        val path = prefs.python.ifBlank { h.neue.prefs.world.pythonPath }
        checking = "Looking for Python"
        checkSaid = null
        return scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { MapperTrainer.python(path) }
                python = found
                if (found == null) {
                    missing = null
                    checkSaid = "No Python 3 found${if (path.isBlank()) " on the PATH" else " at $path"}. Install Python 3.10 or newer, then ${TrainWords.INSTALL}."
                    return@launch
                }
                checking = "Asking ${found.version} for torch"
                val lacks = withContext(Dispatchers.IO) { MapperTrainer.missing(found) }
                missing = lacks.getOrNull()
                lacks.exceptionOrNull()?.let { checkSaid = it.message }
                if (probe && missing?.isEmpty() == true) {
                    checking = "Probing this machine"
                    val p = withContext(Dispatchers.IO) { MapperTrainer.probe(found, MapperTrainer.install(home)) }
                    p.getOrNull()?.let { probed -> h.neue.update { it.copy(mapperTrain = it.mapperTrain.copy(probe = probed)) } }
                    p.exceptionOrNull()?.let { checkSaid = "The probe failed: ${it.message}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkSaid = e.message ?: e::class.simpleName
            } finally {
                checking = null
            }
        }
    }

    /** Why a round cannot start now, or null: the switch, Python, torch, a deck, a round already going. */
    fun refusal(): String? = when {
        !possible -> MapperTrainer.why
        !prefs.on -> "The trainer is off. Only the person switches it on, in Gameplay Mapper's Training tab: it runs their own Python."
        running -> "A round is running already."
        h.builder.deckId == null -> "Save the deck first: its training is kept with it."
        python == null -> checkSaid ?: "Python has not been found yet: the Training tab looks for it."
        missing == null -> "Python has not been asked for torch yet."
        missing!!.isNotEmpty() -> TrainWords.missing(missing!!)
        else -> null
    }

    /** A round started on the builder's deck (or the paused one carried on) by [by]; null, or why not. */
    fun start(by: String): String? {
        refusal()?.let { return it }
        return launch(by, "Started a round", gateOnly = false)
    }

    /** The candidate waiting at the gate judged, by [by]; null, or why not. */
    fun gate(by: String): String? {
        if (!possible) return MapperTrainer.why
        if (running) return "A round is running: it ends at the gate by itself."
        if (h.builder.deckId == null) return "Save the deck first."
        if (state.phase != TrainState.GATING) return "No network waits for the gate: a round ends there by itself."
        return launch(by, "Asked the gate to judge network ${state.candidate}", gateOnly = true)
    }

    /** The round paused between steps (while training, the trainer saves and stops), by [by]. */
    fun pause(by: String): String? {
        if (!running) return "No round is running."
        pausing = true
        // The loop owns the state file while it runs: the line is written once it has stopped.
        pendingNotes += by to "Paused the round"
        return null
    }

    /** Lines for the run's log said while the loop held the state file. */
    private val pendingNotes = ArrayList<Pair<String, String>>()

    /**
     * The next round's settings from [config], held inside its bounds, as [by]'s; the batch and minutes stay the plan's.
     * Null while a round runs (its state is the loop's until it stops) or when the state cannot be read.
     */
    fun tune(config: TrainConfig, by: String, why: String = ""): TrainConfig? {
        if (running) return null
        val id = h.builder.deckId ?: return null
        val c = config.bounded()
        val st = store(id)
        val now = System.currentTimeMillis()
        val read = st.read(TrainLoop.STATE)
        val base = if (read == null) TrainState(deckId = id) else TrainState.decode(read) ?: return null
        val next = base.copy(config = c).noted(by, "Set the training${if (why.isNotBlank()) " ($why)" else ""}: ${c.json()}", now)
        st.write(TrainLoop.STATE, next.encode())
        if (deckId == id) state = next
        return c
    }

    private fun note(by: String, what: String) {
        val id = deckId ?: return
        val st = store(id)
        val read = st.read(TrainLoop.STATE) ?: return
        val next = TrainState.decode(read)?.noted(by, what, System.currentTimeMillis()) ?: return
        st.write(TrainLoop.STATE, next.encode())
        state = next
    }

    private fun launch(by: String, what: String, gateOnly: Boolean): String? {
        val id = h.builder.deckId ?: return "Save the deck first."
        val found = python ?: return "Python has not been found yet."
        val deck = h.goldfishDeck()
        val kit = h.goldfishKit()
        // A round maps while nothing trains, and trains while nothing maps: the probe's workers (sized to share the cores with
        // the trainer) may take every core but one. The person's own plan is kept as they set it.
        val plan = plan.let { p -> if (p.personal) p else p.copy(workers = maxOf(p.workers, MapWork.workers())) }
        open(id)
        pausing = false
        running = true
        said = null
        curves = TrainCurves()
        progress = null
        note(by, what)
        DiagnosticLog.event("Mapper training: $what on ${deck.name.ifBlank { id }} by $by")
        job = scope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) {
                    val dir = MapperTrainer.install(home)
                    val loop = TrainLoop(
                        deck, kit, store(id), MapperTrainer.helper(found, dir), loader, plan,
                        now = { System.currentTimeMillis() }, told = ::told,
                    )
                    if (gateOnly) loop.gate { pausing } else loop.round { pausing }
                }
                said = outcome.words
                DiagnosticLog.event("Mapper training: ${outcome.kind} — ${outcome.words}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = "The round failed: ${e.message ?: e::class.simpleName}"
                DiagnosticLog.event("Mapper training failed: ${Background.words(e)}")
            } finally {
                running = false
                job = null
                pendingNotes.toList().also { pendingNotes.clear() }.forEach { (who, words) -> note(who, words) }
                DiagnosticLog.note(NOTE, null)
                open(id, force = true)
                forgetPrior()
            }
        }
        return null
    }

    /** A line from the loop (from its workers' threads too): posted to the page, at most every [POST_MS] while mapping. */
    private fun told(p: TrainLoop.Progress) {
        val e = p.event
        val now = System.currentTimeMillis()
        if (e == null && p.done in 1 until p.total && now - lastPost < POST_MS) return
        lastPost = now
        DiagnosticLog.note(NOTE, "${TrainState.words(p.phase)} ${if (p.total > 0) "${p.done}/${p.total}" else ""} ${p.words}".trim())
        scope.launch {
            progress = p
            if (e != null && e !is TrainEvent.Other) {
                curves.add(e)
                ticks++
            }
        }
    }

    @Volatile
    private var lastPost = 0L

    private val loader = NetLoader { path, version -> MapperTrainer.load(File(path), version) }

    // ---------------------------------------------------------------- the network in use, for the mapper's own runs

    private class InUse(val deckId: String, val version: Int, val vocab: String, val net: PriorNet, val prior: NetPrior)

    private var inUse: InUse? = null
    private val inUseLock = Any()

    /**
     * The order the mapper's runs on [deck] map in (M3: "the mapper's own runs use the promoted network"): the network in
     * use when there is one this computer can run on the deck as it is, else the hand-written order, and why — in words.
     * The prior only reorders: a complete map's boards are the same either way.
     */
    fun prior(deck: GoldfishDeck, kit: GoldfishKit): Pair<MovePrior, String?> {
        val id = deck.id ?: return MovePrior.NONE to null
        val st = store(id)
        val ladder = st.read(TrainLoop.LADDER)?.let(TrainLadder::decode) ?: return MovePrior.NONE to null
        val v = ladder.byVersion(ladder.inUse)?.takeIf { ladder.inUse > 0 } ?: return MovePrior.NONE to null
        val previous = st.read(TrainLoop.VOCAB)?.let(MapperVocab::read).orEmpty()
        val vocab = MapperVocab.of((deck.main + deck.extra).map(kit::canonical), previous)
        val heads = TrainingExport.grownHeads(BoardTraits.HEADS, st.read(TrainLoop.HEADS)?.let(TrainingExport::readHeads).orEmpty())
        synchronized(inUseLock) {
            inUse?.takeIf { it.deckId == id && it.version == v.version && it.vocab == vocab.hash() }?.let { return it.prior to "Ordered by network ${v.version}." }
            inUse?.net?.close()
            inUse = null
            val net = MapperTrainer.load(File(st.path(v.file)), v.version).getOrElse { return MovePrior.NONE to it.message }
            NetPrior.refusal(net, vocab.hash(), heads)?.let { net.close(); return MovePrior.NONE to "Mapped in the hand-written order: $it" }
            val p = NetPrior(net, vocab)
            inUse = InUse(id, v.version, vocab.hash(), net, p)
            return p to "Ordered by network ${v.version}."
        }
    }

    /** The network kept for the mapper let go: a new one may be in use. */
    private fun forgetPrior() {
        synchronized(inUseLock) {
            inUse?.net?.close()
            inUse = null
        }
    }

    /**
     * The studio's picture (`--mapper-tab=training`): a run made up in memory — the switch on, a probe, two rounds' curves,
     * a ladder and a verdict — nothing written, nothing run. Never in the app.
     */
    fun demo(id: String, python: Found, state: TrainState, ladder: TrainLadder, gateSet: GateSet?, curves: TrainCurves, progress: TrainLoop.Progress?) {
        pictured = true
        this.deckId = id
        this.python = python
        this.missing = emptyList()
        this.state = state
        this.ladder = ladder
        this.gateSet = gateSet
        this.curves = curves
        this.progress = progress
        this.running = progress != null
        ticks++
    }

    companion object {
        /** The folder under `effects/mapper/` the trainer's files are laid out in, by their hash. */
        const val TRAINER = "trainer"

        /** At most this often the page hears of a map done, in ms. */
        const val POST_MS = 150L

        private const val NOTE = "mapper training"
    }
}
