package com.kaiharimoto.neue.present.play

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.present.Ease
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Geometry
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.Transition
import com.kaiharimoto.mastertool.core.present.edit.Transform
import com.kaiharimoto.mastertool.core.present.play.CompiledShow
import com.kaiharimoto.mastertool.core.present.play.ElementState
import com.kaiharimoto.mastertool.core.present.play.StageTween
import com.kaiharimoto.mastertool.core.present.stage.DeckStage
import com.kaiharimoto.mastertool.core.present.stage.StageFrame
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout
import com.kaiharimoto.neue.cursor.cursor
import com.kaiharimoto.mastertool.core.input.CursorMode
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.present.Playing
import com.kaiharimoto.neue.present.Presentations
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.present.paint.DeckLayer
import com.kaiharimoto.neue.present.paint.drawInk
import com.kaiharimoto.neue.present.paint.drawLaser
import com.kaiharimoto.neue.present.paint.drawThemeBackground
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.math.min

/** How long the whole-deck view takes to come and go. */
private const val OVERVIEW_MS = 650

/**
 * A presentation playing (1.0.70), over the whole window: the slide in the theme, its
 * builds and transitions running on one frame clock that sleeps when nothing moves, the deck
 * gliding between deck slides, the whole deck on demand (D), the laser, the pen, a blank
 * screen and the speaker notes. Clicks and keys move it on; Esc ends it.
 */
