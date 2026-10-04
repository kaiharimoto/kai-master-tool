package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardCategory
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.round
import kotlin.random.Random

/**
 * Ai World's instruments (1.0.95, kai: "we can provide it tools it can operate that we design and engineer
 * ourselves to save tokens and start with a strong foundation"): the studies Ai would otherwise write from scratch
 * every time, built once here, tested, and run at the app's own speed rather than the script engine's. Ai runs one
 * in a single step (`world_tool`) or from a script (`ygo.tools.*`); either way it prints what it did to the world's
 * terminal and pins its boards, so the person watches an instrument work as they watch Ai's own code.
 *
 * Each takes a JSON object and gives [Result]: lines for the terminal, boards, and a JSON answer to build on.
 */
object Instruments {
    data class Result(val lines: List<String>, val boards: List<WorldApi.Shown>, val answer: JsonElement)

    data class Spec(val name: String, val summary: String, val args: String)

    val ALL = listOf(
        Spec(
            "openings",
            "Opening hands: the exact odds of each condition going first (5) and second (6), checked by a seeded simulation, with sample hands.",
            "deck (id; the open deck by default), conditions (['Starters>=1', 'Starters>=1 & Hand traps>=1', 'Bricks>=2']; " +
                "group or card names, quoted when they hold & or spaces are fine), groups ({name: [cards]}; the deck's own groups by default), " +
                "trials (default 50000), seed (default 1)",
        ),
        Spec(
            "ratios",
            "A ratio sweep: one condition's odds as a card's (or a group's) copies change, or as the deck grows with blanks.",
            "deck, condition ('Starters>=1'), card (a card to sweep 0..3 copies) or grow (how many cards to add, default 5), groups",
        ),
        Spec(
            "card_web",
            "The deck's card web read off the cards' own text: who searches, summons, sends or names whom, with hubs.",
            "deck, include (main, extra, side; default main and extra)",
        ),
        Spec(
            "composition",
            "The deck's make-up: card types, Extra Deck kinds, levels, attributes and the ATK curve, as charts.",
            "deck",
        ),
        Spec(
            "matchups",
            "Logged practice games (Prep) as a matchup heatmap: win rates going first and second, before and after siding, with how many games, and each matchup's best-of-three.",
            "deck (only games with this deck; default all)",
        ),
    )

    fun list(): String = ALL.joinToString("\n") { "- ${it.name}: ${it.summary} Args: ${it.args}." }

    /** One line each, for a tool's description; [list] has the arguments. */
    fun brief(): String = ALL.joinToString("; ") { "${it.name} — ${it.summary.substringBefore(':').substringBefore('.')}" }

    fun run(name: String, args: JsonObject, host: WorldHost): Result = when (name.trim().lowercase().replace('-', '_')) {
        "openings" -> openings(args, host)
        "ratios" -> ratios(args, host)
        "card_web", "web" -> cardWeb(args, host)
        "composition" -> composition(args, host)
        "matchups" -> matchups(args, host)
        else -> throw IllegalArgumentException("no instrument “$name” — one of ${ALL.joinToString { it.name }}")
    }

    // ---- The deck as instruments read it -------------------------------------------------------------------------

    /** The main deck, its cards, and its cards' groups: given, the deck's own, or none. */
    class Hand(val entry: DeckEntry, val cards: Map<CardId, Card>, val groups: Map<String, Set<String>>) {
        val main: List<String> = entry.deck.main.map { name(it) }
        fun name(id: CardId): String = cards[id]?.name ?: "#${id.value}"

        /** The cards a condition's word stands for: a group, else a card by name. */
        fun members(word: String): Set<String> =
            groups.entries.firstOrNull { it.key.equals(word, ignoreCase = true) }?.value
                ?: main.filter { it.equals(word, ignoreCase = true) }.toSet().ifEmpty {
                    throw IllegalArgumentException("“$word” is neither a group (${groups.keys.joinToString().ifEmpty { "none" }}) nor a card in the deck")
                }
    }

