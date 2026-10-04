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

    /** Siding (1.0.40): your decks sided against their web. */
    GO_SIDING,

    /** Format (1.0.33): the webs of decks, the fields you prepare for. */
    GO_FORMAT,

    /** Prep (1.0.50): an event and the practice for it. */
    GO_PREP,
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

    /** The groups' layout, turned: as is, fitted, separate (1.0.37). */
    GROUP_ARRANGEMENT,
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

    /**
     * The assistant's panel, open or closed (Ai, 1.0.43): docked beside every page.
     * Live only while Ai is on ([DeskContext.ai]); with it off, the key is dead and
     * no menu or palette names it.
     */
    AI_PANEL,

    /** Speak to the assistant (1.0.57): the microphone, on or off. Never Ai's own to press. */
    AI_VOICE,

    /** Talk mode (1.0.57): a conversation out loud, listen, answer aloud, listen again. */
    AI_TALK,

    /** Present (1.0.70): deck profiles as slides, presented and recorded. */
    GO_PRESENT,

    /** From the first slide, or from the one being edited. */
    PRESENT_START,
    PRESENT_FROM_HERE,

    /** A new slide after the current one. */
    SLIDE_NEW,

    /** The selected elements, or the current slide, copied in place. */
    PRESENT_DUPLICATE,
    PRESENT_COPY,
    PRESENT_CUT,
    PRESENT_PASTE,
    PRESENT_SELECT_ALL,
    PRESENT_GROUP,
    PRESENT_UNGROUP,
    BRING_FORWARD,
    SEND_BACKWARD,
    BRING_TO_FRONT,
    SEND_TO_BACK,

    /** The selected elements a step along, or ten with Shift; with nothing selected, the slide before or after. */
    NUDGE_LEFT,
    NUDGE_RIGHT,
    NUDGE_UP,
    NUDGE_DOWN,
    NUDGE_LEFT_FAR,
    NUDGE_RIGHT_FAR,
    NUDGE_UP_FAR,
    NUDGE_DOWN_FAR,
    TEXT_BOLD,
    TEXT_ITALIC,
    TEXT_UNDERLINE,

    /** While presenting: the next click, or the one before. */
    PRESENT_NEXT,
    PRESENT_PREVIOUS,
    PRESENT_FIRST,
    PRESENT_LAST,

    /** While presenting: the whole deck, at any time (kai: "return to full deck view on demand anytime"). */
    PRESENT_DECK,
    PRESENT_BLACK,
    PRESENT_WHITE,
    PRESENT_LASER,
    PRESENT_PEN,
    PRESENT_CLEAR_INK,

    /** While presenting: the speaker notes over the slide, or away. */
    PRESENT_NOTES,

    /** Duel (1.0.74): the duel simulator — a table, two seats, every card moved by hand. */
    GO_DUEL,
    DUEL_NEW,
    DUEL_DRAW,
    DUEL_SHUFFLE,
    DUEL_NEXT_PHASE,
    DUEL_END_TURN,
    DUEL_LP,
    /** "Hold on, I'm thinking" — a mark beside your life points the other player sees. */
    DUEL_THINK,
    DUEL_COMMAND,
    DUEL_CHAT,
    /**
     * Ai's cue at the foot of the log, by key (1.0.86): whatever its first button offers now — No response, Over to you,
     * Done, Don't wait, else Your move. Ai's own (in [AI]): never run by it, dead while it is off.
     */
    DUEL_AI_ANSWER,
    /** Ai reads what you did and asks about anything unclear, moving nothing (1.0.86). */
    DUEL_AI_CATCH_UP,
    /** One player's table or two. */
    DUEL_SIDES,
    /** Sit at the other seat (the hot-seat's turn of the table). */
    DUEL_SWAP,
    /** The far seat's cards turned round to face them, or upright (1.0.78). */
    DUEL_FACING,
    DUEL_RESOLVE,
    /** The whole chain resolved, newest link first (1.0.90, kai: "the chain system … better with a keyboard"). */
    DUEL_RESOLVE_ALL,
    /**
     * No response: priority passed across a hot-seat while a chain stands (1.0.90). Y, the key Ai's cues use, so it lives
     * only while Ai is off ([DeskAction.WITHOUT_AI]); with Ai on, Y passes this way at a table Ai does not sit at.
     */
    DUEL_PASS,
    /** The focused card into the selection, or out of it (1.0.90): several cards, one move. */
    DUEL_SELECT,
    /** Ordering several cards onto a Deck (1.0.90): the chosen card one place nearer the top, or the bottom. */
    DUEL_ORDER_EARLIER,
    DUEL_ORDER_LATER,

    /** The verbs, on the card under the pointer (or the selection). */
    DUEL_DEFAULT,
    DUEL_ACTIVATE,
    DUEL_SUMMON,
    DUEL_SPECIAL,
    DUEL_SET,
    DUEL_POSITION,
    DUEL_FLIP,
    DUEL_GRAVE,
    DUEL_BANISH,
    DUEL_BANISH_DOWN,
    DUEL_HAND,
    DUEL_DECK_TOP,
    DUEL_DECK_BOTTOM,
    DUEL_DECK_SHUFFLE,
    DUEL_EXTRA,
    DUEL_ATTACH,
    DUEL_REVEAL,
    DUEL_COUNTER_UP,
    DUEL_COUNTER_DOWN,
    DUEL_TARGET,
    /** In the Battle Phase: attack with it, then click their monster or their life points (1.0.86). */
    DUEL_ATTACK,
    /**
     * Command mode's voice (1.0.87, kai: "hold a key to talk"): held, the microphone listens; let go, what was
     * said is written out and shown as a move to confirm. A [HELD] action: pressed and let go by a hand only.
     */
    DUEL_VOICE,

    /** The card just placed, moved to that zone instead (the numbers shown on the free zones). */
    DUEL_ZONE_1,
    DUEL_ZONE_2,
    DUEL_ZONE_3,
    DUEL_ZONE_4,
    DUEL_ZONE_5,
    DUEL_ZONE_S1,
    DUEL_ZONE_S2,
    DUEL_ZONE_S3,
    DUEL_ZONE_S4,
    DUEL_ZONE_S5,
    DUEL_ZONE_EMZ_LEFT,
    DUEL_ZONE_EMZ_RIGHT,
    DUEL_ZONE_FIELD,

    /**
     * Command mode (1.0.87): a focus that walks the table by the arrows (`DuelFocus`), so a whole duel is
     * played without a mouse. The verbs, Space and the numbers act on it once the keys moved last.
     */
    DUEL_FOCUS_UP,
    DUEL_FOCUS_DOWN,
    DUEL_FOCUS_LEFT,
    DUEL_FOCUS_RIGHT,
    DUEL_FOCUS_ROW_START,
    DUEL_FOCUS_ROW_END,
    /** Enter: the focused card's verbs, the focused pile opened, the picked card put down — with no focus, chat. */
    DUEL_FOCUS_ACT,
    /** Pick up the focused card, to put it down where Enter is pressed next. */
    DUEL_PICK,
    /** Every place's coordinate written at its corner, as a chessboard's edge (`DuelPrefs.coordinates`). */
    DUEL_COORDINATES,
    /** Before turn 1 (1.0.87): throw this seat's two dice for who goes first, a fling with no hand behind it. */
    DUEL_ROLL,

    /** A replay (1.0.75): a step, a phase or a turn either way; the ends; play; edit. */
    REPLAY_BACK,
    REPLAY_FORWARD,
    REPLAY_BACK_PHASE,
    REPLAY_FORWARD_PHASE,
    REPLAY_BACK_TURN,
    REPLAY_FORWARD_TURN,
    REPLAY_START,
    REPLAY_END,
    REPLAY_PLAY,
    /** The step just played, taken out of the replay. */
    REPLAY_DELETE,
    /** "What if": play on from here as a duel of its own. */
    REPLAY_BRANCH,

    // Ai World (1.0.95): Ai's own computer, watched.
    GO_WORLD,
    /** Runs the file open in the editor. */
    WORLD_RUN,
    /** Stops the run in progress, Ai's or the person's. */
    WORLD_STOP,
    /** The page follows Ai to the pane it is working in, or stays where the person put it. */
    WORLD_FOLLOW,
    WORLD_NEW,
    WORLD_PANE_FILES,
    WORLD_PANE_EDITOR,
    WORLD_PANE_TERMINAL,
    WORLD_PANE_BOARDS,
    WORLD_PANE_THOUGHTS,
    WORLD_PANE_ACTIVITY,
    ;

    companion object {
        /** The assistant's own actions: live only while it is on, and never run by it (`run_action`). */
        val AI: Set<DeskAction> = setOf(AI_PANEL, AI_VOICE, AI_TALK, DUEL_AI_ANSWER, DUEL_AI_CATCH_UP)

        /**
         * Actions that are held, not pressed (1.0.87): a press starts them and letting go ends them
         * ([DeskShortcut.hold]). Never run by Ai (`run_action`): a press with no hand to let go would leave
         * the microphone open. Unlike [AI], they live whether Ai is on or off.
         */
        val HELD: Set<DeskAction> = setOf(DUEL_VOICE)

        /** Actions live only while Ai is off (1.0.90): they share a chord with an [AI] action, which wins while it lives. */
        val WITHOUT_AI: Set<DeskAction> = setOf(DUEL_PASS)
    }
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

    /** On Present, making slides, with nothing covering it and nothing being presented. */
    PRESENT_EDIT("Making slides"),

    /** While a presentation is playing. */
    PRESENTING("Presenting"),

    /** On Duel, with nothing covering it (1.0.74). Verbs act on the card under the pointer. */
    DUEL("Duelling"),

    /** A replay open on the Duel page (1.0.75). */
    REPLAY("Watching a replay"),

    /** On Ai World, with nothing covering it (1.0.95). */
    WORLD("In Ai World"),
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
    /** Ai is on (Settings → Ai). Off, every trace of it is gone, its key included. */
    val ai: Boolean = true,
    /** Present is the page on screen (1.0.70). */
    val onPresent: Boolean = false,
    /** A presentation is playing: the page's keys give way to the presenter's. */
    val presenting: Boolean = false,
    /** Duel is the page on screen (1.0.74). */
    val onDuel: Boolean = false,
    /** A replay is open on it (1.0.75): its keys stand in for the duel's. */
    val replaying: Boolean = false,
    /** Ai World is the page on screen (1.0.95). */
    val onWorld: Boolean = false,
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
    /**
     * Held, not pressed (1.0.87): the key going down starts [action] and the same key coming up ends it; the
     * key's own repeats while held are nothing. Only [DeskAction.HELD] actions, never [repeatable].
     */
    val hold: Boolean = false,
)

