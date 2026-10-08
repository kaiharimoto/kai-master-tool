package com.kaiharimoto.mastertool.core.duel

/**
 * A table's cards by uid ([DuelState.cards]) as `DuelRules` keeps them (2026-10, the engine's speed, M.md §8): **the same
 * map** as the `LinkedHashMap` it stands in for — equal to it either way round, hashing as it does, iterating and so written
 * to a file in the same order, read-only — but a card changed is one array copied, not every entry hashed into a new map,
 * and a look-up by uid ([byUid], [DuelState.card]) boxes nothing.
 *
 * A table holds one once `DuelRules` has changed a card of it ([with]); a table read from a file, or dealt, holds the map it
 * was made with until then. The uids and where each stands are shared by every table made from it while no card joins or
 * leaves: only the cards are copied.
 */
internal class CardMap private constructor(
    /** The uids in the map's order. */
    private val uids: IntArray,
    /** The cards, in step with [uids]. */
    private val held: Array<CardInst>,
    private val slots: Slots,
) : AbstractMap<Int, CardInst>() {

    override val size: Int get() = uids.size

    override fun isEmpty(): Boolean = uids.isEmpty()

    /** The card [uid], or null when the table holds none. */
    fun byUid(uid: Int): CardInst? {
        val i = slots.find(uid)
        return if (i < 0) null else held[i]
    }

    override fun get(key: Int): CardInst? = byUid(key)

    override fun containsKey(key: Int): Boolean = slots.find(key) >= 0

    override fun containsValue(value: CardInst): Boolean = held.any { it == value }

    /** This map with [uid] holding [card]: in its place when the map holds it, else last (as a `LinkedHashMap` puts it). */
    fun with(uid: Int, card: CardInst): CardMap {
        val i = slots.find(uid)
        if (i >= 0) {
            val next = held.copyOf()
            next[i] = card
            return CardMap(uids, next, slots)
        }
        val ids = uids.copyOf(uids.size + 1)
        ids[uids.size] = uid
        val next = arrayOfNulls<CardInst>(held.size + 1)
        held.copyInto(next)
        next[held.size] = card
        @Suppress("UNCHECKED_CAST")
        return CardMap(ids, next as Array<CardInst>, Slots(ids))
    }

    override val entries: Set<Map.Entry<Int, CardInst>>
        get() = object : AbstractSet<Map.Entry<Int, CardInst>>() {
            override val size: Int get() = uids.size

            override fun iterator(): Iterator<Map.Entry<Int, CardInst>> = object : Iterator<Map.Entry<Int, CardInst>> {
                private var i = 0

                override fun hasNext(): Boolean = i < uids.size

                override fun next(): Map.Entry<Int, CardInst> {
                    if (i >= uids.size) throw NoSuchElementException()
                    val e = Entry(uids[i], held[i])
                    i++
                    return e
                }
            }
        }

    override val keys: Set<Int>
        get() = object : AbstractSet<Int>() {
            override val size: Int get() = uids.size

            override fun contains(element: Int): Boolean = slots.find(element) >= 0

            override fun iterator(): Iterator<Int> = uids.iterator()
        }

    override val values: Collection<CardInst>
        get() = object : AbstractCollection<CardInst>() {
            override val size: Int get() = held.size

            override fun iterator(): Iterator<CardInst> = held.iterator()
        }

    /** An entry as `Map.Entry`'s contract has it: equal to any entry of an equal key and value, hashing as `HashMap`'s. */
    private class Entry(override val key: Int, override val value: CardInst) : Map.Entry<Int, CardInst> {
        override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()

        override fun toString(): String = "$key=$value"
    }

    /** Each uid's position in [uids], open-addressed; never changed once built, so the maps made from one share it. */
    private class Slots(uids: IntArray) {
        private val keys: IntArray
        /** A position + 1, or 0 for an empty slot. */
        private val at: IntArray

        init {
            var cap = 8
            while (cap < uids.size * 2) cap = cap shl 1
            keys = IntArray(cap)
            at = IntArray(cap)
            val mask = cap - 1
            for (p in uids.indices) {
                var i = mix(uids[p]) and mask
                while (at[i] != 0) i = (i + 1) and mask
                keys[i] = uids[p]
                at[i] = p + 1
            }
        }

        fun find(uid: Int): Int {
            val mask = keys.size - 1
            var i = mix(uid) and mask
            while (true) {
                val p = at[i]
                if (p == 0) return -1
                if (keys[i] == uid) return p - 1
                i = (i + 1) and mask
            }
        }

        private fun mix(key: Int): Int {
            val h = key * -0x61c88647
            return h xor (h ushr 16)
        }
    }

    companion object {
        /** [cards] as a [CardMap]: itself when it is one, else its entries in its order (a map's keys are each once). */
        fun of(cards: Map<Int, CardInst>): CardMap {
            if (cards is CardMap) return cards
            val ids = IntArray(cards.size)
            val held = arrayOfNulls<CardInst>(cards.size)
            var p = 0
            for ((uid, card) in cards) {
                ids[p] = uid
                held[p] = card
                p++
            }
            @Suppress("UNCHECKED_CAST")
            return CardMap(ids, held as Array<CardInst>, Slots(ids))
        }
    }
}
