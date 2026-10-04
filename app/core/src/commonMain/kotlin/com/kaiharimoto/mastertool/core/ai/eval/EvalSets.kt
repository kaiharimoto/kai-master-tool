package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.hand.HandConstraint
import com.kaiharimoto.mastertool.core.hand.HandOdds
import com.kaiharimoto.mastertool.core.hand.HandQuery

/**
 * The built-in sets (1.0.99). Every key is either computed by the app's own arithmetic or a rule or ruling with one
 * settled answer and its source named; a key that could be wrong does not go in.
 */
object EvalSets {
    const val HAND_ODDS = "hand-odds"
    const val RULINGS = "rulings"
    const val DECKLISTS = "decklists"
    const val PLANTED = "planted-errors"

    /** What every question is asked under: answer plainly, end on the line the grader reads. */
    const val INSTRUCTIONS = "You are being tested on a question with a known answer. Use the tools you have if they help " +
        "(calculate and hand_odds for numbers, rulings and card_info for rules and cards, resolve_cards for card names). " +
        "Answer briefly, then end with one last line in exactly the form the question asks for, starting with ANSWER:."

    val all: List<EvalSet> by lazy { listOf(handOdds(), rulings(), decklists(), planted()) }

    fun byId(id: String): EvalSet? = all.firstOrNull { it.id == id }

    // ---- hand odds: computed, so the key is the app's own counter ---------------------------------------------

    private data class Odds(val deck: Int, val groups: List<Pair<String, Int>>, val hand: Int, val need: List<HandConstraint>, val words: String)

    fun handOdds(): EvalSet {
        val specs = buildList {
            // At least one of a group, going first (5) and second (6), over deck sizes and copies.
            for (deck in listOf(40, 45, 50, 60)) for (copies in listOf(3, 9, 12)) for (hand in listOf(5, 6)) {
                add(Odds(deck, listOf("starter" to copies), hand, listOf(HandConstraint("starter", 1, hand)), "at least 1 starter"))
            }
            // Exactly one hand trap; at least two of a group.
            add(Odds(40, listOf("hand trap" to 9), 5, listOf(HandConstraint("hand trap", 1, 1)), "exactly 1 hand trap"))
            add(Odds(40, listOf("hand trap" to 12), 5, listOf(HandConstraint("hand trap", 2, 5)), "at least 2 hand traps"))
            add(Odds(40, listOf("brick" to 6), 5, listOf(HandConstraint("brick", 0, 0)), "no brick at all"))
            add(Odds(42, listOf("extender" to 8), 6, listOf(HandConstraint("extender", 2, 6)), "at least 2 extenders"))
            // Two groups together.
            add(Odds(40, listOf("starter" to 9, "hand trap" to 12), 5, listOf(HandConstraint("starter", 1, 5), HandConstraint("hand trap", 1, 5)), "at least 1 starter and at least 1 hand trap"))
            add(Odds(40, listOf("starter" to 12, "brick" to 4), 5, listOf(HandConstraint("starter", 1, 5), HandConstraint("brick", 0, 0)), "at least 1 starter and no brick"))
            add(Odds(45, listOf("starter" to 10, "extender" to 8), 5, listOf(HandConstraint("starter", 1, 5), HandConstraint("extender", 1, 5)), "at least 1 starter and at least 1 extender"))
            add(Odds(40, listOf("starter" to 9, "hand trap" to 15), 6, listOf(HandConstraint("starter", 1, 6), HandConstraint("hand trap", 2, 6)), "at least 1 starter and at least 2 hand traps"))
            add(Odds(40, listOf("one-card combo" to 3, "starter" to 6), 5, listOf(HandConstraint("one-card combo", 1, 5)), "at least 1 one-card combo"))
            add(Odds(60, listOf("starter" to 15, "hand trap" to 15), 5, listOf(HandConstraint("starter", 1, 5), HandConstraint("hand trap", 1, 5)), "at least 1 starter and at least 1 hand trap"))
            add(Odds(40, listOf("garnet" to 1), 5, listOf(HandConstraint("garnet", 1, 1)), "the one garnet"))
            add(Odds(40, listOf("garnet" to 2), 6, listOf(HandConstraint("garnet", 0, 0)), "neither garnet"))
            add(Odds(41, listOf("starter" to 11), 5, listOf(HandConstraint("starter", 3, 5)), "at least 3 starters"))
            add(Odds(40, listOf("starter" to 9, "extender" to 6, "hand trap" to 9), 5, listOf(HandConstraint("starter", 1, 5), HandConstraint("extender", 1, 5), HandConstraint("hand trap", 1, 5)), "at least 1 starter, at least 1 extender and at least 1 hand trap"))
            add(Odds(40, listOf("starter" to 6), 5, listOf(HandConstraint("starter", 2, 2)), "exactly 2 starters"))
            add(Odds(40, listOf("starter" to 7, "hand trap" to 9), 6, listOf(HandConstraint("starter", 1, 1), HandConstraint("hand trap", 1, 6)), "exactly 1 starter and at least 1 hand trap"))
        }
        val items = specs.mapIndexed { i, s ->
            val p = HandOdds.probability(s.groups.toMap(), s.deck, s.hand, HandQuery(s.need))
            val groups = s.groups.joinToString(" and ") { (name, n) -> "$n ${if (n == 1) name else plural(name)}" }
            val rest = s.deck - s.groups.sumOf { it.second }
            val turn = if (s.hand == 5) "going first, drawing 5 cards" else "going second, holding 6 cards"
            EvalItem(
                "odds-${(i + 1).toString().padStart(2, '0')}",
                "A ${s.deck}-card Main Deck holds $groups (no card counts for two of these), and $rest other cards. " +
                    "Shuffled, $turn: what is the probability of ${s.words}? Give it as a percentage to one decimal place, " +
                    "on a last line in the form ANSWER: 74.2%",
                Grader.Percent(p, 1),
                "the app's exact counter (HandOdds)",
            )
        }
        return EvalSet(HAND_ODDS, "Hand odds", "${items.size} opening-hand questions with exact answers, computed by the app.", items)
    }

