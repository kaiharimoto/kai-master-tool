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
import com.kaiharimoto.neue.cursor.cursorPointer
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
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
import com.kaiharimoto.neue.kit.onPointer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.LaunchedEffect
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.size.Precision
import com.kaiharimoto.mastertool.core.layout.DecodeSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kaiharimoto.mastertool.core.motion.DeskLean
import com.kaiharimoto.mastertool.core.motion.LeanPose
import com.kaiharimoto.neue.art.LocalArt
import com.kaiharimoto.neue.art.LocalCustomArt
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

/** The width of the full-size original. */
private const val ORIGINAL_WIDTH = 813

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
 * - Each is decoded at the size the card is drawn, and decoded again when the
 *   card grows ([DecodeSize]) — Coil sizes a decode to the box as first
 *   measured and never looks again, so a card laid out small before the window
 *   was maximised stayed blurry until Settings and back rebuilt it. The sharper
 *   decode takes over from the softer one in place (its placeholder is the
 *   softer one), so it never flashes either.
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
    /**
     * Show the artwork chip on hover, for a card printed with more than one
     * picture (1.0.16): `2/9` in the corner, a click the next art, a right-click
     * the one before.
     */
    artChip: Boolean = false,
) {
    // The artwork chosen for this card (1.0.14): the same card with another picture,
    // under that picture's passcode, so the originals and the name masks keep apart.
    val arts = LocalArts.current
    val custom = LocalCustomArt.current
    val own = custom?.version
    val choice = arts[card.id.value]
    // The pool's artwork (`CardArt`), or one of the person's own pictures (`CustomArt`).
    val drawn = remember(card, choice, own) { custom?.drawn(card, choice) ?: CardArt.show(card, choice?.let(::CardId)) }
    val library = LocalArt.current
    if (drawn !== card && drawn.id.value > 0) LaunchedEffect(drawn.id, library) { library?.want(drawn) }
    val step = LocalArtStep.current
    val all = remember(card, own) { custom?.choices(card) ?: CardArt.arts(card).map { it.value } }
    val chip = if (artChip && step != null && all.size > 1) {
        ArtChip(all.indexOf(choice ?: card.id.value).coerceAtLeast(0) + 1, all.size) { by -> step(card, by) }
    } else {
        null
    }
    NeueCardFace(drawn, modifier, format, copies, selected, dimmed, foil, marker, outlined, motion, chip)
}

/** Steps a card's artwork: provided by the window, which owns the choice (`NeueState.stepArt`). */
val LocalArtStep = compositionLocalOf<((Card, Int) -> Unit)?> { null }

