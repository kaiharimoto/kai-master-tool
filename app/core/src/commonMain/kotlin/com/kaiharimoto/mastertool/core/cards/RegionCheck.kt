package com.kaiharimoto.mastertool.core.cards

import com.kaiharimoto.mastertool.core.ai.rules.Yugipedia
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.search.TextMatching
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A second opinion on where a card is printed (1.1.1, kai: "Trap holic exists in the tcg").
 *
 * YGOPRODeck's `formats` are behind for some cards: Trap Holic was printed in the TCG in Duelist's Advance (DUAD-EN078,
 * 4 July 2025) and a year later the site still lists it as OCG and Master Duel only. Read on 2026-10-04, 47 of the 383
 * cards the site calls OCG-only are in Yugipedia's "TCG cards" category. One source is not proof, so a card is
 * **not released** in a region only when both agree:
 * - the pool's `formats` lack the region, and
 * - Yugipedia knows the card (it is in "TCG cards" or "OCG cards") and it is not in that region's category.
 *
 * Where the two disagree the region stays unconfirmed — Yugipedia's "TCG cards" also holds cards announced for a set not
 * out yet, and Speed Duel prints — and so does a card Yugipedia does not know; [com.kaiharimoto.mastertool.core.deck.Legality]
 * reads both as unknown, never illegal and never vouched for. Applied over the pool in memory
 * ([com.kaiharimoto.mastertool.core.data.CardRepository.useRegions]);
 * nothing in the database changes.
 */
class RegionNames(tcg: Collection<String>, ocg: Collection<String>) {
    private val tcg: Set<String> = tcg.mapTo(HashSet(tcg.size * 2), ::key)
    private val ocg: Set<String> = ocg.mapTo(HashSet(ocg.size * 2), ::key)

    val isEmpty: Boolean get() = tcg.isEmpty() || ocg.isEmpty()

    /** [card] with the regions both sources agree it was never printed in; the same card when there are none. */
    fun confirm(card: Card): Card {
        if (card.formats.isEmpty() || isEmpty) return card
        val k = key(card.name)
        if (k !in tcg && k !in ocg) return card
        val absent = listOf("TCG" to tcg, "OCG" to ocg)
            .filter { (word, names) -> word !in card.formats && k !in names }
            .mapTo(LinkedHashSet()) { it.first }
        return if (absent.isEmpty() || absent == card.absentFrom) card else card.copy(absentFrom = card.absentFrom + absent)
    }

    companion object {
        /** A name as both sources are matched: Yugipedia's " (card)" disambiguation dropped, then normalised. */
        fun key(name: String): String = TextMatching.normalize(name.trim().removeSuffix(" (card)"))

        const val TCG_CATEGORY = "TCG cards"
        const val OCG_CATEGORY = "OCG cards"

        /** One page of a category's members (500), from [continueFrom] when given. */
        fun categoryUrl(category: String, continueFrom: String? = null): String =
            "${Yugipedia.API}?action=query&list=categorymembers&cmtitle=Category:${Yugipedia.title(category)}" +
                "&cmnamespace=0&cmlimit=500&cmprop=title&format=json" + (continueFrom?.let { "&cmcontinue=" + Yugipedia.title(it) } ?: "")
    }
}

/**
 * The two categories as kept on the device (`<data>/banlists/regions.json`, a cache like the lists: never synced, read
 * with unknown keys ignored, fetched again weekly).
 */
@Serializable
data class RegionDoc(
    val version: Int = 1,
    /** When it was read, epoch milliseconds. */
    val checked: Long = 0,
    val tcg: List<String> = emptyList(),
    val ocg: List<String> = emptyList(),
) {
    fun names(): RegionNames = RegionNames(tcg, ocg)

    /** Whether it is old enough to read again: a week, or never read whole. */
    fun stale(now: Long): Boolean = tcg.isEmpty() || ocg.isEmpty() || now - checked > WEEK_MS

    companion object {
        const val FILE = "regions.json"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** A category this long is cut short, never read for ever (each is about 14,500 today: 30 pages). */
        const val MAX_PAGES = 80

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): RegionDoc? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(doc: RegionDoc): String = json.encodeToString(serializer(), doc)
    }
}
