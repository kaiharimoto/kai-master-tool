package com.kaiharimoto.mastertool.core.ai.proposals

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.prep.MatchupLedger
import com.kaiharimoto.mastertool.core.prep.TestGame
import com.kaiharimoto.mastertool.core.prep.TestStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A change Ai proposes to a deck (Phase G, G.9; the red team's A1): what to change ([ops], the same as `edit_deck`'s), why,
 * the evidence it rests on, and what it should do ([expect]: a metric before and after, with its range when one was
 * computed). It changes nothing: the person's Apply makes the edit (one step of undo), Not now leaves it. Kept per deck in
 * `<data>/ai/proposals/<deck>.json` ([ProposalPaths]), so an accepted proposal can be held, later, to the games played with
 * the version it made ([Proposals.outcome]).
 */
@Serializable
data class Proposal(
    val id: String,
    val deckId: String = "",
    val title: String = "",
    val ops: List<ProposalOp> = emptyList(),
    val why: String = "",
    /** What it rests on, in words: the tools and the numbers they gave ("hand_odds: Starters>=1 going first 71.2% → 76.4%"). */
    val evidence: List<String> = emptyList(),
    val expect: List<Expect> = emptyList(),
    val at: Long = 0L,
    /** [OPEN], [APPLIED] or [DECLINED]. */
    val state: String = OPEN,
    val decidedAt: Long? = null,
    /** The deck's print before the change, and after it when applied: the versions its results are read at. */
    val fromPrint: String? = null,
    val toPrint: String? = null,
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
        const val OPEN = "open"
        const val APPLIED = "applied"
        const val DECLINED = "declined"
    }
}

/** One edit, as `edit_deck` takes it: add, remove, set or move [count] of [card] in [section]. */
@Serializable
data class ProposalOp(
    val op: String,
    val card: String,
    val count: Int? = null,
    val section: String? = null,
    val toSection: String? = null,
)

/** What a change should do: [metric] from [before] to [after] (percent points or a count, as said), [low]–[high] its range. */
@Serializable
data class Expect(
    val metric: String,
    val before: Double? = null,
    val after: Double? = null,
    val low: Double? = null,
    val high: Double? = null,
    /** "%" for percent, or the unit in words; blank for a plain number. */
    val unit: String = "%",
)

/** A deck's proposals, newest last. */
@Serializable
data class ProposalBook(val deckId: String = "", val proposals: List<Proposal> = emptyList()) {
    fun put(p: Proposal): ProposalBook = copy(proposals = proposals.filterNot { it.id == p.id } + p)

    fun byId(id: String): Proposal? = proposals.firstOrNull { it.id == id }
}

object ProposalPaths {
    const val FOLDER = "proposals"

    /** The file under `<data>/ai/`. */
    fun of(deckId: String): String = "$FOLDER/${AiMemory.safeId(deckId).ifBlank { "deck" }}.json"
}

object ProposalCodec {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; encodeDefaults = false; prettyPrint = true }

    fun encode(b: ProposalBook): String = json.encodeToString(ProposalBook.serializer(), b)

    /** A broken or missing file reads as an empty book. */
    fun decode(text: String?): ProposalBook = text?.let { runCatching { json.decodeFromString(ProposalBook.serializer(), it) }.getOrNull() } ?: ProposalBook()
}

object Proposals {
    /** The line that carries a proposal in `deck_propose`'s result, for the chat to draw the card from. */
    const val MARK = "Proposal card: "

    fun embed(p: Proposal): String = MARK + ProposalCodec.json.encodeToString(Proposal.serializer(), p).replace("\n", "")

    /** The proposal a tool result carries, or null. */
    fun read(content: String): Proposal? {
        val line = content.lineSequence().lastOrNull { it.startsWith(MARK) } ?: return null
        return runCatching { ProposalCodec.json.decodeFromString(Proposal.serializer(), line.removePrefix(MARK)) }.getOrNull()
    }

    /** "−1 Droll & Lock Bird, +1 Ash Blossom & Joyous Spring": the ops in words. */
    fun opsWords(ops: List<ProposalOp>): String = ops.joinToString(", ") { o ->
        val n = o.count ?: 1
        val where = o.section?.takeIf { it.isNotBlank() && !it.equals("main", ignoreCase = true) }?.let { " (${it.lowercase()})" }.orEmpty()
        when (o.op) {
            "add" -> "+$n ${o.card}$where"
            "remove" -> "−$n ${o.card}$where"
            "set" -> "${o.card} to $n$where"
            "move" -> "${o.card} to ${o.toSection?.lowercase() ?: "another section"}"
            else -> "${o.op} ${o.card}"
        }
    }

    /** "Opens going first: 71.2% → 76.4% (73.0–79.6)". */
    fun expectWords(e: Expect): String {
        fun v(x: Double?) = x?.let { (kotlin.math.round(it * 10) / 10.0).let { r -> if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString() } + e.unit }
        val move = listOfNotNull(v(e.before), v(e.after)).joinToString(" → ")
        val range = if (e.low != null && e.high != null) " (${v(e.low)?.removeSuffix(e.unit)}–${v(e.high)})" else ""
        return "${e.metric}: $move$range".trim()
    }

    /** The proposal's claims as one text, for the evidence ledger to judge: why and every expectation. */
    fun claims(p: Proposal): String = (listOf(p.why) + p.expect.map(::expectWords)).filter { it.isNotBlank() }.joinToString("\n")

    /**
     * An applied proposal held to the games played since (A1's "scored later"): the people's games at the version it made
     * ([Proposal.toPrint]) against those at the one it came from ([Proposal.fromPrint]), with the difference and its range.
     * Null until both have games.
     */
    data class Outcome(val before: TestStats.Rate, val after: TestStats.Rate, val difference: MatchupLedger.Difference?)

    fun outcome(p: Proposal, games: List<TestGame>): Outcome? {
        if (p.state != Proposal.APPLIED || p.fromPrint == null || p.toPrint == null) return null
        fun rate(print: String): TestStats.Rate {
            val decided = games.filter { it.deckPrint == print && it.result != TestGame.DRAW && MatchupLedger.people(it) }
            return TestStats.Rate(decided.count { it.result == TestGame.WIN }, decided.size)
        }
        val before = rate(p.fromPrint)
        val after = rate(p.toPrint)
        if (before.games == 0 && after.games == 0) return null
        return Outcome(before, after, MatchupLedger.difference(before, after))
    }

    /** "Since: 62% (41–79%) of 13 games, against 51% (…) before; +11 points (−14 to +34), within chance." */
    fun outcomeWords(o: Outcome): String = buildString {
        append("Since: ${MatchupLedger.rateWords(o.after)}, against ${MatchupLedger.rateWords(o.before)} before")
        o.difference?.let { append("; ${MatchupLedger.differenceWords(it)}") }
        append(".")
    }
}
