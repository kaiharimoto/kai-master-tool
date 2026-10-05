package com.kaiharimoto.neue.world.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.world.WorldApi
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.apps.AppCall
import com.kaiharimoto.mastertool.core.world.apps.AppCode
import com.kaiharimoto.mastertool.core.world.apps.AppCodec
import com.kaiharimoto.mastertool.core.world.apps.AppEvents
import com.kaiharimoto.mastertool.core.world.apps.AppLimits
import com.kaiharimoto.mastertool.core.world.apps.AppManifest
import com.kaiharimoto.mastertool.core.world.apps.AppPaths
import com.kaiharimoto.mastertool.core.world.apps.AppRunner
import com.kaiharimoto.mastertool.core.world.apps.AppStore
import com.kaiharimoto.mastertool.core.world.apps.JsApp
import com.kaiharimoto.mastertool.core.world.apps.LiveThrottle
import com.kaiharimoto.mastertool.core.world.apps.Offered
import com.kaiharimoto.mastertool.core.world.apps.StateRead
import com.kaiharimoto.mastertool.core.world.apps.UiEvent
import com.kaiharimoto.mastertool.core.world.apps.UiTree
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.neue.world.Worlds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.Executors

/**
 * What the desktop answers for what runs inside its windows (`docs/world/DESKTOP.md` §13): a window opened or brought
 * forward for [by] (Ai's through the focus policy and the avatar, never past it), closed, or asked about. [DeskWindows]
 * is the desktop's; a test may set its own.
 */
interface WorldWindows {
    fun open(app: AppRef, by: String)
    fun close(app: AppRef) = Unit
    fun isOpen(app: AppRef): Boolean = false
}

/** The windows of the World's desktop (`WorldDeskState`): the person's open at once; Ai's arrive as the focus policy decides. */
class DeskWindows(private val world: Worlds) : WorldWindows {
    override fun open(app: AppRef, by: String) {
        if (by == WorldEvent.AI) {
            val does = when (app) {
                is AppRef.Made -> AiDoes.OpenApp(app.slug, world.apps.manifest(app.slug)?.title ?: app.slug)
                is AppRef.BuiltIn -> AiDoes.Read(app, app.kind.title)
            }
            world.desk.arriveNow(app, does)
        } else {
            world.desk.open(app)
        }
    }

    override fun close(app: AppRef) {
        if (world.desk.desk.isOpen(app.key)) world.desk.close(app.key)
    }

    override fun isOpen(app: AppRef): Boolean = world.desk.desk.isOpen(app.key)
}

/**
 * One app's live window (§8.5): its manifest and code, the state, the screen its `view` last drew, and what went wrong.
 * Read and changed on the main thread only; the app's calls run on [WorldApps]' own threads and come back here.
 */
class AppHost internal constructor(val slug: String) {
    var manifest by mutableStateOf<AppManifest?>(null)
        internal set

    /** The screen, or null before the first view. */
    var tree by mutableStateOf<UiTree?>(null)
        internal set

    /** The last call that threw, stopped or ran past a limit: one line over the app, with its line in `main.js`. */
    var failure by mutableStateOf<AppCall.Failed?>(null)
        internal set

    /** The last `view` failed: the last good screen stays, at 45 %. */
    var stale by mutableStateOf(false)
        internal set

    /** An `on` has run past [AppLimits.BUSY_MS]: the breathing square in the title bar; the old screen stays. */
    var busy by mutableStateOf(false)
        internal set

    /** A line the window shows once: events dropped, a state set aside. */
    var note by mutableStateOf<String?>(null)

    /** A new version's `view` threw on the old state: the window offers Start fresh (§8.3). */
    var offerFresh by mutableStateOf(false)
        internal set

    /** There is no such app (deleted, or a sync took it away). */
    var missing by mutableStateOf(false)
        internal set

    /** Events this window has answered, for tests and the studio. */
    var answered by mutableStateOf(0)
        internal set

    internal var state: String = "{}"
    internal var code: AppCode? = null
    internal val events = AppEvents()
    internal val live = LiveThrottle()
    internal var pumping = false
    internal var started = false
    internal var by: String = WorldEvent.YOU
    internal var save: Job? = null

    /** Nothing is waiting or running. */
    val idle: Boolean get() = !pumping && events.size == 0
}

