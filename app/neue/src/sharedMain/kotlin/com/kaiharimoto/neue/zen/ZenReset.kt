package com.kaiharimoto.neue.zen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.theme.MuMotion
import kotlinx.coroutines.launch

/**
 * The bottom-right corner of deep zen: nothing there until the pointer goes into
 * the corner, and then only half there — the garden is the cards, and these are
 * not part of it.
 *
 * - **Groups** (kai, 1.0.15) breaks the deck into its Roles groups, each piece's
 *   outline glowing faintly in its colour, and closes it back into one when
 *   pressed again. Shown when the deck has groups to break into.
 * - **Labels** (kai, 1.0.24: "let the user toggle the labels for the groups as
 *   well") writes each group's name on its piece, as the builder does, or takes
 *   the names off. Beside Groups, and only live while the pieces are out: with
 *   the deck closed there is nothing for a name to stand on. Kept in the settings.
 * - **Put the cards back** draws every moved card home over a slow beat, then
 *   forgets where they were. Shown when something has been moved.
 * - **Leave zen**, always (1.0.15): kai found the corner empty when nothing had
 *   been moved and the deck had no groups, and read it as broken. A key still
 *   wakes the builder; this is the pointer's way.
 */
@Composable
fun ZenReset(
    zen: ZenLayer,
    hasGroups: Boolean,
    onLeave: () -> Unit,
    labels: Boolean,
    onLabels: () -> Unit,
    modifier: Modifier = Modifier,
    always: Boolean = false,
    onLeaveFullScreen: (() -> Unit)? = null,
) {
    // Read through the counter, so the button hears the first card moved.
    val moved = zen.arranged >= 0 && !zen.arrangement.isEmpty
    val shown by animateFloatAsState(
        // [always] on a touch screen, where nothing can reach for the corner.
        if (zen.corner || always && zen.deep > 0.5f) 1f else 0f,
        tween(MuMotion.BASE, easing = MuMotion.ease),
        label = "reset",
    )
    if (shown <= 0.001f) return
    val scope = rememberCoroutineScope()
    val home = remember { Animatable(1f) }
    Row(
        modifier
            .onSizeChanged { zen.cornerRow = it.width.toFloat() }
            .padding(32.dp)
            .alpha(shown * 0.7f * zen.deep),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Always there, so the corner always answers: the one pointer way out of zen.
        MuButton("Leave zen", onLeave, variant = BtnVariant.GHOST, size = BtnSize.SM)
        // On a tablet a key is not there to leave full screen with, and the bars that
        // hold its button are folded away (touch swarm, rec 3): the corner offers it.
        if (onLeaveFullScreen != null) MuButton("Leave full screen", onLeaveFullScreen, variant = BtnVariant.GHOST, size = BtnSize.SM)
        if (hasGroups) {
            MuButton(
                "Groups",
                { zen.groups = !zen.groups },
                variant = if (zen.groups) BtnVariant.PRIMARY else BtnVariant.SECONDARY,
                size = BtnSize.SM,
                toggled = zen.groups,
            )
            // Always beside Groups, so pressing Groups never slides it out from under the pointer.
            MuButton(
                "Labels",
                onLabels,
                variant = if (labels && zen.groups) BtnVariant.PRIMARY else BtnVariant.SECONDARY,
                size = BtnSize.SM,
                enabled = zen.groups,
                toggled = labels && zen.groups,
                reason = "Open the groups first",
            )
        }
        if (moved) {
            MuButton(
                "Put the cards back",
                {
                    scope.launch {
                        home.snapTo(1f)
                        home.animateTo(0f, tween(MuMotion.SLOW * 2, easing = MuMotion.ease)) { zen.gather = value }
                        zen.arrangement.reset()
                        zen.selection = emptySet()
                        zen.arranged++
                        zen.gather = 1f
                    }
                },
                variant = BtnVariant.SECONDARY,
                size = BtnSize.SM,
            )
        }
    }
}
