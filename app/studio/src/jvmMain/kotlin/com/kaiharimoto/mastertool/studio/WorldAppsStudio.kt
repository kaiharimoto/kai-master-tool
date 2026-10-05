package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.mastertool.core.ai.AiSession
import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Part
import com.kaiharimoto.mastertool.core.ai.Role
import com.kaiharimoto.mastertool.core.ai.report.ReportLog
import com.kaiharimoto.mastertool.core.ai.report.SessionReport
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.apps.AppStore
import com.kaiharimoto.mastertool.core.world.apps.UiEvent
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.mastertool.core.world.desk.DeskSize
import com.kaiharimoto.mastertool.core.world.desk.WorldAddress
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * What runs inside the World's windows, photographed one app at a time (agent C, `docs/world/DESKTOP.md` §12.4), on the
 * `--world=demo` world: `--world-app=browser|thoughts|instruments|library|hand-odds|combo-lines|matchups` draws that app
 * full-size alone (`world.apps.solo`) until the desktop's page frames it; `--world-page=b5` is the Browser's selected page
 * (`home` for the new-tab page); `--world-library=search:<words>` searches the Library; `--world-app-fail=true` makes the
 * app's next press throw, to show the error line.
 */
internal suspend fun studioWorldApps(h: NeueHolders, map: Map<String, String>, clock: FrameClock) {
    val which = map["world-app"] ?: return
    val world = h.world
    val w = world.open ?: return
    val now = System.currentTimeMillis()
    // Thoughts: the demo's conversation as a World conversation, its actions linked to what they made.
    h.ai.preview(
        AiSession(
            id = "studio-world-thoughts",
            title = "Openings",
            connection = "anthropic-demo",
            mode = AiSession.MODE_WORLD,
            turns = listOf(
                ChatTurn.user("In Ai World, in the world “${w.title}”: study my deck's openings."),
                ChatTurn(Role.ASSISTANT, listOf(
                    Part.Reasoning("The question is how often a hand has a starter, and how often that starter survives a hand trap. A seeded simulation answers the first; the engine map answers which cards the second depends on."),
                    Part.Text("I'll count first, then map who finds whom."),
                    Part.ToolUse("s1", "world_write", JsonObject(mapOf("path" to JsonPrimitive("openings.js")))),
                    Part.ToolUse("s2", "world_run", JsonObject(mapOf("path" to JsonPrimitive("openings.js")))),
                )),
                ChatTurn(Role.USER, listOf(
                    Part.ToolResult("s1", "world_write", "{}", summary = "Wrote openings.js (19 lines)"),
                    Part.ToolResult("s2", "world_run", "{}", summary = "Ran openings.js · 412 ms · opens 63.4%"),
                )),
                ChatTurn(Role.ASSISTANT, listOf(
                    Part.Reasoning("63% is fine but the two-starter rate is low. The engine map should show whether the hub carries every line."),
                    Part.ToolUse("s3", "world_show", JsonObject(mapOf("id" to JsonPrimitive("b5")))),
                    Part.ToolUse("s4", "world_app", JsonObject(mapOf("slug" to JsonPrimitive("hand-odds")))),
                )),
                ChatTurn(Role.USER, listOf(
                    Part.ToolResult("s3", "world_show", "{}", summary = "Pinned “Who finds whom”"),
                    Part.ToolResult("s4", "world_app", "{}", summary = "Made Hand odds"),
                )),
                ChatTurn(Role.ASSISTANT, listOf(Part.Text("**63 %** of hands open a starter. The web shows one card every line passes through: see *Who finds whom*. I made **Hand odds** for you to try other cards."))),
            ),
            createdAt = now,
            updatedAt = now,
        ),
    )
    // Ai's apps, made as Ai makes them: through the store, logged.
    val store = AppStore(File(world.dir, w.id))
    listOf(
        AppManifest("hand-odds", "Hand odds", "calculator", "odds", "HO", "The odds of seeing a card in the opening hand", DeskSize(520.0, 620.0)) to HAND_ODDS,
        AppManifest("combo-lines", "Combo lines", "explorer", "line", "CL", "The deck's saved combos, a step at a time", DeskSize(640.0, 640.0)) to COMBO_LINES,
        AppManifest("matchups", "Matchup tracker", "tracker", "versus", "MT", "Games against the field, with how sure each rate is", DeskSize(720.0, 640.0)) to MATCHUP_TRACKER,
    ).forEach { (m, code) -> if (store.manifest(m.slug) == null) store.make(m, code, now) }
    world.reload()
    // An instrument's run under its form (the person's, so its pages open), then a few Browser tabs, one per kind of page.
    world.tool("openings", JsonObject(mapOf("trials" to JsonPrimitive(5_000))), WorldEvent.YOU)
    listOf("b1", "b2", "b5", "b6", "b7", "b14").forEach { world.browser.open(WorldAddress.Board(it).format(), select = false) }
    val page = map["world-page"] ?: "b2"
    world.browser.open(if (page == "home") WorldAddress.HOME else WorldAddress.Board(page).format())
    // The Library: what Ai knows about the builder's deck and itself.
    seedLibrary(h, now)
    val app = BuiltInApp.of(which)?.ref ?: AppRef.Made(which)
    world.apps.solo = app
    clock.run(30)
    if (app is AppRef.Made) {
        // Wait for its first screen, then play a few events as the person would.
        repeat(200) { if (world.apps.isLive(app.slug) && world.apps.host(app.slug).tree != null) return@repeat; delay(20); clock.run(1) }
        val host = world.apps.host(app.slug)
        fun send(id: String, type: String, value: Any) {
            val v = when (value) {
                is Int -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
            world.apps.send(host, id, type, v)
        }
        val deck = h.builder.deckId
        val card = h.builder.deck.main.firstOrNull()?.value
        when (app.slug) {
            "hand-odds" -> {
                if (deck != null) send("deck", UiEvent.CHANGE, deck)
                if (card != null) send("card", UiEvent.CHANGE, card)
                send("hand", UiEvent.CHANGE, 6)
            }
            "combo-lines" -> if (deck != null) send("deck", UiEvent.CHANGE, deck)
            "matchups" -> listOf("won", "won", "lost", "won", "lost", "won", "lost", "won").forEachIndexed { i, r ->
                send("result", UiEvent.CHANGE, r)
                send("opp", UiEvent.CHANGE, listOf("Snake-Eye", "Tenpai", "Yubel")[i % 3])
                send("log", UiEvent.PRESS, true)
                // A person logs a game at a time: the queue holds eight.
                repeat(100) { if (!host.idle) { delay(20); clock.run(1) } }
            }
        }
        if (map["world-app-fail"] == "true") send("nope-not-a-widget", UiEvent.PRESS, true)
        repeat(300) { if (host.idle && host.answered > 0) return@repeat; delay(20); clock.run(1) }
        if (map["world-app-fail"] == "true") host.note = null
    }
    map["world-library"]?.let { spec ->
        val lib = world.library
        if (spec.startsWith("search:")) lib.search(spec.removePrefix("search:")) else lib.catalog.shelf(lib.shelf, lib.deck).firstOrNull()?.let { lib.open(it) }
    }
    clock.run(90)
    if (which == "library" && map["world-library"] == null) {
        world.library.catalog.shelf(world.library.shelf, world.library.deck).firstOrNull()?.let { world.library.open(it) }
        clock.run(60)
    }
    println("[neue-studio] world app: $which, ${world.apps.list.size} apps, ${world.browser.tabs.tabs.size} tabs, ${world.library.catalog.docs.size} documents")
}

/** Ai's memory as the Library reads it: a guide for the builder's deck, its notes, a report, evidence, and Ai's own notes. */
private fun seedLibrary(h: NeueHolders, now: Long) {
    val ai = File(Platform.dataDir, "ai")
    val deck = h.builder.deckId ?: "studio"
    val safe = com.kaiharimoto.mastertool.core.ai.memory.AiMemory.safeId(deck)
    fun put(path: String, text: String) = File(ai, path).also { it.parentFile.mkdirs() }.writeText(text)
    val name = h.builder.deckName
    put("guides/$safe.md", buildString {
        append("# How ${name} plays\n\nThe deck wins by ending turn one on two interruptions, and by keeping one card that starts the engine through a hand trap.\n\n")
        append("## Openings\n\nA hand with one starter goes first comfortably. The deck opens a starter in **63 %** of hands (100,000 seeded hands, openings instrument).\n\n")
        append("- Lead with the starter that searches; hold the one that summons for after Ash Blossom.\n- Two starters is a hand to extend, not to over-commit with.\n\n")
        append("## Going second\n\nBreak the board before you build one. Count their interruptions before the first Normal Summon.\n\n")
        repeat(40) { i -> append("### Note ${i + 1}\n\nA line from Fine Tuning: when the opponent holds Nibiru, stop at four summons and pass with the backup set. Seen in practice game ${i + 3}.\n\n") }
    })
    put("decks/$safe.md", "# Notes\n\n- Side out the second copy of the slow starter against Maliss.\n- kai prefers going first in the mirror.\n")
    put("MEMORY.md", "# Lessons\n\n- Seed every simulation and print the seed.\n- Check a simulation against the exact odds before believing it.\n")
    put("USER.md", "# About kai\n\nPlays at locals weekly; likes to see the numbers behind a choice.\n")
    // Two saved combos, for Combo lines.
    val names = h.builder.deck.main.mapNotNull { h.builder.index.byId(it)?.name }.distinct()
    if (names.size >= 3) {
        val book = com.kaiharimoto.mastertool.core.duel.ai.ComboBook(listOf(
            com.kaiharimoto.mastertool.core.duel.ai.Combo("c1", "${names[0]} into the end board", deck, listOf(names[0]), listOf("Normal Summon ${names[0]}", "${names[0]} searches ${names[1]}", "Special Summon ${names[1]}", "Link into the boss", "Set ${names[2]}")),
            com.kaiharimoto.mastertool.core.duel.ai.Combo("c2", "${names[1]} alone", deck, listOf(names[1]), listOf("Activate ${names[1]}", "Add ${names[2]}", "Set two")),
        ))
        File(File(Platform.dataDir, "duel"), com.kaiharimoto.mastertool.core.duel.ai.ComboCodec.path(deck)).also { it.parentFile.mkdirs() }.writeText(com.kaiharimoto.mastertool.core.duel.ai.ComboCodec.encode(book))
    }
    put("reports/$safe.json", ReportLog.write(listOf(SessionReport(deck, name, now - 86_400_000, "fine-tuning", "deep", "Taught the main line and its backups.", listOf("The hub card carries every line"), listOf("Going second wants a board breaker"), emptyList(), 72, 58, 40, "Knows the lines; has not played the mirror yet.", startedAt = now - 86_400_000 - 1_800_000))))
}

private val HAND_ODDS = """
// Hand odds — v1. Rhino 1.7.15: ES5 and the prelude; ui.* builds the widgets.
function init() {
  return { deck: null, card: null, copies: 3, size: 40, hand: 5, atLeast: 1 };
}

function view(s) {
  var odds = s.card ? ygo.atLeast(s.size, s.copies, s.hand, s.atLeast) : null;
  return ui.col({ gap: 4 }, [
    ui.deckPicker({ id: 'deck', label: 'Deck', value: s.deck }),
    ui.row({ gap: 4 }, [
      ui.cardPicker({ id: 'card', label: 'Card', value: s.card, weight: 2 }),
      ui.stepper({ id: 'copies', label: 'Copies', value: s.copies, min: 1, max: 3 })
    ]),
    ui.row({ gap: 4 }, [
      ui.stepper({ id: 'hand', label: 'Hand', value: s.hand, min: 5, max: 6, hint: '5 going first, 6 second' }),
      ui.stepper({ id: 'atLeast', label: 'At least', value: s.atLeast, min: 1, max: s.copies })
    ]),
    odds === null ? ui.empty({ text: 'Pick a card to see its odds.' })
                  : ui.stat({ value: (odds * 100).toFixed(1) + '%', label: 'to see ' + s.atLeast + '+ in ' + s.hand }),
    ui.board('chart', { type: 'bar', title: 'By hand size', labels: ['5', '6', '7'],
      series: [{ name: 'odds', values: [5, 6, 7].map(function (n) { return s.card ? 100 * ygo.atLeast(s.size, s.copies, n, s.atLeast) : 0; }) }] })
  ]);
}

function on(s, e) {
  if (e.id === 'deck') {
    var d = ygo.deck(e.value);
    s.deck = e.value;
    s.size = d ? d.main.length : 40;
  } else if (e.id === 'card') {
    s.card = e.value;
    var copies = s.deck ? ygo.deck(s.deck).ids.main.filter(function (c) { return c === e.value; }).length : 0;
    s.copies = Math.min(3, copies || s.copies);
  } else {
    s[e.id] = e.value;
  }
  if (s.atLeast > s.copies) s.atLeast = s.copies;
  return s;
}
""".trimStart()

private val COMBO_LINES = """
// Combo lines — v1: the deck's saved combos, their odds of opening, and each line a step at a time.
function init() {
  return { deck: null, combo: 0, step: 0, combos: [] };
}

function load(s, deck) {
  var saved = ygo.combos(deck);
  var odds = saved.length ? ygo.tools.combos({ deck: deck }).combos : [];
  s.combos = saved.map(function (c, i) {
    return { name: c.name, steps: c.steps, first: odds[i] ? odds[i].first : null };
  });
  s.combo = 0;
  s.step = 0;
}

function view(s) {
  if (!s.deck) return ui.col([ui.deckPicker({ id: 'deck', label: 'Deck', value: s.deck }), ui.empty('Pick a deck to see its combos.')]);
  if (!s.combos.length) return ui.col([ui.deckPicker({ id: 'deck', label: 'Deck', value: s.deck }), ui.empty('This deck has no saved combos yet.')]);
  var c = s.combos[s.combo];
  var shown = c.steps.slice(0, s.step + 1);
  return ui.col({ gap: 3 }, [
    ui.deckPicker({ id: 'deck', label: 'Deck', value: s.deck }),
    ui.table({ id: 'pick', columns: ['Combo', 'Opens, first'], pickable: true,
      rows: s.combos.map(function (x) { return [x.name, x.first === null ? '—' : (x.first * 100).toFixed(1) + '%']; }) }),
    ui.section(c.name, [
      ui.row([
        ui.button({ id: 'back', label: '‹', disabled: s.step === 0 }),
        ui.text('Step ' + (s.step + 1) + ' of ' + c.steps.length, { mono: true }),
        ui.button({ id: 'next', label: '›', kind: 'primary', disabled: s.step >= c.steps.length - 1 })
      ]),
      ui.board('line', shown.join('\n'))
    ])
  ]);
}

function on(s, e) {
  if (e.id === 'deck') { s.deck = e.value; load(s, e.value); }
  else if (e.id === 'pick') { s.combo = e.value; s.step = 0; }
  else if (e.id === 'next') { s.step = Math.min(s.step + 1, s.combos[s.combo].steps.length - 1); }
  else if (e.id === 'back') { s.step = Math.max(0, s.step - 1); }
  return s;
}
""".trimStart()

private val MATCHUP_TRACKER = """
// Matchup tracker — v1: a game is three presses; every rate says how sure it is.
function init() {
  return { result: 'won', order: 'first', opp: 'Snake-Eye', opponents: ['Snake-Eye', 'Tenpai', 'Yubel'], games: [] };
}

function rows(s) {
  return s.opponents.map(function (o) {
    var gs = s.games.filter(function (g) { return g.opp === o; });
    var won = gs.filter(function (g) { return g.won; }).length;
    var ci = gs.length ? ygo.stats.wilson(won, gs.length) : [0, 1];
    return { opp: o, n: gs.length, won: won, rate: gs.length ? won / gs.length : 0, low: ci[0], high: ci[1] };
  });
}

function view(s) {
  var r = rows(s);
  return ui.col({ gap: 3 }, [
    ui.row([
      ui.segmented({ id: 'result', label: 'Result', value: s.result, options: ['won', 'lost'] }),
      ui.segmented({ id: 'order', label: 'Went', value: s.order, options: ['first', 'second'] }),
      ui.select({ id: 'opp', label: 'Against', value: s.opp, options: s.opponents })
    ]),
    ui.button({ id: 'log', label: 'Log the game', kind: 'primary' }),
    s.games.length === 0 ? ui.empty('No games yet: log one above.') : ui.table({ columns: ['Against', 'Games', 'Won', 'Rate', '95 % range'],
      rows: r.map(function (x) { return [x.opp, x.n, x.won, (x.rate * 100).toFixed(0) + '%', (x.low * 100).toFixed(0) + '–' + (x.high * 100).toFixed(0) + '%']; }) }),
    ui.board('chart', { type: 'bar', title: 'Game win by opponent', labels: s.opponents,
      series: [{ name: 'win %', values: r.map(function (x) { return Math.round(x.rate * 100); }) }] })
  ]);
}

function on(s, e) {
  if (e.id === 'log') s.games.push({ opp: s.opp, won: s.result === 'won', first: s.order === 'first' });
  else s[e.id] = e.value;
  return s;
}
""".trimStart()
