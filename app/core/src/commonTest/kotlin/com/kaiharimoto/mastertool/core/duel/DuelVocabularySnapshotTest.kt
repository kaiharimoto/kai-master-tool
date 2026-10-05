package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.text.CommandHelp
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.Parsed
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.QueryKind
import com.kaiharimoto.mastertool.core.duel.text.DuelComplete
import com.kaiharimoto.mastertool.core.duel.text.DuelLetters
import com.kaiharimoto.mastertool.core.duel.text.DuelWords
import com.kaiharimoto.mastertool.core.duel.text.PileWords
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The duel's word tables as they stood before they were made one list per fact (the refactor's step 1): each literal
 * here was copied from the source as it was, and every table derived since must read the same — the same words, the
 * same verbs, the same labels, the same piles. Where two copies differed, they still do; this is what holds them to it.
 */
class DuelVocabularySnapshotTest {

    // ---- the verb words and letters ----------------------------------------------------------------------------

    /** `DuelCommand.verbWords`, in its order: the words, then the verb keys' letters. */
    private val verbWords: List<Pair<String, DuelVerb>> = listOf(
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
        "spin" to DuelVerb.DECK_SHUFFLE, "top" to DuelVerb.DECK_TOP, "bottom" to DuelVerb.DECK_BOTTOM,
        "extra" to DuelVerb.EXTRA,
        "attach" to DuelVerb.ATTACH, "overlay" to DuelVerb.ATTACH, "detach" to DuelVerb.DETACH,
        "reveal" to DuelVerb.REVEAL, "show" to DuelVerb.REVEAL,
        "target" to DuelVerb.TARGET, "point" to DuelVerb.TARGET,
        "counter" to DuelVerb.COUNTER_UP, "uncounter" to DuelVerb.COUNTER_DOWN,
        "place" to DuelVerb.PLACE, "put" to DuelVerb.PLACE, "move" to DuelVerb.MOVE,
        "do" to DuelVerb.DEFAULT,
        // Phase D §5½: Shortcut's word and letter, added beside the rest; `use` above stays Activate.
        "shortcut" to DuelVerb.SHORTCUT,
        "a" to DuelVerb.ACTIVATE, "s" to DuelVerb.SUMMON, "e" to DuelVerb.SET, "p" to DuelVerb.POSITION, "f" to DuelVerb.FLIP,
        "g" to DuelVerb.GRAVE, "b" to DuelVerb.BANISH, "h" to DuelVerb.HAND, "k" to DuelVerb.DECK_TOP, "x" to DuelVerb.EXTRA,
        "o" to DuelVerb.ATTACH, "r" to DuelVerb.REVEAL, "c" to DuelVerb.COUNTER_UP, "t" to DuelVerb.TARGET, "u" to DuelVerb.SHORTCUT, "m" to DuelVerb.MOVE,
        "bd" to DuelVerb.BANISH_DOWN, "kb" to DuelVerb.DECK_BOTTOM, "ks" to DuelVerb.DECK_SHUFFLE, "cd" to DuelVerb.COUNTER_DOWN,
    )

    /** `DuelCommand.letters`. */
    private val letters = setOf("a", "s", "e", "p", "f", "g", "b", "h", "k", "x", "o", "r", "c", "t", "u", "m", "bd", "kb", "ks", "cd")

    /** The two-letter verbs, as `DuelComplete.suggest` and `CommandHelp.letters` each spelled them out. */
    private val twoLetters = setOf("bd", "kb", "ks", "cd")

    /** `DuelComplete.LETTERS`: the keys' letters, with completion's own labels. */
    private val completionLetters = listOf(
        "s" to "Summon", "e" to "Set", "a" to "Activate", "g" to "Send to the GY", "b" to "Banish", "h" to "To the hand",
        "t" to "Target", "o" to "Attach", "p" to "Position", "f" to "Flip", "r" to "Reveal", "k" to "To the top of the Deck",
        "x" to "To the Extra Deck", "c" to "A counter", "u" to "Shortcut",
    )

    /** `CommandHelp.letters`, as the help shows it. */
    private val helpLetters = listOf(
        "a" to "Activate", "b" to "Banish", "c" to "Add a counter", "e" to "Set", "f" to "Flip", "g" to "Send to GY",
        "h" to "To hand", "k" to "To top of Deck", "m" to "Move", "o" to "Attach as material", "p" to "Change position",
        "r" to "Reveal", "s" to "Summon", "t" to "Target", "u" to "Shortcut", "x" to "To Extra Deck",
        "bd" to "Banish face-down", "cd" to "Remove a counter", "kb" to "To bottom of Deck", "ks" to "Shuffle into Deck",
    )

    @Test
    fun theVerbWordsAreAsTheyWere() {
        assertEquals(verbWords, DuelCommand.VERB_WORDS.toList())
        assertEquals(letters, DuelCommand.LETTER_WORDS)
        assertEquals(letters, DuelCommand.VERB_WORDS.keys.filter { it.length == 1 || it in twoLetters }.toSet())
    }

