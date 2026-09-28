package com.kaiharimoto.mastertool.ui.deckbuilder

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kaiharimoto.mastertool.core.prefs.ThemeMode
import com.kaiharimoto.mastertool.core.prefs.UiPreferences
import com.kaiharimoto.mastertool.ui.fx.defaultFeedbackEnabled
import com.kaiharimoto.mastertool.ui.update.UpdateState

/**
 * The overflow menu, written once for two bars.
 *
 * There are two builder headers now — the tablet's, and a portrait one narrow
 * enough that almost everything on it has to be behind the one control. They
 * had better offer the same menu, and the only way to be sure of that is for
 * there to be one menu. A second copy is how a setting ends up reachable on a
 * tablet and not on a phone, which is exactly the failure `docs/classic/DEVICES.md` was
 * written after.
 */

/** What you can do to the deck as a file. */
@Composable
fun ColumnScope.DeckFileMenuItems(state: DeckBuilderState, onClose: () -> Unit) {
    DropdownMenuItem(
        text = { Text("New deck") },
        onClick = { onClose(); state.newDeck() },
    )
    DropdownMenuItem(
        text = { Text("Import…") },
        onClick = { onClose(); state.importFromFile() },
    )
    DropdownMenuItem(
        text = { Text("Export…") },
        onClick = { onClose(); state.exportToFile() },
    )
    DropdownMenuItem(
        text = { Text("Share") },
        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
        onClick = { onClose(); state.shareDeck() },
    )
}

/**
 * How the app looks and feels, and the two housekeeping items under it.
 *
 * These stay open when they are toggled — a theme you are cycling through is
 * one you want to see change without reopening the menu each time — which is
 * why they do not call [onClose].
 */
@Composable
fun ColumnScope.AppSettingsMenuItems(
    state: DeckBuilderState,
    layout: DeckLayoutState,
    updateState: UpdateState,
    onClose: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                if (layout.preferences.fitAll) {
                    "✓ Fit the whole deck on screen"
                } else {
                    "Fit the whole deck on screen"
                },
            )
        },
        onClick = {
            onClose()
            layout.setFitAll(!layout.preferences.fitAll)
        },
    )
    DropdownMenuItem(
        text = {
            Text(if (layout.preferences.stacked) "Show every copy" else "Stack duplicate cards")
        },
        onClick = {
            onClose()
            layout.update { it.copy(stacked = !it.stacked) }
        },
    )
    DropdownMenuItem(
        text = {
            Text(
                if (state.searchEffects) {
                    "Search effect text: on"
                } else {
                    "Search effect text: off"
                },
            )
        },
        onClick = {
            val next = !state.searchEffects
            state.onSearchEffectsChange(next)
            layout.update { it.copy(searchEffects = next) }
        },
    )
    DropdownMenuItem(
        text = {
            val effective = layout.preferences.feedbackEnabled ?: defaultFeedbackEnabled()
            Text(if (effective) "Sound & haptics: on" else "Sound & haptics: off")
        },
        onClick = {
            layout.update {
                val effective = it.feedbackEnabled ?: defaultFeedbackEnabled()
                it.copy(feedbackEnabled = !effective)
            }
        },
    )
    DropdownMenuItem(
        text = {
            Text(
                if (layout.preferences.prismaticCards) {
                    "Prismatic borders: on"
                } else {
                    "Prismatic borders: off"
                },
            )
        },
        onClick = { layout.update { it.copy(prismaticCards = !it.prismaticCards) } },
    )
    DropdownMenuItem(
        text = {
            Text(
                when (layout.preferences.themeMode) {
                    ThemeMode.SYSTEM -> "Theme: match system"
                    ThemeMode.DARK -> "Theme: dark"
                    ThemeMode.LIGHT -> "Theme: light"
                },
            )
        },
        onClick = {
            // Cycles rather than opening a submenu: three values, and the whole
            // screen previews the choice instantly.
            layout.update {
                it.copy(
                    themeMode = when (it.themeMode) {
                        ThemeMode.SYSTEM -> ThemeMode.DARK
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.SYSTEM
                    },
                )
            }
        },
    )
    DropdownMenuItem(
        text = { Text("Reset layout") },
        onClick = {
            onClose()
            layout.update {
                // Keep what is about the deck or the person — the format, the
                // stacked view, the pinned easter-egg pool, the theme, which
                // room the table is in — and reset what is about the layout.
                // "The room" in the comment this replaces meant the panes; the
                // play stage now has a literal one, and it is a taste rather
                // than a layout, so it survives like the theme beside it.
                UiPreferences.DEFAULT.copy(
                    format = it.format,
                    stacked = it.stacked,
                    easterEggPool = it.easterEggPool,
                    themeMode = it.themeMode,
                    feedbackEnabled = it.feedbackEnabled,
                    cardBackUrl = it.cardBackUrl,
                    prismaticCards = it.prismaticCards,
                    searchEffects = it.searchEffects,
                    scene = it.scene,
                    deskLight = it.deskLight,
                    // Tuning is a taste too, and one that took a person an
                    // evening with a slider. A menu item on a *different
                    // screen* with no undo is the last place it should be
                    // destroyed from; the panel has its own reset.
                    stageTuning = it.stageTuning,
                )
            }
        },
    )

    HorizontalDivider()

    DropdownMenuItem(
        text = { Text("Refresh card database") },
        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        onClick = { onClose(); state.refreshCardPool(force = true) },
    )
    DropdownMenuItem(
        text = {
            Text(if (updateState.isChecking) "Checking…" else "v${updateState.currentVersionName}")
        },
        leadingIcon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        onClick = { onClose(); updateState.check(userInitiated = true) },
    )
}
