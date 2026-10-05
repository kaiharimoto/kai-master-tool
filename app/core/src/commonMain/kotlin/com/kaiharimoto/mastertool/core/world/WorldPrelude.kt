package com.kaiharimoto.mastertool.core.world

/**
 * The `ygo` a world's scripts see: in JavaScript built over the one door the engine binds ([WorldApi.call], as
 * `__ygo(name, json)`), in Python a module of its own reading the data the world wrote beside it. Both name things
 * the same way, so a skill teaches one API. Randomness is the script's own and seeded — mulberry32 in JavaScript,
 * `random.Random` in Python — so a run is the same run every time and a number Ai shows can be shown again.
 */
object WorldPrelude {
    /** A Python run's line that pins a board: the marker, then the board as JSON. */
    const val SHOW_MARK = "@@ygo-show "

    /** The data a Python run reads, written beside `ygo.py` before it runs. */
    const val PY_DATA = "ygo_data.json"
    const val PY_MODULE = "ygo.py"

    val JS: String = """
(function (g) {
  var door = g.__ygo, say = g.__print;
  delete g.__ygo; delete g.__print;
  function call(name, args) {
    var r = door(name, JSON.stringify(args === undefined ? {} : args));
    return r === undefined || r === null ? null : JSON.parse(r);
  }
  function text(args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) {
      var a = args[i];
      parts.push(typeof a === 'string' ? a : (a !== null && typeof a === 'object' ? JSON.stringify(a) : String(a)));
    }
    return parts.join(' ');
  }
  g.print = function () { say(text(arguments)); };
  g.console = { log: g.print, info: g.print, warn: g.print, error: g.print,
    table: function (rows) { say(JSON.stringify(rows, null, 1)); } };

  // Guards on what the instruction count cannot see: one call that builds a huge string or array.
  var BIG = 5000000;
  function guard(proto, name, size) {
    var f = proto[name];
    if (!f) return;
    proto[name] = function () {
      if (size(this, arguments) > BIG) throw new RangeError(name + ' would make more than 5,000,000 items');
      return f.apply(this, arguments);
    };
  }
  guard(String.prototype, 'repeat', function (s, a) { return String(s).length * Number(a[0]); });
  guard(String.prototype, 'padStart', function (s, a) { return Number(a[0]); });
  guard(String.prototype, 'padEnd', function (s, a) { return Number(a[0]); });
  guard(Array.prototype, 'fill', function (s) { return s.length; });
  guard(Array.prototype, 'join', function (s) { return s.length; });

  function rng(seed) {
    var s = (Number(seed) >>> 0) || 1;
    var r = {
      next: function () {
        s = (s + 0x6D2B79F5) | 0;
        var t = Math.imul(s ^ (s >>> 15), 1 | s);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
      }
    };
    r.int = function (n) { return Math.floor(r.next() * n); };
    r.pick = function (a) { return a[r.int(a.length)]; };
    r.shuffle = function (a) {
      a = a.slice();
      for (var i = a.length - 1; i > 0; i--) { var j = r.int(i + 1), t = a[i]; a[i] = a[j]; a[j] = t; }
      return a;
    };
    // The first k of a fair shuffle, without shuffling the rest: what an opening hand needs, a tenth of the work.
    r.draw = function (a, k) {
      a = a.slice();
      var n = Math.min(k, a.length);
      for (var i = 0; i < n; i++) { var j = i + r.int(a.length - i), t = a[i]; a[i] = a[j]; a[j] = t; }
      return a.slice(0, n);
    };
    r.sample = function (a, k) { return r.shuffle(a).slice(0, k); };
    return r;
  }

  function show(kind) {
    return function (body, o) {
      o = o || {};
      return call('show', { kind: kind, body: body, title: o.title, id: o.id, note: o.note });
    };
  }
  function stat(f) { return function (xs, o) { o = o || {}; o.f = f; o.xs = xs; return call('stats', o); }; }

  var ygo = {
    card: function (q) { return call('card', { q: String(q) }); },
    search: function (q, limit) { return call('search', { q: String(q), limit: limit || 20 }); },
    deck: function (id) { return call('deck', id === undefined || id === null ? {} : { id: String(id) }); },
    decks: function () { return call('decks'); },
    // The Forbidden & Limited list in force on a day (yyyy-MM-dd; default today), 'tcg' or 'ocg' (default the app's format).
    banlist: function (date, region) {
      var l = call('banlist', { date: date === undefined || date === null ? null : String(date), region: region || null });
      if (l) l.status = function (name) { return call('banStatus', { title: l.title, region: l.region, name: String(name) }); };
      return l;
    },
    // A deck (ygo.deck()'s, or an id; null the open one) checked against that day's list and the cards released by then.
    legal: function (deck, date, region) {
      var id = deck !== null && typeof deck === 'object' ? deck.id : deck;
      return call('legal', { id: id === undefined || id === null ? null : String(id), date: date === undefined || date === null ? null : String(date), region: region || null });
    },
    comb: function (n, k) { return call('comb', { n: n, k: k }); },
    hypergeo: function (N, K, n, k) { return call('hypergeo', { N: N, K: K, n: n, k: k }); },
    atLeast: function (N, K, n, k) { return call('atLeast', { N: N, K: K, n: n, k: k }); },
    atMost: function (N, K, n, k) { return call('atMost', { N: N, K: K, n: n, k: k }); },
    handOdds: function (o) { return call('handOdds', o); },
    rng: rng,
    deal: function (cards, seed, hand) {
      var lib = rng(seed).shuffle(cards), h = hand === undefined ? 5 : hand;
      return { hand: lib.slice(0, h), library: lib.slice(h) };
    },
    hand: function (cards, r, n) { return (typeof r === 'object' ? r : rng(r)).draw(cards, n === undefined ? 5 : n); },
    simulate: function (n, seed, fn) {
      if (n > 1000000) throw new RangeError('simulate: at most 1,000,000 trials');
      var r = rng(seed), out = new Array(n);
      for (var i = 0; i < n; i++) out[i] = fn(r, i);
      return out;
    },
    rate: function (xs) {
      var k = 0;
      for (var i = 0; i < xs.length; i++) if (xs[i]) k++;
      var ci = call('stats', { f: 'wilson', k: k, n: xs.length });
      return { k: k, n: xs.length, p: xs.length ? k / xs.length : 0, low: ci[0], high: ci[1] };
    },
    stats: {
      mean: stat('mean'), sd: stat('sd'), variance: stat('variance'), median: stat('median'),
      quantile: function (xs, q) { return call('stats', { f: 'quantile', xs: xs, q: q }); },
      histogram: function (xs, bins) { return call('stats', { f: 'histogram', xs: xs, bins: bins || 10 }); },
      correlation: function (xs, ys) { return call('stats', { f: 'correlation', xs: xs, ys: ys }); },
      wilson: function (k, n, z) { return call('stats', { f: 'wilson', k: k, n: n, z: z }); },
      binomPmf: function (n, k, p) { return call('stats', { f: 'binomPmf', n: n, k: k, p: p }); },
      binomCdf: function (n, k, p) { return call('stats', { f: 'binomCdf', n: n, k: k, p: p }); },
      normalCdf: function (z) { return call('stats', { f: 'normalCdf', z: z }); },
      chiSquare: function (observed, expected) { return call('stats', { f: 'chiSquare', xs: observed, ys: expected }); }
    },
    show: {
      chart: show('chart'), graph: show('graph'), flow: show('flow'), table: show('table'), stat: show('stat'),
      markdown: show('markdown'), cards: show('cards'), board: show('board'), line: show('line'), image: show('image')
    },
    tools: (function () {
      // The app's instruments (Instruments.kt): engineered, tested, run at the app's speed. ygo.tools.list() names them.
      var t = { list: function () { return call('tools'); }, guide: function () { return call('guide'); } };
      [${Instruments.ALL.joinToString(", ") { "'" + it.name + "'" }}].forEach(function (n) {
        t[n] = function (args) { return call('tool', { name: n, args: args || {} }); };
      });
      return t;
    })(),
    // One of your own instruments (a file under lib/, say): run in the global scope, its functions yours to call; its last value returned.
    use: function (path) { return (0, eval)(call('file', { path: String(path) })); },
    // A file of this world read a page at a time: {text, from, next, total}; next is null at the end.
    read: function (path, from) { return call('read', { path: String(path), from: from || 0 }); },
    // What Ai knows (guides, books, notes, reports, evidence, rubrics), read-only: list(scope), read(path, from), search(q, scope).
    knowledge: {
      list: function (scope) { return call('knowledgeList', { scope: scope === undefined || scope === null ? null : String(scope) }); },
      read: function (path, from) { return call('knowledgeRead', { path: String(path), from: from || 0 }); },
      search: function (q, scope) { return call('knowledgeSearch', { q: String(q), scope: scope === undefined || scope === null ? null : String(scope) }); }
    },
    duel: (function () {
      // A table of the script's own, a sandbox for testing lines (Phase C): a seed (a fresh one when none is given; t.seed
      // says which), first (the seat that has turn 1), or fork: true for the duel in play as Ai's seat sees it. The script
      // moves both seats; how a table ended is the script's to read (kind 'scripted'), never a duel record — Ai's games
      // are Ai vs Ai on the Duel page, two sessions, one a seat.
      function table(t) {
        var h = t.h;
        return {
          h: h, seed: t.seed, first: t.first, forkOf: t.forkOf,
          do: function (line, seat) { return call('duelDo', { h: h, line: String(line), seat: seat || 0 }); },
          brief: function (seat) { return call('duelBrief', { h: h, seat: seat || 0 }); },
          state: function () { return call('duelState', { h: h }); },
          moves: function (seat) { return call('duelMoves', { h: h, seat: seat || 0 }); },
          result: function () { return call('duelResult', { h: h }); }
        };
      }
      return {
        start: function (o) { return table(call('duelNew', o || {})); },
        fork: function (o) { var x = o || {}; x.fork = true; return table(call('duelNew', x)); }
      };
    })()
  };
  g.ygo = ygo;
})(this);
"""

