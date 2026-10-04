package com.kaiharimoto.mastertool.core.input

/**
 * The Mac's menu bar (Phase 4), as data read off [DeskShortcuts] — so, like the
 * palette and the help dialog, it cannot name a binding that does not exist.
 *
 * A Mac app without a menu bar is a Java program; one with a menu bar whose items
 * disagree with the keyboard is worse. So every item is a [DeskAction] and its
 * label and chord come from the table.
 *
 * **Which items carry an accelerator is the one decision here.** A menu
 * accelerator on the Mac takes its key before the window sees it, in every
 * context — including a focused text field, where the table deliberately leaves
 * some chords dead (`Undo` belongs to the deck name while you type it, not to
 * the deck). So an item carries its chord only when [accelerated]: the chord has
 * a modifier (a bare `N` or `Delete` in a menu would eat typing) and the table
 * lets it fire in text input anyway. Everything else is still in the menu,
 * clickable, and still on the keyboard through the window's own handler.
 */
data class DeskMenu(val title: String, val items: List<DeskMenuItem>)

data class DeskMenuItem(
    val action: DeskAction,
    val label: String,
    /** Drawn after this item: a rule, as the Mac groups its menus. */
    val ruleAfter: Boolean = false,
)

object DeskMenuBar {

    private fun item(action: DeskAction, label: String, ruleAfter: Boolean = false) = DeskMenuItem(action, label, ruleAfter)

    /**
     * The application menu (About, Settings, Quit) is the Mac's own and is
     * supplied by the system; Settings is routed to [DeskAction.GO_SETTINGS].
     */
    val menus: List<DeskMenu> get() = if (aiShown) all else all.map { menu -> menu.copy(items = menu.items.filter { it.action !in DeskAction.AI }) }

    /**
     * Whether Ai's item is in the menus: off, every trace of Ai is gone (Settings → Ai).
     * Set by the desktop as the setting changes, a fact about the app like [DeskShortcuts.macLabels].
     */
    var aiShown: Boolean = true

    /** The assistant's name, as the person chose it: its menu item and help row say it (1.0.46). */
    var aiName: String = "Ai"

    private val all: List<DeskMenu> = listOf(
        DeskMenu(
            "File",
            listOf(
                item(DeskAction.NEW_DECK, "New deck"),
                item(DeskAction.IMPORT, "Import…", ruleAfter = true),
                item(DeskAction.SAVE, "Save"),
                item(DeskAction.EXPORT, "Export…"),
                item(DeskAction.SCREENSHOT, "Picture of the deck"),
            ),
        ),
        DeskMenu(
            "Edit",
            listOf(
                item(DeskAction.UNDO, "Undo"),
                item(DeskAction.REDO, "Redo", ruleAfter = true),
                item(DeskAction.FOCUS_SEARCH, "Search the pool"),
                item(DeskAction.ADVANCED_SEARCH, "Advanced search", ruleAfter = true),
                item(DeskAction.NEW_GROUP, "New group from the selection"),
                item(DeskAction.REMOVE_SELECTED, "Remove the selected card", ruleAfter = true),
                item(DeskAction.PRESENT_START, "Present from the start"),
                item(DeskAction.PRESENT_FROM_HERE, "Present from this slide"),
            ),
        ),
        DeskMenu(
            "View",
            listOf(
                item(DeskAction.GO_BUILDER, "Builder"),
                item(DeskAction.GO_DECKS, "Decks"),
                item(DeskAction.GO_SIDING, "Siding"),
                item(DeskAction.GO_FORMAT, "Format"),
                item(DeskAction.GO_PREP, "Prep"),
                item(DeskAction.GO_PRESENT, "Present"),
                item(DeskAction.GO_DUEL, "Duel"),
                item(DeskAction.GO_WORLD, "Ai World", ruleAfter = true),
                item(DeskAction.WEB_PREVIOUS, "Previous deck in the web"),
                item(DeskAction.WEB_NEXT, "Next deck in the web", ruleAfter = true),
                item(DeskAction.TOGGLE_POOL, "Show or hide the pool"),
                item(DeskAction.TOGGLE_INSPECTOR, "Show or hide the inspector"),
                item(DeskAction.TOGGLE_KEYS, "Show or hide the groups"),
                item(DeskAction.GROUP_ARRANGEMENT, "Groups as is, fitted or separate", ruleAfter = true),
                item(DeskAction.ZOOM_IN, "Larger"),
                item(DeskAction.ZOOM_OUT, "Smaller"),
                item(DeskAction.ZOOM_RESET, "Actual size", ruleAfter = true),
                item(DeskAction.TOGGLE_THEME, "Switch paper and ink"),
                item(DeskAction.IMMERSIVE, "Immersive mode"),
                item(DeskAction.ZEN, "Zen", ruleAfter = true),
                item(DeskAction.AI_PANEL, "Ai"),
            ),
        ),
        DeskMenu(
            "Help",
            listOf(
                item(DeskAction.PALETTE, "Command palette"),
                item(DeskAction.HELP, "Keyboard and mouse"),
            ),
        ),
    )

    /** The chord a menu item is labelled and accelerated with, or null — see the class. */
    fun accelerated(item: DeskMenuItem): KeyChord? {
        val rows = DeskShortcuts.all.filter { it.action == item.action }
        return rows.firstOrNull { row ->
            (row.chord.ctrl || row.chord.alt) && row.allowedInTextInput
        }?.chord
    }

    /**
     * Whether [action], chosen from the menu, may run in [context]: exactly when
     * one of its chords would have fired there. A menu is another way to press
     * the key, not a way round the table's scopes.
     */
    fun enabled(action: DeskAction, context: DeskContext): Boolean =
        DeskShortcuts.live(context).any { it.action == action }
}

/**
 * One key press, arriving twice. On the Mac a menu accelerator and the window's
 * key handler can both hear the same ⌘-chord, depending on which of them the JDK
 * lets consume it; neither may be dropped, because either may be the only one
 * that fires. This admits an action once per press: a second arrival of the
 * same action within [ECHO_MS] is the echo, not a second press — a held key
 * repeats no faster than every thirty milliseconds, and a person no faster than
 * that either.
 */
class ActionEcho(private val windowMs: Long = ECHO_MS) {
    private var last: DeskAction? = null
    private var lastAt: Long = Long.MIN_VALUE

    fun admit(action: DeskAction, nowMs: Long): Boolean {
        val echo = action == last && nowMs - lastAt in 0 until windowMs
        if (echo) return false
        last = action
        lastAt = nowMs
        return true
    }

    companion object {
        const val ECHO_MS = 25L
    }
}
