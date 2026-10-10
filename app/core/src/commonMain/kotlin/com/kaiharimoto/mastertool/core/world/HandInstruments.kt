package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.text.ChatChart
import com.kaiharimoto.mastertool.core.deck.DeckEditor
import com.kaiharimoto.mastertool.core.world.Instruments.pct
import com.kaiharimoto.mastertool.core.world.Instruments.points
import com.kaiharimoto.mastertool.core.world.Study.Companion.nums
import com.kaiharimoto.mastertool.core.world.Study.Companion.obj
import com.kaiharimoto.mastertool.core.world.Study.Companion.pc
import com.kaiharimoto.mastertool.core.world.Study.Companion.slug
import com.kaiharimoto.mastertool.core.world.Study.Companion.strs
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The instruments about hands (1.0.97): what a deck opens, what a card's or a group's copies are worth, the best role
 * counts, what is seen by a turn, the odds of each combo, and a side plan's effect. Every number is exact
 * ([HandCounter], [RoleOdds], Hall's theorem for combos); openings checks itself against a seeded simulation.
 */
internal object HandInstruments {
    const val MAX_TRIALS = 1_000_000
    private const val FIRST = 5
    private const val SECOND = 6

    /** A card that stands in for a slot no condition names: what a sweep adds to keep the deck's size. */
    const val BLANK = "(a card no condition names)"

    // ---- openings ---------------------------------------------------------------------------------------------

    fun openings(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        val goals = Instruments.conditionsOf(args, read)
        val asked = args.num("trials")?.toLong() ?: 50_000L
        val trials = asked.coerceIn(0L, MAX_TRIALS.toLong()).toInt()
        val seed = args.num("seed")?.toLong() ?: 1L
        val samples = (args.int("samples") ?: 3).coerceIn(0, 6)
        val focus = ((args.int("goal") ?: 1) - 1).coerceIn(0, goals.size - 1)
        val s = Study()
        s.say("openings: ${read.entry.name} (${read.main.size} cards) — how often does each condition hold in the opening hand, going first (5) and second (6)?")
        s.say("  method: exact (the hypergeometric over the deck's cards, groups overlapping or not)" + if (trials > 0) "; checked by $trials seeded hands each way, seed $seed" else "")
        if (asked > MAX_TRIALS) s.warn("trials: at most ${commas(MAX_TRIALS)} a run (asked ${commas(asked)}); the odds are exact either way")
        if (asked < 0) s.warn("trials cannot be negative: 0 is exact only")
        val resolved = read.resolve(goals)
        s.warn(read)
        val counter = HandCounter.of(read.main, resolved.sets)
        val rows = goals.mapIndexed { i, g ->
            val goal = resolved.goals[i]
            val exact = listOf(FIRST, SECOND).map { h -> runCatching { counter.probability(goal, h) }.getOrNull() }
            val sim = if (trials > 0) listOf(FIRST, SECOND).mapIndexed { k, h -> counter.simulate(goal, h, trials, seed + k).toDouble() / trials } else listOf(null, null)
            // The check: is the simulation inside its own 99.9 % interval round the exact number?
            val check = if (trials == 0 || exact.any { it == null }) null else (0..1).all { k ->
                val hits = (sim[k]!! * trials).toInt()
                val (lo, hi) = WorldStats.wilson(hits, trials, 3.29)
                exact[k]!! in lo..hi
            }
            Row(g.text, exact[0] ?: sim[0] ?: 0.0, exact[1] ?: sim[1] ?: 0.0, exact[0], exact[1], sim[0], sim[1], check)
        }
        rows.forEach { r ->
            s.say("  ${r.text}: first ${pct(r.first)}, second ${pct(r.second)}" + when (r.check) {
                true -> " (check ok)"
                false -> " (check OFF: the simulation disagrees — say so)"
                null -> if (r.exactFirst == null) " (simulated: too many groups at once to count)" else ""
            })
        }
        val goal = goals[focus]
        val bricks = bricks(read, resolved.goals[focus], resolved.sets, goal)
        val worth = contribution(read, goals[focus])
        if (bricks.isNotEmpty()) s.say("  when “${goal.text}” fails going first, the hand most often holds: ${bricks.first().first} (${pct(bricks.first().second)} of all hands)")
        worth.firstOrNull()?.let { s.say("  the card that lifts it most: ${it.name} (${points(it.lift)} points when it is in the hand)") }

        val top = rows[focus]
        s.stat("openings-headline", Goals.words(top.text), pct(top.first), "going first; ${pct(top.second)} going second", read.entry.name, howMade(trials, seed))
        s.table(
            "openings", "Opening hands — ${read.entry.name}",
            listOf("Condition", "First (5)", "Second (6)", "Simulated first", "Simulated second", "Check"),
            rows.map { r -> listOf(Goals.words(r.text), pct(r.first), pct(r.second), r.simFirst?.let(::pct) ?: "—", r.simSecond?.let(::pct) ?: "—", when (r.check) { true -> "ok"; false -> "OFF"; null -> "—" }) },
            howMade(trials, seed),
        )
        if (rows.size > 1) {
            s.chart(
                "openings-chart", "Going first or second", ChatChart.Type.HBAR, rows.map { Goals.words(it.text) },
                listOf(ChatChart.Series("Going first (5)", rows.map { pc(it.first) }), ChatChart.Series("Going second (6)", rows.map { pc(it.second) })), "%",
                "Exact odds.",
            )
        }
        if (bricks.isNotEmpty()) {
            s.table("openings-bricks", "When “${Goals.words(goal.text)}” fails", listOf("The hand holds", "Of all hands"), bricks.take(8).map { listOf(it.first, pct(it.second)) },
                "Exact, going first: the failing hands by how many of each group they hold, commonest first.")
        }
        if (worth.isNotEmpty()) {
            s.table(
                "openings-worth", "What each card is worth to “${Goals.words(goal.text)}”", listOf("Card", "Copies", "When it is in the hand", "Lift"),
                worth.take(20).map { listOf(it.name, "${it.copies}", pct(it.given), points(it.lift)) },
                "Exact, going first: the chance of the goal when the card is in the opening hand, against the deck's ${pct(top.first)}.",
                cards = listOf(0),
            )
        }
        if (samples > 0) {
            val random = Random(seed + 101)
            val hands = (1..samples).map { k -> "Hand $k" to draw(read.main, FIRST, random) }
            s.board("openings-samples", "${hands.size} hands, seed ${seed + 101}", BoardKind.CARDS, Instruments.cardsText(hands), "Dealt by the same seeded shuffle the check uses: what the numbers look like in the hand.")
        }
        return s.done(obj(
            "rows" to JsonArray(rows.map { r ->
                obj(
                    "condition" to JsonPrimitive(r.text),
                    "exactFirst" to r.exactFirst?.let(::JsonPrimitive), "exactSecond" to r.exactSecond?.let(::JsonPrimitive),
                    "simFirst" to r.simFirst?.let(::JsonPrimitive), "simSecond" to r.simSecond?.let(::JsonPrimitive),
                    "check" to r.check?.let(::JsonPrimitive), "trials" to JsonPrimitive(trials), "seed" to JsonPrimitive(seed),
                )
            }),
            "bricks" to JsonArray(bricks.take(8).map { obj("hand" to JsonPrimitive(it.first), "p" to JsonPrimitive(it.second)) }),
            "worth" to JsonArray(worth.map { obj("card" to JsonPrimitive(it.name), "copies" to JsonPrimitive(it.copies), "given" to JsonPrimitive(it.given), "lift" to JsonPrimitive(it.lift)) }),
        ))
    }

    private data class Row(
        val text: String, val first: Double, val second: Double,
        val exactFirst: Double?, val exactSecond: Double?, val simFirst: Double?, val simSecond: Double?, val check: Boolean?,
    )

    private fun howMade(trials: Int, seed: Long) =
        "Exact (hypergeometric)." + if (trials > 0) " Checked by $trials seeded hands each way (seed $seed): 'ok' when the simulation falls inside its 99.9% interval." else ""

    /** The failing hands going first, by how many of each of the deck's groups (and the goal's words) they hold. */
    private fun bricks(read: Instruments.DeckRead, goal: List<List<Bound>>, sets: List<Set<String>>, g: Goal): List<Pair<String, Double>> {
        val groups = read.groups.entries.filter { (_, v) -> read.main.any { it in v } }.take(6)
        val all = sets + groups.map { it.value }
        val labels = g.words + groups.map { it.key }
        if (all.size > 12) return emptyList()
        val counter = HandCounter.of(read.main, all)
        val out = LinkedHashMap<String, Double>()
        runCatching { counter.distribution(FIRST) }.getOrNull()?.forEach { (counts, p) ->
            if (HandCounter.meets(counts, goal)) return@forEach
            // Each label once, the goal's own words first; a word that is also a group is said once.
            val key = labels.indices.distinctBy { labels[it].lowercase() }.joinToString(", ") { "${counts[it]} ${labels[it]}" }
            out[key] = (out[key] ?: 0.0) + p
        }
        return out.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    private class Worth(val name: String, val copies: Int, val given: Double, val lift: Double)

    /** For each Main Deck card: the goal's chance going first when it is in the hand, and how far that is from the deck's. */
    private fun contribution(read: Instruments.DeckRead, goal: Goal): List<Worth> {
        val names = read.main.groupingBy { it }.eachCount()
        if (names.size > 60) return emptyList()
        val base = read.resolve(listOf(goal), warn = false)
        val baseP = HandCounter.of(read.main, base.sets).probability(base.goals[0], FIRST)
        return names.mapNotNull { (name, copies) ->
            val sets = base.sets + listOf(setOf(name))
            val idx = sets.size - 1
            val counter = HandCounter.of(read.main, sets)
            val with = base.goals[0].map { all -> all + Bound(idx, 1, Goals.NO_MAX) }
            val pBoth = runCatching { counter.probability(with, FIRST) }.getOrNull() ?: return@mapNotNull null
            val pCard = counter.probability(listOf(listOf(Bound(idx, 1, Goals.NO_MAX))), FIRST)
            if (pCard <= 0.0) null else Worth(name, copies, pBoth / pCard, pBoth / pCard - baseP)
        }.sortedByDescending { it.lift }
    }

    private fun draw(deck: List<String>, n: Int, random: Random): List<String> {
        val a = deck.toMutableList()
        for (i in 0 until minOf(n, a.size)) {
            val j = i + random.nextInt(a.size - i)
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a.take(n)
    }

    // ---- ratios -----------------------------------------------------------------------------------------------

    fun ratios(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        val condText = args.str("condition") ?: (args["conditions"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw IllegalArgumentException("ratios needs a condition, like condition: 'Starters>=1'")
        val goal = Goals.parse(condText)
        val also = args.str("also")?.let(Goals::parse)
        val keep = args["keep_size"]?.let { (it as? JsonPrimitive)?.contentOrNull != "false" } ?: true
        val goalsAll = listOfNotNull(goal, also)
        val named = read.resolve(goalsAll, warn = false).sets.flatten().toSet()
        val cutName = args.str("cut")?.let { c ->
            read.canonical(c) ?: read.groups.keys.firstOrNull { it.equals(c, ignoreCase = true) }
                ?: throw IllegalArgumentException("cut: “$c” is neither a card in the deck nor a group" + (read.suggest(c)?.let { " — did you mean “$it”?" } ?: ""))
        }
        val s = Study()
        val steps: List<Pair<String, List<String>>>
        val what: String
        val notes = mutableListOf<String>()
        when {
            args.str("card") != null -> {
                val raw = args.str("card")!!
                val name = read.canonical(raw) ?: throw IllegalArgumentException("no card “$raw”" + (read.suggest(raw)?.let { " — did you mean “$it”?" } ?: "") + ": give a Main Deck card's full name")
                val card = read.card(name) ?: host.cardNamed(name)
                require(card == null || !card.isExtraDeck) { "“$name” is an Extra Deck card: a sweep changes the Main Deck. Sweep a Main Deck card, or a group" }
                val have = read.main.count { it == name }
                // Swept no further than the list in force allows (2026-10, the red team): a Limited card at 2 and 3
                // copies is a deck nobody can register.
                val limit = card?.let { DeckEditor.copyLimit(it, host.format()) } ?: 3
                if (limit < 3) notes += "$name is ${card?.banStatus(host.format())?.name?.lowercase()} in the ${host.format().name}: swept to $limit, its limit"
                // A card the condition never names counts toward nothing: its sweep is flat, and says nothing of the card.
                if (name !in named) notes += "$name is in no group or card the condition names: its copies count toward nothing here — " +
                    "put it in a group (groups: {\"Hand traps\": [\"$name\", …]}) or name it in the condition"
                val from = (args.int("from") ?: 0).coerceIn(0, limit)
                val to = (args.int("to") ?: limit).coerceIn(from, limit)
                what = "copies of $name"
                steps = (from..to).map { n -> "$n" to resize(read.main.filter { it != name } + List(n) { name }, read.main.size, keep, have - n, cutName, named + name, read) }
            }
            args.str("group") != null -> {
                val raw = args.str("group")!!
                val (gName, members) = read.groups.entries.firstOrNull { it.key.equals(raw, ignoreCase = true) }?.toPair()
                    ?: throw IllegalArgumentException("no group “$raw” — the deck's groups are ${read.groups.keys.joinToString().ifEmpty { "none" }}")
                val have = read.main.count { it in members }
                val from = (args.int("from") ?: maxOf(0, have - 3)).coerceAtLeast(0)
                val to = (args.int("to") ?: (have + 3)).coerceIn(from, read.main.size)
                what = "cards in $gName"
                val token = "⟨$gName⟩"
                val rest = read.main.filter { it !in members }
                if (goalsAll.flatMap { it.words }.any { w -> w.lowercase() != gName.lowercase() && runCatching { read.members(w) }.getOrNull()?.any { it in members } == true }) {
                    s.warn("the condition names cards of $gName on their own: a group sweep counts the group as one kind of card")
                }
                steps = (from..to).map { n -> "$n" to resize(rest + List(n) { token }, read.main.size, keep, have - n, cutName, named + token, read) }
            }
            else -> {
                val grow = (args.int("grow") ?: 5).coerceIn(1, 20)
                what = "the deck growing"
                steps = (0..grow).map { n -> "${read.main.size + n}" to read.main + List(n) { BLANK } }
            }
        }
        s.say("ratios: ${read.entry.name} — how do the odds of “${goal.text}” move with $what?")
        notes.forEach(s::warn)
        s.say("  method: exact at every step; " + if (keep && args.str("grow") == null) "the deck kept at ${read.main.size} cards by ${cutName?.let { "cutting or adding $it" } ?: "a card no condition names"}" else "the deck's size changing")
        s.warn(read)
        fun odds(g: Goal, deck: List<String>, h: Int): Double {
            val token = steps.firstOrNull()?.second?.firstOrNull { it.startsWith("⟨") }
            val r = readWithToken(read, token, deck)
            val res = r.resolve(listOf(g), deck, warn = false)
            return HandCounter.of(deck, res.sets).probability(res.goals[0], h)
        }
        val first = steps.map { (_, d) -> odds(goal, d, FIRST) }
        val second = steps.map { (_, d) -> odds(goal, d, SECOND) }
        val alsoFirst = also?.let { a -> steps.map { (_, d) -> odds(a, d, FIRST) } }
        steps.forEachIndexed { i, (label, d) ->
            val step = if (i > 0) " (${points(first[i] - first[i - 1])})" else ""
            s.say("  $label: first ${pct(first[i])}$step, second ${pct(second[i])}" + (alsoFirst?.let { ", “${also.text}” ${pct(it[i])}" } ?: "") + " — ${d.size} cards")
        }
        val series = listOfNotNull(
            ChatChart.Series("Going first (5)", first.map(::pc)),
            ChatChart.Series("Going second (6)", second.map(::pc)),
            alsoFirst?.let { ChatChart.Series("“${also.text}” first", it.map(::pc)) },
        )
        s.chart("ratios-${slug(what)}", "“${goal.text}” by $what", ChatChart.Type.LINE, steps.map { it.first }, series, "%",
            "Exact at every step" + if (keep) "; the deck kept at ${read.main.size}." else ".")
        return s.done(obj(
            "steps" to strs(steps.map { it.first }),
            "first" to nums(first),
            "second" to nums(second),
            "alsoFirst" to alsoFirst?.let(::nums),
            "sizes" to JsonArray(steps.map { JsonPrimitive(it.second.size) }),
        ))
    }

    /** [deck] brought back to [size]: [freed] slots filled (or, negative, taken) with [cut], else blanks or cards no condition names. */
    private fun resize(deck: List<String>, size: Int, keep: Boolean, freed: Int, cut: String?, named: Set<String>, read: Instruments.DeckRead): List<String> {
        if (!keep) return deck
        val d = deck.toMutableList()
        while (d.size < size) d += (cut?.takeIf { read.groups[it] == null } ?: BLANK)
        while (d.size > size) {
            val victim = cut?.let { c -> read.groups[c]?.let { g -> d.lastOrNull { it in g } } ?: d.lastOrNull { it == c } }
                ?: d.groupingBy { it }.eachCount().filterKeys { it !in named }.maxByOrNull { it.value }?.key
                ?: throw IllegalArgumentException("no card left to cut that the condition does not name: give cut: a card or group, or keep_size: false")
            d.removeAt(d.lastIndexOf(victim))
        }
        return d
    }

    /** The deck read with a group's sweep token standing for the group. */
    private fun readWithToken(read: Instruments.DeckRead, token: String?, deck: List<String>): Instruments.DeckRead {
        if (token == null) return read
        val g = token.removePrefix("⟨").removeSuffix("⟩")
        return Instruments.DeckRead(read.entry, read.cards, read.groups.mapValues { (k, v) -> if (k == g) setOf(token) else v }, read.host)
    }

    // ---- optimize ---------------------------------------------------------------------------------------------

    fun optimize(args: JsonObject, host: WorldHost): Instruments.Result {
        val roles = args["roles"] as? JsonObject
            ?: throw IllegalArgumentException("optimize needs roles, like roles: {\"Starters\": [8, 15], \"Hand traps\": [9, 12], \"Bricks\": 2}")
        require(roles.isNotEmpty() && roles.size <= 8) { "give 1 to 8 roles" }
        val names = roles.keys.toList()
        val ranges = names.map { k ->
            when (val v = roles[k]) {
                is JsonArray -> {
                    val a = v.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }
                    require(a.size == 2 && a[0] in 0..a[1]) { "roles.$k is [min, max], like [8, 15]" }
                    a[0]..a[1]
                }
                is JsonPrimitive -> (v.doubleOrNull?.toInt() ?: throw IllegalArgumentException("roles.$k is a count or [min, max]")).let { it..it }
                else -> throw IllegalArgumentException("roles.$k is a count or [min, max]")
            }
        }
        val sizes = when (val v = args["size"]) {
            null -> listOf(40)
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }.let { a -> require(a.size == 2 && a[0] in 40..a[1] && a[1] <= 60) { "size is 40–60, or [40, 42]" }; (a[0]..a[1]).toList() }
            is JsonPrimitive -> listOf((v.doubleOrNull?.toInt() ?: 40).also { require(it in 40..60) { "size is 40–60" } })
            else -> throw IllegalArgumentException("size is a number or [min, max]")
        }
        val turn = args.str("turn")?.lowercase() ?: "both"
        require(turn in setOf("first", "second", "both")) { "turn is first, second or both" }
        val goal = Goals.parse(args.str("goal") ?: throw IllegalArgumentException("optimize needs a goal over the roles, like goal: 'Starters>=1 & Hand traps>=1'"))
        val versus = args.str("versus")?.let(Goals::parse)
        fun wordsOf(g: Goal): Pair<List<Set<Int>>, List<List<Bound>>> {
            val words = g.words
            val sets = words.map { w ->
                val any = Regex("""^any\s*\((.*)\)$""", RegexOption.IGNORE_CASE).find(w.trim())
                val parts = any?.let { Instruments.splitList(it.groupValues[1]) } ?: listOf(w)
                parts.map { p -> names.indexOfFirst { it.equals(p.trim(), ignoreCase = true) }.also { i -> require(i >= 0) { "“$p” is not a role — the roles are ${names.joinToString()}" } } }.toSet()
            }
            val index = words.withIndex().associate { (i, w) -> w.lowercase() to i }
            return sets to g.any.map { all -> all.map { c -> Bound(index.getValue(c.word.lowercase()), c.min, c.max) } }
        }
        val (gSets, gBounds) = wordsOf(goal)
        val oddsA = listOf(FIRST, SECOND).associateWith { RoleOdds(names.size, gSets, gBounds, it) }
        val oddsB = versus?.let { v -> val (vs, vb) = wordsOf(v); listOf(FIRST, SECOND).associateWith { RoleOdds(names.size, vs, vb, it) } }
        val combos = ranges.fold(1L) { a, r -> a * (r.last - r.first + 1) } * sizes.size
        require(combos <= 300_000) { "that is $combos decks to try: narrow the roles' ranges (at most 300,000)" }
        fun score(o: Map<Int, RoleOdds>, c: IntArray, size: Int): Triple<Double, Double, Double> {
            val f = o.getValue(FIRST).probability(c, size)
            val sc = o.getValue(SECOND).probability(c, size)
            return Triple(f, sc, when (turn) { "first" -> f; "second" -> sc; else -> (f + sc) / 2 })
        }
        data class Tried(val counts: IntArray, val size: Int, val a: Triple<Double, Double, Double>, val b: Triple<Double, Double, Double>?)
        val tried = mutableListOf<Tried>()
        val c = IntArray(names.size)
        fun rec(i: Int) {
            if (i == names.size) {
                sizes.forEach { size -> if (c.sum() <= size) tried += Tried(c.copyOf(), size, score(oddsA, c, size), oddsB?.let { score(it, c, size) }) }
                return
            }
            for (x in ranges[i]) {
                c[i] = x
                rec(i + 1)
            }
        }
        rec(0)
        require(tried.isNotEmpty()) { "no deck fits: the roles' minimums hold more cards than the deck" }
        val top = (args.int("top") ?: 10).coerceIn(1, 50)
        val best = tried.sortedByDescending { it.a.third }
        val s = Study()
        s.say("optimize: which role counts give the best chance of “${goal.text}” (${if (turn == "both") "the mean of going first and second" else "going $turn"})?")
        s.say("  method: every one of ${tried.size} decks tried exactly (deck sizes ${sizes.joinToString()}; the rest of each deck is cards in no role)")
        val b0 = best.first()
        s.say("  best: " + names.indices.joinToString(", ") { "${b0.counts[it]} ${names[it]}" } + " in ${b0.size}: first ${pct(b0.a.first)}, second ${pct(b0.a.second)}")
        // The deck's own counts, where its groups are the roles.
        args.str("deck")?.let { _ ->
            runCatching { Instruments.deckFor(args, host) }.getOrNull()?.let { read ->
                val now = IntArray(names.size) { i -> read.groups.entries.firstOrNull { it.key.equals(names[i], ignoreCase = true) }?.let { g -> read.main.count { it in g.value } } ?: 0 }
                val yours = score(oddsA, now, read.main.size)
                s.say("  your deck now: " + names.indices.joinToString(", ") { "${now[it]} ${names[it]}" } + ": first ${pct(yours.first)}, second ${pct(yours.second)}")
            }
        }
        s.stat("optimize-best", "Best for “${goal.text}”", pct(b0.a.third), names.indices.joinToString(", ") { "${b0.counts[it]} ${names[it]}" } + " in ${b0.size}",
            "first ${pct(b0.a.first)}, second ${pct(b0.a.second)}", "Exact over ${tried.size} decks.")
        s.table("optimize-top", "The best ${minOf(top, best.size)} decks", names + listOf("Size", "First", "Second"),
            best.take(top).map { t -> names.indices.map { "${t.counts[it]}" } + listOf("${t.size}", pct(t.a.first), pct(t.a.second)) }, "Exact; ranked by ${if (turn == "both") "the mean" else "going $turn"}.")
        var frontier: List<Tried> = emptyList()
        if (versus != null) {
            frontier = tried.filter { t -> tried.none { o -> o.a.third >= t.a.third && o.b!!.third >= t.b!!.third && (o.a.third > t.a.third || o.b.third > t.b.third) } }
                .sortedBy { it.a.third }.distinctBy { pc(it.a.third) to pc(it.b!!.third) }
            s.say("  the frontier between “${goal.text}” and “${versus.text}”: ${frontier.size} decks where neither can rise without the other falling")
            s.board("optimize-frontier", "“${goal.text}” against “${versus.text}”", BoardKind.CHART, WorldChart.encode(WorldChart.Scatter("", goal.text, versus.text, listOf(
                WorldChart.Cloud("every deck", tried.take(WorldChart.MAX_POINTS - frontier.size).map { WorldChart.Point(pc(it.a.third), pc(it.b!!.third)) }),
                WorldChart.Cloud("the frontier", frontier.map { t -> WorldChart.Point(pc(t.a.third), pc(t.b!!.third), names.indices.joinToString(" ") { "${t.counts[it]}" }) }),
            ))), "Exact for every deck tried; the frontier is the decks no other beats on both.")
            s.table("optimize-frontier-table", "The frontier", names + listOf("Size", goal.text.take(30), versus.text.take(30)),
                frontier.take(30).map { t -> names.indices.map { "${t.counts[it]}" } + listOf("${t.size}", pct(t.a.third), pct(t.b!!.third)) }, "Each a deck where one goal can rise only by the other falling.")
        }
        fun row(t: Tried) = obj("counts" to JsonObject(names.indices.associate { names[it] to JsonPrimitive(t.counts[it]) }), "size" to JsonPrimitive(t.size),
            "first" to JsonPrimitive(t.a.first), "second" to JsonPrimitive(t.a.second), "score" to JsonPrimitive(t.a.third), "versus" to t.b?.let { JsonPrimitive(it.third) })
        return s.done(obj("tried" to JsonPrimitive(tried.size), "best" to JsonArray(best.take(top).map(::row)), "frontier" to JsonArray(frontier.map(::row))))
    }

    // ---- draws ------------------------------------------------------------------------------------------------

    fun draws(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        val goals = args.str("target")?.let { listOf(Goals.parse("\"$it\">=1")) } ?: Instruments.conditionsOf(args, read)
        val turns = (args.int("turns") ?: 5).coerceIn(1, 20)
        val extra: List<Int> = when (val e = args["extra"]) {
            null -> List(turns) { 0 }
            is JsonArray -> e.map { (it as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0 }.let { l -> List(turns) { l.getOrElse(it) { 0 } } }
            is JsonPrimitive -> List(turns) { e.doubleOrNull?.toInt() ?: 0 }
            else -> throw IllegalArgumentException("extra is a number, or a list per turn")
        }
        val s = Study()
        s.say("draws: ${read.entry.name} — by each of my turns, how often have I seen ${goals.joinToString(" / ") { "“${it.text}”" }}?")
        s.say("  method: exact; going first sees 5 then a card a turn from turn 2, going second 6 then a card a turn" + if (extra.any { it > 0 }) "; plus extra cards seen: ${extra.joinToString()}" else "")
        val resolved = read.resolve(goals)
        s.warn(read)
        val counter = HandCounter.of(read.main, resolved.sets)
        fun seen(first: Boolean, t: Int) = (if (first) FIRST + (t - 1) else SECOND + (t - 1)) + extra.take(t).sum()
        val labels = (1..turns).map { "T$it" }
        val out = goals.mapIndexed { i, g ->
            val f = (1..turns).map { t -> counter.probability(resolved.goals[i], seen(true, t)) }
            val sc = (1..turns).map { t -> counter.probability(resolved.goals[i], seen(false, t)) }
            s.say("  ${g.text}: first " + f.joinToString(" → ") { pct(it) } + "; second " + sc.joinToString(" → ") { pct(it) })
            Triple(g, f, sc)
        }
        out.forEachIndexed { i, (g, f, sc) ->
            s.chart("draws-${i + 1}", "“${g.text}” by turn", ChatChart.Type.LINE, labels,
                listOf(ChatChart.Series("Going first", f.map(::pc)), ChatChart.Series("Going second", sc.map(::pc))), "%", "Exact: the chance of having seen it by each of your turns.")
        }
        s.table("draws-table", "Seen by turn", listOf("Condition") + labels.flatMap { listOf("$it first", "$it second") }.take(18),
            out.map { (g, f, sc) -> listOf(g.text) + f.indices.flatMap { listOf(pct(f[it]), pct(sc[it])) }.take(18) }, "Exact.")
        return s.done(JsonArray(out.map { (g, f, sc) -> obj("condition" to JsonPrimitive(g.text), "first" to nums(f), "second" to nums(sc)) }))
    }

    // ---- combos -----------------------------------------------------------------------------------------------

    fun combos(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        val given = (args["combos"] as? JsonArray)?.map { e ->
            val o = e as? JsonObject ?: throw IllegalArgumentException("each combo is {name, needs: [cards or groups]}")
            val needs = (o["needs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: throw IllegalArgumentException("combo “${o.str("name")}” needs needs: a list of cards or groups")
            (o.str("name") ?: needs.joinToString(" + ")) to needs
        } ?: host.combos(read.entry.id).map { it.name to it.needs }
        require(given.isNotEmpty()) { "no combos: give combos: [{name: 'Ash line', needs: ['Snake-Eye Ash', 'Starters']}], or save combos at the Duel table" }
        require(given.size <= 12) { "at most 12 combos at once" }
        val s = Study()
        s.say("combos: ${read.entry.name} — how often do I open every card each combo needs, and any of them?")
        s.say("  method: exact; a combo's needs are filled by different cards (Hall's theorem turns that into counts)")
        // Every need resolved, then each combo as at-least clauses on unions of its needs.
        val perCombo = given.map { (name, needs) ->
            val bySet = needs.map { read.members(it) }.groupingBy { it }.eachCount().entries.map { it.key to it.value }
            name to Matching.hall(bySet)
        }
        s.warn(read)
        val sets = perCombo.flatMap { it.second.map { (set, _) -> set } }.distinct()
        val index = sets.withIndex().associate { (i, v) -> v to i }
        val goals = perCombo.map { (_, clauses) -> listOf(clauses.map { (set, n) -> Bound(index.getValue(set), n, Goals.NO_MAX) }) }
        val counter = HandCounter.of(read.main, sets)
        val each = goals.map { g -> counter.probability(g, FIRST) to counter.probability(g, SECOND) }
        val anyGoal = goals.flatten()
        val any = counter.probability(anyGoal, FIRST) to counter.probability(anyGoal, SECOND)
        val adds = goals.indices.map { i ->
            val without = goals.filterIndexed { j, _ -> j != i }.flatten()
            any.first - (if (without.isEmpty()) 0.0 else counter.probability(without, FIRST))
        }
        perCombo.forEachIndexed { i, (name, _) -> s.say("  $name: first ${pct(each[i].first)}, second ${pct(each[i].second)}; adds ${points(adds[i])} to opening any") }
        s.say("  any of them: first ${pct(any.first)}, second ${pct(any.second)}")
        s.stat("combos-any", "Opens a combo", pct(any.first), "going first; ${pct(any.second)} going second", "any of ${given.size} combos", "Exact.")
        s.table("combos-table", "Each combo", listOf("Combo", "Needs", "First", "Second", "Adds to any"),
            given.mapIndexed { i, (name, needs) -> listOf(name, needs.joinToString(" + "), pct(each[i].first), pct(each[i].second), points(adds[i])) },
            "Exact. 'Adds to any': how much lower the chance of opening any combo would be without this one.")
        return s.done(obj(
            "combos" to JsonArray(given.mapIndexed { i, (name, _) -> obj("name" to JsonPrimitive(name), "first" to JsonPrimitive(each[i].first), "second" to JsonPrimitive(each[i].second), "adds" to JsonPrimitive(adds[i])) }),
            "anyFirst" to JsonPrimitive(any.first), "anySecond" to JsonPrimitive(any.second),
        ))
    }

    // ---- siding -----------------------------------------------------------------------------------------------

    fun siding(args: JsonObject, host: WorldHost): Instruments.Result {
        val read = Instruments.deckFor(args, host)
        fun list(key: String): List<String> = when (val v = args[key]) {
            null -> emptyList()
            is JsonArray -> v.flatMap { e ->
                val t = (e as? JsonPrimitive)?.contentOrNull?.trim() ?: return@flatMap emptyList()
                val m = Regex("""^(\d+)\s*x?\s+(.+)$""").find(t)
                val (n, raw) = if (m != null && read.canonical(t) == null) m.groupValues[1].toInt() to m.groupValues[2] else 1 to t
                val name = read.canonical(raw) ?: throw IllegalArgumentException("$key: no card “$raw”" + (read.suggest(raw)?.let { " — did you mean “$it”?" } ?: ""))
                List(n) { name }
            }
            is JsonObject -> v.flatMap { (raw, n) ->
                val name = read.canonical(raw) ?: throw IllegalArgumentException("$key: no card “$raw”")
                List((n as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 1) { name }
            }
            else -> throw IllegalArgumentException("$key is a list like ['2 Called by the Grave'] or {card: copies}")
        }
        val out = list("out")
        val inn = list("in")
        require(out.isNotEmpty() || inn.isNotEmpty()) { "siding needs out and in, like out: ['2 Called by the Grave'], in: ['2 Droll & Lock Bird']" }
        val after = read.main.toMutableList()
        out.forEach { n -> require(after.remove(n)) { "out: there are not that many “$n” in the Main Deck" } }
        val side = read.entry.deck.side.map { read.name(it) }.toMutableList()
        val s = Study()
        inn.forEach { n -> if (!side.remove(n)) s.warn("in: “$n” is not in the Side Deck (counted anyway)") }
        after += inn
        if (after.size != read.main.size) s.warn("the deck goes from ${read.main.size} to ${after.size} cards")
        val goals = Instruments.conditionsOf(args, read)
        // A card brought in that no condition names counts toward nothing (the red team): said, so a flat answer is read
        // as the condition's, not the card's.
        val named = read.resolve(goals, warn = false).sets.flatten().toSet()
        inn.distinct().filter { it !in named }.forEach { s.warn("in: “$it” is in no group or card a condition names: it counts toward nothing here") }
        s.say("siding: ${read.entry.name} — out ${out.groupingBy { it }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}; in ${inn.groupingBy { it }.eachCount().entries.joinToString { "${it.value} ${it.key}" }}. What does it do to each condition?")
        s.say("  method: exact, before and after, going first and second")
        val rows = goals.map { g ->
            val before = read.resolve(listOf(g), read.main, warn = false)
            val afterR = read.resolve(listOf(g), after, warn = false)
            val b = listOf(FIRST, SECOND).map { HandCounter.of(read.main, before.sets).probability(before.goals[0], it) }
            val a = listOf(FIRST, SECOND).map { HandCounter.of(after, afterR.sets).probability(afterR.goals[0], it) }
            s.say("  ${g.text}: first ${pct(b[0])} → ${pct(a[0])} (${points(a[0] - b[0])}), second ${pct(b[1])} → ${pct(a[1])} (${points(a[1] - b[1])})")
            listOf(g.text, pct(b[0]), pct(a[0]), points(a[0] - b[0]), pct(b[1]), pct(a[1]), points(a[1] - b[1])) to Pair(b, a)
        }
        s.table("siding", "The side plan, before and after", listOf("Condition", "First before", "First after", "Change", "Second before", "Second after", "Change"),
            rows.map { it.first }, "Exact.")
        return s.done(JsonArray(rows.mapIndexed { i, (_, ba) ->
            obj("condition" to JsonPrimitive(goals[i].text), "beforeFirst" to JsonPrimitive(ba.first[0]), "afterFirst" to JsonPrimitive(ba.second[0]),
                "beforeSecond" to JsonPrimitive(ba.first[1]), "afterSecond" to JsonPrimitive(ba.second[1]))
        }))
    }

    private fun commas(n: Number): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
}
