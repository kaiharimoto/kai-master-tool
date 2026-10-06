package com.kaiharimoto.neue.ai.avatar

import com.kaiharimoto.neue.kit.Micro
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.NeueHolders
import com.kaiharimoto.neue.ai.AiState
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
 * The bar's way to Ai: its marquee, the live face alone (1.0.63, kai: "just have it as the marquee
 * only, its cleaner") — no box, no name, no running line; what Ai is doing is on its face. A rule
 * under it while the panel is open, a wash under the pointer. A click or tap opens or closes the
 * panel; a right-click or a held finger looks into its brain, whose own button is in the panel's head.
 */
@Composable
fun AiBadge(h: NeueHolders, height: Dp = 40.dp) {
    val ai = h.ai
    val c = Mu.colors
    val open = ai.prefs.panelOpen
    val source = remember { MutableInteractionSource() }
    val hot by source.collectIsHotAsState()
    val face = (height - 4.dp).coerceAtMost(AvatarSizes.bar + 8.dp)
    Tip(
        when {
            open -> "Close ${ai.name}"
            !ai.configured -> "Set up ${ai.name}: connect it to a model"
            else -> "${ai.name}: your assistant. Right-click or hold to look into what it knows"
        },
        kbd = DeskShortcuts.chordFor(DeskAction.AI_PANEL)?.let(DeskShortcuts::kbd),
    ) {
        Box(
            Modifier
                .then(if (com.kaiharimoto.neue.ai.chessy.LocalChessy.current != null) Modifier.height(height).widthIn(min = height) else Modifier.size(height))
                .background(animatedColor(if (hot) c.ink06 else Color.Transparent))
                .drawBehind { if (open) drawLine(c.ink, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 1.dp.toPx()) }
                .hoverable(source)
                .cursorPointer(caption = if (open) "Close" else "Open")
                .onContextMenu { ai.memoryOpen = if (ai.memoryOpen != null) null else "USER.md" }
                .muClickable(interactionSource = source) { ai.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            // Chessy is never drawn too small to read (kai): in the bar she is her name
            if (com.kaiharimoto.neue.ai.chessy.LocalChessy.current != null) {
                // her ears, then her name (kai)
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.kaiharimoto.neue.ai.chessy.ChessyMark(14.dp, name = ai.name)
                    Micro(ai.name, color = c.ink)
                }
            } else {
                AiAvatar(ai.face, face, pointer = { h.cursor.position }, name = ai.name)
            }
        }
    }
}
