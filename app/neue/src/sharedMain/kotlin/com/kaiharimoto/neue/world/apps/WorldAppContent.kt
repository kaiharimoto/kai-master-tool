package com.kaiharimoto.neue.world.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.WorldIcons
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import com.kaiharimoto.neue.world.EditorPane
import com.kaiharimoto.neue.world.FilesPane
import com.kaiharimoto.neue.world.TerminalPane
import com.kaiharimoto.neue.world.browser.BrowserApp
import com.kaiharimoto.neue.world.browser.pageTitle
import com.kaiharimoto.neue.world.library.LibraryApp
import kotlinx.coroutines.launch

/*
 * The registry the desktop's windows read (`docs/world/DESKTOP.md` §13, agent C): for any app, its window's body
 * ([WorldAppContent]), the words after its name in the title bar ([windowContext]), its own tools for the title bar
 * ([WorldAppTools]) and its icon ([WorldAppIcon]). The desktop draws the frame round them; nothing an app returns can
 * change the frame (§8.6 point 4).
 */

/** The body of [app]'s window. Files, the Editor and the Terminal are the desktop's own (agent B); this draws 1.0.97's. */
@Composable
fun WorldAppContent(h: NeueHolders, app: AppRef, modifier: Modifier = Modifier) {
    when (app) {
        is AppRef.Made -> AppWindow(h, app.slug, modifier)
        is AppRef.BuiltIn -> when (app.kind) {
            BuiltInApp.FILES -> Box(modifier.fillMaxSize()) { FilesPane(h) }
            BuiltInApp.EDITOR -> Box(modifier.fillMaxSize()) { EditorPane(h) }
            BuiltInApp.TERMINAL -> Box(modifier.fillMaxSize()) { TerminalPane(h) }
            BuiltInApp.BROWSER -> BrowserApp(h, modifier)
            BuiltInApp.THOUGHTS -> ThoughtsApp(h, modifier)
            BuiltInApp.INSTRUMENTS -> InstrumentsApp(h, modifier)
            BuiltInApp.LIBRARY -> LibraryApp(h, modifier)
        }
    }
}

/** [app]'s name as its title bar and taskbar say it: a built-in's, or the manifest's, cleaned (§8.6 point 4). */
fun WorldApps.name(app: AppRef): String = when (app) {
    is AppRef.BuiltIn -> app.kind.title
    is AppRef.Made -> manifest(app.slug)?.title ?: app.slug
}

/**
 * What follows the name in [app]'s title bar, after a `·` (§2.3): `Browser · Opens a starter`, `Hand odds · v3`,
 * `Library · Guide · Snake-Eye`. Empty when there is nothing to add.
 */
fun windowContext(h: NeueHolders, app: AppRef): String {
    val world = h.world
    return when (app) {
        is AppRef.Made -> world.apps.context(app.slug)
        is AppRef.BuiltIn -> when (app.kind) {
            BuiltInApp.BROWSER -> world.browser.current?.let { pageTitle(h, it.parsed) }.orEmpty()
            BuiltInApp.EDITOR -> world.editorPath.orEmpty()
            BuiltInApp.LIBRARY -> world.library.opened?.doc?.title.orEmpty()
            BuiltInApp.TERMINAL -> world.running.orEmpty()
            else -> ""
        }
    }
}

/** [app]'s icon at [size]: a built-in's glyph, or an Ai app's framed tile (§7). */
@Composable
fun WorldAppIcon(h: NeueHolders, app: AppRef, size: Dp, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Mu.colors.ink) {
    when (app) {
        is AppRef.BuiltIn -> WorldIcon(WorldIcons.builtIn(app.kind), size, modifier, color)
        is AppRef.Made -> {
            val tile = h.world.apps.manifest(app.slug)?.tile ?: WorldIcons.tile(app.slug, com.kaiharimoto.mastertool.core.world.apps.AppKind.VIEWER, null, null)
            WorldTile(tile, size, modifier, color)
        }
    }
}

/**
 * [app]'s own tools for its title bar, before `–` `□` `✕` (§2.3): an Ai app's busy square while an event runs past
 * 150 ms, `by Ai`, and its ⋯ — Show code, Back to an earlier version, Start fresh, Delete (confirmed).
 */