    @Test
    fun completionsLettersAreAsTheyWere() {
        // Only the entry whose letter is the whole prefix is offered, so the list's order never shows: compared as a map.
        assertEquals(completionLetters.toMap(), DuelComplete.KEY_LETTERS.toMap())
        assertEquals(completionLetters.size, DuelComplete.KEY_LETTERS.size)
    }

    @Test
    fun theHelpsLettersAreAsTheyWere() {
        assertEquals(helpLetters, CommandHelp.letters.map { it.left to it.right })
    }

    // ---- the piles' words ----------------------------------------------------------------------------------------

    /** `DuelCommand.pileWords`: a pile after "from" or "to". */
    private val pileWords = mapOf(
        "hand" to PileKind.HAND, "deck" to PileKind.DECK, "extra" to PileKind.EXTRA, "ed" to PileKind.EXTRA,
        "gy" to PileKind.GY, "grave" to PileKind.GY, "graveyard" to PileKind.GY,
        "banish" to PileKind.BANISHED, "banished" to PileKind.BANISHED, "removed" to PileKind.BANISHED, "exile" to PileKind.BANISHED,
    )

    /** The random line's `pileOf`: word → (theirs, pile). */
    private val randomPiles = mapOf(
        "hand" to (false to PileKind.HAND), "h" to (false to PileKind.HAND),
        "oh" to (true to PileKind.HAND), "ohand" to (true to PileKind.HAND),
        "gy" to (false to PileKind.GY), "grave" to (false to PileKind.GY), "graveyard" to (false to PileKind.GY),
        "ogy" to (true to PileKind.GY),
        "ban" to (false to PileKind.BANISHED), "banished" to (false to PileKind.BANISHED), "banishment" to (false to PileKind.BANISHED),
        "oban" to (true to PileKind.BANISHED),
        "ex" to (false to PileKind.EXTRA), "extra" to (false to PileKind.EXTRA), "ed" to (false to PileKind.EXTRA),
        "oex" to (true to PileKind.EXTRA), "oed" to (true to PileKind.EXTRA),
        "dk" to (false to PileKind.DECK), "deck" to (false to PileKind.DECK),
        "odk" to (true to PileKind.DECK), "odeck" to (true to PileKind.DECK),
    )

    /** `DuelCommand.queryOf`'s pile words (whole lines). */
    private val queryPiles = mapOf(
        "hand" to QueryKind.HAND, "cards in hand" to QueryKind.HAND,
        "gy" to QueryKind.GY, "graveyard" to QueryKind.GY, "grave" to QueryKind.GY, "grave yard" to QueryKind.GY,
        "ban" to QueryKind.BANISHED, "banished" to QueryKind.BANISHED, "banishment" to QueryKind.BANISHED, "banished cards" to QueryKind.BANISHED,
        "ex" to QueryKind.EXTRA, "extra" to QueryKind.EXTRA, "extra deck" to QueryKind.EXTRA, "ed" to QueryKind.EXTRA,
        "dk" to QueryKind.DECK, "deck" to QueryKind.DECK,
    )

    /** The words `queryOf` takes with an `o` in front (field is no pile, but rides along). */
    private val queryTheirs = setOf("gy", "ban", "ex", "dk", "hand", "field")

    /** The shuffle head's piles: `shuffle hand`, `shuffle ex`; bare is the Deck. */
    private val shufflePiles = mapOf(
        "hand" to PileKind.HAND, "extra" to PileKind.EXTRA, "ed" to PileKind.EXTRA, "ex" to PileKind.EXTRA,
        "deck" to PileKind.DECK, "dk" to PileKind.DECK,
    )

    /** `DuelWords.pileWords`: the log's prose. */
    private val prose = mapOf(
        PileKind.HAND to "the hand", PileKind.DECK to "the Deck", PileKind.EXTRA to "the Extra Deck",
        PileKind.GY to "the GY", PileKind.BANISHED to "banishment",
    )

    @Test
    fun theLinesPileWordsAreAsTheyWere() {
        assertEquals(pileWords, DuelCommand.PILE_WORDS)
    }

    @Test
    fun theRandomLinesPilesAreAsTheyWere() {
        val s = battle()
        randomPiles.forEach { (w, theirsKind) ->
            val (theirs, kind) = theirsKind
            val p = DuelCommand.parse("random $w to gy", s, 0, catalog)
            assertIs<Parsed.Actions>(p, "random $w to gy: $p")
            val pick = p.actions.single() as DuelAction.Pick
            assertEquals(Place.Pile(if (theirs) 1 else 0, kind), pick.from, w)
        }
        // Forms that are not there stay not there.
        listOf("oextra", "ograve", "ograveyard", "obanished", "obanishment", "removed", "exile", "ohh").forEach { w ->
            assertIs<Parsed.Problem>(DuelCommand.parse("random $w to gy", s, 0, catalog), w)
        }
    }

