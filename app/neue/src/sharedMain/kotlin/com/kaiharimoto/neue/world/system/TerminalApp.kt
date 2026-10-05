package com.kaiharimoto.neue.world.system

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.world.Instruments
import com.kaiharimoto.mastertool.core.world.TerminalCommand
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.TermLine

/*
 * The Terminal (`docs/world/DESKTOP.md` §3, `Alt 3`): each run's command and its output streaming, as 1.0.97's pane did,
 * and a command line of its own (§3.1, `TerminalCommand`): a terminal you cannot type in reads as a picture of one.
 * `↑` recalls, Tab completes file names and instruments. Ai never types here; its runs print here. The line printing
 * now is reported to the avatar, which waits beside it while the run lasts.
 */

/** The Terminal's title-bar tools: Clear, once there is something to clear and nothing runs. */
@Composable
internal fun RowScope.TerminalTools(h: NeueHolders) {
    val world = h.world
    if (world.terminal.isNotEmpty() && world.running == null) MicroLink("Clear", { world.terminal.clear() }, color = Mu.colors.ink45)
}

/** The phone switcher's line: the Terminal's last line. */
internal fun terminalSummary(h: NeueHolders): String =
    h.world.running?.let { "Running $it" } ?: h.world.terminal.lastOrNull()?.text?.take(80) ?: "Nothing has run yet"

@Composable
internal fun TerminalApp(h: NeueHolders, modifier: Modifier, phone: Boolean) {
    val world = h.world
    val c = Mu.colors
    val f = LocalMuFonts.current
    val lines = world.terminal
    val list = rememberLazyListState()
    FollowEnd(list, world.open?.id, lines.size)
    val base = MuType.mono(f, 12.sp).copy(lineHeight = 20.sp)
    // The last line's place and its words' width, for the avatar to wait beside (§5.2).
    val last = remember { arrayOfNulls<Any>(2) }
    fun reportLine() {
        val coords = last[0] as? LayoutCoordinates ?: return
        val layout = last[1] as? TextLayoutResult ?: return
        if (!coords.isAttached) return
        val b = coords.boundsInWindow()
        val desk = world.desk
        val d = desk.density
        val width = layout.getLineRight(maxOf(0, layout.lineCount - 1))
        val lineTop = layout.getLineTop(maxOf(0, layout.lineCount - 1))
        val lineH = layout.getLineBottom(maxOf(0, layout.lineCount - 1)) - lineTop
        desk.avatar.report(
            BuiltInApp.TERMINAL.id,
            Anchor.LINE,
            DeskRect(((b.left - desk.origin.x) / d).toDouble(), ((b.top + lineTop - desk.origin.y) / d).toDouble(), (width / d).toDouble(), (lineH / d).toDouble()),
        )
    }
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Help(if (world.running != null) "Starting…" else "Nothing has run yet. Ctrl Enter runs the file in the editor; help lists what you can type below.", Modifier.padding(14.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                itemsIndexed(lines) { i, line ->
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
                    val isLast = i == lines.size - 1 && world.running == null
                    BasicText(
                        shown,
                        style = style.copy(color = colour),
                        modifier = if (isLast) Modifier.onGloballyPositioned { last[0] = it; reportLine() } else Modifier,
                        onTextLayout = { if (isLast) { last[1] = it; reportLine() } },
                    )
                }
                world.running?.let { r ->
                    item {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Breathe()
                            BasicText(
                                "running $r",
                                style = base.copy(color = c.ink45),
                                modifier = Modifier.onGloballyPositioned { last[0] = it; reportLine() },
                                onTextLayout = { last[1] = it; reportLine() },
                            )
                        }
                    }
                }
            }
            ScrollbarFor(list)
        }
        Prompt(h, phone)
    }
}

