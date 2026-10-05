package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.CardWords
import com.kaiharimoto.mastertool.core.ai.Resolved
import com.kaiharimoto.mastertool.core.ai.text.Block
import com.kaiharimoto.mastertool.core.ai.text.CardGroup
import com.kaiharimoto.mastertool.core.ai.text.ChatBoard
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/*
 * Ai's replies laid out as a player reads them (1.0.55, kai: "have Ai respond more visually using
 * card images and coherent and intuitive layouts"): a list as a decklist, a change as what goes
 * out and what comes in, a combo as a line of cards down the page, an end board as the field.
 * Every card is its art; the pointer on one shows it in the inspector, a click opens it large.
 * Parsed in core (`ChatMarkdown`: ```deck, ```compare, ```line, ```board, labelled ```cards).
 */

/** A card named in a reply, found in the pool by its passcode, its exact name or the nearest one. */
internal fun cardNamed(ai: AiState, name: String): Card? {
    val index = ai.h.builder.index
    name.trim().toIntOrNull()?.let { code -> index.byId(CardId(code))?.let { return it } }
    return index.byName(name) ?: (CardWords.resolve(name, index) as? Resolved.Found)?.card
}

/** How wide a card is drawn in a block [across] cards to the row, from the room there is. */
private fun cardWidth(room: Dp, across: Int, least: Dp, most: Dp): Dp =
    ((room - 4.dp * (across - 1)) / across).coerceIn(least, most)

/**
 * One card of a reply: its art, [dimmed] when it is going out, with a [mark] (− or +) in its
 * corner. The pointer on it shows it in the inspector; a click opens it large. A name the pool
 * does not know is a box with the name in it.
 */
@Composable
internal fun ChatCard(ai: AiState, name: String, width: Dp, dimmed: Boolean = false, mark: String? = null, set: Boolean = false) {
    val c = Mu.colors
    val card = remember(name, ai.h.builder.index.size) { cardNamed(ai, name) }
    val height = width / CARD_RATIO
    Box(Modifier.width(width).height(height)) {
        if (card == null) {
            Box(Modifier.width(width).height(height).border(1.dp, c.ink25).padding(4.dp)) {
                Micro(name, color = c.ink45, maxLines = 5)
            }
        } else {
            val neue = ai.h.neue
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            LaunchedEffect(hovered) {
                if (hovered) neue.hovered = card else if (neue.hovered == card) neue.hovered = null
            }
            NeueCard(
                card,
                Modifier
                    .width(width)
                    .alpha(if (dimmed || set) 0.5f else 1f)
                    .hoverable(source)
                    .cursorPointer(caption = "Open")
                    .muClickable(interactionSource = source) { neue.viewing = Viewing(card, null, 0) },
                format = ai.h.builder.format,
                foil = "off",
            )
        }
        if (set) {
            Box(Modifier.align(Alignment.Center).background(c.ink).padding(horizontal = 4.dp, vertical = 1.dp)) {
                Micro("Set", color = c.paper)
            }
        }
        mark?.let {
            Box(Modifier.align(Alignment.TopStart).background(c.ink).padding(horizontal = 4.dp)) {
                Mono(it, color = c.paper)
            }
        }
    }
}

/** A label over a group of cards, with how many there are. */
@Composable
private fun GroupHead(label: String, count: Int?, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Micro(label, color = c.ink70)
        count?.let { Mono(it.toString(), color = c.ink45) }
        Box(Modifier.weight(1f).height(1.dp).background(c.ink12))
    }
}

/** Every copy of a group's cards, as art, wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Copies(ai: AiState, group: CardGroup, width: Dp, dimmed: Boolean = false, mark: String? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        group.lines.forEach { line -> repeat(line.count) { ChatCard(ai, line.name, width, dimmed, mark) } }
    }
}

/** A ```cards block: every copy as its art, under the labels the reply gave them. */
@Composable
internal fun CardsBlock(ai: AiState, block: Block.Cards) {
    val phone = LocalPhone.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = cardWidth(maxWidth, if (phone) 5 else 6, 44.dp, 72.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            block.groups.forEach { g ->
                if (g.label.isNotEmpty()) GroupHead(g.label, g.count)
                Copies(ai, g, w)
            }
        }
    }
}