object DeskShortcuts {

    private fun ctrl(key: String, shift: Boolean = false) = KeyChord(key, ctrl = true, shift = shift)

    val all: List<DeskShortcut> = listOf(
        DeskShortcut(KeyChord("escape"), DeskAction.DISMISS, DeskScope.ANYWHERE, "Close whatever is on top", allowedInTextInput = true),
        DeskShortcut(ctrl("k"), DeskAction.PALETTE, DeskScope.ANYWHERE, "Command palette", allowedInTextInput = true),

        DeskShortcut(ctrl("1"), DeskAction.GO_BUILDER, DeskScope.APP, "Builder", allowedInTextInput = true),
        DeskShortcut(ctrl("2"), DeskAction.GO_DECKS, DeskScope.APP, "Decks", allowedInTextInput = true),
        DeskShortcut(ctrl("3"), DeskAction.GO_SIDING, DeskScope.APP, "Siding", allowedInTextInput = true),
        DeskShortcut(ctrl("4"), DeskAction.GO_FORMAT, DeskScope.APP, "Format: webs of decks", allowedInTextInput = true),
        DeskShortcut(ctrl("5"), DeskAction.GO_PREP, DeskScope.APP, "Prep: an event and its practice", allowedInTextInput = true),
        DeskShortcut(ctrl("6"), DeskAction.GO_PRESENT, DeskScope.APP, "Present: deck profiles as slides", allowedInTextInput = true),
        DeskShortcut(ctrl("7"), DeskAction.GO_DUEL, DeskScope.APP, "Duel: the duel simulator", allowedInTextInput = true),
        DeskShortcut(ctrl("8"), DeskAction.GO_WORLD, DeskScope.APP, "Ai World: Ai's own computer, watched", allowedInTextInput = true),
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
        DeskShortcut(ctrl("i"), DeskAction.AI_PANEL, DeskScope.APP, "Open or close the assistant", allowedInTextInput = true),
        DeskShortcut(ctrl("space", shift = true), DeskAction.AI_VOICE, DeskScope.APP, "Speak to the assistant", allowedInTextInput = true),
        DeskShortcut(ctrl("t", shift = true), DeskAction.AI_TALK, DeskScope.APP, "Talk mode: a conversation out loud", allowedInTextInput = true),
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
        DeskShortcut(KeyChord("k", shift = true), DeskAction.GROUP_ARRANGEMENT, DeskScope.BUILDER, "Groups as is, fitted or separate"),
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

        DeskShortcut(KeyChord("f5"), DeskAction.PRESENT_START, DeskScope.PRESENT_EDIT, "Present from the first slide", allowedInTextInput = true),
        DeskShortcut(KeyChord("f5", shift = true), DeskAction.PRESENT_FROM_HERE, DeskScope.PRESENT_EDIT, "Present from this slide", allowedInTextInput = true),
        DeskShortcut(ctrl("m"), DeskAction.SLIDE_NEW, DeskScope.PRESENT_EDIT, "New slide", allowedInTextInput = true),
        DeskShortcut(ctrl("d"), DeskAction.PRESENT_DUPLICATE, DeskScope.PRESENT_EDIT, "Duplicate the selection or the slide"),
        DeskShortcut(ctrl("c"), DeskAction.PRESENT_COPY, DeskScope.PRESENT_EDIT, "Copy"),
        DeskShortcut(ctrl("x"), DeskAction.PRESENT_CUT, DeskScope.PRESENT_EDIT, "Cut"),
        DeskShortcut(ctrl("v"), DeskAction.PRESENT_PASTE, DeskScope.PRESENT_EDIT, "Paste elements, slides or a picture"),
        DeskShortcut(ctrl("a"), DeskAction.PRESENT_SELECT_ALL, DeskScope.PRESENT_EDIT, "Select everything on the slide"),
        DeskShortcut(ctrl("g"), DeskAction.PRESENT_GROUP, DeskScope.PRESENT_EDIT, "Group the selection"),
        DeskShortcut(ctrl("g", shift = true), DeskAction.PRESENT_UNGROUP, DeskScope.PRESENT_EDIT, "Ungroup"),
        DeskShortcut(ctrl("bracketright"), DeskAction.BRING_FORWARD, DeskScope.PRESENT_EDIT, "Bring forward"),
        DeskShortcut(ctrl("bracketleft"), DeskAction.SEND_BACKWARD, DeskScope.PRESENT_EDIT, "Send backward"),
        DeskShortcut(ctrl("bracketright", shift = true), DeskAction.BRING_TO_FRONT, DeskScope.PRESENT_EDIT, "Bring to front"),
        DeskShortcut(ctrl("bracketleft", shift = true), DeskAction.SEND_TO_BACK, DeskScope.PRESENT_EDIT, "Send to back"),
        DeskShortcut(ctrl("z"), DeskAction.UNDO, DeskScope.PRESENT_EDIT, "Undo", repeatable = true),
        DeskShortcut(ctrl("z", shift = true), DeskAction.REDO, DeskScope.PRESENT_EDIT, "Redo", repeatable = true),
        DeskShortcut(ctrl("y"), DeskAction.REDO, DeskScope.PRESENT_EDIT, "Redo", repeatable = true),
        DeskShortcut(KeyChord("delete"), DeskAction.REMOVE_SELECTED, DeskScope.PRESENT_EDIT, "Delete the selection or the slide"),
        DeskShortcut(KeyChord("backspace"), DeskAction.REMOVE_SELECTED, DeskScope.PRESENT_EDIT, "Delete the selection or the slide"),
        DeskShortcut(KeyChord("left"), DeskAction.NUDGE_LEFT, DeskScope.PRESENT_EDIT, "Nudge left, or the slide before", repeatable = true),
        DeskShortcut(KeyChord("right"), DeskAction.NUDGE_RIGHT, DeskScope.PRESENT_EDIT, "Nudge right, or the slide after", repeatable = true),
        DeskShortcut(KeyChord("up"), DeskAction.NUDGE_UP, DeskScope.PRESENT_EDIT, "Nudge up, or the slide before", repeatable = true),
        DeskShortcut(KeyChord("down"), DeskAction.NUDGE_DOWN, DeskScope.PRESENT_EDIT, "Nudge down, or the slide after", repeatable = true),
        DeskShortcut(KeyChord("left", shift = true), DeskAction.NUDGE_LEFT_FAR, DeskScope.PRESENT_EDIT, "Nudge ten left", repeatable = true),
        DeskShortcut(KeyChord("right", shift = true), DeskAction.NUDGE_RIGHT_FAR, DeskScope.PRESENT_EDIT, "Nudge ten right", repeatable = true),
        DeskShortcut(KeyChord("up", shift = true), DeskAction.NUDGE_UP_FAR, DeskScope.PRESENT_EDIT, "Nudge ten up", repeatable = true),
        DeskShortcut(KeyChord("down", shift = true), DeskAction.NUDGE_DOWN_FAR, DeskScope.PRESENT_EDIT, "Nudge ten down", repeatable = true),
        DeskShortcut(ctrl("b"), DeskAction.TEXT_BOLD, DeskScope.PRESENT_EDIT, "Bold", allowedInTextInput = true),
        DeskShortcut(KeyChord("i", ctrl = true, alt = true), DeskAction.TEXT_ITALIC, DeskScope.PRESENT_EDIT, "Italic", allowedInTextInput = true),
        DeskShortcut(ctrl("u"), DeskAction.TEXT_UNDERLINE, DeskScope.PRESENT_EDIT, "Underline", allowedInTextInput = true),

        DeskShortcut(KeyChord("right"), DeskAction.PRESENT_NEXT, DeskScope.PRESENTING, "Next", repeatable = true),
        DeskShortcut(KeyChord("space"), DeskAction.PRESENT_NEXT, DeskScope.PRESENTING, "Next"),
        DeskShortcut(KeyChord("pagedown"), DeskAction.PRESENT_NEXT, DeskScope.PRESENTING, "Next, from a clicker"),
        DeskShortcut(KeyChord("enter"), DeskAction.PRESENT_NEXT, DeskScope.PRESENTING, "Next"),
        DeskShortcut(KeyChord("down"), DeskAction.PRESENT_NEXT, DeskScope.PRESENTING, "Next", repeatable = true),
        DeskShortcut(KeyChord("n"), DeskAction.PRESENT_NEXT, DeskScope.PRESENTING, "Next"),
        DeskShortcut(KeyChord("left"), DeskAction.PRESENT_PREVIOUS, DeskScope.PRESENTING, "Back", repeatable = true),
        DeskShortcut(KeyChord("pageup"), DeskAction.PRESENT_PREVIOUS, DeskScope.PRESENTING, "Back, from a clicker"),
        DeskShortcut(KeyChord("backspace"), DeskAction.PRESENT_PREVIOUS, DeskScope.PRESENTING, "Back"),
        DeskShortcut(KeyChord("up"), DeskAction.PRESENT_PREVIOUS, DeskScope.PRESENTING, "Back", repeatable = true),
        DeskShortcut(KeyChord("p"), DeskAction.PRESENT_PREVIOUS, DeskScope.PRESENTING, "Back"),
        DeskShortcut(KeyChord("home"), DeskAction.PRESENT_FIRST, DeskScope.PRESENTING, "First slide"),
        DeskShortcut(KeyChord("end"), DeskAction.PRESENT_LAST, DeskScope.PRESENTING, "Last slide"),
        DeskShortcut(KeyChord("d"), DeskAction.PRESENT_DECK, DeskScope.PRESENTING, "The whole deck, or back to the slide"),
        DeskShortcut(KeyChord("b"), DeskAction.PRESENT_BLACK, DeskScope.PRESENTING, "Black screen"),
        DeskShortcut(KeyChord("w"), DeskAction.PRESENT_WHITE, DeskScope.PRESENTING, "White screen"),
        DeskShortcut(KeyChord("l"), DeskAction.PRESENT_LASER, DeskScope.PRESENTING, "Laser pointer"),
        DeskShortcut(KeyChord("e"), DeskAction.PRESENT_PEN, DeskScope.PRESENTING, "Draw on the slide"),
        DeskShortcut(KeyChord("e", shift = true), DeskAction.PRESENT_CLEAR_INK, DeskScope.PRESENTING, "Clear the drawing"),
        DeskShortcut(KeyChord("s"), DeskAction.PRESENT_NOTES, DeskScope.PRESENTING, "Speaker notes"),

        DeskShortcut(ctrl("n", shift = true), DeskAction.DUEL_NEW, DeskScope.DUEL, "New duel", allowedInTextInput = true),
        DeskShortcut(ctrl("z"), DeskAction.UNDO, DeskScope.DUEL, "Undo", repeatable = true),
        DeskShortcut(ctrl("z", shift = true), DeskAction.REDO, DeskScope.DUEL, "Redo", repeatable = true),
        DeskShortcut(ctrl("y"), DeskAction.REDO, DeskScope.DUEL, "Redo", repeatable = true),
        DeskShortcut(KeyChord("d"), DeskAction.DUEL_DRAW, DeskScope.DUEL, "Draw a card", repeatable = true),
        DeskShortcut(KeyChord("d", shift = true), DeskAction.DUEL_SHUFFLE, DeskScope.DUEL, "Shuffle the deck"),
        DeskShortcut(KeyChord("n"), DeskAction.DUEL_NEXT_PHASE, DeskScope.DUEL, "Next phase"),
        DeskShortcut(KeyChord("n", shift = true), DeskAction.DUEL_END_TURN, DeskScope.DUEL, "End the turn"),
        DeskShortcut(KeyChord("l"), DeskAction.DUEL_LP, DeskScope.DUEL, "Change life points"),
        DeskShortcut(KeyChord("w"), DeskAction.DUEL_THINK, DeskScope.DUEL, "I'm thinking, or ready again"),
        DeskShortcut(KeyChord("slash"), DeskAction.DUEL_COMMAND, DeskScope.DUEL, "Command mode: type a move, or ask (any letter that is no key opens it too)"),
        DeskShortcut(ctrl("l"), DeskAction.DUEL_COMMAND, DeskScope.DUEL, "Command mode, from anywhere on the duel", allowedInTextInput = true),
        DeskShortcut(ctrl("enter"), DeskAction.DUEL_CHAT, DeskScope.DUEL, "Chat (Enter too, when nothing is focused)"),
        DeskShortcut(KeyChord("y"), DeskAction.DUEL_AI_ANSWER, DeskScope.DUEL, "Answer the assistant: No response, Over to you, Done, Don't wait or Your move"),
        DeskShortcut(KeyChord("y", shift = true), DeskAction.DUEL_AI_CATCH_UP, DeskScope.DUEL, "The assistant catches up: reads what you did, moves nothing"),
        DeskShortcut(KeyChord("v"), DeskAction.DUEL_SIDES, DeskScope.DUEL, "One player's table or two"),
        DeskShortcut(KeyChord("tab"), DeskAction.DUEL_SWAP, DeskScope.DUEL, "Sit at the other seat"),
        DeskShortcut(KeyChord("f", shift = true), DeskAction.DUEL_FACING, DeskScope.DUEL, "Their cards face them, or face you"),
        DeskShortcut(KeyChord("q"), DeskAction.DUEL_RESOLVE, DeskScope.DUEL, "Resolve the newest chain link"),
        DeskShortcut(KeyChord("q", shift = true), DeskAction.DUEL_RESOLVE_ALL, DeskScope.DUEL, "Resolve the whole chain, newest link first"),
        DeskShortcut(KeyChord("y"), DeskAction.DUEL_PASS, DeskScope.DUEL, "No response: pass while a chain stands"),
        DeskShortcut(KeyChord("space", shift = true), DeskAction.DUEL_SELECT, DeskScope.DUEL, "Select the focused card too, or let it go: then one verb moves them all"),
        DeskShortcut(KeyChord("left", alt = true), DeskAction.DUEL_ORDER_EARLIER, DeskScope.DUEL, "Ordering cards onto the Deck: the chosen card one place nearer the top", repeatable = true),
        DeskShortcut(KeyChord("right", alt = true), DeskAction.DUEL_ORDER_LATER, DeskScope.DUEL, "Ordering cards onto the Deck: the chosen card one place further down", repeatable = true),
        DeskShortcut(KeyChord("space"), DeskAction.DUEL_DEFAULT, DeskScope.DUEL, "The obvious thing for the card under the pointer"),
        DeskShortcut(KeyChord("a"), DeskAction.DUEL_ACTIVATE, DeskScope.DUEL, "Activate it"),
        DeskShortcut(KeyChord("s"), DeskAction.DUEL_SUMMON, DeskScope.DUEL, "Summon it, or Flip Summon it"),
        DeskShortcut(KeyChord("s", shift = true), DeskAction.DUEL_SPECIAL, DeskScope.DUEL, "Special Summon it"),
        DeskShortcut(KeyChord("e"), DeskAction.DUEL_SET, DeskScope.DUEL, "Set it"),
        DeskShortcut(KeyChord("p"), DeskAction.DUEL_POSITION, DeskScope.DUEL, "Attack or Defense Position"),
        DeskShortcut(KeyChord("f"), DeskAction.DUEL_FLIP, DeskScope.DUEL, "Turn it face-up or face-down"),
        DeskShortcut(KeyChord("g"), DeskAction.DUEL_GRAVE, DeskScope.DUEL, "Send it to the GY"),
        DeskShortcut(KeyChord("b"), DeskAction.DUEL_BANISH, DeskScope.DUEL, "Banish it"),
        DeskShortcut(KeyChord("b", shift = true), DeskAction.DUEL_BANISH_DOWN, DeskScope.DUEL, "Banish it face-down"),
        DeskShortcut(KeyChord("h"), DeskAction.DUEL_HAND, DeskScope.DUEL, "Return it to the hand"),
        DeskShortcut(KeyChord("k"), DeskAction.DUEL_DECK_TOP, DeskScope.DUEL, "Put it on top of the deck"),
        DeskShortcut(KeyChord("k", shift = true), DeskAction.DUEL_DECK_BOTTOM, DeskScope.DUEL, "Put it on the bottom of the deck"),
        DeskShortcut(KeyChord("k", alt = true), DeskAction.DUEL_DECK_SHUFFLE, DeskScope.DUEL, "Shuffle it into the deck"),
        DeskShortcut(KeyChord("x"), DeskAction.DUEL_EXTRA, DeskScope.DUEL, "Return it to the Extra Deck"),
        DeskShortcut(KeyChord("o"), DeskAction.DUEL_ATTACH, DeskScope.DUEL, "Attach it as material: then click the card it goes under"),
        DeskShortcut(KeyChord("r"), DeskAction.DUEL_REVEAL, DeskScope.DUEL, "Reveal it"),
        DeskShortcut(KeyChord("c"), DeskAction.DUEL_COUNTER_UP, DeskScope.DUEL, "Put a counter on it", repeatable = true),
        DeskShortcut(KeyChord("c", shift = true), DeskAction.DUEL_COUNTER_DOWN, DeskScope.DUEL, "Take a counter off it", repeatable = true),
        DeskShortcut(KeyChord("t"), DeskAction.DUEL_TARGET, DeskScope.DUEL, "Target it, or take the arrow back"),
        DeskShortcut(KeyChord("a", shift = true), DeskAction.DUEL_ATTACK, DeskScope.DUEL, "Attack with it: then click their monster, or their life points for a direct attack"),
        // Hold to speak (1.0.87). While typing in the command line M types an m ("m3"), so Alt M is the same key there.
        DeskShortcut(KeyChord("m"), DeskAction.DUEL_VOICE, DeskScope.DUEL, "Hold to speak a command; let go to send", hold = true),
        DeskShortcut(KeyChord("m", alt = true), DeskAction.DUEL_VOICE, DeskScope.DUEL, "Hold to speak a command, also while typing", allowedInTextInput = true, hold = true),
        DeskShortcut(KeyChord("1"), DeskAction.DUEL_ZONE_1, DeskScope.DUEL, "The card just placed to Monster Zone 1, or Spell & Trap Zone 1"),
        DeskShortcut(KeyChord("2"), DeskAction.DUEL_ZONE_2, DeskScope.DUEL, "To zone 2"),
        DeskShortcut(KeyChord("3"), DeskAction.DUEL_ZONE_3, DeskScope.DUEL, "To zone 3"),
        DeskShortcut(KeyChord("4"), DeskAction.DUEL_ZONE_4, DeskScope.DUEL, "To zone 4"),
        DeskShortcut(KeyChord("5"), DeskAction.DUEL_ZONE_5, DeskScope.DUEL, "To zone 5"),
        DeskShortcut(KeyChord("1", shift = true), DeskAction.DUEL_ZONE_S1, DeskScope.DUEL, "To Spell & Trap Zone 1"),
        DeskShortcut(KeyChord("2", shift = true), DeskAction.DUEL_ZONE_S2, DeskScope.DUEL, "To Spell & Trap Zone 2"),
        DeskShortcut(KeyChord("3", shift = true), DeskAction.DUEL_ZONE_S3, DeskScope.DUEL, "To Spell & Trap Zone 3"),
        DeskShortcut(KeyChord("4", shift = true), DeskAction.DUEL_ZONE_S4, DeskScope.DUEL, "To Spell & Trap Zone 4"),
        DeskShortcut(KeyChord("5", shift = true), DeskAction.DUEL_ZONE_S5, DeskScope.DUEL, "To Spell & Trap Zone 5"),
        DeskShortcut(KeyChord("6"), DeskAction.DUEL_ZONE_EMZ_LEFT, DeskScope.DUEL, "To the left Extra Monster Zone"),
        DeskShortcut(KeyChord("7"), DeskAction.DUEL_ZONE_EMZ_RIGHT, DeskScope.DUEL, "To the right Extra Monster Zone"),
        DeskShortcut(KeyChord("0"), DeskAction.DUEL_ZONE_FIELD, DeskScope.DUEL, "To the Field Zone"),
        DeskShortcut(KeyChord("up"), DeskAction.DUEL_FOCUS_UP, DeskScope.DUEL, "Walk the table: the place above, or the verb above in the menu", repeatable = true),
        DeskShortcut(KeyChord("down"), DeskAction.DUEL_FOCUS_DOWN, DeskScope.DUEL, "Walk the table: the place below, or the verb below in the menu", repeatable = true),
        DeskShortcut(KeyChord("left"), DeskAction.DUEL_FOCUS_LEFT, DeskScope.DUEL, "Walk the table: the place to the left", repeatable = true),
        DeskShortcut(KeyChord("right"), DeskAction.DUEL_FOCUS_RIGHT, DeskScope.DUEL, "Walk the table: the place to the right", repeatable = true),
        DeskShortcut(KeyChord("left", shift = true), DeskAction.DUEL_FOCUS_ROW_START, DeskScope.DUEL, "The row's first place"),
        DeskShortcut(KeyChord("right", shift = true), DeskAction.DUEL_FOCUS_ROW_END, DeskScope.DUEL, "The row's last place"),
        DeskShortcut(KeyChord("enter"), DeskAction.DUEL_FOCUS_ACT, DeskScope.DUEL, "Act on the focus: the card's verbs, the pile opened, the picked card put down; with nothing focused, chat"),
        DeskShortcut(KeyChord("enter", shift = true), DeskAction.DUEL_PICK, DeskScope.DUEL, "Pick up the focused card: then Enter where it goes"),
        DeskShortcut(KeyChord("i"), DeskAction.DUEL_COORDINATES, DeskScope.DUEL, "Coordinates on every place, or none"),
        DeskShortcut(KeyChord("r", shift = true), DeskAction.DUEL_ROLL, DeskScope.DUEL, "Before turn 1: throw your dice for who goes first"),

        DeskShortcut(KeyChord("left"), DeskAction.REPLAY_BACK, DeskScope.REPLAY, "A step back", repeatable = true),
        DeskShortcut(KeyChord("right"), DeskAction.REPLAY_FORWARD, DeskScope.REPLAY, "A step on", repeatable = true),
        DeskShortcut(KeyChord("left", shift = true), DeskAction.REPLAY_BACK_PHASE, DeskScope.REPLAY, "A phase back", repeatable = true),
        DeskShortcut(KeyChord("right", shift = true), DeskAction.REPLAY_FORWARD_PHASE, DeskScope.REPLAY, "A phase on", repeatable = true),
        DeskShortcut(ctrl("left"), DeskAction.REPLAY_BACK_TURN, DeskScope.REPLAY, "A turn back", repeatable = true),
        DeskShortcut(ctrl("right"), DeskAction.REPLAY_FORWARD_TURN, DeskScope.REPLAY, "A turn on", repeatable = true),
        DeskShortcut(KeyChord("home"), DeskAction.REPLAY_START, DeskScope.REPLAY, "The start"),
        DeskShortcut(KeyChord("end"), DeskAction.REPLAY_END, DeskScope.REPLAY, "The end"),
        DeskShortcut(KeyChord("space"), DeskAction.REPLAY_PLAY, DeskScope.REPLAY, "Play or pause"),
        DeskShortcut(KeyChord("delete"), DeskAction.REPLAY_DELETE, DeskScope.REPLAY, "Take out the step just played"),
        DeskShortcut(KeyChord("enter"), DeskAction.REPLAY_BRANCH, DeskScope.REPLAY, "What if: play on from here"),
        DeskShortcut(ctrl("z"), DeskAction.UNDO, DeskScope.REPLAY, "A step back", repeatable = true),
        DeskShortcut(ctrl("z", shift = true), DeskAction.REDO, DeskScope.REPLAY, "A step on", repeatable = true),

        DeskShortcut(KeyChord("up"), DeskAction.POOL_PREVIOUS, DeskScope.POOL, "Previous result, or the card above the selected one", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("down"), DeskAction.POOL_NEXT, DeskScope.POOL, "Next result, or the card below the selected one", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("enter"), DeskAction.POOL_ADD, DeskScope.POOL, "Add the result to the deck", allowedInTextInput = true, repeatable = true),
        DeskShortcut(KeyChord("enter", shift = true), DeskAction.POOL_ADD_TO_SIDE, DeskScope.POOL, "Add the result to the side deck", allowedInTextInput = true, repeatable = true),
        // Ai World (1.0.95).
        DeskShortcut(ctrl("enter"), DeskAction.WORLD_RUN, DeskScope.WORLD, "Run the file in the editor", allowedInTextInput = true),
        DeskShortcut(ctrl("period"), DeskAction.WORLD_STOP, DeskScope.WORLD, "Stop the run", allowedInTextInput = true),
        DeskShortcut(KeyChord("f"), DeskAction.WORLD_FOLLOW, DeskScope.WORLD, "Follow Ai from pane to pane, or stay put"),
        DeskShortcut(KeyChord("n", alt = true), DeskAction.WORLD_NEW, DeskScope.WORLD, "A new world"),
        DeskShortcut(KeyChord("1", alt = true), DeskAction.WORLD_PANE_FILES, DeskScope.WORLD, "Files", allowedInTextInput = true),
        DeskShortcut(KeyChord("2", alt = true), DeskAction.WORLD_PANE_EDITOR, DeskScope.WORLD, "Editor", allowedInTextInput = true),
        DeskShortcut(KeyChord("3", alt = true), DeskAction.WORLD_PANE_TERMINAL, DeskScope.WORLD, "Terminal", allowedInTextInput = true),
        DeskShortcut(KeyChord("4", alt = true), DeskAction.WORLD_PANE_BOARDS, DeskScope.WORLD, "Boards", allowedInTextInput = true),
        DeskShortcut(KeyChord("5", alt = true), DeskAction.WORLD_PANE_THOUGHTS, DeskScope.WORLD, "Thoughts", allowedInTextInput = true),
        DeskShortcut(KeyChord("6", alt = true), DeskAction.WORLD_PANE_ACTIVITY, DeskScope.WORLD, "Activity", allowedInTextInput = true),
    )

