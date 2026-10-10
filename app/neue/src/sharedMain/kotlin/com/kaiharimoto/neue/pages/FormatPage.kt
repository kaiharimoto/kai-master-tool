package com.kaiharimoto.neue.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.data.StoredDeck
import com.kaiharimoto.mastertool.core.library.DeckCovers
import com.kaiharimoto.mastertool.core.web.DeckWeb
import com.kaiharimoto.mastertool.core.web.WebCodec
import com.kaiharimoto.mastertool.core.web.WebEntry
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.JvmZlib
import com.kaiharimoto.mastertool.core.ydk.YdkCodec
import com.kaiharimoto.mastertool.ui.AppDependencies
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.builder.CardActions
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tag
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.QrSource
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.web.Webs
import kotlinx.coroutines.launch

/**
 * `05 Format` (kai, 1.0.33: "format web — expected decks at a tournament to play
 * against… a reason someone would use this program"): the webs of decks. A web is
 * the field you expect at an event — the decks you will face, and yours among them,
 * starred — with notes about the room and, where you know it, each deck's share.
 *
 * Its decks are started by importing (a file, or on a phone or tablet a deck's QR
 * code) or copying in from the library, and are fully editable: a tile opens its
 * deck in the builder, whose bar then steps through the web (‹ ›, `Alt ←`/`Alt →`).
 * A web goes out as one `.ydkw` file ([WebCodec]) and comes back in the same way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormatPage(
    deps: AppDependencies,
    webs: Webs,
    state: DeckBuilderState,
    neue: NeueState,
    reload: Int,
    onOpenDeck: (String) -> Unit,
) {
    val c = Mu.colors
    val phone = LocalPhone.current
    val scope = rememberCoroutineScope()
    val web = webs.selected
    var confirmDeleteWeb by remember { mutableStateOf<DeckWeb?>(null) }
    var confirmRemove by remember { mutableStateOf<Pair<DeckWeb, StoredDeck>?>(null) }

    /** A `.ydkw` from the file picker: a new web. A deck file picked here lands in the open web instead. */
    fun openFile(into: DeckWeb?) {
        scope.launch {
            val file = deps.fileAccess.importDeck() ?: return@launch
            if (WebCodec.isWeb(file.content)) {
                webs.open(file.content) { made -> neue.note = Note(made?.let { "Opened “${it.name}”: ${it.entries.size} decks" } ?: "That file holds no web") }
            } else if (into != null) {
                val parsed = YdkCodec.parse(file.content)
                webs.add(into.id, file.name.substringBeforeLast('.'), parsed.document) { neue.note = Note("Added “${file.name.substringBeforeLast('.')}” to ${into.name}") }
            } else {
                neue.note = Note("That is a deck, not a web: open a web first, then Import deck")
            }
        }
    }

    /** A deck's QR code, into the web (a phone or a tablet): the whole deck, its groups and name with it. */
    fun scanInto(into: DeckWeb, from: QrSource) {
        scope.launch {
            val text = CardActions.readCode(from, neue) ?: return@launch
            val read = DeckCodes.read(text, JvmZlib)
            if (read == null) {
                neue.note = Note("That code holds no deck")
            } else {
                val name = read.name ?: "Scanned deck"
                webs.add(into.id, name, read.parsed.document) { neue.note = Note("Added “$name” to ${into.name}") }
            }
        }
    }

    fun importMenu(into: DeckWeb, at: Offset) {
        val sources = Platform.scanSources
        neue.menu = MenuSpec(
            at,
            buildList {
                add(MenuEntry("A .ydk or .ydkx file") { openFile(into) })
                if (sources.isNotEmpty()) {
                    add(MenuEntry("Scan a QR code", hint = "Camera", separatorBefore = true, enabled = QrSource.CAMERA in sources, reason = "No camera") { scanInto(into, QrSource.CAMERA) })
                    add(MenuEntry("A picture of a QR code") { scanInto(into, QrSource.PICTURE) })
                }
            },
        )
    }

    fun libraryMenu(into: DeckWeb, at: Offset) {
        scope.launch {
            val owned = webs.library.deckIds
            val decks = deps.deckRepository.all().filter { it.entry.id !in owned }.sortedByDescending { it.entry.updatedAtEpochMs }
            neue.menu = MenuSpec(
                at,
                if (decks.isEmpty()) {
                    listOf(MenuEntry("Your library has no decks yet", enabled = false))
                } else {
                    listOf(MenuEntry("A copy comes into the web; the library keeps its own", enabled = false)) +
                        decks.take(30).mapIndexed { i, stored ->
                            MenuEntry(stored.entry.name, hint = "${stored.entry.deck.main.size}", separatorBefore = i == 0) {
                                webs.addFromLibrary(into.id, stored) { neue.note = Note("Copied “${stored.entry.name}” into ${into.name}") }
                            }
                        }
                },
            )
        }
    }

    fun exportWeb(target: DeckWeb, share: Boolean) {
        scope.launch {
            val text = webs.fileText(target)
            val name = "${target.name.ifBlank { "web" }}.ydkw"
            if (share) {
                deps.fileAccess.shareDeck(name, text)
            } else if (deps.fileAccess.exportDeck(name, text)) {
                neue.note = Note("Exported $name")
            }
        }
    }

    fun exportMenu(target: DeckWeb, at: Offset) {
        if (!Platform.canShare) {
            exportWeb(target, share = false)
            return
        }
        neue.menu = MenuSpec(
            at,
            listOf(
                MenuEntry("Save the .ydkw file") { exportWeb(target, share = false) },
                MenuEntry("Share the .ydkw file…") { exportWeb(target, share = true) },
            ),
        )
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            numeral = 4,
            title = "Format",
            subtitle = web?.let { w -> "${w.name} · ${w.entries.size} ${if (w.entries.size == 1) "deck" else "decks"} · ${w.mine.size} yours" }
                ?: "${webs.library.webs.size} webs",
        ) {
            MuButton("New web", { webs.create() }, variant = BtnVariant.SECONDARY, size = BtnSize.SM, icon = Icons.Plus)
            MuButton("Open a .ydkw", { openFile(null) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.Import)
        }
        if (webs.loaded && webs.library.webs.isEmpty()) {
            EmptyState(
                "No webs yet.",
                "A web is the field you expect at an event: the decks you will face, and yours among them. Start one, then import the decks.",
            ) { MuButton("New web", { webs.create("Spring Regional") }, variant = BtnVariant.PRIMARY, arrow = true) }
            return@Column
        }
        if (phone) {
            // A phone: the webs as chips over the page, not a column beside it.
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                webs.library.webs.forEach { w ->
                    Tag(w.name, w.id == web?.id, { webs.selectedId = w.id }, count = "${w.entries.size}", caption = "Open")
                }
            }
            if (web != null) WebBody(deps, webs, web, state, neue, reload, onOpenDeck, { openFile(it) }, ::importMenu, ::libraryMenu, ::exportMenu, { confirmDeleteWeb = it }, { w, s -> confirmRemove = w to s })
        } else {
            Row(Modifier.fillMaxSize()) {
                WebList(webs, web, Modifier.width(248.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
                if (web != null) {
                    WebBody(deps, webs, web, state, neue, reload, onOpenDeck, { openFile(it) }, ::importMenu, ::libraryMenu, ::exportMenu, { confirmDeleteWeb = it }, { w, s -> confirmRemove = w to s })
                }
            }
        }
    }

    confirmDeleteWeb?.let { target ->
        MuDialog(
            title = "Delete “${target.name}”",
            onDismiss = { confirmDeleteWeb = null },
            width = 420.dp,
            description = "The web and its ${target.entries.size} decks will be removed from this ${if (neue.touchFirst) "device" else "computer"}. Export it as a .ydkw first to keep a copy. This cannot be undone.",
            footer = {
                MuButton("Cancel", { confirmDeleteWeb = null }, variant = BtnVariant.GHOST)
                MuButton("Delete", {
                    confirmDeleteWeb = null
                    webs.delete(target.id) { neue.note = Note("Deleted “${target.name}”") }
                }, variant = BtnVariant.PRIMARY)
            },
        ) {}
    }
    confirmRemove?.let { (target, stored) ->
        MuDialog(
            title = "Remove “${stored.entry.name}”",
            onDismiss = { confirmRemove = null },
            width = 420.dp,
            description = "The deck leaves ${target.name} and is deleted: a web's decks are nowhere else. Copy it to your library first to keep it. This cannot be undone.",
            footer = {
                MuButton("Cancel", { confirmRemove = null }, variant = BtnVariant.GHOST)
                MuButton("Remove", {
                    confirmRemove = null
                    webs.remove(target.id, stored.entry.id) { neue.note = Note("Removed “${stored.entry.name}”") }
                }, variant = BtnVariant.PRIMARY)
            },
        ) {}
    }
}

