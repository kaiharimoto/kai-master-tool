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

    /** The tools a stale number can be computed again with, by the app alone. */
    val RERUNNABLE = setOf("hand_odds", "world_tool")

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
        val proofs = mutableListOf<Proof>()
        val missing = mutableListOf<Numbers.Claimed>()
        claims.forEach { c ->
            val s = sources.firstOrNull { Numbers.found(c, Numbers.values(it.content)) }
            if (s == null) {
                missing += c
            } else if (proofs.none { it.tool == s.tool && it.input == s.input }) {
                proofs += Proof(s.tool, s.input, excerpt(s.content, c), now, if (s.tool in DECK_TOOLS) deck else "")
            }
        }
        return when {
            missing.isEmpty() -> Verdict.Proved(Proven(entry, proofs, Proven.Status.CHECKED, now))
            Numbers.isEstimate(entry) -> Verdict.Proved(Proven(entry, proofs, Proven.Status.ESTIMATE, now))
            else -> Verdict.Refused(
                "Not written: the guide keeps only numbers a check computed, and nothing in this conversation computed " +
                    missing.joinToString { "“${it.written}”" } + ". Compute it first (hand_odds, calculate, or an instrument with " +
                    "world_tool) and write it then; or write the entry without the number; or, if it is your judgment, say so " +
                    "in the entry with “(estimate)”.",
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
