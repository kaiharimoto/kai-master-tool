package com.kaiharimoto.neue.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * [onEvent] for every pointer event of [type] — Compose Desktop's
 * `Modifier.onPointerEvent`, which exists only on the desktop, rewritten on the
 * common `pointerInput` so the same line works on Android. It behaves as the
 * desktop one does: the handler is read fresh on each event (a lambda closing
 * over state must not see the state of the composition that installed it), the
 * events are not consumed, and the detector is keyed on the type and the pass.
 */
fun Modifier.onPointer(
    type: PointerEventType,
    pass: PointerEventPass = PointerEventPass.Main,
    onEvent: AwaitPointerEventScope.(event: PointerEvent) -> Unit,
): Modifier = composed {
    val handler by rememberUpdatedState(onEvent)
    pointerInput(type, pass) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(pass)
                if (event.type == type) handler(event)
            }
        }
    }
}
