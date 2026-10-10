package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.duel.text.NameScore
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.round

/**
 * Ai World's instruments (1.0.97, kai: "we can provide it tools it can operate that we design and engineer
 * ourselves to save tokens and start with a strong foundation"): the studies Ai would otherwise write from scratch
 * every time, built once here, tested, and run at the app's own speed rather than the script engine's. Ai runs one
 * in a single step (`world_tool`) or from a script (`ygo.tools.*`); either way it prints what it did to the world's
 * terminal and pins its boards, so the person watches an instrument work as they watch Ai's own code.
 *
 * Each takes a JSON object and gives [Result]: lines for the terminal, boards, and a JSON answer to build on.
 *
 * 1.0.96, after a red team (`docs/world/INSTRUMENTS-REDTEAM.md`): every hand number exact ([HandCounter]) and
 * checked by a seeded simulation; `or` and `any(…)`; bricks and what each card is worth to a hand; sweeps that keep the
 * deck legal and say what they cut; a card web read off Konami's phrasing; matchups that say how sure they are; and
 * four new instruments — [optimize][HandInstruments.optimize], [draws][HandInstruments.draws],
 * [combos][HandInstruments.combos], [siding][HandInstruments.siding]. [GUIDE] is how they are built, for Ai's own.
 */
object Instruments {
    data class Result(val lines: List<String>, val boards: List<WorldApi.Shown>, val answer: JsonElement)

    /** [short] is the few words a tool's description lists it by; [args] each argument, with its default. */
    data class Spec(val name: String, val short: String, val summary: String, val args: String)

    /** One clause of a condition: at least [min] and at most [max] cards of [word] (a group, a card, or `any(…)`). */
    data class Clause(val word: String, val min: Int, val max: Int)

    private const val DECK = "deck (a deck's id or name; the open deck by default)"
    private const val GROUPS = "groups ({name: [card names or passcodes]}; the deck's own groups by default)"
    private const val CONDITION = "a condition is groups or cards compared with numbers — 'Starters>=1 & Hand traps>=1', " +
        "'Starters>=1 | Extenders>=2' (& binds tighter than |), 'any(Ash Blossom & Joyous Spring, Infinite Impermanence)>=1', 'Bricks<2'"

