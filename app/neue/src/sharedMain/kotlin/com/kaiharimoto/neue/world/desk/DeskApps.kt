package com.kaiharimoto.neue.world.desk

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.world.system.EditorApp
import com.kaiharimoto.neue.world.system.EditorTools
import com.kaiharimoto.neue.world.system.FilesApp
import com.kaiharimoto.neue.world.system.FilesTools
import com.kaiharimoto.neue.world.system.TerminalApp
import com.kaiharimoto.neue.world.system.TerminalTools
import com.kaiharimoto.neue.world.system.editorSummary
import com.kaiharimoto.neue.world.system.terminalSummary

/**
 * What runs inside a window (`docs/world/DESKTOP.md` §13): the desk draws the frame — title bar, keep-out, move, snap,
 * resize — and asks this one table what goes in it, per [AppRef]. **The seam between the shell and the apps.**
 *
 * The shell's own apps (Files, the Editor, the Terminal: `neue/world/system/`) answer here directly. The Browser,
 * Thoughts, Instruments, Library and every app Ai made are drawn by the app host (agent C's `WorldApps`); until it
 * lands they stand as plain placeholders ([Interim]). Wiring C in is this file alone: each `Interim` call below becomes
 * the host's `content`, `toolbar`, `title` and `summary` for that [AppRef].
 *
 * Everything here is asked per window, on the phone as on the desk ([phone] says which).
 */
internal object DeskApps {
    /** The window's body. */
    @Composable
    fun Content(h: NeueHolders, ref: AppRef, modifier: Modifier, phone: Boolean) {
        when (ref) {
            BuiltInApp.FILES.ref -> FilesApp(h, modifier, phone)
            BuiltInApp.EDITOR.ref -> EditorApp(h, modifier, phone)
            BuiltInApp.TERMINAL.ref -> TerminalApp(h, modifier, phone)
            BuiltInApp.BROWSER.ref -> Interim.Browser(h, modifier)
            BuiltInApp.THOUGHTS.ref -> Interim.Thoughts(h, modifier, phone)
            else -> Interim.Placeholder(h, ref, modifier)
        }
    }

    /** The window's own tools, in its title bar after the context and before `– □ ✕`. */
    @Composable
    fun RowScope.Tools(h: NeueHolders, ref: AppRef) {
        when (ref) {
            BuiltInApp.FILES.ref -> FilesTools(h)
            BuiltInApp.EDITOR.ref -> EditorTools(h)
            BuiltInApp.TERMINAL.ref -> TerminalTools(h)
            else -> Unit
        }
    }

    /** What the title bar says after the app's name and a `·` (§2.3): `openings.js`, `Opens a starter`, `v3`; null for nothing. */
    fun context(h: NeueHolders, ref: AppRef): String? = when (ref) {
        BuiltInApp.EDITOR.ref -> h.world.editorPath
        BuiltInApp.TERMINAL.ref -> h.world.running ?: h.world.terminal.lastOrNull { it.kind == com.kaiharimoto.neue.world.TermLine.Kind.COMMAND }?.text?.substringAfter(' ')
        BuiltInApp.FILES.ref -> h.world.open?.title
        BuiltInApp.BROWSER.ref -> Interim.browserTitle(h)
        is AppRef.Made -> h.world.desk.apps.firstOrNull { it.slug == ref.slug }?.let { "v${it.version}" }
        else -> null
    }

    /** The phone switcher's one line (§2.5): words, never a live picture. */
    fun summary(h: NeueHolders, ref: AppRef): String = when (ref) {
        BuiltInApp.EDITOR.ref -> editorSummary(h)
        BuiltInApp.TERMINAL.ref -> terminalSummary(h)
        BuiltInApp.FILES.ref -> "${h.world.files.size} files"
        BuiltInApp.BROWSER.ref -> h.world.desk.desk.tabs.let { t -> "${t.tabs.size} tabs" + (Interim.browserTitle(h)?.let { " · $it" }.orEmpty()) }
        is AppRef.Made -> h.world.desk.apps.firstOrNull { it.slug == ref.slug }?.description.orEmpty()
        else -> ""
    }
}
