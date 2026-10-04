package com.kaiharimoto.neue.duel.dice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Opening
import com.kaiharimoto.mastertool.core.duel.dice.DiceRuns
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceStage
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.DieFaces
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors
import kotlin.math.cos
import kotlin.math.sin

/** A seat's two dice in the person's hand: carried to (x, y) on the table, turned [held], before the throw. */
data class DiceCarry(val seat: Int, val x: Float, val y: Float, val held: List<Quat>)

/** One die to draw: where in its seat's arena, how turned, the numbers on its faces. */
private data class DieShot(val seat: Int, val p: V3, val q: Quat, val label: List<Int>)

/** How the dice lie in front of the field before they are thrown: square to the table, a little turned. */
val RESTING: List<Quat> = listOf(
    yaw(0.32),
    // A quarter turn about x first, so the second die shows another face on top.
    (yaw(-0.27) * Quat(0.70710678, 0.70710678, 0.0, 0.0)).normalized(),
)

fun yaw(a: Double): Quat = Quat(cos(a / 2), 0.0, 0.0, sin(a / 2))

/**
 * The opening roll on the table (1.0.87): each seat's two dice — resting in front of its field, carried in the
 * person's hand, or thrown and tumbling across the field frame by frame — and, over the shared row, who has
 * rolled what and the winner's choice.
 *
 * The throw is [DiceSim]'s, run to rest at once and played back in real time; each die's faces are relabelled
 * ([DieFaces.relabel]) so the face the physics put on top reads the stamped value, chosen before the first frame is
 * drawn, so every frame shows the same die. Drawn in paper and ink: faces turned from the light shaded in steps of
 * ink, crisp ink edges and pips — no shadow, no colour.
 */
