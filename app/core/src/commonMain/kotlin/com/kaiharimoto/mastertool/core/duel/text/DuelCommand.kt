package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelRules
import com.kaiharimoto.mastertool.core.duel.Outcome
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf

/**
 * The duel's command line — what DuelingBook's menus and Omega's slash commands are for, in the words
 * a player already says across a table: `ash to hand`, `summon droll to m3`, `set called by`, `mill 3`,
 * `lp -1000`, `lp opp /2`, `chain ash`, `bp`, `end`. A slash in front is allowed and ignored.
 *
 * Card names match loosely ("ash", "abjs", "called by") but only among cards the player acting can see —
 * their own hand, deck and Extra Deck, both fields, both graveyards and what is banished face-up — so
 * the line can never be used to find out where a hidden card is.
 */
object DuelCommand {

    sealed interface Parsed {
        data class Actions(val actions: List<DuelAction>, val said: String) : Parsed
        data class Problem(val text: String) : Parsed
    }

    /** One example per thing the line can do, for the help and the empty line's hint. */
    val EXAMPLES = listOf(
        "draw", "draw 2", "mill 3", "shuffle", "ash to hand", "summon droll to m3", "set called by", "activate pot",
        "chain ash", "banish ash", "gy ash", "ash to deck bottom", "attach ash to zeus", "lp -1000", "lp opp /2",
        "lp =4000", "bp", "m2", "ep", "next", "end", "coin", "dice", "token", "look 3", "excavate 3", "resolve",
        "think", "say ok?",
    )

