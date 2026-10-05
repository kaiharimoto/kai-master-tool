package com.kaiharimoto.neue.duel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Given
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ShortcutAsking
import com.kaiharimoto.mastertool.core.duel.ShortcutStep
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.effects.DeclareKind
import com.kaiharimoto.mastertool.core.duel.effects.Decision
import com.kaiharimoto.mastertool.core.duel.effects.FxEngine
import com.kaiharimoto.mastertool.core.duel.effects.Purpose
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.PositionGlyphs
import com.kaiharimoto.mastertool.core.duel.text.ShortcutWindow
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.DuelLayout
import com.kaiharimoto.mastertool.core.layout.Slot
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.RequestFocusOnce
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlin.math.roundToInt

// ---- The Shortcut window (Phase D §5¾): the table's `Chooser`, drawn from the Decision alone ------------------------------
//
// Its arithmetic — the words, the groups, the chips, where it stands — is core's `ShortcutWindow`; its state is the
// `DuelShortcuts` part of `Duels`. This file only draws: the table marks (the dim holed for what is lit, rings, the picks'
// numbers, the zones' keys and glyphs) and the window itself (the frame every choice shares, and one of seven bodies).

/** Over the cards and the verb strip (85), the dim just under the window; below a carried card (100). */
private const val DIM_Z = 86f
private const val MARK_Z = 86.5f
private const val WINDOW_Z = 87f

/** What the question lights on the table, in table dp: its cards, zones and piles, and the card using the effect. */
private class Lit(
    val cards: List<Pair<Int, Slot>>,
    val zones: List<Pair<Place.Zone, Slot>>,
    val closed: List<Pair<String, Slot>>,
    val piles: List<Triple<Place.Pile, Slot, Int>>,
    val source: Slot?,
) {
    val all: List<Slot> get() = cards.map { it.second } + zones.map { it.second } + piles.map { it.second } + listOfNotNull(source)
}

private fun frameOf(frames: List<CardFrame>, uid: Int?): Slot? = uid?.let { u -> frames.firstOrNull { it.uid == u && it.shown }?.let(::seenBox) }

private fun litOf(s: DuelState, l: DuelLayout, frames: List<CardFrame>, step: ShortcutStep.Asking, source: Int?): Lit {
    val q = step.decision
    val cards = mutableListOf<Pair<Int, Slot>>()
    val zones = mutableListOf<Pair<Place.Zone, Slot>>()
    val closed = mutableListOf<Pair<String, Slot>>()
    val piles = mutableListOf<Triple<Place.Pile, Slot, Int>>()
    when (q) {
        is Decision.Cards -> {
            val byPile = HashMap<Place.Pile, Int>()
            q.among.forEach { u ->
                val f = frameOf(frames, u)
                val p = s.placeOf(u)
                if (f != null) cards += u to f
                else if (p is Place.Pile) byPile.merge(p.copy(at = null), 1, Int::plus)
            }
            // A pile holding candidates is lit with their count, open or shut: the source of a summon, a target's GY.
            q.among.mapNotNull { s.placeOf(it) as? Place.Pile }.filter { it.kind != PileKind.HAND }.map { it.copy(at = null) }.distinct()
                .forEach { p -> l.pile(p.seat, p.kind)?.let { piles += Triple(p, it, q.among.count { u -> (s.placeOf(u) as? Place.Pile)?.let { pp -> pp.seat == p.seat && pp.kind == p.kind } == true }) } }
        }
        is Decision.Zone -> {
            q.among.forEach { z -> l.zone(z)?.let { zones += z to it } }
            q.closed.forEach { (z, why) -> l.zone(z)?.let { closed += why to it } }
        }
        else -> {}
    }
    return Lit(cards, zones, closed, piles, frameOf(frames, source))
}

/** The card a question is for: the card using the effect, else the card being placed. */
private fun sourceOf(a: ShortcutAsking, q: Decision): Int? = when (q) {
    is Decision.Cards -> q.effect?.uid
    is Decision.Zone -> q.effect?.uid
    is Decision.Position -> q.effect?.uid
    is Decision.YesNo -> q.effect?.uid
    is Decision.Option -> q.effect?.uid
    is Decision.Declare -> q.effect?.uid
    is Decision.Order -> null
} ?: a.uid

/**
 * The Shortcut window and what it marks on the table, while a Shortcut asks (D.md §5¾). Drawn inside the table's box, in
 * its dp; the table's one arbiter takes every press on the table and hands it to the window's part.
 */
@Composable
internal fun ShortcutLayer(h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout, frames: List<CardFrame>, viewers: Set<Int>) {
    val part = duels.shortcutPart
    val step = part.question ?: return
    val a = part.asking ?: return
    val source = sourceOf(a, step.decision)
    val lit = remember(step, s, frames, l) { litOf(s, l, frames, step, source) }
    ShortcutMarks(duels, s, l, frames, step, a, lit)
    ShortcutPrompt(h, duels, s, l, frames, viewers, step, a, lit, source)
}

/** A card's name where the choosing seat may name it — one it sees, or its own Deck's as a search shows them — else where it is. */
private fun nameFor(duels: Duels, s: DuelState, uid: Int?, seat: Int): String {
    val c = uid?.let { s.cards[it] } ?: return "it"
    val p = s.placeOf(c.uid)
    val own = c.owner == seat && p is Place.Pile && (p.kind == PileKind.DECK || p.kind == PileKind.EXTRA)
    return if (DuelSight.sees(s, c.uid, seat) || own) duels.catalog.nameOf(c)
    else ShortcutWindow.coord(s, c.uid, seat, duels.shown?.header?.seed ?: 0L)?.let { "the card in $it" } ?: "a card"
}

