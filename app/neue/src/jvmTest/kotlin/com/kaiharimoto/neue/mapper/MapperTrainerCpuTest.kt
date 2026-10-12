package com.kaiharimoto.neue.mapper

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateSet
import com.kaiharimoto.mastertool.core.duel.mapper.train.HardwarePlan
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetLoader
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetPrior
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainCurves
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainEvent
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLoop
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainSizes
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainState
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainWords
import com.kaiharimoto.mastertool.core.duel.search.ladder.Bench
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One whole round on the CPU (Phase I, I.10; M.md §10, M3 "proved on the CPU"), at a tiny scale on the ladder's bench deck —
 * whose every card is written — with the real trainer: the held-out hands mapped exhaustively, a round's hands mapped and
 * written as records, tier S trained for its minute with the person's Python, exported to ONNX, loaded into ONNX Runtime
 * here, used as the search's prior (a complete map's boards unchanged), judged by the gate, and the ladder written. It may
 * well not promote on CPU-scale data: the proof is that every number is computed.
 *
 * Skipped unless `NEUE_TRAINER_PYTHON` names a Python with torch, numpy, onnx and onnxruntime (CI's "Mapper trainer (CPU)").
 */
class MapperTrainerCpuTest {
    @Test
    fun aWholeRoundOnTheCpu() = runBlocking {
        val path = System.getenv("NEUE_TRAINER_PYTHON")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val t0 = System.nanoTime()
        fun say(line: String) = println("MapperTrainerCpuTest [${(System.nanoTime() - t0) / 1_000_000_000}s]: $line")
        val python = assertNotNull(MapperTrainer.python(path), "no Python at $path")
        assertEquals(emptyList(), MapperTrainer.missing(python).getOrThrow(), TrainWords.INSTALL)
        val home = Files.createTempDirectory("mapper-train").toFile()
        val trainer = MapperTrainer.install(File(home, "trainer"))
        assertTrue(File(trainer, "mapper_train/train.py").isFile, "the trainer laid out from the app's resources")
        val probe = MapperTrainer.probe(python, trainer).getOrThrow()
        say("probe: ${TrainWords.probe(probe)}")
        val plan = HardwarePlan.of(probe).copy(workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 4), minutes = 3)
        say("plan: ${TrainWords.plan(plan)}")

        val bench = assertNotNull(Bench.deck("scouts"))
        val deck = GoldfishDeck(bench.main, bench.extra, id = "bench-scouts", fingerprint = "bench-${Bench.print}", name = bench.name)
        val kit = Bench.kit
        val store = FileTrainStore(File(home, "train"))
        // The bench deck's maps are big (every card written): most hands do not finish within a budget a CPU spends in
        // minutes, and only finished maps teach or judge. Many hands, then, and a small gate set: far from the 30 a verdict
        // needs, which a GPU's run reaches.
        val sizes = TrainSizes(hands = 160, budget = 40_000, recordsPerMap = 200, recordsPerRound = 12_000, gateHands = 10, gateBudget = 4_000, exhaustive = 120_000)
        val loader = NetLoader { p, v -> MapperTrainer.load(File(p), v) }
        val curves = TrainCurves()
        var lastPhase = ""
        val loop = TrainLoop(deck, kit, store, MapperTrainer.helper(python, trainer), loader, plan, sizes, now = { System.currentTimeMillis() }) { p ->
            p.event?.let { e -> curves.add(e); if (e is TrainEvent.Start || e is TrainEvent.Eval || e is TrainEvent.Done || e is TrainEvent.Failed) say("trainer: $e") }
            if (p.phase != lastPhase) { lastPhase = p.phase; say("step: ${p.phase} ${p.words}") }
        }
        // Tier S for at most its three minutes: few enough epochs to finish on a CPU, a quarter of the hands held out.
        loop.tune(TrainConfig(epochs = 3, warmupSteps = 40, lr = 1e-3, valFraction = 0.25), TrainState.PERSON)

        val end = loop.round()
        say("round: ${end.kind} — ${end.words}")
        assertEquals(TrainLoop.Outcome.Kind.JUDGED, end.kind, end.words)

        val set = assertNotNull(store.read(TrainLoop.GATE)?.let(GateSet::decode))
        val records = store.read(TrainLoop.records(1))!!.lines().filter { it.isNotBlank() }
        say("gate set: ${set.hands.size} held-out hands (${set.skipped} dealt and left out), budget ${set.budget}; round 1: ${records.size} records")
        assertTrue(set.heldOut.leaks(records).isEmpty(), "no record of a held-out hand")
        assertTrue(records.size > 100)
        say("curves: ${TrainWords.curves(curves)}")
        assertTrue(curves.steps.isNotEmpty() && curves.evals.isNotEmpty())
        assertTrue(curves.steps.all { it.loss.isFinite() })

        // The network in ONNX Runtime, here: what it was trained on, and as the search's prior.
        val ladder = assertNotNull(loop.ladder())
        val v1 = assertNotNull(ladder.byVersion(1))
        say("network 1: ${File(store.path(v1.file)).length() / 1024} KB, tier ${v1.tier}, vocabulary ${v1.vocabHash}")
        val net = loader.load(store.path(v1.file), 1).getOrThrow()
        try {
            val vocab = loop.vocab()
            assertNull(NetPrior.refusal(net, vocab.hash(), loop.heads()))
            val prior = NetPrior(net, vocab)
            // The gate's hands, which finish exhaustively: mapped whole in both orders, the same boards.
            val maps = Mapper.Plan(MapperSetup(deck, true, 1, 1, sizes.exhaustive), kit).maps
            var compared = 0
            var bareMoves = 0L
            var priorMoves = 0L
            set.hands.take(4).forEach { g ->
                val t = g.deal.table(maps.main, maps.extra, kit)
                val bare = MapSearch(kit, sizes.exhaustive, ordered = maps.ordered, zonesMatter = maps.zones).map(t, g.deal.fodderUids(t))
                val ordered = MapSearch(kit, sizes.exhaustive, ordered = maps.ordered, zonesMatter = maps.zones, prior = prior).map(t, g.deal.fodderUids(t))
                assertTrue(bare.complete && ordered.complete)
                assertEquals(bare.ends.map { it.key }, ordered.ends.map { it.key }, "a complete map's boards never change with the prior")
                assertEquals(0, ordered.priorRefused)
                compared++
                bareMoves += bare.ends.filter { it.key in g.front }.sumOf { it.at }
                priorMoves += ordered.ends.filter { it.key in g.front }.sumOf { it.at }
            }
            say("prior: $compared complete maps the same in the network's order as in the hand-written one (front found after $priorMoves engine moves in all, against $bareMoves)")
            assertTrue(compared > 0)
        } finally {
            net.close()
        }

        val gate = assertNotNull(ladder.lastGate)
        say("gate: ${TrainWords.gate(gate)}")
        say("ladder: ${TrainWords.ladder(ladder)}")
        say("held out: ${TrainWords.heldOut(ladder, set)}")
        assertEquals(set.hands.size, gate.hands)
        assertTrue(gate.p in 0.0..1.0 && gate.recall in 0.0..1.0 && gate.againstRecall in 0.0..1.0)
        assertTrue(gate.valueError.isNotEmpty() && gate.valueError.values.all { it.isFinite() })
        assertNotNull(ladder.rated(1))
        assertTrue(store.read(TrainLoop.LADDER)!!.contains("\"gates\""), "the ladder is written")
        home.deleteRecursively()
    }
}
