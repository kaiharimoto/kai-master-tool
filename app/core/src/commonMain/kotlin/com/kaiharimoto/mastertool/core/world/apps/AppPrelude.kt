package com.kaiharimoto.mastertool.core.world.apps

/**
 * What an app's JavaScript sees beside `ygo.*` ([com.kaiharimoto.mastertool.core.world.WorldPrelude]): `ui.*`, which
 * builds the widgets of §8.4 as plain data — `{ui: 'stepper', id: 'hand', …}` — for [UiTree.parse] to read, and the
 * seeded `Math` each call is given (§8.3: `Math.random` from the app's version and the event's `seq`, so a run of events
 * replays exactly). Evaluated once into the sealed scope every call starts from.
 *
 * Every builder takes its props first; a container takes its children second (or as its only argument); a words widget
 * takes its text first. `ui.board(kind, body, props)` is a board's payload, as `ygo.show` takes it. A card is drawn as its
 * art: `ui.card(nameOrPasscode, {label, size: 'large'})`, `ui.cards({cards})`, a table's `cards: ['Card']` columns.
 */
object AppPrelude {
    /** The global a call's seeded `Math` is made by: `__mathFor(seed)`. */
    const val MATH_FOR = "__mathFor"

    /** The seed for a call: the app's [version] and the event's [seq] (0 for `init` and `view`). */
    fun seed(version: Int, seq: Long): Int = (version * 1_000_003 + seq * 7_919 + 1).toInt()

    val JS: String = """
(function (g) {
  function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
  function node(kind, props, children) {
    var o = {};
    if (props !== null && typeof props === 'object') for (var k in props) if (own(props, k)) o[k] = props[k];
    o.ui = kind;
    if (children !== undefined) o.children = children;
    return o;
  }
  function box(kind) {
    return function (props, children) {
      if (Array.isArray(props)) { children = props; props = {}; }
      return node(kind, props || {}, children || []);
    };
  }
  function leaf(kind) { return function (props) { return node(kind, props || {}); }; }
  function words(kind, key) {
    return function (text, props) {
      if (text !== null && typeof text === 'object' && !Array.isArray(text)) return node(kind, text);
      var o = node(kind, props || {}); o[key] = text === undefined || text === null ? '' : String(text); return o;
    };
  }
  var ui = {
    col: box('col'), row: box('row'), grid: box('grid'),
    section: function (title, children) {
      if (title !== null && typeof title === 'object' && !Array.isArray(title)) return node('section', title, children || []);
      return node('section', { title: String(title) }, children || []);
    },
    divider: leaf('divider'), space: leaf('space'),
    text: words('text', 'text'), note: words('note', 'text'), markdown: words('markdown', 'text'), empty: words('empty', 'text'),
    kv: function (rows, props) { var o = node('kv', props || {}); o.rows = rows; return o; },
    stat: leaf('stat'), button: leaf('button'), input: leaf('input'), stepper: leaf('stepper'), slider: leaf('slider'),
    select: leaf('select'), segmented: leaf('segmented'), toggle: leaf('toggle'), checks: leaf('checks'),
    cardPicker: leaf('cardPicker'), deckPicker: leaf('deckPicker'),
    table: leaf('table'), cards: leaf('cards'), card: words('card', 'card'), progress: leaf('progress'),
    board: function (kind, body, props) { var o = node('board', props || {}); o.kind = String(kind); o.body = body; return o; }
  };
  g.ui = ui;
  g.$MATH_FOR = function (seed) {
    var m = Object.create(Math), r = g.ygo.rng(seed);
    m.random = function () { return r.next(); };
    return m;
  };
})(this);
"""
}
