package com.kaiharimoto.mastertool.core.ai

import com.kaiharimoto.mastertool.core.ai.providers.Providers
import com.kaiharimoto.mastertool.core.ai.providers.Wire
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Shootout's tools for Ai (Phase S stage 3, S.md §6, §6½): looking at a matchup and how far Ai is trusted on it, judging
 * one hand as its own judge, and the rubric — how the person judges the matchup, in words.
 *
 * `shootout_judge` is answered only inside a judging request the page makes (one hand, its examples, the rubric and the
 * model's prediction, never the person's answer to it), so an answer can never be given to a hand Ai was not asked.
 */
object ShootoutTools {

    val state = ToolSpec(
        "shootout_state",
        "Shootout (09): the deck and the target the page is on (the deck alone, or an opponent of its web), the trials kept " +
            "(the person's blind answers, Ai's, the person's after seeing Ai's), the rubric, and the trust panel — per kind of hand " +
            "Ai's agreement with the person's blind answers and its range, which kinds it may judge alone, the audits, how much " +
            "of the data is Ai's and what Ai's answers moved. Read it before writing the rubric.",
        schema { },
        ToolGroup.LOOK,
        phase = 3,
    )

    val judge = ToolSpec(
        "shootout_judge",
        "Shootout (09): your answer to the hand you were asked to judge. A rating: answer 1–5 (1 clear win or plays through, " +
            "2 lean win, 3 coin flip, 4 lean loss, 5 clear loss or bricks). A comparison: prefer left or right. sure: 0–1, how likely " +
            "the person's own answer is within one step of yours — be honest, it is scored. why: one line. question: optional, one " +
            "short question you would ask the person if they answer differently. Call it once per hand.",
        schema {
            string("answer", "A rating: 1 to 5, or its words (clear win … clear loss)")
            enum("prefer", "A comparison: the hand the person would rather open with", listOf("left", "right"))
            any("sure", "How sure you are, 0 to 1", required = true)
            string("why", "One line: what decided it", required = true)
            string("question", "One short question for the person, if they answer differently")
        },
        ToolGroup.APP,
        phase = 3,
    )

    val rubric = ToolSpec(
        "shootout_rubric",
        "Shootout (09): the rubric for the matchup on the page — how the person judges it, in short entries (\"A starter and a hand " +
            "trap beats their turn one unless it is Ash-only\"). read; add {text}; replace {old_text, text}; remove {old_text}. Each " +
            "entry one rule in the person's terms. A number in it (a percentage, odds) must come from a tool in this conversation or " +
            "the person's words, else write it without the number or mark it (estimate). The person reviews every change at the end.",
        schema {
            enum("action", "What to do", listOf("read", "add", "replace", "remove"), required = true)
            string("text", "add, replace: the entry")
            string("old_text", "replace, remove: a unique part of the entry")
        },
        ToolGroup.MEMORY,
        phase = 3,
    )

    val results = ToolSpec(
        "shootout_results",
        "Shootout (09): the results for the deck and target on the page, read from the person's own answers — each situation's " +
            "win rate over real hands, whether to go first or second when the roll is won, how much is settled, the calls (clear " +
            "of zero at 95 % with every card counted), and per card and situation its worth per copy in the opening hand, as the " +
            "turn's draw going second, and one more copy's, each with its 95 % range and the hands behind it; pairs that matter. " +
            "A card no hand has shown is unrated. Look-only.",
        schema { },
        ToolGroup.LOOK,
        phase = 3,
    )

    val whatIf = ToolSpec(
        "shootout_whatif",
        "Shootout (09): what a change to the deck does to each situation's win rate, read off the model of the person's answers " +
            "on the same hands — one copy of `from` made `to` (a swap), one more `to` (from left out: in any other card's place), " +
            "or one fewer `from` (to left out). Both are cards Shootout has rated for this deck (a name or passcode). Look-only: " +
            "nothing in the deck changes.",
        schema {
            string("from", "The card a copy is taken from, or leave out for one more of `to`")
            string("to", "The card the copy becomes, or leave out for one fewer of `from`")
        },
        ToolGroup.LOOK,
        phase = 3,
    )

    val priors = ToolSpec(
        "shootout_priors",
        "Shootout (09): your reading of the deck before any hand, when Shootout asks for it — where the ratings start, which the " +
            "person's answers then move. roles: only for a deck with no groups, every card's role (2 to 8 roles). leans: each role's " +
            "lean, -2 hurts a lot … 2 helps a lot, against an average card of the deck, one line why. pairs: up to 12 pairs of the " +
            "deck's cards that likely work together or get in each other's way, one line why. summary: one line on how the deck " +
            "wins. Cards by name as listed. Call it once.",
        schema {
            objects("roles", "Only when the deck has no groups: every card and its role") {
                string("card", "The card's name as listed", required = true)
                string("role", "Its role: a short name a player uses", required = true)
            }
            objects("leans", "Each role's lean", required = true) {
                string("role", "The role", required = true)
                integer("step", "-2 hurts a lot, -1 hurts, 0 neither, 1 helps, 2 helps a lot", required = true, min = -2, max = 2)
                string("why", "One line", required = true)
            }
            objects("pairs", "Pairs that likely work together or get in each other's way") {
                string("a", "A card's name", required = true)
                string("b", "The other card's name", required = true)
                string("why", "One line", required = true)
            }
            string("summary", "One line: how the deck wins", required = true)
        },
        ToolGroup.APP,
        phase = 3,
    )

    val all: List<ToolSpec> = listOf(state, judge, rubric, results, whatIf, priors)

    /** The tools a judging request offers: its answer alone — the cards' text comes with the hand, and nothing is changed. */
    val JUDGING: Set<String> = setOf("shootout_judge")

    /** The tools the deck's reading offers (S4.3): its answer alone — the deck comes with the request. */
    val READING: Set<String> = setOf("shootout_priors")

    /** How sure Ai said it was, read leniently ("0.8", 80, "80%"); null when it is not a number. */
    fun sure(input: JsonObject): Double? {
        val p = input["sure"] as? JsonPrimitive ?: return null
        val x = p.doubleOrNull ?: p.contentOrNull?.trim()?.removeSuffix("%")?.trim()?.toDoubleOrNull() ?: return null
        return (if (x > 1.0) x / 100 else x).coerceIn(0.0, 1.0)
    }

    /**
     * Why a test on held-out answers — Ai's exam on a calibration set, Is it better? (the ablation) — may not run on
     * [provider], or null (the red team, 2026-10). Codex keeps its read-only shell, which reads any file on this computer,
     * and loads the person's own MCP servers from its configuration: the held-out answers are in Shootout's files, so its
     * score would not be blind. Claude Code runs with no tool of its own and only the request's MCP server. Ordinary judging
     * stays open on Codex: the hand it judges has no answer of the person's yet.
     */
    fun heldOutRefusal(provider: String?, what: String): String? =
        if (Providers.byId(provider)?.wire == Wire.CODEX_CLI) {
            "$what does not run on Codex: Codex can read this computer's files, where your held-out answers are kept, so its " +
                "score would not be blind. Run it on Claude Code or an API connection."
        } else {
            null
        }
}
