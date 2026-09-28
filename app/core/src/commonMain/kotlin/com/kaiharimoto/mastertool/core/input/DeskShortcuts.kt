package com.kaiharimoto.mastertool.core.input

/**
 * The keyboard of Neue Master Tool, the desktop builder, as data.
 *
 * A second table rather than more rows in [ShortcutTable], for one reason: the
 * tablet's handlers are exhaustive `when`s over [ShortcutAction], so every
 * action added there is a branch the tablet has to carry for a screen it never
 * shows. The chord type and the "one table, one pure resolve" contract are the
 * same; only the vocabulary differs, because a window with a mouse, a rail of
 * pages and a command palette says different things than a touch screen does.
 *
 * The command palette and the help page both render [all], so neither can
 * describe a binding that does not exist.
 */
enum class DeskAction {
    PALETTE,
    GO_DECKS,
    GO_BUILDER,
    GO_ODDS,
    GO_STATS,
    GO_SETTINGS,
    HELP,
    DISMISS,

    SAVE,
    UNDO,
    REDO,
    NEW_DECK,
    IMPORT,
    EXPORT,

    FOCUS_SEARCH,
    POOL_PREVIOUS,
    POOL_NEXT,
    POOL_ADD,
    POOL_ADD_TO_SIDE,
    REMOVE_SELECTED,

    /** The selected card, or the highlighted result, opened large. */
    VIEW_SELECTED,

    TOGGLE_INSPECTOR,
    TOGGLE_POOL,
    TOGGLE_FILTERS,

    /** The Groups panel beside the deck, open or closed. */
    TOGGLE_KEYS,
    NEXT_LENS,
    PREVIOUS_LENS,
    NEW_GROUP,
    GROUPS,
    ISSUES,

    ZOOM_IN,
    ZOOM_OUT,
    ZOOM_RESET,
    TOGGLE_THEME,

    /**
     * Straight into zen (kai, 1.0.15: "instantly start zen mode with a one button
     * hotkey"): immersive if it was not, and deep at once rather than after ten
     * idle seconds. Any key brings the builder back, as ever.
     */
    ZEN,

    /** The artwork of the card being read — the inspector's card — one along, or one back (1.0.16). */
    NEXT_ART,
    PREVIOUS_ART,

    /** Full screen, with every bar folded away until the pointer reaches for it. */
    IMMERSIVE,
    /** A picture of the deck — main, extra and side — with none of the window around it. */
    SCREENSHOT,
}

/** Where a desk shortcut applies, with the heading it is listed under. Declaration order is display order. */
enum class DeskScope(val heading: String) {
    /** Always, over a drawer, a dialog or the palette too. */
    ANYWHERE("Anywhere"),

    /** Whenever nothing covers the window. */
    APP("Pages and files"),

    /** On the builder page, with nothing covering it. */
    BUILDER("Building a deck"),

    /**
     * The pool's own keys. These are live *while typing in the search field*,
     * which is the point of them: type a name, press ↓ twice, press Enter.
     */
    POOL("The card pool"),
}

/** What is on screen, which decides which desk shortcuts are live. */
data class DeskContext(
    /** Any text field has focus. */
    val textInputFocused: Boolean = false,
    /** The focused text field is the pool's search field. */
    val searchFocused: Boolean = false,
    /** A drawer, dialog, menu or the palette is covering the window. */
    val overlayOpen: Boolean = false,
    /** The builder is the page on screen. */
    val onBuilder: Boolean = true,
)

data class DeskShortcut(
    val chord: KeyChord,
    val action: DeskAction,
    val scope: DeskScope,
    val description: String,
    /** Unmodified keys stay dead while typing, or "side" in the deck name opens things on the way past. */
    val allowedInTextInput: Boolean = false,
    /** Holding the key keeps firing: stepping actions only, never anything that opens or closes. */
    val repeatable: Boolean = false,
)

object DeskShortcuts {

    private fun ctrl(key: String, shift: Boolean = false) = KeyChord(key, ctrl = true, shift = shift)

