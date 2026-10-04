package com.kaiharimoto.neue.duel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.constrain
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.CardLook
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.cards.CARD_RATIO
import com.kaiharimoto.neue.cards.ClassicCardBack
import com.kaiharimoto.neue.cards.Foils
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import com.kaiharimoto.neue.cards.HoloCache
import com.kaiharimoto.neue.cards.drawFoilStar
import kotlin.math.PI
import kotlin.math.sin

/**
 * One card on the duel table, at its frame. Cards may move, and nothing else may: a card that changes
 * place glides there on the family's one easing, and while it is carried it follows the pointer
 * exactly, never sprung toward it.
 */
@Composable
internal fun TableCard(
    frame: CardFrame,
    /** What a right-click does to it, for the family cursor's caption. */
    caption: String?,
    inst: CardInst,
    card: Card?,
    name: String,
    selected: Boolean,
    carried: Boolean,
    foil: String,
    /** The card's ATK/DEF, shown on a monster face-up on the field. */
    stats: TableStats?,
    /**
     * Carried over a Deck's three places (kai, 1.0.93: "have the card itself shrink in size and become semi transparent to
     * see the button choice better"): the card draws small and see-through, shrinking toward this point — where it is held.
     */
    overDeck: TransformOrigin? = null,
    /**
     * A key that changes each time the card becomes a link of a chain (1.0.95, kai: "I also need a card to lift and shine a
     * holographic glimmer in the same texture as the foiling in a star + shape when a card is activating/declaring its
     * effect"): the card rises and settles while a foil star swells over it and goes, the light sweeping across.
     */
    flash: Int? = null,
) {
    val density = LocalDensity.current
    val glint = remember { Animatable(0f) }
    // The key the card was first drawn with: a card already on a chain when the table is drawn (the page opened again, a
    // duel read back) does not glint; only an activation seen happening does.
    val seen = remember { arrayOf(flash) }
    LaunchedEffect(flash) {
        val fresh = flash != null && flash != seen[0]
        seen[0] = flash
        glint.snapTo(0f)
        if (fresh) {
            glint.animateTo(1f, tween(GLINT_MS, easing = LinearEasing))
            glint.snapTo(0f)
        }
    }
    val starCache = remember { HoloCache() }
    val small by animateFloatAsState(if (overDeck != null) 1f else 0f, tween(MuMotion.FAST, easing = MuMotion.ease), label = "deck")
    val pivot = overDeck ?: TransformOrigin.Center
    val spec = if (carried) snap<Float>() else tween(MuMotion.BASE, easing = MuMotion.ease)
    val x by animateFloatAsState(frame.x, spec, label = "x")
    val y by animateFloatAsState(frame.y, spec, label = "y")
    // The width as it glides is read where it is used — the card's size at layout, the plate in its own scope — so a
    // glide re-lays the card each frame and never composes it again (1.0.92).
    val width = animateFloatAsState(frame.w, spec, label = "w")
    val rot by animateFloatAsState(frame.rotation, tween(MuMotion.BASE, easing = MuMotion.ease), label = "r")
    // Whether the plate fits: composed again only when that changes, never on each frame of a glide.
    val wide by remember { derivedStateOf { width.value >= 40f } }
    Box(
        Modifier
            .zIndex(if (carried) 100f else frame.z)
            .offset { with(density) { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) } }
            .cardSize { width.value }
            .graphicsLayer {
                rotationZ = rot
                // The lift as it activates: up and back down, eased by the sine of the glint's progress.
                val g = glint.value
                if (g > 0f) {
                    val up = sin(PI.toFloat() * g)
                    scaleX = 1f + GLINT_LIFT * up
                    scaleY = 1f + GLINT_LIFT * up
                }
                if (small > 0f) {
                    transformOrigin = pivot
                    val scale = 1f - (1f - OVER_DECK_SCALE) * small
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (1f - OVER_DECK_ALPHA) * small
                }
            }
            .then(if (frame.shown && caption != null) Modifier.cursorPointer(caption = caption, emphasis = true, holdOnPress = true) else Modifier)
            .drawWithContent {
                drawContent()
                val g = glint.value
                if (g > 0f && g < 1f) {
                    // The star swells and goes; the light crosses it from one corner to the other.
                    val radius = size.minDimension * GLINT_SIZE * sin(PI.toFloat() * g)
                    drawFoilStar(center, radius, Offset(g * 2f - 1f, g * 2f - 1f), starCache)
                }
            },
    ) {
        if (frame.shown || carried) {
            when (frame.look) {
                CardLook.BACK -> CardBack(Modifier.fillMaxSize())
                CardLook.FACE, CardLook.SET -> {
                    if (card != null) {
                        NeueCard(card, Modifier.fillMaxSize(), selected = selected, foil = if (frame.look == CardLook.SET) "off" else foil)
                    } else {
                        TokenFace(name, Modifier.fillMaxSize())
                    }
                    if (frame.look == CardLook.SET) SetMark(Modifier.fillMaxSize())
                }
            }
            if (frame.look != CardLook.BACK && selected && card == null) {
                Box(Modifier.fillMaxSize().border(2.dp, Mu.colors.ink))
            }
            if (frame.look == CardLook.BACK && selected) Box(Modifier.fillMaxSize().border(2.dp, Mu.colors.paper))
            // What sits on it: counters, materials, its battle numbers.
            val counters = inst.counters.values.sum()
            val plated = stats != null && wide
            if (stats != null && plated) PlateOn(stats, width, frame.rotation) { rot }
            if (counters > 0) Badge("$counters", Modifier.align(Alignment.TopEnd))
            // The materials' count leaves the foot to the plate: an Xyz Monster is the card with both.
            if (inst.under.isNotEmpty() && frame.shown) Badge("${inst.under.size}", Modifier.align(if (plated) Alignment.TopStart else Alignment.BottomStart), outline = true)
        }
    }
}

