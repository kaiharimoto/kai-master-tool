package com.kaiharimoto.neue.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DropSpot
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelDrop
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.replay.ReplayUnit
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.layout.DuelFocus
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.Page

/**
 * The verb keys the window runs: [VERB_KEYS] turned round (one verb a key), and Space, the obvious thing, which is
 * the window's alone.
 */
internal val VERBS: Map<DeskAction, DuelVerb> =
    mapOf(DeskAction.DUEL_DEFAULT to DuelVerb.DEFAULT) + VERB_KEYS.entries.associate { (verb, key) -> key to verb }

private val ZONES = mapOf(
    DeskAction.DUEL_ZONE_1 to (ZoneKind.MONSTER to 0),
    DeskAction.DUEL_ZONE_2 to (ZoneKind.MONSTER to 1),
    DeskAction.DUEL_ZONE_3 to (ZoneKind.MONSTER to 2),
    DeskAction.DUEL_ZONE_4 to (ZoneKind.MONSTER to 3),
    DeskAction.DUEL_ZONE_5 to (ZoneKind.MONSTER to 4),
    DeskAction.DUEL_ZONE_S1 to (ZoneKind.SPELL to 0),
    DeskAction.DUEL_ZONE_S2 to (ZoneKind.SPELL to 1),
    DeskAction.DUEL_ZONE_S3 to (ZoneKind.SPELL to 2),
    DeskAction.DUEL_ZONE_S4 to (ZoneKind.SPELL to 3),
    DeskAction.DUEL_ZONE_S5 to (ZoneKind.SPELL to 4),
    DeskAction.DUEL_ZONE_EMZ_LEFT to (ZoneKind.EMZ to 0),
    DeskAction.DUEL_ZONE_EMZ_RIGHT to (ZoneKind.EMZ to 1),
    DeskAction.DUEL_ZONE_FIELD to (ZoneKind.FIELD to 0),
)

