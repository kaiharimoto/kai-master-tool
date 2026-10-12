package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishDeck
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.CALLER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.ELDER
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.FROG
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.SAGE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.STONE
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishFixtures.WALL
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.MapSearch
import com.kaiharimoto.mastertool.core.duel.mapper.Mapper
import com.kaiharimoto.mastertool.core.duel.mapper.MapperSetup
import com.kaiharimoto.mastertool.core.duel.mapper.MovePrior
import com.kaiharimoto.mastertool.core.duel.mapper.TrainingExport
import com.kaiharimoto.mastertool.core.sync.Sha256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A deck's `train/` folder in memory. */
internal class MemoryStore : TrainStore {
    val files = LinkedHashMap<String, String>()

    override fun read(rel: String): String? = files[rel]

    override fun write(rel: String, text: String) {
        files[rel] = text
    }

    override fun delete(rel: String) {
        files.keys.removeAll { it == rel || it.startsWith("$rel/") }
    }

    override fun list(rel: String): List<String> = files.keys.filter { it.startsWith("$rel/") }.map { it.removePrefix("$rel/").substringBefore('/') }.distinct()

    override fun path(rel: String): String = "/mem/$rel"

    fun rel(path: String) = path.removePrefix("/mem/")
}

/** The trainer's process made up: it reports a little, writes a checkpoint, and exports what the run's files say. */
internal class FakeHelper(private val store: MemoryStore) : TrainHelper {
    val trains = ArrayList<Boolean>()
    val configs = ArrayList<TrainConfig>()
    var ends: (Int) -> TrainEvent? = { TrainEvent.Done("checkpoint.pt", finished = true) }
    var exported: (Exported) -> Exported = { it }

    override suspend fun train(data: String, out: String, tier: ModelTier, config: TrainConfig, resume: Boolean, stop: () -> Boolean, event: (TrainEvent) -> Unit): TrainEvent? {
        trains += resume
        configs += config
        event(TrainEvent.Start(tier.name, 100))
        event(TrainEvent.Step(20, 1.5, 1.2, 0.6, 3e-4, 40.0))
        event(TrainEvent.Eval(20, 1.4, 0.5, mapOf("interruptions" to 0.3)))
        store.write("${store.rel(out)}/checkpoint.pt", "weights")
        return ends(trains.size)
    }

    override suspend fun export(checkpoint: String, out: String): Exported {
        val vocab = store.read(TrainLoop.VOCAB)!!
        val heads = TrainingExport.readHeads(store.read(TrainLoop.HEADS)!!)!!
        store.write(store.rel(out), "onnx")
        return exported(Exported(true, Sha256.hex(vocab).take(16), heads, "S", 2_600_000))
    }
}

/** The gate's hands, the self-play loop and the ladder (M3), on the goldfish's toy cards with a made-up trainer and network. */
class TrainLoopTest {
    private val kit = GoldfishFixtures.kit()
    private val main: List<Int> = GoldfishFixtures.deck(CALLER to 3, FROG to 3, ELDER to 3, SAGE to 3, STONE to 3, WALL to 3) + List(22) { STONE }
    private val deck: GoldfishDeck = GoldfishDeck(main, id = "pond", fingerprint = "pond-v1")
    private val sizes = TrainSizes(hands = 24, budget = 4_000, recordsPerMap = 40, gateHands = 12, gateBudget = 300, exhaustive = 50_000)
    private val plan = HardwarePlan(ModelTier.S, batch = 64, workers = 1, minutes = 5)

    private fun loop(store: MemoryStore, helper: TrainHelper = FakeHelper(store), loader: NetLoader = fakeLoader(store), told: (TrainLoop.Progress) -> Unit = {}): TrainLoop =
        TrainLoop(deck, kit, store, helper, loader, plan, sizes, now = { 1_791_700_000_000L }, told = told)

    /** ONNX Runtime made up: every file a [FakeNet] trained on the run's vocabulary and heads. */
    private fun fakeLoader(store: MemoryStore): NetLoader = NetLoader { _, v ->
        val hash = Sha256.hex(store.read(TrainLoop.VOCAB)!!).take(16)
        Result.success(FakeNet(hash, TrainingExport.readHeads(store.read(TrainLoop.HEADS)!!)!!, version = v))
    }

