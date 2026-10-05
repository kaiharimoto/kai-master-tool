package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AvatarPath
import com.kaiharimoto.mastertool.core.world.desk.AvatarPilot
import com.kaiharimoto.mastertool.core.world.desk.AvatarTarget
import com.kaiharimoto.mastertool.core.world.desk.AvatarTargets
import com.kaiharimoto.mastertool.core.world.desk.Desk
import com.kaiharimoto.mastertool.core.world.desk.DeskPoint
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.mastertool.core.world.desk.FocusDecision
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.avatar.AiAvatar
import com.kaiharimoto.neue.cursor.cursor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Ai on the desktop (1.1.x, `docs/world/DESKTOP.md` §5): Ai's own face from §4k′ — never a new character — small, going
 * to the thing it is about to use. kai's waiver: Master UI lets nothing move by itself, and kai waived that for named
 * things only (the cards' lean, zen, the dice); "a small Ai icon jumping around the desktop opening things" is the same
 * waiver for this one avatar, in this one file. `MasterUiLawTest.movementIsNamed` refuses movement anywhere else in
 * `neue/world/`. Windows still move only under the person's hand.
 *
 * The arithmetic is core's: [AvatarPilot] turns what Ai does into targets, [AvatarPath] hops between them (a quadratic
 * arc, Master UI's one easing, no spring) and follows a moving caret. This file owns the loop and the drawing:
 * - the loop runs only while a hop or a follow is unsettled, or a dwell is counting down, then stops (§5.4); asleep,
 *   nothing is drawn here at all — the taskbar's home cell shows the still `AiMark`;
 * - the position is a plain [FloatArray] written by the loop and read in `Modifier.offset { }`, the lean and the
 *   landing's squash in `graphicsLayer { }`, a tick read there: nothing recomposes as it moves (§4q).
 */

/** The avatar's state on the desk: where it is, where it goes, what it says. Plain where the loop writes, state where the page reads. */
class DeskAvatarState {
    internal val pilot = AvatarPilot()

    /** Where each target is on screen, reported from layout in dp from the World page's top-left. */
    val targets = AvatarTargets()

    internal var path: AvatarPath? = null

    /** x, y (the centre, dp), lean (°), squash (0–1 through the landing), alpha. Written by the loop, read in the draw. */
    internal val pose = floatArrayOf(0f, 0f, 0f, 0f, 1f)

    /** Bumped each frame the loop moves it: read in `offset {}` and `graphicsLayer {}`, so only placement and draw redo. */
    internal val tick = mutableIntStateOf(0)

    /** Asleep at home: drawn as the still mark in the taskbar, no loop (§5.4). */
    var asleep by mutableStateOf(true)
        private set

    /** The taskbar's line (§2.2): Asleep, Writing openings.js, Running openings.js, Waiting on you, Done. */
    var status by mutableStateOf(AvatarPilot.ASLEEP)
        private set

    /** The app whose window, icon or cell it is at or heading for; null at home. */
    var at by mutableStateOf<String?>(null)
        private set

    /** Where the system asks for less motion: a fade at the target instead of a hop (§5.5). */
    var reduced = false

    /** Held by the person's hand (§5.5): it follows the pointer, and goes back to its work when let go. */
    internal var held = false

    /** The studio's picture: the pose set by hand ([pose]), the loop leaves it. */
    internal var frozen = false

    internal val signal = Channel<Unit>(Channel.CONFLATED)

    private var current: AvatarTarget? = null
    private var point: DeskPoint? = null
    private var since = -1L
    private var squashFrom = -1L
    private var lastNow = 0L
    private var launchedAt: String? = null

    /** What Ai does, told by the desk: its way queued (§5.2). [decision] is the focus policy's for its window. */
    fun on(does: AiDoes, desk: Desk, decision: FocusDecision = FocusDecision.RAISE) {
        pilot.on(does, desk, decision)
        launchedAt = null
        refresh()
        signal.trySend(Unit)
    }

    /** Skip ahead (§5.5): the waiting targets dropped, the hop under way finished in 120 ms. */
    fun skip() {
        pilot.skip()
        path?.skip(lastNow)
        signal.trySend(Unit)
    }

    /** A target's place, from layout: the loop is woken only when the one it heads for moved. */
    fun report(app: String?, anchor: Anchor, rect: DeskRect, key: String? = null) {
        val was = targets.rect(app, anchor, key)
        if (was == rect) return
        targets.report(app, anchor, rect, key)
        val c = current ?: return
        if (c.app == app && (c.anchor == anchor || c.anchor == Anchor.LAUNCH) && c.key == key) signal.trySend(Unit)
    }

    /** [app]'s window went: its targets with it (its icon and cell stay). */
    fun forget(app: String) = targets.forget(app)

    /** Whether it stands at [app]'s icon or cell now: `arrive` waits for this before opening the window (§5.3). */
    fun standsAt(app: String): Boolean = launchedAt == app

    private fun refresh() {
        status = pilot.status
        asleep = pilot.asleep
        at = pilot.current?.app
    }

    /** Where [t] is now, from [here]: its own place, else its window's taskbar cell, else its title bar; null to stay. */
    private fun pointFor(t: AvatarTarget, here: DeskPoint): DeskPoint? =
        targets.point(t, here, SIZE) ?: t.app?.let { app ->
            targets.rect(app, Anchor.CELL)?.let { AvatarTargets.stand(it, Anchor.CELL, SIZE) }
                ?: targets.rect(app, Anchor.TITLE)?.let { AvatarTargets.stand(it, Anchor.TITLE, SIZE) }
        }

    private fun home(): DeskPoint? = targets.rect(null, Anchor.HOME)?.center

    /**
     * The travel loop: frames while a hop or a follow is unsettled, a plain wait while it dwells, and out once the pilot
     * has nothing more — the next [signal] starts it again.
     */
    internal suspend fun travel() {
        while (!frozen) {
            var rest = 0L
            var done = false
            withFrameNanos { n ->
                val now = n / 1_000_000L
                lastNow = now
                val p = path ?: AvatarPath(home() ?: DeskPoint.ZERO, reduced).also { path = it; it.step(now) }
                p.reduced = reduced
                if (held || frozen) {
                    done = true
                    return@withFrameNanos
                }
                val here = p.position
                val c = current
                val cPoint = c?.let { pointFor(it, here) }
                val arrived = c == null || cPoint == null || (!p.wantsFrames(now) && here.distanceTo(cPoint) < ARRIVED)
                val t = pilot.next(now, arrived)
                if (t != null && t !== c) {
                    current = t
                    since = -1L
                    val tp = pointFor(t, here)
                    point = tp
                    if (tp != null) p.hopTo(tp, now)
                } else if (t != null) {
                    val tp = pointFor(t, here)
                    if (tp != null && tp != point) {
                        point = tp
                        if (t.follow) p.follow(tp, now) else if (tp.distanceTo(p.target) > ARRIVED) p.hopTo(tp, now)
                    }
                }
                val at = p.step(now)
                val moving = p.wantsFrames(now)
                val now2 = current
                if (!moving && now2 != null && since < 0) {
                    since = now
                    if (now2.press) squashFrom = now
                    if (now2.anchor == Anchor.LAUNCH || now2.anchor == Anchor.ICON || now2.anchor == Anchor.CELL) launchedAt = now2.app
                }
                val squashing = squashFrom >= 0 && now - squashFrom < SQUASH_MS
                pose[0] = at.x.toFloat()
                pose[1] = at.y.toFloat()
                pose[2] = p.lean(now)
                pose[3] = if (squashing) ((now - squashFrom).toFloat() / SQUASH_MS) else 0f
                pose[4] = p.alpha(now)
                tick.intValue++
                refresh()
                if (!moving && !squashing) {
                    val dwell = now2?.dwell ?: AvatarPilot.DWELL_MS
                    val left = if (now2 == null) 0L else dwell - (now - since)
                    when {
                        left > 0 -> rest = left
                        pilot.next(now, true) === now2 -> done = true
                    }
                    refresh()
                }
            }
            if (done) break
            if (rest > 0) delay(minOf(rest, REST_STEP_MS))
        }
    }

    /** Picked up by the person: it rides the pointer at [x], [y] (dp). */
    internal fun hold(x: Float, y: Float) {
        held = true
        pose[0] = x
        pose[1] = y
        tick.intValue++
    }

    /** Let go: it hops back to its work, or home, from where it was dropped. */
    internal fun letGo() {
        held = false
        path = AvatarPath(DeskPoint(pose[0].toDouble(), pose[1].toDouble()), reduced)
        point = null
        current?.let { since = -1L }
        current = null
        signal.trySend(Unit)
    }

    /**
     * The studio's frozen hop (§12.4: "the hop frozen at a fraction"): from [from] to [to], [t] of the way through its
     * time, eased as the loop eases it.
     */
    fun pose(from: DeskPoint, to: DeskPoint, t: Double, status: String) {
        frozen = true
        val p = AvatarPath(from)
        p.hopTo(to, 0L)
        val ms = (AvatarPath.duration(from.distanceTo(to)) * t).toLong()
        val at = p.step(ms)
        pose[0] = at.x.toFloat()
        pose[1] = at.y.toFloat()
        pose[2] = p.lean(ms)
        pose[3] = 0f
        pose[4] = 1f
        asleep = false
        this.status = status
        tick.intValue++
    }

    companion object {
        /** The avatar on the desk (§5.1): the rig's glyph, head and eyes. A phone draws it at 24. */
        const val SIZE = 28.0
        const val PHONE_SIZE = 24.0

        /** The landing's squash. */
        const val SQUASH_MS = 120L

        private const val ARRIVED = 1.0

        /** A dwell is waited out in steps this long, without frames. */
        private const val REST_STEP_MS = 120L
    }
}

/**
 * The avatar, drawn over the World page (§5): the live glyph (`AiAvatar`, its own 30 fps loop sleeping between steps)
 * while Ai works and the avatar is shown; nothing while it sleeps at home. A click opens Thoughts; the pointer resting
 * on it pets it (`AvatarPlay`); a drag picks it up, and let go it hops back.
 */
@Composable
fun DeskAvatarLayer(h: NeueHolders, state: DeskAvatarState, size: Dp) {
    LaunchedEffect(state) {
        state.signal.trySend(Unit)
        for (wake in state.signal) state.travel()
    }
    val shown = h.neue.prefs.world.avatar
    if (!state.asleep && shown) {
        val ai = h.ai
        Box(
            Modifier
                .offset {
                    state.tick.intValue
                    IntOffset(((state.pose[0] - size.value / 2f) * density).roundToInt(), ((state.pose[1] - size.value / 2f) * density).roundToInt())
                }
                .size(size)
                .graphicsLayer {
                    state.tick.intValue
                    val k = state.pose[3]
                    val squash = if (k > 0f) sin(PI * k).toFloat() else 0f
                    transformOrigin = TransformOrigin(0.5f, 0.92f)
                    rotationZ = state.pose[2]
                    scaleX = 1f + 0.10f * squash
                    scaleY = 1f - 0.18f * squash
                    alpha = state.pose[4]
                }
                .cursor(CursorMode.DRAG, caption = "Thoughts", holdOnPress = true)
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            if (e.type == PointerEventType.Move && !state.held) {
                                e.changes.firstOrNull()?.let { ch ->
                                    val dx = ch.position.x - ch.previousPosition.x
                                    if (dx != 0f && !ch.pressed) ai.touched(ai.play.stroke(dx / density, ai.clock(), ai.mood.sleeping))
                                }
                            }
                        }
                    }
                }
                .pointerInput(state) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitPointerEvent()
                            if (down.type != PointerEventType.Press) continue
                            val first = down.changes.firstOrNull() ?: continue
                            first.consume()
                            var moved = 0f
                            var picked = false
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == first.id } ?: break
                                if (!ch.pressed) {
                                    ch.consume()
                                    break
                                }
                                moved += ch.positionChange().getDistance()
                                if (moved > viewConfiguration.touchSlop) picked = true
                                if (picked) {
                                    val d = ch.positionChange()
                                    ch.consume()
                                    state.hold(state.pose[0] + d.x / density, state.pose[1] + d.y / density)
                                }
                            }
                            if (picked) {
                                state.letGo()
                            } else {
                                // A click: Thoughts, at this moment.
                                h.world.desk.open(com.kaiharimoto.mastertool.core.world.desk.BuiltInApp.THOUGHTS.ref)
                            }
                        }
                    }
                },
        ) {
            AiAvatar(ai.face, size, pointer = { h.cursor.position }, name = ai.name)
        }
    }
}
