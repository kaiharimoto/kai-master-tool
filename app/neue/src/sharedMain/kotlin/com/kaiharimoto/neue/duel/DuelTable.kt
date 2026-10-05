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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.DeckPart
import com.kaiharimoto.mastertool.core.duel.DropSpot
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelDrop
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelSeats
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.dice.DiceSim
import com.kaiharimoto.mastertool.core.duel.dice.DiceStage
import com.kaiharimoto.mastertool.core.duel.dice.DiceThrow
import com.kaiharimoto.mastertool.core.duel.dice.Quat
import com.kaiharimoto.mastertool.core.duel.dice.Toss
import com.kaiharimoto.mastertool.core.duel.dice.TossRuns
import com.kaiharimoto.mastertool.core.duel.dice.V3
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.input.DeskMouse
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.CardLook
import com.kaiharimoto.mastertool.core.layout.DuelFocus
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.duel.dice.ChanceCarry
import com.kaiharimoto.neue.duel.dice.DIE_HOME
import com.kaiharimoto.neue.duel.dice.DiceCarry
import com.kaiharimoto.neue.duel.dice.OpeningDice
import com.kaiharimoto.neue.duel.dice.RESTING
import com.kaiharimoto.neue.duel.dice.TableChance
import com.kaiharimoto.neue.duel.dice.drawnAt
import com.kaiharimoto.neue.duel.dice.shapeOf
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
    // The card carried and where it is now: written on every move of the pointer, so only what draws the carried card,
    // its hint and what lies over it read it (1.0.92) — the table as a whole reads [carried], which changes as a carry
    // begins and ends. Both are written together.
    val carry = remember { mutableStateOf<Carry?>(null) }
    var carried by remember { mutableStateOf<Int?>(null) }
    // The box dragged over the table to pick cards: read by the box alone, not the table, as it grows (1.0.92).
    val box = remember { mutableStateOf<Slot?>(null) }
    // A card carried out of an open pile: the pile steps aside, so the zones under it take the drop (1.0.78).
    var stripLeft by remember { mutableStateOf(false) }
    val facing = h.neue.prefs.duel.facing
    // A hand the viewers cannot see is drawn in its veils' order (1.0.87), as the focus walks it.
    val secret = game.header.seed
    // Every hand but the bottom seat's in the notation's order (1.0.87, the red team): the third card drawn there is the `oh3`
    // the Spotlight reads, both hands face-up or not.
    val notationSeat = duels.bottom
    // A hand riffles round the card in hand (1.0.94; the far hand too, 1.0.95): the one the keys are on when they moved last, else the one
    // under the pointer; none while a card is carried, so the hand holds still for the drop.
    // Derived, so only a change of the card in hand composes the table again — never each move of the pointer or the keys.
    val riffleState = rememberUpdatedState(s)
    val carriedNow = rememberUpdatedState(carried)
    val riffle by remember(duels) {
        derivedStateOf {
            val st = riffleState.value
            when {
                carriedNow.value != null -> null
                // Either hand (1.0.95): theirs riffles as yours does.
                duels.byKeys -> (duels.focus as? DuelFocus.Slot.HandCard)?.let { DuelFocus.uidAt(st, it, duels.eyes) }
                else -> duels.hovered?.takeIf { uid -> st.seats.any { uid in it.hand } }
            }
        }
    }
    val frames = remember(s, layout, viewers, duels.strip, facing, duels.stripRow, secret, notationSeat, riffle) {
        DuelFrames.of(s, layout, viewers, duels.strip, facing, duels.stripRow, secret, notationSeat, riffle)
    }
    // Command mode (1.0.87): the focus reads its grid off the table as drawn, and goes with its card when the table changes.
    SideEffect {
        duels.tableLayout = layout
        duels.eyes = DuelFocus.Eyes(viewers, secret, notationSeat)
    }
    LaunchedEffect(s, viewers, duels.strip, duels.bottom, layout) { duels.refocus() }
    val framesNow by rememberUpdatedState(frames)
    val stateNow by rememberUpdatedState(s)
    val layoutNow by rememberUpdatedState(layout)
    // The frames as drawn: the carried card where the pointer holds it, an open pile's cards gone while one carried out
    // of it looks for a place. Read by what draws over the cards, each in its own scope.
    val shownFrames = remember {
        derivedStateOf {
            val fs = framesNow
            carry.value?.let { cr -> fs.map { f -> carriedFrame(f, cr, stripLeft, layoutNow) } } ?: fs
        }
    }
    val index = h.builder.index
    // Another seat's open pile answers with its verbs only to someone who plays both seats (1.0.86).
    val playsBoth = playsBoth(h)
    val playsBothNow by rememberUpdatedState(playsBoth)

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
        // A Deck is three places (1.0.87, kai): its upper third the top, the middle shuffled in, the lower third the bottom.
        fun pileSpot(seat: Int, kind: PileKind): DropSpot.Pile =
            DropSpot.Pile(seat, kind, if (kind == PileKind.DECK) l.pile(seat, kind)?.let { DeckPart.at(y - it.top, it.height) } else null)
        val under = framesNow.filter { it.uid != uid && it.shown && !it.inStrip && it.contains(x, y) }.maxByOrNull { it.z }
        if (under != null) {
            when (val p = st.placeOf(under.uid)) {
                is Place.Zone -> return DropSpot.Zone(p)
                is Place.Pile -> if (p.kind == PileKind.HAND) {
                    return DropSpot.Hand(p.seat, DuelFrames.handIndex(framesNow, st.seats[p.seat].hand, x))
                } else return pileSpot(p.seat, p.kind)
                else -> Unit
            }
        }
        // A player's life points: in the Battle Phase, somewhere to aim a direct attack (1.0.86).
        l.score.entries.firstOrNull { it.value.contains(x, y) }?.let { return DropSpot.Score(it.key) }
        return when (val spot = l.spotAt(x, y)) {
            is DuelSpot.Zone -> DropSpot.Zone(if (spot.zone.kind == ZoneKind.EMZ) spot.zone.copy(seat = duels.seatFor(uid)) else spot.zone)
            is DuelSpot.Pile -> pileSpot(spot.seat, spot.kind)
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

    /** The seat whose resting dice are under (x, y), when the person may throw them now (1.0.87, the opening roll). */
    fun diceAt(x: Float, y: Float): Int? {
        val o = stateNow.opening ?: return null
        if (o.decided || duels.diceCarry != null) return null
        val stage = DiceStage(layoutNow)
        return (0..1).firstOrNull { seat ->
            o.waitsOn(seat) && duels.mayRoll(seat, playsBothNow) && com.kaiharimoto.neue.duel.dice.restBox(stage, seat)?.contains(x, y) == true
        }
    }

    /**
     * The table's die or coin under (x, y) that the person may throw now (1.0.96): one lying where it landed first, then
     * the ones kept beside each Extra Deck. Never while the opening roll is in play.
     */
    fun chanceAt(x: Float, y: Float): Triple<Int, Boolean, DiceSim.Pose?>? {
        if (stateNow.opening?.decided == false || duels.chanceCarry != null || duels.diceCarry != null) return null
        val stage = DiceStage(layoutNow)
        val seats = (0..1).filter { duels.mayRoll(it, playsBothNow) && stage.home(it) != null }
        for (seat in seats) for (ch in stateNow.chance.filter { it.seat == seat }) {
            val rest = TossRuns.of(shapeOf(ch.coin), ch.toss).rest.single()
            // Where this window draws it (1.1.9: past the middle row, folded onto the table drawn here).
            val at = drawnAt(stage, seat, stage.shown(seat, rest.p, ch.toss.far))
            val home = stage.home(seat) ?: continue
            val r = if (ch.coin) home.coinRadius + home.size * 0.2f else home.size * 0.75f
            if (kotlin.math.abs(x - at.x) <= r && kotlin.math.abs(y - at.y) <= r) return Triple(seat, ch.coin, rest)
        }
        for (seat in seats) {
            val home = stage.home(seat) ?: continue
            val out = stateNow.chance.filter { it.seat == seat }.map { it.coin }.toSet()
            if (false !in out && home.onDie(x, y)) return Triple(seat, false, null)
            if (true !in out && home.onCoin(x, y)) return Triple(seat, true, null)
        }
        return null
    }

    fun mine(uid: Int) = stateNow.solo || duels.seatFor(uid) == duels.bottom

    /** Whether the person plays [f]'s card, or only points at it: an open pile of theirs is target-only (1.0.86). */
    fun plays(f: CardFrame) = if (f.inStrip) DuelSeats.stripPlays(stateNow, duels.seatFor(f.uid), duels.bottom, playsBothNow) else mine(f.uid)

    fun rightClick(hit: Hit, x: Float) {
        // A right-click puts a waiting attack away, and does nothing else (1.0.86).
        if (duels.attacking != null) { duels.attacking = null; return }
        when (hit) {
            is Hit.Card -> {
                val uid = hit.frame.uid
                if (!plays(hit.frame)) duels.verb(uid, DuelVerb.TARGET, seat = duels.bottom)
                else {
                    val v = DuelVerbs.default(stateNow, duels.seatFor(uid), uid, duels.catalog)
                    duels.verb(uid, DuelVerb.DEFAULT, zone = zoneNear(uid, v, x))
                }
            }
            is Hit.Pile -> if (hit.kind == PileKind.DECK) duels.act(DuelAction.Draw(hit.seat), hit.seat) else duels.openPile(hit.seat, hit.kind)
            Hit.Chain -> if (stateNow.chain.isNotEmpty()) duels.act(DuelAction.ChainClear)
            Hit.Table -> duels.openSpotlight()
        }
    }

    fun click(hit: Hit, shift: Boolean, alt: Boolean, x: Float, y: Float, at: Long, ctrl: Boolean = false, finger: Boolean = false) {
        // A link's card waiting for what it targets (1.0.90, the chain well's menu): the card clicked gets its arrow.
        duels.linkTarget?.let { from ->
            if (hit is Hit.Card && hit.frame.uid != from) { duels.targetFromLink(hit.frame.uid); return }
            duels.linkTarget = null
        }
        // An attack waiting for what it attacks (1.0.86): their monster, or their hand for a direct attack —
        // the same answer a drag of the attacker there would give. Anywhere else puts it away.
        duels.attacking?.let { a ->
            val aim = DuelDrop.intent(stateNow, a, dropAt(a, x, y), duels.catalog).actions.singleOrNull() as? DuelAction.Attack
            if (aim != null) { duels.attack(aim.target); return }
            duels.attacking = null
        }
        val key: Any? = when (hit) { is Hit.Card -> hit.frame.uid; else -> hit }
        val double = lastClick.first == key && at - lastClick.second < DeskMouse.DOUBLE_CLICK_MS && !lastClick.third
        lastClick = Triple(key, at, double)
        if (double) {
            when (hit) {
                is Hit.Card -> rightClick(hit, x)
                // A pile's double-click is its click again, never the right-click (1.2.0, kai: "when I try to open the deck to
                // search a card it draws me a card by accident"): the first click opened the Deck, and the second drew. With a
                // mouse the Deck draws only by a right-click, D or a drag to the hand, as DuelInput's table says. A finger's
                // double-tap is its right-click (DuelInput.TOUCH), so there it still draws — and the pile the first tap opened
                // is put away, so one gesture does one thing.
                is Hit.Pile -> if (finger) {
                    if (duels.strip == hit.seat to hit.kind) duels.closeStrip()
                    rightClick(hit, x)
                }
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
                // Several cards (1.0.90): Ctrl (⌘) click or a tap in select mode puts one in or takes it out, Shift click a
                // run of one hand, pile or row; the selection's bar then says what they can all do.
                when {
                    ctrl || duels.selecting -> duels.toggleSelect(uid)
                    shift -> duels.rangeSelect(uid)
                    else -> {
                        duels.inspected = uid
                        duels.selection = setOf(uid)
                        // What it can do, beside it (1.0.78).
                        duels.verbStrip = true
                    }
                }
            }
            is Hit.Pile -> if (alt) duels.act(DuelAction.Ping(duels.bottom, DuelAction.PING_LOOK, place = Place.Pile(hit.seat, hit.kind))) else duels.openPile(hit.seat, hit.kind)
            Hit.Chain -> if (stateNow.chain.isNotEmpty()) duels.resolveChain()
            Hit.Table -> {
                if (duels.ordering == null) duels.clearSelection()
                duels.attaching = null
                duels.verbStrip = false
                duels.chainMenu = null
            }
        }
    }

    val arbiter = Modifier.pointerInput(Unit) {
        val d = density.density
        var lastPointer = Offset.Unspecified
        // A press met while waiting out a die's double-click (1.1.9), handled as the next event.
        var pending: PointerEvent? = null
        awaitPointerEventScope {
            while (true) {
                var event = pending ?: awaitPointerEvent()
                pending = null
                // Hover, between presses: the card the keys act on.
                if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                    val p = event.changes.first().position
                    // The pointer moved: the keys act on what it is over again (1.0.87), the ring put away. Only a real
                    // move: the table re-laid under a still pointer sends a synthetic one, and the keys would lose the ring.
                    if (event.type == PointerEventType.Move && p != lastPointer) duels.lastInput = Duels.Input.POINTER
                    lastPointer = p
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
                duels.lastInput = Duels.Input.POINTER
                val down = event.changes.first()
                if (down.isConsumed) continue
                val x0 = down.position.x / d
                val y0 = down.position.y / d
                // The opening roll (1.0.87): a press on the person's resting dice picks both up; they follow the pointer,
                // tumbling as it moves, and letting go throws them at the hand's speed. Let go without moving: a toss.
                val diceSeat = diceAt(x0, y0)
                if (diceSeat != null) {
                    down.consume()
                    val stage = DiceStage(layoutNow)
                    duels.diceCarry = DiceCarry(diceSeat, x0, y0, RESTING)
                    duels.carrying = true
                    val f = try {
                        fling(down, d, stage, diceSeat, RESTING, viewConfiguration.touchSlop / d) { p, held -> duels.diceCarry = DiceCarry(diceSeat, p.x, p.y, held) }
                    } finally {
                        duels.diceCarry = null
                        duels.carrying = false
                    }
                    val toss = if (!f.moved) null else DiceThrow.fromDrag(
                        stage.under(diceSeat, f.p.x, f.p.y, DiceThrow.HELD),
                        f.held,
                        stage.velocity(diceSeat, f.vx, f.vy, DiceThrow.HELD),
                        f.wobble,
                    )
                    duels.throwDice(diceSeat, toss)
                    continue
                }
                // The table's die and coin (1.0.96): picked up from beside the Extra Deck, or where one lies, and thrown
                // the same way; a press let go without moving throws it from the corner (or, lying out, where it lies).
                val chanceHit = chanceAt(x0, y0)
                if (chanceHit != null) {
                    down.consume()
                    val stage = DiceStage(layoutNow)
                    val (seat, coin, lying) = chanceHit
                    val start = lying?.q ?: if (coin) Quat.IDENTITY else DIE_HOME
                    duels.chanceCarry = ChanceCarry(seat, coin, x0, y0, start)
                    duels.carrying = true
                    val f = try {
                        fling(down, d, stage, seat, listOf(start), viewConfiguration.touchSlop / d) { p, held -> duels.chanceCarry = ChanceCarry(seat, coin, p.x, p.y, held.first()) }
                    } finally {
                        duels.chanceCarry = null
                        duels.carrying = false
                    }
                    // Carried back onto its home (1.1.9): put back, thrown or not — one lying out is stowed, one picked up from
                    // home is only set down again.
                    if (f.moved && stage.home(seat)?.over(coin, f.p.x, f.p.y) == true) {
                        if (lying != null) duels.stowChance(seat, coin)
                        continue
                    }
                    // A double-click (a finger's double-tap) on one lying out puts it back (1.1.9): so its click waits the
                    // double-click's time before it throws again, and a press anywhere else is the table's as ever.
                    if (!f.moved && lying != null) {
                        var second: PointerEvent? = null
                        withTimeoutOrNull(DeskMouse.DOUBLE_CLICK_MS) {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Press) { second = e; break }
                            }
                        }
                        val press = second?.changes?.firstOrNull()
                        val same = press != null && !press.isConsumed &&
                            chanceAt(press.position.x / d, press.position.y / d)?.let { it.first == seat && it.second == coin } == true
                        if (same) {
                            press!!.consume()
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            duels.stowChance(seat, coin)
                            continue
                        }
                        pending = second
                    }
                    val reach = DiceSim.reachFor(stateNow.solo)
                    val toss = when {
                        f.moved -> {
                            // Where the hand let go, back into the throw's own arena: the table past the middle row may be
                            // drawn folded on this window ([DiceStage.shown]), never in the physics.
                            val at = stage.unshown(seat, stage.under(seat, f.p.x, f.p.y, DiceThrow.HELD), reach)
                            val v = stage.velocity(seat, f.vx, f.vy, DiceThrow.HELD)
                            if (coin) Toss.coin(at, f.held.first(), v, f.wobble, reach) else Toss.die(at, f.held.first(), v, f.wobble, reach)
                        }
                        // Lying out: thrown again from where it lies, a short hop any way.
                        lying != null -> {
                            val r = kotlin.random.Random.Default
                            val v = V3((r.nextDouble() - 0.5) * 10.0, (r.nextDouble() - 0.5) * 10.0)
                            if (coin) Toss.coin(lying.p, lying.q, v, (r.nextDouble() - 0.5) * 8.0, reach) else Toss.die(lying.p, lying.q, v, (r.nextDouble() - 0.5) * 16.0, reach)
                        }
                        else -> null
                    }
                    duels.throwChance(seat, coin, toss)
                    continue
                }
                val hit = hitAt(x0, y0)
                // A press outside an open pile closes it (1.0.78, kai: "close it by pressing outside of it"),
                // and goes on to do what it does; a press on the pile itself is its own toggle.
                duels.strip?.let { open ->
                    val onItsPile = hit is Hit.Pile && hit.seat == open.first && hit.kind == open.second
                    if (!onItsPile && !stripGround(stateNow, layoutNow, open).contains(x0, y0)) duels.closeStrip()
                }
                // So does a press outside the life-point pad (1.0.94), as every other window here closes; a press on its own
                // score is that score's toggle, which closes it already.
                duels.lpPad?.let { seat ->
                    val onScore = layoutNow.score[seat]?.contains(x0, y0) == true
                    if (!onScore && !lpPadSlot(layoutNow, seat).contains(x0, y0)) duels.lpPad = null
                }
                val shift = event.keyboardModifiers.isShiftPressed
                val alt = event.keyboardModifiers.isAltPressed
                val ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
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
                    released -> click(hit, shift, alt, x0, y0, down.uptimeMillis, ctrl, finger)
                    decided == null && moved == null -> {
                        // A hold: every verb for the card, beside it read large. By a finger it is also select mode (1.0.90):
                        // each tap after it puts a card into the selection or takes it out.
                        if (hit is Hit.Card) {
                            duels.inspected = hit.frame.uid
                            if (finger && duels.selecting) duels.toggleSelect(hit.frame.uid)
                            else {
                                duels.selection = setOf(hit.frame.uid)
                                duels.verbStrip = true
                                if (finger) duels.selecting = true
                            }
                        } else if (hit is Hit.Table && finger) duels.openSpotlight()
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
                                carry.value = Carry(uid, gx, gy, p.x, p.y, spot, DuelDrop.intent(stateNow, uid, spot, duels.catalog, mods.isAltPressed, mods.isShiftPressed, duels.dragActor()))
                            }
                            carried = uid
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
                                carry.value = null
                                carried = null
                                duels.carrying = false
                                throw gone
                            }
                            update()
                            val done = carry.value
                            val left = stripLeft
                            carry.value = null
                            carried = null
                            stripLeft = false
                            duels.carrying = false
                            if (done != null && !done.intent.none) {
                                val targets = if (uid in duels.selection && duels.selection.size > 1 && done.spot is DropSpot.Pile) duels.selection.toList() else listOf(uid)
                                val deck = (done.spot as? DropSpot.Pile)?.takeIf { it.kind == PileKind.DECK }
                                if (targets.size > 1 && deck != null && deck.part != DeckPart.SHUFFLE) {
                                    // Several onto the Deck's top or bottom (1.0.90): their order first, in the ordering strip.
                                    duels.ordering = Duels.Ordering(targets.filter { it in stateNow.cards }, bottom = deck.part == DeckPart.BOTTOM || mods.isShiftPressed)
                                } else {
                                    val actions = targets.flatMap { t -> DuelDrop.intent(stateNow, t, done.spot, duels.catalog, mods.isAltPressed, mods.isShiftPressed, duels.dragActor()).actions }
                                    if (duels.act(actions, duels.seatFor(uid)) && targets.size > 1) duels.clearSelection()
                                }
                                duels.inspected = uid
                            }
                            // Carried out of an open pile and let go: the pile has done its work (kai, 1.0.78).
                            if (fromStrip && left) duels.closeStrip()
                        } else if (hit is Hit.Table) {
                            // A box: every card it touches is selected.
                            var p = moved!!
                            while (true) {
                                box.value = Slot(minOf(x0, p.x), minOf(y0, p.y), kotlin.math.abs(p.x - x0), kotlin.math.abs(p.y - y0))
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                ch.consume()
                                p = ch.position / d
                                if (!ch.pressed) break
                            }
                            val b = box.value
                            box.value = null
                            if (b != null) {
                                val boxed = framesNow.filter { f ->
                                    f.shown && (f.inStrip || stateNow.placeOf(f.uid).let { it is Place.Zone || (it is Place.Pile && it.kind == PileKind.HAND) }) &&
                                        f.x < b.right && f.x + f.w > b.left && f.y < b.bottom && f.y + f.h > b.top
                                }.map { it.uid }
                                // Ctrl or Shift held: the box adds to what is selected (1.0.90).
                                duels.selection = (if (ctrl || shift) duels.selection.toList() + boxed else boxed).distinct().toSet()
                                duels.verbStrip = duels.selection.size == 1
                                duels.selection.singleOrNull()?.let { duels.inspected = it }
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
        val attacker = duels.attacking
        val words = remember(s, attacker, duels.bottom, playsBoth, duels.catalog, index) { CardWords(s, attacker, duels.bottom, playsBoth, duels.catalog, index) }
        val leaving = carried != null && stripLeft
        frames.filter { it.uid in s.cards }.forEach { f ->
            key(f.uid) {
                val inst = s.cards.getValue(f.uid)
                val isCarried = f.uid == carried
                // An open pile's cards step aside while one carried out of it looks for a place.
                val base = if (!isCarried && leaving && f.inStrip) f.copy(shown = false) else f
                CarriedCard(
                    base = base,
                    // Only the carried card follows the pointer, and only it reads where the pointer is.
                    follow = if (isCarried) carry else null,
                    layout = layout,
                    caption = words.caption(base, shown = isCarried || base.shown),
                    inst = inst,
                    card = words.card(f.uid),
                    name = words.name(f.uid),
                    selected = f.uid in duels.selection || f.uid == duels.attaching || f.uid == attacker || f.uid == duels.picked || f.uid == duels.linkTarget,
                    carried = isCarried,
                    foil = h.neue.prefs.foil,
                    stats = if (base.look == CardLook.BACK) null else words.stats(f.uid),
                    flash = s.chain.indexOfLast { it.uid == f.uid }.takeIf { it >= 0 }?.plus(1),
                )
            }
        }

        // The open pile's own ground, over the table — gone while a card carried out of it looks for a place.
        if (!stripLeft) duels.strip?.let { (seat, kind) -> StripGround(duels, s, layout, seat, kind) }
        ShuffleOffer(duels, s, layout)
        // With nothing carried the frames as drawn are the frames.
        if (carried == null && duels.verbStrip && duels.selection.size < 2) VerbStrip(duels, s, layout, frames, playsBoth)
        // Several cards, one move (1.0.90): their order on each, what they can all do, and the order onto a Deck.
        if (carried == null) SelectionBadges(duels, s, frames)
        if (carried == null) SelectionBar(h, duels, s, layout, viewers, frames)
        if (carried == null) OrderingStrip(h, duels, s, layout, viewers)
        // Command mode (1.0.87): the coordinates at every place's corner, and the ring the arrows walk.
        if (h.neue.prefs.duel.coordinates) Coordinates(duels, s, layout, shownFrames)
        // The ring reads the keys and the focus itself, so walking the table redraws the ring alone (1.0.92).
        if (carried == null) FocusRing(duels, s, layout, frames, viewers)

        // The chain, the arrows, the pings — over the cards.
        ChainWell(s, layout, duels, viewers)
        ChainMenu(duels, s, layout, viewers)
        duels.linkTarget?.let { from -> if (from in s.cards) LinkTargetBand(duels, s, layout, from) }
        // Under an open pile, which covers the cards they point at.
        Canvas(Modifier.fillMaxSize().zIndex(if (duels.strip != null) DuelFrames.Z_STRIP - 1f else 50f)) { arrows(s, layout, shownFrames.value, c.ink, c.paper) }
        Pings(game, layout, shownFrames)
        // The opening roll (1.0.87): the dice in front of each field, in the hand, or tumbling across it; the result.
        if (duels.replay == null) OpeningDice(duels, s, layout, playsBoth)
        // The table's die and coin (1.0.96): beside each Extra Deck, in the hand, in the air, or where they landed.
        TableChance(duels, s, layout, playsBoth)

        // What letting go will do, where it will happen.
        DropHint(carry, layout)
        PickBox(box)
        ScoreColumn(h, duels, s, layout)
        PhaseStrip(h, duels, s, layout)
        duels.attacking?.let { a -> if (a in s.cards) AttackBand(duels, s, layout, a) }
        if (duels.replay == null && carried == null) BattleChip(h, duels, game, layout)
        duels.lpPad?.let { seat -> LpPad(duels, s, layout, seat) }
        // Command mode's Spotlight (1.0.87): the table dims but for what the line touches and where it goes. The dim reads
        // whether the Spotlight is open itself, so a key typed in it never redraws the table (1.0.92).
        SpotlightDim(duels, s, layout, shownFrames)
    }
}

