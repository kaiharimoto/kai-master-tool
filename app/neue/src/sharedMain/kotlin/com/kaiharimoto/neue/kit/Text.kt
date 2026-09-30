package com.kaiharimoto.neue.kit

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.LocalMuText
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType

/** Text in the current surface's ink unless told otherwise. */
@Composable
fun MuText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalMuText.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    val resolved = if (color != Color.Unspecified) color else style.color.takeIf { it != Color.Unspecified } ?: Mu.colors.ink
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = resolved, textAlign = align ?: style.textAlign),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = maxLines != 1,
    )
}

/** Styled text — bold, italic, code runs — in the current surface's ink (Ai's replies, 1.0.43). */
@Composable
fun MuText(
    text: androidx.compose.ui.text.AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalMuText.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    val resolved = if (color != Color.Unspecified) color else style.color.takeIf { it != Color.Unspecified } ?: Mu.colors.ink
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = resolved),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/**
 * Micro caps, the label voice (§3). The source text stays sentence case and
 * is set in capitals here, because micro caps are a treatment, not a spelling.
 */
@Composable
fun Micro(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Mu.colors.ink70,
    size: TextUnit = 11.sp,
    maxLines: Int = 1,
) {
    MuText(com.kaiharimoto.mastertool.core.ai.text.MicroCaps.of(text, LocalKeepCase.current), modifier, MuType.micro(LocalMuFonts.current, size), color, maxLines)
}

/**
 * Names that keep their own spelling inside micro caps (1.0.45): the assistant's, so
 * "Ai" the name is never set as "AI" the letters (`MicroCaps`).
 */
val LocalKeepCase = androidx.compose.runtime.staticCompositionLocalOf<Set<String>> { emptySet() }

/** Mono, for data: counts, percentages, keys, indexes (§3). */
@Composable
fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Mu.colors.ink45,
    size: TextUnit = 11.sp,
    align: TextAlign? = null,
) {
    MuText(text, modifier, MuType.mono(LocalMuFonts.current, size), color, maxLines = 1, align = align)
}

/** `01`, `02` — the family's signature (§3). */
@Composable
fun Numeral(n: Int, modifier: Modifier = Modifier, color: Color = Mu.colors.ink45) {
    Mono(n.toString().padStart(2, '0'), modifier, color)
}

@Composable
fun H1(text: String, modifier: Modifier = Modifier) =
    MuText(text, modifier, MuType.h1(LocalMuFonts.current), maxLines = 1)

@Composable
fun H2(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) =
    MuText(text, modifier, MuType.h2(LocalMuFonts.current), maxLines = maxLines)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) =
    MuText(text, modifier, MuType.body(LocalMuFonts.current), color, maxLines)

@Composable
fun RowText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, maxLines: Int = 1) =
    MuText(text, modifier, MuType.row(LocalMuFonts.current), color, maxLines)

@Composable
fun Small(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink70, maxLines: Int = Int.MAX_VALUE) =
    MuText(text, modifier, MuType.small(LocalMuFonts.current), color, maxLines)

@Composable
fun Help(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink45, maxLines: Int = Int.MAX_VALUE) =
    MuText(text, modifier, MuType.help(LocalMuFonts.current), color, maxLines)

/** A percentage the way the family writes one: `57%`, `<1%`, `>99%`, `100%`. */
fun percent(p: Double): String = when {
    p.isNaN() -> "--"
    p <= 0.0 -> "0%"
    p >= 1.0 -> "100%"
    p < 0.01 -> "<1%"
    p > 0.99 -> ">99%"
    else -> "${(p * 100).let { kotlin.math.round(it * 10) / 10 }.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }}%"
}
