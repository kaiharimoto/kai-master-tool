package com.kaiharimoto.mastertool.core.shootout.teach

import com.kaiharimoto.mastertool.core.ai.ToolArgs
import com.kaiharimoto.mastertool.core.shootout.bench.Bench
import com.kaiharimoto.mastertool.core.shootout.store.PairPick
import com.kaiharimoto.mastertool.core.shootout.store.RoleLean
import com.kaiharimoto.mastertool.core.shootout.store.RolePick
import com.kaiharimoto.mastertool.core.shootout.store.ShootoutPriors
import kotlinx.serialization.json.JsonObject

/**
 * Ai reads the deck before any hand (Phase S, S4.3; `docs/phases/S.md` §6 1): what it is handed — the deck's cards with their
 * copies, roles and text, and in a matchup the opponent's — and how its answer through `shootout_priors` is read into
 * [ShootoutPriors]. A deck with groups keeps them as its roles and Ai leans each; a deck with none is given Ai's roles too.
 */
object PriorsBrief {
    /** A role's name, in characters. */
    const val ROLE_NAME = 32

    /** Ai's roles for a deck with no groups: at least two, at most this many. */
    const val MAX_ROLES = 8

    /** A reason, in characters. */
    const val WHY = 200

    /** Whether the deck brings roles of its own (its groups), so Ai only leans them. */
    fun ownRoles(bench: Bench): Boolean = !bench.aiRoles && bench.roleNames != listOf(Bench.ALL_CARDS)

    /** The message: the deck as it stands, what is asked, and how to answer. [name] names a card, [cardText] gives its text. */
    fun text(bench: Bench, deckName: String, name: (Int) -> String, cardText: (Int) -> String?): String {
        val main = bench.strata.first()
        val mine = bench.own.filter { bench.copies(it, main) > 0 }
        val own = ownRoles(bench)
        return buildString {
            appendLine("Read “$deckName” before any hand is judged, then answer with shootout_priors (once).")
            appendLine()
            appendLine(
                if (bench.alone) {
                    "The person will judge its opening hands on their own: how often a hand like it does what the deck wants."
                } else {
                    "The person will judge its opening hands against “${bench.opponentName}”: how a game with a hand like it goes."
                },
            )
            appendLine(
                "Your reading only sets where the ratings start; the person's answers move them from there. Say what the cards' text " +
                    "makes likely, and leave a role level (0) when the text does not say.",
            )
            appendLine()
            appendLine("## The deck")
            mine.forEach { id ->
                val role = if (own) " · ${bench.roleOf(id) ?: Bench.UNGROUPED}" else ""
                val t = cardText(id)?.replace('\n', ' ')?.take(JudgeBrief.CARD_TEXT).orEmpty()
                appendLine("- ${bench.copies(id, main)} ${name(id)}$role${if (t.isNotEmpty()) ": $t" else ""}")
            }
            val theirs = bench.decks.theirs(main)
            if (theirs != null) {
                appendLine()
                appendLine("## Their deck (${bench.opponentName})")
                bench.theirs.withIndex().filter { theirs[it.index] > 0 }.forEach { (i, id) ->
                    val t = cardText(id)?.replace('\n', ' ')?.take(JudgeBrief.CARD_TEXT / 2).orEmpty()
                    appendLine("- ${theirs[i]} ${name(id)}${if (t.isNotEmpty()) ": $t" else ""}")
                }
            }
            appendLine()
            appendLine("## What to answer")
            if (own) {
                val roles = bench.roleNames.filter { r -> mine.any { bench.roleOf(it) == r } }
                appendLine("The deck's groups are its roles: ${roles.joinToString(", ")}. Give each a lean (leans).")
            } else {
                appendLine("The deck has no groups: give every card above a role (roles) — 2 to $MAX_ROLES roles, short names a player uses (Starters, Extenders, Hand traps, Bricks) — and a lean for each role (leans).")
            }
            appendLine("A lean is how much one card of the role likely helps a hand, against an average card of the deck, in steps: -2 hurts a lot, -1 hurts, 0 neither, 1 helps, 2 helps a lot. One line why each.")
            appendLine("pairs: up to ${Bench.PAIRS} pairs of the deck's cards that likely work together (a combo) or get in each other's way, one line why each — only where the text says so.")
            append("summary: one line on how the deck wins${if (bench.alone) "" else " this matchup"}.")
        }.trim()
    }

    /** What `shootout_priors` gave: the reading, or what was wrong with it in words for Ai. */
    sealed interface Read {
        class Done(val priors: ShootoutPriors) : Read
        class Problem(val message: String) : Read
    }

