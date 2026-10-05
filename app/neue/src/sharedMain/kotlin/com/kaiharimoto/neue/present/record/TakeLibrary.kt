package com.kaiharimoto.neue.present.record

import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.record.Chapters
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeCodec
import com.kaiharimoto.mastertool.core.present.record.TakeNames
import com.kaiharimoto.mastertool.core.present.record.TakePaths
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.platform.Capture
import com.kaiharimoto.neue.platform.LiveCamera
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.platform.RenderProgress
import com.kaiharimoto.neue.platform.RenderRequest
import com.kaiharimoto.neue.platform.RenderResult
import com.kaiharimoto.neue.platform.TakeVideo
import com.kaiharimoto.neue.platform.deliverCopy
import com.kaiharimoto.neue.present.paint.SlideContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A take rendering into a video (1.1.13): the take, its frozen presentation, how far it is, and the picture of the
 * frame just made. The window hands it what it draws with ([ready]) — the frozen presentation's slide context and the
 * window's composition locals — and the work runs on the library's own scope, so presenting, or the window being
 * swapped for immersive mode, never stops it.
 */
class RenderTask(val take: Take, val presentation: Presentation) {
    var progress by mutableStateOf<RenderProgress?>(null)
        internal set

    /** When it began, ms, for the time left. */
    val startedAt: Long = System.currentTimeMillis()

    internal val drawing = CompletableDeferred<Pair<SlideContext, CompositionLocalContext?>>()

    /** Handed by the window: what to draw with. */
    fun ready(ctx: SlideContext, locals: CompositionLocalContext?) {
        if (!drawing.isCompleted) drawing.complete(ctx to locals)
    }

    internal var job: Job? = null

    /** The last picture made, for the dialog's preview. */
    val preview: ImageBitmap? get() = progress?.preview
}

/**
 * Present's takes (1.1.13, `docs/present/RECORDING.md`): every take of a presentation, the one recording now, the one
 * rendering, and the camera open for the live preview. Takes are files under `<data>/present/<id>/takes/<take>/`
 * ([TakePaths]) and this device's own — never synced, never backed up.
 */