    val PYTHON: String = """
""${'"'}Ai World's helper (written by the app; edits are overwritten). The same names as JavaScript's ygo.*.""${'"'}
import json, math, os, random, sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_DATA = None
SHOW_MARK = "$SHOW_MARK"


def _data():
    global _DATA
    if _DATA is None:
        with open(os.path.join(_HERE, "$PY_DATA"), encoding="utf-8") as f:
            _DATA = json.load(f)
    return _DATA


def decks():
    return _data().get("decks_list", [])


def deck(id=None):
    d = _data()
    return d.get("decks", {}).get(id or d.get("open") or "", None)


def card(name):
    for dk in _data().get("decks", {}).values():
        c = dk.get("cards", {}).get(name)
        if c:
            return c
    low = str(name).lower()
    for dk in _data().get("decks", {}).values():
        for n, c in dk.get("cards", {}).items():
            if n.lower() == low or str(c.get("id")) == low:
                return c
    return None


def comb(n, k):
    return math.comb(int(n), int(k)) if 0 <= k <= n else 0


def hypergeo(N, K, n, k):
    if k < 0 or k > n or k > K or n - k > N - K:
        return 0.0
    return comb(K, k) * comb(N - K, n - k) / comb(N, n)


def at_least(N, K, n, k):
    return min(1.0, sum(hypergeo(N, K, n, i) for i in range(max(k, 0), min(n, K) + 1)))


def at_most(N, K, n, k):
    return min(1.0, sum(hypergeo(N, K, n, i) for i in range(0, min(k, n) + 1)))


def hand_odds(groups, deck, hand=5, need=()):
    ""${'"'}Exact: the chance a hand meets every {group, min, max} at once (multivariate hypergeometric).""${'"'}
    names = list(groups)
    sizes = [int(groups[g]) for g in names]
    rest = deck - sum(sizes)
    lim = {g: (0, hand) for g in names}
    for r in need:
        lo, hi = lim.get(r["group"], (0, hand))
        lim[r["group"]] = (max(lo, r.get("min", 0)), min(hi, r.get("max", hand)))
    total = 0.0

    def walk(i, left, ways):
        nonlocal total
        if i == len(names):
            if 0 <= left <= rest:
                total += ways * comb(rest, left)
            return
        lo, hi = lim[names[i]]
        for k in range(lo, min(hi, sizes[i], left) + 1):
            walk(i + 1, left - k, ways * comb(sizes[i], k))

    walk(0, hand, 1)
    return total / comb(deck, hand)


def rng(seed):
    return random.Random(seed)


def deal(cards, seed, hand=5):
    lib = list(cards)
    random.Random(seed).shuffle(lib)
    return {"hand": lib[:hand], "library": lib[hand:]}


def wilson(k, n, z=1.96):
    if n <= 0:
        return (0.0, 1.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, c - h), min(1.0, c + h))


def rate(xs):
    xs = list(xs)
    k = sum(1 for x in xs if x)
    lo, hi = wilson(k, len(xs))
    return {"k": k, "n": len(xs), "p": k / len(xs) if xs else 0, "low": lo, "high": hi}


def show(kind, body, title=None, id=None, note=None):
    ""${'"'}Pins a board: chart, graph, flow, table, stat, markdown, cards, board, line, image.""${'"'}
    print(SHOW_MARK + json.dumps({"kind": kind, "body": body, "title": title, "id": id, "note": note}), flush=True)
"""
}
