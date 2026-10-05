package com.kaiharimoto.neue.present.record

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.kaiharimoto.mastertool.core.present.PresentCodec
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeClock
import com.kaiharimoto.mastertool.core.present.record.TakeLog
import com.kaiharimoto.mastertool.core.present.record.TakeNames
import com.kaiharimoto.mastertool.core.present.record.TakePaths
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.Note
import com.kaiharimoto.neue.platform.Capture
import com.kaiharimoto.neue.platform.LiveCamera
import com.kaiharimoto.neue.platform.LiveMic
import com.kaiharimoto.neue.platform.Voice
import com.kaiharimoto.neue.present.Playing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A take being recorded (1.1.13): counted in, then recording — the presenter's every move written down
 * ([TakeLog]) on the take's own clock ([TakeClock]), the camera's frames and the microphone's sound into files
 * beside it — paused and carried on, then stopped and kept. Nothing is drawn while recording: the video is made
 * afterwards from what was done, so recording costs the show nothing and the video is the slides to the pixel.
 */
class Recording internal constructor(
    val presentation: Presentation,
    val id: String,
    name: String,
    val dir: File,
    internal val playing: Playing,
) {
    enum class Phase { COUNTING, RECORDING, PAUSED }

    /** "Take 3": settled once the folder is read, as the count-in begins. */
    var name by mutableStateOf(name)
        internal set

    var phase by mutableStateOf(Phase.COUNTING)
        internal set

    /** Seconds left of the count-in; 0 once recording. */
    var countdown by mutableIntStateOf(0)
        internal set

    /** The take's length so far, ticked for the bar. */
    var shownMs by mutableLongStateOf(0L)
        internal set

    /** Chapters marked so far. */
    var marks by mutableIntStateOf(0)
        internal set

    val startedAt: Long = System.currentTimeMillis()
    internal val clock = TakeClock { System.nanoTime() / 1_000_000 }
    internal val log = TakeLog()
    internal var camera: LiveCamera? = null
    internal var mic: LiveMic? = null
    internal var job: Job? = null

    /** Whether there is sound, so the bar can say so. */
    var hasSound by mutableStateOf(false)
        internal set

    /** Whether the camera is being recorded. */
    var hasCamera by mutableStateOf(false)
        internal set
}

/**
 * R, the record button, the recording light: record a take — presenting from this slide first when nothing plays —
 * or, while recording, pause, and carry on. During the count-in it is put off.
 */
fun TakeLibrary.record(h: NeueHolders) {
    val rec = recording
    if (rec != null) {
        when (rec.phase) {
            Recording.Phase.COUNTING -> stopRecording(h, thenShow = false)
            Recording.Phase.RECORDING -> {
                rec.clock.pause()
                rec.phase = Recording.Phase.PAUSED
                rec.shownMs = rec.clock.elapsed
            }
            Recording.Phase.PAUSED -> {
                rec.clock.resume()
                rec.phase = Recording.Phase.RECORDING
            }
        }
        return
    }
    if (!Capture.canRecord) {
        h.neue.note = Note(Capture.whyNot ?: "Recording is not on this device")
        return
    }
    val present = h.present
    if (present.playing == null) {
        if (present.open == null) return
        present.present(present.slideIndex)
    }
    val pl = present.playing ?: return
    begin(h, pl)
}