/**
 * The person at the table plays both seats (1.0.86): a hot-seat with both hands face-up and no Ai at the
 * other seat, so another seat's open pile answers with its verbs, not only Target.
 */
internal fun playsBoth(h: NeueHolders): Boolean {
    val prefs = h.neue.prefs
    val aiSeated = prefs.ai.enabled && (prefs.duel.aiPlays || h.duel.aiSession != null)
    return DuelSeats.playsBoth(prefs.duel, networked = h.duel.role != null, aiSeated = aiSeated)
}

/** A click on [uid] while [attacker] waits to attack: its words when the click would declare it, else null. */
private fun attackCaption(s: DuelState, attacker: Int, uid: Int, catalog: DuelCatalog): String? {
    val spot = when (val p = s.placeOf(uid)) {
        is Place.Zone -> DropSpot.Zone(p)
        is Place.Pile -> if (p.kind == PileKind.HAND) DropSpot.Hand(p.seat, 0) else null
        else -> null
    }
    val intent = DuelDrop.intent(s, attacker, spot, catalog)
    return intent.label.takeIf { intent.actions.singleOrNull() is DuelAction.Attack }
}

/** [f] as drawn while [cr] is carried: the carried card where the pointer holds it, an open pile's cards gone once one left it. */
private fun carriedFrame(f: CardFrame, cr: Carry, stripLeft: Boolean, l: DuelLayout): CardFrame = when {
    f.uid == cr.uid -> f.copy(x = cr.x - cr.grabX, y = cr.y - cr.grabY, rotation = 0f, z = 100f, shown = true, w = l.card, h = l.cardHeight)
    stripLeft && f.inStrip -> f.copy(shown = false)
    else -> f
}

