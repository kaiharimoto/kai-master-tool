package com.kaiharimoto.neue.world.library

import com.kaiharimoto.neue.world.type.readingMeasure
import com.kaiharimoto.neue.world.type.WorldType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.evidence.Ledger
import com.kaiharimoto.mastertool.core.ai.evidence.Proven
import com.kaiharimoto.mastertool.core.ai.library.LibraryCatalog
import com.kaiharimoto.mastertool.core.ai.library.LibraryDoc
import com.kaiharimoto.mastertool.core.ai.library.LibraryHit
import com.kaiharimoto.mastertool.core.ai.library.LibraryKind
import com.kaiharimoto.mastertool.core.ai.library.LibrarySections
import com.kaiharimoto.mastertool.core.ai.library.Shelf
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.MarkdownBlock
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.world.type.Help
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.world.type.Micro
import com.kaiharimoto.neue.world.type.MicroLink
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.world.type.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/*
 * The Library (`docs/world/DESKTOP.md` §10; kai: "I don't want there to be a cap to the knowledge"): everything Ai knows,
 * browsable and searchable — the guide and its book, deck and web notes, Ai's lessons, its session reports, the evidence
 * ledger and Shootout's rubrics. Shelves on the left, the document in the middle at a 68-character measure, its contents on
 * the right for anything longer than a screen. Read-only: Edit opens Ai's brain on that file.
 */

private val DAY = SimpleDateFormat("d MMM yyyy")

/** The Library's window body. */
@Composable
fun LibraryApp(h: NeueHolders, modifier: Modifier = Modifier) {
    val lib = h.world.library
    val phone = LocalPhone.current
    // The catalogue, read when the window opens and when the decks it names change: a list of files, never their text.
    LaunchedEffect(Unit) {
        val decks = h.deps.deckRepository.all().associate { it.entry.id to it.entry.name }
        val webs = h.webs.library.webs.associate { it.id to it.name }
        if (lib.deck == null) {
            lib.deck = h.world.open?.scope?.takeIf { it.startsWith(World.SCOPE_DECK) }?.removePrefix(World.SCOPE_DECK) ?: h.builder.deckId
        }
        lib.refresh(decks, webs)
    }
    Column(modifier.fillMaxSize()) {
        SearchLine(h)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 640.dp && !phone
            val contents = maxWidth >= 980.dp && !phone
            when {
                lib.query.isNotBlank() && LibraryQueryReady(lib) -> Row(Modifier.fillMaxSize()) {
                    if (wide) Shelves(h, Modifier.width(240.dp).fillMaxHeight())
                    Hits(h, Modifier.weight(1f).fillMaxHeight())
                }
                wide -> Row(Modifier.fillMaxSize()) {
                    Shelves(h, Modifier.width(240.dp).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Mu.colors.ink12))
                    Reader(h, Modifier.weight(1f).fillMaxHeight(), contents)
                }
                // A phone: the shelves, then the document, with its contents behind a button.
                lib.opened != null || lib.reading != null -> Reader(h, Modifier.fillMaxSize(), contents = false, back = { lib.close() })
                else -> Shelves(h, Modifier.fillMaxSize())
            }
        }
    }
}

/** Whether the query has something to look for (two characters at least). */
private fun LibraryQueryReady(lib: WorldLibrary): Boolean = com.kaiharimoto.mastertool.core.ai.library.LibraryQuery.parse(lib.query) != null

@Composable
private fun SearchLine(h: NeueHolders) {
    val lib = h.world.library
    val c = Mu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MuInput(lib.query, { lib.search(it) }, Modifier.weight(1f), placeholder = "Search everything ${h.ai.name} knows — a \"quoted run\" matches whole", dense = true)
        if (lib.searching) Breathe()
        if (lib.query.isNotEmpty()) MicroLink("Clear", { lib.search("") })
        Mono("${lib.catalog.docs.size} documents", color = c.ink45)
    }
}

// ---- The shelves --------------------------------------------------------------------------------------------------

