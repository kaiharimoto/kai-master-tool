package com.kaiharimoto.mastertool.core.duel.voice

import com.kaiharimoto.mastertool.core.ai.voice.Hints
import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.PileKind
import com.kaiharimoto.mastertool.core.duel.Place
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand

/**
 * What was said at the table, understood (1.0.87, Command mode): a transcriber's words — "Summon Ash Blossom to
 * monster three.", "Attack their monster one with my monster three", "Yes." — turned into the Line's own language
 * ([normalize]) and sorted into what to do with them ([classify]). Pure, and held to a corpus of real phrasings.
 *
 * Every move heard is a [Spoken.Command] the Line previews and the person confirms ("yes", Enter) — the end of the
 * turn and a phase change too; only a question, a cue to Ai, undo and words to Ai need no confirm.
 */
object DuelSpeech {

    sealed interface Spoken {
        /** A move (or the chrome's words) in the Line's language: preview it, then commit on a confirm. */
        data class Command(val line: String) : Spoken
        data object Confirm : Spoken
        data object Cancel : Spoken
        data object Undo : Spoken
        data class Cue(val cue: AiCue) : Spoken
        /** A question: answer it ([com.kaiharimoto.mastertool.core.duel.text.DuelAnswer]) — nothing to confirm. */
        data class Query(val query: DuelCommand.Parsed.Query) : Spoken {
            val kind: DuelCommand.QueryKind get() = query.kind
            val arg: String get() = query.arg
        }
        data class ToAi(val text: String) : Spoken
        data class Unknown(val text: String) : Spoken
    }

