package com.kaiharimoto.mastertool.core.duel.mapper.train

import com.kaiharimoto.mastertool.core.ai.TrainTools
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The training in words (the page, `train_status`, `eval_heldout`) and Ai's tuning held to its bounds. */
class TrainWordsTest {
    @Test
    fun whatIsMissingIsSaidWithWhatInstallsIt() {
        assertEquals(listOf("torch", "onnx"), TrainWords.missingOf("""{"torch": false, "numpy": true, "onnx": false, "onnxruntime": true}"""))
        assertEquals(emptyList(), TrainWords.missingOf("warning: something\n" + """{"torch": true, "numpy": true, "onnx": true, "onnxruntime": true}"""))
        assertNull(TrainWords.missingOf("Traceback (most recent call last):"))
        assertEquals("torch not found — pip install torch numpy onnx onnxruntime", TrainWords.missing(listOf("torch")))
        assertEquals("", TrainWords.missing(emptyList()))
        assertTrue(TrainWords.MODULES.all { "'$it'" in TrainWords.CHECK })
    }

    @Test
    fun thePlanInForceIsThePersonsElseTheProbes() {
        val cpu = HardwareProbe("cpu", "", 16L shl 30, 8, "2.6.0", 30.0)
        assertEquals(ModelTier.S, MapperTrainPrefs().plan(8).tier)
        assertEquals(HardwarePlan.of(cpu), MapperTrainPrefs(probe = cpu).plan(8))
        val mine = HardwarePlan(ModelTier.M, batch = 32, workers = 2)
        assertTrue(MapperTrainPrefs(plan = mine, probe = cpu).plan(8).let { it.personal && it.tier == ModelTier.M })
        assertTrue("yours" in TrainWords.plan(MapperTrainPrefs(plan = mine).plan(8)))
        assertTrue("CPU" in TrainWords.probe(cpu) && "torch 2.6.0" in TrainWords.probe(cpu))
    }

    @Test
    fun theHeldOutHandsAreSaidInAggregateOnly() {
        val deals = (1..30).map { i -> GateDeal(MapDeal(listOf(900_000_600 + i % 7, 900_000_700 + i), first = i % 2 == 0), listOf("board-$i"), BoardTraits.HEADS.associateWith { 1.0 }, 10, 0.5) }
        val set = GateSet(print = "p", budget = 300, hands = deals)
        val record = GateRecord(1, 0, false, 12, 10, 8, 0.33, 0.05, recall = 0.61, againstRecall = 0.5, valueError = mapOf("negates" to 0.2), againstError = mapOf("negates" to 0.4), hands = 30, budget = 300)
        val words = TrainWords.heldOut(TrainLadder(gates = listOf(record)), set)
        assertTrue("30 held-out hands" in words && "15 going first" in words, words)
        assertTrue("0.61" in words && "12 better" in words)
        // Never a hand: no card of any gate hand, and no board of one.
        deals.forEach { d -> d.deal.hand.forEach { c -> assertFalse(c.toString() in words, "card $c named") }; assertFalse(d.front.single() in words) }
        assertTrue("first round" in TrainWords.heldOut(TrainLadder(), null))
    }

    @Test
    fun theLadderAndTheGateAreSaidWithTheirNumbers() {
        assertTrue("hand-written order" in TrainWords.ladder(TrainLadder()))
        val l = TrainLadder(
            versions = listOf(NetVersion(1, tier = "S", round = 1, rated = Rated(1, 1042.0, 1001.0, 1090.0, 0, 40), promoted = true), NetVersion(2, tier = "S", round = 2, rated = Rated(2, 1030.0, 990.0, 1070.0, 1, 40))),
            inUse = 1,
            attempts = 1,
        )
        val words = TrainWords.ladder(l)
        assertTrue("Network 1 (tier S, round 1): 1042 (1001–1090) — in use" in words, words)
        assertTrue("Network 2 (tier S, round 2): 1030 (990–1070) — failed the gate" in words, words)
        val gate = TrainWords.gate(GateRecord(2, 1, false, 14, 16, 10, 0.71, 0.025, elo = -12.0, eloLow = -60.0, eloHigh = 35.0, recall = 0.6, againstRecall = 0.62, hands = 40, budget = 20_000, attempts = 2, why = "Its edge is not clear yet."))
        assertTrue("against network 1" in gate && "p 0.71" in gate && "2 tried since" in gate && "−12" in gate, gate)
    }

    @Test
    fun aiTunesInsideTheBoundsAndNeverThePlan() {
        val base = TrainConfig(batch = 256, maxMinutes = 45)
        val (c, problems) = TrainTools.tuned(base, buildJsonObject { put("lr", 9.0); put("epochs", 0); put("value_weight", JsonPrimitive("2")); put("weight_decay", "lots") })
        assertEquals(1e-2, c.lr)
        assertEquals(1, c.epochs)
        assertEquals(2.0, c.valueWeight)
        assertEquals(base.weightDecay, c.weightDecay)
        assertEquals(listOf("weight_decay is not a number."), problems)
        assertEquals(256, c.batch, "the batch is the machine's plan")
        assertEquals(45, c.maxMinutes)
        assertFalse("batch" in TrainTools.tune.schema.toString())
    }
}
