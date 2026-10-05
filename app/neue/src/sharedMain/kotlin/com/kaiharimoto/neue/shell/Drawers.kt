package com.kaiharimoto.neue.shell

import com.kaiharimoto.neue.builder.RulesPicker
import com.kaiharimoto.neue.builder.showInDeck
import com.kaiharimoto.mastertool.core.deck.DeckIssue
import com.kaiharimoto.mastertool.core.prep.PrepEvent
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.IssueSeverity
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.GroupMarkers
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.H2
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDrawer
import com.kaiharimoto.neue.kit.RowText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Strip
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu

/**
 * The right-hand drawer: what the deck is checked against, and what fails it (the 1.1.2 design review, finding 4:
 * the ✓ opens it to choose the rules, so it is Legality, not Issues). [event] is the Prep event whose day it offers.
 * (The groups are edited beside the deck since 1.0.15.)
 */
@Composable
fun BoxScope.Drawers(state: DeckBuilderState, neue: NeueState, event: PrepEvent? = null) {
    val open = neue.drawer
    MuDrawer(
        visible = open != null,
        onDismiss = { neue.drawer = null },
        header = {
            H2("Legality")
            Small("What the deck is checked against, and what fails it.", Modifier.padding(top = 4.dp))
        },
    ) {
        when (open) {
            Drawer.ISSUES -> Issues(state, neue, event)
            null -> Unit
        }
    }
}

@Composable
private fun Issues(state: DeckBuilderState, neue: NeueState, event: PrepEvent?) {
    val c = Mu.colors
    val validation = state.validation
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            RulesPicker(state, neue, event)
            if (validation.issues.isEmpty()) {
                // Kit §6's empty state: the verdict, then what it was checked against in one line.
                val words = state.rulesInForce.words()
                EmptyState("Legal.", "In $words.", inset = 24.dp)
            }
            listOf(IssueSeverity.ERROR to "Not legal", IssueSeverity.WARNING to "Worth a look").forEach { (severity, heading) ->
                val rows = validation.issues.filter { it.severity == severity }
                if (rows.isNotEmpty()) IssueRows(state, neue, rows, severity, heading)
            }
        }
        ScrollbarFor(scroll)
    }
}

/** One severity's issues under its strip, each with Show when it names a card the deck holds. */
@Composable
private fun IssueRows(state: DeckBuilderState, neue: NeueState, rows: List<DeckIssue>, severity: IssueSeverity, heading: String) {
    val c = Mu.colors
    Column {
        Strip(heading, inset = 24.dp) { Mono(rows.size.toString()) }
        rows.forEach { issue ->
            // Failure is the whole row inverted with ✕ (§10); a warning is a plain row.
            Inverted(severity == IssueSeverity.ERROR) {
                val inner = Mu.colors
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (severity == IssueSeverity.ERROR) inner.paper else Color.Transparent)
                        .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Micro(if (severity == IssueSeverity.ERROR) "✕ Failed" else "— Note", color = inner.ink)
                    RowText(issue.message, Modifier.weight(1f), color = inner.ink.copy(alpha = 0.8f), maxLines = 3)
                    // A card's issue shows it wherever the deck holds it, named section or not (finding 5).
                    val id = issue.cardId
                    if (id != null && (issue.section != null || DeckSection.entries.any { id in state.deck[it] })) {
                        MuButton("Show", { showInDeck(state, neue, id, issue.section) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                    }
                }
            }
        }
    }
}

/** Deletes a group, frees its cards, and offers the way back. */
fun deleteGroup(state: DeckBuilderState, id: String) {
    val name = state.groups.byId(id)?.name
    state.updateGroups { it.remove(id) }
    state.showToast("Deleted ${name?.ifBlank { null } ?: "the group"}. Its cards stay in the deck", undo = state::undo)
}