    @Test
    fun heldOutIsByEngineCardsAndSeat() {
        val deal = MapDeal(listOf(SAGE, CALLER), first = false, seed = 9, fodder = listOf(STONE))
        val held = HeldOut(setOf(HeldOut.key(deal)))
        assertEquals("${listOf(SAGE, CALLER).sorted().joinToString(".")}/2nd", HeldOut.key(deal))
        // The same starter beside other blanks, from another seed, is the same gate hand.
        assertTrue(held.holds(MapDeal(listOf(CALLER, SAGE), first = false, seed = 1, fodder = listOf(STONE, STONE))))
        assertFalse(held.holds(MapDeal(listOf(CALLER, SAGE), first = true)))
        assertTrue(held.holds(TrainingExport.handId(MapDeal(listOf(CALLER, SAGE), first = false, fodder = listOf(ELDER)))))
        assertEquals(HeldOut.key(deal), HeldOut.keyOf(TrainingExport.handId(deal)))
        val line = """{"v":1,"hand":"${TrainingExport.handId(deal)}","step":0}"""
        assertEquals(listOf(TrainingExport.handId(deal)), held.leaks(listOf(line, """{"v":1,"hand":"${TrainingExport.handId(MapDeal(listOf(FROG)))}"}""")))
    }

    @Test
    fun noRoundDealsFromTheGatesSeed() {
        assertTrue((1..100_000).none { TrainLoop.roundSeed(it) == GateSet.GATE_SEED })
    }

    @Test
    fun theGateSetIsTheSameOnAnyNumberOfWorkers() = runTest {
        val one = assertNotNull(GateSet.build(deck, kit, sizes.gateHands, sizes.gateBudget, sizes.exhaustive, workers = 1))
        val three = assertNotNull(GateSet.build(deck, kit, sizes.gateHands, sizes.gateBudget, sizes.exhaustive, workers = 3))
        assertEquals(one, three)
        assertEquals(sizes.gateHands, one.hands.size)
        assertTrue(one.hands.all { it.front.isNotEmpty() && it.moves > 0 })
        assertTrue(one.hands.any { it.deal.first } && one.hands.any { !it.deal.first }, "both seat orders")
        assertEquals(one.hands.size, one.hands.map { it.key }.distinct().size)
        assertEquals(BoardTraits.HEADS, one.heads())
        assertEquals(one, GateSet.decode(one.encode()))
    }

    @Test
    fun versionZerosRecallIsTheHandWrittenOrdersOwnAtTheBudget() = runTest {
        val set = assertNotNull(GateSet.build(deck, kit, sizes.gateHands, sizes.gateBudget, sizes.exhaustive, workers = 1))
        val maps = Mapper.Plan(MapperSetup(deck, true, 1, GateSet.GATE_SEED, set.budget), kit).maps
        set.hands.forEach { g ->
            val t = g.deal.table(maps.main, maps.extra, kit)
            val m = MapSearch(kit, set.budget, ordered = maps.ordered, zonesMatter = maps.zones).map(t, g.deal.fodderUids(t))
            assertEquals(FrontRecall.auc(g.front.toSet(), m.found, set.budget), g.bare, 1e-12, g.key)
        }
        val run = GateRun(set, deck, kit, loop(MemoryStore()).vocab(), workers = 1)
        assertEquals(set.bare, run.recall(MovePrior.NONE))
    }