/**
 * Ai's apps in the open world (§8), an owned part of [Worlds] (`world.apps`): the registry the desktop's windows read
 * (manifests, a [AppHost] per open app), and every change through [AppStore], logged as a [WorldEvent] of kind `app`.
 *
 * Calls run off the frame thread on the apps' own two threads, apart from the world's runs, so a 30-second study never
 * freezes a calculator; one call at a time per app ([AppEvents]); the state is written 500 ms after the last event.
 */
class WorldApps internal constructor(private val world: Worlds, private val runner: AppRunner = JsApp()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** For the window's menus: work that outlives the menu that asked for it. */
    internal val scopeForUi: CoroutineScope get() = scope
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "world-apps").apply { isDaemon = true } }.asCoroutineDispatcher()

    /** The open world's apps, by the order they were made. */
    var list by mutableStateOf<List<AppManifest>>(emptyList())
        private set

    private val hosts = mutableStateMapOf<String, AppHost>()

    /** The desktop's windows. */
    var windows: WorldWindows = DeskWindows(world)

    /** The instrument the Instruments app shows next (a page's *Run it in Instruments*). */
    var instrumentsPick by mutableStateOf<String?>(null)

    /** Apps the person has not opened since Ai made them: the desktop's `NEW` tag. */
    var unopened by mutableStateOf<Set<String>>(emptySet())
        private set

    /** What threw since Ai last read the world (§8.5: "the notice goes to Ai at its next turn"). */
    private val unheard = ArrayList<String>()

    private var folder: File? = null

    fun manifest(slug: String): AppManifest? = list.firstOrNull { it.slug == slug }

    internal fun store(): AppStore? = folder?.let(::AppStore)

    /** The apps of the world in [dir] (its `apps/`): read off the frame thread; every window of the world before closed. */
    internal fun load(dir: File?) {
        flushAll()
        hosts.clear()
        folder = dir
        list = emptyList()
        unopened = emptySet()
        if (dir == null) return
        scope.launch {
            val read = withContext(Dispatchers.IO) { AppStore(dir).list() }
            if (folder == dir) list = read
        }
    }

    /** After a sync or a restore: the list read again, and every open window reloaded from disk. */
    internal fun reload() {
        val dir = folder ?: return
        scope.launch {
            val read = withContext(Dispatchers.IO) { AppStore(dir).list() }
            if (folder != dir) return@launch
            list = read
            hosts.values.toList().forEach { h ->
                val m = read.firstOrNull { it.slug == h.slug }
                if (m == null) {
                    h.missing = true
                } else if (m.version != h.manifest?.version) {
                    reloadCode(h, h.manifest?.version ?: m.version)
                }
            }
        }
    }

    /** [slug]'s live window, started the first time it is asked for. */
    fun host(slug: String): AppHost {
        val h = hosts.getOrPut(slug) { AppHost(slug) }
        if (!h.started) {
            h.started = true
            scope.launch { start(h) }
        }
        return h
    }

    /** The window of [slug] is shown: its `NEW` tag goes. */
    fun opened(slug: String) {
        if (slug in unopened) unopened = unopened - slug
    }

    /** The window closed: its state written now, its calls stopped, its live state let go. */
    fun closed(slug: String) {
        val h = hosts.remove(slug) ?: return
        runner.stop(slug)
        flush(h)
    }

    private suspend fun snapshot(): WorldHost {
        val now = System.currentTimeMillis()
        cached?.let { (at, host) -> if (now - at < SNAPSHOT_MS) return host }
        val read = world.hostForApps()
        cached = now to read
        return read
    }

    private var cached: Pair<Long, WorldHost>? = null

    private suspend fun start(h: AppHost) {
        val store = store() ?: run { h.missing = true; return }
        val read = withContext(Dispatchers.IO) { Triple(store.manifest(h.slug), store.appCode(h.slug), store.readState(h.slug)) }
        val (m, code, state) = read
        if (m == null || code == null) {
            h.missing = true
            return
        }
        h.manifest = m
        h.code = code
        when (state) {
            is StateRead.Ok -> h.state = state.json
            is StateRead.Fresh -> if (!init(h)) return
            is StateRead.Broken -> {
                h.note = "The saved state would not read (${state.why}): it is kept as state.broken.json, and the app started afresh."
                if (!init(h)) return
            }
        }
        render(h)
        if (h.events.size > 0) pump(h)
    }

    private suspend fun init(h: AppHost): Boolean {
        val code = h.code ?: return false
        return when (val r = withContext(pool) { runner.init(code, snapshot()) }) {
            is AppCall.Ok -> {
                h.state = r.value
                true
            }
            is AppCall.Failed -> {
                threw(h, r)
                false
            }
        }
    }

    /** The screen for the state as it stands; a view that throws keeps the last good screen at 45 %. */
    private suspend fun render(h: AppHost): Boolean {
        val code = h.code ?: return false
        return when (val r = withContext(pool) { runner.view(code, h.state, snapshot()) }) {
            is AppCall.Ok -> {
                h.tree = r.value
                h.stale = false
                true
            }
            is AppCall.Failed -> {
                h.stale = h.tree != null
                threw(h, r)
                false
            }
        }
    }

    private fun threw(h: AppHost, f: AppCall.Failed) {
        h.failure = f
        val m = h.manifest ?: return
        val store = store() ?: return
        val e = store.threw(m, f, System.currentTimeMillis())
        world.log(e)
        synchronized(unheard) {
            unheard += "${m.title} (${m.slug}): ${f.words}"
            while (unheard.size > 20) unheard.removeAt(0)
        }
    }

    /** What threw since Ai last asked, said once (`world_state`). */
    fun takeUnheard(): List<String> = synchronized(unheard) { unheard.toList().also { unheard.clear() } }

    /**
     * The person's (or Ai's) event: a press, a change, a pick. Queued — a `change` of the same widget waiting is replaced
     * — and run when the app is free; too many waiting are dropped with a line in the window.
     */
    fun send(h: AppHost, id: String, type: String, value: JsonElement, by: String = WorldEvent.YOU) {
        h.by = by
        when (h.events.offer(id, type, value)) {
            Offered.DROPPED -> h.note = "Events came faster than the app could answer: ${h.events.dropped} were dropped."
            else -> Unit
        }
        pump(h)
    }

    private fun pump(h: AppHost) {
        // Events offered before the app has started wait for it: start() pumps them once its first screen is drawn.
        if (h.pumping || h.code == null || h.tree == null && h.failure == null) return
        h.pumping = true
        scope.launch {
            try {
                while (true) {
                    val e = h.events.take() ?: break
                    handle(h, e)
                }
            } finally {
                h.pumping = false
            }
        }
    }

    private suspend fun handle(h: AppHost, e: UiEvent) {
        val code = h.code ?: return
        val by = h.by
        val busy = scope.launch {
            delay(AppLimits.BUSY_MS)
            h.busy = true
        }
        val host = snapshot()
        val r = try {
            withContext(pool) { runner.on(code, h.state, e, host) }
        } finally {
            busy.cancel()
            h.busy = false
        }
        when (r) {
            is AppCall.Ok -> {
                val problem = AppCodec.stateProblem(r.value)
                if (problem != null) {
                    threw(h, AppCall.Failed("on(${e.type} ${e.id})", problem))
                    return
                }
                h.state = r.value
                h.failure = null
                h.answered++
                if (r.shown.isNotEmpty()) world.pinFromApp(h.slug, r.shown.take(AppLimits.SHOWS), by)
                saveSoon(h)
                render(h)
            }
            is AppCall.Failed -> threw(h, r)
        }
    }

    private fun saveSoon(h: AppHost) {
        h.save?.cancel()
        h.save = scope.launch {
            delay(AppLimits.SAVE_AFTER_MS)
            write(h)
        }
    }

    private suspend fun write(h: AppHost) {
        val store = store() ?: return
        val json = h.state
        withContext(Dispatchers.IO) { runCatching { store.writeState(h.slug, json) } }
    }

    private fun flush(h: AppHost) {
        if (h.save?.isActive != true) return
        h.save?.cancel()
        val store = store() ?: return
        val json = h.state
        runCatching { store.writeState(h.slug, json) }
    }

    private fun flushAll() = hosts.values.forEach(::flush)

    /** A new version arrived (a change, Back to, a sync): `migrate` from [from], then the screen; a failing view offers Start fresh. */
    private suspend fun reloadCode(h: AppHost, from: Int) {
        val store = store() ?: return
        val (m, code) = withContext(Dispatchers.IO) { store.manifest(h.slug) to store.appCode(h.slug) }
        if (m == null || code == null) {
            h.missing = true
            return
        }
        h.manifest = m
        h.code = code
        h.failure = null
        h.offerFresh = false
        when (val r = withContext(pool) { runner.migrate(code, h.state, from, snapshot()) }) {
            is AppCall.Ok -> h.state = r.value
            is AppCall.Failed -> {
                threw(h, r)
                h.offerFresh = true
                return
            }
        }
        if (!render(h)) h.offerFresh = true
    }

    /** *Start fresh* (§8.3): the state kept as `state.prev.json`, and the app from `init()`. */
    fun startFresh(slug: String) {
        val h = hosts[slug] ?: return
        scope.launch {
            val store = store() ?: return@launch
            h.save?.cancel()
            withContext(Dispatchers.IO) { store.startFresh(slug) }
            h.failure = null
            h.offerFresh = false
            h.stale = false
            if (init(h)) {
                write(h)
                render(h)
            }
        }
    }

    // ---- Making and changing (Ai's `world_app`, the person's save and Back to) ---------------------------------------

    /** Why [code] would not run as an app, with its line, or null when `init` and `view` both ran: checked in the cage. */
    suspend fun check(slug: String, code: String): AppCall.Failed? =
        when (val r = withContext(pool) { runner.check(AppCode(slug, 0, code), snapshot()) }) {
            is AppCall.Failed -> r
            is AppCall.Ok -> null
        }

    /** A new app, checked first: nothing is written when `init` or `view` throws. */
    suspend fun make(m: AppManifest, code: String, by: String = WorldEvent.AI): Result<AppManifest> = runCatching {
        val store = store() ?: error("No world is open: world_new makes one.")
        require(AppCodec.validSlug(m.slug)) { "an app's slug is a-z, 0-9 and -, at most 32: “${m.slug}”" }
        AppCodec.codeProblem(code)?.let { throw IllegalArgumentException(it) }
        check(m.slug, code)?.let { throw IllegalArgumentException("The app did not run: ${it.words}. Nothing was made; fix it and make it again.") }
        val (made, event) = withContext(Dispatchers.IO) { store.make(m.copy(by = by), code, System.currentTimeMillis()) }.getOrThrow()
        world.log(event)
        list = list.filterNot { it.slug == made.slug } + made
        if (by == WorldEvent.AI) {
            unopened = unopened + made.slug
            onAiApp(made.slug, made.title, true)
        } else {
            world.desk.refreshApps()
        }
        made
    }

    /** New code for [slug] (and its manifest's other fields, when given), checked first; the state kept, its window reloaded. */
    suspend fun change(slug: String, code: String, manifest: AppManifest? = null, by: String = WorldEvent.AI): Result<AppManifest> = runCatching {
        val store = store() ?: error("No world is open.")
        val old = manifest(slug) ?: withContext(Dispatchers.IO) { store.manifest(slug) } ?: error("There is no app “$slug”. world_state lists the apps.")
        AppCodec.codeProblem(code)?.let { throw IllegalArgumentException(it) }
        if (by == WorldEvent.AI) check(slug, code)?.let { throw IllegalArgumentException("The new code did not run: ${it.words}. The app is unchanged.") }
        val (next, event) = withContext(Dispatchers.IO) { store.change(slug, code, System.currentTimeMillis(), by, manifest) }.getOrThrow()
        world.log(event)
        list = list.map { if (it.slug == slug) next else it }
        hosts[slug]?.let { reloadCode(it, old.version) }
        if (by == WorldEvent.AI) onAiApp(slug, next.title, false) else world.desk.refreshApps()
        next
    }

    /** *Back to v[to]*: that version's code as the next version (a version only rises). */
    suspend fun back(slug: String, to: Int, by: String = WorldEvent.YOU): Result<AppManifest> = runCatching {
        val store = store() ?: error("No world is open.")
        val old = manifest(slug) ?: error("There is no app “$slug”.")
        val (next, event) = withContext(Dispatchers.IO) { store.back(slug, to, System.currentTimeMillis(), by) }.getOrThrow()
        world.log(event)
        list = list.map { if (it.slug == slug) next else it }
        if (by == WorldEvent.AI) onAiApp(slug, next.title, false) else world.desk.refreshApps()
        hosts[slug]?.let { reloadCode(it, old.version) }
        next
    }

    /** The versions kept for Back to, newest first. */
    suspend fun versions(slug: String): List<Int> = store()?.let { s -> withContext(Dispatchers.IO) { s.versions(slug) } }.orEmpty()

    /** Deletes [slug] and everything of it (the person confirmed). */
    suspend fun delete(slug: String, by: String = WorldEvent.YOU): Result<String> = runCatching {
        val store = store() ?: error("No world is open.")
        hosts.remove(slug)?.let { it.save?.cancel() }
        runner.stop(slug)
        windows.close(AppRef.Made(slug))
        val event = withContext(Dispatchers.IO) { store.delete(slug, System.currentTimeMillis(), by) }.getOrThrow()
        world.log(event)
        list = list.filterNot { it.slug == slug }
        unopened = unopened - slug
        world.desk.refreshApps()
        "Deleted $slug."
    }

    /** The app's code, as the Editor and `world_read` show it. */
    fun code(slug: String): String? = store()?.code(slug)

    /** The state as it stands (the window's, else the file), for Ai: enveloped, since it holds the person's typing (§8.6). */
    suspend fun stateForAi(slug: String): String? {
        val m = manifest(slug) ?: return null
        val live = hosts[slug]?.takeIf { it.started && !it.missing }?.state
        val json = live ?: store()?.let { s -> withContext(Dispatchers.IO) { (s.readState(slug) as? StateRead.Ok)?.json } } ?: "(no state yet: the app has not been opened)"
        return AppCodec.forAi(m, json)
    }

    /**
     * Ai presses its own app (`world_app press`): the event offered as the person's would be, then waited on; the screen
     * after it in words, or what went wrong with its line.
     */
    suspend fun press(slug: String, id: String, type: String, value: JsonElement): Result<String> = runCatching {
        manifest(slug) ?: error("There is no app “$slug”.")
        val h = host(slug)
        val deadline = System.currentTimeMillis() + AppLimits.INIT_MS + AppLimits.VIEW_MS + 2_000
        while (h.code == null && !h.missing && h.failure == null && System.currentTimeMillis() < deadline) delay(20)
        if (h.missing) error("There is no app “$slug”.")
        h.failure?.takeIf { h.code == null || h.tree == null }?.let { error("The app could not start: ${it.words}") }
        val before = h.answered
        val failedBefore = h.failure
        send(h, id, type, value, WorldEvent.AI)
        val until = System.currentTimeMillis() + AppLimits.ON_MS + AppLimits.VIEW_MS + 2_000
        while ((!h.idle || h.answered == before && h.failure === failedBefore) && System.currentTimeMillis() < until) delay(20)
        h.failure?.takeIf { it !== failedBefore }?.let { return@runCatching "It went wrong: ${it.words}. The state is as it was before the call that failed." }
        "Pressed. The screen now:\n" + (h.tree?.let { UiWords.describe(it.root) } ?: "(nothing drawn yet)")
    }

    /** [slug]'s window started (if it was not) and its first screen waited for: the screen in words, or why it is not there. */
    suspend fun screenNow(slug: String): String {
        val h = host(slug)
        val until = System.currentTimeMillis() + AppLimits.INIT_MS + AppLimits.VIEW_MS + 3_000
        while (h.tree == null && h.failure == null && !h.missing && System.currentTimeMillis() < until) delay(20)
        return when {
            h.missing -> "There is no app “$slug”."
            h.failure != null && h.tree == null -> "It did not start: ${h.failure?.words}"
            else -> (h.failure?.let { "The last call failed: ${it.words}\n" }.orEmpty()) + "Its screen:\n" + (h.tree?.let { UiWords.describe(it.root) } ?: "(not drawn yet)")
        }
    }

    /** Ai made ([made]) or changed [slug] this turn: the desktop's `aiMadeApp` (its window is where the answer is, §6.4). */
    var onAiApp: (slug: String, name: String, made: Boolean) -> Unit = { slug, name, made -> world.desk.aiMadeApp(slug, name, made) }

    /** The screen of [slug] in words, when its window is open. */
    fun screen(slug: String): String? = hosts[slug]?.tree?.let { UiWords.describe(it.root) }

    /** Whether [slug]'s window is live in this run. */
    fun isLive(slug: String): Boolean = hosts.containsKey(slug)

    companion object {
        /** How long one read of the app is shared between an app's calls. */
        const val SNAPSHOT_MS = 2_000L

        /** Where an app's code is, as the Editor and Files name it. */
        fun codePath(slug: String): String = AppPaths.code(slug)

        /** The app [path] is the code of (`apps/<slug>/main.js`), or null. */
        fun slugOfCode(path: String): String? =
            path.takeIf { it.startsWith(AppPaths.ROOT + "/") && it.endsWith("/" + AppPaths.CODE) }
                ?.removePrefix(AppPaths.ROOT + "/")?.removeSuffix("/" + AppPaths.CODE)?.takeIf(AppCodec::validSlug)
    }
}

/** Worlds' pins from an app's `ygo.show`, at most four an event, opened in this world's Browser (§8.6 point 2). */
internal suspend fun Worlds.pinFromApp(slug: String, shown: List<WorldApi.Shown>, by: String) {
    pin(shown, AppPaths.dir(slug), by)
}
