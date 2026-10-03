package com.kaiharimoto.neue.duel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.layout.CardFrame
import com.kaiharimoto.mastertool.core.layout.CardLook
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.neue.cards.NeueCard
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.Mono
import com.kaiharimoto.neue.kit.drawHatch
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuMotion

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
    stats: String?,
) {
    val density = LocalDensity.current
    val spec = if (carried) snap<Float>() else tween(MuMotion.BASE, easing = MuMotion.ease)
    val x by animateFloatAsState(frame.x, spec, label = "x")
    val y by animateFloatAsState(frame.y, spec, label = "y")
    val w by animateFloatAsState(frame.w, spec, label = "w")
    val rot by animateFloatAsState(frame.rotation, tween(MuMotion.BASE, easing = MuMotion.ease), label = "r")
    Box(
        Modifier
            .zIndex(if (carried) 100f else frame.z)
            .offset { with(density) { IntOffset(x.dp.roundToPx(), y.dp.roundToPx()) } }
            .size(w.dp, (w / com.kaiharimoto.neue.cards.CARD_RATIO).dp)
            .graphicsLayer { rotationZ = rot }
            .then(if (frame.shown && caption != null) Modifier.cursorPointer(caption = caption, emphasis = true, holdOnPress = true) else Modifier),
    ) {
        if (frame.shown || carried) {
            when (frame.look) {
                CardLook.BACK -> CardBack(Modifier.fillMaxSize())
                CardLook.FACE, CardLook.SET -> {
                    if (card != null) {
                        NeueCard(card, Modifier.fillMaxSize(), selected = selected, dimmed = frame.look == CardLook.SET, foil = if (frame.look == CardLook.SET) "off" else foil)
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
            if (counters > 0) Badge("$counters", Modifier.align(Alignment.TopEnd))
            if (inst.under.isNotEmpty() && frame.shown) Badge("${inst.under.size}", Modifier.align(Alignment.BottomStart), outline = true)
            if (stats != null && w >= 48f) {
                val c = Mu.colors
                // Its numbers read upright at the foot of the card as it lies — in Defense, or turned to
                // face the other seat (1.0.78) — so they are counter-turned inside the turned card.
                val h = w / com.kaiharimoto.neue.cards.CARD_RATIO
                val across = frame.rotation % 180f != 0f
                Box(Modifier.fillMaxSize().graphicsLayer { rotationZ = -rot }, contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize((if (across) h else w).dp, (if (across) w else h).dp)) {
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.paper).padding(vertical = 1.dp), contentAlignment = Alignment.Center) {
                            Mono(stats, color = c.ink, size = (w / 9f).coerceIn(8f, 12f).sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The back of a card, in paper and ink: an ink face, a paper rule inside its edge, and the hatch.
 * Every face-down card has one — a set card is never just a missing picture.
 */
@Composable
internal fun CardBack(modifier: Modifier) {
    val c = Mu.colors
    Box(
        modifier.background(c.ink).drawBehind {
            val inset = size.minDimension * 0.07f
            drawHatch(c.paper.copy(alpha = 0.18f), period = size.minDimension * 0.12f, stroke = 1.dp.toPx())
            drawRect(c.paper, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(1.dp.toPx()))
        },
    )
}

/** A card face-down that its controller may read: its face dimmed under the hatch, marked "Set". */
@Composable
private fun SetMark(modifier: Modifier) {
    val c = Mu.colors
    Box(modifier.drawBehind { drawHatch(c.paper.copy(alpha = 0.55f), period = 6.dp.toPx(), stroke = 1.dp.toPx()) }, contentAlignment = Alignment.TopStart) {
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
