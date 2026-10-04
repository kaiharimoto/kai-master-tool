package com.kaiharimoto.mastertool.core.world

import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.DeckEntry
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
    private val duels = mutableListOf<DuelGame>()
    private val catalog = DuelCatalog.cached { code -> host.cardById(code)?.let(DuelCardInfo::of) }

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
        "comb" -> num(Calc.choose(args.need("n"), args.need("k")))
        "hypergeo" -> num(Calc.hypergeo(args.need("N"), args.need("K"), args.need("n"), args.need("k")))
        "atLeast" -> num(Calc.atLeast(args.need("N"), args.need("K"), args.need("n"), args.need("k")))
        "atMost" -> num(Calc.atMost(args.need("N"), args.need("K"), args.need("n"), args.need("k")))
        "handOdds" -> num(handOdds(args))
        "stats" -> stats(args)
        "show" -> show(args)
        "duelNew" -> duelNew(args)
        "duelDo" -> duelDo(args)
        "duelBrief" -> JsonPrimitive(duel(args).let { g -> DuelBrief.describe(g.state, args.int("seat") ?: 0, catalog, g.header.seed) })
        "duelState" -> duelState(duel(args))
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

    private fun find(q: String): Card? = q.trim().toIntOrNull()?.let(host::cardById) ?: host.cardNamed(q)

    private fun handOdds(a: JsonObject): Double {
        val groups = (a["groups"] as? JsonObject ?: throw IllegalArgumentException("handOdds needs groups: {name: copies}"))
            .mapValues { (k, v) -> (v as? JsonPrimitive)?.doubleOrNull?.toInt() ?: throw IllegalArgumentException("groups.$k is a count") }
        val need = (a["need"] as? JsonArray ?: throw IllegalArgumentException("handOdds needs need: [{group, min, max}]")).map { e ->
            val o = e as? JsonObject ?: throw IllegalArgumentException("each need is {group, min, max}")
            val g = o.str("group") ?: throw IllegalArgumentException("each need names a group")
            HandConstraint(g, o.int("min") ?: 0, o.int("max") ?: 60)
        }
        val deck = a.int("deck") ?: throw IllegalArgumentException("handOdds needs deck: its size")
        return HandOdds.probability(groups, deck, a.int("hand") ?: 5, HandQuery(need))
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
            "histogram" -> WorldStats.histogram(xs, a.int("bins") ?: 10).let { (edges, counts) ->
                buildJsonObject {
                    put("edges", JsonArray(edges.map(::num)))
                    put("counts", JsonArray(counts.map { JsonPrimitive(it) }))
                }
            }
            "wilson" -> WorldStats.wilson(a.need("k").toInt(), a.need("n").toInt(), a.num("z") ?: 1.96).let { (lo, hi) ->
                JsonArray(listOf(num(lo), num(hi)))
            }
            "binomPmf" -> num(WorldStats.binomPmf(a.need("n").toInt(), a.need("k").toInt(), a.need("p")))
            "binomCdf" -> num(WorldStats.binomCdf(a.need("n").toInt(), a.need("k").toInt(), a.need("p")))
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
        (runCatching { (ShowSpec.obj(payload)["title"] as? JsonPrimitive)?.contentOrNull }.getOrNull()).orEmpty()
            .ifEmpty { k.name.lowercase().replaceFirstChar { it.uppercase() } }

    // ---- A duel table of the script's own: the real rules, headless, both seats the script's. -------------------

    private fun duelNew(a: JsonObject): JsonElement {
        require(duels.size < limits.duels) { "a run may open at most ${limits.duels} duel tables" }
        val solo = a["solo"]?.let { (it as? JsonPrimitive)?.contentOrNull == "true" } ?: (a.str("b") == null)
        fun seat(id: String?, label: String): SeatSetup {
            val d = (if (id == null || id == "open") host.deck(null) else host.deck(id))
                ?: throw IllegalArgumentException("no deck “${id ?: "open"}” for $label")
            return SeatSetup(name = d.name, main = d.deck.main.map { it.value }, extra = d.deck.extra.map { it.value }, deckId = d.id, deckName = d.name)
        }
        val seats = listOf(seat(a.str("a"), "seat 0"), if (solo) SeatSetup() else seat(a.str("b"), "seat 1"))
        val header = DuelHeader(id = "world-${duels.size}", seed = a.num("seed")?.toLong() ?: 1L, seats = seats, solo = solo, handSize = a.int("hand") ?: 5)
        duels += DuelGame.start(header)
        return JsonPrimitive(duels.size - 1)
    }

    private fun duel(a: JsonObject): DuelGame =
        duels.getOrNull(a.int("h") ?: -1) ?: throw IllegalArgumentException("no duel table ${a["h"]} — open one with ygo.duel.start")

    private fun duelDo(a: JsonObject): JsonElement {
        val h = a.int("h") ?: -1
        val game = duel(a)
        val seat = a.int("seat") ?: 0
        val line = a.str("line") ?: throw IllegalArgumentException("duel.do needs a line, like “draw” or “summon ash to m3”")
        return when (val p = DuelCommand.parse(line, game.state, seat, catalog, game.header.seed, anyCopy = true)) {
            is DuelCommand.Parsed.Actions -> {
                val r = game.act(p.actions, seat)
                if (r.problem != null) refused(r.problem) else {
                    duels[h] = r.game
                    done(p.said)
                }
            }
            is DuelCommand.Parsed.Many -> {
                var g = game
                for (part in p.parts) {
                    val r = g.act(part.actions, seat)
                    if (r.problem != null) return refused(r.problem)
                    g = r.game
                }
                duels[h] = g
                done(p.parts.joinToString("; ") { it.said })
            }
            is DuelCommand.Parsed.Problem -> refused(p.text + if (p.choices.isEmpty()) "" else " (did you mean ${p.choices.joinToString(", ")}?)")
            is DuelCommand.Parsed.Ruling -> refused("a ruling is not a move")
            else -> refused("“$line” asks the table something; ygo.duel takes moves — read the table with brief() or state()")
        }
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
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.int(key: String): Int? = num(key)?.toInt()
internal fun JsonObject.need(key: String): Double = num(key) ?: throw IllegalArgumentException("needs $key, a number")
