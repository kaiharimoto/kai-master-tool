package com.kaiharimoto.neue.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.text.ChatFollow
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.ActivityLine
import com.kaiharimoto.neue.ai.ReasoningView
import com.kaiharimoto.neue.ai.ReplyView
import com.kaiharimoto.neue.ai.avatar.AiMark
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

/*
 * Ai World's panes (1.0.97, kai: "I want to see everything the Ai is doing"): its files, the editor it types into,
 * the terminal its runs print to, the boards it pins, its thoughts as they stream, and everything it did. Each pane is
 * a square ink frame with a micro-caps title; the one Ai is working in says so with its still mark — nothing glides
 * after it, since only cards move.
 */

/** A pane's frame: numeral, title, "Ai is here", its own controls, and maximise. */
@Composable
internal fun Pane(
    h: NeueHolders,
    pane: WorldPane,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val world = h.world
    val c = Mu.colors
    val here = world.aiPane == pane
    Column(modifier.let { if (framed) it.border(1.dp, c.ink) else it }) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .drawBehind { drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Mono((pane.ordinal + 1).toString().padStart(2, '0'), color = if (world.focus == pane) c.ink else c.ink45)
            Micro(pane.title, color = c.ink)
            if (here) {
                // Where Ai is working now: its mark, still, and the words — the pane does not move.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AiMark(14.dp, name = h.ai.name)
                    Micro("${h.ai.name} is here", color = c.ink70)
                }
            }
            Spacer(Modifier.weight(1f))
            trailing()
            if (!LocalPhone.current) {
                val big = world.maximized == pane
                Tip(if (big) "Back to every pane" else "Give this pane the page", kbd = "Alt ${pane.ordinal + 1}") {
                    IconButton(
                        if (big) Icons.Minimize else Icons.Maximize,
                        { world.maximized = if (big) null else pane; world.focus = pane },
                        size = 24.dp,
                        label = if (big) "Restore" else "Maximise",
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds(), content = content)
    }
}

// ---- Files --------------------------------------------------------------------------------------------------------

/** One row of the file tree: a folder heading, or a file. */
private data class TreeRow(val path: String, val name: String, val depth: Int, val folder: Boolean)

private fun tree(files: List<String>): List<TreeRow> = buildList {
    var open = emptyList<String>()
    files.forEach { path ->
        val parts = path.split('/')
        val dirs = parts.dropLast(1)
        val shared = dirs.zip(open).takeWhile { (a, b) -> a == b }.size
        for (d in shared until dirs.size) add(TreeRow(dirs.take(d + 1).joinToString("/") + "/", dirs[d], d, folder = true))
        open = dirs
        add(TreeRow(path, parts.last(), dirs.size, folder = false))
    }
}

@Composable
internal fun FilesPane(h: NeueHolders) {
    val world = h.world
    val c = Mu.colors
    val rows = remember(world.files) { tree(world.files) }
    if (rows.isEmpty()) {
        Help("No files yet.", Modifier.padding(12.dp))
        return
    }
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
            items(rows, key = { it.path }) { row ->
                if (row.folder) {
                    Row(Modifier.fillMaxWidth().padding(start = (10 + row.depth * 12).dp, top = 6.dp, bottom = 2.dp)) {
                        Micro(row.name + "/", color = c.ink45)
                    }
                } else {
                    FileRow(h, row)
                }
            }
        }
        ScrollbarFor(list)
    }
}

@Composable
private fun FileRow(h: NeueHolders, row: TreeRow) {
    val world = h.world
    val open = world.editorPath == row.path
    val source = remember { MutableInteractionSource() }
    Inverted(open) {
        val c = Mu.colors
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (open) c.paper else androidx.compose.ui.graphics.Color.Transparent)
                .hoverable(source)
                .cursorPointer(caption = "Open")
                .muClickable(interactionSource = source) {
                    if (world.editorPath != row.path) {
                        world.saveEditor()
                        world.showFile(row.path)
                    }
                    world.focus = WorldPane.EDITOR
                }
                .padding(start = (10 + row.depth * 12).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MuText(row.name, Modifier.weight(1f), style = MuType.mono(LocalMuFonts.current, 12.sp), color = c.ink, maxLines = 1)
            WorldPaths.lang(row.path)?.let { Mono(it, color = c.ink45, size = 10.sp) }
        }
    }
}

// ---- The editor ---------------------------------------------------------------------------------------------------

/** What the editor's title strip carries: the path, Ai typing (and Skip), an edit not saved yet (and Save). */
@Composable
internal fun RowScope.EditorStatus(h: NeueHolders) {
    val world = h.world
    val c = Mu.colors
    if (world.typing) {
        Breathe()
        Micro("Typing", color = c.ink70)
        MicroLink("Skip", { world.skipTyping = true }, color = c.ink)
    } else if (world.edited) {
        Micro("Edited", color = c.ink70)
        MicroLink("Save", { world.saveEditor() }, color = c.ink)
    }
    world.editorPath?.let { Mono(it, Modifier.widthIn(max = 220.dp), color = c.ink45) }
}

