package com.kaiharimoto.mastertool.core.ai.evidence

import com.kaiharimoto.mastertool.core.ai.ChatTurn
import com.kaiharimoto.mastertool.core.ai.Role

/**
 * Whether what Ai is about to write into a deck's guide has its numbers' proof in the conversation (1.0.98, the
 * evidence ledger). A percentage, odds or a probability must be one a tool computed in this conversation, or one the
 * person said; otherwise the write is refused with what to do instead — compute it, drop it, or say it is an estimate.
 */
object Evidence {
    /** Something said in the conversation a number can be read from: a tool's answer, or the person's own words. */
    data class Source(val tool: String, val input: String, val content: String)

    /** The person, as a source: in Fine Tuning they are the one who knows. */
    const val PERSON = "the person"

    /** The tools whose answers depend on the deck: their numbers go stale when it changes. */
    val DECK_TOOLS = setOf(
        "hand_odds", "world_tool", "world_run", "analyze_deck", "expected_winrate", "matchup_matrix", "validate_deck",
        // The Mapper's and Shootout's numbers are the deck's too (2026-10, the red team's finding 12): they go stale with it.
        "mapper_map", "mapper_starters", "mapper_library", "shootout_state", "shootout_results", "deck_compare", "mapper_ablate",
    )

    /** The Mapper's tools: what it counts of dealt hands vouches for a line as the goldfish's does ([simulated]). */
    val MAPPER_TOOLS = setOf("mapper_map", "mapper_starters", "mapper_library", "deck_compare", "mapper_ablate")

    /**
     * The tools whose answers are other people's words read from outside the app: a number found only there is what its
     * author claims, never something a check of ours computed. It is written as theirs — [attributed] — and kept as
     * [Proven.Status.QUOTED] (the course study, Phase 1).
     */
    val QUOTED_TOOLS = setOf(
        "web_fetch", "web_search", "watch_video", "archetype_guide", "rulings",
        "browser_read", "browser_elements", "browser_screenshot", "course_read", "course_frames", "course_pictures", "replay_read",
        // What the study wrote down from the course is still the author's word (1.1.47): a number found there is theirs.
        "course_open", "course_search", "playbook_read", "playbook_search",
    )

    /** Whether [s] is someone else's words read from outside: a number it holds is theirs to vouch for. */
    fun quoted(s: Source): Boolean = s.tool in QUOTED_TOOLS

    /**
     * The guide as it was before a write, as a source of the numbers the new entries keep from it (passed by the guide's
     * writer). Like [REREAD_TOOLS], Ai's own words read back.
     */
    const val CARRIED = "the guide"

    /**
     * The tools that read Ai's own memory back — the guide, its notes, its past conversations. A number found only there
     * is one Ai wrote before, not one a check computed now: it is judged as a quoted one, written as its author's
     * ("(per Joe)") unless a check computes it here too, or unless the ledger's record of the entry it was read from was
     * computed ([judge]'s `carried`). Before this, the distil's `memory_read` of the guide turned an author's number into
     * a checked one.
     */
    val REREAD_TOOLS = setOf("memory_read", "recall", "session_search")

    /** Whether [s] is Ai's own memory read back ([REREAD_TOOLS], [CARRIED]). */
    fun reread(s: Source): Boolean = s.tool in REREAD_TOOLS || s.tool == CARRIED

    /**
     * Whether [entry] says whose number it is: "(per Joe, chapter 3)", "(quoted …)", "(source: …)" or "according to".
     * Only an explicit mark counts — "once per turn" is card text, not an attribution.
     */
    fun attributed(entry: String): Boolean = ATTRIBUTION.containsMatchIn(entry)

    private val ATTRIBUTION = Regex("""\((?:per|quoted|quoting|source:|from)\s[^)]*\)|\baccording\s+to\b""", RegexOption.IGNORE_CASE)

    /** The tools a stale number can be computed again with, by the app alone. */
    val RERUNNABLE = setOf("hand_odds", "world_tool", "expected_winrate", "matchup_matrix")

