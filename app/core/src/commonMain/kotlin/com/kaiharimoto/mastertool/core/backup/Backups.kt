package com.kaiharimoto.mastertool.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Everything the person made, in one file (1.0.69, kai: "I have a lot of progress in my current version
 * on desktop that might be outdated or be outdated by changes we make in the future, how do we account
 * for that?").
 *
 * A backup is a `.nmtbackup` zip of the person's work read through the app, not a copy of its database —
 * decks, every settings document, Ai's folder but its keys, their own card pictures — so it restores into
 * any later version whatever its tables look like. One is made by itself the first time a new version
 * opens (before it changes anything), once a week, and before a restore; the newest [KEEP] are kept in
 * `<data>/backups`. Export hands one to the person; Restore reads one back, keeping whatever was made since.
 */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    /** The app and version that wrote it. */
    val app: String = "Neue Master Tool",
    val version: String = "",
    val at: Long = 0,
    /** Why it was made, in words: "Before 1.0.69", "Weekly", "Before restoring", "By hand". */
    val reason: String = "",
    val device: String = "",
    val decks: Int = 0,
    val files: Int = 0,
) {
    companion object {
        const val FORMAT = 1
        const val NAME = "manifest.json"
    }
}

/** One deck as a backup keeps it: everything the library has, its id and times included. */
@Serializable
data class BackupDeck(
    val id: String,
    val name: String,
    val main: List<Int> = emptyList(),
    val extra: List<Int> = emptyList(),
    val side: List<Int> = emptyList(),
    val notes: String = "",
    val extended: JsonObject? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

object Backups {
    const val EXTENSION = "nmtbackup"
    const val KEEP = 10

    /** A week, in milliseconds: how often one is made while the app is used. */
    const val WEEK = 7L * 24 * 60 * 60 * 1000

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** The file's name: when, and why, readable in a folder listing and sorted by time. */
    fun fileName(stamp: String, reason: String): String {
        val why = reason.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else ' ' }.joinToString("").trim().replace(Regex(" +"), " ")
        return "$stamp $why.$EXTENSION"
    }

    /** Which of [names] (sorted oldest first by their stamp) to delete so [KEEP] remain. */
    fun toDelete(names: List<String>, keep: Int = KEEP): List<String> = names.sorted().dropLast(keep)

    /**
     * Whether a backup is due as the app opens: the first time a version opens over another's data
     * ([lastVersion] differs and there is something to keep), or a week since the last.
     */
    fun due(lastVersion: String?, current: String, lastAt: Long?, now: Long, hasData: Boolean): String? = when {
        !hasData -> null
        lastVersion != null && lastVersion != current -> "Before $current (from $lastVersion)"
        lastVersion == null -> "Before $current"
        lastAt == null || now - lastAt >= WEEK -> "Weekly"
        else -> null
    }
}
