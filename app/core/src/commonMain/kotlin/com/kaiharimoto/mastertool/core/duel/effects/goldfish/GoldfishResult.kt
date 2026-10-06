package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/*
 * What a goldfish run comes to (Phase D step 4, `docs/phases/D.md` §5.6), and the deck's file that keeps its targets and the
 * results the person kept (`<data>/effects/goldfish/<deck>.json`, §6): versioned, read forgivingly, synced newer wins,
 * backed up, deleted with the deck. Every number opens its hands: each hand's outcome is kept, and any hand is made again
 * from the seed and its index as a replay (`GoldfishReplay`).
 */

/** How one hand ended (§5.4). */
@Serializable
enum class HandEnd {
    /** The target was met, with its line. */
    REACHED,

    /** The search covered everything within its bounds: the trusted effects cannot get there from this hand. */
    NO_LINE,

    /** The budget ran out, or a choice had more answers than the search tries: not known. */
    UNDECIDED,
}

/**
 * One hand of a run: its [index] (hand k is dealt from the seed and k), the cards dealt ([hand], canonical passcodes, in the
 * order drawn), its engine part ([reduced]: the cards that are not blanks, sorted — hands with the same engine part share a
 * search), how it ended, and the line it took ([line]: an index into [GoldfishResult.lines]).
 */
@Serializable
data class HandOutcome(
    val index: Int,
    val hand: List<Int>,
    val reduced: List<Int> = emptyList(),
    val end: HandEnd,
    val line: Int? = null,
    /** Engine moves spent on its search (shared when [reduced] was searched before: the first hand's count). */
    val moves: Int = 0,
    /** It held a card the goldfish plays as inert (§5.5). */
    val heldUnknown: Boolean = false,
    /** It reached the target through a line that moves a card played as inert. */
    val touchedUnknown: Boolean = false,
)

/** A line found, as its skeleton ("Aluber → Branded Fusion → Mirrorjade") and how many hands it reached; [combo] for a recorded line. */
@Serializable
data class LineCount(
    val skeleton: String,
    val count: Int,
    val combo: String? = null,
    /** The cards played as inert that the line moves ("touches Aluber, unknown"). */
    val touches: List<Int> = emptyList(),
    /**
     * The skeleton's cards in its order, one a part (canonical passcodes): what the pane draws as a strip of art. Empty in
     * results kept before it was written (the pane then reads the skeleton's names).
     */
    val cards: List<Int> = emptyList(),
)

/** A recorded line (or a target) that needs a card the goldfish plays as inert: never 0 %, not computable, naming the cards. */
@Serializable
data class NotComputable(
    /** The combo's id, or "target" for the target itself. */
    val what: String,
    val name: String = "",
    val cards: List<Int> = emptyList(),
    val why: String = "",
)

/**
 * A goldfish run (§5.6). [deck] is the deck's fingerprint (`Ledger.fingerprint`), [library] the trusted scripts' (`FxTrust.library`):
 * a number goes stale when either moves.
 */
@Serializable
data class GoldfishResult(
    val version: Int = VERSION,
    val deck: String,
    val library: String,
    val target: EndBoard,
    val first: Boolean,
    val hands: Int,
    val seed: Long,
    val budget: Int,
    val reached: Int,
    val noLine: Int,
    val undecided: Int,
    /** The deck's cards played as inert (canonical passcodes). */
    val unknown: List<Int> = emptyList(),
    val heldUnknown: Int = 0,
    val touchedUnknown: Int = 0,
    val notComputable: List<NotComputable> = emptyList(),
    val lines: List<LineCount> = emptyList(),
    val outcomes: List<HandOutcome> = emptyList(),
    val ms: Long = 0L,
    // ---- the trust rule (D.md §11) ----
    /** The cards whose trusted effects the found lines used (canonical passcodes): a wrong number traces to a wrong script. */
    val used: List<Int> = emptyList(),
    /** Of [used], those whose scripts had warnings the person had not accepted. */
    val warned: List<Int> = emptyList(),
    /** How many of [used] were played by you at the table with the script they have now. */
    val playedByYou: Int = 0,
    // ---- how it was run ----
    /** The deck's id, when it has one. */
    val deckId: String? = null,
    /** The recorded line asked about (a combo's id): "this line". Null for the search. */
    val combo: String? = null,
    /** Every hand searched on its own, none reduced (§5.3): the scripts read the Deck's order, or the run played a recorded line. */
    val ordered: Boolean = false,
    /** The most engine moves one line may hold. */
    val depth: Int = 0,
    /** When it was run, in ms. */
    val at: Long = 0L,
    /** The engine's version ([com.kaiharimoto.mastertool.core.duel.effects.FxVocab.ENGINE]). */
    val engine: Int = 0,
    /** Kept by the person (a result is stored only when they keep it). */
    val name: String = "",
) {
    /** The share reached, of [hands]. */
    val rate: Double get() = if (hands == 0) 0.0 else reached.toDouble() / hands

    companion object {
        const val VERSION = 1
    }
}

