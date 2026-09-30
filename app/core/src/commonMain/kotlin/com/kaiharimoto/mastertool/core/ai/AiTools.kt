package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.search.EffectKind

/**
 * Everything Ai can do in the app, as data: the prompt lists it, every backend
 * sends it, the app's MCP server serves it, and the app's `AiHost` answers each
 * name. `AiToolsTest` holds the catalogue to its promises — every desk action is
 * reachable, every setting is settable — so a feature added later cannot quietly
 * fall outside Ai's reach.
 *
 * Cards are named the way a player names them: `"Ash Blossom & Joyous Spring"`,
 * a passcode, or with a count in front, `"3 Ash Blossom"` / `"3x Ash Blossom"`.
 */
object AiTools {
    val PAGES = listOf("DECKS", "BUILDER", "SIDING", "FORMAT", "SETTINGS")
    val SECTIONS = listOf("main", "extra", "side")
    val EXPORTS = listOf("ydk", "ydkx", "ydke", "text", "qr")

    private const val CARD_WORDS =
        "Cards by name as a player writes them, or by passcode; a count may lead (\"3 Ash Blossom & Joyous Spring\", \"2x 14558127\")."

    val appState = ToolSpec(
        "app_state",
        "What is on screen now: the page, the deck open in the builder (name, id, counts, legality, its web), " +
            "the selected card, the format (TCG/OCG) and the webs. Call it first when unsure where the person is.",
        schema { },
        ToolGroup.LOOK,
    )

    val listDecks = ToolSpec(
        "list_decks",
        "The deck library: every saved deck with its id, name, counts and tags, newest first. " +
            "Decks inside a web are listed with the web's name. Optional query matches a name or a card in the deck.",
        schema { string("query", "Only decks whose name, tags or cards match this") },
        ToolGroup.LOOK,
    )

    val getDeck = ToolSpec(
        "get_deck",
        "One deck's full list by section with card names, counts and passcodes, its groups and its legality. " +
            "Without deck_id, the deck open in the builder (including unsaved changes).",
        schema { string("deck_id", "The deck's id from list_decks; omit for the open deck") },
        ToolGroup.LOOK,
    )

    val validateDeck = ToolSpec(
        "validate_deck",
        "Checks a deck against the format's banlist and the size rules and lists every issue. Without deck_id, the open deck.",
        schema {
            string("deck_id", "Omit for the open deck")
            enum("format", "Which banlist; defaults to the app's format", listOf("TCG", "OCG"))
        },
        ToolGroup.LOOK,
    )

    val getSettings = ToolSpec(
        "get_settings",
        "Every setting of the app with its current value and what it does. Change one with set_setting.",
        schema { },
        ToolGroup.LOOK,
    )

    val listWebs = ToolSpec(
        "list_webs",
        "The webs of decks (Format page): each web is the field expected at an event — its decks, their share of the " +
            "field, and which deck is the person's own (starred).",
        schema { },
        ToolGroup.LOOK,
    )

    val getWeb = ToolSpec(
        "get_web",
        "One web in full: notes, and each deck with its id, share, star and main-deck headline cards.",
        schema { string("web_id", "The web's id from list_webs", required = true) },
        ToolGroup.LOOK,
    )

    val getSiding = ToolSpec(
        "get_siding",
        "A deck's siding plans: for each matchup, going first and going second, the cards out, the cards in, and why.",
        schema {
            string("deck_id", "The deck being sided", required = true)
            string("against", "Only the matchup against this web deck id or matchup name")
        },
        ToolGroup.LOOK,
    )

