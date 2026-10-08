package com.kaiharimoto.mastertool.core.duel

import kotlin.concurrent.Volatile

/**
 * A map keyed by `Int`, open-addressed in arrays and never changed once built (2026-10, the engine's speed, M.md §8): a
 * look-up boxes no key and walks no nodes, where a `HashMap<Int, …>` did both on every card a move read — a passcode or a
 * uid past 127 is a new `Integer` each time. [IntMemo] grows one a key at a time by copying it, so threads that share it
 * read it safely; [Builder] fills one in a pass (a table's place index).
 */
internal class IntTable<V> private constructor(
    private val keys: IntArray,
    private val values: Array<Any?>,
    private val used: BooleanArray,
    /** How many keys it holds. */
    val size: Int,
) {
    /** The slot holding [key], or -1 when it holds none. */
    fun slot(key: Int): Int {
        val mask = keys.size - 1
        var i = mix(key) and mask
        while (used[i]) {
            if (keys[i] == key) return i
            i = (i + 1) and mask
        }
        return -1
    }

    /** The value in a slot [slot] found. */
    @Suppress("UNCHECKED_CAST")
    fun valueAt(slot: Int): V = values[slot] as V

    /** [key]'s value, or null when it holds none. */
    operator fun get(key: Int): V? {
        val i = slot(key)
        return if (i < 0) null else valueAt(i)
    }

    /** A new table with [key] set to [value] beside every key of this one. */
    fun plus(key: Int, value: V): IntTable<V> {
        val b = Builder<V>(size + 1)
        for (i in keys.indices) if (used[i]) b.put(keys[i], valueAt(i))
        b.put(key, value)
        return b.build()
    }

    /** Fills a table once, sized for [expected] keys (it grows past them): each key kept as first put ([putIfAbsent]) or last ([put]). */
    class Builder<V>(expected: Int) {
        private var keys = IntArray(capacity(expected))
        private var values = arrayOfNulls<Any?>(keys.size)
        private var used = BooleanArray(keys.size)
        private var size = 0

        private fun find(key: Int): Int {
            val mask = keys.size - 1
            var i = mix(key) and mask
            while (used[i] && keys[i] != key) i = (i + 1) and mask
            return i
        }

        /** The slot for a new [key], free at [free] unless the table must grow first (it is half full). */
        private fun claim(key: Int, free: Int): Int {
            val i = if (size + 1 > keys.size / 2) {
                grow()
                find(key)
            } else free
            used[i] = true
            keys[i] = key
            size++
            return i
        }

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            val oldUsed = used
            keys = IntArray(oldKeys.size * 2)
            values = arrayOfNulls(keys.size)
            used = BooleanArray(keys.size)
            for (j in oldKeys.indices) if (oldUsed[j]) {
                val i = find(oldKeys[j])
                used[i] = true
                keys[i] = oldKeys[j]
                values[i] = oldValues[j]
            }
        }

        // The slot is claimed before [values] is read: claiming may grow the table, and with it [values].
        fun put(key: Int, value: V) {
            var i = find(key)
            if (!used[i]) i = claim(key, i)
            values[i] = value
        }

        fun putIfAbsent(key: Int, value: V) {
            var i = find(key)
            if (used[i]) return
            i = claim(key, i)
            values[i] = value
        }

        fun build(): IntTable<V> = IntTable(keys, values, used, size)
    }

    companion object {
        private val NONE = IntTable<Any?>(IntArray(1), arrayOfNulls(1), BooleanArray(1), 0)

        @Suppress("UNCHECKED_CAST")
        fun <V> empty(): IntTable<V> = NONE as IntTable<V>

        /** A power of two at least twice [n]: the table is never more than half full. */
        private fun capacity(n: Int): Int {
            var c = 8
            while (c < n * 2) c = c shl 1
            return c
        }

        /** Spreads a key's bits over the table (Fibonacci hashing): uids and passcodes run in steps. */
        private fun mix(key: Int): Int {
            val h = key * -0x61c88647
            return h xor (h ushr 16)
        }
    }
}

/**
 * [compute] remembered a key at a time, a miss too, in an [IntTable] replaced whole on every miss and never changed in
 * place: threads that share one read it safely, and two that miss at once each work the same answer out.
 */
internal class IntMemo<V>(private val compute: (Int) -> V) {
    @Volatile
    private var table: IntTable<V> = IntTable.empty()

    operator fun get(key: Int): V {
        val now = table
        val i = now.slot(key)
        if (i >= 0) return now.valueAt(i)
        val v = compute(key)
        table = now.plus(key, v)
        return v
    }
}
