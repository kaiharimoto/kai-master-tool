package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.ai.vision.NameMatch
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
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.nameOf

/**
 * The duel's command line — what DuelingBook's menus and Omega's slash commands are for, in the words
 * a player already says across a table: `ash to hand`, `summon droll to m3`, `set called by`, `mill 3`,
 * `lp -1000`, `lp opp /2`, `chain ash`, `bp`, `end`. A slash in front is allowed and ignored.
 *
 * Card names match loosely ("ash", "abjs", "called by") but only among cards the player acting can see —
 * their own hand, deck and Extra Deck, both fields, both graveyards and what is banished face-up — so
 * the line can never be used to find out where a hidden card is.
 *
 * **Command mode** (1.0.87): table coordinates ([DuelNotation]: `h2`, `m3`, `os2`, `gy3`) name a card by
 * where it is, before any name; the duel's verb keys are verbs here too, as a head before a coordinate
 * (`s h2 m3`, `e h4 s2`, `g om3`); moves join with `;` (each its own step, [Parsed.Many]); every mouse-only
 * gesture has words ([Parsed.Ui]); questions answer in words ([Parsed.Query], [DuelAnswer]); and a name not
 * found is matched forgivingly — spelling, then sound ([Phonetic]) — among the names in reach, asking
 * "Did you mean" when unsure. A hidden card is reachable only by its coordinate, and only by the verbs that
 * do not need to know what it is.
 */
object DuelCommand {

    sealed interface Parsed {
        /**
         * Moves to commit as one group. [named]: the cards the line named by name — the player knew them —
         * which the preview may name though the table hides them (their own Deck's card searched by name).
         */
        data class Actions(val actions: List<DuelAction>, val said: String, val named: Set<Int> = emptySet()) : Parsed

        /** Why not. [choices]: "Did you mean" names (or coordinates), never a card the seat cannot see; [query] the words they replace. */
        data class Problem(val text: String, val choices: List<String> = emptyList(), val query: String? = null) : Parsed

        /** A house ruling to keep (1.0.79): "ruling e4: no free zone, can't activate". Not an action on the table. */
        data class Ruling(val code: Int?, val card: String?, val text: String) : Parsed

        /**
         * Several moves joined with `;` (1.0.87): each its own group, its cards bound when the line was read (each
         * against the table the moves before it leave), to be committed one at a time in order, stopping at the
         * first refused — so a watch or a held phase change can come between them, and a phase change or the end of
         * the turn is never grouped with other moves.
         */
        data class Many(val parts: List<Actions>, val lines: List<String>) : Parsed

        /** A question about the table, answered in words through the asker's eyes ([DuelAnswer]); nothing moves. */
        data class Query(val kind: QueryKind, val theirs: Boolean = false, val arg: String = "", val uid: Int? = null) : Parsed

        /**
         * Something the table's chrome does rather than the table (1.0.87): open or close a pile's strip, read a card
         * into the inspector, cue Ai ([cue], or [arg] "catch up" / "respond"), sit at the other seat, undo, redo.
         */
        data class Ui(
            val kind: UiKind,
            val arg: String = "",
            val cue: AiCue? = null,
            val uid: Int? = null,
            val seat: Int? = null,
            val pile: PileKind? = null,
        ) : Parsed
    }

    /** What a [Parsed.Query] asks about. [BOARD] is both fields at once. */
    enum class QueryKind { HAND, FIELD, BOARD, GY, BANISHED, EXTRA, DECK, LP, CHAIN, CARD, TURN }

    enum class UiKind { OPEN, CLOSE, READ, CUE, SWAP, UNDO, REDO }

    /** [Parsed.Ui.arg] of the cues that are not an [AiCue]. */
    const val CUE_CATCH_UP = "catch up"
    const val CUE_RESPOND = "respond"

    /**
     * What a command wants of the card it names (1.0.79), so the lookup reaches where a player would:
     * "e4 to hand" means the Deck's copy before the GY's, "summon droll" the hand's.
     */
    enum class Want { ANY, HAND, PLAY, AWAY, TARGET }

    /** A name looked up: one card, none, or several different cards it could mean. */
    sealed interface Lookup {
        data class One(val uid: Int) : Lookup
        /** [choices]: what the player may have meant ("Did you mean"); [query] the words they replace. */
        data class None(val why: String, val choices: List<String> = emptyList(), val query: String? = null) : Lookup
        data class Many(val names: List<String>) : Lookup
    }

    /**
     * The line's dry run (1.0.87): what Enter would do, in words that use coordinates and name only what the seat can
     * see; the cards it touches and where they go, for the table to outline; and why not, with "Did you mean" choices
     * and [fixes] — the whole line with each choice put in. [parts] are a `;` line's steps.
     */
    data class Preview(
        val ok: Boolean,
        val words: String,
        val actions: List<DuelAction>,
        val touched: Set<Int>,
        val dest: List<Place>,
        val problem: String?,
        val choices: List<String>,
        val parsed: Parsed? = null,
        val fixes: List<String> = emptyList(),
        val parts: List<List<DuelAction>> = emptyList(),
    )

    /** One example per thing the line can do, for the help and the empty line's hint. */
    val EXAMPLES = listOf(
        "draw", "draw 2", "mill 3", "shuffle", "ash to hand", "summon droll to m3", "set called by", "activate pot",
        "chain ash", "banish ash", "gy ash", "ash to deck bottom", "attach ash to zeus", "lp -1000", "lp opp /2",
        "lp =4000", "bp", "m2", "ep", "next", "end", "coin", "dice", "token", "look 3", "excavate 3", "resolve",
        "think", "say ok?", "place angelechy in s2", "move zeus to m4", "token atk 6500 def 8500 def",
        "resolve keep", "lock synchro only", "unlock 1", "ruling e4: no free zone, can't activate",
        "zeus attacks arias", "zeus attacks directly",
        // Command mode (1.0.87).
        "s h2 m3", "e h4 s2", "a s1", "a m3 om1", "attack m3 direct", "g om3", "b gy2", "o h4 m3", "t om2",
        "target om2 with s1", "counter m3 +2", "detach m3", "s h2 m3; bp", "open ogy", "close", "read om2", "?m3",
        "hand", "field", "their field", "gy", "lp", "chain", "no response", "your move", "pass", "swap", "undo",
    )

