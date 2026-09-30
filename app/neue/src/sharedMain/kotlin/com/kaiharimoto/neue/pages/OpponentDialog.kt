package com.kaiharimoto.neue.pages

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.SidingCodec
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.theme.Mu

/**
 * An opponent made by hand (1.0.42, kai: "let the user add siding patterns to the current
 * deck and create opponent decks by choosing a name and 3 main cards in a card
 * picker/searcher"): a name, and three cards to know the deck by, found in the card pool.
 * Its decklist, when there is one, is linked later from the matchup's menu. [start] is
 * the matchup being changed, or null for a new one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpponentDialog(start: Matchup?, state: DeckBuilderState, onDismiss: () -> Unit, onSave: (String, List<CardId>) -> Unit) {
    val c = Mu.colors
    var name by remember { mutableStateOf(start?.name.orEmpty()) }
    var covers by remember { mutableStateOf(start?.covers.orEmpty()) }
    var query by remember { mutableStateOf("") }
    val results = remember(query, state.index) {
        if (query.isBlank()) emptyList() else state.index.search(query, limit = 24).cards
    }
    val full = covers.size >= SidingCodec.COVERS
    val width = if (LocalTouchFirst.current) 64.dp else 56.dp
    MuDialog(
        title = if (start == null) "New opponent" else "Edit opponent",
        onDismiss = onDismiss,
        width = 640.dp,
        description = "A name, and three cards to know the deck by. Link its decklist later, from the matchup's menu, if you get one.",
        footer = {
            MuButton("Cancel", onDismiss, variant = BtnVariant.GHOST)
            MuButton(
                if (start == null) "Add" else "Save",
                { onSave(name.trim(), covers) },
                variant = BtnVariant.PRIMARY,
                enabled = name.isNotBlank(),
                reason = "Name the deck first",
            )
        },
    ) {
        FieldLabel("Name")
        MuInput(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Snake-Eye, Yubel, Ryzeal…")
        FieldLabel("Its cards", hint = "${covers.size} of ${SidingCodec.COVERS}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(SidingCodec.COVERS) { i ->
                val id = covers.getOrNull(i)
                val card = id?.let(state.index::byId)
                Box(
                    Modifier
                        .width(width)
                        .aspectRatio(CARD_RATIO)
                        .border(1.dp, if (card == null) c.ink25 else c.ink)
                        .let { base ->
                            if (card == null) base else base
                                .cursorPointer(caption = "Remove")
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { covers = covers - id }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (card != null) NeueCard(card, Modifier.fillMaxSize(), foil = "off") else Small("${i + 1}", color = c.ink25)
                }
            }
        }
        MuInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search the pool for its cards", imeAction = androidx.compose.ui.text.input.ImeAction.Search)
        if (query.isNotBlank() && results.isEmpty()) Small("No card by that name.", color = c.ink45)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            results.forEach { card ->
                val chosen = card.id in covers
                Box(
                    Modifier
                        .width(width)
                        .aspectRatio(CARD_RATIO)
                        .cursorPointer(
                            caption = if (chosen) "Remove" else "Add",
                            enabled = chosen || !full,
                            reason = "Three cards already: take one out first",
                        )
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = chosen || !full) {
                            covers = if (chosen) covers - card.id else covers + card.id
                        },
                ) {
                    NeueCard(card, Modifier.fillMaxSize(), foil = "off", selected = chosen, dimmed = !chosen && full)
                }
            }
        }
    }
}