/** The duel's keys (`DeskShortcuts`' Duelling rows), run on the window's holders. */
internal fun runDuel(h: NeueHolders, action: DeskAction) {
    val duels = h.duel
    if (action == DeskAction.DUEL_NEW) { duels.setupOpen = true; return }
    if (duels.replay != null) {
        when (action) {
            DeskAction.REPLAY_BACK -> duels.step(ReplayUnit.GROUP, -1)
            DeskAction.REPLAY_FORWARD -> duels.step(ReplayUnit.GROUP, 1)
            DeskAction.REPLAY_BACK_PHASE -> duels.step(ReplayUnit.PHASE, -1)
            DeskAction.REPLAY_FORWARD_PHASE -> duels.step(ReplayUnit.PHASE, 1)
            DeskAction.REPLAY_BACK_TURN -> duels.step(ReplayUnit.TURN, -1)
            DeskAction.REPLAY_FORWARD_TURN -> duels.step(ReplayUnit.TURN, 1)
            DeskAction.REPLAY_START -> duels.seek(0)
            DeskAction.REPLAY_END -> duels.seek(Int.MAX_VALUE)
            DeskAction.REPLAY_PLAY -> duels.play(1)
            DeskAction.REPLAY_DELETE -> duels.deleteStep()
            DeskAction.REPLAY_BRANCH -> duels.branch()
            else -> Unit
        }
        return
    }
    val game = duels.shown ?: return
    val s = game.state
    // The ordering strip (1.0.90) has the keys while it is open: its own arrows, Enter, and K / Shift K / Alt K / R.
    if (duels.ordering != null && runOrdering(duels, action)) return
    if (runFocus(h, action)) return
    VERBS[action]?.let { verb ->
        // Several selected (1.0.90): a verb key moves them all, whatever the pointer is over — but a card under the
        // pointer that is not among them is the pointer's, as before.
        if (duels.selection.size > 1 && (duels.byKeys || duels.hovered == null || duels.hovered in duels.selection) && verb != DuelVerb.ATTACK) {
            duels.verbAll(verb)
            return
        }
        // The focus's card once the keys moved last (1.0.87); else the card under the pointer, the one selected, the one being read.
        val uid = duels.keyTarget() ?: return
        if (uid !in s.cards) return
        // A card in another seat's open pile is the person's to play only when they play both seats (1.0.86).
        val inStrip = duels.strip?.let { (seat, kind) -> s.placeOf(uid).let { it is Place.Pile && it.seat == seat && it.kind == kind } } == true
        val mine = s.solo || duels.seatFor(uid) == duels.bottom || (inStrip && playsBoth(h))
        when {
            verb == DuelVerb.TARGET || (verb == DuelVerb.DEFAULT && !mine) -> duels.verb(uid, DuelVerb.TARGET, seat = duels.bottom)
            else -> duels.verb(uid, verb)
        }
        return
    }
    ZONES[action]?.let { (kind, index) ->
        // While Ai's question stands in the log's foot a digit answers it (1.0.86) — unless a card was just placed:
        // then the digit is still that card's zone, as it was before.
        val digit = DIGITS[action]
        val placing = duels.placed?.let { Duels.now() < it.until } == true
        if (digit != null && !placing && answerByDigit(h, digit)) return
        // No card just placed, and the keys lead (1.0.87): the focused card to that zone — focus h2, press 3.
        if (!placing && duels.byKeys) {
            duels.keyTarget()?.let { placeByKey(h, it, kind, index) }
            return
        }
        val p = duels.placed
        // Shift and a number is a Spell & Trap Zone; a plain number is the zone of the kind just placed in.
        val k = if (kind == ZoneKind.MONSTER && p?.kind == ZoneKind.SPELL) ZoneKind.SPELL else kind
        duels.replace(k, index)
        return
    }
    when (action) {
        DeskAction.DUEL_DRAW -> duels.act(DuelAction.Draw(duels.bottom), duels.bottom)
        DeskAction.DUEL_SHUFFLE -> duels.act(DuelAction.Shuffle(duels.bottom, PileKind.DECK), duels.bottom)
        DeskAction.DUEL_NEXT_PHASE -> if (s.phase == DuelPhase.END) duels.goPhase(null, end = true) else duels.goPhase(s.phase.next())
        DeskAction.DUEL_END_TURN -> duels.goPhase(null, end = true)
        DeskAction.DUEL_LP -> duels.lpPad = if (duels.lpPad == null) duels.bottom else null
        DeskAction.DUEL_THINK -> duels.act(DuelAction.Thinking(duels.bottom, duels.bottom !in s.thinking), duels.bottom)
        // Command mode (1.0.87): the Spotlight, open — or, open already, its field given the keyboard back.
        DeskAction.DUEL_COMMAND -> duels.openSpotlight(swallow = '/')
        DeskAction.DUEL_CHAT -> chatKey(h)
        DeskAction.DUEL_AI_ANSWER, DeskAction.DUEL_AI_CATCH_UP -> answerAi(h, game, catching = action == DeskAction.DUEL_AI_CATCH_UP)
        DeskAction.DUEL_SIDES -> h.neue.update { it.copy(duel = it.duel.copy(twoSided = !it.duel.twoSided)) }
        DeskAction.DUEL_SWAP -> duels.swap()
        DeskAction.DUEL_FACING -> h.neue.update { it.copy(duel = it.duel.copy(facing = !it.duel.facing)) }
        DeskAction.DUEL_RESOLVE -> duels.resolveChain()
        // The chain by keys (1.0.90).
        DeskAction.DUEL_RESOLVE_ALL -> duels.resolveAll()
        DeskAction.DUEL_PASS -> duels.pass()
        DeskAction.DUEL_SELECT -> duels.selectFocused()
        // The opening roll (1.0.87): this seat's dice thrown with a fling of their own.
        DeskAction.DUEL_ROLL -> duels.throwDice(duels.bottom)
        else -> Unit
    }
}

/** The digit keys, while Ai's question stands in the log (1.0.86): its first six options. */
private val DIGITS = mapOf(
    DeskAction.DUEL_ZONE_1 to 1,
    DeskAction.DUEL_ZONE_2 to 2,
    DeskAction.DUEL_ZONE_3 to 3,
    DeskAction.DUEL_ZONE_4 to 4,
    DeskAction.DUEL_ZONE_5 to 5,
    DeskAction.DUEL_ZONE_EMZ_LEFT to 6,
)

/**
 * Y and Shift Y (1.0.86): the log's first cue button, whatever it is now, or Catch up — at a table Ai sits at. While
 * Ai is busy the key says how to stop it rather than queue a cue nobody pressed a button for.
 */
private fun answerAi(h: NeueHolders, game: com.kaiharimoto.mastertool.core.duel.DuelGame, catching: Boolean) {
    if (h.duel.replay != null) return
    // No Ai at this table (1.0.90): Y is No response across the hot-seat while a chain stands, as DUEL_PASS is with Ai off.
    if (!aiAtTable(h)) {
        if (!catching) h.duel.pass()
        return
    }
    val cue = aiCueNow(h, game)
    when {
        cue == AiCue.BUSY -> h.neue.note = Note("${h.ai.name} is thinking. Esc stops it.")
        !catching -> giveCue(h, cue)
        cue == AiCue.YOUR_MOVE -> catchUp(h)
        else -> h.neue.note = Note("Catch up is for when nothing is open: ${h.ai.name} waits on your answer first.")
    }
}

