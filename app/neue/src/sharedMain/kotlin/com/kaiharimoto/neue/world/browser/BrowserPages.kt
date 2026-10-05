package com.kaiharimoto.neue.world.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.BoardKind
import com.kaiharimoto.mastertool.core.world.Instruments
import com.kaiharimoto.mastertool.core.world.InstrumentForm
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.Icon
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.mastertool.core.world.desk.WorldHome
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.EmptyState
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.BoardBody
import com.kaiharimoto.neue.world.apps.GLYPH_SMALL
import com.kaiharimoto.neue.world.apps.WorldIcon
import com.kaiharimoto.neue.world.apps.WorldTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date

/*
 * The Browser's pages (`docs/world/DESKTOP.md` §4): a board, a file, a run, an instrument, the new-tab page, and the page
 * that says there is no such page. Each is a column at most 1,120 dp wide, centred: its kind in micro caps, its title,
 * Ai's note, where it came from with links, and its body at the page's width.
 */

/** The widest a page's column is drawn. */
private val PAGE_MAX = 1120.dp

private val CLOCK = SimpleDateFormat("HH:mm")

/** The run that pinned board [id], newest first. */
internal fun runOf(h: NeueHolders, id: String): WorldEvent? =
    h.world.activity.lastOrNull { it.kind == WorldEvent.Kind.RUN && it.run?.boards?.contains(id) == true }

/** A tab's title: the page's own, in words. */
internal fun pageTitle(h: NeueHolders, a: WorldAddress): String = when (a) {
    is WorldAddress.Home -> if (a.q != null) "Pages · ${a.q}" else "Home"
    is WorldAddress.Board -> h.world.open?.board(a.id)?.title?.ifBlank { a.id } ?: "Taken down"
    is WorldAddress.File -> a.path.substringAfterLast('/')
    is WorldAddress.Run -> h.world.activity.firstOrNull { it.t == a.t }?.let { it.path ?: it.run?.path ?: it.text } ?: "A run"
    is WorldAddress.Instrument -> a.name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    is WorldAddress.App -> h.world.apps.manifest(a.slug)?.title ?: a.slug
    is WorldAddress.Unknown -> "No such page"
}

/** A tab's glyph (§7.2): the page's kind. */
internal fun pageGlyph(h: NeueHolders, a: WorldAddress): Icon = when (a) {
    is WorldAddress.Home -> WorldIcons.PAGE_HOME
    is WorldAddress.Board -> WorldIcons.page(h.world.open?.board(a.id)?.type ?: BoardKind.MARKDOWN)
    is WorldAddress.File -> when (a.path.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp" -> WorldIcons.PAGE_IMAGE
        "csv", "tsv" -> WorldIcons.PAGE_TABLE
        "js", "py", "json" -> WorldIcons.EDITOR
        else -> WorldIcons.PAGE_MARKDOWN
    }
    is WorldAddress.Run -> WorldIcons.TERMINAL
    is WorldAddress.Instrument -> WorldIcons.INSTRUMENTS
    is WorldAddress.App -> WorldIcons.BROWSER
    is WorldAddress.Unknown -> WorldIcons.PAGE_MARKDOWN
}

/** The page at [a]. */
@Composable
internal fun PageAt(h: NeueHolders, a: WorldAddress) {
    when (a) {
        is WorldAddress.Home -> HomePage(h, a.q)
        is WorldAddress.Board -> BoardPage(h, a.id)
        is WorldAddress.File -> FilePage(h, a.path)
        is WorldAddress.Run -> RunPage(h, a.t)
        is WorldAddress.Instrument -> InstrumentPage(h, a.name)
        is WorldAddress.App -> NoPage(h, a.format(), "an app opens in its own window, never a tab")
        is WorldAddress.Unknown -> NoPage(h, a.raw, a.why)
    }
}

/** A page's column: centred, at most [PAGE_MAX] wide, its head over its body. */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().widthIn(max = PAGE_MAX).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** A page's head (§4): the kind in micro caps, the title in h2, Ai's note, and where it came from. */
@Composable
private fun PageHead(kind: String, title: String, note: String = "", from: @Composable (() -> Unit)? = null) {
    val c = Mu.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Micro(kind, color = c.ink45)
        MuText(title, style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 2)
        if (note.isNotBlank()) Small(note, color = c.ink70, maxLines = 4)
        from?.invoke()
    }
}

// ---- A board ------------------------------------------------------------------------------------------------------

