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
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.layout
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
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyHit
import com.kaiharimoto.mastertool.core.ai.chessy.toys.ToyKind
import com.kaiharimoto.mastertool.core.audio.PetSound
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.chessy.ChessyInk.particles
import com.kaiharimoto.neue.ai.chessy.PetToysInk.catnip
import com.kaiharimoto.neue.ai.chessy.PetToysInk.feather
import com.kaiharimoto.neue.ai.chessy.PetToysInk.flakes
import com.kaiharimoto.neue.ai.chessy.PetToysInk.mouse
import com.kaiharimoto.neue.ai.chessy.PetToysInk.wand
import com.kaiharimoto.neue.ai.chessy.PetToysInk.yarn
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuColors
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.theme.LocalMuFonts
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
 * given — and her fondness in foil hearts. The pointer is a paw here ([CursorMode.PAW]). One arbiter takes every press
 * on the room, so a press is a toy's, a shelf's or hers and never two. Esc, Back or Bye-bye lets her go back to the chat
 * box.
 *
 * She plays (kai, 1.1.29): she moves about the floor and goes after the toys herself ([ChessyPlay]) — bites the yarn to
 * set it rolling, bites at the feather, stalks and pounces on the mouse and now and then catches it (it bounces off her)
 * — her box following her body, leaning, squashing and stretching about her base, her jaws open as she bites; she and
 * the toys cast shadows on the floor; and it all sounds ([PetAudio]: her meows, purrs and giggles, the toys, chimes as
 * she grows fond of you), unless `ai.petSound` is off.
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
    val audio = remember { PetAudio() }
    val touch = remember { TouchMarks() }
    var touchFrame by remember { mutableIntStateOf(0) } // read only in the draw
    var touchWake by remember { mutableIntStateOf(0) } // wakes the touch marks' clock
    var biting by remember { mutableStateOf(false) } // her jaws open, mid-bite
    var lastFond by remember { mutableFloatStateOf(0f) }
    var scurry by remember { mutableIntStateOf(0) } // the mouse's running sound, cut when it stops
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
    // her voice for a face: what a cat would say, looking like that
    fun voiceOf(e: Expression): PetSound? = when (e) {
        Expression.DELIGHTED -> PetSound.TRILL
        Expression.LOVE -> PetSound.PURR
        Expression.SHY, Expression.OOPS, Expression.SURPRISED, Expression.SAD -> PetSound.MEW
        Expression.FOUND -> PetSound.MRRP
        Expression.ANGRY -> PetSound.HMPH
        Expression.WAITING -> PetSound.NYA
        Expression.SLEEPING -> PetSound.PURR
        else -> null
    }
    fun react(r: AmieReaction?, at: Offset? = null, voice: PetSound? = null) {
        r ?: return
        // a cute pop for the face she makes, her voice, a twinkle for sparkles, a ring, and chimes as she grows fond of you
        audio.play(PetSound.POP, .35f)
        (voice ?: voiceOf(r.mood))?.let { v -> audio.play(v, when { v == PetSound.PURR && r.mood == Expression.SLEEPING -> .45f; v == PetSound.NYA -> .81f; v == PetSound.PURR -> .9f; else -> .8f }) }
        if (r.sparkles > 0) audio.play(PetSound.SPARKLE, .35f)
        if (r.ring) audio.play(PetSound.BELL, .5f)
        if (amie.fondness > lastFond + .001f) audio.play(PetSound.CHIME, .45f, take = (amie.fondness * 2.99f).toInt())
        lastFond = amie.fondness
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
    fun giveNip() {
        audio.play(PetSound.RUSTLE, .8f)
        val r = amie.nip(now())
        react(r, head(), voice = if (r.mood == Expression.LOVE) PetSound.NYAA else PetSound.HMPH)
    }
    fun windMouse() {
        audio.play(PetSound.WIND, .7f)
        audio.cut(scurry)
        scurry = audio.play(PetSound.SCURRY, .5f)
    }
    fun towardHer(x: Float) = if (x < toys.room.middle) 1f else -1f
    LaunchedEffect(amie) {
        react(amie.greet(now()), voice = PetSound.NYA)
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
            // catnip on the floor at her feet, settled, and she goes to roll in it; or the bag held up, pouring
            "catnip" -> { toys.catnip.sprinkle(room.herX + room.reach * 1.5f, room.floor - 4f, 40, room, toys.random()); toys.catnip.flakes.step(5f, room, toys.random()) }
            "pour" -> { toys.catnip.held = true; toys.catnip.x = room.herX - room.headR * 1.6f; toys.catnip.y = room.headY - room.headR * .4f }
            "finger" -> {
                // a finger stroking across her head, still down: its ring, the ripple it landed with, its prints
                val h = head()
                val step = 26f * density.density
                touch.land(Offset(h.x - step * 4f, h.y + step), now())
                for (k in 1..8) touch.move(Offset(h.x - step * 4f + k * step, h.y + step - kotlin.math.sin(k * .7f) * step * .6f), step * .8f, now() - (8 - k) * .07)
                touchWake++
                stroke(AmieZone.HEAD, h, 60)
            }
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
                if (demo == "toys") react(amie.toy(ToyKind.YARN, ToyHit.NEAR, now()), head())
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
            amie.idle(now())?.let { react(it, head(), voice = if (amie.high(now()) > 0f) (if (kotlin.random.Random.nextBoolean()) PetSound.NYAA else PetSound.GIGGLE) else null) }
            if (now() > moodUntil && mood != Expression.SLEEPING) mood = Expression.LISTENING
            refill = kotlin.math.ceil(amie.nipRefill(now())).toInt()
        }
    }
    // the room's clock: every frame while she or a toy moves, a few times a second while all is still (her mind still
    // runs: she wanders off, goes after a toy); what happens, she answers and it sounds
    LaunchedEffect(toys) {
        var last = 0L
        fun advance(dt: Float) {
            toys.her.silly = amie.high(now())
            for (e in toys.step(dt)) {
                val at = Offset(e.x, e.y)
                when (e.hit) {
                    ToyHit.BOUNCE -> audio.play(PetSound.THUD, e.strength * (if (e.kind == ToyKind.MOUSE) .5f else .9f))
                    ToyHit.SWISH -> audio.play(PetSound.SWISH, e.strength * .55f)
                    ToyHit.POUNCE -> audio.play(PetSound.MRRP, .7f)
                    ToyHit.LAND -> audio.play(PetSound.LAND, e.strength * .8f)
                    ToyHit.BIT -> { audio.play(PetSound.NOM, .9f); burst(at, 0, 4) }
                    ToyHit.MISSED -> audio.play(PetSound.SNAP, .6f)
                    ToyHit.CAUGHT -> { audio.play(PetSound.BOING, .8f); burst(at, 1, 5) }
                    ToyHit.NEAR -> Unit
                    ToyHit.POUR -> audio.play(PetSound.RUSTLE, .2f * e.strength)
                    // down in the catnip: the catnip has her (or she has had enough for now, and says so)
                    ToyHit.ROLL -> giveNip()
                }
                react(amie.toy(e.kind, e.hit, now()), at, voice = if (e.hit == ToyHit.CAUGHT) PetSound.GIGGLE else null)
            }
            if (!toys.mouse.out || toys.mouse.wound <= 0f) { audio.cut(scurry); scurry = 0 }
            if (biting != toys.her.mouthOpen) biting = toys.her.mouthOpen
            val side = box[2]
            box[0] = toys.room.herX - side / 2f
            box[1] = toys.room.floor - side * SIT - toys.her.hop + toys.her.sink
            toyFrame++
        }
        while (true) {
            if (!toys.moving) {
                delay(100)
                advance(.1f)
                last = 0L
                continue
            }
            withFrameNanos { t ->
                val dt = if (last == 0L) 1f / 60f else ((t - last) / 1e9f)
                last = t
                advance(dt)
            }
        }
    }
    // the sound: on while she is out, unless it is turned off
    // and quiet while the app is not the one in front (kai: "when I defocus the app in pet mode I still hear chessy")
    val soundOn = ai.prefs.petSound && androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    androidx.compose.runtime.DisposableEffect(amie, soundOn) {
        audio.start(soundOn)
        onDispose { audio.stop() }
    }
    // the touch marks' clock, asleep when no finger is down and nothing is fading
    LaunchedEffect(touchWake) {
        while (touch.live(now())) withFrameNanos { touchFrame++ }
        touchFrame++
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
                        // her: a tap, a stroke (petting, tickling, a rub) or a hold (a hug); a hand on her holds her still
                        toys.her.touched()
                        // a finger shows where it touches her: a ring round it, a ripple as it lands, paw prints as it strokes
                        val finger = down.type != PointerType.Mouse
                        if (finger) { touch.land(start, now()); touchWake++ }
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
                            toys.her.touched()
                            if (moved >= slop) hold.cancel()
                            if (finger) touch.move(ch.position, 22f * density.density, now())
                            if (d != Offset.Zero) {
                                ch.consume()
                                val z = zoneAt(ch.position)
                                react(amie.stroke(z, d.x / density.density, d.y / density.density, now()), ch.position)
                                if (z == AmieZone.HEAD && now() - lastHeart > .35) { lastHeart = now(); burst(ch.position, 1, 0) }
                            }
                        }
                        hold.cancel()
                        touch.at = null
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
                                ToyKind.CATNIP -> { toys.catnip.held = true; toys.catnip.x = p.x; toys.catnip.y = p.y; audio.play(PetSound.RUSTLE, .5f) }
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
                        taken == ToyKind.YARN && moved >= slop -> { y.release(v.x, v.y, room, toys.random()); if (v.getDistance() > 600f * u) audio.play(PetSound.SWISH, .4f) }
                        taken == ToyKind.MOUSE && moved >= slop -> m.release(v.x, v.y, room)
                        taken == ToyKind.FEATHER -> toys.wand.held = false
                        // the bag let go: back in its slot; what was poured stays on the floor
                        taken == ToyKind.CATNIP -> toys.catnip.held = false
                        // a tap on a toy in the room: the yarn hops toward her, the mouse is wound again
                        onYarn -> { y.held = false; y.vx = towardHer(y.x) * 260f * u; y.vy = -700f * u }
                        onMouse -> { m.held = false; m.facing = towardHer(m.x); m.wind(); windMouse() }
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
                            windMouse()
                        }
                        // a tap on the bag: a pinch shaken out in front of her
                        slot == ToyKind.CATNIP -> if (toys.catnip.sprinkle(room.herX - towardHer(room.herX) * room.reach * 2f, room.headY, 24, room, toys.random()) > 0) audio.play(PetSound.RUSTLE, .6f)
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
            val roomy = maxWidth >= 1000.dp
            // the bands, top to bottom (kai, 1.1.28: "the pet mode layout needs a rework, the toys are too small"): the head
            // row; her room, with her words beside her (above her on a phone); the floor; and the toy box along the bottom
            val slotW = if (compact) 82f else 150f
            val slotH = if (compact) 94f else 136f
            val barTop = h - (slotH + (if (compact) 18f else 26f)) * px
            val floorY = barTop - (if (compact) 24f else 34f) * px
            val topY = (if (compact) 190f else 80f) * px
            val sit = SIT
            val side = min(w * (if (compact) .78f else .42f), (floorY - topY) / sit)
            val left = (w - side) / 2f
            val top = floorY - side * sit
            val fit = ChessyFit.of(side, side, head = false)
            val sheet = fit[0]
            toys.room.apply {
                this.left = 0f; this.right = w; this.top = 0f; floor = floorY; unit = px
                // her shape over the floor, from the sheet: her head's middle, her mouth, half her width, her bite's reach
                headRise = (1660f - 760f) * sheet; headR = 520f * sheet
                mouthRise = (1660f - 1190f) * sheet
                halfW = 1320f * sheet / 2f * .92f
                reach = 230f * sheet
            }
            if (box[2] != side) { box[2] = side; box[3] = side; box[0] = toys.room.herX - side / 2f; box[1] = top }
            toys.scale(if (compact) .6f else 1f)

            // the room behind her: the floor, a rug, paw prints wandering to it
            Canvas(Modifier.fillMaxSize()) { room(c, floorY, w, left + side / 2f, min(side * .4f, w * .44f), px, prints = !compact) }

            // her shadow on the floor (kai: "cast a shadow for physics"): smaller and fainter the higher she leaps
            Canvas(Modifier.fillMaxSize()) {
                toyFrame
                floorShadow(c, toys.room.herX, floorY, side * .26f, toys.her.hop / (side * .35f), px)
            }

            // her, where her body is: hopping, leaning, squashing and stretching about her base; swaying on catnip
            Box(
                Modifier
                    .offset { toyFrame; IntOffset((toys.room.herX - side / 2f).toInt(), (top - toys.her.hop + toys.her.sink).toInt()) }
                    .size(with(density) { side.toDp() })
                    .graphicsLayer {
                        auraT
                        toyFrame
                        // rolling in catnip she turns over about her middle, as a ball would
                        val rolling = toys.her.spin != 0f
                        rotationZ = toys.her.lean + amie.wobble(now()) + toys.her.spin
                        scaleX = toys.her.sx
                        scaleY = toys.her.sy
                        transformOrigin = TransformOrigin(.5f, if (rolling) .55f else sit)
                    }
                    .chessyAura { auraT }
                    .cursor(CursorMode.PAW, caption = "Pet", holdOnPress = true),
            ) {
                ChessyAvatar(
                    if (biting) Expression.FOUND else mood, with(density) { side.toDp() }, talking = speaking,
                    pointer = { toys.focus()?.let { (x, y) -> Offset(origin[0] + x, origin[1] + y) } ?: ai.h.cursor.position },
                    rigHook = { rig: ChessyRig ->
                        if (kick[0] != 0) { rig.twitch(kick[0]); kick[0] = 0 }
                        if (kick[1] != 0) { rig.ring(); kick[1] = 0 }
                    },
                )
            }

            // the toy box: one row of slots along the bottom, each with its name and what it does
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (compact) 12.dp else 18.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 16.dp),
            ) {
                for (kind in ToyKind.entries) {
                    val faded = kind == ToyKind.CATNIP && refill > 0
                    Box(
                        Modifier
                            .size(slotW.dp, slotH.dp)
                            .onGloballyPositioned { co ->
                                val r = co.boundsInWindow()
                                slots[kind.ordinal] = Rect(r.left - origin[0], r.top - origin[1], r.right - origin[0], r.bottom - origin[1])
                            }
                            .cursor(CursorMode.DRAG, caption = if (kind == ToyKind.FEATHER) "Drag to wave" else kind.verb, holdOnPress = true),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Canvas(Modifier.fillMaxSize()) { cropMarks(c.ink45, 12.dp.toPx(), 1.5.dp.toPx()) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 8.dp)) {
                            Micro(if (compact) kind.short else kind.title, color = c.ink, size = if (compact) 10.sp else 12.sp)
                            if (!compact || faded) Mono(if (faded) "Refills in ${refill}s" else kind.hint, color = c.ink45, size = if (compact) 9.sp else 11.sp)
                        }
                    }
                }
            }

            // the toys: at rest in the box, and out in the room in front of her
            Canvas(Modifier.fillMaxSize()) {
                toyFrame
                drawToys(toys, c, px, refill > 0) { rest(it) }
            }

            // what she says, in her box: beside her head, always on the same side, square to the page so it reads
            if (line.isNotEmpty()) {
                val say: @Composable (Modifier, androidx.compose.ui.unit.TextUnit) -> Unit = { mod, size ->
                    ChessySay(line, typedUnits, ai.name, 0f, caretOn = { (auraT * 2f).toInt() % 2 == 0 }, modifier = mod, textSize = size)
                }
                if (compact) {
                    Box(Modifier.fillMaxWidth().padding(top = 70.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
                        say(Modifier.widthIn(max = 380.dp), 18.sp)
                    }
                } else {
                    // beside her head as she moves: on her right where there is room, else on her left
                    val gap = 14f * px
                    val margin = 24f * px
                    Box(
                        Modifier.layout { m, cs ->
                            val p = m.measure(cs.copy(minWidth = 0, minHeight = 0, maxWidth = (420f * px).toInt()))
                            layout(cs.maxWidth, cs.maxHeight) {
                                toyFrame
                                val r = toys.room
                                val right = r.herX + r.halfW * .92f + gap
                                val x = if (right + p.width < w - margin) right else (r.herX - r.halfW * .92f - gap - p.width).coerceAtLeast(margin)
                                p.place(x.toInt(), (r.headY - r.headR * .85f).toInt().coerceAtLeast((64f * px).toInt()))
                            }
                        },
                    ) { say(Modifier, 20.sp) }
                }
            }

            // her name and how fond of you she has grown; sound, and Bye-bye
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Fondness({ amie.fondness }, { frame }, large = !compact)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MuButton(
                        if (soundOn) "Sound on" else "Sound off",
                        { ai.h.neue.update { it.copy(ai = it.ai.copy(petSound = !it.ai.petSound)) } },
                        variant = BtnVariant.SUBTLE,
                        toggled = soundOn,
                    )
                    MuButton("Bye-bye", { ai.closeAmie() })
                }
            }
        }
        // where a finger pets her, over her and under the hearts
        Canvas(Modifier.fillMaxSize()) {
            touchFrame
            val t = now()
            val s = this.density
            with(ChessyInk) {
                for (p in touch.prints) touchPrint(Offset(p[0].toFloat(), p[1].toFloat()), p[2].toFloat(), s, ((t - p[3]) / TouchMarks.PRINT_LIFE).toFloat(), c.ink, c.paper)
                for (r in touch.ripples) touchRipple(Offset(r[0].toFloat(), r[1].toFloat()), s, ((t - r[2]) / TouchMarks.RIPPLE_LIFE).toFloat())
                touch.at?.let { touchRing(it, s, (((t - touch.downAt) / .12).toFloat()).coerceIn(0f, 1f)) }
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
private fun DrawScope.room(c: MuColors, floorY: Float, w: Float, mid: Float, rugHalf: Float, px: Float, prints: Boolean = true) {
    // paw prints: a cat's walk across the floor to the rug, faint
    val step = 34f * px
    var x = 40f * px
    var k = 0
    while (prints && x < mid - rugHalf - 20f * px) {
        val yy = floorY + (if (k % 2 == 0) 12f else 24f) * px
        printAt(c, x, yy, px)
        x += step
        k++
    }
    // the floor
    drawLine(c.ink25, Offset(24f * px, floorY), Offset(w - 24f * px, floorY), strokeWidth = px)
    for (end in listOf(24f * px, w - 24f * px)) drawLine(c.ink, Offset(end, floorY - 6f * px), Offset(end, floorY + 6f * px), strokeWidth = 1.5f * px)
    // the rug: a flat oval seen across the floor, woven rings and a fringe
    val rh = 11f * px
    drawOval(c.paper, Offset(mid - rugHalf, floorY - rh * .5f), androidx.compose.ui.geometry.Size(rugHalf * 2f, rh * 2f))
    drawOval(c.ink06, Offset(mid - rugHalf, floorY - rh * .5f), androidx.compose.ui.geometry.Size(rugHalf * 2f, rh * 2f))
    for (ring in 1..3) {
        val k2 = 1f - ring * .2f
        drawOval(c.ink12, Offset(mid - rugHalf * k2, floorY - rh * .5f + rh * (1f - k2)), androidx.compose.ui.geometry.Size(rugHalf * 2f * k2, rh * 2f * k2), style = Stroke(px))
    }
    drawOval(c.ink45, Offset(mid - rugHalf, floorY - rh * .5f), androidx.compose.ui.geometry.Size(rugHalf * 2f, rh * 2f), style = Stroke(1.2f * px))
    for (i in -3..3) {
        val fx = mid + i * rugHalf * .14f
        drawLine(c.ink25, Offset(fx, floorY + rh * 1.48f), Offset(fx, floorY + rh * 1.48f + 4f * px), strokeWidth = px)
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
    if (!nip.held) rest(ToyKind.CATNIP).let { catnip(it.x, it.y, nip.size, c, px, angle = nip.angle, fill = nip.fill, faded = nipFaded) }
    // a toy out in the room leaves its place in the box drawn faintly, so the box shows what is missing
    val ghost = Stroke(1.2f * px, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f * px, 4f * px)))
    if (y.out) rest(ToyKind.YARN).let { drawCircle(c.ink25, y.radius, it, style = ghost) }
    if (m.out) rest(ToyKind.MOUSE).let { drawOval(c.ink25, Offset(it.x - m.length * .5f, it.y - m.height * .3f), androidx.compose.ui.geometry.Size(m.length, m.height * .9f), style = ghost) }
    if (w.held) rest(ToyKind.FEATHER).let { drawLine(c.ink25, Offset(it.x - w.stick * .22f, it.y + w.stick * .12f), Offset(it.x + w.stick * .08f, it.y - w.stick * .1f), strokeWidth = 1.2f * px, pathEffect = ghost.pathEffect) }
    if (nip.held) rest(ToyKind.CATNIP).let { drawRect(c.ink25, Offset(it.x - nip.size / 2f, it.y - nip.size / 2f), androidx.compose.ui.geometry.Size(nip.size, nip.size), style = ghost) }
    // their shadows on the floor, under them, fainter the higher they are
    val room = toys.room
    if (m.out) floorShadow(c, m.x, room.floor, m.length * .42f, (room.floor - m.height / 2f - m.y) / (m.length * 2.5f), px)
    if (y.out) floorShadow(c, y.x, room.floor, y.radius * .95f, (room.floor - y.radius - y.y) / (y.radius * 6f), px)
    // catnip on the floor and in the air
    flakes(nip.flakes, c, px)
    // out in the room
    if (m.out) mouse(m.x, m.y + m.hop, m.length, m.q, m.key, c, m.tail, px)
    if (y.out) yarn(y.x, y.y, y.radius, y.q, c, y.strand, px)
    if (w.held) {
        val (tx, ty) = w.tip(toys.room)
        wand(w.hx, w.hy, tx, ty, w.string, w.speed, w.stick * .3f, c, px)
    }
    if (nip.held) catnip(nip.x, nip.y, nip.size, c, px, angle = nip.angle, fill = nip.fill)
}

/**
 * A shadow on the floor under something [high] off it (0 on the floor, 1 high up): a flat ink oval [half] wide each way,
 * narrower and fainter as it rises. Kai's word ("cast a shadow for physics"): the petting room's one shadow, drawn flat in
 * ink, no blur.
 */
private fun DrawScope.floorShadow(c: MuColors, x: Float, floorY: Float, half: Float, high: Float, px: Float) {
    val k = high.coerceIn(0f, 1f)
    val hw = half * (1f - .45f * k)
    val hh = (4f * px + half * .1f) * (1f - .35f * k)
    drawOval(c.ink.copy(alpha = .16f * (1f - .7f * k)), Offset(x - hw, floorY - hh * .55f), androidx.compose.ui.geometry.Size(hw * 2f, hh * 2f))
}

/** Her name and five hearts, filled with foil as she grows fond of you; both read when drawn. */
@Composable
private fun Fondness(fondness: () -> Float, frame: () -> Int, large: Boolean = true) {
    val c = Mu.colors
    val pips = remember { MarkList(5) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Micro(CHESSY_NAME, color = c.ink, size = if (large) 13.sp else 11.sp)
        // read in the draw: a particle's frame redraws the hearts, never recomposes the layer
        val step = if (large) 30f else 20f
        Canvas(Modifier.size((step * 5f).dp, (if (large) 26 else 20).dp)) {
            frame()
            pips.clear()
            val full = (fondness() * 5f + .001f).toInt()
            for (i in 0 until 5) pips.add(MarkShape.HEART, (step / 2f + i * step) * density, size.height / 2f, step * .37f * density, if (i < full) 1f else .22f)
            particles(pips, Offset(-.3f, -.5f))
        }
    }
}

/** What a held press on her in the chat box does while she is the assistant: her petting mode, and Ai's hold otherwise. */
internal fun AiState.holdFace(longer: Boolean): AvatarPlay.Reaction? = if (prefs.persona == com.kaiharimoto.mastertool.core.prefs.AiPrefs.PERSONA_CHESSY) { openAmie(); null } else play.hold(longer)

/**
 * Where a finger is on her and what it left (1.1.29): [at] while it is down (since [downAt]), a ripple where each touch
 * landed, and a paw print every [step] pixels of a stroke, turned the way the stroke went; each fades and is let go.
 * Times are seconds; plain, read in the draw.
 */
private class TouchMarks {
    var at: Offset? = null
    var downAt = 0.0
    private var last: Offset? = null
    val prints = ArrayList<DoubleArray>() // x, y, angle (degrees), born
    val ripples = ArrayList<DoubleArray>() // x, y, born

    fun land(p: Offset, now: Double) {
        at = p
        downAt = now
        last = p
        ripples += doubleArrayOf(p.x.toDouble(), p.y.toDouble(), now)
    }

    fun move(p: Offset, step: Float, now: Double) {
        at = p
        val l = last ?: return run { last = p }
        val d = p - l
        if (d.getDistance() < step) return
        val angle = kotlin.math.atan2(d.y.toDouble(), d.x.toDouble()) * 180.0 / kotlin.math.PI + 90.0
        prints += doubleArrayOf(p.x.toDouble(), p.y.toDouble(), angle, now)
        last = p
        while (prints.size > 24) prints.removeAt(0)
    }

    /** Whether anything is still to be drawn at [now]: a finger down, or marks still fading. */
    fun live(now: Double): Boolean {
        prints.removeAll { now - it[3] > PRINT_LIFE }
        ripples.removeAll { now - it[2] > RIPPLE_LIFE }
        return at != null || prints.isNotEmpty() || ripples.isNotEmpty()
    }

    companion object {
        const val PRINT_LIFE = .8
        const val RIPPLE_LIFE = .5
    }
}

/** How far down her sheet her hair tips touch the floor: where she sits. */
private const val SIT = 1660f / ChessyFit.SHEET_H
