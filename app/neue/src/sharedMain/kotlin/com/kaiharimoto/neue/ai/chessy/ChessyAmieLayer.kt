package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.avatar.AvatarPlay
import com.kaiharimoto.mastertool.core.ai.avatar.Expression
import com.kaiharimoto.mastertool.core.ai.avatar.MarkList
import com.kaiharimoto.mastertool.core.ai.avatar.MarkShape
import com.kaiharimoto.mastertool.core.ai.chessy.AmieLove
import com.kaiharimoto.mastertool.core.ai.chessy.AmieParticles
import com.kaiharimoto.mastertool.core.ai.chessy.AmieReaction
import com.kaiharimoto.mastertool.core.ai.chessy.AmieZone
import com.kaiharimoto.mastertool.core.ai.chessy.AmieZones
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyAmie
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyFit
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyRig
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyType
import com.kaiharimoto.mastertool.core.ai.chessy.toys.PetToys
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyKind
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.chessy.ChessyInk.particles
import com.kaiharimoto.neue.ai.chessy.PetToysInk.catnip
import com.kaiharimoto.neue.ai.chessy.PetToysInk.feather
import com.kaiharimoto.neue.ai.chessy.PetToysInk.mouse
import com.kaiharimoto.neue.ai.chessy.PetToysInk.wand
import com.kaiharimoto.neue.ai.chessy.PetToysInk.yarn
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Chessy's petting mode (kai, 2026-10: "an Easter egg … like Pokemon Amie, where Chessy becomes bigger and takes over the
 * centre of the screen and the user can pet and interact with her"): held on in the chat box, she comes out large and
 * sits on the floor of her own room, looks at your hand, and answers it (`core/ai/chessy`'s [ChessyAmie]): petted on her
 * head, tickled under her chin, her cheeks squished, an ear touched (it twitches), her bell flicked (it rings and
 * swings), held, or left alone.
 *
 * Her room (kai: "expand more on the pet mode … this is our chance to captivate the user and make them fall in love
 * with our chessy's charms!"): a rug on the floor she sits on; **a toy box** of a yarn ball, a feather wand, a wind-up
 * mouse and a pouch of catnip ([PetToys], drawn by [PetToysInk] as the duel's dice are) — thrown, waved, wound and
 * given; **her favourite things**, found one by one ([AmieLove]); and her fondness in foil hearts. The pointer is a paw
 * here ([CursorMode.PAW]). One arbiter takes every press on the room, so a press is a toy's, a shelf's or hers and
 * never two. Esc, Back or Bye-bye lets her go back to the chat box.
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
    val toys = remember(amie) { PetToys() }
    var frame by remember { mutableIntStateOf(0) } // read only in the draw
    var toyFrame by remember { mutableIntStateOf(0) } // read only in the draw
    var bursts by remember { mutableIntStateOf(0) } // wakes the particles' clock
    var loved by remember { mutableIntStateOf(0) } // her favourite things, read again when one is found
    var mood by remember { mutableStateOf(Expression.FOUND) }
    var moodUntil by remember { mutableDoubleStateOf(0.0) }
    var line by remember { mutableStateOf("") }
    var lineAt by remember { mutableDoubleStateOf(0.0) }
    var typedUnits by remember { mutableIntStateOf(0) }
    // her aura's clock, in seconds, read only when drawn
    var auraT by remember { mutableFloatStateOf(0f) }
    var speaking by remember { mutableStateOf(false) }
    var lastHeart by remember { mutableDoubleStateOf(0.0) }
    var refill by remember { mutableIntStateOf(0) } // seconds until the catnip is full again
    val kick = remember { IntArray(2) } // [ear to twitch, bell to ring]
    val box = remember { FloatArray(4) } // her canvas in the room: left, top, width, height
    val origin = remember { FloatArray(2) } // the room in the window
    val slots = remember { Array(ToyKind.entries.size) { Rect.Zero } } // the toy box's slots, in the room
    fun now() = System.nanoTime() / 1e9
    // where on the room a part of her sheet is, for the particles to rise from
    fun sheetToRoom(x: Float, y: Float): Offset {
        val f = ChessyFit.of(box[2], box[3], head = false)
        return Offset(box[0] + f[1] + x * f[0], box[1] + f[2] + y * f[0])
    }
    fun burst(at: Offset?, hearts: Int, sparkles: Int) {
        if (hearts + sparkles == 0) return
        val p = at ?: sheetToRoom(640f, 520f)
        particles.burst(p.x, p.y, hearts, sparkles, unit = with(density) { 16.dp.toPx() } * (box[2] / with(density) { 420.dp.toPx() }).coerceIn(.7f, 1.4f))
        bursts++
    }
    var talk: Job? by remember { mutableStateOf(null) }
    fun react(r: AmieReaction?, at: Offset? = null) {
        loved = amie.loves.sum()
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
    fun head() = sheetToRoom(640f, 560f)
    fun towardHer(x: Float) = if (x < toys.room.middle) 1f else -1f
    LaunchedEffect(amie) {
        react(amie.greet(now()))
        // the studio's pictures: a hand played through the same grammar a real one goes through
        val demo = ai.amieDemo ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        fun stroke(zone: AmieZone, at: Offset, n: Int) {
            var t = now()
            repeat(n) { i -> t += .06; react(amie.stroke(zone, if (i % 4 < 2) 22f else -22f, 0f, t), at); if (zone == AmieZone.HEAD && i % 6 == 0) burst(at, 1, 0) }
        }
        val room = toys.room
        when (demo) {
            "pet" -> stroke(AmieZone.HEAD, head(), 160)
            "tickle" -> stroke(AmieZone.CHIN, sheetToRoom(630f, 1290f), 40)
            "bell" -> react(amie.tap(AmieZone.BELL, now()), sheetToRoom(630f, 1470f))
            "ear" -> react(amie.tap(AmieZone.EAR_R, now()), sheetToRoom(1050f, 200f))
            "hug" -> react(amie.hold(AmieZone.HEAD, now()), head())
            "sulk" -> repeat(ChessyAmie.POKES) { react(amie.tap(AmieZone.FACE, now() + it * .1), sheetToRoom(630f, 960f)) }
            "catnip" -> react(amie.nip(now()), head())
            "toys", "yarn", "mouse", "feather" -> {
                if (demo == "toys" || demo == "yarn") {
                    toys.yarn.out = true
                    toys.yarn.place(room.left + room.headR * .6f, room.headY - room.headR * .3f)
                    toys.yarn.release(1300f * room.unit, -500f * room.unit, room, toys.random())
                }
                if (demo == "toys" || demo == "mouse") {
                    toys.mouse.out = true
                    toys.mouse.facing = -1f
                    toys.mouse.place(room.middle + room.headR * 1.5f, room.floor - toys.mouse.height / 2f)
                    toys.mouse.wind()
                }
                if (demo == "toys" || demo == "feather") toys.wand.take(room.middle + room.headR * .9f, room.headY + room.headR * 1.1f, room)
                if (demo == "toys") react(amie.toy(ToyKind.YARN, com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyHit.NEAR, now()), head())
            }
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
    // left alone she wonders where you went, then dozes; catnip has its own beats; a worn face goes back to listening
    LaunchedEffect(amie) {
        while (true) {
            delay(500)
            amie.idle(now())?.let { react(it, head()) }
            if (now() > moodUntil && mood != Expression.SLEEPING) mood = Expression.LISTENING
            refill = kotlin.math.ceil(amie.nipRefill(now())).toInt()
        }
    }
    // the toys' clock, asleep when nothing in the room moves; what they do, she answers
    LaunchedEffect(toys) {
        var last = 0L
        while (true) {
            if (!toys.moving) { last = 0L; delay(80); continue }
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f)
                last = t
                for (e in toys.step(dt)) react(amie.toy(e.kind, e.hit, now()), Offset(e.x, e.y))
                toyFrame++
            }
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
    fun onHer(p: Offset): Boolean = p.x in box[0]..(box[0] + box[2]) && p.y in box[1]..(box[1] + box[3])
    fun zoneAt(p: Offset): AmieZone {
        if (!onHer(p)) return AmieZone.NONE
        val (x, y) = ChessyFit.toSheet(box[2], box[3], head = false, p.x - box[0], p.y - box[1])
        return AmieZones.at(x, y)
    }
    fun slotAt(p: Offset): ToyKind? = ToyKind.entries.firstOrNull { slots[it.ordinal].contains(p) }
    // where a toy rests in its slot: a little above the slot's middle, clear of its label
    fun rest(kind: ToyKind): Offset = slots[kind.ordinal].let { Offset(it.center.x, it.center.y - it.height * .1f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.paper)
            .onGloballyPositioned { co -> val p = co.positionInWindow(); origin[0] = p.x; origin[1] = p.y }
            .cursor(CursorMode.PAW, holdOnPress = true)
            // the one arbiter: a press is a toy's, a slot's or hers, decided where it lands
            .pointerInput(amie) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = down.position
                    val slop = 10.dp.toPx()
                    val room = toys.room
                    val y = toys.yarn
                    val m = toys.mouse
                    val onYarn = y.out && hypot(start.x - y.x, start.y - y.y) < y.radius * 1.6f
                    val onMouse = !onYarn && m.out && abs(start.x - m.x) < m.length * .6f && abs(start.y - m.y) < m.height * 1.2f
                    val slot = if (onYarn || onMouse) null else slotAt(start)
                    val zone = if (onYarn || onMouse || slot != null) AmieZone.NONE else zoneAt(start)
                    if (!onYarn && !onMouse && slot == null && zone == AmieZone.NONE) return@awaitEachGesture
                    down.consume()
                    // the hand's last few places, for a throw's speed
                    val trail = ArrayDeque<Triple<Long, Float, Float>>()
                    fun track(t: Long, p: Offset) { trail.addLast(Triple(t, p.x, p.y)); while (trail.size > 2 && t - trail.first().first > 90) trail.removeFirst() }
                    fun throwSpeed(): Offset {
                        if (trail.size < 2) return Offset.Zero
                        val a = trail.first()
                        val b = trail.last()
                        val dt = ((b.first - a.first).coerceAtLeast(8)) / 1000f
                        return Offset((b.second - a.second) / dt, (b.third - a.third) / dt)
                    }
                    track(down.uptimeMillis, start)
                    if (zone != AmieZone.NONE) {
                        // her: a tap, a stroke (petting, tickling, a rub) or a hold (a hug)
                        var moved = 0f
                        var held = false
                        val hold = scope.launch {
                            delay((ChessyAmie.HOLD * 1000).toLong())
                            if (moved < slop) { held = true; react(amie.hold(zoneAt(start), now()), start) }
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
                                val z = zoneAt(ch.position)
                                react(amie.stroke(z, d.x / density.density, d.y / density.density, now()), ch.position)
                                if (z == AmieZone.HEAD && now() - lastHeart > .35) { lastHeart = now(); burst(ch.position, 1, 0) }
                            }
                        }
                        hold.cancel()
                        if (!held && moved < slop) react(amie.tap(zone, now()), start)
                        return@awaitEachGesture
                    }
                    // a toy: in the room, or taken from its slot once the hand moves
                    var taken: ToyKind? = when {
                        onYarn -> ToyKind.YARN.also { y.held = true }
                        onMouse -> ToyKind.MOUSE.also { m.held = true }
                        else -> null
                    }
                    var moved = 0f
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull() ?: break
                        if (!ch.pressed) { ch.consume(); break }
                        ch.consume()
                        val p = ch.position
                        moved += (p - ch.previousPosition).getDistance()
                        track(ch.uptimeMillis, p)
                        if (taken == null && slot != null && moved >= slop) {
                            taken = slot
                            when (slot) {
                                ToyKind.YARN -> { y.out = true; y.place(p.x, p.y); y.held = true }
                                ToyKind.MOUSE -> { m.out = true; m.facing = towardHer(p.x); m.place(p.x, p.y); m.held = true }
                                ToyKind.FEATHER -> toys.wand.take(p.x, p.y, room)
                                ToyKind.CATNIP -> { toys.catnip.held = true }
                            }
                        }
                        when (taken) {
                            ToyKind.YARN -> { y.x = p.x; y.y = p.y.coerceAtMost(room.floor - y.radius); y.vx = 0f; y.vy = 0f }
                            ToyKind.MOUSE -> { m.x = p.x; m.y = p.y.coerceAtMost(room.floor - m.height / 2f) }
                            ToyKind.FEATHER -> { toys.wand.hx = p.x; toys.wand.hy = p.y }
                            ToyKind.CATNIP -> { toys.catnip.x = p.x; toys.catnip.y = p.y }
                            null -> Unit
                        }
                        toyFrame++
                    }
                    val v = throwSpeed()
                    val u = room.unit
                    when {
                        // let go of a toy carried
                        taken == ToyKind.YARN && moved >= slop -> y.release(v.x, v.y, room, toys.random())
                        taken == ToyKind.MOUSE && moved >= slop -> m.release(v.x, v.y, room)
                        taken == ToyKind.FEATHER -> toys.wand.held = false
                        taken == ToyKind.CATNIP -> {
                            toys.catnip.held = false
                            if (toys.catnip.over(room)) react(amie.nip(now()), head())
                        }
                        // a tap on a toy in the room: the yarn hops toward her, the mouse is wound again
                        onYarn -> { y.held = false; y.vx = towardHer(y.x) * 260f * u; y.vy = -700f * u }
                        onMouse -> { m.held = false; m.facing = towardHer(m.x); m.wind() }
                        // a tap on a slot: a toy out to her, or back in the box
                        slot == ToyKind.YARN -> if (y.out) y.out = false else {
                            val r = rest(ToyKind.YARN)
                            y.out = true
                            y.place(r.x, r.y)
                            y.release((room.middle - r.x) * 1.5f, -1100f * u, room, toys.random())
                        }
                        slot == ToyKind.MOUSE -> if (m.out) m.out = false else {
                            val r = rest(ToyKind.MOUSE)
                            m.out = true
                            m.facing = towardHer(r.x)
                            m.place(r.x.coerceIn(room.left + m.length, room.right - m.length), room.floor - m.height / 2f)
                            m.wind()
                        }
                        slot == ToyKind.CATNIP -> react(amie.nip(now()), head())
                        else -> Unit
                    }
                    toyFrame++
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
                            if (d != Offset.Zero) react(amie.stroke(zoneAt(ch.position), d.x / density.density, d.y / density.density, now()), ch.position)
                        }
                    }
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val px = density.density
            val compact = maxWidth < 640.dp
            val roomy = maxWidth >= 980.dp
            // the floor she sits on, the toy box beside her (under her on a narrow window), her favourite things
            val floorY = h - (if (compact) 150f else 84f) * px
            val topY = (if (compact) 120f else 72f) * px
            val sit = 1660f / ChessyFit.SHEET_H
            val side = min(w * (if (compact) .9f else .5f), (floorY - topY) / sit)
            val left = (w - side) / 2f
            val top = floorY - side * sit
            box[0] = left; box[1] = top; box[2] = side; box[3] = side
            val fit = ChessyFit.of(side, side, head = false)
            toys.room.apply {
                this.left = 0f; this.right = w; this.top = 0f; floor = floorY; unit = px
                headX = left + fit[1] + 640f * fit[0]; headY = top + fit[2] + 760f * fit[0]; headR = 520f * fit[0]
                bellX = left + fit[1] + 630f * fit[0]; bellY = top + fit[2] + 1475f * fit[0]; bellR = 130f * fit[0]
            }
            toys.scale(if (compact) .7f else 1f)

            // the room behind her: the floor, a rug, paw prints wandering across
            Canvas(Modifier.fillMaxSize()) { room(c, floorY, w, left + side / 2f, min(side * .4f, w * .44f), px) }

            // her, on the rug, swaying while catnip has her
            Box(
                Modifier
                    .offset { IntOffset(left.toInt(), top.toInt()) }
                    .size(with(density) { side.toDp() })
                    .graphicsLayer {
                        auraT
                        rotationZ = amie.wobble(now())
                        transformOrigin = TransformOrigin(.5f, sit)
                    }
                    .chessyAura { auraT }
                    .cursor(CursorMode.PAW, caption = "Pet", holdOnPress = true),
            ) {
                ChessyAvatar(
                    mood, with(density) { side.toDp() }, talking = speaking,
                    pointer = { toys.focus()?.let { (x, y) -> Offset(origin[0] + x, origin[1] + y) } ?: ai.h.cursor.position },
                    rigHook = { rig: ChessyRig ->
                        if (kick[0] != 0) { rig.twitch(kick[0]); kick[0] = 0 }
                        if (kick[1] != 0) { rig.ring(); kick[1] = 0 }
                    },
                )
            }

            // the toy box: four slots down the right, or along the bottom on a narrow window
            val slotModifier = Modifier.size(if (compact) 76.dp else 128.dp, if (compact) 76.dp else 108.dp)
            val shelf: @Composable () -> Unit = {
                for (kind in ToyKind.entries) {
                    val faded = kind == ToyKind.CATNIP && refill > 0
                    Box(
                        slotModifier
                            .onGloballyPositioned { co ->
                                val r = co.boundsInWindow()
                                slots[kind.ordinal] = Rect(r.left - origin[0], r.top - origin[1], r.right - origin[0], r.bottom - origin[1])
                            }
                            .cursor(CursorMode.DRAG, caption = if (kind == ToyKind.FEATHER) "Drag to wave" else kind.verb, holdOnPress = true),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Canvas(Modifier.fillMaxSize()) { cropMarks(c.ink25, 10.dp.toPx(), 1.dp.toPx()) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 6.dp)) {
                            Micro(if (compact) kind.short else kind.title, color = c.ink70, size = if (compact) 9.sp else 10.sp)
                            if (faded) Mono("${refill}s", color = c.ink45, size = 9.sp)
                        }
                    }
                }
            }
            if (compact) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { shelf() }
            } else {
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Micro("Toy box", color = c.ink)
                    shelf()
                }
            }

            // the toys: at rest in the box, and out in the room in front of her
            Canvas(Modifier.fillMaxSize()) {
                toyFrame
                drawToys(toys, c, px, refill > 0) { rest(it) }
            }

            // her favourite things, found one by one
            if (roomy) Favourites(amie.loves, loved, Modifier.align(Alignment.CenterStart).padding(start = 28.dp).width(208.dp))
            // on a phone the room above her holds them, two to a row, where there is room
            if (compact && top / px > 330f) Favourites(amie.loves, loved, Modifier.padding(top = 150.dp, start = 20.dp, end = 20.dp).fillMaxWidth(), columns = 2)

            // what she says, in the takeover's box: beside her head (over her on a narrow window), tilted a little
            if (line.isNotEmpty()) {
                val tilt = if (line.hashCode() % 2 == 0) -1.5f else 1.5f
                val say: @Composable (Modifier) -> Unit = { mod ->
                    ChessySay(
                        line, typedUnits, ai.name, tilt,
                        caretOn = { (auraT * 2f).toInt() % 2 == 0 },
                        modifier = mod,
                        jitter = { val since = (now() - lineAt).toFloat(); if (since < .22f) (ChessyInk.hash((auraT * 30f).toInt(), 9) - .5f) * 10f else 0f },
                    )
                }
                if (compact) {
                    Box(Modifier.fillMaxWidth().padding(top = 64.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) { say(Modifier.widthIn(max = 360.dp)) }
                } else {
                    val headTop = with(density) { (top + side * .1f).toDp() }
                    val sheetLeft = left + fit[1] + 120f * fit[0]
                    val sheetRight = left + fit[1] + 1200f * fit[0]
                    if (tilt < 0f) {
                        Box(Modifier.offset(y = headTop).width(with(density) { sheetLeft.toDp() }).padding(start = 248.dp, end = 4.dp), contentAlignment = Alignment.TopEnd) {
                            say(Modifier.widthIn(max = 300.dp))
                        }
                    } else {
                        Box(Modifier.offset(x = with(density) { sheetRight.toDp() }, y = headTop).width(with(density) { (w - sheetRight).toDp() }).padding(start = 4.dp, end = 160.dp), contentAlignment = Alignment.TopStart) {
                            say(Modifier.widthIn(max = 300.dp))
                        }
                    }
                }
            }

            // her name, how fond of you she has grown, how many of her favourite things you found; and Bye-bye
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Fondness({ amie.fondness }, { frame })
                    if (!roomy) Mono("${amie.found}/${AmieLove.entries.size} ♡", color = c.ink45)
                }
                MuButton("Bye-bye", { ai.closeAmie() })
            }
            if (!compact) {
                Micro(
                    "Pet her  ·  throw the yarn  ·  wave the feather  ·  wind the mouse  ·  give her catnip",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                    color = c.ink45,
                )
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

/** The room: a hairline floor with crop marks at its ends, a woven rug under her, paw prints wandering to her. */
private fun DrawScope.room(c: MuColors, floorY: Float, w: Float, mid: Float, rugHalf: Float, px: Float) {
    // paw prints: a cat's walk across the floor to the rug, faint
    val step = 34f * px
    var x = 40f * px
    var k = 0
    while (x < mid - rugHalf - 20f * px) {
        val yy = floorY + (if (k % 2 == 0) 22f else 40f) * px
        printAt(c, x, yy, px)
        x += step
        k++
    }
    // the floor
    drawLine(c.ink25, Offset(24f * px, floorY), Offset(w - 24f * px, floorY), strokeWidth = px)
    for (end in listOf(24f * px, w - 24f * px)) drawLine(c.ink, Offset(end, floorY - 6f * px), Offset(end, floorY + 6f * px), strokeWidth = 1.5f * px)
    // the rug: a flat oval seen across the floor, woven rings and a fringe
    val rh = 22f * px
    drawOval(c.paper, Offset(mid - rugHalf, floorY - rh * .5f), androidx.compose.ui.geometry.Size(rugHalf * 2f, rh * 2f))
    drawOval(c.ink06, Offset(mid - rugHalf, floorY - rh * .5f), androidx.compose.ui.geometry.Size(rugHalf * 2f, rh * 2f))
    for (ring in 1..3) {
        val k2 = 1f - ring * .2f
        drawOval(c.ink12, Offset(mid - rugHalf * k2, floorY - rh * .5f + rh * (1f - k2)), androidx.compose.ui.geometry.Size(rugHalf * 2f * k2, rh * 2f * k2), style = Stroke(px))
    }
    drawOval(c.ink45, Offset(mid - rugHalf, floorY - rh * .5f), androidx.compose.ui.geometry.Size(rugHalf * 2f, rh * 2f), style = Stroke(1.2f * px))
    for (i in -3..3) {
        val fx = mid + i * rugHalf * .14f
        drawLine(c.ink25, Offset(fx, floorY + rh * 1.48f), Offset(fx, floorY + rh * 1.48f + 6f * px), strokeWidth = px)
    }
}

/** A paw print on the floor, faint: a pad and four toes. */
private fun DrawScope.printAt(c: MuColors, x: Float, y: Float, px: Float) {
    drawOval(c.ink06, Offset(x - 5f * px, y - 2f * px), androidx.compose.ui.geometry.Size(10f * px, 7f * px))
    for ((dx, dy) in listOf(-6f to -6f, -2f to -9f, 2f to -9f, 6f to -6f)) drawCircle(c.ink06, 2.2f * px, Offset(x + dx * px, y + dy * px))
}

/** Four crop marks at the corners of this canvas, [arm] long, [weight] thick: a slot of the toy box. */
private fun DrawScope.cropMarks(colour: androidx.compose.ui.graphics.Color, arm: Float, weight: Float) {
    val r = size.width
    val b = size.height
    for (corner in 0 until 4) {
        val cx = if (corner == 1 || corner == 2) r else 0f
        val cy = if (corner >= 2) b else 0f
        val dx = if (cx > 0f) -1f else 1f
        val dy = if (cy > 0f) -1f else 1f
        drawLine(colour, Offset(cx, cy), Offset(cx + dx * arm, cy), strokeWidth = weight)
        drawLine(colour, Offset(cx, cy), Offset(cx, cy + dy * arm), strokeWidth = weight)
    }
}

/** Every toy where it is: in its slot ([rest]) when not out, else in the room; the one in the hand last. */
private fun DrawScope.drawToys(toys: PetToys, c: MuColors, px: Float, nipFaded: Boolean, rest: (ToyKind) -> Offset) {
    val y = toys.yarn
    val m = toys.mouse
    val w = toys.wand
    val nip = toys.catnip
    // in the box
    if (!y.out) rest(ToyKind.YARN).let { yarn(it.x, it.y, y.radius, y.q, c, null, px) }
    if (!m.out) rest(ToyKind.MOUSE).let { mouse(it.x, it.y + m.height * .2f, m.length, com.kaiharimoto.mastertool.core.duel.dice.Quat(0.97, 0.0, 0.26, 0.0).normalized(), .8f, c, null, px) }
    if (!w.held) rest(ToyKind.FEATHER).let { r ->
        val hx = r.x - w.stick * .22f
        val hy = r.y + w.stick * .12f
        val tx = r.x + w.stick * .08f
        val ty = r.y - w.stick * .1f
        wand(hx, hy, tx, ty, null, 0f, w.stick * .26f, c, px)
    }
    if (!nip.held) rest(ToyKind.CATNIP).let { catnip(it.x, it.y, nip.size, c, px, faded = nipFaded) }
    // a toy out in the room leaves its place in the box drawn faintly, so the box shows what is missing
    val ghost = Stroke(1.2f * px, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * px, 4f * px)))
    if (y.out) rest(ToyKind.YARN).let { drawCircle(c.ink25, y.radius, it, style = ghost) }
    if (m.out) rest(ToyKind.MOUSE).let { drawOval(c.ink25, Offset(it.x - m.length * .5f, it.y - m.height * .3f), androidx.compose.ui.geometry.Size(m.length, m.height * .9f), style = ghost) }
    if (w.held) rest(ToyKind.FEATHER).let { drawLine(c.ink25, Offset(it.x - w.stick * .22f, it.y + w.stick * .12f), Offset(it.x + w.stick * .08f, it.y - w.stick * .1f), strokeWidth = 1.2f * px, pathEffect = ghost.pathEffect) }
    if (nip.held) rest(ToyKind.CATNIP).let { drawRect(c.ink25, Offset(it.x - nip.size / 2f, it.y - nip.size / 2f), androidx.compose.ui.geometry.Size(nip.size, nip.size), style = ghost) }
    // out in the room
    if (m.out) mouse(m.x, m.y + m.hop, m.length, m.q, m.key, c, m.tail, px)
    if (y.out) yarn(y.x, y.y, y.radius, y.q, c, y.strand, px)
    if (w.held) {
        val (tx, ty) = w.tip(toys.room)
        wand(w.hx, w.hy, tx, ty, w.string, w.speed, w.stick * .3f, c, px)
    }
    if (nip.held) catnip(nip.x, nip.y, nip.size, c, px)
}

/** Her favourite things: each found one by its name and how often she answered it, the rest still a secret. */
@Composable
private fun Favourites(loves: IntArray, version: Int, modifier: Modifier, columns: Int = 1) {
    val c = Mu.colors
    version
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Micro("Her favourite things", color = c.ink)
            Mono("${loves.count { it > 0 }}/${loves.size}", color = c.ink45)
        }
        Canvas(Modifier.fillMaxWidth().size(1.dp)) { drawLine(c.ink25, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = size.height) }
        for (row in AmieLove.entries.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (l in row) {
                    val n = loves[l.ordinal]
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (n > 0) {
                            Small(l.title, color = c.ink)
                            Mono("♡ $n", color = c.ink45)
                        } else {
                            Small("? ? ?", color = c.ink25)
                            Mono("—", color = c.ink25)
                        }
                    }
                }
            }
        }
    }
}

/** Her name and five hearts, filled with foil as she grows fond of you; both read when drawn. */
@Composable
private fun Fondness(fondness: () -> Float, frame: () -> Int) {
    val c = Mu.colors
    val pips = remember { MarkList(5) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
