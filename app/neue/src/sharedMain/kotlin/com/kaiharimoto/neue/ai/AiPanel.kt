package com.kaiharimoto.neue.ai

import com.kaiharimoto.neue.ai.chessy.chessySpot
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
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.prefs.AiPrefs
import com.kaiharimoto.neue.ai.avatar.AiName
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MuIcon
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    // The history's lines read ahead, off the main thread, so the list opens at once (1.0.92).
    LaunchedEffect(ai) { withContext(Dispatchers.IO) { runCatching { ai.files.warm() } } }
    Row(modifier.background(c.paper).then(if (com.kaiharimoto.neue.ai.chessy.LocalChessy.current != null) Modifier.chessySpot(com.kaiharimoto.neue.ai.chessy.ChessyCrew.PANEL) else Modifier)) {
        if (!phone) PanelEdge(h)
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .let { if (phone) it.imePadding() else it }
                // A picture dropped anywhere on the panel goes with the next message (1.0.55).
                .let { if (!ai.wizardOpen && ai.configured) it.takesPictures(ai) else it },
        ) {
            Head(ai, phone)
            if (!ai.wizardOpen && AiState.PHASE >= 3 && ai.configured) Tools(ai)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ai.demoOpen && !ai.wizardOpen -> AiDemoView(ai, Modifier.fillMaxSize())
                    ai.wizardOpen -> SetupWizard(ai, Modifier.fillMaxSize())
                    ai.historyOpen -> SessionList(ai, Modifier.fillMaxSize())
                    else -> Transcript(ai, Modifier.fillMaxSize())
                }
            }
            if (!ai.wizardOpen && !ai.historyOpen && !ai.demoOpen) Composer(ai, phone = phone)
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
            NameInHead(ai)
            val line = when {
                ai.wizardOpen -> "Setting up"
                provider == null -> "Not connected"
                ai.profiling -> "Learn About You · " + provider.label
                ai.refactoring -> "Refactor guide · " + provider.label
                ai.writing -> "Reader's guide · " + provider.label
                ai.session?.mode == AiSession.MODE_DUEL -> "At the duel table · " + provider.label
                ai.session?.mode == AiSession.MODE_WORLD -> "In Ai World · " + provider.label
                ai.session?.mode == AiSession.MODE_EFFECTS -> "Writing effects · " + provider.label
                ai.session?.mode == AiSession.MODE_RUBRIC -> "Shootout interview · " + provider.label
                ai.tuning -> "Fine Tuning · " + when (ai.session?.mode) {
                    AiSession.MODE_STUDY -> "studying"
                    AiSession.MODE_PRINCIPLES -> "first principles"
                    else -> "being taught"
                } + " · " + provider.label
                else -> provider.label + (connection?.model?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }
            // The model's name is the way to its settings (1.0.54): model, effort and the rest, without the setup.
            if (provider != null && !ai.wizardOpen) {
                val source = remember { MutableInteractionSource() }
                val hovered by source.collectIsHoveredAsState()
                Tip("Model, effort and the rest: change them here") {
                    Row(
                        Modifier
                            .hoverable(source)
                            .cursorPointer(caption = "Settings")
                            .muClickable(interactionSource = source) { ai.quickOpen = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Mono(line, color = if (hovered) c.ink else c.ink45)
                        Mono("▾", color = if (hovered) c.ink else c.ink45)
                    }
                }
            } else {
                Mono(line, color = c.ink45)
            }
        }
        val size = if (phone) 40.dp else 28.dp
        // How full the model's window is (1.0.56); a click shows what fills it.
        if (!ai.wizardOpen && ai.configured) ContextGauge(ai)
        if (!ai.wizardOpen) {
            // Its brain lives here, with it (1.0.63, kai: "the Ai button to see his brain should be in the Ai panel").
            Tip("Look into ${ai.name}: read and edit what it knows and how it thinks") {
                IconButton(Icons.Brain, { ai.memoryOpen = if (ai.memoryOpen != null) null else "USER.md" }, size = size, toggled = ai.memoryOpen != null, label = "Brain")
            }
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

/**
 * The name at the top of the panel, renamed in place (1.0.46, kai: "let me change the name
 * of the AI without having to go through setup every time"): a click makes it a field,
 * Enter or leaving it keeps the new name, Esc keeps the old one.
 */
@Composable
private fun NameInHead(ai: AiState) {
    val c = Mu.colors
    // Chessy's name is her own (kai): shown, never renamed here; Ai's name waits for Ai to come back
    if (com.kaiharimoto.neue.ai.chessy.LocalChessy.current != null) {
        Tip("${ai.name}'s name is her own. Switch back to Ai in Settings to rename Ai.") { AiName(ai.name, c.ink) }
        return
    }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(ai.ownName) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (editing) {
        fun keep() {
            if (draft.isNotBlank()) ai.rename(draft)
            editing = false
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        MuInput(
            draft,
            { draft = it.take(AiPrefs.MAX_NAME) },
            Modifier.width(180.dp).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    draft = ai.ownName
                    editing = false
                    true
                } else {
                    false
                }
            },
            placeholder = ai.name,
            dense = true,
            focusRequester = focus,
            onFocusChange = { focused -> if (!focused && editing) keep() },
            onSubmit = { keep() },
        )
    } else {
        val source = remember { MutableInteractionSource() }
        val hovered by source.collectIsHoveredAsState()
        Row(
            Modifier
                .hoverable(source)
                .cursorPointer(caption = "Rename")
                .muClickable(interactionSource = source) {
                    draft = ai.ownName
                    editing = true
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiName(ai.name, c.ink)
            if (hovered || LocalTouchFirst.current) {
                MuIcon(Icons.Pencil, c.ink45, Modifier.size(11.dp))
            }
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

/**
 * Under the head (1.0.54): what Ai learns and keeps — Teach (Fine Tuning of the open deck, three
 * ways), its Guide to that deck, and About you (the person's profile, Learn About You). While one
 * of them runs, Finish ends it.
 */
@Composable
private fun Tools(ai: AiState) {
    val c = Mu.colors
    val saved = ai.h.builder.deckId != null
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ai.tuning) {
            Tip(if (ai.refactoring || ai.writing) "Finish: every change to the guide, to keep or undo" else "Finish: its report, and what it learned to keep or undo") {
                WordToggle("Finish", on = true, onClick = { ai.finishTuning() })
            }
            // The book as it is written (1.0.67): open it beside the session and watch the chapters land.
            if (ai.writing) Tip("Read the guide as it is written") { WordToggle("Read", on = false, onClick = { ai.openBook() }) }
            Mono(
                when {
                    ai.profiling -> "Learning about you"
                    ai.refactoring -> "Refactoring ${ai.h.builder.deckName}'s guide"
                    ai.writing -> "Writing ${ai.h.builder.deckName}'s guide"
                    ai.session?.mode == AiSession.MODE_RUBRIC -> "Writing the Shootout rubric"
                    else -> "Learning ${ai.h.builder.deckName}"
                },
                color = c.ink45,
            )
        } else {
            Tip("Fine Tuning: teach ${ai.name} the open deck, let it study it, learn it from first principles, or refactor its guide") {
                WordToggle("Teach", on = false, onClick = { ai.askTune() })
            }
            Tip(if (saved) "${ai.name}'s guide to ${ai.h.builder.deckName}: kept across sessions, as a PDF too" else "Save the deck first: the guide belongs to a saved deck") {
                WordToggle("Guide", on = ai.docOpen is LivingDoc.Guide, onClick = { ai.openGuide() })
            }
            Tip("Learn About You: ${ai.name} interviews you, and keeps your profile") {
                WordToggle("About you", on = ai.docOpen == LivingDoc.Profile, onClick = { ai.profileAsk = true })
            }
        }
    }
}