    @Test
    fun aRoundMapsTrainsExportsAndJudges() = runTest {
        val store = MemoryStore()
        val helper = FakeHelper(store)
        val phases = LinkedHashSet<String>()
        val loop = loop(store, helper) { phases += it.phase }
        val end = loop.round()
        assertEquals(TrainLoop.Outcome.Kind.JUDGED, end.kind, end.words)
        assertEquals(listOf(TrainLoop.GATE_BUILD, TrainState.MAPPING, TrainState.TRAINING, TrainState.EXPORTING, TrainState.GATING), phases.toList())
        // What the trainer reads: the vocabulary, the heads, and the round's records, none of a gate hand's.
        val set = assertNotNull(GateSet.decode(store.read(TrainLoop.GATE)!!))
        val lines = store.read(TrainLoop.records(1))!!.lines().filter { it.isNotBlank() }
        assertTrue(lines.isNotEmpty())
        assertTrue(set.heldOut.leaks(lines).isEmpty())
        assertTrue(lines.all { Json.parseToJsonElement(it).jsonObject.getValue("prior").jsonPrimitive.content == "none" }, "no network in use: every map in the hand-written order")
        assertEquals(listOf(false), helper.trains)
        assertEquals(plan.batch, helper.configs.single().batch, "the batch is the machine's plan")
        assertEquals(plan.minutes, helper.configs.single().maxMinutes)
        // The ladder: network 1 judged against the hand-written order on every gate hand.
        val ladder = assertNotNull(loop.ladder())
        val gate = assertNotNull(ladder.lastGate)
        assertEquals(1, gate.candidate)
        assertEquals(0, gate.against)
        assertEquals(set.hands.size, gate.hands)
        assertEquals(sizes.gateBudget, gate.budget)
        assertEquals(BoardTraits.HEADS.toSet(), gate.valueError.keys)
        assertFalse(gate.promote, "twelve hands are too few for a verdict: ${gate.why}")
        assertTrue("30 are needed" in gate.why)
        assertEquals(0, ladder.inUse)
        assertEquals(1, ladder.attempts)
        val v1 = assertNotNull(ladder.byVersion(1))
        assertEquals(TrainLoop.net(1), v1.file)
        assertEquals(set.print, v1.gateSet)
        assertEquals(set.bare.keys, v1.recall.keys)
        assertNotNull(store.read(TrainLoop.net(1)), "a network that fails the gate stays a file")
        assertEquals(TrainState.IDLE, loop.state()!!.phase)
        // A second round makes network 2, judged with the bar shared between two candidates.
        assertEquals(TrainLoop.Outcome.Kind.JUDGED, loop.round().kind)
        val second = assertNotNull(loop.ladder()).lastGate!!
        assertEquals(2, second.candidate)
        assertEquals(2, second.attempts)
        assertEquals(TrainGate.ALPHA / 2, second.bar, 1e-12)
        assertEquals(listOf("2"), store.list(TrainLoop.ROUNDS), "only the newest round's checkpoint is kept")
    }

    @Test
    fun aPauseWhileTrainingCarriesOnFromTheCheckpoint() = runTest {
        val store = MemoryStore()
        val helper = FakeHelper(store)
        helper.ends = { n -> if (n == 1) TrainEvent.Done("checkpoint.pt", finished = false, stopped = true) else TrainEvent.Done("checkpoint.pt") }
        val loop = loop(store, helper)
        val first = loop.round()
        assertEquals(TrainLoop.Outcome.Kind.PAUSED, first.kind)
        assertEquals(TrainState.TRAINING, loop.state()!!.phase)
        val records = store.read(TrainLoop.records(1))
        assertEquals(TrainLoop.Outcome.Kind.JUDGED, loop.round().kind)
        assertEquals(listOf(false, true), helper.trains, "the second start is --resume")
        assertEquals(records, store.read(TrainLoop.records(1)), "the round's maps were not made again")
        assertEquals(1, loop.state()!!.round)
    }

    @Test
    fun aNetworkInUseOrdersThreeMapsInFourAndOneIsAlwaysBare() = runTest {
        val store = MemoryStore()
        val helper = FakeHelper(store)
        // Network 1 is in use: a round made it, and the ladder says it passed.
        val loop = loop(store, helper)
        loop.round()
        val ladder = loop.ladder()!!
        store.write(TrainLoop.LADDER, ladder.copy(inUse = 1).encode())
        helper.ends = { TrainEvent.Done("checkpoint.pt", finished = false, stopped = true) }
        assertEquals(TrainLoop.Outcome.Kind.PAUSED, loop.round().kind)
        val priors = store.read(TrainLoop.records(2))!!.lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
            .groupBy({ it.getValue("hand").jsonPrimitive.content }, { it.getValue("prior").jsonPrimitive.content }).mapValues { it.value.distinct().single() }
        assertTrue("none" in priors.values, "a share of maps with no prior")
        assertTrue(priors.values.any { it.startsWith("net-1-") }, "the rest in the network's order")
    }

    @Test
    fun anExportThatIsNotTheRunsIsRefused() = runTest {
        val store = MemoryStore()
        val helper = FakeHelper(store)
        helper.exported = { it.copy(vocabHash = "0123456789abcdef") }
        val loop = loop(store, helper)
        val end = loop.round()
        assertEquals(TrainLoop.Outcome.Kind.FAILED, end.kind)
        assertTrue(loop.ladder()!!.versions.isEmpty())
        assertEquals(TrainState.EXPORTING, loop.state()!!.phase, "export is made again on Resume")
    }

