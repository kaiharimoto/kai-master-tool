package com.kaiharimoto.neue.present.play

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.prefs.NeueTheme
import com.kaiharimoto.mastertool.core.present.play.ElementState
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.art.LocalCustomArt
import com.kaiharimoto.neue.art.LocalArt
import com.kaiharimoto.neue.cards.LocalArts
import com.kaiharimoto.neue.cards.LocalLimitMarks
import com.kaiharimoto.neue.cards.LocalNameStyle
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuSlider
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Small
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideView
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuTheme
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.delay

/**
 * The presenter view (1.0.70): with the slides on the other screen, this window is the
 * presenter's — the slide as it stands, the next one, the speaker notes large enough to read
 * from a step back, the clock, and the controls. The keys still move the show.
 */
@Composable
fun PresenterConsole(h: NeueHolders, ctx: SlideContext) {
    val present = h.present
    val pl = present.playing ?: return
    val show = pl.show
    val c = Mu.colors
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pl) { while (true) { delay(500); tick = System.nanoTime() } }
    var notesSize by remember { androidx.compose.runtime.mutableFloatStateOf(26f) }
    val cursor = pl.cursor
    val slide = show.slides.getOrNull(cursor.slide) ?: return
    val nextCursor = show.next(cursor)
    val nextSlide = nextCursor?.slide?.takeIf { it != cursor.slide }?.let { show.slides.getOrNull(it) }
    val elapsed = (tick.coerceAtLeast(pl.startedAt) - pl.startedAt) / 1_000_000_000
    val onSlide = (tick.coerceAtLeast(pl.slideStartedAt) - pl.slideStartedAt) / 1_000_000_000
    Column(Modifier.fillMaxSize().background(c.paper).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Micro("Presenter view", color = c.ink70)
            Micro("Slide ${cursor.slide + 1} of ${show.slides.size} · click ${cursor.step + 1} of ${show.builds[cursor.slide].count}", Modifier.weight(1f), color = c.ink)
            Mono("%d:%02d".format(elapsed / 60, elapsed % 60), color = c.ink, size = 28.sp)
            Mono("this slide %d:%02d".format(onSlide / 60, onSlide % 60), color = c.ink45)
            slide.durationMs?.let { d -> Mono("rehearsed %d:%02d".format(d / 60000, d / 1000 % 60), color = c.ink45) }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, c.ink)) {
                    SlideView(
                        ctx, slide, show.zone(cursor.slide), show.stage(cursor.slide), Modifier.fillMaxSize(),
                        deck = if (slide.deck != null) ({ show.deckFrame(cursor.slide) }) else null,
                        deckKeys = show.deckFrame(cursor.slide)?.cards?.map { it.key }.orEmpty(),
                        state = { e -> show.state(cursor, e, Long.MAX_VALUE / 4) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuButton("Back", { present.previous() }, size = BtnSize.SM)
                    MuButton("Next", { present.next() }, size = BtnSize.SM, variant = BtnVariant.PRIMARY, arrow = true)
                    MuButton(if (pl.overview) "Back to the slide" else "Whole deck", { present.toggleOverview() }, size = BtnSize.SM, variant = BtnVariant.GHOST, enabled = show.presentation.deck != null)
                    MuButton(if (pl.blank == "B") "Show the slide" else "Black screen", { present.blank("B") }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                    MuButton(if (pl.laser) "Laser off" else "Laser", { present.toggleLaser() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                    MuButton("End", { present.stop() }, size = BtnSize.SM, variant = BtnVariant.GHOST)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Micro("Next", color = c.ink70)
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, c.ink25)) {
                    if (nextSlide != null) {
                        val i = show.slides.indexOf(nextSlide)
                        SlideView(
                            ctx, nextSlide, show.zone(i), show.stage(i), Modifier.fillMaxSize(),
                            deck = if (nextSlide.deck != null) ({ show.deckFrame(i) }) else null,
                            deckKeys = show.deckFrame(i)?.cards?.map { it.key }.orEmpty(),
                            state = { ElementState.SHOWN },
                        )
                    } else if (nextCursor != null) {
                        Small("The next click builds this slide.", Modifier.align(Alignment.Center), color = c.ink45)
                    } else {
                        Small("The end.", Modifier.align(Alignment.Center), color = c.ink45)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Micro("Notes", Modifier.weight(1f), color = c.ink70)
                    MuSlider(notesSize, { notesSize = it }, Modifier.width(120.dp), 16f..48f, name = "Text size")
                }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    MuText(
                        slide.notes.ifBlank { slide.deck?.note?.ifBlank { null } ?: "No notes on this slide." },
                        style = MuType.body(LocalMuFonts.current).copy(fontSize = notesSize.sp, lineHeight = (notesSize * 1.4f).sp),
                        color = if (slide.notes.isBlank()) c.ink45 else c.ink,
                    )
                }
            }
        }
    }
}

/**
 * The slides for the audience, in a window of their own on the other screen (the desktop's
 * presenter view): the window's art and theme provided as the main window provides them.
 */
@Composable
fun PresentAudience(h: NeueHolders, ctx: SlideContext) {
    val neue = h.neue
    CompositionLocalProvider(
        LocalArt provides h.art,
        LocalArts provides neue.prefs.arts,
        LocalCustomArt provides h.customArt,
        LocalNameStyle provides neue.prefs.foilNames,
        LocalLimitMarks provides neue.prefs.limitMarks,
    ) {
        MuTheme(ink = neue.prefs.theme == NeueTheme.INK) {
            PresentStage(h.present, ctx)
        }
    }
}
