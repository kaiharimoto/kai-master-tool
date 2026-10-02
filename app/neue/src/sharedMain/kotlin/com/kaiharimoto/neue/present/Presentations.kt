package com.kaiharimoto.neue.present

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.present.PresentIds
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.edit.EditHistory
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.Cursor
import com.kaiharimoto.neue.platform.decodePicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** A presentation playing: where it is, and what the presenter has over the slides. */
class Playing(val show: CompiledShow, start: Cursor) {
    var cursor by mutableStateOf(start)

    /** The cursor before the last move, and when the move came, for the transition and the builds. */
    var from by mutableStateOf<Cursor?>(null)
    var since by mutableStateOf(0L)

    /** The whole deck over the slide, on demand (D). */
    var overview by mutableStateOf(false)
    var overviewSince by mutableStateOf(0L)

    /** Black or white over everything (B, W), or neither. */
    var blank by mutableStateOf<String?>(null)
    var laser by mutableStateOf(false)
    var pen by mutableStateOf(false)
    var notes by mutableStateOf(false)

    /** The pen's strokes on this slide, in canvas units. */
    var ink by mutableStateOf<List<List<Pair<Float, Float>>>>(emptyList())

    /** When the show began, for the presenter's clock; and each slide's time, for rehearsing. */
    val startedAt = System.nanoTime()
    var slideStartedAt = System.nanoTime()
    val timings = HashMap<String, Long>()

    /** Rehearsing: each slide's time is kept as the show goes, and offered back at the end. */
    var rehearse = false

    /** The last move went back: what arrives arrives with its builds done. */
    var backward by mutableStateOf(false)

    /** The frame clock, in the presenter's frame nanoseconds, set every frame while anything moves. */
    var now by mutableStateOf(0L)

    /** A move: the transition and the builds run from here. */
    fun moveTo(next: Cursor, back: Boolean = false) {
        if (next == cursor) return
        if (next.slide != cursor.slide) {
            show.slides.getOrNull(cursor.slide)?.let { s ->
                timings[s.id] = (timings[s.id] ?: 0L) + (System.nanoTime() - slideStartedAt) / 1_000_000
            }
            slideStartedAt = System.nanoTime()
            ink = emptyList()
        }
        from = cursor
        backward = back
        cursor = next
        since = now
    }

    /** How long since the last move, in ms, on the frame clock. */
    val ms: Long get() = ((now - since) / 1_000_000).coerceAtLeast(0)
}

/**
 * Present for the app's lifetime (1.0.70, `NEUE.md` §4o): the presentations in
 * `<data>/present/<id>.json`, their pictures in `<data>/present/media/`, the one open in
 * the editor with its undo, its selection and the slide in view, and a presentation
 * playing. One place, so the page, the presenter and (phase 2) Ai change the same thing.
 */
