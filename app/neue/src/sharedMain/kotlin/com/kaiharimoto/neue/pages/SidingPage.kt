package com.kaiharimoto.neue.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.web.Webs

/**
 * 03 Siding (1.0.40, kai: "3 should be Siding and 4 should be Format"): the siding
 * editor as a page of its own. It sides the deck asked for ([Webs.side]: from Format,
 * a matchup, the editor's own menu), else the deck in the builder (1.0.42, kai: "if the
 * deck has no matchups yet, or isn't a part of a web, let the user add siding patterns to
 * the current deck"). A deck of a web is sided against the web's decks, and its bar
 * leads back to the web on Format; a deck on its own is sided against the opponents it is
 * given here, each a name and three cards, its decklist linked later.
 */
@Composable
internal fun SidingPage(webs: Webs, state: DeckBuilderState, neue: NeueState, reload: Int, onSave: () -> Unit) {
    // Until the webs are read, nothing: a web's deck would flash as a deck on its own.
    if (!webs.loaded) return
    val target = webs.sidingDeckId ?: state.deckId
    if (target == null) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(numeral = 3, title = "Siding", subtitle = state.deckName.ifBlank { "An unsaved deck" })
            EmptyState(
                "Save this deck to side it.",
                "A side plan is kept with its deck, so the deck needs saving first. Then add the decks you expect to face.",
            ) { MuButton("Save the deck", onSave, variant = BtnVariant.PRIMARY, arrow = true) }
        }
        return
    }
    val web = webs.webOf(target)
    if (web != null) {
        SidingHost(webs, web, target, state, neue, reload, onBack = {
            webs.selectedId = web.id
            neue.go(Page.FORMAT)
        })
        return
    }
    // A deck on its own: read from the library (the builder's copy wins where it is the builder's).
    var me by remember(target) { mutableStateOf<StoredDeck?>(null) }
    LaunchedEffect(target, reload, state.dirty) { me = webs.stored(target) }
    val mine = me ?: return
    SidingEditor(webs, null, listOf(mine), mine, state, neue, onBack = null, reload = reload)
}
