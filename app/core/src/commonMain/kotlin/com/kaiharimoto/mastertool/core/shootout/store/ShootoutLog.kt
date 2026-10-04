package com.kaiharimoto.mastertool.core.shootout.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One answered trial as it is kept on disk (Phase S §5): the hands as canonical passcodes, one entry per copy, so a
 * trial outlives every change to the deck — a card cut later is still the card that was in the hand.
 *
 * The log is append-only: a trial is written once and never changed. Everything a later stage reads is here from
 * the first version, so nothing older has to be rewritten: who answered ([judge]) and whether they had seen Ai's
 * answer first ([sawAi]), Ai's own answer kept apart ([ai]), the plans a sided trial was dealt under ([plans]), how
 * long the answer took ([ms], for fatigue), the card that decided it and the reason tags (S.md §1, optional).
 */
@Serializable
data class StoredTrial(
    /** Unique within its file: the session and the trial's place in it. */
    val id: String,
    /** When it was answered, epoch milliseconds. */
    val at: Long = 0,
    /** The stratum's name ([com.kaiharimoto.mastertool.core.shootout.model.Stratum]); a name no build knows drops the trial. */
    val stratum: String,
    /** [RATE] or [COMPARE]. */
    val kind: String = RATE,
    /** A rating's hand. */
    val hand: List<Int> = emptyList(),
    /** A comparison's two hands. */
    val left: List<Int> = emptyList(),
    val right: List<Int> = emptyList(),
    /** The opponent's hand, in a matchup. */
    val opponent: List<Int>? = null,
    /** A rating's answer: the name of a five-point answer, from `CLEAR_LOSS` to `CLEAR_WIN`. */
    val answer: String? = null,
    /** A comparison's answer: [LEFT] or [RIGHT]. */
    val prefer: String? = null,
    /** Who answered: [PERSON] or [AI]. */
    val judge: String = PERSON,
    /** Whether the person had seen Ai's answer before giving theirs (S.md §6½: such answers are their own judge). */
    val sawAi: Boolean = false,
    /** Ai's answer to the same trial, kept apart and never mixed into the person's (S.md §6). */
    val ai: AiVerdict? = null,
    /** Why the picker showed it: `chosen`, `plain` (a shuffled hand, the honesty check) or `repeat`. */
    val reason: String = "chosen",
    /** Both siding plans' fingerprints, for a sided trial ([PlanPrint]). */
    val plans: PlanPrints? = null,
    /** How long the answer took, in milliseconds. */
    val ms: Long? = null,
    /** The session it was answered in. */
    val session: String? = null,
    /** The card the person said decided it (optional, S.md §1). */
    val decisive: Int? = null,
    /** Reason tags (optional, S.md §1): `bricked`, `interrupted`, `out-resourced`, `opponent-bricked`. */
    val tags: List<String> = emptyList(),
    val note: String? = null,
    /**
     * The person's trial an answer of Ai's is to (stage 3, S.md §6½): Ai's answers are kept as trials of their own
     * ([judge] [AI]), each pointing at the person's answer to the same hand, so the pair is how agreement is measured and
     * a trial once written is never changed. Null for Ai's solo hands and for every answer of the person's.
     */
    val of: String? = null,
    /** How the answer was given (stage 3): one of [TeachModes] — `calibration`, `apprentice`, `supervised`, `solo`, `audit`, `exam`. */
    val mode: String? = null,
    /** Your turn's draw, marked on screen, when you went second (1.1.5, `TrialDraws`); one of [hand]. */
    val turnDraw: Int? = null,
    /** Their turn's draw, when they went second; one of [opponent]. */
    val theirTurnDraw: Int? = null,
    /** Cards turned up for your draws by card effects (1.1.5): a look ahead, never part of the rated hand. */
    val drew: List<Int> = emptyList(),
    /** Cards turned up for their draws by card effects. */
    val theyDrew: List<Int> = emptyList(),
) {
    /** Every hand of yours it shows. */
    val hands: List<List<Int>> get() = if (kind == COMPARE) listOf(left, right) else listOf(hand)

    /** Whether any hand of yours it shows holds [card]. */
    fun holds(card: Int): Boolean = hands.any { card in it }

    /** Whether one hand it shows holds both [a] and [b]. */
    fun holdsBoth(a: Int, b: Int): Boolean = hands.any { a in it && b in it }

    /** The person's own answer, given blind: the reference judge (S.md §4½). */
    val blind: Boolean get() = judge == PERSON && !sawAi

    companion object {
        const val RATE = "rate"
        const val COMPARE = "compare"
        const val LEFT = "left"
        const val RIGHT = "right"
        const val PERSON = "person"
        const val AI = "ai"
    }
}

