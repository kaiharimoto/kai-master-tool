package com.kaiharimoto.mastertool.core.ai.playbook

import com.kaiharimoto.mastertool.core.ai.memory.AiMemory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What Ai knows about how a deck is played, as data (kai, 2026-10: "the notes need to be thorough in order to attain
 * mastery"). The guide is prose, read whole or by budget; the playbook is the same knowledge taken apart into the
 * pieces a player reaches for — a line, card by card, with the board it ends on and what it plays through; a decision,
 * with its situation, choice and why; a card's role; a matchup; a principle; a ruling — each with where it was learned.
 * The same thing learned twice is one entry with two sources, so a line seen in a chapter and in three replays carries
 * four pieces of evidence.
 *
 * Kept as `ai/playbooks/<deck>.json`, beside the guide; synced and backed up like it, deleted with the deck. Unknown
 * keys are ignored, so an older build reads a newer playbook.
 */
@Serializable
data class Playbook(
    val deckId: String,
    val entries: List<Play> = emptyList(),
    /** The next entry's number: ids are never reused, so a source naming `line-7` always means the same line. */
    val next: Int = 1,
) {
    fun entry(id: String): Play? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    /** [play] put in place of the entry with its id. */
    fun with(play: Play): Playbook = copy(entries = entries.map { if (it.id == play.id) play else it })

    val size: Int get() = entries.size
}

@Serializable
data class Play(
    val id: String,
    val kind: Kind,
    /** What a player calls it: "Aluber into Mirrorjade", "Ash on Branded Fusion going second". */
    val title: String,
    /** The whole of it, in Ai's words: as long as it needs to be. */
    val body: String = "",
    /** Every card it is about, by exact name: what finds it when those cards are in hand or on the table. */
    val cards: List<String> = emptyList(),
    /** A line: the cards the hand must hold to start it. */
    val needs: List<String> = emptyList(),
    /** A line: its steps, in order. */
    val steps: List<Step> = emptyList(),
    /** A line: the board it ends on. */
    val endBoard: String = "",
    /** A line: the interruptions it plays through, and how. */
    val through: List<String> = emptyList(),
    /** A line, a decision: what stops it, and what to do then. */
    val weakTo: List<String> = emptyList(),
    /** A decision: when it comes up. */
    val situation: String = "",
    /** A decision: what to do. */
    val choice: String = "",
    /** A decision, and anything else: why. */
    val why: String = "",
    /** A matchup: the deck it is against. */
    val against: String = "",
    val going: Going = Going.EITHER,
    val sources: List<Source> = emptyList(),
    val confidence: Confidence = Confidence.STATED,
    val updatedAt: Long = 0,
) {
    @Serializable
    enum class Kind(val word: String) {
        LINE("line"), DECISION("decision"), CARD("card"), MATCHUP("matchup"), PRINCIPLE("principle"), RULING("ruling");

        companion object {
            fun of(word: String?): Kind? = entries.firstOrNull { it.word.equals(word?.trim(), ignoreCase = true) || it.name.equals(word?.trim(), ignoreCase = true) }
        }
    }

    @Serializable
    enum class Going(val word: String) {
        FIRST("first"), SECOND("second"), EITHER("either");

        companion object {
            fun of(word: String?): Going = entries.firstOrNull { it.word.equals(word?.trim(), ignoreCase = true) } ?: EITHER
        }
    }

    /** How Ai knows it: the author said so, a replay showed it, Ai worked it out, or the engine or a tool proved it. */
    @Serializable
    enum class Confidence(val word: String) {
        STATED("stated"), SHOWN("shown"), INFERRED("inferred"), VERIFIED("verified");

        companion object {
            fun of(word: String?): Confidence = entries.firstOrNull { it.word.equals(word?.trim(), ignoreCase = true) } ?: STATED
        }
    }

    /** Every card the entry touches: what it is about, what it needs, what its steps use. */
    val allCards: Set<String> get() = (cards + needs + steps.map { it.card }).filter { it.isNotBlank() }.map { it.trim() }.toSet()
}

@Serializable
data class Step(
    /** The card that acts. */
    val card: String,
    /** What is done with it: "Normal Summon", "activate its first effect, sending Albaz from Deck to GY". */
    val action: String,
    /** What the step leaves: "Mirrorjade on the field; Aluber in GY". */
    val result: String = "",
)

/** Where an entry was learned: a chapter's section, a replay's game and turn, the person, Ai's own study. */
@Serializable
data class Source(val ref: String, val note: String = "")

object PlaybookPaths {
    const val DIR = "playbooks"
    fun of(deckId: String): String = "$DIR/${AiMemory.safeId(deckId)}.json"
}

object PlaybookCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; isLenient = true; coerceInputValues = true; prettyPrint = true }

    /** The playbook [text] holds; an empty one when there is none yet, null when it cannot be read (never written over). */
    fun read(text: String?, deckId: String): Playbook? =
        if (text.isNullOrBlank()) Playbook(deckId) else runCatching { json.decodeFromString(Playbook.serializer(), text) }.getOrNull()

    fun write(book: Playbook): String = json.encodeToString(Playbook.serializer(), book)
}