// ---- the table's marks ----------------------------------------------------------------------------------------------

@Composable
private fun ShortcutMarks(duels: Duels, s: DuelState, l: DuelLayout, frames: List<CardFrame>, step: ShortcutStep.Asking, a: ShortcutAsking, lit: Lit) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val q = step.decision
    val picks = part.picks
    val phone = LocalPhone.current
    val placed = a.placed(step.asked)
    val targetsFrom = lit.source
    Canvas(Modifier.fillMaxSize().zIndex(DIM_Z)) {
        val d = density
        fun Slot.rect(grow: Float) = Rect((left - grow) * d, (top - grow) * d, (right + grow) * d, (bottom + grow) * d)
        val holes = Path().apply { lit.all.forEach { addRect(it.rect(4f)) } }
        clipPath(holes, clipOp = ClipOp.Difference) { drawRect(c.paper.copy(alpha = 0.82f)) }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        val dots = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
        // Candidates: ink outside, paper inside, so the ring reads on any art (the Spotlight's).
        lit.cards.forEach { (_, b) ->
            val outer = b.rect(4f)
            drawRect(c.ink, outer.topLeft, outer.size, style = Stroke(2.dp.toPx()))
            val inner = b.rect(2f)
            drawRect(c.paper, inner.topLeft, inner.size, style = Stroke(2.dp.toPx()))
        }
        lit.piles.forEach { (_, b, _) ->
            val r = b.rect(3f)
            drawRect(c.ink, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
        }
        lit.zones.forEach { (_, b) ->
            val r = b.rect(2f)
            drawRect(c.paper, r.topLeft, r.size)
            drawRect(c.ink, r.topLeft, r.size, style = Stroke(2.dp.toPx(), pathEffect = dash))
        }
        lit.closed.forEach { (_, b) ->
            val r = b.rect(0f)
            drawRect(c.ink25, r.topLeft, r.size, style = Stroke(1.dp.toPx(), pathEffect = dots))
        }
        // The card using the effect: ringed twice.
        lit.source?.let { b ->
            val r = b.rect(6f)
            drawRect(c.ink, r.topLeft, r.size, style = Stroke(1.dp.toPx()))
        }
        // A target's arrow from the card using the effect (§5¾.6): both players see what is aimed at.
        if (q is Decision.Cards && q.purpose == Purpose.TARGET && targetsFrom != null) {
            picks.mapNotNull { i -> lit.cards.firstOrNull { it.first == q.among.getOrNull(i) }?.second }.forEach { t ->
                drawLine(c.ink, Offset(targetsFrom.centerX * d, targetsFrom.centerY * d), Offset(t.centerX * d, t.centerY * d), 2.dp.toPx(), StrokeCap.Square)
            }
        }
        // The cards placed so far in this use, dashed in their zones until the last is placed (§5¾.5).
        placed.forEach { p ->
            val z = l.zone(p.zone) ?: return@forEach
            val r = z.rect(1f)
            drawRect(c.ink, r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = dash))
        }
    }
    // The picks' numbers, on the cards as they lie.
    if (q is Decision.Cards) {
        val badges = picks.mapIndexedNotNull { k, i -> lit.cards.firstOrNull { it.first == q.among.getOrNull(i) }?.let { Triple(k, it.first, it.second) } }
        badges.forEach { (k, uid, b) ->
            key(uid) {
                Box(Modifier.zIndex(MARK_Z).offset((b.left - 3).dp, (b.top - 3).dp).background(c.ink).border(1.dp, c.paper).padding(horizontal = 4.dp)) {
                    Mono("${k + 1}", color = c.paper, size = 11.sp)
                }
            }
        }
        // What a lit card on the table answers to the pointer: its caption, never a click of its own (the arbiter's).
        lit.cards.forEach { (uid, b) ->
            key("cap", uid) {
                val coord = ShortcutWindow.coord(s, uid, a.seat, duels.shown?.header?.seed ?: 0L) ?: ""
                val verb = if (q.purpose == Purpose.TARGET) "Target" else "Pick"
                Box(Modifier.zIndex(MARK_Z).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp).cursorPointer(caption = "$verb · $coord", emphasis = true))
            }
        }
        // A pile holding candidates: its count, and a press opens it as a row (§5¾.4, §5¾.6).
        lit.piles.forEach { (p, b, n) ->
            key("pile", p.seat, p.kind) {
                val word = when (p.kind) {
                    PileKind.DECK -> "in Deck"
                    PileKind.GY -> "in GY"
                    PileKind.BANISHED -> "banished"
                    PileKind.EXTRA -> "in Extra"
                    PileKind.HAND -> "in hand"
                }
                val opens = p.kind == PileKind.GY || p.kind == PileKind.BANISHED
                Box(
                    Modifier.zIndex(MARK_Z).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp)
                        .then(if (opens) Modifier.cursorPointer(caption = "Open it as a row") else Modifier),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // At the pile's head, clear of its own count at the foot.
                    Box(Modifier.padding(top = 3.dp).background(c.paper).border(1.dp, c.ink).padding(horizontal = 3.dp)) {
                        Micro("$n $word", color = c.ink, size = 8.sp)
                    }
                }
            }
        }
    }
    // The zones a card may go to: each with its key and the glyph of the position chosen; the focused one heavier.
    if (q is Decision.Zone) {
        val pos = part.position ?: q.positions.firstOrNull() ?: CardPosition.FACE_UP_ATK
        lit.zones.forEachIndexed { i, (z, b) ->
            key("zone", z.seat, z.kind, z.index) {
                val focused = i == part.cursor
                Box(
                    Modifier.zIndex(MARK_Z).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp)
                        .then(if (focused) Modifier.border(2.dp, c.ink) else Modifier)
                        .cursorPointer(caption = "Place · ${ShortcutWindow.zoneLabel(z, a.seat)} · ${PositionGlyphs.word(pos)}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        PositionGlyph(pos, if (phone) 20.dp else 30.dp)
                        Box(Modifier.background(if (focused) c.ink else c.paper).border(1.dp, c.ink).padding(horizontal = 5.dp)) {
                            Mono(ShortcutWindow.zoneKey(z), color = if (focused) c.paper else c.ink)
                        }
                    }
                }
            }
        }
        lit.closed.forEach { (why, b) ->
            key("closed", b.left, b.top) {
                Box(Modifier.zIndex(MARK_Z).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp).cursor(CursorMode.NO, reason = why))
            }
        }
    }
    // The cards placed so far in this use, named in their zones.
    placed.forEach { p ->
        val b = l.zone(p.zone)
        if (b != null) key("placed", p.zone.seat, p.zone.kind, p.zone.index) {
            Box(Modifier.zIndex(MARK_Z).offset(b.left.dp, b.top.dp).size(b.width.dp, b.height.dp).padding(3.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    p.position?.let { PositionGlyph(it, 16.dp) }
                    Micro(nameFor(duels, s, p.card, a.seat), color = c.ink, size = 8.sp, maxLines = 2)
                }
            }
        }
    }
}

