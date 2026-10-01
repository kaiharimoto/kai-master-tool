package com.kaiharimoto.mastertool.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sync across devices (1.0.68, kai: "Sometimes I'm on the go and I want to have access to everything
 * on the laptop and computer … give users options to use any service they choose. Bring your cloud").
 *
 * Every device keeps its own copy and meets the others in a store anyone already has — a synced
 * folder, a WebDAV server, Google Drive, Dropbox, OneDrive ([SyncStore]). In it are two kinds of file,
 * and no device ever writes one another device writes:
 * - `blobs/<sha-256>`: one item's content at one moment. Named by its hash, so it never changes once
 *   written and the same bytes from two devices are one file.
 * - `devices/<device>.json`: that device's [Manifest] — which version of every item it holds.
 *
 * An item is anything that syncs, by a path of its own (`decks/<id>.json`, `prefs/neue.json`,
 * `ai/MEMORY.md`, `art/<passcode>/<file>`). The newest version of an item is the newest across every
 * device's manifest; each device remembers what it last agreed on ([SyncState]), so it can tell its own
 * edits from everyone else's and decide each item three ways ([SyncPlan]).
 */
@Serializable
data class Version(
    /** The content's SHA-256; empty for a deletion. */
    val hash: String = "",
    /** When it was made, by its device's clock. */
    val at: Long = 0,
    val device: String = "",
    val deleted: Boolean = false,
) {
    /** Later first by time, then by device, so every device picks the same newest. */
    fun newerThan(o: Version): Boolean = at > o.at || (at == o.at && device > o.device)

    /** What it holds: its hash, or null when the item is gone. */
    val content: String? get() = hash.takeUnless { deleted || it.isEmpty() }
}

/** One device's view of every item, written by it alone as `devices/<device>.json`. */
@Serializable
data class Manifest(
    val device: String,
    /** What the person calls the device ("Kai's laptop"), for the copy a conflict keeps. */
    val name: String = "",
    val at: Long = 0,
    val items: Map<String, Version> = emptyMap(),
    val format: Int = FORMAT,
) {
    companion object {
        const val FORMAT = 1
    }
}

/** What this device last agreed with the store: kept on the device, never synced. */
@Serializable
data class SyncState(
    val device: String,
    val items: Map<String, Version> = emptyMap(),
    val lastSync: Long = 0,
)

/** An item as it is on this device now: its hash, when it last changed, and its bytes when asked. */
class LocalItem(val hash: String, val at: Long, val bytes: suspend () -> ByteArray) {
    companion object {
        fun of(bytes: ByteArray, at: Long) = LocalItem(Sha256.hex(bytes), at) { bytes }
    }
}

/**
 * Where the files meet. Names are relative (`devices/x.json`, `blobs/<hash>`); a store puts them under
 * its own folder. [list] gives the names directly in a folder, without the folder's own prefix.
 */
interface SyncStore {
    /** Shown to the person: "Nextcloud · kai", "~/Dropbox/Neue". */
    val label: String

    suspend fun list(folder: String): List<String>
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, bytes: ByteArray)
    suspend fun delete(name: String)
}

/** A store's failure, worded for the person: what went wrong and what to do. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** How a disagreement over one item is settled. */
enum class ConflictRule {
    /** The newer edit wins; the other is kept as a copy beside it (a deck: "Labrynth (from phone)"). */
    KEEP_BOTH,

    /** A JSON document merged key by key against the version both started from; a key both changed goes to the newer. */
    MERGE,

    /** The newer edit wins. */
    NEWER,
}

/** This device's side of a sync: what it holds, and how it takes in what others changed. */
interface SyncLocal {
    /** Every item this device holds, by path. */
    suspend fun snapshot(): Map<String, LocalItem>

    /** Writes [bytes] as the item at [path], or deletes it when null. */
    suspend fun apply(path: String, bytes: ByteArray?)

    /** Keeps the losing side of a conflict beside the winner, as a new item; false where there is no such thing. */
    suspend fun keepCopy(path: String, bytes: ByteArray, from: String): Boolean = false

    fun rule(path: String): ConflictRule = Sync.rule(path)
}

object Sync {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    const val DEVICES = "devices"
    const val BLOBS = "blobs"

    fun rule(path: String): ConflictRule = when {
        path.startsWith("decks/") -> ConflictRule.KEEP_BOTH
        path.startsWith("prefs/") -> ConflictRule.MERGE
        else -> ConflictRule.NEWER
    }

    /** The newest version of every item across every device's manifest. */
    fun latest(manifests: Collection<Manifest>): Map<String, Version> {
        val out = HashMap<String, Version>()
        manifests.forEach { m -> m.items.forEach { (path, v) -> if (out[path]?.let { v.newerThan(it) } != false) out[path] = v } }
        return out
    }
}

/** What one sync did, for the line under Sync now. */
data class SyncReport(
    val sent: Int = 0,
    val received: Int = 0,
    val merged: Int = 0,
    /** Items both sides changed; [copies] of them were kept beside the winner. */
    val conflicts: Int = 0,
    val copies: Int = 0,
    val at: Long = 0,
    /** The other devices that have synced, by name. */
    val devices: List<String> = emptyList(),
) {
    val nothing: Boolean get() = sent + received + merged + conflicts == 0
}