/**
 * One card of the table at [base], or — the card carried — where the pointer holds it (1.0.92): the pointer's every move
 * is read here, by the carried card alone, never by the table round it. Every card goes through this, carried or not, so
 * a card picked up or let go keeps its glide.
 */
@Composable
private fun CarriedCard(
    base: CardFrame,
    follow: State<Carry?>?,
    layout: DuelLayout,
    caption: String?,
    inst: CardInst,
    card: Card?,
    name: String,
    selected: Boolean,
    carried: Boolean,
    foil: String,
    stats: TableStats?,
    /** Which link of the chain the card is (1.0.95): a new one plays its activation's glint. */
    flash: Int? = null,
) {
    val cr = follow?.value
    val held = cr != null && cr.uid == base.uid
    val frame = if (held) carriedFrame(base, cr, stripLeft = false, layout) else base
    // Over a Deck's top / shuffle / bottom, the card gets out of the way of the choice (1.0.93).
    val overDeck = if (held && !cr.intent.none && (cr.spot as? DropSpot.Pile)?.part != null) {
        TransformOrigin((cr.grabX / layout.card).coerceIn(0f, 1f), (cr.grabY / layout.cardHeight).coerceIn(0f, 1f))
    } else null
    TableCard(frame = frame, caption = caption, inst = inst, card = card, name = name, selected = selected, carried = carried, foil = foil, stats = stats, overDeck = overDeck, flash = flash)
}