// ---- the window ---------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortcutPrompt(
    h: NeueHolders, duels: Duels, s: DuelState, l: DuelLayout, frames: List<CardFrame>, viewers: Set<Int>,
    step: ShortcutStep.Asking, a: ShortcutAsking, lit: Lit, source: Int?,
) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val q = step.decision
    val body = ShortcutWindow.body(step)
    val phone = LocalPhone.current
    val px = LocalDensity.current.density
    val onTable = ShortcutWindow.onTable(q)
    val width = when {
        phone -> l.width
        body == ShortcutWindow.Body.WHICH -> minOf(340f, l.width - 8f)
        else -> l.field.width.coerceAtMost(l.width)
    }
    val sourceSlot = lit.source
    val inHand = source?.let { s.placeOf(it) }.let { it is Place.Pile && it.kind == PileKind.HAND }
    androidx.compose.ui.layout.Layout(
        content = {
            Column(
                Modifier.width(width.dp).background(c.paper)
                    .then(if (phone) Modifier.drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx()) } else Modifier.border(2.dp, c.ink))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Head(h, duels, s, step, a, source)
                when (body) {
                    ShortcutWindow.Body.WHICH -> WhichBody(duels, a)
                    ShortcutWindow.Body.OPTION -> OptionBody(duels, q as Decision.Option)
                    ShortcutWindow.Body.YES_NO -> YesNoBody(duels, q as Decision.YesNo)
                    ShortcutWindow.Body.PICK -> PickBody(h, duels, s, q as Decision.Cards, a, phone)
                    ShortcutWindow.Body.PLACE -> PlaceBody(h, duels, s, q as Decision.Zone, step, a, phone)
                    ShortcutWindow.Body.POSITION -> PositionBody(duels, q as Decision.Position, phone)
                    ShortcutWindow.Body.ORDER -> OrderBody(duels, q as Decision.Order)
                    ShortcutWindow.Body.DECLARE -> DeclareBody(h, duels, s, q as Decision.Declare)
                }
                Foot(duels, s, step, a, body, phone)
            }
        },
        modifier = Modifier.zIndex(WINDOW_Z),
    ) { measurables, constraints ->
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints(maxHeight = (l.height * px).roundToInt()))
        val hDp = p.height / px
        val slot = if (body == ShortcutWindow.Body.WHICH && sourceSlot != null && !phone) ShortcutWindow.beside(l, sourceSlot, width, hDp, inHand)
        else ShortcutWindow.place(l, lit.all, hDp, onTable, phone).slot
        // The window's own presses are its own: the table's arbiter leaves a press here to the chips.
        part.windowAt = Slot(slot.left, slot.top, p.width / px, hDp)
        layout(constraints.maxWidth, constraints.maxHeight) { p.place((slot.left * px).roundToInt(), (slot.top * px).roundToInt()) }
    }
}

