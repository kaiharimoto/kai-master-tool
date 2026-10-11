package com.kaiharimoto.mastertool.core.duel.coach

import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.DuelAction
import com.kaiharimoto.mastertool.core.duel.ai.DuelBrief
import com.kaiharimoto.mastertool.core.duel.DuelEntry
import com.kaiharimoto.mastertool.core.duel.DuelGame
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.Provenance
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.FxTag
import com.kaiharimoto.mastertool.core.duel.effects.FxRules
import com.kaiharimoto.mastertool.core.duel.effects.FxSummons
import com.kaiharimoto.mastertool.core.duel.effects.FxTable
import com.kaiharimoto.mastertool.core.duel.effects.FxFold
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.search.Prior
import com.kaiharimoto.mastertool.core.duel.search.Regret
import com.kaiharimoto.mastertool.core.duel.search.Root
import com.kaiharimoto.mastertool.core.duel.search.SearchPlayer
import com.kaiharimoto.mastertool.core.duel.search.SearchVersion
import com.kaiharimoto.mastertool.core.duel.search.Seen
import com.kaiharimoto.mastertool.core.duel.search.TurnValue
import com.kaiharimoto.mastertool.core.world.WorldStats
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.roundToInt

/*
 * The misplay finder (Phase F, F.1; `docs/phases/F.md`): every decision a seat made in a replay, weighed by the app's own
 * search from that seat's view, and the move played marked by how much win chance it gave up against the best way the
 * search found — Lichess's thresholds, the better line beside it. A review is the search's opinion with its numbers, never a
 * verdict on the player; what it cannot map or search is "not judged" and never counted.
 */

/** One decision in a replay: a seat's own gesture, entries [at] until [end] of the log, by [by]. */
data class Decision(val seat: Int, val at: Int, val end: Int, val by: Provenance?, val actions: List<DuelAction>, val tags: List<FxTag?>)

object Decisions {
    /**
     * Every decision in [game]'s log (its played entries): a group of entries made by a seat — never the deal, the table's own
     * moves (an automatic draw), talk, or a peek — one decision a group.
     */
    fun of(game: DuelGame): List<Decision> {
        val entries = game.played
        val out = ArrayList<Decision>()
        var i = game.floor
        while (i < entries.size) {
            val first = entries[i]
            var j = i + 1
            while (j < entries.size && entries[j].group == first.group && entries[j].seat == first.seat) j++
            val group = entries.subList(i, j)
            if (decides(first, group)) {
                out += Decision(first.seat!!, i, j, first.by, group.map { it.action }, group.map { it.fx })
            }
            i = j
        }
        return out
    }

    private fun decides(first: DuelEntry, group: List<DuelEntry>): Boolean {
        if (first.seat == null) return false
        if (first.by?.by == Provenance.TABLE || first.by?.peek == true) return false
        return group.any { !DuelGame.isTalk(it.action) && !it.action.social }
    }
}

/**
 * What a decision was among the search's ways (F.1): every way it could have been, by its tags and its actions — an
 * activation or a procedure by its card, a Normal Summon or Set, a phase, an attack, the turn's end, a pass. More than one is
 * read charitably (the best of them); none is "not judged".
 */
object DecisionRoots {
    fun matches(d: Decision, root: Root, before: DuelState): Boolean {
        val tag = d.tags.firstOrNull { it != null && (it.part == FxTag.ACTIVATE || it.part == FxTag.COST || it.part == FxTag.PROC) }
        return when (root) {
            is Root.Move -> when (val m = root.move) {
                is FxMove.Activate -> tag != null && tag.uid == m.uid && tag.effect == m.effect
                is FxMove.Procedure -> tag != null && tag.part == FxTag.PROC && tag.uid == m.uid
                is FxMove.NormalSummon -> d.actions.any { a ->
                    a is DuelAction.Move && a.uid == m.uid && (a.how == FxSummons.HOW_NORMAL || a.how == FxSummons.HOW_SET) &&
                        (a.pos == CardPosition.FACE_DOWN_DEF) == m.set
                }
                is FxMove.Phase -> d.actions.any { it is DuelAction.Phase && it.phase == m.to } ||
                    (m.to == DuelPhase.END && d.actions.any { it == DuelAction.EndTurn })
                FxMove.Pass, FxMove.Resolve -> tag == null && (d.actions.all { it is DuelAction.Answer && !it.respond } ||
                    d.tags.any { it?.part == FxTag.RESOLVE })
            }
            is Root.End -> root.sets.isEmpty() && d.actions.any { it == DuelAction.EndTurn || (it is DuelAction.Phase && it.phase == DuelPhase.END) }
            Root.Battle -> before.phase == DuelPhase.MAIN1 && d.actions.any { it is DuelAction.Phase && it.phase == DuelPhase.BATTLE }
            is Root.Attack -> d.actions.any { it is DuelAction.Attack && it.attacker == root.attacker && it.target == root.target }
            Root.LetGo -> d.actions.all { it is DuelAction.Answer && !it.respond }
        }
    }
}

