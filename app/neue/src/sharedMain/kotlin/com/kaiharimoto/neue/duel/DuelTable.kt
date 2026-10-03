package com.kaiharimoto.neue.duel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.DropSpot
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelDrop
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.input.DeskMouse
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.byFinger
import com.kaiharimoto.neue.kit.releasesTyping
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A card being carried: which, where the pointer holds it, and what letting go will do. */
private data class Carry(val uid: Int, val grabX: Float, val grabY: Float, val x: Float, val y: Float, val spot: DropSpot?, val intent: DuelDrop.Intent)

/** What a press landed on. */
private sealed interface Hit {
    data class Card(val frame: CardFrame) : Hit
    data class Pile(val seat: Int, val kind: PileKind) : Hit
    data object Chain : Hit
    data object Table : Hit
}

/**
 * The duel table: the zones, the piles, both hands, the chain well and every card, laid out by
 * [DuelLayout] and drawn from [DuelFrames]. One pointer handler for all of it — one arbiter decides
 * what a press is (a click, a right-click, a hold, a drag, a box) and what it landed on, so two
 * handlers never argue over one press. What a drag will do is drawn where it will land.
 */
@Composable
internal fun DuelTable(h: NeueHolders, duels: Duels, game: DuelGame, layout: DuelLayout, viewers: Set<Int>) {
    val c = Mu.colors
    val s = game.state
    val density = LocalDensity.current
    var carry by remember { mutableStateOf<Carry?>(null) }
    var box by remember { mutableStateOf<Slot?>(null) }
    // A card carried out of an open pile: the pile steps aside, so the zones under it take the drop (1.0.78).
    var stripLeft by remember { mutableStateOf(false) }
    val facing = h.neue.prefs.duel.facing
    val frames = remember(s, layout, viewers, duels.strip, facing, duels.stripRow) { DuelFrames.of(s, layout, viewers, duels.strip, facing, duels.stripRow) }
    val shownFrames = carry?.let { cr ->
        frames.map { f ->
            when {
                f.uid == cr.uid -> f.copy(x = cr.x - cr.grabX, y = cr.y - cr.grabY, rotation = 0f, z = 100f, shown = true, w = layout.card, h = layout.cardHeight)
                stripLeft && f.inStrip -> f.copy(shown = false)
                else -> f
            }
        }
    } ?: frames
    val framesNow by rememberUpdatedState(frames)
    val stateNow by rememberUpdatedState(s)
    val layoutNow by rememberUpdatedState(layout)
    val index = h.builder.index

    // ---- the one arbiter ---------------------------------------------------------------------------
    fun hitAt(x: Float, y: Float): Hit {
        DuelFrames.hit(framesNow, x, y)?.let { f ->
            val where = stateNow.placeOf(f.uid)
            if (!f.inStrip && where is Place.Pile && where.kind != PileKind.HAND) return Hit.Pile(where.seat, where.kind)
            return Hit.Card(f)
        }
        return when (val spot = layoutNow.spotAt(x, y)) {
            is DuelSpot.Pile -> Hit.Pile(spot.seat, spot.kind)
            DuelSpot.Chain -> Hit.Chain
            else -> Hit.Table
        }
    }

    /** Where a card carried to (x, y) would go: a card under it, else the spot. */
    fun dropAt(uid: Int, x: Float, y: Float): DropSpot? {
        val st = stateNow
        val l = layoutNow
        val strip = duels.strip
        if (strip != null && !stripLeft && stripGround(st, l, strip).contains(x, y)) return DropSpot.Pile(strip.first, strip.second)
        val under = framesNow.filter { it.uid != uid && it.shown && !it.inStrip && it.contains(x, y) }.maxByOrNull { it.z }
        if (under != null) {
            when (val p = st.placeOf(under.uid)) {
                is Place.Zone -> return DropSpot.Zone(p)
                is Place.Pile -> if (p.kind == PileKind.HAND) {
                    return DropSpot.Hand(p.seat, DuelFrames.handIndex(framesNow, st.seats[p.seat].hand, x))
                } else return DropSpot.Pile(p.seat, p.kind)
                else -> Unit
            }
        }
        return when (val spot = l.spotAt(x, y)) {
            is DuelSpot.Zone -> DropSpot.Zone(if (spot.zone.kind == ZoneKind.EMZ) spot.zone.copy(seat = duels.seatFor(uid)) else spot.zone)
            is DuelSpot.Pile -> DropSpot.Pile(spot.seat, spot.kind)
            is DuelSpot.Hand -> DropSpot.Hand(spot.seat, DuelFrames.handIndex(framesNow, st.seats[spot.seat].hand, x))
            DuelSpot.Chain -> DropSpot.Chain
            null -> null
        }
    }

    /** The free zone of the kind a verb needs nearest the pointer's column, for a right-click on a hand card. */
    fun zoneNear(uid: Int, verb: DuelVerb, x: Float): Place.Zone? {
        val seat = duels.seatFor(uid)
        val kind = DuelVerbs.zoneKind(stateNow, seat, uid, verb, duels.catalog) ?: return null
        if (kind == ZoneKind.FIELD) return null
        return stateNow.freeZones(seat, kind).minByOrNull { z -> layoutNow.zone(z)?.let { kotlin.math.abs(it.centerX - x) } ?: Float.MAX_VALUE }
    }

    var lastClick by remember { mutableStateOf(Triple<Any?, Long, Boolean>(null, 0L, false)) }

    fun mine(uid: Int) = stateNow.solo || duels.seatFor(uid) == duels.bottom

    fun rightClick(hit: Hit, x: Float) {
        when (hit) {
            is Hit.Card -> {
                val uid = hit.frame.uid
                if (!hit.frame.inStrip && !mine(uid)) duels.verb(uid, DuelVerb.TARGET, seat = duels.bottom)
                else {
                    val v = DuelVerbs.default(stateNow, duels.seatFor(uid), uid, duels.catalog)
                    duels.verb(uid, DuelVerb.DEFAULT, zone = zoneNear(uid, v, x))
                }
            }
            is Hit.Pile -> if (hit.kind == PileKind.DECK) duels.act(DuelAction.Draw(hit.seat), hit.seat) else duels.openPile(hit.seat, hit.kind)
            Hit.Chain -> if (stateNow.chain.isNotEmpty()) duels.act(DuelAction.ChainClear)
            Hit.Table -> duels.commandFocus++
        }
    }

    fun click(hit: Hit, shift: Boolean, alt: Boolean, x: Float, at: Long) {
        val key: Any? = when (hit) { is Hit.Card -> hit.frame.uid; else -> hit }
        val double = lastClick.first == key && at - lastClick.second < DeskMouse.DOUBLE_CLICK_MS && !lastClick.third
        lastClick = Triple(key, at, double)
        if (double) {
            when (hit) {
                is Hit.Card, is Hit.Pile -> rightClick(hit, x)
                else -> Unit
            }
            return
        }
        when (hit) {
            is Hit.Card -> {
                val uid = hit.frame.uid
                val attaching = duels.attaching
                if (attaching != null && attaching != uid && stateNow.placeOf(uid) is Place.Zone) {
                    duels.attaching = null
                    duels.verb(attaching, DuelVerb.ATTACH, host = uid)
                    return
                }
                if (alt) { duels.act(DuelAction.Ping(duels.bottom, DuelAction.PING_LOOK, uid = uid)); return }
                duels.inspected = uid
                duels.selection = if (shift) (if (uid in duels.selection) duels.selection - uid else duels.selection + uid) else setOf(uid)
                // What it can do, beside it (1.0.78).
                duels.verbStrip = true
            }
            is Hit.Pile -> if (alt) duels.act(DuelAction.Ping(duels.bottom, DuelAction.PING_LOOK, place = Place.Pile(hit.seat, hit.kind))) else duels.openPile(hit.seat, hit.kind)
            Hit.Chain -> if (stateNow.chain.isNotEmpty()) duels.resolveChain()
            Hit.Table -> {
                duels.selection = emptySet()
                duels.attaching = null
                duels.verbStrip = false
            }
        }
    }

    val arbiter = Modifier.pointerInput(Unit) {
        val d = density.density
        awaitPointerEventScope {
            while (true) {
                var event = awaitPointerEvent()
                // Hover, between presses: the card the keys act on.
                if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                    val p = event.changes.first().position
                    duels.hovered = DuelFrames.hit(framesNow, p.x / d, p.y / d)?.uid
                    continue
                }
                if (event.type == PointerEventType.Exit) { duels.hovered = null; continue }
                // The wheel over a long open pile turns its rows.
                if (event.type == PointerEventType.Scroll) {
                    val open = duels.strip
                    val ch = event.changes.first()
                    if (open != null) {
                        val n = stateNow.seats[open.first].pile(open.second).size
                        val grid = DuelFrames.stripGrid(n, layoutNow)
                        if (grid.scrolls && stripGround(stateNow, layoutNow, open).contains(ch.position.x / d, ch.position.y / d)) {
                            val step = if (ch.scrollDelta.y > 0) 1 else if (ch.scrollDelta.y < 0) -1 else 0
                            duels.stripRow = (duels.stripRow + step).coerceIn(0, grid.rows - grid.visibleRows)
                            ch.consume()
                        }
                    }
                    continue
                }
                if (event.type != PointerEventType.Press) continue
                val down = event.changes.first()
                if (down.isConsumed) continue
                val x0 = down.position.x / d
                val y0 = down.position.y / d
                val hit = hitAt(x0, y0)
                // A press outside an open pile closes it (1.0.78, kai: "close it by pressing outside of it"),
                // and goes on to do what it does; a press on the pile itself is its own toggle.
                duels.strip?.let { open ->
                    val onItsPile = hit is Hit.Pile && hit.seat == open.first && hit.kind == open.second
                    if (!onItsPile && !stripGround(stateNow, layoutNow, open).contains(x0, y0)) duels.closeStrip()
                }
                val shift = event.keyboardModifiers.isShiftPressed
                val alt = event.keyboardModifiers.isAltPressed
                if (event.buttons.isSecondaryPressed) {
                    down.consume()
                    rightClick(hit, x0)
                    do { event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                    continue
                }
                val finger = down.byFinger
                val slop = viewConfiguration.touchSlop / d
                // Up before the hold and still: a click. Moved: a drag. Neither: a hold.
                var moved: Offset? = null
                var released = false
                val decided = withTimeoutOrNull(DeskMouse.HOLD_MS) {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                        if (!ch.pressed) { released = true; return@withTimeoutOrNull true }
                        val p = ch.position / d
                        if ((p - Offset(x0, y0)).getDistance() > slop) { moved = p; return@withTimeoutOrNull true }
                    }
                    @Suppress("UNREACHABLE_CODE") true
                }
                // A mouse held still before it moves still drags (1.0.85: aiming first killed the drag). The
                // hold shows the card's verbs; a move past the slop then carries it as any drag.
                if (decided == null && !released && moved == null && !finger && hit is Hit.Card) {
                    duels.inspected = hit.frame.uid
                    duels.selection = setOf(hit.frame.uid)
                    duels.verbStrip = true
                    var dragged = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val p = ch.position / d
                        if ((p - Offset(x0, y0)).getDistance() > slop) { moved = p; dragged = true; break }
                    }
                    if (!dragged) continue
                }
                when {
                    released -> click(hit, shift, alt, x0, down.uptimeMillis)
                    decided == null && moved == null -> {
                        // A hold: every verb for the card, beside it read large.
                        if (hit is Hit.Card) {
                            duels.inspected = hit.frame.uid
                            duels.selection = setOf(hit.frame.uid)
                            duels.verbStrip = true
                        } else if (hit is Hit.Table && finger) duels.commandFocus++
                        do { event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
                    }
                    moved != null -> {
                        val cardHit = when (hit) {
                            is Hit.Card -> hit.frame
                            is Hit.Pile -> framesNow.firstOrNull { f -> f.shown && !f.inStrip && stateNow.placeOf(f.uid).let { it is Place.Pile && it.seat == hit.seat && it.kind == hit.kind && it.at == 0 } }
                            else -> null
                        }
                        if (cardHit != null) {
                            // Carry it: the card follows the pointer, and what letting go will do is drawn under it.
                            val uid = cardHit.uid
                            val gx = (x0 - cardHit.x).coerceIn(0f, layoutNow.card)
                            val gy = (y0 - cardHit.y).coerceIn(0f, layoutNow.cardHeight)
                            var p = moved!!
                            var mods = event.keyboardModifiers
                            val fromStrip = cardHit.inStrip
                            val open = duels.strip
                            stripLeft = false
                            duels.carrying = true
                            duels.verbStrip = false
                            val update = {
                                if (fromStrip && open != null && !stripLeft && !stripGround(stateNow, layoutNow, open).contains(p.x, p.y)) stripLeft = true
                                val spot = dropAt(uid, p.x, p.y)
                                carry = Carry(uid, gx, gy, p.x, p.y, spot, DuelDrop.intent(stateNow, uid, spot, duels.catalog, mods.isAltPressed, mods.isShiftPressed))
                            }
                            update()
                            try {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    ch.consume()
                                    mods = e.keyboardModifiers
                                    p = ch.position / d
                                    if (!ch.pressed) break
                                    update()
                                }
                            } catch (gone: kotlinx.coroutines.CancellationException) {
                                // The gesture was cut off: nothing is carried any more (1.0.85; the flag stayed set).
                                carry = null
                                duels.carrying = false
                                throw gone
                            }
                            update()
                            val done = carry
                            val left = stripLeft
                            carry = null
                            stripLeft = false
                            duels.carrying = false
                            if (done != null && !done.intent.none) {
                                val targets = if (uid in duels.selection && duels.selection.size > 1 && done.spot is DropSpot.Pile) duels.selection.toList() else listOf(uid)
                                val actions = targets.flatMap { t -> DuelDrop.intent(stateNow, t, done.spot, duels.catalog, mods.isAltPressed, mods.isShiftPressed).actions }
                                duels.act(actions, duels.seatFor(uid))
                                duels.inspected = uid
                            }
                            // Carried out of an open pile and let go: the pile has done its work (kai, 1.0.78).
                            if (fromStrip && left) duels.closeStrip()
                        } else if (hit is Hit.Table) {
                            // A box: every card it touches is selected.
                            var p = moved!!
                            while (true) {
                                box = Slot(minOf(x0, p.x), minOf(y0, p.y), kotlin.math.abs(p.x - x0), kotlin.math.abs(p.y - y0))
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                ch.consume()
                                p = ch.position / d
                                if (!ch.pressed) break
                            }
                            val b = box
                            box = null
                            if (b != null) {
                                duels.selection = framesNow.filter { f ->
                                    f.shown && !f.inStrip && f.x < b.right && f.x + f.w > b.left && f.y < b.bottom && f.y + f.h > b.top &&
                                        stateNow.placeOf(f.uid).let { it is Place.Zone || (it is Place.Pile && it.kind == PileKind.HAND) }
                                }.map { it.uid }.toSet()
                            }
                        } else {
                            do { event = awaitPointerEvent() } while (event.changes.any { it.pressed })
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.size(layout.width.dp, layout.height.dp).releasesTyping().then(arbiter)) {
        // The zones and piles, as hairline frames; the pile's name and count.
        Canvas(Modifier.fillMaxSize()) {
            val ink12 = c.ink12
            val ink25 = c.ink25
            layout.spots.forEach { (spot, slot) ->
                if (spot is DuelSpot.Hand) return@forEach
                val strong = spot is DuelSpot.Pile || spot == DuelSpot.Chain || (spot is DuelSpot.Zone && spot.zone.kind == ZoneKind.EMZ)
                frame(slot, if (strong) ink25 else ink12)
            }
        }
        PileLabels(s, layout)

        // The zone a card just placed could move to: the number to press.
        val placed = duels.placed
        if (placed != null) {
            var now by remember(placed) { mutableStateOf(Duels.now()) }
            LaunchedEffect(placed) { delay(placed.until - Duels.now()); now = Duels.now() }
            if (now < placed.until) ZoneNumbers(s, layout, placed)
        }

        // Every card: hidden pile cards are placed too, so a card leaving its pile glides out of it.
        shownFrames.filter { it.uid in s.cards }.forEach { f ->
            key(f.uid) {
                val inst = s.cards.getValue(f.uid)
                val card = if (inst.token && inst.code == 0) null else index.byId(CardId(inst.code))
                val inMonsterZone = s.placeOf(f.uid).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) }
                val stats = when {
                    f.look == com.kaiharimoto.mastertool.core.layout.CardLook.BACK || !inst.faceUp || !inMonsterZone -> null
                    // A token's own numbers, when its maker gave them (1.0.79).
                    inst.token && (inst.atk != null || inst.def != null) -> "${inst.atk ?: "?"} / ${inst.def ?: "?"}"
                    card != null && card.atk != null -> "${card.atk}" + (card.def?.let { " / $it" } ?: "")
                    else -> null
                }
                val caption = when {
                    !f.shown -> null
                    f.inStrip || s.placeOf(f.uid).let { it is Place.Zone || (it is Place.Pile && it.kind == PileKind.HAND) } ->
                        if (!f.inStrip && !(s.solo || duels.seatFor(f.uid) == duels.bottom)) DuelVerb.TARGET.label
                        else DuelVerbs.default(s, duels.seatFor(f.uid), f.uid, duels.catalog).label
                    s.placeOf(f.uid).let { it is Place.Pile && it.kind == PileKind.DECK } -> "Draw"
                    else -> "Open"
                }
                TableCard(
                    frame = f,
                    caption = caption,
                    inst = inst,
                    card = card,
                    name = duels.catalog.nameOf(inst),
                    selected = f.uid in duels.selection || f.uid == duels.attaching,
                    carried = carry?.uid == f.uid,
                    foil = h.neue.prefs.foil,
                    stats = stats,
                )
            }
        }

        // The open pile's own ground, over the table — gone while a card carried out of it looks for a place.
        if (!stripLeft) duels.strip?.let { (seat, kind) -> StripGround(duels, s, layout, seat, kind) }
        ShuffleOffer(duels, s, layout)
        if (carry == null && duels.verbStrip) VerbStrip(duels, s, layout, shownFrames)

        // The chain, the arrows, the pings — over the cards.
        ChainWell(s, layout, duels, viewers)
        // Under an open pile, which covers the cards they point at.
        Canvas(Modifier.fillMaxSize().zIndex(if (duels.strip != null) DuelFrames.Z_STRIP - 1f else 50f)) { arrows(s, layout, shownFrames, c.ink, c.paper) }
        Pings(game, layout, shownFrames)

        // What letting go will do, where it will happen.
        carry?.let { cr -> DropHint(cr, layout, shownFrames, s) }
        box?.let { b ->
            Box(Modifier.zIndex(60f).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp).border(1.dp, c.ink).background(c.ink06))
        }
        ScoreColumn(h, duels, s, layout)
        PhaseStrip(duels, s, layout)
        duels.lpPad?.let { seat -> LpPad(duels, s, layout, seat) }
    }
}