/** The frame's head (§5¾.2): the card, who and which, the sentence; the step, its crumbs and the count. */
@Composable
private fun Head(h: NeueHolders, duels: Duels, s: DuelState, step: ShortcutStep.Asking, a: ShortcutAsking, source: Int?) {
    val c = Mu.colors
    val q = step.decision
    val part = duels.shortcutPart
    val inst = source?.let { s.cards[it] }
    val name = inst?.let { duels.catalog.nameOf(it) }
    val label = when (q) {
        is Decision.Cards -> q.effect?.label
        is Decision.Zone -> q.effect?.label
        is Decision.Position -> q.effect?.label
        is Decision.YesNo -> q.effect?.label
        is Decision.Option -> q.effect?.label
        is Decision.Declare -> q.effect?.label
        is Decision.Order -> null
    }
    val effect = (a.ask as? com.kaiharimoto.mastertool.core.duel.text.ShortcutAsk.Use)?.effect
    val verified = inst != null && label != null && duels.shortcuts()?.let { sc ->
        (effect ?: sc.book.script(inst.code)?.effects?.firstOrNull { it.label == label }?.id)?.let { sc.isVerified(inst.code, it) }
    } == true
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        if (source != null) Thumb(h, duels, s, source, 30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Micro(ShortcutWindow.who(name, if (step.which) null else label), Modifier.weight(1f, fill = false), color = c.ink70, size = 10.sp)
                if (!verified) Box(Modifier.dashedBorder(c.ink).padding(horizontal = 4.dp)) { Micro("Unverified", color = c.ink, size = 9.sp) }
            }
            MuText(ShortcutWindow.sentence(q, s, a.seat, duels.catalog), style = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = c.ink, maxLines = 3)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ShortcutWindow.step(q)?.let { Micro(it, color = c.ink70, size = 10.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ShortcutWindow.crumbs(step).forEach { cr ->
                    val on = cr.state == ShortcutWindow.Crumb.State.NOW
                    val done = cr.state == ShortcutWindow.Crumb.State.DONE
                    Box(
                        Modifier.background(if (on) c.ink else c.paper).border(1.dp, if (on) c.ink else if (done) c.ink70 else c.ink25)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) { Micro((if (done) "✓ " else "") + cr.word, color = if (on) c.paper else if (done) c.ink70 else c.ink45, size = 9.sp) }
                }
            }
            val count = when (q) {
                is Decision.Cards -> ShortcutWindow.count(q, part.picks.size)
                else -> ""
            }
            if (count.isNotEmpty()) Mono(count, color = c.ink, size = 18.sp)
        }
    }
}

/** The frame's foot (§5¾.2): Esc's words, Enter's chip — the answer said exactly — and this body's keys. */
@Composable
private fun Foot(duels: Duels, s: DuelState, step: ShortcutStep.Asking, a: ShortcutAsking, body: ShortcutWindow.Body, phone: Boolean) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val q = step.decision
    val back = ShortcutWindow.back(a, ShortcutWindow.lastWords(a, step.asked, s, a.seat, duels.catalog))
    val (enter, waits) = when (q) {
        is Decision.Cards -> ShortcutWindow.enter(q, part.picks.map { nameFor(duels, s, q.among.getOrNull(it), a.seat) }) to ShortcutWindow.waiting(q, part.picks.size)
        is Decision.Zone -> {
            val z = q.among.getOrNull(part.cursor)
            ShortcutWindow.enter(q, listOf(nameFor(duels, s, q.card, a.seat)), z?.let { ShortcutWindow.zoneLabel(it, a.seat) }, part.position) to null
        }
        is Decision.Position -> ShortcutWindow.enter(q, emptyList(), position = part.position) to null
        is Decision.Option -> ShortcutWindow.enter(q, listOfNotNull(q.among.getOrNull(part.cursor))) to null
        is Decision.YesNo -> "Yes" to null
        is Decision.Order -> ShortcutWindow.enter(q, emptyList()) to null
        is Decision.Declare -> "Declare" to "Choose a row"
    }
    part.hint?.let { Small(it, color = c.ink) }
    // An answer typed (/, or any letter that is not a key here): a coordinate, a label, yes or no — the line's own words.
    val typed = part.typed
    if (typed != null && body != ShortcutWindow.Body.DECLARE) {
        val focus = remember { FocusRequester() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono("Type", color = c.ink45, size = 10.sp)
            MuInput(typed, { part.typed = it }, Modifier.weight(1f), placeholder = "gy1, om2, m3, yes, 2", mono = true, dense = true, focusRequester = focus, onSubmit = { part.typedAnswer(part.typed ?: "") })
        }
        RequestFocusOnce(focus, step)
    }
    val buttonH = if (phone) 44.dp else 28.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.height(buttonH).border(1.dp, c.ink).cursorPointer(caption = back, showsWords = true).muClickable { part.back() }.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            // A phone keeps Back short, so the inverted Enter fills the rest of the row (§5¾.11); its words are the cursor's.
            Micro(if (phone) back.substringBefore(":") else back, color = c.ink, maxLines = 1)
        }
        if (body != ShortcutWindow.Body.DECLARE && body != ShortcutWindow.Body.YES_NO) {
            val ready = waits == null
            Box(
                Modifier.then(if (phone) Modifier.weight(1f) else Modifier).height(buttonH)
                    .background(if (ready) c.ink else c.paper).then(if (ready) Modifier else Modifier.dashedBorder(c.ink45))
                    .cursorPointer(caption = enter, showsWords = true, enabled = ready, reason = waits)
                    .muClickable(enabled = ready) { part.confirm() }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Micro(enter, color = if (ready) c.paper else c.ink45, maxLines = 1)
                    if (!phone) Mono("Enter", color = if (ready) c.paper else c.ink45, size = 10.sp)
                }
            }
        }
        if (!phone) {
            Spacer(Modifier.weight(1f))
            Mono(ShortcutWindow.keys(body), color = c.ink45, size = 10.sp)
        }
    }
}

