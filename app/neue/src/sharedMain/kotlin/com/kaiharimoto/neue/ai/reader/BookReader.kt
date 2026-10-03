package com.kaiharimoto.neue.ai.reader

import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.askTune
import com.kaiharimoto.neue.ai.writing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.ai.memory.MemoryKind
import com.kaiharimoto.mastertool.core.ai.report.ReaderGuide
import com.kaiharimoto.mastertool.core.ai.report.book.Block
import com.kaiharimoto.mastertool.core.ai.report.book.BookArt
import com.kaiharimoto.mastertool.core.ai.report.book.BookPdf
import com.kaiharimoto.mastertool.core.ai.report.book.Faces
import com.kaiharimoto.mastertool.core.ai.report.book.GuideBook
import com.kaiharimoto.mastertool.core.ai.text.ChatMarkdown
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Viewing
import com.kaiharimoto.neue.ai.AiDocs
import com.kaiharimoto.neue.ai.LocalCardLink
import com.kaiharimoto.neue.ai.styled
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.HRule
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.VRule
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The reader's guide, read in the app (1.0.67, kai: "In the full app ready, it's interactable/animated
 * would be really ambitious but also really good"). The book over the whole window: its cover with
 * the contents, a chapter's opening, each section with its kicker and headline, then its blocks —
 * words set by the app, pictures painted from the drawings the PDF is made of.
 *
 * The contents stand beside the page on a wide window and slide in on a narrow one; the place is
 * kept per deck while the app runs. A card anywhere opens large; a line's steps show the board after
 * each play, the cards sliding from zone to zone; checklists tick. PDF and JSON from the bar.
 */