private fun DrawScope.frame(slot: Slot, color: androidx.compose.ui.graphics.Color) {
    drawRect(color, Offset(slot.left.dp.toPx(), slot.top.dp.toPx()), Size(slot.width.dp.toPx(), slot.height.dp.toPx()), style = Stroke(1.dp.toPx()))
}

/** Each pile's name over its frame when it is empty, and its count beneath it. */
@Composable
private fun PileLabels(s: com.kaiharimoto.mastertool.core.duel.DuelState, l: DuelLayout) {
    val c = Mu.colors
    l.spots.entries.mapNotNull { (spot, slot) -> (spot as? DuelSpot.Pile)?.let { it to slot } }.forEach { (spot, slot) ->
        val n = s.seats[spot.seat].pile(spot.kind).size
        val label = when (spot.kind) {
            PileKind.DECK -> "Deck"
            PileKind.EXTRA -> "Extra"
            PileKind.GY -> "GY"
            PileKind.BANISHED -> "Banished"
            PileKind.HAND -> "Hand"
        }
        if (n == 0) {
            Box(Modifier.offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp), contentAlignment = Alignment.Center) {
                Micro(label, color = c.ink45, size = (slot.width / 8f).coerceIn(8f, 11f).let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) })
            }
        } else {
            Box(Modifier.zIndex(3f).offset(slot.right.dp - 22.dp, slot.bottom.dp - 16.dp).background(c.paper).border(1.dp, c.ink).padding(horizontal = 3.dp)) {
                Mono("$n", color = c.ink)
            }
        }
    }
}

