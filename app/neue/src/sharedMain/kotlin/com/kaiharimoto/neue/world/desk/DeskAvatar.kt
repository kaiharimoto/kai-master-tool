package com.kaiharimoto.neue.world.desk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.AiNow
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.AvatarPath
import com.kaiharimoto.mastertool.core.world.desk.AvatarPilot
import com.kaiharimoto.mastertool.core.world.desk.AvatarStatus
import com.kaiharimoto.mastertool.core.world.desk.AvatarTarget
import com.kaiharimoto.mastertool.core.world.desk.AvatarTargets
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.Desk
import com.kaiharimoto.mastertool.core.world.desk.DeskPoint
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.mastertool.core.world.desk.FocusDecision
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.avatar.AiAvatar
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.world.type.Small
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

    /** At home or on its way there: the taskbar's line speaks for it, so its plate says nothing (§5.7). */
    var home by mutableStateOf(true)
        private set

    /** What it went to do where it stands (the pilot's, §5.7), and since when on the wall clock: what the plate says. */
    var doing by mutableStateOf<AvatarStatus?>(null)
        private set
    var doingSince = 0L
        private set

    /** A run's end, said beside it for its while ([AvatarStatus.hold]), and when on the wall clock. */
    var outcome by mutableStateOf<AvatarStatus?>(null)
        private set
    var outcomeAt = 0L
        private set

    /** The waiting pulse (0 drawn in, 1 breathed out): written by its own slow loop, read only in the ring's draw. */
    internal val breath = mutableFloatStateOf(0f)

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

    /** Where it is and what it heads for, in words: the studio's log. */
    fun describe(): String = "current ${pilot.current} behind ${pilot.behind} at ${path?.position} target ${path?.target} since $since frozen $frozen held $held"

    /** Whether it stands at [app]'s icon or cell now: `arrive` waits for this before opening the window (§5.3). */
    fun standsAt(app: String): Boolean = launchedAt == app

    /** Ai's run ended (`WorldDeskState.ran`): pleased, worried, or waiting on the person (Python off), for its while. */
    fun ran(label: String, ok: Boolean, error: String, at: Long = System.currentTimeMillis()) {
        outcome = AvatarStatus.ran(label, ok, error)
        outcomeAt = at
    }

    private fun refresh() {
        status = pilot.status
        asleep = pilot.asleep
        at = pilot.current?.app
        home = pilot.current?.anchor.let { it == null || it == Anchor.HOME }
        val d = pilot.doing
        if (d != doing) {
            doing = d
            doingSince = System.currentTimeMillis()
        }
    }

    /** The page it stands on (dp): a target past its edge (a caret scrolled off a phone) is met at the edge. */
    var bounds: DeskRect? = null

    /** Where [t] is now, from [here]: its own place, else its window's taskbar cell, else its title bar; null to stay. */
    private fun pointFor(t: AvatarTarget, here: DeskPoint): DeskPoint? {
        val p = targets.point(t, here, SIZE) ?: t.app?.let { app ->
            targets.rect(app, Anchor.CELL)?.let { AvatarTargets.stand(it, Anchor.CELL, SIZE) }
                ?: targets.rect(app, Anchor.TITLE)?.let { AvatarTargets.stand(it, Anchor.TITLE, SIZE) }
        } ?: return null
        val b = bounds ?: return p
        val half = SIZE / 2
        return DeskPoint(p.x.coerceIn(b.x + half, maxOf(b.x + half, b.right - half)), p.y)
    }

    private fun home(): DeskPoint? = targets.rect(null, Anchor.HOME)?.center

    /**
     * The travel loop: frames while a hop or a follow is unsettled, a plain wait while it dwells, and out once the pilot
     * has nothing more — the next [signal] starts it again.
     */
    internal suspend fun travel() {
        while (!frozen) {
            var rest = 0L
            withFrameNanos { n -> rest = frame(n / 1_000_000L) }
            if (rest < 0) break
            if (rest > 0) delay(minOf(rest, REST_STEP_MS))
        }
    }

    /**
     * One frame of the loop at [now] (ms): the pilot asked, the path stepped, the pose written. Returns 0 to be called
     * again next frame, a wait in ms while it dwells (no frames meanwhile), or -1 when there is nothing more to do.
     */
    internal fun frame(now: Long): Long {
        lastNow = now
        val p = path ?: AvatarPath(home() ?: DeskPoint.ZERO, reduced).also { path = it; it.step(now) }
        p.reduced = reduced
        if (held || frozen) return -1L
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
        if (moving || squashing) return 0L
        // The pilot counts its dwell from the frame after it saw the avatar arrive (at most one rest after [since]):
        // wait that out too, and never leave while a route is waiting, or the next one would wait for a signal.
        val dwell = (now2?.dwell ?: AvatarPilot.DWELL_MS) + REST_STEP_MS + FRAME_MS
        val left = if (now2 == null) 0L else dwell - (now - since)
        return when {
            left > 0 -> left
            pilot.behind > 0 -> FRAME_MS
            pilot.next(now, true) === now2 -> { refresh(); -1L }
            else -> 0L
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
    fun pose(from: DeskPoint, to: DeskPoint, t: Double, status: String, app: String? = null, doing: AvatarStatus? = null) {
        frozen = true
        this.doing = doing
        doingSince = 0L
        home = false
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
        this.at = app
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

        /** About a frame. */
        private const val FRAME_MS = 17L
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
        // What it is doing, in words, a face and a sign (§5.7): core's rules over the pilot's place and Ai's state.
        val status = avatarStatus(h, state)
        val prefs = h.neue.prefs.world
        val plate = AvatarStatus.plate(status, prefs.avatar, state.asleep, state.home, prefs.recede, h.world.desk.recedeBroken)
        val pulsing = plate != AvatarStatus.Plate.NONE && status?.pulses == true
        WaitingRing(state, size, pulsing)
        // A hand's moment (petting, a poke) answers in its own face; otherwise the face is the work's.
        val face = if (ai.handLine != null) ai.face else status?.expression ?: ai.face
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
                                h.world.desk.open(BuiltInApp.THOUGHTS.ref)
                            }
                        }
                    }
                },
        ) {
            AiAvatar(face, size, pointer = { h.cursor.position }, name = ai.name)
        }
        // The plate stands clear of the ring while it breathes.
        StatusPlate(state, if (pulsing) size + RING_OUT.dp else size, status, plate)
    }
}

