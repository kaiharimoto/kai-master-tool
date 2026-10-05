package com.kaiharimoto.neue.world.type

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaiharimoto.mastertool.core.ai.text.MicroCaps
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.LocalKeepCase
import com.kaiharimoto.neue.kit.LocalPhone
import com.kaiharimoto.neue.kit.LocalTouchFirst
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.mastertool.core.input.TouchMetrics
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuFonts
import com.kaiharimoto.neue.theme.MuType

/**
 * Ai World's type scale (`docs/world/READABILITY.md` §1; kai: "set visual guidelines and rules on text sizes and layouts to
 * ensure that items are easy to read at a glance"). Six tiers, each a size, a weight and a line height, built on Master
 * UI's own tokens ([MuType]) — a step larger on a phone. Every piece of text in `neue/world/` is set in one of them: the
 * law test `WorldReadabilityTest` refuses a raw `.sp` there, outside this file, and the kit's smaller text helpers.
 *
 * | Tier | Desk | Phone | For |
 * |---|---|---|---|
 * | title | 20 / 500 / 1.15 | 20 | a page's or a plate's title |
 * | heading | 15 / 500 / 1.3 | 16 | a section of an app, a group of rows |
 * | body | 13 / 400 / 1.45 | 14 | prose, rows, a tab's title, a table's cells, a field's value |
 * | label | 12 / 400 / 1.35 | 13 | the line under a row: where it came from, a hint, a count |
 * | micro | 11 / 500 / caps +0.08 em | 11 | a kind, a column's head, a section's name — never a sentence |
 * | mono | 12 / 400 / 1.35 | 13 | code, addresses, file names, numbers in data |
 * | data | 11 mono | 11 | a chart's ticks and value labels, beside the marks they name |
 */
object WorldType {
    const val TITLE = 20
    const val HEADING_DESK = 15
    const val HEADING_PHONE = 16
    const val BODY_DESK = 13
    const val BODY_PHONE = 14
    const val LABEL_DESK = 12
    const val LABEL_PHONE = 13
    const val MICRO = 11
    const val MONO_DESK = 12
    const val MONO_PHONE = 13
    const val DATA = 11

    /** The floors (READABILITY.md §1): body text a person reads, by form, and anything at all. */
    const val BODY_FLOOR_DESK = 13
    const val BODY_FLOOR_PHONE = 14
    const val FLOOR = 11

    /** Every tier's size on the desk and on a phone, for the floors' test. */
    val tiers: Map<String, Pair<Int, Int>> = mapOf(
        "title" to (TITLE to TITLE),
        "heading" to (HEADING_DESK to HEADING_PHONE),
        "body" to (BODY_DESK to BODY_PHONE),
        "label" to (LABEL_DESK to LABEL_PHONE),
        "micro" to (MICRO to MICRO),
        "mono" to (MONO_DESK to MONO_PHONE),
        "data" to (DATA to DATA),
    )

    fun title(f: MuFonts): TextStyle = MuType.h2(f)

    fun heading(f: MuFonts, phone: Boolean): TextStyle = sized(MuType.body(f).copy(fontWeight = FontWeight.Medium), if (phone) HEADING_PHONE else HEADING_DESK, 1.3f)

    fun body(f: MuFonts, phone: Boolean): TextStyle = sized(MuType.row(f), if (phone) BODY_PHONE else BODY_DESK, 1.45f)

    fun label(f: MuFonts, phone: Boolean): TextStyle = sized(MuType.small(f), if (phone) LABEL_PHONE else LABEL_DESK, 1.35f)

    fun micro(f: MuFonts): TextStyle = MuType.micro(f, MICRO.sp)

    fun mono(f: MuFonts, phone: Boolean): TextStyle = sized(MuType.mono(f), if (phone) MONO_PHONE else MONO_DESK, 1.35f)

    fun data(f: MuFonts): TextStyle = MuType.mono(f, DATA.sp)

    /** The Editor's lines: code is read closely, so a step over mono (13 / 22, the mockup's), 14 on a phone. */
    fun code(f: MuFonts, phone: Boolean): TextStyle = sized(MuType.mono(f), if (phone) BODY_PHONE else BODY_DESK, 1.7f)

