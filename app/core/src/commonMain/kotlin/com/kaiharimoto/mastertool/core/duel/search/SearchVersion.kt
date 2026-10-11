package com.kaiharimoto.mastertool.core.duel.search

import com.kaiharimoto.mastertool.core.duel.DuelCatalog
import com.kaiharimoto.mastertool.core.duel.DuelSight
import com.kaiharimoto.mastertool.core.duel.DuelState
import com.kaiharimoto.mastertool.core.duel.nameOf
import com.kaiharimoto.mastertool.core.duel.effects.FxMove
import com.kaiharimoto.mastertool.core.duel.effects.goldfish.GoldfishKit
import com.kaiharimoto.mastertool.core.duel.text.DuelNotation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * A version of the app's own search (Phase E, E.5b; `docs/phases/E.md` §4.6): what it values a turn's end by ([weights]), the
 * chance it gives the other seat of keeping a live answer ([hold]: worlds weighed by its passes, and with [chance] its
 * answers too), and the version it was made from and is judged against ([parent]). v0 and v1 are built in; a version tuned
 * by the ladder or proposed by Ai is a file, `<data>/duel/search/value-<n>.json`, kept with its gate's [verdict] once its
 * games are played. **A version plays at the table only once it has passed its gate** ([SearchVersions.inUse]).
 */
@Serializable
data class SearchVersion(
    val n: Int,
    val weights: ValueWeights = ValueWeights(),
    val hold: Double = Worlds.HOLD_BACK,
    val chance: Boolean = false,
    val parent: Int? = null,
    /** Where it came from: [SearchVersions.BUILT_IN], [SearchVersions.TUNED] or [SearchVersions.PROPOSED]. */
    val source: String = SearchVersions.BUILT_IN,
    /** For a tuned version, the step it is: a coordinate and its direction ("damage up"). */
    val step: String? = null,
    /** In words: Ai's why for a proposed version, the step for a tuned one. */
    val note: String = "",
    val verdict: GateRecord? = null,
    val at: Long = 0L,
) {
    val name: String get() = SearchPlayer.name(n)
}

/** What the gate decided for a version against its parent, on the bench it was played on (E.4's sign test on paired deals). */
@Serializable
data class GateRecord(
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val p: Double,
    val bar: Double,
    val pass: Boolean,
    val bench: String = "",
    val at: Long = 0L,
)

object SearchVersions {
    const val BUILT_IN = "built-in"
    const val TUNED = "tuned"
    const val PROPOSED = "proposed"

    /** Under `<data>/duel/`, beside the ladder: synced and backed up with the duel folder. */
    const val FOLDER = "search"

    fun file(n: Int): String = "value-$n.json"

    /** v0 (E.3): the weights as written, the other seat answering knowing everything. */
    val V0 = SearchVersion(0)

    /** v1 (E.5a): the other seat's answers as chance. Its gate against v0 was not passed (p 0.062): it never played in use. */
    val V1 = SearchVersion(1, chance = true, parent = 0, note = "the other seat's answers as chance")

    val builtIn: List<SearchVersion> = listOf(V0, V1)

    /** Every version: the built-in ones, then the files by number (a file never replaces a built-in number). */
    fun all(files: List<SearchVersion>): List<SearchVersion> =
        builtIn + files.filter { f -> builtIn.none { it.n == f.n } }.distinctBy { it.n }.sortedBy { it.n }

    /** The version that plays: the newest that passed its gate, else v0. */
    fun inUse(all: List<SearchVersion>): SearchVersion = all.filter { it.verdict?.pass == true }.maxByOrNull { it.n } ?: V0

    /** The number the next version takes. */
    fun next(all: List<SearchVersion>): Int = (all.maxOfOrNull { it.n } ?: 0) + 1

