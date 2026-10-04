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

/** Ai's answer to a trial (S.md §6, §6½): its own judge, stored apart, weighted by its measured agreement later. */
@Serializable
data class AiVerdict(
    val answer: String? = null,
    val prefer: String? = null,
    /** How sure it said it was, 0 to 1. */
    val sure: Double? = null,
    val why: String? = null,
    val model: String? = null,
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
) {
    fun plus(trial: StoredTrial): ShootoutLog = copy(trials = trials + trial)

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
        return ShootoutLog(
            version = (root["version"] as? JsonPrimitive)?.intOrNull ?: ShootoutLog.VERSION,
            deck = deck,
            opponent = (root["opponent"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            opponentName = (root["opponentName"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            trials = trials,
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
