package com.kaiharimoto.neue.lounge

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeMatch
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Mu

/**
 * Siding between a match's games (`docs/LOUNGE.md`, round two): the player's deck as they registered it (or last sided
 * it), a card clicked in the Main or Extra Deck going to the Side Deck and one clicked in the Side Deck coming back in —
 * card for card, checked as it goes by the rule kai's computer checks it by (`LoungeMatch.check`). The player who lost
 * the last game also chooses who goes first. The same in a friend's browser and in kai's window.
 */
@Composable
fun SidingStrip(client: LoungeClient, siding: LoungeWire.Siding, cardOf: (Int) -> Card?) {
    val c = Mu.colors
    val kept = remember(siding) { Deck(siding.main.map(::CardId), siding.extra.map(::CardId), siding.side.map(::CardId)) }
    val main = remember(siding) { mutableStateListOf<CardId>().apply { addAll(kept.main) } }
    val extra = remember(siding) { mutableStateListOf<CardId>().apply { addAll(kept.extra) } }
    val side = remember(siding) { mutableStateListOf<CardId>().apply { addAll(kept.side) } }
    var first by remember(siding) { mutableStateOf(true) }
    val isExtra = { id: CardId -> cardOf(id.value)?.isExtraDeck }
    val proposed = Deck(main.toList(), extra.toList(), side.toList())
    val problem = LoungeMatch.check(kept, proposed, isExtra)
    // Out and in, counted against the deck as it came: what a player says aloud at a table.
    val out = (kept.main + kept.extra).groupingBy { it }.eachCount().let { was ->
        val now = (main + extra).groupingBy { it }.eachCount()
        was.entries.sumOf { (id, n) -> (n - (now[id] ?: 0)).coerceAtLeast(0) }
    }
    Column(Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Micro("Side for game ${siding.game}", color = c.ink)
            Mono(if (out == 0) "No changes" else "$out out · $out in", color = c.ink45)
            Box(Modifier.weight(1f))
            MuButton("As it came", { main.clear(); main += kept.main; extra.clear(); extra += kept.extra; side.clear(); side += kept.side },
                size = BtnSize.SM, variant = BtnVariant.GHOST, enabled = out > 0)
        }
        Small("Click a card in the Main or Extra Deck to side it out, and one in the Side Deck to bring it in.", color = c.ink45)
        Pile("Main deck", main, cardOf, "Side out") { i -> side += main.removeAt(i) }
        if (extra.isNotEmpty() || kept.extra.isNotEmpty()) Pile("Extra deck", extra, cardOf, "Side out") { i -> side += extra.removeAt(i) }
        Pile("Side deck", side, cardOf, "Bring in") { i ->
            val id = side.removeAt(i)
            if (isExtra(id) == true) extra += id else main += id
        }
        if (siding.choose) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small("You lost the last game: you choose", color = c.ink)
            Segmented(first, listOf(true, false), { if (it) "Go first" else "Go second" }, { first = it }, small = true)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuButton("Ready for game ${siding.game}", {
                client.ask(LoungeWire.Side(main.map { it.value }, extra.map { it.value }, side.map { it.value }, first.takeIf { siding.choose }))
            }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = problem == null)
            problem?.let { Small(it, color = c.ink) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pile(title: String, ids: List<CardId>, cardOf: (Int) -> Card?, verb: String, onClick: (Int) -> Unit) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(title, color = c.ink)
            Mono("${ids.size}", color = c.ink45)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ids.forEachIndexed { i, id ->
                val card = cardOf(id.value)
                Box(Modifier.size(44.dp, 64.dp).cursorPointer(caption = verb).muClickable { onClick(i) }) {
                    if (card != null) NeueCard(card, Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().border(1.dp, c.ink25)) { Mono("#${id.value}", color = c.ink45) }
                }
            }
        }
    }
}
