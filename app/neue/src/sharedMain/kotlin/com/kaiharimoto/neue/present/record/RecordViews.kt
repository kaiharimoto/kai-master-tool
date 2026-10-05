package com.kaiharimoto.neue.present.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.mastertool.core.input.PresentAction
import com.kaiharimoto.mastertool.core.input.PresentGestures
import com.kaiharimoto.mastertool.core.input.PresentPress
import com.kaiharimoto.mastertool.core.input.PresentTarget
import com.kaiharimoto.mastertool.core.present.record.CameraFit
import com.kaiharimoto.mastertool.core.present.record.TakeNames
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.platform.Capture
import com.kaiharimoto.neue.platform.LiveCamera
import com.kaiharimoto.neue.present.Presentations
import com.kaiharimoto.neue.present.rememberSlideContext
import com.kaiharimoto.neue.present.wantArt
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/**
 * The camera's picture filling its zone (1.1.13): cropped to the zone's shape ([CameraFit]), mirrored as people expect
 * to see themselves when [mirror] says. [picture] is read where it is drawn, so a new frame redraws, never recomposes.
 * The zone's own outline clips it (`SlideView`'s camera frame).
 */
@Composable
fun CameraPicture(picture: () -> ImageBitmap?, mirror: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        picture()?.let { img ->
            val crop = CameraFit.cover(img.width, img.height, size.width, size.height)
            val dst = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            if (mirror) {
                scale(-1f, 1f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                    drawImage(img, IntOffset(crop[0], crop[1]), IntSize(crop[2], crop[3]), dstSize = dst)
                }
            } else {
                drawImage(img, IntOffset(crop[0], crop[1]), IntSize(crop[2], crop[3]), dstSize = dst)
            }
        }
    }
}

/** The live camera for the camera zone, or null while none is open: what `SlideView`'s `camera` slot takes. */
fun liveCamera(camera: LiveCamera?, mirror: Boolean): (@Composable () -> Unit)? =
    camera?.let { cam -> { CameraPicture({ cam.picture.value }, mirror) } }

/**
 * The recording bar over the presenter's slide (1.1.13): Record while nothing records; the count-in; then the light,
 * the take's time, Pause or Carry on, Mark and Stop. The presenter's alone — the audience's window never draws it —
 * and never in the video, which is drawn afterwards. Away while the pointer rests ([idle]) unless recording.
 */