    val ALL = listOf(
        Spec(
            "openings", "opening odds, bricks, each card's worth",
            "Opening hands: the exact odds of each condition going first (5 cards) and second (6), checked by a seeded simulation; " +
                "for the goal, the failing hands by what they hold and how much each card lifts the odds.",
            "$DECK, conditions (a list; $CONDITION; one per group by default), $GROUPS, goal (which condition to break down: its number from 1, " +
                "default 1), trials (simulated hands each way, default 50000, 0 for exact only, at most 1000000), seed (default 1), samples (hands shown, 0–6, default 3)",
        ),
        Spec(
            "ratios", "a card's or group's copies swept",
            "A ratio sweep: a condition's exact odds as a card's or a group's copies change, the deck kept at its size by cutting " +
                "what you name (a tech's worth: what each copy adds, and what it costs elsewhere).",
            "$DECK, condition, card (a Main Deck card, swept from 0 to 3 copies) or group (a group, swept around its count) or grow " +
                "(blanks to add, the deck growing), from and to (the copies or group count to sweep), cut (a card or group that makes room; " +
                "default a card the condition does not name), also (a second condition to watch, like 'Starters>=1'), keep_size (default true), $GROUPS",
        ),
        Spec(
            "optimize", "best role counts and the frontier",
            "A consistency optimiser: given roles with bounds and a goal over them, every count is tried exactly and the best kept; " +
                "with a second goal, the frontier between them.",
            "roles ({Starters: [8, 15], 'Hand traps': [9, 12], Bricks: 2} — counts, a [min, max] or a fixed number; the rest of the deck is 'other'), " +
                "goal (a condition over the roles), versus (a second goal: the frontier between the two), size (40 or [40, 42]), " +
                "turn (first, second or both — the mean — default both), top (rows, default 10), $DECK (its groups give the current counts)",
        ),
        Spec(
            "draws", "odds of seeing it by turn N",
            "Draw-into odds: the exact chance of having seen a card, a group or a condition by each of your turns, going first and second, with extra cards seen per turn.",
            "$DECK, target (a card or group: at least one) or conditions, turns (default 5, at most 20), extra (cards seen beyond the draw each turn: a number, " +
                "or a list per turn), $GROUPS",
        ),
        Spec(
            "combos", "odds of opening each combo",
            "Combo odds: for each saved combo (or ones given), the exact chance of opening every card it needs, the chance of opening any of them, and how much each adds.",
            "$DECK, combos (a list of {name, needs: [cards or groups]}; the deck's saved combos by default), $GROUPS",
        ),
        Spec(
            "siding", "a side plan's effect on the odds",
            "A side plan checked: the cards in and out against the deck, and each condition's exact odds before and after, going first and second.",
            "$DECK, out and in (lists like ['2 Called by the Grave', 'Droll & Lock Bird'], or {card: copies}; in comes from the Side Deck), " +
                "conditions (default one per group), $GROUPS",
        ),
        Spec(
            "card_web", "who searches or summons whom",
            "The deck's card web read off the cards' own text — by name and by property (\"add 1 Level 1 FIRE monster\"), materials too — " +
                "with each link's verb, hubs, stand-alone cards and each card's access (its copies and its searchers).",
            "$DECK, include (main, extra, side; default main and extra)",
        ),
        Spec(
            "composition", "what the deck is made of",
            "The deck's make-up: kinds and subtypes, Levels, Attributes, Types, what the cards do (searches, negates, hand traps — read off the text), the Extra Deck and the ATK curve.",
            DECK,
        ),
        Spec(
            "matchups", "logged games, how sure, the field",
            "Logged games (Prep) as matchups: win rates going first and second and before and after siding, shrunk toward 50 % for few games, " +
                "best of three with 95 % intervals, what is clear and what is noise, why games were lost, and the match win to expect against a field.",
            "deck (whose games; the open deck's by default, 'all' for every deck), shares ({opponent: percent of the field}; the event's field by default)",
        ),
        GoldfishInstrument.SPEC,
    )

    fun list(): String = ALL.joinToString("\n") { "- ${it.name}: ${it.summary} Args: ${it.args}." } +
        "\n- guide: how an instrument is built — read it before writing your own."

    /** One short line each, for a tool's description; [list] has the arguments. */
    fun brief(): String = ALL.joinToString("; ") { "${it.name} (${it.short})" }

    fun run(name: String, args: JsonObject, host: WorldHost): Result = when (name.trim().lowercase().replace('-', '_')) {
        "openings", "opening" -> HandInstruments.openings(args, host)
        "ratios", "ratio" -> HandInstruments.ratios(args, host)
        "optimize", "optimise", "optimizer" -> HandInstruments.optimize(args, host)
        "draws", "draw", "draw_into" -> HandInstruments.draws(args, host)
        "combos", "combo", "combo_odds" -> HandInstruments.combos(args, host)
        "siding", "side", "side_plan" -> HandInstruments.siding(args, host)
        "card_web", "web" -> DeckInstruments.cardWeb(args, host)
        "composition" -> DeckInstruments.composition(args, host)
        "matchups", "matchup" -> MatchupInstrument.matchups(args, host)
        "goldfish" -> GoldfishInstrument.run(args, host)
        "guide" -> Result(GUIDE.lines(), emptyList(), JsonPrimitive(GUIDE))
        "list" -> Result(list().lines(), emptyList(), JsonPrimitive(list()))
        else -> throw IllegalArgumentException("no instrument “$name” — one of ${ALL.joinToString { it.name }}, or list or guide")
    }

