package com.kaiharimoto.mastertool.studio

import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.present.record.mark
import com.kaiharimoto.neue.present.record.record
import kotlinx.coroutines.delay
import java.io.File

/**
 * Present's recording (1.1.13), with the synthetic camera and a silent microphone (`-Dneue.camera=synthetic`), on the
 * real holder:
 *
 * - `--present-record=setup` the camera and microphone dialog, the camera live in it;
 * - `=countdown` a take counting in over the slide;
 * - `=bar` a take recording: two clicks, a chapter marked, the bar with its time;
 * - `=paused` the same, paused;
 * - `=takes` the take kept as the show ends, and the Takes dialog it opens;
 * - `=rendering` that take rendering, caught a third of the way;
 * - `=render` rendered to the end — the video copied beside the shot.
 *
 * Recording runs on the real clock (the count-in, the camera's frames), so the window is turned a frame at a time while
 * real time passes.
 */
internal suspend fun studioRecord(h: NeueHolders, map: Map<String, String>, clock: FrameClock, out: File, name: String) {
    val what = map["present-record"] ?: return
    System.setProperty("neue.camera", "synthetic")
    val takes = h.present.takes
    suspend fun live(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            clock.run(1)
            delay(15)
        }
    }
    fun say(s: String) = println("[neue-studio] record: $s")
    if (what == "setup") {
        takes.settingUp = true
        live(3_000)
        say("setup, camera ${takes.camera?.name ?: "none"} (${takes.cameraProblem ?: "open"})")
        return
    }
    h.neue.update { it.copy(record = it.record.copy(countdown = if (what == "countdown") 3 else 1)) }
    takes.record(h)
    if (what == "countdown") {
        live(1_200)
        say("counting in: ${takes.recording?.countdown}")
        return
    }
    // The count-in, then a little over five seconds of a show: two clicks, a chapter marked.
    live(1_600)
    say("recording ${takes.recording?.phase}, camera ${takes.recording?.hasCamera}, sound ${takes.recording?.hasSound}")
    live(1_500)
    h.present.next()
    live(1_500)
    h.present.next()
    live(1_000)
    takes.mark(h)
    live(1_200)
    if (what == "paused") {
        takes.record(h)
        live(400)
    }
    if (what == "bar" || what == "paused") {
        say("${takes.recording?.phase} at ${takes.recording?.shownMs} ms")
        return
    }
    // The show ends: the take is kept and the Takes dialog opens on it.
    h.present.stop()
    live(2_500)
    val take = takes.takes.firstOrNull()
    say("kept ${take?.name}: ${take?.durationMs} ms, ${take?.events?.size} events, camera ${take?.camera}, sound ${take?.audio}; dialog ${takes.showing}")
    if (what == "takes" || take == null) return
    takes.render(h, take)
    val started = System.currentTimeMillis()
    while (System.currentTimeMillis() - started < 15 * 60_000) {
        live(250)
        val task = takes.rendering
        val pr = task?.progress
        if (what == "rendering" && pr != null && pr.total > 0 && pr.done * 3 >= pr.total) {
            say("rendering ${pr.done} of ${pr.total}: ${pr.stage}")
            return
        }
        if (task == null && System.currentTimeMillis() - started > 2_000) break
    }
    live(500)
    val done = takes.takes.firstOrNull { it.id == take.id }
    val video = done?.let { takes.video(it) }
    if (video != null) video.copyTo(File(out, "$name-${video.name.replace(' ', '-')}"), overwrite = true)
    say("rendered: ${video?.name} ${video?.length()} bytes, ${done?.renderedCodec}, in ${(System.currentTimeMillis() - started) / 1000} s")
}