@Composable
private fun Shelves(h: NeueHolders, modifier: Modifier) {
    val lib = h.world.library
    val c = Mu.colors
    val docs = lib.shelved()
    val list = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            item {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Shelf.entries.forEach { s ->
                        val n = lib.catalog.shelf(s, lib.deck).size
                        ShelfRow(s.title, n, s == lib.shelf) { lib.shelf = s }
                    }
                }
            }
            if (lib.shelf == Shelf.THIS_DECK && lib.decks.isNotEmpty()) {
                item {
                    val options = lib.decks.entries.sortedBy { it.value.lowercase() }.map { it.key to it.value }
                    val chosen = options.firstOrNull { it.first == lib.deck } ?: ("" to "Choose a deck")
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        MuSelect(chosen, listOf(chosen) + options.filter { it != chosen }, { it.second }, { lib.deck = it.first.ifEmpty { null } }, Modifier.fillMaxWidth(), small = true)
                    }
                }
            }
            item { Micro(lib.shelf.title, Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp), color = c.ink45) }
            if (!lib.ready) item { Help("Reading the catalogue…", Modifier.padding(horizontal = 14.dp)) }
            if (lib.ready && docs.isEmpty()) {
                item {
                    Help(
                        when (lib.shelf) {
                            Shelf.THIS_DECK -> if (lib.deck == null) "No deck chosen." else "${h.ai.name} knows nothing written down about this deck yet. Fine Tuning teaches it."
                            Shelf.WEBS -> "No notes on any web yet."
                            Shelf.AI -> "${h.ai.name} has written nothing to itself yet."
                            Shelf.EVERYTHING -> "Nothing yet."
                        },
                        Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            items(docs, key = { it.path }) { d -> DocRow(h, d) }
        }
        ScrollbarFor(list)
    }
}

@Composable
private fun ShelfRow(title: String, count: Int, on: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Inverted(on) {
        val c = Mu.colors
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (on) c.paper else animatedColor(if (hot) c.ink06 else c.paper))
                .hoverable(source)
                .cursorPointer(caption = if (on) null else "Show")
                .muClickable(interactionSource = source, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuText(title, Modifier.weight(1f), style = WorldType.body(LocalMuFonts.current, LocalPhone.current), color = c.ink, maxLines = 1)
            Mono(count.toString(), color = if (on) c.ink70 else c.ink45)
        }
    }
}

@Composable
private fun DocRow(h: NeueHolders, d: LibraryDoc) {
    val lib = h.world.library
    val on = lib.opened?.doc?.path == d.path || lib.reading?.path == d.path
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val c = Mu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (on) c.ink12 else if (hot) c.ink06 else c.paper))
            .hoverable(source)
            .cursorPointer(caption = "Read")
            .muClickable(interactionSource = source) { lib.open(d) }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Small(d.title, color = c.ink, maxLines = 2)
        Mono("${size(d.bytes)} · ${DAY.format(Date(d.updated))}", color = c.ink45)
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

// ---- Reading ------------------------------------------------------------------------------------------------------

@Composable
private fun Reader(h: NeueHolders, modifier: Modifier, contents: Boolean, back: (() -> Unit)? = null) {
    val lib = h.world.library
    val c = Mu.colors
    val opened = lib.opened
    Column(modifier) {
        if (back != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { MicroLink("‹ The shelves", back, color = c.ink) }
        }
        when {
            lib.reading != null -> Help("Reading ${lib.reading?.title}…", Modifier.padding(24.dp))
            opened == null -> EmptyState(
                "Everything ${h.ai.name} knows.",
                "Pick a document from a shelf: the guide and its book, notes on decks and webs, ${h.ai.name}'s lessons, its session reports, the evidence behind its numbers. Search reads all of it.",
            )
            else -> Document(h, opened, contents)
        }
    }
}