    /**
     * How many candidates [v]'s gate shares its 5 % with: every version judged against the line in use since the last
     * promotion before it, counting it. v1 was the first.
     */
    fun attempts(v: SearchVersion, all: List<SearchVersion>): Int {
        val lastPromotion = all.filter { it.n < v.n && it.verdict?.pass == true }.maxOfOrNull { it.n } ?: 0
        return all.count { it.n in (lastPromotion + 1)..v.n && it.parent != null }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(v: SearchVersion): String = json.encodeToString(SearchVersion.serializer(), v)

    /** Forgiving: a file that does not read is no version, never an error. */
    fun decode(text: String): SearchVersion? = runCatching { json.decodeFromString(SearchVersion.serializer(), text) }.getOrNull()
}

/**
 * Tuning on won games (E.5b, E.md §4.6): coordinate ascent from the version in use, one step at a time, each step a version
 * of its own that the ladder plays against its parent and the gate judges. A step is one weight, or the hold, moved up or
 * down; the coordinates are tried in a fixed order, so the same ladder tunes the same way on any device. A step that passes
 * becomes the version in use, and the sweep starts again from it.
 */
object ValueTuning {
    /** The coordinates, in the order they are tried: every value weight, then the hold. */
    val coordinates: List<String> = listOf(
        "interruptions", "negates", "bodies", "set", "hand", "handTraps", "damage", "taken", "theirBodies", "theirInterruptions", "hold",
    )

    const val UP = 1.5
    const val DOWN = 2.0 / 3.0
    const val HOLD_STEP = 0.1

    /** Every step from [from] in order: each coordinate up, then down. */
    fun steps(): List<String> = coordinates.flatMap { listOf("$it up", "$it down") }

    /**
     * The next version to try among [all]: the version in use with the first step not yet tried from it; null when every step
     * has been (the sweep is done until a version passes).
     */
    fun next(all: List<SearchVersion>, at: Long = 0L): SearchVersion? {
        val from = SearchVersions.inUse(all)
        val tried = all.filter { it.parent == from.n && it.source == SearchVersions.TUNED }.mapNotNull { it.step }.toSet()
        val step = steps().firstOrNull { it !in tried } ?: return null
        return apply(from, step, SearchVersions.next(all), at)
    }

    /** [from] moved one [step] ("damage up"), as version [n]. */
    fun apply(from: SearchVersion, step: String, n: Int, at: Long = 0L): SearchVersion {
        val (what, way) = step.split(' ').let { it[0] to it.getOrElse(1) { "up" } }
        val f = if (way == "up") UP else DOWN
        val w = from.weights
        fun r(x: Double) = (x * f * 1000).roundToInt() / 1000.0
        val weights = when (what) {
            "interruptions" -> w.copy(interruptions = r(w.interruptions))
            "negates" -> w.copy(negates = r(w.negates))
            "bodies" -> w.copy(bodies = r(w.bodies))
            "set" -> w.copy(set = r(w.set))
            "hand" -> w.copy(hand = r(w.hand))
            "handTraps" -> w.copy(handTraps = r(w.handTraps))
            "damage" -> w.copy(damage = r(w.damage))
            "taken" -> w.copy(taken = r(w.taken))
            "theirBodies" -> w.copy(theirBodies = r(w.theirBodies))
            "theirInterruptions" -> w.copy(theirInterruptions = r(w.theirInterruptions))
            else -> w
        }.copy(version = n)
        val hold = if (what == "hold") (from.hold + if (way == "up") HOLD_STEP else -HOLD_STEP).coerceIn(0.05, 0.95) else from.hold
        return SearchVersion(
            n, weights, ((hold * 1000).roundToInt() / 1000.0), from.chance, from.n, SearchVersions.TUNED, step,
            "v${from.n} with $step", at = at,
        )
    }
}

/**
 * Value weights proposed by Ai (`value_propose`, E.5b), checked before they are kept: only known weights, each from 0 to 20,
 * the hold from 0.05 to 0.95, and something changed from the version in use. The rest stay the version in use's.
 */
object ValueProposal {
    const val MAX_WEIGHT = 20.0

