package com.kaiharimoto.mastertool.core.shootout.bench

import com.kaiharimoto.mastertool.core.shootout.model.Answer
import com.kaiharimoto.mastertool.core.shootout.model.Stratum
import com.kaiharimoto.mastertool.core.shootout.store.StoredTrial
import com.kaiharimoto.mastertool.core.shootout.teach.Prediction
import kotlin.math.roundToInt

/**
 * Why a hand went the way it did, in the person's words (Phase S, stage 4, S4.1; `docs/phases/S.md` §1, §8): the reason
 * tags and the card that decided it. **Optional, and never a rating's input**: the fit reads the answers only, so a reason
 * explains a rating and never moves one.
 */
object Reasons {
    const val BRICKED = "bricked"
    const val INTERRUPTED = "interrupted"
    const val OUT_RESOURCED = "out-resourced"
    const val THEY_BRICKED = "opponent-bricked"

    /** Every tag, in the order they stand on screen. */
    val TAGS: List<String> = listOf(BRICKED, INTERRUPTED, OUT_RESOURCED, THEY_BRICKED)

    /** The tags that mean something for [alone] (the deck on its own has no opponent to interrupt or brick). */
    fun tagsFor(alone: Boolean): List<String> = if (alone) listOf(BRICKED) else TAGS

    /** A tag as the screen says it. */
    fun words(tag: String): String = when (tag) {
        BRICKED -> "Bricked"
        INTERRUPTED -> "Interrupted"
        OUT_RESOURCED -> "Out-resourced"
        THEY_BRICKED -> "They bricked"
        else -> tag
    }

    /** [tags] kept to the known ones, each once, in [TAGS]' order: what a stored trial may hold. */
    fun clean(tags: Collection<String>): List<String> = TAGS.filter { it in tags }

    /** [tags] with [tag] put in, or taken out when it was there. */
    fun toggled(tags: List<String>, tag: String): List<String> = clean(if (tag in tags) tags - tag else tags + tag)
}

/**
 * What the person marked on a hand before answering it (S4.1): the reason tags, and the card that decided it — one of the
 * hand's cards, by passcode as shown. Kept on the trial with its answer.
 */
data class TrialMarks(val tags: List<String> = emptyList(), val decisive: Int? = null) {
    val empty: Boolean get() = tags.isEmpty() && decisive == null

    companion object {
        val NONE = TrialMarks()
    }
}

/**
 * The surprise question (S4.1, S.md §6 3): an answer the model gave little chance, on a hand it was sure of, is one it did
 * not expect — and the one worth a "why?". Asked by the app, never by Ai, and at most once in [EVERY] answers so it never
 * slows a session down.
 */
object Surprise {
    /** Under this chance of the answer given, the model did not expect it. */
    const val CHANCE = 0.08

    /**
     * A hand the model knows next to nothing of surprises it with nothing: its value's standard deviation, in log-odds, at
     * most this. Within it the doubt is counted, not cut off: see [of].
     */
    const val SURE = 1.5

    /** The model has read at least this many of the person's answers before anything surprises it. */
    const val MIN_ANSWERS = 15

    /** At most one question in this many answers. */
    const val EVERY = 5

    /**
     * The chance the model gave the answer [given] (a rating's answer name, or a comparison's [StoredTrial.LEFT]/[RIGHT])
     * when it surprised the model; null when it did not, or when the model had read fewer than [MIN_ANSWERS] answers
     * ([answers]). [readings] are the model's prediction with the hand's value at its estimate and a standard deviation
     * either side ([ShootoutRun.predict]): the answer surprises only when it is under [CHANCE] in every one, so the fit's
     * own doubt about the hand is counted; the chance kept is the largest of them.
     */
    fun of(readings: List<Prediction>, given: String, answers: Int): Double? {
        if (answers < MIN_ANSWERS || readings.isEmpty() || readings.any { it.sd > SURE }) return null
        val chance = readings.maxOf { p ->
            when (given) {
                StoredTrial.LEFT -> p.win
                StoredTrial.RIGHT -> 1 - p.win
                else -> Answer.entries.indexOfFirst { it.name == given }.let { i -> p.chances.getOrNull(i) } ?: return null
            }
        }
        return chance.takeIf { it < CHANCE }
    }