/** A card's face, small: its art where the pool has it, its name in a paper box where it does not, its back unseen. */
@Composable
private fun Thumb(h: NeueHolders, duels: Duels, s: DuelState, uid: Int, width: Dp, seat: Int = duels.bottom, modifier: Modifier = Modifier) {
    val inst = s.cards[uid] ?: return
    val p = s.placeOf(uid)
    val own = inst.owner == seat && p is Place.Pile && (p.kind == PileKind.DECK || p.kind == PileKind.EXTRA)
    val sees = DuelSight.sees(s, uid, seat) || own || (duels.eyes.viewers.isNotEmpty() && duels.eyes.viewers.any { DuelSight.sees(s, uid, it) })
    val card = if (sees && !(inst.token && inst.code == 0)) h.builder.index.byId(CardId(inst.code)) else null
    Box(modifier.size(width, width / CARD_RATIO)) {
        when {
            card != null -> NeueCard(card, Modifier.fillMaxSize(), foil = "off")
            sees -> TokenFace(duels.catalog.nameOf(inst), Modifier.fillMaxSize(), token = inst.token)
            else -> CardBack(Modifier.fillMaxSize())
        }
    }
}

/** A dashed ink outline, 1 dp: what is not offered, or not verified. */
private fun Modifier.dashedBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(color, Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

// ---- the bodies ---------------------------------------------------------------------------------------------------

/** Which Shortcut (§5¾.3): every one of the card's, numbered; those the engine refuses now greyed with their rule. */
@Composable
private fun WhichBody(duels: Duels, a: ShortcutAsking) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val uid = a.uid ?: return
    val opts = part.options(uid)
    val legal = opts.filter { it.legal }
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
        opts.forEach { o ->
            val k = legal.indexOf(o)
            val on = o.legal && k == part.cursor
            Row(
                Modifier.fillMaxWidth().background(if (on) c.ink else c.paper)
                    .then(if (o.legal) Modifier.cursorPointer(caption = "Use ${o.label}").muClickable { part.cursor = k; part.answer(listOf(k)) }
                    else Modifier.cursor(CursorMode.NO, reason = o.why ?: "Not now"))
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val ink = if (on) c.paper else if (o.legal) c.ink else c.ink45
                Box(Modifier.size(20.dp).then(if (o.legal) Modifier.border(1.dp, if (on) c.paper else c.ink) else Modifier.dashedBorder(c.ink25)), contentAlignment = Alignment.Center) {
                    Mono(if (o.legal) "${k + 1}" else "–", color = ink)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MuText(o.label, style = MuType.row(LocalMuFonts.current).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = ink, maxLines = 1)
                        if (!o.verified) Micro("Unverified", color = ink, size = 8.sp)
                    }
                    if (o.needs.isNotBlank()) Mono(o.needs, color = if (on) c.paper else c.ink70, size = 10.sp)
                    if (!o.legal) Small("${o.why ?: "Not now"} · by hand: Activate", color = c.ink45, maxLines = 2)
                }
            }
        }
    }
}

/** An option of the effect's own (§5¾.3): rows, numbered. */
@Composable
private fun OptionBody(duels: Duels, q: Decision.Option) {
    val c = Mu.colors
    val part = duels.shortcutPart
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
        q.among.forEachIndexed { i, o ->
            val on = i == part.cursor
            Row(
                Modifier.fillMaxWidth().background(if (on) c.ink else c.paper).cursorPointer(caption = o).muClickable { part.cursor = i; part.answer(listOf(i)) }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp).border(1.dp, if (on) c.paper else c.ink), contentAlignment = Alignment.Center) { Mono("${i + 1}", color = if (on) c.paper else c.ink) }
                MuText(o, style = MuType.row(LocalMuFonts.current), color = if (on) c.paper else c.ink, maxLines = 2)
            }
        }
    }
}

/** Yes or no (§5¾.3); a waiting trigger's is Use or Skip (§5¾.7). */
@Composable
private fun YesNoBody(duels: Duels, q: Decision.YesNo) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val trigger = q.by != null
    val phone = LocalPhone.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1 to (if (trigger) "Use" else "Yes"), 0 to (if (trigger) "Skip" else "No")).forEach { (v, word) ->
            val strong = v == 1
            Box(
                Modifier.weight(1f).height(if (phone) 44.dp else 34.dp).background(if (strong) c.ink else c.paper).border(1.dp, c.ink)
                    .cursorPointer(caption = word, showsWords = true).muClickable { part.answer(listOf(v)) },
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Micro(word, color = if (strong) c.paper else c.ink)
                    Mono(if (strong) "Y" else "N", color = if (strong) c.paper else c.ink45, size = 10.sp)
                }
            }
        }
    }
}