    /**
     * Ai's answer read against [bench]: cards by name or passcode ([name] names the deck's cards), roles only for a deck with
     * none of its own, a lean for a role the deck has, steps within −2..2, pairs of two different cards of the deck.
     */
    fun read(input: JsonObject, bench: Bench, name: (Int) -> String, model: String?, at: Long): Read {
        val main = bench.strata.first()
        val mine = bench.own.filter { bench.copies(it, main) > 0 }
        val byName = mine.associateBy { name(it).trim().lowercase() }
        fun card(raw: String?): Int? {
            val s = raw?.trim() ?: return null
            s.toIntOrNull()?.let { p -> return bench.canonical(p).takeIf { it in mine } }
            return byName[s.lowercase()]
        }
        val own = ownRoles(bench)
        val roleInput = ToolArgs.objects(input, "roles")
        val roles = ArrayList<RolePick>()
        if (roleInput.isNotEmpty()) {
            if (own) return Read.Problem("The deck's groups are its roles (${bench.roleNames.joinToString()}): leave roles out and lean those.")
            roleInput.forEach { o ->
                val raw = ToolArgs.string(o, "card")
                val c = card(raw) ?: return Read.Problem("roles: “$raw” is not a card of the deck. Use the names as listed.")
                val r = ToolArgs.string(o, "role")?.trim()?.take(ROLE_NAME)?.takeIf { it.isNotEmpty() } ?: return Read.Problem("roles: ${name(c)} has no role.")
                if (roles.none { it.card == c }) roles += RolePick(c, r)
            }
            val distinct = roles.map { it.role.lowercase() }.distinct().size
            if (distinct !in 2..MAX_ROLES) return Read.Problem("roles: give 2 to $MAX_ROLES roles; you gave $distinct.")
        } else if (!own) {
            return Read.Problem("The deck has no groups: give every card a role (roles), then lean each role.")
        }
        val known = if (own) bench.roleNames else roles.map { it.role }.distinct()
        val leans = ArrayList<RoleLean>()
        ToolArgs.objects(input, "leans").forEach { o ->
            val raw = ToolArgs.string(o, "role")?.trim().orEmpty()
            val role = known.firstOrNull { it.equals(raw, ignoreCase = true) }
                ?: return Read.Problem("leans: “$raw” is not a role. The roles are: ${known.joinToString()}.")
            val step = ToolArgs.int(o, "step") ?: return Read.Problem("leans: $role needs a step from -2 to 2.")
            if (step !in ShootoutPriors.STEPS) return Read.Problem("leans: $role's step is $step; steps run from -2 to 2.")
            if (leans.none { it.role == role }) leans += RoleLean(role, step, why(o))
        }
        val pairs = ArrayList<PairPick>()
        ToolArgs.objects(input, "pairs").forEach { o ->
            val ra = ToolArgs.string(o, "a")
            val rb = ToolArgs.string(o, "b")
            val a = card(ra) ?: return Read.Problem("pairs: “$ra” is not a card of the deck.")
            val b = card(rb) ?: return Read.Problem("pairs: “$rb” is not a card of the deck.")
            if (a == b) return Read.Problem("pairs: a pair is two different cards (${name(a)} twice).")
            if (pairs.none { setOf(it.a, it.b) == setOf(a, b) }) pairs += PairPick(minOf(a, b), maxOf(a, b), why(o))
        }
        if (pairs.size > Bench.PAIRS) return Read.Problem("pairs: at most ${Bench.PAIRS}; you gave ${pairs.size}.")
        val summary = ToolArgs.string(input, "summary")?.trim()?.take(WHY * 2).orEmpty()
        return Read.Done(ShootoutPriors(roles = roles, leans = leans, pairs = pairs, model = model, at = at, deckPrint = bench.deckPrint, summary = summary))
    }

    private fun why(o: JsonObject): String = ToolArgs.string(o, "why")?.trim()?.take(WHY).orEmpty()

    /** The reading in words, for `shootout_state`: what Ai said of the deck, and what is in use. Empty with none. */
    fun describe(p: ShootoutPriors?, name: (Int) -> String): String {
        if (p == null || p.isEmpty) return ""
        return buildString {
            if (p.summary.isNotBlank()) appendLine("- ${p.summary}")
            if (p.roles.isNotEmpty()) appendLine("- Roles (the deck has no groups): " + p.roles.groupBy { it.role }.entries.joinToString("; ") { (r, cs) -> "$r: ${cs.joinToString { name(it.card) }}" })
            p.leans.forEach { l -> appendLine("- ${l.role}: ${ShootoutPriors.stepWords(l.step)}${if (!l.on) " (turned off)" else ""}${if (l.why.isNotBlank()) " — ${l.why}" else ""}") }
            p.pairs.forEach { q -> appendLine("- ${name(q.a)} + ${name(q.b)}${if (!q.on) " (turned off)" else ""}${if (q.why.isNotBlank()) " — ${q.why}" else ""}") }
        }.trim()
    }
}