@Composable
fun WorldAppTools(h: NeueHolders, app: AppRef, color: androidx.compose.ui.graphics.Color = Mu.colors.ink) {
    if (app !is AppRef.Made) return
    val apps = h.world.apps
    val host = if (apps.isLive(app.slug)) apps.host(app.slug) else null
    val scope = rememberCoroutineScope()
    var at by remember { mutableStateOf(Offset.Zero) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (host?.busy == true) Breathe(color = color)
        Micro("by Ai", color = color)
        Box(Modifier.onGloballyPositioned { at = it.boundsInWindow().bottomLeft + Offset(0f, 4f) }) {
            Tip("The app's code, its versions, a fresh start") {
                IconButton(Icons.More, {
                    scope.launch {
                        val versions = apps.versions(app.slug)
                        h.neue.menu = MenuSpec(at, appMenu(h, app.slug, versions))
                    }
                }, size = 24.dp, label = "More")
            }
        }
    }
}

private fun appMenu(h: NeueHolders, slug: String, versions: List<Int>): List<MenuEntry> {
    val apps = h.world.apps
    val current = apps.manifest(slug)?.version ?: 0
    return buildList {
        add(MenuEntry("Show code", hint = "In the Editor; a save is a new version") { showCode(h, slug) })
        versions.filter { it < current }.forEach { v ->
            add(MenuEntry("Back to v$v", hint = "Its code as v${current + 1}; the state kept") {
                h.world.apps.backSoon(slug, v) { h.neue.note = Note(it) }
            })
        }
        add(MenuEntry("Start fresh", hint = "The state kept aside as state.prev.json", separatorBefore = true) { apps.startFresh(slug) })
        add(MenuEntry("Delete…", separatorBefore = true) {
            h.neue.menu = MenuSpec(
                h.neue.menu?.at ?: Offset.Zero,
                listOf(
                    MenuEntry("Delete “${apps.name(AppRef.Made(slug))}” and its state", hint = "This cannot be undone") {
                        apps.deleteSoon(slug) { h.neue.note = Note(it) }
                    },
                    MenuEntry("Keep it"),
                ),
            )
        })
    }
}

/**
 * An app given the whole World page (1.0.97's page has no window for Instruments, the Library or Ai's apps; the studio's
 * `--world-app=`): a title bar of the desktop's kind over the app.
 */
@Composable
fun SoloApp(h: NeueHolders, app: AppRef, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val c = Mu.colors
    Column(modifier.fillMaxSize()) {
        // The window in front: an ink title bar with paper words (§2.3).
        com.kaiharimoto.neue.theme.Inverted(true) {
            val ic = Mu.colors
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .drawBehind { drawRect(ic.paper) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorldAppIcon(h, app, 16.dp, color = ic.ink)
                MuText(h.world.apps.name(app), style = MuType.small(LocalMuFonts.current), color = ic.ink, maxLines = 1)
                val more = windowContext(h, app)
                if (more.isNotEmpty()) Mono("· $more", Modifier.widthIn(max = 480.dp), color = ic.ink70)
                Box(Modifier.weight(1f))
                WorldAppTools(h, app, ic.ink)
                if (onClose != null) IconButton(Icons.X, onClose, size = 28.dp, label = "Close")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).drawBehind { drawLine(c.ink, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()); drawLine(c.ink, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()); drawLine(c.ink, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) {
            WorldAppContent(h, app, Modifier.fillMaxSize().padding(1.dp))
        }
    }
}

/** *Back to* from the window's menu, said in a note when it is done. */
internal fun WorldApps.backSoon(slug: String, to: Int, said: (String) -> Unit) {
    scopeForUi.launch { back(slug, to).fold({ said("Back to v$to, now v${it.version}") }, { said(it.message ?: "Could not go back") }) }
}

/** Delete from the window's menu (the person confirmed), said in a note. */
internal fun WorldApps.deleteSoon(slug: String, said: (String) -> Unit) {
    scopeForUi.launch { delete(slug, WorldEvent.YOU).fold({ said("Deleted") }, { said(it.message ?: "Could not delete it") }) }
}
