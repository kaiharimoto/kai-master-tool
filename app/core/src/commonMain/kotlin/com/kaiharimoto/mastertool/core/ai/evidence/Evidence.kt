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
    val DECK_TOOLS = setOf("hand_odds", "world_tool", "world_run", "analyze_deck", "expected_winrate", "matchup_matrix", "validate_deck")

    /**
     * The tools whose answers are other people's words read from outside the app: a number found only there is what its
     * author claims, never something a check of ours computed. It is written as theirs — [attributed] — and kept as
     * [Proven.Status.QUOTED] (the course study, Phase 1).
     */
    val QUOTED_TOOLS = setOf(
        "web_fetch", "web_search", "watch_video", "archetype_guide", "rulings",
        "browser_read", "browser_elements", "browser_screenshot", "course_read", "course_frames",
    )

    /** Whether [s] is someone else's words read from outside: a number it holds is theirs to vouch for. */
    fun quoted(s: Source): Boolean = s.tool in QUOTED_TOOLS

    /**
     * Whether [entry] says whose number it is: "(per Joe, chapter 3)", "(quoted …)", "(source: …)" or "according to".
     * Only an explicit mark counts — "once per turn" is card text, not an attribution.
     */
    fun attributed(entry: String): Boolean = ATTRIBUTION.containsMatchIn(entry)

    private val ATTRIBUTION = Regex("""\((?:per|quoted|quoting|source:|from)\s[^)]*\)|\baccording\s+to\b""", RegexOption.IGNORE_CASE)

    /** The tools a stale number can be computed again with, by the app alone. */
    val RERUNNABLE = setOf("hand_odds", "world_tool")

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
     */
    fun judge(entry: String, sources: List<Source>, deck: String, now: Long): Verdict {
        val claims = Numbers.claimed(entry)
        if (claims.isEmpty()) return Verdict.Words
        // Only the goldfish can vouch for a line (Phase D step 4): a line's percentage is traced to its answers alone.
        val line = lineClaims(entry)
        val pool = if (line) sources.filter(::goldfish) else sources
        val proofs = mutableListOf<Proof>()
        val missing = mutableListOf<Numbers.Claimed>()
        // A number one of our own checks computed outranks the same number read in someone's guide.
        val ours = pool.filterNot(::quoted)
        var anyQuoted = false
        claims.forEach { c ->
            val s = ours.firstOrNull { Numbers.found(c, Numbers.values(it.content)) }
                ?: pool.firstOrNull { quoted(it) && Numbers.found(c, Numbers.values(it.content)) }?.also { anyQuoted = true }
            if (s == null) {
                missing += c
            } else if (proofs.none { it.tool == s.tool && it.input == s.input }) {
                proofs += Proof(
                    s.tool, s.input, excerpt(s.content, c), now, if (s.tool in DECK_TOOLS || s.tool == GOLDFISH) deck else "",
                    library = if (goldfish(s)) libraryOf(s.content) else "",
                )
            }
        }
        val unattributed = anyQuoted && !attributed(entry) && !Numbers.isEstimate(entry)
        return when {
            missing.isEmpty() && unattributed -> Verdict.Refused(
                "Not written: " + proofs.filter { it.tool in QUOTED_TOOLS }.map { it.tool }.distinct().joinToString() +
                    " only read that number in someone else's words — it is their claim, not a check of ours. Say whose it is " +
                    "in the entry, “(per <author>, <where>)”, and it is kept as quoted; or compute it yourself (hand_odds, " +
                    "calculate, world_tool) and write that; or write the entry without the number.",
            )
            missing.isEmpty() -> Verdict.Proved(
                Proven(entry, proofs, if (anyQuoted && !Numbers.isEstimate(entry)) Proven.Status.QUOTED else if (anyQuoted) Proven.Status.ESTIMATE else Proven.Status.CHECKED, now),
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

    /** The few lines of [content] around where [claim]'s number stands, so the proof shows what it rests on. */
    private fun excerpt(content: String, claim: Numbers.Claimed): String {
        val lines = content.lines()
        val at = lines.indexOfFirst { l -> Numbers.found(claim, Numbers.values(l)) }
        val pick = if (at < 0) lines.take(3) else lines.subList((at - 1).coerceAtLeast(0), (at + 2).coerceAtMost(lines.size))
        return pick.joinToString("\n").take(400)
    }
}
