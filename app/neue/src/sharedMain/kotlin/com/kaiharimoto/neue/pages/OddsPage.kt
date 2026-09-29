package com.kaiharimoto.neue.pages

import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.deck.DeckGrouping
import com.kaiharimoto.mastertool.core.deck.Lens
import com.kaiharimoto.mastertool.core.hand.Ask
import com.kaiharimoto.mastertool.core.hand.HandGoal
import com.kaiharimoto.mastertool.core.hand.LensOdds
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.SectionTitle
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.percent
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * `03 Odds`: the questions this deck is asked, and the answers, exact.
 *
 * Three sections. The goals kai wrote — "a starter and no bricks" — stored
 * with the deck. The rate at which each key of the current lens opens. And
 * every card in the main deck with its own chance of being in the opening hand.
 * Ratios are drawn as 3px ink bars (§12), never as a coloured chart.
 */
@Composable
fun OddsPage(state: DeckBuilderState) {
    val c = Mu.colors
    val size = state.deck.main.size
    Column(Modifier.fillMaxSize()) {
        PageHeader(3, "Odds", "Main deck · $size cards · exact, not simulated") {
            MuButton("New goal", state::newGoal, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Plus, enabled = size > 0, reason = "Main deck is empty")
        }
        if (size == 0) {
            EmptyState("Empty deck.", "Odds are about a main deck. Add cards on the builder and they appear here.")
            return@Column
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(
                // A phone keeps 16 at its edges (v1.3.5).
                Modifier.fillMaxSize().verticalScroll(scroll).padding(if (com.kaiharimoto.neue.kit.LocalPhone.current) 16.dp else 32.dp).widthIn(max = 1280.dp),
                verticalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Goals(state)
                Keys(state)
                Cards(state)
            }
            ScrollbarFor(scroll)
        }
    }
    state.editingGoal?.let { GoalDialog(state, it) }
}

@Composable
private fun Goals(state: DeckBuilderState) {
    val c = Mu.colors
    Column {
        SectionTitle(1, "Goals")
        if (state.goals.goals.isEmpty()) {
            Small(
                if (state.groups.groups.isEmpty()) {
                    "A goal is a question about the opening hand, written in terms of your groups. Make groups on the builder first (N), then ask."
                } else {
                    "A goal is a question about the opening hand: at least one starter, at most one brick. New goal asks one."
                },
                Modifier.padding(top = 4.dp).widthIn(max = 512.dp),
            )
        }
        state.goals.goals.forEachIndexed { i, goal ->
            val p = state.oddsOf(goal)
            val source = remember(goal.id) { MutableInteractionSource() }
            val hovered by source.collectIsHotAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(animatedColor(if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(caption = "Open")
                    .clickable(interactionSource = source, indication = null) { state.openGoal(goal.id) }
                    .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val phone = com.kaiharimoto.neue.kit.LocalPhone.current
                if (!phone) Numeral(i + 1)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RowText(goal.name.ifBlank { "Untitled goal" })
                    Mono(describe(goal, state) + " · in ${goal.handSize} cards")
                    // On a phone the bar goes under the words, the width of the row (v1.3.5).
                    if (phone) Ratio(p, Modifier.fillMaxWidth().padding(top = 4.dp))
                }
                if (!phone) Ratio(p, Modifier.width(240.dp))
                MuText(percent(p), style = MuType.h2(LocalMuFonts.current), modifier = Modifier.width(if (phone) 72.dp else 96.dp), align = TextAlign.End)
            }
        }
    }
}

private fun describe(goal: HandGoal, state: DeckBuilderState): String =
    goal.asks.filterValues { it.constrains }.entries.joinToString(" · ") { (id, ask) ->
        val name = if (id == HandGoal.UNGROUPED) "ungrouped" else state.groups.byId(id)?.name ?: "?"
        "${ask.label} ${name.lowercase()}"
    }.ifBlank { "No asks yet" }

@Composable
private fun Keys(state: DeckBuilderState) {
    val lens = if (state.lens == Lens.DECK) (if (state.groups.groups.isNotEmpty()) Lens.ROLES else Lens.TYPE) else state.lens
    val keying = remember(state.deck, state.groups, lens, state.format) {
        com.kaiharimoto.mastertool.core.deck.DeckLenses.key(lens, state.deck.main, state.index::byId, state.groups, state.format)
    }
    val five = LensOdds.atLeastOne(keying, state.deck.main.size, 5)
    val six = LensOdds.atLeastOne(keying, state.deck.main.size, 6)
    val c = Mu.colors
    Column {
        val phone = com.kaiharimoto.neue.kit.LocalPhone.current
        if (phone) {
            // No room beside the title for five tabs on a phone: they go under it, and scroll.
            SectionTitle(2, "At least one, by ${lens.displayName.lowercase()}")
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                Segmented(lens, Lens.entries.filter { it != Lens.DECK }, { it.displayName }, state::useLens, small = true)
            }
        } else SectionTitle(2, "At least one, by ${lens.displayName.lowercase()}") {
            Segmented(lens, Lens.entries.filter { it != Lens.DECK }, { it.displayName }, state::useLens, small = true)
        }
        TableHead("Key", "Cards", "Going first · 5", "Going second · 6")
        keying.keys.forEach { key ->
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(10.dp).background(GroupMarkers.paint(key.paint, c.ink)).border(1.dp, c.ink))
                RowText(key.label, Modifier.weight(1f))
                Mono(keying.countOf(key.id).toString(), Modifier.width(countWidth()), color = c.ink, align = TextAlign.End)
                RateCell(five[key.id] ?: 0.0)
                RateCell(six[key.id] ?: 0.0)
            }
        }
        if (keying.keys.isEmpty()) Small("Nothing in this lens.", Modifier.padding(top = 8.dp), color = Mu.colors.ink45)
    }
}

