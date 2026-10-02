package com.kaiharimoto.mastertool.core.present.play

import com.kaiharimoto.mastertool.core.present.DeckFocus
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.Themes
import com.kaiharimoto.mastertool.core.present.stage.Box
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import com.kaiharimoto.mastertool.core.present.stage.StageCard
import com.kaiharimoto.mastertool.core.present.stage.StageFrame
import com.kaiharimoto.mastertool.core.present.stage.StageLabel
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout

/** Where a presentation is: which slide, and how many of its clicks have been taken. */
data class Cursor(val slide: Int, val step: Int = 0)

/**
 * A presentation made ready to play: each slide's builds, its webcam zone, its stage and, for a
 * deck slide, the deck's frame — all worked out once, so the presenter, the studio and the
 * recorder draw the same thing at the same moment.
 */
class CompiledShow(val presentation: Presentation) {
    val slides: List<Slide> = presentation.slides
    val builds: List<SlideBuilds> = slides.map { Builds.compile(it) }
    private val theme = Themes.of(presentation)

    /** The deck slides' focuses in order, and which slide index each is. */
    private val deckIndices: List<Int> = slides.indices.filter { slides[it].deck != null && !slides[it].hidden }
    private val steps: List<DeckFocus> = deckIndices.map { slides[it].deck!! }

    private val frames = HashMap<Pair<Int, Boolean>, StageFrame>()

    /** The webcam zone on slide [i], or null. */
    fun zone(i: Int): Box? = slides.getOrNull(i)?.let { WebcamLayout.zone(presentation.webcam, it.camera) }

    /** The room slide [i] leaves its content. */
    fun stage(i: Int): Box = WebcamLayout.stage(zone(i))

    /** The deck's frame on slide [i] (a deck slide), or in [overview] the whole deck for any slide. */
    fun deckFrame(i: Int, overview: Boolean = false): StageFrame? {
        val deck = presentation.deck ?: return null
        val slide = slides.getOrNull(i) ?: return null
        if (slide.deck == null && !overview) return null
        return frames.getOrPut(i to overview) {
            val step = deckStepOf(i)
            // Every band round the camera is tried, and the one that draws the largest cards kept.
            WebcamLayout.bands(zone(i)).map { band ->
                DeckStage.frame(deck, steps, presentation.style, step, band, overview, theme.dim)
            }.maxBy { f -> f.cards.maxOfOrNull { it.box.w } ?: 0f }
        }
    }

    /** The deck step slide [i] is, or the last one before it for any other slide. */
    fun deckStepOf(i: Int): Int {
        val at = deckIndices.indexOf(i)
        if (at >= 0) return at
        return deckIndices.indexOfLast { it < i }.coerceAtLeast(0)
    }

    /** The slide of the first deck step whose focus shows card [key]: where a click in the overview goes. */
    fun slideFor(key: String): Int? {
        val deck = presentation.deck ?: return null
        val step = steps.indexOfFirst { !it.all && key in DeckStage.focused(deck, it) }
        return deckIndices.getOrNull(step)
    }

    // ---- moving through the show ------------------------------------------------

    private fun visible(i: Int) = slides.getOrNull(i)?.hidden == false

    val first: Cursor? get() = slides.indices.firstOrNull(::visible)?.let { Cursor(it) }

    val last: Cursor? get() = slides.indices.lastOrNull(::visible)?.let { Cursor(it, builds[it].count - 1) }

    /** The next click: the slide's next build, else the next shown slide's arrival; null at the end. */
    fun next(c: Cursor): Cursor? {
        if (c.step < builds.getOrNull(c.slide)?.count?.minus(1) ?: 0) return c.copy(step = c.step + 1)
        val n = (c.slide + 1 until slides.size).firstOrNull(::visible) ?: return null
        return Cursor(n, 0)
    }

    /** A click back: the build before, else the shown slide before, every build done. */
    fun previous(c: Cursor): Cursor? {
        if (c.step > 0) return c.copy(step = c.step - 1)
        val p = (c.slide - 1 downTo 0).firstOrNull(::visible) ?: return null
        return Cursor(p, builds[p].count - 1)
    }

    /** Slide [i] arrived, or with every build done when [done]. */
    fun at(i: Int, done: Boolean = false): Cursor? {
        if (i !in slides.indices) return null
        return Cursor(i, if (done) builds[i].count - 1 else 0)
    }

    /** How many clicks the whole show takes, for the presenter's count. */
    val clicks: Int get() = slides.indices.filter(::visible).sumOf { builds[it].count }

    fun state(c: Cursor, element: Element, ms: Long): ElementState =
        builds.getOrNull(c.slide)?.let { Builds.state(it, element, c.step, ms) } ?: ElementState.SHOWN
}

/**
 * The deck's glide from one frame to the next (the reader's `DrawingView` idea): copies in
 * both move and change; copies only in [b] come in, small and clear, growing into place;
 * copies only in [a] go, fading where they stood.
 */
object StageTween {
    fun between(a: StageFrame?, b: StageFrame?, t: Float): StageFrame {
        if (b == null) return a?.let { fade(it, 1f - t) } ?: StageFrame.EMPTY
        if (a == null || t >= 1f) return if (a == null) fade(b, t) else b
        if (t <= 0f) return a
        val from = a.cards.associateBy { it.key }
        val into = b.cards.associateBy { it.key }
        val cards = ArrayList<StageCard>(b.cards.size + a.cards.size)
        for (c in b.cards) {
            val o = from[c.key]
            cards += if (o != null) {
                c.copy(
                    box = o.box.lerp(c.box, t),
                    alpha = o.alpha + (c.alpha - o.alpha) * t,
                    emphasis = o.emphasis + (c.emphasis - o.emphasis) * t,
                    outer = if (t < 0.5f) o.outer else c.outer,
                )
            } else {
                val s = 0.82f + 0.18f * t
                val bx = c.box
                c.copy(
                    box = Box(bx.cx - bx.w * s / 2f, bx.cy - bx.h * s / 2f, bx.w * s, bx.h * s),
                    alpha = c.alpha * t,
                )
            }
        }
        for (o in a.cards) if (o.key !in into) cards += o.copy(alpha = o.alpha * (1f - t), emphasis = 0f)
        val labels = ArrayList<StageLabel>()
        val fromLabels = a.labels.associateBy { it.group }
        val intoLabels = b.labels.associateBy { it.group }
        for (l in b.labels) {
            val o = fromLabels[l.group]
            labels += if (o != null) l.copy(box = o.box.lerp(l.box, t), alpha = o.alpha + (l.alpha - o.alpha) * t) else l.copy(alpha = l.alpha * t)
        }
        for (o in a.labels) if (o.group !in intoLabels) labels += o.copy(alpha = o.alpha * (1f - t))
        return StageFrame(cards, labels, if (t < 0.5f) a.note else b.note, a.colors + b.colors)
    }

    private fun fade(f: StageFrame, t: Float): StageFrame = f.copy(
        cards = f.cards.map { it.copy(alpha = it.alpha * t) },
        labels = f.labels.map { it.copy(alpha = it.alpha * t) },
    )
}