@Composable
fun BookReader(h: NeueHolders, deckId: String, modifier: Modifier = Modifier) {
    val ai = h.ai
    val c = Mu.colors
    val phone = LocalPhone.current
    val scope = rememberCoroutineScope()
    val version = ai.bookVersion
    val book = remember(deckId, version) { ai.files.read(GuideBook.path(deckId)).let(GuideBook::read)?.withIds() }
    val fonts by produceState<Faces?>(null) { value = Faces.of(AiDocs.fonts()) }
    val deck = remember(deckId, h.builder.deck) { AiDocs.deckNames(h, deckId) }
    val art = remember(fonts, book, deck) {
        val f = fonts
        if (f != null && book != null) BookArt.of(f, book, deck, AiDocs.kind(h)) else null
    }
    val cards: (String) -> Card? = remember(h.builder.index) { { name -> h.builder.index.byName(name) } }
    val open: (String) -> Unit = { name -> cards(name)?.let { h.neue.viewing = Viewing(it, null, 0) } }
    val onBuilder = h.builder.deckId == deckId
    val write = {
        h.neue.reading = null
        ai.setOpen(true)
        ai.askTune(AiSession.MODE_WRITE)
    }
    // Out of date when Ai's notes on the deck have changed since it was written.
    val stale = remember(book, version) {
        val notes = ai.files.read(AiMemory.path(MemoryKind.GUIDE, deckId)).orEmpty()
        book != null && book.notesHash.isNotBlank() && notes.isNotBlank() && book.notesHash != ReaderGuide.hashOf(notes)
    }

    BoxWithConstraints(
        modifier
            .background(c.paper)
            // The page under it hears nothing while it is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val wide = maxWidth >= 1000.dp
        var contents by remember { mutableStateOf(wide) }
        val items = remember(book) { book?.let(::itemsOf).orEmpty() }
        val list = rememberLazyListState(initialFirstVisibleItemIndex = (ai.bookPlaces[deckId] ?: 0).coerceAtMost((items.size - 1).coerceAtLeast(0)))
        DisposableEffect(deckId) { onDispose { ai.bookPlaces[deckId] = list.firstVisibleItemIndex } }
        val chapter by remember(items) { derivedStateOf { items.getOrNull(list.firstVisibleItemIndex)?.chapter ?: -1 } }
        val jump: (String) -> Unit = { id ->
            val at = items.indexOfFirst { it.id == id }
            if (at >= 0) scope.launch { list.scrollToItem(at) }
            if (!wide) contents = false
        }

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(Icons.X, { h.neue.reading = null }, label = "Close")
                if (book != null) IconButton(Icons.PanelLeft, { contents = !contents }, toggled = contents, label = "Contents")
                Column(Modifier.weight(1f)) {
                    Micro(if (ai.writing && onBuilder) "${ai.name} is writing" else "Reader's guide", color = c.ink45)
                    MuText(book?.title ?: h.builder.deckName, style = MuType.row(LocalMuFonts.current).copy(fontWeight = FontWeight.Medium), color = c.ink, maxLines = 1)
                }
                if (book != null && book.chapters.isNotEmpty()) {
                    Mono("${two(chapter + 1)}/${two(book.chapters.size)}".takeIf { chapter >= 0 } ?: "––/${two(book.chapters.size)}", color = c.ink45)
                }
                if (book != null && !phone) Exports(h, deckId, book)
                // With nothing written the page's own button says it; the bar's would repeat it.
                if (!phone && !ai.writing && book != null && book.chapters.isNotEmpty()) {
                    MuButton(
                        if (book.isEmpty) "Write it" else "Update",
                        write,
                        variant = BtnVariant.SECONDARY,
                        size = BtnSize.SM,
                        enabled = onBuilder && ai.configured,
                        reason = if (!onBuilder) "Open the deck in the builder first" else "Set up ${ai.name} first",
                    )
                }
            }
            HRule(color = c.ink)
            if (stale && !ai.writing) {
                Row(
                    Modifier.fillMaxWidth().background(c.ink06).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Small("${ai.name}'s notes on this deck have changed since this was written.", Modifier.weight(1f), color = c.ink)
                    if (onBuilder) MuButton("Update it", write, variant = BtnVariant.GHOST, size = BtnSize.SM)
                }
            }
            if (book == null || book.chapters.isEmpty()) {
                Empty(h, onBuilder, write)
                return@Column
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (contents && wide) {
                    Box(Modifier.width(300.dp).fillMaxHeight()) { Contents(book, chapter, jump, Modifier.fillMaxSize()) }
                    VRule(color = c.ink12)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val gutter = if (phone) 16.dp else 32.dp
                    val column = minOf(maxWidth - gutter * 2, 600.dp)
                    // A point is a dp on a phone, a little more on a wide column: the pictures were set for a phone's page.
                    val z = (column.value / 360f).coerceIn(1.05f, 1.35f)
                    val width = column.value / z
                    CompositionLocalProvider(LocalCardLink provides open) {
                        LazyColumn(Modifier.fillMaxSize(), state = list, horizontalAlignment = Alignment.CenterHorizontally) {
                            itemsIndexed(items, key = { _, it -> it.id }) { _, item ->
                                Box(Modifier.width(column)) {
                                    when (item) {
                                        is Item.Cover -> Cover(book, column, jump, cards)
                                        is Item.Opener -> Opener(book, item.chapter, jump, onBuilder, ai.name, write)
                                        is Item.Head -> Head(book, item.chapter, item.section)
                                        is Item.Piece -> if (art != null) Piece(ai, art, item.block, z, width, column, cards, open)
                                        is Item.Sources -> Sources(book)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // A narrow window's contents: a drawer over the page, from the left.
        if (book != null && contents && !wide) {
            Box(Modifier.fillMaxSize().padding(top = 49.dp).background(c.ink.copy(alpha = 0.12f)).muClickable { contents = false })
            Column(Modifier.padding(top = 49.dp).width(minOf(maxWidth - 48.dp, 340.dp)).fillMaxHeight().background(c.paper)) {
                Contents(book, chapter, jump, Modifier.weight(1f))
                HRule()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Exports(h, deckId, book)
                    if (!ai.writing) MuButton("Update", write, variant = BtnVariant.GHOST, size = BtnSize.SM, enabled = onBuilder && ai.configured, reason = "Open the deck in the builder first")
                }
            }
            Box(Modifier.padding(top = 48.dp).fillMaxWidth().height(1.dp).background(c.ink))
        }
    }
}

private fun two(n: Int) = n.toString().padStart(2, '0')

/** The book as the rows of a list: the cover, each chapter's opening, each section's head, each block. */
private sealed interface Item {
    val id: String
    val chapter: Int

    data object Cover : Item {
        override val id = "cover"
        override val chapter = -1
    }

    data class Opener(override val chapter: Int, override val id: String) : Item
    data class Head(override val chapter: Int, val section: Int, override val id: String) : Item
    data class Piece(override val chapter: Int, val block: Block, override val id: String) : Item
    data object Sources : Item {
        override val id = "sources"
        override val chapter = Int.MAX_VALUE
    }
}

private fun itemsOf(book: GuideBook): List<Item> = buildList {
    add(Item.Cover)
    book.chapters.forEachIndexed { ci, ch ->
        add(Item.Opener(ci, ch.id))
        ch.sections.forEachIndexed { si, s ->
            add(Item.Head(ci, si, s.id))
            s.blocks.forEachIndexed { bi, b -> add(Item.Piece(ci, b, b.id.ifBlank { "${s.id}/${bi + 1}" })) }
        }
    }
    if (book.sources.isNotEmpty()) add(Item.Sources)
}

@Composable
private fun Exports(h: NeueHolders, deckId: String, book: GuideBook) {
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf(false) }
    MuButton(if (making) "Making…" else "PDF", {
        making = true
        scope.launch {
            try {
                AiDocs.deliverBook(h, deckId, book)
            } finally {
                making = false
            }
        }
    }, variant = BtnVariant.GHOST, size = BtnSize.SM, icon = Icons.Export, enabled = !making, reason = "The PDF is being made")
    MuButton("JSON", { scope.launch { AiDocs.deliverBookJson(h, book) } }, variant = BtnVariant.GHOST, size = BtnSize.SM)
}

@Composable
private fun Empty(h: NeueHolders, onBuilder: Boolean, write: () -> Unit) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Micro("Reader's guide", color = c.ink45)
            MuText("No guide yet", style = MuType.h1(f).copy(fontWeight = FontWeight.Bold), color = c.ink)
            Small(
                "${h.ai.name} writes a book about this deck for you to read: what to remember, every line with the board after each play, " +
                    "where it breaks, each matchup and hands to solve — a chapter at a time, from what it knows of the deck.",
                color = c.ink70,
            )
            MuButton(
                "Write the reader's guide", write, variant = BtnVariant.PRIMARY, arrow = true,
                enabled = onBuilder && h.ai.configured,
                reason = if (!onBuilder) "Open the deck in the builder first" else "Set up ${h.ai.name} first",
            )
        }
    }
}

/** Every chapter numbered and its sections under it; the chapter being read marked with a bar. */
@Composable
private fun Contents(book: GuideBook, at: Int, jump: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        Micro("Contents", Modifier.padding(horizontal = 20.dp), color = c.ink45)
        Box(Modifier.height(10.dp))
        book.chapters.forEachIndexed { ci, ch ->
            val here = ci == at
            Row(
                Modifier
                    .fillMaxWidth()
                    .cursorPointer(caption = "Go")
                    .muClickable { jump(ch.id) }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.width(2.dp).height(18.dp).background(if (here) c.ink else c.paper))
                Mono(two(ci + 1), color = if (here) c.ink else c.ink45)
                MuText(
                    ch.title,
                    Modifier.weight(1f),
                    style = MuType.row(f).copy(fontWeight = if (here) FontWeight.Bold else FontWeight.Medium),
                    color = if (ch.written) c.ink else c.ink45,
                )
                if (!ch.written) Mono("planned", color = c.ink45)
            }
            if (here) {
                ch.sections.forEachIndexed { si, s ->
                    Row(
                        Modifier.fillMaxWidth().cursorPointer(caption = "Go").muClickable { jump(s.id) }.padding(start = 64.dp, end = 20.dp, top = 3.dp, bottom = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Mono("${ci + 1}.${si + 1}", color = c.ink45)
                        Small(s.title, Modifier.weight(1f), color = c.ink70)
                    }
                }
                Box(Modifier.height(6.dp))
            }
        }
    }
}

