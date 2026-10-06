package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import com.kaiharimoto.mastertool.core.ai.chessy.AmieParticles
import com.kaiharimoto.mastertool.core.ai.chessy.AmieReaction
import com.kaiharimoto.mastertool.core.ai.chessy.AmieZone
import com.kaiharimoto.mastertool.core.ai.chessy.AmieZones
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFit
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyRig
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyType
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.chessy.ChessyInk.particles
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Chessy's petting mode (kai, 2026-10: "an Easter egg … like Pokemon Amie, where Chessy becomes bigger and takes over the
 * centre of the screen and the user can pet and interact with her"): held on in the chat box, she comes out large in
 * the middle of the window, looks at your hand, and answers it (`core/ai/chessy`'s [ChessyAmie]): petted on her head,
 * tickled under her chin, her cheeks squished, an ear touched (it twitches), her bell flicked (it rings and swings),
 * held, or left alone. Foil hearts and sparkles rise where she was touched, and she says it in a square box under her,
 * with a kaomoji. A press outside her, Esc, Back or "Bye-bye" lets her go back to the chat box.
 */
@Composable
fun ChessyAmieLayer(ai: AiState) {
    val amie = ai.amie ?: return
    val c = Mu.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val particles = remember { AmieParticles() }
    val marks = remember { MarkList(AmieParticles.MAX) }
    val foils = remember { MarkFoils() }
    var frame by remember { mutableIntStateOf(0) } // read only in the draw
    var bursts by remember { mutableIntStateOf(0) } // wakes the particles' clock
    var mood by remember { mutableStateOf(Expression.FOUND) }
    var moodUntil by remember { mutableDoubleStateOf(0.0) }
    var line by remember { mutableStateOf("") }
    var lineAt by remember { mutableDoubleStateOf(0.0) }
    var typedUnits by remember { mutableIntStateOf(0) }
    // her aura's clock, in seconds, read only when drawn
    var auraT by remember { mutableFloatStateOf(0f) }
    var speaking by remember { mutableStateOf(false) }
    var lastHeart by remember { mutableDoubleStateOf(0.0) }
    val kick = remember { IntArray(2) } // [ear to twitch, bell to ring]
    val box = remember { FloatArray(4) } // her canvas in the window: left, top, width, height
    fun now() = System.nanoTime() / 1e9
    // where on the window a part of her sheet is, for the particles to rise from
    fun sheetToWindow(x: Float, y: Float): Offset {
        val f = ChessyFit.of(box[2], box[3], head = false)
        return Offset(box[0] + f[1] + x * f[0], box[1] + f[2] + y * f[0])
    }
    fun burst(at: Offset?, hearts: Int, sparkles: Int) {
        if (hearts + sparkles == 0) return
        val p = at ?: sheetToWindow(640f, 520f)
        particles.burst(p.x, p.y, hearts, sparkles, unit = with(density) { 16.dp.toPx() } * (box[2] / with(density) { 420.dp.toPx() }).coerceIn(.7f, 1.4f))
        bursts++
    }
    var talk: Job? by remember { mutableStateOf(null) }
    fun react(r: AmieReaction?, at: Offset? = null) {
        r ?: return
        mood = r.mood
        moodUntil = now() + r.seconds
        line = r.line
        lineAt = now()
        talk?.cancel()
        talk = scope.launch { speaking = true; delay(min(2200L, 300L + r.line.length * 38L)); speaking = false }
        if (r.ear != 0) kick[0] = r.ear
        if (r.ring) kick[1] = 1
        burst(at, r.hearts, r.sparkles)
    }
    LaunchedEffect(amie) {
        react(amie.greet(now()))
        // the studio's pictures: a hand played through the same grammar a real one goes through
        val demo = ai.amieDemo ?: return@LaunchedEffect
        withFrameNanos { }
        val head = sheetToWindow(640f, 480f)
        fun stroke(zone: AmieZone, at: Offset, n: Int) {
            var t = now()
            repeat(n) { i -> t += .06; react(amie.stroke(zone, if (i % 4 < 2) 22f else -22f, 0f, t), at); if (zone == AmieZone.HEAD && i % 6 == 0) burst(at, 1, 0) }
        }
        when (demo) {
            "pet" -> stroke(AmieZone.HEAD, head, 160)
            "tickle" -> stroke(AmieZone.CHIN, sheetToWindow(630f, 1290f), 40)
            "bell" -> react(amie.tap(AmieZone.BELL, now()), sheetToWindow(630f, 1470f))
            "ear" -> react(amie.tap(AmieZone.EAR_R, now()), sheetToWindow(1050f, 200f))
            "hug" -> react(amie.hold(AmieZone.HEAD, now()), head)
            "sulk" -> repeat(ChessyAmie.POKES) { react(amie.tap(AmieZone.FACE, now() + it * .1), sheetToWindow(630f, 960f)) }
        }
    }
    // her words typed as the takeover types them: a unit at a time, 26 a second, an emoticon whole
    LaunchedEffect(line, lineAt) {
        val units = ChessyType.layout(line).units
        typedUnits = 0
        while (typedUnits < units) {
            withFrameNanos { }
            typedUnits = ((now() - lineAt) * 26).toInt().coerceIn(0, units)
        }
    }
    // the aura breathes and tears while she is out
    LaunchedEffect(amie) { while (true) withFrameNanos { auraT = (it / 1_000_000L % 1_000_000L) / 1000f } }
    // left alone she wonders where you went, then dozes; a worn face goes back to listening
    LaunchedEffect(amie) {
        while (true) {
            delay(500)
            amie.idle(now())?.let { react(it) }
            if (now() > moodUntil && mood != Expression.SLEEPING) mood = Expression.LISTENING
        }
    }
    // the particles' clock, asleep when none are out
    LaunchedEffect(bursts) {
        var last = 0L
        while (particles.live.isNotEmpty()) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else (t - last) / 1e9f
                last = t
                particles.step(dt)
                frame++
            }
        }
    }
    fun zoneAt(local: Offset): AmieZone {
        val (x, y) = ChessyFit.toSheet(box[2], box[3], head = false, local.x, local.y)
        return AmieZones.at(x, y)
    }
    fun window(local: Offset) = Offset(box[0] + local.x, box[1] + local.y)

    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper.copy(alpha = .92f))
            .cursorPointer(caption = "Let her go")
            .pointerInput(amie) { detectTapGestures { ai.closeAmie() } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // about half the window (kai: "just 50% of the screen proportionally"), narrower where the window is
            val side = min(maxWidth.value * .8f, maxHeight.value * .5f).dp
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // her name and how fond of you she has grown: five foil hearts
                Fondness({ amie.fondness }, { frame })
                Box(
                    Modifier
                        .size(side)
                        .onGloballyPositioned { co -> val r = co.boundsInWindow(); box[0] = r.left; box[1] = r.top; box[2] = r.width; box[3] = r.height }
                        .chessyAura { auraT }
                        .cursorPointer(caption = "Pet")
                        // a press: a tap, a stroke (petting, tickling, a rub) or a hold (a hug)
                        .pointerInput(amie) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                var moved = 0f
                                var held = false
                                val slop = 10.dp.toPx()
                                val hold = scope.launch {
                                    delay((ChessyAmie.HOLD * 1000).toLong())
                                    if (moved < slop) { held = true; react(amie.hold(zoneAt(down.position), now()), window(down.position)) }
                                }
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull() ?: break
                                    if (!ch.pressed) { ch.consume(); break }
                                    val d = ch.position - ch.previousPosition
                                    moved += d.getDistance()
                                    if (moved >= slop) hold.cancel()
                                    if (d != Offset.Zero) {
                                        ch.consume()
                                        val zone = zoneAt(ch.position)
                                        react(amie.stroke(zone, d.x / density.density, d.y / density.density, now()), window(ch.position))
                                        // while a hand strokes her head, a heart now and then
                                        if (zone == AmieZone.HEAD && now() - lastHeart > .35) { lastHeart = now(); burst(window(ch.position), 1, 0) }
                                    }
                                }
                                hold.cancel()
                                if (!held && moved < slop) {
                                    val zone = zoneAt(down.position)
                                    if (zone == AmieZone.NONE) ai.closeAmie() else react(amie.tap(zone, now()), window(down.position))
                                }
                            }
                        }
                        // a mouse only passing over her pets her too, as it does Ai's face
                        .pointerInput(amie) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull() ?: continue
                                    if (e.type == PointerEventType.Move && ch.type == PointerType.Mouse && !ch.pressed) {
                                        val d = ch.position - ch.previousPosition
                                        if (d != Offset.Zero) react(amie.stroke(zoneAt(ch.position), d.x / density.density, d.y / density.density, now()), window(ch.position))
                                    }
                                }
                            }
                        },
                ) {
                    ChessyAvatar(
                        mood, side, talking = speaking, pointer = { ai.h.cursor.position },
                        rigHook = { rig: ChessyRig ->
                            if (kick[0] != 0) { rig.twitch(kick[0]); kick[0] = 0 }
                            if (kick[1] != 0) { rig.ring(); kick[1] = 0 }
                        },
                    )
                }
                // what she says, in the takeover's box: tilted a little, one way then the other, a short glitch as it lands
                if (line.isNotEmpty()) {
                    val tilt = if (line.hashCode() % 2 == 0) -1.5f else 1.5f
                    ChessySay(
                        line,
                        typedUnits,
                        ai.name,
                        tilt,
                        caretOn = { (auraT * 2f).toInt() % 2 == 0 },
                        modifier = Modifier.widthIn(max = maxOf(side, 280.dp)),
                        jitter = { val since = (now() - lineAt).toFloat(); if (since < .22f) (ChessyInk.hash((auraT * 30f).toInt(), 9) - .5f) * 10f else 0f },
                    )
                }
                MuButton("Bye-bye", { ai.closeAmie() })
            }
        }
        // the hearts and sparkles, over everything, never in the hand's way
        Canvas(Modifier.fillMaxSize()) {
            frame
            marks.clear()
            for (p in particles.live) {
                marks.add(if (p.heart) MarkShape.HEART else MarkShape.STAR, p.x, p.y, p.size, p.alpha, rot = if (p.heart) 0f else p.age * 120f)
            }
            val at = ai.h.cursor.position
            val light = if (at != null && size.width > 0f) Offset((at.x / size.width) * 2f - 1f, (at.y / size.height) * 2f - 1f) else Offset(-.4f, -.6f)
            particles(marks, light, foils)
        }
    }
}

/** Her name and five hearts, filled with foil as she grows fond of you; both read when drawn. */
@Composable
private fun Fondness(fondness: () -> Float, frame: () -> Int) {
    val c = Mu.colors
    val pips = remember { MarkList(5) }
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro(CHESSY_NAME, color = c.ink)
        // read in the draw: a particle's frame redraws the hearts, never recomposes the layer
        Canvas(Modifier.size(110.dp, 20.dp)) {
            frame()
            pips.clear()
            val full = (fondness() * 5f + .001f).toInt()
            for (i in 0 until 5) pips.add(MarkShape.HEART, (11f + i * 22f) * density, size.height / 2f, 8f * density, if (i < full) 1f else .22f)
            particles(pips, Offset(-.3f, -.5f))
        }
    }
}

/** What a held press on her in the chat box does while she is the assistant: her petting mode, and Ai's hold otherwise. */
internal fun AiState.holdFace(longer: Boolean): AvatarPlay.Reaction? = if (prefs.persona == com.kaiharimoto.mastertool.core.prefs.AiPrefs.PERSONA_CHESSY) { openAmie(); null } else play.hold(longer)