    fun resolve(chord: KeyChord, context: DeskContext): DeskAction? = resolveShortcut(chord, context)?.action

    fun resolveShortcut(chord: KeyChord, context: DeskContext): DeskShortcut? =
        live(context).firstOrNull { it.chord == chord }

    /** Every row that would fire in [context], in table order. */
    fun live(context: DeskContext): List<DeskShortcut> =
        all.filter {
            (!context.textInputFocused || it.allowedInTextInput) && it.isActive(context) &&
                (context.ai || it.action !in DeskAction.AI) && !(context.ai && it.action in DeskAction.WITHOUT_AI)
        }

    /** The first chord bound to [action], for hints beside a menu item or in a tooltip. */
    fun chordFor(action: DeskAction): KeyChord? = all.firstOrNull { it.action == action }?.chord

    private fun DeskShortcut.isActive(context: DeskContext): Boolean = when (scope) {
        DeskScope.ANYWHERE -> true
        DeskScope.APP -> !context.overlayOpen && !context.presenting
        DeskScope.BUILDER -> !context.overlayOpen && context.onBuilder
        // Typing anywhere but the search field (the deck name) must not add cards.
        DeskScope.POOL -> !context.overlayOpen && context.onBuilder &&
            (context.searchFocused || !context.textInputFocused)
        DeskScope.PRESENT_EDIT -> !context.overlayOpen && context.onPresent && !context.presenting && !context.onBuilder
        DeskScope.PRESENTING -> !context.overlayOpen && context.presenting
        DeskScope.DUEL -> !context.overlayOpen && context.onDuel && !context.onBuilder && !context.replaying
        DeskScope.REPLAY -> !context.overlayOpen && context.onDuel && !context.onBuilder && context.replaying
        DeskScope.WORLD -> !context.overlayOpen && context.onWorld && !context.onBuilder
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
        "f5" -> "F5"
        "pageup" -> "Page Up"
        "pagedown" -> "Page Down"
        "home" -> "Home"
        "end" -> "End"
        "tab" -> "Tab"
        "bracketleft" -> "["
        "bracketright" -> "]"
        else -> key.uppercase()
    }
}
