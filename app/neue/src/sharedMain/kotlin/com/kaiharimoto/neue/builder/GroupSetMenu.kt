package com.kaiharimoto.neue.builder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.RequestFocusOnce
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/**
 * The deck's sets of groups, beside the Groups button (kai, 2026-10: "open a new way of
 * looking at the deck and choose between these sets"): the set in use by name, and a menu
 * to choose another, start a new one empty or from a copy, rename or delete one. Faint
 * while the groups are off, like the arrangement; choosing a set brings them out.
 */
@Composable
internal fun GroupSetButton(state: DeckBuilderState, neue: NeueState, nameWidth: Dp) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    var at by remember { mutableStateOf(Offset.Zero) }
    val on = groupsOn(state)
    val sets = state.groupSets
    Tip("Sets of groups: other ways of grouping this deck, one in use at a time") {
        Row(
            Modifier
                .height(28.dp)
                .background(animatedColor(if (hovered) c.ink06 else Color.Transparent))
                .border(1.dp, if (on) c.ink else c.ink25)
                .hoverable(source)
                .onGloballyPositioned { at = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }
                .cursorPointer(caption = "Sets")
                .muClickable(interactionSource = source) { neue.menu = MenuSpec(at, groupSetMenu(state, neue)) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Micro(sets.current.name, Modifier.widthIn(max = nameWidth), color = if (on) c.ink else c.ink45)
            Micro("▾", color = c.ink70)
        }
    }
}

/** The sets menu: every set to choose, then New, Copy, Rename and Delete for the one in use. */
internal fun groupSetMenu(state: DeckBuilderState, neue: NeueState): List<MenuEntry> {
    val sets = state.groupSets
    val current = sets.current
    return buildList {
        add(MenuEntry("Sets of groups"))
        sets.sets.forEach { set ->
            val groups = if (set.id == current.id) state.groups.groups.size else set.groups.groups.size
            add(
                MenuEntry(
                    set.name,
                    hint = if (set.id == current.id) "In use" else if (groups == 1) "1 group" else "$groups groups",
                ) { state.useGroupSet(set.id) }
            )
        }
        add(MenuEntry("New set", hint = "No groups", separatorBefore = true) { state.addGroupSet(copy = false) })
        add(MenuEntry("Copy “${current.name}”", hint = "To change") { state.addGroupSet(copy = true) })
        add(MenuEntry("Rename “${current.name}”…") { neue.renamingSet = current.id })
        add(
            MenuEntry(
                "Delete “${current.name}”",
                danger = true,
                enabled = sets.sets.size > 1,
                reason = "A deck keeps one set",
            ) { state.deleteGroupSet(current.id) }
        )
    }
}

/** The set being renamed, while it is (`NeueState.renamingSet`). */
@Composable
internal fun GroupSetRenameDialog(state: DeckBuilderState, neue: NeueState) {
    val id = neue.renamingSet ?: return
    val set = state.groupSets.byId(id) ?: return
    var name by remember(id) { mutableStateOf(set.name) }
    val focus = remember { FocusRequester() }
    RequestFocusOnce(focus)
    fun submit() {
        if (name.isBlank()) return
        neue.renamingSet = null
        state.renameGroupSet(id, name)
    }
    MuDialog(
        "Rename the set",
        onDismiss = { neue.renamingSet = null },
        width = 384.dp,
        description = "A name for this way of grouping the deck",
        footer = {
            MuButton("Cancel", { neue.renamingSet = null }, variant = BtnVariant.SUBTLE)
            MuButton("Rename", { submit() }, variant = BtnVariant.PRIMARY, enabled = name.isNotBlank(), reason = "Give it a name")
        },
    ) {
        MuInput(name, { name = it }, Modifier.fillMaxWidth(), focusRequester = focus, onSubmit = { submit() })
    }
}
