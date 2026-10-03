package com.kaiharimoto.mastertool.core.duel.text

import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcut
import com.kaiharimoto.mastertool.core.input.DeskShortcuts

/**
 * Command mode in the help (1.0.87, F1): the notation, the verb letters, lines to type, phrases to say and the
 * Spotlight's keys — all read from the tables that do the work ([DuelNotation], [DuelCommand], [DuelSpeech],
 * [DeskShortcuts]), so the help cannot promise a word the Line does not read.
 */
object CommandHelp {

    /**
     * Phrases to say, holding M, and the line each becomes ([DuelSpeech.normalize]) — the help's "Command mode" section and the
     * spoken take of the demo. `SpotlightTest` holds each to its line.
     */
    val SPOKEN: List<Pair<String, String>> = listOf(
        "Summon h2 to m3" to "summon h2 to m3",
        "Summon Ash Blossom to monster three" to "summon ash blossom to m3",
        "Set hand four to spell two" to "set h4 to s2",
        "Go to battle" to "bp",
        "My monster three attacks their monster one" to "m3 attacks om1",
        "Attack directly with monster two" to "attack direct with m2",
        "Yes" to "yes",
        "Scratch that" to "scratch that",
        "No response" to "no response",
        "What's on their field" to "their field",
        "Life points" to "lp",
        "End turn" to "end",
    )

    /** What each example line does, in words. */
    private val EXAMPLE_WORDS = mapOf(
        "s h2 m3" to "Summon the second card in your hand to Monster Zone 3",
        "discard random" to "Discard a card from your hand at random",
        "random oh to gy" to "Send a card of their hand to the GY at random",
        "banish random ex down" to "Banish a card of your Extra Deck face-down at random",
        "random h2 h4 kb" to "Those cards to the bottom of the Deck in a random order",
        "ks h1" to "Shuffle h1 into the Deck (Alt K)",
        "e h4 s2" to "Set the fourth card in your hand in Spell & Trap Zone 2",
        "a s1" to "Activate the card in Spell & Trap Zone 1",
        "a m3 om1" to "Your M3 attacks their M1 (in the Battle Phase)",
        "attack m3 direct" to "A direct attack",
        "g om3" to "Send their M3 to the GY",
        "b gy2" to "Banish the second card of your GY",
        "o h4 m3" to "Attach h4 to your M3 as material",
        "t om2" to "Target their M2",
        "target om2 with s1" to "Target their M2 with your S1",
        "counter m3 +2" to "Two counters on your M3",
        "detach m3" to "Detach a material from your M3",
        "s h2 m3; bp" to "Two moves, made in order",
        "open ogy" to "Lay their GY open",
        "close" to "Close the open pile",
        "read om2" to "Read their M2 in the inspector",
        "?m3" to "Ask what is in M3",
        "hand" to "Ask what is in your hand",
        "field" to "Ask what is on your field",
        "their field" to "Ask what is on theirs",
        "gy" to "Ask what is in your GY",
        "lp" to "Ask the life points",
        "chain" to "Ask what is on the chain",
        "no response" to "Tell Ai you do not respond",
        "your move" to "Hand Ai the move",
        "pass" to "Let the chain resolve",
        "swap" to "Sit at the other seat",
        "undo" to "Take the last move back",
        // The chain by keys and several cards at once (1.0.90).
        "resolve all" to "Resolve the whole chain, newest link first (Shift Q)",
        "negate 2" to "Negate Chain Link 2: it stays, and an activated Spell or Trap goes to the GY",
        "g gy1 h2 ban1" to "One verb, several cards: all three to the GY, one undo",
        "k gy1 gy3" to "On top of the Deck, top first: gy1 the new top card, gy3 under it",
        "kb gy1 gy3" to "On the bottom, top first: gy3 the bottom card, gy1 just above it",
        "t om1 om2" to "An arrow to each of their cards",
    )

    /** One row of the help: what to type or press on the left, what it means on the right. */
    data class Row(val left: String, val right: String)

    /** The coordinates, from the viewer's side. */
    val notation: List<Row> = listOf(
        Row("h1 h2 …", "Your hand, left to right"),
        Row("m1 – m5", "Your Monster Zones"),
        Row("s1 – s5", "Your Spell & Trap Zones"),
        Row("e1 e2", "The Extra Monster Zones, left and right"),
        Row("fz", "Your Field Zone"),
        Row("gy ban ex dk", "Your GY, banished cards, Extra Deck and Deck"),
        Row("gy3", "The third card from the top of your GY"),
        Row("om3 os2 oh4 ogy", "Theirs: the same with an o in front"),
    )

    /** The verb keys' letters, which are the Line's verbs before a coordinate: `s h2 m3`, `e h4 s2`, `g om3`. */
    val letters: List<Row> = DuelLetters.ROWS.map { Row(it.letter, it.verb.label) }
        .sortedBy { it.left.length * 100 + it.left.first().code }

    /** Lines to type: Command mode's own examples, as the Line's help lists them. */
    val examples: List<Row> = DuelCommand.EXAMPLES.dropWhile { it != "s h2 m3" }.map { line -> Row(line, EXAMPLE_WORDS[line] ?: "") }

    /** Phrases to say, holding M, and the line each becomes. */
    val spoken: List<Row> = SPOKEN.map { (said, line) -> Row("“$said”", line) }

    /** The Spotlight's own keys, while it is open. */
    val keys: List<Row> = listOf(
        Row("↑ ↓", "Choose a row; on an empty box, the lines made before"),
        Row("Enter", "Make it, and close the box"),
        Row("Shift Enter", "Make it, and keep typing"),
        Row("Tab", "Take the chosen row into the line"),
        Row("1 2 3", "Pick a “did you mean”"),
        Row("Ctrl Enter", "Say the words in the chat instead"),
        Row("Esc", "Close the box"),
    )

    /** The window's keys that open the box or listen, from [DeskShortcuts] (so they cannot drift). */
    val opening: List<DeskShortcut> = DeskShortcuts.all.filter { it.action == DeskAction.DUEL_COMMAND || it.action == DeskAction.DUEL_VOICE }
}