private fun TakeLibrary.begin(h: NeueHolders, pl: Playing) {
    val p = pl.show.presentation
    val prefs = h.neue.prefs.record
    val id = TakePaths.id(System.currentTimeMillis())
    val dir = File(folder(p.id), id)
    // The name is settled as the folder is read, below; "Take" until then.
    val rec = Recording(p, id, "Take", dir, pl)
    recording = rec
    rec.countdown = prefs.countIn
    // Whoever is listening (Ai's microphone, the duel's push-to-talk) lets go: the take has the microphone.
    Voice.stopListening()
    if (p.webcam.enabled) wantCamera("take", prefs.camera)
    rec.job = launch {
        val name = withContext(Dispatchers.IO) {
            dir.mkdirs()
            // Frozen as it is now: a later edit never changes the take until it is rendered on purpose.
            File(dir, Take.PRESENTATION).writeText(PresentCodec.encode(p))
            val names = folder(p.id).listFiles { f -> f.isDirectory && f != dir }.orEmpty()
                .mapNotNull { f -> com.kaiharimoto.mastertool.core.present.record.TakeCodec.decode(runCatching { File(f, Take.FILE).readText() }.getOrNull())?.name }
            TakeNames.next(names)
        }
        val mic = if (prefs.muted) null else Capture.openMic(prefs.microphone)
        rec.mic = mic
        for (i in prefs.countIn downTo 1) {
            rec.countdown = i
            delay(1_000)
        }
        rec.countdown = 0
        // The camera had the count-in to open; a take without it records the slides and the sound.
        val cam = if (p.webcam.enabled) camera else null
        rec.camera = cam
        rec.clock.start()
        rec.phase = Recording.Phase.RECORDING
        rec.hasCamera = cam?.startRecording(File(dir, Take.CAMERA), { rec.clock.elapsed }, { rec.clock.paused }) == true
        rec.hasSound = mic?.startRecording(File(dir, Take.AUDIO)) { rec.clock.paused } == true
        rec.log.observe(0L, pl.view())
        val started = Take(id, p.id, name, rec.startedAt, events = emptyList(), finished = false, fps = prefs.renderFps, mirror = p.webcam.mirror)
        write(started)
        rec.name = name
        // Every change the audience sees, written down on the take's clock — during a pause too, at the pause's
        // moment, so the take carries on from where the presenter went while it was paused.
        launch {
            snapshotFlow { pl.view() }.collect { v -> rec.log.observe(rec.clock.elapsed, v) }
        }
        var ticks = 0
        while (true) {
            rec.shownMs = rec.clock.elapsed
            delay(200)
            // Every five seconds the take so far is written down, unfinished: if the app closes mid-take, what was
            // recorded until then still renders.
            if (++ticks % 25 == 0) {
                write(
                    started.copy(
                        durationMs = rec.clock.elapsed,
                        events = rec.log.events,
                        camera = Take.CAMERA.takeIf { rec.hasCamera },
                        audio = Take.AUDIO.takeIf { rec.hasSound },
                    ),
                )
            }
        }
    }
    if (p.webcam.enabled && camera == null && cameraProblem != null) h.neue.note = Note("Recording without the camera: $cameraProblem")
}

/** M, or a right-click on the recording light: a chapter here, named after the slide on screen. */
fun TakeLibrary.mark(h: NeueHolders) {
    val rec = recording ?: return
    if (rec.phase == Recording.Phase.COUNTING) return
    val at = rec.clock.elapsed
    val slide = rec.playing.show.slides.getOrNull(rec.playing.cursor.slide)
    val title = (slide?.section ?: slide?.title)?.trim().orEmpty().ifBlank { "Chapter ${rec.marks + 1}" }
    rec.log.mark(at, "$title (${TakeNames.length(at)})")
    rec.marks++
    h.neue.note = Note("Chapter marked at ${TakeNames.length(at)}")
}

/**
 * Shift R, Stop on the bar, or the show ending: the take kept — its files closed, its events and length written —
 * and, when [thenShow], the Takes dialog opened on it. A count-in stopped keeps nothing.
 */
fun TakeLibrary.stopRecording(h: NeueHolders, thenShow: Boolean) {
    val rec = recording ?: return
    recording = null
    rec.job?.cancel()
    val counting = rec.phase == Recording.Phase.COUNTING
    rec.clock.pause()
    val length = rec.clock.elapsed
    val name = rec.name
    launch {
        withContext(Dispatchers.IO) {
            rec.camera?.stopRecording()
            rec.mic?.stopRecording()
            rec.mic?.close()
        }
        releaseCamera("take")
        if (counting || length < MIN_TAKE_MS) {
            withContext(Dispatchers.IO) { rec.dir.deleteRecursively() }
            if (!counting) h.neue.note = Note("Too short to keep: a take is at least a second")
            return@launch
        }
        val camera = File(rec.dir, Take.CAMERA).takeIf { rec.hasCamera && it.isFile && it.length() > 0 }
        val audio = File(rec.dir, Take.AUDIO).takeIf { rec.hasSound && it.isFile && it.length() > 44 }
        val take = Take(
            id = rec.id,
            presentationId = rec.presentation.id,
            name = name,
            startedAt = rec.startedAt,
            durationMs = length,
            events = rec.log.events,
            camera = camera?.name,
            audio = audio?.name,
            fps = h.neue.prefs.record.renderFps,
            finished = true,
            mirror = rec.presentation.webcam.mirror,
        )
        write(take)
        if (!thenShow) madeThisShow++
        val p = rec.presentation
        if (thenShow) {
            show(p)
        } else {
            h.neue.note = Note("$name kept: ${TakeNames.length(length)}. Render it from Takes", action = "Takes", lastsMs = 10_000) { show(p) }
        }
    }
}

/** The show ended (Esc, the last click, the console's End): a take recording is kept, and the takes made shown. */
fun TakeLibrary.showEnded(h: NeueHolders, p: Presentation) {
    if (recording != null) {
        stopRecording(h, thenShow = true)
    } else if (madeThisShow > 0) {
        show(p)
    }
    madeThisShow = 0
}

/** A take shorter than this is a slip of the key, not a take. */
private const val MIN_TAKE_MS = 1_000L