    /** The Terminal's lines: the mono tier with room between them (12 / 20, the mockup's). */
    fun terminal(f: MuFonts, phone: Boolean): TextStyle = sized(MuType.mono(f), if (phone) MONO_PHONE else MONO_DESK, 1.67f)

    private fun sized(s: TextStyle, size: Int, leading: Float) = s.copy(fontSize = size.sp, lineHeight = (size * leading).sp)
}

/** A line of ordinary prose, as long as the measure's characters: what [readingMeasure] measures. */
private const val SAMPLE = "The deck opens a starter in most hands, but only a few of those survive one hand trap and go on to an end board."

/** The longest line prose is set in (READABILITY.md §2): 72 characters, the middle of 45–75. */
const val MEASURE_CHARS = 72

/**
 * How wide [MEASURE_CHARS] characters of ordinary prose are in [style], measured — so the measure follows the face, the
 * size and the phone rather than a guess at a character's width.
 */
@Composable
fun readingMeasure(style: TextStyle = WorldType.body(LocalMuFonts.current, LocalPhone.current)): androidx.compose.ui.unit.Dp {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(style, density) {
        with(density) { measurer.measure(SAMPLE.take(MEASURE_CHARS), style, maxLines = 1, softWrap = false).size.width.toDp() }
    }
}

// ---- The tiers as text. These stand in, inside `neue/world/`, for the kit's helpers of the same names. -------------

/** Body: prose, a row's words, a table's cell. Ink by default. */
@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) =
    MuText(text, modifier, WorldType.body(LocalMuFonts.current, LocalPhone.current), color, maxLines, align)

/** Heading: a section of an app, a group of rows. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink, maxLines: Int = 2) =
    MuText(text, modifier, WorldType.heading(LocalMuFonts.current, LocalPhone.current), color, maxLines)

/** Label: the line under a row — a source, a hint, a count. Ink-70 by default: read, but after the row. */
@Composable
fun Small(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink70, maxLines: Int = Int.MAX_VALUE) =
    MuText(text, modifier, WorldType.label(LocalMuFonts.current, LocalPhone.current), color, maxLines)

/** A hint, an empty state's words: the label tier (the kit's 11 sp help text is below the World's floor). */
@Composable
fun Help(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink70, maxLines: Int = Int.MAX_VALUE) =
    MuText(text, modifier, WorldType.label(LocalMuFonts.current, LocalPhone.current), color, maxLines)

/** Mono: code, an address, a file's name, a number in data. [data] is the chart's 11 sp, for ticks beside their marks. */
@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink70, data: Boolean = false, align: TextAlign? = null, maxLines: Int = 1) {
    val f = LocalMuFonts.current
    MuText(text, modifier, if (data) WorldType.data(f) else WorldType.mono(f, LocalPhone.current), color, maxLines, align)
}

/** Micro caps: a kind, a column's head, a section's name. Never a sentence, never a file's name. */
@Composable
fun Micro(text: String, modifier: Modifier = Modifier, color: Color = Mu.colors.ink70, maxLines: Int = 1, align: TextAlign? = null) =
    MuText(MicroCaps.of(text, LocalKeepCase.current), modifier, WorldType.micro(LocalMuFonts.current), color, maxLines, align)

/** A link in micro caps, for a verb ("Open the browser"). Ink-70, ink under the pointer. */
@Composable
fun MicroLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Mu.colors.ink70) =
    Link(text, onClick, modifier, color, mono = false)

/** A link set as itself, in mono: a file's name or an address, never put in capitals. */
@Composable
fun MonoLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Mu.colors.ink) =
    Link(text, onClick, modifier, color, mono = true)

@Composable
private fun Link(text: String, onClick: () -> Unit, modifier: Modifier, color: Color, mono: Boolean) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHotAsState()
    val touch = LocalTouchFirst.current
    Box(
        modifier
            .let { if (touch) it.heightIn(min = TouchMetrics.LINK.dp) else it }
            .hoverable(source)
            .cursorPointer(showsWords = true)
            .muClickable(interactionSource = source, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        val c = animatedColor(if (hovered) Mu.colors.ink else color)
        if (mono) {
            val f = LocalMuFonts.current
            MuText(text, style = WorldType.mono(f, LocalPhone.current).copy(textDecoration = if (hovered) TextDecoration.Underline else TextDecoration.None), color = c, maxLines = 1)
        } else {
            Micro(text, color = c)
        }
    }
}