class TakeLibrary(private val presentDir: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()

    /** The takes of [shownFor], newest first. */
    var takes by mutableStateOf<List<Take>>(emptyList())
        private set

    /** The presentation [takes] are of. */
    var shownFor by mutableStateOf<String?>(null)
        private set

    /** The Takes dialog, open. */
    var showing by mutableStateOf(false)

    /** The camera and microphone dialog, open. */
    var settingUp by mutableStateOf(false)

    /** The take recording now, counting in, recording or paused. */
    var recording by mutableStateOf<Recording?>(null)
        internal set

    /** The take rendering now. */
    var rendering by mutableStateOf<RenderTask?>(null)
        private set

    /** The camera open for the live preview, if one is. */
    var camera by mutableStateOf<LiveCamera?>(null)
        private set

    /** Why the camera did not open, in words; null when it did, or nobody asked. */
    var cameraProblem by mutableStateOf<String?>(null)
        private set

    /** The take being renamed in the dialog, by id. */
    var renaming by mutableStateOf<String?>(null)

    /** A take waiting on the delete confirmation. */
    var confirmDelete by mutableStateOf<Take?>(null)

    /** Takes made in the show playing now: the dialog opens on them when it ends. */
    internal var madeThisShow = 0

    /** The window's holders, for a press on the recording bar: the settings and the notes. Set by the window. */
    var holders: NeueHolders? = null

    fun folder(presentationId: String): File = File(presentDir, "$presentationId/${TakePaths.FOLDER}")

    fun folderOf(take: Take): File = File(folder(take.presentationId), take.id)

    // ---- the list ------------------------------------------------------------------------------

    /** [presentationId]'s takes read from their folders. */
    fun load(presentationId: String) {
        shownFor = presentationId
        scope.launch {
            val read = withContext(Dispatchers.IO) { readAll(presentationId) }
            if (shownFor == presentationId) takes = read
        }
    }

    private fun readAll(presentationId: String): List<Take> =
        folder(presentationId).listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> TakeCodec.decode(runCatching { File(dir, Take.FILE).readText() }.getOrNull()) }
            .sortedByDescending { it.startedAt }

    /** The Takes dialog opened on [p]'s takes. */
    fun show(p: Presentation) {
        load(p.id)
        showing = true
    }

    internal suspend fun write(take: Take) = io.withLock {
        withContext(Dispatchers.IO) {
            val dir = folderOf(take).apply { mkdirs() }
            val temp = File(dir, "${Take.FILE}.tmp")
            temp.writeText(TakeCodec.encode(take))
            val target = File(dir, Take.FILE)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
        if (shownFor == take.presentationId) takes = (listOf(take) + takes.filterNot { it.id == take.id }).sortedByDescending { it.startedAt }
    }

    fun rename(take: Take, name: String) {
        val clean = name.trim().ifBlank { return }
        scope.launch { write(take.copy(name = clean)) }
    }

    fun delete(take: Take) {
        if (rendering?.take?.id == take.id) cancelRender()
        takes = takes.filterNot { it.id == take.id }
        scope.launch { io.withLock { withContext(Dispatchers.IO) { folderOf(take).deleteRecursively() } } }
    }

    /** Every take of a presentation deleted with it. */
    fun deleteAll(presentationId: String) {
        scope.launch { io.withLock { withContext(Dispatchers.IO) { File(presentDir, presentationId).deleteRecursively() } } }
    }

    /** The YouTube chapters of [take], read off its frozen presentation; empty when YouTube would not take them. */
    suspend fun chapters(take: Take): String {
        val p = frozen(take) ?: return ""
        return Chapters.text(Chapters.of(p, take.events, take.durationMs))
    }

    /** The presentation as it was when [take] began recording. */
    suspend fun frozen(take: Take): Presentation? = withContext(Dispatchers.IO) {
        PresentCodec.decode(runCatching { File(folderOf(take), Take.PRESENTATION).readText() }.getOrNull())
    }

    /** The video rendered from [take] last, if it is still there. */
    fun video(take: Take): File? = take.rendered?.let { File(folderOf(take), it) }?.takeIf { it.isFile }

    /** The space [take]'s folder takes on this device. */
    fun size(take: Take): Long = folderOf(take).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun play(h: NeueHolders, take: Take) {
        val v = video(take) ?: run { h.neue.note = Note("Render ${take.name} first: there is no video of it yet"); return }
        Platform.open(v)
    }

    fun showFolder(take: Take) = Platform.open(folderOf(take))

    fun saveCopy(h: NeueHolders, take: Take) {
        val v = video(take) ?: return
        val mime = if (v.extension == "mp4") "video/mp4" else "video/webm"
        scope.launch { deliverCopy(v, TakePaths.fileName(take.name, v.extension), mime)?.let { h.neue.note = Note(it) } }
    }

    fun copyChapters(h: NeueHolders, take: Take) {
        scope.launch {
            val text = chapters(take)
            if (text.isBlank()) {
                h.neue.note = Note("No chapters: YouTube needs at least three, each ten seconds or more. Slide titles and sections name them, and M marks one while recording.")
            } else {
                Platform.copy(text)
                h.neue.note = Note("Chapters copied: paste them into the video's description")
            }
        }
    }

    // ---- the camera ----------------------------------------------------------------------------

    private val cameraUsers = LinkedHashSet<String>()
    private var cameraName: String? = null
    private var opening: Job? = null

    /**
     * The camera wanted by [user] ("show", "setup", "take"), named [name]: opened by the first, closed when the last
     * lets go. A different name reopens it.
     */
    fun wantCamera(user: String, name: String?) {
        if (!Capture.canRecord) return
        cameraUsers += user
        if (camera != null && cameraName == name) return
        reopen(name)
    }

    fun releaseCamera(user: String) {
        cameraUsers -= user
        if (cameraUsers.isEmpty()) {
            opening?.cancel()
            val c = camera
            camera = null
            cameraName = null
            if (c != null) scope.launch(Dispatchers.IO) { c.close() }
        }
    }

    private fun reopen(name: String?) {
        opening?.cancel()
        val old = camera
        camera = null
        cameraName = name
        opening = scope.launch {
            if (old != null) withContext(Dispatchers.IO) { old.close() }
            val opened = Capture.openCamera(name)
            if (cameraUsers.isEmpty()) {
                opened?.let { withContext(Dispatchers.IO) { it.close() } }
                return@launch
            }
            camera = opened
            cameraProblem = if (opened == null) Capture.lastError ?: "No camera was found" else null
        }
    }

    // ---- rendering -----------------------------------------------------------------------------

    /** [take] rendered into a video; the window supplies what it is drawn with ([RenderTask.ready]). */
    fun render(h: NeueHolders, take: Take) {
        if (!TakeVideo.canRender) {
            h.neue.note = Note(Capture.whyNot ?: "Rendering is not on this device")
            return
        }
        if (rendering != null) {
            h.neue.note = Note("One take renders at a time: ${rendering?.take?.name} is rendering")
            return
        }
        scope.launch {
            val p = frozen(take) ?: run { h.neue.note = Note("${take.name}'s presentation could not be read"); return@launch }
            val task = RenderTask(take, p)
            rendering = task
            task.job = scope.launch {
                val (ctx, locals) = task.drawing.await()
                // At the rate it was recorded for (Settings' 30 or 60, as recording began).
                val result = TakeVideo.render(RenderRequest(take, folderOf(take), p), ctx, locals) { pr -> task.progress = pr }
                finish(h, task, result)
            }
            task.job?.invokeOnCompletion { if (rendering === task) rendering = null }
        }
    }

    private suspend fun finish(h: NeueHolders, task: RenderTask, result: RenderResult) {
        when (result) {
            is RenderResult.Done -> {
                val dir = folderOf(task.take)
                val named = File(dir, TakePaths.fileName(task.take.name, result.file.extension))
                withContext(Dispatchers.IO) {
                    // The video rendered before goes: a take keeps one.
                    video(task.take)?.takeIf { it != named }?.delete()
                    if (result.file != named) {
                        named.delete()
                        if (!result.file.renameTo(named)) result.file.copyTo(named, overwrite = true).also { result.file.delete() }
                    }
                }
                val done = task.take.copy(rendered = named.name, renderedCodec = result.codec, renderedAt = System.currentTimeMillis())
                write(done)
                h.neue.note = Note("${done.name} is a video: ${TakeNames.length(done.durationMs)}, ${EncoderPick.describe(result.codec)}", action = "Play", lastsMs = 12_000) { Platform.open(named) }
            }
            is RenderResult.Failed -> h.neue.note = Note("${task.take.name} could not be rendered: ${result.reason}", lastsMs = 12_000)
            RenderResult.Cancelled -> h.neue.note = Note("Rendering ${task.take.name} stopped")
        }
        rendering = null
    }

    fun cancelRender() {
        rendering?.job?.cancel()
        rendering = null
    }

    // ---- recording -----------------------------------------------------------------------------

    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)
}
