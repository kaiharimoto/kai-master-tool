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
import androidx.compose.runtime.compositionLocalOf
import com.kaiharimoto.mastertool.core.model.CardArt
import com.kaiharimoto.mastertool.core.model.CardId
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.LaunchedEffect
import coil3.BitmapImage
import coil3.compose.AsyncImage
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kaiharimoto.mastertool.core.motion.DeskLean
import com.kaiharimoto.mastertool.core.motion.LeanPose
import com.kaiharimoto.neue.art.LocalArt
import coil3.compose.AsyncImagePainter
import com.kaiharimoto.mastertool.core.layout.ArtFrame
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

/** The width of YGOPRODeck's small render. A card drawn wider than this is an upscale of it. */
private const val SMALL_WIDTH = 268

/** How many of its own widths the eye is from a leaning card. */
private const val EYE_WIDTHS = 2.2f

/** How a card's picture is still arriving, arrived, or failed. */
private enum class ArtState { LOADING, READY, FAILED }

/**
 * A card, as Master UI §17 frames a picture.
 *
 * The art is content and keeps its colour; everything the app draws on it is
 * paper and ink. Square corners, no shadow. The foil answers the pointer,
 * because foil is light and light is what a card does when you move over it —
 * and, at kai's request and nowhere but on a card, the card itself may lean and
 * lift ([motion], `DeskLean`). Chrome never moves.
 *
 * - The picture is the full-size original when the art library has it
 *   ([LocalArt]), else YGOPRODeck's small render. The small one stays
 *   underneath until the original has decoded, so arriving never flashes.
 *
 * - Pending art is the live hatch at the card's own ratio; failed art is the
 *   static hatch with the name in a paper block (§17.2).
 * - The ban state is an inverted mono square, `0` `1` `2`; the copy count a
 *   paper block, `×3`, in the corner the card's own copyright line is in.
 * - Selected is the double ring: 2px ink inset 2px, a 1px paper line inside
 *   it, so it reads on light art and dark art alike.
 */
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
    /** How the card is leaning this frame; read in the draw phase, so leaning never recomposes. */
    motion: (() -> LeanPose)? = null,
) {
    // The artwork chosen for this card (1.0.14): the same card with another picture,
    // under that picture's passcode, so the originals and the name masks keep apart.
    val arts = LocalArts.current
    val drawn = remember(card, arts) { CardArt.show(card, arts[card.id.value]?.let(::CardId)) }
    val library = LocalArt.current
    if (drawn !== card) LaunchedEffect(drawn.id, library) { library?.want(drawn) }
    NeueCardFace(drawn, modifier, format, copies, selected, dimmed, foil, marker, outlined, motion)
}