    fun parse(text: String, s: DuelState, seat: Int, catalog: DuelCatalog): Parsed {
        val line = text.trim().removePrefix("/").trim()
        if (line.isEmpty()) return Parsed.Problem("Type a command, like “ash to hand”")
        val words = line.lowercase().split(Regex("\\s+"))
        val head = words.first()
        val rest = words.drop(1)
        val n = rest.firstOrNull()?.toIntOrNull()

        fun one(a: DuelAction, said: String) = Parsed.Actions(listOf(a), said)

        when (head) {
            "draw", "d", "dr" -> {
                val k = n ?: 1
                return one(DuelAction.Draw(seat, k), if (k == 1) "Draw" else "Draw $k")
            }
            "mill", "dump" -> {
                val k = (n ?: 1).coerceAtLeast(1)
                val top = s.seats[seat].deck.take(k)
                if (top.size < k) return Parsed.Problem("The deck holds only ${top.size}")
                return Parsed.Actions(top.map { DuelAction.Move(it, Place.Pile(seat, PileKind.GY), how = "send") }, "Mill $k")
            }
            "shuffle" -> {
                val pile = when (rest.firstOrNull()) {
                    "hand" -> PileKind.HAND
                    "extra", "ed" -> PileKind.EXTRA
                    else -> PileKind.DECK
                }
                return one(DuelAction.Shuffle(seat, pile), "Shuffle ${pile.label}")
            }
            "lp", "life" -> return lp(rest, s, seat)
            "dp", "draw-phase" -> return one(DuelAction.Phase(DuelPhase.DRAW), "Draw Phase")
            "sp", "standby" -> return one(DuelAction.Phase(DuelPhase.STANDBY), "Standby Phase")
            "m1", "mp1", "main1" -> return one(DuelAction.Phase(DuelPhase.MAIN1), "Main Phase 1")
            "bp", "battle" -> return one(DuelAction.Phase(DuelPhase.BATTLE), "Battle Phase")
            "m2", "mp2", "main2" -> return one(DuelAction.Phase(DuelPhase.MAIN2), "Main Phase 2")
            "ep" -> return one(DuelAction.Phase(DuelPhase.END), "End Phase")
            "next", "np" -> return one(DuelAction.Phase(s.phase.next()), s.phase.next().label)
            "end", "pass", "et" -> if (rest.isEmpty() || rest == listOf("turn")) return one(DuelAction.EndTurn, "End turn")
            "coin", "flip-coin" -> return one(DuelAction.Coin(seat), "Coin")
            "dice", "die", "roll" -> return one(DuelAction.Dice(seat), "Die")
            "resolve", "res" -> return one(DuelAction.ChainResolve, "Resolve")
            "think", "thinking", "wait" -> return one(DuelAction.Thinking(seat, true), "Thinking")
            "ready" -> return one(DuelAction.Thinking(seat, false), "Ready")
            "concede", "surrender" -> return one(DuelAction.Concede(seat), "Concede")
            "say", "chat" -> {
                val said = line.substringAfter(' ', "").trim()
                if (said.isEmpty()) return Parsed.Problem("Say what?")
                return one(DuelAction.Chat(seat, said), "Say")
            }
            "note" -> {
                val said = line.substringAfter(' ', "").trim()
                if (said.isEmpty()) return Parsed.Problem("Note what?")
                return one(DuelAction.Note(said, seat), "Note")
            }
            "look", "peek", "top", "excavate" -> {
                val k = (n ?: 1).coerceAtLeast(1)
                val top = s.seats[seat].deck.take(k)
                if (top.isEmpty()) return Parsed.Problem("The deck is empty")
                return one(DuelAction.Reveal(seat, top, to = if (head == "excavate") null else seat), "${head.replaceFirstChar { it.uppercase() }} $k")
            }
            "token", "tokens" -> {
                var args = rest
                val zoneWord = args.lastOrNull()?.let { zoneOf(it, seat) }
                if (zoneWord != null) args = args.dropLast(1)
                val count = args.firstOrNull()?.toIntOrNull()?.also { args = args.drop(1) } ?: 1
                val name = args.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifBlank { "Token" }
                val actions = mutableListOf<DuelAction>()
                var state = s
                repeat(count.coerceIn(1, 5)) {
                    val z = (if (count == 1) zoneWord else null) ?: DuelVerbs.nearestFree(state, seat, ZoneKind.MONSTER)
                        ?: return Parsed.Problem("No free Monster Zone")
                    val a = DuelAction.Token(seat, z, name = name)
                    actions += a
                    state = (DuelRules.apply(state, a) as? Outcome.Ok)?.state ?: return Parsed.Problem("That zone is taken")
                }
                return Parsed.Actions(actions, if (count > 1) "$count tokens" else "Token")
            }
            "clear" -> if (rest.firstOrNull() == "chain" || rest.isEmpty()) return one(DuelAction.ChainClear, "Clear the chain")
            // A chain link for a card where it stands (an effect on the field or in the GY), nothing moved.
            "link", "effect" -> {
                val q = rest.joinToString(" ").ifBlank { return Parsed.Problem("Which card's effect?") }
                val uid = find(q, s, seat, catalog, null, false) ?: return Parsed.Problem("No card you can see matches “$q”")
                return one(DuelAction.ChainAdd(seat, uid), "Effect: ${catalog.nameOf(s.cards.getValue(uid))}")
            }
        }
        return cardCommand(words, s, seat, catalog)
    }

    private fun lp(rest: List<String>, s: DuelState, seat: Int): Parsed {
        var target = seat
        var args = rest
        when (args.firstOrNull()) {
            "opp", "op", "them", "opponent", "b" -> { target = 1 - seat; args = args.drop(1) }
            "me", "my", "mine", "a" -> { target = seat; args = args.drop(1) }
        }
        val expr = args.joinToString("").ifEmpty { return Parsed.Problem("LP by how much? “lp -1000”") }
        val lp = s.seats[target].lp
        val action = when {
            expr.startsWith("/") -> expr.drop(1).toIntOrNull()?.takeIf { it > 0 }?.let { DuelAction.Lp(target, set = lp / it) }
            expr.startsWith("=") -> expr.drop(1).toIntOrNull()?.let { DuelAction.Lp(target, set = it) }
            expr.startsWith("+") -> expr.drop(1).toIntOrNull()?.let { DuelAction.Lp(target, delta = it) }
            expr.startsWith("-") -> expr.drop(1).toIntOrNull()?.let { DuelAction.Lp(target, delta = -it) }
            else -> expr.toIntOrNull()?.let { DuelAction.Lp(target, delta = -it) }
        } ?: return Parsed.Problem("“$expr” is not an LP change: try -1000, +500, =4000 or /2")
        return Parsed.Actions(listOf(action), "LP")
    }