/** Esc and Back on the Duel page: one layer at a time, from the top. */
internal fun dismissDuel(h: NeueHolders): Boolean {
    if (h.neue.page != Page.DUEL || h.neue.hasTop || h.overlays.isOpen) return false
    val d = h.duel
    when {
        // The Spotlight first (1.0.87): it stands over everything on the table.
        d.spotlight != null -> d.closeSpotlight()
        // What is open on the table closes first (1.0.86, the red team: Esc stopped Ai's turn and closed nothing).
        d.combosOpen -> d.combosOpen = false
        d.setupOpen -> d.setupOpen = false
        d.libraryOpen -> d.libraryOpen = false
        d.lpPad != null -> d.lpPad = null
        // Several cards (1.0.90): the ordering strip, the chain well's menu and a link's aim first, then the selection's keys.
        d.ordering != null -> d.ordering = null
        d.chainMenu != null -> d.chainMenu = null
        d.linkTarget != null -> d.linkTarget = null
        d.selCursor != null -> d.selCursor = null
        d.attaching != null -> d.attaching = null
        d.attacking != null -> d.attacking = null
        d.drawer != null -> d.drawer = null
        // Command mode (1.0.87): the verb menu Enter opened, then the open pile, then what was picked up.
        d.verbStrip && d.verbCursor != null -> d.verbStrip = false
        d.strip != null -> d.closeStrip()
        d.picked != null -> d.picked = null
        d.verbStrip -> d.verbStrip = false
        d.verbsOpen -> d.verbsOpen = false
        // Then Ai thinking at the table stops, and the moves it was playing out with it.
        aiAtTable(h) && h.ai.running && h.ai.session?.mode == com.kaiharimoto.mastertool.core.ai.AiSession.MODE_DUEL -> {
            h.ai.stop()
            if (d.playing) d.stopRequested = true
        }
        d.playing -> d.stopRequested = true
        d.selection.isNotEmpty() || d.selecting -> d.clearSelection()
        d.replay != null -> d.closeReplay()
        // The focus is let go last: the ring goes, and the keys act on the pointer's card again.
        d.focus != null -> d.clearFocus()
        else -> return false
    }
    return true
}

// ---- Command mode (1.0.87): the table walked by the keys ------------------------------------------------

/** Enter's duty before 1.0.87, and Ctrl Enter's: Ai's picked answers sent, else the chat. */
private fun chatKey(h: NeueHolders) {
    if (!answerPicked(h)) h.duel.chatFocus++
}

/** The focus's keys: the arrows, the row's ends, Enter, Shift Enter and I. False for any other action. */
private fun runFocus(h: NeueHolders, action: DeskAction): Boolean {
    val d = h.duel
    val menuOpen = d.verbStrip && d.verbCursor != null
    val s0 = d.shown?.state
    // Enter's menu on a link in the chain well (1.0.90): ↑↓ choose in it.
    val chainOpen = d.chainMenu?.let { i -> s0?.let { linkItems(it, i) } }?.takeIf { it.isNotEmpty() }
    if (chainOpen != null && (action == DeskAction.DUEL_FOCUS_UP || action == DeskAction.DUEL_FOCUS_DOWN)) {
        d.chainCursor = (d.chainCursor + if (action == DeskAction.DUEL_FOCUS_UP) -1 else 1).coerceIn(0, chainOpen.size - 1)
        return true
    }
    // The selection's bar, once Enter put the keys in it (1.0.90).
    val selOpen = d.selCursor != null && d.selection.size > 1
    if (selOpen && s0 != null && (action == DeskAction.DUEL_FOCUS_UP || action == DeskAction.DUEL_FOCUS_DOWN || action == DeskAction.DUEL_FOCUS_LEFT || action == DeskAction.DUEL_FOCUS_RIGHT)) {
        val n = selectionVerbs(d, s0, d.eyes.viewers).size
        val back = action == DeskAction.DUEL_FOCUS_UP || action == DeskAction.DUEL_FOCUS_LEFT
        if (n > 0) d.selCursor = ((d.selCursor ?: 0) + if (back) -1 else 1).coerceIn(0, n - 1)
        return true
    }
    when (action) {
        DeskAction.DUEL_FOCUS_UP, DeskAction.DUEL_FOCUS_DOWN -> if (menuOpen) {
            val s = d.shown?.state ?: return true
            val menu = d.inspected?.let { verbMenu(d, s, it, playsBoth(h)) }
            if (menu == null) d.verbStrip = false
            else d.verbCursor = ((d.verbCursor ?: 0) + if (action == DeskAction.DUEL_FOCUS_UP) -1 else 1).coerceIn(0, menu.size - 1)
        } else d.walk(if (action == DeskAction.DUEL_FOCUS_UP) DuelFocus.Dir.UP else DuelFocus.Dir.DOWN)
        DeskAction.DUEL_FOCUS_LEFT -> d.walk(DuelFocus.Dir.LEFT)
        DeskAction.DUEL_FOCUS_RIGHT -> d.walk(DuelFocus.Dir.RIGHT)
        DeskAction.DUEL_FOCUS_ROW_START -> d.walkRow(end = false)
        DeskAction.DUEL_FOCUS_ROW_END -> d.walkRow(end = true)
        DeskAction.DUEL_FOCUS_ACT -> enterOnFocus(h)
        DeskAction.DUEL_PICK -> pickFocus(h)
        DeskAction.DUEL_COORDINATES -> h.neue.update { it.copy(duel = it.duel.copy(coordinates = !it.duel.coordinates)) }
        else -> return false
    }
    return true
}