    val searchCards = ToolSpec(
        "search_cards",
        "Searches the whole card pool (every card ever printed) by name and/or by what the card says, with filters. " +
            "Returns name, passcode, type, stats, archetype, ban status and a short effect. Use it before adding a card " +
            "you are not certain of by exact name. A query may carry its own scope: `text:` or `name:`, and \"quoted words\" must appear together.",
        schema {
            string("query", "Words to look for; empty lists by filters alone")
            enum("scope", "Search names, printed text or both (default both)", listOf("names", "text", "all"))
            strings("categories", "Monster, Spell or Trap", values = listOf("MONSTER", "SPELL", "TRAP"))
            strings("attributes", "e.g. DARK, LIGHT", values = listOf("DARK", "LIGHT", "EARTH", "WATER", "FIRE", "WIND", "DIVINE"))
            strings("types", "Monster types or Spell/Trap properties, e.g. Spellcaster, Quick-Play, Counter")
            integers("levels", "Levels or Ranks")
            strings("archetypes", "Archetypes as the pool names them, e.g. Branded, Mitsurugi")
            strings("effects", "What the card does, read from its text; the card must do all of them",
                values = EffectKind.entries.map { it.name })
            boolean("extra_deck", "true: only Extra Deck monsters; false: only main-deck cards")
            integer("atk_min", "Lowest ATK"); integer("atk_max", "Highest ATK")
            integer("def_min", "Lowest DEF"); integer("def_max", "Highest DEF")
            strings("ban_status", "Only cards at these limits in the app's format", values = listOf("FORBIDDEN", "LIMITED", "SEMI_LIMITED", "UNLIMITED"))
            integer("limit", "How many results (default 20, at most 60)", min = 1, max = 60)
        },
        ToolGroup.CARDS,
    )

    val cardInfo = ToolSpec(
        "card_info",
        "The full printed text and details of cards: type, attribute, stats, archetype, TCG and OCG ban status, " +
            "alternate artworks. $CARD_WORDS",
        schema { strings("cards", "The cards to read", required = true) },
        ToolGroup.CARDS,
    )

    val showInPool = ToolSpec(
        "show_in_pool",
        "Puts a search into the builder's card pool so the person sees the results (the same filters as search_cards).",
        schema {
            string("query", "Words to search for")
            strings("categories", "MONSTER, SPELL, TRAP")
            strings("archetypes", "Archetypes")
            strings("effects", "Effect kinds", values = EffectKind.entries.map { it.name })
            boolean("clear", "Clear the search and every filter")
        },
        ToolGroup.CARDS,
    )

    val openDeck = ToolSpec(
        "open_deck",
        "Opens a deck in the builder (the deck there is saved first).",
        schema { string("deck_id", "The deck's id", required = true) },
        ToolGroup.BUILD,
    )

    val newDeck = ToolSpec(
        "new_deck",
        "Starts a new deck in the builder with these cards and saves it to the library. $CARD_WORDS " +
            "Extra Deck monsters listed under main go to the extra deck by themselves. Returns what could not be added and why.",
        schema {
            string("name", "The deck's name", required = true)
            strings("main", "Main deck cards")
            strings("extra", "Extra deck cards")
            strings("side", "Side deck cards")
        },
        ToolGroup.BUILD,
    )

    val editDeck = ToolSpec(
        "edit_deck",
        "Changes the deck open in the builder, one step per op, in order; each lands on undo. " +
            "add (count copies, default 1), remove (count copies, default all), set (exactly count copies), " +
            "move (count copies from section to to_section). $CARD_WORDS Returns what each op did.",
        schema {
            objects("ops", "The changes, in order", required = true) {
                enum("op", "What to do", listOf("add", "remove", "set", "move"), required = true)
                string("card", "The card", required = true)
                integer("count", "How many copies", min = 0, max = 3)
                enum("section", "Where (default: main, or extra for Extra Deck monsters)", SECTIONS)
                enum("to_section", "For move: where to", SECTIONS)
            }
        },
        ToolGroup.BUILD,
    )

    val setGroups = ToolSpec(
        "set_groups",
        "Sorts the open deck's cards into named groups (the Groups button: engine, starters, hand traps…), each with its cards. " +
            "replace: true clears the old groups first; otherwise groups of the same name are updated. " +
            "A card belongs to one group at most. Colours are 0–6.",
        schema {
            objects("groups", "The groups", required = true) {
                string("name", "The group's name", required = true)
                strings("cards", "Its cards, by name or passcode", required = true)
                integer("color", "Palette index 0–6", min = 0, max = 6)
            }
            boolean("replace", "Clear every existing group first")
            boolean("show", "Show the deck in its groups afterwards (default true)")
        },
        ToolGroup.BUILD,
    )

