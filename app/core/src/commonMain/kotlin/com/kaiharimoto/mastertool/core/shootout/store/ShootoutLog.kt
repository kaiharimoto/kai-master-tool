package com.kaiharimoto.mastertool.core.shootout.store

import com.kaiharimoto.mastertool.core.shootout.model.Answer
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
 * The log is append-only, but for the person's own hand on it (2026-10, kai: "a way to adjust trials and erase them"):
 * their answer may be changed ([ShootoutLog.adjusted], the first one kept in [first]) and a trial erased
 * ([ShootoutLog.erased]); nothing else ever rewrites one. Everything a later stage reads is here from
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
    /**
     * Your turn's draw, marked on screen, when you went second (1.1.5, `TrialDraws`). One of [hand] when nothing was drawn
     * by an effect; from 1.1.7 an effect's draw takes the top card first, so after one the turn's draw is the next card
     * down and the marked sixth is the first of [drew]. 1.1.5–1.1.6 wrote it as one of [hand] always.
     */
    val turnDraw: Int? = null,
    /** Their turn's draw as shown, when they went second; likewise. */
    val theirTurnDraw: Int? = null,
    /**
     * Cards turned up for your draws by card effects (1.1.5), in order. From 1.1.7 they come off the top, so when you went
     * second the first is the sixth of [hand]; 1.1.5–1.1.6 drew them from under it. Cards past the hand are never rated.
     */
    val drew: List<Int> = emptyList(),
    /** Cards turned up for their draws by card effects. */
    val theyDrew: List<Int> = emptyList(),
    /**
     * Your hand's turn's draw, when you went second (2026-10): the one of [hand] dealt sixth, which the model rates as the
     * draw and apart from the opening five. Null going first, and on trials kept before it (read off [turnDraw] and [drew]
     * where they say, else as unknown).
     */
    val sixth: Int? = null,
    /** A comparison's two hands' turn's draws, likewise; null on comparisons kept before the draw was shown in them. */
    val leftSixth: Int? = null,
    val rightSixth: Int? = null,
    /**
     * When the person changed this answer after giving it (2026-10), epoch milliseconds; null for an answer as given. The
     * fit reads the answer as it stands now.
     */
    val adjusted: Long? = null,
    /** The answer as first given, before any change: a rating's answer name, or a comparison's [LEFT]/[RIGHT]. */
    val first: String? = null,
    /**
     * Your deck's print when it was answered (`Ledger.fingerprint`, by card; 2026-10, Phase G), so answers given to one
     * version of a deck can later be told from another's; null on trials kept before.
     */
    val deckPrint: String? = null,
) {
    /** Every hand of yours it shows. */
    val hands: List<List<Int>> get() = if (kind == COMPARE) listOf(left, right) else listOf(hand)

    /** Whether any hand of yours it shows holds [card]. */
    fun holds(card: Int): Boolean = hands.any { card in it }

    /** Whether one hand it shows holds both [a] and [b]. */
    fun holdsBoth(a: Int, b: Int): Boolean = hands.any { a in it && b in it }

    /** The answer as it stands: a rating's answer name, or a comparison's [LEFT]/[RIGHT]. */
    val given: String? get() = if (kind == COMPARE) prefer else answer

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

/** Card against card (2026-10): the deck's [card] and the [substitute] put in every copy's place, as canonical passcodes. */
@Serializable
data class VersusPick(val card: Int, val substitute: Int)

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
    /** Card against card (2026-10): the card and its substitute this log compares; null for an ordinary Shootout's log. */
    val versus: VersusPick? = null,
) {
    fun plus(trial: StoredTrial): ShootoutLog = copy(trials = trials + trial)

    /**
     * The log with the person's answer to trial [id] changed to [to] (2026-10): a rating's answer name, or a comparison's
     * [StoredTrial.LEFT]/[StoredTrial.RIGHT]. Only the person's own answers change — Ai's are Ai's — and the answer as first
     * given is kept. A blind answer changed once Ai had answered the same hand is counted from then on as given having seen
     * Ai's ([StoredTrial.sawAi]): the person may have changed it on seeing Ai's beside it, so it never again scores Ai's
     * agreement as a blind answer would. Null when there is no such trial or [to] is not an answer of its kind.
     */
    fun adjusted(id: String, to: String, at: Long): ShootoutLog? {
        val i = trials.indexOfFirst { it.id == id }
        val t = trials.getOrNull(i)?.takeIf { it.judge == StoredTrial.PERSON } ?: return null
        val valid = if (t.kind == StoredTrial.COMPARE) to == StoredTrial.LEFT || to == StoredTrial.RIGHT else to in ANSWERS
        if (!valid) return null
        if (t.given == to) return this
        val aiAnswered = t.ai != null || trials.any { it.judge == StoredTrial.AI && it.of == id }
        val next = (if (t.kind == StoredTrial.COMPARE) t.copy(prefer = to) else t.copy(answer = to)).copy(
            adjusted = at,
            first = t.first ?: t.given,
            sawAi = t.sawAi || aiAnswered,
        )
        return copy(trials = trials.toMutableList().also { it[i] = next })
    }

    /**
     * The log with the trials [ids] erased (2026-10), and with them the person's notes on them and Ai's answers to them
     * ([StoredTrial.of]): an answer to a hand that is gone has nothing left to agree with. What went is kept in the
     * [Erasure], so it can be put back.
     */
    fun erased(ids: Set<String>): Erasure {
        val gone = trials.indices.filter { trials[it].id in ids || trials[it].of in ids }
        val goneIds = gone.map { trials[it].id }.toSet()
        val keptNotes = notes.filter { it.trial !in goneIds }
        return Erasure(
            log = copy(trials = trials.filterIndexed { i, _ -> i !in gone }, notes = keptNotes),
            trials = gone.map { it to trials[it] },
            notes = notes.filter { it.trial in goneIds },
        )
    }

    /** The log with an [Erasure]'s trials back in their places and its notes back; a trial already there again is left. */
    fun restored(e: Erasure): ShootoutLog {
        val here = trials.map { it.id }.toSet()
        val out = trials.toMutableList()
        e.trials.filter { (_, t) -> t.id !in here }.forEach { (i, t) -> out.add(i.coerceAtMost(out.size), t) }
        return copy(trials = out, notes = notes + e.notes.filter { it !in notes })
    }

    /** The person's notes on [trial], oldest first. */
    fun notesOn(trial: String): List<TrialNote> = notes.filter { it.trial == trial }

    /** The trust settings, the defaults when none were chosen. */
    val trusted: TrustSettings get() = trust ?: TrustSettings()

    companion object {
        /** The format's version: a newer one is read for what it shares with this one. */
        const val VERSION = 1

        /** A rating's answers by name. */
        private val ANSWERS = Answer.entries.map { it.name }.toSet()
    }
}

