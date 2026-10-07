package com.kaiharimoto.guest

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeDecks
import com.kaiharimoto.mastertool.core.duel.lounge.LoungeWire
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.search.CardFilter
import com.kaiharimoto.mastertool.core.search.CardIndex
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.neue.builder.FilterPanel
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.lounge.LegalMark
import com.kaiharimoto.neue.lounge.LoungeClient
import kotlinx.coroutines.delay
import com.kaiharimoto.neue.theme.Mu

/**
 * A friend's decks, kept on kai's computer so they follow them to any browser (`docs/LOUNGE.md`): brought as a `.ydk`
 * or `.ydkx` file or a `ydke://` code, built or changed here with the same search Neue's builder uses, and saved back.
 */
@Composable
fun DecksPage(client: LoungeClient, cards: CardIndex, modifier: Modifier = Modifier) {
    val c = Mu.colors
    LaunchedEffect(Unit) { client.ask(LoungeWire.Decks) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    // A deck asked for (to edit or to save as a file) arrives as Deck; so does one just saved, which opens nothing.
    var exportNext by remember { mutableStateOf<String?>(null) }
    var editNext by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(client.openDeck) {
        val d = client.openDeck ?: return@LaunchedEffect
        client.openDeck = null
        if (exportNext == d.id) {
            exportNext = null
            saveText("${d.name}.ydk", d.text)
        } else if (editNext == d.id) {
            editNext = null
            val deck = LoungeDecks.read(d.text) ?: return@LaunchedEffect
            editing = Editing(d.id, d.name, deck)
        }
    }
    val e = editing
    if (e != null) {
        DeckEditor(e, cards, client, onSave = { name, deck ->
            client.ask(LoungeWire.DeckSave(e.id, name, YdkCodec.write(YdkDocument(deck))))
            editing = null
        }, onClose = { editing = null }, modifier = modifier)
        return
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Micro("Your decks", color = c.ink)
        Small("Kept on kai's computer: they are here from any browser you join from." +
            (client.rules.takeIf { it.isNotEmpty() }?.let { " ✓ is a deck legal in $it, kai's rules." } ?: ""), color = c.ink45)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuButton("Upload .ydk", {
                chooseText(".ydk,.ydkx,.txt") { name, text -> client.ask(LoungeWire.DeckSave(null, name.substringBeforeLast('.'), text)) }
            }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            MuButton("New deck", { editing = Editing(null, "New deck", Deck(emptyList(), emptyList(), emptyList())) }, size = BtnSize.SM)
        }
        var code by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MuInput(code, { code = it }, Modifier.widthIn(max = 420.dp).weight(1f, fill = false), placeholder = "Paste a ydke:// code", mono = true, dense = true)
            MuButton("Add", {
                if (code.isNotBlank()) { client.ask(LoungeWire.DeckSave(null, "Pasted deck", code.trim())); code = "" }
            }, size = BtnSize.SM, enabled = code.startsWith("ydke://"))
        }
        HRule()
        if (client.decks.isEmpty()) Small("No decks yet.", color = c.ink45)
        client.decks.forEach { d ->
            Row(
                Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Small(d.name, color = c.ink, maxLines = 1)
                    d.issues.firstOrNull()?.let { Small(it, color = c.ink45, maxLines = 1) }
                }
                LegalMark(d, client.rules)
                Mono("${d.main} · ${d.extra} · ${d.side}", color = c.ink45)
                MuButton("Edit", { editNext = d.id; client.ask(LoungeWire.DeckGet(d.id)) }, size = BtnSize.SM)
                MuButton("Save as file", { exportNext = d.id; client.ask(LoungeWire.DeckGet(d.id)) }, size = BtnSize.SM, variant = BtnVariant.SUBTLE)
                MuButton("Delete", { client.ask(LoungeWire.DeckDelete(d.id)) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
        }
    }
}

/** A deck being built: its id when it is one already kept, its name, its cards. */
private class Editing(val id: String?, val name: String, val deck: Deck)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckEditor(e: Editing, cards: CardIndex, client: LoungeClient, onSave: (String, Deck) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val c = Mu.colors
    var name by remember { mutableStateOf(e.name) }
    val main = remember { mutableStateListOf<CardId>().apply { addAll(e.deck.main) } }
    val extra = remember { mutableStateListOf<CardId>().apply { addAll(e.deck.extra) } }
    val side = remember { mutableStateListOf<CardId>().apply { addAll(e.deck.side) } }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(CardFilter.NONE) }
    var filters by remember { mutableStateOf(false) }
    var toSide by remember { mutableStateOf(false) }
    // A name, words, or the filters alone: browsing by a facet needs no query.
    val browsing = query.length >= 2 || filter.activeFacetCount > 0
    val found = remember(query, filter, cards) { if (!browsing) emptyList() else cards.search(query.takeIf { it.length >= 2 }.orEmpty(), filter, limit = 120).cards }
    fun copies(id: CardId) = (main + extra + side).count { it == id }
    // The deck checked against kai's rules as it is built, a moment after each change.
    val deckNow = Deck(main.toList(), extra.toList(), side.toList())
    LaunchedEffect(deckNow) {
        delay(400)
        client.ask(LoungeWire.Check(YdkCodec.write(YdkDocument(deckNow))))
    }
    Row(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuInput(name, { name = it.take(60) }, Modifier.widthIn(max = 360.dp).weight(1f, fill = false), dense = true)
                MuButton("Save", { onSave(name, Deck(main.toList(), extra.toList(), side.toList())) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = main.isNotEmpty())
                MuButton("Cancel", onClose, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            client.checked?.takeIf { client.rules.isNotEmpty() }?.let { ch ->
                if (ch.issues.isEmpty()) Small("✓ Legal in ${client.rules}", color = c.ink45)
                else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Small("✕ Not legal in ${client.rules}:", color = c.ink)
                    ch.issues.take(6).forEach { Small("· $it", color = c.ink) }
                    if (ch.issues.size > 6) Small("and ${ch.issues.size - 6} more", color = c.ink45)
                }
            }
            Small("Click a card here to take one copy out; right-click moves it between the decks and the Side Deck.", color = c.ink45)
            Section("Main deck", main, cards) { i -> side += main.removeAt(i) }
            Section("Extra deck", extra, cards) { i -> side += extra.removeAt(i) }
            Section("Side deck", side, cards) { i ->
                val id = side.removeAt(i)
                if (cards.byId(id)?.isExtraDeck == true) extra += id else main += id
            }
        }
        Column(Modifier.widthIn(min = 320.dp, max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Find cards")
            MuInput(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "A name, or text: words it says", dense = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton(if (filter.activeFacetCount > 0) "Filters · ${filter.activeFacetCount}" else "Filters", { filters = !filters }, size = BtnSize.SM, toggled = filters)
                if (filter.activeFacetCount > 0) MuButton("Clear", { filter = CardFilter.NONE }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            if (filters) FilterPanel(filter, { filter = it }, cards, Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Small("Adds to", color = c.ink45)
                MuButton("Main / Extra", { toSide = false }, size = BtnSize.SM, toggled = !toSide)
                MuButton("Side", { toSide = true }, size = BtnSize.SM, toggled = toSide)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                found.forEach { card ->
                    val full = copies(card.id) >= 3
                    Box(Modifier.size(66.dp, 96.dp).cursorPointer(caption = if (full) "Three already" else "Add").muClickable {
                        if (full) return@muClickable
                        when {
                            toSide -> side += card.id
                            card.isExtraDeck -> extra += card.id
                            else -> main += card.id
                        }
                    }) { NeueCard(card, Modifier.fillMaxSize(), dimmed = full) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, ids: MutableList<CardId>, cards: CardIndex, onMove: (Int) -> Unit) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Micro(title, color = c.ink)
            Mono("${ids.size}", color = c.ink45)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ids.forEachIndexed { i, id ->
                val card: Card? = cards.byId(id)
                Box(Modifier.size(54.dp, 79.dp).cursorPointer(caption = "Take out").onContextMenu { onMove(i) }.muClickable { ids.removeAt(i) }) {
                    if (card != null) NeueCard(card, Modifier.fillMaxSize()) else Box(Modifier.fillMaxSize().border(1.dp, c.ink25)) { Mono("#${id.value}", color = c.ink45) }
                }
            }
        }
    }
}
