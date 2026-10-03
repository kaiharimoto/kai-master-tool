package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.CardKind
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
        /** A house ruling to keep (1.0.79): "ruling e4: no free zone, can't activate". Not an action on the table. */
        data class Ruling(val code: Int?, val card: String?, val text: String) : Parsed
    }

    /**
     * What a command wants of the card it names (1.0.79), so the lookup reaches where a player would:
     * "e4 to hand" means the Deck's copy before the GY's, "summon droll" the hand's.
     */
    enum class Want { ANY, HAND, PLAY, AWAY, TARGET }

    /** A name looked up: one card, none, or several different cards it could mean. */
    sealed interface Lookup {
        data class One(val uid: Int) : Lookup
        data class None(val why: String) : Lookup
        data class Many(val names: List<String>) : Lookup
    }

    /** One example per thing the line can do, for the help and the empty line's hint. */
    val EXAMPLES = listOf(
        "draw", "draw 2", "mill 3", "shuffle", "ash to hand", "summon droll to m3", "set called by", "activate pot",
        "chain ash", "banish ash", "gy ash", "ash to deck bottom", "attach ash to zeus", "lp -1000", "lp opp /2",
        "lp =4000", "bp", "m2", "ep", "next", "end", "coin", "dice", "token", "look 3", "excavate 3", "resolve",
        "think", "say ok?", "place angelechy in s2", "move zeus to m4", "token atk 6500 def 8500 def",
        "resolve keep", "lock synchro only", "unlock 1", "ruling e4: no free zone, can't activate",
        "zeus attacks arias", "zeus attacks directly",
    )

    fun parse(text: String, s: DuelState, seat: Int, catalog: DuelCatalog): Parsed {
        val line = text.trim().removePrefix("/").trim()
        if (line.isEmpty()) return Parsed.Problem("Type a command, like “ash to hand”")
        val words = line.lowercase().split(Regex("\\s+"))
        val head = words.first()
        val rest = words.drop(1)
        val n = rest.firstOrNull()?.toIntOrNull()

        fun one(a: DuelAction, said: String) = Parsed.Actions(listOf(a), said)

        // "zeus attacks arias", "zeus attacks directly", "attack arias with zeus", "attack directly with zeus" (1.0.83).
        val lower = line.lowercase()
        if (head == "attack" || " attacks " in " $lower ") return attack(lower, s, seat, catalog)

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
            "dp", "draw-phase" -> return phase(DuelPhase.DRAW, s, seat)
            "sp", "standby" -> return phase(DuelPhase.STANDBY, s, seat)
            "m1", "mp1", "main1" -> return phase(DuelPhase.MAIN1, s, seat)
            "bp", "battle" -> return phase(DuelPhase.BATTLE, s, seat)
            "m2", "mp2", "main2" -> return phase(DuelPhase.MAIN2, s, seat)
            "ep" -> return phase(DuelPhase.END, s, seat)
            // From the End Phase, next is the other player's turn.
            "next", "np" -> return if (s.phase == DuelPhase.END) endTurn(s, seat) else phase(s.phase.next(), s, seat)
            "end", "pass", "et" -> if (rest.isEmpty() || rest == listOf("turn")) return endTurn(s, seat)
            "accept", "yes" -> {
                val p = s.proposal ?: return Parsed.Problem("Nothing was asked")
                if (seat == p.seat) return Parsed.Problem("The other player answers that")
                return if (p.end) one(DuelAction.EndTurn, "End turn") else one(DuelAction.Phase(p.phase ?: s.phase), "${p.phase?.label} Phase")
            }
            "decline", "no" -> if (s.proposal != null) return one(DuelAction.Decline(seat), "Not yet")
            "lock" -> {
                var text = line.substringAfter(' ', "").trim()
                var until = com.kaiharimoto.mastertool.core.duel.Lock.UNTIL_TURN
                Regex("\\s*\\((?:until )?(?:the )?(?:end of (?:the )?)?(turn|chain|duel)\\)\\s*$|\\s+until (?:the )?(?:end of (?:the )?)?(turn|chain|duel)\\s*$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                    until = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase()
                    text = text.substring(0, m.range.first).trim()
                }
                if (text.isEmpty()) return Parsed.Problem("Lock what? “lock Synchro Monsters only from the Extra Deck”")
                return one(DuelAction.Lock(seat, text, until), "Lock")
            }
            "unlock" -> {
                val id = n ?: s.locks.lastOrNull()?.id ?: return Parsed.Problem("No lock to lift")
                return one(DuelAction.Unlock(id), "Unlock")
            }
            "ruling", "rule" -> {
                val body = line.substringAfter(' ', "").trim()
                val colon = body.indexOf(':')
                if (colon < 0) return if (body.isEmpty()) Parsed.Problem("“ruling <card>: what we agreed”") else Parsed.Ruling(null, null, body)
                val q = body.substring(0, colon).trim()
                val text = body.substring(colon + 1).trim().ifEmpty { return Parsed.Problem("What did you agree?") }
                if (q.isEmpty()) return Parsed.Ruling(null, null, text)
                return when (val l = lookup(q, s, seat, catalog, want = Want.ANY, everywhere = true)) {
                    is Lookup.One -> s.cards.getValue(l.uid).let { Parsed.Ruling(it.code.takeIf { c -> c != 0 }, catalog.nameOf(it), text) }
                    else -> Parsed.Ruling(null, q, text)
                }
            }
            "coin", "flip-coin" -> return one(DuelAction.Coin(seat), "Coin")
            "dice", "die", "roll" -> return one(DuelAction.Dice(seat), "Die")
            "resolve", "res" -> {
                if (s.chain.isEmpty()) return Parsed.Problem("There is no chain to resolve")
                val keep = rest.firstOrNull() in setOf("keep", "stay", "stays")
                return Parsed.Actions(DuelVerbs.resolve(s, catalog, keep), "Resolve")
            }
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
            "token", "tokens" -> return token(rest, s, seat)
            "clear" -> if (rest.firstOrNull() == "chain" || rest.isEmpty()) return one(DuelAction.ChainClear, "Clear the chain")
            // A chain link for a card where it stands (an effect on the field or in the GY), nothing moved.
            "link", "effect" -> {
                val q = rest.joinToString(" ").ifBlank { return Parsed.Problem("Which card's effect?") }
                val uid = when (val l = lookup(q, s, seat, catalog, want = Want.ANY)) {
                    is Lookup.One -> l.uid
                    is Lookup.Many -> return Parsed.Problem(manyWords(q, l))
                    is Lookup.None -> return Parsed.Problem(l.why)
                }
                return one(DuelAction.ChainAdd(seat, uid), "Effect: ${catalog.nameOf(s.cards.getValue(uid))}")
            }
        }
        return cardCommand(words, s, seat, catalog)
    }

    private fun attack(line: String, s: DuelState, seat: Int, catalog: DuelCatalog): Parsed {
        val (by, at) = when {
            line.startsWith("attack ") -> {
                val rest = line.removePrefix("attack ").trim()
                val with = rest.lastIndexOf(" with ")
                when {
                    rest.startsWith("with ") -> rest.removePrefix("with ").removeSuffix(" directly").trim() to "directly"
                    with >= 0 -> rest.substring(with + 6).trim() to rest.substring(0, with).trim()
                    else -> return Parsed.Problem("Attack with which monster? “attack arias with zeus”, or “zeus attacks directly”")
                }
            }
            else -> line.substringBefore(" attacks ").trim() to line.substringAfter(" attacks ").trim()
        }
        val attacker = when (val l = lookup(by, s, seat, catalog, Want.ANY, fieldOnly = true)) {
            is Lookup.One -> l.uid
            is Lookup.Many -> return Parsed.Problem(manyWords(by, l))
            is Lookup.None -> return Parsed.Problem("No monster of yours on the field matches “$by”")
        }
        val target = if (at == "directly" || at == "direct" || at.isBlank()) null else when (val l = lookup(at, s, seat, catalog, Want.TARGET, fieldOnly = true)) {
            is Lookup.One -> l.uid.takeIf { s.cards[it]?.controller != seat } ?: return Parsed.Problem("Attack a monster the other player controls")
            is Lookup.Many -> return Parsed.Problem(manyWords(at, l))
            is Lookup.None -> return Parsed.Problem("No monster of theirs matches “$at”")
        }
        return Parsed.Actions(listOf(DuelAction.Attack(seat, attacker, target)), "Attack")
    }

    /**
     * A phase change. The seat whose turn it is moves the phase; the other asks (1.0.79, Ai's feedback:
     * acting as the other seat to move the phase is how hidden cards leaked).
     */
    private fun phase(p: DuelPhase, s: DuelState, seat: Int): Parsed =
        if (!s.solo && seat != s.active) Parsed.Actions(listOf(DuelAction.Propose(seat, p)), "Ask for the ${p.label} Phase")
        else Parsed.Actions(listOf(DuelAction.Phase(p)), "${p.label} Phase")

    private fun endTurn(s: DuelState, seat: Int): Parsed =
        if (!s.solo && seat != s.active) Parsed.Actions(listOf(DuelAction.Propose(seat, end = true)), "Ask to end the turn")
        else Parsed.Actions(listOf(DuelAction.EndTurn), "End turn")

    /**
     * `token [n] [name] [atk N] [def N] [atk|def|def-pos] [their] [zone]` (1.0.79): a token's stats, its
     * position, and the side it goes to ("their" puts it on the other player's field).
     */
    private fun token(rest: List<String>, s: DuelState, seat: Int): Parsed {
        var count = 1
        var atk: Int? = null
        var def: Int? = null
        var pos = CardPosition.FACE_UP_DEF
        var side = seat
        var zoneWord: Place.Zone? = null
        val name = mutableListOf<String>()
        var i = 0
        while (i < rest.size) {
            val w = rest[i]
            val next = rest.getOrNull(i + 1)?.toIntOrNull()
            when {
                i == 0 && w.toIntOrNull() != null && w.toInt() in 1..5 -> count = w.toInt()
                (w == "atk" || w == "attack") && next != null -> { atk = next; i++ }
                (w == "def" || w == "defense") && next != null -> { def = next; i++ }
                w in setOf("atk-pos", "atkpos", "atk", "attack", "attack-position") -> pos = CardPosition.FACE_UP_ATK
                w in setOf("def-pos", "defpos", "def", "defense", "defense-position") -> pos = CardPosition.FACE_UP_DEF
                w in setOf("their", "theirs", "opp", "opponent", "opponents", "opponent's") -> side = 1 - seat
                zoneOf(w, seat) != null -> zoneWord = zoneOf(w, seat)
                else -> name += w
            }
            i++
        }
        if (side != seat && s.solo) return Parsed.Problem("There is no other player at this table")
        val label = name.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.ifBlank { "Token" }
        val actions = mutableListOf<DuelAction>()
        var state = s
        repeat(count) {
            val z = (if (count == 1) zoneWord?.copy(seat = side) else null) ?: DuelVerbs.nearestFree(state, side, ZoneKind.MONSTER)
                ?: return Parsed.Problem("No free Monster Zone")
            val a = DuelAction.Token(seat, z, pos = pos, name = label, atk = atk, def = def)
            actions += a
            state = (DuelRules.apply(state, a) as? Outcome.Ok)?.state ?: return Parsed.Problem("That zone is taken")
        }
        return Parsed.Actions(actions, if (count > 1) "$count tokens" else "Token")
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
        "place" to DuelVerb.PLACE, "put" to DuelVerb.PLACE, "move" to DuelVerb.MOVE,
    )

    private val pileWords: Map<String, PileKind> = mapOf(
        "hand" to PileKind.HAND, "deck" to PileKind.DECK, "extra" to PileKind.EXTRA, "ed" to PileKind.EXTRA,
        "gy" to PileKind.GY, "grave" to PileKind.GY, "graveyard" to PileKind.GY,
        "banish" to PileKind.BANISHED, "banished" to PileKind.BANISHED, "removed" to PileKind.BANISHED, "exile" to PileKind.BANISHED,
    )

    /** Words that only say a placed card lies face-up as a Spell — "as continuous", "as a Continuous Spell". */
    private val asContinuous = Regex("\\s+(as\\s+)?(an?\\s+)?(face-?up\\s+)?(continuous|face-?up)(\\s+(spell|trap|card))?$")

    private fun cardCommand(words: List<String>, s: DuelState, seat: Int, catalog: DuelCatalog): Parsed {
        var verb: DuelVerb? = verbWords[words.first()]
        var body = if (verb != null) words.drop(1) else words
        var defense = words.first() == "def"

        // "… to <destination>" (also "in", "into", "on" for a zone): the last one whose rest is a place,
        // so a name with "to" in it ("Back to Square One") stays whole.
        var dest: List<String> = emptyList()
        for (i in body.indices.reversed()) {
            if (body[i] !in setOf("to", "in", "into", "on", "onto")) continue
            val d = body.drop(i + 1).joinToString(" ").replace(asContinuous, "").split(" ").filter { it.isNotEmpty() }
            if (d.isEmpty() || i == 0) continue
            if (isPlace(d, s, seat, catalog) || (body[i] == "to" && attachHost(d.joinToString(" "), s, seat, catalog) is Lookup.One)) {
                dest = d
                body = body.take(i)
                break
            }
        }
        // "… from <pile>": the last "from" followed by a pile.
        var from: PileKind? = null
        var fromField = false
        for (i in body.indices.reversed()) {
            if (body[i] != "from" || i == 0) continue
            val w = body.drop(i + 1).joinToString(" ")
            val pile = pileWords[w] ?: pileWords[w.removePrefix("the ")]
            if (pile != null || w == "field") {
                from = pile
                fromField = w == "field"
                body = body.take(i)
                break
            }
        }
        // "ash gy", "ash banish": a verb after the name — unless the whole of it names a card ("Torrential Tribute").
        if (verb == null && dest.isEmpty() && body.size >= 2 && verbWords.containsKey(body.last())) {
            val whole = body.joinToString(" ")
            val named = lookup(whole, s, seat, catalog, Want.ANY, everywhere = true)
            val strong = named is Lookup.One && NameScore.of(whole, catalog.nameOf(s.cards.getValue(named.uid))) >= 70
            if (!strong) {
                verb = verbWords[body.last()]
                body = body.dropLast(1)
            }
        }
        // A trailing position or "as continuous": "summon droll def", "place angelechy as continuous".
        body = body.joinToString(" ").replace(asContinuous, "").split(" ").filter { it.isNotEmpty() }
        if (body.lastOrNull() == "def" || body.lastOrNull() == "defense") { defense = true; body = body.dropLast(1) }
        if (body.lastOrNull() == "atk" || body.lastOrNull() == "attack") body = body.dropLast(1)
        if (body.lastOrNull() in setOf("facedown", "fd") && verb == DuelVerb.BANISH) { verb = DuelVerb.BANISH_DOWN; body = body.dropLast(1) }

        val query = body.joinToString(" ").trim()
        if (query.isEmpty()) return Parsed.Problem("Which card?")
        val d = dest.joinToString(" ")
        val zone = if (dest.isNotEmpty()) zoneOf(dest.joinToString(""), seat) else null
        val want = wantOf(verb, d, zone)
        val uid = when (val l = lookup(query, s, seat, catalog, want, from, fromField)) {
            is Lookup.One -> l.uid
            is Lookup.Many -> return Parsed.Problem(manyWords(query, l))
            is Lookup.None -> return Parsed.Problem(l.why)
        }
        val name = catalog.nameOf(s.cards.getValue(uid))
        val kind = DuelVerbs.kindOf(s.cards.getValue(uid), catalog)
        val spellish = kind == CardKind.SPELL || kind == CardKind.TRAP || kind == CardKind.FIELD_SPELL
        val where = s.placeOf(uid)
        val inHand = where is Place.Pile && where.kind == PileKind.HAND

        var host: Int? = null
        var placeZone: Place.Zone? = zone
        if (dest.isNotEmpty()) {
            when {
                zone != null -> if (verb == null) verb = when {
                    where is Place.Zone -> DuelVerb.MOVE
                    zone.kind == ZoneKind.MONSTER || zone.kind == ZoneKind.EMZ -> if (spellish) DuelVerb.PLACE else DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.SPECIAL
                    // A Spell or Trap from the hand to its zone is played as it would be played; anything else is placed.
                    inHand && spellish -> DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.ACTIVATE
                    else -> DuelVerb.PLACE
                }
                d == "deck" || d == "top" || d == "deck top" || d == "top of deck" -> if (verb == null || verb == DuelVerb.DEFAULT) verb = DuelVerb.DECK_TOP
                d == "bottom" || d == "deck bottom" || d == "bottom of deck" -> verb = DuelVerb.DECK_BOTTOM
                d == "field" -> {
                    if (verb == DuelVerb.PLACE || (verb == null && kind == CardKind.FIELD_SPELL)) {
                        verb = DuelVerb.PLACE
                        if (kind == CardKind.FIELD_SPELL) placeZone = Place.Zone(seat, ZoneKind.FIELD, 0)
                    } else if (verb == null) {
                        verb = DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.SPECIAL
                    }
                }
                pileWords[d] != null -> verb = when (pileWords.getValue(d)) {
                    PileKind.HAND -> DuelVerb.HAND
                    PileKind.DECK -> DuelVerb.DECK_TOP
                    PileKind.EXTRA -> DuelVerb.EXTRA
                    PileKind.GY -> DuelVerb.GRAVE
                    PileKind.BANISHED -> if (verb == DuelVerb.BANISH_DOWN) DuelVerb.BANISH_DOWN else DuelVerb.BANISH
                }
                else -> {
                    host = when (val l = attachHost(d, s, seat, catalog)) {
                        is Lookup.One -> l.uid
                        is Lookup.Many -> return Parsed.Problem(manyWords(d, l))
                        is Lookup.None -> return Parsed.Problem("Where is “$d”? Try hand, gy, deck, m3, s2, emz left, fz, or a card on the field")
                    }
                    verb = DuelVerb.ATTACH
                }
            }
        }
        val v = verb ?: DuelVerb.DEFAULT
        if (v == DuelVerb.MOVE && placeZone == null) return Parsed.Problem("Move $name where? “move $query to m4”")
        val result = DuelVerbs.actions(s, seat, uid, v, catalog, placeZone, host)
        if (result.needsHost) return Parsed.Problem("Attach $name to which card? “attach $query to <card>”")
        if (result.needsTarget) return Parsed.Problem("Attack what with $name? “$query attacks <card>”, or “$query attacks directly”")
        result.problem?.let { return Parsed.Problem(it) }
        // A zone named outright is always where the card goes; a command that would move nothing there is refused (1.0.79).
        if (placeZone != null && result.actions.none { it is DuelAction.Move && it.to is Place.Zone && (it.to as Place.Zone).let { z -> z.kind == placeZone.kind && z.index == placeZone.index } }) {
            return Parsed.Problem("That would not put $name in ${DuelWords.zoneName(placeZone, s)}. Try “place $query in ${dest.joinToString(" ")}”")
        }
        // "summon … def": the monster comes in face-up Defense.
        val actions = if (defense) result.actions.map {
            if (it is DuelAction.Move && it.pos == CardPosition.FACE_UP_ATK && it.to is Place.Zone) it.copy(pos = CardPosition.FACE_UP_DEF) else it
        } else result.actions
        return Parsed.Actions(actions, "${(if (v == DuelVerb.DEFAULT) DuelVerbs.default(s, seat, uid, catalog) else v).label}: $name")
    }

    private val placing = setOf(DuelVerb.SUMMON, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.ACTIVATE)

    /** Whether words after "to" are a place: a zone, a pile, the deck's ends, the field. */
    private fun isPlace(d: List<String>, s: DuelState, seat: Int, catalog: DuelCatalog): Boolean {
        val w = d.joinToString(" ")
        return zoneOf(d.joinToString(""), seat) != null || pileWords[w] != null || w == "field" ||
            w in setOf("deck", "top", "deck top", "top of deck", "bottom", "deck bottom", "bottom of deck")
    }

    private fun attachHost(d: String, s: DuelState, seat: Int, catalog: DuelCatalog): Lookup {
        val l = lookup(d, s, seat, catalog, Want.ANY, fieldOnly = true)
        return if (l is Lookup.One && s.placeOf(l.uid) !is Place.Zone) Lookup.None("Materials go under a card on the field") else l
    }

    private fun wantOf(verb: DuelVerb?, dest: String, zone: Place.Zone?): Want = when {
        verb == DuelVerb.TARGET -> Want.TARGET
        verb == DuelVerb.HAND || dest == "hand" -> Want.HAND
        verb in setOf(DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.BANISH_DOWN, DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.EXTRA, DuelVerb.ATTACH, DuelVerb.FLIP, DuelVerb.POSITION, DuelVerb.MOVE) -> Want.AWAY
        dest in setOf("gy", "grave", "graveyard", "banish", "banished", "deck", "bottom", "extra", "ed") -> Want.AWAY
        verb in placing || verb == DuelVerb.PLACE || zone != null -> Want.PLAY
        else -> Want.ANY
    }

    private fun manyWords(q: String, l: Lookup.Many): String =
        "“$q” could be ${l.names.take(5).joinToString(", ")}${if (l.names.size > 5) ", …" else ""}. Say more of the name, or use its #number"

    /**
     * "m3", "s2", "st2", "s/t2", "fz", and the Extra Monster Zones: "emz left" / "emz right" (also
     * "left emz", "emzl"…) are the acting seat's own left and right (1.0.79, Ai: "it's unclear whose left
     * or right that is"); "el"/"er" and "emz1"/"emz2" keep their old meaning, the left and right as seat
     * 0 sees them, so combos written before still replay.
     */
    fun zoneOf(word: String, seat: Int): Place.Zone? {
        val w = word.lowercase().replace("/", "").replace("-", "").replace(" ", "")
        Regex("^(m|mz|monster)([1-5])$").find(w)?.let { return Place.Zone(seat, ZoneKind.MONSTER, it.groupValues[2].toInt() - 1) }
        Regex("^(s|st|sz|spell|trap)([1-5])$").find(w)?.let { return Place.Zone(seat, ZoneKind.SPELL, it.groupValues[2].toInt() - 1) }
        Regex("^(p|pz|pendulum)([12])$").find(w)?.let { return Place.Zone(seat, ZoneKind.SPELL, if (it.groupValues[2] == "1") 0 else 4) }
        Regex("^emz([12])$").find(w)?.let { return Place.Zone(seat, ZoneKind.EMZ, it.groupValues[1].toInt() - 1) }
        // Seat 0's left is index 0; across the table, seat 1's left is index 1.
        val myLeft = if (seat == 0) 0 else 1
        return when (w) {
            "emz", "extramonster", "emzleft", "leftemz", "myleftemz", "emzmyleft", "emzmine", "myemz" -> Place.Zone(seat, ZoneKind.EMZ, myLeft)
            "emzright", "rightemz", "myrightemz", "emzmyright" -> Place.Zone(seat, ZoneKind.EMZ, 1 - myLeft)
            "el", "emzl" -> Place.Zone(seat, ZoneKind.EMZ, 0)
            "er", "emzr" -> Place.Zone(seat, ZoneKind.EMZ, 1)
            "fz", "fieldzone", "fieldspell", "fieldspellzone" -> Place.Zone(seat, ZoneKind.FIELD, 0)
            else -> null
        }
    }

    /** The card [query] names, or null — the old answer, for callers that only need one. */
    fun find(query: String, s: DuelState, seat: Int, catalog: DuelCatalog, from: PileKind?, fieldOnly: Boolean): Int? =
        (lookup(query, s, seat, catalog, Want.ANY, from, fieldOnly) as? Lookup.One)?.uid

    /**
     * The card [query] names (1.0.79, Ai's feedback). Only the acting seat's own cards unless the query
     * says "their" (or "opp"), is a `#uid`, or the verb is a target; the places are tried in the order
     * [want] reaches for — the Deck first for a search, the hand first to play, the field first to send
     * away — and among equally good matches the first place wins. When the best match is shared by
     * different cards' names, it is [Lookup.Many]: the command fails and lists them, never guesses.
     * Only what the seat may see is ever matched, plus its own Deck and Extra Deck (it knows its list).
     */
    fun lookup(
        query: String,
        s: DuelState,
        seat: Int,
        catalog: DuelCatalog,
        want: Want = Want.ANY,
        from: PileKind? = null,
        fieldOnly: Boolean = false,
        everywhere: Boolean = false,
    ): Lookup {
        val q0 = query.trim()
        // "#17": a card by its uid, as Ai is shown them — only one the seat may see (or its own deck's).
        Regex("^#(\\d+)$").find(q0)?.let { m ->
            val uid = m.groupValues[1].toInt()
            val own = s.cards[uid]?.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) }
            return if (uid in s.cards && (DuelSight.sees(s, uid, seat) || own)) Lookup.One(uid) else Lookup.None("No card #$uid you can see")
        }
        var q = q0
        var side: Int? = if (everywhere || want == Want.TARGET) null else seat
        Regex("^(their|theirs|opp|opponent|opponents|opponent's|the opponent's)\\s+", RegexOption.IGNORE_CASE).find(q)?.let { side = 1 - seat; q = q.substring(it.range.last + 1) }
        Regex("^(my|mine|own)\\s+", RegexOption.IGNORE_CASE).find(q)?.let { side = seat; q = q.substring(it.range.last + 1) }
        if (q.isBlank()) return Lookup.None("Which card?")
        val other = 1 - seat
        fun field(of: Int) = s.onField().filter { s.cards[it]?.controller == of }
        fun materials(of: Int) = field(of).flatMap { s.cards[it]?.under ?: emptyList() }
        fun piles(of: Int, kinds: List<PileKind>) = kinds.flatMap { s.seats[of].pile(it) }
        fun reach(of: Int): List<Int> = when {
            fieldOnly -> field(of)
            from != null -> s.seats[of].pile(from)
            else -> when (want) {
                Want.HAND -> piles(of, listOf(PileKind.DECK, PileKind.GY, PileKind.BANISHED)) + field(of) + piles(of, listOf(PileKind.EXTRA)) + materials(of)
                Want.PLAY -> piles(of, listOf(PileKind.HAND)) + field(of) + piles(of, listOf(PileKind.GY, PileKind.BANISHED, PileKind.EXTRA, PileKind.DECK)) + materials(of)
                Want.AWAY -> field(of) + piles(of, listOf(PileKind.HAND)) + materials(of) + piles(of, listOf(PileKind.GY, PileKind.BANISHED, PileKind.DECK, PileKind.EXTRA))
                Want.TARGET -> field(of) + piles(of, listOf(PileKind.GY, PileKind.BANISHED, PileKind.HAND)) + materials(of)
                Want.ANY -> piles(of, listOf(PileKind.HAND)) + field(of) + piles(of, listOf(PileKind.GY, PileKind.BANISHED)) + materials(of) + piles(of, listOf(PileKind.EXTRA, PileKind.DECK))
            }
        }
        val order = when (side) {
            seat -> reach(seat)
            other -> reach(other)
            else -> if (want == Want.TARGET) reach(other) + reach(seat) else reach(seat) + reach(other)
        }.distinct().filter { uid ->
            // A search reaches into the Deck: never for a card already in the hand.
            !(want == Want.HAND && s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.HAND })
        }
        // The deck and Extra Deck are searchable by their owner, who knows the decklist; nothing else hidden is.
        val visible = order.filter { uid ->
            DuelSight.sees(s, uid, seat) || (s.cards[uid]?.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) })
        }
        var best = 0
        val top = mutableListOf<Int>()
        visible.forEach { uid ->
            val card = s.cards[uid] ?: return@forEach
            val score = NameScore.of(q, catalog.nameOf(card))
            when {
                score > best -> { best = score; top.clear(); top += uid }
                score == best && score > 0 -> top += uid
            }
        }
        if (top.isEmpty()) {
            val whose = when (side) {
                seat -> "of yours "
                other -> "of theirs "
                else -> ""
            }
            return Lookup.None("No card ${whose}you can see matches “$q”" + if (side == seat && want != Want.TARGET) " (theirs: “their $q”)" else "")
        }
        val names = top.map { catalog.nameOf(s.cards.getValue(it)) }.distinct()
        return if (names.size > 1) Lookup.Many(names) else Lookup.One(top.first())
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
