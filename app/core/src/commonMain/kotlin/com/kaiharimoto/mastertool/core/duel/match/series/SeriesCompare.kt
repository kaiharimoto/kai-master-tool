package com.kaiharimoto.mastertool.core.duel.match.series

import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.mapper.compare.Paired
import com.kaiharimoto.mastertool.core.duel.match.DoorPlayer
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.siding.SidePlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Two versions of a deck compared by simulated matches (Phase G part two, G2.1; `docs/phases/G2.md` §1): version A and
 * version B each play the same best-of-three matches against the same opponents — same seeds, same first player, same
 * plans — and the difference in match win is read pair by pair (`Paired`: a Newcombe interval, McNemar's p), then weighted
 * by the opponents' shares of the field. The seeds are fixed before the run and never added to after it (the ladder's
 * rule); every number says how much of each deck the engine could play.
 */

/**
 * An opponent of a comparison, its share of the field (any scale: the shares are read as proportions), and the plans each
 * version sides with against it, going first and going second ([plans] by version; none: that version never sides).
 */
data class SeriesOpponent(val deck: SeriesDeck, val share: Double, val plans: List<Pair<SidePlan, SidePlan>> = emptyList()) {
    /** Version [v] as it plays this opponent: its own lists, this matchup's plans. */
    fun versus(v: Int, version: SeriesDeck): SeriesDeck =
        plans.getOrNull(v)?.let { (first, second) -> version.copy(first = first, second = second) } ?: version
}

/** How much of a deck the engine plays: its Main Deck copies with a written, trusted effect or none to write, of all. */
@Serializable
data class SeriesCoverage(val deck: String, val name: String, val written: Int, val copies: Int) {
    val share: Double get() = if (copies == 0) 0.0 else written.toDouble() / copies

    companion object {
        /** A deck the engine plays less than this of is refused as yours and flagged as an opponent: mostly blanks. */
        const val FLOOR = 0.5

        fun of(id: String, name: String, deck: Deck, kit: GoldfishKit): SeriesCoverage {
            val codes = deck.main.map { kit.canonical(it.value) }
            return SeriesCoverage(id, name, codes.count { kit.known(it) }, codes.size)
        }
    }
}

/** One opponent's matches: version A's and version B's, on the same seeds. */
@Serializable
data class OpponentRun(
    val id: String,
    val name: String,
    val share: Double,
    val a: List<SeriesMatch> = emptyList(),
    val b: List<SeriesMatch> = emptyList(),
)

/**
 * A comparison by series, kept at `<data>/duel/series/<id>.json` as each match ends: the deck, the two versions and the
 * change in words, the opponents and their matches, how many seed pairs, which search played at what strength, and each
 * deck's coverage. [complete] once every match is played.
 */
@Serializable
data class SeriesRun(
    val id: String,
    val deck: String = "",
    val label: String = "",
    val nameA: String = "",
    val nameB: String = "",
    val printA: String = "",
    val printB: String = "",
    val pairs: Int = 0,
    val version: String = "",
    val strength: String = "",
    val opponents: List<OpponentRun> = emptyList(),
    val coverage: List<SeriesCoverage> = emptyList(),
    val at: Long = 0L,
    val complete: Boolean = false,
) {
    /** Matches played, of all the run sets out to play. */
    val played: Int get() = opponents.sumOf { it.a.size + it.b.size }
    val total: Int get() = opponents.size * pairs * 2 * 2
}

/** What a comparison comes to so far: each opponent's paired matches, and the field's difference with its interval. */
class SeriesReading(val opponents: List<Row>, val difference: Double, val interval: Pair<Double, Double>, val pairs: Int) {
    class Row(val id: String, val name: String, val weight: Double, val paired: Paired) {
        /** Version A's and version B's match win against this opponent. */
        val a: Double get() = paired.a
        val b: Double get() = paired.b
    }

    /** Whether B is clearly better (1), clearly worse (−1), or not told apart (0) at 95 %. */
    val verdict: Int get() = when {
        pairs == 0 -> 0
        interval.first > 0 -> 1
        interval.second < 0 -> -1
        else -> 0
    }

    companion object {
        /** [run] read: each opponent's matches paired by seed and first player; the field weighted by the shares of those with any. */
        fun of(run: SeriesRun): SeriesReading {
            val rows = run.opponents.map { o ->
                val b = o.b.associateBy { it.seed to it.opener }
                var both = 0
                var onlyA = 0
                var onlyB = 0
                var neither = 0
                o.a.forEach { ma ->
                    val mb = b[ma.seed to ma.opener] ?: return@forEach
                    val wa = ma.winner == 0
                    val wb = mb.winner == 0
                    when {
                        wa && wb -> both++
                        wa -> onlyA++
                        wb -> onlyB++
                        else -> neither++
                    }
                }
                Row(o.id, o.name, o.share, Paired(both, onlyA, onlyB, neither))
            }
            val counted = rows.filter { it.paired.hands > 0 && it.weight > 0 }
            val total = counted.sumOf { it.weight }
            if (counted.isEmpty() || total <= 0) return SeriesReading(rows, 0.0, 0.0 to 0.0, 0)
            val pairs = counted.sumOf { it.paired.hands }
            // One opponent: Newcombe's interval for paired proportions. Several: the weighted difference, its variance the
            // weighted sum of each opponent's paired variance (a normal approximation, said in the words).
            if (counted.size == 1) {
                val p = counted.single().paired
                return SeriesReading(rows, p.difference, p.interval, pairs)
            }
            var d = 0.0
            var v = 0.0
            counted.forEach { r ->
                val w = r.weight / total
                val n = r.paired.hands.toDouble()
                val b = r.paired.onlyA.toDouble()
                val c = r.paired.onlyB.toDouble()
                d += w * r.paired.difference
                v += w * w * ((b + c) / (n * n) - (c - b) * (c - b) / (n * n * n)).coerceAtLeast(0.0)
            }
            val half = 1.96 * sqrt(v)
            return SeriesReading(rows, d, (d - half).coerceAtLeast(-1.0) to (d + half).coerceAtMost(1.0), pairs)
        }
    }
}