/** One candidate cell of the picking strip: the card, its pick number, its name and coordinate (§5¾.4). */
@Composable
private fun Cell(h: NeueHolders, duels: Duels, s: DuelState, q: Decision.Cards, indices: List<Int>, seat: Int, phone: Boolean) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val first = indices.first()
    val uid = q.among[first]
    val picked = indices.filter { it in part.picks }
    val n = picked.firstOrNull()?.let { part.picks.indexOf(it) + 1 }
    val still = FxEngine.stillLegal(q, part.picks)
    val offered = picked.isNotEmpty() || indices.any { it in still }
    val focus = part.cursor in indices
    val coord = ShortcutWindow.coord(s, uid, seat, duels.shown?.header?.seed ?: 0L) ?: ""
    val w = if (phone) 40.dp else 46.dp
    Column(
        Modifier.width(w + 14.dp)
            .cursorPointer(caption = "${if (q.purpose == Purpose.TARGET) "Target" else "Pick"} · $coord", emphasis = true)
            .muClickable {
                // A hidden pile's copies are one cell: a click takes the next copy, or lets the last go.
                val next = indices.firstOrNull { it !in part.picks }
                if (next != null && (offered || q.max == 1)) part.togglePick(next) else picked.lastOrNull()?.let { part.togglePick(it) }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.padding(3.dp).then(if (n != null) Modifier.border(2.dp, c.ink) else if (focus) Modifier.dashedBorder(c.ink, 2.dp) else Modifier)
                .graphicsLayer { alpha = if (offered) 1f else 0.3f },
        ) {
            Thumb(h, duels, s, uid, w, seat, Modifier.padding(if (n != null || focus) 3.dp else 0.dp))
            if (n != null) Box(Modifier.align(Alignment.TopStart).background(c.ink).border(1.dp, c.paper).padding(horizontal = 4.dp)) { Mono("$n", color = c.paper, size = 11.sp) }
            if (indices.size > 1) Box(Modifier.align(Alignment.BottomEnd).background(c.paper).border(1.dp, c.ink).padding(horizontal = 3.dp)) { Mono("×${indices.size}", color = c.ink, size = 10.sp) }
        }
        // On a phone the card's face names it; the cell keeps its coordinate.
        if (!phone) Micro(nameFor(duels, s, uid, seat), color = if (offered) c.ink else c.ink45, size = 8.sp, maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
        Mono(coord, color = c.ink45, size = 9.sp)
    }
}

/** The picking strip (option B, §5¾.4): every candidate in one row, grouped by place in the effect's order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickBody(h: NeueHolders, duels: Duels, s: DuelState, q: Decision.Cards, a: ShortcutAsking, phone: Boolean) {
    val c = Mu.colors
    val seat = q.by ?: a.seat
    val groups = remember(q, s) { ShortcutWindow.groups(q, s, seat) }
    // A search through a hidden pile is the chooser's to see: across a table, the other player sees only that it happens.
    val hidden = q.hidden && duels.eyes.viewers.isNotEmpty() && seat !in duels.eyes.viewers
    if (hidden) {
        Small("${duels.shown?.header?.seats?.getOrNull(seat)?.name ?: "They"} ${if (q.purpose == Purpose.TARGET) "are choosing" else "are searching"} · ${q.among.size} to choose from", color = c.ink70)
        return
    }
    val empty = groups.filter { it.indices.isEmpty() }
    if (phone && empty.isNotEmpty()) Small(empty.joinToString(" · ") { "${it.head} · ${it.none}" }, color = c.ink45, maxLines = 2)
    val content: @Composable () -> Unit = {
        groups.filter { it.indices.isNotEmpty() || !phone }.forEachIndexed { gi, gr ->
            key(gr.head) {
                Column(
                    Modifier.padding(start = if (gi == 0) 0.dp else 10.dp).then(if (gi == 0) Modifier else Modifier.drawBehind { drawLine(c.ink12, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) })
                        .padding(start = if (gi == 0) 0.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        Modifier.then(if (gr.indices.isEmpty()) Modifier.dashedBorder(c.ink25) else Modifier.drawBehind { drawLine(c.ink, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) })
                            .padding(bottom = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Micro(gr.head, color = if (gr.indices.isEmpty()) c.ink45 else c.ink, size = 10.sp)
                        gr.coord?.let { Mono(it, color = c.ink45, size = 10.sp) }
                        Mono("${gr.indices.size}", color = c.ink45, size = 10.sp)
                    }
                    if (gr.indices.isEmpty()) Small(gr.none ?: "none", color = c.ink45, maxLines = 2)
                    else Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Identical copies in a hidden pile are one card, ×2 (§5¾.4); elsewhere each copy stands, so the copy is chosen.
                        val hiddenPile = (gr.place as? Place.Pile)?.kind.let { it == PileKind.DECK || it == PileKind.EXTRA }
                        val cells = if (hiddenPile) gr.indices.groupBy { s.cards[q.among[it]]?.code }.values.toList() else gr.indices.map { listOf(it) }
                        cells.forEach { idx -> key(idx.first()) { Cell(h, duels, s, q, idx, seat, phone) } }
                    }
                }
            }
        }
    }
    if (phone) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { content() }
    else FlowRow(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    // The picks as chips, each with ✕ to let it go (a target's are numbered, §5¾.6).
    val part = duels.shortcutPart
    if (part.picks.isNotEmpty() && q.purpose == Purpose.TARGET) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            part.picks.forEachIndexed { k, i ->
                val uid = q.among.getOrNull(i)
                Row(
                    Modifier.border(1.dp, c.ink25).cursorPointer(caption = "Let it go").muClickable { part.togglePick(i) }.padding(horizontal = 6.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mono("${k + 1}", color = c.ink)
                    Micro(nameFor(duels, s, uid, seat), color = c.ink, size = 9.sp)
                    Mono(uid?.let { ShortcutWindow.coord(s, it, seat) } ?: "", color = c.ink45, size = 9.sp)
                    Mono("×", color = c.ink)
                }
            }
        }
    }
}

/** The position chips (§5¾.5, §5¾.9): a glyph and its word each, the chosen inverted, one the rules forbid dashed with why. */
@Composable
private fun PositionChips(duels: Duels, allowed: List<CardPosition>, link: Boolean, big: Boolean) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val chips = ShortcutWindow.chips(allowed, link)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            val on = chip.allowed && chip.position == part.position
            Row(
                Modifier.then(if (big) Modifier.weight(1f).height(44.dp) else Modifier)
                    .background(if (on) c.ink else c.paper)
                    .then(if (chip.allowed) Modifier.border(1.dp, if (on) c.ink else c.ink25) else Modifier.dashedBorder(c.ink45))
                    .cursorPointer(caption = chip.word, showsWords = true, enabled = chip.allowed, reason = chip.why)
                    .muClickable(enabled = chip.allowed) { part.choosePosition(chip.position) }
                    .padding(start = 6.dp, end = 9.dp, top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp, if (big) Alignment.CenterHorizontally else Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (on) c.paper else if (chip.allowed) c.ink else c.ink45
                PositionGlyph(chip.position, if (big) 24.dp else 20.dp, ink)
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Micro(chip.word, color = ink, size = 10.sp)
                        Mono(chip.key, color = if (on) c.paper else c.ink45, size = 10.sp)
                    }
                    chip.why?.let { Small(it, color = c.ink70, maxLines = 1) }
                }
            }
        }
    }
}