@Composable
private fun ZoneNumbers(s: com.kaiharimoto.mastertool.core.duel.DuelState, l: DuelLayout, placed: Placed) {
    val c = Mu.colors
    val zones = s.freeZones(placed.seat, placed.kind) + if (placed.kind == ZoneKind.MONSTER) s.freeZones(placed.seat, ZoneKind.EMZ) else emptyList()
    zones.mapNotNull { z -> l.zone(z)?.let { z to it } }.forEach { (z, slot) ->
        val key = when (z.kind) {
            ZoneKind.EMZ -> if (z.index == 0) "6" else "7"
            ZoneKind.SPELL -> "Shift ${z.index + 1}"
            else -> "${z.index + 1}"
        }
        Box(Modifier.zIndex(40f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.border(1.dp, c.ink).background(c.paper).padding(horizontal = 6.dp, vertical = 2.dp)) { Mono(key, color = c.ink) }
        }
    }
}

/** The highlight under a carried card: the spot it will land in, framed, and what it will do there in words. */
@Composable
private fun DropHint(cr: Carry, l: DuelLayout, frames: List<CardFrame>, s: com.kaiharimoto.mastertool.core.duel.DuelState) {
    val c = Mu.colors
    if (cr.intent.none) return
    val slot: Slot? = when (val spot = cr.spot) {
        is DropSpot.Zone -> l.zone(spot.zone)
        is DropSpot.Pile -> l.pile(spot.seat, spot.kind)
        is DropSpot.Hand -> l.pile(spot.seat, PileKind.HAND)
        DropSpot.Chain -> l[DuelSpot.Chain]
        null -> null
    }
    slot ?: return
    Box(Modifier.zIndex(90f).offset((slot.left - 3).dp, (slot.top - 3).dp).size((slot.width + 6).dp, (slot.height + 6).dp).border(2.dp, c.ink))
    Box(Modifier.zIndex(91f).offset(slot.left.dp, (slot.top - 22).dp).background(c.ink).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Micro(cr.intent.label, color = c.paper)
    }
}