/** Plays a comparison's matches, [workers] at a time, each kept as it ends; matches already in [run] are not played again. */
object SeriesRunner {
    /** One match to play: version (0 A, 1 B), opponent, seed and opener. */
    private class Job(val version: Int, val opponent: Int, val seed: Long, val opener: Int)

    suspend fun run(
        run: SeriesRun,
        versions: List<SeriesDeck>,
        opponents: List<SeriesOpponent>,
        kit: GoldfishKit,
        players: (seat: Int, against: Deck) -> DoorPlayer,
        isExtra: (CardId) -> Boolean,
        workers: Int,
        stop: () -> Boolean,
        kept: suspend (SeriesRun) -> Unit,
    ): SeriesRun {
        var current = run
        val lock = Mutex()
        val jobs = Channel<Job>(Channel.UNLIMITED)
        val seeds = MatchSeries.seeds(run.pairs)
        // Pair by pair, A and B together, so a run stopped part way leaves whole pairs to read.
        for ((seed, opener) in seeds) for ((oi, _) in opponents.withIndex()) for (v in 0..1) {
            val o = run.opponents[oi]
            val done = (if (v == 0) o.a else o.b).any { it.seed == seed && it.opener == opener }
            if (!done) jobs.send(Job(v, oi, seed, opener))
        }
        jobs.close()
        coroutineScope {
            repeat(workers.coerceAtLeast(1)) {
                launch {
                    for (j in jobs) {
                        if (stop()) break
                        val o = opponents[j.opponent]
                        val m = MatchSeries.play(o.versus(j.version, versions[j.version]), o.deck, j.seed, j.opener, kit, players, isExtra)
                        if (stop()) break
                        lock.withLock {
                            current = current.copy(
                                opponents = current.opponents.mapIndexed { i, o ->
                                    if (i != j.opponent) o else if (j.version == 0) o.copy(a = o.a + m) else o.copy(b = o.b + m)
                                },
                            )
                            current = current.copy(complete = current.played >= current.total)
                            kept(current)
                        }
                    }
                }
            }
        }
        return current
    }
}

/** A comparison in words (the page and Ai's `match_results`): the change, the field's difference, each opponent, coverage. */
object SeriesWords {
    fun pct(x: Double): String = "${(x * 100).roundToInt()}%"

    fun points(x: Double): String = (x * 100).roundToInt().let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }

    fun headline(r: SeriesReading): String = when {
        r.pairs == 0 -> "No pair of matches played yet."
        else -> "${points(r.difference)} points of match win (${points(r.interval.first)} to ${points(r.interval.second)}), " +
            "over ${r.pairs} ${if (r.pairs == 1) "pair" else "pairs"} of matches: " + when (r.verdict) {
                1 -> "the change is better."
                -1 -> "the change is worse."
                else -> "not told apart yet."
            }
    }

    fun of(run: SeriesRun, r: SeriesReading = SeriesReading.of(run)): String = buildString {
        appendLine("${run.label.ifBlank { "${run.nameA} against ${run.nameB}" }}: ${run.nameA} (A) against ${run.nameB} (B), best of three, each against the same opponents on the same seeds.")
        appendLine(headline(r))
        if (r.opponents.size > 1 && r.pairs > 0) appendLine("Weighted by the field's shares (a normal approximation over the opponents).")
        r.opponents.forEach { o ->
            append("- ${o.name}")
            if (r.opponents.size > 1) append(" (${pct(o.weight / r.opponents.sumOf { it.weight }.coerceAtLeast(1e-9))} of the field)")
            if (o.paired.hands == 0) appendLine(": nothing paired yet")
            else appendLine(": A wins ${pct(o.a)}, B ${pct(o.b)} of ${o.paired.hands} matches each; ${points(o.paired.difference)} (${points(o.paired.interval.first)} to ${points(o.paired.interval.second)})")
        }
        appendLine("Played: ${run.played} of ${run.total} matches, search ${run.version} at ${run.strength}${if (run.complete) "" else ", not finished"}.")
        append("What the engine plays of each deck: ")
        append(run.coverage.joinToString("; ") { c -> "${c.name} ${pct(c.share)}${if (c.share < SeriesCoverage.FLOOR) " (mostly blanks)" else ""}" })
        append(". Unwritten cards are blanks, battle effects inert: the app's measurement under those limits, never a promise.")
    }
}

object SeriesCodec {
    /** The folder under `<data>/duel/`: synced and backed up with the duel folder. */
    const val FOLDER = "series"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(r: SeriesRun): String = json.encodeToString(SeriesRun.serializer(), r)

    /** Forgiving: a file that does not read is no run, never an error. */
    fun decode(text: String): SeriesRun? = runCatching { json.decodeFromString(SeriesRun.serializer(), text) }.getOrNull()
}
