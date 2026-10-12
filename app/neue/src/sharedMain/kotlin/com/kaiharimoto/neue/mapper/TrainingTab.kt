package com.kaiharimoto.neue.mapper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishWords
import com.kaiharimoto.mastertool.core.duel.mapper.train.Elo
import com.kaiharimoto.mastertool.core.duel.mapper.train.GateRecord
import com.kaiharimoto.mastertool.core.duel.mapper.train.ModelTier
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainCurves
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainEvent
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainLoop
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainState
import com.kaiharimoto.mastertool.core.duel.mapper.train.TrainWords
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The Training tab (Phase I, I.10; M.md §6, the desk's alone): the person's switch and Python, what the machine trains on,
 * the round as it runs — its curves drawn in ink — the gate's verdict with every number it read, and the ladder. Paper and
 * ink, nothing that moves but the breathing square while something runs.
 */

/** The page's subtitle on the Training tab: the deck, the network in use, and the step. */
internal fun trainingSubtitle(h: NeueHolders): String {
    if (!MapperTrainer.possible) return MapperTrainer.why
    val t = h.training
    val inUse = if (t.ladder.inUse == 0) "the hand-written order in use" else "network ${t.ladder.inUse} in use"
    val step = if (t.running) TrainState.words(t.progress?.phase ?: t.state.phase) else if (t.state.phase != TrainState.IDLE) "${TrainState.words(t.state.phase)}, paused" else null
    return listOfNotNull(h.builder.deckName.ifBlank { "No deck" }, inUse, step).joinToString(" · ")
}

/** The ladder's row above or below the one open. */
internal fun stepVersion(h: NeueHolders, by: Int) {
    val t = h.training
    val all = listOf(0) + t.ladder.versions.map { it.version }.sorted()
    val at = all.indexOf(t.chosen ?: 0).coerceAtLeast(0)
    t.chosen = all[(at + by).coerceIn(0, all.lastIndex)].takeIf { it > 0 }
}

@Composable
internal fun TrainingTab(h: NeueHolders, phone: Boolean) {
    if (!MapperTrainer.possible) {
        Column(Modifier.fillMaxSize().padding(if (phone) 16.dp else 32.dp)) { EmptyState("Training is the desk's.", MapperTrainer.why) }
        return
    }
    val t = h.training
    LaunchedEffect(h.builder.deckId, h.decksReload) { t.open(h.builder.deckId, force = true) }
    LaunchedEffect(t.prefs.on) { if (t.prefs.on && t.python == null && t.checking == null) t.check(probe = t.prefs.probe == null) }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Helper(h)
        }
        VRule(color = Mu.colors.ink12)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Now(h)
        }
        VRule(color = Mu.colors.ink12)
        Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Ladder(h)
        }
    }
}

/** The helper: the person's switch, their Python and torch, the machine and the plan. */
@Composable
private fun Helper(h: NeueHolders) {
    val t = h.training
    val c = Mu.colors
    val prefs = t.prefs
    Micro("The trainer", color = c.ink45)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MuSwitch(prefs.on, { t.switch(it) }, label = "Train on this computer")
        Small("Train on this computer", color = c.ink)
    }
    Help("It runs your own Python with torch, as a program of its own: off until you switch it on. Ai can never switch it on, change your plan, or promote a network the gate failed.")
    HRule()
    Micro("Python", color = c.ink45)
    var path by remember(prefs.python) { mutableStateOf(prefs.python) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MuInput(path, { path = it }, Modifier.weight(1f), placeholder = "python3 on the PATH", mono = true, dense = true, onSubmit = { t.setPython(path); t.check() })
        MuButton("Find", { t.setPython(path); t.check() }, size = BtnSize.SM, enabled = t.checking == null, reason = "Looking already")
    }
    when {
        t.checking != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Breathe()
            Small(t.checking!!, color = c.ink70)
        }
        t.python != null -> {
            Small(t.python!!.version, color = c.ink)
            t.missing?.let { m -> Small(if (m.isEmpty()) "torch, numpy, onnx and onnxruntime found." else TrainWords.missing(m), color = c.ink) }
        }
        prefs.on -> Small("Not looked for yet.", color = c.ink70)
        else -> Small("Switch it on to look for Python.", color = c.ink70)
    }
    t.checkSaid?.let { Help(it, color = c.ink70) }
    HRule()
    Micro("This machine", color = c.ink45)
    Small(prefs.probe?.let(TrainWords::probe) ?: "Not probed yet.", color = c.ink)
    MuButton(
        "Probe again", { t.check(probe = true) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE,
        enabled = prefs.on && t.checking == null && t.missing?.isEmpty() == true, reason = "Switch it on, with torch found",
    )
    Micro("The plan", color = c.ink45)
    Small(TrainWords.plan(t.plan), color = c.ink)
    PlanChoice(h)
}

