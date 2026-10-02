package com.kaiharimoto.neue.present

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.edit.PresentEdits
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/**
 * Cards for a slide (1.0.70): the presentation's deck first, any card by name or by what it
 * says, picked one or many, in the order picked.
 */
@Composable
internal fun CardPicker(h: NeueHolders) {
    val present = h.present
    val target = present.pickingCards ?: return
    val p = present.open ?: return
    val index = h.builder.index
    val c = Mu.colors
    val existing = present.slide?.elements?.firstOrNull { it.id in present.selection && (it.type == Element.CARD || it.type == Element.CARDS) }
    var picked by remember(target) { mutableStateOf(if (target == PickTarget.REPLACE) existing?.cards.orEmpty() else emptyList()) }
    var query by remember { mutableStateOf("") }
    val results: List<Card> = remember(query, index, p.deck) {
        if (query.trim().length < 2) p.deck?.distinct.orEmpty().mapNotNull { index.byId(CardId(it)) }
        else index.search(query, limit = 90).cards
    }
    val single = target == PickTarget.NEW && picked.size <= 1 || existing?.type == Element.CARD && target == PickTarget.REPLACE
    fun done() {
        val slide = present.slide ?: return
        when (target) {
            PickTarget.REPLACE -> if (existing != null) {
                val ids = if (existing.type == Element.CARD) picked.takeLast(1) else picked
                present.commit(PresentEdits.updateElements(p, slide.id, setOf(existing.id)) { it.copy(cards = ids) }, "Cards")
            }
            PickTarget.FOCUS -> slide.deck?.let { f ->
                present.commit(PresentEdits.updateSlide(p, slide.id) { it.copy(deck = f.copy(all = false, cards = (f.cards + picked).distinct())) }, "Focus")
            }
            else -> if (picked.isNotEmpty()) {
                val e = if (picked.size == 1 && target == PickTarget.NEW) {
                    Element(newElementId(), Element.CARD, 760f, 180f, 400f, 720f, cards = picked)
                } else {
                    Element(newElementId(), Element.CARDS, 160f, 260f, 1600f, 620f, cards = picked, cardLabels = true)
                }
                insertElement(h, e)
            }
        }
        present.pickingCards = null
    }
    MuDialog(
        if (target == PickTarget.REPLACE) "Choose the cards" else if (target == PickTarget.NEW) "Add a card" else "Add cards",
        { present.pickingCards = null },
        width = 860.dp,
        scrolls = false,
        description = if (single) "Pick one, or several for a row." else "Pick them in the order they should stand.",
        footer = {
            Small(if (picked.isEmpty()) "None picked" else "${picked.size} picked", color = c.ink70)
            MuButton("Clear", { picked = emptyList() }, variant = BtnVariant.GHOST, enabled = picked.isNotEmpty())
            MuButton(if (target == PickTarget.REPLACE) "Use these" else "Add", ::done, variant = BtnVariant.PRIMARY, enabled = picked.isNotEmpty(), reason = "Pick a card")
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MuInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search by name, or text: what it says")
            if (results.isEmpty()) Help(if (query.isBlank()) "Type a card's name." else "No card matches.")
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().height(420.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id.value }) { card ->
                    val at = picked.indexOf(card.id.value)
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(CARD_RATIO)
                            .border(if (at >= 0) 3.dp else 0.dp, if (at >= 0) c.ink else c.ink12)
                            .cursorPointer(label = card.name)
                            .muClickable {
                                picked = if (at >= 0) picked - card.id.value else picked + card.id.value
                            }
                            .graphicsLayer { alpha = if (at >= 0 || picked.isEmpty()) 1f else 0.8f },
                    ) {
                        NeueCard(card, Modifier.fillMaxSize(), format = h.builder.format, foil = "off", copies = if (at >= 0) at + 1 else 0)
                    }
                }
            }
        }
    }
}