    val all: List<DeskShortcut> = listOf(
        DeskShortcut(KeyChord("escape"), DeskAction.DISMISS, DeskScope.ANYWHERE, "Close whatever is on top", allowedInTextInput = true),
        DeskShortcut(ctrl("k"), DeskAction.PALETTE, DeskScope.ANYWHERE, "Command palette", allowedInTextInput = true),

        DeskShortcut(ctrl("1"), DeskAction.GO_DECKS, DeskScope.APP, "Decks", allowedInTextInput = true),
        DeskShortcut(ctrl("2"), DeskAction.GO_BUILDER, DeskScope.APP, "Builder", allowedInTextInput = true),
        DeskShortcut(ctrl("3"), DeskAction.GO_ODDS, DeskScope.APP, "Odds", allowedInTextInput = true),
        DeskShortcut(ctrl("4"), DeskAction.GO_STATS, DeskScope.APP, "Statistics", allowedInTextInput = true),
        DeskShortcut(ctrl("comma"), DeskAction.GO_SETTINGS, DeskScope.APP, "Settings", allowedInTextInput = true),
        DeskShortcut(KeyChord("f1"), DeskAction.HELP, DeskScope.APP, "Keyboard shortcuts", allowedInTextInput = true),
        DeskShortcut(ctrl("s"), DeskAction.SAVE, DeskScope.APP, "Save the deck", allowedInTextInput = true),
        DeskShortcut(ctrl("n"), DeskAction.NEW_DECK, DeskScope.APP, "New deck", allowedInTextInput = true),
        DeskShortcut(ctrl("o"), DeskAction.IMPORT, DeskScope.APP, "Import a .ydk or .ydkx", allowedInTextInput = true),
        DeskShortcut(ctrl("e"), DeskAction.EXPORT, DeskScope.APP, "Export the deck", allowedInTextInput = true),
        DeskShortcut(ctrl("equals"), DeskAction.ZOOM_IN, DeskScope.APP, "Larger interface", allowedInTextInput = true),
        DeskShortcut(ctrl("minus"), DeskAction.ZOOM_OUT, DeskScope.APP, "Smaller interface", allowedInTextInput = true),
        DeskShortcut(ctrl("0"), DeskAction.ZOOM_RESET, DeskScope.APP, "Interface at 100%", allowedInTextInput = true),
        DeskShortcut(ctrl("i", shift = true), DeskAction.TOGGLE_THEME, DeskScope.APP, "Switch paper and ink", allowedInTextInput = true),
        DeskShortcut(KeyChord("f11"), DeskAction.IMMERSIVE, DeskScope.APP, "Immersive mode", allowedInTextInput = true),
        DeskShortcut(ctrl("s", shift = true), DeskAction.SCREENSHOT, DeskScope.APP, "Screenshot of the deck", allowedInTextInput = true),

        DeskShortcut(ctrl("z"), DeskAction.UNDO, DeskScope.BUILDER, "Undo", repeatable = true),
        DeskShortcut(ctrl("z", shift = true), DeskAction.REDO, DeskScope.BUILDER, "Redo", repeatable = true),
        DeskShortcut(ctrl("y"), DeskAction.REDO, DeskScope.BUILDER, "Redo", repeatable = true),
        DeskShortcut(ctrl("f"), DeskAction.FOCUS_SEARCH, DeskScope.BUILDER, "Search the pool", allowedInTextInput = true),
        DeskShortcut(KeyChord("slash"), DeskAction.FOCUS_SEARCH, DeskScope.BUILDER, "Search the pool"),
        DeskShortcut(ctrl("j"), DeskAction.TOGGLE_INSPECTOR, DeskScope.BUILDER, "Show or hide the inspector", allowedInTextInput = true),
        DeskShortcut(ctrl("b"), DeskAction.TOGGLE_POOL, DeskScope.BUILDER, "Show or hide the pool", allowedInTextInput = true),
        DeskShortcut(ctrl("f", shift = true), DeskAction.TOGGLE_FILTERS, DeskScope.BUILDER, "Filters", allowedInTextInput = true),
        DeskShortcut(KeyChord("delete"), DeskAction.REMOVE_SELECTED, DeskScope.BUILDER, "Remove the selected card", repeatable = true),
        DeskShortcut(KeyChord("backspace"), DeskAction.REMOVE_SELECTED, DeskScope.BUILDER, "Remove the selected card", repeatable = true),
        DeskShortcut(KeyChord("space"), DeskAction.VIEW_SELECTED, DeskScope.BUILDER, "Open the selected card large"),
        DeskShortcut(KeyChord("k"), DeskAction.TOGGLE_KEYS, DeskScope.BUILDER, "Show or hide the groups"),
        DeskShortcut(KeyChord("b"), DeskAction.NEXT_LENS, DeskScope.BUILDER, "Next lens"),
        DeskShortcut(KeyChord("b", shift = true), DeskAction.PREVIOUS_LENS, DeskScope.BUILDER, "Previous lens"),
        DeskShortcut(KeyChord("n"), DeskAction.NEW_GROUP, DeskScope.BUILDER, "New group from a selection"),
        DeskShortcut(KeyChord("g"), DeskAction.GROUPS, DeskScope.BUILDER, "Open the groups"),
        DeskShortcut(KeyChord("z"), DeskAction.ZEN, DeskScope.BUILDER, "Zen, now"),
        DeskShortcut(KeyChord("a"), DeskAction.NEXT_ART, DeskScope.BUILDER, "Next artwork of the card being read"),
        DeskShortcut(KeyChord("a", shift = true), DeskAction.PREVIOUS_ART, DeskScope.BUILDER, "Previous artwork"),
        DeskShortcut(KeyChord("i"), DeskAction.ISSUES, DeskScope.BUILDER, "Issues"),

        DeskShortcut(KeyChord("up"), DeskAction.POOL_PREVIOUS, DeskScope.POOL, "Previous result", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("down"), DeskAction.POOL_NEXT, DeskScope.POOL, "Next result", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("enter"), DeskAction.POOL_ADD, DeskScope.POOL, "Add the result to the deck", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("enter", shift = true), DeskAction.POOL_ADD_TO_SIDE, DeskScope.POOL, "Add the result to the side deck", allowedInTextInput = true, repeatable = true),
    )