@Composable
private fun Cards(state: DeckBuilderState) {
    val c = Mu.colors
    val stats = state.mainStatistics
    val stacks = remember(state.deck.main) { DeckGrouping.stacks(state.deck.main).sortedByDescending { it.count } }
    Column {
        SectionTitle(3, "Every card")
        TableHead("Card", "Copies", "Going first · 5", "Going second · 6")
        stacks.forEach { stack ->
            val card = state.index.byId(stack.id)
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.size(10.dp))
                RowText(card?.name ?: stack.id.value.toString(), Modifier.weight(1f))
                Mono("×${stack.count}", Modifier.width(countWidth()), color = c.ink, align = TextAlign.End)
                RateCell(stats.openingHandOdds(stack.count, 5))
                RateCell(stats.openingHandOdds(stack.count, 6))
            }
        }
    }
}

@Composable
private fun TableHead(a: String, b: String, d: String, e: String) {
    val c = Mu.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(10.dp))
        Micro(a, Modifier.weight(1f), color = c.ink45)
        val phone = com.kaiharimoto.neue.kit.LocalPhone.current
        Micro(if (phone) "#" else b, Modifier.width(countWidth()), color = c.ink45)
        Micro(if (phone) "First" else d, Modifier.width(rateWidth()), color = c.ink45)
        Micro(if (phone) "Second" else e, Modifier.width(rateWidth()), color = c.ink45)
    }
}

@Composable
private fun RateCell(p: Double) {
    val phone = com.kaiharimoto.neue.kit.LocalPhone.current
    Row(Modifier.width(rateWidth()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (phone) 6.dp else 12.dp)) {
        Ratio(p, Modifier.weight(1f))
        Mono(percent(p), Modifier.width(if (phone) 44.dp else 56.dp), color = Mu.colors.ink, size = 12.sp, align = TextAlign.End)
    }
}

/** A table's rate column: 260 on the desk, and on a phone what two fit beside a name (v1.3.5). */
@Composable
private fun rateWidth() = if (com.kaiharimoto.neue.kit.LocalPhone.current) 84.dp else 260.dp

@Composable
private fun countWidth() = if (com.kaiharimoto.neue.kit.LocalPhone.current) 28.dp else 64.dp

/** A ratio (§12): a 3px ink-12 track with an ink fill. */
@Composable
private fun Ratio(p: Double, modifier: Modifier = Modifier) {
    val c = Mu.colors
    Box(modifier.height(3.dp).background(c.ink12)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).background(c.ink))
    }
}

/** Editing a goal: its name, its hand size, and one ask per group; the answer updates as you ask. */
@Composable
private fun GoalDialog(state: DeckBuilderState, goal: HandGoal) {
    val c = Mu.colors
    val p = state.oddsOf(goal)
    MuDialog(
        title = if (state.goals.byId(goal.id) == null) "New goal" else "Edit goal",
        onDismiss = state::cancelGoal,
        width = 672.dp,
        description = "Ask for each group what the opening hand should hold. Everything left at Any is free.",
        footer = {
            if (state.goals.byId(goal.id) != null) MuButton("✕ Delete", state::deleteGoal, variant = BtnVariant.GHOST, size = BtnSize.MD)
            Box(Modifier.weight(1f))
            MuButton("Cancel", state::cancelGoal, variant = BtnVariant.GHOST)
            MuButton("Save goal", state::saveGoal, variant = BtnVariant.PRIMARY)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Name")
                MuInput(goal.name, state::setGoalName, placeholder = "Starter and no brick", onFocusChange = state::onTextFieldFocusChanged)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Hand")
                Segmented(goal.handSize, listOf(5, 6), { if (it == 5) "5 · first" else "6 · second" }, state::setGoalHandSize)
            }
        }
        HRule(Modifier.padding(top = 20.dp), color = c.ink)
        val rows = state.groups.ordered().map { it.id to it.name } + (HandGoal.UNGROUPED to "Ungrouped")
        rows.forEach { (id, name) ->
            val count = if (id == HandGoal.UNGROUPED) {
                state.deck.main.count { state.groups.groupOf(it) == null }
            } else {
                state.groups.countIn(state.deck.main, id)
            }
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val color = state.groups.byId(id)?.let { GroupMarkers.hue(it.color) }
                Box(Modifier.size(10.dp).background(color ?: c.paper).border(1.dp, c.ink))
                RowText(name, Modifier.weight(1f))
                Mono("$count", Modifier.width(32.dp), align = TextAlign.End)
                Segmented(goal.ask(id), Ask.entries, { it.label }, { state.setGoalAsk(id, it) }, small = true)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.Bottom) {
            Help(if (goal.isEmpty) "Ask something to get an answer." else "Chance the opening ${goal.handSize} hold all of it", Modifier.weight(1f))
            // An empty question is not a certainty; it is no question yet.
            MuText(if (goal.isEmpty) "--" else percent(p), style = MuType.display(LocalMuFonts.current))
        }
    }
}