    private fun plural(word: String) = if (word.endsWith("s")) word else word + "s"

    // ---- rulings and rules: one settled answer each, its source named ------------------------------------------

    private fun yn(id: String, q: String, yes: Boolean, source: String) =
        EvalItem(id, "$q Answer yes or no, on a last line in the form ANSWER: yes", Grader.YesNo(yes), source)

    fun rulings(): EvalSet {
        val rules = "the game's rules"
        val faq = "Konami's FAQ, as YGOrganization translates it"
        val items = listOf(
            yn("rule-01", "Can a deck hold four copies of one card across its Main, Extra and Side Decks together?", false, rules),
            yn("rule-02", "Is a 39-card Main Deck legal?", false, rules),
            yn("rule-03", "Is a 60-card Main Deck legal?", true, rules),
            yn("rule-04", "Can an Extra Deck hold 16 cards?", false, rules),
            yn("rule-05", "Does the player who goes first draw a card in the Draw Phase of the Duel's first turn?", false, rules),
            yn("rule-06", "Does each player start a Duel with 8000 Life Points?", true, rules),
            yn("rule-07", "If the turn player holds 7 cards at the end of their turn, must they discard down to 6?", true, rules),
            yn("rule-08", "Without a card effect allowing it, can a player Normal Summon or Set more than one monster in a turn?", false, rules),
            yn("rule-09", "Is a Counter Trap Card Spell Speed 3?", true, rules),
            yn("rule-10", "Can a Spell Speed 1 effect be chained to a Spell Speed 2 effect?", false, rules),
            yn("rule-11", "Can a Quick-Play Spell Card be activated during the same turn it was Set?", false, rules),
            yn("rule-12", "Without a card that allows it, can a Trap Card be activated during the same turn it was Set?", false, rules),
            yn("rule-13", "Can a player activate a Quick-Play Spell Card from their hand during the opponent's turn?", false, rules),
            yn("rule-14", "Can the player who goes first conduct their Battle Phase on the Duel's first turn?", false, rules),
            yn("rule-15", "Does a Link Monster have a DEF value?", false, rules),
            yn("rule-16", "Can a Link Monster be in Defense Position?", false, rules),
            yn("rule-17", "Does a Tribute Summon of a Level 5 or 6 monster need 1 Tribute?", true, rules),
            yn("rule-18", "Does a Tribute Summon of a Level 7 or higher monster need 2 Tributes?", true, rules),
            yn("rule-19", "Does an Xyz Monster have a Level?", false, rules),
            yn("rule-20", "Can a monster effect that says only \"Once per turn\" be used once per turn by each face-up copy of that card?", true, rules),
            yn("rule-21", "Does \"You can only use this effect of [card name] once per turn\" limit every copy of that card together to one use per turn?", true, rules),
            yn("rule-22", "Under the current Master Rule, can a Fusion Monster be Special Summoned from the Extra Deck to a Main Monster Zone that no Link Monster points to?", true, rules),
            yn("rule-23", "Under the current Master Rule, can a Link Monster be Special Summoned from the Extra Deck to a Main Monster Zone that no Link Monster points to?", false, rules),
            yn("rule-24", "Is a Pendulum Monster that would be sent from the field to the Graveyard placed face-up in the Extra Deck instead?", true, rules),
            yn("rule-25", "When a Token leaves the field, is it sent to the Graveyard?", false, rules),
            yn("rule-26", "Can a player activate a Field Spell while they already control a face-up Field Spell, replacing it?", true, rules),
            yn("rule-27", "Is discarding Ash Blossom & Joyous Spring a cost to activate its effect?", true, faq),
            yn("rule-28", "Can Ash Blossom & Joyous Spring's effect be activated during the Damage Step?", false, faq),
            yn("rule-29", "Does Ash Blossom & Joyous Spring's effect target?", false, faq),
            yn("rule-30", "Is Ash Blossom & Joyous Spring's effect a Quick Effect activated from the hand?", true, faq),
        )
        return EvalSet(RULINGS, "Rulings and rules", "${items.size} questions with one settled answer each: the game's rules, and Konami's FAQ.", items)
    }