/** The artwork chosen for each card, by the card's own passcode (`NeuePreferences.arts`). */
val LocalArts = compositionLocalOf<Map<Int, Int>> { emptyMap() }

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NeueCardFace(
    card: Card,
    modifier: Modifier,
    format: Format,
    copies: Int,
    selected: Boolean,
    dimmed: Boolean,
    foil: String,
    marker: Marker?,
    outlined: Boolean,
    motion: (() -> LeanPose)?,
) {
    val c = Mu.colors
    var art by remember(card.id) { mutableStateOf(ArtState.LOADING) }
    var original by remember(card.id) { mutableStateOf(ArtState.LOADING) }
    val library = LocalArt.current
    val names = LocalNameStyle.current
    // The decoded pictures, kept so the name can be read off whichever is showing.
    var smallImage by remember(card.id) { mutableStateOf<coil3.Image?>(null) }
    var hdImage by remember(card.id) { mutableStateOf<coil3.Image?>(null) }
    var nameMask by remember(card.id) { mutableStateOf<NameMask?>(null) }
    val nameSource = hdImage ?: smallImage
    LaunchedEffect(nameSource, names, foil) {
        val source = nameSource
        if (source == null || names == NameStyles.PRINTED || foil != Foils.HOLO) return@LaunchedEffect
        val key = "${card.id.value}@${source.width}"
        nameMask = NameMasks.cached(key) ?: withContext(Dispatchers.Default) {
            runCatching {
                val bitmap = (source as? BitmapImage)?.bitmap ?: source.toBitmap()
                NameMasks.read(key, bitmap, card.frameType)
            }.getOrNull()
        }
    }
    val hd by remember(card.id, library) {
        derivedStateOf { library?.let { it.version; it.fileFor(card.id.value) } }
    }
    // Where the pointer is over the card, -1..1 on each axis; null when it is not.
    var feel by remember { mutableStateOf<Offset?>(null) }
    var hovered by remember { mutableStateOf(false) }
    // The light follows the pointer and settles back when it leaves, over the
    // family's base duration: light moving, never the card.
    // The frame round the artwork moves with the card's template, so the foil lands on it.
    val artFrame = remember(card.frameType) { ArtFrame.of(card.frameType) }
    val light by animateOffsetAsState(feel ?: Offset.Zero, tween(MuMotion.BASE, easing = MuMotion.ease), label = "light")

    Box(
        modifier
            .let { base ->
                if (motion == null) base else base.graphicsLayer {
                    val pose = motion()
                    // Compose turns a positive rotationY right-edge-away; the pose
                    // is written the other way round, nearest edge up.
                    rotationX = pose.rotationX
                    rotationY = -pose.rotationY
                    scaleX = 1f + pose.lift
                    scaleY = 1f + pose.lift
                    // Zen's float: a drift in card widths, and a turn in the card's own plane.
                    translationX = pose.dx * size.width
                    translationY = pose.dy * size.width
                    rotationZ = pose.spin
                    // The eye two card-widths off the page, whatever size the card is drawn:
                    // cameraDistance is in 72-pixel inches, so a fixed one flattens a small
                    // card to nothing and throws a large one at the viewer.
                    cameraDistance = (size.width * EYE_WIDTHS / 72f).coerceAtLeast(0.5f)
                }
            }
            .onSizeChanged { px ->
                // Drawn wider than the small render: ask for the original now, not in its turn.
                if (px.width > SMALL_WIDTH && hd == null) library?.want(card)
            }
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
        val shown = art == ArtState.READY || original == ArtState.READY
        if (!shown) {
            Hatch(Modifier.fillMaxSize(), live = art == ArtState.LOADING, color = c.ink25)
        }
        if (art == ArtState.FAILED && !shown) {
            Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.background(c.paper).padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Help(card.name, color = c.ink, maxLines = 4)
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    if (art == ArtState.READY || original == ArtState.READY) {
                        // The pointer's own light on this card, plus the lean it shares with its
                        // neighbours — so a card beside the pointer catches light as it turns.
                        val lean = motion?.invoke()?.light(DeskLean.MAX_DEGREES)
                        val own = if (foil == Foils.HOLO) light else feel
                        val lit = when {
                            lean == null -> own
                            own == null -> Offset(lean.first, lean.second)
                            else -> Offset((own.x + lean.first).coerceIn(-1f, 1f), (own.y + lean.second).coerceIn(-1f, 1f))
                        }
                        drawFoil(foil, lit, artFrame)
                        val mask = nameMask
                        if (mask != null && foil == Foils.HOLO && names != NameStyles.PRINTED) {
                            drawFoilName(mask, lit ?: Offset.Zero, outlined = names == NameStyles.OUTLINE)
                        }
                    }
                },
        ) {
            val file = hd
            if (file == null || original != ArtState.READY) {
                AsyncImage(
                    model = card.imageUrlSmall ?: card.imageUrl,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) smallImage = state.result.image
                        art = when (state) {
                            is AsyncImagePainter.State.Success -> ArtState.READY
                            is AsyncImagePainter.State.Error -> ArtState.FAILED
                            else -> if (art == ArtState.READY) ArtState.READY else ArtState.LOADING
                        }
                    },
                )
            }
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) hdImage = state.result.image
                        original = when (state) {
                            is AsyncImagePainter.State.Success -> ArtState.READY
                            is AsyncImagePainter.State.Error -> ArtState.FAILED
                            else -> ArtState.LOADING
                        }
                    },
                )
            }
        }

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
