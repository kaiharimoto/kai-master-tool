package com.kaiharimoto.mastertool.core.duel.mapper.train

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hardware plan, the gate and the trainer's link (M.md §4.4–§4.5). */
class TrainPlanTest {
    private val gib = 1L shl 30

    @Test
    fun theTierFollowsTheMachine() {
        assertEquals(ModelTier.L, HardwarePlan.of(probe("cuda", "RTX 4080", 16 * gib, 16, steps = 400.0)).tier)
        assertEquals(ModelTier.M, HardwarePlan.of(probe("cuda", "RTX 3060", 8 * gib, 8, steps = 200.0)).tier)
        assertEquals(ModelTier.S, HardwarePlan.of(probe("cuda", "MX450", 2 * gib, 4, steps = 60.0)).tier)
        assertEquals(ModelTier.M, HardwarePlan.of(probe("mps", "Apple M3 Pro", 36 * gib, 12, steps = 150.0)).tier)
        assertEquals(ModelTier.S, HardwarePlan.of(probe("cpu", "", 64 * gib, 32, steps = 30.0)).tier)
        // A big card that measured slow stays small.
        assertEquals(ModelTier.S, HardwarePlan.of(probe("cuda", "old", 24 * gib, 8, steps = 5.0)).tier)
    }

    @Test
    fun theCpuSharesItsCoresWithTheTrainer() {
        assertEquals(15, HardwarePlan.of(probe("cuda", "", 16 * gib, 16, steps = 400.0)).workers)
        assertEquals(7, HardwarePlan.of(probe("cpu", "", 16 * gib, 16, steps = 30.0)).workers)
        assertEquals(1, HardwarePlan.of(probe("cpu", "", 4 * gib, 1)).workers)
        assertTrue(HardwarePlan.of(probe("cuda", "", 16 * gib, 16, steps = 400.0)).mixed)
        assertFalse(HardwarePlan.of(probe("mps", "", 36 * gib, 12, steps = 150.0)).mixed)
    }

    @Test
    fun thePersonsOwnPlanIsNeverReplaced() {
        val mine = HardwarePlan(ModelTier.S, batch = 32, workers = 2, personal = true)
        val probed = HardwarePlan.of(probe("cuda", "", 16 * gib, 16, steps = 400.0))
        assertEquals(mine, mine.next(probed))
        assertEquals(probed, HardwarePlan().next(probed))
        assertFalse(HardwarePlan().next(probed.copy(personal = true)).personal, "a proposal is never the person's")
    }

    @Test
    fun theProbesJsonIsRead() {
        val p = assertNotNull(
            HardwareProbe.parse(
                """{"device":"cuda","device_name":"RTX 4090","memory_bytes":25769803776,"cpu_cores":24,"torch_version":"2.4.1","steps_per_s":512.5,"new":1}""",
            ),
        )
        assertEquals("RTX 4090", p.deviceName)
        assertEquals(24, p.cpuCores)
        assertEquals(ModelTier.L, HardwarePlan.of(p).tier)
        assertNull(HardwareProbe.parse("Traceback (most recent call last):"))
    }

    @Test
    fun aCandidateWinsOnlyWithAClearEdgeAndNothingLost() {
        val wins = List(40) { GateHand("h$it", 5, 3) } + List(10) { GateHand("d$it", 3, 3) }
        val v = TrainGate.judge(wins, emptyList(), 0.40, 0.41)
        assertTrue(v.promote, v.why)
        assertTrue(v.elo > 0 && v.eloLow > 0)
        assertFalse(TrainGate.judge(wins, listOf("board-1"), 0.40, 0.41).promote, "a confirmed board lost")
        assertFalse(TrainGate.judge(wins, emptyList(), 0.60, 0.41).promote, "worse predictions")
        assertFalse(TrainGate.judge(wins.take(10), emptyList(), 0.40, 0.41).promote, "too few hands")
        val even = List(25) { GateHand("w$it", 5, 3) } + List(25) { GateHand("l$it", 3, 5) }
        assertFalse(TrainGate.judge(even, emptyList(), 0.40, 0.41).promote, "no edge")
    }

    @Test
    fun eloIsTheLogisticOfTheScore() {
        assertEquals(0.0, Elo.diff(0.5), 1e-9)
        assertEquals(0.75, Elo.expected(Elo.diff(0.75)), 1e-9)
        assertTrue(Elo.diff(1.0) < 900, "a sweep is held finite")
        val v = TrainGate.judge(List(40) { GateHand("h$it", 2, 1) }, emptyList(), 0.0, 0.0)
        val r = TrainGate.rate(Rated(0, Elo.BASE, Elo.BASE, Elo.BASE), 1, v, 40)
        assertEquals(Elo.BASE + v.elo, r.elo, 1e-9)
        assertEquals(0, r.parent)
    }

    @Test
    fun theTrainersLinesAreRead() {
        val s = TrainEvent.parse("""{"event":"step","step":10,"loss":1.5,"policy_loss":1.0,"value_loss":0.5,"lr":0.0003,"steps_per_s":42.0}""")
        assertEquals(TrainEvent.Step(10, 1.5, 1.0, 0.5, 0.0003, 42.0), s)
        val e = TrainEvent.parse("""{"event":"eval","step":10,"val_loss":1.2,"val_policy_top1":0.6,"val_value_mae":{"interruptions":0.3}}""")
        assertEquals(TrainEvent.Eval(10, 1.2, 0.6, mapOf("interruptions" to 0.3)), e)
        assertEquals(TrainEvent.Done("/x/ckpt.pt"), TrainEvent.parse("""{"event":"done","checkpoint":"/x/ckpt.pt"}"""))
        assertTrue(TrainEvent.parse("UserWarning: something") is TrainEvent.Other)
    }

    @Test
    fun aConfigIsHeldInsideItsBounds() {
        val c = TrainConfig(lr = 5.0, batch = 1_000_000, epochs = 0, maxMinutes = 100_000, valFraction = 0.0).bounded()
        assertEquals(1e-2, c.lr)
        assertEquals(1024, c.batch)
        assertEquals(1, c.epochs)
        assertEquals(24 * 60, c.maxMinutes)
        assertEquals(0.05, c.valFraction)
        assertTrue(TrainConfig(lr = 5.0).json().contains("\"lr\":0.01"))
    }

    @Test
    fun aPlateauAndOverfittingAreSeen() {
        val curves = TrainCurves()
        listOf(1.0, 0.8, 0.7, 0.71, 0.72, 0.73).forEachIndexed { i, l -> curves.add(TrainEvent.Eval(i * 10, l, 0.5, emptyMap())) }
        (0..50).forEach { curves.add(TrainEvent.Step(it, 2.0 - it * 0.02, 0.0, 0.0, 0.0, 0.0)) }
        assertTrue(curves.plateaued())
        assertTrue(curves.overfitting())
    }

    private fun probe(device: String, name: String, mem: Long, cores: Int, steps: Double = 0.0) =
        HardwareProbe(device, name, mem, cores, "2.4", steps)
}