/**
 * What the avatar says now (§5.7, [AvatarStatus.resolve]): its place's errand, a run's end for its while, another tool's
 * words, thinking between tools, a question waiting. Recomposes on Ai's state changing, never on a frame; a run's end is
 * looked at again once, when its while is up.
 */
@Composable
internal fun avatarStatus(h: NeueHolders, state: DeskAvatarState): AvatarStatus? {
    val ai = h.ai
    var looked by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val expires = AvatarStatus.expires(state.outcome, state.outcomeAt)
    LaunchedEffect(expires) {
        if (expires != null) {
            delay((expires - System.currentTimeMillis()).coerceAtLeast(0L) + 1L)
            looked = System.currentTimeMillis()
        }
    }
    val now = maxOf(looked, System.currentTimeMillis())
    val aiNow = AiNow(
        running = ai.running,
        tool = ai.tool,
        asking = ai.question != null || ai.confirm != null,
        // The mood table's own reading of a reply streaming: no recomposition per word.
        answering = ai.face == Expression.SPEAKING,
    )
    return AvatarStatus.resolve(state.doing, state.doingSince, state.outcome, state.outcomeAt, aiNow, now)
}

/** The waiting pulse (§5.7): a square ink ring round the avatar that breathes, slowly, while the person is wanted. */
@Composable
private fun WaitingRing(state: DeskAvatarState, avatar: Dp, pulsing: Boolean) {
    // Its own loop, 20 steps a second and only while it waits: nothing asks for frames otherwise (§4q). Reduced motion
    // keeps the ring still.
    LaunchedEffect(pulsing, state.reduced) {
        state.breath.floatValue = 0f
        if (!pulsing || state.reduced) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            state.breath.floatValue = AvatarStatus.breath(System.currentTimeMillis() - start)
            delay(BREATH_STEP_MS)
        }
    }
    if (!pulsing) return
    val ink = Mu.colors.ink
    val paper = Mu.colors.paper
    val room = avatar + RING_ROOM
    Box(
        Modifier
            .offset {
                state.tick.intValue
                IntOffset(((state.pose[0] - room.value / 2f) * density).roundToInt(), ((state.pose[1] - room.value / 2f) * density).roundToInt())
            }
            .size(room)
            .drawBehind {
                val b = state.breath.floatValue
                val side = (avatar.toPx() + (RING_IN + (RING_OUT - RING_IN) * b).dp.toPx())
                val at = (this.size.width - side) / 2f
                val fade = state.pose[4]
                // An ink line edged in paper, so it reads on a title bar's ink as on the page's paper.
                drawRect(paper, Offset(at, at), Size(side, side), alpha = fade, style = Stroke(width = 3.5.dp.toPx(), join = StrokeJoin.Miter))
                drawRect(ink, Offset(at, at), Size(side, side), alpha = (1f - 0.45f * b) * fade, style = Stroke(width = 1.5.dp.toPx(), join = StrokeJoin.Miter))
            },
    )
}