/** The activation's glint (1.0.95): how long, how far the card lifts, how big the star grows against the card. */
private const val GLINT_MS = 900
private const val GLINT_LIFT = 0.08f
private const val GLINT_SIZE = 0.62f

/** A card carried over a Deck draws at this share of its size and this opacity, so the Deck's choices show (1.0.93). */
private const val OVER_DECK_SCALE = 0.45f
private const val OVER_DECK_ALPHA = 0.45f

/** How much of the card back lies over a set card's face: enough to read as face-down, little enough to read the card. */
private const val SET_BACK_ALPHA = 0.5f

/** A card's printed frame, as a share of its width: the plate stays inside it. */
private const val FRAME_INSET = 0.065f

/**
 * `size(w.dp, (w / CARD_RATIO).dp)`, with [w] read at layout (1.0.92): the same pixels, the same constraints, and a card
 * whose width glides is measured again each frame instead of composed again.
 */
private fun Modifier.cardSize(w: () -> Float): Modifier = layout { measurable, constraints ->
    val width = w()
    val wide = width.dp.roundToPx().coerceAtLeast(0)
    val tall = (width / CARD_RATIO).dp.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.constrain(Constraints(wide, wide, tall, tall)))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * A monster's battle numbers on its card ([width] the card's as it glides, [rotation] how it lies, [rot] how it is turned
 * now): composed in its own scope, so the glide's every frame recomposes the plate alone (1.0.92).
 */
@Composable
private fun PlateOn(stats: TableStats, width: State<Float>, rotation: Float, rot: () -> Float) {
    val w = width.value
    // Its numbers read upright along the foot of the card as it lies — in Defense, or turned
    // to face the other seat (1.0.78) — so the plate is counter-turned inside the turned card,
    // across the bottom of the box the card fills as it lies.
    val h = w / CARD_RATIO
    val across = rotation % 180f != 0f
    // Upside down (turned to face the other seat): the card's foot, where its effect text and printed ATK / DEF are, is at
    // the top of the box as you see it, so the plate is too (kai, 1.0.93: a card facing the other way "has the statline on
    // the top of the card by the name instead of by the effect area"). Its numbers still read upright.
    val turned = ((rotation % 360f) + 360f) % 360f in 135f..225f
    val boxW = if (across) h else w
    // Inside the card's frame on every side, so the border and its foil stay whole round the card
    // (kai, 1.0.87: the plate across the foot "covers the border foiling … the card is being cut off").
    val frameInset = w * FRAME_INSET
    Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = -rot() }, contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(boxW.dp, (if (across) w else h).dp)) {
            StatPlate(
                stats, boxW - frameInset * 2,
                if (turned && !across) {
                    Modifier.align(Alignment.TopCenter).padding(start = frameInset.dp, end = frameInset.dp, top = (frameInset * 1.15f).dp)
                } else {
                    Modifier.align(Alignment.BottomCenter).padding(start = frameInset.dp, end = frameInset.dp, bottom = (frameInset * 1.15f).dp)
                },
            )
        }
    }
}