    private fun deckFor(args: JsonObject, host: WorldHost): Hand {
        val entry = host.deck(args.str("deck")) ?: throw IllegalArgumentException("no deck “${args.str("deck") ?: "open"}” to study")
        require(entry.deck.main.isNotEmpty()) { "“${entry.name}” has no Main Deck" }
        val ids = (entry.deck.main + entry.deck.extra + entry.deck.side).distinct()
        val cards = ids.mapNotNull { id -> host.cardById(id.value)?.let { id to it } }.toMap()
        val given = args["groups"] as? JsonObject
        val groups = if (given != null) {
            given.mapValues { (_, v) -> (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet() }
        } else {
            host.groups(entry.id).mapValues { (_, codes) -> codes.mapNotNull { c -> cards[CardId(c)]?.name }.toSet() }
        }
        return Hand(entry, cards, groups)
    }

    /** One clause of a condition: at least, at most or exactly so many of a group or card. */
    data class Clause(val word: String, val min: Int, val max: Int)

    /** [text] split at `&`, `&&` or ` and ` — never inside quotes, where a card's own name may hold an `&`. */
    private fun clauses(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { quoted = !quoted; cur.append(c) }
                !quoted && c == '&' -> { out += cur.toString(); cur.clear(); if (text.getOrNull(i + 1) == '&') i++ }
                !quoted && text.regionMatches(i, " and ", 0, 5, ignoreCase = true) -> { out += cur.toString(); cur.clear(); i += 4 }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** "Starters>=1 & Hand traps>=1", "Bricks<=0", "\"Ash Blossom & Joyous Spring\"=1", "Starters >= 2 and Bricks < 2". */
    fun parseCondition(text: String): List<Clause> {
        val parts = clauses(text)
        require(parts.isNotEmpty()) { "an empty condition" }
        return parts.map { p ->
            val m = Regex("^\"?(.+?)\"?\\s*(>=|<=|=|==|>|<)\\s*(\\d+)$").find(p)
                ?: throw IllegalArgumentException("“$p” reads as a group or card, then >=, <=, =, > or <, then a number")
            val word = m.groupValues[1].trim()
            val n = m.groupValues[3].toInt()
            when (m.groupValues[2]) {
                ">=" -> Clause(word, n, 60)
                ">" -> Clause(word, n + 1, 60)
                "<=" -> Clause(word, 0, n)
                "<" -> Clause(word, 0, maxOf(n - 1, 0))
                else -> Clause(word, n, n)
            }
        }
    }

    /** Exact odds where the clauses' sets do not overlap; null where they do (the simulation answers then). */
    private fun exact(hand: Hand, deck: List<String>, clauses: List<Clause>, size: Int): Double? {
        val sets = clauses.map { hand.members(it.word) }
        val keys = clauses.map { it.word.lowercase() }.distinct()
        val byKey = keys.associateWith { k -> sets[clauses.indexOfFirst { it.word.lowercase() == k }] }
        val all = byKey.values.toList()
        for (i in all.indices) for (j in i + 1 until all.size) if (all[i].intersect(all[j]).isNotEmpty()) return null
        val sizes = byKey.mapValues { (_, set) -> deck.count { it in set } }
        val query = HandQuery(clauses.map { HandConstraint(it.word.lowercase(), it.min, it.max) })
        return HandOdds.probability(sizes, deck.size, size, query)
    }

    private fun meets(hand: List<String>, clauses: List<Clause>, sets: List<Set<String>>): Boolean =
        clauses.indices.all { i -> hand.count { it in sets[i] }.let { it >= clauses[i].min && it <= clauses[i].max } }

    /** The first [k] of a fair shuffle of [deck], by a partial Fisher–Yates on [random]. */
    private fun draw(deck: Array<String>, k: Int, random: Random): List<String> {
        val a = deck.copyOf()
        for (i in 0 until minOf(k, a.size)) {
            val j = i + random.nextInt(a.size - i)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a.take(k)
    }

    private fun pct(p: Double): String = "${round(p * 1000) / 10}%"

    // ---- openings -------------------------------------------------------------------------------------------------

    private fun openings(args: JsonObject, host: WorldHost): Result {
        val hand = deckFor(args, host)
        val conditions = (args["conditions"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: args.str("condition")?.let(::listOf)
            ?: hand.groups.keys.take(4).map { "$it>=1" }.ifEmpty { throw IllegalArgumentException("give conditions, like ['Starters>=1'] — the deck has no groups to guess from") }
        require(conditions.size <= 12) { "at most 12 conditions" }
        val trials = (args.int("trials") ?: 50_000).coerceIn(1_000, 500_000)
        val seed = args.num("seed")?.toLong() ?: 1L
        val deck = hand.main.toTypedArray()
        val lines = mutableListOf("openings: ${hand.entry.name}, ${deck.size} cards, $trials hands each way, seed $seed")
        val rows = mutableListOf<List<String>>()
        val firstBars = mutableListOf<Double>()
        val secondBars = mutableListOf<Double>()
        val answer = buildJsonArray {
            conditions.forEach { text ->
                val clauses = parseCondition(text)
                val sets = clauses.map { hand.members(it.word) }
                val random = Random(seed)
                val sim = listOf(5, 6).map { size -> (0 until trials).count { meets(draw(deck, size, random), clauses, sets) } }
                val exacts = listOf(5, 6).map { exact(hand, hand.main, clauses, it) }
                val (lo1, hi1) = WorldStats.wilson(sim[0], trials)
                val (lo2, hi2) = WorldStats.wilson(sim[1], trials)
                val shown1 = exacts[0] ?: sim[0].toDouble() / trials
                val shown2 = exacts[1] ?: sim[1].toDouble() / trials
                firstBars += round(shown1 * 1000) / 10
                secondBars += round(shown2 * 1000) / 10
                rows += listOf(
                    text,
                    exacts[0]?.let(::pct) ?: "—", "${pct(sim[0].toDouble() / trials)} (${pct(lo1)}–${pct(hi1)})",
                    exacts[1]?.let(::pct) ?: "—", "${pct(sim[1].toDouble() / trials)} (${pct(lo2)}–${pct(hi2)})",
                )
                lines += "  $text: first ${pct(shown1)}, second ${pct(shown2)}" + if (exacts[0] == null) " (simulated: its sets overlap)" else ""
                add(buildJsonObject {
                    put("condition", text)
                    exacts[0]?.let { put("exactFirst", it) }
                    exacts[1]?.let { put("exactSecond", it) }
                    put("simFirst", sim[0].toDouble() / trials)
                    put("simSecond", sim[1].toDouble() / trials)
                    put("trials", trials)
                    put("seed", seed)
                })
            }
        }
        val samples = Random(seed + 1).let { r -> (1..3).map { draw(deck, 5, r) } }
        val boards = listOf(
            shown("openings-table", "Opening hands — ${hand.entry.name}", BoardKind.TABLE, WorldTable.encode(WorldTable(
                "", listOf("Condition", "Exact, first", "Simulated, first (95%)", "Exact, second", "Simulated, second (95%)"), rows,
            )), "Exact odds by the hypergeometric; the simulation ($trials hands, seed $seed) checks them."),
            shown("openings-chart", "Going first or second", BoardKind.CHART, WorldChart.encode(WorldChart.Bars(
                ChatChart.Chart(
                    ChatChart.Type.HBAR, "", conditions.map { it.take(40) },
                    listOf(
                        ChatChart.Series("Going first (5)", firstBars),
                        ChatChart.Series("Going second (6)", secondBars),
                    ),
                    "%",
                ),
            )), ""),
            shown("openings-samples", "Three hands, seed ${seed + 1}", BoardKind.CARDS,
                samples.mapIndexed { i, h -> "## Hand ${i + 1}\n" + h.joinToString("\n") }.joinToString("\n"), "What the numbers look like in the hand."),
        )
        return Result(lines, boards, answer)
    }

    // ---- ratios ---------------------------------------------------------------------------------------------------

    private fun ratios(args: JsonObject, host: WorldHost): Result {
        val hand = deckFor(args, host)
        val condition = args.str("condition") ?: throw IllegalArgumentException("ratios needs a condition, like 'Starters>=1'")
        val clauses = parseCondition(condition)
        val card = args.str("card")
        val steps: List<Pair<String, List<String>>> = if (card != null) {
            val name = hand.cards.values.firstOrNull { it.name.equals(card, ignoreCase = true) }?.name
                ?: host.cardNamed(card)?.name ?: throw IllegalArgumentException("no card “$card”")
            val without = hand.main.filter { it != name }
            (0..3).map { n -> "$n" to without + List(n) { name } }
        } else {
            val grow = (args.int("grow") ?: 5).coerceIn(1, 20)
            (0..grow).map { n -> "${hand.main.size + n}" to hand.main + List(n) { "(blank)" } }
        }
        val first = mutableListOf<Double>()
        val second = mutableListOf<Double>()
        val lines = mutableListOf("ratios: ${hand.entry.name}, “$condition” as ${if (card != null) "copies of $card" else "the deck grows"} change")
        steps.forEach { (label, deck) ->
            // The swept card joins whichever group the condition names it by: a card swept is counted by name.
            val sized = Hand(hand.entry, hand.cards, hand.groups)
            val p1 = exactOrSim(sized, deck, clauses, 5)
            val p2 = exactOrSim(sized, deck, clauses, 6)
            first += round(p1 * 1000) / 10
            second += round(p2 * 1000) / 10
            lines += "  $label: first ${pct(p1)}, second ${pct(p2)} (${deck.size} cards)"
        }
        val chart = ChatChart.Chart(
            ChatChart.Type.LINE, "", steps.map { it.first },
            listOf(
                ChatChart.Series("Going first (5)", first),
                ChatChart.Series("Going second (6)", second),
            ),
            "%",
        )
        val title = if (card != null) "“$condition” by copies of $card" else "“$condition” as the deck grows"
        return Result(
            lines,
            listOf(shown("ratios-${card ?: "size"}".lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(40), title, BoardKind.CHART, WorldChart.encode(WorldChart.Bars(chart)), "Exact odds at each step.")),
            buildJsonObject {
                put("steps", JsonArray(steps.map { JsonPrimitive(it.first) }))
                put("first", JsonArray(first.map { JsonPrimitive(it) }))
                put("second", JsonArray(second.map { JsonPrimitive(it) }))
            },
        )
    }

    private fun exactOrSim(hand: Hand, deck: List<String>, clauses: List<Clause>, size: Int): Double =
        exact(hand, deck, clauses, size) ?: run {
            val sets = clauses.map { hand.members(it.word) }
            val r = Random(1)
            val a = deck.toTypedArray()
            (0 until 50_000).count { meets(draw(a, size, r), clauses, sets) } / 50_000.0
        }

    // ---- card_web -------------------------------------------------------------------------------------------------

    /** What a card's text does to another it names: the verb nearest before the name, in its sentence. */
    private val VERBS = listOf(
        "special summon" to "summons", "add" to "searches", "send" to "sends", "banish" to "banishes", "set" to "sets",
        "target" to "targets", "destroy" to "destroys", "shuffle" to "shuffles", "excavate" to "excavates", "tribute" to "tributes",
    )

    fun webOf(cards: List<Card>, groupOf: (String) -> String = { "" }): WorldGraph {
        val names = cards.map { it.name }.distinct()
        val edges = mutableListOf<WorldGraph.Edge>()
        cards.distinctBy { it.name }.forEach { a ->
            val text = a.description
            Regex("\"([^\"]{3,60})\"").findAll(text).forEach { m ->
                val term = m.groupValues[1]
                val sentence = text.substring(0, m.range.first).substringAfterLast('.').lowercase()
                val verb = VERBS.filter { (w, _) -> w in sentence }.maxByOrNull { (w, _) -> sentence.lastIndexOf(w) }?.second ?: "names"
                names.filter { it != a.name && (it.equals(term, ignoreCase = true) || it.contains(term, ignoreCase = true)) }.forEach { b ->
                    edges += WorldGraph.Edge(a.name, b, verb)
                }
            }
        }
        val kept = edges.distinctBy { it.from to it.to }.take(WorldGraph.MAX_EDGES)
        val linked = (kept.map { it.from } + kept.map { it.to }).toSet()
        val degree = linked.associateWith { n -> kept.count { it.from == n || it.to == n } }
        val nodes = names.filter { it in linked }.take(WorldGraph.MAX_NODES).map { n ->
            WorldGraph.Node(n, n, groupOf(n), (1.0 + (degree[n] ?: 0) / 3.0).coerceAtMost(4.0), card = true)
        }
        val ids = nodes.map { it.id }.toSet()
        return WorldGraph("", nodes, kept.filter { it.from in ids && it.to in ids })
    }

    private fun cardWeb(args: JsonObject, host: WorldHost): Result {
        val hand = deckFor(args, host)
        val include = (args["include"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() } ?: listOf("main", "extra")
        val ids = buildList {
            if ("main" in include) addAll(hand.entry.deck.main)
            if ("extra" in include) addAll(hand.entry.deck.extra)
            if ("side" in include) addAll(hand.entry.deck.side)
        }.distinct()
        val cards = ids.mapNotNull { hand.cards[it] }
        val groupOf = { n: String -> hand.groups.entries.firstOrNull { n in it.value }?.key.orEmpty() }
        val web = webOf(cards, groupOf)
        val hubs = web.nodes.sortedByDescending { n -> web.edges.count { it.from == n.id || it.to == n.id } }.take(5)
        val lines = listOf(
            "card_web: ${hand.entry.name}: ${web.nodes.size} cards linked by ${web.edges.size} mentions in their text",
            "  hubs: " + hubs.joinToString { n -> "${n.id} (${web.edges.count { it.from == n.id || it.to == n.id }})" },
        ) + if (web.edges.isEmpty()) listOf("  (no card names another: the deck's links are by type or attribute, which the text read does not follow)") else emptyList()
        return Result(
            lines,
            if (web.nodes.isEmpty()) emptyList() else listOf(
                shown("card-web", "Card web — ${hand.entry.name}", BoardKind.GRAPH, WorldGraph.encode(web),
                    "Read off the cards' text: an arrow where one card names another (or its archetype), with the verb before it."),
            ),
            buildJsonObject {
                put("nodes", web.nodes.size)
                put("edges", JsonArray(web.edges.map { e -> JsonArray(listOf(JsonPrimitive(e.from), JsonPrimitive(e.to), JsonPrimitive(e.label))) }))
                put("hubs", JsonArray(hubs.map { JsonPrimitive(it.id) }))
            },
        )
    }

    // ---- composition ----------------------------------------------------------------------------------------------

    private fun composition(args: JsonObject, host: WorldHost): Result {
        val hand = deckFor(args, host)
        val main = hand.entry.deck.main.mapNotNull { hand.cards[it] }
        val extra = hand.entry.deck.extra.mapNotNull { hand.cards[it] }
        fun bars(title: String, counts: Map<String, Int>): String = WorldChart.encode(WorldChart.Bars(
            ChatChart.Chart(
                ChatChart.Type.BAR, title, counts.keys.toList().take(30),
                listOf(ChatChart.Series("cards", counts.values.take(30).map { it.toDouble() })),
            ),
        ))
        val kinds = main.groupingBy { it.category.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.eachCount()
        val levels = main.filter { it.category == CardCategory.MONSTER }.groupingBy { it.level?.toString() ?: "—" }.eachCount().toSortedMap(compareBy { it.toIntOrNull() ?: 99 })
        val attributes = main.filter { it.category == CardCategory.MONSTER }.groupingBy { it.attribute.name.lowercase() }.eachCount()
        val extraKinds = extra.groupingBy { c -> listOf("link", "xyz", "synchro", "fusion").firstOrNull { c.frameType.contains(it, true) } ?: "other" }.eachCount()
        val atk = main.mapNotNull { it.atk?.toDouble() }
        val lines = listOf(
            "composition: ${hand.entry.name}: " + kinds.entries.joinToString { "${it.value} ${it.key}" } + "; Extra Deck " + extraKinds.entries.joinToString { "${it.value} ${it.key}" },
        )
        val boards = buildList {
            add(shown("composition-kinds", "Main Deck by kind", BoardKind.CHART, bars("", kinds), ""))
            if (levels.isNotEmpty()) add(shown("composition-levels", "Monsters by Level", BoardKind.CHART, bars("", levels), ""))
            if (attributes.isNotEmpty()) add(shown("composition-attributes", "Monsters by Attribute", BoardKind.CHART, bars("", attributes), ""))
            if (extraKinds.isNotEmpty()) add(shown("composition-extra", "Extra Deck by kind", BoardKind.CHART, bars("", extraKinds), ""))
            if (atk.size >= 3) add(shown("composition-atk", "ATK curve", BoardKind.CHART, WorldChart.encode(WorldChart.parse(
                buildJsonObject {
                    put("type", "histogram")
                    put("values", JsonArray(atk.map { JsonPrimitive(it) }))
                    put("bins", 8)
                }.toString(),
            ).getOrThrow()), ""))
        }
        return Result(lines, boards, buildJsonObject {
            put("kinds", JsonObject(kinds.mapValues { JsonPrimitive(it.value) }))
            put("levels", JsonObject(levels.mapValues { JsonPrimitive(it.value) }))
            put("extra", JsonObject(extraKinds.mapValues { JsonPrimitive(it.value) }))
        })
    }

    // ---- matchups -------------------------------------------------------------------------------------------------

    private fun matchups(args: JsonObject, host: WorldHost): Result {
        val games = host.games()
        require(games.isNotEmpty()) { "no practice games are logged yet (Prep, or log_game)" }
        val rows = TestStats.matrix(games, args.str("deck")).filter { it.all.games > 0 }
        require(rows.isNotEmpty()) { "no decided games for that deck" }
        fun rate(r: TestStats.Rate): Double = if (r.games == 0) Double.NaN else round(r.wins * 1000.0 / r.games) / 10
        val cols = listOf("Going first", "Going second", "Before siding", "After siding", "All")
        val values = rows.map { r -> listOf(rate(r.first), rate(r.second), rate(r.preSide), rate(r.postSide), rate(r.all)) }
        val table = rows.map { r ->
            val bo3 = if (r.first.games > 0 && r.second.games > 0) pct(TestStats.matchWin(r.first.wins.toDouble() / r.first.games, r.second.wins.toDouble() / r.second.games)) else "—"
            listOf(r.name, "${r.all.wins}/${r.all.games}", r.all.wilson().let { (lo, hi) -> "${pct(lo)}–${pct(hi)}" }, bo3)
        }
        val lines = listOf("matchups: ${games.size} games against ${rows.size} opponents") +
            rows.map { r -> "  ${r.name}: ${r.all.wins}/${r.all.games} (${pct(r.all.wins.toDouble() / r.all.games)})" }
        return Result(
            lines,
            listOf(
                shown("matchups-heat", "Matchups, win %", BoardKind.CHART, WorldChart.encode(WorldChart.Heatmap("", rows.map { it.name }, cols, values, "%")),
                    "From the games logged in Prep; a blank cell has no games."),
                shown("matchups-table", "Matchups, with how sure", BoardKind.TABLE, WorldTable.encode(WorldTable("",
                    listOf("Opponent", "Won", "95% interval", "Best of three"), table)), "Best of three from the first and second rates, the loser choosing."),
            ),
            JsonArray(rows.map { r ->
                buildJsonObject {
                    put("opponent", r.name)
                    put("wins", r.all.wins)
                    put("games", r.all.games)
                }
            }),
        )
    }

    private fun shown(id: String, title: String, kind: BoardKind, payload: String, note: String) = WorldApi.Shown(id, title, kind, payload, note)
}