/**
 * What each card on the table says — the family cursor's caption, its name, its picture, its battle numbers — read once
 * for a table and kept (1.0.92), never again on every frame of a drag: a caption asks the card's default verb, an
 * attack's intent and where the card is. Made again when the table, the attack waiting, the bottom seat, who plays both
 * seats, the catalog or the pool changes. One thread's (composition's).
 */
private class CardWords(
    private val s: DuelState,
    private val attacker: Int?,
    private val bottom: Int,
    private val playsBoth: Boolean,
    private val catalog: DuelCatalog,
    private val index: CardIndex,
) {
    private val captions = HashMap<Long, String?>()
    private val cards = HashMap<Int, Card?>()
    private val names = HashMap<Int, String>()
    private val numbers = HashMap<Int, TableStats?>()

    /** [Duels.seatFor] on this table: the controller of a card on the field, else its owner. */
    private fun seatFor(uid: Int): Int {
        val card = s.cards[uid] ?: return bottom
        return if (s.placeOf(uid) is Place.Zone) card.controller else card.owner
    }

    /** What a right-click does to [f]'s card, for the cursor's caption; null when it is not [shown]. */
    fun caption(f: CardFrame, shown: Boolean): String? {
        if (!shown) return null
        val key = (f.uid.toLong() shl 1) or (if (f.inStrip) 1L else 0L)
        if (key in captions) return captions[key]
        return captionOf(f.uid, f.inStrip).also { captions[key] = it }
    }

    private fun captionOf(uid: Int, inStrip: Boolean): String? = when {
        // What a click does while an attack waits: "Attack Arias", "Attack directly" (1.0.86).
        attacker != null && attacker != uid && !inStrip -> attackCaption(s, attacker, uid, catalog)
        inStrip || s.placeOf(uid).let { it is Place.Zone || (it is Place.Pile && it.kind == PileKind.HAND) } ->
            if (!(if (inStrip) DuelSeats.stripPlays(s, seatFor(uid), bottom, playsBoth) else s.solo || seatFor(uid) == bottom)) DuelVerb.TARGET.label
            else DuelVerbs.default(s, seatFor(uid), uid, catalog).label
        s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.DECK } -> "Draw"
        else -> "Open"
    }

    fun card(uid: Int): Card? = cards.getOrPut(uid) {
        val inst = s.cards.getValue(uid)
        if (inst.token && inst.code == 0) null else index.byId(CardId(inst.code))
    }

    fun name(uid: Int): String = names.getOrPut(uid) { catalog.nameOf(s.cards.getValue(uid)) }

    /** The card's ATK/DEF on a face-up monster in a Monster Zone; a card drawn as its back shows none (the caller's). */
    fun stats(uid: Int): TableStats? {
        if (uid in numbers) return numbers[uid]
        val inst = s.cards.getValue(uid)
        val card = card(uid)
        val inMonsterZone = s.placeOf(uid).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) }
        val out = when {
            !inst.faceUp || !inMonsterZone -> null
            // A token's own numbers, when its maker gave them (1.0.79).
            inst.token && (inst.atk != null || inst.def != null) -> TableStats("${inst.atk ?: "?"}", "${inst.def ?: "?"}", inst.defense)
            card != null && card.atk != null -> TableStats("${card.atk}", card.def?.toString(), inst.defense)
            else -> null
        }
        numbers[uid] = out
        return out
    }
}

