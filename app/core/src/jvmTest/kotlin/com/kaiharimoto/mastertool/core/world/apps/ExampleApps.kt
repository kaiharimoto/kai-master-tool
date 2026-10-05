package com.kaiharimoto.mastertool.core.world.apps

import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.world.WorldHost

/**
 * The three apps of `docs/world/DESKTOP.md` §8.8, written to the contract of §8.3, as fixtures: `ExampleAppsTest` runs
 * them through scripted events, and they are what the `world-app` skill shows Ai as a good app looks.
 */
object ExampleApps {
    /** Hand odds (`calculator`, glyph `odds`, `HO`): a deck, a card and its copies, the hand, at least; the odds by hand size. */
    val HAND_ODDS = """
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
                  : ui.row({ gap: 4 }, [
                      ui.stat({ value: (odds * 100).toFixed(1) + '%', label: 'to see ' + s.atLeast + '+ in ' + s.hand, weight: 2 }),
                      ui.card(String(s.card), { label: 'The card counted', weight: 1 })
                    ]),
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

    /** Combo lines (`explorer`, glyph `line`, `CL`): the deck's saved combos with each one's odds; step through one. */
    val COMBO_LINES = """
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

function cardsOf(steps) {
  var seen = [], m, re = /\[\[([^\]]+)\]\]/g;
  steps.forEach(function (step) { while ((m = re.exec(step)) !== null) if (seen.indexOf(m[1]) < 0) seen.push(m[1]); });
  return seen;
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
      ui.cards({ cards: cardsOf(c.steps) }),
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

    /** Matchup tracker (`tracker`, glyph `versus`, `MT`): games logged in three presses; rates with Wilson ranges. */
    val MATCHUP_TRACKER = """
// Matchup tracker — v1: a game is three presses; every rate says how sure it is.
function init() {
  return { result: 'won', order: 'first', opp: 'Snake-Eye', opponents: ['Snake-Eye', 'Tenpai', 'Yubel'], games: [],
           covers: { 'Snake-Eye': 'Snake-Eye Ash', 'Tenpai': 'Tenpai Dragon Chundra', 'Yubel': 'Yubel' } };
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
    s.games.length === 0 ? ui.empty('No games yet: log one above.') : ui.table({ columns: ['Against', 'Games', 'Won', 'Rate', '95 % range', 'Their card'], cards: ['Their card'],
      rows: r.map(function (x) { return [x.opp, x.n, x.won, (x.rate * 100).toFixed(0) + '%', (x.low * 100).toFixed(0) + '–' + (x.high * 100).toFixed(0) + '%', (s.covers || {})[x.opp] || '']; }) }),
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

    val cards = listOf(
        Card(CardId(14558127), "Ash Blossom & Joyous Spring", "Tuner Effect Monster", "effect", "Discard this card…", atk = 0, def = 1800, level = 3),
        Card(CardId(2), "Aluber the Jester of Despia", "Effect Monster", "effect", "Add 1 Branded Spell/Trap."),
        Card(CardId(3), "Branded Fusion", "Spell Card", "spell", "Fusion Summon 1 Fusion Monster."),
        Card(CardId(4), "Brick", "Normal Monster", "normal", ""),
    )

    val deck = DeckEntry(
        "d1", "Branded",
        Deck(main = List(3) { CardId(14558127) } + List(3) { CardId(2) } + List(3) { CardId(3) } + List(31) { CardId(4) }),
        0, 0,
    )

    val combos = listOf(
        Combo("c1", "Aluber into Fusion", "d1", needs = listOf("Aluber the Jester of Despia"), steps = listOf("summon aluber to m3", "aluber search branded fusion", "activate branded fusion")),
        Combo("c2", "Fusion alone", "d1", needs = listOf("Branded Fusion"), steps = listOf("activate branded fusion", "send albaz to gy")),
    )

    /** The app's world as a test reads it: one deck, its combos, and a couple of files. */
    fun host(files: Map<String, String> = mapOf("notes/plan.md" to "# Plan\n\nOpen Aluber.")) = object : WorldHost {
        override fun cardById(id: Int) = cards.firstOrNull { it.id.value == id }
        override fun cardNamed(name: String) = cards.firstOrNull { it.name.equals(name, ignoreCase = true) }
        override fun search(query: String, limit: Int) = cards.filter { query.lowercase() in it.name.lowercase() }.take(limit)
        override fun deck(id: String?) = if (id == null || id == "d1") deck else null
        override fun decks() = listOf(deck)
        override fun combos(deckId: String) = if (deckId == "d1") combos else emptyList()
        override fun file(path: String) = files[path]
    }
}
