package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import com.kaiharimoto.mastertool.core.duel.mapper.MapDeal
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateDeal
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateRecord
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateSet
import com.kaiharimoto.mastertool.core.duel.mapper.train.HardwareProbe
import com.kaiharimoto.mastertool.core.duel.mapper.train.MapperTrainPrefs
import com.kaiharimoto.mastertool.core.duel.mapper.train.NetVersion
import com.kaiharimoto.mastertool.core.duel.mapper.train.Rated
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainConfig
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainCurves
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainEvent
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLadder
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLoop
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainNote
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainState
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.mapper.MapperTab
import com.kaiharimoto.neue.world.Found
import kotlin.math.exp
import kotlin.math.sin

/**
 * The Training tab, photographed (I.10, `--page=mapper --mapper=demo --mapper-tab=training`): a run made up for the picture —
 * the switch on, a GPU probed, round 3 training with its curves, a ladder of two networks (one in use, one that failed the
 * gate) and the last verdict. Nothing is run, written or trained. `--mapper-train=running|paused|first` picks the moment:
 * a round training, one paused at the gate, or the tab before any round.
 */
internal fun studioTraining(h: NeueHolders, moment: String) {
    val gib = 1L shl 30
    val probe = HardwareProbe("cuda", "NVIDIA GeForce RTX 4070", 12 * gib, 16, "2.6.0", 312.4)
    h.neue.update { it.copy(mapperTrain = MapperTrainPrefs(on = true, probe = probe)) }
    val id = h.builder.deckId ?: "studio"
    val python = Found(listOf("python3"), "Python 3.12.3")
    if (moment == "first") {
        h.training.demo(id, python, TrainState(deckId = id), TrainLadder(deckId = id), null, TrainCurves(), null)
        h.mapper.tab = MapperTab.TRAINING
        return
    }
    val curves = TrainCurves()
    curves.add(TrainEvent.Start("M", 41_200))
    for (s in 20..1_400 step 20) {
        val loss = 0.85 + 1.9 * exp(-s / 380.0) + 0.04 * sin(s / 37.0)
        curves.add(TrainEvent.Step(s, loss, loss * 0.72, loss * 0.28, 3e-4, 41.0))
        if (s % 280 == 0) {
            curves.add(TrainEvent.Eval(s, loss + 0.12, 0.38 + s / 6_000.0, mapOf("interruptions" to 0.41, "negates" to 0.33), loss + 0.1, mapOf("interruptions" to 0.92, "negates" to 0.71)))
        }
    }
    val hands = (0 until 40).map { k -> GateDeal(MapDeal(listOf(k), first = k % 2 == 0), listOf("b$k"), BoardTraits.HEADS.associateWith { 1.0 }, 9_000, 0.5) }
    val set = GateSet(print = "studio", hands = hands)
    val heads = BoardTraits.HEADS.associateWith { 0.4 }
    val first = GateRecord(
        1, 0, true, 26, 7, 7, 0.0012, 0.05, 0.74, 0.59, 0.86, 181.0, 64.0, 315.0, recall = 0.71, againstRecall = 0.58,
        valueError = heads, againstError = BoardTraits.HEADS.associateWith { 0.9 }, hands = 40, budget = 20_000, attempts = 1,
        why = "It found more of the front on 26 hands and less on 7 (p 0.001): search rating +181.",
    )
    val second = GateRecord(
        2, 1, false, 15, 13, 12, 0.425, 0.05, 0.525, 0.37, 0.67, 17.0, -92.0, 125.0, recall = 0.73, againstRecall = 0.71,
        valueError = BoardTraits.HEADS.associateWith { 0.38 }, againstError = heads, hands = 40, budget = 20_000, attempts = 1,
        why = "Its edge is not clear yet: more of the front on 15 hands, less on 13 (p 0.425, needed below 0.05).",
    )
    val ladder = TrainLadder(
        deckId = id,
        versions = listOf(
            NetVersion(1, TrainLoop.net(1), tier = "M", round = 1, records = 38_400, rated = Rated(1, 1181.0, 1064.0, 1315.0, 0, 40), promoted = true),
            NetVersion(2, TrainLoop.net(2), tier = "M", round = 2, records = 40_100, rated = Rated(2, 1198.0, 1089.0, 1306.0, 1, 40)),
        ),
        inUse = 1, attempts = 1, gates = listOf(first, second),
    )
    val log = listOf(
        TrainNote(1, TrainState.LOOP, "Network 1 passed the gate and is in use."),
        TrainNote(2, TrainState.LOOP, "Network 2 did not pass the gate: it stays a file."),
        TrainNote(3, TrainState.AI, "Set the training (the held-out objective was still falling): epochs 6"),
        TrainNote(4, TrainState.PERSON, "Started a round"),
        TrainNote(5, TrainState.LOOP, "Mapped round 3: 41,200 records"),
    )
    val running = moment != "paused"
    val state = TrainState(
        deckId = id, round = 3, phase = if (running) TrainState.TRAINING else TrainState.GATING, candidate = 3, trained = true, records = 41_200,
        config = TrainConfig(epochs = 6), said = if (running) "" else "Paused before the gate: Resume or Judge carries on.", log = log,
    )
    val progress = if (running) TrainLoop.Progress(TrainState.TRAINING, event = curves.steps.last()) else null
    h.training.demo(id, python, state, ladder, set, curves, progress)
    h.training.chosen = null
    h.mapper.tab = MapperTab.TRAINING
}