/**
 * The place step (§5¾.5): the queue of cards to place — placed with its zone, placing now inverted, next — and the
 * position chips. The zones themselves are lit on the table, each with its key.
 */
@Composable
private fun PlaceBody(h: NeueHolders, duels: Duels, s: DuelState, q: Decision.Zone, step: ShortcutStep.Asking, a: ShortcutAsking, phone: Boolean) {
    val c = Mu.colors
    // The cards picked for this summon, in the order they will be placed.
    val pickedIdx = step.asked.indexOfLast { it is Decision.Cards && it.purpose == Purpose.SUMMON }
    val queue = (step.asked.getOrNull(pickedIdx) as? Decision.Cards)?.let { d ->
        (a.given.getOrNull(pickedIdx) as? Given.Pick)?.answer?.mapNotNull { d.among.getOrNull(it) }
    }.orEmpty().ifEmpty { listOfNotNull(q.card) }
    val placed = a.placed(step.asked)
    if (queue.size > 1 || placed.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            queue.forEachIndexed { i, uid ->
                val done = placed.firstOrNull { it.card == uid }
                val now = uid == q.card && done == null
                Row(
                    Modifier.border(if (now) 2.dp else 1.dp, if (now) c.ink else c.ink25).padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(h, duels, s, uid, 22.dp, a.seat)
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Micro(nameFor(duels, s, uid, a.seat), color = c.ink, size = 9.sp)
                        when {
                            done != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Micro("✓ Placed · ${ShortcutWindow.zoneLabel(done.zone, a.seat)}", color = c.ink70, size = 8.sp)
                                done.position?.let { PositionGlyph(it, 12.dp, c.ink70) }
                            }
                            now -> Box(Modifier.background(c.ink).padding(horizontal = 5.dp)) { Micro("Placing now", color = c.paper, size = 8.sp) }
                            else -> Micro("Next", color = c.ink45, size = 8.sp)
                        }
                    }
                }
                if (i < queue.size - 1) Mono("→", color = c.ink45)
            }
        }
    }
    val link = q.card?.let { s.cards[it] }?.let { duels.catalog.info(it.code)?.link } == true
    PositionChips(duels, q.positions, link, big = phone)
    Small(if (phone) "Tap a lit zone" else "Click a lit zone, or press its number: ${q.among.joinToString(" · ") { ShortcutWindow.zoneKey(it) }}", color = c.ink70, maxLines = 2)
}

/** A position asked alone (§5¾.3): the three chips, large. */
@Composable
private fun PositionBody(duels: Duels, q: Decision.Position, phone: Boolean) {
    val s = duels.shown?.state
    val link = s?.cards?.get(q.card)?.let { duels.catalog.info(it.code)?.link } == true
    PositionChips(duels, q.among, link, big = true)
}

/** Several Shortcuts waiting (§5¾.7): their order on the chain, L1 first; ↑↓ choose, Alt ↑↓ or the arrows move. */
@Composable
private fun OrderBody(duels: Duels, q: Decision.Order) {
    val c = Mu.colors
    val part = duels.shortcutPart
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink12)) {
        part.order.forEachIndexed { k, i ->
            val on = k == part.cursor
            Row(
                Modifier.fillMaxWidth().then(if (on) Modifier.border(2.dp, c.ink) else Modifier).cursorPointer(caption = "Choose it").muClickable { part.cursor = k }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(34.dp).border(1.dp, c.ink), contentAlignment = Alignment.Center) { Mono("L${(duels.shown?.state?.chain?.size ?: 0) + k + 1}", color = c.ink) }
                MuText(q.labels.getOrNull(i) ?: "Trigger ${i + 1}", Modifier.weight(1f), style = MuType.row(LocalMuFonts.current), color = c.ink, maxLines = 1)
                Box(Modifier.border(1.dp, c.ink25).cursorPointer(caption = "Earlier").muClickable { part.cursor = k; part.moveOrder(-1) }.padding(horizontal = 6.dp)) { Mono("↑", color = c.ink) }
                Box(Modifier.border(1.dp, c.ink25).cursorPointer(caption = "Later").muClickable { part.cursor = k; part.moveOrder(1) }.padding(horizontal = 6.dp)) { Mono("↓", color = c.ink) }
            }
        }
    }
    Small("They go on the chain in this order: the last resolves first.", color = c.ink70)
}