/** Target arrows: from the card (or the seat's score) to each target, ink over a paper edge so they read on any art. */
private fun DrawScope.arrows(s: com.kaiharimoto.mastertool.core.duel.DuelState, l: DuelLayout, frames: List<CardFrame>, ink: androidx.compose.ui.graphics.Color, paper: androidx.compose.ui.graphics.Color) {
    fun centre(uid: Int): Offset? = frames.firstOrNull { it.uid == uid && it.shown }?.let { Offset(it.centerX.dp.toPx(), it.centerY.dp.toPx()) }
    s.arrows.forEach { a ->
        val from = a.from?.let(::centre) ?: (l.score[a.seat] ?: l.turn).let { Offset(it.centerX.dp.toPx(), it.centerY.dp.toPx()) }
        a.to.forEach { t ->
            val to = centre(t) ?: return@forEach
            drawLine(paper, from, to, 5.dp.toPx())
            drawLine(ink, from, to, 2.dp.toPx())
            val angle = atan2(to.y - from.y, to.x - from.x)
            val head = 12.dp.toPx()
            val path = Path().apply {
                moveTo(to.x, to.y)
                lineTo(to.x - head * cos(angle - 0.45f), to.y - head * sin(angle - 0.45f))
                lineTo(to.x - head * cos(angle + 0.45f), to.y - head * sin(angle + 0.45f))
                close()
            }
            drawPath(path, ink)
        }
    }
    // The attack declared last, while the Battle Phase lasts (1.0.83): a heavier arrow, to its target or, for
    // a direct attack, to the other player's life points.
    if (s.phase == com.kaiharimoto.mastertool.core.board.DuelPhase.BATTLE) s.attacks.lastOrNull()?.let { atk ->
        val from = centre(atk.attacker) ?: return@let
        val to = atk.target?.let(::centre)
            ?: (l.score[1 - atk.seat] ?: l.pile(1 - atk.seat, com.kaiharimoto.mastertool.core.duel.PileKind.HAND))?.let { Offset(it.centerX.dp.toPx(), it.centerY.dp.toPx()) }
            ?: return@let
        drawLine(paper, from, to, 9.dp.toPx())
        drawLine(ink, from, to, 4.dp.toPx())
        val angle = atan2(to.y - from.y, to.x - from.x)
        val head = 20.dp.toPx()
        val path = Path().apply {
            moveTo(to.x, to.y)
            lineTo(to.x - head * cos(angle - 0.5f), to.y - head * sin(angle - 0.5f))
            lineTo(to.x - head * cos(angle + 0.5f), to.y - head * sin(angle + 0.5f))
            close()
        }
        drawPath(path, ink)
    }
}

