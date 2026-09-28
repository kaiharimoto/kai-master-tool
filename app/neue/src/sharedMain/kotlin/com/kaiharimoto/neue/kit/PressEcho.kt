package com.kaiharimoto.neue.kit

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.kaiharimoto.mastertool.core.input.DeskTouch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whether a control should wear its hot look: hovered, as on the desk, or pressed
 * (touch swarm, rec 14). A finger never hovers, so every control in the kit gave
 * no sign that a tap had landed unless its result showed somewhere else, and kai
 * repeated taps that had already worked. A press now wears the hover's own look —
 * the inversion or the ink06 wash, no ripple, no new colour — and keeps it for
 * [DeskTouch.PRESS_ECHO_MS] after the lift, so that a quick tap is seen at all.
 */
@Composable
fun InteractionSource.collectIsHotAsState(): State<Boolean> {
    val hovered = collectIsHoveredAsState()
    val pressed = remember(this) { mutableStateOf(false) }
    LaunchedEffect(this) {
        var echo: Job? = null
        interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    echo?.cancel()
                    pressed.value = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    echo?.cancel()
                    echo = launch {
                        delay(DeskTouch.PRESS_ECHO_MS)
                        pressed.value = false
                    }
                }
            }
        }
    }
    return remember(this) { derivedStateOf { hovered.value || pressed.value } }
}
