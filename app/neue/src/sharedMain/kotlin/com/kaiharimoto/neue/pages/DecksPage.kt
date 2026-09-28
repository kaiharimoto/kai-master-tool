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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import com.kaiharimoto.neue.kit.MuDialog
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.library.DeckCovers
import com.kaiharimoto.mastertool.core.library.DeckSearch
import com.kaiharimoto.mastertool.core.library.DeckTags
import com.kaiharimoto.mastertool.core.ydk.DeckExportFormat
import com.kaiharimoto.mastertool.core.ydk.DeckText
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Tag
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.launch
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
 *
 * 1.0.18 (kai: "duplicate decks, export them, a tagging system based on the card
 * type in the deck, and … type the name of a card and it will filter decks by
 * those with the card in it"): each row can be duplicated and exported; each
 * deck carries tags read off its cards (`DeckTags`), which filter the list from
 * a strip under the header; and the search matches a deck's name, the name of
 * any card in it, or a tag (`DeckSearch`) — a row found by its cards says which.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecksPage(deps: AppDependencies, state: DeckBuilderState, neue: NeueState, reload: Int) {
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    var decks by remember { mutableStateOf<List<StoredDeck>?>(null) }
    var filter by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    LaunchedEffect(reload, state.deckId, bump) {
        decks = deps.deckRepository.all().sortedByDescending { it.entry.updatedAtEpochMs }
    }
    // Tags follow the card pool: until it has loaded, a deck's cards have no types.
    val tags = remember(decks, state.index) {
        decks.orEmpty().associate { it.entry.id to DeckTags.of(it.entry.deck, state.index::byId) }
    }
    val allTags = remember(tags) {
        tags.values.flatten().groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val shown = remember(decks, tags, filter, tagFilter, state.index) {
        decks?.mapNotNull { stored ->
            val own = tags[stored.entry.id].orEmpty()
            if (tagFilter != null && tagFilter !in own) return@mapNotNull null
            DeckSearch.match(stored.entry.name, stored.entry.deck, own, filter, state.index::byId)?.let { stored to it }
        }
    }
    // The deck whose covers are being picked (kai, 1.0.15: click the thumbnails).
    var picking by remember { mutableStateOf<StoredDeck?>(null) }

    fun duplicate(stored: StoredDeck) {
        scope.launch {
            val id = deps.newDeckId()
            deps.deckRepository.save(id, "${stored.entry.name} copy", stored.entry.deck, stored.extended, stored.entry.notes)
            neue.prefs.covers[stored.entry.id]?.let { own -> neue.update { it.copy(covers = it.covers + (id to own)) } }
            neue.note = com.kaiharimoto.neue.Note("Duplicated “${stored.entry.name}”")
            bump++
        }
    }

    fun export(stored: StoredDeck, format: DeckExportFormat) {
        val deck = stored.entry.deck
        val name = stored.entry.name.ifBlank { "deck" }
        when (format) {
            DeckExportFormat.YDK, DeckExportFormat.YDKX -> scope.launch {
                val groups = format == DeckExportFormat.YDKX
                val text = YdkCodec.write(deck, createdBy = "kai's master tool", extended = if (groups) stored.extended else null)
                val file = "$name.${if (groups) "ydkx" else "ydk"}"
                if (deps.fileAccess.exportDeck(file, text)) neue.note = com.kaiharimoto.neue.Note("Exported $file")
            }
            DeckExportFormat.YDKE -> {
                CardActions.copy(YdkeCodec.encode(deck))
                neue.note = com.kaiharimoto.neue.Note("YDKe code copied")
            }
            DeckExportFormat.TEXT -> {
                CardActions.copy(DeckText.write(deck) { state.index.byId(it)?.name })
                neue.note = com.kaiharimoto.neue.Note("Decklist copied as text")
            }
        }
    }

    fun exportMenu(stored: StoredDeck, at: Offset) {
        neue.menu = MenuSpec(
            at,
            DeckExportFormat.entries.map { format ->
                MenuEntry(
                    format.label,
                    hint = when (format) {
                        DeckExportFormat.YDK -> ".ydk"
                        DeckExportFormat.YDKX -> ".ydkx"
                        else -> "Copies"
                    },
                    separatorBefore = format == DeckExportFormat.YDKE,
                ) { export(stored, format) }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            numeral = 1,
            title = "Decks",
            subtitle = decks?.let { "${it.size} saved" } ?: "Loading",
        ) {
            MuInput(filter, { filter = it }, Modifier.width(320.dp), placeholder = "A deck, a card in one, or a tag")
            MuButton("Import", { state.importFromFile(); neue.go(Page.BUILDER) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Import)
            MuButton("New deck", { state.newDeck(); neue.go(Page.BUILDER) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Plus)
        }
        // Every tag in the library, most used first: one click keeps the decks carrying it.
        if (allTags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Tag("All", tagFilter == null, { tagFilter = null }, caption = "Every deck")
                allTags.forEach { (tag, n) ->
                    Tag(tag, tagFilter == tag, { tagFilter = if (tagFilter == tag) null else tag }, count = "$n", caption = if (tagFilter == tag) "Clear" else "Filter")
                }
            }
        }
        when {
            shown == null -> Unit
            decks?.isEmpty() == true -> EmptyState(
                "Nothing yet.",
                "Build a deck and save it, or import a .ydk file, and it is kept here.",
            ) { MuButton("New deck", { state.newDeck(); neue.go(Page.BUILDER) }, variant = BtnVariant.PRIMARY, arrow = true) }
            shown.isEmpty() -> EmptyState("No matches.", "No saved deck has that in its name, in its cards or in its tags.")
            else -> Box(Modifier.fillMaxSize()) {
                val list = rememberLazyListState()
                val now = remember(decks) { System.currentTimeMillis() }
                LazyColumn(state = list) {
                    itemsIndexed(shown, key = { _, d -> d.first.entry.id }) { i, (stored, match) ->
                        DeckRow(
                            n = i + 1,
                            stored = stored,
                            state = state,
                            current = stored.entry.id == state.deckId,
                            default = stored.entry.id == neue.prefs.defaultDeckId,
                            covers = neue.prefs.covers[stored.entry.id].orEmpty(),
                            now = now,
                            tags = tags[stored.entry.id].orEmpty(),
                            tagFilter = tagFilter,
                            matched = match.cards.takeIf { filter.isNotBlank() }.orEmpty(),
                            onTag = { tag -> tagFilter = if (tagFilter == tag) null else tag },
                            onDefault = {
                                val id = stored.entry.id
                                neue.update { it.copy(defaultDeckId = if (it.defaultDeckId == id) null else id) }
                            },
                            onOpen = {
                                state.load(stored.entry.id)
                                neue.go(Page.BUILDER)
                            },
                            onDelete = { neue.confirmDelete = stored.entry.id to stored.entry.name },
                            onCovers = { picking = stored },
                            onDuplicate = { duplicate(stored) },
                            onExport = { at -> exportMenu(stored, at) },
                        )
                    }
                }
                ScrollbarFor(list)
            }
        }
    }
    picking?.let { stored -> CoverPicker(stored, state, neue) { picking = null } }
}

/**
 * Picking a deck's covers (kai, 1.0.15: "select the 3 main cards … using a card
 * picker by clicking on the thumbnails"): every card in the deck once, main then
 * extra then side; a click puts it on the cover or takes it off, numbered in the
 * order it will stand. A fourth lets go of the first (`DeckCovers.toggle`).
 */
@Composable
private fun CoverPicker(stored: StoredDeck, state: DeckBuilderState, neue: NeueState, onDismiss: () -> Unit) {
    val c = Mu.colors
    val id = stored.entry.id
    val deck = stored.entry.deck
    val cards = remember(deck) { (deck.main + deck.extra + deck.side).distinct() }
    val chosen = neue.prefs.covers[id].orEmpty()
    MuDialog(
        title = "Covers for “${stored.entry.name}”",
        onDismiss = onDismiss,
        width = 880.dp,
        description = "Pick up to three, in the order they should stand. With none, the deck shows its most-played card.",
        footer = {
            MuButton("Clear", { neue.update { it.copy(covers = it.covers - id) } }, variant = BtnVariant.GHOST, enabled = chosen.isNotEmpty(), reason = "No covers picked")
            MuButton("Done", onDismiss, variant = BtnVariant.PRIMARY)
        },
    ) {
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        Box(Modifier.fillMaxWidth().height(460.dp)) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(96.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(cards.size) { i ->
                    val raw = cards[i]
                    val card = state.index.byId(raw)
                    val order = chosen.indexOf(raw.value)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(com.kaiharimoto.neue.cards.CARD_RATIO)
                            .cursorPointer(caption = if (order >= 0) "Take off" else "Cover")
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                neue.update { p -> p.copy(covers = p.covers + (id to DeckCovers.toggle(p.covers[id].orEmpty(), raw.value))) }
                            },
                    ) {
                        if (card != null) NeueCard(card, Modifier.fillMaxSize(), foil = "off", selected = order >= 0)
                        else Box(Modifier.fillMaxSize().background(c.ink06))
                        if (order >= 0) {
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp).background(c.ink).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Mono("${order + 1}", color = c.paper)
                            }
                        }
                    }
                }
            }
            ScrollbarFor(grid)
        }
    }
}