    @Test
    fun aCandidateOfADeckSinceChangedIsNeverJudgedOnTheNewOne() = runTest {
        val store = MemoryStore()
        val loop = loop(store)
        // Paused at the gate: trained and exported on the deck as it was.
        assertEquals(TrainLoop.Outcome.Kind.PAUSED, loop.round { loop.state()?.phase == TrainState.GATING }.kind)
        assertEquals(TrainState.GATING, loop.state()!!.phase)
        // A Pond Net in for a stone: the vocabulary grows, and network 1 reads the old one.
        val changed = GoldfishDeck(main.dropLast(1) + GoldfishFixtures.NET, id = "pond", fingerprint = "pond-v2")
        val later = TrainLoop(changed, kit, store, FakeHelper(store), fakeLoader(store), plan, sizes, now = { 1L })
        val end = later.gate()
        assertEquals(TrainLoop.Outcome.Kind.NOTHING, end.kind)
        assertTrue("deck changed" in end.words, end.words)
        assertEquals(TrainState.IDLE, later.state()!!.phase, "the next round starts afresh")
        assertNull(later.ladder()!!.lastGate, "never judged")
        assertNotNull(store.read(TrainLoop.net(1)), "and kept as a file")
    }

    @Test
    fun aFileANewerBuildWroteIsNeverWrittenOver() = runTest {
        val store = MemoryStore()
        store.write(TrainLoop.LADDER, "{not json")
        val end = loop(store).round()
        assertEquals(TrainLoop.Outcome.Kind.FAILED, end.kind)
        assertEquals("{not json", store.read(TrainLoop.LADDER))
    }

    @Test
    fun aiTunesOnlyInsideTheBoundsAndItIsLogged() {
        val store = MemoryStore()
        val loop = loop(store)
        val c = assertNotNull(loop.tune(TrainConfig(lr = 5.0, epochs = 999, valueWeight = Double.NaN), TrainState.AI))
        assertEquals(1e-2, c.lr)
        assertEquals(50, c.epochs)
        assertEquals(TrainConfig().valueWeight, c.valueWeight)
        val note = loop.state()!!.log.last()
        assertEquals(TrainState.AI, note.by)
        assertTrue("lr" in note.what)
    }

    @Test
    fun theGatePromotesOnlyOnAPassAndShares5PercentAmongTheCandidates() {
        val keys = (1..40).map { "h$it" }
        val current = keys.associateWith { 0.5 }
        val better = Measured(keys.associateWith { k -> if (k == "h1" || k == "h2") 0.4 else 0.7 }, BoardTraits.HEADS.associateWith { 0.3 })
        val error = BoardTraits.HEADS.associateWith { 0.5 }
        val pass = GateRun.verdict(better, current, error, emptyList(), attempts = 1, budget = 300)
        assertTrue(pass.promote, pass.why)
        assertEquals(38, pass.wins)
        assertEquals(2, pass.losses)
        // The same edge with a confirmed board lost, or a head predicted worse, does not pass.
        assertFalse(GateRun.verdict(better, current, error, listOf("the Sage board"), 1, 300).promote)
        assertFalse(GateRun.verdict(Measured(better.recall, error + ("negates" to 0.9)), current, error, emptyList(), 1, 300).promote)
        // A narrow edge that passes alone does not pass as the fifth candidate since the last promotion.
        val narrow = Measured(keys.associateWith { k -> if (k.removePrefix("h").toInt() <= 13) 0.4 else 0.6 }, BoardTraits.HEADS.associateWith { 0.3 })
        assertTrue(GateRun.verdict(narrow, current, error, emptyList(), 1, 300).promote)
        assertFalse(GateRun.verdict(narrow, current, error, emptyList(), 5, 300).promote)
        // On the ladder: a pass is in use, rated from its parent, and the count starts again; a fail stays a row.
        val v1 = NetVersion(1, TrainLoop.net(1))
        val record = GateRecord.of(1, 0, pass, better, current, error, 300, 1, 5L)
        val ladder = TrainLadder(versions = listOf(v1)).judged(v1, record, pass)
        assertEquals(1, ladder.inUse)
        assertEquals(0, ladder.attempts)
        val rated = assertNotNull(ladder.rated(1))
        assertEquals(Elo.BASE + pass.elo, rated.elo, 1e-9)
        assertTrue(rated.low < rated.elo && rated.elo < rated.high)
        val v2 = NetVersion(2, TrainLoop.net(2))
        val fail = GateRun.verdict(narrow, better.recall, error, emptyList(), 1, 300)
        val after = ladder.made(v2).judged(v2, GateRecord.of(2, 1, fail, narrow, better.recall, error, 300, 1, 6L), fail)
        assertEquals(1, after.inUse)
        assertEquals(1, after.attempts)
        assertFalse(after.byVersion(2)!!.promoted)
        assertEquals(rated.elo + fail.elo, after.rated(2)!!.elo, 1e-9)
        assertEquals(after, TrainLadder.decode(after.encode()))
    }
}