    /** Whether a question may be asked now, [since] answers after the last one asked (null: none asked this session). */
    fun mayAsk(since: Int?): Boolean = since == null || since >= EVERY

    /** The question, in words: how little chance the model gave the answer. */
    fun words(chance: Double): String = "Your answer surprised the model: it gave it ${ShootoutWords.chance(chance)}. Why?"

    /** The few words a "why?" keeps: a margin, not an essay. */
    const val NOTE_MAX = 300
}

/**
 * Fatigue, read from the session's answers (S4.1, S.md §4½ "left for stage 2"): answers slowing down, rushing, or surprising
 * the model more and more. **Said as a suggestion, never used to weigh an answer** — the app cannot tell a tired answer
 * from a hard hand.
 */
object Fatigue {
    enum class Kind { SLOWED, RUSHED, DRIFTING }

    /** What was measured, and the line the screen says. */
    data class Reading(val kind: Kind, val words: String)

    /** Nothing is read before this many answers in a session. */
    const val MIN_ANSWERS = 20

    /** The first answers, settling in, are left out of the session's own pace. */
    const val WARM_UP = 2

    /** The session's pace is the median of this many answers after the warm-up; now is the median of the last this many. */
    const val SPAN = 10

    /** An answer that took longer than this is a pause (the person stepped away), not a slow answer. */
    const val PAUSE_MS = 120_000L

    const val SLOWER = 1.6
    const val FASTER = 0.5

    /** Surprises in the last [RECENT] answers: at least [SURPRISES], and at least twice the rate before them. */
    const val RECENT = 12
    const val SURPRISES = 3

    /**
     * The session's answers in the order given — [ms] how long each took (null when not kept), [surprised] whether it
     * surprised the model — read for fatigue; null when nothing stands out or there are too few.
     */
    fun read(ms: List<Long?>, surprised: List<Boolean>): Reading? {
        if (ms.size < MIN_ANSWERS) return null
        val times = ms.drop(WARM_UP).map { it?.takeIf { t -> t in 1..PAUSE_MS } }
        val base = median(times.take(SPAN).filterNotNull())
        val now = median(times.takeLast(SPAN).filterNotNull())
        if (base != null && now != null && base > 0) {
            val ratio = now / base
            if (ratio >= SLOWER) {
                return Reading(Kind.SLOWED, "Your answers have slowed by ${((ratio - 1) * 100).roundToInt()} % since the session began (${secs(base)} each, now ${secs(now)}). A short break keeps them steady.")
            }
            if (ratio <= FASTER) {
                return Reading(Kind.RUSHED, "Your answers come twice as fast as when the session began (${secs(base)} each, now ${secs(now)}). Take each hand as it comes, or stop here.")
            }
        }
        if (surprised.size >= MIN_ANSWERS) {
            val recent = surprised.takeLast(RECENT).count { it }
            val before = surprised.dropLast(RECENT)
            val rateBefore = (before.count { it } + 1.0) / (before.size + 2.0)
            if (recent >= SURPRISES && recent.toDouble() / RECENT >= 2 * rateBefore) {
                return Reading(Kind.DRIFTING, "$recent of your last $RECENT answers surprised the model, more than earlier in the session. A short break keeps your answers steady.")
            }
        }
        return null
    }

