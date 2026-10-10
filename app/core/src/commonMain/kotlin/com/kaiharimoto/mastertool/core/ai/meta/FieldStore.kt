package com.kaiharimoto.mastertool.core.ai.meta

import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.remote.DeckFormat
import com.kaiharimoto.mastertool.core.remote.TournamentDeck
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The field as last read, kept on this device (Phase G, G.5): `<data>/field/latest.json`, a cache — re-readable from
 * YGOPRODeck at any time, so never synced or backed up (`InboundPath.DEVICE_FOLDERS`). It lets the inspector say how the
 * field plays a card ("3 in 88% of 41 lists like yours") and Format draw the room without asking the site again.
 */
@Serializable
data class FieldSnapshot(
    /** When it was read, epoch milliseconds. */
    val readAt: Long,
    /** TCG, OCG or Genesys. */
    val format: String,
    val tier: Int,
    val days: Int,
    /** The day it was read as of, when a past one; null for the latest. */
    val asOf: String? = null,
    val lists: List<StoredList> = emptyList(),
) {
    /** The lists as the meta tools read them. */
    fun decks(): List<TournamentDeck> = lists.map { it.deck() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun of(readAt: Long, format: DeckFormat, tier: Int, days: Int, asOf: String?, decks: List<TournamentDeck>) =
            FieldSnapshot(readAt, format.name, tier, days, asOf, decks.map(StoredList::of))

        fun encode(s: FieldSnapshot): String = json.encodeToString(serializer(), s)

        /** A snapshot read leniently; null for anything that is not one. */
        fun decode(text: String): FieldSnapshot? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** One tournament list as kept ([FieldSnapshot]). */
@Serializable
data class StoredList(
    val number: Int,
    val name: String,
    val event: String,
    val placement: String,
    val players: Int? = null,
    val pilot: String? = null,
    val format: String = DeckFormat.TCG.name,
    val daysAgo: Int = 0,
    val tier: Int = 0,
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val side: List<Int> = emptyList(),
    val url: String = "",
    val date: String? = null,
    val day: String? = null,
) {
    fun deck(): TournamentDeck = TournamentDeck(
        number, name, event, placement, players, pilot,
        DeckFormat.entries.firstOrNull { it.name == format } ?: DeckFormat.TCG,
        daysAgo, tier, Deck(main.map(::CardId), extra.map(::CardId), side.map(::CardId)), url, date, day,
    )

    companion object {
        fun of(d: TournamentDeck) = StoredList(
            d.number, d.name, d.event, d.placement, d.players, d.pilot, d.format.name, d.daysAgo, d.tier,
            d.deck.main.map { it.value }, d.deck.extra.map { it.value }, d.deck.side.map { it.value }, d.url, d.date, d.day,
        )
    }
}