@Composable
private fun Document(h: NeueHolders, o: WorldLibrary.Opened, contents: Boolean) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val doc = o.doc
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showContents by remember(doc.path) { mutableStateOf(false) }
    // Markdown is a lazy column of blocks — never one Text — so a guide of any length opens without a stall.
    val markdown = doc.kind !in setOf(LibraryKind.BOOK, LibraryKind.REPORTS, LibraryKind.EVIDENCE)
    val rows = remember(o) { if (markdown) rowsOf(o.sections) else emptyList() }
    val lib = h.world.library
    LaunchedEffect(o, lib.jump) {
        val at = lib.jump ?: return@LaunchedEffect
        lib.jump = null
        val i = rows.indexOfLast { it.offset <= at }.coerceAtLeast(0)
        if (rows.isNotEmpty()) list.scrollToItem(i + 1)
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (LocalPhone.current) 16.dp else 32.dp, vertical = 20.dp)) {
                item {
                    Column(Modifier.widthIn(max = MEASURE).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Micro(doc.kind.title, color = c.ink45)
                        MuText(doc.title, style = MuType.h2(f), color = c.ink, maxLines = 3)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Mono(o.count, color = c.ink45)
                            Mono(doc.path, color = c.ink45)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            editable(doc)?.let { path ->
                                MuButton("Edit", { h.ai.memoryOpen = path }, variant = BtnVariant.SUBTLE, size = BtnSize.SM)
                            }
                            if (doc.kind == LibraryKind.BOOK) {
                                val deck = doc.scope.removePrefix(LibraryCatalog.DECK)
                                MuButton("Open the reader", { h.neue.reading = deck }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true)
                            }
                            if (!contents && markdown && o.sections.size > 1) MicroLink(if (showContents) "Hide contents" else "Contents", { showContents = !showContents }, color = c.ink)
                        }
                    }
                }
                if (showContents && !contents) {
                    item { Contents(o.sections, Modifier.widthIn(max = MEASURE).padding(bottom = 16.dp)) { at -> showContents = false; scope.launch { list.scrollToItem(rows.indexOfFirst { it.offset >= at }.coerceAtLeast(0) + 1) } } }
                }
                when (doc.kind) {
                    LibraryKind.BOOK -> item { BookOutline(o.text) }
                    LibraryKind.REPORTS -> item { Reports(o.text) }
                    LibraryKind.EVIDENCE -> item { Evidence(o.text) }
                    else -> items(rows.size) { i -> RowView(h, rows[i]) }
                }
            }
            ScrollbarFor(list)
        }
        if (contents && markdown && o.sections.size > 1) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.ink12))
            Contents(o.sections, Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { at ->
                scope.launch { list.scrollToItem(rows.indexOfFirst { it.offset >= at }.coerceAtLeast(0) + 1) }
            }
        }
    }
}

/** The reading measure: 68 characters of body text. */
/** The document's column: the World's reading measure (READABILITY.md §2), measured in the body tier. */
private val MEASURE: androidx.compose.ui.unit.Dp
    @Composable get() = readingMeasure()

/** The memory file [doc] is, for Ai's brain (`MemoryDialog`), or null when it is not one the brain edits. */
private fun editable(doc: LibraryDoc): String? = when (doc.kind) {
    LibraryKind.GUIDE, LibraryKind.NOTES, LibraryKind.WEB, LibraryKind.LESSONS, LibraryKind.SOUL, LibraryKind.USER ->
        doc.path.removePrefix(LibraryCatalog.MEMORY + "/")
    else -> null
}

/** One row of a document as read: a heading, or a block of at most 4 KB. */
private data class DocRow(val offset: Int, val heading: String?, val level: Int, val text: String)

private fun rowsOf(sections: List<LibrarySections.Section>): List<DocRow> = buildList {
    sections.forEach { s ->
        if (s.title.isNotEmpty()) add(DocRow(s.offset, s.title, s.level, ""))
        s.blocks.forEach { b -> add(DocRow(b.offset, null, 0, b.text)) }
    }
}