/** One row of a declaration's results: a name on the table (by its index) or any card of the pool (by its passcode). */
private data class DeclareRow(val name: String, val kind: String, val index: Int? = null, val code: Int? = null)

/** A declaration (§5¾.8): a search for a name — any card that exists — or short lists for a Type, an Attribute, a Level. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeclareBody(h: NeueHolders, duels: Duels, s: DuelState, q: Decision.Declare) {
    val c = Mu.colors
    val part = duels.shortcutPart
    val query = part.typed ?: ""
    val focus = remember { FocusRequester() }
    if (q.kind == DeclareKind.NAME) {
        val rows = remember(q, query) {
            // The names the seat has seen first, then any card of the pool: a few of each, so both are in reach.
            val onTable = q.among.mapIndexed { i, n -> DeclareRow(n, "Seen", index = i) }.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            val pool = if (!q.open || query.isBlank()) emptyList() else h.builder.index.search(query, limit = 12).cards
                .filter { card -> onTable.none { it.name.equals(card.name, ignoreCase = true) } }
                .map { card -> DeclareRow(card.name, listOfNotNull(card.frameType.substringBefore('_').replaceFirstChar { it.uppercase() }, card.level?.let { "Level $it" }).joinToString(" · "), code = card.id.value) }
            if (pool.isEmpty()) onTable.take(9) else onTable.take(4) + pool.take(9 - minOf(4, onTable.size))
        }
        fun choose(r: DeclareRow) { if (r.code != null) part.named(r.code) else r.index?.let { part.answer(listOf(it)) } }
        MuInput(
            query, { part.typed = it; part.cursor = 0 },
            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { part.cursor = (part.cursor + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)); true }
                    Key.DirectionUp -> { part.cursor = (part.cursor - 1).coerceAtLeast(0); true }
                    else -> false
                }
            },
            placeholder = "Any card's name", mono = true, focusRequester = focus,
            onSubmit = { rows.getOrNull(part.cursor)?.let(::choose) },
        )
        RequestFocusOnce(focus, q)
        Mono(if (q.open) "Any card that exists may be named · ${q.among.size} on the table" else "${rows.size} of ${q.among.size} you can name", color = c.ink45, size = 10.sp)
        Column(Modifier.fillMaxWidth()) {
            rows.forEachIndexed { i, r ->
                val on = i == part.cursor
                Row(
                    Modifier.fillMaxWidth().background(if (on) c.ink else c.paper).cursorPointer(caption = "Declare ${r.name}").muClickable { choose(r) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mono("${i + 1}", color = if (on) c.paper else c.ink45)
                    MuText(r.name, Modifier.weight(1f), style = MuType.row(LocalMuFonts.current), color = if (on) c.paper else c.ink, maxLines = 1)
                    Mono(r.kind, color = if (on) c.paper else c.ink45, size = 10.sp)
                }
            }
        }
    } else {
        val shown = q.among.withIndex().filter { query.isBlank() || it.value.contains(query, ignoreCase = true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { (i, w) ->
                val on = i == part.cursor
                Box(
                    Modifier.background(if (on) c.ink else c.paper).border(1.dp, if (on) c.ink else c.ink25).cursorPointer(caption = "Declare $w").muClickable { part.answer(listOf(i)) }
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                ) {
                    if (q.kind == DeclareKind.LEVEL) Mono(w.padStart(2, '0'), color = if (on) c.paper else c.ink) else Micro(w, color = if (on) c.paper else c.ink, size = 10.sp)
                }
            }
        }
    }
}

// ---- Shift Q's strip (§5½ 3) --------------------------------------------------------------------------------------

/** Shift Q while a written link stands: By hand (Enter), as before, or By Shortcut (U), each written link as written. */
@Composable
internal fun ResolveStrip(duels: Duels, s: DuelState, l: DuelLayout) {
    val c = Mu.colors
    if (!duels.shortcutPart.resolveStrip || s.chain.isEmpty()) return
    val phone = LocalPhone.current
    val u = DeskShortcuts.all.firstOrNull { it.action == DeskAction.DUEL_SHORTCUT }?.chord?.let(DeskShortcuts::kbd) ?: "U"
    val hand = l.pile(l.bottom, PileKind.HAND) ?: l.field
    Row(
        Modifier.zIndex(WINDOW_Z).offset(l.field.left.dp, (hand.top - (if (phone) 64 else 48)).coerceAtLeast(0f).dp).width(l.field.width.dp)
            .background(c.paper).border(2.dp, c.ink).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Micro("Resolve the whole chain (${s.chain.size})", Modifier.weight(1f), color = c.ink)
        VerbChip("By hand", "Enter", strong = true) { duels.byHandAll() }
        VerbChip("By Shortcut", u) { duels.resolveByShortcut(all = true) }
        VerbChip("Cancel", "Esc") { duels.shortcutPart.resolveStrip = false }
    }
}
