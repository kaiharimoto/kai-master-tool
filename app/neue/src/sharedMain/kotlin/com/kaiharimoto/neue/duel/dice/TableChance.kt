package com.kaiharimoto.neue.duel.dice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.Chance
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceStage
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.DieFaces
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.TossRuns
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.duel.Duels
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The table's die or coin in the person's hand (1.0.96): carried to (x, y), turned [held], before the throw. */
data class ChanceCarry(val seat: Int, val coin: Boolean, val x: Float, val y: Float, val held: Quat)

/** How the die lies at home by the Extra Deck: square to the table, a little turned. */
val DIE_HOME: Quat = yaw(0.42)

/** A throw from the corner glides out of its home over its first moment, so it never jumps there. */
private const val GLIDE = 0.14

internal fun shapeOf(coin: Boolean) = if (coin) DiceSim.Shape.COIN else DiceSim.Shape.DIE

/** Where [seat]'s die or coin is kept, as a point of its arena on the table (z: lying there). */
internal fun homeIn(stage: DiceStage, seat: Int, coin: Boolean): V3? {
    val home = stage.home(seat) ?: return null
    val (x, y) = if (coin) home.coin else home.die
    return stage.under(seat, x, y, shapeOf(coin).let { if (coin) DiceSim.COIN_H else 0.5 })
}

/** Where a die or coin lying at [p] in [seat]'s arena is drawn, dp. */
internal fun drawnAt(stage: DiceStage, seat: Int, p: V3): Offset = stage.project(stage.toTable(seat, p)).let { Offset(it.x, it.y) }

/**
 * Each seat's own die and coin (1.0.96, kai: "have a 3d dice and coin by the left side near the extra deck for both
 * players that the players can use in game for dice rolls and coin flips. clicking on one of them will bring them to the
 * field, just dragging them from the corner also works. the coin is thrown by dragging and throwing"): kept beside the
 * Extra Deck ([DiceStage.home]); carried in the hand; thrown — [DiceSim] run once ([TossRuns]) and played back in real
 * time — and lying where they landed, reading the stamped value, until the next move puts them back.
 *
 * The die is the opening roll's, drawn by [drawDie]; the coin a disc of paper with an ink rim, H on its heads and T on
 * its tails, which face is which chosen so the face the physics leaves up reads the stamped side.
 */