/**
 * A turn's value as a win chance (F.1): a logistic of the turn player's value, fitted to the results of the ladder's games
 * ([FIT]). A won game is certain, a lost one hopeless.
 *
 * Measured (`WinChanceFitTest`, `NEUE_FIT=1`, 2026-10): search v0 at Fast against itself on the ladder's 24 deals from both
 * seats, 48 games, 2,757 decisions, each its chosen value for the turn player against whether that player won. Newton's
 * method gives A −0.605 and B 0.141: log-loss 0.645, Brier 0.228 against a coin's 0.250 — a weak reading, which is why every
 * review says where its numbers come from. Near the middle a point of value is about three points of win chance.
 */
object WinChance {
    /** The fit: chance = 1 ÷ (1 + e^−(A + B·value)). */
    const val A = -0.605
    const val B = 0.141

    /** Where the fit came from, said beside every review. */
    const val FIT = "fitted to 48 games of search v0 against itself on the bench decks (Brier 0.228, a coin's 0.250)"

    /** The turn player's chance of winning at [value]. */
    fun of(value: Double): Double = when {
        value >= TurnValue.WIN / 2 -> 1.0
        value <= TurnValue.LOSS / 2 -> 0.0
        else -> 1.0 / (1.0 + exp(-(A + B * value)))
    }
}

/** Lichess's marks (F.md §1): a drop in win chance of 5, 10 and 15 points. */
enum class Mark(val sign: String, val words: String, val points: Double) {
    INACCURACY("?!", "inaccuracy", 5.0),
    MISTAKE("?", "mistake", 10.0),
    BLUNDER("??", "blunder", 15.0),
    ;

    companion object {
        /** The mark for a drop of [points] of win chance, or null below an inaccuracy. */
        fun of(points: Double): Mark? = entries.lastOrNull { points >= it.points - 1e-9 }
    }
}

/**
 * One decision judged: what was played and the best way, in the search's words; the drop in win chance (0–100) and its
 * [mark]; the worlds and whether all were searched whole. [why] when it was not judged.
 */
@Serializable
data class Judged(
    val at: Int,
    val seat: Int,
    val by: String? = null,
    val played: String = "",
    val best: String = "",
    val line: List<String> = emptyList(),
    /** The drop in win chance, in points (0–100), and the regret in the search's value. */
    val drop: Double = 0.0,
    val regret: Double = 0.0,
    val mark: String? = null,
    val worlds: Int = 0,
    val complete: Boolean = true,
    val why: String? = null,
) {
    val judged: Boolean get() = why == null
}

/** A replay's review (F.1), kept at `<data>/duel/coach/<replay>.json`: by which search, at what strength, every decision. */
@Serializable
data class ReviewBook(
    val replay: String,
    val duel: String = "",
    val version: String = "",
    val strength: String = "",
    val at: Long = 0L,
    /** When the duel was played (its header's time), for the monthly rate. */
    val played: Long = 0L,
    val decisions: List<Judged> = emptyList(),
    val complete: Boolean = true,
    /** The replay's length when it was reviewed: a replay edited since (a step cut, a note put in) has moved its steps. 0: unknown. */
    val steps: Int = 0,
) {
    /** Whether this review still lines up with a replay of [entries] steps. */
    fun fits(entries: Int): Boolean = steps == 0 || steps == entries
}

object Review {
    /** The folder under `<data>/duel/`, beside the replays: synced and backed up with them. */
    const val FOLDER = "coach"

