package com.kaiharimoto.mastertool.core.ai.eval

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.ai.calc.Calc
import com.kaiharimoto.mastertool.core.ai.evidence.Evidence
import com.kaiharimoto.mastertool.core.ai.proposals.Expect
import com.kaiharimoto.mastertool.core.ai.proposals.Proposal
import com.kaiharimoto.mastertool.core.ai.proposals.ProposalOp
import com.kaiharimoto.mastertool.core.ai.proposals.Proposals
import com.kaiharimoto.mastertool.core.deck.DeckGroup
import com.kaiharimoto.mastertool.core.deck.DeckGroups
import com.kaiharimoto.mastertool.core.hand.GoalCount
import com.kaiharimoto.mastertool.core.hand.HandGoal
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.world.Goals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The optimization set (Phase G, G.9; the red team's A1 and A2): decks with a worse change planted, and the change that undoes
 * it. Ai is handed the deck and three tools — `get_deck`, `hand_odds` (counted exactly, over the deck's own groups) and
 * `deck_propose` — and passes when it proposes the change that undoes the plant **with proof**: its numbers must be ones the
 * table computed in the conversation (`Evidence.judge`), never a guess. Graded by code on the proposal, never on words.
 */
object Optimizations {
    /** One deck with a plant: its groups (a card and its copies), the cards on hand to add, and the change that undoes it. */
    data class Case(
        val id: String,
        val deck: String,
        val groups: List<Pair<String, List<Pair<String, Int>>>>,
        /** Cards the person owns and could add, each with its role. */
        val binder: List<Pair<String, String>>,
        /** A passing proposal takes out a copy of one of [out] and puts in one of [into]. */
        val out: Set<String>,
        val into: Set<String>,
        val why: String,
    ) {
        val main: List<String> get() = groups.flatMap { (_, cards) -> cards.flatMap { (n, k) -> List(k) { n } } }
    }

    val all: List<Case> = listOf(
        Case(
            "brick-for-starter", "Snake-Eye",
            listOf(
                "Starters" to listOf("Snake-Eye Ash" to 3, "Snake-Eyes Poplar" to 3, "Original Sinful Spoils - Snake-Eye" to 2),
                "Extenders" to listOf("Snake-Eye Oak" to 3, "Snake-Eye Birch" to 2, "Diabellstar the Black Witch" to 2, "Fiendsmith Engraver" to 2, "Fiendsmith's Tract" to 2),
                "Hand traps" to listOf("Ash Blossom & Joyous Spring" to 3, "Infinite Impermanence" to 3, "Effect Veiler" to 2, "Ghost Belle & Haunted Mansion" to 2),
                "Board breakers" to listOf("Called by the Grave" to 2, "Triple Tactics Talent" to 2, "Dark Ruler No More" to 1),
                "Other" to listOf("Promethean Princess, Bestower of Flames" to 1, "Bonfire" to 2),
                "Bricks" to listOf("Blue-Eyes White Dragon" to 3),
            ),
            listOf(
                "WANTED: Seeker of Sinful Spoils" to "Starter: searches a Snake-Eye card by itself",
                "Original Sinful Spoils - Snake-Eye" to "Starter (the deck plays 2)",
                "Droll & Lock Bird" to "Hand trap",
                "Nibiru, the Primal Being" to "Hand trap",
            ),
            out = setOf("Blue-Eyes White Dragon"),
            into = setOf("WANTED: Seeker of Sinful Spoils", "Original Sinful Spoils - Snake-Eye"),
            why = "Three copies of a card that does nothing in the deck, where a starter belongs.",
        ),
        Case(
            "too-many-hand-traps", "Branded",
            listOf(
                "Starters" to listOf("Fallen of Albaz" to 3, "Branded Fusion" to 3),
                "Extenders" to listOf("Aluber the Jester of Despia" to 3, "Branded in Red" to 2, "Branded Opening" to 1, "Tri-Brigade Mercourier" to 2),
                "Hand traps" to listOf(
                    "Ash Blossom & Joyous Spring" to 3, "Infinite Impermanence" to 3, "Effect Veiler" to 3, "Ghost Belle & Haunted Mansion" to 3,
                    "Nibiru, the Primal Being" to 3, "Droll & Lock Bird" to 3,
                ),
                "Other" to listOf("Called by the Grave" to 2, "Branded Lost" to 1, "Branded Retribution" to 2, "Foolish Burial" to 1, "Sprind the Irondash Dragon" to 2),
            ),
            listOf(
                "Branded Opening" to "Starter: sends a monster and searches a Branded card (the deck plays 1)",
                "Despian Tragedy" to "Extender",
                "Pot of Prosperity" to "Draw spell",
            ),
            out = setOf("Nibiru, the Primal Being", "Droll & Lock Bird", "Ghost Belle & Haunted Mansion", "Effect Veiler"),
            into = setOf("Branded Opening"),
            why = "Eighteen hand traps beside six starters: the hands that hold no starter are the problem, not the interaction.",
        ),
        Case(
            "garnet-at-three", "Fiendsmith Tenpai",
            listOf(
                "Starters" to listOf("Tenpai Dragon Paidra" to 3, "Tenpai Dragon Chundra" to 3, "Sangen Kaimen" to 3, "Fiendsmith Engraver" to 3),
                "Extenders" to listOf("Tenpai Dragon Fadra" to 2, "Fiendsmith's Tract" to 2, "Sangen Summoning" to 2),
                "Hand traps" to listOf("Ash Blossom & Joyous Spring" to 3, "Infinite Impermanence" to 3, "Effect Veiler" to 2, "Ghost Belle & Haunted Mansion" to 2),
                "Other" to listOf("Called by the Grave" to 2, "Triple Tactics Talent" to 2, "Forbidden Droplet" to 2, "Bonfire" to 2, "Harpie's Feather Duster" to 1),
                "Garnets (never wanted in hand)" to listOf("Fiendsmith in Paradise" to 3),
            ),
            listOf(
                "Tenpai Dragon Fadra" to "Extender (the deck plays 2)",
                "Sangen Summoning" to "Extender (the deck plays 2)",
                "Droll & Lock Bird" to "Hand trap",
            ),
            out = setOf("Fiendsmith in Paradise"),
            into = setOf("Tenpai Dragon Fadra", "Sangen Summoning", "Droll & Lock Bird"),
            why = "A card the deck only wants in the Graveyard, at three copies: each is a dead draw.",
        ),
    )

    fun byId(id: String): Case? = all.firstOrNull { it.id == id }

    /** What the model is told: the job and the tools, and that only a proposal with proof counts. */
    const val RULES = "You are improving a Yu-Gi-Oh! deck for a player. You have three tools: get_deck reads the deck with its groups, " +
        "hand_odds counts an opening hand's odds exactly (a group, cards or a condition; with and without ask about a change saved " +
        "nowhere), and deck_propose offers the player one change. Find the change that helps the deck most, measure it with " +
        "hand_odds before and after, and propose it with deck_propose: the ops, why, and what it should do with the numbers " +
        "hand_odds gave. Every number you write must be one a tool gave you. Propose one change; the player decides."

    fun prompt(c: Case): String = "Here is my ${c.deck} deck (40 cards). Cards I own and could add: " +
        c.binder.joinToString("; ") { (n, role) -> "$n — $role" } + ". What one change would make it better? Propose it."

    fun set(): EvalSet = EvalSet(
        EvalSets.OPTIMIZE,
        "Optimization",
        "${all.size} decks with a worse change planted: passed by proposing the change that undoes it, with numbers a tool computed.",
        all.map { c -> EvalItem(c.id, prompt(c), Grader.Optimize(c.id), "written for the set: ${c.why}") },
    )
}

/**
 * One optimization case's table (Phase G, G.9): the deck, its groups, three tools answered here, every result kept as the
 * evidence a proposal is judged against, and the grade on the proposals made.
 */
class OptimizeTable(val case: Optimizations.Case, private val now: Long = 0L) {
    private val names: List<String> = (case.main + case.binder.map { it.first }).distinct()
    private val idOf: Map<String, CardId> = names.withIndex().associate { (i, n) -> n.lowercase() to CardId(900_001 + i) }
    private val nameOf: Map<CardId, String> = names.associateBy { idOf.getValue(it.lowercase()) }
    private val main: List<CardId> = case.main.map { idOf.getValue(it.lowercase()) }
    private val groups: DeckGroups = DeckGroups(
        groups = case.groups.mapIndexed { i, (g, _) -> DeckGroup("g$i", g, i % 7, i) },
        assignments = case.groups.flatMapIndexed { i, (_, cards) -> cards.map { (n, _) -> idOf.getValue(n.lowercase()) to "g$i" } }.toMap(),
    )

    /** Every tool result so far, newest first: what `Evidence.judge` reads. */
    val sources = mutableListOf<Evidence.Source>()

    /** The proposals the table accepted, and those it refused (with why). */
    val proposed = mutableListOf<Proposal>()
    val refused = mutableListOf<String>()

    fun tool(name: String, input: JsonObject): Pair<String, Boolean> {
        val bare = name.removePrefix("mcp__neue__")
        val (text, error) = when (bare) {
            "get_deck" -> deckWords() to false
            "hand_odds" -> odds(input)
            "deck_propose" -> propose(input)
            else -> "Only get_deck, hand_odds and deck_propose are here." to true
        }
        if (!error && bare != "deck_propose") sources.add(0, Evidence.Source(bare, input.toString(), text))
        return text to error
    }

    private fun deckWords(): String = buildString {
        appendLine("“${case.deck}”, ${main.size} cards, by group:")
        case.groups.forEach { (g, cards) -> appendLine("$g (${cards.sumOf { it.second }}): " + cards.joinToString(", ") { (n, k) -> "$k $n" }) }
    }.trim()

    private fun card(n: String): CardId? = idOf[n.trim().lowercase()]

    private fun odds(i: JsonObject): Pair<String, Boolean> {
        var deck = main
        val changed = mutableListOf<String>()
        for (w in ToolArgs.strings(i, "without").filter { it.isNotBlank() }) {
            val id = card(w) ?: return "No card named “$w” here." to true
            val at = deck.indexOfLast { it == id }
            if (at < 0) return "The deck holds no ${nameOf[id]} to cut." to true
            deck = deck.toMutableList().also { it.removeAt(at) }
            changed += "−1 ${nameOf[id]}"
        }
        for (w in ToolArgs.strings(i, "with").filter { it.isNotBlank() }) {
            val id = card(w) ?: return "No card named “$w” here." to true
            deck = deck + id
            changed += "+1 ${nameOf[id]}"
        }
        val n = ToolArgs.int(i, "at_least") ?: 1
        val condition = ToolArgs.string(i, "condition")?.trim()?.takeIf { it.isNotEmpty() }
            ?: ToolArgs.string(i, "group")?.trim()?.takeIf { it.isNotEmpty() }?.let { g ->
                val found = case.groups.firstOrNull { it.first.equals(g, ignoreCase = true) }?.first ?: return "No group “$g”. Groups: ${case.groups.joinToString { it.first }}." to true
                "\"$found\">=$n"
            }
            ?: ToolArgs.strings(i, "cards").filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { cs ->
                cs.forEach { if (card(it) == null) return "No card named “$it” here." to true }
                "any(${cs.joinToString(", ") { "\"${nameOf[card(it)]}\"" }})>=$n"
            }
            ?: return "Ask with a group, cards or a condition." to true
        val goal = HandGoal("q", "", condition = condition)
        val read: (CardId) -> String? = { nameOf[it] }
        GoalCount.problem(goal, deck, groups, read)?.let { return "$it Groups: ${case.groups.joinToString { g -> g.first }}." to true }
        val o = GoalCount.odds(goal, deck, groups, read)
        val turn = ToolArgs.string(i, "turn") ?: "both"
        fun pct(p: Double) = Calc.format(p * 100) + "%"
        val lines = buildList {
            if (turn != "second") add("going first (5 cards): ${pct(o.first)}")
            if (turn != "first") add("going second (6 cards): ${pct(o.second)}")
        }
        val with = if (changed.isEmpty()) "" else " with ${changed.joinToString(", ")} (saved nowhere)"
        return "“${case.deck}”$with, ${deck.size} cards, ${Goals.words(condition)}:\n" + lines.joinToString("\n") to false
    }

    private fun propose(i: JsonObject): Pair<String, Boolean> {
        val ops = ToolArgs.objects(i, "ops").map { o ->
            ProposalOp(
                ToolArgs.string(o, "op").orEmpty(),
                ToolArgs.string(o, "card").orEmpty(),
                ToolArgs.int(o, "count"),
                ToolArgs.string(o, "section"),
                ToolArgs.string(o, "to_section"),
            )
        }
        if (ops.isEmpty()) return "A proposal needs its ops." to true
        ops.forEach { o -> if (card(o.card) == null) return "No card named “${o.card}” in the deck or the binder." to true }
        val expect = ToolArgs.objects(i, "expect").map { e ->
            Expect(ToolArgs.string(e, "metric").orEmpty(), ToolArgs.double(e, "before"), ToolArgs.double(e, "after"), ToolArgs.double(e, "low"), ToolArgs.double(e, "high"), ToolArgs.string(e, "unit") ?: "%")
        }
        val p = Proposal(
            id = "p${proposed.size + refused.size + 1}", deckId = case.id, title = ToolArgs.string(i, "title").orEmpty(),
            ops = ops, why = ToolArgs.string(i, "why").orEmpty(), evidence = ToolArgs.strings(i, "evidence"), expect = expect, at = now,
        )
        return when (val v = Evidence.judge(Proposals.claims(p), sources, "", now)) {
            is Evidence.Verdict.Refused -> {
                refused += v.message
                "Not proposed: ${v.message}" to true
            }
            is Evidence.Verdict.Words -> {
                refused += "no numbers"
                "Not proposed: say what the change does, with the numbers a tool gave (why and expect)." to true
            }
            is Evidence.Verdict.Proved -> {
                proposed += p
                "Proposed to the player: ${p.title}. ${Proposals.opsWords(p.ops)}." to false
            }
        }
    }

    /** Passed: a proposal, proved, that takes out a planted card and puts in one that undoes the plant. */
    fun grade(): Graded {
        val hit = proposed.firstOrNull { p ->
            val outs = p.ops.filter { it.op == "remove" || (it.op == "set" && (it.count ?: 0) < 3) }.map { it.card.trim().lowercase() }
            val ins = p.ops.filter { it.op == "add" || it.op == "set" }.map { it.card.trim().lowercase() }
            outs.any { o -> case.out.any { it.lowercase() == o } } && ins.any { a -> case.into.any { it.lowercase() == a } }
        }
        return when {
            hit != null -> Graded(true, "proposed with proof: ${Proposals.opsWords(hit.ops)}")
            proposed.isNotEmpty() -> Graded(false, "proposed another change: " + proposed.joinToString("; ") { Proposals.opsWords(it.ops) })
            refused.isNotEmpty() -> Graded(false, "proposals refused: ${refused.last()}")
            else -> Graded(false, "no proposal")
        }
    }

    companion object {
        val TOOLS: Set<String> = setOf("get_deck", "hand_odds", "deck_propose")
    }
}