    /**
     * Words that claim a line or a hand reaches its board — "gets there", "goes off", "makes the board" — which, with a
     * percentage, only the goldfish may vouch for (Phase D step 4, [lineClaims]).
     */
    private val LINE = Regex(
        """\b(gets?\s+there|got\s+there|go(?:es)?\s+off|went\s+off|going\s+off|makes?\s+(?:the|its|this|that)\s+(?:end\s+)?board|made\s+(?:the|its|this|that)\s+(?:end\s+)?board|reach(?:es|ed)?\s+(?:the|its|this|that)\s+(?:end\s+)?board)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether [entry] claims, with a number, that a line or a hand gets there, goes off or makes the board: such a number is
     * held to a `goldfish` source (the instrument through `world_tool`). Ai's own `world_run` simulation of play cannot vouch
     * for it — it did not use the written effects the app trusts.
     */
    fun lineClaims(entry: String): Boolean = LINE.containsMatchIn(entry) && Numbers.claimed(entry).isNotEmpty()

    /** Whether [s] is the goldfish's answer: the `goldfish` instrument run through `world_tool`. */
    fun goldfish(s: Source): Boolean =
        s.tool == GOLDFISH || (s.tool == "world_tool" && Regex(""""name"\s*:\s*"goldfish"""").containsMatchIn(s.input))

    /** Whether [s] played the deck's written effects out: the goldfish, or the Mapper over dealt hands. */
    fun simulated(s: Source): Boolean = goldfish(s) || s.tool in MAPPER_TOOLS

    const val GOLDFISH = "goldfish"

    /** The library fingerprint a goldfish answer names ("library 1a2b3c4d5e6f"), or empty. */
    fun libraryOf(content: String): String = Regex("""\blibrary ([0-9a-f]{12})\b""").find(content)?.groupValues?.get(1).orEmpty()

    /** Every tool answer and every word of the person's in [turns], newest first. */
    fun sources(turns: List<ChatTurn>): List<Source> {
        val calls = turns.flatMap { it.toolUses }.associateBy { it.id }
        return turns.flatMap { t ->
            when {
                t.isToolResults -> t.toolResults.filter { !it.isError }.map { r ->
                    val call = calls[r.id]
                    Source(r.name.removePrefix("mcp__neue__"), call?.input?.toString().orEmpty(), r.content)
                }
                t.role == Role.USER && t.text.isNotBlank() -> listOf(Source(PERSON, "", t.text))
                else -> emptyList()
            }
        }.asReversed()
    }

    sealed interface Verdict {
        /** No number in it that needs a proof: words. */
        data object Words : Verdict

        /** Every number found: [proven] is what the ledger keeps. */
        data class Proved(val proven: Proven) : Verdict

        /** A number nobody computed: refused, with what to do instead. */
        data class Refused(val message: String) : Verdict
    }

    /**
     * [entry] judged against [sources]: each claimed number traced to the newest source that computed it; an entry that
     * says it is an estimate is kept as one. [deck] is the guide's deck as it stands ([Ledger.fingerprint]).
     *
     * A number found only in Ai's own memory read back ([reread]) is as strong as the record it came from: [carried] are
     * the ledger's records of the guide entries the write keeps from (the one it replaces, the guide as it was). One of
     * them that was computed and holds the number lends it its proofs and its status (stale stays stale); else the number
     * is judged as a quoted one — never stronger than [Proven.Status.QUOTED].
     */
    fun judge(entry: String, sources: List<Source>, deck: String, now: Long, carried: List<Proven> = emptyList()): Verdict {
        val claims = Numbers.claimed(entry)
        if (claims.isEmpty()) return Verdict.Words
        // Only the goldfish can vouch for a line (Phase D step 4): a line's percentage is traced to its answers alone.
        val line = lineClaims(entry)
        val pool = if (line) sources.filter(::simulated) else sources
        val proofs = mutableListOf<Proof>()
        val missing = mutableListOf<Numbers.Claimed>()
        // A number one of our own checks computed outranks the same number read in someone's guide, or read back.
        val ours = pool.filterNot { quoted(it) || reread(it) }
        var anyQuoted = false
        var status = Proven.Status.CHECKED
        fun keep(s: Source, c: Numbers.Claimed) {
            if (proofs.none { it.tool == s.tool && it.input == s.input }) {
                proofs += Proof(
                    s.tool, s.input, excerpt(s.content, c), now, if (s.tool in DECK_TOOLS || s.tool == GOLDFISH) deck else "",
                    library = if (simulated(s)) libraryOf(s.content) else "",
                )
            }
        }
        claims.forEach { c ->
            val s = ours.firstOrNull { Numbers.found(c, valuesOf(it)) }
            if (s != null) {
                keep(s, c)
                return@forEach
            }
            // Read back from Ai's own memory: the record of the entry it was written in vouches, when a check computed it.
            if (pool.any { reread(it) && Numbers.found(c, valuesOf(it)) }) {
                val records = carried.filter { r -> r.status in COMPUTED && r.proofs.isNotEmpty() && Numbers.found(c, Numbers.values(r.entry)) }
                if (records.isNotEmpty()) {
                    records.flatMap { it.proofs }.forEach { p -> if (p !in proofs) proofs += p }
                    status = (records.map { it.status } + status).minBy { COMPUTED.indexOf(it) }
                    return@forEach
                }
            }
            val q = pool.firstOrNull { (quoted(it) || reread(it)) && Numbers.found(c, valuesOf(it)) }
            if (q == null) {
                missing += c
            } else {
                anyQuoted = true
                keep(q, c)
            }
        }
        val unattributed = anyQuoted && !attributed(entry) && !Numbers.isEstimate(entry)
        return when {
            missing.isEmpty() && unattributed -> Verdict.Refused(refusedQuoted(proofs))
            missing.isEmpty() -> Verdict.Proved(
                Proven(entry, proofs, if (anyQuoted && !Numbers.isEstimate(entry)) Proven.Status.QUOTED else if (anyQuoted) Proven.Status.ESTIMATE else status, now),
            )
            Numbers.isEstimate(entry) -> Verdict.Proved(Proven(entry, proofs, Proven.Status.ESTIMATE, now))
            line -> Verdict.Refused(
                "Not written: a line's or a hand's percentage — that it gets there, goes off or makes the board — is held to the " +
                    "goldfish, which plays the deck's written effects through the engine, and nothing in this conversation from it " +
                    "computed " + missing.joinToString { "“${it.written}”" } + ". Run it (world_tool goldfish, with the deck and a " +
                    "target fx_target names) and write the number it gives; or write the line without the number; or mark it " +
                    "“(estimate)”. A world_run simulation cannot vouch for a line.",
            )
            else -> Verdict.Refused(
                "Not written: the guide keeps only numbers a check computed, and nothing in this conversation computed " +
                    missing.joinToString { "“${it.written}”" } + ". Compute it first (hand_odds, calculate, or an instrument with " +
                    "world_tool) and write it then; or write the entry without the number; or, if it is your judgment, say so " +
                    "in the entry with “(estimate)”. A number read in a guide, a page or a video is written as its author's: " +
                    "“(per <author>)”.",
            )
        }
    }

    /** The statuses of a record a check computed, weakest first: what a number read back from it may carry. */
    private val COMPUTED = listOf(Proven.Status.CONTRADICTED, Proven.Status.STALE, Proven.Status.CHECKED)

    private fun refusedQuoted(proofs: List<Proof>): String {
        val outside = proofs.filter { it.tool in QUOTED_TOOLS }.map { it.tool }.distinct()
        val back = proofs.filter { it.tool in REREAD_TOOLS || it.tool == CARRIED }.map { it.tool }.distinct()
        val why = listOfNotNull(
            outside.takeIf { it.isNotEmpty() }?.let { it.joinToString() + " only read that number in someone else's words — it is their claim, not a check of ours" },
            back.takeIf { it.isNotEmpty() }?.let {
                it.joinToString() + " only read that number back from what was written before — " +
                    "a number of the guide's own that no check of ours computed, or someone else's written down"
            },
        ).joinToString("; and ")
        return "Not written: $why. Say whose it is in the entry, “(per <author>, <where>)”, and it is kept as quoted; or " +
            "compute it yourself (hand_odds, calculate, world_tool) and write that; or write the entry without the number."
    }

    /** The probabilities [s] states: a calculation's or a script's every number, anything else's only as written ([Numbers.values]). */
    fun valuesOf(s: Source): List<Double> = Numbers.values(s.content, bare = s.tool in Numbers.BARE_TOOLS)

    /** The few lines of [content] around where [claim]'s number stands, so the proof shows what it rests on. */
    private fun excerpt(content: String, claim: Numbers.Claimed): String {
        val lines = content.lines()
        // The line that states it as a probability first; else any line holding the number (a calculation's bare one).
        val at = lines.indexOfFirst { l -> Numbers.found(claim, Numbers.values(l)) }.takeIf { it >= 0 }
            ?: lines.indexOfFirst { l -> Numbers.found(claim, Numbers.values(l, bare = true)) }
        val pick = if (at < 0) lines.take(3) else lines.subList((at - 1).coerceAtLeast(0), (at + 2).coerceAtMost(lines.size))
        return pick.joinToString("\n").take(400)
    }
}
