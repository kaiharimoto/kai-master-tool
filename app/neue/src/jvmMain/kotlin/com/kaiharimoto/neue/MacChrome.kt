package com.kaiharimoto.neue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskMenuBar
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.prefs.NeueTheme

/**
 * Neue as a Mac app (Phase 4): the parts of the Mac that are not the window's
 * contents — the menu bar at the top of the screen, the application menu's
 * About, Settings and Quit, and a title bar in the theme's own light.
 *
 * None of it runs anywhere else; `Main.kt` calls it only on macOS.
 */
object MacChrome {

    /**
     * Before AWT starts: the menu bar at the top of the screen rather than in
     * the window, the app's own name in it, and the Command glyph in every label the tables
     * write.
     */
    fun prepare() {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "Neue Master Tool")
        System.setProperty("apple.awt.application.appearance", "system")
        DeskShortcuts.macLabels = true
    }

    /**
     * The application menu is the system's; its About and Settings go where
     * they go in the app (the Settings page, which carries the version), and
     * Quit saves the settings document first, as closing the window does.
     */
    fun handleAppMenu(h: NeueHolders, quit: () -> Unit) {
        val desktop = runCatching { java.awt.Desktop.getDesktop() }.getOrNull() ?: return
        runCatching { desktop.setAboutHandler { h.runFromMenu(DeskAction.GO_SETTINGS) } }
        runCatching { desktop.setPreferencesHandler { h.runFromMenu(DeskAction.GO_SETTINGS) } }
        runCatching {
            desktop.setQuitHandler { _, response ->
                h.neue.flush()
                h.flushDuel()
                h.flushPresent()
                response.cancelQuit()
                quit()
            }
        }
    }
}

/** `DeskMenuBar`, drawn by the Mac: every item a table action, every accelerator the table's chord. */
@Composable
fun FrameWindowScope.MacMenuBar(h: NeueHolders) {
    // Read here so the menu is drawn again when Ai is turned on or off (1.0.43).
    DeskMenuBar.aiShown = h.neue.prefs.ai.enabled
    val aiName = h.neue.prefs.ai.name
    MenuBar {
        DeskMenuBar.menus.forEach { menu ->
            Menu(menu.title) {
                menu.items.forEach { item ->
                    val chord = DeskMenuBar.accelerated(item)
                    val key = chord?.let { DeskKeys.keyFor(it.key) }
                    Item(
                        if (item.action == DeskAction.AI_PANEL) aiName else item.label,
                        onClick = { h.runFromMenu(item.action) },
                        shortcut = if (chord != null && key != null) {
                            KeyShortcut(key, meta = chord.ctrl, shift = chord.shift, alt = chord.alt)
                        } else {
                            null
                        },
                    )
                    if (item.ruleAfter) Separator()
                }
            }
        }
    }
}

/** The title bar in the theme's light: dark for ink, light for paper, as the Mac draws its own. */
@Composable
fun FrameWindowScope.MacTitleBar(theme: NeueTheme) {
    LaunchedEffect(theme) {
        window.rootPane.putClientProperty(
            "apple.awt.windowAppearance",
            if (theme == NeueTheme.INK) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua",
        )
    }
}
