package com.kaiharimoto.neue.ai.avatar

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.AiState
import com.kaiharimoto.neue.ai.ideas
import com.kaiharimoto.neue.cursor.cursorPointer
import com.kaiharimoto.neue.kit.MuText
import com.kaiharimoto.neue.kit.Tip
import com.kaiharimoto.neue.kit.onContextMenu
import com.kaiharimoto.neue.kit.animatedColor
import com.kaiharimoto.neue.kit.collectIsHotAsState
import com.kaiharimoto.neue.kit.muClickable
import com.kaiharimoto.neue.theme.Inverted
import com.kaiharimoto.neue.theme.LocalMuFonts
import com.kaiharimoto.neue.theme.Mu
import com.kaiharimoto.neue.theme.MuType
import kotlinx.coroutines.delay

/** How long the start of an answer stays in the bar after it arrives. */
private const val REPLY_SHOWN_MS = 20_000L

/**
 * Ai's face keeps time here (1.0.52): a few times a second the mood table is read
 * afresh (`MoodTracker`), so the moments that pass, pass, and sleep comes after a
 * minute of nobody doing anything. One clock for the app, so the bar and the panel
 * always wear the same face.
 */
@Composable
fun AiFaceClock(ai: AiState) {
    LaunchedEffect(ai) {
        while (true) {
            ai.tickFace()
            delay(120)
        }
    }
}

/**
 * The bar's way to Ai (1.0.52, kai: "replace the button … with a marquee"; 1.0.54: "too big and
 * distracting, needs to be just the marquee"): one line running at an even pace — what it is doing
 * while it works, the start of its answer for a while after, and what to ask it the rest of the
 * time — and nothing else: no box, no face (the face lives on the composer). Quiet grey at rest,
 * ink under the pointer or while the panel is open, and a rule under it while open. A click opens
 * or closes the panel. [width] is 200 dp on the desk and the tablet; a phone's is shorter.
 */
@Composable
fun AiMarquee(h: NeueHolders, width: Dp = 200.dp, height: Dp = 32.dp) {
    val ai = h.ai
    val c = Mu.colors
    val open = ai.prefs.panelOpen
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    // The answer's line gives way to the suggestions when its time is up.
    val expired = remember { mutableIntStateOf(0) }
    LaunchedEffect(ai.repliedAt) {
        val left = ai.repliedAt + REPLY_SHOWN_MS - System.currentTimeMillis()
        if (left > 0) {
            delay(left)
            expired.intValue++
        }
    }
    expired.intValue
    val suggestions = ideas(ai)
    val line = when {
        !ai.configured -> "Set up ${ai.name}: connect it to a model"
        ai.problem != null -> "✕  " + ai.problem!!.first
        ai.confirm != null -> "Waiting on you  ·  " + ai.confirm!!.title
        ai.question != null -> "Waiting on you  ·  " + ai.question!!.question
        ai.running -> ai.working ?: ai.status ?: when {
            ai.streaming.isNotEmpty() -> "Writing"
            ai.studying -> "Studying the deck"
            else -> "Thinking"
        }
        ai.lastReply != null && System.currentTimeMillis() - ai.repliedAt < REPLY_SHOWN_MS -> ai.lastReply!!
        else -> (listOf("Ask ${ai.name}") + suggestions).joinToString("  ·  ")
    }
    Tip(
        if (open) "Close ${ai.name}" else "${ai.name}: your assistant. Ask it anything, or have it build and tune decks",
        kbd = DeskShortcuts.chordFor(DeskAction.AI_PANEL)?.let(DeskShortcuts::kbd),
    ) {
        Box(
            Modifier
                .width(width)
                .height(height)
                .hoverable(source)
                .cursorPointer(caption = if (open) "Close" else "Open")
                .muClickable(interactionSource = source) { ai.toggle() }
                .drawBehind { if (open) drawLine(c.ink, Offset(0f, size.height - 4.dp.toPx()), Offset(size.width, size.height - 4.dp.toPx()), 1.dp.toPx()) }
                .clipToBounds(),
            contentAlignment = Alignment.CenterStart,
        ) {
            // A new line starts from its beginning.
            key(line) {
                BasicText(
                    line,
                    Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        repeatDelayMillis = 0,
                        initialDelayMillis = 900,
                        spacing = MarqueeSpacing(48.dp),
                        velocity = 24.dp,
                    ),
                    style = MuType.small(LocalMuFonts.current).copy(color = animatedColor(if (open || hot || ai.running) c.ink else c.ink45)),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * The bar's way to Ai on a phone or a tablet (1.0.59, kai: "the android button for ai should be the
 * Ai marquee and not the sliding text like desktop"): the marquee as it stood in 1.0.52, a box with
 * Ai's live face and its name, and no line running through it — what Ai is doing is on its face.
 * Inverted while the panel is open; a tap opens or closes it, and a held finger (or a right-click)
 * looks into its brain, which the desk reaches from its own button beside the line.
 */
@Composable
fun AiBadge(h: NeueHolders, height: Dp = 40.dp) {
    val ai = h.ai
    val c = Mu.colors
    val open = ai.prefs.panelOpen
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val face = (height - 8.dp).coerceAtMost(AvatarSizes.bar + 4.dp)
    Tip(
        when {
            open -> "Close ${ai.name}"
            !ai.configured -> "Set up ${ai.name}: connect it to a model"
            else -> "${ai.name}: your assistant. Hold to look into what it knows"
        },
        kbd = DeskShortcuts.chordFor(DeskAction.AI_PANEL)?.let(DeskShortcuts::kbd),
    ) {
        Box(
            Modifier
                .height(height)
                .border(1.dp, c.ink)
                .hoverable(source)
                .cursorPointer(caption = if (open) "Close" else "Open")
                .onContextMenu { ai.memoryOpen = if (ai.memoryOpen != null) null else "USER.md" }
                .muClickable(interactionSource = source) { ai.toggle() },
        ) {
            Inverted(open) {
                val s = Mu.colors
                Row(
                    Modifier.fillMaxHeight().background(animatedColor(if (open) s.paper else if (hot) s.ink06 else Color.Transparent)).padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(face), contentAlignment = Alignment.Center) {
                        AiAvatar(ai.face, face, name = ai.name)
                    }
                    MuText(
                        ai.name,
                        style = MuType.row(LocalMuFonts.current).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = s.ink,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Looking into Ai (1.0.54, kai: "read Ai's brain (the MDs) and edit them in app in the top bar.
 * The button can be just called Ai"): its name, small, beside the marquee; a click opens the brain.
 */
@Composable
fun AiBrainButton(h: NeueHolders) {
    val ai = h.ai
    Tip("Look into ${ai.name}: read and edit what it knows and how it thinks") {
        com.kaiharimoto.neue.kit.WordToggle(ai.name, on = ai.memoryOpen != null, onClick = {
            ai.memoryOpen = if (ai.memoryOpen != null) null else "USER.md"
        })
    }
}