    // ---- cards ---------------------------------------------------------------------------------------

    private val verbWords: Map<String, DuelVerb> = mapOf(
        "summon" to DuelVerb.SUMMON, "ns" to DuelVerb.SUMMON, "normal" to DuelVerb.SUMMON,
        "ss" to DuelVerb.SPECIAL, "special" to DuelVerb.SPECIAL,
        "set" to DuelVerb.SET, "mset" to DuelVerb.SET,
        "activate" to DuelVerb.ACTIVATE, "act" to DuelVerb.ACTIVATE, "chain" to DuelVerb.ACTIVATE, "use" to DuelVerb.ACTIVATE, "play" to DuelVerb.ACTIVATE,
        "flip" to DuelVerb.FLIP,
        "pos" to DuelVerb.POSITION, "position" to DuelVerb.POSITION, "def" to DuelVerb.POSITION, "atk" to DuelVerb.POSITION,
        "gy" to DuelVerb.GRAVE, "grave" to DuelVerb.GRAVE, "send" to DuelVerb.GRAVE, "discard" to DuelVerb.GRAVE,
        "destroy" to DuelVerb.GRAVE, "tribute" to DuelVerb.GRAVE, "kill" to DuelVerb.GRAVE,
        "banish" to DuelVerb.BANISH, "remove" to DuelVerb.BANISH, "bfd" to DuelVerb.BANISH_DOWN,
        "add" to DuelVerb.HAND, "search" to DuelVerb.HAND, "bounce" to DuelVerb.HAND, "hand" to DuelVerb.HAND,
        "spin" to DuelVerb.DECK_TOP, "top" to DuelVerb.DECK_TOP, "bottom" to DuelVerb.DECK_BOTTOM,
        "extra" to DuelVerb.EXTRA,
        "attach" to DuelVerb.ATTACH, "overlay" to DuelVerb.ATTACH, "detach" to DuelVerb.DETACH,
        "reveal" to DuelVerb.REVEAL, "show" to DuelVerb.REVEAL,
        "target" to DuelVerb.TARGET, "point" to DuelVerb.TARGET,
        "counter" to DuelVerb.COUNTER_UP, "uncounter" to DuelVerb.COUNTER_DOWN,
    )

    private val pileWords: Map<String, PileKind> = mapOf(
        "hand" to PileKind.HAND, "deck" to PileKind.DECK, "extra" to PileKind.EXTRA, "ed" to PileKind.EXTRA,
        "gy" to PileKind.GY, "grave" to PileKind.GY, "graveyard" to PileKind.GY,
        "banish" to PileKind.BANISHED, "banished" to PileKind.BANISHED, "removed" to PileKind.BANISHED, "exile" to PileKind.BANISHED,
    )

