package com.kaiharimoto.neue.duel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.DuelSelection
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.DuelFrames
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.DuelSpot
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu
import kotlin.math.roundToInt

// ---- Several cards, one move (1.0.89, kai: "let me select multiple cards on the field, graveyard, hand, and across
// graveyard and banished and perform an action with them. if put to the bottom of the deck or top of the deck, I can
// choose the order") -----------------------------------------------------------------------------------------------

/** Whether the table's eyes see [uid]: a card they do not is named by where it is, and offered only what needs no name. */
internal fun seenBy(s: DuelState, uid: Int, viewers: Set<Int>): Boolean = viewers.isEmpty() || viewers.any { DuelSight.sees(s, uid, it) }

/** What every selected card can do together ([DuelSelection.verbs]), for the selection's bar and its keys. */
internal fun selectionVerbs(duels: Duels, s: DuelState, viewers: Set<Int>): List<DuelVerb> =
    DuelSelection.verbs(s, duels.selection.filter { it in s.cards }, duels.catalog, duels::seatFor, sees = { seenBy(s, it, viewers) })

/** A verb's words where it stands: Activate while a chain stands is a response, and says so (1.0.89). */
internal fun verbWords(v: DuelVerb, s: DuelState, several: Boolean = false): String = when {
    v == DuelVerb.ACTIVATE && s.chain.isNotEmpty() -> if (several) "Chain them" else "Chain it (link ${s.chain.size + 1})"
    else -> v.label
}

/** The key a verb answers to, written as this machine writes it. */
internal fun verbKey(v: DuelVerb): String? = VERB_KEYS[v]?.let { DeskShortcuts.chordFor(it) }?.let(DeskShortcuts::kbd)

private fun keyOf(a: DeskAction): String? = DeskShortcuts.chordFor(a)?.let(DeskShortcuts::kbd)

/**
 * Each selected card's place in the selection, "1/3", on the card as it lies (1.0.89): an ink box at its corner. A card in a
 * pile that is shut has no badge; the selection's bar lists it.
 */
@Composable
internal fun SelectionBadges(duels: Duels, s: DuelState, frames: List<CardFrame>) {
    val c = Mu.colors
    val picked = duels.ordering?.order ?: duels.selection.toList()
    if (picked.size < 2) return
    // Measured first, drawn after: no return out of a lambda that draws (NonLocalReturnTest).
    val badges = picked.mapIndexedNotNull { i, uid -> frames.firstOrNull { it.uid == uid && it.shown }?.let { Triple(i, uid, it) } }
    badges.forEach { (i, uid, f) ->
        key(uid) {
            val box = seenBox(f)
            Box(Modifier.zIndex(f.z + 0.5f).offset((box.left + 3).dp, (box.top + 3).dp).background(c.ink).border(1.dp, c.paper).padding(horizontal = 4.dp, vertical = 1.dp)) {
                Mono("${i + 1}/${picked.size}", color = c.paper, size = 11.sp)
            }
        }
    }
}