/** The set's bounds, played by code: nothing proposed, the fix guessed without measuring it, and the fix measured and proved. */
object OptimizeBaselines {
    fun nothing(c: Optimizations.Case): Graded = OptimizeTable(c).grade()

    /** The right change, with numbers made up: refused by the ledger. */
    fun guessed(c: Optimizations.Case): Graded = OptimizeTable(c).also { t ->
        t.tool("deck_propose", proposal(c, "71.3%", "77.9%", 71.3, 77.9))
    }.grade()

    /** The right change, measured with hand_odds before and after, then proposed with those numbers. */
    fun solved(c: Optimizations.Case): Graded {
        val t = OptimizeTable(c)
        val out = c.out.first { o -> c.main.contains(o) }
        val into = c.into.first()
        val starters = c.groups.first().first
        val before = t.tool("hand_odds", obj("group" to starters, "turn" to "first")).first
        val after = t.tool("hand_odds", obj("condition" to "\"$starters\">=1 | \"$into\">=1", "turn" to "first", "without" to listOf(out), "with" to listOf(into))).first
        fun last(s: String) = Regex("""([0-9]+(?:\.[0-9]+)?)%""").findAll(s).last().groupValues[1]
        val b = last(before)
        val a = last(after)
        t.tool("deck_propose", proposal(c, "$b%", "$a%", b.toDouble(), a.toDouble()))
        return t.grade()
    }

    private fun proposal(c: Optimizations.Case, b: String, a: String, before: Double, after: Double): JsonObject {
        val out = c.out.first { o -> c.main.contains(o) }
        val into = c.into.first()
        return obj(
            "title" to "$into over $out",
            "ops" to listOf(obj("op" to "remove", "card" to out, "count" to 1), obj("op" to "add", "card" to into, "count" to 1)),
            "why" to "Opening a starter going first rises from $b to $a.",
            "expect" to listOf(obj("metric" to "Opens going first", "before" to before, "after" to after)),
        )
    }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

    private fun json(v: Any): kotlinx.serialization.json.JsonElement = when (v) {
        is JsonObject -> v
        is List<*> -> kotlinx.serialization.json.JsonArray(v.map { json(it!!) })
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    fun bounds(all: List<Optimizations.Case> = Optimizations.all): Triple<Int, Int, Int> =
        Triple(all.count { nothing(it).pass }, all.count { guessed(it).pass }, all.count { solved(it).pass })
}

