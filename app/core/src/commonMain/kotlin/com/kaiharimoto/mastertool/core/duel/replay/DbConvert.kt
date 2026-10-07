package com.kaiharimoto.mastertool.core.duel.replay

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelHeader
import com.kaiharimoto.mastertool.core.duel.DuelRecord
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.DuelSetup
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.SeatSetup
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A DuelingBook replay turned into our own duel log (1.1.51, kai: "the DuelingBook replays would need to be converted to
 * work with our player"), so it plays on the Duel page's table: stepped a gesture, a phase or a turn at a time, both ways,
 * branched into a what-if, kept in the duel's replays.
 *
 * DuelingBook's document numbers every card of a game: a player's Main Deck from their `start`, then their Extra Deck
 * (`main_total`, `extra_total`), the same number for the whole game however the card moves. Each play names the cards it
 * moves (`id`; `start_id` onto `end_id` for materials; `attacking_id`, `attacked_id`), a zone relative to the player who
 * made it ("M-3" theirs, "M2-3" the other player's, "S-1", "F-1"/"F-2", "Left EMZ"), and the card's face when it was
 * shown (`card.name`). So each DuelingBook card is one of our cards for the whole game ([Ids]), and each play the moves
 * of ours that put the table where DuelingBook's was — one gesture each, by the player who made it.
 *
 * What a replay never shows stays unknown: a Main Deck card never seen is a card with no face (code 0), never guessed.
 * The opening hands are read from what was played from them before it was drawn; the rest of each hand is cards never
 * seen. A play the table cannot hold (a card it lost track of, an attack outside the Battle Phase as it reads the turn) is
 * kept as a note in the log in DuelingBook's own words, never dropped, and counted.
 *
 * Read forgivingly, as [com.kaiharimoto.mastertool.core.ai.course.DbReplays] reads it: the document is DuelingBook's and
 * unpublished. Pure: the same replay and the same card names give the same games.
 */
object DbConvert {
    /** One game of the replay, as our log. [moves] plays drawn on the table, [notes] kept in words, [unseen] cards never shown. */
    data class Game(val n: Int, val record: DuelRecord, val moves: Int, val notes: Int, val unseen: Int)

    data class Result(val players: List<String>, val games: List<Game>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [raw] — DuelingBook's replay document as kept — as one [Game] a game; null when it is not a replay. [codeOf] gives a
     * card's passcode from its name (the app's card pool); a name it does not know falls back to the document's own
     * serial number, then to a card with no face.
     */
    fun convert(raw: String, title: String = "", codeOf: (String) -> Int?): Result? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        if (root.str("action").equals("Error", ignoreCase = true)) return null
        val players = listOf("player1", "player2").map { k -> (root[k] as? JsonObject)?.str("username").orEmpty() }
        if (players.all { it.isBlank() }) return null
        val plays = (root["plays"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // The games: each from the deck numbers it was dealt with — the document's own for the first, then those each
        // "Begin next duel" or "Back to RPS" carries — to the next of them.
        val segments = ArrayList<Pair<JsonObject, List<JsonObject>>>()
        var deal = root
        var current = ArrayList<JsonObject>()
        for (p in plays) {
            if (p.str("play").lowercase() in NEW_GAME) {
                segments += deal to current
                current = ArrayList()
                if ((p["player1"] as? JsonObject)?.get("start") != null) deal = p
                continue
            }
            current += p
        }
        segments += deal to current
        val games = segments.filter { (_, ps) -> ps.any { kindOf(it.str("play")) != Kind.TALK && kindOf(it.str("play")) != Kind.NOISE } }
            .mapIndexed { i, (d, ps) -> game(i + 1, d, ps, players, title, codeOf) }
        if (games.isEmpty()) return null
        return Result(players.filter { it.isNotBlank() }, games)
    }

    // ---- one game ---------------------------------------------------------------------------------------

    /** Where a card was before the game's first move of it, as far as that move tells. */
    private enum class Origin { DECK, HAND, EITHER }

    /** What a play is, for the converter. */
    private enum class Kind { TALK, NOISE, TABLE }

    /** DuelingBook's numbers for one player's cards in a game. */
    private class Range(val start: Int?, val main: Int, val extra: Int)

    /**
     * DuelingBook's card numbers as ours: a player's cards are dealt as [DuelSetup.initial] deals ours — the Main Deck
     * then the Extra Deck, from 1 + seat × [DuelState.SEAT_UIDS] — so the kth card of their numbers is our kth.
     * A document without the numbers gives each card the next of its player's as it first appears.
     */
    private class Ids(val ranges: List<Range>) {
        private val seen = HashMap<Int, Int>()
        private val next = IntArray(2)
        private val tokens = HashMap<Int, Int>()
        private var tokenNext = DuelState.TOKEN_UIDS
        val numbered: Boolean get() = ranges.all { it.start != null }

        fun seatOf(id: Int): Int? = ranges.indexOfFirst { r -> r.start != null && id >= r.start && id < r.start + r.main + r.extra }.takeIf { it >= 0 }

        fun extra(id: Int): Boolean {
            val s = seatOf(id) ?: return false
            val r = ranges[s]
            return id - r.start!! >= r.main
        }

        /** Ours for DuelingBook's [id]; [actor] stands in for its owner when the document has no numbers. */
        fun uid(id: Int, actor: Int?): Int? {
            tokens[id]?.let { return it }
            if (numbered) {
                val s = seatOf(id) ?: return null
                return 1 + s * DuelState.SEAT_UIDS + (id - ranges[s].start!!)
            }
            seen[id]?.let { return it }
            val s = actor ?: return null
            if (next[s] >= DuelState.SEAT_UIDS - 1) return null
            return (1 + s * DuelState.SEAT_UIDS + next[s]++).also { seen[id] = it }
        }

        fun token(id: Int): Int = tokens.getOrPut(id) { tokenNext++ }

        /** How many cards each seat ends up with when numbered as they appear. */
        fun counted(seat: Int): Int = next[seat]
    }

    private fun game(n: Int, deal: JsonObject, plays: List<JsonObject>, players: List<String>, title: String, codeOf: (String) -> Int?): Game {
        // Who is which seat: the document's player1 is seat 0, whatever a later game's own order says.
        val ranges = (0..1).map { s ->
            val obj = listOf("player1", "player2").map { deal[it] as? JsonObject }.firstOrNull { it?.str("username") == players[s] }
                ?: (deal[if (s == 0) "player1" else "player2"] as? JsonObject)
            Range(obj?.int("start"), obj?.int("main_total") ?: 0, obj?.int("extra_total") ?: 0)
        }
        val ids = Ids(ranges)
        fun seat(p: JsonObject): Int? = players.indexOf(p.str("username")).takeIf { it >= 0 && p.str("username").isNotBlank() }

        // What each card was shown to be, and where each Main Deck card was before it was first moved.
        val names = HashMap<Int, String>()
        val serials = HashMap<Int, Int>()
        val origins = LinkedHashMap<Int, Origin>()
        for (p in plays) {
            val actor = seat(p)
            val key = p.str("play").lowercase()
            if (kindOf(key) != Kind.TABLE) continue
            // A token is numbered apart from the decks, before anything else asks after its number.
            if (key == "summon token") { p.int("id")?.let(ids::token); continue }
            if (key == "remove token") continue
            val moved = p.int("id") ?: p.int("start_id")
            (p["card"] as? JsonObject)?.let { c ->
                val uid = moved?.let { ids.uid(it, actor) }
                if (uid != null) {
                    c.str("name").takeIf { it.isNotBlank() }?.let { names.getOrPut(uid) { it } }
                    c.int("serial_number")?.takeIf { it > 0 }?.let { serials.getOrPut(uid) { it } }
                }
            }
            if (moved != null) ids.uid(moved, actor)?.let { u -> if (u !in origins) origins[u] = origin(key, words(p)) }
            listOf("end_id", "attacking_id", "attacked_id").forEach { k ->
                p.int(k)?.let { id -> ids.uid(id, actor)?.let { u -> if (u !in origins) origins[u] = Origin.EITHER } }
            }
        }
        fun code(uid: Int): Int = names[uid]?.let(codeOf) ?: serials[uid] ?: 0

        val seats = (0..1).map { s ->
            val r = ranges[s]
            val total = if (ids.numbered) r.main + r.extra else ids.counted(s)
            val main = if (ids.numbered) r.main else total
            val uids = (0 until total).map { 1 + s * DuelState.SEAT_UIDS + it }
            SeatSetup(name = players[s], main = uids.take(main).map(::code), extra = uids.drop(main).map(::code))
        }
        val first = plays.firstOrNull { it.str("play").equals("Start turn", ignoreCase = true) }?.let(::seat)
            ?: plays.firstOrNull { kindOf(it.str("play").lowercase()) == Kind.TABLE }?.let(::seat) ?: 0
        val header = DuelHeader(id = "db-$n", seats = seats, first = first, handSize = HAND)

        // The deal: each opening hand is what was played from it before any draw, filled out with cards never shown.
        val entries = ArrayList<DuelEntry>()
        var state = DuelSetup.initial(header)
        var unseen = 0
        for (s in 0..1) {
            val mains = state.seats[s].deck.toSet()
            val sure = origins.filter { (u, o) -> u in mains && o == Origin.HAND }.keys.toList()
            val maybe = origins.filter { (u, o) -> u in mains && o == Origin.EITHER }.keys.toList()
            val never = state.seats[s].deck.filter { it !in origins }
            val hand = (sure + maybe + never).distinct().take(minOf(HAND, mains.size))
            unseen += state.seats[s].deck.count { it !in origins }
            for (u in hand) {
                val a = DuelAction.Move(u, Place.Pile(s, PileKind.HAND), how = "deal")
                val o = DuelRules.apply(state, a) as? Outcome.Ok ?: continue
                state = o.state
                entries += DuelEntry(entries.size, 0L, null, 0, a)
            }
        }

        // The plays, one gesture each.
        var group = 0
        var moves = 0
        var notes = 0
        var started = false
        for (p in plays) {
            val key = p.str("play").lowercase()
            val kind = kindOf(key)
            if (kind == Kind.NOISE) continue
            val actor = seat(p)
            val at = ((p["seconds"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).let { if (it > 0) (it * 1000).toLong() else 0L }
            if (kind == Kind.TALK) {
                val text = (p.str("message").ifBlank { p.str("text") }.ifBlank { p.str("msg") }).trim()
                if (text.isBlank()) continue
                val a = if (actor != null && key == "duel message") DuelAction.Chat(actor, text) else DuelAction.Note("${p.str("username").ifBlank { "A watcher" }}: $text")
                group++
                entries += DuelEntry(entries.size, at, actor, group, a)
                continue
            }
            val turn = Turn(started)
            val actions = actions(key, p, actor, state, ids, turn)
            started = turn.started
            if (actions == null) {
                // Nothing of the table's: kept in DuelingBook's words, when it said any.
                val said = words(p).ifBlank { continue }
                group++
                notes++
                entries += DuelEntry(entries.size, at, actor, group, DuelAction.Note(said, actor))
                continue
            }
            if (actions.isEmpty()) continue
            group++
            var drew = false
            var refused: String? = null
            for (a in actions) {
                when (val o = DuelRules.apply(state, a, actor)) {
                    is Outcome.Ok -> {
                        state = o.state
                        entries += DuelEntry(entries.size, at, actor, group, a)
                        drew = true
                    }
                    is Outcome.Refused -> refused = refused ?: o.reason
                }
            }
            if (drew) moves++
            if (refused != null) {
                notes++
                val said = words(p).ifBlank { p.str("play") }
                entries += DuelEntry(entries.size, at, actor, group, DuelAction.Note("$said (not on the table: $refused)", actor))
            }
        }
        val name = listOf(title.ifBlank { players.filter { it.isNotBlank() }.joinToString(" vs ") }, "game $n").joinToString(" · ")
        return Game(n, DuelRecord(header, entries, cursor = entries.size, name = name), moves, notes, unseen)
    }

    /** Whether the game's first turn has begun, carried from play to play. */
    private class Turn(var started: Boolean)

    // ---- one play -----------------------------------------------------------------------------------------

    /**
     * The moves of ours that do what play [key] did, on [s]; empty when it changes nothing on the table, null when it is
     * nothing the table draws (a declaration, an effect applied by hand) and is kept in words.
     */
    private fun actions(key: String, p: JsonObject, actor: Int?, s: DuelState, ids: Ids, turn: Turn): List<DuelAction>? {
        val seat = actor ?: return null
        fun uid(k: String = "id"): Int? = p.int(k)?.let { ids.uid(it, seat) }
        val id = uid() ?: uid("start_id")
        fun owner(u: Int): Int = s.cards[u]?.owner ?: seat
        fun onField(u: Int): Place.Zone? = s.placeOf(u) as? Place.Zone
        fun pile(u: Int, kind: PileKind, at: Int? = null, pos: CardPosition? = null, how: String) =
            DuelAction.Move(u, Place.Pile(owner(u), kind, at), pos, how)

        // The phases and the turn.
        PHASES[key]?.let { return listOf(DuelAction.Phase(it)) }
        when (key) {
            "start turn" -> {
                val begun = turn.started
                turn.started = true
                // The turn passes when DuelingBook's End turn was missed, or the turn player is not who we think.
                return if (begun && s.active != seat) listOf(DuelAction.EndTurn) else emptyList()
            }
            "end turn" -> return if (s.active == seat) listOf(DuelAction.EndTurn) else emptyList()
            "life points" -> {
                val life = p.int("life")
                return listOf(if (life != null) DuelAction.Lp(seat, set = life.coerceAtLeast(0)) else DuelAction.Lp(seat, p.int("amount") ?: return null))
            }
            "coin" -> {
                val r = p["result"] as? JsonPrimitive
                val heads = r?.booleanOrNull ?: r?.contentOrNull?.lowercase()?.let { it.startsWith("h") || it == "1" } ?: return null
                return listOf(DuelAction.Coin(seat, heads))
            }
            "die" -> return listOf(DuelAction.Dice(seat, p.int("result")?.takeIf { it in 1..6 } ?: return null))
            "shuffle deck" -> return listOf(DuelAction.Shuffle(seat, PileKind.DECK))
            "shuffle hand" -> return if (s.seats[seat].hand.size > 1) listOf(DuelAction.Shuffle(seat, PileKind.HAND)) else emptyList()
            "summon token" -> {
                val raw = p.int("id") ?: return null
                val zone = zoneFor(p.str("zone"), seat, ZoneKind.MONSTER, s, null) ?: return null
                return listOf(DuelAction.Token(seat, zone, CardPosition.FACE_UP_DEF, uid = ids.token(raw)))
            }
            "remove token" -> return listOf(DuelAction.Move(id ?: return null, Place.Void, how = "remove"))
            "attack", "attack directly" -> {
                val attacker = uid("attacking_id") ?: id ?: return null
                val target = if (key == "attack") uid("attacked_id") else null
                return listOf(DuelAction.Attack(seat, attacker, target))
            }
            "target card" -> return listOf(DuelAction.Target(seat, to = listOf(id ?: return null)))
            "add counter", "remove counter" -> {
                val u = id ?: return null
                val now = s.cards[u]?.counters?.get("") ?: 0
                val total = p.int("total") ?: (now + if (key == "add counter") 1 else -1)
                return if (total == now) emptyList() else listOf(DuelAction.Counter(u, total - now))
            }
            "overlay" -> {
                val top = uid("start_id") ?: return null
                val under = uid("end_id") ?: return null
                val zone = onField(under) ?: return null
                return listOf(DuelAction.Move(top, zone, how = "special", over = true))
            }
            "ol atk", "ol def", "ol atk from extra", "ol def from extra" -> {
                val top = uid("start_id") ?: return null
                val under = uid("end_id") ?: return null
                val zone = onField(under) ?: return null
                return listOf(DuelAction.Move(top, zone, if ("def" in key) CardPosition.FACE_UP_DEF else CardPosition.FACE_UP_ATK, "special", over = true))
            }
            "attach", "attach top card from deck 2" -> {
                val mat = uid("start_id") ?: return null
                val host = uid("end_id") ?: return null
                return listOf(DuelAction.Move(mat, Place.Under(host), how = "attach"))
            }
            "detach" -> return listOf(pile(id ?: return null, PileKind.GY, how = "detach"))
            "draw card" -> return listOf(pile(id ?: return null, PileKind.HAND, how = "draw"))
            "flip", "flip monster" -> return listOf(DuelAction.Position(id ?: return null, CardPosition.FACE_UP_DEF))
            "flip summon", "to atk" -> return listOf(DuelAction.Position(id ?: return null, CardPosition.FACE_UP_ATK))
            "to def" -> {
                val down = (p["face_down"] as? JsonPrimitive)?.booleanOrNull == true
                return listOf(DuelAction.Position(id ?: return null, if (down) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_UP_DEF))
            }
            "turn face-down" -> {
                val u = id ?: return null
                val z = onField(u) ?: return null
                return listOf(DuelAction.Position(u, if (z.kind == ZoneKind.MONSTER || z.kind == ZoneKind.EMZ) CardPosition.FACE_DOWN_DEF else CardPosition.FACE_DOWN_ATK))
            }
            "change control" -> {
                val u = id ?: return null
                val from = onField(u) ?: return null
                val to = 1 - (s.cards[u]?.controller ?: seat)
                val kind = if (from.kind == ZoneKind.EMZ) ZoneKind.MONSTER else from.kind
                val index = DIGIT.find(p.str("zone"))?.value?.toIntOrNull()?.minus(1)
                val zone = Place.Zone(to, kind, index ?: 0).takeIf { index != null && s.at(it) == null }
                    ?: s.freeZones(to, kind).firstOrNull() ?: return null
                return listOf(DuelAction.Move(u, zone, how = "control"))
            }
            "reveal", "reveal card from hand", "reveal card from deck", "reveal from extra" ->
                return listOf(DuelAction.Reveal(seat, listOf(id ?: return null)))
            "move" -> {
                val u = id ?: return null
                val z = p.str("zone")
                val kind = when {
                    z.startsWith("M") || "EMZ" in z || "Extra Monster" in z -> ZoneKind.MONSTER
                    z.startsWith("F") -> ZoneKind.FIELD
                    else -> ZoneKind.SPELL
                }
                val zone = zoneFor(z, seat, kind, s, u) ?: return null
                return listOf(DuelAction.Move(u, zone, how = "move"))
            }
        }
        val u = id ?: return null
        return when {
            key == "normal summon" -> listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.MONSTER, s, u)?.let { DuelAction.Move(u, it, CardPosition.FACE_UP_ATK, "normal") })
            key.startsWith("ss ") -> {
                val position = p.str("position").lowercase()
                val pos = when {
                    position == "set" || "fd" in position -> CardPosition.FACE_DOWN_DEF
                    "def" in key || position.startsWith("def") -> CardPosition.FACE_UP_DEF
                    else -> CardPosition.FACE_UP_ATK
                }
                listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.MONSTER, s, u)?.let { DuelAction.Move(u, it, pos, "special") })
            }
            key.startsWith("set monster to st") -> listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.SPELL, s, u)?.let { DuelAction.Move(u, it, CardPosition.FACE_DOWN_ATK, "set") })
            key.startsWith("set monster") -> listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.MONSTER, s, u)?.let { DuelAction.Move(u, it, CardPosition.FACE_DOWN_DEF, "set") })
            key.startsWith("set st") -> listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.SPELL, s, u)?.let { DuelAction.Move(u, it, CardPosition.FACE_DOWN_ATK, "set") })
            key.startsWith("to st") -> listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.SPELL, s, u)?.let { DuelAction.Move(u, it, CardPosition.FACE_UP_ATK, "place") })
            key.startsWith("activate st") || key.startsWith("activate spell") -> {
                val there = onField(u)
                when {
                    there != null && s.cards[u]?.faceUp == true -> emptyList()
                    there != null -> listOf(DuelAction.Position(u, CardPosition.FACE_UP_ATK))
                    else -> listOfNotNull(zoneFor(p.str("zone"), seat, ZoneKind.SPELL, s, u)?.let { DuelAction.Move(u, it, CardPosition.FACE_UP_ATK, "activate") })
                }
            }
            key.startsWith("activate field spell") || key.startsWith("set field spell") -> {
                val up = key.startsWith("activate")
                val side = if (key.endsWith(" 2") || "opponent" in key || p.str("zone") == "F-2") 1 - seat else seat
                val zone = Place.Zone(side, ZoneKind.FIELD, 0)
                val pos = if (up) CardPosition.FACE_UP_ATK else CardPosition.FACE_DOWN_ATK
                if (onField(u) == zone) {
                    if (s.cards[u]?.pos == pos) emptyList() else listOf(DuelAction.Position(u, pos))
                } else {
                    // A Field Spell that was there goes to the GY, as DuelingBook sends it.
                    listOfNotNull(s.at(zone)?.let { pile(it, PileKind.GY, how = "send") }, DuelAction.Move(u, zone, pos, if (up) "activate" else "set"))
                }
            }
            key.startsWith("activate pendulum") -> {
                val zone = Place.Zone(seat, ZoneKind.SPELL, if ("right" in key) 4 else 0)
                if (onField(u) == zone) emptyList() else listOf(DuelAction.Move(u, zone, CardPosition.FACE_UP_ATK, "activate"))
            }
            key.startsWith("to hand") -> listOf(pile(u, PileKind.HAND, how = "add"))
            key.startsWith("to gy") || key.startsWith("to grave") || key == "mill" -> listOf(pile(u, PileKind.GY, how = "send"))
            key.startsWith("banish") -> listOf(pile(u, PileKind.BANISHED, pos = if (FACE_DOWN.containsMatchIn(key)) CardPosition.FACE_DOWN_DEF else null, how = "banish"))
            key.startsWith("to t deck") -> listOf(pile(u, PileKind.DECK, Place.TOP, how = "return"))
            key.startsWith("to b deck") -> listOf(pile(u, PileKind.DECK, Place.BOTTOM, how = "return"))
            key.startsWith("to ed") || key.startsWith("to extra") -> {
                // A Main Deck card goes there face-up only, as a Pendulum Monster does.
                val dealtThere = ids.numbered && ids.extra(p.int("id") ?: p.int("start_id") ?: 0)
                val up = FACE_UP.containsMatchIn(key) || !dealtThere
                listOf(pile(u, PileKind.EXTRA, pos = if (up) CardPosition.FACE_UP_ATK else CardPosition.FACE_DOWN_DEF, how = "return"))
            }
            else -> null
        }
    }

    /**
     * The zone DuelingBook named — "M-3" the player's own, "M2-3" the other's, "S-1", "F-1"/"F-2", "Left EMZ" as the player
     * sees it — or, when it named none or that zone holds another card, the first free one of [kind] on the player's side.
     */
    private fun zoneFor(z: String, seat: Int, kind: ZoneKind, s: DuelState, uid: Int?): Place.Zone? {
        val named = parseZone(z, seat)
        if (named != null && (s.at(named) == null || s.at(named) == uid)) return named
        val side = named?.seat ?: seat
        val want = if (named?.kind == ZoneKind.EMZ) ZoneKind.MONSTER else named?.kind ?: kind
        // A card already in a zone of that kind on that side stays where it is rather than being moved along.
        uid?.let { u -> (s.placeOf(u) as? Place.Zone)?.takeIf { it.seat == side && it.kind == want } }?.let { return it }
        return s.freeZones(side, want).firstOrNull() ?: named
    }

    private fun parseZone(z: String, seat: Int): Place.Zone? {
        val t = z.trim()
        if (t.isEmpty()) return null
        val emz = t.contains("EMZ", ignoreCase = true) || t.contains("Extra Monster Zone", ignoreCase = true)
        if (emz) {
            // Left and right as the player sees them; ours are as seat 0 sees them.
            val left = t.startsWith("Left", ignoreCase = true)
            return Place.Zone(seat, ZoneKind.EMZ, if (left == (seat == 0)) 0 else 1)
        }
        val m = ZONE.matchEntire(t) ?: return null
        val other = m.groupValues[2] == "2"
        val n = m.groupValues[3].toInt()
        return when (m.groupValues[1].uppercase()) {
            "M" -> Place.Zone(if (other) 1 - seat else seat, ZoneKind.MONSTER, (n - 1).coerceIn(0, DuelState.ZONES - 1))
            "S" -> Place.Zone(if (other) 1 - seat else seat, ZoneKind.SPELL, (n - 1).coerceIn(0, DuelState.ZONES - 1))
            // F-1 is the player's own Field Zone, F-2 the other's.
            "F" -> Place.Zone(if (n == 2 || other) 1 - seat else seat, ZoneKind.FIELD, 0)
            else -> null
        }
    }

    /** Where the card a play first moves was before it, read off the play's name and, for a plain one, its words. */
    private fun origin(key: String, words: String): Origin {
        val w = words.lowercase()
        return when {
            "from hand" in key -> Origin.HAND
            key == "draw card" || key == "mill" || "deck" in key.substringAfter(" from ", "") || "top card" in key ||
                key == "to hand" || key == "to hand 2" -> Origin.DECK
            key in FROM_HAND -> Origin.HAND
            "from extra" in key || "from grave" in key || "from field" in key || "from banished" in key -> Origin.EITHER
            HAND_WORDS.containsMatchIn(w) -> Origin.HAND
            DECK_WORDS.containsMatchIn(w) -> Origin.DECK
            else -> Origin.EITHER
        }
    }

    private fun kindOf(play: String): Kind {
        val k = play.lowercase().trim()
        return when {
            k in TALK -> Kind.TALK
            k.isEmpty() || k in NOISE -> Kind.NOISE
            else -> Kind.TABLE
        }
    }

    /** What DuelingBook wrote of a play: its private words (the replay is over), else its public ones. */
    private fun words(p: JsonObject): String {
        val log = p["log"]
        val inLog = (log as? JsonObject)?.let { it.str("private_log").ifBlank { it.str("public_log") } }
            ?: (log as? JsonPrimitive)?.contentOrNull.orEmpty()
        return inLog.ifBlank { p.str("private_log").ifBlank { p.str("public_log") } }.replace('\n', ' ').trim()
    }

    /** Cards a hand is dealt in a DuelingBook game. */
    private const val HAND = 5

    private val NEW_GAME = setOf("begin next duel", "back to rps")
    private val TALK = setOf("duel message", "message", "watcher message")
    private val NOISE = setOf(
        "add watcher", "remove watcher", "countdown", "swap cards", "thinking", "permission event", "permission granted",
        "permission denied", "good", "stop good", "rps", "call admin", "cancel call", "left duel", "rejoin duel", "resume game",
        "show deck", "show hand", "show ed", "show extra deck", "pick first", "initialize cards", "ready", "done siding",
        "resume siding", "stop viewing", "view deck", "preview skillback",
    )
    private val PHASES = mapOf(
        "enter dp" to DuelPhase.DRAW, "enter sp" to DuelPhase.STANDBY, "enter m1" to DuelPhase.MAIN1,
        "enter bp" to DuelPhase.BATTLE, "enter m2" to DuelPhase.MAIN2, "enter ep" to DuelPhase.END,
    )
    /** Plays that only ever take a card from a hand. */
    private val FROM_HAND = setOf(
        "normal summon", "set monster", "set st", "activate st", "activate spell", "to st", "activate field spell",
        "set field spell", "activate pendulum left", "activate pendulum right", "set monster to st from hand",
    )
    private val HAND_WORDS = Regex("""\bfrom (?:the |their |his |her |your )?hand\b""")
    private val DECK_WORDS = Regex("""\bfrom (?:the |their |his |her |your )?deck\b|\btop (?:card )?of (?:the |their |his |her |your )?deck\b""")
    private val FACE_DOWN = Regex("""\bfd\b""")
    private val FACE_UP = Regex("""\bfu\b""")
    private val ZONE = Regex("""^([MSF])(2)?-([1-5])$""", RegexOption.IGNORE_CASE)
    private val DIGIT = Regex("""[1-5]$""")

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
}
