package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.cards.BanlistHistory
import com.kaiharimoto.mastertool.core.cards.BanlistMatch
import com.kaiharimoto.mastertool.core.cards.LimitationList
import com.kaiharimoto.mastertool.core.cards.YugipediaLists
import com.kaiharimoto.mastertool.core.deck.DeckValidator
import com.kaiharimoto.mastertool.core.deck.Legality
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelFork
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ai.Combo
import com.kaiharimoto.mastertool.core.duel.ai.ComboRunner
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.ai.DuelMoves
import com.kaiharimoto.mastertool.core.duel.record.DuelResult
import com.kaiharimoto.mastertool.core.duel.record.DuelResults
import kotlin.random.Random
import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckEntry
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.mastertool.core.prep.TestGame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * What a world's scripts can ask of the app, through one door: [call], a name and a JSON object in, JSON out. The
 * JavaScript engine binds that door as one function and builds `ygo.*` over it in [WorldPrelude]; nothing else of the
 * app is in reach. Everything here only reads: the pool, the decks, the maths, a duel table of the script's own.
 *
 * What a script pins with `ygo.show` is checked here ([ShowSpec]) and collected in [shown] for the run to place.
 */
class WorldApi(private val host: WorldHost, private val limits: Limits = Limits()) {
    data class Limits(val shows: Int = 24, val duels: Int = 8, val search: Int = 200)

    /** A board a run asked for, checked; the world gives it an id and a place. */
    data class Shown(val id: String?, val title: String, val kind: BoardKind, val payload: String, val note: String)

    val shown = mutableListOf<Shown>()

    /** Where an instrument's terminal lines go: the running engine's own output, so they stream with the script's. */
    var print: (String) -> Unit = {}
    private val duels = mutableListOf<DuelGame>()
    /** The tables forked from the duel in play: the table's handle → that duel's id. */
    private val forkOf = HashMap<Int, String>()
    /** The tables whose end is already kept in [finished]. */
    private val ended = HashSet<Int>()

    /**
     * Each self-play table that finished in this run, as a result of its own kind (Phase C stage 3): the world keeps them
     * with the duel records, apart from games against people.
     */
    val finished = mutableListOf<DuelResult>()

    // A fork's cards its seat never saw are unknown cards (DuelFork): they read as such, never as a passcode.
    private val catalog = DuelCatalog.cached { code -> if (code == DuelFork.UNKNOWN) DuelFork.UNKNOWN_INFO else host.cardById(code)?.let(DuelCardInfo::of) }

    /** The answer to [name] with [args], or an [IllegalArgumentException] whose message is the script's error. */
    fun call(name: String, args: JsonObject): JsonElement = when (name) {
        "card" -> (args.str("q") ?: args.str("name") ?: args.int("id")?.toString())?.let(::find)?.let(::cardJson) ?: JsonNull
        "search" -> {
            val q = args.str("q").orEmpty()
            val limit = (args.int("limit") ?: 20).coerceIn(1, limits.search)
            JsonArray(host.search(q, limit).map(::cardJson))
        }
        "decks" -> JsonArray(host.decks().map { d ->
            buildJsonObject {
                put("id", d.id)
                put("name", d.name)
                put("main", d.deck.main.size)
                put("extra", d.deck.extra.size)
                put("side", d.deck.side.size)
            }
        })
        "deck" -> host.deck(args.str("id"))?.let(::deckJson) ?: JsonNull
        "banlist" -> banlist(args)
        "banStatus" -> banStatus(args)
        "legal" -> legal(args)
        "comb" -> num(Calc.choose(args.size("n", MAX_COMB), args.size("k", MAX_COMB)))
        "hypergeo" -> num(Calc.hypergeo(args.size("N"), args.size("K"), args.size("n"), args.size("k")))
        "atLeast" -> num(Calc.atLeast(args.size("N"), args.size("K"), args.size("n"), args.size("k")))
        "atMost" -> num(Calc.atMost(args.size("N"), args.size("K"), args.size("n"), args.size("k")))
        "handOdds" -> num(handOdds(args))
        "stats" -> stats(args)
        "show" -> show(args)
        "duelNew" -> duelNew(args)
        "duelDo" -> duelDo(args)
        "duelBrief" -> JsonPrimitive(duel(args).let { g -> DuelBrief.describe(g.state, args.int("seat") ?: 0, catalog, g.header.seed) })
        "duelState" -> duelState(duel(args))
        "duelMoves" -> duelMoves(args)
        "duelResult" -> duelResult(args)
        "tool" -> tool(args)
        "guide" -> JsonPrimitive(Instruments.GUIDE)
        "file" -> {
            val path = args.str("path")?.let(WorldPaths::safe) ?: throw IllegalArgumentException("use needs a path inside the world, like 'lib/brick_rate.js'")
            host.file(path)?.let(::JsonPrimitive) ?: throw IllegalArgumentException("there is no $path in this world: world_write it first (world_state lists the files)")
        }
        "tools" -> JsonArray(Instruments.ALL.map { t ->
            buildJsonObject {
                put("name", t.name)
                put("summary", t.summary)
                put("args", t.args)
                put("short", t.short)
            }
        })
        else -> throw IllegalArgumentException("ygo has no “$name”")
    }

