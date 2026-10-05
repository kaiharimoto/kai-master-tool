package com.kaiharimoto.mastertool.core.world

/**
 * `ygo.fx` (alias `fx`), the effect builder (Phase D step 2, `docs/phases/D.md` §3.2): the words a script in
 * `lib/effects/<passcode>.js` writes a card's effects in. Every builder returns a **plain object** in the shape the
 * compiled `CardScript` JSON has (`FxCodec`: `t` names a family's word, enums are their names), and checks its own
 * arguments, so a mistake fails at the line that made it — `JsRuntime.describe` names the script's own line, not this
 * file's. Nothing here is ever called during play: `FxCompile` runs the file once, shut in, and keeps the data.
 *
 * **One builder per word.** Each builder carries the word it builds (`.word`, "op:add", "filter:name-has"…) and the enum
 * tables ([E]) are the vocabulary's own enums lowercased; `FxPreludeTest` holds both to `core/duel/effects`, as R17 holds
 * `ygo.tools` to the instruments — a word added to the vocabulary without a builder fails the build.
 *
 * Steps: an op builder returns a step (`{op: …}`); `fx.then(step)`, `fx.andIfYouDo(step)`, `fx.also(step)` ("also,
 * after that") and `fx.alongside(step)` ("also", at the same time) set how it joins the step before; `fx.and` is the
 * default. Picks: an op takes its pick's fields inline (`fx.add({from: 'your deck', where: …})`) or a whole
 * `pick: fx.pick({...})`; the op's own `faceDown` is the op's, the pick's is `pickFaceDown`.
 */
