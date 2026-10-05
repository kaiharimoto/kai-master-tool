package com.kaiharimoto.neue.world.system

import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.world.type.WorldType
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.apps.AppPaths
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.world.type.Help
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.world.type.Micro
import com.kaiharimoto.neue.world.type.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.ScrollbarFor
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.desk.DeskDialog
import com.kaiharimoto.neue.world.desk.deskPointer

/*
 * Files (`docs/world/DESKTOP.md` §3, `Alt 1`): the world's tree, `files/` and `apps/` as two roots. A click picks a
 * file, a double-click (a finger's tap) opens it — code and text in the Editor, markdown, pictures, CSV and JSON as pages
 * in the Browser. Right-click (a held finger): Open, Open as page, Run, Rename, Delete (confirmed). New file in its bar.
 */

/** One row of the tree: a root or folder heading, a file, or an app's code. */
private data class TreeRow(val path: String, val name: String, val depth: Int, val folder: Boolean, val app: String? = null)

private fun tree(files: List<String>): List<TreeRow> = buildList {
    var open = emptyList<String>()
    files.forEach { path ->
        val parts = path.split('/')
        val dirs = parts.dropLast(1)
        val shared = dirs.zip(open).takeWhile { (a, b) -> a == b }.size
        for (d in shared until dirs.size) add(TreeRow(dirs.take(d + 1).joinToString("/") + "/", dirs[d], d + 1, folder = true))
        open = dirs
        add(TreeRow(path, parts.last(), dirs.size + 1, folder = false))
    }
}

/** Files' title-bar tools: New file. */
@Composable
internal fun RowScope.FilesTools(h: NeueHolders) {
    if (h.world.open != null) {
        Tip("A new file in this world") {
            IconButton(Icons.Plus, { h.world.desk.dialog = DeskDialog.NewFile }, size = 24.dp, label = "New file")
        }
    }
}

@Composable
internal fun FilesApp(h: NeueHolders, modifier: Modifier, phone: Boolean) {
    val world = h.world
    val apps = world.desk.apps
    val rows = remember(world.files, apps) {
        buildList {
            add(TreeRow("files/", "files", 0, folder = true))
            addAll(tree(world.files))
            if (apps.isNotEmpty()) {
                add(TreeRow("${AppPaths.ROOT}/", AppPaths.ROOT, 0, folder = true))
                apps.forEach { a ->
                    add(TreeRow("${AppPaths.ROOT}/${a.slug}/", a.slug, 1, folder = true))
                    add(TreeRow("${AppPaths.ROOT}/${a.slug}/${AppPaths.CODE}", AppPaths.CODE, 2, folder = false, app = a.slug))
                }
            }
        }
    }
    var picked by remember(world.open?.id) { mutableStateOf<String?>(null) }
    Box(modifier) {
        if (world.open == null) {
            Help("No world is open.", Modifier.padding(12.dp))
        } else {
            val list = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 6.dp)) {
                items(rows, key = { it.path }) { row ->
                    if (row.folder) {
                        Row(Modifier.fillMaxWidth().padding(start = (12 + row.depth * 12).dp, top = 8.dp, bottom = 2.dp)) {
                            Micro(row.name + "/", color = Mu.colors.ink45)
                        }
                    } else {
                        FileRow(h, row, picked == row.path, phone) { picked = row.path }
                    }
                }
            }
            ScrollbarFor(list)
            if (world.files.isEmpty()) Help("No files yet.", Modifier.padding(start = 24.dp, top = 40.dp))
        }
    }
}

@Composable
private fun FileRow(h: NeueHolders, row: TreeRow, picked: Boolean, phone: Boolean, onPick: () -> Unit) {
    val world = h.world
    val c = Mu.colors
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val at = remember { arrayOf(Offset.Zero) }
    val open = world.editorPath == row.path
    val touch = LocalTouchFirst.current
    fun openIt() {
        if (row.app != null) world.desk.open(AppRef.Made(row.app)) else openFile(h, row.path)
    }
    val fill = when {
        picked -> c.ink
        hot -> c.ink06
        else -> Color.Transparent
    }
    val ink = if (picked) c.paper else c.ink
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (phone || touch) 40.dp else 28.dp)
            .onGloballyPositioned { at[0] = it.positionInWindow() }
            .background(animatedColor(fill))
            .hoverable(source)
            .cursorPointer(caption = if (picked) "Open" else "Select")
            .onContextMenu { local -> onPick(); h.neue.menu = MenuSpec(at[0] + local, menu(h, row)) }
            .deskPointer(
                row.path,
                onTap = { finger -> if (finger) openIt() else onPick() },
                onDouble = { openIt() },
            )
            .padding(start = (12 + row.depth * 12).dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuText(row.name, Modifier.weight(1f), style = WorldType.mono(LocalMuFonts.current, LocalPhone.current), color = ink, maxLines = 1)
        if (open && !picked) Mono("open", color = c.ink70, data = true)
        WorldPaths.lang(row.path)?.let { Mono(it, color = if (picked) c.paper else c.ink70, data = true) }
    }
}

private fun menu(h: NeueHolders, row: TreeRow): List<MenuEntry> {
    val world = h.world
    if (row.app != null) {
        return listOf(
            MenuEntry("Open", hint = "Its window") { world.desk.open(AppRef.Made(row.app)) },
        )
    }
    val code = WorldPaths.lang(row.path) != null
    return listOf(
        MenuEntry("Open", hint = if (opensAsPage(row.path)) "As a page" else "In the Editor") { openFile(h, row.path) },
        MenuEntry("Open as page", hint = "In the Browser") { openAddress(h, WorldAddress.File(row.path)) },
        MenuEntry("Run", enabled = code && world.running == null, reason = if (!code) "Only .js and .py files run" else "Something is running") {
            world.launch { world.run(row.path, null, null, by = WorldEvent.YOU) }
            world.desk.open(com.kaiharimoto.mastertool.core.world.desk.BuiltInApp.TERMINAL.ref)
        },
        MenuEntry("Rename…", separatorBefore = true) { world.desk.dialog = DeskDialog.Rename(row.path) },
        MenuEntry("Delete…", danger = true) { world.desk.dialog = DeskDialog.Delete(row.path) },
    )
}