/** A monster's battle numbers as the table shows them; [defense] says which one battles. */
internal data class TableStats(val atk: String, val def: String?, val defense: Boolean)

/**
 * A monster's ATK / DEF (1.0.87, kai: the old readout "cuts the bottom of the card off and blends in with
 * the background"; then, of a plate the card's full width, "it feels like the card is being cut off"): a solid
 * ink plate inside the card's frame, over the foot of its text box where its printed ATK and DEF are, paper
 * numerals — so it reads against the card in both themes and the frame and its foil stay whole. The numerals are sized from the card's width to fit; the number that battles (ATK in
 * Attack Position, DEF in Defense) is full paper, the other at the ramp's meta weight. A paper hairline
 * round it keeps it apart from a dark frame (Xyz, Link) and from the plate of a card lying across beside it
 * (a card in Defense is wider than its zone, so two such plates meet).
 */
@Composable
private fun StatPlate(stats: TableStats, width: Float, modifier: Modifier) {
    val c = Mu.colors
    val chars = stats.atk.length + (stats.def?.let { it.length + 3 } ?: 0)
    // JetBrains Mono advances 0.6 em: the line fits the plate with a little room either side.
    val size = minOf(width / 7f, (width - 6f) / (0.62f * chars), 13f).coerceAtLeast(6f)
    val quiet = c.paper.copy(alpha = c.ink45.alpha)
    Row(
        modifier.fillMaxWidth().background(c.ink)
            .padding(top = (size * 0.18f + 1f).dp, bottom = (size * 0.14f).dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Mono(stats.atk, color = if (stats.defense && stats.def != null) quiet else c.paper, size = size.sp)
        if (stats.def != null) {
            Mono(" / ", color = quiet, size = size.sp)
            Mono(stats.def, color = if (stats.defense) c.paper else quiet, size = size.sp)
        }
    }
}

/**
 * The back of a card: kai's own artwork, the classic app's back (1.0.88; [com.kaiharimoto.neue.cards.ClassicCardBack]).
 * Every face-down card has one — a set card is never just a missing picture.
 */
@Composable
internal fun CardBack(modifier: Modifier) {
    ClassicCardBack(modifier)
}

/** A card face-down that its controller may read: its face dimmed under the hatch, marked "Set". */
@Composable
private fun SetMark(modifier: Modifier) {
    val c = Mu.colors
    // The card's own back, see-through, over its face (kai, 1.0.93: "instead of white stripes, have it be a transparent
    // version of the card back"): it reads as face-down at a glance, and its controller still reads the card beneath.
    Box(modifier, contentAlignment = Alignment.TopStart) {
        // The see-through back over a set card wears no foil: the card's own face shows through it.
        ClassicCardBack(Modifier.fillMaxSize().graphicsLayer { alpha = SET_BACK_ALPHA }, foil = Foils.OFF)
        Box(Modifier.background(c.ink).padding(horizontal = 3.dp, vertical = 1.dp)) {
            Micro("Set", color = c.paper, size = 8.sp)
        }
    }
}

/** A token with no picture: its name in a paper box. */
@Composable
internal fun TokenFace(name: String, modifier: Modifier) {
    val c = Mu.colors
    Column(modifier.background(c.paper).border(1.dp, c.ink).padding(4.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Micro("Token", color = c.ink45, size = 8.sp)
        Micro(name, color = c.ink, size = 9.sp, maxLines = 3)
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, outline: Boolean = false) {
    val c = Mu.colors
    Box(
        modifier.offset(if (outline) (-2).dp else 2.dp, if (outline) 2.dp else (-2).dp)
            .background(if (outline) c.paper else c.ink)
            .border(1.dp, c.ink)
            .size(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Mono(text, color = if (outline) c.ink else c.paper, size = 10.sp)
    }
}
