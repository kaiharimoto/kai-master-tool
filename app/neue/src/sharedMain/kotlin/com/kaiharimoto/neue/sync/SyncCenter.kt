package com.kaiharimoto.neue.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.sync.Cloud
import com.kaiharimoto.mastertool.core.sync.CloudSignIn
import com.kaiharimoto.mastertool.core.sync.CloudTokens
import com.kaiharimoto.mastertool.core.sync.GoogleDriveStore
import com.kaiharimoto.mastertool.core.sync.Sync
import com.kaiharimoto.mastertool.core.sync.SyncEngine
import com.kaiharimoto.mastertool.core.sync.SyncException
import com.kaiharimoto.mastertool.core.sync.SyncPrefs
import com.kaiharimoto.mastertool.core.sync.SyncReport
import com.kaiharimoto.mastertool.core.sync.SyncState
import com.kaiharimoto.mastertool.core.sync.SyncStore
import com.kaiharimoto.mastertool.core.sync.WebDavStore
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.SecretStore
import com.kaiharimoto.neue.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sync, for the app's lifetime (1.0.68): where this device syncs to (`NeuePreferences.sync`, with any
 * password or token in `SecretStore`), one sync at a time, what the last one did, and the sign-in to the
 * clouds. Settings › Sync is its face; it runs by itself on opening, a little after anything is saved,
 * and every few minutes while the app is open.
 */
class SyncCenter(private val h: NeueHolders) {
    private val dir = File(Platform.dataDir, "sync")
    private val stateFile = File(dir, "state.json")
    private val seen = SeenTimes(File(dir, "seen.json")) { h.deps.now() }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http by lazy { com.kaiharimoto.mastertool.core.remote.HttpClientFactory.create() }

    var running by mutableStateOf(false)
        private set
    var last by mutableStateOf<SyncReport?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** The cloud being signed in to, while the browser is out. */
    var signingIn by mutableStateOf<Cloud?>(null)
        private set

    val prefs: SyncPrefs get() = h.neue.prefs.sync
    val on: Boolean get() = prefs.service != SyncPrefs.OFF && configured(prefs)

    /** What Ai is called here, for Settings' words. */
    val aiName: String get() = h.neue.prefs.ai.name

    /** What the other devices call this one. */
    val deviceName: String get() = prefs.deviceName.ifBlank { SyncPlatform.deviceName }

    /** Whether [p] says enough to sync: a folder chosen, a server given, a cloud signed in. */
    fun configured(p: SyncPrefs): Boolean = when (p.service) {
        SyncPrefs.FOLDER -> !p.folder.isNullOrBlank()
        SyncPrefs.WEBDAV -> p.webdavUrl.isNotBlank() && SecretStore.get(WEBDAV_KEY) != null
        else -> Cloud.of(p.service)?.let { SecretStore.get(key(it)) != null } ?: false
    }

    fun update(change: (SyncPrefs) -> SyncPrefs) = h.neue.update { it.copy(sync = change(it.sync)) }

    /** Sync now, unless one is running; [quiet] keeps a failure off the screen's note (the automatic ones). */
    fun syncNow(quiet: Boolean = false) {
        if (!on || lock.isLocked) return
        scope.launch { run(quiet) }
    }

    private suspend fun run(quiet: Boolean) = lock.withLock {
        running = true
        try {
            settleOpenDeck()
            val store = store() ?: return@withLock
            val state = readState()
            val local = NeueSyncLocal(h, seen)
            val (next, report) = SyncEngine(store, local, state.device, deviceName) { h.deps.now() }.run(state)
            writeState(next)
            seen.save()
            withContext(Dispatchers.Main) { reload(local.changed) }
            last = report
            problem = null
        } catch (e: SyncException) {
            problem = e.message
            if (!quiet) h.neue.note = com.kaiharimoto.neue.Note(e.message ?: "Sync could not finish")
        } catch (e: Exception) {
            problem = "Sync could not finish: ${e.message ?: e::class.simpleName}"
            if (!quiet) h.neue.note = com.kaiharimoto.neue.Note("Sync could not finish")
        } finally {
            running = false
        }
    }