@Composable
fun PresentStage(present: Presentations, ctx: SlideContext, modifier: Modifier = Modifier, camera: (@Composable () -> Unit)? = null) {
    val pl = present.playing ?: return
    val show = pl.show
    val p = show.presentation
    val allKeys = remember(p.deck) { p.deck?.let { d -> DeckStage.copies(d).map { it.key } }.orEmpty() }

    // The frame clock: every frame while a transition, a build, the whole-deck view, the laser
    // or the pen moves; asleep once they have settled, until the next move wakes it.
    LaunchedEffect(pl) {
        while (true) {
            withFrameNanos { pl.now = it }
            if (pl.since == 0L) pl.since = pl.now
            val settled = pl.ms > settleMs(pl) && (pl.now - pl.overviewSince) / 1_000_000 > OVERVIEW_MS + 50 && !pl.laser
            if (settled) {
                snapshotFlow { Triple(pl.since, pl.overviewSince, pl.laser) }.drop(1).first()
            }
        }
    }

    var laserAt by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(
        modifier.fillMaxSize().background(Color.Black)
            .cursor(if (pl.laser || pl.pen) CursorMode.NATIVE else CursorMode.DEFAULT)
            .pointerInput(pl) {
                // Clicks and taps move on; a held finger points; the pen draws.
                awaitPointerEventScope {
                    var downAt: Offset? = null
                    var downTime = 0L
                    var drawing = false
                    var secondaryDown = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val s = min(w / Presentation.WIDTH, h / Presentation.HEIGHT)
                        val ox = (w - Presentation.WIDTH * s) / 2f
                        val oy = (h - Presentation.HEIGHT * s) / 2f
                        val cx = (change.position.x - ox) / s
                        val cy = (change.position.y - oy) / s
                        when (event.type) {
                            PointerEventType.Move -> {
                                if (pl.laser) laserAt = Offset(cx, cy)
                                if (drawing) {
                                    val strokes = pl.ink
                                    if (strokes.isNotEmpty()) pl.ink = strokes.dropLast(1) + listOf(strokes.last() + (cx to cy))
                                }
                                val d = downAt
                                if (d != null && !pl.pen && change.uptimeMillis - downTime > 450 && (change.position - d).getDistance() < 24f) {
                                    pl.laser = true
                                    laserAt = Offset(cx, cy)
                                }
                            }
                            PointerEventType.Press -> {
                                downAt = change.position
                                downTime = change.uptimeMillis
                                secondaryDown = event.buttons.isSecondaryPressed
                                if (pl.pen) {
                                    drawing = true
                                    pl.ink = pl.ink + listOf(listOf(cx to cy))
                                }
                                change.consume()
                            }
                            PointerEventType.Release -> {
                                val d = downAt
                                downAt = null
                                if (drawing) {
                                    drawing = false
                                } else if (d != null && (change.position - d).getDistance() < 24f && change.uptimeMillis - downTime < 450) {
                                    val secondary = secondaryDown
                                    when {
                                        pl.overview -> {
                                            // A card in the whole deck: the slide that talks about it.
                                            val f = show.deckFrame(pl.cursor.slide, overview = true)
                                            val hit = f?.cards?.firstOrNull { it.box.contains(cx, cy) }
                                            val to = hit?.let { show.slideFor(it.key) }
                                            present.toggleOverview()
                                            if (to != null) present.goToSlide(to)
                                        }
                                        secondary -> present.previous()
                                        // A linked element jumps to its slide.
                                        linkAt(show, pl.cursor.slide, cx, cy)?.let { to -> show.presentation.indexOf(to).takeIf { it >= 0 } } != null ->
                                            present.goToSlide(show.presentation.indexOf(linkAt(show, pl.cursor.slide, cx, cy)))
                                        change.type == androidx.compose.ui.input.pointer.PointerType.Touch && change.position.x < w / 3f -> present.previous()
                                        else -> present.next()
                                    }
                                } else if (pl.laser && change.type == androidx.compose.ui.input.pointer.PointerType.Touch) {
                                    pl.laser = false
                                }
                                change.consume()
                            }
                            PointerEventType.Exit -> laserAt = null
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val cursor = pl.cursor
        val from = pl.from
        val slide = show.slides.getOrNull(cursor.slide) ?: return@BoxWithConstraints
        val fromSlide = from?.slide?.takeIf { it != cursor.slide }?.let { show.slides.getOrNull(it) }
        val transition = slide.transition
        val bothDeck = fromSlide?.deck != null && slide.deck != null

        // The fraction of the way into the slide, eased; 1 once arrived.
        fun arrived(): Float {
            if (fromSlide == null) return 1f
            val d = if (bothDeck) maxOf(transition.durationMs, 700) else transition.durationMs
            if (transition.kind == Transition.NONE && !bothDeck) return 1f
            return Ease.apply(Ease.IN_OUT, (pl.ms.toFloat() / d.coerceAtLeast(1)).coerceIn(0f, 1f))
        }
        fun overviewAmount(): Float {
            val t = ((pl.now - pl.overviewSince) / 1_000_000f / OVERVIEW_MS).coerceIn(0f, 1f)
            val e = Ease.apply(Ease.IN_OUT, t)
            return if (pl.overview) e else 1f - e
        }
        val fromFrame = from?.slide?.let { show.deckFrame(it) }
        val toFrame = show.deckFrame(cursor.slide)
        val overviewFrame = remember(cursor.slide) { show.deckFrame(cursor.slide, overview = true) }
        val deckFrame: () -> StageFrame? = {
            if (overviewAmount() > 0.001f) null else if (fromSlide != null || fromFrame != null && from.slide != cursor.slide) StageTween.between(fromFrame, toFrame, arrived()) else toFrame
        }
        val state: (Element) -> ElementState = { e ->
            show.state(cursor, e, if (pl.backward) Long.MAX_VALUE / 4 else (pl.ms - transitionLead(pl)).coerceAtLeast(0))
        }

        Box(Modifier.fillMaxSize()) {
            // The slide leaving, under the one arriving.
            if (fromSlide != null) {
                SlideView(
                    ctx, fromSlide, show.zone(from.slide), show.stage(from.slide),
                    Modifier.fillMaxSize().graphicsLayer { applyLeaving(this, transition, arrived(), size.width, size.height) },
                    state = { ElementState.SHOWN },
                    fade = { if (arrived() >= 1f) 0f else 1f },
                    camera = camera,
                )
            }
            SlideView(
                ctx, slide, show.zone(cursor.slide), show.stage(cursor.slide),
                Modifier.fillMaxSize().graphicsLayer { applyArriving(this, transition, arrived(), size.width, size.height, bothDeck) },
                deck = if (slide.deck != null || fromSlide?.deck != null) deckFrame else null,
                deckKeys = allKeys,
                state = state,
                fade = { if (bothDeck || transition.kind == Transition.FADE || transition.kind == Transition.MORPH) arrived() else 1f },
                camera = camera,
            )
            // The whole deck, on demand.
            if (p.deck != null) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (overviewAmount() > 0.001f) 1f else 0f }) {
                    OverviewLayer(ctx, { overviewAmount() }, { toFrame }, { overviewFrame }, allKeys, show.zone(cursor.slide), camera)
                }
            }
            // Laser and pen over everything.
            Canvas(Modifier.fillMaxSize()) {
                val s = min(size.width / Presentation.WIDTH, size.height / Presentation.HEIGHT)
                val ox = (size.width - Presentation.WIDTH * s) / 2f
                val oy = (size.height - Presentation.HEIGHT * s) / 2f
                translate(ox, oy) {
                    drawInk(ctx, pl.ink, s)
                    val l = laserAt
                    if (pl.laser && l != null) drawLaser(ctx, l.x, l.y, s)
                }
            }
            pl.blank?.let { b -> Box(Modifier.fillMaxSize().background(if (b == "W") Color.White else Color.Black)) }
            if (pl.notes) NotesPanel(pl, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** The slide an element at ([x], [y]) on slide [i] links to, if one does. */
private fun linkAt(show: CompiledShow, i: Int, x: Float, y: Float): String? {
    val slide = show.slides.getOrNull(i) ?: return null
    val stage = show.stage(i)
    return slide.elements.asReversed().firstOrNull { e ->
        e.link != null && Transform.hit(Geometry.box(e, stage), e.rotation, x, y)
    }?.link
}

/** How long the builds wait for the slide's arrival: they begin once it is in. */
private fun transitionLead(pl: Playing): Long {
    val from = pl.from ?: return 0
    if (from.slide == pl.cursor.slide) return 0
    val t = pl.show.slides.getOrNull(pl.cursor.slide)?.transition ?: return 0
    return if (t.kind == Transition.NONE) 0 else (t.durationMs * 0.6f).toLong()
}

/** How long after a move until nothing moves: the transition, then the step's builds. */
private fun settleMs(pl: Playing): Long {
    val t = pl.show.slides.getOrNull(pl.cursor.slide)?.transition?.durationMs ?: 0
    val builds = pl.show.builds.getOrNull(pl.cursor.slide)?.length(pl.cursor.step) ?: 0
    return maxOf(t, 700).toLong() + builds + transitionLead(pl) + 80
}

private fun applyLeaving(layer: androidx.compose.ui.graphics.GraphicsLayerScope, t: Transition, p: Float, w: Float, h: Float) {
    when (t.kind) {
        Transition.PUSH -> when (t.direction) {
            Transition.RIGHT -> layer.translationX = w * p
            Transition.UP -> layer.translationY = -h * p
            Transition.DOWN -> layer.translationY = h * p
            else -> layer.translationX = -w * p
        }
        Transition.ZOOM -> {
            layer.alpha = 1f - p
            layer.scaleX = 1f + 0.08f * p
            layer.scaleY = 1f + 0.08f * p
        }
        else -> Unit
    }
}

private fun applyArriving(layer: androidx.compose.ui.graphics.GraphicsLayerScope, t: Transition, p: Float, w: Float, h: Float, bothDeck: Boolean) {
    if (bothDeck) return
    when (t.kind) {
        Transition.PUSH, Transition.COVER -> when (t.direction) {
            Transition.RIGHT -> layer.translationX = -w * (1f - p)
            Transition.UP -> layer.translationY = h * (1f - p)
            Transition.DOWN -> layer.translationY = -h * (1f - p)
            else -> layer.translationX = w * (1f - p)
        }
        Transition.ZOOM -> {
            layer.alpha = p
            layer.scaleX = 0.88f + 0.12f * p
            layer.scaleY = 0.88f + 0.12f * p
        }
        else -> Unit
    }
}

/** The whole deck over the slide: the theme's background coming up, the cards gliding out to it. */
@Composable
private fun OverviewLayer(
    ctx: SlideContext,
    amount: () -> Float,
    slideFrame: () -> StageFrame?,
    overview: () -> StageFrame?,
    keys: List<String>,
    zone: com.kaiharimoto.mastertool.core.present.stage.Box?,
    camera: (@Composable () -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { drawThemeBackground(ctx, amount()) }
        SlideView(
            ctx,
            Slide("overview"),
            // The camera stays where it is: the creator is still talking.
            zone = zone,
            stage = WebcamLayout.safe,
            modifier = Modifier.fillMaxSize(),
            deck = { StageTween.between(slideFrame(), overview(), amount()) },
            deckKeys = keys,
            drawBackground = false,
            drawElements = false,
            camera = camera,
        )
    }
}

/** The speaker notes over the bottom of the slide (S): this slide's, what comes next, and the time. */
@Composable
private fun NotesPanel(pl: Playing, modifier: Modifier) {
    val c = Mu.colors
    val slide = pl.show.slides.getOrNull(pl.cursor.slide) ?: return
    val next = pl.show.next(pl.cursor)?.let { if (it.slide != pl.cursor.slide) pl.show.slides.getOrNull(it.slide) else null }
    val elapsed = (System.nanoTime() - pl.startedAt) / 1_000_000_000
    Column(
        modifier.fillMaxWidth(0.9f).padding(bottom = 24.dp).background(c.paper.copy(alpha = 0.96f)).border(1.dp, c.ink).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Micro("Slide ${pl.cursor.slide + 1} of ${pl.show.slides.size}", color = c.ink70)
            Micro(slide.title.ifBlank { "Untitled" }, Modifier.weight(1f), color = c.ink)
            Mono("%d:%02d".format(elapsed / 60, elapsed % 60), color = c.ink)
        }
        Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            MuText(
                slide.notes.ifBlank { slide.deck?.note?.ifBlank { null } ?: "No notes on this slide." },
                style = MuType.body(LocalMuFonts.current),
                color = if (slide.notes.isBlank()) c.ink45 else c.ink,
            )
        }
        if (next != null) Small("Next: ${next.title.ifBlank { "slide ${pl.show.slides.indexOf(next) + 1}" }}", color = c.ink45)
    }
}
