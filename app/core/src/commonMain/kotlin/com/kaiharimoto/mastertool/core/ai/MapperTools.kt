package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.duel.mapper.BoardFilter
import com.kaiharimoto.mastertool.core.duel.mapper.BoardPreset
import com.kaiharimoto.mastertool.core.duel.mapper.BoardTraits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Gameplay Mapper's tools for Ai (Phase M step M1, `docs/phases/M.md`): reading a deck's board library the way the person's
 * filters and weights read it, its starter table, and mapping — the starter table, or hands dealt from a seed. Every run is
 * the page's own run: it shows on page 10 with its progress and the person's Stop, and what it finds is kept with the deck.
 * Nothing here ranks a board in advance; Ai chooses with filters and weights, as the person does.
 */
object MapperTools {
    private const val TRAITS = "interruptions, negates, removal, bodies, set, hand, gy, banished, handInterruptions"

    val library = ToolSpec(
        "mapper_library",
        "Gameplay Mapper (10), read-only: a deck's board library going first (or second) — every distinct end board the runs " +
            "found, measured, never ranked in advance. Choose with filters ({head, min, max}) and weights ({head: number}, " +
            "negative prefers less) over the traits ($TRAITS), cards it must use or avoid, or a saved preset by name; the " +
            "answer ranks what passes, marks the Pareto front (nothing beats it on every weighted trait), and gives each " +
            "board's share of dealt hands that make at least as much, with its 95 % range, when a run has counted them. With " +
            "board (a key from a list), that board in full: its cards, every trait, its lines and starters.",
        schema {
            string("deck_id", "A deck's id, or 'open' for the builder's deck (the default)")
            boolean("second", "The library going second (default: going first)")
            objects("filters", "Bounds on traits") {
                string("head", "A trait: $TRAITS", required = true)
                integer("min", "At least")
                integer("max", "At most")
            }
            any("weights", "Weights by trait, an object: {\"negates\": 2, \"hand\": 0.5}")
            strings("uses", "Cards the board or its starter must hold, by name or passcode")
            strings("avoids", "Cards it must not")
            string("preset", "A saved preset's name, used for whatever is not given")
            integer("limit", "Boards to list (default 8)", min = 1, max = 40)
            string("board", "A board's key, for that board in full")
        },
        ToolGroup.LOOK,
        phase = 3,
    )