@Composable
private fun BoardPage(h: NeueHolders, id: String) {
    val world = h.world
    val w = world.open ?: return NoPage(h, WorldAddress.Board(id).format(), "no world is open")
    val b = w.board(id) ?: return NoPage(h, WorldAddress.Board(id).format(), "this page was taken down, or never pinned")
    val run = runOf(h, id)
    PageColumn {
        PageHead((b.type?.id ?: b.kind).uppercase(), b.title.ifBlank { b.id }, b.note) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val src = b.source
                if (src != null) {
                    Mono("from", color = Mu.colors.ink45)
                    if (src.startsWith("apps/")) {
                        val slug = src.removePrefix("apps/").substringBefore('/')
                        MicroLink(world.apps.manifest(slug)?.title ?: slug, { world.apps.windows.open(AppRef.Made(slug), WorldEvent.YOU) }, color = Mu.colors.ink)
                    } else {
                        MicroLink(src, { world.browser.go(WorldAddress.File(src).format()) }, color = Mu.colors.ink)
                    }
                }
                if (run != null) {
                    Mono("·", color = Mu.colors.ink45)
                    MicroLink("ran ${CLOCK.format(Date(run.t))}", { world.browser.go(WorldAddress.Run(run.t).format()) }, color = Mu.colors.ink)
                    run.run?.ms?.let { Mono("· $it ms", color = Mu.colors.ink45) }
                } else if (b.updated > 0) {
                    Mono((if (src != null) "· " else "") + "pinned ${CLOCK.format(Date(b.updated))}", color = Mu.colors.ink45)
                }
            }
        }
        Body(h, b, w.id)
    }
}

/** A board's body at the page's width: the room left under the head, or a tall enough page to scroll in a short window. */
@Composable
private fun ColumnScope.Body(h: NeueHolders, b: Board, worldId: String) {
    val c = Mu.colors
    Box(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).border(1.dp, c.ink12)) {
        BoardBody(h, b, worldId, Modifier.fillMaxSize())
    }
}

// ---- A file -------------------------------------------------------------------------------------------------------

@Composable
private fun FilePage(h: NeueHolders, path: String) {
    val world = h.world
    val w = world.open ?: return NoPage(h, WorldAddress.File(path).format(), "no world is open")
    val ext = path.substringAfterLast('.', "").lowercase()
    val text by produceState<String?>(null, w.id, path, world.files) { value = withContext(Dispatchers.IO) { world.read(path) } }
    val exists = path in world.files || WorldPaths.lang(path) != null && text != null
    PageColumn {
        PageHead("FILE", path) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MicroLink("Open in the Editor", {
                    world.saveEditor()
                    world.showFile(path)
                    world.apps.windows.open(BuiltInApp.EDITOR.ref, WorldEvent.YOU)
                }, color = Mu.colors.ink)
                if (WorldPaths.lang(path) != null) MicroLink("Run", {
                    world.saveEditor()
                    world.showFile(path)
                    world.runEditor()
                }, color = Mu.colors.ink)
            }
        }
        when {
            ext in setOf("png", "jpg", "jpeg", "gif", "webp") -> Body(h, Board("file", path, BoardKind.IMAGE.id, path), w.id)
            text == null && !exists -> Help("There is no $path in this world.")
            text == null -> Help("Reading…")
            ext == "md" || ext == "markdown" || ext == "txt" -> Body(h, Board("file", path, BoardKind.MARKDOWN.id, text.orEmpty()), w.id)
            ext == "csv" || ext == "tsv" -> Body(h, Board("file", path, BoardKind.TABLE.id, csvTable(text.orEmpty(), if (ext == "tsv") '\t' else ',')), w.id)
            ext == "json" -> Lines(prettyJson(text.orEmpty()))
            else -> Lines(text.orEmpty())
        }
    }
}

/** A file's lines, numbered and drawn lazily, so a large file reads without a stall. */
@Composable
private fun ColumnScope.Lines(text: String) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val lines = remember(text) { text.lines() }
    val width = lines.size.toString().length
    val list = rememberLazyListState()
    Box(Modifier.fillMaxWidth().weight(1f).border(1.dp, c.ink12)) {
        LazyColumn(Modifier.fillMaxSize().padding(vertical = 8.dp), state = list) {
            itemsIndexed(lines) { i, l ->
                Row(Modifier.padding(horizontal = 8.dp)) {
                    MuText((i + 1).toString().padStart(width), Modifier.padding(end = 12.dp), style = MuType.mono(f, 12.sp).copy(textAlign = TextAlign.End), color = c.ink45)
                    MuText(l, style = MuType.mono(f, 12.sp), color = c.ink)
                }
            }
        }
        ScrollbarFor(list)
    }
}

/** CSV as the World's table: the first row its columns, quoted fields read whole. At most [WorldTableRows] rows. */
internal fun csvTable(text: String, sep: Char = ','): String {
    fun fields(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == sep && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        out += cur.toString()
        return out
    }
    val rows = text.lineSequence().filter { it.isNotBlank() }.take(WorldTableRows + 1).map(::fields).toList()
    val head = rows.firstOrNull().orEmpty()
    val body = rows.drop(1)
    return JsonObject(
        mapOf(
            "columns" to JsonArray(head.map(::JsonPrimitive)),
            "rows" to JsonArray(body.map { r -> JsonArray(head.indices.map { JsonPrimitive(r.getOrElse(it) { "" }) }) }),
        ),
    ).toString()
}