    private fun cardCommand(words: List<String>, s: DuelState, seat: Int, catalog: DuelCatalog): Parsed {
        var verb: DuelVerb? = verbWords[words.first()]
        var body = if (verb != null) words.drop(1) else words
        // "ash gy", "ash banish": a verb after the name.
        if (verb == null && body.size >= 2 && verbWords.containsKey(body.last()) && "to" !in body) {
            verb = verbWords[body.last()]
            body = body.dropLast(1)
        }
        var defense = words.first() == "def"

        // "… to <destination>"
        var dest: List<String> = emptyList()
        val toAt = body.lastIndexOf("to")
        if (toAt >= 0) {
            dest = body.drop(toAt + 1)
            body = body.take(toAt)
        }
        // "… from <pile>"
        var from: PileKind? = null
        var fromField = false
        val fromAt = body.lastIndexOf("from")
        if (fromAt >= 0) {
            val w = body.drop(fromAt + 1).joinToString(" ")
            from = pileWords[w]
            fromField = w == "field"
            if (from == null && !fromField) return Parsed.Problem("From where? “$w” is not a hand, deck, GY, banished or Extra Deck")
            body = body.take(fromAt)
        }
        // A trailing position word: "summon droll def".
        if (body.lastOrNull() == "def" || body.lastOrNull() == "defense") { defense = true; body = body.dropLast(1) }
        if (body.lastOrNull() == "atk" || body.lastOrNull() == "attack") body = body.dropLast(1)
        if (body.lastOrNull() in setOf("facedown", "fd") && verb == DuelVerb.BANISH) { verb = DuelVerb.BANISH_DOWN; body = body.dropLast(1) }

        val query = body.joinToString(" ").trim()
        if (query.isEmpty()) return Parsed.Problem("Which card?")
        val uid = find(query, s, seat, catalog, from, fromField)
            ?: return Parsed.Problem("No card you can see matches “$query”")
        val name = catalog.nameOf(s.cards.getValue(uid))

        var zone: Place.Zone? = null
        var host: Int? = null
        if (dest.isNotEmpty()) {
            val d = dest.joinToString(" ")
            val z = zoneOf(dest.joinToString(""), seat)
            when {
                z != null -> { zone = z; if (verb == null) verb = DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.SPECIAL }
                d == "deck" || d == "top" || d == "deck top" || d == "top of deck" -> if (verb == null || verb == DuelVerb.DEFAULT) verb = DuelVerb.DECK_TOP
                d == "bottom" || d == "deck bottom" || d == "bottom of deck" -> verb = DuelVerb.DECK_BOTTOM
                d == "field" -> if (verb == null) verb = DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.SPECIAL
                pileWords[d] != null -> verb = when (pileWords.getValue(d)) {
                    PileKind.HAND -> DuelVerb.HAND
                    PileKind.DECK -> DuelVerb.DECK_TOP
                    PileKind.EXTRA -> DuelVerb.EXTRA
                    PileKind.GY -> DuelVerb.GRAVE
                    PileKind.BANISHED -> if (verb == DuelVerb.BANISH_DOWN) DuelVerb.BANISH_DOWN else DuelVerb.BANISH
                }
                else -> {
                    host = find(d, s, seat, catalog, null, true)?.takeIf { s.placeOf(it) is Place.Zone }
                        ?: return Parsed.Problem("Where is “$d”? Try hand, gy, deck, m3, s2, emz, or a card on the field")
                    verb = DuelVerb.ATTACH
                }
            }
        }
        val v = verb ?: DuelVerb.DEFAULT
        val result = DuelVerbs.actions(s, seat, uid, v, catalog, zone, host)
        if (result.needsHost) return Parsed.Problem("Attach $name to which card? “attach $query to <card>”")
        result.problem?.let { return Parsed.Problem(it) }
        // "summon … def": the monster comes in face-up Defense.
        val actions = if (defense) result.actions.map {
            if (it is DuelAction.Move && it.pos == CardPosition.FACE_UP_ATK && it.to is Place.Zone) it.copy(pos = CardPosition.FACE_UP_DEF) else it
        } else result.actions
        return Parsed.Actions(actions, "${(if (v == DuelVerb.DEFAULT) DuelVerbs.default(s, seat, uid, catalog) else v).label}: $name")
    }

    private val placing = setOf(DuelVerb.SUMMON, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.ACTIVATE)

