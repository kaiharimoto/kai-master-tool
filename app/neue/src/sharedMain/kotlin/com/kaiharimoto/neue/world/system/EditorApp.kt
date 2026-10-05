package com.kaiharimoto.neue.world.system

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.world.desk.Anchor
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskRect
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.MicroLink
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.kit.reportsTextFocus
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/*
 * The Editor (`docs/world/DESKTOP.md` §3, `Alt 2`): one file at a time — 1.0.97's pane in a window. Its bar: Run, the
 * language, the file (a menu of the world's files), and the state — Ai typing with Skip and Take over, the person's edit
 * with Save, or Saved. Ai's caret is reported to the avatar, which rides 8 dp to its right as the code types in. A file
 * over [IN_PLACE] opens read-only, its lines drawn lazily, with Open as page.
 */

/** Up to this many characters edit in place (§11). */
internal const val IN_PLACE = 1_000_000

private fun kbd(action: DeskAction): String? = DeskShortcuts.chordFor(action)?.let(DeskShortcuts::kbd)

/** The Editor's title-bar tools: none; its own bar is inside the window, where Run is beside the code. */
@Composable
internal fun RowScope.EditorTools(h: NeueHolders) = Unit

/** The phone switcher's line: `openings.js · line 41`, and Ai at work. */
internal fun editorSummary(h: NeueHolders): String {
    val w = h.world
    val path = w.editorPath ?: return "No file open"
    val lines = w.editorText.count { it == '\n' } + 1
    return "$path · line $lines" + if (w.typing) " · Ai at work" else ""
}

@Composable
internal fun EditorApp(h: NeueHolders, modifier: Modifier, phone: Boolean) {
    val world = h.world
    Column(modifier) {
        EditorBar(h, phone)
        val path = world.editorPath
        when {
            path == null -> Help("No file open: pick one in Files, or ask Ai to write one.", Modifier.padding(12.dp))
            (world.sizeOf(path) ?: 0L) > IN_PLACE -> LargeFile(h, path)
            else -> CodeField(h)
        }
    }
}

@Composable
private fun EditorBar(h: NeueHolders, phone: Boolean) {
    val world = h.world
    val c = Mu.colors
    val path = world.editorPath
    val at = remember { arrayOf(Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (phone) 44.dp else 36.dp)
            .drawBehind { drawLine(c.ink12, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Tip("Run the file", kbd = kbd(DeskAction.WORLD_RUN)) {
            MuButton("Run", { world.runEditor() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, enabled = world.runBlocked == null, reason = world.runBlocked, arrow = true)
        }
        if (path != null) Mono(languageOf(path), color = c.ink45)
        if (path != null && !phone) {
            // The file's name is a menu of the world's files (§3).
            val source = remember { MutableInteractionSource() }
            val hot by source.collectIsHotAsState()
            Row(
                Modifier
                    .onGloballyPositioned { at[0] = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }
                    .hoverable(source)
                    .cursorPointer(caption = "Files")
                    .muClickable(interactionSource = source) {
                        h.neue.menu = MenuSpec(at[0], world.files.map { f -> MenuEntry(f, enabled = f != path, reason = "Open now") { openFile(h, f) } })
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Mono(path, Modifier.widthIn(max = 240.dp), color = animatedColor(if (hot) c.ink else c.ink70), size = 12.sp)
                Mono("▾", color = c.ink45)
            }
        }
        Spacer(Modifier.weight(1f))
        when {
            world.typing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Breathe()
                Small(if (phone) "Typing" else "${h.ai.name} is typing", color = c.ink70, maxLines = 1)
                Tip("Finish the typing at once", kbd = kbd(DeskAction.WORLD_SKIP)) { MicroLink("Skip", { world.skip() }, color = c.ink) }
                if (!phone) Tip("The editor is yours: Ai's typing ends, and it may not overwrite your change") { MicroLink("Take over", { world.takeOver() }, color = c.ink) }
            }
            world.edited -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Small("Edited", color = c.ink70, maxLines = 1)
                MicroLink("Save", { world.saveEditor() }, color = c.ink)
            }
            path != null -> Small("Saved", color = c.ink45, maxLines = 1)
        }
    }
}

/** The code, typed into by Ai or the person; the line numbers beside it; the caret reported to the avatar. */
@Composable
private fun CodeField(h: NeueHolders) {
    val world = h.world
    val c = Mu.colors
    val f = LocalMuFonts.current
    val style = MuType.mono(f, 13.sp).copy(color = c.ink, lineHeight = 22.sp)
    val text = world.editorText
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    val numbers = remember(lines) { (1..lines).joinToString("\n") }
    val gutter = (lines.toString().length * 8 + 26).dp
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    // While Ai types, the editor keeps the line being written in view.
    LaunchedEffect(world.typing, lines) { if (world.typing) vertical.scrollTo(vertical.maxValue) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    // The caret's place, reported from layout: the field's coordinates and its last text layout, plain.
    val laid = remember { arrayOfNulls<Any>(2) }
    fun reportCaret() {
        val coords = laid[0] as? LayoutCoordinates ?: return
        val layout = laid[1] as? TextLayoutResult ?: return
        if (!coords.isAttached) return
        val end = layout.layoutInput.text.length
        val r = layout.getCursorRect(end)
        val tl = coords.localToWindow(r.topLeft)
        val desk = world.desk
        val d = desk.density
        desk.avatar.report(
            BuiltInApp.EDITOR.id,
            Anchor.CARET,
            DeskRect(((tl.x - desk.origin.x) / d).toDouble(), ((tl.y - desk.origin.y) / d).toDouble(), 2.0, (r.height / d).toDouble()),
        )
    }
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(vertical)) {
            val room = maxWidth
            Row(Modifier.padding(vertical = 10.dp)) {
                MuText(numbers, Modifier.width(gutter).padding(end = 14.dp), style = style.copy(color = c.ink45, textAlign = TextAlign.End))
                Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                    BasicTextField(
                        value = text,
                        onValueChange = { world.personEdited(it) },
                        readOnly = world.typing,
                        textStyle = style,
                        cursorBrush = SolidColor(c.ink),
                        interactionSource = source,
                        onTextLayout = { laid[1] = it; reportCaret() },
                        modifier = Modifier
                            .widthIn(min = room - gutter - 8.dp)
                            .onGloballyPositioned { laid[0] = it; reportCaret() }
                            .cursor(CursorMode.TEXT, fontSize = 13.sp, singleLine = false, focused = focused, reason = if (world.typing) "Ai is still typing" else null)
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

/** A file too large to edit in place: its lines, read-only and drawn as they scroll into view, and Open as page. */
@Composable
private fun LargeFile(h: NeueHolders, path: String) {
    val c = Mu.colors
    val f = LocalMuFonts.current
    val lines = remember(h.world.editorText) { h.world.editorText.lines() }
    val list = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Small("Over a megabyte: read-only here.", color = c.ink70, maxLines = 1)
            MicroLink("Open as page", { openAddress(h, WorldAddress.File(path)) }, color = c.ink)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                itemsIndexed(lines) { i, line ->
                    Row {
                        MuText((i + 1).toString(), Modifier.width(64.dp).padding(end = 14.dp), style = MuType.mono(f, 13.sp).copy(color = c.ink45, textAlign = TextAlign.End), maxLines = 1)
                        MuText(line, style = MuType.mono(f, 13.sp), color = c.ink, maxLines = 1)
                    }
                }
            }
            ScrollbarFor(list)
        }
    }
}