    /** The open deck's edits saved first, when the person saves automatically, so they travel too. */
    private suspend fun settleOpenDeck() {
        val b = h.builder
        if (!b.dirty || b.deckId == null || !h.neue.prefs.autoSave) return
        withContext(Dispatchers.Main) { b.save(quiet = true) }
        repeat(30) { if (!b.dirty) return; delay(100) }
    }

    /** The screens that show what came in, told once. */
    private fun reload(changed: Set<String>) {
        if (changed.isEmpty()) return
        if ("decks" in changed) {
            h.decksReload++
            val open = h.builder.deckId
            if (open != null && "deck:$open" in changed && !h.builder.dirty) h.builder.load(open)
        }
        if ("ai" in changed) h.ai.bookChanged()
        if ("art" in changed) h.customArt.reload()
        if ("present" in changed) h.present.reload()
        if ("replays" in changed) { h.duel.loadReplays(); h.duel.reloadRulings() }
    }

    /** The store [prefs] names, or null when there is none to sync with. */
    private suspend fun store(p: SyncPrefs = prefs): SyncStore? = when (p.service) {
        SyncPrefs.FOLDER -> p.folder?.takeIf { it.isNotBlank() }?.let { SyncPlatform.folderStore(it) }
        SyncPrefs.WEBDAV -> SecretStore.get(WEBDAV_KEY)?.let { WebDavStore(http, p.webdavUrl, p.webdavUser, it, label = "WebDAV · ${host(p.webdavUrl)}") }
        else -> Cloud.of(p.service)?.let { cloud ->
            val token: suspend () -> String = { access(cloud) }
            when (cloud) {
                Cloud.GOOGLE_DRIVE -> GoogleDriveStore(http, token, p.account)
            }
        }
    }

    /** A current access token for [cloud], refreshed and kept when it has run out. */
    private suspend fun access(cloud: Cloud): String {
        val t = tokens(cloud) ?: throw SyncException("Sign in to ${cloud.label} in Settings › Sync.")
        if (t.access.isNotBlank() && t.expiresAt > h.deps.now()) return t.access
        val fresh = CloudSignIn.refresh(http, cloud, t, h.deps.now())
        SecretStore.put(key(cloud), Sync.json.encodeToString(CloudTokens.serializer(), fresh))
        return fresh.access
    }

    private fun tokens(cloud: Cloud): CloudTokens? = SecretStore.get(key(cloud))?.let { runCatching { Sync.json.decodeFromString(CloudTokens.serializer(), it) }.getOrNull() }

    // ---- choosing where -------------------------------------------------------------------------

    fun turnOff() = update { it.copy(service = SyncPrefs.OFF) }

    /** A folder chosen with the system's chooser, then synced with. */
    fun chooseFolder() {
        scope.launch {
            val folder = withContext(Dispatchers.Main) { SyncPlatform.pickFolder() } ?: return@launch
            withContext(Dispatchers.Main) { update { it.copy(service = SyncPrefs.FOLDER, folder = folder) } }
            problem = null
            syncNow()
        }
    }

