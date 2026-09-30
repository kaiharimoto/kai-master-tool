package com.kaiharimoto.neue.ai.avatar

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
 * The bar's way to Ai (1.0.59 on a phone or a tablet, kai: "the android button for ai should be the
 * Ai marquee and not the sliding text"; 1.0.63 on the desk too): the marquee as it stood in 1.0.52,
 * a box with Ai's live face and its name, and no line running through it — what Ai is doing is on
 * its face. Inverted while the panel is open; a click or tap opens or closes it, and a right-click
 * or a held finger looks into its brain, whose own button is in the panel's head.
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