    // ---- decklists: the names people write, read back exactly --------------------------------------------------

    private fun deck(id: String, written: String, expected: Map<String, Int>) = EvalItem(
        id,
        "Read this decklist as a player wrote it, and write it back with every card's exact official English name and " +
            "its count. Main Deck only.\n\n$written\n\nEnd with a line ANSWER: and then one line per card in the form " +
            "\"3 Ash Blossom & Joyous Spring\".",
        Grader.Decklist(expected),
        "written for the set; the names are the official ones",
    )

    fun decklists(): EvalSet {
        val ash = "Ash Blossom & Joyous Spring"
        val imperm = "Infinite Impermanence"
        val maxx = "Maxx \"C\""
        val veiler = "Effect Veiler"
        val called = "Called by the Grave"
        val crossout = "Crossout Designator"
        val prosperity = "Pot of Prosperity"
        val ttt = "Triple Tactics Talent"
        val nib = "Nibiru, the Primal Being"
        val ogre = "Ghost Ogre & Snow Rabbit"
        val droll = "Droll & Lock Bird"
        val droplet = "Forbidden Droplet"
        val drnm = "Dark Ruler No More"
        val storm = "Lightning Storm"
        val belle = "Ghost Belle & Haunted Mansion"
        val mourner = "Ghost Mourner & Moonlit Chill"
        val evenly = "Evenly Matched"
        val items = listOf(
            deck("deck-01", "3 ash\n3 imperm\n2 called by", mapOf(ash to 3, imperm to 3, called to 2)),
            deck("deck-02", "Ash Blossom x3\nInfinite Impermanance x2\nMaxx C x1", mapOf(ash to 3, imperm to 2, maxx to 1)),
            deck("deck-03", "3x veiler, 1x crossout, 2x prosperity", mapOf(veiler to 3, crossout to 1, prosperity to 2)),
            deck("deck-04", "TTT x2\nNib x1\nash blossom joyous spring x3", mapOf(ttt to 2, nib to 1, ash to 3)),
            deck("deck-05", "ghost ogre 3\ndroll and lock bird 2\nforbidden droplet 1", mapOf(ogre to 3, droll to 2, droplet to 1)),
            deck("deck-06", "1 DRNM\n2 lightning storm\n3 Called By The Grave", mapOf(drnm to 1, storm to 2, called to 3)),
            deck("deck-07", "Belle x3, Mourner x2, Imperm x3", mapOf(belle to 3, mourner to 2, imperm to 3)),
            deck("deck-08", "3 Maxx \"C\"\n3 Ash\n1 Nibiru", mapOf(maxx to 3, ash to 3, nib to 1)),
            deck("deck-09", "evenly matched 2\ncrossout designator 1\ntriple tactics talent 3", mapOf(evenly to 2, crossout to 1, ttt to 3)),
            deck("deck-10", "- 3 Infinite Impermenance\n- 3 Effect Veiller\n- 1 Called by", mapOf(imperm to 3, veiler to 3, called to 1)),
            deck("deck-11", "Pot of Prosp x2\nDroplet x3\nDark Ruler no more x1", mapOf(prosperity to 2, droplet to 3, drnm to 1)),
            deck("deck-12", "3 ogre\n3 belle\n2 droll", mapOf(ogre to 3, belle to 3, droll to 2)),
            deck("deck-13", "1 x Lightning Storm\n2 x Evenly Matched\n3 x Ash Blossom", mapOf(storm to 1, evenly to 2, ash to 3)),
            deck("deck-14", "Nibiru the primal being 2\nGhost Mourner 1\nMaxx c 3", mapOf(nib to 2, mourner to 1, maxx to 3)),
            deck("deck-15", "3 imperm 3 ash 3 maxx", mapOf(imperm to 3, ash to 3, maxx to 3)),
            deck("deck-16", "crossout x3\ncalled by the grave x2\nveiler x1", mapOf(crossout to 3, called to 2, veiler to 1)),
            deck("deck-17", "Triple Tactics x1\nPot of Prosperity x3\nForbiden Droplet x2", mapOf(ttt to 1, prosperity to 3, droplet to 2)),
            deck("deck-18", "Droll & Lockbird 3\nGhost Ogre and Snow Rabbit 1\nEffect Veiler 2", mapOf(droll to 3, ogre to 1, veiler to 2)),
            deck("deck-19", "2 DRNM\n1 lightning strom\n2 Evenly", mapOf(drnm to 2, storm to 1, evenly to 2)),
            deck("deck-20", "ash 2 / imperm 2 / called 2 / veiler 2", mapOf(ash to 2, imperm to 2, called to 2, veiler to 2)),
        )
        return EvalSet(DECKLISTS, "Decklists", "${items.size} decklists as people write them — nicknames, typos, counts before and after — read back exactly.", items)
    }