/** The webs down the left: the open one inverted, each with its count of decks. */
@Composable
private fun WebList(webs: Webs, open: DeckWeb?, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Micro("Webs", Modifier.padding(start = 8.dp, bottom = 8.dp), color = c.ink70)
        webs.library.webs.forEach { w ->
            val on = w.id == open?.id
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHotAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(animatedColor(if (on) c.ink else if (hovered) c.ink06 else Color.Transparent))
                    .hoverable(source)
                    .cursorPointer(caption = if (on) null else "Open")
                    .clickable(interactionSource = source, indication = null) { webs.selectedId = w.id }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuText(w.name.ifBlank { "Untitled web" }, Modifier.weight(1f), style = MuType.body(LocalMuFonts.current).copy(fontSize = 14.sp), color = if (on) c.paper else c.ink, maxLines = 1)
                Mono("${w.entries.size}", color = if (on) c.paper else c.ink45)
            }
        }
    }
}

/** The open web: its name and notes, what can be done to it, and the field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebBody(
    deps: AppDependencies,
    webs: Webs,
    web: DeckWeb,
    state: DeckBuilderState,
    neue: NeueState,
    reload: Int,
    onOpenDeck: (String) -> Unit,
    importFile: (DeckWeb) -> Unit,
    importMenu: (DeckWeb, Offset) -> Unit,
    libraryMenu: (DeckWeb, Offset) -> Unit,
    exportMenu: (DeckWeb, Offset) -> Unit,
    onDelete: (DeckWeb) -> Unit,
    onRemove: (DeckWeb, StoredDeck) -> Unit,
) {
    val c = Mu.colors
    val phone = LocalPhone.current
    var decks by remember(web.id) { mutableStateOf<List<StoredDeck>?>(null) }
    LaunchedEffect(web.id, web.deckIds, webs.revision, reload, state.deckId) { decks = webs.decks(web) }
    val gutter = if (phone) 16.dp else 32.dp

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WebNameField(web.name, { webs.rename(web.id, it) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var importAt by remember { mutableStateOf(Offset.Zero) }
                MuButton(
                    "Import deck",
                    { if (neue.touchFirst) importMenu(web, importAt) else importFile(web) },
                    Modifier.onGloballyPositioned { importAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) },
                    variant = BtnVariant.SUBTLE,
                    size = BtnSize.SM,
                    icon = Icons.Import,
                )
                var libraryAt by remember { mutableStateOf(Offset.Zero) }
                MuButton(
                    "Add from library",
                    { libraryMenu(web, libraryAt) },
                    Modifier.onGloballyPositioned { libraryAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) },
                    variant = BtnVariant.SUBTLE,
                    size = BtnSize.SM,
                    icon = Icons.Plus,
                )
                var exportAt by remember { mutableStateOf(Offset.Zero) }
                MuButton(
                    "Export .ydkw",
                    { exportMenu(web, exportAt) },
                    Modifier.onGloballyPositioned { exportAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) },
                    variant = BtnVariant.PRIMARY,
                    size = BtnSize.SM,
                    icon = Icons.Export,
                    enabled = web.entries.isNotEmpty(),
                    reason = "The web has no decks",
                )
                MuButton("✕ Delete web", { onDelete(web) }, variant = BtnVariant.GHOST, size = BtnSize.SM)
            }
            NotesField(web.notes, { webs.setNotes(web.id, it) }, placeholder = "Notes about the room: what is popular, what people side, what to expect.")
        }
        val view = when {
            webs.showMatchups -> WebView.MATCHUPS
            webs.showEvent -> WebView.EVENT
            else -> WebView.FIELD
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(view, WebView.entries, { it.title }, { webs.showMatchups = it == WebView.MATCHUPS; webs.showEvent = it == WebView.EVENT }, small = true)
            if (!phone) {
                Small(
                    when {
                        view == WebView.MATCHUPS -> "How each of your decks sides against the field."
                        view == WebView.EVENT -> "Where the event is won or lost: your match win against each deck, and what each costs you."
                        web.totalShare > 0 -> "Shares written down add up to ${web.totalShare}%."
                        else -> "Star the decks you play; click one to open it in the builder."
                    },
                    color = c.ink45,
                )
            }
        }
        val list = decks
        when {
            list == null -> Unit
            view == WebView.MATCHUPS -> MatchupTable(webs, web, list, state, neue, Modifier.fillMaxSize().padding(horizontal = gutter))
            view == WebView.EVENT -> EventTable(web, list, state, neue, webs, Modifier.fillMaxSize().padding(horizontal = gutter))
            list.isEmpty() -> EmptyState(
                "No decks in this web yet.",
                "Import the decks you expect to face, or copy yours in from the library. Each is a deck of its own, editable in the builder.",
            )
            else -> Box(Modifier.fillMaxSize()) {
                val grid = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (phone) 160.dp else 208.dp),
                    state = grid,
                    modifier = Modifier.fillMaxSize().padding(horizontal = gutter),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.entry.id }) { stored ->
                        val entry = web.entry(stored.entry.id) ?: WebEntry(stored.entry.id)
                        FieldTile(
                            stored = stored,
                            entry = entry,
                            state = state,
                            neue = neue,
                            current = stored.entry.id == state.deckId,
                            onOpen = { onOpenDeck(stored.entry.id) },
                            onStar = { webs.star(web.id, stored.entry.id, !entry.mine) },
                            onMenu = { at -> tileMenu(web, stored, entry, at, webs, neue, onOpenDeck, onRemove) },
                        )
                    }
                }
                ScrollbarFor(grid)
            }
        }
    }
}

/** Everything a deck in the web can do, one menu: a hold, a right-click or More. */
private fun tileMenu(
    web: DeckWeb,
    stored: StoredDeck,
    entry: WebEntry,
    at: Offset,
    webs: Webs,
    neue: NeueState,
    onOpenDeck: (String) -> Unit,
    onRemove: (DeckWeb, StoredDeck) -> Unit,
) {
    val id = stored.entry.id
    val position = web.position(id) ?: 1
    neue.menu = MenuSpec(
        at,
        listOf(
            MenuEntry("Open in the builder") { onOpenDeck(id) },
            MenuEntry("Side this deck", hint = "Matchups") { webs.side(id) },
            MenuEntry(if (entry.mine) "Not one of mine" else "★ One of mine") { webs.star(web.id, id, !entry.mine) },
            MenuEntry("Share of the field…", hint = entry.share?.let { "$it%" } ?: "—") {
                neue.menu = MenuSpec(
                    at,
                    listOf(MenuEntry("Unknown", hint = if (entry.share == null) "✓" else null) { webs.share(web.id, id, null) }) +
                        SHARES.map { s -> MenuEntry("$s%", hint = if (entry.share == s) "✓" else null) { webs.share(web.id, id, s, WebEntry.SOURCE_HAND) } },
                )
            },
            MenuEntry("Earlier in the web", enabled = position > 1, separatorBefore = true) { webs.move(web.id, id, position - 2) },
            MenuEntry("Later in the web", enabled = position < web.entries.size) { webs.move(web.id, id, position) },
            MenuEntry("Copy to my library", separatorBefore = true) {
                webs.copyToLibrary(stored) { neue.note = Note("“${stored.entry.name}” copied to your library") }
            },
            MenuEntry("✕ Remove from the web", danger = true, separatorBefore = true) { onRemove(web, stored) },
        ),
    )
}