@Composable
private fun RowView(h: NeueHolders, r: DocRow) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Box(Modifier.widthIn(max = MEASURE).padding(bottom = 10.dp)) {
        if (r.heading != null) {
            MuText(r.heading, Modifier.padding(top = 12.dp), style = if (r.level <= 2) WorldType.title(f) else WorldType.heading(f, LocalPhone.current), color = c.ink)
        } else {
            val blocks = remember(r.text) { ChatMarkdown.parse(r.text) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { blocks.forEach { MarkdownBlock(h.ai, it) } }
        }
    }
}

@Composable
private fun Contents(sections: List<LibrarySections.Section>, modifier: Modifier, go: (Int) -> Unit) {
    val c = Mu.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro("Contents", color = c.ink45)
        sections.filter { it.title.isNotEmpty() }.take(200).forEach { s ->
            Box(Modifier.padding(start = ((s.level - 1).coerceAtLeast(0) * 10).dp)) {
                MicroLink(s.title, { go(s.offset) }, color = c.ink70)
            }
        }
    }
}

/** The reader's book (§10.2): its chapters listed; the reader paints it. */
@Composable
private fun BookOutline(text: String) {
    val c = Mu.colors
    val book = remember(text) { GuideBook.read(text) }
    Column(Modifier.widthIn(max = MEASURE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (book == null) Help("The book would not read.")
        if (book != null && book.bigIdea.isNotBlank()) Small(book.bigIdea, color = c.ink)
        book?.chapters.orEmpty().forEachIndexed { i, ch ->
            Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mono((i + 1).toString().padStart(2, '0'), color = c.ink45)
                    MuText(ch.title, style = WorldType.heading(LocalMuFonts.current, LocalPhone.current), color = c.ink)
                }
                if (ch.summary.isNotBlank()) Small(ch.summary, color = c.ink70)
                Mono(if (ch.written) ch.sections.joinToString(" · ") { it.title } else "planned, not written yet", color = c.ink45)
            }
        }
    }
}

/** Session reports (§10.2): scores with their why, what was learned, the questions asked. */
@Composable
private fun Reports(text: String) {
    val c = Mu.colors
    val reports = remember(text) { ReportLog.read(text).reversed() }
    Column(Modifier.widthIn(max = MEASURE), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (reports.isEmpty()) Help("No reports would read.")
        reports.forEach { r ->
            Column(Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Mono("${DAY.format(Date(r.at))} · ${r.mode}" + (if (r.minutes > 0) " · ${r.minutes} min" else ""), color = c.ink45)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Score("Understanding", r.understanding)
                    Score("Playing", r.playing)
                    Score("Mirror", r.mirror)
                }
                if (r.summary.isNotBlank()) Small(r.summary, color = c.ink)
                if (r.why.isNotBlank()) Small(r.why, color = c.ink70)
                if (r.learned.isNotEmpty()) {
                    Micro("Learned", color = c.ink45)
                    r.learned.forEach { Small("– $it", color = c.ink70) }
                }
                if (r.questions.isNotEmpty()) {
                    Micro("Asked", color = c.ink45)
                    r.questions.forEach { q -> Small("${q.question} — ${q.answer}", color = c.ink70) }
                }
            }
        }
    }
}

@Composable
private fun Score(label: String, n: Int) {
    Column {
        Micro(label, color = Mu.colors.ink45)
        MuText("$n / 100", style = WorldType.heading(LocalMuFonts.current, LocalPhone.current).copy(fontFamily = LocalMuFonts.current.mono), color = Mu.colors.ink)
    }
}

