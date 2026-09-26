package com.kaiharimoto.neue.cards

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.kaiharimoto.mastertool.core.model.BanStatus
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.Format
import com.kaiharimoto.neue.kit.Hatch
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion

/** A Yu-Gi-Oh! card is 59 × 86. */
const val CARD_RATIO = 59f / 86f

/** How a card's picture is still arriving, arrived, or failed. */
private enum class ArtState { LOADING, READY, FAILED }

/**
 * A card, as Master UI §17 frames a picture.
 *
 * The art is content and keeps its colour; everything the app draws on it is
 * paper and ink. Square corners, no tilt, no lift, no shadow, no hover zoom —
 * the one thing that answers the pointer is the foil, because foil is light
 * and light is what a card does when you move over it.
 *
 * - Pending art is the live hatch at the card's own ratio; failed art is the
 *   static hatch with the name in a paper block (§17.2).
 * - The ban state is an inverted mono square, `0` `1` `2`; the copy count a
 *   paper block, `×3`, in the corner the card's own copyright line is in.
 * - Selected is the double ring: 2px ink inset 2px, a 1px paper line inside
 *   it, so it reads on light art and dark art alike.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NeueCard(
    card: Card,
    modifier: Modifier = Modifier,
    format: Format = Format.TCG,
    copies: Int = 0,
    selected: Boolean = false,
    dimmed: Boolean = false,
    foil: String = Foils.HOLO,
    marker: Marker? = null,
    outlined: Boolean = false,
) {
    val c = Mu.colors
    var art by remember(card.id) { mutableStateOf(ArtState.LOADING) }
    // Where the pointer is over the card, -1..1 on each axis; null when it is not.
    var feel by remember { mutableStateOf<Offset?>(null) }
    var hovered by remember { mutableStateOf(false) }
    // The light follows the pointer and settles back when it leaves, over the
    // family's base duration: light moving, never the card.
    val light by animateOffsetAsState(feel ?: Offset.Zero, tween(MuMotion.BASE, easing = MuMotion.ease), label = "light")

    Box(
        modifier
            .alpha(if (dimmed) 0.35f else 1f)
            .background(c.ink06)
            .clipToBounds()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false; feel = null }
            .onPointerEvent(PointerEventType.Move) { event ->
                val p = event.changes.first().position
                val w = size.width.coerceAtLeast(1)
                val h = size.height.coerceAtLeast(1)
                feel = Offset((p.x / w) * 2f - 1f, (p.y / h) * 2f - 1f)
            },
    ) {
        if (art != ArtState.READY) {
            Hatch(Modifier.fillMaxSize(), live = art == ArtState.LOADING, color = c.ink25)
        }
        if (art == ArtState.FAILED) {
            Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.background(c.paper).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Help(card.name, color = c.ink, maxLines = 4)
                }
            }
        }
        AsyncImage(
            model = card.imageUrlSmall ?: card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (art == ArtState.READY) drawFoil(foil, if (foil == Foils.HOLO) light else feel)
                },
            onState = { state ->
                art = when (state) {
                    is AsyncImagePainter.State.Success -> ArtState.READY
                    is AsyncImagePainter.State.Error -> ArtState.FAILED
                    else -> ArtState.LOADING
                }
            },
        )

        val ban = card.banStatus(format)
        if (ban != BanStatus.UNLIMITED) {
            Inverted {
                Box(
                    Modifier.align(Alignment.TopStart).padding(3.dp).size(16.dp).background(Mu.colors.paper),
                    contentAlignment = Alignment.Center,
                ) {
                    Mono(ban.maxCopies.toString(), color = Mu.colors.ink, size = 10.sp, align = TextAlign.Center)
                }
            }
        }
        if (copies > 0) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(3.dp).background(c.paper).border(1.dp, c.ink).padding(horizontal = 3.dp),
            ) {
                Mono("×$copies", color = c.ink, size = 10.sp)
            }
        }
        if (marker != null) {
            MarkerChip(marker, Modifier.align(Alignment.BottomStart).padding(3.dp))
        }

        // Rings are drawn last and inside the card, so they never change its size.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                when {
                    selected -> {
                        val outer = 2.dp.toPx()
                        val inset = 2.dp.toPx()
                        drawRect(
                            c.ink,
                            topLeft = Offset(inset + outer / 2, inset + outer / 2),
                            size = Size(size.width - 2 * inset - outer, size.height - 2 * inset - outer),
                            style = Stroke(outer),
                        )
                        val inner = 1.dp.toPx()
                        val k = inset + outer + inner / 2
                        drawRect(
                            c.paper,
                            topLeft = Offset(k, k),
                            size = Size(size.width - 2 * k, size.height - 2 * k),
                            style = Stroke(inner),
                        )
                    }
                    hovered || outlined -> {
                        val w = 1.dp.toPx()
                        drawRect(c.ink, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
                    }
                }
            },
        )
    }
}

/** A lens or group mark on a card: the two letters of the key, in the key's colour. */
data class Marker(val mark: String, val color: Color)

@Composable
fun MarkerChip(marker: Marker, modifier: Modifier = Modifier) {
    val light = marker.color.luminanceApprox() > 0.5f
    Box(modifier.background(marker.color).padding(horizontal = 3.dp)) {
        Mono(marker.mark, color = if (light) Color.Black else Color.White, size = 10.sp)
    }
}

private fun Color.luminanceApprox(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