/** The cover kai chose: the deck's hub as a picture, its name, the big idea, then the contents. */
@Composable
private fun Cover(book: GuideBook, column: Dp, jump: (String) -> Unit, cards: (String) -> Card?) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val phone = LocalPhone.current
    Column(Modifier.fillMaxWidth().padding(top = if (phone) 16.dp else 32.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        BookPdf.hero(book)?.let { hero ->
            Artwork(hero, column.value, column.value * 0.72f, cards)
            Box(Modifier.fillMaxWidth().height(4.dp).background(c.ink))
            Box(Modifier.height(22.dp))
        }
        Micro("A deck guide", color = c.ink45)
        Box(Modifier.height(6.dp))
        MuText(book.title, style = MuType.display(f).copy(fontSize = if (phone) 40.sp else 52.sp, lineHeight = if (phone) 40.sp else 50.sp), color = c.ink)
        if (book.subtitle.isNotBlank()) {
            Box(Modifier.height(8.dp))
            Mono(book.subtitle, color = c.ink45)
        }
        if (book.bigIdea.isNotBlank()) {
            Box(Modifier.height(14.dp))
            MuText(book.bigIdea, style = MuType.h2(f).copy(fontSize = 20.sp, lineHeight = 26.sp), color = c.ink)
        }
        Box(Modifier.height(24.dp))
        HRule(color = c.ink)
        Box(Modifier.height(10.dp))
        Micro("Contents", color = c.ink45)
        Box(Modifier.height(8.dp))
        book.chapters.forEachIndexed { ci, ch ->
            Row(
                Modifier.fillMaxWidth().cursorPointer(caption = "Go").muClickable { jump(ch.id) }.padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono(two(ci + 1), color = c.ink, size = 13.sp)
                MuText(ch.title, Modifier.weight(1f), style = MuType.body(f).copy(fontSize = 17.sp, fontWeight = FontWeight.Medium), color = if (ch.written) c.ink else c.ink45)
                Mono(if (ch.written) "${ch.sections.size} ${if (ch.sections.size == 1) "section" else "sections"}" else "planned", color = c.ink45)
            }
        }
    }
}