private const val WorldTableRows = 300

private fun prettyJson(text: String): String = runCatching {
    kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), WorldCodec.json.parseToJsonElement(text))
}.getOrDefault(text)

// ---- A run --------------------------------------------------------------------------------------------------------

@Composable
private fun RunPage(h: NeueHolders, t: Long) {
    val world = h.world
    val c = Mu.colors
    val f = LocalMuFonts.current
    val e = world.activity.firstOrNull { it.t == t && it.kind == WorldEvent.Kind.RUN }
        ?: return NoPage(h, WorldAddress.Run(t).format(), "no run at that time in what this world remembers")
    val r = e.run
    PageColumn {
        PageHead("RUN", e.path ?: r?.path ?: e.text, "") {
            Mono("${CLOCK.format(Date(e.t))} · ${if (e.by == WorldEvent.AI) h.ai.name else "you"}" + (r?.let { " · ${it.ms} ms · " + if (it.ok) "ok" else "failed" }.orEmpty()), color = c.ink45)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r == null) {
                Help(e.text)
            } else {
                if (r.out.isNotBlank()) {
                    Micro("Output", color = c.ink45)
                    MuText(r.out, Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(10.dp), style = MuType.mono(f, 12.sp), color = c.ink70)
                    if (r.cut) Help("The output ran past what is kept: the whole of it is in out/, if it was written there.")
                }
                if (r.err.isNotBlank()) {
                    Micro("Error", color = c.ink45)
                    MuText(r.err, Modifier.fillMaxWidth().border(1.dp, c.ink).padding(10.dp), style = MuType.mono(f, 12.sp).copy(fontWeight = FontWeight.Medium), color = c.ink)
                }
                if (r.boards.isNotEmpty()) {
                    Micro("Pages it pinned", color = c.ink45)
                    r.boards.forEach { id ->
                        val b = world.open?.board(id)
                        PageRow(h, WorldAddress.Board(id).format(), b?.title ?: "$id (taken down)", b?.type?.id ?: "", WorldIcons.page(b?.type))
                    }
                }
                r.path?.let { p -> if (r.lang != "instrument") MicroLink("Open $p", { world.browser.go(WorldAddress.File(p).format()) }, color = c.ink) }
            }
        }
    }
}

// ---- An instrument ------------------------------------------------------------------------------------------------

@Composable
private fun InstrumentPage(h: NeueHolders, name: String) {
    val spec = Instruments.ALL.firstOrNull { it.name == name } ?: return NoPage(h, WorldAddress.Instrument(name).format(), "there is no such instrument")
    val form = InstrumentForm.of(name)
    val c = Mu.colors
    PageColumn {
        PageHead("INSTRUMENT", form?.question ?: spec.short, spec.summary)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Micro("Its arguments", color = c.ink45)
            form?.fields?.forEach { fl ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Mono(fl.name, Modifier.widthIn(min = 120.dp), color = c.ink)
                    Small(fl.label + (if (fl.hint.isNotBlank()) " — ${fl.hint}" else "") + (if (fl.default.isNotBlank()) " (default ${fl.default})" else ""), color = c.ink70)
                }
            } ?: Small(spec.args, color = c.ink70)
            Micro("From code", color = c.ink45)
            Mono("ygo.tools.run('$name', { … })   ·   world_tool $name", color = c.ink70)
            MuButton("Run it in Instruments", {
                h.world.apps.instrumentsPick = name
                h.world.apps.windows.open(BuiltInApp.INSTRUMENTS.ref, WorldEvent.YOU)
            }, variant = BtnVariant.PRIMARY, size = BtnSize.SM, arrow = true)
        }
    }
}

// ---- Home and no page ---------------------------------------------------------------------------------------------

