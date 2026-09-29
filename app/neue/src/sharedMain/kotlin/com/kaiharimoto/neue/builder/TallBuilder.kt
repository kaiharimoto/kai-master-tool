package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.layout.DockMetrics
import com.kaiharimoto.mastertool.core.layout.PoolDock
import com.kaiharimoto.mastertool.core.layout.PoolStop
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.mastertool.core.input.CursorMode

/** The dock's grabber: a thumb's height, with the bar drawn in its middle. */
private val GRABBER = 24.dp

/** The Cards and Groups tabs over the pool, when the groups are out. */
private val DOCK_TABS = 40.dp

/** How much of the deck survives the dock at half: a row and its label, read at a glance. */
private val MIN_DECK = 200.dp

/** How far ahead of a released flick the dock aims, so a short fast throw reaches its stop. */
private const val FLICK_SECONDS = 1f / 12f

/**
 * The builder on an upright phone (v1.3.5, kai's pick): the deck on top, the pool
 * docked along the bottom under the thumbs.
 *
 * *The pool goes where the thumbs are.* On a tablet the pool is a pane down the
 * left; on a 412dp phone there is no left to put it in, and the top of the
 * screen is where a thumb cannot go. So the deck takes the top — fitted width
 * first and scrolling ([DeckColumn], five cards across) — and the pool is a dock
 * with three stops ([PoolDock]): its search alone, half the screen, or all of
 * it. A finger in the search field takes it to the top, since typing means the
 * pool; letting go brings the deck back, never all the way down over the
 * results just found ([PoolDock.afterTyping]).
 *
 * There is no inspector: a tap on a card opens it large, with every action
 * beside it ([com.kaiharimoto.neue.NeueState.viewSoon]). The Groups panel is the
 * dock's second tab while the groups are out.
 */
@Composable
fun TallBuilder(state: DeckBuilderState, neue: NeueState, drag: NeueDrag, onSearchEffects: (Boolean) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var header by remember { mutableIntStateOf(0) }
        val groups = groupsOn(state)
        val chrome = with(density) { (GRABBER + (if (groups) DOCK_TABS else 0.dp)).toPx() } + header
        val metrics = with(density) {
            DockMetrics(windowHeight = maxHeight.toPx(), chrome = chrome, minDeck = MIN_DECK.toPx())
        }
        val stored = PoolStop.entries.firstOrNull { it.name == neue.prefs.phoneDockStop } ?: PoolStop.HALF
        // Typing is a stop the dock is in, never one it is set to: a process killed with the
        // keyboard up does not come back with the pool over the whole deck.
        val typing = neue.searchFocused
        val settled = if (typing) PoolDock.whileTyping() else stored
        // Only on the way out of the field: a dock left at its lowest stays there on opening.
        var wasTyping by remember { mutableStateOf(false) }
        LaunchedEffect(typing) {
            if (wasTyping && !typing) {
                val after = PoolDock.afterTyping(stored)
                if (after != stored) neue.update { it.copy(phoneDockStop = after.name) }
            }
            wasTyping = typing
        }
        // Non-null only while a thumb is on the grabber: the deck is sized against the
        // settled stop, so a drag moves one surface rather than re-fitting every card.
        var dragging by remember { mutableStateOf<Float?>(null) }
        val animated by animateFloatAsState(
            PoolDock.height(settled, metrics),
            tween(MuMotion.PANEL, easing = MuMotion.ease),
            label = "dock",
        )
        val live = dragging ?: animated
        val deckHeight = with(density) { PoolDock.deckHeight(settled, metrics).toDp() }

        DeckColumn(state, neue, drag, Modifier.fillMaxWidth().height(deckHeight))
        val c = Mu.colors

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Outside the height, so the keyboard lifts the dock rather than eating its field.
                .imePadding()
                .height(with(density) { live.toDp() })
                .background(c.paper)
                .drawBehind { drawLine(c.ink, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) },
        ) {
            val grab = rememberDraggableState { delta ->
                val from = dragging ?: live
                dragging = (from - delta).coerceIn(PoolDock.height(PoolStop.PEEK, metrics), PoolDock.height(PoolStop.FULL, metrics))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(GRABBER)
                    .cursor(CursorMode.DRAG, caption = "Pool")
                    .draggable(
                        grab,
                        Orientation.Vertical,
                        onDragStopped = { velocity ->
                            val released = dragging
                            dragging = null
                            if (released != null) {
                                val stop = PoolDock.nearest(released - velocity * FLICK_SECONDS, metrics)
                                neue.update { it.copy(phoneDockStop = stop.name) }
                            }
                        },
                    )
                    // A tap steps it: down to half from the top, up to half from the bottom, and up from half.
                    .muClickable {
                        val next = when (stored) {
                            PoolStop.PEEK -> PoolStop.HALF
                            PoolStop.HALF -> PoolStop.FULL
                            PoolStop.FULL -> PoolStop.HALF
                        }
                        neue.update { it.copy(phoneDockStop = next.name) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(40.dp).height(3.dp).background(c.ink45))
            }
            PhonePool(state, neue, drag, onSearchEffects, Modifier.fillMaxWidth().weight(1f), onHeader = { header = it })
        }
    }
}

/**
 * The phone's pool, docked or in its pane (v1.3.5): the pool, and — while the groups
 * are out — a second tab with the Groups panel the width of the window, since a phone
 * has no room for it beside the deck.
 */
@Composable
fun PhonePool(
    state: DeckBuilderState,
    neue: NeueState,
    drag: NeueDrag,
    onSearchEffects: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onHeader: ((Int) -> Unit)? = null,
) {
    val groups = groupsOn(state)
    var groupsTab by remember { mutableStateOf(groups) }
    // The groups turned on, or a group being drawn up, is the Groups tab; off, the cards.
    LaunchedEffect(groups, state.groupDraft != null) { groupsTab = groups }
    Column(modifier) {
        if (groups) {
            Row(Modifier.fillMaxWidth().height(DOCK_TABS).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DockTab("Cards", !groupsTab, Modifier.weight(1f)) { groupsTab = false }
                DockTab("Groups", groupsTab, Modifier.weight(1f), count = state.groups.groups.size) { groupsTab = true }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (groups && groupsTab) {
                GroupsPanel(state, neue, Modifier.fillMaxSize(), fillWidth = true)
            } else {
                PoolPane(state, neue, drag, onSearchEffects, Modifier.fillMaxSize(), onHeader = onHeader)
            }
        }
    }
}

@Composable
private fun DockTab(label: String, active: Boolean, modifier: Modifier, count: Int? = null, onClick: () -> Unit) {
    val c = Mu.colors
    Inverted(active) {
        Row(
            modifier
                .fillMaxHeight()
                .padding(vertical = 4.dp)
                .background(Mu.colors.paper)
                .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .cursorPointer(caption = label)
                .muClickable(onClick = onClick),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Micro(label, color = Mu.colors.ink)
            if (count != null) Mono("  $count", color = Mu.colors.ink70)
        }
    }
}