/** The evidence ledger (§10.2): the claim, its proof, when, and stale where the deck changed since. */
@Composable
private fun Evidence(text: String) {
    val c = Mu.colors
    val rows = remember(text) { Ledger.read(text) }
    val stale = rows.count { it.status == Proven.Status.STALE || it.status == Proven.Status.CONTRADICTED }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Mono("${rows.size} numbers" + if (stale > 0) ", $stale stale" else "", Modifier.padding(bottom = 8.dp), color = c.ink45)
        Row(Modifier.fillMaxWidth().background(c.ink06).padding(vertical = 6.dp)) {
            listOf("Claim" to 3f, "Proof" to 2f, "When" to 1f, "Status" to 1f).forEach { (t, w) ->
                Micro(t, Modifier.weight(w).padding(horizontal = 8.dp), color = c.ink70)
            }
        }
        rows.forEach { p ->
            Row(Modifier.fillMaxWidth().drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }.padding(vertical = 6.dp)) {
                Small(p.entry, Modifier.weight(3f).padding(horizontal = 8.dp), color = c.ink, maxLines = 4)
                Small(
                    p.proofs.firstOrNull()?.let { pr -> if (pr.tool == com.kaiharimoto.mastertool.core.ai.evidence.Evidence.PERSON) "the person said" else "${pr.tool} ${pr.input.take(80)}" } ?: if (p.status == Proven.Status.ESTIMATE) "an estimate" else "—",
                    Modifier.weight(2f).padding(horizontal = 8.dp),
                    color = c.ink70,
                    maxLines = 3,
                )
                Mono(if (p.checkedAt > 0) DAY.format(Date(p.checkedAt)) else "", Modifier.weight(1f).padding(horizontal = 8.dp), color = c.ink45)
                MuText(
                    p.status.name.lowercase(),
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = WorldType.mono(LocalMuFonts.current, LocalPhone.current).copy(fontWeight = if (p.status == Proven.Status.CHECKED) FontWeight.Normal else FontWeight.Bold),
                    color = if (p.status == Proven.Status.CHECKED) c.ink70 else c.ink,
                )
            }
        }
    }
}

// ---- Search -------------------------------------------------------------------------------------------------------

@Composable
private fun Hits(h: NeueHolders, modifier: Modifier) {
    val lib = h.world.library
    val c = Mu.colors
    val groups = lib.grouped()
    val list = rememberLazyListState()
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
            item {
                Mono(
                    when {
                        lib.searching && lib.hits.isEmpty() -> "Searching…"
                        lib.hits.isEmpty() -> "Nothing found."
                        else -> "${lib.hits.size}${if (lib.capped) "+" else ""} lines in ${groups.size} documents" + if (lib.searching) " so far" else ""
                    },
                    Modifier.padding(bottom = 8.dp),
                    color = c.ink45,
                )
            }
            groups.forEach { (doc, hits) ->
                item(key = "d:${doc.path}") {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Micro(doc.title, Modifier.weight(1f), color = c.ink70)
                        Mono(hits.size.toString(), color = c.ink45)
                    }
                }
                items(hits, key = { "h:${it.doc.path}:${it.offset}" }) { hit -> HitRow(h, hit) }
            }
            if (lib.capped && !lib.searching) item { MicroLink("More", { lib.more() }, Modifier.padding(top = 12.dp), color = c.ink) }
        }
        ScrollbarFor(list)
    }
}

@Composable
private fun HitRow(h: NeueHolders, hit: LibraryHit) {
    val lib = h.world.library
    val c = Mu.colors
    val f = LocalMuFonts.current
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val text = remember(hit) { inverted(hit, c.paper, c.ink) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot) c.ink06 else c.paper))
            .hoverable(source)
            .cursorPointer(caption = "Read")
            .muClickable(interactionSource = source) {
                lib.open(hit.doc, hit.offset)
                lib.search("")
            }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        MuText(text, style = WorldType.label(f, LocalPhone.current), color = c.ink70, maxLines = 3)
    }
}

/** A hit's line with each match inverted (§10.3): paper on ink, the kit's emphasis. */
private fun inverted(hit: LibraryHit, paper: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val line = hit.line
    val ranges = hit.ranges.filter { it.first >= 0 && it.last < line.length }.sortedBy { it.first }
    var at = 0
    ranges.forEach { r ->
        if (r.first < at) return@forEach
        append(line.substring(at, r.first))
        withStyle(SpanStyle(color = paper, background = ink)) { append(line.substring(r.first, r.last + 1)) }
        at = r.last + 1
    }
    if (at < line.length) append(line.substring(at))
}