    val renameDeck = ToolSpec(
        "rename_deck",
        "Renames the deck open in the builder.",
        schema { string("name", "The new name", required = true) },
        ToolGroup.BUILD,
    )

    val saveDeck = ToolSpec(
        "save_deck",
        "Saves the deck open in the builder to the library.",
        schema { },
        ToolGroup.BUILD,
    )

    val undo = ToolSpec(
        "undo",
        "Undoes the last changes to the open deck (or redoes them with redo: true).",
        schema {
            integer("steps", "How many steps (default 1)", min = 1, max = 50)
            boolean("redo", "Redo instead")
        },
        ToolGroup.BUILD,
    )

    val importDeck = ToolSpec(
        "import_deck",
        "Makes a new deck from a decklist in text: a .ydk/.ydkx file's lines, a ydke:// code, or a list of " +
            "\"3 Card Name\" lines under Main/Extra/Side headings. It opens in the builder and is saved.",
        schema {
            string("text", "The decklist", required = true)
            string("name", "The deck's name")
        },
        ToolGroup.BUILD,
    )

    val exportDeck = ToolSpec(
        "export_deck",
        "Exports the open deck the way the Export menu does: a .ydk or .ydkx file (a save dialog opens), a ydke:// code " +
            "or a text list (copied to the clipboard), or a QR code shown on screen.",
        schema { enum("format", "How", EXPORTS, required = true) },
        ToolGroup.BUILD,
    )

    val deleteDeck = ToolSpec(
        "delete_deck",
        "Deletes a deck from the library for good. The person is asked to confirm.",
        schema { string("deck_id", "The deck's id", required = true) },
        ToolGroup.BUILD,
        destructive = true,
    )

    val createWeb = ToolSpec(
        "create_web",
        "Makes a new web of decks on the Format page: the field expected at an event.",
        schema {
            string("name", "The web's name, e.g. the event", required = true)
            string("notes", "Notes about the event")
        },
        ToolGroup.FORMAT,
    )

    val addDeckToWeb = ToolSpec(
        "add_deck_to_web",
        "Adds a deck to a web: a copy of a library deck (deck_id), or a new one from a decklist (text, as import_deck reads). " +
            "share is its expected share of the field in percent; mine: true stars it as the person's own deck.",
        schema {
            string("web_id", "The web", required = true)
            string("deck_id", "A library deck to copy in")
            string("text", "Or a decklist to make the deck from")
            string("name", "The deck's name (with text)")
            integer("share", "Percent of the field", min = 0, max = 100)
            boolean("mine", "The person's own deck")
        },
        ToolGroup.FORMAT,
    )

    val setWebEntry = ToolSpec(
        "set_web_entry",
        "Changes one deck in a web: its share of the field, whether it is the person's own (starred), its place in the list.",
        schema {
            string("web_id", "The web", required = true)
            string("deck_id", "The deck", required = true)
            integer("share", "Percent of the field; -1 clears it", min = -1, max = 100)
            boolean("mine", "Star or unstar it as the person's own")
            integer("position", "Its place in the web, from 0", min = 0)
        },
        ToolGroup.FORMAT,
    )

    val setWebNotes = ToolSpec(
        "set_web_notes",
        "Renames a web or replaces its notes.",
        schema {
            string("web_id", "The web", required = true)
            string("name", "A new name")
            string("notes", "The new notes")
        },
        ToolGroup.FORMAT,
    )

    val removeFromWeb = ToolSpec(
        "remove_from_web",
        "Takes a deck out of a web and deletes that copy. The person is asked to confirm.",
        schema {
            string("web_id", "The web", required = true)
            string("deck_id", "The deck", required = true)
        },
        ToolGroup.FORMAT,
        destructive = true,
    )

    val deleteWeb = ToolSpec(
        "delete_web",
        "Deletes a web and every deck in it. The person is asked to confirm.",
        schema { string("web_id", "The web", required = true) },
        ToolGroup.FORMAT,
        destructive = true,
    )

