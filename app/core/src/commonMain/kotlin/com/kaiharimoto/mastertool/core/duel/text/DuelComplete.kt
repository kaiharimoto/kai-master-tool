package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.DuelVerb
import com.kaiharimoto.mastertool.core.duel.DuelVerbs
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ZoneKind
import com.kaiharimoto.mastertool.core.duel.nameOf

/**
 * The Line's live completion (1.0.87): as a move is typed, what could come next, best first — a line from history,
 * a verb (its word or its key's letter), a card in reach with its coordinate ("Ash Blossom · h2"), a zone, a phase,
 * a cue for Ai, a question. Only cards the seat can see are named; a hidden one is offered by its coordinate alone.
 *
 * A [Suggestion] replaces the word under the cursor — from [Suggestion.from] to the cursor — with [Suggestion.insert];
 * a history line replaces the whole line ([apply]).
 */
object DuelComplete {

    enum class Kind { HISTORY, VERB, CARD, ZONE, PHASE, CUE, QUERY }

    data class Suggestion(val insert: String, val label: String, val kind: Kind, val from: Int = 0)

    private val VERBS = listOf(
        "summon" to "Summon", "set" to "Set", "activate" to "Activate", "attack" to "Attack", "ss" to "Special Summon",
        "target" to "Target", "gy" to "Send to the GY", "banish" to "Banish", "add" to "Add to the hand", "flip" to "Flip",
        "pos" to "Change position", "attach" to "Attach as material", "detach" to "Detach", "reveal" to "Reveal",
        "counter" to "Counters", "move" to "Move", "place" to "Place", "chain" to "Chain", "resolve" to "Resolve the chain",
        "draw" to "Draw", "mill" to "Mill", "lp" to "Life points", "token" to "Token", "open" to "Open a pile", "read" to "Read a card",
        "say" to "Say", "undo" to "Undo", "swap" to "Sit at the other seat",
        "random" to "A card at random", "discard" to "Discard", "spin" to "Shuffle into the Deck",
    )

    /**
     * The verb keys' letters, as `DeskShortcuts` binds them ([DuelLetters.KEY_HINTS]: the single letters with a key).
     * Only the letter that is the whole prefix is offered, so their order never shows.
     */
    private val LETTERS = DuelLetters.KEY_HINTS

    /** For tests. */
    internal val KEY_LETTERS: List<Pair<String, String>> get() = LETTERS

    private val PHASES = listOf("dp" to DuelPhase.DRAW, "sp" to DuelPhase.STANDBY, "m1" to DuelPhase.MAIN1, "bp" to DuelPhase.BATTLE, "m2" to DuelPhase.MAIN2, "ep" to DuelPhase.END)

    private val CUES = listOf("no response", "your move", "over to you", "done", "don't wait", "catch up", "respond", "pass")

    private val QUERIES = listOf(
        "hand" to "Your hand", "field" to "Your field", "their field" to "Their field", "board" to "Both fields",
        "gy" to "Your GY", "ogy" to "Their GY", "ban" to "Your banished cards", "oban" to "Their banished cards",
        "lp" to "Life points", "chain" to "The chain", "turn" to "Turn and phase",
    )