/** A chapter's opening: its number large, its title, what it holds, its sections to jump to. */
@Composable
private fun Opener(book: GuideBook, ci: Int, jump: (String) -> Unit, onBuilder: Boolean, name: String, write: () -> Unit) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val ch = book.chapters[ci]
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 16.dp)) {
        HRule(strong = true)
        Box(Modifier.height(14.dp))
        Micro("Chapter", color = c.ink45)
        MuText(two(ci + 1), style = MuType.mono(f, 72.sp).copy(lineHeight = 76.sp, letterSpacing = (-0.04).em), color = c.ink)
        MuText(ch.title, style = MuType.h1(f).copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 36.sp, letterSpacing = (-0.025).em), color = c.ink)
        if (ch.summary.isNotBlank()) {
            Box(Modifier.height(10.dp))
            MuText(ch.summary, style = MuType.body(f).copy(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium), color = c.ink70)
        }
        Box(Modifier.height(18.dp))
        if (!ch.written) {
            Help("Not written yet: $name writes it in its next writing session.", color = c.ink45)
            if (onBuilder) {
                Box(Modifier.height(10.dp))
                MuButton("Write it", write, variant = BtnVariant.SECONDARY, size = BtnSize.SM, arrow = true)
            }
            return@Column
        }
        HRule(color = c.ink)
        Box(Modifier.height(8.dp))
        ch.sections.forEachIndexed { si, s ->
            Row(
                Modifier.fillMaxWidth().cursorPointer(caption = "Go").muClickable { jump(s.id) }.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Mono("${ci + 1}.${si + 1}", color = c.ink45)
                MuText(s.title, Modifier.weight(1f), style = MuType.body(f).copy(fontSize = 15.sp), color = c.ink)
            }
        }
    }
}