    fun resolve(chord: KeyChord, context: DeskContext): DeskAction? = resolveShortcut(chord, context)?.action

    fun resolveShortcut(chord: KeyChord, context: DeskContext): DeskShortcut? =
        live(context).firstOrNull { it.chord == chord }

    /** Every row that would fire in [context], in table order. */
    fun live(context: DeskContext): List<DeskShortcut> =
        all.filter { (!context.textInputFocused || it.allowedInTextInput) && it.isActive(context) }

    /** The first chord bound to [action], for hints beside a menu item or in a tooltip. */
    fun chordFor(action: DeskAction): KeyChord? = all.firstOrNull { it.action == action }?.chord

    private fun DeskShortcut.isActive(context: DeskContext): Boolean = when (scope) {
        DeskScope.ANYWHERE -> true
        DeskScope.APP -> !context.overlayOpen
        DeskScope.BUILDER -> !context.overlayOpen && context.onBuilder
        // Typing anywhere but the search field (the deck name) must not add cards.
        DeskScope.POOL -> !context.overlayOpen && context.onBuilder &&
            (context.searchFocused || !context.textInputFocused)
    }

    /**
     * How a chord is written in the family's `Kbd`: `Ctrl K`, `Shift Enter`, `Esc`.
     * Space-separated, never a plus and never `⌘` (Master UI §9).
     */
    fun kbd(chord: KeyChord): String = buildList {
        if (chord.ctrl) add("Ctrl")
        if (chord.alt) add("Alt")
        if (chord.shift) add("Shift")
        add(keyName(chord.key))
    }.joinToString(" ")

    private fun keyName(key: String): String = when (key) {
        "escape" -> "Esc"
        "slash" -> "/"
        "comma" -> ","
        "equals" -> "="
        "minus" -> "-"
        "up" -> "↑"
        "down" -> "↓"
        "left" -> "←"
        "right" -> "→"
        "enter" -> "Enter"
        "delete" -> "Del"
        "backspace" -> "Backspace"
        "space" -> "Space"
        "f1" -> "F1"
        "f11" -> "F11"
        else -> key.uppercase()
    }
}