object FxPrelude {
    val JS: String = """
  ygo.fx = (function () {
    function fail(where, msg) { throw new TypeError('fx.' + where + ': ' + msg); }
    function norm(s) { return String(s).trim().toLowerCase().replace(/[\s\-\/&]+/g, '_'); }
    // The vocabulary's enums, lowercased (FxPreludeTest holds them to the Kotlin enums).
    var E = {
      kind: ['ignition', 'trigger', 'quick', 'activation', 'continuous'],
      where: ['hand', 'deck', 'extra', 'monster_zone', 'spell_zone', 'field_zone', 'gy', 'banished'],
      rel: ['you', 'them', 'any'],
      area: ['hand', 'deck', 'extra', 'gy', 'banished', 'monsters', 'spells', 'field', 'materials'],
      timing: ['if', 'when'],
      event: ['summoned', 'normal_summoned', 'special_summoned', 'flipped', 'sent_to_gy', 'destroyed', 'banished',
        'added_to_hand', 'discarded', 'detached', 'material', 'left_field', 'drawn', 'standby', 'end_phase', 'activated'],
      cause: ['cost', 'effect', 'material', 'battle', 'tribute'],
      procKind: ['normal', 'tribute', 'flip', 'special', 'fusion', 'synchro', 'xyz', 'link', 'ritual', 'pendulum', 'inherent'],
      includes: ['search', 'salvage', 'special_summon', 'send_from_deck', 'draw', 'destroy', 'banish', 'return', 'negate', 'discard'],
      cardType: ['monster', 'spell', 'trap'],
      frame: ['normal', 'effect', 'fusion', 'synchro', 'xyz', 'link', 'ritual', 'pendulum', 'tuner', 'token'],
      stat: ['level', 'rank', 'link', 'atk', 'def', 'name', 'attribute', 'race'],
      join: ['and', 'and_if_you_do', 'then', 'also', 'with'],
      dest: ['hand', 'deck_top', 'deck_bottom', 'deck_shuffled', 'extra', 'gy', 'banished', 'monster_zone', 'spell_zone', 'field_zone'],
      pos: ['attack', 'defense', 'set', 'either'],
      levelRule: ['equal', 'at_least'],
      negWhat: ['activation', 'effect'],
      linkRef: ['answered', 'newest'],
      declareKind: ['name', 'type', 'attribute', 'level'],
      ban: ['special_summon', 'special_summon_from_extra', 'normal_summon', 'activate'],
      cmp: ['ge', 'le', 'eq', 'gt', 'lt', 'ne'],
      phase: ['draw', 'standby', 'main1', 'battle', 'main2', 'end'],
      attribute: ['dark', 'light', 'earth', 'water', 'fire', 'wind', 'divine'],
      until: ['turn', 'chain', 'duel']
    };
    // Other ways a person writes the same word.
    var ALIAS = {
      where: { monsters: 'monster_zone', monster: 'monster_zone', field: 'monster_zone', mzone: 'monster_zone', spells: 'spell_zone',
        spell: 'spell_zone', s_t: 'spell_zone', st: 'spell_zone', szone: 'spell_zone', graveyard: 'gy', banishment: 'banished',
        extra_deck: 'extra', field_spell: 'field_zone', fzone: 'field_zone' },
      area: { graveyard: 'gy', banishment: 'banished', extra_deck: 'extra', monster: 'monsters', monster_zone: 'monsters',
        monster_zones: 'monsters', spell: 'spells', spell_zone: 'spells', spell_zones: 'spells', spells_traps: 'spells',
        s_t: 'spells', spell_trap_zone: 'spells', spell_trap_zones: 'spells', xyz_materials: 'materials' },
      rel: { your: 'you', yours: 'you', own: 'you', mine: 'you', my: 'you', their: 'them', theirs: 'them', opponent: 'them',
        opponents: 'them', "opponent's": 'them', either: 'any', both: 'any', each: 'any' },
      dest: { deck: 'deck_shuffled', top: 'deck_top', bottom: 'deck_bottom', graveyard: 'gy', banishment: 'banished', extra_deck: 'extra',
        monsters: 'monster_zone', field: 'monster_zone', spells: 'spell_zone' },
      pos: { atk: 'attack', def: 'defense', face_down: 'set' },
      phase: { main_1: 'main1', main_2: 'main2', main: 'main1', end_phase: 'end' },
      procKind: { tribute_summon: 'tribute', normal_summon: 'normal', special_summon: 'special' },
      event: { sent: 'sent_to_gy', added: 'added_to_hand', summon: 'summoned', normal_summon: 'normal_summoned', special_summon: 'special_summoned' },
      cmp: { '>=': 'ge', '<=': 'le', '==': 'eq', '=': 'eq', '>': 'gt', '<': 'lt', '!=': 'ne' }
    };
    function word(table, v, where) {
      if (typeof v !== 'string') fail(where, 'expected one of ' + E[table].join(', ') + ' (it was ' + JSON.stringify(v) + ')');
      var raw = String(v).trim().toLowerCase();
      var w = ALIAS[table] && ALIAS[table][raw] !== undefined ? ALIAS[table][raw] : norm(raw);
      if (ALIAS[table] && ALIAS[table][w] !== undefined) w = ALIAS[table][w];
      if (E[table].indexOf(w) < 0) fail(where, '“' + v + '” is not one of ' + E[table].join(', '));
      return w.toUpperCase();
    }
    function words(table, vs, where) {
      if (vs === undefined || vs === null) return [];
      if (!(vs instanceof Array)) vs = [vs];
      var out = [];
      for (var i = 0; i < vs.length; i++) out.push(word(table, vs[i], where));
      return out;
    }
    function int(v, where, min, max) {
      if (typeof v !== 'number' || v !== Math.floor(v) || !isFinite(v)) fail(where, 'expected a whole number (it was ' + JSON.stringify(v) + ')');
      if (min !== undefined && v < min) fail(where, v + ' is below ' + min);
      if (max !== undefined && v > max) fail(where, v + ' is above ' + max);
      return v;
    }
    function str(v, where) {
      if (typeof v !== 'string' || v.trim() === '') fail(where, 'expected words (it was ' + JSON.stringify(v) + ')');
      return v;
    }
    function bool(v, where) {
      if (typeof v !== 'boolean') fail(where, 'expected true or false (it was ' + JSON.stringify(v) + ')');
      return v;
    }
    function obj(v, where) {
      if (v === undefined || v === null) return {};
      if (typeof v !== 'object' || v instanceof Array) fail(where, 'expected an object of options');
      return v;
    }
    // Only the keys a builder knows: a misspelt option fails, never quietly does nothing.
    function only(o, keys, where) {
      for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k) && keys.indexOf(k) < 0) fail(where, 'it takes no “' + k + '” (it takes ' + keys.join(', ') + ')');
    }
    function tagged(w, f) { f.word = w; return f; }
    function isFilter(v) { return v !== null && typeof v === 'object' && typeof v.t === 'string' && v.op === undefined; }
    function filter(v, where) {
      if (v === undefined || v === null) return undefined;
      if (!isFilter(v)) fail(where, 'expected a filter, like fx.monster() or fx.nameHas("…")');
      return v;
    }
    function filters(vs, where) {
      var out = [];
      for (var i = 0; i < vs.length; i++) out.push(filter(vs[i], where));
      return out;
    }
    function num(v, where) {
      if (typeof v === 'number') return int(v, where);
      if (v !== null && typeof v === 'object' && typeof v.t === 'string') return v;
      fail(where, 'expected a number, fx.num.count(…) or fx.num.of(…)');
    }
    function cond(v, where) {
      if (v === undefined || v === null) return undefined;
      if (v === null || typeof v !== 'object' || typeof v.t !== 'string') fail(where, 'expected a condition, like fx.cond.theirTurn()');
      return v;
    }
    function step(v, where) {
      if (v === null || typeof v !== 'object' || v.op === undefined) fail(where, 'expected a step, like fx.draw(1)');
      return v;
    }
    function steps(vs, where) {
      if (vs === undefined || vs === null) return [];
      if (!(vs instanceof Array)) vs = [vs];
      var out = [];
      for (var i = 0; i < vs.length; i++) out.push(step(vs[i], where + '[' + i + ']'));
      return out;
    }
    function span(a, b, where) {
      if (a !== null && typeof a === 'object') {
        only(a, ['min', 'max'], where);
        var o = {};
        if (a.min !== undefined && a.min !== null) o.min = int(a.min, where);
        if (a.max !== undefined && a.max !== null) o.max = int(a.max, where);
        return o;
      }
      var s = {};
      if (a !== undefined && a !== null) s.min = int(a, where);
      if (b === undefined) { if (s.min !== undefined) s.max = s.min; }
      else if (b !== null) s.max = int(b, where);
      if (s.min === undefined && s.max === undefined) fail(where, 'give a number, a range (1, 4), or {min, max}');
      return s;
    }
    // "your deck", "their monsters", "any gy", "deck" (yours), or {rel, area}.
    function spot(v, where) {
      if (v !== null && typeof v === 'object' && v.area !== undefined) {
        return { rel: word('rel', v.rel === undefined ? 'you' : v.rel, where), area: word('area', v.area, where) };
      }
      if (typeof v !== 'string') fail(where, 'expected a place like "your deck" or "their monsters"');
      var parts = String(v).trim().split(/\s+/);
      var first = parts[0].toLowerCase();
      var rel = 'you', rest = parts;
      if (E.rel.indexOf(first) >= 0 || (ALIAS.rel[first] !== undefined)) { rel = first; rest = parts.slice(1); }
      if (rest.length === 0) fail(where, '“' + v + '” names a seat but no place');
      return { rel: word('rel', rel, where), area: word('area', rest.join(' '), where) };
    }
    function spots(v, where) {
      if (v === undefined || v === null) return [];
      if (!(v instanceof Array)) v = [v];
      var out = [];
      for (var i = 0; i < v.length; i++) out.push(spot(v[i], where));
      return out;
    }
    var PICK = ['n', 'upTo', 'from', 'where', 'who', 'ref', 'bind', 'top', 'all', 'pickFaceDown'];
    function pickOf(o, where) {
      o = obj(o, where);
      var p = {};
      if (o.n !== undefined) p.n = int(o.n, where + ' n', 1, 60);
      if (o.upTo !== undefined) p.upTo = bool(o.upTo, where + ' upTo');
      if (o.from !== undefined) p.from = spots(o.from, where + ' from');
      if (o.where !== undefined) p.where = filter(o.where, where + ' where');
      if (o.who !== undefined) p.who = word('rel', o.who, where + ' who');
      if (o.ref !== undefined) p.ref = str(o.ref, where + ' ref');
      if (o.bind !== undefined) p.bind = str(o.bind, where + ' bind');
      if (o.top !== undefined) p.top = bool(o.top, where + ' top');
      if (o.all !== undefined) p.all = bool(o.all, where + ' all');
      if (o.pickFaceDown !== undefined) p.faceDown = bool(o.pickFaceDown, where + ' pickFaceDown');
      if (p.ref === undefined && (p.from === undefined || p.from.length === 0)) fail(where, 'say where it picks from (from: "your deck") or what it means (ref: "self")');
      return p;
    }
    // An op's pick: a whole `pick`, or the pick's fields inline beside the op's own [own] keys.
    function pickIn(o, own, where) {
      o = obj(o, where);
      if (o.pick !== undefined) {
        only(o, own.concat(['pick']), where);
        // A pick fx.pick built (and checked) already: kept as it is.
        if (o.pick === null || typeof o.pick !== 'object' || o.pick.t !== undefined || o.pick.op !== undefined ||
            (o.pick.ref === undefined && !(o.pick.from instanceof Array))) fail(where, 'pick must be fx.pick({...})');
        return o.pick;
      }
      only(o, own.concat(PICK), where);
      var p = {};
      for (var i = 0; i < PICK.length; i++) if (o[PICK[i]] !== undefined) p[PICK[i]] = o[PICK[i]];
      return pickOf(p, where);
    }
    function op(t, fields) { var o = { t: t }; for (var k in fields) if (fields[k] !== undefined) o[k] = fields[k]; return { op: o }; }
    function joined(link) {
      return function (s) { var x = step(s, link.toLowerCase()); var o = { op: x.op, link: link }; return o; };
    }

    var fx = {};
    fx.words = function () {
      var out = [];
      function walk(o, depth) {
        if (depth > 2) return;
        for (var k in o) {
          var v = o[k];
          if (typeof v === 'function' && v.word) out.push(v.word);
          else if (v !== null && typeof v === 'object' && !(v instanceof Array) && k !== 'enums') walk(v, depth + 1);
        }
      }
      walk(fx, 0);
      out.sort();
      return out;
    };
    fx.enums = E;

    // ---- The card and its effects ------------------------------------------------------------------------------
    fx.card = tagged('card', function (passcode, o) {
      o = obj(o, 'card');
      only(o, ['name', 'alsoNamed', 'summon', 'effects', 'unsupported', 'notes'], 'card');
      var c = { card: int(passcode, 'card passcode', 1), vocab: 1 };
      if (o.name !== undefined) c.name = str(o.name, 'card name'); else c.name = '';
      if (o.alsoNamed !== undefined) { c.alsoNamed = []; [].concat(o.alsoNamed).forEach(function (n) { c.alsoNamed.push(str(n, 'card alsoNamed')); }); }
      if (o.summon !== undefined) {
        if (o.summon === null || typeof o.summon !== 'object' || o.summon.t !== undefined) fail('card', 'summon must be fx.summon({...})');
        c.summon = o.summon;
      }
      c.effects = [];
      [].concat(o.effects === undefined ? [] : o.effects).forEach(function (e, i) {
        if (e === null || typeof e !== 'object' || typeof e.kind !== 'string') fail('card', 'effects[' + i + '] must be fx.ignition/trigger/quick/activation/continuous(…)');
        c.effects.push(e);
      });
      if (o.unsupported !== undefined) { c.unsupported = []; [].concat(o.unsupported).forEach(function (n) { c.unsupported.push(str(n, 'card unsupported')); }); }
      if (o.notes !== undefined) c.notes = String(o.notes);
      return c;
    });
    var EFFECT = ['label', 'from', 'on', 'self', 'about', 'timing', 'optional', 'respond', 'opt', 'condition', 'cost', 'targets', 'does', 'leaves', 'sameTurn'];
    // Where a trigger about this card is used from, when it does not say: where the event leaves the card.
    var TRIGGER_FROM = { SUMMONED: 'MONSTER_ZONE', NORMAL_SUMMONED: 'MONSTER_ZONE', SPECIAL_SUMMONED: 'MONSTER_ZONE', FLIPPED: 'MONSTER_ZONE',
      SENT_TO_GY: 'GY', DESTROYED: 'GY', DISCARDED: 'GY', BANISHED: 'BANISHED', ADDED_TO_HAND: 'HAND', DRAWN: 'HAND', MATERIAL: 'GY' };
    function effect(kind) {
      return tagged('kind:' + kind.toLowerCase(), function (id, o) {
        var w = kind.toLowerCase();
        o = obj(o, w);
        only(o, EFFECT, w + ' ' + id);
        var e = { id: str(id, w + ' id'), kind: kind };
        if (o.label !== undefined) e.label = str(o.label, w + ' label');
        e.from = words('where', o.from, w + ' from');
        if (kind === 'TRIGGER') {
          if (o.on === undefined || o.on === null || typeof o.on.event !== 'string') fail(w + ' ' + id, 'a trigger says what sets it off: on: fx.on.summoned(…)');
          var t = { on: o.on };
          if (o.self !== undefined) t.self = bool(o.self, w + ' self');
          if (o.about !== undefined) t.about = filter(o.about, w + ' about');
          if (o.timing !== undefined) t.timing = word('timing', o.timing, w + ' timing');
          if (o.optional !== undefined) t.optional = bool(o.optional, w + ' optional');
          e.trigger = t;
          if (e.from.length === 0 && t.self !== false && TRIGGER_FROM[o.on.event]) e.from = [TRIGGER_FROM[o.on.event]];
        } else {
          ['on', 'self', 'about', 'timing', 'optional'].forEach(function (k) { if (o[k] !== undefined) fail(w + ' ' + id, k + ' belongs to a trigger (fx.trigger)'); });
        }
        if (o.respond !== undefined) {
          if (kind !== 'QUICK' && kind !== 'ACTIVATION') fail(w + ' ' + id, 'respond belongs to a quick effect or an activation');
          e.respond = o.respond;
        }
        if (o.opt !== undefined && o.opt !== null) { if (typeof o.opt.t !== 'string') fail(w + ' opt', 'expected fx.opt.byName(), fx.opt.perCopy() or fx.opt.perDuel()'); e.opt = o.opt; }
        if (o.condition !== undefined) e.condition = cond(o.condition, w + ' condition');
        e.cost = steps(o.cost, w + ' ' + id + ' cost');
        if (o.targets !== undefined) {
          e.targets = [];
          [].concat(o.targets).forEach(function (p, i) {
            if (p === null || typeof p !== 'object' || p.op !== undefined || p.t !== undefined) fail(w + ' ' + id + ' targets[' + i + ']', 'expected fx.pick({...})');
            e.targets.push(p);
          });
        }
        e.does = steps(o.does, w + ' ' + id + ' does');
        if (o.leaves !== undefined) {
          e.leaves = [];
          [].concat(o.leaves).forEach(function (r, i) {
            if (r === null || typeof r !== 'object' || typeof r.ban !== 'string') fail(w + ' ' + id + ' leaves[' + i + ']', 'expected fx.restriction(…)');
            e.leaves.push(r);
          });
        }
        if (o.sameTurn !== undefined) e.sameTurn = bool(o.sameTurn, w + ' sameTurn');
        if (e.cost.length === 0) delete e.cost;
        if (e.does.length === 0) delete e.does;
        return e;
      });
    }
    fx.ignition = effect('IGNITION');
    fx.trigger = effect('TRIGGER');
    fx.quick = effect('QUICK');
    fx.activation = effect('ACTIVATION');
    fx.continuous = effect('CONTINUOUS');

    // What a trigger waits for: fx.on.summoned('normal', 'special'), fx.on.sentToGy({cause: 'effect', from: 'monster_zone'}).
    fx.on = {};
    E.event.forEach(function (ev) {
      var name = ev.replace(/_([a-z])/g, function (m, c) { return c.toUpperCase(); });
      fx.on[name] = tagged('event:' + ev, function () {
        var a = Array.prototype.slice.call(arguments);
        var on = { event: ev.toUpperCase() };
        var o = a.length > 0 && a[a.length - 1] !== null && typeof a[a.length - 1] === 'object' && !(a[a.length - 1] instanceof Array) ? a.pop() : {};
        only(o, ['from', 'cause', 'summon'], 'on.' + name);
        var kinds = words('procKind', a.concat(o.summon === undefined ? [] : [].concat(o.summon)), 'on.' + name);
        if (kinds.length) on.summon = kinds;
        if (o.from !== undefined) on.from = word('where', o.from, 'on.' + name + ' from');
        if (o.cause !== undefined) on.cause = word('cause', o.cause, 'on.' + name + ' cause');
        return on;
      });
    });
    fx.respond = tagged('respond', function (o) {
      o = obj(o, 'respond');
      only(o, ['seat', 'about', 'includes'], 'respond');
      var r = {};
      if (o.seat !== undefined) r.seat = word('rel', o.seat, 'respond seat');
      if (o.about !== undefined) r.about = filter(o.about, 'respond about');
      if (o.includes !== undefined) r.includes = words('includes', o.includes, 'respond includes');
      return r;
    });

    // ---- Once per turn ----------------------------------------------------------------------------------------
    fx.opt = {
      byName: tagged('opt:name', function (o) {
        o = obj(o, 'opt.byName');
        only(o, ['times', 'group', 'activate'], 'opt.byName');
        var r = { t: 'name' };
        if (o.times !== undefined) r.times = int(o.times, 'opt.byName times', 1, 9);
        if (o.group !== undefined) r.group = str(o.group, 'opt.byName group');
        if (o.activate !== undefined) r.activate = bool(o.activate, 'opt.byName activate');
        return r;
      }),
      perCopy: tagged('opt:copy', function () { return { t: 'copy' }; }),
      perDuel: tagged('opt:duel', function () { return { t: 'duel' }; })
    };
    // "You can only activate 1 X per turn".
    fx.opt.oneCardPerTurn = function () { return fx.opt.byName({ group: 'card', activate: true }); };

    // ---- Choosing cards -----------------------------------------------------------------------------------------
    fx.pick = tagged('pick', function (o) { only(obj(o, 'pick'), PICK, 'pick'); return pickOf(o, 'pick'); });
    fx.spot = tagged('spot', function (rel, area) { return area === undefined ? spot(rel, 'spot') : spot({ rel: rel, area: area }, 'spot'); });

    // ---- Filters --------------------------------------------------------------------------------------------------
    function f0(t) { return tagged('filter:' + t, function () { return { t: t }; }); }
    fx.any = f0('any');
    fx.self = f0('self');
    fx.notSelf = f0('not-self');
    fx.faceUp = f0('face-up');
    fx.faceDown = f0('face-down');
    fx.name = tagged('filter:name', function (card) {
      if (typeof card === 'string') {
        var c = ygo.card(card);
        if (!c) fail('name', 'no card named “' + card + '” (a passcode works too)');
        return { t: 'name', card: c.id };
      }
      return { t: 'name', card: int(card, 'name', 1) };
    });
    fx.nameHas = tagged('filter:name-has', function (w) { return { t: 'name-has', word: str(w, 'nameHas') }; });
    fx.kind = tagged('filter:kind', function (type, sub) {
      var k = { t: 'kind', type: word('cardType', type, 'kind') };
      if (sub !== undefined && sub !== null) k.sub = str(sub, 'kind sub');
      return k;
    });
    fx.monster = function () { return fx.kind('monster'); };
    fx.spell = function (sub) { return fx.kind('spell', sub); };
    fx.trap = function (sub) { return fx.kind('trap', sub); };
    fx.frame = tagged('filter:frame', function (f) { return { t: 'frame', frame: word('frame', f, 'frame') }; });
    fx.tuner = function () { return fx.frame('tuner'); };
    fx.attribute = tagged('filter:attribute', function () {
      var a = words('attribute', Array.prototype.slice.call(arguments).reduce(function (x, y) { return x.concat(y); }, []), 'attribute');
      if (!a.length) fail('attribute', 'name at least one Attribute');
      return { t: 'attribute', any: a };
    });
    fx.race = tagged('filter:race', function () {
      var r = Array.prototype.slice.call(arguments).reduce(function (x, y) { return x.concat(y); }, []).map(function (x) { return str(x, 'race'); });
      if (!r.length) fail('race', 'name at least one Type, like "Spellcaster"');
      return { t: 'race', any: r };
    });
    function ranged(t, name) { return tagged('filter:' + t, function (a, b) { return { t: t, span: span(a, b, name) }; }); }
    fx.level = ranged('level', 'level');
    fx.rank = ranged('rank', 'rank');
    fx.linkRating = ranged('link', 'linkRating');
    fx.atk = ranged('atk', 'atk');
    fx.def = ranged('def', 'def');
    fx.controller = tagged('filter:controller', function (rel) { return { t: 'controller', rel: word('rel', rel, 'controller') }; });
    fx.same = tagged('filter:same', function (stat, ref) { return { t: 'same', stat: word('stat', stat, 'same'), ref: str(ref, 'same ref') }; });
    fx.lowest = tagged('filter:lowest', function (stat) { return { t: 'lowest', stat: word('stat', stat, 'lowest') }; });
    fx.highest = tagged('filter:highest', function (stat) { return { t: 'highest', stat: word('stat', stat, 'highest') }; });
    fx.all = tagged('filter:all', function () { return { t: 'all', all: filters(Array.prototype.slice.call(arguments), 'all') }; });
    fx.anyOf = tagged('filter:any-of', function () { return { t: 'any-of', any: filters(Array.prototype.slice.call(arguments), 'anyOf') }; });
    fx.not = tagged('filter:not', function (f) { return { t: 'not', not: filter(f, 'not') }; });
    fx.declared = tagged('filter:declared', function (ref) { return { t: 'declared', ref: str(ref === undefined ? 'declared' : ref, 'declared') }; });

    // ---- Numbers --------------------------------------------------------------------------------------------------
    fx.num = {
      'const': tagged('num:const', function (n) { return { t: 'const', n: int(n, 'num.const') }; }),
      count: tagged('num:count', function (from, where) { var c = { t: 'count', from: spots(from, 'num.count') }; if (where !== undefined) c.where = filter(where, 'num.count'); return c; }),
      of: tagged('num:of', function (stat, ref) { return { t: 'of', stat: word('stat', stat, 'num.of'), ref: ref === undefined ? 'self' : str(ref, 'num.of ref') }; })
    };

    // ---- Conditions -----------------------------------------------------------------------------------------------
    function conds(vs, where) { var out = []; for (var i = 0; i < vs.length; i++) out.push(cond(vs[i], where)); return out; }
    fx.cond = {
      controls: tagged('cond:controls', function (where, n, seat) {
        var c = { t: 'controls' };
        if (where !== undefined && where !== null) c.where = filter(where, 'cond.controls');
        if (n !== undefined && n !== null) c.n = num(n, 'cond.controls n');
        if (seat !== undefined && seat !== null) c.seat = word('rel', seat, 'cond.controls seat');
        return c;
      }),
      noMonsters: tagged('cond:no-monsters', function (seat) { var c = { t: 'no-monsters' }; if (seat !== undefined) c.seat = word('rel', seat, 'cond.noMonsters'); return c; }),
      count: tagged('cond:count', function (from, where, cmp, n) {
        var c = { t: 'count', from: spots(from, 'cond.count') };
        if (where !== undefined && where !== null) c.where = filter(where, 'cond.count');
        if (cmp !== undefined && cmp !== null) c.cmp = word('cmp', cmp, 'cond.count cmp');
        if (n !== undefined && n !== null) c.n = num(n, 'cond.count n');
        return c;
      }),
      phase: tagged('cond:phase', function () { return { t: 'phase', any: words('phase', Array.prototype.slice.call(arguments), 'cond.phase') }; }),
      turn: tagged('cond:turn', function (whose) { return { t: 'turn', whose: word('rel', whose, 'cond.turn') }; }),
      chainEmpty: tagged('cond:chain-empty', function () { return { t: 'chain-empty' }; }),
      newest: tagged('cond:newest', function (o) {
        o = obj(o, 'cond.newest');
        only(o, ['seat', 'about', 'includes'], 'cond.newest');
        var c = { t: 'newest' };
        if (o.seat !== undefined) c.seat = word('rel', o.seat, 'cond.newest seat');
        if (o.about !== undefined) c.about = filter(o.about, 'cond.newest about');
        if (o.includes !== undefined) c.includes = words('includes', o.includes, 'cond.newest includes');
        return c;
      }),
      thisTurn: tagged('cond:this-turn', function (ev) { return { t: 'this-turn', event: word('event', ev, 'cond.thisTurn') }; }),
      lp: tagged('cond:lp', function (seat, cmp, n) { return { t: 'lp', seat: word('rel', seat, 'cond.lp'), cmp: word('cmp', cmp, 'cond.lp'), n: num(n, 'cond.lp n') }; }),
      compare: tagged('cond:compare', function (left, cmp, right) { return { t: 'compare', left: num(left, 'cond.compare'), cmp: word('cmp', cmp, 'cond.compare'), right: num(right, 'cond.compare') }; }),
      all: tagged('cond:all', function () { return { t: 'all', all: conds(Array.prototype.slice.call(arguments), 'cond.all') }; }),
      anyOf: tagged('cond:any-of', function () { return { t: 'any-of', any: conds(Array.prototype.slice.call(arguments), 'cond.anyOf') }; }),
      not: tagged('cond:not', function (c) { return { t: 'not', not: cond(c, 'cond.not') }; })
    };
    fx.cond.yourTurn = function () { return fx.cond.turn('you'); };
    fx.cond.theirTurn = function () { return fx.cond.turn('them'); };

    // ---- Steps ------------------------------------------------------------------------------------------------------
    fx.and = joined('AND');
    fx.andIfYouDo = joined('AND_IF_YOU_DO');
    fx.then = joined('THEN');
    fx.also = joined('ALSO');
    fx.alongside = joined('WITH');
    function picking(t, own) {
      return tagged('op:' + t, function (o) {
        var extra = {};
        (own || []).forEach(function (k) { if (o && o[k] !== undefined) extra[k] = o[k]; });
        var f = { pick: pickIn(o, own || [], t) };
        if (extra.faceDown !== undefined) f.faceDown = bool(extra.faceDown, t + ' faceDown');
        return op(t, f);
      });
    }
    fx.move = tagged('op:move', function (to, o) {
      var p = pickIn(o, ['faceDown'], 'move');
      return op('move', { pick: p, to: word('dest', to, 'move'), faceDown: o && o.faceDown !== undefined ? bool(o.faceDown, 'move faceDown') : undefined });
    });
    fx.add = picking('add');
    fx.send = picking('send');
    fx.discard = picking('discard');
    fx.destroy = picking('destroy');
    fx.banish = picking('banish', ['faceDown']);
    fx.tribute = picking('tribute');
    fx.reveal = picking('reveal');
    fx.returnTo = tagged('op:return', function (to, o) { return op('return', { pick: pickIn(o, [], 'returnTo'), to: word('dest', to, 'returnTo') }); });
    fx.draw = tagged('op:draw', function (n, rel) { return op('draw', { n: n === undefined ? 1 : int(n, 'draw', 1, 60), rel: rel === undefined ? undefined : word('rel', rel, 'draw') }); });
    fx.shuffle = tagged('op:shuffle', function (rel, pile) { return op('shuffle', { rel: rel === undefined ? undefined : word('rel', rel, 'shuffle'), pile: pile === undefined ? undefined : word('area', pile, 'shuffle') }); });
    fx.specialSummon = tagged('op:special', function (o) {
      var p = pickIn(o, ['pos'], 'specialSummon');
      return op('special', { pick: p, pos: o && o.pos !== undefined ? word('pos', o.pos, 'specialSummon pos') : undefined });
    });
    fx.fusionSummon = tagged('op:fusion', function (fusion, o) {
      o = obj(o, 'fusionSummon'); only(o, ['materialsFrom'], 'fusionSummon');
      return op('fusion', { fusion: filter(fusion, 'fusionSummon'), materialsFrom: o.materialsFrom === undefined ? undefined : spots(o.materialsFrom, 'fusionSummon materialsFrom') });
    });
    fx.ritualSummon = tagged('op:ritual', function (ritual, o) {
      o = obj(o, 'ritualSummon'); only(o, ['tributesFrom', 'levels', 'from'], 'ritualSummon');
      return op('ritual', {
        ritual: filter(ritual, 'ritualSummon'),
        tributesFrom: o.tributesFrom === undefined ? undefined : spots(o.tributesFrom, 'ritualSummon tributesFrom'),
        levels: o.levels === undefined ? undefined : word('levelRule', o.levels, 'ritualSummon levels'),
        from: o.from === undefined ? undefined : spots(o.from, 'ritualSummon from')
      });
    });
    fx.synchroSummon = tagged('op:synchro', function (f) { return op('synchro', { f: filter(f, 'synchroSummon') }); });
    fx.xyzSummon = tagged('op:xyz', function (f) { return op('xyz', { f: filter(f, 'xyzSummon') }); });
    fx.linkSummon = tagged('op:link', function (f) { return op('link', { f: filter(f, 'linkSummon') }); });
    fx.attach = tagged('op:attach', function (o, to) { return op('attach', { pick: pickIn(o, [], 'attach'), to: to === undefined ? undefined : str(to, 'attach to') }); });
    fx.detach = tagged('op:detach', function (n, from) { return op('detach', { n: n === undefined ? undefined : int(n, 'detach', 1, 60), from: from === undefined ? undefined : str(from, 'detach from') }); });
    fx.token = tagged('op:token', function (o) {
      o = obj(o, 'token');
      only(o, ['name', 'attribute', 'race', 'level', 'atk', 'def', 'n', 'pos', 'rel'], 'token');
      return op('token', {
        name: str(o.name, 'token name'),
        attribute: o.attribute === undefined ? undefined : word('attribute', o.attribute, 'token attribute'),
        race: o.race === undefined ? undefined : str(o.race, 'token race'),
        level: o.level === undefined ? undefined : int(o.level, 'token level', 1, 12),
        atk: o.atk === undefined ? undefined : int(o.atk, 'token atk', 0),
        def: o.def === undefined ? undefined : int(o.def, 'token def', 0),
        n: o.n === undefined ? undefined : int(o.n, 'token n', 1, 5),
        pos: o.pos === undefined ? undefined : word('pos', o.pos, 'token pos'),
        rel: o.rel === undefined ? undefined : word('rel', o.rel, 'token rel')
      });
    });
    fx.negate = tagged('op:negate', function (o) {
      o = obj(o, 'negate'); only(o, ['what', 'link', 'bind'], 'negate');
      return op('negate', {
        what: o.what === undefined ? undefined : word('negWhat', o.what, 'negate what'),
        link: o.link === undefined ? undefined : word('linkRef', o.link, 'negate link'),
        bind: o.bind === undefined ? undefined : str(o.bind, 'negate bind')
      });
    });
    fx.changeLevel = tagged('op:level', function (o) {
      o = obj(o, 'changeLevel');
      var own = ['to', 'by', 'until'];
      var p = pickIn(o, own, 'changeLevel');
      if ((o.to === undefined) === (o.by === undefined)) fail('changeLevel', 'give either to (a Level) or by (how far it moves)');
      return op('level', {
        pick: p,
        to: o.to === undefined ? undefined : num(o.to, 'changeLevel to'),
        by: o.by === undefined ? undefined : num(o.by, 'changeLevel by'),
        until: o.until === undefined ? undefined : word('until', o.until, 'changeLevel until').toLowerCase()
      });
    });
    fx.lp = tagged('op:lp', function (rel, delta) { return op('lp', { rel: word('rel', rel, 'lp'), delta: num(delta, 'lp') }); });
    fx.payLp = tagged('op:pay', function (n) { return op('pay', { n: num(n, 'payLp') }); });
    fx.counter = tagged('op:counter', function (o, kind, delta) {
      return op('counter', { pick: pickIn(o, [], 'counter'), kind: kind === undefined ? undefined : String(kind), delta: delta === undefined ? undefined : int(delta, 'counter delta', -99, 99) });
    });
    fx.normalSummonAgain = tagged('op:normal-again', function (f) { return op('normal-again', { filter: f === undefined ? undefined : filter(f, 'normalSummonAgain') }); });
    fx.choose = tagged('op:choose', function (options, o) {
      if (!(options instanceof Array) || options.length < 2) fail('choose', 'give two or more options, each a list of steps');
      o = obj(o, 'choose'); only(o, ['who', 'labels'], 'choose');
      var opts = options.map(function (x, i) { return steps(x, 'choose option ' + (i + 1)); });
      return op('choose', {
        options: opts,
        who: o.who === undefined ? undefined : word('rel', o.who, 'choose who'),
        labels: o.labels === undefined ? undefined : [].concat(o.labels).map(function (l) { return str(l, 'choose labels'); })
      });
    });
    fx.ifThen = tagged('op:if', function (c, then, otherwise) {
      return op('if', { cond: cond(c, 'ifThen'), then: steps(then, 'ifThen then'), otherwise: otherwise === undefined ? undefined : steps(otherwise, 'ifThen otherwise') });
    });
    fx.restrict = tagged('op:restrict', function (r) {
      if (r === null || typeof r !== 'object' || typeof r.ban !== 'string') fail('restrict', 'expected fx.restriction(…)');
      return op('restrict', { restriction: r });
    });
    fx.declare = tagged('op:declare', function (kind, o) {
      o = obj(o, 'declare'); only(o, ['among', 'bind'], 'declare');
      return op('declare', { kind: word('declareKind', kind, 'declare'), among: o.among === undefined ? undefined : filter(o.among, 'declare among'), bind: o.bind === undefined ? undefined : str(o.bind, 'declare bind') });
    });

    // ---- Restrictions and summoning ------------------------------------------------------------------------------
    fx.restriction = tagged('restriction', function (ban, o) {
      o = obj(o, 'restriction'); only(o, ['except', 'seat', 'until'], 'restriction');
      var r = { ban: word('ban', ban, 'restriction') };
      if (o.except !== undefined) r.except = filter(o.except, 'restriction except');
      if (o.seat !== undefined) r.seat = word('rel', o.seat, 'restriction seat');
      if (o.until !== undefined) r.until = word('until', o.until, 'restriction until').toLowerCase();
      return r;
    });
    fx.summon = tagged('summon', function (o) {
      o = obj(o, 'summon'); only(o, ['normal', 'mustFirstBe', 'procs', 'oncePerTurn', 'tributes'], 'summon');
      var s = {};
      if (o.normal !== undefined) s.normal = bool(o.normal, 'summon normal');
      if (o.mustFirstBe !== undefined) s.mustFirstBe = word('procKind', o.mustFirstBe, 'summon mustFirstBe');
      if (o.procs !== undefined) s.procs = [].concat(o.procs).map(function (p, i) {
        if (p === null || typeof p !== 'object' || typeof p.t !== 'string') fail('summon procs[' + i + ']', 'expected fx.proc.…(…)');
        return p;
      });
      if (o.oncePerTurn !== undefined) s.oncePerTurn = bool(o.oncePerTurn, 'summon oncePerTurn');
      if (o.tributes !== undefined) s.tributes = int(o.tributes, 'summon tributes', 0, 3);
      return s;
    });
    fx.mat = tagged('mat', function (n, where, o) {
      o = obj(o, 'mat'); only(o, ['upTo', 'more'], 'mat');
      var m = { n: int(n, 'mat', 1, 12) };
      if (where !== undefined && where !== null) m.where = filter(where, 'mat');
      if (o.upTo !== undefined) m.upTo = bool(o.upTo, 'mat upTo');
      if (o.more !== undefined) m.more = bool(o.more, 'mat more');
      return m;
    });
    function mat(m, where) { if (m === null || typeof m !== 'object' || typeof m.n !== 'number') fail(where, 'expected fx.mat(n, filter)'); return m; }
    fx.proc = {
      fusion: tagged('proc:fusion', function () { return { t: 'fusion', materials: Array.prototype.slice.call(arguments).reduce(function (x, y) { return x.concat(y); }, []).map(function (m) { return mat(m, 'proc.fusion'); }) }; }),
      synchro: tagged('proc:synchro', function (tuner, others) { return { t: 'synchro', tuner: mat(tuner, 'proc.synchro tuner'), others: mat(others, 'proc.synchro others') }; }),
      xyz: tagged('proc:xyz', function (n, each, max) {
        var x = { t: 'xyz', n: int(n, 'proc.xyz', 1, 12) };
        if (each !== undefined && each !== null) x.each = filter(each, 'proc.xyz each');
        if (max !== undefined && max !== null) x.max = int(max, 'proc.xyz max', 1, 12);
        return x;
      }),
      link: tagged('proc:link', function (min, max, each, also) {
        var l = { t: 'link', min: int(min, 'proc.link min', 1, 6), max: int(max === undefined ? min : max, 'proc.link max', 1, 6) };
        if (each !== undefined && each !== null) l.each = filter(each, 'proc.link each');
        if (also !== undefined && also !== null) l.also = filter(also, 'proc.link also');
        return l;
      }),
      ritual: tagged('proc:ritual', function () { return { t: 'ritual' }; }),
      inherent: tagged('proc:inherent', function (o) {
        o = obj(o, 'proc.inherent'); only(o, ['from', 'condition', 'cost', 'opt', 'pos'], 'proc.inherent');
        var p = { t: 'inherent' };
        if (o.from !== undefined) p.from = word('where', o.from, 'proc.inherent from');
        if (o.condition !== undefined) p.condition = cond(o.condition, 'proc.inherent condition');
        if (o.cost !== undefined) p.cost = steps(o.cost, 'proc.inherent cost');
        if (o.opt !== undefined) p.opt = o.opt;
        if (o.pos !== undefined) p.pos = word('pos', o.pos, 'proc.inherent pos');
        return p;
      })
    };
    return fx;
  })();
  g.fx = ygo.fx;
"""
}
