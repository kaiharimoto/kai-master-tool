package com.kaiharimoto.neue.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Offset
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

/** A finger or a pen, rather than a mouse: no buttons, no hover. */
val PointerInputChange.byFinger: Boolean
    get() = type == PointerType.Touch || type == PointerType.Stylus || type == PointerType.Eraser

/**
 * Whether this press is the primary one: a mouse's left button, or a finger or
 * a pen, which press with no button at all (Android reports none for a touch).
 */
val PointerEvent.isPrimaryPress: Boolean
    get() = buttons.isPrimaryPressed || changes.any { it.byFinger }

/**
 * A context menu's gesture, both idioms (1.3.0): a right-click, or a finger held
 * still for [com.kaiharimoto.mastertool.core.input.DeskMouse.HOLD_MS]. [onOpen]
 * hears where, in the element's own pixels. The rest of a held finger is spent,
 * so the tap under it does not fire as it lifts.
 */
fun Modifier.onContextMenu(onOpen: (Offset) -> Unit): Modifier = composed {
    val handler by rememberUpdatedState(onOpen)
    pointerInput(Unit) {
        awaitEachGesture {
            var event: PointerEvent
            do {
                event = awaitPointerEvent()
            } while (event.type != PointerEventType.Press)
            val change = event.changes.firstOrNull() ?: return@awaitEachGesture
            if (event.buttons.isSecondaryPressed) {
                handler(change.position)
                return@awaitEachGesture
            }
            if (!change.byFinger) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val held = withTimeoutOrNull(com.kaiharimoto.mastertool.core.input.DeskMouse.HOLD_MS) {
                var total = Offset.Zero
                while (true) {
                    val next = awaitPointerEvent()
                    val c = next.changes.firstOrNull { it.id == change.id } ?: return@withTimeoutOrNull false
                    if (!c.pressed) return@withTimeoutOrNull false
                    total += c.positionChange()
                    if (total.getDistance() > slop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: true
            if (held) {
                handler(change.position)
                do {
                    val rest = awaitPointerEvent()
                    rest.changes.forEach { it.consume() }
                } while (rest.changes.any { it.pressed })
            }
        }
    }
}

/** Whether this window is a touch screen first (a tablet), for what would otherwise wait on a hover. */
val LocalTouchFirst = androidx.compose.runtime.staticCompositionLocalOf { false }