private fun DrawScope.frame(slot: Slot, color: androidx.compose.ui.graphics.Color) {
    drawRect(color, Offset(slot.left.dp.toPx(), slot.top.dp.toPx()), Size(slot.width.dp.toPx(), slot.height.dp.toPx()), style = Stroke(1.dp.toPx()))
}

/** Each pile's name over its frame when it is empty, and its count beneath it. */
@Composable
private fun PileLabels(s: DuelState, l: DuelLayout) {
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
private fun ZoneNumbers(s: DuelState, l: DuelLayout, placed: Placed) {
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
private fun DropHint(carry: State<Carry?>, l: DuelLayout) {
    val c = Mu.colors
    val cr = carry.value ?: return
    if (cr.intent.none) return
    val slot: Slot? = when (val spot = cr.spot) {
        is DropSpot.Zone -> l.zone(spot.zone)
        is DropSpot.Pile -> l.pile(spot.seat, spot.kind)
        is DropSpot.Hand -> l.pile(spot.seat, PileKind.HAND)
        DropSpot.Chain -> l[DuelSpot.Chain]
        is DropSpot.Score -> l.score[spot.seat]
        null -> null
    }
    slot ?: return
    Box(Modifier.zIndex(90f).offset((slot.left - 3).dp, (slot.top - 3).dp).size((slot.width + 6).dp, (slot.height + 6).dp).border(2.dp, c.ink))
    // Over a Deck, its three places: the third the card is over in ink, the others named faintly (1.0.87).
    val part = (cr.spot as? DropSpot.Pile)?.part
    if (part != null) {
        val third = slot.height / 3f
        DeckPart.entries.forEachIndexed { i, p ->
            val on = p == part
            Box(
                Modifier.zIndex(90f).offset(slot.left.dp, (slot.top + third * i).dp).size(slot.width.dp, third.dp)
                    .background(if (on) c.ink else c.paper.copy(alpha = 0.85f)).border(1.dp, c.ink),
                contentAlignment = Alignment.Center,
            ) {
                Micro(
                    when (p) { DeckPart.TOP -> "Top"; DeckPart.SHUFFLE -> "Shuffle"; DeckPart.BOTTOM -> "Bottom" },
                    color = if (on) c.paper else c.ink70,
                    size = (slot.width / 7f).coerceIn(7f, 11f).let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) },
                )
            }
        }
    }
    Box(Modifier.zIndex(91f).offset(slot.left.dp, (slot.top - 22).dp).background(c.ink).padding(horizontal = 6.dp, vertical = 3.dp)) {
        Micro(cr.intent.label, color = c.paper)
    }
}