/** A ping, for three seconds: a frame round what was pointed at and the word, in ink. */
@Composable
private fun Pings(game: DuelGame, l: DuelLayout, frames: List<CardFrame>) {
    val c = Mu.colors
    var tick by remember { mutableStateOf(0) }
    val now = remember(tick, game.cursor) { Duels.now() }
    val recent = game.played.takeLast(12).filter { it.action is DuelAction.Ping && now - it.at < PING_MS }
    LaunchedEffect(game.cursor, tick) { if (recent.isNotEmpty()) { delay(PING_MS); tick++ } }
    recent.mapNotNull { e ->
        val p = e.action as DuelAction.Ping
        val slot = p.uid?.let { uid -> frames.firstOrNull { it.uid == uid && it.shown }?.let { Slot(it.x, it.y, it.w, it.h) } }
            ?: (p.place as? Place.Pile)?.let { l.pile(it.seat, it.kind) }
        slot?.let { p to it }
    }.forEach { (p, slot) ->
        Box(Modifier.zIndex(80f).offset((slot.left - 5).dp, (slot.top - 5).dp).size((slot.width + 10).dp, (slot.height + 10).dp).border(3.dp, c.ink))
        Box(Modifier.zIndex(81f).offset(slot.left.dp, (slot.bottom + 6).dp).background(c.ink).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Micro(when (p.kind) { DuelAction.PING_OK -> "OK?"; DuelAction.PING_NO -> "No"; DuelAction.PING_WAIT -> "Wait"; else -> "Look" }, color = c.paper)
        }
    }
}

private const val PING_MS = 3000L

/** The ground an open pile covers, its head included: a press outside it closes the pile. */
internal fun stripGround(s: com.kaiharimoto.mastertool.core.duel.DuelState, l: DuelLayout, strip: Pair<Int, PileKind>): Slot {
    val area = DuelFrames.stripBand(l, s.seats[strip.first].pile(strip.second).size).inflated(l.gap)
    return Slot(area.left, area.top - DuelFrames.STRIP_HEAD, area.width, area.height + DuelFrames.STRIP_HEAD)
}