/** A section's head: the kicker, then the headline that makes a claim — or, for a lesson, its maxim below. */
@Composable
private fun Head(book: GuideBook, ci: Int, si: Int) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val ch = book.chapters[ci]
    val s = ch.sections[si]
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Mono("${two(ci + 1)}.${si + 1}", color = c.ink)
            Micro("— ${ch.title}", color = c.ink45)
        }
        if (s.blocks.firstOrNull() !is Block.Lesson) {
            Box(Modifier.height(8.dp))
            MuText(s.title, style = MuType.h1(f).copy(fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 29.sp, letterSpacing = (-0.025).em), color = c.ink)
        }
    }
}

/** One block: words set by the app; a picture painted from its drawings; a line with its board after each play. */
@Composable
private fun Piece(ai: AiState, art: BookArt, b: Block, z: Float, width: Float, column: Dp, cards: (String) -> Card?, open: (String) -> Unit) {
    val onTag: (String) -> Unit = { tag -> if (tag.startsWith("card:")) open(tag.substringAfter(':')) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (b) {
            is Block.Text -> Words(b, column)
            is Block.Line -> LineView(art, b, z, width, cards, open)
            is Block.Checklist -> {
                val d = remember(art, b, width) { art.drawings(b, width) }
                d.forEach { drawing ->
                    val tags = remember(drawing) { drawing.tags().filter { it.tag.startsWith("check:") } }
                    val ink = Mu.colors.ink
                    val paper = Mu.colors.paper
                    DrawingView(
                        drawing, z, cards,
                        onTag = { tag -> if (tag.startsWith("check:")) ai.bookTicks["${b.id}/$tag"] = !(ai.bookTicks["${b.id}/$tag"] ?: false) else onTag(tag) },
                        overlay = { s ->
                            tags.filter { ai.bookTicks["${b.id}/${it.tag}"] == true }.forEach { t ->
                                drawRect(ink, Offset(0f, (t.top + 2f) * s), Size(9f * s, 9f * s))
                                val p = androidx.compose.ui.graphics.Path().apply {
                                    moveTo(2f * s, (t.top + 6.5f) * s)
                                    lineTo(4f * s, (t.top + 8.6f) * s)
                                    lineTo(7.2f * s, (t.top + 4f) * s)
                                }
                                drawPath(p, paper, style = Stroke(1.3f * s))
                            }
                        },
                    )
                }
            }
            else -> {
                val d = remember(art, b, width) { art.drawings(b, width) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { d.forEach { DrawingView(it, z, cards, onTag = onTag) } }
            }
        }
    }
}

/** Words, a paragraph at a line of the text; a label in the margin on a wide column, over the words on a narrow one. */
@Composable
private fun Words(t: Block.Text, column: Dp) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val style = MuType.body(f).copy(fontSize = 16.sp, lineHeight = 25.sp)
    val paragraphs = remember(t.text) { t.text.split('\n').filter { it.isNotBlank() } }
    val body: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            paragraphs.forEach { MuText(styled(ChatMarkdown.inline(it)), style = style, color = c.ink) }
        }
    }
    when {
        t.label.isBlank() -> body(Modifier.fillMaxWidth())
        column >= 520.dp -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Micro(t.label, Modifier.width(96.dp).padding(top = 6.dp), color = c.ink, maxLines = 2)
            body(Modifier.weight(1f))
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro(t.label, color = c.ink)
            body(Modifier.fillMaxWidth())
        }
    }
}

/**
 * A line: its steps, each a row with where it can be stopped, then the board after each play — one
 * board, stepped through, its cards sliding to where the play puts them. A step's row, or its choke
 * point, shows the board after it; a finger drags across the board to step.
 */