/**
 * The selection's bar (1.0.89): with two cards or more selected, a band over the near hand lists them in the order picked —
 * each by name when the table's eyes see it, else by where it is — and every verb they all take, each with its key; one
 * press does it to all of them, one undo. A card's chip takes it out; Clear (Esc) lets them all go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionBar(h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout, viewers: Set<Int>, frames: List<CardFrame>) {
    val c = Mu.colors
    val uids = duels.selection.filter { it in s.cards }
    if (uids.size < 2 || duels.ordering != null) return
    val verbs = selectionVerbs(duels, s, viewers)
    val cursor = duels.selCursor?.coerceIn(0, (verbs.size - 1).coerceAtLeast(0))
    val hand = l.pile(l.bottom, PileKind.HAND) ?: l.field
    val density = LocalDensity.current
    androidx.compose.ui.layout.Layout(
        content = {
            Column(
                Modifier.width(l.field.width.dp).background(c.paper).border(1.dp, c.ink).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.background(c.ink).padding(horizontal = 6.dp, vertical = 4.dp)) { Micro("${uids.size} selected", color = c.paper) }
                    uids.forEachIndexed { i, uid ->
                        val label = DuelSelection.label(s, uid, seenBy(s, uid, viewers), duels.catalog, duels.bottom, duels.game?.header?.seed ?: 0L)
                        Row(
                            Modifier.border(1.dp, c.ink25).cursorPointer(caption = "Take it out").muClickable { duels.toggleSelect(uid) }
                                .padding(end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(Modifier.background(c.ink).padding(horizontal = 5.dp, vertical = 3.dp)) { Mono("${i + 1}", color = c.paper) }
                            Micro(label.name, color = c.ink)
                            label.coord?.let { Mono(it, color = c.ink45) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (verbs.isEmpty()) Small("Nothing they can all do together: take one out, or act on each.", color = c.ink70)
                    verbs.forEachIndexed { i, v ->
                        val words = when (v) {
                            DuelVerb.DECK_TOP -> "Top of Deck, in order…"
                            DuelVerb.DECK_BOTTOM -> "Bottom of Deck, in order…"
                            DuelVerb.TARGET -> "Target each"
                            else -> verbWords(v, s, several = true)
                        }
                        VerbChip(words, verbKey(v), strong = cursor == i) { duels.verbAll(v) }
                    }
                    VerbChip("Clear", "Esc") { duels.clearSelection() }
                }
                if (cursor != null) Mono("↑↓ choose · Enter · Esc", color = c.ink45, size = 9.sp)
            }
        },
        modifier = Modifier.zIndex(PICKS_Z),
    ) { measurables, constraints ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        val px = density.density
        val x = l.field.left * px
        // Its foot on the near hand's top edge, or its head at the field's top: whichever covers less of what is picked and
        // of an open pile (1.0.89, the first picture: the bar over the hand hid the banished pile laid open under it).
        val hgt = p.height / px
        val low = (hand.top - hgt - 4f).coerceAtLeast(0f)
        val high = l.field.top + 4f
        fun covers(top: Float): Float {
            val band = com.kaiharimoto.mastertool.core.layout.Slot(l.field.left, top, l.field.width, hgt)
            fun over(a: com.kaiharimoto.mastertool.core.layout.Slot): Float {
                val w = minOf(a.right, band.right) - maxOf(a.left, band.left)
                val hh = minOf(a.bottom, band.bottom) - maxOf(a.top, band.top)
                return if (w > 0f && hh > 0f) w * hh else 0f
            }
            val strip = duels.strip?.let { stripGround(s, l, it) }?.let(::over) ?: 0f
            val picked = frames.filter { it.shown && it.uid in duels.selection }.sumOf { over(seenBox(it)).toDouble() }.toFloat()
            return strip * 4f + picked
        }
        val top = if (covers(high) < covers(low)) high else low
        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x.roundToInt(), (top * px).roundToInt()) }
    }
}

/**
 * The ordering strip (1.0.89): several cards going onto a Deck, laid in a row as they will stand in it, **top first**, each
 * with its number. The order picked is the first order; a drag, or ← → to choose and Alt ← → to move, changes it; K and
 * Shift K say top or bottom; Enter puts them there, R in a random order, Alt K shuffles them in, Esc lets it go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrderingStrip(h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout, viewers: Set<Int>) {
    val c = Mu.colors
    val o = duels.ordering ?: return
    val order = o.order.filter { it in s.cards }
    if (order.isEmpty()) return
    val index = h.builder.index
    val n = order.size
    val gap = 10f
    val pad = 10f
    val cardW = minOf(l.card * 0.85f, (l.field.width - pad * 2 - gap * (n - 1)) / n).coerceAtLeast(28f)
    val cardH = cardW / CARD_RATIO
    val width = (cardW * n + gap * (n - 1) + pad * 2).coerceAtLeast(minOf(600f, l.field.width))
    val left = l.field.left + (l.field.width - width) / 2f
    val orderNow by rememberUpdatedState(order)
    val px = LocalDensity.current.density
    androidx.compose.ui.layout.Layout(
        content = { Box(
        Modifier.width(width.dp).background(c.paper).border(2.dp, c.ink).padding(pad.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Micro(DuelSelection.orderHead(o.bottom), Modifier.weight(1f), color = c.ink)
                VerbChip("Top", keyOf(DeskAction.DUEL_DECK_TOP), strong = !o.bottom) { duels.orderTo(false) }
                VerbChip("Bottom", keyOf(DeskAction.DUEL_DECK_BOTTOM), strong = o.bottom) { duels.orderTo(true) }
            }
            Mono(
                (if (o.bottom) "1 stands highest, $n is the Deck's bottom card" else "1 is the Deck's new top card, $n the lowest of them") + " · " + ORDER_HINT,
                color = c.ink45, size = 10.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap.dp, Alignment.CenterHorizontally)) {
                order.forEachIndexed { i, uid ->
                    key(uid) {
                        var dx by remember { mutableStateOf(0f) }
                        val inst = s.cards.getValue(uid)
                        val sees = seenBy(s, uid, viewers)
                        val card = if (!sees || (inst.token && inst.code == 0)) null else index.byId(CardId(inst.code))
                        val chosen = i == o.cursor
                        Column(
                            Modifier.width(cardW.dp).zIndex(if (dx != 0f) 2f else 1f).graphicsLayer { translationX = dx * density }
                                .cursorPointer(caption = "Drag to reorder", emphasis = true)
                                .pointerInput(uid) {
                                    detectDragGestures(
                                        onDragStart = { duels.ordering = duels.ordering?.copy(cursor = orderNow.indexOf(uid).coerceAtLeast(0)) },
                                        onDragEnd = {
                                            val from = orderNow.indexOf(uid)
                                            val steps = (dx / (cardW + gap)).roundToInt()
                                            dx = 0f
                                            if (from >= 0 && steps != 0) duels.orderMove(steps, from)
                                        },
                                        onDragCancel = { dx = 0f },
                                    ) { ch, drag -> ch.consume(); dx += drag.x / density }
                                }
                                .muClickable { duels.ordering = duels.ordering?.copy(cursor = i) },
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(cardW.dp, cardH.dp).then(if (chosen) Modifier.border(2.dp, c.ink) else Modifier)) {
                                when {
                                    card != null -> NeueCard(card, Modifier.fillMaxSize().padding(if (chosen) 3.dp else 0.dp), foil = "off")
                                    sees -> TokenFace(duels.catalog.nameOf(inst), Modifier.fillMaxSize())
                                    else -> CardBack(Modifier.fillMaxSize().padding(if (chosen) 3.dp else 0.dp))
                                }
                                Box(Modifier.align(Alignment.TopStart).background(c.ink).border(1.dp, c.paper).padding(horizontal = 5.dp, vertical = 1.dp)) {
                                    Mono("${i + 1}", color = c.paper, size = 12.sp)
                                }
                            }
                            val label = DuelSelection.label(s, uid, sees, duels.catalog, duels.bottom, duels.game?.header?.seed ?: 0L)
                            Micro(label.name, color = if (chosen) c.ink else c.ink70, size = 9.sp)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VerbChip(if (o.bottom) "Put them on the bottom" else "Put them on top", "Enter", strong = true) { duels.commitOrdering() }
                VerbChip("Random order", keyOf(DeskAction.DUEL_REVEAL)) { duels.orderRandom() }
                VerbChip("Shuffle in", keyOf(DeskAction.DUEL_DECK_SHUFFLE)) { duels.orderShuffle() }
                VerbChip("Cancel", "Esc") { duels.ordering = null }
            }
        }
    } },
        modifier = Modifier.zIndex(PICKS_Z),
    ) { measurables, constraints ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        // In the middle of the field, over it, as an open pile lies.
        val top = (l.field.top + (l.field.height - p.height / px) / 2f).coerceAtLeast(0f)
        layout(constraints.maxWidth, constraints.maxHeight) { p.place((left * px).roundToInt(), (top * px).roundToInt()) }
    }
}

/** The ordering strip's keys, as its foot does not have room to say: ← → choose, Alt ← → move. */
internal val ORDER_HINT: String get() = "← → choose · ${keyOf(DeskAction.DUEL_ORDER_EARLIER) ?: "Alt ←"} ${keyOf(DeskAction.DUEL_ORDER_LATER) ?: "Alt →"} move · drag"