    /** A WebDAV server, signed in to and read before it is kept; [done] hears the problem, or null. */
    fun connectWebDav(url: String, user: String, password: String, done: (String?) -> Unit) {
        scope.launch {
            val store = WebDavStore(http, url, user, password)
            val failed = runCatching { store.test() }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (failed == null) {
                    SecretStore.put(WEBDAV_KEY, password)
                    update { it.copy(service = SyncPrefs.WEBDAV, webdavUrl = url.trim(), webdavUser = user.trim()) }
                    problem = null
                    syncNow()
                }
                done(failed?.let { it.message ?: "The server could not be reached." })
            }
        }
    }

    /**
     * Signing in to [cloud]: the browser opens on the service's own page, and the app waits on
     * `localhost` for it to come back. On a phone the page that says so has a button back to the app.
     */
    fun signIn(cloud: Cloud) {
        if (!cloud.ready || signingIn != null) return
        signingIn = cloud
        // Out of sight while the browser is up: on Android the listener must not be frozen (kai, 1.0.87: "it just loads
        // endlessly forever").
        Platform.keepAwake(SIGN_IN, true, "Signing in to ${cloud.label}", "Waiting for the browser")
        scope.launch {
            try {
                val verifier = SyncPlatform.random(64)
                val state = SyncPlatform.random(24)
                val back = if (Platform.os == com.kaiharimoto.mastertool.core.update.DesktopOs.ANDROID) RETURN else null
                val query = kotlinx.coroutines.coroutineScope {
                    val reply = async { Loopback.await(page = Loopback.page(cloud.label, back)) }
                    // The listener is up before the browser is sent anywhere.
                    delay(150)
                    withContext(Dispatchers.Main) { Platform.browse(CloudSignIn.authorizeUrl(cloud, verifier, state)) }
                    reply.await()
                }
                val code = CloudSignIn.codeFrom(query, state).getOrThrow()
                val t = CloudSignIn.exchange(http, cloud, code, verifier, h.deps.now())
                val account = CloudSignIn.account(http, cloud, t.access)
                SecretStore.put(key(cloud), Sync.json.encodeToString(CloudTokens.serializer(), t.copy(account = account)))
                withContext(Dispatchers.Main) { update { it.copy(service = cloud.id, account = account) } }
                problem = null
                syncNow()
            } catch (e: SyncException) {
                problem = e.message
            } catch (e: Exception) {
                problem = "Signing in to ${cloud.label} did not finish: ${e.message ?: e::class.simpleName}"
            } finally {
                signingIn = null
                Platform.keepAwake(SIGN_IN, false, "", "")
            }
        }
    }

    /** Signs this device out of [cloud]: its token forgotten here; what is in the cloud stays. */
    fun signOut(cloud: Cloud) {
        SecretStore.remove(key(cloud))
        update { if (it.service == cloud.id) it.copy(service = SyncPrefs.OFF, account = "") else it }
    }

    // ---- this device's state ---------------------------------------------------------------------

    private fun readState(): SyncState {
        val stored = runCatching { Sync.json.decodeFromString(SyncState.serializer(), stateFile.readText()) }.getOrNull()
        val where = place(prefs)
        // Each place is met afresh: a device moved to another store has agreed nothing with it yet.
        return if (stored != null && stored.device.isNotBlank() && placeFile().readTextOrNull() == where) stored
        else SyncState(stored?.device?.takeIf { it.isNotBlank() } ?: newDevice(), lastSync = 0)
    }

    private fun writeState(state: SyncState) {
        dir.mkdirs()
        stateFile.writeText(Sync.json.encodeToString(SyncState.serializer(), state))
        placeFile().writeText(place(prefs))
    }

    private fun placeFile() = File(dir, "place.txt")
    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()

    /** Where this device syncs, as one line: a different line is a different store. */
    private fun place(p: SyncPrefs) = when (p.service) {
        SyncPrefs.FOLDER -> "folder:${p.folder}"
        SyncPrefs.WEBDAV -> "webdav:${p.webdavUrl}|${p.webdavUser}"
        else -> "${p.service}:${p.account}"
    }

    private fun newDevice() = "d" + SyncPlatform.random(12).lowercase().filter { it.isLetterOrDigit() }.padEnd(8, '0')

    private fun host(url: String) = url.substringAfter("://").substringBefore('/')

    companion object {
        const val WEBDAV_KEY = "sync:webdav"

        /** Where a phone's browser hands back to the app once signed in (`MainActivity`'s intent filter). */
        const val RETURN = "neuemastertool://signed-in"
        private const val SIGN_IN = "sign-in"

        /** How often the app syncs by itself while it is open. */
        const val EVERY_MS = 3 * 60_000L

        fun key(cloud: Cloud) = "sync:${cloud.id}"
    }
}
