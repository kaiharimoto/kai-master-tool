package com.kaiharimoto.neue.builder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.layout.ArtFrame
import com.kaiharimoto.mastertool.core.motion.LeanPose
import com.kaiharimoto.mastertool.ui.deckbuilder.DeckBuilderState
import com.kaiharimoto.neue.NeueState
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.IconButton
import com.kaiharimoto.neue.kit.Icons
import com.kaiharimoto.neue.kit.LocalDeviceTilt
import com.kaiharimoto.neue.kit.LocalTilt
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Segmented
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import com.kaiharimoto.mastertool.core.layout.Showcase as ShowcaseFit

/** How far the whole card turns at the tilt's edge, in degrees: a card held in the hand, not a spinning one. */
private const val TURN_DEGREES = 16f

/**
 * A card full screen, turning with the phone (kai, v1.3.6: "a full screen card art
 * with gyroscopic as a strong demo point"). On ink, whatever the theme — the card is
 * the only light in the room.
 *
 * - **Card**: the whole card, as large as the screen allows ([ShowcaseFit.card]). It
 *   turns against the hand's turn, as a card held in the hand seems to stay put in the
 *   room, and its foil catches the light as it goes (`DeskLean`'s light, from the tilt).
 * - **Art**: the artwork alone, covering the screen ([ShowcaseFit.art]); the tilt slides
 *   it a little behind the glass, and the foil's frame is off the screen's edges.
 *
 * The tilt is the phone's (`LocalTilt`); on the desk, where there is none, the pointer
 * across the screen stands in for it. Cards may move and nothing else may: the two
 * controls in the corner stand still. A tap anywhere else, Back or Esc closes it.
 */
@Composable
fun Showcase(state: DeckBuilderState, neue: NeueState) {
    val card = neue.showcase ?: return
    val phoneTilt = LocalDeviceTilt.current
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var artOnly by remember(card) { mutableStateOf(false) }
    val shown = remember(card) { Animatable(0f) }
    LaunchedEffect(card) {
        neue.showcaseCovers = false
        shown.animateTo(1f, tween(MuMotion.BASE, easing = MuMotion.ease))
        neue.showcaseCovers = true
    }
    DisposableEffect(Unit) { onDispose { neue.showcaseCovers = false } }
    // The foil the showcase draws with: the chosen one, or the holographic when it is off —
    // a card shown for its foil with none would be a card shown for nothing.
    val foil = if (neue.prefs.foil == Foils.OFF) Foils.HOLO else neue.prefs.foil
    val frame = remember(card.frameType) { ArtFrame.of(card.frameType) }
    val density = LocalDensity.current
    fun tilt(): Offset = phoneTilt?.value?.let { Offset(it.x, it.y) } ?: pointer ?: Offset.Zero

    Inverted {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = shown.value }
                .background(Mu.colors.paper)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val at = event.changes.firstOrNull()?.position ?: continue
                            pointer = if (event.type == PointerEventType.Exit) null else Offset(
                                (at.x / size.width * 2f - 1f).coerceIn(-1f, 1f),
                                (at.y / size.height * 2f - 1f).coerceIn(-1f, 1f),
                            )
                        }
                    }
                }
                .cursorPointer(caption = "Close")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { neue.showcase = null }
                .clipToBounds(),
        ) {
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }
            // The card's own tilt light stays with the showcase's turn, so it is not counted twice.
            CompositionLocalProvider(LocalTilt provides null) {
                if (artOnly && frame != null) {
                    val level = ShowcaseFit.art(frame, w, h, CARD_RATIO, 0f, 0f)
                    NeueCard(
                        card = card,
                        modifier = Modifier
                            .size(with(density) { level.width.toDp() }, with(density) { level.height.toDp() })
                            // Where it sits is read at layout, so a turn moves the picture and recomposes nothing.
                            .offset {
                                val t = tilt()
                                val p = ShowcaseFit.art(frame, w, h, CARD_RATIO, t.x, t.y)
                                IntOffset(p.left.toInt(), p.top.toInt())
                            },
                        format = state.format,
                        marks = state.marks,
                        foil = foil,
                        motion = { val t = tilt(); LeanPose(rotationX = -t.y * 4f, rotationY = t.x * 4f) },
                    )
                } else {
                    val p = ShowcaseFit.card(w, h, CARD_RATIO)
                    NeueCard(
                        card = card,
                        modifier = Modifier
                            .offset { IntOffset(p.left.toInt(), p.top.toInt()) }
                            .size(with(density) { p.width.toDp() }, with(density) { p.height.toDp() }),
                        format = state.format,
                        marks = state.marks,
                        foil = foil,
                        // Turned against the hand: the card seems to stay put as the phone turns round it.
                        motion = { val t = tilt(); LeanPose(rotationX = t.y * TURN_DEGREES, rotationY = -t.x * TURN_DEGREES) },
                    )
                }
            }
            // The two controls, still, in the top corner.
            Row(
                Modifier.align(Alignment.TopEnd).padding(12.dp).background(Mu.colors.paper),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (frame != null) {
                    Segmented(artOnly, listOf(false, true), { if (it) "Art" else "Card" }, { artOnly = it }, small = true)
                }
                IconButton(Icons.X, { neue.showcase = null }, size = 40.dp, label = "Close")
            }
            if (phoneTilt?.value == null && neue.touchFirst) {
                Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
                    Micro("No tilt sensor, or it is off in Settings", color = Mu.colors.ink45)
                }
            }
        }
    }
}