    /**
     * What a Python run reads beside `ygo.py` ([WorldPrelude.PY_DATA]): Python cannot call back into the app, so the
     * open deck and every library deck are written out whole before it runs, up to [maxDecks].
     */
    fun pythonData(maxDecks: Int = 40): String {
        val open = host.deck(null)
        val all = (listOfNotNull(open) + host.decks()).distinctBy { it.id }.take(maxDecks)
        return buildJsonObject {
            open?.let { put("open", it.id) }
            put("decks_list", call("decks", JsonObject(emptyMap())))
            put("decks", buildJsonObject { all.forEach { put(it.id, deckJson(it)) } })
        }.toString()
    }

    /** An instrument ([Instruments]) run by name: its lines printed, its boards pinned, its answer returned. */
    fun tool(args: JsonObject): JsonElement {
        val name = args.str("name") ?: throw IllegalArgumentException("tool needs a name: ${Instruments.ALL.joinToString { it.name }}")
        val given = args["args"] as? JsonObject ?: JsonObject(emptyMap())
        val r = Instruments.run(name, given, host)
        r.lines.forEach(print)
        r.boards.forEach { b ->
            require(shown.size < limits.shows) { "a run may show at most ${limits.shows} boards" }
            shown += b
        }
        return r.answer
    }

    private fun find(q: String): Card? = q.trim().toIntOrNull()?.let(host::cardById) ?: host.cardNamed(q)

    // ---- The Forbidden & Limited lists by date (1.1.1): what the app keeps from Yugipedia, never fetched here. ----

    private val matches = HashMap<String, BanlistMatch>()

    private fun regionOf(a: JsonObject): Format = when (val r = a.str("region")?.trim()?.lowercase()) {
        null, "" -> host.format()
        "tcg" -> Format.TCG
        "ocg" -> Format.OCG
        else -> throw IllegalArgumentException("region is 'tcg' or 'ocg' (it was “$r”)")
    }

    private fun history(region: Format): BanlistHistory =
        host.banlists(region)?.takeIf { !it.isEmpty }
            ?: throw IllegalArgumentException("no ${region.name} banlists are kept yet: the app reads them from Yugipedia in the background — run again in a minute")

    /** The list in force on the args' date (today's when none), or null before the first. */
    private fun listOn(a: JsonObject, h: BanlistHistory): LimitationList? {
        val date = a.str("date")?.trim()?.takeIf { it.isNotEmpty() } ?: host.today() ?: return h.latest
        require(Legality.isDate(date)) { "a date is yyyy-MM-dd, like '2025-05-01' (it was “$date”)" }
        return h.asOf(date)
    }

    private fun matched(list: LimitationList): BanlistMatch =
        matches.getOrPut(list.region.name + "/" + list.title) { list.match { name -> host.cardNamed(name) } }

