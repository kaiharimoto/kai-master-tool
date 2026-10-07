package com.kaiharimoto.mastertool.core.sync

/**
 * What to do with each item, decided three ways (1.0.68): what this device last agreed on (the base),
 * what it holds now, and the newest version any device has published. Pure, so every case is a test.
 *
 * - Neither side changed it: nothing.
 * - Only this device changed it: send (or publish its deletion).
 * - Only another device changed it: take theirs (or delete it here).
 * - Both changed it to the same thing: agree.
 * - Both changed it differently: a conflict, settled by the item's [ConflictRule]. An edit always beats
 *   a deletion, so nothing anyone wrote is lost because someone else removed it.
 */
object SyncPlan {
    sealed interface Step {
        val path: String

        /** This device's version goes out. */
        data class Send(override val path: String, val hash: String, val at: Long) : Step

        /** This device deleted it; the deletion goes out. */
        data class Tombstone(override val path: String) : Step

        /** Another device's version comes in ([version] is a deletion when it removed the item). */
        data class Take(override val path: String, val version: Version) : Step

        /** Both sides hold the same; [version] is what to remember. */
        data class Agree(override val path: String, val version: Version) : Step

        /** Both changed it differently: [localWins] by the rule of the newer edit. */
        data class Conflict(override val path: String, val local: LocalMeta?, val remote: Version, val base: Version?, val localWins: Boolean) : Step
    }

    /** An item here: its hash and when it changed. */
    data class LocalMeta(val hash: String, val at: Long)

    fun plan(base: Map<String, Version>, local: Map<String, LocalMeta>, remote: Map<String, Version>): List<Step> {
        val paths = (base.keys + local.keys + remote.keys).sorted()
        return paths.mapNotNull { path -> step(path, base[path], local[path], remote[path]) }
    }

    private fun step(path: String, b: Version?, l: LocalMeta?, r: Version?): Step? {
        val baseHash = b?.content
        val localHash = l?.hash
        val remoteHash = r?.content
        if (localHash == remoteHash) {
            // The same on both sides: remember it (a deletion everyone agrees on is remembered as one).
            return when {
                r != null -> if (b == r) null else Step.Agree(path, r)
                localHash != null -> Step.Send(path, localHash, l.at)
                else -> null
            }
        }
        val localChanged = localHash != baseHash
        // An item no device has published, or a store emptied since: what this device holds goes out.
        if (r == null) return if (localHash != null) Step.Send(path, localHash, l.at) else null
        val remoteChanged = remoteHash != baseHash || b == null
        return when {
            localChanged && !remoteChanged -> if (localHash != null) Step.Send(path, localHash, l.at) else Step.Tombstone(path)
            !localChanged -> Step.Take(path, r)
            // An edit beats a deletion, whichever came later.
            localHash == null -> Step.Take(path, r)
            remoteHash == null -> Step.Send(path, localHash, l.at)
            // A device meeting the store for the first time takes what is there: its own settings
            // and notes are fresh defaults far more often than work, and a deck's loser is kept anyway.
            else -> Step.Conflict(path, l, r, b, localWins = b != null && l.at > r.at)
        }
    }
}