/** Which artwork is showing, [at] of [of], and how to step it. */
class ArtChip(val at: Int, val of: Int, val onStep: (Int) -> Unit)

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
    artChip: ArtChip? = null,
) {
    val c = Mu.colors
    var art by remember(card.id) { mutableStateOf(ArtState.LOADING) }
    var original by remember(card.id) { mutableStateOf(ArtState.LOADING) }
    val library = LocalArt.current
    val names = LocalNameStyle.current
    // The decoded pictures, kept so the name can be read off whichever is showing.
    var smallImage by remember(card.id) { mutableStateOf<coil3.Image?>(null) }
    var hdImage by remember(card.id) { mutableStateOf<coil3.Image?>(null) }
    // The widest this box has been drawn, in DecodeSize's steps: 0 until it is measured,
    // then only up — so a resize redraws the card a handful of times, not every frame. It
    // belongs to the box, not the card, so a box that is handed another card (an artwork
    // stepped, a deck shifting) has its size at once rather than waiting to be measured.
    var reach by remember { mutableStateOf(0) }
    val smallDecode = DecodeSize.width(reach, 0, SMALL_WIDTH)
    val hdDecode = DecodeSize.width(reach, 0, ORIGINAL_WIDTH)
    // The last decode of each, which the next one shows while it loads.
    val shownKeys = remember(card.id) { DecodeKeys() }
    var nameMask by remember(card.id) { mutableStateOf<NameMask?>(null) }
    val nameSource = hdImage ?: smallImage
    LaunchedEffect(nameSource, names, foil) {
        val source = nameSource
        if (source == null || names == NameStyles.PRINTED || foil != Foils.HOLO) return@LaunchedEffect
        val key = "${card.id.value}@${source.width}"
        nameMask = NameMasks.cached(key) ?: withContext(Dispatchers.Default) {
            runCatching {
                NameMasks.read(key, source, card.frameType)
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
    // A finger's art chip (touch swarm, rec 7): it comes only once the selection has
    // stood past a double-tap, so the second tap lands on the card, and only on a
    // card wide enough for the chip not to be most of what the finger aims at.
    val touch = com.kaiharimoto.neue.kit.LocalTouchFirst.current
    var widthDp by remember { mutableStateOf(0f) }
    var chipReady by remember { mutableStateOf(false) }
    if (touch) {
        LaunchedEffect(selected) {
            chipReady = false
            if (selected) {
                kotlinx.coroutines.delay(com.kaiharimoto.mastertool.core.input.DeskTouch.CHIP_DELAY_MS)
                chipReady = true
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The phone's tilt (v1.3.6): the light on a card no finger is over follows the hand.
    // Read in the draw below, so a turn redraws the foil and recomposes nothing.
    val tilt = com.kaiharimoto.neue.kit.LocalTilt.current
    // The selected card stands up out of the page (1.0.41, kai: "it's a bit hard to tell
    // which card is being selected"): a little larger than its neighbours, and framed.
    val raise by androidx.compose.animation.core.animateFloatAsState(
        if (selected) SELECT_RAISE else 0f,
        tween(MuMotion.BASE, easing = MuMotion.ease),
        label = "raise",
    )

    Box(
        modifier
            .let { base ->
                if (motion == null && !selected && raise == 0f) base else base.graphicsLayer {
                    val pose = motion?.invoke() ?: LeanPose.REST
                    // Compose turns a positive rotationY right-edge-away; the pose
                    // is written the other way round, nearest edge up.
                    rotationX = pose.rotationX
                    rotationY = -pose.rotationY
                    scaleX = 1f + pose.lift + raise
                    scaleY = 1f + pose.lift + raise
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
                widthDp = with(density) { px.width.toDp().value }
                val grown = DecodeSize.width(px.width, reach)
                if (grown != reach) reach = grown
                // Drawn wider than the small render: ask for the original now, not in its turn.
                if (px.width > SMALL_WIDTH && hd == null) library?.want(card)
            }
            // The selection's frame, outside the card's edge and over its neighbours (the deck
            // draws a selected card above them): a paper hairline, then a band of ink, so it
            // reads against any artwork and any neighbour. Drawn before the clip, in the lift.
            .drawWithContent {
                drawContent()
                if (selected) {
                    val gap = 1.5.dp.toPx()
                    val band = 3.dp.toPx()
                    drawRect(c.paper, topLeft = Offset(-gap / 2, -gap / 2), size = Size(size.width + gap, size.height + gap), style = Stroke(gap))
                    val out = gap + band / 2
                    drawRect(c.ink, topLeft = Offset(-out, -out), size = Size(size.width + 2 * out, size.height + 2 * out), style = Stroke(band))
                }
            }
            .alpha(if (dimmed) 0.35f else 1f)
            .background(c.ink06)
            .clipToBounds()
            .onPointer(PointerEventType.Enter) { hovered = true }
            .onPointer(PointerEventType.Exit) { hovered = false; feel = null }
            .onPointer(PointerEventType.Move) { event ->
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
                        val tipped = if (feel == null) tilt?.value else null
                        val own = when {
                            tipped != null -> Offset(tipped.x, tipped.y)
                            foil == Foils.HOLO -> light
                            else -> feel
                        }
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
            val context = LocalPlatformContext.current
            val smallUrl = card.imageUrlSmall ?: card.imageUrl
            val smallRequest = remember(smallUrl, smallDecode) {
                if (smallDecode == 0) null else decodeRequest(context, smallUrl, smallDecode, shownKeys.small)
            }
            if ((file == null || original != ArtState.READY) && smallRequest != null) {
                AsyncImage(
                    model = smallRequest,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) {
                            smallImage = state.result.image
                            shownKeys.small = state.result.memoryCacheKey
                        }
                        art = when (state) {
                            is AsyncImagePainter.State.Success -> ArtState.READY
                            is AsyncImagePainter.State.Error -> ArtState.FAILED
                            else -> if (art == ArtState.READY) ArtState.READY else ArtState.LOADING
                        }
                    },
                )
            }
            val hdRequest = remember(file, hdDecode) {
                if (file == null || hdDecode == 0) null else decodeRequest(context, file, hdDecode, shownKeys.hd)
            }
            if (hdRequest != null) {
                AsyncImage(
                    model = hdRequest,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        if (state is AsyncImagePainter.State.Success) {
                            hdImage = state.result.image
                            shownKeys.hd = state.result.memoryCacheKey
                        }
                        original = when (state) {
                            is AsyncImagePainter.State.Success -> ArtState.READY
                            is AsyncImagePainter.State.Error -> ArtState.FAILED
                            // A sharper decode of a picture already showing: it stays shown meanwhile.
                            else -> if (original == ArtState.READY) ArtState.READY else ArtState.LOADING
                        }
                    },
                )
            }
        }

        val ban = card.banStatus(format)
        // Forbidden always shows; Limited and Semi-Limited only when asked for (kai, 1.0.73).
        if (ban != BanStatus.UNLIMITED && (ban.maxCopies == 0 || LocalLimitMarks.current)) {
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
        // The artwork chip, on hover (1.0.16): on the card, where the pointer already is,
        // because the inspector's arrows were a journey across other cards away. Its press
        // is spent here, so it never also selects, drags or opens the card under it.
        // On a touch screen, where nothing hovers, the chip is on the selected card.
        val fingerChip = touch && selected && chipReady && widthDp >= com.kaiharimoto.mastertool.core.input.DeskTouch.CHIP_MIN_CARD_DP
        if (artChip != null && (hovered || fingerChip)) {
            Inverted {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .background(Mu.colors.paper)
                        .cursorPointer(caption = "Next art")
                        .pointerInput(artChip.at, artChip.of) {
                            awaitEachGesture {
                                // A press, not any event: a hover over the chip is a stream of
                                // moves, and 1.0.16 stepped the artwork on every one of them.
                                var down: androidx.compose.ui.input.pointer.PointerEvent
                                do {
                                    down = awaitPointerEvent()
                                } while (down.type != androidx.compose.ui.input.pointer.PointerEventType.Press || down.changes.none { it.pressed })
                                val back = down.buttons.isSecondaryPressed
                                down.changes.forEach { it.consume() }
                                do {
                                    val e = awaitPointerEvent()
                                    e.changes.forEach { it.consume() }
                                } while (e.changes.any { it.pressed })
                                artChip.onStep(if (back) -1 else 1)
                            }
                        }
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Mono("${artChip.at}/${artChip.of}", color = Mu.colors.ink, size = 10.sp)
                }
            }
        }

        // Rings are drawn last and inside the card, so they never change its size.
        Box(
            Modifier.fillMaxSize().drawWithContent {
                when {
                    // A selected card is framed outside its edge, above the card (1.0.41).
                    selected -> Unit
                    hovered || outlined -> {
                        val w = 1.dp.toPx()
                        drawRect(c.ink, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
                    }
                }
            },
        )
    }
}

/** The memory-cache keys of the decodes a card last showed: the placeholders for its next, sharper ones. */
private class DecodeKeys {
    var small: MemoryCache.Key? = null
    var hd: MemoryCache.Key? = null
}

/**
 * A request for [data] decoded [width] pixels wide (and the card's height), no
 * larger than its source. [shown] is the decode on screen now, drawn until this
 * one arrives.
 */
private fun decodeRequest(context: coil3.PlatformContext, data: Any?, width: Int, shown: MemoryCache.Key?): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .size(width, DecodeSize.height(width))
        .precision(Precision.INEXACT)
        .placeholderMemoryCacheKey(shown)
        .build()

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

/** How much larger a selected card stands than its neighbours: the hover's lift, and a little more. */
private const val SELECT_RAISE = 0.05f