    val setSidingPlan = ToolSpec(
        "set_siding_plan",
        "Writes a siding plan: deck_id sided against a matchup (a web deck id, or a name), for one turn, " +
            "the cards out of the main/extra deck and in from the side deck, one entry per copy, and why. " +
            "Replaces that turn's plan. $CARD_WORDS",
        schema {
            string("deck_id", "The deck being sided", required = true)
            string("against", "The opponent's web deck id, or a matchup name", required = true)
            enum("turn", "Going first or second", listOf("first", "second"), required = true)
            strings("out", "Cards taken out, one entry per copy or with a count")
            strings("in", "Cards brought in from the side deck")
            string("why", "The reason, in a sentence or two")
        },
        ToolGroup.FORMAT,
    )

    val navigate = ToolSpec(
        "navigate",
        "Goes to a page: DECKS (the library), BUILDER, SIDING, FORMAT (webs of decks), SETTINGS.",
        schema { enum("page", "The page", PAGES, required = true) },
        ToolGroup.APP,
    )

    val runAction = ToolSpec(
        "run_action",
        "Does anything the keyboard or the command palette can: the action's name from this list. " +
            "Actions that act on 'the selected card' need a card selected.",
        schema { enum("action", "The action", DeskAction.entries.filter { it != DeskAction.AI_PANEL }.map { it.name }, required = true) },
        ToolGroup.APP,
    )

    val setSetting = ToolSpec(
        "set_setting",
        "Changes one setting by its key from get_settings. Turning Ai off (ai.enabled false) asks the person first.",
        schema {
            string("key", "The setting's key", required = true)
            any("value", "Its new value", required = true)
        },
        ToolGroup.APP,
    )

    val memory = ToolSpec(
        "memory",
        "Your own long-term memory, kept as short markdown entries. user: what you learn about the person (their events, " +
            "format, decks, habits, what they want from you). agent: what you learned about doing this job well. " +
            "deck / web: notes about the deck or web in scope (the open deck, or the web it belongs to). " +
            "add a new entry; replace an entry (old_text is a unique part of it); remove one. Keep entries short and durable: " +
            "facts that will still matter next week, never a transcript.",
        schema {
            enum("action", "What to do", listOf("add", "replace", "remove"), required = true)
            enum("scope", "Which memory", listOf("user", "agent", "deck", "web"), required = true)
            string("text", "The entry (add, replace)")
            string("old_text", "A unique part of the entry to replace or remove")
        },
        ToolGroup.MEMORY,
    )

    val memoryRead = ToolSpec(
        "memory_read",
        "Reads a memory file in full: user, agent, or the notes of a deck or web by id.",
        schema {
            enum("scope", "Which memory", listOf("user", "agent", "deck", "web"), required = true)
            string("id", "For deck or web: its id (default: the one in scope)")
        },
        ToolGroup.MEMORY,
    )

    val skillView = ToolSpec(
        "skill_view",
        "Reads one of your skills in full: step-by-step know-how for a kind of task. The skills are listed in your instructions by name.",
        schema { string("name", "The skill's name", required = true) },
        ToolGroup.MEMORY,
    )

    val skillManage = ToolSpec(
        "skill_manage",
        "Writes a skill of your own, or improves one you wrote, after working out how to do a kind of task well " +
            "(what worked, in steps). create needs name, description and body; patch replaces old_text with new_text.",
        schema {
            enum("action", "What to do", listOf("create", "patch", "delete"), required = true)
            string("name", "The skill's name, lowercase-with-dashes", required = true)
            string("description", "One line: when to use it")
            string("body", "The skill, in markdown")
            string("old_text", "For patch: the text to replace")
            string("new_text", "For patch: the new text")
        },
        ToolGroup.MEMORY,
        phase = 3,
    )

    val sessionSearch = ToolSpec(
        "session_search",
        "Searches your past conversations with the person for words, newest first, and returns the matching lines.",
        schema {
            string("query", "Words to find", required = true)
            integer("limit", "How many matches (default 12)", min = 1, max = 40)
        },
        ToolGroup.MEMORY,
        phase = 3,
    )