@Composable
private fun LineView(art: BookArt, b: Block.Line, z: Float, width: Float, cards: (String) -> Card?, open: (String) -> Unit) {
    val c = Mu.colors
    val rows = remember(art, b, width) { art.drawings(b, width) }
    val frames = remember(art, b, width) { if (b.frames && b.line.steps.isNotEmpty()) art.frames(b.line, minOf(width, 360f)) else emptyList() }
    var step by remember(b.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, d ->
            DrawingView(
                d, z, cards,
                // The step the board shows: an ink bar in the margin, apart from the picture's own their-turn band.
                modifier = if (frames.isNotEmpty() && i == step) Modifier.drawBehind {
                    drawRect(c.ink, Offset(-10.dp.toPx(), 0f), Size(3.dp.toPx(), size.height))
                } else Modifier,
                onTag = { tag ->
                    when (tag.substringBefore(':')) {
                        "card" -> open(tag.substringAfter(':'))
                        "step", "choke" -> tag.substringAfter(':').toIntOrNull()?.let { step = it.coerceIn(0, (frames.size - 1).coerceAtLeast(0)) }
                    }
                },
            )
        }
        if (frames.isNotEmpty()) {
            Box(Modifier.height(16.dp))
            FramesPlayer(frames, step, { step = it }, z, cards, open)
        }
    }
}

/** The board after each play, one at a time: back, the count, forward, and play to watch it unfold. */
@Composable
private fun FramesPlayer(frames: List<BookArt.Frame>, step: Int, onStep: (Int) -> Unit, z: Float, cards: (String) -> Card?, open: (String) -> Unit) {
    val c = Mu.colors
    val t = remember { Animatable(1f) }
    var shown by remember { mutableIntStateOf(step) }
    var from by remember { mutableStateOf<Map<String, androidx.compose.ui.geometry.Rect>?>(null) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (step == shown) return@LaunchedEffect
        from = cardPlaces(frames[shown.coerceIn(0, frames.lastIndex)].drawing)
        shown = step
        t.snapTo(0f)
        // Cards may move (kai): a play takes a little longer than the chrome's slowest, so the eye can follow it.
        t.animateTo(1f, tween(MuMotion.SLOW + MuMotion.BASE, easing = MuMotion.ease))
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        if (step >= frames.lastIndex) onStep(0)
        while (playing) {
            delay(1500)
            val next = shown + 1
            if (next > frames.lastIndex) {
                playing = false
            } else {
                onStep(next)
            }
        }
    }
    val drag = remember { floatArrayOf(0f) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(c.ink))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Micro("The board after each play", Modifier.weight(1f), color = c.ink)
            IconButton(Icons.ChevronLeft, { playing = false; onStep((step - 1).coerceAtLeast(0)) }, enabled = step > 0, label = "Back a play")
            Mono("${two(step + 1)}/${two(frames.size)}", color = c.ink)
            IconButton(Icons.ChevronRight, { playing = false; onStep((step + 1).coerceAtMost(frames.lastIndex)) }, enabled = step < frames.lastIndex, label = "Next play")
            MuButton(if (playing) "Pause" else "Play", { playing = !playing }, variant = BtnVariant.GHOST, size = BtnSize.SM)
        }
        val frame = frames[shown.coerceIn(0, frames.lastIndex)]
        Box(
            Modifier.pointerInput(frames) {
                detectHorizontalDragGestures(onDragEnd = { drag[0] = 0f }) { change, dx ->
                    drag[0] += dx
                    val notch = 48.dp.toPx()
                    if (drag[0] <= -notch) { drag[0] = 0f; playing = false; onStep((shown + 1).coerceAtMost(frames.lastIndex)) }
                    if (drag[0] >= notch) { drag[0] = 0f; playing = false; onStep((shown - 1).coerceAtLeast(0)) }
                    change.consume()
                }
            },
        ) {
            DrawingView(frame.drawing, z, cards, onTag = { tag -> if (tag.startsWith("card:")) open(tag.substringAfter(':')) }, from = from, t = { t.value })
        }
    }
}

@Composable
private fun Sources(book: GuideBook) {
    val c = Mu.colors
    Column(Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HRule(color = c.ink)
        Micro("Sources", color = c.ink45)
        book.sources.forEach { Small(it, color = c.ink70) }
    }
}