    /** The word under [cursor] and where it starts. */
    private fun word(text: String, cursor: Int): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        var i = c
        while (i > 0 && text[i - 1] != ' ' && text[i - 1] != ';') i--
        return text.substring(i, c) to i
    }

    /** [text] with [s] put in at [cursor]: the new line and where the cursor goes. */
    fun apply(text: String, cursor: Int, s: Suggestion): Pair<String, Int> {
        val c = cursor.coerceIn(0, text.length)
        if (s.kind == Kind.HISTORY) return s.insert to s.insert.length
        val from = s.from.coerceIn(0, c)
        val tail = text.substring(c)
        val ins = s.insert + if (tail.startsWith(" ")) "" else " "
        val out = text.substring(0, from) + ins + tail
        return out to from + ins.length
    }

    fun suggest(
        text: String,
        cursor: Int,
        s: DuelState,
        seat: Int,
        catalog: DuelCatalog,
        history: List<String> = emptyList(),
        secret: Long = 0L,
        most: Int = 8,
    ): List<Suggestion> {
        val c = cursor.coerceIn(0, text.length)
        val (w, from) = word(text, c)
        val prefix = w.lowercase()
        // The move being typed: after the last ";".
        val move = text.substring(0, from).substringAfterLast(';').trim().lowercase()
        val before = move.split(' ').filter { it.isNotEmpty() }
        val scored = mutableListOf<Pair<Suggestion, Int>>()
        fun add(sug: Suggestion, score: Int) { if (scored.none { it.first.insert == sug.insert && it.first.kind == sug.kind }) scored += sug to score }
        fun starts(word: String) = prefix.isEmpty() || word.lowercase().startsWith(prefix)

        // History: the whole line so far is the start of a line typed before.
        val typed = text.substring(0, c).trim().lowercase()
        if (typed.isNotEmpty()) history.asReversed().distinct().forEach { h ->
            if (h.lowercase().startsWith(typed) && h.length > typed.length) add(Suggestion(h, h, Kind.HISTORY, 0), 95)
        }

        val head = before.firstOrNull()
        val verb = head?.let { DuelCommand.VERB_WORDS[it] }
        val attacking = head in setOf("attack", "at") || (head == "a" && s.phase == DuelPhase.BATTLE && before.size == 2)

        // Cards in reach, by name or coordinate.
        fun cards(score: Int, filter: (Int) -> Boolean = { true }) {
            reach(s, seat, catalog, secret).filter(filter).forEach { uid ->
                val coord = DuelNotation.coordOf(s, uid, seat, secret)
                val seen = DuelSight.sees(s, uid, seat) || ownList(s, uid, seat)
                val name = if (seen) s.cards[uid]?.let { catalog.nameOf(it) } else null
                val byName = name != null && prefix.isNotEmpty() && NameScore.of(prefix, name) > 0
                val byCoord = coord != null && starts(coord)
                if (!byName && !byCoord) return@forEach
                val insert = coord ?: name!!.lowercase()
                val label = listOfNotNull(name ?: "Face-down card", coord).joinToString(" · ")
                add(Suggestion(insert, label, Kind.CARD, from), score + if (byName && name != null) NameScore.of(prefix, name) / 10 else 0)
            }
        }
        fun zones(score: Int, kinds: Set<ZoneKind>, free: Boolean, mine: Boolean = true) {
            val side = if (mine) seat else 1 - seat
            val list = buildList {
                if (ZoneKind.MONSTER in kinds) (0 until DuelState.ZONES).forEach { add(Place.Zone(side, ZoneKind.MONSTER, it)) }
                if (ZoneKind.EMZ in kinds) (0..1).forEach { add(Place.Zone(seat, ZoneKind.EMZ, it)) }
                if (ZoneKind.SPELL in kinds) (0 until DuelState.ZONES).forEach { add(Place.Zone(side, ZoneKind.SPELL, it)) }
                if (ZoneKind.FIELD in kinds) add(Place.Zone(side, ZoneKind.FIELD, 0))
            }
            list.filter { (s.at(it) == null) == free }.forEach { z ->
                val coord = DuelNotation.slotCoord(z, seat) ?: return@forEach
                if (!starts(coord)) return@forEach
                val there = s.at(z)
                val label = there?.let { u -> if (DuelSight.sees(s, u, seat)) "${s.cards[u]?.let { catalog.nameOf(it) }} · $coord" else "Face-down card · $coord" }
                    ?: "${DuelNotation.label(DuelNotation.parse(coord)!!)} (empty)"
                add(Suggestion(coord, label, Kind.ZONE, from), score)
            }
        }

        when {
            // The first word: verbs, letters, phases, cues, questions, cards.
            before.isEmpty() -> {
                VERBS.forEach { (v, label) -> if (prefix.isNotEmpty() && starts(v)) add(Suggestion(v, label, Kind.VERB, from), 70 - v.length) }
                if (prefix.length == 1) LETTERS.forEach { (l, label) -> if (l == prefix) add(Suggestion(l, "$label (key)", Kind.VERB, from), 75) }
                PHASES.forEach { (p, phase) -> if (prefix.isNotEmpty() && starts(p)) add(Suggestion(p, "${phase.label} Phase", Kind.PHASE, from), 60) }
                if (prefix.isNotEmpty() && starts("end")) add(Suggestion("end", "End the turn", Kind.PHASE, from), 58)
                if (prefix.isNotEmpty() && starts("next")) add(Suggestion("next", "Next phase", Kind.PHASE, from), 57)
                CUES.forEach { cue -> if (prefix.length >= 2 && starts(cue)) add(Suggestion(cue, "Ai: $cue", Kind.CUE, from), 50) }
                QUERIES.forEach { (q, label) -> if (prefix.isNotEmpty() && starts(q)) add(Suggestion(q, label, Kind.QUERY, from), 45) }
                if (prefix.isNotEmpty()) cards(40)
            }
            // After "attack X": their monsters, or direct.
            attacking && before.size >= 2 -> {
                zones(80, setOf(ZoneKind.MONSTER), free = false, mine = false)
                s.emz.forEachIndexed { i, u -> if (u != null && s.cards[u]?.controller != seat && starts("e${i + 1}")) add(Suggestion("e${i + 1}", "${if (DuelSight.sees(s, u, seat)) s.cards[u]?.let { catalog.nameOf(it) } else "Face-down card"} · e${i + 1}", Kind.ZONE, from), 80) }
                if (starts("direct")) add(Suggestion("direct", "Attack directly", Kind.ZONE, from), 70)
            }
            attacking -> cards(80) { u -> DuelVerbs.canAttack(s, seat, u) }
            // After a verb and its card: where it goes.
            verb != null && before.size >= 2 || before.lastOrNull() in setOf("to", "in", "into", "on") -> {
                val v = verb
                val card = before.getOrNull(1)?.let { DuelCommand.lookup(it, s, seat, catalog, secret = secret) as? DuelCommand.Lookup.One }?.uid
                val kind = card?.takeIf { DuelSight.sees(s, it, seat) }?.let { u -> v?.let { DuelVerbs.zoneKind(s, seat, u, it, catalog) } ?: DuelVerbs.zoneKind(s, seat, u, DuelVerb.DEFAULT, catalog) }
                when {
                    v == DuelVerb.ATTACH -> zones(80, setOf(ZoneKind.MONSTER, ZoneKind.EMZ), free = false)
                    v == DuelVerb.TARGET -> cards(70)
                    kind == ZoneKind.MONSTER -> zones(80, setOf(ZoneKind.MONSTER, ZoneKind.EMZ), free = true)
                    kind == ZoneKind.SPELL -> zones(80, setOf(ZoneKind.SPELL), free = true)
                    kind == ZoneKind.FIELD -> zones(80, setOf(ZoneKind.FIELD), free = true)
                    else -> {
                        zones(60, setOf(ZoneKind.MONSTER, ZoneKind.SPELL, ZoneKind.FIELD, ZoneKind.EMZ), free = true)
                        listOf("gy" to "The GY", "ban" to "Banished", "hand" to "The hand", "deck" to "The top of the Deck", "ex" to "The Extra Deck")
                            .forEach { (p, label) -> if (starts(p)) add(Suggestion(p, label, Kind.ZONE, from), 55) }
                    }
                }
            }
            // After a verb: the cards it could take, the hand first for a play, the field first for the rest.
            verb != null -> {
                val play = verb in setOf(DuelVerb.SUMMON, DuelVerb.SET, DuelVerb.SPECIAL, DuelVerb.ACTIVATE, DuelVerb.PLACE)
                // A key's letter is a verb only before a coordinate: a card with none (the Deck's, by name) is not offered.
                val letter = head!!.length == 1 || head in DuelLetters.TWO_LETTER
                cards(70) { u ->
                    (!letter || DuelNotation.coordOf(s, u, seat, secret) != null) &&
                        (if (play) !(s.placeOf(u).let { it is Place.Pile && it.kind == PileKind.DECK }) else true)
                }
                if (verb == DuelVerb.TARGET) zones(65, setOf(ZoneKind.MONSTER, ZoneKind.SPELL), free = false, mine = false)
            }
            head in setOf("open", "look") -> listOf("gy", "ogy", "ban", "oban", "ex", "oex", "dk").forEach { p -> if (starts(p)) add(Suggestion(p, p, Kind.ZONE, from), 70) }
            head in setOf("read", "?") -> cards(70)
            else -> cards(40)
        }
        return scored.sortedByDescending { it.second }.map { it.first }.take(most)
    }

    /** Every card the seat could name or point at: its hand and piles, both fields, the other seat's open piles and hand by place. */
    internal fun reach(s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long): List<Int> {
        val other = 1 - seat
        val own = s.seats[seat]
        val theirs = s.seats.getOrNull(other)
        // Never in an order the seat could not see (1.0.87, the red team): their hand as it is shown (by veil, so the
        // card that came in last is not last), and the Deck and Extra Deck by name, never top first.
        val byName = compareBy<Int> { s.cards[it]?.let { c -> catalog.nameOf(c) }.orEmpty() }
        return buildList {
            addAll(own.hand)
            addAll(s.onField())
            addAll(own.gy)
            addAll(own.banished)
            theirs?.let { addAll(it.gy); addAll(it.banished); addAll(DuelNotation.handOrder(s, other, seat, secret)) }
            addAll(own.extra.sortedWith(byName))
            addAll(own.deck.filter { ownList(s, it, seat) }.sortedWith(byName))
        }.distinct()
    }

    /** A card of the seat's own Deck or Extra Deck: it knows its list, so it may name one, though no coordinate. */
    private fun ownList(s: DuelState, uid: Int, seat: Int): Boolean =
        s.cards[uid]?.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) }
}
