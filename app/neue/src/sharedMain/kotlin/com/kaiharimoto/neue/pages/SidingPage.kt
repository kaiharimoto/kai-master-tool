package com.kaiharimoto.neue.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Page
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.web.Webs

/**
 * 03 Siding (1.0.40, kai: "3 should be Siding and 4 should be Format"): the siding
 * editor, which stood inside Format from 1.0.35, as a page of its own. It sides a deck
 * of yours in the web open on Format — the one last asked for ([Webs.side]), else the
 * first you star — and its bar's way back leads to that web on Format, where the webs
 * are chosen and their decks starred.
 */
@Composable
internal fun SidingPage(webs: Webs, state: DeckBuilderState, neue: NeueState, reload: Int) {
    val web = webs.selected
    val deckId = webs.sidingDeckId?.takeIf { web?.has(it) == true } ?: web?.mine?.firstOrNull()?.deckId
    if (web != null && deckId != null) {
        SidingHost(webs, web, deckId, state, neue, reload, onBack = { neue.go(Page.FORMAT) })
        return
    }
    // Until the webs are read, nothing: "no webs yet" would flash before them.
    if (!webs.loaded) return
    Column(Modifier.fillMaxSize()) {
        PageHeader(numeral = 3, title = "Siding", subtitle = web?.name ?: "No webs yet")
        EmptyState(
            if (web == null) "No webs yet." else "No deck of yours in “${web.name.ifBlank { "this web" }}”.",
            if (web == null) {
                "Siding is planned against a web of decks: the field you expect at an event. Start one on Format and import its decks."
            } else {
                "Star the decks you play in the web on Format, then side them here against the rest of the field."
            },
        ) { MuButton("Go to Format", { neue.go(Page.FORMAT) }, variant = BtnVariant.PRIMARY, arrow = true) }
    }
}
