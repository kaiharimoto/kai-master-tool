package com.kaiharimoto.mastertool.core.shootout.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ai's reading of a deck before any hand is judged (Phase S, S4.3; `docs/phases/S.md` §6 1): which of the deck's groups likely
 * help a hand and which likely hurt ([leans]), which pairs of cards likely work together or get in each other's way ([pairs]),
 * and — for a deck with no groups — a role for each card ([roles]). **It only sets where the ratings start**: the person's
 * answers move them from there, and a wrong lean washes out (`ShootoutPriorsTest`). Shown, and every line can be turned off
 * or changed; kept per target beside the trials, `<deck>/<target>.priors.json`.
 */
@Serializable
data class ShootoutPriors(
    val version: Int = VERSION,
    /** Shootout's own roles for a deck with no groups: each card and its role. Empty: the deck's groups are its roles. */
    val roles: List<RolePick> = emptyList(),
    /** Each role's lean, by its name: how much a card of it likely helps a hand, in steps from −2 to +2. */
    val leans: List<RoleLean> = emptyList(),
    /** The pairs fitted first: cards that likely work together, or in each other's way. */
    val pairs: List<PairPick> = emptyList(),
    /** Who wrote it: [BY_AI], or [BY_PERSON] once they changed a line. */
    val by: String = BY_AI,
    /** The model that proposed it, when Ai did. */
    val model: String? = null,
    val at: Long = 0,
    /** The deck's print when it was proposed (`Ledger.fingerprint`): a changed deck is said, never re-read silently. */
    val deckPrint: String? = null,
    /** Ai's one line on the deck, as it read it. */
    val summary: String = "",
) {
    val isEmpty: Boolean get() = roles.isEmpty() && leans.isEmpty() && pairs.isEmpty()

    /** The leans and pairs the person left on. */
    val inUse: Int get() = leans.count { it.on && it.step != 0 } + pairs.count { it.on }

    companion object {
        const val VERSION = 1
        const val BY_AI = "ai"
        const val BY_PERSON = "person"

        /** One step of lean, in log-odds of a win: ±2 steps is ±0.8, about the spread of real roles' averages (S.md §4½). */
        const val STEP = 0.4

        /** The leans' range, in steps. */
        val STEPS: IntRange = -2..2

        /** A step in words. */
        fun stepWords(step: Int): String = when {
            step <= -2 -> "hurts a lot"
            step == -1 -> "hurts"
            step == 0 -> "neither"
            step == 1 -> "helps"
            else -> "helps a lot"
        }
    }
}

/** A card and the role Ai gave it (a canonical passcode). */
@Serializable
data class RolePick(val card: Int, val role: String)

/** A role's lean, in steps; [on] false is turned off by the person. */
@Serializable
data class RoleLean(val role: String, val step: Int, val why: String = "", val on: Boolean = true)

/** A pair of cards (canonical passcodes) to fit, and why. */
@Serializable
data class PairPick(val a: Int, val b: Int, val why: String = "", val on: Boolean = true)

/** Reads and writes [ShootoutPriors], forgivingly: a newer build's keys are skipped, a file that will not read is none. */
object ShootoutPriorsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true; coerceInputValues = true; prettyPrint = true }

    fun encode(p: ShootoutPriors): String = json.encodeToString(ShootoutPriors.serializer(), p)

    fun decode(text: String?): ShootoutPriors? =
        if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(ShootoutPriors.serializer(), text) }.getOrNull()
}
