package com.kaiharimoto.mastertool.core.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One row of YGOPRODeck's `cardsets.php`: a printed set, and when it reached the TCG. */
@Serializable
data class CardSetRelease(
    @SerialName("set_name") val name: String,
    @SerialName("set_code") val code: String = "",
    @SerialName("num_of_cards") val cards: Int = 0,
    /** `yyyy-MM-dd`, or absent for a promotion that never had a release. */
    @SerialName("tcg_date") val tcgDate: String? = null,
)

object CardSetReleases {

    private val ISO = Regex("""\d{4}-\d{2}-\d{2}""")

    /**
     * The newest set already on shelves on [today] (`yyyy-MM-dd`).
     *
     * The list carries announced sets with dates still to come, so "the latest"
     * is the latest one *out*, not the last row. ISO dates sort as strings. Two
     * sets out the same day are told apart by size — a booster and the promo
     * pack beside it, and the booster is the one a player means.
     */
    fun latest(sets: List<CardSetRelease>, today: String): CardSetRelease? =
        sets.asSequence()
            .filter { set -> set.tcgDate?.let { ISO.matches(it) && it <= today } == true }
            .maxWithOrNull(compareBy<CardSetRelease>({ it.tcgDate }, { it.cards }))
}