/** Where a card put down at [slot] lands, as a drop there would ([DuelDrop.intent]). */
private fun spotAt(d: Duels, slot: DuelFocus.Slot, uid: Int): DropSpot = when (slot) {
    is DuelFocus.Slot.Zone -> DropSpot.Zone(if (slot.place.kind == ZoneKind.EMZ) slot.place.copy(seat = d.seatFor(uid)) else slot.place)
    is DuelFocus.Slot.Pile -> DropSpot.Pile(slot.seat, slot.kind)
    is DuelFocus.Slot.HandCard -> DropSpot.Hand(slot.seat, slot.index)
    is DuelFocus.Slot.PileCard -> DropSpot.Pile(slot.seat, slot.kind)
    is DuelFocus.Slot.Link -> DropSpot.Chain
}

/**
 * Enter on the focus: the verb chosen in the menu; an attack or an attach waiting, aimed here; the picked card
 * put down here; a pile opened onto its first card; a card's verbs as a menu. With no focus — the pointer moved
 * last — or Ai's question in the log, Enter is what it was: the answer, else the chat.
 */
private fun enterOnFocus(h: NeueHolders) {
    val d = h.duel
    val s = d.shown?.state ?: return
    val asking = h.ai.question != null && duelTalking(h)
    val focus = d.focus
    // The chain well's menu (1.0.90): Enter does the item chosen.
    d.chainMenu?.let { i ->
        val item = linkItems(s, i).getOrNull(d.chainCursor)
        if (item != null) runLinkItem(d, s, i, item) else d.chainMenu = null
        return
    }
    // The selection's bar with the keys in it: Enter does the verb chosen.
    if (d.selCursor != null && d.selection.size > 1) {
        val v = selectionVerbs(d, s, d.eyes.viewers).getOrNull(d.selCursor ?: 0)
        d.selCursor = null
        if (v != null) d.verbAll(v)
        return
    }
    if (asking || !d.byKeys || focus == null) {
        chatKey(h)
        return
    }
    if (d.verbStrip && d.verbCursor != null) {
        val menu = d.inspected?.let { verbMenu(d, s, it, playsBoth(h)) }
        if (menu == null) d.verbStrip = false else runVerbItem(d, menu, d.verbCursor ?: 0)
        return
    }
    val uid = d.focusUid()
    val attacker = d.attacking
    val attaching = d.attaching
    val picked = d.picked
    val aiming = d.linkTarget
    when {
        // A link's card aims (1.0.90): Enter on a card gives it the arrow.
        aiming != null -> if (uid != null && uid != aiming && focus !is DuelFocus.Slot.Link) d.targetFromLink(uid)
            else h.neue.note = Note("Walk to the card it targets, then Enter. Esc to stop.")
        // On a link in the chain well: what can be done with it, as a menu.
        focus is DuelFocus.Slot.Link -> if (s.chain.isNotEmpty()) {
            d.chainMenu = focus.index.coerceIn(0, s.chain.size - 1)
            d.chainCursor = 0
        }
        attacker != null -> {
            val aim = DuelDrop.intent(s, attacker, spotAt(d, focus, attacker), d.catalog).actions.singleOrNull() as? DuelAction.Attack
            if (aim != null) d.attack(aim.target) else h.neue.note = Note("Not something to attack: their monster, or their hand to attack directly. Esc to stop.")
        }
        attaching != null -> {
            if (uid != null && uid != attaching && focus is DuelFocus.Slot.Zone) d.verb(attaching, DuelVerb.ATTACH, host = uid)
            else h.neue.note = Note("Walk to the monster it goes under, then Enter. Esc to stop.")
        }
        picked != null -> {
            val intent = if (picked in s.cards) DuelDrop.intent(s, picked, spotAt(d, focus, picked), d.catalog, actor = d.dragActor()) else DuelDrop.NONE
            when {
                picked !in s.cards -> d.picked = null
                intent.none -> h.neue.note = Note("Nothing to do with it there")
                d.act(intent.actions, d.seatFor(picked)) -> {
                    d.picked = null
                    d.inspected = picked
                }
            }
        }
        focus is DuelFocus.Slot.Pile -> {
            if (d.strip != focus.seat to focus.kind) d.openPile(focus.seat, focus.kind)
            if (s.seats[focus.seat].pile(focus.kind).isNotEmpty()) d.focusOn(DuelFocus.Slot.PileCard(focus.seat, focus.kind, 0))
        }
        // Several selected and the focus among them: the keys go into the selection's bar.
        uid != null && d.selection.size > 1 && uid in d.selection -> {
            d.inspected = uid
            d.verbStrip = false
            d.selCursor = 0
        }
        uid != null -> {
            d.inspected = uid
            d.selection = setOf(uid)
            d.verbStrip = true
            d.verbCursor = 0
        }
        else -> h.neue.note = Note("Empty. Shift Enter picks a card up; Enter here puts it down.")
    }
}