    val starters = ToolSpec(
        "mapper_starters",
        "Gameplay Mapper (10): a deck's starter table — every engine card alone and every pair, each mapped beside the deck's " +
            "cards that do nothing, with the boards it reaches, the chance of opening it, and for a pair the boards neither " +
            "card makes alone (its real extenders). Reads the kept table; with run: true maps it again into the library, " +
            "shown on page 10 with its progress (minutes on a large deck; the person can stop it). With card, that card's rows.",
        schema {
            string("deck_id", "A deck's id, or 'open' for the builder's deck (the default)")
            boolean("second", "Going second (default: going first)")
            boolean("run", "Map the table now (default: read the kept one)")
            boolean("pairs", "With run: map the pairs too (default true)")
            string("card", "A card by name or passcode: its rows only")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val map = ToolSpec(
        "mapper_map",
        "Gameplay Mapper (10): maps hands dealt from a seed as the goldfish deals them — each hand's end boards found by " +
            "searching every line of its written effects — and counts how many hands reach each kind of board; the best board " +
            "of each kind joins the library. Shown on page 10 with its progress (the person can stop it; a stopped run keeps " +
            "what it mapped). Runs on the builder's deck. Give hands (default 100, at most 2000) and seed; with cards, maps that " +
            "one hand instead and lists its boards (the library is not changed). Only cards with written effects play (fx_state " +
            "says which).",
        schema {
            string("deck_id", "A deck's id, or 'open' for the builder's deck (the default)")
            boolean("second", "Going second, six cards (default: going first, five)")
            integer("hands", "Hands to deal (default 100)", min = 1, max = 2000)
            integer("seed", "The seed (default 1): the same seed deals the same hands")
            strings("cards", "One hand to map, by name or passcode")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val preset = ToolSpec(
        "mapper_preset",
        "Gameplay Mapper (10): saves filters and weights as a preset of the deck's, marked as yours (Ai's) with your reason, " +
            "and puts it on the page — the person sees its weights like any other and keeps or deletes it. Give name, why and " +
            "the query (filters, weights, uses, avoids, as mapper_library takes them). With remove, deletes one of yours.",
        schema {
            string("name", "The preset's name: \"Ash-proof negates\"")
            string("why", "One line: what it is for")
            objects("filters", "Bounds on traits") {
                string("head", "A trait: $TRAITS", required = true)
                integer("min", "At least")
                integer("max", "At most")
            }
            any("weights", "Weights by trait, an object")
            strings("uses", "Cards the board or its starter must hold")
            strings("avoids", "Cards it must not")
            string("remove", "One of your presets, by name, to delete")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val compare = ToolSpec(
        "deck_compare",
        "Gameplay Mapper (10): is a change better? The builder's deck against a change (changes: cut, add or swap a card's " +
            "copies — each new copy dealt where a cut one was) or against another saved deck (variant_deck_id), on the same hands: " +
            "hand k is dealt both ways from the same keys, so only the hands the change touches can differ, and the run stops once " +
            "the answer is known (the interval clear of zero, or within a point). Each hand is asked whether it reaches at least " +
            "`interruptions` interruptions (default 1), or a board passing `filters`. Returns both shares, the difference in points " +
            "with its paired 95 % interval, the exact McNemar test, the hands that changed, and undecided hands counted both ways. " +
            "Refused, naming the cards to write, when a differing card has no trusted script yet something could pick it; an engine " +
            "card swapped for a hand trap is said to be a stress test's or Shootout's question. Shown on page 10 with its progress " +
            "and the person's Stop. Only cards with written effects play (fx_state says which).",
        schema {
            objects("changes", "The change: each a cut, an add or a swap") {
                string("out", "A card to cut, by name or passcode")
                string("into", "A card to add, by name or passcode")
                integer("copies", "How many copies (default 1)", min = 1, max = 3)
            }
            string("variant_deck_id", "Another saved deck to compare against instead")
            integer("interruptions", "Ask: at least this many interruptions (default 1)", min = 1, max = 5)
            objects("filters", "Ask instead: a board passing these bounds") {
                string("head", "A trait: $TRAITS", required = true)
                integer("min", "At least")
                integer("max", "At most")
            }
            boolean("second", "Going second, six cards (default: going first)")
            integer("hands", "At most this many hands (default 2000)", min = 100, max = 4000)
            integer("seed", "The seed (default 1)")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val ablate = ToolSpec(
        "mapper_ablate",
        "Gameplay Mapper (10): a card's worth to the builder's deck — the deck against itself with one copy (or all) of the card " +
            "replaced by a blank, on the same hands — as the share of hands that lose the ask without it, with its paired 95 % " +
            "interval and the kinds of board no hand reaches without it. card: \"engine\" measures every engine card in turn (the " +
            "Starters tab's Without it column). Shown on page 10 with its progress and the person's Stop.",
        schema {
            string("card", "A card by name or passcode, or \"engine\" for every engine card", required = true)
            enum("copies", "One copy or all of them (default one)", listOf("one", "all"))
            integer("interruptions", "Ask: at least this many interruptions (default 1)", min = 1, max = 5)
            boolean("second", "Going second (default: going first)")
            integer("hands", "Hands each (default 300)", min = 50, max = 2000)
            integer("seed", "The seed (default 1)")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val all: List<ToolSpec> = listOf(library, starters, map, preset, compare, ablate)

    /**
     * The query in [input] (filters, weights, uses, avoids) over [base]: what is given replaces [base]'s, what is not keeps
     * it. [card] reads a card by name or passcode. Each thing that could not be read is said in the second list, and left out.
     */
    fun query(input: JsonObject, base: BoardPreset, card: (String) -> Int?): Pair<BoardPreset, List<String>> {
        val problems = ArrayList<String>()
        fun known(head: String): Boolean = head in BoardTraits.HEADS || head.startsWith(BoardTraits.THROUGH)
        val filters = (input["filters"] as? JsonArray)?.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val head = (o["head"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!known(head)) { problems += "No trait “$head”: $TRAITS."; return@mapNotNull null }
            BoardFilter(head, (o["min"] as? JsonPrimitive)?.doubleOrNull, (o["max"] as? JsonPrimitive)?.doubleOrNull)
        }
        val weights = (input["weights"] as? JsonObject)?.entries?.mapNotNull { (h, v) ->
            if (!known(h)) { problems += "No trait “$h”: $TRAITS."; return@mapNotNull null }
            val x = (v as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }
            if (x == null || !x.isFinite()) { problems += "The weight of $h is not a number."; null } else h to x
        }?.toMap()
        fun cards(key: String): List<Int>? = (input[key] as? JsonArray ?: (input[key] as? JsonPrimitive)?.let { JsonArray(listOf(it)) })
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.mapNotNull { w -> card(w) ?: run { problems += "No card “$w”."; null } }
        return base.copy(
            filters = filters ?: base.filters,
            weights = weights ?: base.weights,
            uses = cards("uses") ?: base.uses,
            avoids = cards("avoids") ?: base.avoids,
        ) to problems
    }
}
