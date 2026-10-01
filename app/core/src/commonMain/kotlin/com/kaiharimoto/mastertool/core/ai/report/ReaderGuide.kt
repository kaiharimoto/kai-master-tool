package com.kaiharimoto.mastertool.core.ai.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A deck's guide written for people (1.0.66, kai: "the guide is very hard to read as a user due to
 * the density and feels like it's more for Ai … we could have it work on two versions, one
 * optimized for itself and one for the user. These guides are also meant to be shared"). Ai's own
 * notes stay what they are — dense labelled lines, cheap to hold in mind (`guides/<id>.md`); this is
 * the same knowledge written again for a reader: what the deck does, its cards by role, the plan
 * going first and second, each line step by step, what stops it, how it sides, and tips. Structured,
 * so every view of it — the app's, the PDF's — is laid out rather than listed. Cards are named as
 * printed; the views draw their art.
 */
@Serializable
data class ReaderGuide(
    val deckName: String,
    /** Who played it and where, when it is a list from somewhere: "Kaihuang Zhang · Las Vegas Regional, Top 8". */
    val subtitle: String = "",
    /** What the deck does and why it wins, in two or three sentences a newcomer follows. */
    val pitch: String,
    val roles: List<Role> = emptyList(),
    val goingFirst: List<String> = emptyList(),
    val goingSecond: List<String> = emptyList(),
    val lines: List<Line> = emptyList(),
    val chokePoints: List<Choke> = emptyList(),
    val siding: List<Side> = emptyList(),
    val tips: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    val updatedAt: Long = 0,
    /** Which version of Ai's notes it was written from: when the notes change, it is out of date. */
    val notesHash: String = "",
) {
    /** A group of cards doing one job: Starters, Extenders, Payoffs, Hand traps, Tech. */
    @Serializable
    data class Role(val name: String, val cards: List<RoleCard>)

    @Serializable
    data class RoleCard(val card: String, val note: String = "", val copies: Int = 0)

    /** A combo or a turn, step by step, and the board it ends on. */
    @Serializable
    data class Line(
        val name: String,
        val note: String = "",
        val steps: List<Step>,
        val endBoard: List<String> = emptyList(),
    )

    @Serializable
    data class Step(val card: String, val action: String)

    /** What stops the deck, and how to play around it. */
    @Serializable
    data class Choke(val card: String = "", val text: String)

    /** One matchup's siding: what comes in, what goes out, and why. */
    @Serializable
    data class Side(val matchup: String, val sideIn: List<String> = emptyList(), val sideOut: List<String> = emptyList(), val why: String = "")

    /** Every card the guide names, first mention first: what the views fetch art for. */
    fun cards(): List<String> = buildList {
        roles.forEach { r -> r.cards.forEach { add(it.card) } }
        lines.forEach { l -> l.steps.forEach { add(it.card) }; addAll(l.endBoard) }
        chokePoints.forEach { if (it.card.isNotBlank()) add(it.card) }
        siding.forEach { addAll(it.sideIn); addAll(it.sideOut) }
    }.filter { it.isNotBlank() }.distinct()

    val isEmpty: Boolean get() = pitch.isBlank() && roles.isEmpty() && lines.isEmpty()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

        fun read(text: String?): ReaderGuide? = text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        fun write(guide: ReaderGuide): String = Json { prettyPrint = true; encodeDefaults = false }.encodeToString(serializer(), guide)

        /** A short, stable fingerprint of Ai's notes: the reader's guide remembers which it was written from. */
        fun hashOf(notes: String): String {
            var h = 1469598103934665603L
            notes.trim().forEach { ch -> h = (h xor ch.code.toLong()) * 1099511628211L }
            return h.toULong().toString(36)
        }
    }
}