    /** "m3", "s2", "st2", "s/t2", "emz", "el", "er", "fz" — a zone on the acting seat's side. */
    fun zoneOf(word: String, seat: Int): Place.Zone? {
        val w = word.lowercase().replace("/", "").replace("-", "")
        Regex("^(m|mz|monster)([1-5])$").find(w)?.let { return Place.Zone(seat, ZoneKind.MONSTER, it.groupValues[2].toInt() - 1) }
        Regex("^(s|st|sz|spell|trap)([1-5])$").find(w)?.let { return Place.Zone(seat, ZoneKind.SPELL, it.groupValues[2].toInt() - 1) }
        Regex("^(p|pz|pendulum)([12])$").find(w)?.let { return Place.Zone(seat, ZoneKind.SPELL, if (it.groupValues[2] == "1") 0 else 4) }
        Regex("^emz([12])$").find(w)?.let { return Place.Zone(seat, ZoneKind.EMZ, it.groupValues[1].toInt() - 1) }
        return when (w) {
            "emz", "el", "emzl", "extramonster" -> Place.Zone(seat, ZoneKind.EMZ, 0)
            "er", "emzr" -> Place.Zone(seat, ZoneKind.EMZ, 1)
            "fz", "fieldzone", "fieldspell" -> Place.Zone(seat, ZoneKind.FIELD, 0)
            else -> null
        }
    }

    /**
     * The card [query] names among those [seat] can see, preferring (on a tie) the hand, then the
     * field, the graveyard, banishment, the Extra Deck and last the deck — the order a player reaches.
     */
    fun find(query: String, s: DuelState, seat: Int, catalog: DuelCatalog, from: PileKind?, fieldOnly: Boolean): Int? {
        // "#17": a card by its uid, as Ai is shown them — only one the seat may see (or its own deck's).
        Regex("^#(\\d+)$").find(query.trim())?.let { m ->
            val uid = m.groupValues[1].toInt()
            val own = s.cards[uid]?.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) }
            return uid.takeIf { it in s.cards && (DuelSight.sees(s, uid, seat) || own) }
        }
        val other = 1 - seat
        val mine = s.seats[seat]
        val theirs = s.seats[other]
        val order: List<Int> = when {
            fieldOnly -> s.onField().sortedBy { if (s.cards[it]?.controller == seat) 0 else 1 }
            from != null -> mine.pile(from) + (if (from == PileKind.GY || from == PileKind.BANISHED) theirs.pile(from) else emptyList())
            else -> mine.hand + s.onField().sortedBy { if (s.cards[it]?.controller == seat) 0 else 1 } +
                mine.gy + theirs.gy + mine.banished + theirs.banished + mine.extra + mine.deck +
                s.onField().flatMap { s.cards[it]?.under ?: emptyList() }
        }
        // The deck and Extra Deck are searchable by their owner, who knows the decklist; nothing else hidden is.
        val visible = order.filter { uid ->
            DuelSight.sees(s, uid, seat) || (s.cards[uid]?.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) })
        }
        var best: Int? = null
        var bestScore = 0
        visible.forEach { uid ->
            val card = s.cards[uid] ?: return@forEach
            val score = NameScore.of(query, catalog.nameOf(card))
            if (score > bestScore) { best = uid; bestScore = score }
        }
        return best
    }
}

/** How well a typed fragment names a card: exact beats a prefix beats word prefixes beats initials beats a substring. */
object NameScore {
    fun of(query: String, name: String): Int {
        val q = norm(query)
        val n = norm(name)
        if (q.isEmpty() || n.isEmpty()) return 0
        if (q == n) return 100
        if (n.startsWith(q)) return 80
        val qw = words(query)
        val nw = words(name)
        if (qw.isNotEmpty() && wordPrefixes(qw, nw)) return 70
        val initials = nw.joinToString("") { it.take(1) }
        if (q.length >= 2 && initials.startsWith(q)) return 60
        if (n.contains(q) && q.length >= 3) return 50
        return 0
    }

    /** Every typed word is the start of a name word, in order ("called by" → "Called by the Grave"). */
    private fun wordPrefixes(q: List<String>, n: List<String>): Boolean {
        var j = 0
        for (w in q) {
            while (j < n.size && !n[j].startsWith(w)) j++
            if (j == n.size) return false
            j++
        }
        return true
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
}