/** The person's own plan: a tier and a batch, or the probe's. */
@Composable
private fun PlanChoice(h: NeueHolders) {
    val t = h.training
    val c = Mu.colors
    val mine = t.prefs.plan
    Help("Yours, if you set it: a new probe never replaces it, and Ai never changes it.", color = c.ink70)
    val tiers = listOf<ModelTier?>(null) + ModelTier.entries
    Segmented(mine?.tier, tiers, { it?.name ?: "The probe's" }, { tier ->
        t.setPlan(tier?.let { (mine ?: t.plan).copy(tier = it) })
    }, small = true)
    if (mine != null) {
        Segmented(mine.batch, listOf(32, 64, 128, 256), { "Batch $it" }, { b -> t.setPlan(mine.copy(batch = b)) }, small = true)
    }
}

/** What is happening: the round's step, its buttons, its curves, the last verdict and the run's log. */
@Composable
private fun Now(h: NeueHolders) {
    val t = h.training
    val c = Mu.colors
    val st = t.state
    val deck = h.builder.deckId
    if (deck == null) {
        EmptyState("Nothing to train yet.", "Save the deck first: its network is trained on its own cards, and kept with it.")
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        H2(if (st.round == 0) "No round yet" else "Round ${st.round}: ${TrainState.words(t.progress?.phase?.takeIf { t.running } ?: st.phase).lowercase()}")
        if (t.running) Breathe()
    }
    val refusal = t.refusal()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val paused = st.phase != TrainState.IDLE
        if (t.running) {
            MuButton("Pause", { t.pause(TrainState.PERSON) }, variant = BtnVariant.PRIMARY)
        } else {
            MuButton(if (paused) "Resume the round" else "Start a round", { t.start(TrainState.PERSON)?.let { t.said = it } }, variant = BtnVariant.PRIMARY, enabled = refusal == null, reason = refusal, arrow = true)
            if (st.phase == TrainState.GATING) MuButton("Judge the candidate", { t.gate(TrainState.PERSON)?.let { t.said = it } })
        }
    }
    t.progress?.takeIf { t.running }?.let { p -> ProgressLine(p) }
    t.said?.let { Small(it, color = c.ink) } ?: st.said.takeIf { it.isNotBlank() && !t.running }?.let { Small(it, color = c.ink) }
    HRule()
    Micro("This round's curves", color = c.ink45)
    // Read so the curves redraw as the trainer's lines arrive.
    val ticks = t.ticks
    key(ticks) { Curves(t.curves) }
    Small(TrainWords.curves(t.curves), color = c.ink70)
    HRule()
    val gate = t.ladder.lastGate
    Micro("The gate", color = c.ink45)
    if (gate == null) {
        Help(
            "A new network replaces the one in use only when it finds the boards nothing beats sooner, on held-out hands no round trains on, " +
                "predicts each trait no worse, and still finds every board you confirmed. Each round ends there.",
            color = c.ink70,
        )
    } else {
        Verdict(gate)
    }
    if (st.log.isNotEmpty()) {
        HRule()
        Micro("The run's log", color = c.ink45)
        st.log.takeLast(8).asReversed().forEach { n ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(
                    when (n.by) {
                        TrainState.AI -> h.ai.name
                        TrainState.PERSON -> "You"
                        else -> "Loop"
                    },
                    Modifier.width(48.dp), color = c.ink70,
                )
                Small(n.what, color = c.ink, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ProgressLine(p: TrainLoop.Progress) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val step = p.event as? TrainEvent.Step
        val words = buildString {
            append(TrainState.words(p.phase).takeIf { p.phase != TrainLoop.GATE_BUILD } ?: "Mapping the held-out hands")
            when {
                p.total > 0 -> append(": ").append(GoldfishWords.count(p.done)).append(" of ").append(GoldfishWords.count(p.total))
                p.done > 0 -> append(": ").append(GoldfishWords.count(p.done)).append(" mapped")
                step != null -> append(": step ").append(GoldfishWords.count(step.step)).append(", loss ").append(three(step.loss))
            }
            if (p.words.isNotBlank() && p.phase != TrainLoop.GATE_BUILD) append(" · ").append(p.words)
        }
        Small(words, color = c.ink)
        Progress(if (p.total > 0) p.done.toFloat() / p.total else null)
    }
}

/**
 * A round's curves in ink: the training loss as a line, the held-out objective as squares on it, over the steps. No
 * colour, no axis words beyond the two ends: the numbers are in the words below.
 */
@Composable
private fun Curves(curves: TrainCurves) {
    val c = Mu.colors
    val steps = curves.steps.filter { it.loss.isFinite() }
    val evals = curves.evals.filter { it.objective.isFinite() }
    if (steps.isEmpty() && evals.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(140.dp).border(1.dp, c.ink12), contentAlignment = Alignment.Center) {
            Help("The curves draw as the trainer reports.", color = c.ink70)
        }
        return
    }
    val values = steps.map { it.loss } + evals.map { it.objective }
    val hi = values.maxOrNull() ?: 1.0
    val lo = minOf(0.0, values.minOrNull() ?: 0.0)
    val last = maxOf(steps.maxOfOrNull { it.step } ?: 0, evals.maxOfOrNull { it.step } ?: 0).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(line = true, "Training loss")
            Legend(line = false, "Held-out objective")
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp).border(1.dp, c.ink12).padding(8.dp)) {
            fun x(step: Int) = size.width * step / last
            fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo).coerceAtLeast(1e-9))).toFloat()
            for (k in 1..3) drawLine(c.ink12, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4))
            if (steps.size >= 2) {
                val path = Path()
                steps.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.step), y(s.loss)) else path.lineTo(x(s.step), y(s.loss)) }
                drawPath(path, c.ink, style = Stroke(1.5.dp.toPx()))
            } else {
                steps.forEach { s -> drawRect(c.ink, Offset(x(s.step) - 2.dp.toPx(), y(s.loss) - 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx())) }
            }
            val side = 7.dp.toPx()
            evals.forEach { e ->
                val tl = Offset(x(e.step) - side / 2, y(e.objective) - side / 2)
                drawRect(c.paper, tl, Size(side, side))
                drawRect(c.ink, tl, Size(side, side), style = Stroke(1.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Mono("step 0", color = c.ink70)
            Mono("step ${GoldfishWords.count(last)}", color = c.ink70)
        }
    }
}