/** A ```deck block: the list as a decklist, section by section, every copy as its art. */
@Composable
internal fun DeckBlock(ai: AiState, block: Block.Deck) {
    val c = Mu.colors
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp)) {
        // Ten across, as a decklist is read: a 40-card Main Deck is four rows.
        val w = cardWidth(maxWidth, 10, 28.dp, 60.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Micro("Decklist", color = c.ink)
                Mono(block.sections.joinToString(" · ") { it.count.toString() }, color = c.ink45)
            }
            block.sections.forEach { g ->
                GroupHead(g.label, g.count)
                Copies(ai, g, w)
            }
        }
    }
}

/** A ```compare block: what goes out, dimmed and marked −, above what comes in, marked +. */
@Composable
internal fun CompareBlock(ai: AiState, block: Block.Compare) {
    val c = Mu.colors
    val phone = LocalPhone.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = cardWidth(maxWidth, if (phone) 5 else 7, 40.dp, 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(block.out, "−", true), Triple(block.into, "+", false)).forEach { (g, sign, out) ->
                if (g.lines.isEmpty()) return@forEach
                GroupHead(g.label.ifEmpty { if (out) "Out" else "In" }.let { "$it  $sign${g.count}" }, null)
                Copies(ai, g, w, dimmed = out, mark = sign)
            }
            val net = block.into.count - block.out.count
            if (net != 0) Small(if (net > 0) "The deck grows by $net." else "The deck shrinks by ${-net}.", color = c.ink45)
        }
    }
}

/**
 * A ```line block: a combo down the page, one step a row — its number, the card it turns on and
 * what happens — joined by a rule, so the order reads at a glance and the words have room.
 */
@Composable
internal fun LineBlock(ai: AiState, block: Block.Line) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val w = if (LocalPhone.current) 40.dp else 46.dp
    Column(Modifier.fillMaxWidth()) {
        block.steps.forEachIndexed { i, step ->
            val last = i == block.steps.lastIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // The thread from this step's number down to the next.
                        if (!last) drawLine(c.ink25, Offset(10.dp.toPx(), 22.dp.toPx()), Offset(10.dp.toPx(), size.height), 1.dp.toPx())
                    }
                    .padding(bottom = if (last) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(20.dp).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
                    Mono((i + 1).toString().padStart(2, '0'), color = c.ink)
                }
                step.card?.let { ChatCard(ai, it, w) }
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    step.card?.let { Small(it, color = c.ink45, maxLines = 1) }
                    MuText(styled(step.action), style = MuType.row(f), color = c.ink)
                }
            }
        }
    }
}

/**
 * A ```board block: the field as Master Rule lays it out — the two Extra Monster Zones over the
 * second and fourth columns, five Main Monster Zones, five Spell & Trap Zones with the Field
 * Zone beside them — and the hand, the GY and the banished as strips under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoardBlock(ai: AiState, block: Block.Board) {
    val c = Mu.colors
    val b = block.board
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp)) {
        // Six columns: the Field Zone, then the five zones of each row.
        val w = cardWidth(maxWidth, 6, 30.dp, 58.dp)
        val h = w / CARD_RATIO
        @Composable
        fun zone(slot: ChatBoard.Slot?) {
            if (slot == null) {
                Box(Modifier.width(w).height(h).border(1.dp, c.ink12))
            } else {
                ChatCard(ai, slot.name, w, set = slot.set)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (b.extraMonsters.any { it != null }) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.width(w))
                    (0 until 5).forEach { col ->
                        when (col) {
                            1 -> zone(b.extraMonsters[0])
                            3 -> zone(b.extraMonsters[1])
                            else -> Box(Modifier.width(w))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(w))
                b.monsters.forEach { zone(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (b.field != null) zone(b.field) else Box(Modifier.width(w).height(h).border(1.dp, c.ink06))
                b.spells.forEach { zone(it) }
            }
            listOf("Hand" to b.hand, "GY" to b.graveyard, "Banished" to b.banished).forEach { (label, pile) ->
                if (pile.isEmpty()) return@forEach
                GroupHead(label, pile.sumOf { it.count })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    pile.forEach { line -> repeat(line.count) { ChatCard(ai, line.name, w * 0.8f) } }
                }
            }
        }
    }
}
