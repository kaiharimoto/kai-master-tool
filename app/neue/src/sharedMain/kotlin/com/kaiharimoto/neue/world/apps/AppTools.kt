package com.kaiharimoto.neue.world.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.kit.Breathe
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.MenuEntry
import com.kaiharimoto.neue.kit.MenuSpec
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.launch

/*
 * What an Ai app's window adds to the desktop's frame (`docs/world/DESKTOP.md` §2.3, §8.5, §8.6): `by Ai`, the breathing
 * square while an event runs past 150 ms, and its ⋯ — Show code, Back to an earlier version, Start fresh, Delete. The
 * desktop's `DeskApps` puts it in the title bar; nothing an app returns can change the frame (§8.6 point 4).
 */

/** [app]'s own tools for its title bar, before `–` `□` `✕`; nothing for a built-in. */
@Composable
fun WorldAppTools(h: NeueHolders, app: AppRef, color: Color = Mu.colors.ink) {
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

/** [app]'s name, as its title bar and taskbar say it: the manifest's, cleaned (§8.6 point 4). */
fun WorldApps.name(app: AppRef): String = when (app) {
    is AppRef.BuiltIn -> app.kind.title
    is AppRef.Made -> manifest(app.slug)?.title ?: app.slug
}

private fun appMenu(h: NeueHolders, slug: String, versions: List<Int>): List<MenuEntry> {
    val apps = h.world.apps
    val current = apps.manifest(slug)?.version ?: 0
    return buildList {
        add(MenuEntry("Show code", hint = "In the Editor; a save is a new version") { showCode(h, slug) })
        versions.filter { it < current }.forEach { v ->
            add(MenuEntry("Back to v$v", hint = "Its code as v${current + 1}; the state kept") {
                apps.backSoon(slug, v) { h.neue.note = Note(it) }
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

/** *Back to* from the window's menu, said in a note when it is done. */
internal fun WorldApps.backSoon(slug: String, to: Int, said: (String) -> Unit) {
    scopeForUi.launch { back(slug, to).fold({ said("Back to v$to, now v${it.version}") }, { said(it.message ?: "Could not go back") }) }
}

/** Delete from the window's menu (the person confirmed), said in a note. */
internal fun WorldApps.deleteSoon(slug: String, said: (String) -> Unit) {
    scopeForUi.launch { delete(slug, WorldEvent.YOU).fold({ said("Deleted") }, { said(it.message ?: "Could not delete it") }) }
}