/**
 * Trials erased from a log (2026-10): the [log] without them, and what went — each trial with the place it stood in, and
 * the notes on them — so [ShootoutLog.restored] can put them back.
 */
data class Erasure(
    val log: ShootoutLog,
    val trials: List<Pair<Int, StoredTrial>>,
    val notes: List<TrialNote>,
) {
    /** How many of the person's hands went (Ai's answers to them go too, uncounted). */
    val hands: Int get() = trials.count { it.second.judge == StoredTrial.PERSON }
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
        val versus = root["versus"]?.let { v ->
            try {
                json.decodeFromJsonElement(VersusPick.serializer(), v)
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
            versus = versus,
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

    /** Card against card's logs (2026-10), a folder of the deck's own: deleted, synced and backed up with it. */
    const val VERSUS = "versus"

    /** The folder of the deck's card-against-card logs. */
    fun versusFolder(deckId: String): String = "${folder(deckId)}/$VERSUS"

    /**
     * The log comparing [card] with [substitute] (canonical passcodes) for the deck alone ([opponentId] null) or against
     * one opponent: `versus/<target>.<card>.<substitute>.json`. [safe] never writes a dot, so the parts never run together.
     */
    fun versus(deckId: String, opponentId: String?, card: Int, substitute: Int): String =
        "${versusFolder(deckId)}/${if (opponentId == null) "alone" else safe(opponentId)}.$card.$substitute.json"

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