    /** Heads whose words are free text: a `;` in them is theirs, not a join. */
    private val FREE_TEXT = setOf("say", "chat", "note", "ruling", "rule", "lock")

    /** Spoken or typed words the line tidies before reading: trailing marks, a slash, `m 3` for `m3`. */
    fun clean(text: String): String {
        var t = text.trim().removePrefix("/").trim()
        t = t.trimEnd('.', '!', ',', ' ')
        if (t.length > 1 && t.endsWith("?") && !t.startsWith("?")) t = t.dropLast(1).trimEnd()
        t = t.replace(Regex("\\s+"), " ")
        return t.replace(Regex("(?i)\\b(o?)(h|m|s|e|gy|ban|ex|dk) (\\d{1,2})\\b")) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] }
    }

    /** What the line asks of the table (see the object's notes). [secret] is the duel's, for the order of their hand (`oh2`). */
    fun parse(text: String, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long = 0L): Parsed {
        val line = clean(text)
        if (line.isEmpty()) return Parsed.Problem("Type a command, like “ash to hand”")
        val head = line.substringBefore(' ').lowercase()
        if (';' in line && head !in FREE_TEXT) {
            val lines = line.split(';').map { clean(it) }.filter { it.isNotEmpty() }
            if (lines.size > 1) return many(lines, s, seat, catalog, secret)
            if (lines.isEmpty()) return Parsed.Problem("Type a command, like “ash to hand”")
            return Reader(s, seat, catalog, secret).single(lines.single())
        }
        return Reader(s, seat, catalog, secret).single(line)
    }

    private fun many(lines: List<String>, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long): Parsed {
        var state = s
        val parts = mutableListOf<Parsed.Actions>()
        lines.forEachIndexed { i, l ->
            when (val p = Reader(state, seat, catalog, secret).single(l)) {
                is Parsed.Actions -> {
                    val (next, why) = DuelRules.applyAll(state, p.actions, seat)
                    if (next == null) return Parsed.Problem("Move ${i + 1} (“$l”): $why")
                    parts += p
                    state = next
                }
                is Parsed.Problem -> return p.copy(text = "Move ${i + 1} (“$l”): ${p.text}")
                else -> return Parsed.Problem("“$l” is not a move: only moves join with ;")
            }
        }
        return Parsed.Many(parts, lines)
    }

    /** The line's dry run: see [Preview]. Nothing is stamped or rolled, so it never tells a coming shuffle, coin or die. */
    fun preview(text: String, s: DuelState, seat: Int, catalog: DuelCatalog, secret: Long = 0L): Preview =
        DuelPreview.of(text, parse(text, s, seat, catalog, secret), s, seat, catalog, secret)

    // ---- reading one move ----------------------------------------------------------------------------

    private class Reader(val s: DuelState, val seat: Int, val catalog: DuelCatalog, val secret: Long) {

        fun look(q: String, want: Want = Want.ANY, from: PileKind? = null, fieldOnly: Boolean = false, everywhere: Boolean = false) =
            lookup(q, s, seat, catalog, want, from, fieldOnly, everywhere, secret)

        /** Whether [line], whole, is a card's name the seat can reach — so a system word at its head is that card's. */
        fun namesCard(line: String): Boolean {
            val l = look(line, Want.ANY, everywhere = true)
            return l is Lookup.One && NameScore.of(line, catalog.nameOf(s.cards.getValue(l.uid))) >= 70
        }

        /** A card's words for the seat: its name when the seat can see it (or named it), else where it is. */
        fun label(uid: Int, named: Boolean): String {
            val card = s.cards[uid] ?: return "a card"
            if (named || DuelSight.sees(s, uid, seat)) return catalog.nameOf(card)
            val c = DuelNotation.coordOf(s, uid, seat, secret)
            val where = s.placeOf(uid)
            return when {
                where is Place.Zone -> "the face-down card in ${c ?: "that zone"}"
                c != null -> "the card at $c"
                where is Place.Pile && where.kind == PileKind.DECK -> "the top card of ${if (where.seat == seat) "your" else "their"} Deck"
                else -> "a hidden card"
            }
        }

        fun single(line: String): Parsed {
            val words = line.lowercase().split(' ').filter { it.isNotEmpty() }
            val head = words.first()
            val rest = words.drop(1)
            val lower = words.joinToString(" ")
            val bare = rest.isEmpty()
            val n = rest.singleOrNull()?.toIntOrNull()
            val counted = bare || n != null

            fun one(a: DuelAction, said: String) = Parsed.Actions(listOf(a), said)

            // ---- questions, the chrome, Ai's cues: whole lines --------------------------------------------
            if (lower.startsWith("?")) return cardQuery(lower.drop(1).trim())
            queryOf(lower)?.let { return it }
            chrome(lower, head, rest)?.let { return it }

            // ---- the table's own words: only as the whole line (1.0.87, the red team: "battle fader", "draw muscle") --
            when (head) {
                "draw", "d", "dr" -> when {
                    counted -> { val k = n ?: 1; return one(DuelAction.Draw(seat, k), if (k == 1) "Draw" else "Draw $k") }
                    !namesCard(lower) -> return Parsed.Problem("Draw how many? “draw 2”")
                }
                "mill", "dump" -> when {
                    counted -> {
                        val k = (n ?: 1).coerceAtLeast(1)
                        val top = s.seats[seat].deck.take(k)
                        if (top.size < k) return Parsed.Problem("The deck holds only ${top.size}")
                        return Parsed.Actions(top.map { DuelAction.Move(it, Place.Pile(seat, PileKind.GY), how = "send") }, "Mill $k")
                    }
                    !namesCard(lower) -> return Parsed.Problem("Mill how many? “mill 3”")
                }
                "shuffle" -> if (bare || rest.size == 1 && rest[0] in setOf("hand", "extra", "ed", "deck", "dk", "ex")) {
                    val pile = when (rest.firstOrNull()) {
                        "hand" -> PileKind.HAND
                        "extra", "ed", "ex" -> PileKind.EXTRA
                        else -> PileKind.DECK
                    }
                    return one(DuelAction.Shuffle(seat, pile), "Shuffle ${pile.label}")
                }
                "lp", "life" -> return lp(rest)
                "dp", "draw-phase" -> if (bare) return phase(DuelPhase.DRAW)
                "sp", "standby" -> if (bare) return phase(DuelPhase.STANDBY)
                // m1 and m2 are phases only alone: "m1 gy", "m2 om3" are coordinates (1.0.87).
                "m1", "mp1", "main1" -> if (bare) return phase(DuelPhase.MAIN1)
                "bp", "battle" -> if (bare) return phase(DuelPhase.BATTLE)
                "m2", "mp2", "main2" -> if (bare) return phase(DuelPhase.MAIN2)
                "ep" -> if (bare) return phase(DuelPhase.END)
                // From the End Phase, next is the other player's turn.
                "next", "np" -> if (bare) return if (s.phase == DuelPhase.END) endTurn() else phase(s.phase.next())
                "end", "pass", "et" -> if (bare || rest == listOf("turn")) return endTurn()
                "accept", "yes" -> if (bare) {
                    val p = s.proposal ?: return Parsed.Problem("Nothing was asked")
                    if (seat == p.seat) return Parsed.Problem("The other player answers that")
                    return if (p.end) one(DuelAction.EndTurn, "End turn") else one(DuelAction.Phase(p.phase ?: s.phase), "${p.phase?.label} Phase")
                }
                "decline", "no" -> if (bare && s.proposal != null) return one(DuelAction.Decline(seat), "Not yet")
                "lock" -> if (!namesCard(lower)) {
                    var text = line.substringAfter(' ', "").trim()
                    var until = com.kaiharimoto.mastertool.core.duel.Lock.UNTIL_TURN
                    Regex("\\s*\\((?:until )?(?:the )?(?:end of (?:the )?)?(turn|chain|duel)\\)\\s*$|\\s+until (?:the )?(?:end of (?:the )?)?(turn|chain|duel)\\s*$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
                        until = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase()
                        text = text.substring(0, m.range.first).trim()
                    }
                    if (text.isEmpty()) return Parsed.Problem("Lock what? “lock Synchro Monsters only from the Extra Deck”")
                    return one(DuelAction.Lock(seat, text, until), "Lock")
                }
                "unlock" -> if (counted) {
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
                    return when (val l = look(q, Want.ANY, everywhere = true)) {
                        is Lookup.One -> s.cards.getValue(l.uid).let { Parsed.Ruling(it.code.takeIf { c -> c != 0 }, catalog.nameOf(it), text) }
                        else -> Parsed.Ruling(null, q, text)
                    }
                }
                "coin", "flip-coin" -> if (bare) return one(DuelAction.Coin(seat), "Coin")
                "dice", "die", "roll" -> if (bare) return one(DuelAction.Dice(seat), "Die")
                "resolve", "res" -> if (bare || (rest.size == 1 && rest.single() in setOf("keep", "stay", "stays", "chain", "it"))) {
                    if (s.chain.isEmpty()) return Parsed.Problem("There is no chain to resolve")
                    val keep = rest.firstOrNull() in setOf("keep", "stay", "stays")
                    return Parsed.Actions(DuelVerbs.resolve(s, catalog, keep), "Resolve")
                }
                "think", "thinking", "wait" -> if (bare) return one(DuelAction.Thinking(seat, true), "Thinking")
                "ready" -> if (bare) return one(DuelAction.Thinking(seat, false), "Ready")
                "concede", "surrender" -> if (bare) return one(DuelAction.Concede(seat), "Concede")
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
                "look", "peek", "top", "excavate" -> if (counted) {
                    val k = (n ?: 1).coerceAtLeast(1)
                    val top = s.seats[seat].deck.take(k)
                    if (top.isEmpty()) return Parsed.Problem("The deck is empty")
                    return one(DuelAction.Reveal(seat, top, to = if (head == "excavate") null else seat), "${head.replaceFirstChar { it.uppercase() }} $k")
                }
                "token", "tokens" -> if (!namesCard(lower)) return token(rest)
                "clear" -> if (bare || rest == listOf("chain")) return one(DuelAction.ChainClear, "Clear the chain")
                // A chain link for a card where it stands (an effect on the field or in the GY), nothing moved.
                // "effect veiler" is Effect Veiler, activated (the red team: it was a link with nothing discarded).
                "link", "effect" -> if (!bare && namesCard(lower)) {
                    if (head == "effect") return cardCommand(listOf("activate") + words)
                } else if (!bare) {
                    val q = rest.joinToString(" ")
                    val uid = when (val l = look(q, Want.ANY)) {
                        is Lookup.One -> l.uid
                        is Lookup.Many -> return Parsed.Problem(manyWords(q, l))
                        is Lookup.None -> return none(l)
                    }
                    return Parsed.Actions(listOf(DuelAction.ChainAdd(seat, uid)), "Effect: ${label(uid, DuelNotation.parse(q) == null)}")
                }
            }

            // ---- attacks, arrows, counters, materials ---------------------------------------------------
            if ((head == "attack" || head == "at" || " attacks " in " $lower ") && !namesCard(lower)) return attack(lower)
            // "a m3 om1" / "a m3 direct": Activate's key letter attacks only with two monsters, or direct (1.0.87).
            if (head == "a" && rest.size == 2 && attackShape(rest[0], rest[1])) return attack("attack ${rest[1]} with ${rest[0]}")
            if (head in setOf("target", "t", "point") && " with " in " $lower ") return targetWith(rest)
            if (head in setOf("counter", "counters", "c") && rest.size >= 2 && rest.any { it.matches(DELTA) }) return counter(rest)
            if (head == "detach" && rest.size == 1 && DuelNotation.parse(rest.single())?.kind?.onField == true) {
                val host = DuelNotation.at(s, rest.single(), seat, secret) ?: return Parsed.Problem("Nothing is in ${rest.single()}")
                val material = s.cards[host]?.under?.firstOrNull() ?: return Parsed.Problem("${label(host, false)} has no materials")
                val r = DuelVerbs.actions(s, seat, material, DuelVerb.DETACH, catalog)
                r.problem?.let { return Parsed.Problem(it) }
                return Parsed.Actions(r.actions, "Detach from ${label(host, false)}")
            }
            // A coordinate alone reads the card there (1.0.87); "m1"/"m2" alone stayed phases above.
            if (bare) DuelNotation.parse(head)?.let { c ->
                if (c.index == null && c.kind.pile != null) return Parsed.Problem("Which card of ${DuelNotation.label(c)}? ${c}1, ${c}2…")
                val uid = DuelNotation.at(s, c, seat, secret) ?: return Parsed.Problem("Nothing is in $c")
                return Parsed.Ui(UiKind.READ, head, uid = uid)
            }
            return cardCommand(words)
        }

        // ---- questions and the chrome ------------------------------------------------------------------

        fun cardQuery(q: String): Parsed {
            if (q.isEmpty()) return Parsed.Problem("Which card? “?m3”")
            queryOf(q)?.let { return it }
            return when (val l = look(q, Want.ANY, everywhere = DuelNotation.parse(q) == null)) {
                is Lookup.One -> Parsed.Query(QueryKind.CARD, arg = q, uid = l.uid)
                is Lookup.Many -> Parsed.Problem(manyWords(q, l))
                is Lookup.None -> none(l)
            }
        }

        /** Open a pile's strip, close it, read a card, cue Ai, swap seats, undo, redo. */
        fun chrome(lower: String, head: String, rest: List<String>): Parsed? {
            when (lower) {
                "close", "close it", "close pile", "close strip" -> return Parsed.Ui(UiKind.CLOSE)
                "swap", "swap seats", "switch seats", "other seat" -> return Parsed.Ui(UiKind.SWAP)
                "undo", "take back", "take it back" -> return Parsed.Ui(UiKind.UNDO)
                "redo" -> return Parsed.Ui(UiKind.REDO)
                "no response", "no resp", "nr", "let it resolve" -> return Parsed.Ui(UiKind.CUE, lower, cue = AiCue.NO_RESPONSE)
                "over to you", "pass priority" -> return Parsed.Ui(UiKind.CUE, lower, cue = AiCue.PASS)
                // With a chain open, "pass" passes priority; otherwise it ends the turn, as it always has.
                "pass" -> if (s.chain.isNotEmpty()) return Parsed.Ui(UiKind.CUE, lower, cue = AiCue.PASS)
                "go", "go ahead", "your move", "your turn", "ai go" -> return Parsed.Ui(UiKind.CUE, lower, cue = AiCue.YOUR_MOVE)
                "done", "done responding", "i'm done" -> return Parsed.Ui(UiKind.CUE, lower, cue = AiCue.DONE)
                "don't wait", "dont wait", "go on" -> return Parsed.Ui(UiKind.CUE, lower, cue = AiCue.DONT_WAIT)
                "catch up", "catchup" -> return Parsed.Ui(UiKind.CUE, CUE_CATCH_UP)
                "respond", "i respond", "hold on" -> return Parsed.Ui(UiKind.CUE, CUE_RESPOND)
            }
            if (head in setOf("open", "look", "browse") && rest.isNotEmpty()) {
                val what = rest.joinToString(" ").removePrefix("at ").trim()
                val q = queryOf(what)
                if (q != null && q.kind in setOf(QueryKind.GY, QueryKind.BANISHED, QueryKind.EXTRA, QueryKind.DECK)) {
                    val pile = when (q.kind) {
                        QueryKind.GY -> PileKind.GY
                        QueryKind.BANISHED -> PileKind.BANISHED
                        QueryKind.EXTRA -> PileKind.EXTRA
                        else -> PileKind.DECK
                    }
                    if (q.theirs && s.solo) return Parsed.Problem("There is no other player at this table")
                    return Parsed.Ui(UiKind.OPEN, what, seat = if (q.theirs) 1 - seat else seat, pile = pile)
                }
                if (head == "look" && rest.firstOrNull() == "at") return read(what)
                if (head == "open") return Parsed.Problem("Open which pile? gy, ogy, ban, oban, ex, oex, dk")
            }
            if (head in setOf("read", "inspect") && rest.isNotEmpty()) return read(rest.joinToString(" "))
            return null
        }

        fun read(what: String): Parsed {
            queryOf(what)?.let { return it }
            return when (val l = look(what, Want.ANY, everywhere = DuelNotation.parse(what) == null)) {
                is Lookup.One -> Parsed.Ui(UiKind.READ, what, uid = l.uid)
                is Lookup.Many -> Parsed.Problem(manyWords(what, l))
                is Lookup.None -> none(l)
            }
        }

        // ---- attacks ----------------------------------------------------------------------------------

        /** "a X Y" is an attack when X is your monster on the field and Y their monster, or direct. */
        fun attackShape(x: String, y: String): Boolean {
            val by = look(x, Want.ANY, fieldOnly = true) as? Lookup.One ?: return false
            val at = s.placeOf(by.uid)
            if (at !is Place.Zone || (at.kind != ZoneKind.MONSTER && at.kind != ZoneKind.EMZ) || s.cards[by.uid]?.controller != seat) return false
            if (y in DIRECT) return true
            val t = look(y, Want.TARGET, fieldOnly = true) as? Lookup.One ?: return false
            val there = s.placeOf(t.uid)
            return there is Place.Zone && (there.kind == ZoneKind.MONSTER || there.kind == ZoneKind.EMZ) && s.cards[t.uid]?.controller != seat
        }

        fun attack(line: String): Parsed {
            val (by, at) = when {
                line.startsWith("attack ") || line.startsWith("at ") -> {
                    val rest = line.substringAfter(' ').trim()
                    val with = rest.lastIndexOf(" with ")
                    val parts = rest.split(' ')
                    when {
                        rest.startsWith("with ") -> rest.removePrefix("with ").split(' ').filterNot { it in DIRECT }.joinToString(" ").trim() to "directly"
                        with >= 0 -> rest.substring(with + 6).trim() to rest.substring(0, with).trim()
                        // "attack m3 om1", "attack m3 direct": the attacker first.
                        parts.size >= 2 && parts.last() in DIRECT -> parts.dropLast(1).joinToString(" ") to "directly"
                        parts.size == 2 -> parts[0] to parts[1]
                        else -> return Parsed.Problem("Attack with which monster? “attack arias with zeus”, “a m3 om1”, or “zeus attacks directly”")
                    }
                }
                else -> line.substringBefore(" attacks ").trim() to line.substringAfter(" attacks ").trim()
            }
            val attacker = when (val l = look(by, Want.ANY, fieldOnly = true)) {
                is Lookup.One -> l.uid
                is Lookup.Many -> return Parsed.Problem(manyWords(by, l))
                is Lookup.None -> return if (DuelNotation.parse(by) != null || l.choices.isNotEmpty()) none(l) else Parsed.Problem("No monster of yours on the field matches “$by”")
            }
            if (s.cards[attacker]?.controller != seat) return Parsed.Problem("Attack with a monster you control")
            val target = if (at in DIRECT || at.isBlank()) null else when (val l = look(at, Want.TARGET, fieldOnly = true)) {
                is Lookup.One -> l.uid.takeIf { s.cards[it]?.controller != seat } ?: return Parsed.Problem("Attack a monster the other player controls")
                is Lookup.Many -> return Parsed.Problem(manyWords(at, l))
                is Lookup.None -> return if (DuelNotation.parse(at) != null || l.choices.isNotEmpty()) none(l) else Parsed.Problem("No monster of theirs matches “$at”")
            }
            // Through the verb, so the Battle Phase and the turn player are checked as a click is (1.0.87, the red team).
            val r = DuelVerbs.actions(s, seat, attacker, DuelVerb.ATTACK, catalog, host = target, direct = target == null)
            r.problem?.let { return Parsed.Problem(it) }
            return Parsed.Actions(r.actions, "Attack")
        }

        fun targetWith(rest: List<String>): Parsed {
            val body = rest.joinToString(" ")
            val target = body.substringBeforeLast(" with ").trim()
            val by = body.substringAfterLast(" with ").trim()
            val from = when (val l = look(by, Want.ANY)) {
                is Lookup.One -> l.uid
                is Lookup.Many -> return Parsed.Problem(manyWords(by, l))
                is Lookup.None -> return none(l)
            }
            val to = when (val l = look(target, Want.TARGET)) {
                is Lookup.One -> l.uid
                is Lookup.Many -> return Parsed.Problem(manyWords(target, l))
                is Lookup.None -> return none(l)
            }
            return Parsed.Actions(listOf(DuelAction.Target(seat, from, listOf(to))), "Target ${label(to, false)} with ${label(from, false)}")
        }

        fun counter(rest: List<String>): Parsed {
            val at = rest.indexOfFirst { it.matches(DELTA) }
            val q = rest.take(at).joinToString(" ").ifBlank { return Parsed.Problem("Counters on which card? “counter m3 +2”") }
            val delta = rest[at].removePrefix("+").toInt()
            if (delta == 0) return Parsed.Problem("How many counters? “counter m3 +2” or “counter m3 -1”")
            val kind = rest.drop(at + 1).joinToString(" ").removeSuffix(" counters").removeSuffix(" counter").trim()
            val uid = when (val l = look(q, Want.AWAY)) {
                is Lookup.One -> l.uid
                is Lookup.Many -> return Parsed.Problem(manyWords(q, l))
                is Lookup.None -> return none(l)
            }
            val k = kind.ifBlank { if (delta < 0) s.cards[uid]?.counters?.keys?.firstOrNull() ?: "" else "" }
            return Parsed.Actions(listOf(DuelAction.Counter(uid, delta, k)), "Counter ${if (delta > 0) "+$delta" else "$delta"}: ${label(uid, false)}")
        }

        fun phase(p: DuelPhase): Parsed =
            if (!s.solo && seat != s.active) Parsed.Actions(listOf(DuelAction.Propose(seat, p)), "Ask for the ${p.label} Phase")
            else Parsed.Actions(listOf(DuelAction.Phase(p)), "${p.label} Phase")

        fun endTurn(): Parsed =
            if (!s.solo && seat != s.active) Parsed.Actions(listOf(DuelAction.Propose(seat, end = true)), "Ask to end the turn")
            else Parsed.Actions(listOf(DuelAction.EndTurn), "End turn")

        /**
         * `token [n] [name] [atk N] [def N] [atk|def|def-pos] [their] [zone]` (1.0.79): a token's stats, its
         * position, and the side it goes to ("their" puts it on the other player's field).
         */
        fun token(rest: List<String>): Parsed {
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
                    w in setOf("to", "in", "into") && rest.getOrNull(i + 1)?.let { destZone(it) } != null -> Unit
                    destZone(w) != null -> {
                        val z = destZone(w)!!
                        zoneWord = z
                        if (DuelNotation.parse(w)?.mine == false) side = 1 - seat
                    }
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

        fun lp(rest: List<String>): Parsed {
            if (rest.isEmpty()) return Parsed.Query(QueryKind.LP)
            var target = seat
            var args = rest
            when (args.firstOrNull()) {
                "opp", "op", "o", "them", "their", "theirs", "opponent", "b" -> { target = 1 - seat; args = args.drop(1) }
                "me", "my", "mine", "a" -> { target = seat; args = args.drop(1) }
            }
            if (target != seat && s.solo) return Parsed.Problem("There is no other player at this table")
            val expr = args.joinToString("").ifEmpty { return Parsed.Query(QueryKind.LP) }
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

        // ---- cards ------------------------------------------------------------------------------------

        /** A destination zone: the old words ("m3", "emz left", "p1", "fz") or a coordinate ("om1", "e2", "ofz"). */
        fun destZone(word: String): Place.Zone? {
            DuelNotation.parse(word)?.let { c -> if (c.kind.onField) return DuelNotation.toPlace(c, seat) as Place.Zone }
            return zoneOf(word, seat)
        }

        /** Whether words after "to" are a place: a zone, a pile, the deck's ends, the field, a coordinate. */
        fun isPlace(d: List<String>): Boolean {
            val w = d.joinToString(" ")
            return destZone(d.joinToString("")) != null || pileWords[w] != null || w == "field" ||
                w in setOf("deck", "top", "deck top", "top of deck", "bottom", "deck bottom", "bottom of deck") ||
                (d.size == 1 && DuelNotation.parse(w) != null)
        }

        fun attachHost(d: String): Lookup {
            val l = look(d, Want.ANY, fieldOnly = true)
            return if (l is Lookup.One && s.placeOf(l.uid) !is Place.Zone) Lookup.None("Materials go under a card on the field") else l
        }

        fun cardCommand(words: List<String>): Parsed {
            // A verb key's letter is a verb only before a coordinate (1.0.87): "g om3", never "g ash".
            val first = words.first()
            var verb: DuelVerb? = verbWords[first]?.takeIf { first !in letters || words.getOrNull(1)?.let { w -> DuelNotation.parse(w) != null } == true }
            var body = if (verb != null) words.drop(1) else words
            var defense = first == "def"
            val wholeName by lazy { namesCard(body.joinToString(" ")) }

            // "… to <destination>" (also "in", "into", "on" for a zone): the last one whose rest is a place,
            // so a name with "to" in it ("Back to Square One") stays whole.
            var dest: List<String> = emptyList()
            for (i in body.indices.reversed()) {
                if (body[i] !in setOf("to", "in", "into", "on", "onto")) continue
                val d = body.drop(i + 1).joinToString(" ").replace(asContinuous, "").split(" ").filter { it.isNotEmpty() }
                if (d.isEmpty() || i == 0) continue
                // A name with " to " in it is a host only when no verb says otherwise and the whole is no card's name (1.0.87).
                val host = body[i] == "to" && (verb == null || verb == DuelVerb.ATTACH) && !wholeName && attachHost(d.joinToString(" ")) is Lookup.One
                if (isPlace(d) || host) {
                    dest = d
                    body = body.take(i)
                    break
                }
            }
            // "s h2 m3", "o h4 m3", "move m3 m4": a verb, a card, then a coordinate is where it goes (1.0.87).
            if (dest.isEmpty() && verb != null && body.size >= 2 && DuelNotation.parse(body.last()) != null) {
                dest = listOf(body.last())
                body = body.dropLast(1)
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
            if (verb == null && dest.isEmpty() && body.size >= 2 && verbWords.containsKey(body.last()) && body.last() !in letters) {
                val whole = body.joinToString(" ")
                val named = look(whole, Want.ANY, everywhere = true)
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
            var d = dest.joinToString(" ")
            // A pile coordinate as a destination is that pile ("to ogy": the card goes to its owner's GY anyway).
            var handAt: Int? = null
            DuelNotation.parse(d)?.takeIf { it.kind.pile != null }?.let { c ->
                d = when (c.kind) {
                    DuelNotation.Kind.HAND -> { handAt = c.index; "hand" }
                    DuelNotation.Kind.GY -> "gy"
                    DuelNotation.Kind.BANISHED -> "banished"
                    DuelNotation.Kind.EXTRA -> "extra"
                    else -> "deck"
                }
            }
            val zone = if (dest.isNotEmpty() && pileWords[d] == null && d != "deck") destZone(dest.joinToString("")) else null
            val want = wantOf(verb, d, zone)
            val byCoord = DuelNotation.parse(query) != null || Regex("^(their|my) \\S+$").find(query)?.let { DuelNotation.parse(query.substringAfter(' ')) != null } == true
            val uid = when (val l = look(query, want, from, fromField)) {
                is Lookup.One -> l.uid
                is Lookup.Many -> return Parsed.Problem(manyWords(query, l))
                is Lookup.None -> return none(l)
            }
            // A card the seat cannot see is reachable only by its coordinate, and only by verbs that need not know what it is.
            val blind = byCoord && !DuelSight.sees(s, uid, seat)
            val name = label(uid, !byCoord)
            val named = if (byCoord) emptySet() else setOf(uid)
            val where = s.placeOf(uid)
            val inHand = where is Place.Pile && where.kind == PileKind.HAND
            val kind = if (blind) null else DuelVerbs.kindOf(s.cards.getValue(uid), catalog)
            val spellish = kind == CardKind.SPELL || kind == CardKind.TRAP || kind == CardKind.FIELD_SPELL

            var host: Int? = null
            var placeZone: Place.Zone? = zone
            if (dest.isNotEmpty()) {
                when {
                    // A material goes under the card in the zone named: "attach h4 to m3", "o h4 m3".
                    zone != null && verb == DuelVerb.ATTACH -> {
                        host = s.at(zone) ?: return Parsed.Problem("Nothing is in ${DuelNotation.slotCoord(zone, seat)} to attach to")
                        placeZone = null
                    }
                    // The hand at a place: "move h3 to h1".
                    handAt != null && inHand && (where as Place.Pile).seat == s.cards.getValue(uid).owner -> {
                        val at = handAt!!.coerceAtMost(s.seats[where.seat].hand.size - 1)
                        return Parsed.Actions(listOf(DuelAction.Move(uid, Place.Pile(where.seat, PileKind.HAND, at), how = "return")), "Move in the hand: $name", named)
                    }
                    zone != null -> if (verb == null) verb = when {
                        where is Place.Zone -> DuelVerb.MOVE
                        blind -> return blindProblem(dest.joinToString(" "))
                        zone.kind == ZoneKind.MONSTER || zone.kind == ZoneKind.EMZ -> if (spellish) DuelVerb.PLACE else DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.SPECIAL
                        // A Spell or Trap from the hand to its zone is played as it would be played; anything else is placed.
                        inHand && spellish -> DuelVerbs.default(s, seat, uid, catalog).takeIf { it in placing } ?: DuelVerb.ACTIVATE
                        else -> DuelVerb.PLACE
                    }
                    d == "deck" || d == "top" || d == "deck top" || d == "top of deck" -> if (verb == null || verb == DuelVerb.DEFAULT) verb = DuelVerb.DECK_TOP
                    d == "bottom" || d == "deck bottom" || d == "bottom of deck" -> verb = DuelVerb.DECK_BOTTOM
                    d == "field" -> {
                        if (blind) return blindProblem("field")
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
                        host = when (val l = attachHost(d)) {
                            is Lookup.One -> l.uid
                            is Lookup.Many -> return Parsed.Problem(manyWords(d, l))
                            is Lookup.None -> return Parsed.Problem("Where is “$d”? Try hand, gy, deck, m3, s2, emz left, fz, or a card on the field")
                        }
                        verb = DuelVerb.ATTACH
                    }
                }
            }
            if (blind && (verb ?: DuelVerb.DEFAULT) !in BLIND_VERBS) return blindProblem(null)
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
            val shown = if (v == DuelVerb.DEFAULT) DuelVerbs.default(s, seat, uid, catalog) else v
            return Parsed.Actions(actions, "${shown.label}: $name", named)
        }

        fun blindProblem(dest: String?): Parsed = Parsed.Problem(
            "You cannot see that card, so say what to do with it: target, attack, flip, gy, banish, hand, deck, move, attach or counter" +
                (dest?.let { " (“move … to $it”)" } ?: ""),
        )

        fun none(l: Lookup.None): Parsed = Parsed.Problem(l.why, l.choices, l.query)
    }

    private val DIRECT = setOf("direct", "directly", "d", "dir", "lp", "face")
    private val DELTA = Regex("^[+-]?\\d+$")

    /** The verbs a hidden card takes: none of them needs to know what it is (1.0.87, the red team). */
    private val BLIND_VERBS = setOf(
        DuelVerb.TARGET, DuelVerb.ATTACK, DuelVerb.FLIP, DuelVerb.GRAVE, DuelVerb.BANISH, DuelVerb.BANISH_DOWN, DuelVerb.HAND,
        DuelVerb.DECK_TOP, DuelVerb.DECK_BOTTOM, DuelVerb.ATTACH, DuelVerb.MOVE, DuelVerb.REVEAL, DuelVerb.COUNTER_UP, DuelVerb.COUNTER_DOWN,
    )

    /**
     * The words that ask rather than act (1.0.87): `hand`, `field`, `their field`, `board`, `gy`, `ogy`, `ban`, `oban`,
     * `ex`, `oex`, `dk`, `odk`, `lp`, `chain`, `turn` — and the same with "my"/"their" in front.
     */
    fun queryOf(text: String): Parsed.Query? {
        var t = text.trim().lowercase().trimEnd('?', '.', '!').trim().removePrefix("the ").trim()
        var theirs = false
        Regex("^(their|theirs|opp|opponent's|opponents|opponent|the opponent's)\\s+").find(t)?.let { theirs = true; t = t.substring(it.range.last + 1) }
        Regex("^(my|mine|own|your)\\s+").find(t)?.let { t = t.substring(it.range.last + 1) }
        if (t.length >= 2 && t.startsWith("o") && !theirs && t.drop(1) in setOf("gy", "ban", "ex", "dk", "hand", "field")) { theirs = true; t = t.drop(1) }
        val kind = when (t) {
            "hand", "cards in hand" -> QueryKind.HAND
            "field", "side", "side of the field" -> QueryKind.FIELD
            "board", "table", "both fields" -> if (theirs) null else QueryKind.BOARD
            "gy", "graveyard", "grave", "grave yard" -> QueryKind.GY
            "ban", "banished", "banishment", "banished cards" -> QueryKind.BANISHED
            "ex", "extra", "extra deck", "ed" -> QueryKind.EXTRA
            "dk", "deck" -> QueryKind.DECK
            "lp", "life", "life points", "lps" -> if (theirs) null else QueryKind.LP
            "chain" -> if (theirs) null else QueryKind.CHAIN
            "turn", "phase", "whose turn" -> if (theirs) null else QueryKind.TURN
            else -> null
        } ?: return null
        return Parsed.Query(kind, theirs)
    }

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
        "do" to DuelVerb.DEFAULT,
        // The duel's verb keys (`DeskShortcuts`, DUEL scope), as a head before a coordinate (1.0.87); Shift's verbs by two letters.
        "a" to DuelVerb.ACTIVATE, "s" to DuelVerb.SUMMON, "e" to DuelVerb.SET, "p" to DuelVerb.POSITION, "f" to DuelVerb.FLIP,
        "g" to DuelVerb.GRAVE, "b" to DuelVerb.BANISH, "h" to DuelVerb.HAND, "k" to DuelVerb.DECK_TOP, "x" to DuelVerb.EXTRA,
        "o" to DuelVerb.ATTACH, "r" to DuelVerb.REVEAL, "c" to DuelVerb.COUNTER_UP, "t" to DuelVerb.TARGET, "m" to DuelVerb.MOVE,
        "bd" to DuelVerb.BANISH_DOWN, "kb" to DuelVerb.DECK_BOTTOM, "cd" to DuelVerb.COUNTER_DOWN,
    )

    /** The one- and two-letter verbs: verbs only before a coordinate. */
    private val letters = setOf("a", "s", "e", "p", "f", "g", "b", "h", "k", "x", "o", "r", "c", "t", "m", "bd", "kb", "cd")

    /** Every verb word the line knows, for completion: word → verb. */
    val VERB_WORDS: Map<String, DuelVerb> get() = verbWords

    private val pileWords: Map<String, PileKind> = mapOf(
        "hand" to PileKind.HAND, "deck" to PileKind.DECK, "extra" to PileKind.EXTRA, "ed" to PileKind.EXTRA,
        "gy" to PileKind.GY, "grave" to PileKind.GY, "graveyard" to PileKind.GY,
        "banish" to PileKind.BANISHED, "banished" to PileKind.BANISHED, "removed" to PileKind.BANISHED, "exile" to PileKind.BANISHED,
    )

    /** Words that only say a placed card lies face-up as a Spell — "as continuous", "as a Continuous Spell". */
    private val asContinuous = Regex("\\s+(as\\s+)?(an?\\s+)?(face-?up\\s+)?(continuous|face-?up)(\\s+(spell|trap|card))?$")

    private val placing = setOf(DuelVerb.SUMMON, DuelVerb.SPECIAL, DuelVerb.SET, DuelVerb.ACTIVATE)

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
     * 0 sees them, so combos written before still replay. Table notation's `e1`/`e2` (1.0.87) are that same
     * absolute left and right ([DuelNotation]).
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
     * says "their" (or "opp"), is a `#uid`, or the verb is a target.
     *
     * 1.0.87: a table coordinate (`m3`, `oh2`, `gy3`) is the card there, before any name — a hidden one too, since
     * where a card lies is no secret (but with [everywhere], which names what it finds, only one the seat sees); a
     * whole pile (`gy`) is no single card. Names are looked for **tier by tier**, in the order [want] reaches —
     * the Deck first for a search, the hand first to play, the field first to send away — and the first tier
     * with a match wins (the red team: Snake-Eye Ash in hand lost to Ash Blossom in the Deck); among one tier's
     * equally good matches, different names are [Lookup.Many], and copies of one name on the field are refused
     * with their coordinates. When no spelling matches, the names in reach are matched forgivingly — by edit
     * distance ([NameMatch]) and by sound ([Phonetic]) — a sure match taken, else "Did you mean". Only what the
     * seat may see is ever matched by name, plus its own Deck and Extra Deck (it knows its list).
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
        secret: Long = 0L,
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
        // A coordinate, as an exact word: the card there (1.0.87). "their m3" is om3.
        DuelNotation.parse(q)?.let { c0 ->
            val c = if (side == other && c0.mine && c0.kind != DuelNotation.Kind.EMZ) c0.copy(mine = false) else c0
            if (c.index == null && c.kind.pile != null) return Lookup.None("Which card of ${DuelNotation.label(c)}? ${c}1, ${c}2… or its name")
            if (fieldOnly && !c.kind.onField) return Lookup.None("$c is not on the field")
            val uid = DuelNotation.at(s, c, seat, secret) ?: return Lookup.None("Nothing is in $c")
            if (everywhere && !DuelSight.sees(s, uid, seat)) return Lookup.None("You cannot see the card in $c")
            return Lookup.One(uid)
        }
        fun field(of: Int) = s.onField().filter { s.cards[it]?.controller == of }
        fun materials(of: Int) = field(of).flatMap { s.cards[it]?.under ?: emptyList() }
        fun piles(of: Int, vararg kinds: PileKind) = kinds.flatMap { s.seats[of].pile(it) }
        fun tiers(of: Int): List<List<Int>> = when {
            fieldOnly -> listOf(field(of))
            from != null -> listOf(s.seats[of].pile(from))
            else -> when (want) {
                Want.HAND -> listOf(piles(of, PileKind.DECK), piles(of, PileKind.GY, PileKind.BANISHED), field(of), piles(of, PileKind.EXTRA), materials(of))
                Want.PLAY -> listOf(piles(of, PileKind.HAND), field(of), piles(of, PileKind.GY, PileKind.BANISHED), piles(of, PileKind.EXTRA), piles(of, PileKind.DECK), materials(of))
                Want.AWAY -> listOf(field(of), piles(of, PileKind.HAND), materials(of), piles(of, PileKind.GY, PileKind.BANISHED), piles(of, PileKind.DECK, PileKind.EXTRA))
                Want.TARGET -> listOf(field(of), piles(of, PileKind.GY, PileKind.BANISHED), piles(of, PileKind.HAND), materials(of))
                Want.ANY -> listOf(piles(of, PileKind.HAND), field(of), piles(of, PileKind.GY, PileKind.BANISHED), materials(of), piles(of, PileKind.EXTRA, PileKind.DECK))
            }
        }
        // The deck and Extra Deck are searchable by their owner, who knows the decklist; nothing else hidden is.
        fun visible(uid: Int) = DuelSight.sees(s, uid, seat) ||
            (s.cards[uid]?.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) })
        val tiers = when (side) {
            seat -> tiers(seat)
            other -> tiers(other)
            else -> if (want == Want.TARGET) tiers(other) + tiers(seat) else tiers(seat) + tiers(other)
        }.map { tier ->
            // A search reaches into the Deck: never for a card already in the hand.
            tier.filter { uid -> visible(uid) && !(want == Want.HAND && s.placeOf(uid).let { it is Place.Pile && it.kind == PileKind.HAND }) }
        }.filter { it.isNotEmpty() }
        val name = { uid: Int -> catalog.nameOf(s.cards.getValue(uid)) }

        fun pick(best: List<Int>): Lookup {
            val names = best.map(name).distinct()
            if (names.size > 1) return Lookup.Many(names)
            // Copies of one name on the field are different cards to a player: say which (1.0.87, the red team).
            val onField = best.filter { s.placeOf(it) is Place.Zone }.distinct()
            if (onField.size > 1 && onField.size == best.size) {
                val coords = onField.mapNotNull { DuelNotation.coordOf(s, it, seat, secret) }
                return Lookup.None("${names.single()} is on the field more than once: say which — ${coords.joinToString(" or ")}", coords, q)
            }
            return Lookup.One(best.first())
        }

        // Spelling: a prefix, word starts or initials first (60+), tier by tier; then a substring (50), tier by tier.
        for (floor in listOf(60, 50)) {
            for (tier in tiers) {
                var best = 0
                val top = mutableListOf<Int>()
                tier.forEach { uid ->
                    val score = NameScore.of(q, name(uid)).takeIf { it >= floor } ?: 0
                    when {
                        score > best -> { best = score; top.clear(); top += uid }
                        score == best && score > 0 -> top += uid
                    }
                }
                if (top.isNotEmpty()) return pick(top)
            }
        }
        // Forgiving (1.0.87): spelling by edit distance, or sound — sure, tier by tier; else "Did you mean".
        val whose = when (side) {
            seat -> "of yours "
            other -> "of theirs "
            else -> ""
        }
        val why = "No card ${whose}you can see matches “$q”" + if (side == seat && want != Want.TARGET) " (theirs: “their $q”)" else ""
        val near = mutableListOf<Pair<String, Double>>()
        for (tier in tiers) {
            val scored = tier.map { it to maxOf(NameMatch.score(q, name(it)), Phonetic.score(q, name(it))) }
            val best = scored.maxOfOrNull { it.second } ?: continue
            if (best >= NameMatch.SURE) {
                val top = scored.filter { it.second == best }.map { it.first }
                val names = top.map(name).distinct()
                if (names.size == 1) return pick(top)
                return Lookup.None("$why. Did you mean: ${names.take(3).mapIndexed { i, n -> "${i + 1}. $n" }.joinToString(" ")}?", names.take(3), q)
            }
            scored.forEach { (uid, score) -> if (score >= 0.45) near += name(uid) to score }
        }
        val choices = near.sortedByDescending { it.second }.map { it.first }.distinct().take(3)
        if (choices.isEmpty()) return Lookup.None(why, query = q)
        return Lookup.None("$why. Did you mean: ${choices.mapIndexed { i, n -> "${i + 1}. $n" }.joinToString(" ")}?", choices, q)
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
