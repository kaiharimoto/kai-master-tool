package com.kaiharimoto.mastertool.core.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Saves that land in order, the latest of each key last (1.0.92): a conversation is saved at
 * every turn, and saves launched side by side on a pool of threads could land out of order — an
 * older snapshot written over a newer one. Here one worker writes, one save at a time, each key's
 * newest value only (older ones waiting behind it are dropped, never written after it).
 *
 * [scope] must run on one thread (the app's main one): the waiting saves are kept there, and only
 * [write] goes to [io].
 */
class LatestWrites<K, V>(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val write: (K, V) -> Unit,
) {
    private val waiting = LinkedHashMap<K, V>()
    private var writing: Pair<K, V>? = null
    private var worker: Job? = null

    /** Saves [value] under [key], after whatever is being written now. */
    fun put(key: K, value: V) {
        waiting.remove(key)
        waiting[key] = value
        if (worker == null) worker = scope.launch { drain() }
    }

    /** Whether a value saved under [key] may not be on disk yet: then [pending] is the truth, not the file. */
    fun has(key: K): Boolean = key in waiting || writing?.first == key

    /** The newest value saved under [key] that may not be on disk yet. */
    fun pending(key: K): V? = if (key in waiting) waiting[key] else writing?.takeIf { it.first == key }?.second

    /** Forgets what waits under [key] (it is being deleted); a write already under way still finishes. */
    fun drop(key: K) {
        waiting.remove(key)
    }

    /** Forgets everything waiting. */
    fun dropAll() = waiting.clear()

    /** Waits until everything put so far is written. */
    suspend fun settle() {
        worker?.join()
    }

    private suspend fun drain() {
        try {
            while (waiting.isNotEmpty()) {
                val key = waiting.keys.first()
                // Present, as the first key: a null here is a value (a deletion, say), not a miss.
                @Suppress("UNCHECKED_CAST")
                val value = waiting.remove(key) as V
                writing = key to value
                try {
                    withContext(io) { runCatching { write(key, value) } }
                } finally {
                    writing = null
                }
            }
        } finally {
            worker = null
        }
    }
}
