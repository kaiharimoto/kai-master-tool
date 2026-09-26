package com.kaiharimoto.neue.shell

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
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.deck.IssueSeverity
import com.kaiharimoto.mastertool.core.model.DeckSection
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.Drawer
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Selection
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

/** The right-hand drawers: what is wrong with the deck, and its groups. */
@Composable
fun BoxScope.Drawers(state: DeckBuilderState, neue: NeueState) {
    val open = neue.drawer
    MuDrawer(
        visible = open != null,
        onDismiss = { neue.drawer = null },
        header = {
            when (open) {
                Drawer.GROUPS -> {
                    H2("Groups")
                    Small("The roles you drew. They travel with the deck in the .ydkx file.", Modifier.padding(top = 4.dp))
                }
                else -> {
                    H2("Issues")
                    Small("What stops the deck being legal, then what is worth a look.", Modifier.padding(top = 4.dp))
                }
            }
        },
    ) {
        when (open) {
            Drawer.GROUPS -> Groups(state, neue)
            Drawer.ISSUES -> Issues(state, neue)
            null -> Unit
        }
    }
}

@Composable
private fun Issues(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val validation = state.validation
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (validation.issues.isEmpty()) {
                Small("Nothing. The deck is legal in ${state.format.name}.", Modifier.padding(24.dp), color = c.ink70)
            }
            listOf(IssueSeverity.ERROR to "Not legal", IssueSeverity.WARNING to "Worth a look").forEach { (severity, heading) ->
                val rows = validation.issues.filter { it.severity == severity }
                if (rows.isEmpty()) return@forEach
                Strip(heading) { Mono(rows.size.toString()) }
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
                            val section = issue.section
                            val id = issue.cardId
                            if (section != null && id != null) {
                                MuButton("Show", {
                                    val index = state.deck[section].indexOf(id)
                                    val card = state.index.byId(id)
                                    if (index >= 0 && card != null) neue.selection = Selection.InDeck(card, section, index)
                                    neue.drawer = null
                                }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
                            }
                        }
                    }
                }
            }
        }
        ScrollbarFor(scroll)
    }
}

/**
 * The groups, where each one can be seen to be editable: its name is a field,
 * its colour is six swatches, and "Edit cards", the arrows and "Delete" are
 * written out on every row rather than hidden behind a menu. Before this, the
 * only way to delete a group was to open it for editing and find a link in the
 * lens strip — kai could not tell it was possible.
 */
@Composable
private fun Groups(state: DeckBuilderState, neue: NeueState) {
    val c = Mu.colors
    val groups = state.groups.ordered()
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Small(if (groups.isEmpty()) "No groups yet." else "${groups.size} ${if (groups.size == 1) "group" else "groups"}", Modifier.weight(1f), color = c.ink70)
                MuButton("New group", {
                    neue.drawer = null
                    state.startGroupDraft()
                }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, icon = Icons.Plus)
            }
            Strip("Group") { Micro("In the main deck", color = c.ink45) }
            groups.forEachIndexed { i, group ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(14.dp).background(GroupMarkers.hue(group.color)).border(1.dp, c.ink))
                        // Renamed where it stands: typed freely, written once on Enter or on leaving the field,
                        // so a name is one step of undo rather than one per letter.
                        var text by remember(group.id, group.name) { mutableStateOf(group.name) }
                        fun commit() {
                            val name = text.trim()
                            if (name.isNotEmpty() && name != group.name) state.updateGroups { it.upsert(group.copy(name = name)) }
                            if (name.isEmpty()) text = group.name
                        }
                        MuInput(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = "Name it",
                            dense = true,
                            onFocusChange = { focused ->
                                state.onTextFieldFocusChanged(focused)
                                if (!focused) commit()
                            },
                            onSubmit = ::commit,
                            modifier = Modifier.weight(1f),
                        )
                        Mono(state.groups.countIn(state.deck[DeckSection.MAIN], group.id).toString(), color = c.ink)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Recolour in place: six swatches, the current one framed.
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            GroupMarkers.hues.forEachIndexed { h, hue ->
                                Tip("Colour ${h + 1}") {
                                    Box(
                                        Modifier
                                            .size(16.dp)
                                            .background(hue)
                                            .border(if (group.color == h) 2.dp else 0.dp, if (group.color == h) c.ink else Color.Transparent)
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                                state.updateGroups { it.upsert(group.copy(color = h)) }
                                            },
                                    )
                                }
                            }
                        }
                        Box(Modifier.weight(1f))
                        Tip("Choose its cards on the deck: click to add or remove, then Save group") {
                            MuButton("Edit cards", {
                                neue.drawer = null
                                state.editGroup(group)
                            }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Pencil)
                        }
                        Tip("Move up") {
                            IconButton(Icons.ArrowUp, { state.updateGroups { it.reorder(group.id, i - 1) } }, enabled = i > 0, size = 32.dp)
                        }
                        Tip("Move down") {
                            IconButton(Icons.ArrowDown, { state.updateGroups { it.reorder(group.id, i + 1) } }, enabled = i < groups.lastIndex, size = 32.dp)
                        }
                        Tip("Delete the group. Its cards stay in the deck") {
                            MuButton("Delete", { deleteGroup(state, group.id) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Trash)
                        }
                    }
                }
            }
            Help(
                "A group is a role you draw on the deck. Edit cards opens it on the main deck, where a click adds or removes a card. " +
                    "Hold a card in the deck to put it in a group, and the Roles lens shows them all with their opening odds.",
                Modifier.padding(24.dp),
                color = c.ink45,
            )
        }
        ScrollbarFor(scroll)
    }
}

/** Deletes a group, frees its cards, and offers the way back. */
fun deleteGroup(state: DeckBuilderState, id: String) {
    val name = state.groups.byId(id)?.name
    state.updateGroups { it.remove(id) }
    state.showToast("Deleted ${name?.ifBlank { null } ?: "the group"}. Its cards stay in the deck", undo = state::undo)
}
