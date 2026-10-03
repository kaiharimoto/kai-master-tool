package com.kaiharimoto.mastertool.core.duel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A ruling two players agreed on at this table (1.0.79, Ai's feedback: "the rulings tool had no page
 * for e4 … let us save a ruling we agree on, like 'no free zone, can't activate', and have the table
 * remember it"). Kept by card when it names one ([code], [card] its name as typed or found), and read
 * back wherever that card is read: the inspector, what Ai is told about the table.
 */
@Serializable
data class HouseRuling(
    val id: String,
    val text: String,
    val code: Int? = null,
    val card: String? = null,
    val at: Long = 0L,
)

@Serializable
data class HouseRulingBook(val rulings: List<HouseRuling> = emptyList()) {
    fun forCode(code: Int): List<HouseRuling> = rulings.filter { it.code == code }

    /** Those for the cards [codes], and those for no card at all. */
    fun about(codes: Set<Int>): List<HouseRuling> = rulings.filter { it.code == null || it.code in codes }

    fun add(r: HouseRuling): HouseRulingBook = copy(rulings = rulings + r)

    fun remove(id: String): HouseRulingBook = copy(rulings = rulings.filterNot { it.id == id })
}

object HouseRulingCodec {
    /** Under `<data>/duel/`: synced and backed up with the rest of the folder. */
    const val PATH = "rulings.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false; prettyPrint = true }
    fun encode(b: HouseRulingBook): String = json.encodeToString(HouseRulingBook.serializer(), b)
    fun decode(text: String): HouseRulingBook = runCatching { json.decodeFromString(HouseRulingBook.serializer(), text) }.getOrElse { HouseRulingBook() }
}
