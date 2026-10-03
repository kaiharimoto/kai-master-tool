package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelBattle
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.layout.PhaseBox
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.RequestFocusOnce
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/** The short names on the phase strip, as players say them. */
private val PHASE_SHORT = mapOf(
    DuelPhase.DRAW to "DP", DuelPhase.STANDBY to "SP", DuelPhase.MAIN1 to "M1",
    DuelPhase.BATTLE to "BP", DuelPhase.MAIN2 to "M2", DuelPhase.END to "EP",
)

/**
 * The score column (1.0.78, kai: the seat bars took a row each for what the piles already show): each
 * seat's name and life points at its own end of the column — theirs at the top, yours at the bottom —
 * the seat whose turn it is in ink, and the turn between. A click on the life points opens the pad.
 */
@Composable
internal fun ScoreColumn(h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout) {
    val c = Mu.colors
    l.score.forEach { (seat, slot) ->
        val st = s.seats[seat]
        val turn = s.active == seat
        val ink = if (turn) c.paper else c.ink
        val top = seat != l.bottom
        Column(
            Modifier.zIndex(30f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp)
                .background(if (turn) c.ink else c.paper).border(1.dp, if (turn) c.ink else c.ink25)
                .cursorPointer(caption = if (aimed(duels, s, seat)) "Attack directly" else "Change LP")
                .muClickable {
                    // An attack waiting (1.0.86): their life points take it directly.
                    if (aimed(duels, s, seat)) duels.attack(null)
                    else { duels.attacking = null; duels.lpPad = if (duels.lpPad == seat) null else seat }
                }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp, if (top) Alignment.Top else Alignment.Bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val name: @Composable () -> Unit = { Micro(DuelWords.seatName(s, seat), color = ink, size = 9.sp, maxLines = 1) }
            val marks: @Composable () -> Unit = {
                when {
                    s.conceded == seat -> Micro("Conceded", color = ink, size = 8.sp, maxLines = 1)
                    seat in s.thinking -> Micro("Thinking", color = ink, size = 8.sp, maxLines = 1)
                    l.farHandFolded && top -> Micro("Hand ${st.hand.size}", color = ink, size = 8.sp, maxLines = 1)
                }
            }
            if (top) name()
            Mono("${st.lp}", color = ink, size = if (slot.width >= 64f) 17.sp else 14.sp)
            marks()
            if (!top) name()
        }
    }
    // A short column folds the turn into the phase now (1.0.86).
    if (l.phasesCompact) return
    val t = l.turn
    Row(
        Modifier.zIndex(30f).offset(t.left.dp, t.top.dp).size(t.width.dp, t.height.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Micro("Turn ", color = c.ink45, size = 9.sp)
        Mono("${s.turn}", color = c.ink)
    }
}

/**
 * The phases in the score column: the current one inverted, any of them a click away, End turn last.
 * Where the column is short (1.0.86, a phone lying down gave each phase about 12 dp), it holds the phase
 * now with the turn — its name opens every phase as a menu — one large Next phase, and End turn, each
 * at least a finger's height ([DuelLayout.phaseBoxes]).
 */
@Composable
internal fun PhaseStrip(h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout) {
    val c = Mu.colors
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    l.phaseBoxes().forEach { b ->
        val slot = b.slot
        val place = Modifier.zIndex(30f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp)
        when (b.kind) {
            PhaseBox.Kind.PHASE -> {
                val p = b.phase ?: DuelPhase.DRAW
                val on = s.phase == p
                Box(
                    place.background(if (on) c.ink else c.paper)
                        .border(1.dp, if (on) c.ink else c.ink25)
                        .cursorPointer(caption = p.label)
                        .muClickable { if (!on) duels.goPhase(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Mono(PHASE_SHORT.getValue(p), color = if (on) c.paper else c.ink, size = 11.sp)
                }
            }
            PhaseBox.Kind.NOW -> Column(
                place.background(c.ink).border(1.dp, c.ink)
                    .onGloballyPositioned { menuAt = it.boundsInWindow().topLeft }
                    .cursorPointer(caption = "${s.phase.label} Phase · every phase")
                    .muClickable { h.neue.menu = MenuSpec(menuAt, phaseMenu(duels, s)) },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Micro("Turn ${s.turn}", color = c.paper, size = 8.sp, maxLines = 1)
                Mono("${PHASE_SHORT.getValue(s.phase)} ▾", color = c.paper, size = 11.sp)
            }
            PhaseBox.Kind.NEXT -> {
                // In the End Phase the next thing is the other player's turn.
                val ends = s.phase == DuelPhase.END
                val next = s.phase.next()
                Column(
                    place.background(c.paper).border(1.dp, c.ink)
                        .cursorPointer(caption = if (ends) "End turn" else "Next phase: ${next.label}")
                        .muClickable { if (ends) duels.goPhase(null, end = true) else duels.goPhase(next) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Micro(if (ends) "End" else "Next", color = c.ink, size = 9.sp, maxLines = 1)
                    Mono(if (ends) "turn" else PHASE_SHORT.getValue(next), color = c.ink, size = 15.sp)
                }
            }
            PhaseBox.Kind.END -> Box(
                place.border(1.dp, c.ink)
                    .cursorPointer(caption = "End turn")
                    .muClickable { duels.goPhase(null, end = true) },
                contentAlignment = Alignment.Center,
            ) {
                Micro(if (s.solo) "Next" else "End", color = c.ink, size = 9.sp)
            }
        }
    }
}

/** Every phase, and End turn, from the phase now's name on a short column (a menu in the window's own layer). */
private fun phaseMenu(duels: Duels, s: DuelState): List<MenuEntry> =
    DuelPhase.entries.map { p ->
        MenuEntry(
            "${p.label} Phase",
            hint = if (p == s.phase) "now" else PHASE_SHORT.getValue(p),
            enabled = p != s.phase,
            reason = if (p == s.phase) "The phase now" else null,
        ) { duels.goPhase(p) }
    } + MenuEntry("End turn", separatorBefore = true) { duels.goPhase(null, end = true) }

/** Whether a click on [seat]'s life points would declare the waiting attack directly (1.0.86). */
private fun aimed(duels: Duels, s: DuelState, seat: Int): Boolean {
    val a = duels.attacking ?: return false
    val by = duels.seatFor(a)
    return seat != by && com.kaiharimoto.mastertool.core.duel.DuelVerbs.canAttack(s, by, a)
}

/**
 * The band over the near hand while an attack waits for what it attacks (1.0.86): which monster, what to
 * click, and a way out for a finger (Esc, Back or a right-click elsewhere).
 */
@Composable
internal fun AttackBand(duels: Duels, s: DuelState, l: DuelLayout, attacker: Int) {
    val c = Mu.colors
    val hand = l.pile(l.bottom, PileKind.HAND) ?: l.field
    val name = s.cards[attacker]?.let { duels.catalog.nameOf(it) } ?: return
    Row(
        Modifier.zIndex(DuelFrames.Z_STRIP + 3f).offset(l.field.left.dp, hand.top.dp).width(l.field.width.dp)
            .background(c.ink).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Small("Attacking with $name — click a monster, or their life points for a direct attack · Esc", Modifier.weight(1f), color = c.paper, maxLines = 2)
        Box(
            Modifier.border(1.dp, c.paper).cursorPointer(caption = "Stop attacking").muClickable { duels.attacking = null }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) { Micro("Cancel", color = c.paper) }
    }
}

/**
 * The battle chip (1.0.86): after an attack is declared, what the printed numbers say battle comes to —
 * "Apply 700 to Rival", "Destroy Spark" — as one press that commits the life points and the moves to the
 * GY as one group. A suggestion only: it goes on the next move that changes the table, or by its ✕.
 */
@Composable
internal fun BattleChip(h: NeueHolders, duels: Duels, game: com.kaiharimoto.mastertool.core.duel.DuelGame, l: DuelLayout) {
    val c = Mu.colors
    val outcome = remember(game.cursor, game.entries.size, game.state) { DuelBattle.pending(game, duels.catalog) } ?: return
    var dismissed by remember(game.cursor) { mutableStateOf(false) }
    if (dismissed) return
    val s = game.state
    val words = DuelBattle.words(s, outcome, duels.catalog)
    // Beside the life points it changes, in the column's middle when it changes none.
    val anchor = outcome.damaged?.let { l.score[it] } ?: l.turn
    val nearSeat = outcome.damaged == l.bottom
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.layout.Layout(
        content = {
            Row(Modifier.width(BATTLE_CHIP_W.dp).background(c.paper).border(1.dp, c.ink)) {
                Column(
                    Modifier.weight(1f).background(c.ink)
                        .cursorPointer(caption = "Apply battle")
                        .muClickable { duels.act(DuelBattle.actions(s, outcome), duels.bottom) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    words.forEach { Small(it, color = c.paper, maxLines = 2) }
                }
                IconButton(Icons.X, { dismissed = true }, size = 28.dp, label = "Not this time")
            }
        },
        modifier = Modifier.zIndex(DuelFrames.Z_STRIP + 3f),
    ) { measurables, constraints ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        val px = density.density
        val gap = l.gap * px
        val w = p.width.toFloat()
        val hgt = p.height.toFloat()
        // Left of the score column, level with the seat's block: its top for the far seat, its bottom for the near.
        val x = (anchor.left * px - gap - w).coerceAtLeast(0f)
        val y = if (nearSeat) anchor.bottom * px - hgt else anchor.top * px
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place(x.toInt(), y.coerceIn(0f, (l.height * px - hgt).coerceAtLeast(0f)).toInt())
        }
    }
}

private const val BATTLE_CHIP_W = 176

/**
 * The chain written down: its links, newest at the bottom, each with where its card is now ("Fuwalo ·
 * GY") — named only for the eyes the table is drawn through, so a face-down link stays "A set card".
 * A click resolves the newest (a Normal Spell or Trap to the GY with it); a right-click clears it.
 */
@Composable
internal fun ChainWell(s: DuelState, l: DuelLayout, duels: Duels, viewers: Set<Int>) {
    val c = Mu.colors
    val slot = l[DuelSpot.Chain] ?: return
    Column(
        Modifier.zIndex(20f).offset(slot.left.dp, slot.top.dp).size(slot.width.dp, slot.height.dp)
            // The table's one arbiter takes the press; this says what it will do (1.0.86).
            .then(if (s.chain.isEmpty()) Modifier else Modifier.cursorPointer(caption = "Resolve · right-click clears"))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Micro("Chain", color = if (s.chain.isEmpty()) c.ink45 else c.ink, size = 9.sp)
        // The link the keys stand on (1.0.90): its line inverted, as the Spotlight's chosen row is.
        val focused = (duels.focus as? com.kaiharimoto.mastertool.core.layout.DuelFocus.Slot.Link)?.takeIf { duels.byKeys }?.index ?: duels.chainMenu
        s.chain.takeLast(6).forEachIndexed { i, link ->
            val n = s.chain.size - minOf(6, s.chain.size) + i + 1
            val on = focused == n - 1
            Row(
                Modifier.then(if (on) Modifier.background(c.ink) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.background(if (on) c.paper else c.ink).padding(horizontal = 3.dp)) { Mono("$n", color = if (on) c.ink else c.paper, size = 9.sp) }
                val card = link.uid?.let { s.cards[it] }
                val sees = card != null && viewers.any { v -> com.kaiharimoto.mastertool.core.duel.DuelSight.sees(s, card.uid, v) }
                val where = link.uid?.let { s.placeOf(it) }?.let { p ->
                    when (p) {
                        is Place.Pile -> p.kind.label
                        is Place.Zone -> DuelWords.zoneName(p).removePrefix("the ")
                        else -> null
                    }
                }
                val name = when {
                    card == null -> link.note.ifBlank { "Effect" }
                    sees -> duels.catalog.nameOf(card)
                    else -> "A set card"
                }
                Mono(name + (where?.let { " · $it" } ?: "") + if (link.negated) " · negated" else "", color = if (on) c.paper else c.ink, size = 9.sp)
            }
        }
        // Its keys, while a chain stands (1.0.90).
        if (s.chain.isNotEmpty() && s.chain.size <= 4) {
            val q = com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(com.kaiharimoto.mastertool.core.input.DeskAction.DUEL_RESOLVE)?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd)
            val all = com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(com.kaiharimoto.mastertool.core.input.DeskAction.DUEL_RESOLVE_ALL)?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd)
            Mono("$q · $all all", color = c.ink45, size = 8.sp)
        }
    }
}

/** The ground of an open pile, over the field, with its name, its rows when it scrolls, and a way to close it. */
@Composable
internal fun StripGround(duels: Duels, s: DuelState, l: DuelLayout, seat: Int, kind: PileKind) {
    val c = Mu.colors
    val n = s.seats[seat].pile(kind).size
    val grid = DuelFrames.stripGrid(n, l)
    val ground = stripGround(s, l, seat to kind)
    Box(
        Modifier.zIndex(DuelFrames.Z_STRIP - 0.5f)
            .offset(ground.left.dp, ground.top.dp)
            .size(ground.width.dp, ground.height.dp)
            .background(c.paper).border(1.dp, c.ink),
    ) {
        Row(
            Modifier.fillMaxWidth().height(DuelFrames.STRIP_HEAD.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Micro("${DuelWords.seatName(s, seat)} · ${kind.label} · $n", Modifier.weight(1f), color = c.ink)
            if (grid.scrolls) {
                val first = duels.stripRow.coerceIn(0, grid.rows - grid.visibleRows)
                Mono("rows ${first + 1}–${first + grid.visibleRows} of ${grid.rows}", color = c.ink45)
                IconButton(Icons.ArrowUp, { duels.stripRow = (first - 1).coerceAtLeast(0) }, size = 22.dp, label = "Earlier rows", enabled = first > 0)
                IconButton(Icons.ArrowDown, { duels.stripRow = (first + 1).coerceAtMost(grid.rows - grid.visibleRows) }, size = 22.dp, label = "Later rows", enabled = first < grid.rows - grid.visibleRows)
            }
            if (kind == PileKind.DECK) {
                MuButton("Shuffle and close", { duels.act(DuelAction.Shuffle(seat, PileKind.DECK), seat); duels.strip = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            IconButton(Icons.X, { duels.closeStrip() }, size = 22.dp, label = "Close")
        }
    }
}

/** A deck looked through and closed: "Shuffle" stands on it for a few seconds, as a player shuffles after a search. */
@Composable
internal fun ShuffleOffer(duels: Duels, s: DuelState, l: DuelLayout) {
    val c = Mu.colors
    val (seat, until) = duels.offerShuffle ?: return
    LaunchedEffect(until) {
        kotlinx.coroutines.delay((until - Duels.now()).coerceAtLeast(0))
        if (duels.offerShuffle?.second == until) duels.offerShuffle = null
    }
    val slot = l.pile(seat, PileKind.DECK) ?: return
    Box(
        Modifier.zIndex(45f).offset(slot.left.dp, (slot.bottom - 26f).dp).width(slot.width.dp).height(26.dp)
            .background(c.ink).cursorPointer(caption = "Shuffle the Deck")
            .muClickable { duels.act(DuelAction.Shuffle(seat, PileKind.DECK), seat); duels.offerShuffle = null },
        contentAlignment = Alignment.Center,
    ) { Micro("Shuffle", color = c.paper) }
}

/**
 * The verbs the strip offers for [uid] (1.0.78), and whether the person plays the card or only points at it:
 * the obvious verb first, then the rest; Target first for a card that is not theirs. The strip's last
 * item, after [verbs], is Point at it. One list for the pointer's strip and the keyboard's menu (1.0.87).
 */
internal data class VerbMenu(val uid: Int, val verbs: List<com.kaiharimoto.mastertool.core.duel.DuelVerb>, val mine: Boolean) {
    /** The verbs and Point at it. */
    val size: Int get() = verbs.size + 1
}

internal fun verbMenu(duels: Duels, s: DuelState, uid: Int, playsBoth: Boolean): VerbMenu? {
    if (uid !in s.cards) return null
    val actor = duels.seatFor(uid)
    val inStrip = duels.strip?.let { (seat, kind) -> s.placeOf(uid).let { it is Place.Pile && it.seat == seat && it.kind == kind } } == true
    // Another seat's open pile is for pointing at, unless the person plays both seats (1.0.86).
    val mine = s.solo || actor == duels.bottom || (inStrip && com.kaiharimoto.mastertool.core.duel.DuelSeats.stripPlays(s, actor, duels.bottom, playsBoth))
    val offered = com.kaiharimoto.mastertool.core.duel.DuelVerbs.offered(s, actor, uid, duels.catalog)
    val verbs = if (mine) offered else listOf(com.kaiharimoto.mastertool.core.duel.DuelVerb.TARGET) + offered.filter { it != com.kaiharimoto.mastertool.core.duel.DuelVerb.TARGET }
    return if (verbs.isEmpty()) null else VerbMenu(uid, verbs, mine)
}

/** The menu's [i]-th item done: a verb, or (past the verbs) Point at it. The strip goes away. */
internal fun runVerbItem(duels: Duels, menu: VerbMenu, i: Int) {
    duels.verbStrip = false
    val v = menu.verbs.getOrNull(i)
    when {
        v == null -> duels.act(DuelAction.Ping(duels.bottom, DuelAction.PING_LOOK, uid = menu.uid))
        v == com.kaiharimoto.mastertool.core.duel.DuelVerb.TARGET && !menu.mine -> duels.verb(menu.uid, v, seat = duels.bottom)
        else -> duels.verb(menu.uid, v)
    }
}

/**
 * What the selected card can do, standing beside it on the table (1.0.78, kai's pick of three): the
 * obvious verb first, in ink, every one with its key. Right of the card where there is room, else left;
 * above a card in the hand. Esc, a click on the table, or a verb run puts it away. Opened by Enter on the
 * focus (1.0.87), it is a menu: the verb in ink is the one ↑/↓ have chosen and Enter does.
 */
@Composable
internal fun VerbStrip(duels: Duels, s: DuelState, l: DuelLayout, frames: List<com.kaiharimoto.mastertool.core.layout.CardFrame>, playsBoth: Boolean) {
    val c = Mu.colors
    val uid = duels.inspected?.takeIf { it in s.cards } ?: return
    val f = frames.firstOrNull { it.uid == uid && it.shown } ?: return
    val menu = verbMenu(duels, s, uid, playsBoth) ?: return
    val cursor = duels.verbCursor?.coerceIn(0, menu.size - 1)
    val turned = f.rotation % 180f != 0f
    val vw = if (turned) f.h else f.w
    val vh = if (turned) f.w else f.h
    val anchor = com.kaiharimoto.mastertool.core.layout.Slot(f.centerX - vw / 2f, f.centerY - vh / 2f, vw, vh)
    val inHand = s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.HAND }
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.layout.Layout(
        content = {
            Column(
                Modifier.width(VERB_STRIP_W.dp).background(c.paper).border(1.dp, c.ink).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                menu.verbs.forEachIndexed { i, v ->
                    val key = VERB_KEYS[v]?.let { com.kaiharimoto.mastertool.core.input.DeskShortcuts.chordFor(it) }?.let(com.kaiharimoto.mastertool.core.input.DeskShortcuts::kbd)
                    VerbChip(verbWords(v, s), key, strong = if (cursor == null) i == 0 else i == cursor, modifier = Modifier.fillMaxWidth()) { runVerbItem(duels, menu, i) }
                }
                VerbChip("Point at it", "Alt click", strong = cursor == menu.verbs.size, modifier = Modifier.fillMaxWidth()) { runVerbItem(duels, menu, menu.verbs.size) }
                if (cursor != null) Mono("↑↓ choose · Enter · Esc", color = c.ink45, size = 9.sp)
            }
        },
        // Over everything the table draws — the score column, the phases, the arrows (kai, 1.0.88: the menu sat under the
        // phase buttons and the life points) — below only a card being carried and the drop's highlight.
        modifier = Modifier.zIndex(VERB_Z),
    ) { measurables, constraints ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        val px = density.density
        val gap = 6f * px
        val w = p.width.toFloat()
        val hgt = p.height.toFloat()
        val maxW = l.width * px
        val maxH = l.height * px
        val (x, y) = if (inHand) {
            (anchor.centerX * px - w / 2f) to (anchor.top * px - hgt - gap)
        } else {
            val right = anchor.right * px + gap
            val left = anchor.left * px - gap - w
            (if (right + w <= maxW - 4f * px || left < 0f) right else left) to (anchor.top * px)
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place(
                x.coerceIn(0f, (maxW - w).coerceAtLeast(0f)).toInt(),
                y.coerceIn(0f, (maxH - hgt).coerceAtLeast(0f)).toInt(),
            )
        }
    }
}

/** The verb strip's width: the longest verb and its key. */
private const val VERB_STRIP_W = 168

/** The life-point pad: type a change (−1000, +500, =4000, /2) or tap one. */
@Composable
internal fun LpPad(duels: Duels, s: DuelState, l: DuelLayout, seat: Int) {
    val c = Mu.colors
    val anchor = l.score[seat] ?: l.turn
    val focus = remember { FocusRequester() }
    var text by remember(seat) { mutableStateOf("") }
    fun apply(expr: String) {
        val who = if (seat == duels.bottom) "" else "opp "
        if (duels.run("lp $who$expr")) duels.lpPad = null
    }
    // Beside the score it changes: level with its top for the far seat, its bottom for the near.
    val top = if (seat != l.bottom) anchor.top else anchor.bottom - 92f
    val left = (anchor.left - 8f - 300f).coerceAtLeast(0f)
    Column(
        Modifier.zIndex(70f).offset(left.dp, top.dp).width(300.dp).height(92.dp)
            .background(c.paper).border(1.dp, c.ink).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro("${DuelWords.seatName(s, seat)} · ${s.seats[seat].lp}", color = c.ink)
            MuInput(text, { text = it }, Modifier.weight(1f), placeholder = "-1000", mono = true, dense = true, focusRequester = focus, onSubmit = { apply(text) })
            IconButton(Icons.X, { duels.lpPad = null }, size = 24.dp, label = "Close")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("-1000", "-500", "-100", "+500", "+1000", "/2").forEach { chip ->
                Box(
                    Modifier.weight(1f).border(1.dp, c.ink25).cursorPointer(caption = chip).muClickable { apply(chip) }.padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Mono(if (chip == "/2") "½" else chip, color = c.ink) }
            }
        }
    }
    RequestFocusOnce(focus, seat)
}

/** The verb strip's layer: above the score column (30), the arrows (50) and open piles, under a carried card (100). */
private const val VERB_Z = 85f
