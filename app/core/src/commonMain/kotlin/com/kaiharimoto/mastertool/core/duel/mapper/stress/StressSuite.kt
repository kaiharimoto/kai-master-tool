package com.kaiharimoto.mastertool.core.duel.mapper.stress

import com.kaiharimoto.mastertool.core.ai.meta.FieldProfile
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The stress suite (E.md §4.1, M.md §3.4): the sets of interruptions a deck is tested against, kept per deck beside its
 * library (`<data>/effects/mapper/<deck>/suite.json`, synced, backed up, deleted with the deck). The person chooses every set;
 * Ai may propose one, marked as its own with its reason, as it proposes presets.
 */

/**
 * One set of interruptions held by the other seat: [cards] (canonical passcodes, sorted). Its [key] is what the library's
 * `BoardTraits.through` is keyed by. [by] "ai" when Ai proposed it, with [why]; [share] how much of the field plays it, when
 * it came from the field.
 */
@Serializable
data class StressSet(
    val cards: List<Int>,
    val by: String = PERSON,
    val why: String = "",
    val share: Double? = null,
    val at: Long = 0L,
) {
    val key: String get() = StressSuite.keyOf(cards)

    companion object {
        const val PERSON = "person"
        const val AI = "ai"
    }
}

/** A deck's suite, in the order the person put it. */
@Serializable
data class StressSuite(val version: Int = 1, val sets: List<StressSet> = emptyList()) {
    fun byKey(key: String): StressSet? = sets.firstOrNull { it.key == key }

    /** [set] added, or put in place of the one with its key. */
    fun put(set: StressSet): StressSuite {
        val s = set.copy(cards = set.cards.sorted())
        return copy(sets = if (sets.any { it.key == s.key }) sets.map { if (it.key == s.key) s else it } else sets + s)
    }

    fun remove(key: String): StressSuite = copy(sets = sets.filterNot { it.key == key })

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

        /** A suite read forgivingly: an unreadable file is an empty suite (never written over by reading it). */
        fun decode(text: String?): StressSuite? = text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }

        /** The key a set of cards is kept under: its passcodes sorted, joined by "+". */
        fun keyOf(cards: List<Int>): String = cards.sorted().joinToString("+")

        /** The cards a key names; empty when it is not one ([keyOf]). */
        fun cardsOf(key: String): List<Int> = key.split('+').mapNotNull { it.toIntOrNull() }.takeIf { it.size == key.split('+').size }.orEmpty()

        /** A key in words: "Ash Blossom & Joyous Spring + Effect Veiler"; the key itself when it names no cards. */
        fun words(key: String, name: (Int) -> String): String = cardsOf(key).takeIf { it.isNotEmpty() }?.joinToString(" + ") { name(it) } ?: key

        /** A key short, for a column or a chip: each card's first word ("Ash+Effect"). */
        fun short(key: String, name: (Int) -> String): String =
            cardsOf(key).takeIf { it.isNotEmpty() }?.joinToString("+") { name(it).substringBefore(' ').take(8) } ?: key

        /**
         * Sets to propose from the field ([profile]): each of the [top] interruptions the field plays most, alone, and the two
         * most played together — the person keeps the ones they want. Only cards [cards] knows; marked with the field's share.
         */
        fun fromField(profile: FieldProfile, cards: (CardId) -> Card?, top: Int = 5, at: Long = 0L): List<StressSet> {
            val common = profile.interaction().filter { cards(it.card) != null }.take(top)
            val singles = common.map { StressSet(listOf(it.card.value), why = "played by ${pct(it.share)} of the field", share = it.share, at = at) }
            val pair = common.take(2).takeIf { it.size == 2 }?.let { (a, b) ->
                StressSet(listOf(a.card.value, b.card.value).sorted(), why = "the field's two most played answers together", share = a.share * b.share, at = at)
            }
            return singles + listOfNotNull(pair)
        }

        private fun pct(x: Double): String = "${(x * 100).toInt()} %"
    }
}
