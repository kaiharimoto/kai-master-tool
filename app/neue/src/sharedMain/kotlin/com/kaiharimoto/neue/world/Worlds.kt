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
import com.kaiharimoto.mastertool.core.world.WorldPaths
import com.kaiharimoto.mastertool.core.world.WorldPrefs
import com.kaiharimoto.mastertool.core.world.WorldPrelude
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

/** The panes of the World's screen, in the order the keys number them. */
enum class WorldPane(val title: String) {
    FILES("Files"),
    EDITOR("Editor"),
    TERMINAL("Terminal"),
    BOARDS("Boards"),
    THOUGHTS("Thoughts"),
    ACTIVITY("Activity"),
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

    /** Asked when Ai starts work in a world with [WorldPrefs.follow] on: brings the World page forward. */
    var comeForward: () -> Unit = {}

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

    val terminal = mutableStateListOf<TermLine>()

    /** What is running, in words, or null. */
    var running by mutableStateOf<String?>(null)
        private set

    var activity by mutableStateOf<List<WorldEvent>>(emptyList())
        private set

    /** The pane Ai is working in now, or null when it is not working here. */
    var aiPane by mutableStateOf<WorldPane?>(null)

    /** The pane the person brought forward (the keys' and the phone's tabs). */
    var focus by mutableStateOf(WorldPane.EDITOR)

    /** A pane given the whole page, or null. */
    var maximized by mutableStateOf<WorldPane?>(null)

    /** The board picked on the canvas. */
    var selectedBoard by mutableStateOf<String?>(null)

    /** The person pressed Skip on Ai's typing. */
    var skipTyping by mutableStateOf(false)

    @Volatile
    private var stopAsked = false

    private fun root(w: World): File = File(dir, w.id)
    private fun filesDir(w: World): File = File(root(w), "files")

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

