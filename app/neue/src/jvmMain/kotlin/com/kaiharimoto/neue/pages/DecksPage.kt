package com.kaiharimoto.neue.pages

import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Numeral
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * `01 Decks`: every saved deck as a ruled list, newest first. The deck on the
 * builder is the inverted row. Click opens; the delete asks first, because it
 * is the one thing here that cannot be undone.
 */
@Composable
fun DecksPage(deps: AppDependencies, state: DeckBuilderState, neue: NeueState, reload: Int) {
    val c = Mu.colors
    var decks by remember { mutableStateOf<List<StoredDeck>?>(null) }
    var filter by remember { mutableStateOf("") }
    LaunchedEffect(reload, state.deckId) {
        decks = deps.deckRepository.all().sortedByDescending { it.entry.updatedAtEpochMs }
    }
    val shown = decks?.filter { filter.isBlank() || it.entry.name.contains(filter.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            numeral = 1,
            title = "Decks",
            subtitle = decks?.let { "${it.size} saved" } ?: "Loading",
        ) {
            MuInput(filter, { filter = it }, Modifier.width(288.dp), placeholder = "Find a deck")
            MuButton("Import", { state.importFromFile(); neue.go(Page.BUILDER) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Import)
            MuButton("New deck", { state.newDeck(); neue.go(Page.BUILDER) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Plus)
        }
        when {
            shown == null -> Unit
            decks?.isEmpty() == true -> EmptyState(
                "Nothing yet.",
                "Build a deck and save it, or import a .ydk file, and it is kept here.",
            ) { MuButton("New deck", { state.newDeck(); neue.go(Page.BUILDER) }, variant = BtnVariant.PRIMARY, arrow = true) }
            shown.isEmpty() -> EmptyState("No matches.", "No saved deck has that in its name.")
            else -> Box(Modifier.fillMaxSize()) {
                val list = rememberLazyListState()
                val now = remember(decks) { System.currentTimeMillis() }
                LazyColumn(state = list) {
                    itemsIndexed(shown, key = { _, d -> d.entry.id }) { i, stored ->
                        DeckRow(
                            n = i + 1,
                            stored = stored,
                            state = state,
                            current = stored.entry.id == state.deckId,
                            now = now,
                            onOpen = {
                                state.load(stored.entry.id)
                                neue.go(Page.BUILDER)
                            },
                            onDelete = { neue.confirmDelete = stored.entry.id to stored.entry.name },
                        )
                    }
                }
                ScrollbarFor(list)
            }
        }
    }
}

@Composable
private fun DeckRow(
    n: Int,
    stored: StoredDeck,
    state: DeckBuilderState,
    current: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val deck = stored.entry.deck
    // The deck's face is its most-played main-deck card: a picture, so it keeps its colour (§17).
    val face = remember(deck, state.index) {
        deck.main.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let(state.index::byId)
    }
    val actions by animateFloatAsState(if (hovered) 1f else 0f, label = "actions")
    Inverted(current) {
        val inner = Mu.colors
        Row(
            Modifier
                .fillMaxWidth()
                .background(animatedColor(if (current) inner.paper else if (hovered) c.ink06 else Color.Transparent))
                .hoverable(source)
                .cursorPointer(caption = "Open")
                .clickable(interactionSource = source, indication = null, onClick = onOpen)
                .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(horizontal = 32.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Numeral(n, color = if (current) inner.ink.copy(alpha = 0.6f) else c.ink45)
            Box(Modifier.size(44.dp, 64.dp)) {
                if (face != null) NeueCard(face, Modifier.fillMaxSize(), foil = "off")
                else Box(Modifier.fillMaxSize().background(inner.ink06))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MuText(stored.entry.name, style = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = inner.ink, maxLines = 1)
                Mono(
                    "${deck.main.size} main · ${deck.extra.size} extra · ${deck.side.size} side · ${ago(stored.entry.updatedAtEpochMs, now)}",
                    color = if (current) inner.ink.copy(alpha = 0.6f) else c.ink45,
                )
            }
            if (current) Small("On the builder", color = inner.ink.copy(alpha = 0.7f))
            Row(Modifier.alpha(actions), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("✕ Delete", onDelete, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton("Open", onOpen, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
            }
        }
    }
}
