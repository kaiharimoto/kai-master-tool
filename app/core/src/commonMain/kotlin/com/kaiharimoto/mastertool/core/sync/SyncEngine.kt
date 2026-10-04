package com.kaiharimoto.mastertool.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * One sync (1.0.68): read every device's manifest, decide each item ([SyncPlan]), send blobs before
 * the manifest that names them, take in what others changed, then write this device's manifest and
 * remember what was agreed. A failure part way leaves nothing half-agreed: the state is saved only at
 * the end, and the next sync redoes what did not finish — blobs already sent are simply found there.
 */
class SyncEngine(
    private val store: SyncStore,
    private val local: SyncLocal,
    private val device: String,
    private val deviceName: String,
    private val clock: () -> Long,
    /** The manifests read in earlier syncs with this store, kept by the caller between runs; null reads every one. */
    private val known: ManifestCache? = null,
) {
    suspend fun run(state: SyncState): Pair<SyncState, SyncReport> {
        // A manifest whose content stamp is the one it had when last read is that same manifest (1.0.92).
        val stamps = if (known != null) store.stamps(Sync.DEVICES) else null
        val manifests = store.list(Sync.DEVICES).filter { it.endsWith(".json") }.mapNotNull { name ->
            val stamp = stamps?.get(name)
            known?.get(name, stamp) ?: store.read("${Sync.DEVICES}/$name")?.let { bytes ->
                runCatching { Sync.json.decodeFromString(Manifest.serializer(), bytes.decodeToString()) }.getOrNull()
                    ?.also { known?.put(name, stamp, it) }
            }
        }
        val names = manifests.associate { it.device to it.name.ifBlank { "another device" } }
        val remote = Sync.latest(manifests)
        val snapshot = local.snapshot()
        val steps = SyncPlan.plan(state.items, snapshot.mapValues { (_, v) -> SyncPlan.LocalMeta(v.hash, v.at) }, remote)

        // The store's blobs, listed only when something is to be sent or merged (1.0.92): a sync with
        // nothing new here never needs to know them.
        var listed: HashSet<String>? = null
        suspend fun blobs(): HashSet<String> = listed ?: store.list(Sync.BLOBS).toHashSet().also { listed = it }
        val agreed = HashMap(state.items)
        var sent = 0
        var received = 0
        var merged = 0
        var conflicts = 0
        var copies = 0

        suspend fun send(path: String, bytes: ByteArray, at: Long): Version {
            val hash = Sha256.hex(bytes)
            val there = blobs()
            if (hash !in there) {
                store.write("${Sync.BLOBS}/$hash", bytes)
                there += hash
            }
            sent++
            return Version(hash, at, device)
        }

        suspend fun fetch(v: Version): ByteArray {
            val bytes = store.read("${Sync.BLOBS}/${v.hash}") ?: throw SyncException("A file another device sent is missing from the store. Sync that device again, then this one.")
            if (Sha256.hex(bytes) != v.hash) throw SyncException("A file in the store did not arrive whole. Try again in a moment.")
            return bytes
        }

        suspend fun take(path: String, v: Version) {
            local.apply(path, if (v.content == null) null else fetch(v))
            received++
            agreed[path] = v
        }

        for (step in steps) {
            when (step) {
                is SyncPlan.Step.Agree -> agreed[step.path] = step.version
                is SyncPlan.Step.Send -> agreed[step.path] = send(step.path, snapshot.getValue(step.path).bytes(), step.at)
                is SyncPlan.Step.Tombstone -> {
                    agreed[step.path] = Version("", clock(), device, deleted = true)
                    sent++
                }
                is SyncPlan.Step.Take -> take(step.path, step.version)
                is SyncPlan.Step.Conflict -> {
                    conflicts++
                    val mine = snapshot.getValue(step.path).bytes()
                    val theirs = fetch(step.remote)
                    when (local.rule(step.path)) {
                        ConflictRule.MERGE -> {
                            val base = step.base?.takeIf { it.content != null && it.hash in blobs() }?.let { runCatching { fetch(it) }.getOrNull() }
                            val result = merge(base, mine, theirs, step.localWins)
                            if (result == null) {
                                // Not JSON on one side: the newer wins whole.
                                if (step.localWins) agreed[step.path] = send(step.path, mine, step.local!!.at) else take(step.path, step.remote)
                            } else if (result.contentEquals(theirs)) {
                                take(step.path, step.remote)
                            } else {
                                local.apply(step.path, result)
                                agreed[step.path] = send(step.path, result, maxOf(clock(), step.remote.at + 1))
                                merged++
                            }
                        }
                        ConflictRule.KEEP_BOTH -> {
                            if (step.localWins) {
                                if (local.keepCopy(step.path, theirs, names[step.remote.device] ?: "another device")) copies++
                                agreed[step.path] = send(step.path, mine, step.local!!.at)
                            } else {
                                if (local.keepCopy(step.path, mine, deviceName)) copies++
                                take(step.path, step.remote)
                            }
                        }
                        ConflictRule.NEWER -> if (step.localWins) agreed[step.path] = send(step.path, mine, step.local!!.at) else take(step.path, step.remote)
                    }
                }
            }
        }

        val now = clock()
        val manifest = Manifest(device, deviceName, now, agreed)
        val mine = manifests.firstOrNull { it.device == device }
        if (mine == null || mine.items != agreed || mine.name != deviceName) {
            store.write("${Sync.DEVICES}/$device.json", Sync.json.encodeToString(Manifest.serializer(), manifest).encodeToByteArray())
        }
        val report = SyncReport(sent, received, merged, conflicts, copies, now, manifests.filter { it.device != device }.map { it.name.ifBlank { "another device" } })
        return SyncState(device, agreed, now) to report
    }

    private fun merge(base: ByteArray?, mine: ByteArray, theirs: ByteArray, localNewer: Boolean): ByteArray? {
        fun obj(b: ByteArray?) = b?.let { runCatching { Sync.json.parseToJsonElement(it.decodeToString()) as? JsonObject }.getOrNull() }
        val l = obj(mine) ?: return null
        val r = obj(theirs) ?: return null
        val merged = JsonMerge.threeWay(obj(base), l, r, localNewer)
        return Sync.json.encodeToString(JsonObject.serializer(), merged).encodeToByteArray()
    }
}

/**
 * The device manifests read in earlier syncs with one store, by name, each with the content stamp it
 * was read under ([SyncStore.stamps]). One sync at a time uses it; a manifest with no stamp is never kept.
 */
class ManifestCache {
    private val known = HashMap<String, Pair<String, Manifest>>()

    /** The manifest [name] held when its stamp was [stamp], or null when it is not known so. */
    fun get(name: String, stamp: String?): Manifest? = stamp?.let { s -> known[name]?.takeIf { it.first == s }?.second }

    fun put(name: String, stamp: String?, manifest: Manifest) {
        if (stamp == null) known.remove(name) else known[name] = stamp to manifest
    }
}
