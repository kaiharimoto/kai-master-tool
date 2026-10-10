package com.kaiharimoto.neue.pages

import com.kaiharimoto.mastertool.core.ydk.YdkDocument
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.collectIsHotAsState
import androidx.compose.foundation.layout.imePadding
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
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.onContextMenu
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
 * `02 Decks`: every saved deck as a ruled list, newest first. The deck on the
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
fun DecksPage(
    deps: AppDependencies,
    state: DeckBuilderState,
    neue: NeueState,
    reload: Int,
    hidden: Set<String> = emptySet(),
    onDuplicated: (String, String) -> Unit = { _, _ -> },
) {
    val c = Mu.colors
    val scope = rememberCoroutineScope()
    var decks by remember { mutableStateOf<List<StoredDeck>?>(null) }
    var filter by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableStateOf(0) }
    // A web's decks are the web's (Format, 1.0.33): the library is your own.
    LaunchedEffect(reload, state.deckId, bump, hidden) {
        decks = deps.deckRepository.all().filter { it.entry.id !in hidden }.sortedByDescending { it.entry.updatedAtEpochMs }
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

    fun duplicate(stored: StoredDeck) {
        scope.launch {
            val id = deps.newDeckId()
            deps.deckRepository.save(id, "${stored.entry.name} copy", stored.entry.deck, stored.extended, stored.entry.notes)
            onDuplicated(stored.entry.id, id)
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
            // The live deck when it is the one on the builder, as the share does.
            DeckExportFormat.QR -> CardActions.showQr(
                if (stored.entry.id == state.deckId) state.deckName else stored.entry.name,
                if (stored.entry.id == state.deckId) state.document() else YdkDocument(deck, extended = stored.extended),
                neue.prefs.covers[stored.entry.id].orEmpty(),
                neue,
            )
        }
    }

    /**
     * Opening a deck replaces the one on the builder, and used to throw its unsaved
     * edits away without a word (touch swarm, rec 5). With auto save off, a deck
     * that has been saved before is saved first; a new one asks.
     */
    var unsavedBeforeOpen by remember { mutableStateOf<String?>(null) }
    fun openDeck(id: String) {
        fun go() {
            state.load(id)
            neue.go(Page.BUILDER)
        }
        when {
            id == state.deckId || !state.dirty || neue.prefs.autoSave -> go()
            state.deckId != null -> state.save(quiet = true) { go() }
            state.deck.totalCards == 0 -> go()
            else -> unsavedBeforeOpen = id
        }
    }

    fun exportMenu(stored: StoredDeck, at: Offset) {
        neue.menu = MenuSpec(
            at,
            DeckExportFormat.entries.map { format ->
                CardActions.exportEntry(format, empty = stored.entry.deck.isEmpty) { export(stored, format) }
            } + CardActions.shareEntries(
                code = { YdkeCodec.encode(if (stored.entry.id == state.deckId) state.deck else stored.entry.deck) },
                name = stored.entry.name,
                file = {
                    // The live deck when it is the one on the builder, else the stored one.
                    if (stored.entry.id == state.deckId) {
                        state.shareDeck()
                    } else {
                        scope.launch {
                            val text = YdkCodec.write(stored.entry.deck, createdBy = "kai's master tool", extended = stored.extended)
                            deps.fileAccess.shareDeck("${stored.entry.name.ifBlank { "deck" }}.${if (stored.extended != null) "ydkx" else "ydk"}", text)
                        }
                    }
                },
            ),
        )
    }

    /**
     * Everything a row can do, in one menu (touch swarm, rec 4): the row's hidden
     * buttons on a desk wait for a hover a finger cannot make, and were still there
     * to be tapped by accident — Delete included. On a tablet the row shows "More"
     * instead, and holding the row opens the same list.
     */
    fun rowMenu(stored: StoredDeck, at: Offset, default: Boolean, open: () -> Unit) {
        neue.menu = MenuSpec(
            at,
            listOf(
                MenuEntry("Open", onClick = open),
                MenuEntry("Duplicate") { duplicate(stored) },
                // Its versions and the games at each (Phase G, G.8).
                MenuEntry("Versions…") { neue.versionsOf = stored.entry.id to stored.entry.name },
                MenuEntry(if (default) "Not default" else "Make default") {
                    val id = stored.entry.id
                    neue.update { it.copy(defaultDeckId = if (it.defaultDeckId == id) null else id) }
                },
                MenuEntry("Choose covers…") { neue.coverPicking = stored },
                MenuEntry("Export…") { exportMenu(stored, at) },
                MenuEntry("✕ Delete", danger = true, separatorBefore = true) { neue.confirmDelete = stored.entry.id to stored.entry.name },
            ),
        )
    }


    Column(Modifier.fillMaxSize()) {
        PageHeader(
            numeral = 1,
            title = "Decks",
            subtitle = decks?.let { "${it.size} saved" } ?: "Loading",
        ) {
            MuInput(filter, { filter = it }, if (LocalPhone.current) Modifier.fillMaxWidth() else Modifier.width(320.dp), placeholder = "A deck, a card in one, or a tag", imeAction = androidx.compose.ui.text.input.ImeAction.Search)
            // On a phone or a tablet Import is a menu: a file, or a deck's QR code (v1.3.7).
            var importAt by remember { mutableStateOf(Offset.Zero) }
            MuButton(
                "Import",
                {
                    if (neue.touchFirst) {
                        neue.menu = MenuSpec(importAt, CardActions.importMenu(state, neue))
                    } else {
                        CardActions.importFile(state, neue)
                    }
                },
                Modifier.onGloballyPositioned { importAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) },
                variant = BtnVariant.SUBTLE,
                size = BtnSize.SM,
                icon = Icons.Import,
            )
            MuButton("New deck", { state.newDeck(); neue.go(Page.BUILDER) }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Plus)
        }
        // Every tag in the library, most used first: one click keeps the decks carrying it.
        if (allTags.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = if (LocalPhone.current) 16.dp else 32.dp).padding(vertical = if (LocalPhone.current) 8.dp else 0.dp).padding(bottom = 12.dp),
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
                LazyColumn(state = list, modifier = Modifier.imePadding()) {
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
                            onOpen = { openDeck(stored.entry.id) },
                            onDelete = { neue.confirmDelete = stored.entry.id to stored.entry.name },
                            onCovers = { neue.coverPicking = stored },
                            onDuplicate = { duplicate(stored) },
                            onVersions = { neue.versionsOf = stored.entry.id to stored.entry.name },
                            onExport = { at -> exportMenu(stored, at) },
                            onMenu = { at ->
                                rowMenu(stored, at, stored.entry.id == neue.prefs.defaultDeckId) { openDeck(stored.entry.id) }
                            },
                        )
                    }
                }
                ScrollbarFor(list)
            }
        }
    }
    neue.coverPicking?.let { stored -> CoverPicker(stored, state, neue) { neue.coverPicking = null } }
    unsavedBeforeOpen?.let { id ->
        MuDialog(
            title = "Keep the unsaved deck?",
            onDismiss = { unsavedBeforeOpen = null },
            description = "“${state.deckName.ifBlank { "Untitled Deck" }}” has never been saved. Opening another deck replaces it on the builder.",
            footer = {
                MuButton("Discard", {
                    unsavedBeforeOpen = null
                    state.load(id)
                    neue.go(Page.BUILDER)
                }, variant = BtnVariant.GHOST)
                MuButton("Save", {
                    unsavedBeforeOpen = null
                    state.save(quiet = true) {
                        state.load(id)
                        neue.go(Page.BUILDER)
                    }
                }, variant = BtnVariant.PRIMARY)
            },
        ) {}
    }
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
        scrolls = false,
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
                            .aspectRatio(CARD_RATIO)
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
    onVersions: () -> Unit,
    onExport: (Offset) -> Unit,
    onMenu: (Offset) -> Unit,
) {
    val c = Mu.colors
    val touch = LocalTouchFirst.current
    // A phone's row (v1.3.5): one cover, the name and its line, and More — no numeral, no gaps to spare.
    val phone = LocalPhone.current
    var rowAt by remember { mutableStateOf(Offset.Zero) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
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
                .onGloballyPositioned { rowAt = it.boundsInWindow().topLeft }
                .hoverable(source)
                .cursorPointer(caption = "Open")
                // A hold, or a right-click on the desk, opens everything the row can do.
                .onContextMenu { local -> onMenu(rowAt + local) }
                .clickable(interactionSource = source, indication = null, onClick = onOpen)
                .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(horizontal = if (phone) 16.dp else 32.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (phone) 12.dp else 16.dp),
        ) {
            if (!phone) Numeral(n, color = if (current) inner.ink.copy(alpha = 0.6f) else c.ink45)
            // Three places, flush like the deck's own mosaic, so every name starts on one line.
            // On a tablet the covers open the deck like the rest of the row; choosing them is in More.
            Row(
                Modifier
                    .width(COVER_W * (if (phone) 1 else DeckCovers.MAX))
                    .then(
                        if (touch) {
                            Modifier
                        } else {
                            Modifier
                                .cursorPointer(caption = "Covers")
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCovers)
                        },
                    ),
            ) {
                if (faces.isEmpty()) Box(Modifier.size(COVER_W, COVER_H).background(inner.ink06))
                faces.take(if (phone) 1 else DeckCovers.MAX).forEach { face -> NeueCard(face, Modifier.size(COVER_W, COVER_H), foil = "off") }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MuText(stored.entry.name, style = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = inner.ink, maxLines = 1)
                if (phone) {
                    Mono(
                        "${deck.main.size} · ${deck.extra.size} · ${deck.side.size} · ${ago(stored.entry.updatedAtEpochMs, now)}",
                        color = if (current) inner.ink.copy(alpha = 0.6f) else c.ink45,
                    )
                    if (current || default) Small(listOfNotNull("On the builder".takeIf { current }, "Opens first".takeIf { default }).joinToString(" · "), color = inner.ink.copy(alpha = 0.7f))
                    if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { tag -> TagChip(tag, tag == tagFilter, null) }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mono(
                        "${deck.main.size} main · ${deck.extra.size} extra · ${deck.side.size} side · ${ago(stored.entry.updatedAtEpochMs, now)}",
                        color = if (current) inner.ink.copy(alpha = 0.6f) else c.ink45,
                    )
                    // A finger filters by tag from the page's own tag strip; here they are words.
                    tags.forEach { tag -> TagChip(tag, tag == tagFilter, if (touch) null else ({ onTag(tag) })) }
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
            if (current && !phone) Small("On the builder", color = inner.ink.copy(alpha = 0.7f))
            if (default && !phone) Small("Opens first", color = inner.ink.copy(alpha = 0.7f))
            // A phone's row has no width for five buttons, a mouse plugged in or not: More holds them.
            if (touch || phone) {
                var moreAt by remember { mutableStateOf(Offset.Zero) }
                Box(Modifier.onGloballyPositioned { moreAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
                    if (phone) IconButton(Icons.More, { onMenu(moreAt) }, size = 40.dp, label = "More")
                    else MuButton("More", { onMenu(moreAt) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.More)
                }
            } else Row(Modifier.alpha(actions), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MuButton("✕ Delete", onDelete, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton(if (default) "Not default" else "Make default", onDefault, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton("Duplicate", onDuplicate, variant = BtnVariant.GHOST, size = BtnSize.SM)
                MuButton("Versions", onVersions, variant = BtnVariant.GHOST, size = BtnSize.SM)
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
private fun TagChip(text: String, on: Boolean, onClick: (() -> Unit)?) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Box(
        Modifier
            .height(18.dp)
            .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
            .border(1.dp, if (on || hovered) c.ink else c.ink25)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .hoverable(source)
                        .cursorPointer(caption = if (on) "Clear" else "Filter")
                        .clickable(interactionSource = source, indication = null, onClick = onClick)
                },
            )
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Micro(text, color = if (on) c.paper else c.ink70)
    }
}

/** One cover in a library row. */
private val COVER_W = 44.dp
private val COVER_H = 64.dp