class Presentations(val dir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()

    val mediaDir: File get() = File(dir, "media")

    /** Work for the editor that waits on something (a picture pasted, a file read), on the holder's own scope. */
    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** Every presentation, newest first. */
    var library by mutableStateOf<List<Presentation>>(emptyList())
        private set

    var loaded by mutableStateOf(false)
        private set

    /** The presentation in the editor, if one is open. */
    var open by mutableStateOf<Presentation?>(null)
        private set

    val history = EditHistory<Presentation>()

    /** Bumped on every edit, so Undo and Redo's state is read afresh. */
    var revision by mutableStateOf(0)
        private set

    /** The slide in view, by id. */
    var slideId by mutableStateOf<String?>(null)

    /** The elements selected on it. */
    var selection by mutableStateOf<Set<String>>(emptySet())

    /** Slides picked in the sorter alongside the one in view (Shift or Ctrl click). */
    var slidesPicked by mutableStateOf<Set<String>>(emptySet())

    /** The text element whose words are being edited in place. */
    var editingText by mutableStateOf<String?>(null)

    /** The editor's right panel tab. */
    var tab by mutableStateOf(PropsTab.SLIDE)

    /** A presentation playing, over everything. */
    var playing by mutableStateOf<Playing?>(null)

    /** The card picker, open, and what its cards are for. */
    var pickingCards by mutableStateOf<PickTarget?>(null)

    /** The words selected in the element being edited, so Bold and the rest act on them. */
    var textSelection by mutableStateOf(androidx.compose.ui.text.TextRange.Zero)

    /** What Copy last took: elements, or whole slides. In the app, and on the system clipboard as text. */
    var clipboard by mutableStateOf<List<Element>>(emptyList())
    var clipboardSlides by mutableStateOf<List<Slide>>(emptyList())

    /**
     * The slides on the other screen and the presenter view on this one (the desktop with two
     * screens): set by the presenter's menu, kept for the app's lifetime.
     */
    var audience by mutableStateOf(false)

    /** Whether the desktop found a second screen to show the slides on. Set by the window. */
    var screens by mutableStateOf(1)

    /** An export running: the slides drawn one by one and caught as pictures. */
    var exporting by mutableStateOf<ExportJob?>(null)

    /** The module dialog, open on a module's type. */
    var addingModule by mutableStateOf<String?>(null)

    /** Build with Ai's launcher, open (1.0.71). */
    var briefing by mutableStateOf(false)

    /** Restyle's dialog, open (1.0.72). */
    var restyling by mutableStateOf(false)

    /** The presentation as it was before a restyle began, for Undo restyle; null when there is none. */
    var restyleBefore by mutableStateOf<Presentation?>(null)

    /** The look set by hand from Style: one step of Undo. */
    fun applyTheme(id: String) {
        val o = open ?: return
        commit(o.copy(theme = id, themeOverride = null), "Style: ${com.kaiharimoto.mastertool.core.present.Themes.of(id).name}")
        seal()
    }

    /** The presentation put back as it was before the restyle, as one step of Undo. */
    fun undoRestyle() {
        val before = restyleBefore ?: return
        val o = open
        restyleBefore = null
        if (o == null || o.id != before.id) return
        commit(o.copy(theme = before.theme, themeOverride = before.themeOverride, slides = before.slides), "Undo restyle")
        seal()
    }

    /** The New dialog, open. */
    var creating by mutableStateOf(false)

    /** A presentation waiting on the delete confirmation. */
    var confirmDelete by mutableStateOf<Presentation?>(null)

    /** The pictures decoded, by media file name. */
    private val bitmaps = mutableStateMapOf<String, ImageBitmap>()

    val slide: Slide? get() = open?.slide(slideId) ?: open?.slides?.firstOrNull()

    val slideIndex: Int get() = open?.let { p -> p.indexOf(slide?.id).coerceAtLeast(0) } ?: 0

    fun load() {
        scope.launch {
            library = withContext(Dispatchers.Default) { readAll() }
            loaded = true
        }
    }

    /** The library read, if it has not been yet: Ai can ask before the page was ever opened. */
    suspend fun ensureLoaded() {
        if (loaded) return
        library = withContext(Dispatchers.Default) { readAll() }
        loaded = true
    }

    /** Read the folder again: after a sync or a restore brought presentations in. */
    fun reload() {
        scope.launch {
            val all = withContext(Dispatchers.Default) { readAll() }
            library = all
            val o = open
            if (o != null) {
                val fresh = all.firstOrNull { it.id == o.id }
                if (fresh != null && fresh.updatedAt > o.updatedAt) open = fresh
            }
            loaded = true
        }
    }

    private fun readAll(): List<Presentation> {
        val files = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
        return files.mapNotNull { PresentCodec.decode(runCatching { it.readText() }.getOrNull()) }
            .sortedByDescending { it.updatedAt }
    }

    fun newId(): String = PresentIds.next("p")

    /** [p] added to the library and opened. */
    fun create(p: Presentation) {
        library = listOf(p) + library.filterNot { it.id == p.id }
        openIt(p)
        save(p, now = false)
    }

    fun openIt(p: Presentation) {
        open = p
        history.clear()
        slideId = p.slides.firstOrNull()?.id
        selection = emptySet()
        slidesPicked = emptySet()
        editingText = null
        revision++
    }

    fun close() {
        flush()
        open = null
        selection = emptySet()
        editingText = null
    }

    /**
     * [next] made the open presentation, the one before it kept for Undo as [label]. Edits
     * sharing a [coalesce] key — one drag, one stretch of typing — are one step of Undo.
     */
    fun commit(next: Presentation, label: String, coalesce: String? = null) {
        val before = open ?: return
        if (next == before) return
        history.push(before, label, coalesce)
        put(next)
    }

    /** Ends a coalescing run: the drag let go. */
    fun seal() = history.seal()

    private fun put(next: Presentation) {
        val stamped = next.copy(updatedAt = System.currentTimeMillis())
        open = stamped
        library = listOf(stamped) + library.filterNot { it.id == stamped.id }
        if (slideId != null && stamped.slide(slideId) == null) slideId = stamped.slides.firstOrNull()?.id
        selection = selection.filter { id -> stamped.slide(slideId)?.element(id) != null }.toSet()
        revision++
        save(stamped)
    }

    fun undo() {
        val o = open ?: return
        history.undo(o)?.let(::put)
    }

    fun redo() {
        val o = open ?: return
        history.redo(o)?.let(::put)
    }

    val canUndo: Boolean get() = revision >= 0 && history.canUndo
    val canRedo: Boolean get() = revision >= 0 && history.canRedo

    // ---- the files ---------------------------------------------------------------

    private var saveJob: Job? = null
    private var pending: Presentation? = null

    /** Written a moment after the last edit, a drag's hundred moves one write. */
    private fun save(p: Presentation, now: Boolean = false) {
        pending = p
        saveJob?.cancel()
        saveJob = scope.launch {
            if (!now) delay(400)
            write(p)
        }
    }

    /** The last edit written now: the page left, the window closing. */
    fun flush() {
        val p = pending ?: return
        saveJob?.cancel()
        scope.launch { write(p) }
    }

    private suspend fun write(p: Presentation) = io.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val target = File(dir, "${p.id}.json")
            val temp = File(dir, "${p.id}.json.tmp")
            temp.writeText(PresentCodec.encode(p))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
        if (pending === p) pending = null
    }

    fun delete(p: Presentation) {
        // A write still waiting for it would bring the file back.
        if (pending?.id == p.id) {
            saveJob?.cancel()
            pending = null
        }
        library = library.filterNot { it.id == p.id }
        if (open?.id == p.id) open = null
        scope.launch { io.withLock { withContext(Dispatchers.IO) { File(dir, "${p.id}.json").delete() } } }
    }

    /** A copy of [p] under a new id and name, put in the library. */
    fun duplicate(p: Presentation): Presentation {
        val copy = p.copy(id = newId(), name = "${p.name} (copy)", createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        library = listOf(copy) + library
        save(copy, now = true)
        return copy
    }

    fun rename(name: String) {
        val o = open ?: return
        commit(o.copy(name = name.ifBlank { o.name }), "Rename", coalesce = "rename")
    }

    // ---- pictures ----------------------------------------------------------------

    /**
     * [bytes] kept as a picture of the presentation's: named by its content, so the same
     * picture added twice is one file, and a file once written never changes — which is what
     * lets sync carry it as it is.
     */
    suspend fun putMedia(bytes: ByteArray, extension: String): String = withContext(Dispatchers.IO) {
        val ext = extension.lowercase().ifBlank { "png" }.let { if (it == "jpeg") "jpg" else it }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val name = "$hash.$ext"
        mediaDir.mkdirs()
        val f = File(mediaDir, name)
        if (!f.exists()) f.writeBytes(bytes)
        name
    }

    /** The picture [name], decoded once and kept; null while it decodes, or when it will not. */
    fun bitmap(name: String?): ImageBitmap? {
        if (name.isNullOrBlank()) return null
        bitmaps[name]?.let { return it }
        if (name !in decoding) {
            decoding += name
            scope.launch {
                val image = withContext(Dispatchers.IO) {
                    runCatching { decodePicture(File(mediaDir, name).readBytes()) }.getOrNull()
                }
                if (image != null) bitmaps[name] = image
            }
        }
        return null
    }

    private val decoding = HashSet<String>()

    // ---- presenting ----------------------------------------------------------------

    /** Starts the open presentation from slide [from] (its index), or the first. */
    fun present(from: Int? = null, rehearse: Boolean = false) {
        val p = open ?: return
        flush()
        val show = CompiledShow(p)
        val start = from?.let { show.at(it) }?.takeIf { p.slides.getOrNull(it.slide)?.hidden == false } ?: show.first ?: return
        playing = Playing(show, start).also { it.rehearse = rehearse }
    }

    fun next() {
        val pl = playing ?: return
        if (pl.blank != null) { pl.blank = null; return }
        if (pl.overview) { toggleOverview(); return }
        pl.show.next(pl.cursor)?.let { pl.moveTo(it) }
    }

    fun previous() {
        val pl = playing ?: return
        if (pl.blank != null) { pl.blank = null; return }
        if (pl.overview) { toggleOverview(); return }
        pl.show.previous(pl.cursor)?.let { pl.moveTo(it, back = true) }
    }

    fun first() { playing?.let { pl -> pl.show.first?.let { pl.moveTo(it, back = true) } } }

    fun last() { playing?.let { pl -> pl.show.last?.let { pl.moveTo(it) } } }

    fun goToSlide(index: Int) { playing?.let { pl -> pl.show.at(index)?.let { pl.moveTo(it, back = index < pl.cursor.slide) } } }

    fun toggleOverview() {
        val pl = playing ?: return
        if (pl.show.presentation.deck == null) return
        pl.overview = !pl.overview
        pl.overviewSince = pl.now
    }

    fun blank(kind: String) { playing?.let { it.blank = if (it.blank == kind) null else kind } }

    fun toggleLaser() { playing?.let { it.laser = !it.laser; if (it.laser) it.pen = false } }

    fun togglePen() { playing?.let { it.pen = !it.pen; if (it.pen) it.laser = false } }

    fun clearInk() { playing?.let { it.ink = emptyList() } }

    fun toggleNotes() { playing?.let { it.notes = !it.notes } }

    /** The show ended: a rehearsal's times written onto its slides, the slide it ended on put in view. */
    fun stop() {
        val pl = playing ?: return
        playing = null
        val p = open ?: return
        slideId = p.slides.getOrNull(pl.cursor.slide)?.id ?: slideId
        if (pl.rehearse) {
            pl.show.slides.getOrNull(pl.cursor.slide)?.let { s ->
                pl.timings[s.id] = (pl.timings[s.id] ?: 0L) + (System.nanoTime() - pl.slideStartedAt) / 1_000_000
            }
            if (pl.timings.isNotEmpty()) {
                commit(p.copy(slides = p.slides.map { s -> pl.timings[s.id]?.let { s.copy(durationMs = it) } ?: s }), "Rehearsed timings")
            }
        }
    }

    /** The element ids of the slide in view that a click on [id] selects: its whole group. */
    fun groupOf(id: String): Set<String> {
        val s = slide ?: return setOf(id)
        val g = s.element(id)?.group ?: return setOf(id)
        return s.elements.filter { it.group == g }.map(Element::id).toSet()
    }
}

/** What the card picker's cards go into. */
enum class PickTarget { NEW, NEW_ROW, REPLACE, FOCUS }

/** The editor's right panel. */
enum class PropsTab(val title: String) {
    SLIDE("Slide"),
    ELEMENT("Item"),
    ANIMATE("Builds"),
    DECK("Deck"),
    THEME("Theme"),
}
