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

    /** Format (1.0.33): the webs of decks, the fields you prepare for. */
    GO_FORMAT,
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

    /**
     * The selection one card along, or one row up or down (kai, 1.0.18), in the
     * deck or the pool; the inspector follows it. With nothing selected, up and
     * down walk the pool's results as they always have.
     */
    SELECT_LEFT,
    SELECT_RIGHT,

    /**
     * The search pop-out (1.0.19): the window given over to finding cards, with
     * every filter and the card read large beside the results.
     */
    ADVANCED_SEARCH,

    /** The selected card, or the one being read, onto the active list — or off it (1.0.19). */
    LIST_CARD,

    /** The pool between the whole database and the active list. */
    SHOW_LIST,

    /** The artwork of the card being read — the inspector's card — one along, or one back (1.0.16). */
    NEXT_ART,
    PREVIOUS_ART,

    /** Full screen, with every bar folded away until the pointer reaches for it. */
    IMMERSIVE,
    /** A picture of the deck — main, extra and side — with none of the window around it. */
    SCREENSHOT,

    /**
     * The deck one along in its web, or one back (1.0.33: "when in a web, the user
     * can easily change decks in the web in the deck builder"), saved first.
     */
    WEB_PREVIOUS,
    WEB_NEXT,
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
        DeskShortcut(ctrl("5"), DeskAction.GO_FORMAT, DeskScope.APP, "Format: webs of decks", allowedInTextInput = true),
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
        DeskShortcut(ctrl("f", shift = true), DeskAction.ADVANCED_SEARCH, DeskScope.BUILDER, "Advanced search", allowedInTextInput = true),
        DeskShortcut(KeyChord("f"), DeskAction.TOGGLE_FILTERS, DeskScope.BUILDER, "Filters"),
        DeskShortcut(KeyChord("l"), DeskAction.LIST_CARD, DeskScope.BUILDER, "Put the card on the list, or take it off"),
        DeskShortcut(KeyChord("l", shift = true), DeskAction.SHOW_LIST, DeskScope.BUILDER, "Show the list in the pool, or every card"),
        DeskShortcut(KeyChord("delete"), DeskAction.REMOVE_SELECTED, DeskScope.BUILDER, "Remove the selected card", repeatable = true),
        DeskShortcut(KeyChord("backspace"), DeskAction.REMOVE_SELECTED, DeskScope.BUILDER, "Remove the selected card", repeatable = true),
        DeskShortcut(KeyChord("space"), DeskAction.VIEW_SELECTED, DeskScope.BUILDER, "Open the selected card large"),
        DeskShortcut(KeyChord("k"), DeskAction.TOGGLE_KEYS, DeskScope.BUILDER, "Show or hide the groups"),
        DeskShortcut(KeyChord("b"), DeskAction.NEXT_LENS, DeskScope.BUILDER, "Next lens"),
        DeskShortcut(KeyChord("b", shift = true), DeskAction.PREVIOUS_LENS, DeskScope.BUILDER, "Previous lens"),
        DeskShortcut(KeyChord("n"), DeskAction.NEW_GROUP, DeskScope.BUILDER, "New group from a selection"),
        DeskShortcut(KeyChord("g"), DeskAction.GROUPS, DeskScope.BUILDER, "Open the groups"),
        DeskShortcut(KeyChord("z"), DeskAction.ZEN, DeskScope.BUILDER, "Zen, now"),
        DeskShortcut(KeyChord("left"), DeskAction.SELECT_LEFT, DeskScope.BUILDER, "Select the card to the left", repeatable = true),
        DeskShortcut(KeyChord("right"), DeskAction.SELECT_RIGHT, DeskScope.BUILDER, "Select the card to the right", repeatable = true),
        DeskShortcut(KeyChord("a"), DeskAction.NEXT_ART, DeskScope.BUILDER, "Next artwork of the card being read"),
        DeskShortcut(KeyChord("a", shift = true), DeskAction.PREVIOUS_ART, DeskScope.BUILDER, "Previous artwork"),
        DeskShortcut(KeyChord("i"), DeskAction.ISSUES, DeskScope.BUILDER, "Issues"),
        DeskShortcut(KeyChord("left", alt = true), DeskAction.WEB_PREVIOUS, DeskScope.BUILDER, "Previous deck in the web"),
        DeskShortcut(KeyChord("right", alt = true), DeskAction.WEB_NEXT, DeskScope.BUILDER, "Next deck in the web"),

        DeskShortcut(KeyChord("up"), DeskAction.POOL_PREVIOUS, DeskScope.POOL, "Previous result, or the card above the selected one", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("down"), DeskAction.POOL_NEXT, DeskScope.POOL, "Next result, or the card below the selected one", allowedInTextInput = true, repeatable = true),
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
     * Whether [kbd] writes chords the Mac's way. Set once, before the first frame,
     * by the desktop entry point on macOS (Phase 4) — a fact about the machine,
     * like the path separator, which is why it is not threaded through every tip.
     */
    var macLabels: Boolean = false

    /** [kbd] in this machine's own words: see [macLabels]. */
    fun kbd(chord: KeyChord): String = kbd(chord, macLabels)

    /**
     * How a chord is written in the family's `Kbd`: `Ctrl K`, `Shift Enter`, `Esc`.
     * Space-separated and never a plus (Master UI §9).
     *
     * On a Mac, [mac], it is written as the Mac writes it, because that is the
     * keyboard in front of the reader: `⌘K`, `⇧⌘F`, modifiers in Apple's order
     * (⌥ ⇧ ⌘) and run together, as every menu on the machine prints them. The
     * kit's "never ⌘" is a rule for a web page that cannot know the keyboard; kai
     * chose the Mac's glyphs for the Mac app (Phase 4), and they are written here,
     * in core, so no file in `neue/` spells one — the law test still refuses it
     * there. `Ctrl` in the table *is* ⌘ on a Mac: `DeskKeys` reads either.
     */
    fun kbd(chord: KeyChord, mac: Boolean): String {
        if (!mac) {
            return buildList {
                if (chord.ctrl) add("Ctrl")
                if (chord.alt) add("Alt")
                if (chord.shift) add("Shift")
                add(keyName(chord.key))
            }.joinToString(" ")
        }
        val modifiers = buildString {
            if (chord.alt) append(MAC_OPTION)
            if (chord.shift) append(MAC_SHIFT)
            if (chord.ctrl) append(MAC_COMMAND)
        }
        return modifiers + macKeyName(chord.key)
    }

    const val MAC_COMMAND = "\u2318"
    const val MAC_SHIFT = "\u21E7"
    const val MAC_OPTION = "\u2325"

    private fun macKeyName(key: String): String = when (key) {
        "enter" -> "\u21A9"
        "delete" -> "\u2326"
        "backspace" -> "\u232B"
        else -> keyName(key)
    }

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