// ---- The chain by keys (1.0.89) ------------------------------------------------------------------------------------

/** What Enter offers on a link in the chain well. */
internal enum class LinkItem(val label: String) {
    RESOLVE("Resolve"),
    RESOLVE_ALL("Resolve the whole chain"),
    NEGATE("Negate"),
    TARGET("Target with it"),
    READ("Read it"),
}

/** The items for link [i] (0-based): only the newest link resolves; a negated link is not negated twice. */
internal fun linkItems(s: DuelState, i: Int): List<LinkItem> {
    val link = s.chain.getOrNull(i) ?: return emptyList()
    return buildList {
        if (i == s.chain.size - 1) {
            add(LinkItem.RESOLVE)
            if (s.chain.size > 1) add(LinkItem.RESOLVE_ALL)
        }
        if (!link.negated) add(LinkItem.NEGATE)
        if (link.uid != null && link.uid in s.cards) {
            add(LinkItem.TARGET)
            add(LinkItem.READ)
        }
    }
}

/** The key beside an item, where it has one. */
private fun linkKey(item: LinkItem): String? = when (item) {
    LinkItem.RESOLVE -> keyOf(DeskAction.DUEL_RESOLVE)
    LinkItem.RESOLVE_ALL -> keyOf(DeskAction.DUEL_RESOLVE_ALL)
    else -> null
}