    /** Reloads from disk after a sync or a restore brought other worlds in. */
    fun reload() = load()

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
        val first = w.open ?: files.firstOrNull { WorldPaths.lang(it) != null } ?: files.firstOrNull()
        showFile(first)
    }

    private fun refreshFiles() {
        val w = open ?: run { files = emptyList(); return }
        val base = filesDir(w)
        files = base.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.map { it.relativeTo(base).invariantSeparatorsPath }
            .filter { !it.startsWith(".") }.sorted().toList()
    }

    /** Opens [path] in the editor (the person's click, or Ai's). */
    fun showFile(path: String?) {
        editorPath = path
        editorText = path?.let { read(it) }.orEmpty()
        edited = false
    }

    fun read(path: String): String? {
        val w = open ?: return null
        val safe = WorldPaths.safe(path) ?: return null
        return File(filesDir(w), safe).takeIf { it.isFile }?.let { if (it.length() > MAX_FILE) it.readText().take(MAX_FILE) else it.readText() }
    }

    // ---- Files ----------------------------------------------------------------------------------------------

    /** Writes [text] to [path], typed into the editor first when Ai writes it. The message says what happened. */
    suspend fun write(path: String, text: String, by: String = WorldEvent.AI): Result<String> = runCatching {
        val w = open ?: error("No world is open: world_new makes one.")
        val safe = WorldPaths.safe(path) ?: error("“$path” is not a path inside the world: relative, plain letters, no ..")
        require(text.length <= MAX_FILE) { "a file holds at most ${MAX_FILE / 1000}k characters" }
        if (by == WorldEvent.AI) {
            arrive(WorldPane.EDITOR)
            typeOut(safe, text)
        }
        withContext(Dispatchers.IO) {
            val f = File(filesDir(w), safe)
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
        touch(w.copy(open = safe))
        log(WorldEvent(now(), WorldEvent.Kind.WRITE, by, path = safe, text = "Wrote $safe (${text.lines().size} lines)"))
        "Wrote $safe: ${text.lines().size} lines."
    }

    /** Ai's text, typed in at the person's chosen pace, never longer than a few seconds whatever its length. */
    private suspend fun typeOut(path: String, text: String) {
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
            while (n < text.length && !skipTyping && !stopAsked) {
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
        val f = File(filesDir(w), safe)
        require(f.isFile) { "There is no $safe." }
        withContext(Dispatchers.IO) { f.delete() }
        refreshFiles()
        if (editorPath == safe) showFile(files.firstOrNull())
        log(WorldEvent(now(), WorldEvent.Kind.DELETE, by, path = safe, text = "Deleted $safe"))
        "Deleted $safe."
    }

    /** The person's own edit in the editor, saved. */
    fun saveEditor() {
        val path = editorPath ?: return
        if (!edited || typing) return
        val text = editorText
        scope.launch { write(path, text, WorldEvent.YOU) }
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
        if (by == WorldEvent.AI) arrive(WorldPane.TERMINAL)
        val limit = seconds.coerceIn(1, 120)
        val label = safe ?: "snippet.$language"
        running = label
        stopAsked = false
        line(TermLine.Kind.COMMAND, "${if (language == WorldPaths.LANG_PY) "python" else "js"} $label")
        val started = now()
        try {
            val api = WorldApi(host())
            val outcome = if (language == WorldPaths.LANG_PY) runPython(w, safe, source, limit, api) else runJs(label, source, limit, api)
            val boards = pin(api.shown, safe, by)
            val record = outcome.first.copy(path = safe, boards = boards.map { it.id }, ms = now() - started)
            line(if (record.ok) TermLine.Kind.NOTE else TermLine.Kind.ERR, if (record.ok) "— done in ${record.ms} ms" + (if (boards.isNotEmpty()) ", ${boards.size} board(s)" else "") else record.err)
            log(WorldEvent(now(), WorldEvent.Kind.RUN, by, path = safe, text = (if (record.ok) "Ran " else "Failed ") + label, run = record.copy(out = record.out.take(4_000))))
            if (boards.isNotEmpty() && by == WorldEvent.AI) arrive(WorldPane.BOARDS)
            RunOutcome(record, boards, outcome.second)
        } finally {
            running = null
        }
    }

    private suspend fun runJs(label: String, source: String, seconds: Int, api: WorldApi): Pair<RunRecord, String?> {
        val r = withContext(Dispatchers.IO) {
            JsRuntime(JsRuntime.Limits(millis = seconds * 1000L)).run(source, label, api, onLine = { l -> line(TermLine.Kind.OUT, l) }, stop = { stopAsked })
        }
        r.value?.let { line(TermLine.Kind.OUT, "→ $it") }
        return RunRecord(WorldPaths.LANG_JS, ok = r.ok, out = r.out, err = r.err, cut = r.cut) to r.value
    }

    private suspend fun runPython(w: World, path: String?, source: String, seconds: Int, api: WorldApi): Pair<RunRecord, String?> {
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

    /** Runs an instrument in the open world: its lines in the terminal, its boards pinned. */
    suspend fun tool(name: String, args: JsonObject, by: String = WorldEvent.AI): Result<RunOutcome> = runCatching {
        open ?: error("No world is open: world_new makes one.")
        check(running == null) { "Something is running already: wait for it, or stop it." }
        if (by == WorldEvent.AI) arrive(WorldPane.TERMINAL)
        running = "instrument $name"
        stopAsked = false
        line(TermLine.Kind.COMMAND, "instrument $name ${args.toString().take(200)}")
        val started = now()
        try {
            val h = host()
            val result = withContext(Dispatchers.Default) { Instruments.run(name, args, h) }
            result.lines.forEach { line(TermLine.Kind.OUT, it) }
            val boards = pin(result.boards, null, by)
            val record = RunRecord("instrument", path = name, ok = true, ms = now() - started, out = result.lines.joinToString("\n"), boards = boards.map { it.id })
            line(TermLine.Kind.NOTE, "— done in ${record.ms} ms, ${boards.size} board(s)")
            log(WorldEvent(now(), WorldEvent.Kind.RUN, by, text = "Instrument $name", run = record))
            if (boards.isNotEmpty() && by == WorldEvent.AI) arrive(WorldPane.BOARDS)
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
    private suspend fun pin(shown: List<WorldApi.Shown>, source: String?, by: String): List<Board> {
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
        }
        return placed
    }

    /** A board put straight up by Ai or taken down (`world_show`). */
    suspend fun putBoard(id: String?, kind: String, title: String?, body: String, note: String?, by: String = WorldEvent.AI): Result<Board> = runCatching {
        open ?: error("No world is open: world_new makes one.")
        val (k, payload) = ShowSpec.parse(kind, body).getOrElse { throw IllegalArgumentException(it.message) }
        if (by == WorldEvent.AI) arrive(WorldPane.BOARDS)
        pin(listOf(WorldApi.Shown(id?.filter { it.isLetterOrDigit() || it in "-_" }?.take(40)?.ifEmpty { null }, title.orEmpty().ifEmpty { k.name.lowercase() }, k, payload, note.orEmpty())), null, by).single()
    }

    suspend fun removeBoard(id: String, by: String = WorldEvent.AI): Result<String> = runCatching {
        val w = open ?: error("No world is open.")
        requireNotNull(w.board(id)) { "There is no board $id." }
        touch(w.drop(id))
        log(WorldEvent(now(), WorldEvent.Kind.UNSHOW, by, board = id, text = "Took down $id"))
        "Took down $id."
    }

    /** The person moved a board on the canvas. */
    fun moveBoard(id: String, x: Double, y: Double) {
        val w = open ?: return
        val b = w.board(id) ?: return
        scope.launch { touch(w.put(b.copy(x = x, y = y))) }
    }

    // ---- Keeping it --------------------------------------------------------------------------------------------

    /** Ai has started work in [pane]: the person sees it there, and the page comes forward if they asked it to. */
    fun arrive(pane: WorldPane) {
        aiPane = pane
        if (prefs().follow) {
            focus = pane
            comeForward()
        }
    }

    /** Ai has finished its turn: nobody is working here now. */
    fun leave() {
        aiPane = null
    }

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

    private fun log(e: WorldEvent) {
        val w = open ?: return
        activity = (activity + e).takeLast(MAX_ACTIVITY)
        scope.launch {
            io.withLock { withContext(Dispatchers.IO) { File(root(w), "log.jsonl").appendText(WorldCodec.line(e) + "\n") } }
        }
    }

    private fun now() = System.currentTimeMillis()

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
            append("Python: " + when {
                !WorldPython.possible -> "not on this device"
                !prefs().python -> "off (the person allows it in Settings)"
                else -> "on"
            })
        }
    }

    companion object {
        const val MAX_FILE = 200_000
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