/**
 * The status beside the avatar (§5.7): its sign and its few words on a paper plate with an ink rule, in the label tier
 * (a file's name as itself, in mono), on the avatar's right — its left near the page's edge. It travels with the avatar:
 * placed in layout from the loop's pose, never recomposed as it moves. It fades out as the avatar idles, and folds to its
 * sign while the person's hands are on the page.
 */
@Composable
private fun StatusPlate(
    state: DeskAvatarState,
    /** The avatar's reach: its size, and the ring's while it breathes. */
    size: Dp,
    status: AvatarStatus?,
    plate: AvatarStatus.Plate,
) {
    // The last thing said stays drawn while it fades.
    val kept = remember { arrayOfNulls<AvatarStatus>(1) }
    val keptPlate = remember { arrayOf(AvatarStatus.Plate.FULL) }
    if (status != null && plate != AvatarStatus.Plate.NONE) {
        kept[0] = status
        keptPlate[0] = plate
    }
    val fade by animateFloatAsState(if (plate == AvatarStatus.Plate.NONE) 0f else 1f, tween(MuMotion.BASE, easing = MuMotion.ease), label = "avatar status")
    val said = kept[0] ?: return
    if (fade <= 0f && plate == AvatarStatus.Plate.NONE) return
    val phone = LocalPhone.current
    val c = Mu.colors
    val words = said.words(if (phone) AvatarStatus.CAPTION_PHONE else AvatarStatus.CAPTION_DESK)
    val full = keptPlate[0] == AvatarStatus.Plate.FULL
    Row(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints())
                layout(p.width, p.height) {
                    state.tick.intValue
                    val spot = AvatarStatus.place(
                        DeskPoint(state.pose[0].toDouble(), state.pose[1].toDouble()),
                        size.value.toDouble(),
                        (p.width / density).toDouble(),
                        (p.height / density).toDouble(),
                        state.bounds,
                    )
                    p.place((spot.x * density).roundToInt(), (spot.y * density).roundToInt())
                }
            }
            .graphicsLayer {
                state.tick.intValue
                val on = AvatarStatus.onPage(DeskPoint(state.pose[0].toDouble(), state.pose[1].toDouble()), state.bounds, size.value.toDouble())
                alpha = if (on) fade * state.pose[4] else 0f
            }
            .background(c.paper)
            .border(1.dp, c.ink)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = said.text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconView(WorldIcons.sign(said.sign), SIGN.dp, color = c.ink)
        if (full) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Small(words.verb, color = c.ink, maxLines = 1)
                words.subject?.let { s -> if (words.named) Mono(s, color = c.ink) else Small(s, color = c.ink, maxLines = 1) }
            }
        }
    }
}

/** The plate's sign (dp): two units of stroke on the 32 grid read as a 1 dp line. */
private const val SIGN = 16

/** The ring's room round the avatar, and its side's reach past it drawn in and breathed out (dp). */
private val RING_ROOM = 24.dp
private const val RING_IN = 8f
private const val RING_OUT = 20f

/** The breath is stepped this often: slow enough to cost nothing, smooth enough to read as breathing. */
private const val BREATH_STEP_MS = 50L