/** Link [i]'s [item] done; the menu goes. */
internal fun runLinkItem(duels: Duels, s: DuelState, i: Int, item: LinkItem) {
    duels.chainMenu = null
    when (item) {
        LinkItem.RESOLVE -> duels.resolveChain()
        LinkItem.RESOLVE_ALL -> duels.resolveAll()
        LinkItem.NEGATE -> duels.negate(i + 1)
        LinkItem.TARGET -> {
            duels.linkTarget = s.chain.getOrNull(i)?.uid
            duels.problem = null
        }
        LinkItem.READ -> s.chain.getOrNull(i)?.uid?.let { duels.inspected = it }
    }
}

/** Enter's menu on a link in the chain well (1.0.89): ↑↓ choose, Enter does it, Esc closes. Beside the well. */
@Composable
internal fun ChainMenu(duels: Duels, s: DuelState, l: DuelLayout, viewers: Set<Int>) {
    val c = Mu.colors
    val i = duels.chainMenu ?: return
    val items = linkItems(s, i)
    val well = l[DuelSpot.Chain] ?: return
    if (items.isEmpty()) return
    val cursor = duels.chainCursor.coerceIn(0, items.size - 1)
    val link = s.chain[i]
    val name = link.uid?.let { u -> s.cards[u]?.let { if (seenBy(s, u, viewers)) duels.catalog.nameOf(it) else "a set card" } } ?: link.note.ifBlank { "an effect" }
    val density = LocalDensity.current
    androidx.compose.ui.layout.Layout(
        content = {
            Column(
                Modifier.width(220.dp).background(c.paper).border(1.dp, c.ink).padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Micro("Chain Link ${i + 1} · $name", color = c.ink, maxLines = 2)
                items.forEachIndexed { k, item ->
                    VerbChip(item.label, linkKey(item), strong = k == cursor, modifier = Modifier.fillMaxWidth()) { runLinkItem(duels, s, i, item) }
                }
                Mono("↑↓ choose · Enter · Esc", color = c.ink45, size = 9.sp)
            }
        },
        modifier = Modifier.zIndex(PICKS_Z),
    ) { measurables, constraints ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints())
        val px = density.density
        val right = well.right * px + 6f * px
        val x = if (right + p.width <= l.width * px) right else well.left * px - 6f * px - p.width
        val y = (well.top * px).coerceIn(0f, (l.height * px - p.height).coerceAtLeast(0f))
        layout(constraints.maxWidth, constraints.maxHeight) { p.place(x.roundToInt().coerceAtLeast(0), y.roundToInt()) }
    }
}

/** A link's card waiting for what it targets (1.0.89): a band over the near hand saying how, as an attack's does. */
@Composable
internal fun LinkTargetBand(duels: Duels, s: DuelState, l: DuelLayout, from: Int) {
    val c = Mu.colors
    val hand = l.pile(l.bottom, PileKind.HAND) ?: l.field
    val name = s.cards[from]?.let { duels.catalog.nameOf(it) } ?: return
    Row(
        Modifier.zIndex(DuelFrames.Z_STRIP + 3f).offset(l.field.left.dp, hand.top.dp).width(l.field.width.dp)
            .background(c.ink).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Small("Target with $name — click a card, or walk to it and Enter · Esc", Modifier.weight(1f), color = c.paper, maxLines = 2)
        Box(
            Modifier.border(1.dp, c.paper).cursorPointer(caption = "Stop targeting").muClickable { duels.linkTarget = null }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) { Micro("Cancel", color = c.paper) }
    }
}

/** Over the open pile, the arrows and the score column; under a carried card (100). */
private const val PICKS_Z = 86f