    // ---- planted errors: the fact-checker, tested ---------------------------------------------------------------

    private fun plant(id: String, answer: String, hasError: Boolean, vararg about: String) =
        EvalItem(id, answer, Grader.Planted(hasError, about.toList()), "written for the set: the card's printed stats, or the app's arithmetic")

    fun planted(): EvalSet {
        // 3 of 9 starters in a 40-card deck, 5 cards: 1 − C(31,5)/C(40,5) = 74.2 %; 12 of 40: 85.1 %; 9 of 40, 6 cards: 80.7 %.
        val nine = HandOdds.probability(mapOf("s" to 9), 40, 5, HandQuery(listOf(HandConstraint("s", 1, 5))))
        val twelve = HandOdds.probability(mapOf("s" to 12), 40, 5, HandQuery(listOf(HandConstraint("s", 1, 5))))
        val nineSecond = HandOdds.probability(mapOf("s" to 9), 40, 6, HandQuery(listOf(HandConstraint("s", 1, 6))))
        fun pct(p: Double) = ((p * 1000).let { kotlin.math.round(it) } / 10).toString()
        val items = listOf(
            plant("plant-01", "With 9 starters in a 40-card deck you open at least one ${pct(nine)}% of the time going first. That is about three hands in four, so the engine is consistent enough to play.", false),
            plant("plant-02", "With 9 starters in a 40-card deck you open at least one 81.5% of the time going first, so the engine is very consistent.", true, "81.5", "9 starters"),
            plant("plant-03", "Twelve starters in 40 cards give at least one in the opening five ${pct(twelve)}% of the time.", false),
            plant("plant-04", "Twelve starters in 40 cards give at least one in the opening five 91.3% of the time.", true, "91.3", "twelve", "12"),
            plant("plant-05", "Going second with 6 cards, 9 starters in 40 come up at least once ${pct(nineSecond)}% of the time.", false),
            plant("plant-06", "Going second with 6 cards, 9 starters in 40 come up at least once 74.2% of the time — no better than going first.", true, "74.2", "second"),
            plant("plant-07", "[[Ash Blossom & Joyous Spring]] is a Level 3 FIRE Tuner with 0 ATK and 1800 DEF, so it can be a Synchro Material in a pinch.", false),
            plant("plant-08", "[[Ash Blossom & Joyous Spring]] is a Level 3 FIRE Tuner with 0 ATK and 2000 DEF.", true, "2000", "DEF"),
            plant("plant-09", "[[Effect Veiler]] is a Level 1 LIGHT Tuner with 0 ATK and 0 DEF.", false),
            plant("plant-10", "[[Effect Veiler]] is a Level 2 LIGHT Tuner with 0 ATK and 0 DEF.", true, "level 2", "level"),
            plant("plant-11", "[[Accesscode Talker]] is a Link-4 monster with 2300 ATK.", false),
            plant("plant-12", "[[Accesscode Talker]] is a Link-3 monster with 2300 ATK.", true, "link-3", "link 3", "link rating", "link"),
            plant("plant-13", "[[Maxx \"C\"]] is a Level 2 EARTH monster with 500 ATK and 200 DEF.", false),
            plant("plant-14", "[[Maxx \"C\"]] is a Level 2 EARTH monster with 1500 ATK and 200 DEF.", true, "1500", "atk"),
            plant("plant-15", "[[Nibiru, the Primal Being]] is a Level 11 monster with 3000 ATK and 600 DEF.", false),
            plant("plant-16", "[[Nibiru, the Primal Being]] is a Level 10 monster with 3000 ATK and 600 DEF.", true, "level 10", "level"),
            plant("plant-17", "[[Infinite Impermanence]] is a Normal Trap Card.", false),
            plant("plant-18", "[[Infinite Impermanence]] is a Continuous Trap Card.", true, "continuous"),
            plant("plant-19", "[[Called by the Grave]] is a Quick-Play Spell Card.", false),
            plant("plant-20", "[[Called by the Grave]] is a Normal Spell Card, so it can only be used in your Main Phase.", true, "normal spell", "normal"),
            plant("plant-21", "[[Ghost Ogre & Snow Rabbit]] is a Level 3 LIGHT Tuner with 0 ATK and 1800 DEF.", false),
            plant("plant-22", "[[Ghost Ogre & Snow Rabbit]] is a Level 3 LIGHT Tuner with 0 ATK and 1000 DEF.", true, "1000", "def"),
            plant("plant-23", "In a 40-card deck you hold 5 of its cards going first, an eighth of the deck.", false),
            plant("plant-24", "In a 40-card deck you hold 5 of its cards going first, a sixth of the deck.", true, "sixth"),
        )
        return EvalSet(PLANTED, "The fact-checker", "${items.size} answers, half with one planted mistake: how many mistakes it catches, and how often it cries wolf.", items, checker = true)
    }
}
