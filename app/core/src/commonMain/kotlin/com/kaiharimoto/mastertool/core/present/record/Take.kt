package com.kaiharimoto.mastertool.core.present.record

import com.kaiharimoto.mastertool.core.present.play.Cursor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One thing that happened while a take was recorded (1.0.72), at [at] ms of the take's own clock
 * (a pause stops it, so the take has no gaps): a move to a slide and build, the whole deck shown or
 * put away, a blank screen, the laser, the pen, a mark. Kinds are strings, so a newer build's kinds
 * pass through an older one unread.
 */
@Serializable
data class TakeEvent(
    val at: Long,
    val kind: String,
    val slide: Int = 0,
    val step: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val text: String = "",
) {
    companion object {
        const val GO = "GO"
        const val OVERVIEW = "OVERVIEW"
        const val OVERVIEW_OFF = "OVERVIEW_OFF"
        /** text: "B" black, "W" white, "" back to the slide. */
        const val BLANK = "BLANK"
        const val LASER = "LASER"
        const val LASER_OFF = "LASER_OFF"
        const val INK_START = "INK_START"
        const val INK = "INK"
        const val INK_CLEAR = "INK_CLEAR"
        /** A moment the creator marked, for a chapter or a cut. */
        const val MARK = "MARK"
    }
}

/**
 * A take (1.0.72): what happened, and the files recorded beside it in its folder — the camera's video,
 * the microphone's sound, and the presentation as it was when recording began ([PRESENTATION], read
 * by `PresentCodec`, forgiving), frozen so a later edit never changes what the take shows until it is
 * rendered again on purpose.
 */
@Serializable
data class Take(
    val id: String,
    val presentationId: String,
    val name: String = "",
    val startedAt: Long = 0L,
    val durationMs: Long = 0L,
    val events: List<TakeEvent> = emptyList(),
    /** The camera's video in the take's folder, or null when the camera was off. */
    val camera: String? = null,
    /** The microphone's sound in the take's folder. */
    val audio: String? = null,
    /** How far the camera's first frame is behind the take's clock, in ms. */
    val cameraOffsetMs: Long = 0L,
    val fps: Int = 30,
    /** The video rendered from it last, if any: a file name in the take's folder. */
    val rendered: String? = null,
    val version: Int = 1,
    /**
     * Whether recording ended as it should (1.1.13): false while it records, and for ever after when the app
     * closed or crashed mid-take — the camera's file may then be cut short, and the take says so.
     */
    val finished: Boolean = true,
    /** The encoder the last render used ([EncoderPick]), for its line in the list. */
    val renderedCodec: String? = null,
    /** When it was last rendered, epoch ms. */
    val renderedAt: Long = 0L,
    /** The camera's picture mirrored when it is drawn, as the presentation said when recording began. */
    val mirror: Boolean = true,
) {
    companion object {
        const val FILE = "take.json"
        const val PRESENTATION = "presentation.json"
        const val CAMERA = "camera.mkv"
        const val AUDIO = "audio.wav"
    }
}

/** What the stage shows at one moment of a take: the presenter's state, replayed. */
data class TakeState(
    val cursor: Cursor,
    /** The slide and build moved from, for the transition; null at the start. */
    val from: Cursor?,
    /** When the last move was, in take ms. */
    val since: Long,
    val back: Boolean,
    val overview: Boolean,
    val overviewSince: Long,
    val blank: String?,
    val laser: Pair<Float, Float>?,
    val ink: List<List<Pair<Float, Float>>>,
)

object TakeTimeline {
    /** The presenter's state at [t] ms into [events]: replayed from the start, so any frame is drawn alone. */
    fun stateAt(events: List<TakeEvent>, t: Long, start: Cursor = Cursor(0)): TakeState {
        var cursor = start
        var from: Cursor? = null
        var since = 0L
        var back = false
        var overview = false
        var overviewSince = 0L
        var blank: String? = null
        var laser: Pair<Float, Float>? = null
        val ink = ArrayList<MutableList<Pair<Float, Float>>>()
        for (e in events) {
            if (e.at > t) break
            when (e.kind) {
                TakeEvent.GO -> {
                    val next = Cursor(e.slide, e.step)
                    if (next != cursor) {
                        back = next.slide < cursor.slide || next.slide == cursor.slide && next.step < cursor.step
                        if (next.slide != cursor.slide) ink.clear()
                        from = cursor
                        cursor = next
                        since = e.at
                    }
                }
                TakeEvent.OVERVIEW -> if (!overview) { overview = true; overviewSince = e.at }
                TakeEvent.OVERVIEW_OFF -> if (overview) { overview = false; overviewSince = e.at }
                TakeEvent.BLANK -> blank = e.text.ifBlank { null }
                TakeEvent.LASER -> laser = e.x to e.y
                TakeEvent.LASER_OFF -> laser = null
                TakeEvent.INK_START -> ink += mutableListOf(e.x to e.y)
                TakeEvent.INK -> ink.lastOrNull()?.add(e.x to e.y) ?: ink.add(mutableListOf(e.x to e.y))
                TakeEvent.INK_CLEAR -> ink.clear()
            }
        }
        return TakeState(cursor, from, since, back, overview, overviewSince, blank, laser, ink.map { it.toList() })
    }

    /** The slide in view at each move: (take ms, slide), first at 0. */
    fun slides(events: List<TakeEvent>, start: Int = 0): List<Pair<Long, Int>> {
        val out = arrayListOf(0L to start)
        for (e in events) if (e.kind == TakeEvent.GO && e.slide != out.last().second) out += e.at to e.slide
        return out
    }

    /** How many frames a take of [durationMs] at [fps] is. */
    fun frames(durationMs: Long, fps: Int): Int = ((durationMs * fps + 999) / 1000).toInt().coerceAtLeast(1)

    /** Frame [n]'s moment, in take ms. */
    fun msOf(n: Int, fps: Int): Long = n * 1000L / fps
}

object TakeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    fun encode(t: Take): String = json.encodeToString(Take.serializer(), t)

    /** A take, or null when the file does not read; unknown keys and kinds are skipped. */
    fun decode(text: String?): Take? = text?.let { runCatching { json.decodeFromString(Take.serializer(), it) }.getOrNull() }
}