    val askUser = ToolSpec(
        "ask_user",
        "Asks the person one question with answers to tap (and room to type their own), and waits for the answer. " +
            "Use it in Fine Tuning and whenever a choice is theirs to make.",
        schema {
            string("question", "The question", required = true)
            strings("options", "Two to six short answers", required = true)
            boolean("multiple", "More than one answer may be chosen")
        },
        ToolGroup.ASK,
        phase = 3,
    )

    val analyzeDeck = ToolSpec(
        "analyze_deck",
        "A deck's shape in numbers: monsters, spells and traps; what its cards do (searchers, hand traps, negates…); " +
            "archetypes; ban issues; and the chance to open each group. Without deck_id, the open deck.",
        schema { string("deck_id", "Omit for the open deck") },
        ToolGroup.LOOK,
        phase = 2,
    )

    val tournamentDecks = ToolSpec(
        "ygopro_tournament_decks",
        "Recent tournament decklists from YGOPRODeck's meta decks: event, placement, player count, format (TCG, OCG, Genesys) " +
            "and date. Tier 1 is locals, 2 regionals and WCQs, 3 national and YCS, 4 Worlds. Filter by archetype, format or " +
            "event words; pages back in time with page.",
        schema {
            integer("tier", "Lowest event tier (default 2)", min = 1, max = 4)
            string("format", "TCG, OCG or Genesys (default: the app's format)")
            string("archetype", "Only decks whose name has this")
            string("event", "Only events whose name has this")
            integer("days", "Only the last this many days (default 60)", min = 1, max = 365)
            integer("page", "Older results, from 0", min = 0, max = 20)
        },
        ToolGroup.META,
        phase = 2,
    )

    val tournamentDeck = ToolSpec(
        "ygopro_deck",
        "One YGOPRODeck tournament deck in full, with card names, by its deck number from ygopro_tournament_decks.",
        schema { integer("deck_number", "The deck's number", required = true) },
        ToolGroup.META,
        phase = 2,
    )

    val importTournamentDeck = ToolSpec(
        "import_ygopro_deck",
        "Copies a YGOPRODeck tournament deck into the app: into the library, or into a web with a share.",
        schema {
            integer("deck_number", "The deck's number", required = true)
            string("web_id", "Put it in this web instead of the library")
            integer("share", "Its share of the web's field, in percent", min = 0, max = 100)
            string("name", "A name for it (default: the deck's own)")
        },
        ToolGroup.META,
        phase = 2,
    )

    val fieldSnapshot = ToolSpec(
        "ygopro_field_snapshot",
        "What the field looks like: recent tournament decks grouped into strategies, each with its share of results " +
            "(weighted by placement and event size), its best finishes and one representative list's deck number. " +
            "The first step in building a web of decks for an event.",
        schema {
            integer("tier", "Lowest event tier (default 2)", min = 1, max = 4)
            string("format", "TCG, OCG or Genesys (default: the app's format)")
            integer("days", "How many days back (default 45)", min = 7, max = 365)
            integer("top", "How many strategies to return (default 12)", min = 3, max = 30)
        },
        ToolGroup.META,
        phase = 2,
    )

    /** Every tool, in the order they are offered. */
    val all: List<ToolSpec> = listOf(
        appState, listDecks, getDeck, validateDeck, analyzeDeck, getSettings, listWebs, getWeb, getSiding,
        searchCards, cardInfo, showInPool,
        openDeck, newDeck, editDeck, setGroups, renameDeck, saveDeck, undo, importDeck, exportDeck, deleteDeck,
        createWeb, addDeckToWeb, setWebEntry, setWebNotes, removeFromWeb, deleteWeb, setSidingPlan,
        navigate, runAction, setSetting,
        memory, memoryRead, skillView, skillManage, sessionSearch,
        askUser,
        tournamentDecks, tournamentDeck, importTournamentDeck, fieldSnapshot,
    )

    /** The tools a build that has shipped up to [phase] offers. */
    fun offered(phase: Int): List<ToolSpec> = all.filter { it.phase <= phase }

    private val byName = all.associateBy { it.name }

    fun named(name: String): ToolSpec? = byName[name.removePrefix("mcp__neue__")]
}