@Composable
private fun Legend(line: Boolean, words: String) {
    val c = Mu.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.width(18.dp).height(10.dp)) {
            if (line) drawLine(c.ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.5.dp.toPx())
            else drawRect(c.ink, Offset(size.width / 2 - 3.5.dp.toPx(), size.height / 2 - 3.5.dp.toPx()), Size(7.dp.toPx(), 7.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
        Help(words, color = c.ink70)
    }
}

/** A gate's verdict: passed or not, why, and every number it read beside its name. */
@Composable
private fun Verdict(g: GateRecord) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Inverted(g.promote) {
                Box(Modifier.background(Mu.colors.paper).border(1.dp, Mu.colors.ink).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Mono(if (g.promote) "PASSED" else "NOT PASSED", color = Mu.colors.ink)
                }
            }
            Small("Network ${g.candidate} against ${if (g.against == 0) "the hand-written order" else "network ${g.against}"}", color = c.ink)
        }
        Small(g.why, color = c.ink)
        val rows = listOf(
            "Held-out hands" to "${g.hands} at ${GoldfishWords.count(g.budget)} engine moves each",
            "More of the front" to "${g.wins} hands",
            "Less" to "${g.losses} hands",
            "As much" to "${g.ties} hands",
            "Sign test" to "p ${p(g.p)}, needed below ${p(g.bar)} (${g.attempts} tried since the last promotion)",
            "Front recall" to "${three(g.recall)} against ${three(g.againstRecall)}",
            "Search rating" to "${signed(g.elo)} (${signed(g.eloLow)} to ${signed(g.eloHigh)})",
        ) + listOfNotNull(
            g.valueError.takeIf { it.isNotEmpty() }?.let { v ->
                "Value error, by trait" to v.entries.joinToString(" · ") { (head, e) -> "$head ${three(e)} against ${three(g.againstError[head] ?: Double.NaN)}" }
            },
            if (g.regressed > 0) "Confirmed boards lost" to "${g.regressed}" else null,
        )
        rows.forEach { (k, v) ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Help(k, Modifier.width(150.dp), color = c.ink70)
                Small(v, Modifier.weight(1f), color = c.ink)
            }
        }
    }
}