@Composable
fun RecordBar(present: Presentations, modifier: Modifier = Modifier, idle: Boolean = false) {
    val takes = present.takes
    val h = takes.holders ?: return
    if (!Capture.canRecord || present.playing == null) return
    val rec = takes.recording
    if (rec == null && idle) return
    val c = Mu.colors
    fun kbd(a: DeskAction) = keyFor(a, com.kaiharimoto.mastertool.core.input.DeskScope.PRESENTING)
    Row(
        modifier.background(c.paper).border(1.dp, c.ink).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            rec == null -> Tip("Record a take from here: your camera, your voice and every click", kbd = kbd(DeskAction.PRESENT_RECORD)) {
                MuButton("Record", { takes.record(h) }, size = BtnSize.SM, variant = BtnVariant.PRIMARY)
            }
            rec.phase == Recording.Phase.COUNTING -> {
                Micro("Recording in ${rec.countdown}", color = c.ink)
                MuButton("Not now", { takes.stopRecording(h, thenShow = false) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
            }
            else -> {
                RecordLight(takes, h, paused = rec.phase == Recording.Phase.PAUSED)
                Mono(TakeNames.length(rec.shownMs), color = c.ink, size = 13.sp)
                Micro(
                    when {
                        rec.phase == Recording.Phase.PAUSED -> "Paused"
                        !rec.hasCamera && !rec.hasSound -> "Recording the slides"
                        !rec.hasSound -> "Recording, no sound"
                        else -> "Recording"
                    },
                    color = c.ink70,
                )
                Tip(if (rec.phase == Recording.Phase.PAUSED) "Carry on recording" else "Pause: the take stops until you carry on", kbd = kbd(DeskAction.PRESENT_RECORD)) {
                    MuButton(if (rec.phase == Recording.Phase.PAUSED) "Carry on" else "Pause", { takes.record(h) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
                Tip("A chapter here, for the video's description", kbd = kbd(DeskAction.PRESENT_MARK)) {
                    MuButton("Mark", { takes.mark(h) }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
                Tip("Stop and keep the take; the show goes on", kbd = kbd(DeskAction.PRESENT_RECORD_STOP)) {
                    MuButton("Stop", { takes.stopRecording(h, thenShow = false) }, size = BtnSize.SM, variant = BtnVariant.SECONDARY)
                }
            }
        }
    }
}

/**
 * The recording light: a filled square while recording, an open one while paused. A click (a tap) pauses or carries
 * on; a right-click (a held finger) marks a chapter — `PresentMouse`/`PresentTouch`'s rows, read by
 * [PresentGestures.classify].
 */
@Composable
private fun RecordLight(takes: TakeLibrary, h: NeueHolders, paused: Boolean) {
    val c = Mu.colors
    Box(
        Modifier.size(14.dp)
            .background(if (paused) c.paper else c.ink)
            .border(2.dp, c.ink)
            .cursorPointer(caption = if (paused) "Carry on" else "Pause")
            .pointerInput(takes) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.type != PointerEventType.Press) continue
                        val first = down.changes.firstOrNull() ?: continue
                        val finger = first.type == PointerType.Touch || first.type == PointerType.Stylus
                        val secondary = down.buttons.isSecondaryPressed
                        first.consume()
                        val pressedAt = first.uptimeMillis
                        var moved = false
                        var held = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull() ?: break
                            if ((ch.position - first.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (ch.uptimeMillis - pressedAt > viewConfiguration.longPressTimeoutMillis) held = true
                            ch.consume()
                            if (e.type == PointerEventType.Release || !ch.pressed) break
                        }
                        when (PresentGestures.classify(PresentPress(PresentTarget.RECORD_LIGHT, finger = finger, secondary = secondary, held = held, moved = moved))) {
                            PresentAction.RECORD -> takes.record(h)
                            PresentAction.MARK -> takes.mark(h)
                            else -> Unit
                        }
                    }
                }
            },
    )
}

/** [action]'s key in [scope], as the help prints it: R while presenting, Ctrl Shift R in the editor. */
internal fun keyFor(action: DeskAction, scope: com.kaiharimoto.mastertool.core.input.DeskScope): String? =
    DeskShortcuts.all.firstOrNull { it.action == action && it.scope == scope }?.chord?.let(DeskShortcuts::kbd)

/** The count-in, large over the slide: 3, 2, 1. The presenter's alone, like the bar. */
@Composable
fun CountIn(present: Presentations, modifier: Modifier = Modifier) {
    val rec = present.takes.recording ?: return
    if (rec.phase != Recording.Phase.COUNTING || rec.countdown <= 0) return
    val c = Mu.colors
    Column(
        modifier.background(c.paper).border(1.dp, c.ink).padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuText("${rec.countdown}", style = MuType.mono(LocalMuFonts.current, 96.sp), color = c.ink)
        Micro("Recording ${rec.name.takeIf { it != "Take" }?.let { "$it " } ?: ""}begins", color = c.ink70)
    }
}

/**
 * Present's recording, held in the window for good (1.1.13): it hands the library the holders, opens the camera for
 * the live preview while a show plays with the camera on, gives a render what it draws with, and draws the Takes and
 * the camera-and-microphone dialogs. Composed whatever page is open, like the slide render host.
 */
@Composable
fun TakesHost(h: NeueHolders) {
    val takes = h.present.takes
    takes.holders = h
    val pl = h.present.playing
    val webcamOn = pl?.show?.presentation?.webcam?.enabled == true
    val prefs = h.neue.prefs.record
    // The live camera in its zone while presenting, recording or not, when the person wants it.
    val wantsShow = pl != null && webcamOn && prefs.liveCamera && Capture.canRecord
    DisposableEffect(wantsShow, prefs.camera) {
        if (wantsShow) takes.wantCamera("show", prefs.camera)
        onDispose { if (wantsShow) takes.releaseCamera("show") }
    }
    // The show ended: a take recording is kept and the takes made shown. A window swapped for immersive mode is not
    // an end: the show is still playing.
    DisposableEffect(pl) {
        onDispose {
            if (pl != null && h.present.playing !== pl) takes.showEnded(h, pl.show.presentation)
        }
    }
    takes.rendering?.let { task ->
        val ctx = rememberSlideContext(h, task.presentation)
        val locals = currentCompositionLocalContext
        LaunchedEffect(task) {
            wantArt(h, task.presentation)
            task.ready(ctx, locals)
        }
    }
    if (pl == null) {
        if (takes.showing) TakesDialog(h)
        if (takes.settingUp) RecordSetupDialog(h)
    }
}
