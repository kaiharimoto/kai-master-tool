package com.kaiharimoto.neue.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.WordToggle
import com.kaiharimoto.neue.theme.Mu

/**
 * Ai's panel (1.0.43), docked down the right of every page on the desk and the
 * tablet, the deck re-fitting beside it as it does beside the Groups panel. Its head
 * names the assistant and the model it is talking through, and holds the history,
 * a new conversation, the setup and the close; its body is the conversation, the
 * setup wizard or the list of past conversations. [phone] draws it full screen.
 */
@Composable
fun AiPanel(h: NeueHolders, modifier: Modifier = Modifier, phone: Boolean = false) {
    val ai = h.ai
    val c = Mu.colors
    Row(modifier.background(c.paper)) {
        if (!phone) PanelEdge(h)
        Column(Modifier.weight(1f).fillMaxHeight().let { if (phone) it.imePadding() else it }) {
            Head(ai, phone)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ai.wizardOpen -> SetupWizard(ai, Modifier.fillMaxSize())
                    ai.historyOpen -> SessionList(ai, Modifier.fillMaxSize())
                    else -> Transcript(ai, Modifier.fillMaxSize())
                }
            }
            if (!ai.wizardOpen && !ai.historyOpen) Composer(ai)
        }
    }
}

@Composable
private fun Head(ai: AiState, phone: Boolean) {
    val c = Mu.colors
    val connection = ai.prefs.connection
    val provider = Providers.byId(connection?.provider)
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (phone) 48.dp else 44.dp)
            .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Micro(ai.name, color = c.ink)
            Mono(
                when {
                    ai.wizardOpen -> "Setting up"
                    provider == null -> "Not connected"
                    else -> provider.label + (connection?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                },
                color = c.ink45,
            )
        }
        val size = if (phone) 40.dp else 28.dp
        if (!ai.wizardOpen) {
            Tip("Past conversations") {
                IconButton(Icons.History, { ai.historyOpen = !ai.historyOpen }, size = size, toggled = ai.historyOpen, label = "History")
            }
            Tip("A new conversation, with memory as it stands now") {
                IconButton(Icons.Plus, { ai.newChat() }, size = size, label = "New", enabled = ai.configured && !ai.running, reason = if (ai.running) "Answering" else "Set up first")
            }
        }
        Tip(if (ai.wizardOpen) "Back to the conversation" else "Connect ${ai.name} to a model") {
            IconButton(Icons.Settings, { if (ai.wizardOpen) ai.wizardOpen = false else ai.openWizard() }, size = size, toggled = ai.wizardOpen, label = "Set up")
        }
        Tip("Close", kbd = DeskShortcuts.chordFor(DeskAction.AI_PANEL)?.let(DeskShortcuts::kbd)) {
            IconButton(Icons.X, { ai.setOpen(false) }, size = size, label = "Close")
        }
    }
}

/** The panel's left edge: a rule to drag for its width, like the pool's. */
@Composable
private fun PanelEdge(h: NeueHolders) {
    val c = Mu.colors
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val scale = h.neue.prefs.scale
    val width = h.neue.prefs.ai.panelWidth
    Box(Modifier.width(7.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        VRule(Modifier.width(if (hovered) 2.dp else 1.dp), color = c.ink)
        Box(
            Modifier
                .fillMaxSize()
                .hoverable(source)
                .cursor(CursorMode.DRAG, caption = h.ai.name, value = "${kotlin.math.round(width).toInt()} px")
                .draggable(
                    rememberDraggableState { px ->
                        val dp = with(density) { px.toDp().value } * scale
                        // Dragged left, the panel grows: its edge is on its left.
                        h.neue.update(debounce = true) { it.copy(ai = it.ai.copy(panelWidth = it.ai.panelWidth - dp)) }
                    },
                    Orientation.Horizontal,
                ),
        )
    }
}

/** The bar's switch for the panel: the assistant's name, boxed, inverted while it is out. */
@Composable
fun AiToggle(h: NeueHolders) {
    val ai = h.ai
    Tip("${ai.name}: your assistant. Ask it anything, or have it build and tune decks", kbd = DeskShortcuts.chordFor(DeskAction.AI_PANEL)?.let(DeskShortcuts::kbd)) {
        WordToggle(ai.name, ai.prefs.panelOpen) { ai.toggle() }
    }
}
