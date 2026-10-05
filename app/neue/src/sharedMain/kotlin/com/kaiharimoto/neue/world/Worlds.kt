package com.kaiharimoto.neue.world

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kaiharimoto.mastertool.core.world.Board
import com.kaiharimoto.mastertool.core.world.Instruments
import com.kaiharimoto.mastertool.core.world.JsRuntime
import com.kaiharimoto.mastertool.core.world.RunRecord
import com.kaiharimoto.mastertool.core.world.ShowSpec
import com.kaiharimoto.mastertool.core.world.World
import com.kaiharimoto.mastertool.core.world.WorldApi
import com.kaiharimoto.mastertool.core.world.WorldCanvas
import com.kaiharimoto.mastertool.core.world.WorldCodec
import com.kaiharimoto.mastertool.core.world.WorldEvent
import com.kaiharimoto.mastertool.core.world.WorldHost
import com.kaiharimoto.mastertool.core.world.WorldLimits
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.WorldPrefs
import com.kaiharimoto.mastertool.core.world.WorldPrelude
import com.kaiharimoto.mastertool.core.world.RunLog
import com.kaiharimoto.neue.world.apps.WorldApps
import com.kaiharimoto.neue.world.apps.describeDesk
import com.kaiharimoto.neue.world.browser.WorldBrowser
import com.kaiharimoto.neue.world.library.WorldLibrary
import com.kaiharimoto.mastertool.core.world.desk.AiDoes
import com.kaiharimoto.mastertool.core.world.desk.AppRef
import com.kaiharimoto.mastertool.core.world.desk.BuiltInApp
import com.kaiharimoto.neue.world.desk.WorldDeskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 1.0.97's six panes, by name. The desktop (1.1.x, `docs/world/DESKTOP.md` §3) has apps in windows instead; these are
 * kept so a caller that says "Ai is in the Editor" in the old words still lands Ai in the right window ([app]): the
 * boards are the Browser now, and the activity is Thoughts.
 */
enum class WorldPane(val title: String, val app: BuiltInApp) {
    FILES("Files", BuiltInApp.FILES),
    EDITOR("Editor", BuiltInApp.EDITOR),
    TERMINAL("Terminal", BuiltInApp.TERMINAL),
    BOARDS("Boards", BuiltInApp.BROWSER),
    THOUGHTS("Thoughts", BuiltInApp.THOUGHTS),
    ACTIVITY("Activity", BuiltInApp.THOUGHTS),
    ;

    companion object {
        fun of(app: String?): WorldPane? = entries.firstOrNull { it.app.id == app }
    }
}

/** One line of the World's terminal. */
data class TermLine(val kind: Kind, val text: String) {
    enum class Kind { COMMAND, OUT, ERR, NOTE }
}

/** What a run or an instrument came to, for the person's eyes and Ai's. */
data class RunOutcome(val record: RunRecord, val boards: List<Board>, val value: String? = null) {
    /** In words for Ai: the output (cut for its context), how it ended, the boards it pinned. */
    fun words(limit: Int = 8_000): String = buildString {
        val r = record
        append(if (r.ok) "Ran" else "Failed")
        append(" ${r.path ?: "a ${r.lang} snippet"} in ${r.ms} ms.")
        if (r.out.isNotBlank()) append("\nOutput:\n").append(cut(r.out, limit))
        if (value != null) append("\nValue: ").append(value.take(1_000))
        if (r.err.isNotBlank()) append("\nError: ").append(r.err.take(2_000))
        if (r.cut) append("\n(The output ran past what is kept.)")
        if (boards.isNotEmpty()) append("\nPinned: ").append(boards.joinToString { "${it.id} (${it.kind}: ${it.title})" })
    }

    private fun cut(s: String, limit: Int) = if (s.length <= limit) s else s.take(limit * 3 / 4) + "\n…\n" + s.takeLast(limit / 4)
}

/**
 * Ai World for the app's lifetime (1.0.97): the worlds in `<data>/world/<id>/`, the one open, and everything the
 * person watches — the editor's text as it is typed, the terminal as a run prints, the boards, the activity, and the
 * pane Ai is working in. Ai's tools (`AiWorld`) and the person's clicks change the same state, as the duel's do.
 *
 * Runs go off the main thread (JavaScript in [JsRuntime], Python in [WorldPython]); what they print comes back to it
 * in order. One run at a time.
 */
