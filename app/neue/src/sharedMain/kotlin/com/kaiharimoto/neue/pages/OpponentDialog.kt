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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.siding.Matchup
import com.kaiharimoto.mastertool.core.siding.OpponentGuess
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
import com.kaiharimoto.neue.present.SEARCH_DEBOUNCE_MS
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * An opponent made by hand (1.0.42, kai: "let the user add siding patterns to the current
 * deck and create opponent decks by choosing a name and 3 main cards in a card
 * picker/searcher"): a name, and three cards to know the deck by, found in the card pool.
 * Its decklist, when there is one, is linked later with the matchup's Link a decklist. [start]
 * is the matchup being changed, or null for a new one.
 *
 * The name comes first, so it does the searching (1.0.49, kai: "it would be nice if it
 * suggested cards based on the name of the deck"): the cards of the archetypes it spells
 * ([OpponentGuess]) are offered under it as it is typed, and a new opponent added with
 * none picked takes the first three.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpponentDialog(start: Matchup?, state: DeckBuilderState, onDismiss: () -> Unit, typed: String = "", onSave: (String, List<CardId>) -> Unit) {
    val c = Mu.colors
    var name by remember { mutableStateOf(start?.name ?: typed) }
    var covers by remember { mutableStateOf(start?.covers.orEmpty()) }
    var query by remember { mutableStateOf("") }
    // Both searches run off the frame thread a moment after the last key, as the pool's does (1.0.92).
    val index = state.index
    val results by produceState(emptyList<Card>(), query, index) {
        if (query.isBlank()) {
            value = emptyList()
            return@produceState
        }
        delay(SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { index.search(query, limit = 24).cards }
    }
    val full = covers.size >= SidingCodec.COVERS
    val guess = name.trim()
    // What the suggestions were made for, with them: the label names what is shown.
    val suggestion by produceState(Suggested("", emptyList()), guess, index) {
        if (guess.isEmpty()) {
            value = Suggested("", emptyList())
            return@produceState
        }
        delay(SEARCH_DEBOUNCE_MS)
        value = Suggested(guess, withContext(Dispatchers.Default) { OpponentGuess.suggest(guess, index, limit = 12) })
    }
    val suggested = suggestion.cards
    // Nothing picked on a new opponent: the first suggestions stand for it — for the name as it is
    // when Add is pressed, even inside the moment before its suggestions are shown.
    fun taken(now: Boolean): List<CardId> = when {
        covers.isNotEmpty() || start != null -> covers
        !now || suggestion.name == guess -> suggested.take(SidingCodec.COVERS).map { it.id }
        guess.isEmpty() -> emptyList()
        else -> OpponentGuess.suggest(guess, index, limit = 12).take(SidingCodec.COVERS).map { it.id }
    }
    val taken = taken(now = false)
    val width = if (LocalTouchFirst.current) 64.dp else 56.dp
    MuDialog(
        title = if (start == null) "New opponent" else "Edit opponent",
        onDismiss = onDismiss,
        width = 640.dp,
        description = "A name, and three cards to know the deck by: the name suggests them. Link its decklist later, if you get one.",
        footer = {
            MuButton("Cancel", onDismiss, variant = BtnVariant.GHOST)
            MuButton(
                if (start == null) "Add" else "Save",
                { onSave(name.trim(), taken(now = true)) },
                variant = BtnVariant.PRIMARY,
                enabled = name.isNotBlank(),
                reason = "Name the deck first",
            )
        },
    ) {
        FieldLabel("Name")
        MuInput(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "Snake-Eye, Yubel, Ryzeal…")
        if (suggested.isNotEmpty()) {
            FieldLabel("Suggested for “${suggestion.name}”", hint = "click to pick")
            CardChoices(suggested, covers, full, width) { covers = it }
        } else if (name.isNotBlank() && suggestion.name == guess) {
            Small("No archetype in that name: search the pool below.", color = c.ink45)
        }
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
        if (covers.isEmpty() && taken.isNotEmpty()) {
            Small("Nothing picked: Add takes the first three suggestions.", color = c.ink45)
        }
        MuInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Or search the pool for its cards", imeAction = androidx.compose.ui.text.input.ImeAction.Search)
        if (query.isNotBlank() && results.isEmpty()) Small("No card by that name.", color = c.ink45)
        CardChoices(results, covers, full, width) { covers = it }
    }
}

/** Cards to pick from: a click picks one, or puts it back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardChoices(cards: List<Card>, covers: List<CardId>, full: Boolean, width: androidx.compose.ui.unit.Dp, onCovers: (List<CardId>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cards.forEach { card ->
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
                        onCovers(if (chosen) covers - card.id else covers + card.id)
                    },
            ) {
                NeueCard(card, Modifier.fillMaxSize(), foil = "off", selected = chosen, dimmed = !chosen && full)
            }
        }
    }
}

/** An opponent's name and the cards it suggested. */
private class Suggested(val name: String, val cards: List<Card>)