/** Shift Enter: the focused card picked up (again: put back), to be put down where Enter is pressed next. */
private fun pickFocus(h: NeueHolders) {
    val d = h.duel
    if (!d.byKeys) {
        d.walk(DuelFocus.Dir.UP)
        return
    }
    val uid = d.focusUid()
    if (uid == null) {
        h.neue.note = Note("Nothing here to pick up")
        return
    }
    d.verbStrip = false
    d.picked = if (d.picked == uid) null else uid
}

/**
 * A number with the keys leading and no card just placed (1.0.87): the focused card into that zone. From the hand
 * or a pile it does the obvious thing there (a monster Summoned, a Spell activated — a plain number is a Spell &
 * Trap Zone for a card that goes in one); on the field it moves, as a drop would.
 */
private fun placeByKey(h: NeueHolders, uid: Int, kind: ZoneKind, index: Int) {
    val d = h.duel
    val s = d.shown?.state ?: return
    val seat = d.seatFor(uid)
    if (!s.solo && seat != d.bottom) {
        h.neue.note = Note("That card is the other seat's")
        return
    }
    if (s.placeOf(uid) is Place.Zone) {
        val intent = DuelDrop.intent(s, uid, DropSpot.Zone(Place.Zone(seat, kind, index)), d.catalog)
        if (intent.none) h.neue.note = Note("Nothing to do with it there") else d.act(intent.actions, seat)
        return
    }
    val wants = DuelVerbs.zoneKind(s, seat, uid, DuelVerbs.default(s, seat, uid, d.catalog), d.catalog)
    val k = if (kind == ZoneKind.MONSTER && wants == ZoneKind.SPELL) ZoneKind.SPELL else kind
    d.verb(uid, DuelVerb.DEFAULT, zone = Place.Zone(seat, k, index))
}

/**
 * The ordering strip's keys (1.0.90): ← → choose a card, Alt ← → move it, Enter puts them on the Deck as shown, K and
 * Shift K say top or bottom, R puts them in a random order, Alt K shuffles them in. False for a key it leaves alone.
 */
private fun runOrdering(d: Duels, action: DeskAction): Boolean {
    when (action) {
        DeskAction.DUEL_FOCUS_LEFT, DeskAction.DUEL_FOCUS_UP -> d.orderCursor(-1)
        DeskAction.DUEL_FOCUS_RIGHT, DeskAction.DUEL_FOCUS_DOWN -> d.orderCursor(1)
        DeskAction.DUEL_FOCUS_ROW_START -> d.orderCursor(-1000)
        DeskAction.DUEL_FOCUS_ROW_END -> d.orderCursor(1000)
        DeskAction.DUEL_ORDER_EARLIER -> d.orderMove(-1)
        DeskAction.DUEL_ORDER_LATER -> d.orderMove(1)
        DeskAction.DUEL_FOCUS_ACT -> d.commitOrdering()
        DeskAction.DUEL_DECK_TOP -> d.orderTo(false)
        DeskAction.DUEL_DECK_BOTTOM -> d.orderTo(true)
        DeskAction.DUEL_DECK_SHUFFLE -> d.orderShuffle()
        DeskAction.DUEL_REVEAL -> d.orderRandom()
        else -> return false
    }
    return true
}