class Worlds(val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()

    /** The app as a world's scripts see it, read now: set by the app's holders. */
    var host: suspend () -> WorldHost = { error("The World has no app to read yet.") }

    /** This device's World settings, read when needed. */
    var prefs: () -> WorldPrefs = { WorldPrefs() }

    /**
     * Asked when Ai starts work in a world with [WorldPrefs.follow] on and the focus policy lets it (§6.1): brings the
     * World page forward.
     */
    var comeForward: () -> Unit = {}

    /** The desktop (1.1.x, `docs/world/DESKTOP.md`): windows, the avatar, notices — the shell's own part of this holder. */
    val desk = WorldDeskState(this)
    /**
     * The app's folders every world sees (Phase D step 2: the effects library at `lib/effects/`, [WorldMount]): a path under
     * one is read, listed, written and deleted there, never in the world's own files.
     */
    val mounts = mutableListOf<WorldMount>()

    /** The mount [path] lies under, and the path under it; null for a world's own file. */
    private fun mountOf(path: String): Pair<WorldMount, String>? =
        mounts.firstNotNullOfOrNull { m -> path.takeIf { it.startsWith(m.prefix) }?.removePrefix(m.prefix)?.takeIf { it.isNotEmpty() }?.let { m to it } }

    var list by mutableStateOf<List<World>>(emptyList())
        private set
    var open by mutableStateOf<World?>(null)
        private set
    var files by mutableStateOf<List<String>>(emptyList())
        private set

    var editorPath by mutableStateOf<String?>(null)
        private set

    /** What the editor shows: the file, or as much of Ai's text as has been typed. */
    var editorText by mutableStateOf("")

    /** The editor's text differs from the file: the person's own edit, not yet saved. */
    var edited by mutableStateOf(false)

    /** Ai is typing into the editor: it is read-only until it finishes. */
    var typing by mutableStateOf(false)
        private set

    /**
     * Files the person took over or changed while Ai worked (§5.5): Ai's next whole write to one is refused until it
     * reads it again ([aiSaw] then matches), so the person's own edit is never overwritten.
     */
    private val yours = HashSet<String>()

    /** What each file held when Ai last read or wrote it, by path. */
    private val aiSaw = HashMap<String, Int>()

    val terminal = mutableStateListOf<TermLine>()

    /** What is running, in words, or null. */
    var running by mutableStateOf<String?>(null)
        private set

    var activity by mutableStateOf<List<WorldEvent>>(emptyList())
        private set

    /** Where Ai is working now, in 1.0.97's words: the window the desk says it is in. */
    val aiPane: WorldPane? get() = WorldPane.of(desk.desk.ai)

    /** The board shown last (pinned, or picked from a page). */
    var selectedBoard by mutableStateOf<String?>(null)

    /** The person pressed Skip on Ai's typing. */
    var skipTyping by mutableStateOf(false)

    // ---- What runs inside the desktop's windows (1.1.x, `docs/world/DESKTOP.md` §13, agent C): owned parts ------------

    /** The Browser: every board a page, one per tab (§4). */
    val browser = WorldBrowser(this)

    /** Ai's apps: the registry the windows read, each app's live screen, every change through `AppStore` (§8). */
    val apps = WorldApps(this)

    /** Everything Ai knows, read where it lives under the data folder (§10). */
    val library = WorldLibrary(dir.absoluteFile.parentFile ?: dir)

    @Volatile
    private var stopAsked = false

    private fun root(w: World): File = File(dir, w.id)
    private fun filesDir(w: World): File = File(root(w), "files")

    /** The open world's folder, for what keeps its own files beside `world.json` (the desk, the apps). */
    val openRoot: File? get() = open?.let(::root)

    // ---- Loading, making and opening worlds --------------------------------------------------------------------

    fun load() {
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
                    WorldCodec.decode(File(d, "world.json").takeIf { it.isFile }?.readText())
                }.sortedByDescending { it.updated }
            }
            list = read
            if (open == null) prefs().open?.let { id -> read.firstOrNull { it.id == id } }?.let { openNow(it) }
        }
    }

    /** Reloads from disk after a sync or a restore brought other worlds in, and the open world's apps and tabs with it. */
    fun reload() {
        load()
        open?.let { w ->
            desk.load(root(w))
            apps.reload()
        }
    }

    suspend fun create(title: String, scopeOf: String?, by: String = WorldEvent.AI): World {
        val now = System.currentTimeMillis()
        val w = World(id = "w" + now.toString(36), title = title.trim().ifEmpty { "World" }.take(80), scope = scopeOf, created = now, updated = now)
        withContext(Dispatchers.IO) {
            filesDir(w).mkdirs()
            File(filesDir(w), "README.md").writeText("# ${w.title}\n\nWhat this world is for, and what each file answers.\n")
        }
        save(w)
        list = listOf(w) + list
        openNow(w)
        log(WorldEvent(now, WorldEvent.Kind.NEW, by, text = "Made “${w.title}”"))
        return w
    }

    fun openWorld(id: String): World? {
        val w = list.firstOrNull { it.id == id } ?: return null
        openNow(w)
        return w
    }

    private fun openNow(w: World) {
        open = w
        refreshFiles()
        terminal.clear()
        selectedBoard = null
        activity = WorldCodec.events(File(root(w), "log.jsonl").takeIf { it.isFile }?.readText()).takeLast(MAX_ACTIVITY)
        apps.load(root(w))
        yours.clear()
        aiSaw.clear()
        val first = w.open ?: files.firstOrNull { WorldPaths.lang(it) != null } ?: files.firstOrNull()
        showFile(first)
        desk.load(root(w))
    }

    /** The files read again from disk (the Files window's own refresh). */
    fun refresh() = refreshFiles()

    private fun refreshFiles() {
        val w = open ?: run { files = emptyList(); return }
        val base = filesDir(w)
        val own = base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.map { it.relativeTo(base).invariantSeparatorsPath }
            .filter { !it.startsWith(".") && mountOf(it) == null }.sorted().toList()
        files = own + mounts.flatMap { m -> runCatching { m.listed() }.getOrDefault(emptyList()) }
    }

    /** Lists the files again: a mount's folder changed outside a world (a sync, a compile). */
    fun refreshListing() = refreshFiles()

    /** Opens [path] in the editor (the person's click, or Ai's). */
    fun showFile(path: String?) {
        editorPath = path
        editorText = path?.let { readRaw(it) }.orEmpty()
        edited = false
    }

    /** [path] as Ai reads it (`world_read`, an edit's base): what it saw is remembered, so its next write is allowed. */
    fun read(path: String): String? {
        val text = readRaw(path) ?: return null
        WorldPaths.safe(path)?.let { aiSaw[it] = text.hashCode() }
        return text
    }

    /** [path] as the person reads it (`cat`, a page): nothing remembered for Ai. */
    fun peek(path: String): String? = readRaw(path)

    /** A file's size in bytes, or null when there is none: the Editor opens a large one read-only (§3). */
    fun sizeOf(path: String): Long? {
        val w = open ?: return null
        val safe = WorldPaths.safe(path) ?: return null
        val f = mountOf(safe)?.let { (m, rel) -> if (m.readable(rel)) File(m.dir, rel) else return null } ?: File(filesDir(w), safe)
        return f.takeIf { it.isFile }?.length()
    }

    private fun readRaw(path: String): String? {
        val w = open ?: return null
        val safe = WorldPaths.safe(path) ?: return null
        // An app's code is under the world's apps/, beside files/ (§8.2): the Editor and world_read read it there.
        WorldApps.slugOfCode(safe)?.let { slug -> return apps.code(slug) }
        val f = mountOf(safe)?.let { (m, rel) -> if (m.readable(rel)) File(m.dir, rel) else return null } ?: File(filesDir(w), safe)
        return f.takeIf { it.isFile }?.let { if (it.length() > MAX_FILE) it.readText().take(MAX_FILE) else it.readText() }
    }

    // ---- Files ----------------------------------------------------------------------------------------------

    /** Writes [text] to [path], typed into the editor first when Ai writes it. The message says what happened. */
    suspend fun write(path: String, text: String, by: String = WorldEvent.AI): Result<String> = runCatching {
        val w = open ?: error("No world is open: world_new makes one.")
        val safe = WorldPaths.safe(path) ?: error("“$path” is not a path inside the world: relative, plain letters, no ..")
        require(text.length <= MAX_FILE) { "a file holds at most ${MAX_FILE / 1000}k characters" }
        // An app's code saved from the Editor is a new version of the app (§8.2): the store keeps the old one for Back to.
        WorldApps.slugOfCode(safe)?.let { slug ->
            require(by == WorldEvent.YOU) { "an app's code changes through world_app change, not world_write" }
            val m = apps.change(slug, text, by = by).getOrThrow()
            editorPath = safe
            editorText = text
            edited = false
            return@runCatching "Saved ${m.title} as v${m.version}."
        }
        val mounted = mountOf(safe)
        if (mounted != null) {
            val (m, rel) = mounted
            require(m.writable(rel)) { "${m.prefix} holds the app's own files: write $safe as one of them, or somewhere else in the world." }
            m.refuse(rel, by)?.let { error(it) }
        }
        if (by == WorldEvent.AI) {
            // Take over (§5.5): a file the person is editing, or changed since Ai last read it, is theirs.
            val unsaved = editorPath == safe && edited
            val changed = safe in yours && readRaw(safe)?.hashCode() != aiSaw[safe]
            check(!unsaved && !changed) { "The person is editing $safe: read it again with world_read and send edits." }
            desk.arrive(BuiltInApp.EDITOR.ref, AiDoes.Write(safe))
            typeOut(safe, text)
        }
        withContext(Dispatchers.IO) {
            val f = mounted?.let { (m, rel) -> File(m.dir, rel) } ?: File(filesDir(w), safe)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
        refreshFiles()
        if (editorPath == safe || by == WorldEvent.AI) {
            editorPath = safe
            editorText = text
            edited = false
        }
        if (by == WorldEvent.AI) aiSaw[safe] = text.hashCode() else yours += safe
        touch(w.copy(open = safe))
        log(WorldEvent(now(), WorldEvent.Kind.WRITE, by, path = safe, text = "Wrote $safe (${text.lines().size} lines)"))
        val heard = mounted?.let { (m, rel) -> m.changed(rel, by, deleted = false) }
        heard?.let { line(TermLine.Kind.NOTE, it) }
        "Wrote $safe: ${text.lines().size} lines." + heard?.let { "\n$it" }.orEmpty()
    }

    /** Ai's text, typed in at the person's chosen pace, never longer than a few seconds whatever its length. */
    internal suspend fun typeOut(path: String, text: String) {
        val cps = prefs().typing
        editorPath = path
        if (cps <= 0 || text.isEmpty()) {
            editorText = text
            return
        }
        typing = true
        skipTyping = false
        try {
            val frame = 16L
            val perFrame = maxOf(cps * frame / 1000.0, text.length / (MAX_TYPING_MS / frame.toDouble())).toInt().coerceAtLeast(1)
            var n = 0
            while (n < text.length && !skipTyping && !stopAsked && !takenOver) {
                n = minOf(text.length, n + perFrame)
                editorText = text.take(n)
                delay(frame)
            }
            editorText = text
        } finally {
            typing = false
        }
    }

    suspend fun delete(path: String, by: String = WorldEvent.AI): Result<String> = runCatching {
        val w = open ?: error("No world is open.")
        val safe = WorldPaths.safe(path) ?: error("“$path” is not a path inside the world")
        val mounted = mountOf(safe)
        if (mounted != null) {
            val (m, rel) = mounted
            require(m.writable(rel)) { "${m.prefix} holds the app's own files: $safe is not one a world deletes." }
            m.refuse(rel, by)?.let { error(it) }
        }
        val f = mounted?.let { (m, rel) -> File(m.dir, rel) } ?: File(filesDir(w), safe)
        require(f.isFile) { "There is no $safe." }
        withContext(Dispatchers.IO) { f.delete() }
        refreshFiles()
        if (editorPath == safe) showFile(files.firstOrNull())
        log(WorldEvent(now(), WorldEvent.Kind.DELETE, by, path = safe, text = "Deleted $safe"))
        val heard = mounted?.let { (m, rel) -> m.changed(rel, by, deleted = true) }
        "Deleted $safe." + heard?.let { "\n$it" }.orEmpty()
    }

    /** The person took the Editor over while Ai typed: the typing finishes at once and the file is theirs (§5.5). */
    var takenOver by mutableStateOf(false)
        private set

    /** Take over (the Editor's bar while Ai types). */
    fun takeOver() {
        takenOver = true
        skipTyping = true
        editorPath?.let { yours += it }
    }

    /** The person typed in the editor: the file is theirs until Ai reads it again. */
    fun personEdited(text: String) {
        if (typing) return
        editorText = text
        edited = true
        editorPath?.let { yours += it }
    }

    /** Skip ahead (§5.5): Ai's typing finishes at once, the avatar's waiting targets are dropped. Ai is not stopped. */
    fun skip() {
        skipTyping = true
        desk.avatar.skip()
    }

    /** The person's rename, from Files: the file written under [to] and the old one deleted. */
    suspend fun rename(path: String, to: String): Result<String> = runCatching {
        val w = open ?: error("No world is open.")
        val from = WorldPaths.safe(path) ?: error("“$path” is not a path inside the world")
        val dest = WorldPaths.safe(to) ?: error("“$to” is not a path inside the world: relative, plain letters, no ..")
        require(dest !in files) { "There is a $dest already." }
        val text = readRaw(from) ?: error("There is no $from.")
        withContext(Dispatchers.IO) {
            val f = File(filesDir(w), dest)
            f.parentFile?.mkdirs()
            f.writeText(text)
            File(filesDir(w), from).delete()
        }
        refreshFiles()
        if (editorPath == from) showFile(dest)
        log(WorldEvent(now(), WorldEvent.Kind.WRITE, WorldEvent.YOU, path = dest, text = "Renamed $from to $dest"))
        "Renamed $from to $dest."
    }

    /** The person's own edit in the editor, saved. */
    fun saveEditor() {
        val path = editorPath ?: return
        if (!edited || typing) return
        val text = editorText
        scope.launch { write(path, text, WorldEvent.YOU) }
    }

    // ---- The person's own hands (the page's buttons and keys) --------------------------------------------------

    /** Why the person's Run cannot run now, in words, or null when it can. */
    val runBlocked: String?
        get() = when {
            open == null -> "No world is open"
            running != null -> "Something is running"
            typing -> "Ai is still typing"
            editorPath == null -> "Open a file first"
            WorldPaths.lang(editorPath.orEmpty()) == null -> "Only .js and .py files run"
            else -> null
        }

    /** The person's Run: their edit saved first, then the file in the editor run; what stops it is said in the terminal. */
    fun runEditor() {
        if (runBlocked != null) return
        val path = editorPath ?: return
        val text = editorText
        val save = edited
        scope.launch {
            if (save) write(path, text, WorldEvent.YOU)
            run(path, null, null, by = WorldEvent.YOU).onFailure { line(TermLine.Kind.ERR, it.message ?: "The run failed.") }
        }
    }

    /** The person's New world. */
    fun make(title: String, scopeOf: String?) {
        scope.launch { create(title, scopeOf, WorldEvent.YOU) }
    }

    /** The person's × on a board. */
    fun takeDown(id: String) {
        scope.launch { removeBoard(id, WorldEvent.YOU) }
    }

    /** The person's line at the Terminal, a file's Run, a rename: launched here, what stops it said in the terminal. */
    fun launch(block: suspend () -> Result<*>) {
        scope.launch { block().onFailure { line(TermLine.Kind.ERR, it.message ?: "That did not run.") } }
    }

    /** A line of the person's own in the Terminal (the command they typed, `ls`, `cat`, `help`). */
    fun print(kind: TermLine.Kind, text: String) = line(kind, text)

    /**
     * A world put on screen at once, for the studio's photographs (`--world=demo`): its files written, the terminal
     * and activity as given, [editor] open — no typing, no runs.
     */
    fun seed(w: World, files: Map<String, String>, lines: List<TermLine>, events: List<WorldEvent>, editor: String?) {
        val base = filesDir(w)
        base.mkdirs()
        files.forEach { (path, text) -> File(base, path).also { it.parentFile?.mkdirs() }.writeText(text) }
        File(root(w), "world.json").writeText(WorldCodec.encode(w))
        list = listOf(w) + list.filterNot { it.id == w.id }
        open = w
        refreshFiles()
        terminal.clear()
        terminal += lines
        activity = events
        apps.load(root(w))
        showFile(editor)
        desk.load(root(w))
    }

    // ---- Running ----------------------------------------------------------------------------------------------

    /**
     * Runs [path], or [code] in [lang], in the open world. The terminal fills as it prints; the boards it shows are
     * pinned as it ends. Python only where it is possible and the person allowed it.
     */
    suspend fun run(path: String?, code: String?, lang: String?, seconds: Int = 30, by: String = WorldEvent.AI): Result<RunOutcome> = runCatching {
        val w = open ?: error("No world is open: world_new makes one.")
        check(running == null) { "Something is running already: wait for it, or stop it." }
        val safe = path?.let { WorldPaths.safe(it) ?: error("“$it” is not a path inside the world") }
        val source = when {
            safe != null -> read(safe) ?: error("There is no $safe. world_state lists the files.")
            !code.isNullOrBlank() -> code
            else -> error("Give a path to run, or code.")
        }
        val language = safe?.let { WorldPaths.lang(it) ?: error("$it is not code: .js or .py") } ?: (lang ?: WorldPaths.LANG_JS)
        val limit = seconds.coerceIn(1, 120)
        val label = safe ?: "snippet.$language"
        if (by == WorldEvent.AI) desk.arrive(BuiltInApp.TERMINAL.ref, AiDoes.Run(label))
        running = label
        stopAsked = false
        line(TermLine.Kind.COMMAND, "${if (language == WorldPaths.LANG_PY) "python" else "js"} $label")
        val started = now()
        // Every line, past what is kept too: the whole output goes to files/out/<run>.log when it is longer (§11).
        val whole = RunLog.Collector()
        try {
            val api = WorldApi(hostIn(w))
            val outcome = if (language == WorldPaths.LANG_PY) runPython(w, safe, source, limit, api, whole) else runJs(label, source, limit, api, whole)
            val boards = pin(api.shown, safe, by)
            val at = now()
            val logged = keepWholeOutput(w, whole, at)
            val record = outcome.first.copy(path = safe, boards = boards.map { it.id }, ms = at - started, out = outcome.first.out + logged?.let { "\n" + RunLog.pointer(at) }.orEmpty())
            line(if (record.ok) TermLine.Kind.NOTE else TermLine.Kind.ERR, if (record.ok) "— done in ${record.ms} ms" + (if (boards.isNotEmpty()) ", ${boards.size} board(s)" else "") else record.err)
            logged?.let { line(TermLine.Kind.NOTE, RunLog.pointer(at)) }
            log(WorldEvent(at, WorldEvent.Kind.RUN, by, path = safe, text = (if (record.ok) "Ran " else "Failed ") + label, run = record.copy(out = record.out.take(4_000))))
            desk.ran(label, record.ok, record.err, record.ms, boards.size, at)
            RunOutcome(record, boards, outcome.second)
        } finally {
            running = null
        }
    }

    private suspend fun runJs(label: String, source: String, seconds: Int, api: WorldApi, whole: RunLog.Collector): Pair<RunRecord, String?> {
        val r = withContext(Dispatchers.IO) {
            JsRuntime(JsRuntime.Limits(millis = seconds * 1000L)).run(source, label, api, onLine = { l -> line(TermLine.Kind.OUT, l) }, stop = { stopAsked }, everyLine = whole::line)
        }
        r.value?.let { line(TermLine.Kind.OUT, "→ $it") }
        return RunRecord(WorldPaths.LANG_JS, ok = r.ok, out = r.out, err = r.err, cut = r.cut) to r.value
    }

    private suspend fun runPython(w: World, path: String?, source: String, seconds: Int, api: WorldApi, whole: RunLog.Collector): Pair<RunRecord, String?> {
        val p = prefs()
        check(WorldPython.possible) { "Python does not run on this device; write JavaScript (.js)." }
        check(p.python) { "Python is off on this computer: the person allows it in Settings › Ai World. Write JavaScript (.js) meanwhile." }
        val python = withContext(Dispatchers.IO) { WorldPython.find(p.pythonPath) }
            ?: error("No Python 3 was found${if (p.pythonPath.isNotBlank()) " at ${p.pythonPath}" else " on the PATH"}.")
        val base = filesDir(w)
        val support = File(root(w), ".py")
        val script = withContext(Dispatchers.IO) {
            support.mkdirs()
            File(support, WorldPrelude.PY_MODULE).writeText(WorldPrelude.PYTHON)
            File(support, WorldPrelude.PY_DATA).writeText(api.pythonData())
            File(support, "boot.py").writeText(BOOT)
            File(base, "out").mkdirs()
            if (path != null) File(base, path).absolutePath else File(support, "snippet.py").also { it.writeText(source) }.absolutePath
        }
        val out = StringBuilder()
        val err = StringBuilder()
        var cut = false
        val ended = withContext(Dispatchers.IO) {
            WorldPython.run(python, base, listOf(File(support, "boot.py").absolutePath, script), seconds, onLine = { l, isErr ->
                if (!isErr && l.startsWith(WorldPrelude.SHOW_MARK)) {
                    show(api, l.removePrefix(WorldPrelude.SHOW_MARK))
                } else {
                    if (!isErr) synchronized(whole) { whole.line(l) }
                    val sink = if (isErr) err else out
                    synchronized(sink) {
                        if (sink.length + l.length > MAX_OUTPUT) cut = true else sink.append(l).append('\n')
                    }
                    if (!cut) line(if (isErr) TermLine.Kind.ERR else TermLine.Kind.OUT, l)
                }
            }, stop = { stopAsked })
        }
        val ok = ended.code == 0
        val why = when {
            ended.code == null -> ended.why
            !ok -> err.toString().trim().lines().takeLast(12).joinToString("\n").ifEmpty { "Python ended with code ${ended.code}." }
            else -> ""
        }
        return RunRecord(WorldPaths.LANG_PY, ok = ok, out = out.toString(), err = why, cut = cut) to null
    }

    /** A Python run's `show(...)` line, checked as JavaScript's are. */
    private fun show(api: WorldApi, json: String) {
        runCatching {
            val o = WorldCodec.json.parseToJsonElement(json).jsonObject
            val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val body = o["body"]?.let { if (it is JsonPrimitive) it.contentOrNull.orEmpty() else it.toString() }.orEmpty()
            api.call("show", JsonObject(mapOf("kind" to JsonPrimitive(kind), "body" to JsonPrimitive(body)) + o.filterKeys { it in setOf("title", "id", "note") }))
        }.onFailure { line(TermLine.Kind.ERR, "show: ${it.message}") }
    }

    /**
     * A run's whole output, written to `files/out/<run>.log` when it went past what is kept (§11, [RunLog]): the path, or
     * null when the output fit.
     */
    private suspend fun keepWholeOutput(w: World, whole: RunLog.Collector, at: Long): String? {
        if (!whole.needed) return null
        val path = RunLog.path(at)
        withContext(Dispatchers.IO) {
            val f = File(filesDir(w), path)
            f.parentFile?.mkdirs()
            f.writeText(whole.text())
        }
        refreshFiles()
        return path
    }

    /** The app as the open world's apps read it (`ygo.*` in an app's calls), its own files included. */
    internal suspend fun hostForApps(): WorldHost = open?.let { hostIn(it) } ?: host()

    /** The app as [w]'s code reads it, its own files included (`ygo.use`). */
    private suspend fun hostIn(w: World): WorldHost = host().let { (it as? WorldSnapshot)?.reading(filesDir(w), mounts.toList()) ?: it }

    /** Runs an instrument in the open world: its lines in the terminal, its boards pinned. */
    suspend fun tool(name: String, args: JsonObject, by: String = WorldEvent.AI): Result<RunOutcome> = runCatching {
        val w = open ?: error("No world is open: world_new makes one.")
        check(running == null) { "Something is running already: wait for it, or stop it." }
        if (by == WorldEvent.AI) desk.arrive(BuiltInApp.TERMINAL.ref, AiDoes.Tool(name))
        running = "instrument $name"
        stopAsked = false
        line(TermLine.Kind.COMMAND, "instrument $name ${args.toString().take(200)}")
        val started = now()
        try {
            val h = hostIn(w)
            val result = withContext(Dispatchers.Default) { Instruments.run(name, args, h) }
            result.lines.forEach { line(TermLine.Kind.OUT, it) }
            val boards = pin(result.boards, null, by)
            val record = RunRecord("instrument", path = name, ok = true, ms = now() - started, out = result.lines.joinToString("\n"), boards = boards.map { it.id })
            line(TermLine.Kind.NOTE, "— done in ${record.ms} ms, ${boards.size} board(s)")
            val at = now()
            log(WorldEvent(at, WorldEvent.Kind.RUN, by, text = "Instrument $name", run = record))
            desk.ran(name, ok = true, error = "", ms = record.ms, pages = boards.size, at = at)
            RunOutcome(record, boards, result.answer.toString().take(4_000))
        } catch (e: IllegalArgumentException) {
            line(TermLine.Kind.ERR, e.message.orEmpty())
            throw e
        } finally {
            running = null
        }
    }

    fun stop() {
        stopAsked = true
        skipTyping = true
    }

    // ---- Boards -----------------------------------------------------------------------------------------------

    /** Pins what a run asked to show: an id that exists replaces its board in place, a new one takes a free slot. */
    internal suspend fun pin(shown: List<WorldApi.Shown>, source: String?, by: String, tab: Boolean = true): List<Board> {
        var w = open ?: return emptyList()
        val placed = mutableListOf<Board>()
        shown.forEach { s ->
            val id = s.id ?: "b${(w.boards.size + 1)}-" + now().toString(36).takeLast(4)
            val old = w.board(id)
            val (x, y) = old?.let { it.x to it.y } ?: WorldCanvas.free(w.boards)
            val b = Board(id, s.title, s.kind.id, s.payload, x, y, old?.w ?: WorldCanvas.WIDTH, old?.h ?: WorldCanvas.HEIGHT, source, now(), s.note)
            w = w.put(b).let { if (it.boards.size > World.MAX_BOARDS) it.copy(boards = it.boards.drop(it.boards.size - World.MAX_BOARDS)) else it }
            placed += b
            log(WorldEvent(now(), WorldEvent.Kind.SHOW, by, board = id, text = "Pinned “${b.title}”"))
        }
        if (placed.isNotEmpty()) {
            touch(w)
            selectedBoard = placed.last().id
            // Each page opens in the Browser's tab (§4), Ai walking there to open it; `world_show open: false` pins only.
            if (tab) desk.showed(placed.map { it.id }, by, group = "${source ?: "show"}@${now()}")
        }
        return placed
    }

    /** A board put straight up by Ai or taken down (`world_show`). */
    suspend fun putBoard(id: String?, kind: String, title: String?, body: String, note: String?, by: String = WorldEvent.AI, tab: Boolean = true): Result<Board> = runCatching {
        open ?: error("No world is open: world_new makes one.")
        val (k, payload) = ShowSpec.parse(kind, body).getOrElse { throw IllegalArgumentException(it.message) }
        pin(listOf(WorldApi.Shown(id?.filter { it.isLetterOrDigit() || it in "-_" }?.take(40)?.ifEmpty { null }, title.orEmpty().ifEmpty { k.name.lowercase() }, k, payload, note.orEmpty())), null, by, tab).single()
    }

    suspend fun removeBoard(id: String, by: String = WorldEvent.AI): Result<String> = runCatching {
        val w = open ?: error("No world is open.")
        requireNotNull(w.board(id)) { "There is no board $id." }
        touch(w.drop(id))
        log(WorldEvent(now(), WorldEvent.Kind.UNSHOW, by, board = id, text = "Took down $id"))
        "Took down $id."
    }

    // ---- Keeping it --------------------------------------------------------------------------------------------

    /**
     * Ai has started work in [pane] (1.0.97's words, kept for its tools): it arrives in that app's window on the desk,
     * as the focus policy decides (§6.1), without the wait for the avatar ([WorldDeskState.arrive] waits).
     */
    fun arrive(pane: WorldPane) {
        val does = when (pane) {
            WorldPane.FILES -> AiDoes.Read(pane.app.ref, "the files")
            WorldPane.EDITOR -> AiDoes.Read(pane.app.ref, editorPath?.substringAfterLast('/') ?: "a file")
            WorldPane.TERMINAL -> AiDoes.Run(running ?: "a run")
            WorldPane.BOARDS -> AiDoes.Show(desk.desk.tabs.selected.orEmpty())
            WorldPane.THOUGHTS, WorldPane.ACTIVITY -> AiDoes.Read(pane.app.ref, "its thoughts")
        }
        desk.arriveNow(pane.app.ref, does)
    }

    /** Ai has started work in [app] (`world_open`, an app's window): through the focus policy, never past it. */
    suspend fun arrive(app: AppRef, does: AiDoes) = desk.arrive(app, does)

    /** Ai has finished its turn: nobody is working here now, and what it opened and nobody touched is put away (§6.4). */
    fun leave() {
        takenOver = false
        desk.turnEnd()
    }

    internal fun now() = System.currentTimeMillis()

    private fun line(kind: TermLine.Kind, text: String) {
        scope.launch {
            terminal += TermLine(kind, text)
            if (terminal.size > MAX_TERMINAL) terminal.removeRange(0, terminal.size - MAX_TERMINAL)
        }
    }

    private suspend fun touch(w: World) {
        val next = w.copy(updated = now())
        open = next
        list = listOf(next) + list.filterNot { it.id == next.id }
        save(next)
    }

    private suspend fun save(w: World) = io.withLock {
        withContext(Dispatchers.IO) {
            val d = root(w)
            d.mkdirs()
            val tmp = File(d, "world.json.tmp")
            tmp.writeText(WorldCodec.encode(w))
            val f = File(d, "world.json")
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    internal fun log(e: WorldEvent) {
        val w = open ?: return
        activity = (activity + e).takeLast(MAX_ACTIVITY)
        scope.launch {
            io.withLock { withContext(Dispatchers.IO) { File(root(w), "log.jsonl").appendText(WorldCodec.line(e) + "\n") } }
        }
    }

    /** The world as Ai reads it (`world_state`): its files, boards and last runs. */
    fun describe(w: World? = open): String {
        if (w == null) {
            return if (list.isEmpty()) "No worlds yet: world_new makes one." else
                "Worlds (newest first):\n" + list.joinToString("\n") { "- ${it.id} | ${it.title}${it.scope?.let { s -> " | $s" }.orEmpty()} | ${it.boards.size} boards" }
        }
        val runs = activity.filter { it.kind == WorldEvent.Kind.RUN }.takeLast(5)
        return buildString {
            appendLine("World ${w.id}: “${w.title}”${w.scope?.let { " ($it)" }.orEmpty()}${if (w.id == open?.id) ", open" else ""}.")
            appendLine("Files: " + (if (w.id == open?.id) files else emptyList()).joinToString().ifEmpty { "none" })
            appendLine("Boards:" + if (w.boards.isEmpty()) " none" else "")
            w.boards.forEach { b -> appendLine("- ${b.id} | ${b.kind} | ${b.title}${if (b.note.isNotBlank()) " — ${b.note}" else ""}") }
            if (runs.isNotEmpty()) {
                appendLine("Last runs:")
                runs.forEach { e -> appendLine("- ${e.text}: " + (e.run?.let { r -> if (r.ok) r.out.lines().takeLast(3).joinToString(" / ").take(300) else "error: ${r.err.take(300)}" }.orEmpty())) }
            }
            if (w.id == open?.id) append(describeDesk())
            append("Python: " + when {
                !WorldPython.possible -> "not on this device"
                !prefs().python -> "off (the person allows it in Settings)"
                else -> "on"
            })
        }
    }

    companion object {
        // 16 MB from 1.1.x (`docs/world/DESKTOP.md` §11): the number lives in core, beside the World's other limits.
        const val MAX_FILE = WorldLimits.MAX_FILE
        const val MAX_OUTPUT = 64_000
        const val MAX_TERMINAL = 2_000
        const val MAX_ACTIVITY = 500
        const val MAX_TYPING_MS = 6_000L

        /** Puts the helper's folder on Python's path (`-I` leaves the script's own off) and runs the script as __main__. */
        val BOOT = """
import os, runpy, sys
here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
script = sys.argv[1]
sys.path.insert(1, os.getcwd())
sys.argv = [script]
runpy.run_path(script, run_name="__main__")
""".trimStart()
    }
}