    private fun banlist(a: JsonObject): JsonElement {
        val list = listOn(a, history(regionOf(a))) ?: return JsonNull
        fun names(s: BanStatus) = JsonArray(list.at(s).map(::JsonPrimitive))
        return buildJsonObject {
            put("title", list.title)
            put("region", list.region.name.lowercase())
            put("start", list.start)
            put("end", list.end?.let(::JsonPrimitive) ?: JsonNull)
            put("forbidden", names(BanStatus.FORBIDDEN))
            put("limited", names(BanStatus.LIMITED))
            put("semiLimited", names(BanStatus.SEMI_LIMITED))
            put("offList", names(BanStatus.UNLIMITED))
            put("unmatched", JsonArray(matched(list).unmatched.map(::JsonPrimitive)))
            put("source", Yugipedia.ATTRIBUTION)
            put("url", YugipediaLists.pageUrl(list.title))
        }
    }

    private fun banStatus(a: JsonObject): JsonElement {
        val h = history(regionOf(a))
        val title = a.str("title") ?: throw IllegalArgumentException("status needs the list's title")
        val list = h.named(title) ?: throw IllegalArgumentException("no list “$title” is kept")
        val name = a.str("name") ?: throw IllegalArgumentException("status needs a card's name")
        val card = find(name)
        val s = if (card != null) matched(list).statusOf(card) else list.statusOf(name)
        return JsonPrimitive(s.name.lowercase())
    }

    private fun legal(a: JsonObject): JsonElement {
        val region = regionOf(a)
        val d = host.deck(a.str("id")) ?: throw IllegalArgumentException(if (a.str("id") == null) "no deck is open: give legal a deck from ygo.decks()" else "no deck “${a.str("id")}”: ygo.decks() lists them")
        val h = history(region)
        val list = listOn(a, h) ?: throw IllegalArgumentException("no ${region.name} list was in force then: the first kept is ${h.lists.first().title}, from ${h.lists.first().start}")
        val day = a.str("date")?.trim()?.takeIf { it.isNotEmpty() } ?: host.today()
        val v = DeckValidator.validate(d.deck, { host.cardById(it.value) }, region, asOf = day, limits = matched(list))
        return buildJsonObject {
            put("deck", d.name)
            put("legal", v.isLegal)
            put("list", list.title)
            day?.let { put("date", it) }
            put("issues", JsonArray(v.issues.map { i ->
                buildJsonObject {
                    put("severity", i.severity.name.lowercase())
                    put("message", i.message)
                }
            }))
            put("source", Yugipedia.ATTRIBUTION)
        }
    }