/** The command line: `›`, what is typed, Enter runs it (§3.1). */
@Composable
private fun Prompt(h: NeueHolders, phone: Boolean) {
    val world = h.world
    val c = Mu.colors
    val f = LocalMuFonts.current
    val style = MuType.mono(f, 12.sp).copy(color = c.ink)
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    fun set(text: String) {
        value = TextFieldValue(text, TextRange(text.length))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (phone) 44.dp else 34.dp)
            .drawBehind { drawLine(c.ink12, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Mono("›", color = c.ink, size = 13.sp)
        Box(Modifier.weight(1f)) {
            if (value.text.isEmpty() && !focused) Mono("run, js, tool, open, ls, cat, help", color = c.ink25, size = 12.sp)
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(c.ink),
                interactionSource = source,
                modifier = Modifier
                    .fillMaxWidth()
                    .cursor(CursorMode.TEXT, fontSize = 12.sp, focused = focused)
                    .reportsTextFocus()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                val line = value.text
                                set("")
                                runLine(h, line)
                                true
                            }
                            Key.DirectionUp -> {
                                world.desk.terminalHistory.up()?.let(::set)
                                true
                            }
                            Key.DirectionDown -> {
                                world.desk.terminalHistory.down()?.let(::set)
                                true
                            }
                            Key.Tab -> {
                                val done = TerminalCommand.complete(value.text, world.files, Instruments.ALL.map { it.name })
                                set(done.line)
                                if (done.choices.isNotEmpty()) world.print(TermLine.Kind.NOTE, done.choices.joinToString("   "))
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
    }
}

/** A line typed at the Terminal, run as the person's own (`by: you`). */
internal fun runLine(h: NeueHolders, line: String) = runLineOn(h.world, line) { openAddress(h, it) }

/** [runLine] on [world] alone; [open] is where an `open` goes (the desk's windows). */
internal fun runLineOn(world: com.kaiharimoto.neue.world.Worlds, line: String, open: (com.kaiharimoto.mastertool.core.world.desk.WorldAddress) -> Unit = {}) {
    val cmd = TerminalCommand.parse(line)
    if (cmd !is TerminalCommand.Blank) {
        world.desk.terminalHistory.push(line)
        world.print(TermLine.Kind.COMMAND, line.trim())
    }
    when (cmd) {
        TerminalCommand.Blank -> Unit
        is TerminalCommand.Wrong -> world.print(TermLine.Kind.ERR, cmd.why)
        TerminalCommand.Clear -> world.terminal.clear()
        TerminalCommand.Help -> TerminalCommand.HELP.forEach { (form, what) -> world.print(TermLine.Kind.NOTE, form.padEnd(20) + what) }
        is TerminalCommand.Run -> world.launch { world.run(cmd.path, null, null, by = WorldEvent.YOU) }
        is TerminalCommand.Js -> world.launch { world.run(null, cmd.code, WorldPaths.LANG_JS, by = WorldEvent.YOU) }
        is TerminalCommand.Py -> world.launch { world.run(null, cmd.code, WorldPaths.LANG_PY, by = WorldEvent.YOU) }
        is TerminalCommand.Tool -> world.launch { world.tool(cmd.name, cmd.args, by = WorldEvent.YOU) }
        is TerminalCommand.Open -> open(cmd.address)
        is TerminalCommand.Ls -> {
            val under = cmd.path?.trimEnd('/')?.let { "$it/" }
            val shown = world.files.filter { under == null || it.startsWith(under) }
            if (shown.isEmpty()) world.print(TermLine.Kind.NOTE, if (under == null) "No files yet." else "Nothing under $under")
            shown.forEach { world.print(TermLine.Kind.OUT, it) }
        }
        is TerminalCommand.Cat -> {
            val text = world.peek(cmd.path)
            if (text == null) {
                world.print(TermLine.Kind.ERR, "There is no ${cmd.path}.")
            } else {
                val all = text.lines()
                all.take(CAT_LINES).forEach { world.print(TermLine.Kind.OUT, it) }
                if (all.size > CAT_LINES) world.print(TermLine.Kind.NOTE, "… ${all.size - CAT_LINES} more lines: open ${cmd.path} in the Editor")
            }
        }
    }
}

/** `cat` prints at most this many lines. */
private const val CAT_LINES = 400
