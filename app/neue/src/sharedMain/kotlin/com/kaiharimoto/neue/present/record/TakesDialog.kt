package com.kaiharimoto.neue.present.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskScope
import com.kaiharimoto.mastertool.core.present.record.EncoderPick
import com.kaiharimoto.mastertool.core.present.record.RecordPrefs
import com.kaiharimoto.mastertool.core.present.record.Take
import com.kaiharimoto.mastertool.core.present.record.TakeNames
import com.kaiharimoto.mastertool.core.update.DesktopOs
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.FieldLabel
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Meter
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog
import com.kaiharimoto.neue.kit.MuInput
import com.kaiharimoto.neue.kit.MuSelect
import com.kaiharimoto.neue.kit.MuSwitch
import com.kaiharimoto.neue.kit.Progress
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.platform.Capture
import com.kaiharimoto.neue.platform.LiveMic
import com.kaiharimoto.neue.platform.Platform
import com.kaiharimoto.neue.theme.Mu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The takes of the open presentation (1.1.13, Present ▾ › Takes, Ctrl Alt R): each with its length and when it was
 * recorded, rendered into a video or not; Render (again), Play, Save a copy, Show in folder, the YouTube chapters
 * copied, Rename and Delete. A render runs here with its picture and the time left, and can be stopped.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TakesDialog(h: NeueHolders) {
    val takes = h.present.takes
    val p = h.present.open
    val c = Mu.colors
    LaunchedEffect(p?.id) { p?.let { takes.load(it.id) } }
    MuDialog(
        "Takes${p?.let { " of ${it.name}" } ?: ""}",
        { takes.showing = false; takes.renaming = null; takes.confirmDelete = null },
        width = 720.dp,
        description = "A take is your show as you gave it: every click, your camera and your voice. Render one to make the video — " +
            "the slides are drawn again, to the pixel, with your camera in its zone. Takes stay on this computer: they are never synced or backed up.",
        footer = {
            MuButton("Camera and microphone…", { takes.settingUp = true }, variant = BtnVariant.GHOST)
            if (p != null) {
                Tip("Present from this slide and record", kbd = keyFor(DeskAction.PRESENT_RECORD, DeskScope.PRESENT_EDIT)) {
                    MuButton("Record a take", { takes.showing = false; takes.record(h) }, variant = BtnVariant.PRIMARY, enabled = Capture.canRecord, reason = Capture.whyNot)
                }
            }
        },
    ) {
        if (!Capture.canRecord) Help(Capture.whyNot.orEmpty())
        takes.rendering?.let { RenderPanel(h, it) }
        val list = takes.takes.filter { it.presentationId == p?.id }
        if (list.isEmpty()) {
            Small(
                "No takes yet. Record presents from this slide and counts you in; R pauses and carries on, M marks a chapter, Shift R stops and keeps the take, and Esc ends the show.",
                color = c.ink70,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { t -> key(t.id) { TakeRow(h, t) } }
        }
    }
    takes.confirmDelete?.let { t ->
        MuDialog(
            "Delete ${t.name}?",
            { takes.confirmDelete = null },
            width = 420.dp,
            description = "Its camera, its sound and its video go from this computer. The presentation stays.",
            footer = {
                MuButton("Keep it", { takes.confirmDelete = null }, variant = BtnVariant.GHOST)
                MuButton("Delete", { takes.delete(t); takes.confirmDelete = null }, variant = BtnVariant.PRIMARY)
            },
        ) {}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TakeRow(h: NeueHolders, t: Take) {
    val takes = h.present.takes
    val c = Mu.colors
    val video = remember(t.rendered, t.renderedAt) { takes.video(t) }
    val size by produceState(0L, t.id, t.renderedAt) { value = withContext(Dispatchers.IO) { takes.size(t) } }
    val rendering = takes.rendering?.take?.id == t.id
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.ink12).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (takes.renaming == t.id) {
                var text by remember(t.id) { mutableStateOf(t.name) }
                MuInput(text, { text = it }, Modifier.weight(1f), dense = true, onSubmit = {
                    takes.rename(t, text)
                    takes.renaming = null
                })
                MuButton("Done", { takes.rename(t, text); takes.renaming = null }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            } else {
                Micro(t.name, Modifier.weight(1f), color = c.ink)
                Mono(TakeNames.length(t.durationMs), color = c.ink)
            }
        }
        Small(
            listOfNotNull(
                SimpleDateFormat("d MMM yyyy, HH:mm").format(Date(t.startedAt)),
                if (t.camera != null) "camera" else "no camera",
                if (t.audio != null) "sound" else "no sound",
                "${t.events.count { it.kind == "GO" }} clicks",
                if (size > 0) TakeNames.size(size) else null,
                when {
                    rendering -> "rendering"
                    video != null -> "video: ${t.renderedCodec?.let(EncoderPick::describe) ?: video.extension}"
                    else -> "not rendered yet"
                },
            ).joinToString(" · "),
            color = c.ink70,
        )
        if (!t.finished) Help("The app closed while this take was recording: it renders up to the last moment it wrote down (every five seconds).")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (video != null) MuButton("Play", { takes.play(h, t) }, size = BtnSize.SM, variant = BtnVariant.SECONDARY)
            MuButton(
                if (video != null) "Render again" else "Render",
                { takes.render(h, t) },
                size = BtnSize.SM,
                variant = if (video == null) BtnVariant.PRIMARY else BtnVariant.GHOST,
                enabled = takes.rendering == null && t.durationMs >= 1_000,
                reason = if (t.durationMs < 1_000) "Nothing of this take was kept" else "One take renders at a time",
            )
            if (video != null) {
                MuButton(if (Platform.canShare) "Share" else "Save a copy…", { takes.saveCopy(h, t) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            Tip("The chapters for the video's description, from the slides' titles and sections and the chapters you marked") {
                MuButton("Copy chapters", { takes.copyChapters(h, t) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            if (Platform.os != DesktopOs.ANDROID) MuButton("Show in folder", { takes.showFolder(t) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            MuButton("Rename", { takes.renaming = t.id }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            MuButton("Delete…", { takes.confirmDelete = t }, size = BtnSize.SM, variant = BtnVariant.GHOST)
        }
    }
}

/** The render running: the frame just made, how far, the time left, and Stop. */
@Composable
private fun RenderPanel(h: NeueHolders, task: RenderTask) {
    val c = Mu.colors
    val pr = task.progress
    Column(
        Modifier.fillMaxWidth().border(1.dp, c.ink).padding(12.dp).padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Micro("Rendering ${task.take.name}", color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(240.dp).aspectRatio(16f / 9f).background(c.ink12).border(1.dp, c.ink25)) {
                pr?.preview?.let { img -> CameraPicture({ img }, mirror = false) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fraction = pr?.let { if (it.total > 0) it.done.toFloat() / it.total else null }
                Small(pr?.stage ?: "Getting the art and the camera ready", color = c.ink70)
                Progress(fraction)
                if (pr != null && pr.total > 0 && pr.done > 30) {
                    val spent = System.currentTimeMillis() - task.startedAt
                    val left = spent * (pr.total - pr.done) / pr.done
                    Mono("${pr.done} of ${pr.total} frames · about ${TakeNames.length(left)} left", color = c.ink70)
                }
            }
        }
        MuButton("Stop rendering", { h.present.takes.cancelRender() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
    }
}

/**
 * Camera and microphone (1.1.13; Present ▾, the Takes dialog, the start step): which of each, the camera's picture
 * live and the microphone's level, the count-in, the frame rate, and the camera shown while presenting. This
 * computer's own (`NeuePreferences.record`).
 */
@Composable
fun RecordSetupDialog(h: NeueHolders) {
    val takes = h.present.takes
    MuDialog(
        "Camera and microphone",
        { takes.settingUp = false },
        width = 560.dp,
        description = "For recording takes of a presentation. Your camera stands in each slide's camera zone, cropped to its shape.",
        footer = {
            MuButton("Done", {
                h.neue.update { it.copy(record = it.record.copy(chosen = true)) }
                takes.settingUp = false
            }, variant = BtnVariant.PRIMARY)
        },
    ) {
        RecordDevices(h, preview = true)
    }
}

/** The pickers, the preview and the meter: the setup dialog's and the start step's. */
@Composable
fun RecordDevices(h: NeueHolders, preview: Boolean) {
    val takes = h.present.takes
    val c = Mu.colors
    val prefs = h.neue.prefs.record
    fun put(next: (RecordPrefs) -> RecordPrefs) = h.neue.update { it.copy(record = next(it.record)) }
    if (!Capture.canRecord) {
        Help(Capture.whyNot.orEmpty())
        return
    }
    val cameras by produceState<List<String>?>(null) { value = Capture.cameras() }
    val mics by produceState<List<String>?>(null) { value = Capture.microphones() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldLabel("Camera")
        when {
            cameras == null -> Small("Looking for cameras…", color = c.ink45)
            cameras.isNullOrEmpty() -> Help("No camera was found. A take still records the slides and your voice.")
            else -> {
                val list = cameras.orEmpty()
                val chosen = prefs.camera?.takeIf { it in list } ?: list.first()
                MuSelect(chosen, list, { it }, { v -> put { r -> r.copy(camera = v) } }, Modifier.fillMaxWidth(), small = true)
            }
        }
        if (preview && !cameras.isNullOrEmpty()) {
            DisposableEffect(prefs.camera) {
                takes.wantCamera("setup", prefs.camera)
                onDispose { takes.releaseCamera("setup") }
            }
            val mirror = h.present.open?.webcam?.mirror ?: true
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(c.ink12).border(1.dp, c.ink)) {
                val cam = takes.camera
                if (cam != null) {
                    CameraPicture({ cam.picture.value }, mirror)
                    cam.error.value?.let { Small(it, Modifier.align(Alignment.Center).background(c.paper).padding(8.dp), color = c.ink) }
                } else {
                    Small(takes.cameraProblem ?: "Opening the camera…", Modifier.align(Alignment.Center).padding(16.dp), color = c.ink70)
                }
            }
            if (Platform.os == DesktopOs.MAC) {
                Help("The first time, macOS asks whether Neue Master Tool may use the camera and the microphone. Said no? System Settings › Privacy & Security › Camera, and Microphone.")
            }
        }
        FieldLabel("Microphone")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MuSwitch(!prefs.muted, { on -> put { r -> r.copy(muted = !on) } })
            Small(if (prefs.muted) "No sound" else "Your voice")
        }
        if (!prefs.muted) {
            when {
                mics == null -> Small("Looking for microphones…", color = c.ink45)
                mics.isNullOrEmpty() -> Help("No microphone was found: takes record without sound.")
                else -> {
                    val list = mics.orEmpty()
                    val chosen = prefs.microphone?.takeIf { it in list } ?: list.first()
                    MuSelect(chosen, list, { it }, { v -> put { r -> r.copy(microphone = v) } }, Modifier.fillMaxWidth(), small = true)
                    if (preview) MicLevel(prefs.microphone)
                }
            }
        }
        FieldLabel("Count-in", hint = "before a take begins")
        Segmented(prefs.countIn, listOf(0, 3, 5), { if (it == 0) "None" else "$it seconds" }, { v -> put { r -> r.copy(countdown = v) } }, small = true)
        FieldLabel("Frames a second", hint = "of the video")
        Segmented(prefs.renderFps, listOf(30, 60), { "$it" }, { v -> put { r -> r.copy(fps = v) } }, small = true)
        Help("30 is what most deck profiles need and renders twice as fast; 60 makes the cards glide smoother.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MuSwitch(prefs.liveCamera, { v -> put { r -> r.copy(liveCamera = v) } })
            Small("Show my camera in its zone while presenting, recording or not", Modifier.weight(1f))
        }
    }
}

/** The microphone's level, live, so the person sees it hears them. */
@Composable
private fun MicLevel(name: String?) {
    var mic by remember { mutableStateOf<LiveMic?>(null) }
    LaunchedEffect(name) {
        mic?.let { m -> withContext(Dispatchers.IO) { m.close() } }
        mic = null
        // A moment, so a microphone let go of just now is free again.
        delay(150)
        mic = Capture.openMic(name)
    }
    DisposableEffect(Unit) { onDispose { mic?.close() } }
    val level = mic?.level?.value ?: 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Meter((level * 12).toInt().coerceIn(0, 12), 12, Modifier.weight(1f))
        Small(if (mic == null) "Opening…" else "Speak: the cells follow your voice", color = Mu.colors.ink45)
    }
}