/** The ladder: the hand-written order at its rating, then every network with its rating and range, the one in use marked. */
@Composable
private fun Ladder(h: NeueHolders) {
    val t = h.training
    val c = Mu.colors
    val l = t.ladder
    Micro("The ladder", color = c.ink45)
    Help("Search ratings: how much sooner a network's maps find the boards nothing beats, never how well it plays.", color = c.ink70)
    LadderRow(h, 0, "The hand-written order", "${Elo.BASE.roundToInt()}", "", l.inUse == 0, failed = false)
    l.versions.sortedByDescending { it.version }.forEach { v ->
        key(v.version) {
            val r = v.rated
            LadderRow(
                h, v.version, "Network ${v.version}",
                r?.elo?.roundToInt()?.toString() ?: "—",
                r?.let { "${it.low.roundToInt()}–${it.high.roundToInt()} · " }.orEmpty() + "tier ${v.tier.ifBlank { "?" }}, round ${v.round}",
                inUse = v.version == l.inUse,
                failed = r != null && !v.promoted,
            )
        }
    }
    t.chosen?.let { v -> l.gates.lastOrNull { it.candidate == v } }?.let { g ->
        HRule()
        Verdict(g)
    }
    HRule()
    Micro("Held out", color = c.ink45)
    Help(TrainWords.heldOut(l, t.gateSet).lines().first(), color = c.ink70)
}

@Composable
private fun LadderRow(h: NeueHolders, version: Int, name: String, rating: String, detail: String, inUse: Boolean, failed: Boolean) {
    val t = h.training
    val chosen = t.chosen == version && version > 0
    Inverted(chosen) {
        val c = Mu.colors
        Column(
            Modifier.fillMaxWidth().background(c.paper).border(1.dp, if (inUse || chosen) c.ink else c.ink12)
                .cursorPointer(caption = if (version == 0) null else "Its verdict", label = "$name, rated $rating")
                .muClickable { if (version > 0) t.chosen = if (chosen) null else version }.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Small(name, color = c.ink)
                Mono(rating, color = c.ink)
            }
            val status = when {
                inUse -> "In use"
                failed -> "Failed the gate: kept as a file"
                version > 0 && detail.isNotEmpty() && t.ladder.byVersion(version)?.rated == null -> "Not judged yet"
                else -> ""
            }
            if (detail.isNotEmpty()) Help(detail, color = c.ink70)
            if (status.isNotEmpty()) Help(status, color = c.ink)
        }
    }
}

private fun three(x: Double) = if (!x.isFinite()) "—" else ((x * 1000).roundToInt() / 1000.0).toString()

private fun signed(x: Double) = if (!x.isFinite()) "—" else (if (x >= 0) "+" else "−") + abs(x).roundToInt()

private fun p(x: Double) = if (x < 0.001) "< 0.001" else three(x)