    /**
     * How an instrument is built: the shape these are made in, for Ai to make its own to the same standard
     * (`world_tool guide`, `ygo.tools.guide()`, and the ai-world skill points here).
     */
    const val GUIDE: String = """# How an instrument is built

An instrument is a study you can trust without re-checking it: one question, answered the same way every time, with its working shown. Build yours like the app's.

1. **State the question** in one line, in the player's words: "How often do I open a starter and a hand trap, going first?" Print it first. A study that cannot say its question answers nothing.
2. **Exact before simulated.** Card odds are hypergeometric: count them (ygo.hypergeo, ygo.atLeast, ygo.handOdds, or the openings instrument for any condition). Simulate only what has no closed form — a line that depends on what you drew, a game — and then say so.
3. **Seed everything.** Every random number comes from ygo.rng(seed); print the seed and the number of trials. The same arguments give the same answer, so a number can be shown again.
4. **Report n and an interval.** A rate without its sample size is a guess: k of n, with ygo.rate or ygo.stats.wilson. Ten games say far less than their percentage. Shrink few games toward 50 % before comparing them.
5. **Check against an independent method.** Simulate what you computed exactly and say whether it falls inside its own interval; compute one case by hand. Test the edges: an empty group, a card in two groups, a 60-card deck, a name with & or quotes.
6. **One board per finding, each with a note** on how it was made (exact or n trials, seed). A headline number is a stat; detail is a table; a comparison is a chart.
7. **Errors that teach.** When an argument is wrong, say what is wrong and show a call that works: "no group “Startrs” — groups are Starters, Hand traps; write Starters>=1".
8. **Lines that narrate.** The terminal reads as the study: the question, the method, the finding, the warnings.

## The shape (JavaScript)
```js
// lib/brick_rate.js — what share of hands hold no starter and two or more bricks?
function brickRate(args) {
  var deck = ygo.deck(args.deck), seed = args.seed || 1, n = args.trials || 50000;
  var starters = deck.groups[args.starters || 'Starters'] || [], bricks = deck.groups[args.bricks || 'Bricks'] || [];
  if (!starters.length) throw new Error('no group “' + (args.starters || 'Starters') + '” — give starters: the group’s name');
  var lines = ['brick rate: ' + deck.name + ', ' + n + ' hands, seed ' + seed];
  var hits = ygo.simulate(n, seed, function (r) {
    var h = ygo.hand(deck.main, r, 5), s = 0, b = 0;
    h.forEach(function (c) { if (starters.indexOf(c) >= 0) s++; if (bricks.indexOf(c) >= 0) b++; });
    return s === 0 && b >= 2;
  });
  var rate = ygo.rate(hits);
  lines.push('  ' + (rate.p * 100).toFixed(1) + '% (' + (rate.low * 100).toFixed(1) + '–' + (rate.high * 100).toFixed(1) + '%)');
  ygo.show.stat({value: (rate.p * 100).toFixed(1) + '%', label: 'Hands with no starter and two bricks', detail: n + ' hands, seed ' + seed},
    {id: 'brick-rate', note: 'Simulated: ' + n + ' seeded hands; Wilson 95% interval.'});
  lines.forEach(function (l) { print(l); });
  return {lines: lines, answer: rate};
}
```
Keep your instruments in the world's `lib/` folder, one per file, and load one with `ygo.use('lib/brick_rate.js')`. A function taking an args object and returning {lines, answer}, pinning its boards, is an instrument: the next study builds on it.
"""

    // ---- The deck as instruments read it -------------------------------------------------------------------------

    /**
     * The deck an instrument studies: its cards, and its groups as card names. [notes] are what the reading found
     * worth saying (a group with no Main Deck card, a pool card not in the deck), printed with the study.
     */
    class DeckRead(val entry: DeckEntry, val cards: Map<CardId, Card>, val groups: Map<String, Set<String>>, internal val host: WorldHost) {
        val main: List<String> = entry.deck.main.map { name(it) }
        val notes = mutableListOf<String>()
        val allNames: Set<String> = (entry.deck.main + entry.deck.extra + entry.deck.side).map { name(it) }.toSet()

