package com.kaiharimoto.neue.world.system

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.kaiharimoto.mastertool.core.ai.text.ChatFollow
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskOp
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.world.desk.WorldDeskState

/*
 * Where a thing opens on the desktop (`docs/world/DESKTOP.md` §3, §4): code and text in the Editor; markdown, pictures,
 * CSV and JSON as pages in the Browser; an app in its own window. One place, so Files, the Terminal's `open`, a notice
 * and the launcher agree.
 */

/** The kinds of file Files opens as a page rather than in the Editor (§3). */
private val PAGES = setOf("md", "png", "jpg", "jpeg", "gif", "webp", "csv", "json")

/** Whether [path] opens as a page (the Browser) rather than as text (the Editor). */
internal fun opensAsPage(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in PAGES

/** Opens [path] where it belongs: the Editor, or a page. */
internal fun openFile(h: NeueHolders, path: String) {
    if (opensAsPage(path)) openAddress(h, WorldAddress.File(path)) else openInEditor(h, path)
}

/** [path] in the Editor, the person's: their unsaved edit to the file there saved first. */
internal fun openInEditor(h: NeueHolders, path: String) {
    val world = h.world
    if (world.editorPath != path) {
        world.saveEditor()
        world.showFile(path)
    }
    world.desk.open(BuiltInApp.EDITOR.ref)
}

/**
 * [address] opened for the person (§4): an app is its own window, never a tab; anything else is a page — selected in
 * the tab already showing it, or a new tab — with the Browser brought forward.
 */
internal fun openAddress(h: NeueHolders, address: WorldAddress) {
    val desk = h.world.desk
    when (address) {
        is WorldAddress.App -> desk.open(AppRef.Made(address.slug))
        else -> {
            val text = address.format()
            val tabs = desk.desk.tabs
            val there = tabs.showing(text)
            desk.apply(DeskOp.Tabs(if (there != null) tabs.select(there.id) else tabs.open(text, WorldDeskState.now())))
            if (address is WorldAddress.Board) h.world.selectedBoard = address.id
            desk.open(BuiltInApp.BROWSER.ref)
        }
    }
}

/** The file's language for the Editor's bar: `js`, `py`, or its extension. */
internal fun languageOf(path: String): String = WorldPaths.lang(path) ?: path.substringAfterLast('.', "text").lowercase()

/** Keeps [list] at its end as it grows, only while the reader is there (`ChatFollow`, the chat's rule). */
@Composable
internal fun FollowEnd(list: LazyListState, key: Any?, size: Int) {
    val follow = remember(key) { ChatFollow() }
    val ours = remember { booleanArrayOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { moving ->
            if (!moving && !ours[0]) follow.readerScrolled(atEnd = !list.canScrollForward)
        }
    }
    LaunchedEffect(size) {
        if (size > 0 && follow.shouldFollow(readerScrolling = list.isScrollInProgress)) {
            ours[0] = true
            try {
                list.scrollToItem(size - 1, 1_000_000)
            } finally {
                ours[0] = false
            }
        }
    }
}