    /**
     * [d] judged: the search ([version] at [strength]) weighs every way on from [d]'s seat's view of the table before it,
     * the other seat's hidden cards from [prior]; the move played is the way it was, read charitably.
     */
    fun judge(
        game: DuelGame,
        d: Decision,
        kit: GoldfishKit,
        prior: Prior,
        version: SearchVersion,
        strength: SearchPlayer.Strength,
        words: (Root, DuelState, Int) -> String,
        lineWords: (Regret.Decision) -> List<String> = { emptyList() },
        cancelled: () -> Boolean = { false },
    ): Judged {
        val floor = minOf(game.floor, d.at)
        val before = DuelGame(game.header, game.entries, d.at, game.stateAt(d.at), floor)
        val s = before.state
        if (s.solo) return Judged(d.at, d.seat, d.by?.by, why = "a one-player table")
        if (s.beforeTurnOne) return Judged(d.at, d.seat, d.by?.by, why = "before the first turn")
        val t = FxTable(s, FxFold.fold(before.header, before.played, kit.book, kit.facts, s), kit.book, kit.facts, before.header.seed).current()
        val decider = d.seat
        val turnPlayer = s.active
        // The search weighs a seat's own turn and its answers at the other seat's moments; nothing else is its to judge.
        if (decider != turnPlayer && s.chain.isEmpty() && !FxRules.open(t)) return Judged(d.at, decider, d.by?.by, why = "not a moment the search weighs")
        val r = Regret.decide(
            Seen.of(before, decider, kit, DuelBrief.SELF), prior, kit, strength.worlds, strength.moves, TurnValue(version.weights), cancelled,
            chance = version.chance, hold = version.hold,
        )
        val choice = r.choice ?: return Judged(d.at, decider, d.by?.by, why = "the search could value no way here")
        val ways = r.all.filter { DecisionRoots.matches(d, it.root, s) }
        val played = ways.minByOrNull { it.regret } ?: return Judged(d.at, decider, d.by?.by, why = "the move played is not one of the ways the search weighs")
        val mine = decider == turnPlayer
        // The decider's win chance, world by world: one won world among middling ones is a share of a win, never all of one.
        fun chance(w: Regret.Weighed) = w.expected(WinChance::of).let { if (mine) it else 1 - it }
        val drop = ((chance(choice) - chance(played)) * 100).coerceAtLeast(0.0)
        val rounded = (drop * 10).roundToInt() / 10.0
        return Judged(
            d.at, decider, d.by?.by,
            played = words(played.root, s, decider), best = words(choice.root, s, decider),
            line = if (played.root == choice.root) emptyList() else lineWords(r),
            drop = rounded, regret = (played.regret * 100).roundToInt() / 100.0,
            mark = Mark.of(rounded)?.name, worlds = played.worlds, complete = r.complete,
        )
    }
}

/** Misplays by month (F.1, the chart): mistakes and blunders per 100 of [who]'s decisions judged, with the count behind each. */
object MisplayRate {
    data class Month(val month: String, val decisions: Int, val misplays: Int, val inaccuracies: Int) {
        val rate: Double get() = if (decisions == 0) 0.0 else misplays * 100.0 / decisions

        /** "Mar 26" for "2026-03". */
        val label: String
            get() = month.split('-').let { p ->
                val m = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..12 } ?: return month
                "${MONTHS[m - 1]} ${p[0].takeLast(2)}"
            }

        /** The rate's 95 % range (Wilson), per 100 decisions. */
        val range: Pair<Double, Double> get() = WorldStats.wilson(misplays, decisions).let { (lo, hi) -> lo * 100 to hi * 100 }
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun byMonth(books: List<ReviewBook>, who: String = Provenance.PERSON, monthOf: (Long) -> String): List<Month> =
        books.groupBy { monthOf(it.played.takeIf { p -> p > 0 } ?: it.at) }.map { (m, bs) ->
            val mine = bs.flatMap { it.decisions }.filter { it.judged && it.by == who }
            Month(
                m, mine.size,
                mine.count { it.mark == Mark.MISTAKE.name || it.mark == Mark.BLUNDER.name },
                mine.count { it.mark == Mark.INACCURACY.name },
            )
        }.sortedBy { it.month }
}

/** A review's marks counted (the replay library's line): the person's and every seat's, blunders, mistakes, inaccuracies. */
data class MarkCount(val blunders: Int, val mistakes: Int, val inaccuracies: Int, val judged: Int, val notJudged: Int) {
    val words: String
        get() = if (judged == 0) "nothing judged" else listOfNotNull(
            "$blunders ??".takeIf { blunders > 0 }, "$mistakes ?".takeIf { mistakes > 0 }, "$inaccuracies ?!".takeIf { inaccuracies > 0 },
        ).ifEmpty { listOf("no misplays") }.joinToString(" · ") + " in $judged decisions"

