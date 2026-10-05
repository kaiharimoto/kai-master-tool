package com.kaiharimoto.neue.world

import java.io.File

/**
 * A folder of the app's that every world sees under [prefix] (Phase D step 2: the effects library, `<data>/effects/`, at
 * `lib/effects/`). [Worlds] routes a path under the prefix here — read, listed, written, deleted — so no world keeps a copy
 * of its own, and a write lands where the app reads it.
 *
 * The seam for whoever owns the folder: [writable] and [refuse] say what may be written (the effects library: a card's
 * source or a helper; the asked list's check for Ai's writes is [refuse]'s), and [changed] hears every write and delete,
 * returning a line for the terminal and the writer ("Compiled: 2 effects, 1 warning").
 */
interface WorldMount {
    /** "lib/effects/": a world path under it is this mount's. */
    val prefix: String

    /** Where its files live. */
    val dir: File

    /** Whether [rel] (the path under [prefix]) may be read through a world. */
    fun readable(rel: String): Boolean

    /** Whether [rel] may be written or deleted through a world, by anyone. */
    fun writable(rel: String): Boolean

    /** Why [by] may not write [rel] now, in words, or null when they may (checked after [writable]). */
    fun refuse(rel: String, by: String): String? = null

    /** What a world lists of it: the paths under [prefix], sorted. */
    fun listed(): List<String>

    /** A write ([deleted] false) or a delete of [rel] by [by] has landed: a line for the writer, or null. */
    suspend fun changed(rel: String, by: String, deleted: Boolean): String? = null
}