/** A deck's goldfish file: its targets, and the results the person kept. */
@Serializable
data class GoldfishDoc(
    val version: Int = VERSION,
    val deck: String = "",
    val targets: List<EndBoard> = emptyList(),
    val results: List<GoldfishResult> = emptyList(),
) {
    fun target(id: String): EndBoard? = targets.firstOrNull { it.id == id }

    companion object {
        const val VERSION = 1

        /** At most this many kept results a deck: the oldest go first. */
        const val MOST_RESULTS = 20

        /** At most this many targets a deck. */
        const val MOST_TARGETS = 40
    }
}

object GoldfishCodec {
    /** The vocabulary's own discriminator ("t"), so a filter reads as a script writes it. */
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false; classDiscriminator = "t" }

    fun encode(d: GoldfishDoc): String = json.encodeToString(GoldfishDoc.serializer(), d)

    /** A deck's file as stored; unreadable reads as empty (the person's targets are re-made, never guessed). */
    fun decode(text: String?): GoldfishDoc =
        text?.let { runCatching { json.decodeFromString(GoldfishDoc.serializer(), it) }.getOrNull() } ?: GoldfishDoc()

    fun encodeTarget(t: EndBoard): String = json.encodeToString(EndBoard.serializer(), t)

    fun decodeTarget(text: String): EndBoard? = runCatching { json.decodeFromString(EndBoard.serializer(), text) }.getOrNull()

    fun encodeResult(r: GoldfishResult): String = json.encodeToString(GoldfishResult.serializer(), r)

    fun decodeResult(text: String): GoldfishResult? = runCatching { json.decodeFromString(GoldfishResult.serializer(), text) }.getOrNull()

    /** The file a deck's goldfish lives in, under `<data>/effects/`: `goldfish/<deck>.json`. */
    fun path(deckId: String): String = "${FxPaths.GOLDFISH}/${AiMemory.safeId(deckId).ifBlank { "deck" }}.json"

    /** [d] with [target] put in (replacing one of its id), at most [GoldfishDoc.MOST_TARGETS]. */
    fun putTarget(d: GoldfishDoc, target: EndBoard): GoldfishDoc =
        d.copy(targets = (d.targets.filterNot { it.id == target.id } + target).takeLast(GoldfishDoc.MOST_TARGETS))

    /** [d] without the target [id] (its kept results stay: they carry their target whole). */
    fun dropTarget(d: GoldfishDoc, id: String): GoldfishDoc = d.copy(targets = d.targets.filterNot { it.id == id })

    /** [d] with [r] kept, newest last, at most [GoldfishDoc.MOST_RESULTS]. */
    fun keep(d: GoldfishDoc, r: GoldfishResult): GoldfishDoc = d.copy(results = (d.results + r).takeLast(GoldfishDoc.MOST_RESULTS))

    /** How a target's conditions are written, for an error that teaches. */
    const val EXAMPLE = "[{\"t\": \"controls\", \"where\": {\"t\": \"name-has\", \"word\": \"Example\"}, \"n\": 2}, {\"t\": \"interruptions\", \"n\": 1}]"

    /**
     * A target read from what Ai or the instrument was given: [name] and the conditions [all] (a list of conditions as the
     * vocabulary writes them). Throws [IllegalArgumentException] in words that show a call that works.
     */
    fun target(id: String, name: String, deck: String, all: JsonElement?, by: String, at: Long = 0L): EndBoard {
        require(name.isNotBlank()) { "a target needs a name, like \"Two Example monsters + one negate\"" }
        val list = all as? JsonArray ?: throw IllegalArgumentException("all is a list of conditions, like $EXAMPLE")
        require(list.isNotEmpty()) { "all is empty: give at least one condition, like $EXAMPLE" }
        require(list.size <= MOST_CONDITIONS) { "at most $MOST_CONDITIONS conditions a target (${list.size} given)" }
        val conds = list.mapIndexed { i, e ->
            val c = runCatching { json.decodeFromJsonElement(LenientBoardCond, e) }.getOrElse {
                throw IllegalArgumentException("condition ${i + 1} does not read (${it.message?.lineSequence()?.firstOrNull()}): conditions are like $EXAMPLE")
            }
            require(c !is BoardCond.Unknown) { "condition ${i + 1} is no condition this build knows: “t” is controls, set, holds, gy, banished, interruptions or any-of — like $EXAMPLE" }
            c
        }
        return EndBoard(id, name.trim().take(120), deck, conds, by, at)
    }

    /** The most conditions one target holds. */
    const val MOST_CONDITIONS = 12
}