        fun name(id: CardId): String = cards[id]?.name ?: "#${id.value}"

        fun card(name: String): Card? = cards.values.firstOrNull { it.name == name }

        /** A card name as the deck spells it, whatever the case; else the pool's; else null. */
        fun canonical(word: String): String? =
            allNames.firstOrNull { it.equals(word, ignoreCase = true) } ?: host.cardNamed(word)?.name

        /**
         * The cards a condition's word stands for: a group, a card by name (in any section, or the pool), or
         * `any(a, b, …)` — the cards of any of them.
         */
        fun members(word: String): Set<String> {
            val w = word.trim()
            val any = Regex("""^any\s*\((.*)\)$""", RegexOption.IGNORE_CASE).find(w)
            if (any != null) return splitList(any.groupValues[1]).flatMap { members(it) }.toSet()
            groups.entries.firstOrNull { it.key.equals(w, ignoreCase = true) }?.let { return it.value }
            canonical(w)?.let { return setOf(it) }
            throw IllegalArgumentException(
                "“$w” is neither a group (${groups.keys.joinToString().ifEmpty { "this deck has none" }}) nor a card" +
                    (suggest(w)?.let { " — did you mean “$it”?" } ?: "") +
                    ". Quote a name only when it holds a comparison sign; any(a, b)>=1 is one of several",
            )
        }

        /** The closest card name in the deck to [w], by the command line's name score. */
        fun suggest(w: String): String? =
            allNames.map { it to NameScore.of(w, it) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
                ?: allNames.minByOrNull { editDistance(it.lowercase(), w.lowercase()) }?.takeIf { editDistance(it.lowercase(), w.lowercase()) <= 3 }

        /** Each word of [goals], resolved once: the sets and the goals as bounds on them. */
        fun resolve(goals: List<Goal>, deck: List<String> = main, warn: Boolean = true): Resolved {
            val words = goals.flatMap { it.words }.distinctBy { it.lowercase() }
            val sets = words.map { members(it) }
            if (warn) words.forEachIndexed { i, w ->
                if (deck.none { it in sets[i] }) note("“$w” has no Main Deck card in this deck: every clause on it counts 0")
            }
            val index = words.withIndex().associate { (i, w) -> w.lowercase() to i }
            return Resolved(words, sets, goals.map { g -> g.any.map { all -> all.map { c -> Bound(index.getValue(c.word.lowercase()), c.min, c.max) } } })
        }

        fun note(text: String) {
            if (text !in notes) notes += text
        }
    }

    /** A goal's words as sets of card names, and each goal as bounds on them. */
    class Resolved(val words: List<String>, val sets: List<Set<String>>, val goals: List<List<List<Bound>>>)

