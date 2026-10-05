package com.kaiharimoto.mastertool.core.present.record

import com.kaiharimoto.mastertool.core.present.play.Cursor
import kotlin.math.abs

/**
 * What the presenter shows at one moment, as the recorder reads it off the show playing (1.1.13): the slide and
 * build, the whole deck, a blank screen, the laser's place, the pen's strokes in canvas units.
 */
data class PresenterView(
    val cursor: Cursor,
    val overview: Boolean = false,
    val blank: String? = null,
    val laser: Pair<Float, Float>? = null,
    val ink: List<List<Pair<Float, Float>>> = emptyList(),
)

/**
 * A take's events, written as the show goes (1.1.13): [observe] is handed what the presenter shows whenever it
 * changes, and writes down only the difference — a move, the whole deck shown or put away, a blank, the laser
 * switched or moved, each new point of the pen — so [TakeTimeline.stateAt] replays it to the same picture. The
 * laser is kept to [LASER_MS] between points (a 30 fps video needs no more); the pen keeps every point, since a
 * stroke drawn is what the viewer sees.
 */
class TakeLog {
    private val out = ArrayList<TakeEvent>()
    private var last: PresenterView? = null
    private var laserAt = -1L

    val events: List<TakeEvent> get() = out.toList()

    /** The presenter's [view] at [at] ms of the take. The first call sets where the take begins. */
    fun observe(at: Long, view: PresenterView) {
        val before = last
        last = view
        if (before == null) {
            out += TakeEvent(at, TakeEvent.GO, slide = view.cursor.slide, step = view.cursor.step)
            if (view.overview) out += TakeEvent(at, TakeEvent.OVERVIEW)
            view.blank?.let { out += TakeEvent(at, TakeEvent.BLANK, text = it) }
            view.laser?.let { (x, y) -> out += TakeEvent(at, TakeEvent.LASER, x = x, y = y); laserAt = at }
            view.ink.forEach { stroke -> strokeFrom(at, stroke, 0) }
            return
        }
        val slideChanged = view.cursor.slide != before.cursor.slide
        if (view.cursor != before.cursor) out += TakeEvent(at, TakeEvent.GO, slide = view.cursor.slide, step = view.cursor.step)
        if (view.overview != before.overview) out += TakeEvent(at, if (view.overview) TakeEvent.OVERVIEW else TakeEvent.OVERVIEW_OFF)
        if (view.blank != before.blank) out += TakeEvent(at, TakeEvent.BLANK, text = view.blank.orEmpty())
        val l = view.laser
        when {
            l == null && before.laser != null -> out += TakeEvent(at, TakeEvent.LASER_OFF)
            l != null && before.laser == null -> { out += TakeEvent(at, TakeEvent.LASER, x = l.first, y = l.second); laserAt = at }
            l != null && before.laser != null && moved(l, before.laser) && at - laserAt >= LASER_MS -> {
                out += TakeEvent(at, TakeEvent.LASER, x = l.first, y = l.second)
                laserAt = at
            }
            // A point kept back by the limit is still the laser's place: the next observation writes it.
            l != null && before.laser != null && at - laserAt < LASER_MS -> last = view.copy(laser = before.laser)
        }
        ink(at, before.ink, view.ink, slideChanged)
    }

    /** A moment the creator marked ([TakeEvent.MARK]): a chapter, named [text] when it has words. */
    fun mark(at: Long, text: String) {
        out += TakeEvent(at, TakeEvent.MARK, text = text)
    }

    private fun ink(at: Long, was: List<List<Pair<Float, Float>>>, now: List<List<Pair<Float, Float>>>, slideChanged: Boolean) {
        if (now == was) return
        // A move to another slide takes the pen's lines with it on replay too.
        val from = if (slideChanged) emptyList() else was
        val grows = now.size >= from.size && from.indices.all { i -> i == from.lastIndex || now[i] == from[i] } &&
            (from.isEmpty() || now[from.lastIndex].size >= from.last().size && now[from.lastIndex].subList(0, from.last().size) == from.last())
        if (!grows) {
            // Cleared, or anything else: begin again from nothing.
            if (from.isNotEmpty()) out += TakeEvent(at, TakeEvent.INK_CLEAR)
            now.forEach { strokeFrom(at, it, 0) }
            return
        }
        if (from.isNotEmpty()) {
            val i = from.lastIndex
            for (p in now[i].drop(from[i].size)) out += TakeEvent(at, TakeEvent.INK, x = p.first, y = p.second)
        }
        for (s in now.drop(from.size)) strokeFrom(at, s, 0)
    }

    private fun strokeFrom(at: Long, stroke: List<Pair<Float, Float>>, from: Int) {
        stroke.drop(from).forEachIndexed { i, (x, y) ->
            out += TakeEvent(at, if (i == 0 && from == 0) TakeEvent.INK_START else TakeEvent.INK, x = x, y = y)
        }
    }

    private fun moved(a: Pair<Float, Float>, b: Pair<Float, Float>) = abs(a.first - b.first) >= 1f || abs(a.second - b.second) >= 1f

    companion object {
        /** The laser's points at most this often, in ms. */
        const val LASER_MS = 33L
    }
}