/** What the web's page shows under its notes: the decks, or how yours side against them. */
private enum class WebView(val title: String) { FIELD("The field"), EVENT("The event"), MATCHUPS("Matchups") }

/** The shares offered for a deck, in percent of the field. */
private val SHARES = listOf(5, 10, 15, 20, 25, 30, 35, 40, 50)

/** A deck in the field: its covers, whether it is yours, its name, counts and share. */
@Composable
private fun FieldTile(
    stored: StoredDeck,
    entry: WebEntry,
    state: DeckBuilderState,
    neue: NeueState,
    current: Boolean,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    onMenu: (Offset) -> Unit,
) {
    val c = Mu.colors
    val touch = LocalTouchFirst.current
    val deck = stored.entry.deck
    val covers = neue.prefs.covers[stored.entry.id].orEmpty()
    // Its chosen covers, else its three most-played main-deck cards: a field reads by its faces.
    val faces = remember(deck, covers, state.index) {
        val ids = if (covers.isNotEmpty()) {
            DeckCovers.shown(covers, deck)
        } else {
            deck.main.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(DeckCovers.MAX)
        }
        ids.mapNotNull(state.index::byId)
    }
    var tileAt by remember { mutableStateOf(Offset.Zero) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (current) c.ink else c.ink25)
            .background(animatedColor(if (hovered) c.ink06 else Color.Transparent))
            .onGloballyPositioned { tileAt = it.boundsInWindow().topLeft }
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .onContextMenu { local -> onMenu(tileAt + local) }
            .clickable(interactionSource = source, indication = null, onClick = onOpen)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (faces.isEmpty()) Box(Modifier.size(COVER_W, COVER_H).background(c.ink06))
                faces.take(DeckCovers.MAX).forEach { face -> NeueCard(face, Modifier.size(COVER_W, COVER_H), foil = "off") }
            }
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .size(if (touch) 40.dp else 28.dp)
                        .cursorPointer(label = if (entry.mine) "Not mine" else "Mine")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStar),
                    contentAlignment = Alignment.Center,
                ) {
                    MuText(if (entry.mine) "★" else "☆", style = MuType.body(LocalMuFonts.current).copy(fontSize = 18.sp), color = if (entry.mine) c.ink else c.ink45)
                }
                entry.share?.let { Mono("$it%", color = c.ink70) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            MuText(
                stored.entry.name,
                style = MuType.body(LocalMuFonts.current).copy(fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = c.ink,
                maxLines = 2,
            )
            Mono("${deck.main.size} · ${deck.extra.size} · ${deck.side.size}", color = c.ink45)
            if (current) Small("On the builder", color = c.ink70)
        }
        if (touch) {
            var moreAt by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.onGloballyPositioned { moreAt = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
                MuButton("More", { onMenu(moreAt) }, variant = BtnVariant.SUBTLE, size = BtnSize.SM, icon = Icons.More)
            }
        }
    }
}

/** The web's name, written where it stands, in the page's heading voice. */
@Composable
private fun WebNameField(value: String, onChange: (String) -> Unit) {
    val c = Mu.colors
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.replace('\n', ' ')) },
        singleLine = true,
        textStyle = MuType.h2(LocalMuFonts.current).copy(color = c.ink, lineHeight = 28.sp),
        cursorBrush = SolidColor(c.ink),
        modifier = Modifier
            .fillMaxWidth()
            .reportsTextFocus()
            .cursorPointer(caption = "Rename")
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(bottom = 4.dp),
    )
}

/** The web's notes: a few lines about the room, kept as they are typed. */
@Composable
internal fun NotesField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(1.dp, c.ink25)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (value.isEmpty()) MuText(placeholder, style = MuType.body(f).copy(fontSize = 13.sp), color = c.ink45, maxLines = 2)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = MuType.body(f).copy(color = c.ink, fontSize = 13.sp),
            cursorBrush = SolidColor(c.ink),
            maxLines = 6,
            // Typing is not a shortcut, and Esc lets go of the note before it closes anything (1.0.35).
            modifier = Modifier.fillMaxWidth().reportsTextFocus(),
        )
    }
}

private val COVER_W = 40.dp
private val COVER_H = 58.dp