/** The new-tab page (§4): every page of the world, newest first, grouped by the run or turn that made it; files; apps. */
@Composable
internal fun HomePage(h: NeueHolders, q: String?) {
    val world = h.world
    val c = Mu.colors
    val w = world.open
    var filter by remember(q) { mutableStateOf(q.orEmpty()) }
    if (w == null) {
        EmptyState("No world open.", "Open one from the world's name, or ask ${h.ai.name} to try something here.")
        return
    }
    val groups = remember(w.boards, world.activity, filter) { WorldHome.groups(w, world.activity, filter) }
    val needle = filter.trim().lowercase()
    val files = remember(world.files, filter) { world.files.filter { needle.isEmpty() || needle in it.lowercase() } }
    val apps = world.apps.list.filter { needle.isEmpty() || needle in it.title.lowercase() || needle in it.description.lowercase() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val list = rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().widthIn(max = PAGE_MAX), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Micro("HOME", color = c.ink45)
                    MuText(w.title, style = MuType.h2(LocalMuFonts.current), color = c.ink, maxLines = 2)
                    Mono(WorldHome.summary(w.boards.size, world.files.size, world.apps.list.size).ifEmpty { "Nothing here yet" }, color = c.ink45)
                    MuInput(filter, { filter = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = "Filter the pages, files and apps", dense = true)
                }
            }
            if (groups.isEmpty()) {
                item { Help(if (needle.isEmpty()) "No pages yet. What ${h.ai.name} shows you — charts, webs, tables, cards — opens here, a page a tab." else "No page matches.", Modifier.padding(top = 16.dp)) }
            }
            groups.forEach { g ->
                item(key = "g:${g.title}:${g.at}") {
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val run = g.run
                        if (run != null) MicroLink(g.title, { world.browser.go(WorldAddress.Run(run).format()) }, color = c.ink70) else Micro(g.title, color = c.ink70)
                        Mono(CLOCK.format(Date(g.at)), color = c.ink45)
                    }
                }
                items(g.boards, key = { "b:${it.id}" }) { b ->
                    PageRow(h, WorldAddress.Board(b.id).format(), b.title.ifBlank { b.id }, b.type?.id ?: b.kind, WorldIcons.page(b.type), b.note)
                }
            }
            if (files.isNotEmpty()) {
                item { Micro("Files", Modifier.padding(top = 24.dp, bottom = 4.dp), color = c.ink70) }
                items(files, key = { "f:$it" }) { p -> PageRow(h, WorldAddress.File(p).format(), p, p.substringAfterLast('.', ""), pageGlyph(h, WorldAddress.File(p))) }
            }
            if (apps.isNotEmpty()) {
                item { Micro("Apps", Modifier.padding(top = 24.dp, bottom = 4.dp), color = c.ink70) }
                items(apps, key = { "a:${it.slug}" }) { m ->
                    HomeAppRow(h, m.slug, m.title, m.description, m.tile)
                }
            }
        }
        ScrollbarFor(list)
    }
}

/** One page in a list: its glyph, its title, its kind; a click opens it here, a middle-click in a new tab. */
@Composable
private fun PageRow(h: NeueHolders, address: String, title: String, kind: String, glyph: Icon, note: String = "") {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot) c.ink06 else c.paper))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .onPointer(PointerEventType.Press) { e -> if (e.buttons.isTertiaryPressed) h.world.browser.goNew(address) }
            .muClickable(interactionSource = source) { h.world.browser.go(address) }
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorldIcon(glyph, GLYPH_SMALL)
        Column(Modifier.weight(1f)) {
            Small(title, color = c.ink, maxLines = 1)
            if (note.isNotBlank()) Small(note, color = c.ink45, maxLines = 1)
        }
        if (kind.isNotBlank()) Mono(kind, color = c.ink45)
    }
}

@Composable
private fun HomeAppRow(h: NeueHolders, slug: String, title: String, description: String, tile: com.kaiharimoto.mastertool.core.world.desk.Tile) {
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(animatedColor(if (hot) c.ink06 else c.paper))
            .hoverable(source)
            .cursorPointer(caption = "Open")
            .muClickable(interactionSource = source) { h.world.apps.windows.open(AppRef.Made(slug), WorldEvent.YOU) }
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorldTile(tile, 20.dp)
        Column(Modifier.weight(1f)) {
            Small(title, color = c.ink, maxLines = 1)
            if (description.isNotBlank()) Small(description, color = c.ink45, maxLines = 1)
        }
        Mono("by Ai", color = c.ink45)
    }
}

/** There is no such page (§4): why, and the nearest titles. */
@Composable
private fun NoPage(h: NeueHolders, raw: String, why: String) {
    val world = h.world
    val words = remember(raw) { raw.substringAfterLast('/').lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 } }
    val near = remember(raw, world.open?.boards) {
        world.open?.boards.orEmpty().map { b -> b to words.count { it in b.title.lowercase() || it in b.id.lowercase() } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(3).map { it.first }
    }
    PageColumn {
        PageHead("NO SUCH PAGE", raw.ifBlank { "Nothing" }, "There is no page here: $why.")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            near.forEach { b -> PageRow(h, WorldAddress.Board(b.id).format(), b.title.ifBlank { b.id }, b.type?.id ?: b.kind, WorldIcons.page(b.type), "Did you mean this?") }
            MicroLink("Every page of this world →", { world.browser.go(WorldAddress.HOME) }, Modifier.padding(top = 8.dp), color = Mu.colors.ink)
        }
    }
}