/**
 * Ai's answer to a trial (S.md §6, §6½): its own judge, stored apart, weighted by its measured agreement.
 *
 * Stage 3 adds what makes its agreement honest to measure ([examples], [rubric], [predicted], [asked]): what Ai was
 * shown when it answered, so a score is only ever counted on a hand it never learned from ("using only the examples
 * and rubric it had before"), and [print], the decks it answered on, so a deck change re-earns every kind.
 */
@Serializable
data class AiVerdict(
    val answer: String? = null,
    val prefer: String? = null,
    /** How sure it said it was, 0 to 1. */
    val sure: Double? = null,
    val why: String? = null,
    val model: String? = null,
    /** The person's judged trials it was shown as examples, by id: the example bank as it stood when it answered. */
    val examples: List<String> = emptyList(),
    /** The rubric it was shown, as [com.kaiharimoto.mastertool.core.shootout.teach.Rubric.hash]; null when there was none. */
    val rubric: String? = null,
    /** The model's win chance for the hand it was shown, 0 to 1 (stage 1's fit, one input among the others). */
    val predicted: Double? = null,
    /** The kind of hand ([com.kaiharimoto.mastertool.core.shootout.teach.HandKind.key]). */
    val kind: String? = null,
    /** The decks it answered on ([com.kaiharimoto.mastertool.core.shootout.bench.Bench.print]). */
    val print: String? = null,
    /** When it was asked, epoch milliseconds. */
    val asked: Long? = null,
    /** One short question it would ask the person, written before it knew their answer (apprentice mode). */
    val question: String? = null,
)

/**
 * The person's note on a trial (S.md §6½): written after the trial (an answer to Ai's question, or their own), so it is
 * kept beside the trials, never in one. It goes with its trial into the example bank; recurring ones are offered for the
 * rubric.
 */
@Serializable
data class TrialNote(
    val trial: String,
    val text: String,
    val at: Long = 0,
    /** Ai's question it answers, when it answers one. */
    val question: String? = null,
)

/**
 * How far the person trusts Ai on this matchup (S.md §6½ "The gate"): Ai runs a kind of hand alone only when the bottom
 * of its agreement range clears [bar] and it says it is at least [sure] sure. [solo] is whether the person has let it.
 */
@Serializable
data class TrustSettings(
    val bar: Double = 0.9,
    val sure: Double = 0.8,
    val solo: Boolean = false,
)

/** The two siding plans a sided trial was dealt under, as fingerprints ([PlanPrint]). */
@Serializable
data class PlanPrints(val mine: String = "", val theirs: String = "")

/**
 * One file of trials (S.md §5): `<data>/shootout/<deck>/alone.json` for the deck on its own, or
 * `<data>/shootout/<deck>/<opponent deck>.json` for a matchup, its four strata inside. The fit is read from the
 * trials every time, so nothing derived is stored.
 */
