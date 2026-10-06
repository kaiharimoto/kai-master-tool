package com.kaiharimoto.mastertool.core.ai.chessy.gifts

/**
 * What she has given you: each gift's id and how many times (kept as `AiPrefs.chessyGifts`, synced, so the collection
 * follows you from device to device; a map merges key by key). Once received, a gift is yours for good.
 */
data class GiftCollection(val counts: Map<String, Int> = emptyMap()) {
    fun owned(id: String): Boolean = (counts[id] ?: 0) > 0

    fun times(id: String): Int = counts[id] ?: 0

    fun record(id: String): GiftCollection = GiftCollection(counts + (id to times(id) + 1))

    /** How much of [catalog] is collected: overall and per kind, as (have, of). */
    fun progress(catalog: GiftCatalog): Progress {
        val per = GiftKind.entries.associateWith { k ->
            val items = catalog.ofKind(k)
            items.count { owned(it.id) } to items.size
        }
        return Progress(per.values.sumOf { it.first }, per.values.sumOf { it.second }, per, counts.values.sum())
    }

    /** [have] of [of] collected; [perKind] the same per kind; [received] every gift ever given, repeats too. */
    data class Progress(val have: Int, val of: Int, val perKind: Map<GiftKind, Pair<Int, Int>>, val received: Int) {
        val share: Float get() = if (of == 0) 0f else have.toFloat() / of
    }

    companion object {
        /** A stored map made safe: blank ids and counts below one dropped. */
        fun sanitised(raw: Map<String, Int>): Map<String, Int> = raw.filter { (k, v) -> k.isNotBlank() && v > 0 }
    }
}
