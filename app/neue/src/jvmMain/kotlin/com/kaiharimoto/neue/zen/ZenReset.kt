package com.kaiharimoto.neue.zen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.kaiharimoto.neue.kit.BtnSize
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.theme.MuMotion
import kotlinx.coroutines.launch

/**
 * "Put the cards back", in the bottom-right corner of deep zen: nothing there
 * until the pointer goes into the corner, and then only half there — the
 * garden is the cards, and the button is not part of it. It draws every moved
 * card home over a slow beat, then forgets where they were.
 */
@Composable
fun ZenReset(zen: ZenLayer, modifier: Modifier = Modifier) {
    // Read through the counter, so the button hears the first card moved.
    val moved = zen.arranged >= 0 && !zen.arrangement.isEmpty
    val shown by animateFloatAsState(
        if (zen.corner && moved) 1f else 0f,
        tween(MuMotion.BASE, easing = MuMotion.ease),
        label = "reset",
    )
    if (shown <= 0.001f) return
    val scope = rememberCoroutineScope()
    val home = remember { Animatable(1f) }
    Box(modifier.padding(32.dp).alpha(shown * 0.7f * zen.deep)) {
        MuButton(
            "Put the cards back",
            {
                scope.launch {
                    home.snapTo(1f)
                    home.animateTo(0f, tween(MuMotion.SLOW * 2, easing = MuMotion.ease)) { zen.gather = value }
                    zen.arrangement.reset()
                    zen.arranged++
                    zen.gather = 1f
                }
            },
            variant = BtnVariant.SECONDARY,
            size = BtnSize.SM,
        )
    }
}