@Composable
internal fun TableChance(duels: Duels, s: DuelState, layout: DuelLayout, playsBoth: Boolean) {
    // Not while the opening roll is in play: its dice are the table's then.
    if (s.opening?.decided == false) return
    val stage = remember(layout) { DiceStage(layout) }
    val chance = s.chance
    val runs = remember(chance) { chance.associate { (it.seat to it.coin) to TossRuns.of(shapeOf(it.coin), it.toss) } }
    // Lying there when first drawn (the page opened, a replay stepped onto it): at rest. A new one plays.
    val known = remember { mutableStateMapOf<Pair<Int, Boolean>, Chance?>().apply { chance.forEach { put(it.seat to it.coin, it) } } }
    val started = remember { mutableStateMapOf<Pair<Int, Boolean>, Long>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(chance) {
        while (true) {
            val t = withFrameNanos { it }
            now = t
            (known.keys + chance.map { it.seat to it.coin }).toSet().forEach { k ->
                val c = chance.firstOrNull { (it.seat to it.coin) == k }
                if (known[k] != c) {
                    known[k] = c
                    if (c == null) started.remove(k) else started[k] = t
                }
            }
            val rolling = runs.filter { (k, run) -> started[k]?.let { (t - it) / 1e9 < run.duration } == true }.keys
            if (duels.chanceRolling != rolling) duels.chanceRolling = rolling
            if (rolling.isEmpty()) break
        }
    }
    DisposableEffect(Unit) { onDispose { duels.chanceRolling = emptySet() } }
    val carry = duels.chanceCarry
    val c = Mu.colors
    Canvas(Modifier.fillMaxSize().zIndex(DICE_Z)) {
        val t = now
        val things = ArrayList<Pair<Double, DrawScope.() -> Unit>>()
        fun add(seat: Int, at: V3, draw: DrawScope.() -> Unit) { things += (stage.toTable(seat, at) - stage.eye).length to draw }
        for (seat in 0..1) {
            if (stage.home(seat) == null) continue
            for (coin in listOf(false, true)) {
                val k = seat to coin
                val lying = chance.firstOrNull { it.seat == seat && it.coin == coin }
                val run = runs[k]
                when {
                    carry != null && carry.seat == seat && carry.coin == coin -> {
                        val at = stage.under(seat, carry.x, carry.y, DiceThrow.HELD)
                        add(seat, at) { if (coin) drawCoin(stage, seat, at, carry.held, headsSide = 1, c) else drawDie(stage, DieShot(seat, at, carry.held, DieFaces.STANDARD), c) }
                    }
                    lying != null && run != null -> {
                        val elapsed = when {
                            known[k] != lying -> 0.0
                            else -> started[k]?.let { (t - it).coerceAtLeast(0L) / 1e9 } ?: Double.MAX_VALUE
                        }
                        val pose = run.at(elapsed).single()
                        var p = pose.p
                        if (lying.toss.fromCorner && elapsed < GLIDE) homeIn(stage, seat, coin)?.let { from -> p = from + (p - from) * (elapsed / GLIDE) }
                        if (coin) {
                            // The face the physics leaves up reads the stamped side.
                            val side = if ((run.up.single() == 0) == lying.heads) 1 else -1
                            add(seat, p) { drawCoin(stage, seat, p, pose.q, side, c) }
                        } else {
                            val first = run.frames.first().dice.single()
                            val seen = DieFaces.NORMALS.indices.filter { f -> faceSeen(stage, seat, first.p, first.q, f) }.toSet()
                            val label = DieFaces.relabel(run.up.single(), lying.value, DieFaces.STANDARD, seen, variety = lying.value)
                            add(seat, p) { drawDie(stage, DieShot(seat, p, pose.q, label), c) }
                        }
                    }
                    else -> homeIn(stage, seat, coin)?.let { at ->
                        add(seat, at) { if (coin) drawCoin(stage, seat, at, Quat.IDENTITY, headsSide = 1, c) else drawDie(stage, DieShot(seat, at, DIE_HOME, DieFaces.STANDARD), c) }
                    }
                }
            }
        }
        // Back to front: the farthest from the eye first.
        things.sortedByDescending { it.first }.forEach { it.second(this) }
    }
    // What a press on them does, for the family cursor: the table's one arbiter takes the press itself.
    if (carry == null) for (seat in 0..1) {
        if (!duels.mayRoll(seat, playsBoth)) continue
        val home = stage.home(seat) ?: continue
        for (coin in listOf(false, true)) {
            val lying = chance.firstOrNull { it.seat == seat && it.coin == coin }
            val at = lying?.let { runs[it.seat to it.coin]?.rest?.single()?.p }?.let { drawnAt(stage, seat, it) }
                ?: (if (coin) home.coin else home.die).let { Offset(it.first, it.second) }
            val r = if (coin) home.coinRadius + home.size * 0.2f else home.size * 0.75f
            Box(
                Modifier.zIndex(DICE_Z).offset((at.x - r).dp, (at.y - r).dp).size((2 * r).dp)
                    .cursorPointer(caption = if (coin) "Flip" else "Roll"),
            )
        }
    }
}

/** Points round a coin's rim, as (cos, sin): thirty-two, for a round edge at any size it is drawn. */
private val RIM: List<Pair<Double, Double>> = List(32) { i -> val a = i * 2 * PI / 32; cos(a) to sin(a) }

/**
 * The coin at [p] in [seat]'s arena, turned [q]: its rim's band where it faces the eye, then the face toward the eye with
 * a ring and its letter — H on the side [headsSide] (+1 its +z face, −1 its −z face), T on the other. Paper and ink, shaded
 * in steps by how far each part turns from the light, as the die is.
 */
internal fun DrawScope.drawCoin(stage: DiceStage, seat: Int, p: V3, q: Quat, headsSide: Int, c: MuColors) {
    val px = density
    val r = DiceSim.COIN_R
    val h = DiceSim.COIN_H
    fun screen(body: V3): Offset {
        val seen = stage.project(stage.toTable(seat, p + q.rotate(body)))
        return Offset(seen.x * px, seen.y * px)
    }
    val scale = stage.arena(seat)?.scale ?: 40f
    val edge = (scale * 0.028f).coerceIn(1f, 1.75f) * px
    val up = stage.dirToTable(seat, q.rotate(V3.UP))
    fun faceVisible(side: Int): Boolean = stage.faces(stage.toTable(seat, p + q.rotate(V3(0.0, 0.0, side * h))), up * side.toDouble())
    fun rim(side: Int): Path = Path().apply {
        RIM.forEachIndexed { i, (cu, sv) -> val o = screen(V3(r * cu, r * sv, side * h)); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        close()
    }
    val near = when {
        faceVisible(1) -> 1
        faceVisible(-1) -> -1
        else -> 0
    }
    // The far face first, its outline the edge of the coin where the band does not cover it.
    val farSide = if (near == 0) -1 else -near
    val far = rim(farSide)
    drawPath(far, c.paper)
    drawPath(far, c.ink, style = Stroke(width = edge))
    // The band round the rim, a segment at a time where it faces the eye.
    for (i in RIM.indices) {
        val (c0, s0) = RIM[i]
        val (c1, s1) = RIM[(i + 1) % RIM.size]
        val mid = V3(r * (c0 + c1) / 2, r * (s0 + s1) / 2, 0.0)
        val n = stage.dirToTable(seat, q.rotate(mid.normalized()))
        if (!stage.faces(stage.toTable(seat, p + q.rotate(mid)), n)) continue
        val a = screen(V3(r * c0, r * s0, h))
        val b = screen(V3(r * c1, r * s1, h))
        val d = screen(V3(r * c1, r * s1, -h))
        val e = screen(V3(r * c0, r * s0, -h))
        val quad = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); lineTo(e.x, e.y); close() }
        drawPath(quad, c.paper)
        drawPath(quad, shade(n dot LIGHT, c) ?: c.ink06)
        drawLine(c.ink, if (near >= 0) e else a, if (near >= 0) d else b, strokeWidth = edge)
    }
    if (near == 0) return
    // The face toward the eye.
    val face = rim(near)
    drawPath(face, c.paper)
    shade((up * near.toDouble()) dot LIGHT, c)?.let { drawPath(face, it) }
    drawPath(face, c.ink, style = Stroke(width = edge))
    // Its ring and its letter, read the right way round from outside the face.
    val z = near * h
    val mirror = if (near > 0) 1.0 else -1.0
    // The arena's y runs toward the player, so the letter's up is its −y: upright to the seat that threw it.
    fun on(u: Double, v: Double): Offset = screen(V3(r * u * mirror, -r * v, z))
    val ring = Path().apply {
        RIM.forEachIndexed { i, (cu, sv) -> val o = on(0.78 * cu, 0.78 * sv); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        close()
    }
    drawPath(ring, c.ink, style = Stroke(width = edge * 0.8f))
    val strokes = if (near == headsSide) {
        listOf((-0.26 to -0.34) to (-0.26 to 0.34), (0.26 to -0.34) to (0.26 to 0.34), (-0.26 to 0.0) to (0.26 to 0.0))
    } else {
        listOf((-0.3 to 0.34) to (0.3 to 0.34), (0.0 to 0.34) to (0.0 to -0.34))
    }
    strokes.forEach { (a, b) -> drawLine(c.ink, on(a.first, a.second), on(b.first, b.second), strokeWidth = edge * 2.2f) }
}