    @Test
    fun theRandomLinesProseIsAsItWas() {
        val s = battle()
        fun said(line: String) = (DuelCommand.parse(line, s, 0, catalog) as Parsed.Actions).said
        assertEquals("1 at random to the GY", said("random hand to gy"))
        assertEquals("1 at random to banishment", said("random hand to ban"))
        assertEquals("1 at random to banishment, face-down", said("banish random ex down"))
        assertEquals("1 at random to the top of the Deck", said("random hand to deck"))
        assertEquals("1 at random to the bottom of the Deck", said("random hand kb"))
        assertEquals("1 at random to the hand", said("random gy to hand"))
        assertEquals("1 at random to the Extra Deck", said("random gy to ex"))
    }

    @Test
    fun theQueriesPilesAreAsTheyWere() {
        queryPiles.forEach { (w, kind) ->
            assertEquals(Parsed.Query(kind, false), DuelCommand.queryOf(w), w)
            assertEquals(Parsed.Query(kind, true), DuelCommand.queryOf("their $w"), "their $w")
        }
        queryTheirs.forEach { w ->
            val mine = DuelCommand.queryOf(w)!!
            assertEquals(mine.copy(theirs = true), DuelCommand.queryOf("o$w"), "o$w")
        }
        // Not questions: the hand's letter, and o-forms beyond the list.
        listOf("h", "oh", "ograve", "oed", "oextra", "odeck", "removed", "exile", "banish").forEach { w -> assertNull(DuelCommand.queryOf(w), w) }
    }

    @Test
    fun theShuffleHeadsPilesAreAsTheyWere() {
        val s = battle()
        assertEquals(listOf<DuelAction>(DuelAction.Shuffle(0, PileKind.DECK)), (DuelCommand.parse("shuffle", s, 0, catalog) as Parsed.Actions).actions)
        shufflePiles.forEach { (w, kind) ->
            val p = DuelCommand.parse("shuffle $w", s, 0, catalog)
            assertIs<Parsed.Actions>(p, w)
            assertEquals(listOf<DuelAction>(DuelAction.Shuffle(0, kind)), p.actions, w)
            assertEquals("Shuffle ${kind.label}", p.said)
        }
        listOf("h", "gy", "ban", "grave", "extra deck", "ohand").forEach { w ->
            val p = DuelCommand.parse("shuffle $w", s, 0, catalog)
            assertFalse(p is Parsed.Actions && p.actions.singleOrNull() is DuelAction.Shuffle, "shuffle $w: $p")
        }
    }

    /** The derived tables themselves ([PileWords], [DuelLetters]) against the literals they replaced. */
    @Test
    fun theDerivedTablesAreTheOldLiterals() {
        assertEquals(pileWords, PileWords.LINE)
        assertEquals(randomPiles, PileWords.RANDOM)
        assertEquals(queryPiles, PileWords.QUERY)
        assertEquals(queryTheirs, PileWords.QUERY_THEIRS)
        assertEquals(shufflePiles, PileWords.SHUFFLE)
        PileKind.entries.forEach { assertEquals(prose.getValue(it), PileWords.prose(it)) }
        assertEquals(verbWords.filter { it.first in letters }, DuelLetters.WORDS.toList())
        assertEquals(letters, DuelLetters.LETTERS)
        assertEquals(twoLetters, DuelLetters.TWO_LETTER)
        assertEquals(completionLetters.toMap(), DuelLetters.KEY_HINTS.toMap())
    }

    @Test
    fun theLogsPileProseIsAsItWas() {
        PileKind.entries.forEach { assertEquals(prose.getValue(it), DuelWords.pileProse(it), it.name) }
    }

    // ---- the words a spoken line may begin with ------------------------------------------------------------------

    /** `DuelSpeech.COMMANDS` (it spelled "spin" twice; a set either way). */
    private val commands = setOf(
        "summon", "set", "activate", "chain", "attack", "at", "target", "send", "destroy", "tribute", "banish", "add", "search", "draw",
        "mill", "flip", "pos", "move", "place", "attach", "detach", "reveal", "counter", "token", "lp", "resolve", "bp", "m1", "m2",
        "ep", "end", "next", "ss", "special", "read", "open", "look", "discard", "return", "bounce", "spin", "excavate", "shuffle",
        "coin", "dice", "concede", "swap", "redo", "random", "roll", "throw", "first", "second", "accept", "decline", "lock", "unlock", "say", "note", "?", "use", "play",
        // Phase D §5½.
        "shortcut",
    )

    @Test
    fun theSpokenHeadsAreAsTheyWere() {
        assertEquals(commands, DuelSpeech.COMMAND_HEADS)
    }
}