    private fun handOdds(a: JsonObject): Double {
        val groups = (a["groups"] as? JsonObject ?: throw IllegalArgumentException("handOdds needs groups: {name: copies}"))
            .mapValues { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..MAX_DECK.toDouble() }?.toInt() ?: throw IllegalArgumentException("groups.$k is a count, 0 to $MAX_DECK") }
        require(groups.size <= MAX_GROUPS) { "handOdds takes at most $MAX_GROUPS groups" }
        val need = (a["need"] as? JsonArray ?: throw IllegalArgumentException("handOdds needs need: [{group, min, max}]")).map { e ->
            val o = e as? JsonObject ?: throw IllegalArgumentException("each need is {group, min, max}")
            val g = o.str("group") ?: throw IllegalArgumentException("each need names a group")
            HandConstraint(g, o.int("min") ?: 0, o.int("max") ?: 60)
        }
        val deck = a.size("deck", MAX_DECK).toInt()
        val hand = if (a["hand"] == null) 5 else a.size("hand", MAX_HAND).toInt()
        // HandOdds walks every count of every constrained group: bound the walk before it starts.
        val walk = need.groupBy { it.groupKey }.entries.fold(1.0) { acc, (g, cs) ->
            val low = cs.maxOf { it.min }.coerceAtLeast(0)
            val high = minOf(cs.minOf { it.max }, groups[g] ?: 0, hand)
            acc * (high - low + 1).coerceAtLeast(1)
        }
        require(walk <= MAX_WALK) { "handOdds would weigh ${walk.toLong()} hands' shapes: ask about fewer groups, or narrower counts" }
        return HandOdds.probability(groups, deck, hand, HandQuery(need))
    }

    private fun stats(a: JsonObject): JsonElement {
        val xs = (a["xs"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("xs are numbers") }
        val ys = (a["ys"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.doubleOrNull ?: throw IllegalArgumentException("ys are numbers") }
        require(xs.size <= MAX_SAMPLE && ys.size <= MAX_SAMPLE) { "at most $MAX_SAMPLE numbers at once" }
        return when (val f = a.str("f")) {
            "mean" -> num(WorldStats.mean(xs))
            "sd" -> num(WorldStats.sd(xs))
            "variance" -> num(WorldStats.variance(xs))
            "median" -> num(WorldStats.median(xs))
            "quantile" -> num(WorldStats.quantile(xs, a.need("q")))
            "correlation" -> num(WorldStats.correlation(xs, ys))
            "histogram" -> WorldStats.histogram(xs, if (a["bins"] == null) 10 else a.size("bins", MAX_BINS).toInt().coerceAtLeast(1)).let { (edges, counts) ->
                buildJsonObject {
                    put("edges", JsonArray(edges.map(::num)))
                    put("counts", JsonArray(counts.map { JsonPrimitive(it) }))
                }
            }
            "wilson" -> WorldStats.wilson(a.size("k", MAX_TRIALS).toInt(), a.size("n", MAX_TRIALS).toInt(), a.num("z") ?: 1.96).let { (lo, hi) ->
                JsonArray(listOf(num(lo), num(hi)))
            }
            "binomPmf" -> num(WorldStats.binomPmf(a.size("n", MAX_BINOM).toInt(), a.size("k", MAX_BINOM).toInt(), a.need("p")))
            "binomCdf" -> num(WorldStats.binomCdf(a.size("n", MAX_BINOM).toInt(), a.size("k", MAX_BINOM).toInt(), a.need("p")))
            "normalCdf" -> num(WorldStats.normalCdf(a.need("z")))
            "chiSquare" -> WorldStats.chiSquare(xs, ys).let { c ->
                buildJsonObject {
                    put("statistic", c.statistic)
                    put("df", c.df)
                    put("p", c.p)
                }
            }
            else -> throw IllegalArgumentException("ygo.stats has no “$f”")
        }
    }

    private fun show(a: JsonObject): JsonElement {
        require(shown.size < limits.shows) { "a run may show at most ${limits.shows} boards" }
        val kind = a.str("kind") ?: throw IllegalArgumentException("show needs a kind")
        val body = when (val b = a["body"]) {
            null, JsonNull -> throw IllegalArgumentException("show needs a body")
            is JsonPrimitive -> b.contentOrNull.orEmpty()
            else -> b.toString()
        }
        val (k, payload) = ShowSpec.parse(kind, body).getOrElse { throw IllegalArgumentException("show($kind): ${it.message}") }
        val id = a.str("id")?.let { raw -> raw.filter { it.isLetterOrDigit() || it in "-_" }.take(40).ifEmpty { null } }
        val title = a.str("title") ?: titleOf(k, payload)
        shown += Shown(id, title.take(120), k, payload, a.str("note").orEmpty().take(400))
        return JsonPrimitive(id ?: "#${shown.size}")
    }

    private fun titleOf(k: BoardKind, payload: String): String =
        // A chart's own title, else a stat's label: what the board shows, never only its kind.
        runCatching { ShowSpec.obj(payload).let { o -> ShowSpec.str(o, "title").ifEmpty { ShowSpec.str(o, "label") } } }.getOrNull().orEmpty()
            .ifEmpty { k.name.lowercase().replaceFirstChar { it.uppercase() } }

    // ---- A duel table of the script's own: the real rules, headless, both seats the script's. -------------------
    //
    // Self-play (Phase C stage 3, `docs/phases/C.md` §6): a table takes a seed (a fresh one when none is given, always
    // returned — the tables were all seed 1 before, every game the same deal), who goes first, or a fork of the duel in
    // play as the seat Ai would hold sees it (DuelFork). Moves go through the line Ai plays kai with (ComboRunner.plan),
    // each Ai's own (provenance `ai` on both seats), and a table that ends becomes a self-play result ([finished]).

    private fun duelNew(a: JsonObject): JsonElement {
        require(duels.size < limits.duels) { "a run may open at most ${limits.duels} duel tables" }
        val seed = a.num("seed")?.toLong() ?: Random.nextInt(1, Int.MAX_VALUE).toLong()
        val id = "world-${host.now()}-${duels.size}"
        val fork = (a["fork"] as? JsonPrimitive)?.contentOrNull == "true"
        val game = if (fork) {
            val src = host.liveDuel() ?: throw IllegalArgumentException(
                "there is no duel in play to fork: one is started on the Duel page (a networked table is the two players', never forked)",
            )
            forkOf[duels.size] = src.header.id
            DuelFork.table(src, seed, id)
        } else {
            val solo = a["solo"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: (a.str("b") == null)
            val first = a.int("first") ?: 0
            require(first == 0 || first == 1) { "first is the seat that has turn 1: 0 or 1" }
            require(!solo || first == 0) { "a one-player table is seat 0's" }
            fun seat(id: String?, label: String): SeatSetup {
                val d = (if (id == null || id == "open") host.deck(null) else host.deck(id))
                    ?: throw IllegalArgumentException("no deck “${id ?: "open"}” for $label")
                return SeatSetup(name = d.name, main = d.deck.main.map { it.value }, extra = d.deck.extra.map { it.value }, deckId = d.id, deckName = d.name)
            }
            val seats = listOf(seat(a.str("a"), "seat 0"), if (solo) SeatSetup() else seat(a.str("b"), "seat 1"))
            val header = DuelHeader(id = id, seed = seed, seats = seats, first = first, solo = solo, handSize = if (a["hand"] == null) 5 else a.size("hand", MAX_HAND).toInt(), created = host.now())
            DuelGame.start(header)
        }
        duels += game
        return buildJsonObject {
            put("h", duels.size - 1)
            put("seed", seed)
            put("first", game.header.first)
            put("active", game.state.active)
            forkOf[duels.size - 1]?.let { put("forkOf", it) }
        }
    }

    private fun duel(a: JsonObject): DuelGame =
        duels.getOrNull(a.int("h") ?: -1) ?: throw IllegalArgumentException("no duel table ${a["h"]} — open one with ygo.duel.start")

    private fun seatOf(a: JsonObject): Int = (a.int("seat") ?: 0).also { require(it == 0 || it == 1) { "seat is 0 or 1" } }

    private fun duelDo(a: JsonObject): JsonElement {
        val h = a.int("h") ?: -1
        val game = duel(a)
        val seat = seatOf(a)
        val line = a.str("line") ?: throw IllegalArgumentException("duel.do needs a line, like “draw” or “summon ash to m3”")
        if (DuelResults.ending(game.state) != null) return refused("the duel is over: duel.result() says how it ended")
        // The line Ai plays kai with: planned whole on the table first, then each step made as Ai's own move for that seat.
        val plan = ComboRunner.plan(game.state, seat, listOf(line), catalog, game.header.seed)
        if (!plan.ok) {
            val why = plan.problem.orEmpty().substringAfter("): ", plan.problem.orEmpty())
            return refused(if ("a question or the table's chrome" in why) "“$line” asks the table something; ygo.duel takes moves — read the table with brief() or state()" else why)
        }
        val by = Provenance(Provenance.AI, aiSeat = seat, aiKnows = DuelBrief.FULL)
        var g = game
        for ((_, actions) in plan.steps) {
            val r = g.act(actions, seat, at = host.now(), by = by)
            if (r.problem != null) return refused(r.problem)
            g = r.game
        }
        duels[h] = g
        val result = noteEnd(h, g)
        return buildJsonObject {
            put("ok", true)
            put("said", plan.steps.joinToString("; ") { it.first })
            result?.let { put("ended", resultJson(it)) }
        }
    }

    /** A table that has just ended, kept once as a self-play result. */
    private fun noteEnd(h: Int, g: DuelGame): DuelResult? {
        if (h in ended || g.state.solo) return null
        val r = DuelResults.selfPlay(g, host.now(), "${g.header.id}-s${g.header.seed}", forkOf[h]) ?: return null
        ended += h
        finished += r
        return r
    }

    private fun duelResult(a: JsonObject): JsonElement {
        val h = a.int("h") ?: -1
        duel(a)
        return finished.firstOrNull { it.duel == duels[h].header.id }?.let(::resultJson) ?: JsonNull
    }

    private fun resultJson(r: DuelResult): JsonElement = buildJsonObject {
        put("winner", r.winner?.let(::JsonPrimitive) ?: JsonNull)
        put("how", r.how)
        put("turns", r.turns)
        put("first", r.first)
        put("kind", r.kind)
        put("seed", r.seed)
    }

    /** The moves [seat] may make now (`DuelMoves`, the menu Ai reads at kai's table): each line exactly as `do` takes it. */
    private fun duelMoves(a: JsonObject): JsonElement {
        val g = duel(a)
        val seat = seatOf(a)
        return JsonArray(DuelMoves.menu(g.state, seat, catalog, g.header.seed).flatMap { group ->
            group.moves.map { m ->
                buildJsonObject {
                    put("line", m.line)
                    put("what", m.what)
                    put("group", group.title)
                }
            }
        })
    }

    private fun done(said: String) = buildJsonObject {
        put("ok", true)
        put("said", said)
    }

    private fun refused(why: String) = buildJsonObject {
        put("ok", false)
        put("problem", why)
    }

    private fun duelState(g: DuelGame): JsonElement {
        val s = g.state
        fun names(uids: List<Int?>) = JsonArray(uids.map { u -> u?.let { s.card(it) }?.let { c -> JsonPrimitive(host.cardById(c.code)?.name ?: "#${c.code}") } ?: JsonNull })
        return buildJsonObject {
            put("turn", s.turn)
            put("active", s.active)
            put("phase", s.phase.name.lowercase())
            put("chain", s.chain.size)
            put("seats", JsonArray(s.seats.map { seat ->
                buildJsonObject {
                    put("lp", seat.lp)
                    put("hand", names(seat.hand))
                    put("deck", seat.deck.size)
                    put("extra", seat.extra.size)
                    put("gy", names(seat.gy))
                    put("banished", names(seat.banished))
                    put("monsters", names(seat.monsters))
                    put("spells", names(seat.spells))
                    put("field", names(listOf(seat.field)).first())
                }
            }))
        }
    }

    // ---- JSON shapes --------------------------------------------------------------------------------------------

    private fun cardJson(c: Card): JsonElement = buildJsonObject {
        put("id", c.id.value)
        put("name", c.name)
        put("type", c.type)
        put("frame", c.frameType)
        put("text", c.description)
        c.race?.let { put("race", it) }
        put("attribute", c.attribute.name.lowercase())
        c.atk?.let { put("atk", it) }
        c.def?.let { put("def", it) }
        c.level?.let { put("level", it) }
        c.linkValue?.let { put("link", it) }
        if (c.linkMarkers.isNotEmpty()) put("arrows", JsonArray(c.linkMarkers.map(::JsonPrimitive)))
        c.pendulumScale?.let { put("scale", it) }
        c.archetype?.let { put("archetype", it) }
        put("tcg", c.tcgBanStatus.name.lowercase())
        put("ocg", c.ocgBanStatus.name.lowercase())
        put("extraDeck", c.isExtraDeck)
        put("category", c.category.name.lowercase())
    }

    private fun deckJson(d: DeckEntry): JsonElement {
        val cards = (d.deck.main + d.deck.extra + d.deck.side).distinct().mapNotNull { host.cardById(it.value) }
        val byId = cards.associateBy { it.id }
        fun names(ids: List<CardId>) = JsonArray(ids.map { JsonPrimitive(byId[it]?.name ?: "#${it.value}") })
        val groups = host.groups(d.id)
        return buildJsonObject {
            put("id", d.id)
            put("name", d.name)
            put("main", names(d.deck.main))
            put("extra", names(d.deck.extra))
            put("side", names(d.deck.side))
            put("ids", buildJsonObject {
                put("main", JsonArray(d.deck.main.map { JsonPrimitive(it.value) }))
                put("extra", JsonArray(d.deck.extra.map { JsonPrimitive(it.value) }))
                put("side", JsonArray(d.deck.side.map { JsonPrimitive(it.value) }))
            })
            put("groups", buildJsonObject {
                groups.forEach { (g, ids) -> put(g, JsonArray(ids.map { JsonPrimitive(byId[CardId(it)]?.name ?: "#$it") })) }
            })
            put("cards", buildJsonObject { cards.forEach { put(it.name, cardJson(it)) } })
        }
    }

    private fun num(d: Double): JsonElement = if (d.isFinite()) JsonPrimitive(d) else JsonNull

    companion object {
        const val MAX_SAMPLE = 1_000_000

        /** A deck's scale, for every count a host loop walks: a deck, its copies, a hand, a draw. */
        const val MAX_DECK = 10_000
        const val MAX_COMB = 100_000
        const val MAX_HAND = 60
        const val MAX_GROUPS = 16
        const val MAX_WALK = 2_000_000.0
        const val MAX_BINS = 1_000
        const val MAX_BINOM = 1_000_000
        const val MAX_TRIALS = 1_000_000_000
    }
}

/** The app behind a world's scripts, read-only: the pool and the library, as the app holds them now. */
interface WorldHost {
    fun cardById(id: Int): Card?
    fun cardNamed(name: String): Card?
    fun search(query: String, limit: Int): List<Card>

    /** The deck with [id], or the builder's open deck for null. */
    fun deck(id: String?): DeckEntry?
    fun decks(): List<DeckEntry>

    /** A deck's groups: each group's name and the passcodes in it. */
    fun groups(deckId: String): Map<String, List<Int>> = emptyMap()

    /** The practice games logged in Prep, for the matchups instrument. */
    fun games(): List<TestGame> = emptyList()

    /** A deck's saved combos (Duel's Save as combo, Ai's duel_combo), for the combos instrument (1.0.97). */
    fun combos(deckId: String): List<Combo> = emptyList()

    /**
     * The field to expect with [deckId]: each opponent's key, as the games log names it, and its share in percent —
     * the active event's web (Prep), for the matchups instrument (1.0.97). Empty when no event says.
     */
    fun field(deckId: String?): Map<String, Int> = emptyMap()

    /** A file of the open world by its path under `files/` (`lib/x.js`), for `ygo.use`; null when there is none (1.0.97). */
    fun file(path: String): String? = null

    /** The Forbidden & Limited lists the app keeps for [region] (`<data>/banlists`), for `ygo.banlist`; null when none yet (1.1.1). */
    fun banlists(region: Format): BanlistHistory? = null

    /** The app's format: whose list `ygo.banlist` and `ygo.legal` read when a script names none (1.1.1). */
    fun format(): Format = Format.TCG

    /** Today, `yyyy-MM-dd`, for a script that names no day; null reads the newest list kept (1.1.1). */
    fun today(): String? = null

    /** The time now, in ms: when a self-play table began and ended (Phase C stage 3). */
    fun now(): Long = 0L

    /**
     * The duel in play as the seat Ai would hold reads it, for `ygo.duel.fork` (Phase C stage 3): that seat's view and
     * its own decklist, never the live table itself; null when no duel is in play, or it is a networked table.
     */
    fun liveDuel(): DuelFork.Source? = null
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.int(key: String): Int? = num(key)?.toInt()
internal fun JsonObject.need(key: String): Double = num(key) ?: throw IllegalArgumentException("needs $key, a number")

/**
 * A count a host loop will walk (1.0.97, the red team): a whole number from 0 to [max]. Rhino's budget cannot stop a
 * loop in Kotlin, so a script's numbers are held to a deck's scale here, at the door, before any loop sees them.
 */
internal fun JsonObject.size(key: String, max: Int = WorldApi.MAX_DECK): Double {
    val v = need(key)
    if (!v.isFinite() || v < 0 || v > max || v != kotlin.math.floor(v)) throw IllegalArgumentException("$key must be a whole number from 0 to $max (it was $v)")
    return v
}