    private val UNITS = mapOf(
        "zero" to 0, "oh" to -1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    ).filterValues { it >= 0 }
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
    )

    /** The coordinate pattern, as the Line reads it. */
    private const val COORD = "(?:o?(?:h\\d+|m[1-5]|s[1-5]|fz|gy\\d*|ban\\d*|ex\\d*|dk\\d*)|e[12])"

    /** A coordinate that is one card (not a whole pile). */
    private const val CARD = "(?:o?(?:h\\d+|m[1-5]|s[1-5]|fz|gy\\d+|ban\\d+|ex\\d+|dk\\d+)|e[12])"

    private val CONFIRM = setOf(
        "yes", "yeah", "yep", "yup", "yes please", "sure", "do it", "confirm", "confirmed", "correct", "that's right", "thats right",
        "ok", "okay", "go for it", "commit", "affirmative", "right", "yes do it", "that's it", "exactly",
    )
    private val CANCEL = setOf("no", "nope", "cancel", "scratch that", "never mind", "nevermind", "forget it", "wrong", "not that", "stop", "no no")
    private val UNDO = setOf("undo", "undo that", "take that back", "take it back", "go back", "undo it", "take back")
    private val NO_RESPONSE = setOf("pass", "i pass", "no response", "no responses", "let it resolve", "nothing", "no chain", "go on then")

    /** Words that begin a move or a question: a line starting with one is the Line's, even when it has a problem. */
    private val COMMANDS = setOf(
        "summon", "set", "activate", "chain", "attack", "at", "target", "send", "destroy", "tribute", "banish", "add", "search", "draw",
        "mill", "flip", "pos", "move", "place", "attach", "detach", "reveal", "counter", "token", "lp", "resolve", "bp", "m1", "m2",
        "ep", "end", "next", "ss", "special", "read", "open", "look", "discard", "return", "bounce", "spin", "excavate", "shuffle",
        "coin", "dice", "concede", "swap", "redo", "random", "spin", "accept", "decline", "lock", "unlock", "say", "note", "?", "use", "play",
    )

    /** The table's words and the command words, for the transcriber's prompt. */
    val WORDS = listOf(
        "yes", "undo", "summon", "set", "activate", "attack", "directly", "target", "banish", "graveyard", "extra deck", "monster zone",
        "spell zone", "battle phase", "main phase two", "end turn", "next phase", "resolve", "chain", "no response", "your move",
        "life points", "their field", "my hand",
    )

    // ---- normalize -----------------------------------------------------------------------------------

    /** Number words to digits: "one thousand eight hundred" → 1800, "twenty one" → 21; ordinals ("third", "3rd") too. */
    private fun numbers(words: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            ORDINALS[w]?.let { out += it.toString(); i++; return@let } ?: run {
                Regex("^(\\d+)(st|nd|rd|th)$").find(w)?.let { out += it.groupValues[1]; i++ } ?: run {
                    if (w in UNITS || w in TENS || (w.toIntOrNull() != null && words.getOrNull(i + 1) in setOf("hundred", "thousand"))) {
                        var total = 0
                        var current = 0
                        var any = false
                        while (i < words.size) {
                            val x = words[i]
                            val u = UNITS[x]
                            val t = TENS[x]
                            val d = x.toIntOrNull()
                            when {
                                u != null -> { if (any && current % 10 != 0) break; if (any && u >= 10 && current % 100 != 0) break; current += u }
                                t != null -> { if (any && current % 100 != 0) break; current += t }
                                d != null && !any -> current += d
                                x == "hundred" && any -> current = (if (current == 0) 1 else current) * 100
                                x == "thousand" && any -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                                else -> break
                            }
                            any = true
                            i++
                        }
                        out += (total + current).toString()
                    } else {
                        out += w
                        i++
                    }
                }
            }
        }
        return out
    }

    private class Rule(pattern: String, val with: (MatchResult) -> String) {
        val regex = Regex(pattern)
        constructor(pattern: String, to: String) : this(pattern, { m -> m.groupValues.indices.fold(to) { acc, k -> acc.replace("$$k", m.groupValues[k]) } })
    }

    /** "my"/"their" as a coordinate's prefix: "their" is `o`, "my" nothing. */
    private fun o(whose: String): String = if (whose.trim() == "their") "o" else ""

    private val ZONE_NOUN = "(?:spell and trap|spell trap|back row|backrow|monster|spell|trap)"
    private fun zoneLetter(noun: String) = if (noun == "monster") "m" else "s"

    private val RULES: List<Rule> = listOf(
        // Filler and politeness.
        Rule("\\b(uh+|um+|umm+|uhm|erm|er|hmm+|ah)\\b", ""),
        Rule("\\bplease\\b", ""),
        Rule("^((okay|ok|alright|all right|so|right|now|well|and)\\s+)+(?=\\S)", ""),
        Rule("^(i'll|i will|i'm going to|im going to|i am going to|i want to|i'd like to|i would like to|let's|lets|let me|we'll|i'm gonna|im gonna|i'm|i)\\s+(?=\\S)", ""),
        Rule("^go ahead and\\s+", ""),
        // One move, then another.
        Rule("\\s+(and then|then|after that|and after that)\\s+", " ; "),
        // Whose.
        Rule("\\b(the opponent's|my opponent's|opponent's|opponents|opponent|theirs|their|his|her|the other player's|other player's)\\b", "their"),
        Rule("\\bminus (\\d+)", "-$1"),
        Rule("\\bplus (\\d+)", "+$1"),
        // Life points.
        Rule("^(deal|inflict|do) (\\d+)( points of| points)?( damage)?( to their( lp| life points)?| to them)?$", "lp o -$2"),
        Rule("^(take|lose) (\\d+)( damage| life points| lp)?$", "lp -$2"),
        Rule("^(gain) (\\d+)( life points| lp)?$", "lp +$2"),
        Rule("^their (lp|life points) (-|\\+)(\\d+)$", "lp o $2$3"),
        Rule("^(my )?(lp|life points) (-|\\+)(\\d+)$", "lp $3$4"),
        Rule("^(halve|half) their (lp|life points)$", "lp o /2"),
        Rule("^(halve|half) (my )?(lp|life points)$", "lp /2"),
        Rule("^set their (lp|life points) to (\\d+)$", "lp o =$2"),
        Rule("^(how many |what are |what's |whats |what is )?(my |our |the |their )?(life points|lifepoints|life point|lps|lp)( do i have| left| does their have| are)?$", "lp"),
        // Phases and the turn.
        Rule("\\b(go to |enter |move to |going to |into |to )?(the )?battle phase\\b", "bp"),
        Rule("\\bgo to battle\\b", "bp"),
        Rule("^battle$", "bp"),
        Rule("\\b(go to |enter |move to )?(the )?(main phase 2|main 2|2 main phase|second main phase)\\b", "m2"),
        Rule("\\b(go to |enter |move to )?(the )?(main phase 1|main 1|1 main phase)\\b", "m1"),
        Rule("\\b(go to |enter |move to )?(the )?end phase\\b", "ep"),
        Rule("\\b(go to |enter |move to )?(the )?standby phase\\b", "sp"),
        Rule("\\b(go to |enter |move to )?(the )?draw phase\\b", "dp"),
        Rule("\\b(end|finish) (my |the |our )?turn\\b", "end"),
        Rule("\\bpass (the |my )?turn\\b", "end"),
        Rule("^(turn end|that's my turn|thats my turn|that's turn|done with my turn)$", "end"),
        Rule("\\b(go to |move to )?(the )?next phase\\b", "next"),
        Rule("^(whose turn is it|what turn is it|what phase is it|what phase are we in)$", "turn"),
        // "to"/"too", "for", "won" heard for a zone's number.
        Rule("\\b($ZONE_NOUN|zone|hand)( zone)? (to|too)(?=$| with| attacks| ;)", "$1$2 2"),
        Rule("\\b($ZONE_NOUN|zone|hand)( zone)? for(?=$| with| attacks| ;)", "$1$2 4"),
        Rule("\\b($ZONE_NOUN|zone|hand)( zone)? won(?=$| with| attacks| ;)", "$1$2 1"),
        // The Extra Monster Zones and the Field Zone.
        Rule("\\b(the )?left extra monster zone\\b", "e1"),
        Rule("\\b(the )?right extra monster zone\\b", "e2"),
        Rule("\\b(the )?extra monster zone ([12])\\b", "e$2"),
        Rule("\\b(the )?([12]) extra monster zone\\b", "e$2"),
        Rule("\\bemz ([12])\\b", "e$1"),
        Rule("\\b(my |their )?(the )?field( spell)? zone\\b") { m -> o(m.groupValues[1]) + "fz" },
        // Hand cards.
        Rule("\\b(the )?(\\d+) card (in|of|from) (my |their )?hand\\b") { m -> o(m.groupValues[4]) + "h" + m.groupValues[2] },
        Rule("\\bcard (\\d+) (in|of|from) (my |their )?hand\\b") { m -> o(m.groupValues[3]) + "h" + m.groupValues[1] },
        Rule("\\b(my |their )?hand (card )?(number )?(\\d+)\\b") { m -> o(m.groupValues[1]) + "h" + m.groupValues[4] },
        // Monster and Spell & Trap Zones: "my third monster", "monster zone three", "back row two".
        Rule("\\b(my |their )?(the )?([1-5]) ($ZONE_NOUN)( card)?( zone| slot)?\\b") { m -> o(m.groupValues[1]) + zoneLetter(m.groupValues[4]) + m.groupValues[3] },
        Rule("\\b(my |their )?(the )?($ZONE_NOUN)( card)?( zone| slot| number| row)? ([1-5])\\b") { m -> o(m.groupValues[1]) + zoneLetter(m.groupValues[3]) + m.groupValues[6] },
        Rule("\\b(my |their )?(the )?(m|s|h) ([1-9])\\b") { m -> o(m.groupValues[1]) + m.groupValues[3] + m.groupValues[4] },
        // Piles.
        Rule("\\b(my |their )?(the )?(graveyard|grave yard|cemetery)\\b") { m -> o(m.groupValues[1]) + "gy" },
        Rule("\\b(my|their) grave\\b") { m -> o(m.groupValues[1]) + "gy" },
        Rule("\\b(my |their )?(the )?(banished zone|banished pile|banished cards|banishment|banished|removed from play)\\b") { m -> o(m.groupValues[1]) + "ban" },
        Rule("\\b(my |their )?(the )?extra deck\\b") { m -> o(m.groupValues[1]) + "ex" },
        Rule("\\b(my|their) deck\\b") { m -> o(m.groupValues[1]) + "dk" },
        Rule("\\b(o?(gy|ban|ex|dk)) (\\d{1,2})\\b", "$1$3"),
        // Coordinates with an owner word left over.
        Rule("\\bmy (?=$COORD\\b)", ""),
        Rule("\\btheir (h\\d+|m[1-5]|s[1-5]|fz|gy\\d*|ban\\d*|ex\\d*|dk)\\b", "o$1"),
        Rule("\\b(to|from) my (hand|field)\\b", "$1 $2"),
        // Verbs.
        Rule("\\b(normal|flip|tribute) summon\\b", "summon"),
        Rule("\\bspecial summon\\b", "ss"),
        Rule("\\bin (face up )?attack position\\b", ""),
        Rule("\\bin (face up )?defen[cs]e( position)?\\b", "def"),
        Rule("\\bdefence\\b", "defense"),
        Rule("^banish (.+) face down$", "bfd $1"),
        Rule("^set (.+) face down$", "set $1"),
        Rule("\\bdirectly\\b", "direct"),
        Rule("^with (\\S+) attack (.+)$", "attack $2 with $1"),
        Rule("^attack (.+?) using (\\S+)$", "attack $1 with $2"),
        Rule("^($COORD) attack (.+)$", "$1 attacks $2"),
        Rule("^respond with (.+)$", "chain $1"),
        Rule("\\bin response\\b", ""),
        Rule("^resolve (it|the chain|chain|that|the effect|effect)$", "resolve"),
        Rule("^draw (a|1|one) cards?$", "draw"),
        Rule("^draw (\\d+) cards?$", "draw $1"),
        Rule("^draw for (my )?turn$", "draw"),
        Rule("^(change|switch) (the )?(battle )?position of (.+)$", "pos $4"),
        Rule("^(change|switch) (.+) to (attack|defense)( position)?$", "pos $2"),
        Rule("^(check|look at|inspect) (.+)$", "read $2"),
        // Questions.
        Rule("^(what's|whats|what is|what are|what s|tell me|show me|read me|read out|what do i have|what have i got|how many cards are|how many cards) (on |in |at )?(the )?(.+)$") { m ->
            val rest = m.groupValues[4].trim()
            if (Regex("^$CARD$").matches(rest)) "?$rest" else rest
        },
        Rule("^(read )?(the )?chain$", "chain"),
        // "the" says nothing a name or a place needs.
        Rule("\\bthe\\b", ""),
    )

    /**
     * The words heard, in the Line's language: lower case, no punctuation, numbers as digits, places as coordinates
     * ("monster zone three" → `m3`, "their graveyard" → `ogy`, "fourth card in my hand" → `h4`), phases as their
     * words (`bp`, `m2`, `end`, `next`), "directly" → `direct`, filler gone. Card names pass through for the Line's
     * forgiving lookup.
     */
    fun normalize(heard: String): String {
        var t = heard.lowercase().replace('’', '\'').replace('‘', '\'')
        t = t.replace(Regex("\\ba\\.\\s?i\\.?(?=\\s|,|$)"), "ai")
        t = t.replace("&", " and ")
        t = t.replace(Regex("(?<=[a-z])[-/](?=[a-z])"), " ")
        t = t.replace(Regex("[^a-z0-9'+\\-=/?# ]"), " ")
        t = t.replace(Regex("(?<![a-z])'|'(?![a-z])"), " ").trim()
        // A question mark at the end is the transcriber's; one at the start is the Line's "?m3".
        while (t.length > 1 && t.endsWith("?")) t = t.dropLast(1).trim()
        val words = numbers(t.split(Regex("\\s+")).filter { it.isNotEmpty() })
        t = words.joinToString(" ")
        RULES.forEach { r -> t = r.regex.replace(t) { m -> r.with(m) }.replace(Regex("\\s+"), " ").trim() }
        t = t.replace(Regex("\\s*;\\s*"), "; ").trim().trim(';').trim()
        if (t.length > 1 && t.endsWith("?") && !t.startsWith("?")) t = t.dropLast(1).trim()
        return t
    }

    /**
     * What [normalized] words ask for. [aiAtTable]: Ai sits at the table, so what the Line cannot read goes to it.
     * "pass" and "no response" are No response to Ai (the red team: at a table "pass" means letting it resolve, not
     * ending the turn); "yes"/"no" confirm or cancel what is shown — the page decides whether they answer an ask instead.
     */
    fun classify(normalized: String, s: DuelState, seat: Int, catalog: DuelCatalog, aiAtTable: Boolean = false, secret: Long = 0L): Spoken {
        val t = normalized.trim()
        if (t.isEmpty()) return Spoken.Unknown(t)
        when (t) {
            in CONFIRM -> return Spoken.Confirm
            in CANCEL -> return Spoken.Cancel
            in UNDO -> return Spoken.Undo
            in NO_RESPONSE -> return Spoken.Cue(AiCue.NO_RESPONSE)
        }
        Regex("^(hey |ok |okay )?(ai|hey ai)\\b,?\\s*(.*)$").find(t)?.let { m ->
            val said = m.groupValues[3].trim()
            return if (said.isEmpty()) Spoken.Cue(AiCue.YOUR_MOVE) else Spoken.ToAi(said)
        }
        return when (val p = DuelCommand.parse(t, s, seat, catalog, secret)) {
            is DuelCommand.Parsed.Ui -> when {
                p.kind == DuelCommand.UiKind.UNDO -> Spoken.Undo
                p.kind == DuelCommand.UiKind.CUE && p.cue != null -> Spoken.Cue(p.cue)
                else -> Spoken.Command(t)
            }
            is DuelCommand.Parsed.Query -> Spoken.Query(p)
            is DuelCommand.Parsed.Problem -> when {
                t.substringBefore(' ') in COMMANDS || t.startsWith("?") ||
                    Regex("^$COORD\\b").containsMatchIn(t) -> Spoken.Command(t)
                aiAtTable -> Spoken.ToAi(t)
                else -> Spoken.Unknown(t)
            }
            else -> Spoken.Command(t)
        }
    }

    private fun visibleNames(s: DuelState, seat: Int, catalog: DuelCatalog): Set<String> {
        val own = s.seats[seat]
        return (own.hand + s.onField() + own.gy + own.banished + (s.seats.getOrNull(1 - seat)?.let { it.gy + it.banished } ?: emptyList()))
            .filter { DuelSight.sees(s, it, seat) }
            .mapNotNull { uid -> s.cards[uid]?.let { catalog.nameOf(it) } }
            .toSet()
    }

    /**
     * The transcriber's hint for this table: the names the seat can see or knows (its own Deck and Extra Deck), the
     * Line's words, then the game's — names a hidden card never.
     */
    fun hints(s: DuelState, seat: Int, catalog: DuelCatalog, most: Int = 700): String {
        val names = buildList {
            val own = s.seats[seat]
            (own.hand + s.onField() + own.gy + own.banished + (s.seats.getOrNull(1 - seat)?.let { it.gy + it.banished } ?: emptyList()))
                .filter { DuelSight.sees(s, it, seat) }
                .forEach { uid -> s.cards[uid]?.let { add(catalog.nameOf(it)) } }
            // The seat's own list: it knows its Deck and Extra Deck.
            (own.extra + own.deck).forEach { uid ->
                val c = s.cards[uid] ?: return@forEach
                if (c.owner == seat && s.placeOf(uid).let { it is Place.Pile && (it.kind == PileKind.DECK || it.kind == PileKind.EXTRA) }) add(catalog.nameOf(c))
            }
        }.filterNot { it.startsWith("#") }.distinct()
        // The Line's words first, so a long decklist never pushes "yes" or "summon" past the hint's end; the names after,
        // the seat's own list sorted so the Deck's order is not in it (1.0.87, the red team).
        val (seen, listed) = names.partition { n -> n in visibleNames(s, seat, catalog) }
        return Hints.prompt(WORDS + seen + listed.sorted(), most)
    }
}