    private fun median(xs: List<Long>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun secs(ms: Double): String = ((ms / 100).roundToInt() / 10.0).toString() + " s"
}

/**
 * The person's reasons, counted (S4.1, S.md §5 "how often it is dead in hand"): what they tagged in each stratum, and for a
 * card how often the hands holding it were the ones they said bricked, beside the hands without it. Only the person's own
 * rated hands they gave a reason for are counted, so a card's share is "of the hands you explained" — every number opens
 * its trials ([Behind.Tagged], [Behind.Deciding]).
 */
class ReasonCounts private constructor(
    /** Per stratum, how many of the person's rated hands carry any reason. */
    val explained: Map<Stratum, Int>,
    /** Per stratum, each tag's count. */
    val tags: Map<Stratum, Map<String, Int>>,
    /** Per stratum, the cards named as deciding, most named first. */
    val deciding: Map<Stratum, List<Pair<Int, Int>>>,
    private val rated: Map<Stratum, List<StoredTrial>>,
    private val canon: (Int) -> Int,
) {
    /** A card's bricked hands, holding it and not (S.md §5's "dead in hand", read from the person's own tags). */
    data class Bricks(val with: Int, val withOf: Int, val without: Int, val withoutOf: Int) {
        val withShare: Double get() = with.toDouble() / withOf
        val withoutShare: Double get() = without.toDouble() / withoutOf
    }

    /** [card]'s bricked hands in [stratum], or null under [MIN_HANDS] explained hands either side. */
    fun bricks(card: Int, stratum: Stratum): Bricks? {
        val hands = rated[stratum].orEmpty()
        val (holding, not) = hands.partition { t -> ShootoutResults.opens(t, card, canon) }
        if (holding.size < MIN_HANDS || not.size < MIN_HANDS) return null
        fun bricked(ts: List<StoredTrial>) = ts.count { Reasons.BRICKED in it.tags }
        return Bricks(bricked(holding), holding.size, bricked(not), not.size)
    }

    /** How often [card] was named as deciding in [stratum]. */
    fun named(card: Int, stratum: Stratum): Int = deciding[stratum].orEmpty().firstOrNull { it.first == card }?.second ?: 0

    /** Whether any reason was given at all. */
    val any: Boolean get() = explained.values.any { it > 0 }

    /** The reasons in words, for Ai (`shootout_state`): per stratum, what was tagged and the cards named. Empty with none. */
    fun words(name: (Int) -> String): String = buildString {
        explained.filterValues { it > 0 }.toList().sortedBy { it.first.ordinal }.forEach { (stratum, n) ->
            append("- ${ShootoutWords.stratum(stratum)}: $n hands explained")
            tags[stratum]?.takeIf { it.isNotEmpty() }?.let { t -> append(" — " + t.entries.joinToString(", ") { (tag, k) -> "${Reasons.words(tag).lowercase()} $k" }) }
            deciding[stratum]?.take(3)?.takeIf { it.isNotEmpty() }?.let { d -> append("; decided by " + d.joinToString(", ") { (c, k) -> "${name(c)} ×$k" }) }
            appendLine()
        }
    }.trim()

    companion object {
        /** A card's bricked share is shown only with at least this many explained hands holding it, and without it. */
        const val MIN_HANDS = 5

        /** The person's reasons in [trials], cards read through [canon] (`CardIdentity`: one card, whatever the art). */
        fun of(trials: List<StoredTrial>, canon: (Int) -> Int = { it }): ReasonCounts {
            val mine = trials.filter { it.judge == StoredTrial.PERSON }
            val byStratum = mine.groupBy { t -> Stratum.entries.firstOrNull { it.name == t.stratum } }
                .filterKeys { it != null }.mapKeys { it.key!! }
            val explained = byStratum.mapValues { (_, ts) -> ts.count { it.tags.isNotEmpty() || it.decisive != null } }
            val tags = byStratum.mapValues { (_, ts) -> Reasons.TAGS.associateWith { tag -> ts.count { tag in it.tags } }.filterValues { it > 0 } }
            val deciding = byStratum.mapValues { (_, ts) ->
                ts.mapNotNull { it.decisive?.let(canon) }.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
            }
            val rated = byStratum.mapValues { (_, ts) -> ts.filter { it.kind == StoredTrial.RATE && it.tags.isNotEmpty() } }
            return ReasonCounts(explained, tags, deciding, rated, canon)
        }
    }
}