    /** The deck [args] names (by id, by name, `open`, or none for the open deck), its cards and groups. */
    fun deckFor(args: JsonObject, host: WorldHost): DeckRead {
        val asked = args.str("deck")?.trim()?.takeIf { it.isNotEmpty() }
        val entry = (if (asked == null || asked.equals("open", ignoreCase = true)) host.deck(null) else host.deck(asked))
            ?: asked?.let { a -> host.decks().firstOrNull { it.name.equals(a, ignoreCase = true) } }
            ?: throw IllegalArgumentException(
                if (asked == null) "no deck is open: open one in the builder, or give deck: an id or name from ygo.decks()"
                else "no deck “$asked”: give a deck's id or name (ygo.decks() lists them), or leave deck out for the open deck",
            )
        require(entry.deck.main.isNotEmpty()) { "“${entry.name}” has no Main Deck to study" }
        val ids = (entry.deck.main + entry.deck.extra + entry.deck.side).distinct()
        val cards = ids.mapNotNull { id -> host.cardById(id.value)?.let { id to it } }.toMap()
        val read = DeckRead(entry, cards, emptyMap(), host)
        val given = args["groups"]
        val groups: Map<String, Set<String>> = when {
            given is JsonObject -> given.mapValues { (g, v) ->
                val items = when (v) {
                    is JsonArray -> v.toList()
                    is JsonPrimitive -> listOf(v)
                    else -> throw IllegalArgumentException("groups.$g is a list of card names, like [\"Ash Blossom & Joyous Spring\"]")
                }
                items.mapNotNull { e ->
                    val p = e as? JsonPrimitive ?: return@mapNotNull null
                    val code = p.doubleOrNull?.toInt()
                    if (code != null) {
                        cards[CardId(code)]?.name ?: host.cardById(code)?.name
                            ?: throw IllegalArgumentException("no card has the passcode $code (group “$g”)")
                    } else {
                        val raw = p.contentOrNull.orEmpty().trim()
                        read.canonical(raw) ?: throw IllegalArgumentException(
                            "“$raw” (group “$g”) is no card in this deck or the pool" + (read.suggest(raw)?.let { " — did you mean “$it”?" } ?: "") +
                                ": use full names as ygo.deck() lists them, or passcodes",
                        )
                    }
                }.toSet()
            }
            given != null && given !is kotlinx.serialization.json.JsonNull ->
                throw IllegalArgumentException("groups is an object of lists, like {\"Starters\": [\"Snake-Eye Ash\"], \"Hand traps\": [\"Ash Blossom & Joyous Spring\"]}")
            // A member the deck holds by another printing is named by the pool (Phase B): names are one card's, any printing.
            else -> host.groups(entry.id).mapValues { (_, codes) -> codes.mapNotNull { c -> (cards[CardId(c)] ?: host.cardById(c))?.name }.toSet() }
        }
        return DeckRead(entry, cards, groups, host).also { r ->
            groups.forEach { (g, names) -> names.filter { it !in r.allNames }.forEach { r.note("“$it” (group “$g”) is not in this deck: it counts 0 here") } }
        }
    }

    /** "a, b, \"c, d\"" → the names, never splitting inside quotes or brackets. */
    fun splitList(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var depth = 0
        text.forEach { c ->
            when {
                c == '"' -> quoted = !quoted
                !quoted && c == '(' -> { depth++; cur.append(c) }
                !quoted && c == ')' -> { depth--; cur.append(c) }
                !quoted && depth == 0 && c == ',' -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The conditions [args] gives (a list, or one string), else one per group, the first four. */
    fun conditionsOf(args: JsonObject, read: DeckRead, key: String = "conditions"): List<Goal> {
        val texts = (args[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: args.str(key)?.let(::listOf)
            ?: args.str("condition")?.let(::listOf)
            ?: read.groups.keys.take(4).map { "\"$it\">=1" }.ifEmpty {
                throw IllegalArgumentException("give conditions, like [\"Starters>=1\", \"${Goals.EXAMPLE}\"] — the deck has no groups to guess from")
            }
        require(texts.isNotEmpty()) { "conditions is empty: give one or more, like [\"Starters>=1\"]" }
        require(texts.size <= 12) { "at most 12 conditions at once (${texts.size} given)" }
        return texts.map(Goals::parse)
    }

    fun pct(p: Double): String = if (p.isNaN()) "—" else "${round(p * 1000) / 10}%"

    /** Percentage points, signed: "+3.2". */
    fun points(d: Double): String = (round(d * 1000) / 10).let { if (it > 0) "+$it" else "$it" }

    fun shown(id: String, title: String, kind: BoardKind, payload: String, note: String) = WorldApi.Shown(id, title.take(120), kind, payload, note.take(400))

    /** A cards board's text: each name once with its count, bracketed so a name like "7 Colored Fish" reads as itself. */
    fun cardsText(groups: List<Pair<String, List<String>>>): String = groups.joinToString("\n") { (label, names) ->
        "## $label\n" + names.groupingBy { it }.eachCount().entries.joinToString("\n") { (n, k) -> "$k [[$n]]" }
    }

    private fun editDistance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }
}