    companion object {
        fun of(b: ReviewBook, who: String? = null): MarkCount {
            val ds = b.decisions.filter { who == null || it.by == who }
            val j = ds.filter { it.judged }
            return MarkCount(
                j.count { it.mark == Mark.BLUNDER.name }, j.count { it.mark == Mark.MISTAKE.name }, j.count { it.mark == Mark.INACCURACY.name },
                j.size, ds.size - j.size,
            )
        }
    }
}

/** Reviews in words, for Ai (`duel_review`): the list with the monthly rate, or one replay's decisions. */
object ReviewWords {
    fun list(books: List<ReviewBook>, months: List<MisplayRate.Month>, names: (String) -> String): String = buildString {
        if (books.isEmpty()) {
            append("No replay has been reviewed yet. The person reviews one from the replay bar (Review).")
            return@buildString
        }
        appendLine("Reviews kept (${books.size}), newest first:")
        books.sortedByDescending { it.at }.forEach { b ->
            val c = MarkCount.of(b)
            val you = MarkCount.of(b, Provenance.PERSON)
            append("- ").append(b.replay).append(" \"").append(names(b.replay)).append("\": ").append(c.words)
            if (you.judged > 0 && you.judged != c.judged) append("; the person's own: ").append(you.words)
            if (c.notJudged > 0) append("; ${c.notJudged} not judged")
            append(" (search ${b.version} at ${b.strength}")
            if (!b.complete) append(", stopped part way")
            appendLine(")")
        }
        val shown = months.filter { it.decisions > 0 }
        if (shown.isNotEmpty()) {
            appendLine()
            appendLine("The person's misplays by month (mistakes and blunders per 100 of their decisions judged, 95 % range):")
            shown.forEach { m ->
                appendLine("- ${m.month}: ${pct(m.rate)} (${pct(m.range.first)}–${pct(m.range.second)}), ${m.misplays} of ${m.decisions}; ${m.inaccuracies} inaccuracies")
            }
        }
        append("Win chance is ").append(WinChance.FIT).append('.')
    }

    fun replay(b: ReviewBook, name: String, all: Boolean): String = buildString {
        appendLine("Review of \"$name\" by search ${b.version} at ${b.strength}" + if (b.complete) ":" else " (stopped part way):")
        appendLine(MarkCount.of(b).words + ".")
        val marked = b.decisions.filter { it.mark != null }.sortedByDescending { it.drop }
        val rest = if (all) b.decisions.filter { it.mark == null } else emptyList()
        if (marked.isEmpty() && !all) appendLine("No decision was marked.")
        (marked + rest).forEach { j -> appendLine(line(j)) }
        val notJudged = b.decisions.count { !it.judged }
        if (!all && notJudged > 0) appendLine("$notJudged decisions were not judged (all: true lists them with why).")
        append("Win chance is ").append(WinChance.FIT).append('.')
    }

    private fun line(j: Judged): String {
        val who = "seat ${j.seat + 1}" + (j.by?.let { " ($it)" } ?: "")
        if (!j.judged) return "- step ${j.at}, $who: not judged — ${j.why}"
        val mark = j.mark?.let { runCatching { Mark.valueOf(it) }.getOrNull() }
        return buildString {
            append("- step ${j.at}, $who: ")
            append(mark?.let { "${it.sign} ${it.words}, " } ?: "")
            append("${pct(j.drop)} points of win chance given up; played ${j.played}")
            if (j.best != j.played) append("; better ${j.best}")
            if (j.line.isNotEmpty()) append(" (line: ").append(j.line.joinToString("; ")).append(')')
            append("; ${j.worlds} worlds")
            if (!j.complete) append(", not every line searched")
        }
    }

    private fun pct(x: Double): String = ((x * 10).roundToInt() / 10.0).let { if (it == it.toInt().toDouble()) "${it.toInt()}" else "$it" }
}

object ReviewCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(b: ReviewBook): String = json.encodeToString(ReviewBook.serializer(), b)

    /** Forgiving: a file that does not read is no review, never an error. */
    fun decode(text: String): ReviewBook? = runCatching { json.decodeFromString(ReviewBook.serializer(), text) }.getOrNull()
}
