package com.kaiharimoto.neue.builder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.layout.TextFirst
import com.kaiharimoto.neue.cards.CARD_RATIO
import kotlin.math.roundToInt

/**
 * The least the picture shrinks to while the text is fitted: a deck card's height on a large display,
 * still known on sight (1.0.88).
 */
internal val TEXT_FIRST_ART_MIN = 140.dp

/** The smallest the card's text is set before the column scrolls instead (1.0.88). In sp, so Text size still multiplies it. */
internal val TEXT_FIRST_FLOOR = 11.sp

/**
 * A card read beside the deck, its text first (1.0.88, kai: "prioritize the effect text and try its best
 * to fit all of it in the inspector so the user doesn't have to scroll down"; `TextFirst`).
 *
 * [head] (the name, the type line, the numbers) is measured, then [text] at [style]'s size with a text
 * measurer — no guessing by characters. The picture takes the height they leave of [room], between
 * [artMin] and its natural height (the column's width at 59:86, capped by [artMax]); with the picture at
 * its least the text steps down a size at a time to [floor]; past that the caller's column scrolls.
 * [body] is handed the style chosen and draws the text with it, so what is drawn is what was measured.
 *
 * The picture is centred; [headFirst] puts [head] above it (the phone's viewer, whose head carries its
 * buttons) instead of between it and the text.
 */
@Composable
internal fun TextFirstCard(
    room: Dp?,
    text: String,
    style: TextStyle,
    artGap: Dp,
    headGap: Dp,
    head: @Composable () -> Unit,
    art: @Composable () -> Unit,
    body: @Composable (TextStyle) -> Unit,
    modifier: Modifier = Modifier,
    headFirst: Boolean = false,
    artMin: Dp = TEXT_FIRST_ART_MIN,
    artMax: Dp? = null,
    floor: TextUnit = TEXT_FIRST_FLOOR,
) {
    val measurer = rememberTextMeasurer()
    SubcomposeLayout(modifier) { cons ->
        val width = cons.maxWidth
        val heads = subcompose("head", head).map { it.measure(Constraints(maxWidth = width)) }
        val headH = heads.sumOf { it.height }
        val gaps = artGap.roundToPx() + headGap.roundToPx()
        val natural = (width / CARD_RATIO).roundToInt().let { h -> artMax?.let { minOf(h, it.roundToPx()) } ?: h }
        val leading = if (style.lineHeight.isSp && style.fontSize.isSp) style.lineHeight.value / style.fontSize.value else 1.45f
        fun styleAt(size: Int) = style.copy(fontSize = size.sp, lineHeight = (size * leading).sp)
        val sizes = TextFirst.steps(style.fontSize.value.roundToInt(), floor.value.roundToInt())
        val fit = TextFirst.fit(room?.roundToPx(), headH + gaps, artMin.roundToPx(), natural, sizes) { size ->
            measurer.measure(text, styleAt(size), constraints = Constraints(maxWidth = width)).size.height
        }
        val artH = fit.art
        val artW = (artH * CARD_RATIO).roundToInt().coerceAtMost(width)
        val arts = subcompose("art", art).map { it.measure(Constraints.fixed(artW, artH)) }
        val chosen = styleAt(fit.size)
        val bodies = subcompose("body") { body(chosen) }.map { it.measure(Constraints(maxWidth = width)) }
        val bodyH = bodies.sumOf { it.height }
        layout(width, artH + headH + gaps + bodyH) {
            var y = 0
            fun placeHead() {
                heads.forEach { it.place(0, y); y += it.height }
            }
            if (headFirst) {
                placeHead()
                y += headGap.roundToPx()
            }
            arts.forEach { it.place((width - artW) / 2, y) }
            y += artH + artGap.roundToPx()
            if (!headFirst) {
                placeHead()
                y += headGap.roundToPx()
            }
            bodies.forEach { it.place(0, y); y += it.height }
        }
    }
}