@Composable
internal fun OpeningDice(duels: Duels, s: DuelState, layout: DuelLayout, playsBoth: Boolean) {
    val o = s.opening ?: return
    if (o.decided) return
    val stage = remember(layout) { DiceStage(layout) }
    // Each throw played out once (1.0.92): the second seat's throw no longer plays the first seat's out again, and a
    // throw made here was run ahead off this thread ([DiceRuns.warm]) — the same run, frame for frame.
    val runs = remember(o.throws) { o.throws.map { t -> t?.let(DiceRuns::of) } }
    // A throw already on the table when it was first drawn (the page opened mid-roll) stands at rest; a new one plays.
    val known = remember { mutableStateMapOf<Int, DiceThrow?>().apply { o.throws.forEachIndexed { i, t -> put(i, t) } } }
    val started = remember { mutableStateMapOf<Int, Long>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(o.throws) {
        while (true) {
            val t = withFrameNanos { it }
            now = t
            o.throws.forEachIndexed { seat, th ->
                if (known[seat] != th) {
                    known[seat] = th
                    if (th == null) started.remove(seat) else started[seat] = t
                }
            }
            val rolling = runs.indices.filter { seat -> playing(runs[seat], started[seat], t) }.toSet()
            if (duels.diceRolling != rolling) duels.diceRolling = rolling
            if (rolling.isEmpty()) break
        }
    }
    DisposableEffect(Unit) { onDispose { duels.diceRolling = emptySet() } }
    fun elapsed(seat: Int): Double {
        // A throw just made, not yet met by a frame: its first frame, never a glimpse of where it ends.
        if (known[seat] != o.throws.getOrNull(seat)) return 0.0
        val start = started[seat] ?: return Double.MAX_VALUE
        return (now - start).coerceAtLeast(0L) / 1e9
    }
    fun settled(seat: Int): Boolean = runs.getOrNull(seat)?.let { elapsed(seat) >= it.duration } ?: false

    val carry = duels.diceCarry
    val shots = buildList {
        for (seat in 0..1) {
            val arena = stage.arena(seat) ?: continue
            val run = runs.getOrNull(seat)
            val values = o.dice.getOrNull(seat).orEmpty()
            when {
                carry != null && carry.seat == seat -> {
                    val at = stage.under(seat, carry.x, carry.y, DiceThrow.HELD)
                    carry.held.forEachIndexed { d, q ->
                        val side = if (d == 0) -DiceThrow.SPREAD / 2 else DiceThrow.SPREAD / 2
                        add(DieShot(seat, at + V3(side, 0.0, 0.0), q, DieFaces.STANDARD))
                    }
                }
                run != null && values.size == 2 -> {
                    val poses = run.at(elapsed(seat))
                    val first = run.frames.first().dice
                    poses.forEachIndexed { d, pose ->
                        // The faces in view as the dice left the hand keep their numbers where they can.
                        val seen = DieFaces.NORMALS.indices.filter { f -> faceSeen(stage, seat, first[d].p, first[d].q, f) }.toSet()
                        val label = DieFaces.relabel(run.up[d], values[d], DieFaces.STANDARD, seen, variety = o.round + d)
                        add(DieShot(seat, pose.p, pose.q, label))
                    }
                }
                else -> arena.rest.forEachIndexed { d, p -> add(DieShot(seat, p, RESTING[d], DieFaces.STANDARD)) }
            }
        }
    }
    val c = Mu.colors
    Canvas(Modifier.fillMaxSize().zIndex(DICE_Z)) {
        // Back to front: the die farthest from the eye first.
        shots.sortedByDescending { (stage.toTable(it.seat, it.p) - stage.eye).length }.forEach { drawDie(stage, it, c) }
    }
    // What a press on resting dice does, for the family cursor: the table's one arbiter takes the press itself.
    for (seat in 0..1) {
        if (carry != null || !o.waitsOn(seat) || !duels.mayRoll(seat, playsBoth)) continue
        val r = restBox(stage, seat) ?: continue
        Box(Modifier.zIndex(DICE_Z).offset(r.left.dp, r.top.dp).size(r.width.dp, r.height.dp).cursorPointer(caption = "Throw"))
    }
    OpeningPanel(duels, s, o, layout, playsBoth, settled = (0..1).map { o.thrown(it) && settled(it) })
}

private fun playing(run: DiceSim.Run?, start: Long?, now: Long): Boolean =
    run != null && start != null && (now - start) / 1e9 < run.duration

/** The z the dice are drawn at: over the cards and the chain well, under a carried card and the Spotlight. */
internal const val DICE_Z = 55f

/** Where [seat]'s resting dice are drawn, on the table in dp, grown a little to take a press: null when it has none. */
internal fun restBox(stage: DiceStage, seat: Int): com.kaiharimoto.mastertool.core.layout.Slot? {
    val arena = stage.arena(seat) ?: return null
    val pts = arena.rest.flatMap { p -> listOf(-0.75, 0.75).flatMap { dx -> listOf(-0.75, 0.75).map { dy -> stage.toTable(seat, p + V3(dx, dy, 0.0)) } } }
    val l = pts.minOf { it.x }.toFloat()
    val t = pts.minOf { it.y }.toFloat()
    return com.kaiharimoto.mastertool.core.layout.Slot(l, t, pts.maxOf { it.x }.toFloat() - l, pts.maxOf { it.y }.toFloat() - t)
}

private fun faceSeen(stage: DiceStage, seat: Int, p: V3, q: Quat, face: Int): Boolean {
    val n = stage.dirToTable(seat, q.rotate(DieFaces.NORMALS[face]))
    val at = stage.toTable(seat, p + q.rotate(DieFaces.NORMALS[face] * 0.5))
    return stage.faces(at, n)
}

/** The light, from above the person's left shoulder: a face's shade in ink is how far it turns from it. */
private val LIGHT = V3(-0.45, 0.35, 1.0).normalized()

/** A pip's outline: twelve points round a circle, for any face's plane. */
private val PIP_RING: List<Pair<Double, Double>> = List(12) { i -> val a = i * kotlin.math.PI / 6; cos(a) to sin(a) }

private fun DrawScope.drawDie(stage: DiceStage, die: DieShot, c: MuColors) {
    val px = density
    fun screen(body: V3): Offset {
        val seen = stage.project(stage.toTable(die.seat, die.p + die.q.rotate(body)))
        return Offset(seen.x * px, seen.y * px)
    }
    val scale = (stage.arena(die.seat)?.scale ?: 40f)
    val edge = (scale * 0.028f).coerceIn(1f, 1.75f) * px
    for (face in 0 until 6) {
        val normal = stage.dirToTable(die.seat, die.q.rotate(DieFaces.NORMALS[face]))
        val centre = stage.toTable(die.seat, die.p + die.q.rotate(DieFaces.NORMALS[face] * 0.5))
        if (!stage.faces(centre, normal)) continue
        val outline = Path().apply {
            DieFaces.corners(face).forEachIndexed { i, b -> val o = screen(b); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            close()
        }
        drawPath(outline, c.paper)
        shade(normal dot LIGHT, c)?.let { drawPath(outline, it) }
        // The pips, round on the face, drawn in its perspective.
        val (au, av) = DieFaces.axes(face)
        DieFaces.pips(die.label[face]).forEach { (u, v) ->
            val centrePip = DieFaces.pipAt(face, u, v)
            val pip = Path().apply {
                PIP_RING.forEachIndexed { i, (cu, sv) ->
                    val o = screen(centrePip + au * (cu * DieFaces.PIP_RADIUS) + av * (sv * DieFaces.PIP_RADIUS))
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
                close()
            }
            drawPath(pip, c.ink)
        }
        drawPath(outline, c.ink, style = Stroke(width = edge))
    }
}

/** Steps of ink over a face as it turns from the light: none on top, the faintest to the darkest down the sides. */
private fun shade(lit: Double, c: MuColors): Color? = when {
    lit > 0.8 -> null
    lit > 0.45 -> c.ink06
    lit > 0.1 -> c.ink12
    else -> c.ink25
}

/**
 * Over the shared row: who has thrown what, and the winner's choice. The sums show once each seat's dice have come
 * to rest — the log has them at once, the table lets them land first.
 */
@Composable
private fun OpeningPanel(duels: Duels, s: DuelState, o: Opening, l: DuelLayout, playsBoth: Boolean, settled: List<Boolean>) {
    val c = Mu.colors
    val chain = l[DuelSpot.Chain] ?: return
    val w = maxOf(PANEL_W, 0f).coerceAtMost(l.width - 16f)
    val left = (chain.centerX - w / 2f).coerceIn(8f, (l.width - w - 8f).coerceAtLeast(8f))
    val both = settled.all { it }
    val key = DeskShortcuts.chordFor(DeskAction.DUEL_ROLL)?.let(DeskShortcuts::kbd)
    // Over the shared row; where the table has room above it (a phone upright), there, off the dice's way.
    val above = l.spots.values.minOfOrNull { it.top } ?: 0f
    val top = if (above >= PANEL_ROOM) (above - PANEL_ROOM) / 2f + 4f else (chain.centerY - PANEL_H / 2f).coerceAtLeast(4f)
    Column(
        Modifier.zIndex(DICE_Z + 1f).offset(left.dp, top.dp).width(w.dp)
            .background(c.paper).border(1.dp, c.ink).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Micro(if (o.round > 1) "Opening roll · round ${o.round}" else "Opening roll", color = c.ink45, size = 10.sp)
        // The far seat above, the near seat below, as they sit.
        listOf(1 - l.bottom, l.bottom).filter { l.twoSided || it == l.bottom }.forEach { seat ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small(DuelWords.seatName(s, seat), Modifier.width(110.dp), color = c.ink, maxLines = 1)
                val dice = o.dice.getOrNull(seat).orEmpty()
                val words = when {
                    dice.isEmpty() -> "To throw"
                    !settled[seat] -> "Rolling…"
                    else -> "${dice[0]} + ${dice[1]} = ${dice.sum()}"
                }
                Mono(words, color = if (dice.isEmpty() || !settled[seat]) c.ink45 else c.ink, size = 13.sp)
            }
        }
        val w0 = o.winner
        when {
            w0 != null && both -> {
                Small("${DuelWords.seatName(s, w0)} wins the roll: first or second?", color = c.ink, maxLines = 2)
                if (duels.mayRoll(w0, playsBoth)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MuButton("Go first", { duels.goFirst(w0, true) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
                        MuButton("Go second", { duels.goFirst(w0, false) }, size = BtnSize.SM)
                    }
                } else Small("${DuelWords.seatName(s, w0)} chooses.", color = c.ink45)
            }
            o.tied && both -> Small("A tie at ${o.sum(0)}: both throw again.", color = c.ink)
            (0..1).any { o.waitsOn(it) && duels.mayRoll(it, playsBoth) } && key != null ->
                Small("Throw with a drag, a click, or $key", color = c.ink45)
        }
    }
}

private const val PANEL_W = 300f
private const val PANEL_H = 120f
/** The panel's room at its tallest: the room above the table it stands in instead of the shared row. */
private const val PANEL_ROOM = 170f
