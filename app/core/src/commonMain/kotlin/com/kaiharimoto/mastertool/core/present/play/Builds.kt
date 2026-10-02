package com.kaiharimoto.mastertool.core.present.play

import com.kaiharimoto.mastertool.core.present.Anim
import com.kaiharimoto.mastertool.core.present.Ease
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Slide
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** One build placed in time: which element, what it does, and when inside its click step. */
data class Track(val element: String, val anim: Anim, val startMs: Int) {
    val endMs: Int get() = startMs + anim.durationMs.coerceAtLeast(0)
}

/**
 * A slide's builds in click steps (kai, 1.0.70: "animated and interactive"). Step 0 holds
 * whatever runs on its own when the slide arrives (builds before the first "On click");
 * each "On click" begins the next step, "With previous" starts with the build before it,
 * "After previous" when it ends.
 */
data class SlideBuilds(val steps: List<List<Track>>) {
    /** How many states the slide has: its arrival and one per click. */
    val count: Int get() = steps.size

    fun length(step: Int): Int = steps.getOrNull(step)?.maxOfOrNull { it.endMs } ?: 0
}

/** Where an element stands at a moment of a build: how visible, moved, scaled, cut or typed. */
data class ElementState(
    val alpha: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    /** The share of the element revealed by a wipe, left to right. */
    val clip: Float = 1f,
    /** The share of its text typed so far. */
    val reveal: Float = 1f,
    /** A glow round it, 0..1. */
    val glow: Float = 0f,
) {
    val visible: Boolean get() = alpha > 0.001f

    companion object {
        val SHOWN = ElementState()
        val HIDDEN = ElementState(alpha = 0f)
    }
}

object Builds {
    /** How far a rise, drop or fly travels, in canvas units. */
    const val RISE = 60f
    const val FLY = 1100f

    fun compile(slide: Slide): SlideBuilds {
        val all = slide.elements.flatMap { e -> e.animations.map { e.id to it } }
            .sortedWith(compareBy({ it.second.order }, { it.second.id }))
        val steps = mutableListOf<MutableList<Track>>(mutableListOf())
        var prevStart = 0
        var prevEnd = 0
        for ((element, anim) in all) {
            if (anim.trigger == Anim.ON_CLICK) {
                steps += mutableListOf<Track>()
                prevStart = 0
                prevEnd = 0
            }
            val start = when (anim.trigger) {
                Anim.ON_CLICK -> anim.delayMs
                Anim.AFTER_PREVIOUS -> prevEnd + anim.delayMs
                else -> prevStart + anim.delayMs
            }
            val track = Track(element, anim, start.coerceAtLeast(0))
            steps.last() += track
            prevStart = track.startMs
            prevEnd = max(prevEnd, track.endMs)
        }
        return SlideBuilds(steps)
    }

    /**
     * [element]'s state at click [step] of [builds], [ms] into that step. Builds of earlier
     * steps have finished; [ms] past the step's length is the step finished. An element
     * whose first build is an entrance is hidden until it runs.
     */
    fun state(builds: SlideBuilds, element: Element, step: Int, ms: Long): ElementState {
        val mine = builds.steps.withIndex().flatMap { (i, tracks) -> tracks.filter { it.element == element.id }.map { i to it } }
        if (mine.isEmpty()) return ElementState.SHOWN
        var state = if (mine.first().second.anim.kind == Anim.ENTRANCE) ElementState.HIDDEN else ElementState.SHOWN
        var grown = 1f
        for ((i, track) in mine) {
            if (i > step) break
            val p = if (i < step) 1f else progress(track, ms)
            if (p <= 0f && i == step) {
                // Not begun: an entrance waiting keeps it hidden, anything else leaves it be.
                break
            }
            val a = track.anim
            val eased = Ease.apply(if (a.kind == Anim.EXIT) Ease.IN else Ease.OUT, p)
            state = when (a.kind) {
                Anim.ENTRANCE -> enter(a.effect, eased).let { it.copy(scale = it.scale * grown) }
                Anim.EXIT -> if (p >= 1f) ElementState.HIDDEN else enter(a.effect, 1f - eased).let { it.copy(scale = it.scale * grown) }
                else -> {
                    val e = emphasis(a.effect, p)
                    if (a.effect == Anim.GROW) grown = 1f + 0.12f * (if (p >= 1f) 1f else eased)
                    state.copy(
                        scale = if (a.effect == Anim.GROW) grown else grown * e.scale,
                        rotation = e.rotation,
                        glow = e.glow,
                    )
                }
            }
        }
        return state
    }

    private fun progress(track: Track, ms: Long): Float {
        val d = track.anim.durationMs
        val t = ms - track.startMs
        if (t <= 0) return 0f
        if (d <= 0) return 1f
        return (t.toFloat() / d).coerceIn(0f, 1f)
    }

    /** An entrance [effect] at eased progress [p]: 0 not yet there, 1 arrived. */
    fun enter(effect: String, p: Float): ElementState = when (effect) {
        Anim.RISE -> ElementState(alpha = p, dy = (1f - p) * RISE)
        Anim.DROP -> ElementState(alpha = p, dy = -(1f - p) * RISE)
        Anim.ZOOM -> ElementState(alpha = p, scale = 0.6f + 0.4f * p)
        Anim.WIPE -> ElementState(alpha = if (p > 0f) 1f else 0f, clip = p)
        Anim.FLY_LEFT -> ElementState(alpha = (p * 2f).coerceAtMost(1f), dx = -(1f - p) * FLY)
        Anim.FLY_RIGHT -> ElementState(alpha = (p * 2f).coerceAtMost(1f), dx = (1f - p) * FLY)
        Anim.TYPE -> ElementState(alpha = if (p > 0f) 1f else 0f, reveal = p)
        else -> ElementState(alpha = p)
    }

    /** An emphasis [effect] at linear progress [p]: it ends where it began, but for a grow. */
    fun emphasis(effect: String, p: Float): ElementState {
        val wave = sin(PI * p).toFloat()
        return when (effect) {
            Anim.PULSE -> ElementState(scale = 1f + 0.08f * wave)
            Anim.SPIN -> ElementState(rotation = if (p >= 1f) 0f else 360f * Ease.apply(Ease.IN_OUT, p))
            Anim.GLOW -> ElementState(glow = wave)
            else -> ElementState()
        }
    }
}