@Serializable
data class ShootoutLog(
    val version: Int = VERSION,
    /** The deck's id. */
    val deck: String,
    /** The opponent deck's id, or null for the deck alone. */
    val opponent: String? = null,
    /** The opponent's name when last seen, so a log reads without its deck. */
    val opponentName: String? = null,
    val trials: List<StoredTrial> = emptyList(),
    /** The person's notes on trials (stage 3), in the order written. */
    val notes: List<TrialNote> = emptyList(),
    /** The gate's settings for this matchup (stage 3); null is the defaults. */
    val trust: TrustSettings? = null,
) {
    fun plus(trial: StoredTrial): ShootoutLog = copy(trials = trials + trial)

    /** The person's notes on [trial], oldest first. */
    fun notesOn(trial: String): List<TrialNote> = notes.filter { it.trial == trial }

    /** The trust settings, the defaults when none were chosen. */
    val trusted: TrustSettings get() = trust ?: TrustSettings()

    companion object {
        /** The format's version: a newer one is read for what it shares with this one. */
        const val VERSION = 1
    }
}

/**
 * Reads and writes [ShootoutLog]s, forgivingly (the rule for stored data): keys a newer build added are skipped, and a
 * trial that will not read is dropped alone rather than taking the file with it.
 */
object ShootoutCodec {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val pretty = Json(json) { prettyPrint = false }

    fun encode(log: ShootoutLog): String = pretty.encodeToString(ShootoutLog.serializer(), log)

    /** The log in [text], or null when it is not one at all. */
    fun decode(text: String?): ShootoutLog? {
        if (text.isNullOrBlank()) return null
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val deck = (root["deck"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val trials = (root["trials"] as? JsonArray).orEmpty().mapNotNull { t ->
            try {
                json.decodeFromJsonElement(StoredTrial.serializer(), t)
            } catch (e: Exception) {
                null
            }
        }
        val notes = (root["notes"] as? JsonArray).orEmpty().mapNotNull { n ->
            try {
                json.decodeFromJsonElement(TrialNote.serializer(), n)
            } catch (e: Exception) {
                null
            }
        }
        val trust = root["trust"]?.let { t ->
            try {
                json.decodeFromJsonElement(TrustSettings.serializer(), t)
            } catch (e: Exception) {
                null
            }
        }
        return ShootoutLog(
            version = (root["version"] as? JsonPrimitive)?.intOrNull ?: ShootoutLog.VERSION,
            deck = deck,
            opponent = (root["opponent"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            opponentName = (root["opponentName"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            trials = trials,
            notes = notes,
            trust = trust,
        )
    }
}

/** Where a deck's trials live, under the data folder (S.md §5). */
object ShootoutPaths {
    const val FOLDER = "shootout"
    const val ALONE = "alone.json"

    /** The deck's folder, relative to the data folder. */
    fun folder(deckId: String): String = "$FOLDER/${safe(deckId)}"

    /** The file for the deck alone ([opponentId] null) or against one opponent deck. */
    fun file(deckId: String, opponentId: String?): String =
        "${folder(deckId)}/${if (opponentId == null) ALONE else safe(opponentId) + ".json"}"

    /**
     * How the person judges this matchup, in words Ai keeps (stage 3, S.md §6½): `<deck>/<matchup>.rubric.md` beside the
     * trials — `alone.rubric.md` for the deck on its own. Markdown entries, reviewed like the guide.
     */
    fun rubric(deckId: String, opponentId: String?): String =
        "${folder(deckId)}/${if (opponentId == null) "alone" else safe(opponentId)}$RUBRIC"

    const val RUBRIC = ".rubric.md"

    /**
     * An id as a file name: letters, digits, `-` and `_` kept, anything else as `~` and its code, so two ids never
     * share a file and nothing can leave the folder. `alone` itself is escaped, so no deck can take the deck-alone file.
     */
    fun safe(id: String): String {
        if (id == "alone") return "~alone"
        val out = StringBuilder()
        id.forEach { ch ->
            if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_') out.append(ch)
            else out.append('~').append(ch.code.toString(16))
        }
        return out.toString().ifEmpty { "~" }
    }
}