@Composable
internal fun EditorPane(h: NeueHolders) {
    val world = h.world
    val c = Mu.colors
    val f = LocalMuFonts.current
    if (world.editorPath == null) {
        Help("No file open: pick one in Files, or ask Ai to write one.", Modifier.padding(12.dp))
        return
    }
    val style = MuType.mono(f, 12.sp).copy(color = c.ink, lineHeight = 18.sp)
    val text = world.editorText
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    val numbers = remember(lines) { (1..lines).joinToString("\n") }
    val gutter = (lines.toString().length * 8 + 20).dp
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    // While Ai types, the editor keeps the line being written in view.
    LaunchedEffect(world.typing, lines) { if (world.typing) vertical.scrollTo(vertical.maxValue) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(vertical)) {
            val room = maxWidth
            Row(Modifier.padding(vertical = 8.dp)) {
                MuText(numbers, Modifier.width(gutter).padding(end = 10.dp), style = style.copy(color = c.ink45, textAlign = TextAlign.End))
                Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (!world.typing) { world.editorText = it; world.edited = true } },
                        readOnly = world.typing,
                        textStyle = style,
                        cursorBrush = SolidColor(c.ink),
                        interactionSource = source,
                        modifier = Modifier
                            .widthIn(min = room - gutter - 8.dp)
                            .cursor(CursorMode.TEXT, fontSize = 12.sp, singleLine = false, focused = focused)
                            .reportsTextFocus()
                            .onFocusChanged { if (!it.isFocused) world.saveEditor() }
                            .padding(end = 16.dp),
                    )
                }
            }
        }
        ScrollbarFor(vertical)
    }
}

// ---- The terminal -------------------------------------------------------------------------------------------------

/** Keeps [list] at its end as it grows, only while the reader is there (`ChatFollow`, the chat's rule). */
@Composable
private fun FollowEnd(list: LazyListState, key: Any?, size: Int) {
    val follow = remember(key) { ChatFollow() }
    val ours = remember { booleanArrayOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (!moving && !ours[0]) follow.readerScrolled(atEnd = !list.canScrollForward)
        }
    }
    LaunchedEffect(size) {
        if (size == 0 || !follow.shouldFollow(readerScrolling = list.isScrollInProgress)) return@LaunchedEffect
        ours[0] = true
        try {
            list.scrollToItem(size - 1, 1_000_000)
        } finally {
            ours[0] = false
        }
    }
}

@Composable
internal fun TerminalPane(h: NeueHolders) {
    val world = h.world
    val c = Mu.colors
    val f = LocalMuFonts.current
    val lines = world.terminal
    val list = rememberLazyListState()
    FollowEnd(list, world.open?.id, lines.size)
    val base = MuType.mono(f, 12.sp).copy(lineHeight = 17.sp)
    Box(Modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Help(if (world.running != null) "Starting…" else "Nothing has run yet. Ctrl Enter runs the file in the editor.", Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            itemsIndexed(lines) { _, line ->
                // No colour: what went wrong is heavier and slanted, what was asked is in full ink, notes recede.
                val (style, colour) = when (line.kind) {
                    TermLine.Kind.COMMAND -> base.copy(fontWeight = FontWeight.Medium) to c.ink
                    TermLine.Kind.ERR -> base.copy(fontWeight = FontWeight.Medium, fontStyle = FontStyle.Italic) to c.ink
                    TermLine.Kind.NOTE -> base to c.ink45
                    TermLine.Kind.OUT -> base to c.ink70
                }
                val shown = when (line.kind) {
                    TermLine.Kind.COMMAND -> "$ " + line.text
                    TermLine.Kind.ERR -> "✕ " + line.text
                    else -> line.text
                }
                MuText(shown, style = style, color = colour)
            }
            world.running?.let { r ->
                item {
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Breathe()
                        Mono("running $r", color = c.ink45)
                    }
                }
            }
        }
        ScrollbarFor(list)
    }
}

// ---- Activity -----------------------------------------------------------------------------------------------------

private val CLOCK = SimpleDateFormat("HH:mm:ss")

@Composable
internal fun ActivityPane(h: NeueHolders) {
    val world = h.world
    val events = world.activity
    if (events.isEmpty()) {
        Help("Nothing has happened in this world yet.", Modifier.padding(12.dp))
        return
    }
    val list = rememberLazyListState()
    // Newest first: what just happened is at the top, where the eye starts.
    val shown = remember(events) { events.withIndex().reversed() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items(shown, key = { "${it.index}:${it.value.t}" }) { (_, e) -> EventRow(h, e) }
        }
        ScrollbarFor(list)
    }
}

@Composable
private fun EventRow(h: NeueHolders, e: WorldEvent) {
    val world = h.world
    val c = Mu.colors
    val target = e.path != null || e.board != null
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (!target) it else it.hoverable(source)
                    .cursorPointer(caption = if (e.board != null) "Show" else "Open")
                    .muClickable(interactionSource = source) {
                        val board = e.board
                        val path = e.path
                        if (board != null && world.open?.board(board) != null) {
                            world.browser.show(com.kaiharimoto.mastertool.core.world.desk.WorldAddress.Board(board).format())
                        } else if (path != null && path in world.files) {
                            world.saveEditor()
                            world.showFile(path)
                            world.focus = WorldPane.EDITOR
                        }
                    }
            }
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Mono(CLOCK.format(Date(e.t)), color = c.ink45, size = 10.sp)
        Box(Modifier.width(30.dp)) {
            if (e.by == WorldEvent.AI) AiMark(14.dp, name = h.ai.name) else Micro("You", color = c.ink70)
        }
        val failed = e.run?.ok == false
        MuText(
            e.text,
            Modifier.weight(1f),
            style = MuType.small(LocalMuFonts.current).copy(fontStyle = if (failed) FontStyle.Italic else FontStyle.Normal, fontWeight = if (failed) FontWeight.Medium else FontWeight.Normal),
            color = if (target) c.ink else c.ink70,
            maxLines = 2,
        )
    }
}