/** The box dragged over the table to pick the cards it touches. */
@Composable
private fun PickBox(box: State<Slot?>) {
    val c = Mu.colors
    val b = box.value ?: return
    Box(Modifier.zIndex(60f).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp).border(1.dp, c.ink).background(c.ink06))
}

/** Target arrows: from the card (or the seat's score) to each target, ink over a paper edge so they read on any art. */
private fun DrawScope.arrows(s: DuelState, l: DuelLayout, frames: List<CardFrame>, ink: androidx.compose.ui.graphics.Color, paper: androidx.compose.ui.graphics.Color) {
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
    if (s.phase == DuelPhase.BATTLE) s.attacks.lastOrNull()?.let { atk ->
        val from = centre(atk.attacker) ?: return@let
        val to = atk.target?.let(::centre)
            ?: (l.score[1 - atk.seat] ?: l.pile(1 - atk.seat, PileKind.HAND))?.let { Offset(it.centerX.dp.toPx(), it.centerY.dp.toPx()) }
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
private fun Pings(game: DuelGame, l: DuelLayout, shown: State<List<CardFrame>>) {
    val c = Mu.colors
    var tick by remember { mutableStateOf(0) }
    val now = remember(tick, game.cursor) { Duels.now() }
    val recent = game.played.takeLast(12).filter { it.action is DuelAction.Ping && now - it.at < PING_MS }
    LaunchedEffect(game.cursor, tick) { if (recent.isNotEmpty()) { delay(PING_MS); tick++ } }
    // The frames as drawn read only with a ping to place: a carried card's every move is not the pings' business otherwise.
    val frames = if (recent.isEmpty()) emptyList() else shown.value
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
internal fun stripGround(s: DuelState, l: DuelLayout, strip: Pair<Int, PileKind>): Slot {
    val area = DuelFrames.stripBand(l, s.seats[strip.first].pile(strip.second).size).inflated(l.gap)
    return Slot(area.left, area.top - DuelFrames.STRIP_HEAD, area.width, area.height + DuelFrames.STRIP_HEAD)
}

// ---- Command mode (1.0.87): the focus ring and the coordinates ------------------------------------------

/** The box a card is seen in: its frame, turned as it lies. */
internal fun seenBox(f: CardFrame): Slot {
    val turned = f.rotation % 180f != 0f
    val w = if (turned) f.h else f.w
    val h = if (turned) f.w else f.h
    return Slot(f.centerX - w / 2f, f.centerY - h / 2f, w, h)
}

/** Where [slot] is drawn: its card as it lies, else the zone or the pile's frame. */
private fun boxOf(duels: Duels, s: DuelState, l: DuelLayout, frames: List<CardFrame>, slot: DuelFocus.Slot): Slot? {
    // A link is in the chain well (1.0.90): the well is ringed, and the link's own line inverted in it.
    if (slot is DuelFocus.Slot.Link) return l[DuelSpot.Chain]
    val uid = DuelFocus.uidAt(s, slot, duels.eyes)
    uid?.let { u -> frames.firstOrNull { it.uid == u && it.shown } }?.let { return seenBox(it) }
    return when (slot) {
        is DuelFocus.Slot.Zone -> l.zone(slot.place)
        is DuelFocus.Slot.Pile -> l.pile(slot.seat, slot.kind)
        else -> null
    }
}

/**
 * The focus (1.0.87): a ring in ink outside the card or the place — 2 dp of paper, then 2 dp of ink, so it
 * reads on any art and never touches the card — and its tag above it: the coordinate, and the card's name
 * when the table's eyes may see it ("oh2 · in hand", never its name). With a card picked, or an attack or an
 * attach waiting, the tag says what Enter will do there. It moves nothing and fits nothing.
 */
@Composable
private fun FocusRing(duels: Duels, s: DuelState, l: DuelLayout, frames: List<CardFrame>, viewers: Set<Int>) {
    val c = Mu.colors
    if (!duels.byKeys) return
    val focus = duels.focus ?: return
    val box = boxOf(duels, s, l, frames, focus) ?: return
    val uid = DuelFocus.uidAt(s, focus, duels.eyes)
    val coord = DuelFocus.label(focus, duels.bottom)
    val what = uid?.let { u ->
        val inst = s.cards.getValue(u)
        when {
            viewers.any { DuelSight.sees(s, u, it) } -> duels.catalog.nameOf(inst)
            s.placeOf(u).let { it is Place.Pile && it.kind == PileKind.HAND } -> "in hand"
            s.placeOf(u) is Place.Zone -> "set"
            else -> "face-down"
        }
    }
    val count = (focus as? DuelFocus.Slot.Pile)?.let { s.seats[it.seat].pile(it.kind).size }
    // What Enter does here, when something waits to be put down or aimed.
    val waiting = duels.picked ?: duels.attacking
    val intent = waiting?.takeIf { it in s.cards && it != uid }?.let { w ->
        val spot = when (focus) {
            is DuelFocus.Slot.Zone -> DropSpot.Zone(if (focus.place.kind == ZoneKind.EMZ) focus.place.copy(seat = duels.seatFor(w)) else focus.place)
            is DuelFocus.Slot.Pile -> DropSpot.Pile(focus.seat, focus.kind)
            is DuelFocus.Slot.HandCard -> DropSpot.Hand(focus.seat, focus.index)
            is DuelFocus.Slot.PileCard -> DropSpot.Pile(focus.seat, focus.kind)
            is DuelFocus.Slot.Link -> DropSpot.Chain
        }
        DuelDrop.intent(s, w, spot, duels.catalog).let { i -> if (i.none) "nothing to do here" else "Enter: ${i.label}" }
    }
    // On a link, what Enter offers (1.0.90).
    val linkHint = (focus as? DuelFocus.Slot.Link)?.takeIf { duels.chainMenu == null }?.let { if (s.chain.getOrNull(it.index)?.negated == true) "negated · Enter" else "Enter: resolve, negate, target" }
    val text = listOfNotNull(coord, count?.let { "$it" }, what, intent ?: linkHint).joinToString(" · ")
    Box(Modifier.zIndex(FOCUS_Z).offset((box.left - 4).dp, (box.top - 4).dp).size((box.width + 8).dp, (box.height + 8).dp).border(2.dp, c.ink))
    Box(Modifier.zIndex(FOCUS_Z).offset((box.left - 2).dp, (box.top - 2).dp).size((box.width + 4).dp, (box.height + 4).dp).border(2.dp, c.paper))
    // Above the ring where there is room, else under it.
    val above = box.top - 4 - TAG_H >= 0f
    val tagTop = if (above) box.top - 4 - TAG_H else box.bottom + 4
    Box(Modifier.zIndex(FOCUS_Z + 1f).offset((box.left - 4).dp.coerceAtLeast(0.dp), tagTop.dp).background(c.ink).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Mono(text, color = c.paper)
    }
}

/**
 * Every place's coordinate at its top-left corner, faint, as a chessboard's edge (1.0.87, `I`): the zones and
 * piles, each card in a hand, and an open pile's cards. Over the cards on a paper ground, small enough to
 * leave the art alone.
 */
@Composable
private fun Coordinates(duels: Duels, s: DuelState, l: DuelLayout, shown: State<List<CardFrame>>) {
    val c = Mu.colors
    val frames = shown.value
    val viewer = duels.bottom
    val cells = DuelFocus.cells(s, viewer, duels.focusShape()).map { it.slot }.filter { it !is DuelFocus.Slot.Link } +
        (duels.strip?.let { (seat, kind) -> s.seats[seat].pile(kind).indices.map { DuelFocus.Slot.PileCard(seat, kind, it) } } ?: emptyList())
    // Measured first, drawn after: no return out of a lambda that draws (NonLocalReturnTest).
    val placed = cells.mapNotNull { slot ->
        when (slot) {
            is DuelFocus.Slot.Zone -> l.zone(slot.place)
            is DuelFocus.Slot.Pile -> l.pile(slot.seat, slot.kind)
            else -> boxOf(duels, s, l, frames, slot)
        }?.let { slot to it }
    }
    placed.forEach { (slot, box) ->
        val z = if (slot is DuelFocus.Slot.PileCard) DuelFrames.Z_STRIP + 1f else COORDS_Z
        Box(Modifier.zIndex(z).offset((box.left + 2).dp, (box.top + 2).dp).background(c.paper).padding(horizontal = 2.dp)) {
            Mono(DuelFocus.label(slot, viewer), color = c.ink45, size = androidx.compose.ui.unit.TextUnit(9f, androidx.compose.ui.unit.TextUnitType.Sp))
        }
    }
}

/** Over the open pile and the verb strip, under a carried card's hint. */
private const val FOCUS_Z = 70f
/** Over the hand's cards, under an open pile. */
private const val COORDS_Z = 6f
/** The focus tag's height. */
private const val TAG_H = 20f

/** How a hand let go of what it carried: where, turned how, whether it moved at all, its speed and how its path curved. */
private class Flung(val p: Offset, val held: List<Quat>, val moved: Boolean, val vx: Float, val vy: Float, val wobble: Double)

/**
 * Follows a press on dice or a coin (1.0.87; shared with the table's die and coin, 1.0.96) until it lets go: [held] tumbles
 * a little as the pointer moves — a roll about the axis square to the motion — and [onMove] is told where it is. The speed
 * is the pointer's over its last tenth of a second; [slop] (dp) is how far it may wander and still be a click.
 */
private suspend fun AwaitPointerEventScope.fling(
    down: PointerInputChange,
    d: Float,
    stage: DiceStage,
    seat: Int,
    start: List<Quat>,
    slop: Float,
    onMove: (Offset, List<Quat>) -> Unit,
): Flung {
    val x0 = down.position.x / d
    val y0 = down.position.y / d
    var held = start
    var p = Offset(x0, y0)
    var moved = false
    var wobble = 0.0
    var heading: Offset? = null
    val trail = ArrayDeque<Triple<Long, Float, Float>>()
    trail.addLast(Triple(down.uptimeMillis, x0, y0))
    while (true) {
        val e = awaitPointerEvent()
        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
        ch.consume()
        val np = ch.position / d
        trail.addLast(Triple(ch.uptimeMillis, np.x, np.y))
        while (trail.size > 2 && ch.uptimeMillis - trail.first().first > 100) trail.removeFirst()
        if (!ch.pressed) break
        val step = np - p
        if ((np - Offset(x0, y0)).getDistance() > slop) moved = true
        if (step.getDistance() > 0.01f) {
            // They tumble a little in the hand: a roll about the axis square to the motion.
            val v = stage.velocity(seat, step.x, step.y, DiceThrow.HELD)
            val axis = V3.UP.cross(v)
            val angle = axis.length * 0.5
            if (angle > 1e-6) {
                val n = axis.normalized()
                val r = Quat(kotlin.math.cos(angle / 2), n.x * kotlin.math.sin(angle / 2), n.y * kotlin.math.sin(angle / 2), n.z * kotlin.math.sin(angle / 2))
                held = held.map { q -> (r * q).normalized() }
            }
            // How the path curves: a twist for the throw.
            val dir = step / step.getDistance()
            heading?.let { h0 -> wobble = (wobble + (h0.x * dir.y - h0.y * dir.x) * 3.0).coerceIn(-24.0, 24.0) }
            heading = dir
        }
        p = np
        onMove(p, held)
    }
    val first = trail.first()
    val last = trail.last()
    val dt = (last.first - first.first) / 1000f
    val vx = if (dt > 0.008f) (last.second - first.second) / dt else 0f
    val vy = if (dt > 0.008f) (last.third - first.third) / dt else 0f
    return Flung(p, held, moved, vx, vy, wobble)
}