@Composable
private fun DeckRow(
    n: Int,
    stored: StoredDeck,
    state: DeckBuilderState,
    current: Boolean,
    default: Boolean,
    covers: List<Int>,
    now: Long,
    tags: List<String>,
    tagFilter: String?,
    /** The names of the cards the search found in this deck, when it found any. */
    matched: List<String>,
    onTag: (String) -> Unit,
    onDefault: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onCovers: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: (Offset) -> Unit,
) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val deck = stored.entry.deck
    // The deck's faces: up to three cards chosen for it, else its most-played main-deck
    // card (DeckCovers). Pictures, so they keep their colour (§17).
    val faces = remember(deck, covers, state.index) { DeckCovers.shown(covers, deck).mapNotNull(state.index::byId) }
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
            // Three places, flush like the deck's own mosaic, so every name starts on one line.
            Row(
                Modifier
                    .width(COVER_W * DeckCovers.MAX)
                    .cursorPointer(caption = "Covers")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCovers),
            ) {
                if (faces.isEmpty()) Box(Modifier.size(COVER_W, COVER_H).background(inner.ink06))
                faces.forEach { face -> NeueCard(face, Modifier.size(COVER_W, COVER_H), foil = "off") }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MuText(stored.entry.name, style = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = inner.ink, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mono(
                        "${deck.main.size} main · ${deck.extra.size} extra · ${deck.side.size} side · ${ago(stored.entry.updatedAtEpochMs, now)}",
                        color = if (current) inner.ink.copy(alpha = 0.6f) else c.ink45,
                    )
                    tags.forEach { tag -> TagChip(tag, tag == tagFilter) { onTag(tag) } }
                }
                // Found by a card in it rather than by its name: which ones.
                if (matched.isNotEmpty()) {
                    Small(
                        "With " + matched.take(3).joinToString(", ") + if (matched.size > 3) " and ${matched.size - 3} more" else "",
                        color = inner.ink.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
            if (current) Small("On the builder", color = inner.ink.copy(alpha = 0.7f))
            if (default) Small("Opens first", color = inner.ink.copy(alpha = 0.7f))
            Row(Modifier.alpha(actions), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("✕ Delete", onDelete, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton(if (default) "Not default" else "Make default", onDefault, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton("Duplicate", onDuplicate, variant = BtnVariant.GHOST, size = BtnSize.SM)
                var exportAt by remember { mutableStateOf(Offset.Zero) }
                Box(Modifier.onGloballyPositioned { exportAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
                    MuButton("Export", { onExport(exportAt) }, variant = BtnVariant.GHOST, size = BtnSize.SM, icon = Icons.Export)
                }
                MuButton("Open", onOpen, variant = BtnVariant.SUBTLE, size = BtnSize.SM, arrow = true)
            }
        }
    }
}

/** A deck's tag in its row: a small ruled word, inverted while it is the filter. */
@Composable
private fun TagChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .height(18.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .border(1.dp, if (on || hovered) c.ink else c.ink25)
            .hoverable(source)
            .cursorPointer(caption = if (on) "Clear" else "Filter")
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        com.kaiharimoto.neue.kit.Micro(text, color = if (on) c.paper else c.ink70)
    }
}

/** One cover in a library row. */
private val COVER_W = 44.dp
private val COVER_H = 64.dp