    /** The proposal as a version made from [from] (its number set when kept), or why it is refused. */
    fun of(from: SearchVersion, weights: Map<String, Double>, hold: Double?, chance: Boolean?, why: String): Pair<SearchVersion?, String?> {
        val unknown = weights.keys - ValueTuning.coordinates.toSet() - "hold"
        if (unknown.isNotEmpty()) return null to "No such weight: ${unknown.sorted().joinToString()}. The weights are ${ValueTuning.coordinates.dropLast(1).joinToString()}."
        weights.entries.firstOrNull { !it.value.isFinite() || it.value < 0 || it.value > MAX_WEIGHT }?.let { return null to "${it.key} must be from 0 to ${MAX_WEIGHT.toInt()}." }
        if (hold != null && (!hold.isFinite() || hold < 0.05 || hold > 0.95)) return null to "hold must be from 0.05 to 0.95."
        if (why.isBlank()) return null to "Say why: what you expect it to play better."
        val w = from.weights
        val next = w.copy(
            interruptions = weights["interruptions"] ?: w.interruptions,
            negates = weights["negates"] ?: w.negates,
            bodies = weights["bodies"] ?: w.bodies,
            set = weights["set"] ?: w.set,
            hand = weights["hand"] ?: w.hand,
            handTraps = weights["handTraps"] ?: w.handTraps,
            damage = weights["damage"] ?: w.damage,
            taken = weights["taken"] ?: w.taken,
            theirBodies = weights["theirBodies"] ?: w.theirBodies,
            theirInterruptions = weights["theirInterruptions"] ?: w.theirInterruptions,
        )
        val v = SearchVersion(
            n = 0, weights = next, hold = hold ?: from.hold, chance = chance ?: from.chance, parent = from.n,
            source = SearchVersions.PROPOSED, note = why.trim().take(400),
        )
        if (v.weights == w && v.hold == from.hold && v.chance == from.chance) return null to "Nothing changed from v${from.n}."
        return v to null
    }
}

/** The search's ways on in a player's words (E.5b, `duel_search`): only the cards the seat can see are named. */
object SearchWords {
    fun root(root: Root, s: DuelState, seat: Int, catalog: DuelCatalog, kit: GoldfishKit? = null, secret: Long = 0L): String {
        fun name(uid: Int): String {
            val c = s.cards[uid] ?: return "a card"
            return if (c.token || DuelSight.sees(s, uid, seat)) catalog.nameOf(c) else "a card"
        }
        fun at(uid: Int): String = DuelNotation.coordOf(s, uid, seat, secret)?.let { " ($it)" }.orEmpty()
        return when (root) {
            is Root.Move -> when (val m = root.move) {
                is FxMove.Activate -> {
                    val effect = s.cards[m.uid]?.code?.let { code -> kit?.book?.script(code)?.effects?.firstOrNull { it.id == m.effect }?.label }
                    "Activate ${name(m.uid)}${at(m.uid)}" + (effect?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                }
                is FxMove.NormalSummon -> (if (m.set) "Set " else "Normal Summon ") + name(m.uid) + at(m.uid)
                is FxMove.Procedure -> "Summon ${name(m.uid)}${at(m.uid)} by its procedure"
                is FxMove.Phase -> "On to ${m.to.name.lowercase().replace('_', ' ')}"
                FxMove.Pass -> "Pass"
                FxMove.Resolve -> "Resolve"
            }
            is Root.End -> "End the turn" + if (root.sets.isEmpty()) "" else ", Setting ${root.sets.joinToString { name(it) + at(it) }}"
            Root.Battle -> "Into the Battle Phase, the attacks planned whole"
            is Root.Attack -> "${name(root.attacker)}${at(root.attacker)} attacks " + (root.target?.let { name(it) + at(it) } ?: "directly")
            Root.LetGo -> "Let the moment go"
        }
    }

    /** A decision in words: the way chosen, then every way by least regret, with how many worlds valued each. */
    fun decision(d: Regret.Decision, s: DuelState, seat: Int, catalog: DuelCatalog, kit: GoldfishKit?, secret: Long = 0L): String = buildString {
        val worlds = d.worlds.size
        fun r(x: Double) = (x * 100).roundToInt() / 100.0
        val choice = d.choice
        appendLine(choice?.let { "It would: ${root(it.root, s, seat, catalog, kit, secret)}." } ?: "It found no way it could value within its budget.")
        appendLine("Every way, least regret first (regret: how much worse than the best, world by world; value: its mean at the turn's end):")
        d.all.sortedWith(compareBy<Regret.Weighed> { it.regret }.thenBy { d.all.indexOf(it) }).take(12).forEach { w ->
            appendLine("- ${root(w.root, s, seat, catalog, kit, secret)}: regret ${r(w.regret)}, value ${r(w.mean)}, valued in ${w.worlds} of $worlds worlds")
        }
        if (d.all.size > 12) appendLine("- and ${d.all.size - 12} more ways")
        append(if (d.complete) "Every world was searched to the turn's end." else "Not every line was searched within the budget: the values are lower bounds where it stopped.")
    }.trimEnd()
}
