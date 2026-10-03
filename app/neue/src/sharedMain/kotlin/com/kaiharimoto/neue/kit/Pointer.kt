package com.kaiharimoto.neue.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
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
import com.kaiharimoto.mastertool.core.input.DeskTouch

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
 * still for the system's hold (`DeskTouch.holdMs`). [onOpen]
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
            // The platform's hold, as the cards use (touch swarm, rec 11).
            val held = withTimeoutOrNull(DeskTouch.holdMs(viewConfiguration.longPressTimeoutMillis)) {
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

/** A phone (v1.3.5, `FormFactor.PHONE`): pages lay themselves out for a narrow screen held close. */
val LocalPhone = androidx.compose.runtime.compositionLocalOf { false }

/**
 * How many text fields have focus, anywhere in the window (touch swarm, rec 6).
 *
 * A keyboard cover on a tablet types into the window like a desk's keyboard,
 * and the shortcut table stays out of the way of a focused field — but only of
 * the fields that remembered to say so. Fields said so one by one, and the ones
 * that forgot let "n" open a group draft and Backspace delete a card while a
 * name was being typed. Every kit field reports here instead, so none can forget.
 */
class TextFocus {
    var count by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    val any: Boolean get() = count > 0

    internal fun changed(focused: Boolean) {
        count = (count + if (focused) 1 else -1).coerceAtLeast(0)
    }
}

val LocalTextFocus = androidx.compose.runtime.staticCompositionLocalOf<TextFocus?> { null }

/** Reports this text field's focus to [LocalTextFocus], once per change, and lets go of it if the field leaves while focused. */
fun Modifier.reportsTextFocus(): Modifier = composed {
    val sink = LocalTextFocus.current
    val was = androidx.compose.runtime.remember { booleanArrayOf(false) }
    androidx.compose.runtime.DisposableEffect(sink) {
        onDispose { if (was[0]) sink?.changed(false); was[0] = false }
    }
    onFocusChanged { state ->
        if (state.isFocused != was[0]) {
            was[0] = state.isFocused
            sink?.changed(state.isFocused)
        }
    }
}

/** Where a disabled control's reason goes when a finger taps it (touch swarm, rec 8): the window's note. */
val LocalReasonNote = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * On a touch screen, a tap on a disabled control that knows why it is disabled
 * says why — the desk shows the reason in the cursor's caption, and a finger has
 * no cursor. Everywhere else, nothing.
 */
fun Modifier.explainsWhenDisabled(enabled: Boolean, reason: String?): Modifier = composed {
    val note = LocalReasonNote.current
    if (enabled || reason == null || note == null || !LocalTouchFirst.current) {
        Modifier
    } else {
        this.then(
            Modifier.clickable(
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { note(reason) },
        )
    }
}

/**
 * On a touch screen, a finger's press here lets go of the text field that has the
 * keyboard (touch swarm, rec 10): the deck and the inspector are where a hand goes
 * when typing is done, and the soft keyboard otherwise stays up over half the
 * window through everything that follows. Nothing is consumed, so the press still
 * does what it does. The pool's cards are not wrapped — a tap there keeps typing,
 * so a query can be refined.
 */
fun Modifier.releasesTypingOnFinger(): Modifier = composed {
    val touch = LocalTouchFirst.current
    val typing = LocalTextFocus.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    if (!touch) {
        Modifier
    } else {
        this.then(
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press &&
                            event.changes.any { it.byFinger } && typing?.any == true
                        ) {
                            focus.clearFocus()
                        }
                    }
                }
            },
        )
    }
}

/**
 * A press here — mouse or finger — lets go of the text field that has the keyboard (1.0.78, kai: "if I
 * typed into the command bar … and wanted to click outside of it and do an action, it would be stuck
 * trying to type"). On a desktop nothing else takes focus from a field, so without this a click on the
 * duel table leaves every key typing. Nothing is consumed: the press still does what it does. Put it
 * only on surfaces with no text field of their own.
 */
fun Modifier.releasesTyping(): Modifier = composed {
    val typing = LocalTextFocus.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    this.then(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && typing?.any == true) focus.clearFocus()
                }
            }
        },
    )
}

/** Whether a keyboard is attached: always on the desk; on a tablet, only with a keyboard cover or a paired one. */
val LocalHardwareKeyboard = androidx.compose.runtime.compositionLocalOf { true }

/**
 * `clickable` for the kit, with one rule a finger needs (touch swarm, rec 23):
 * a finger's click must be a tap. Compose fires a click on the lift however long
 * the press lasted, so a left thumb gripping the tablet's corner rested on the
 * rail's Search, Settings and theme, and fired one of them when it let go; a thumb
 * on a dialog's scrim cancelled the dialog. Here a finger that stayed down longer
 * than the hold and a little more, or travelled past the slop, fires nothing —
 * [DeskTouch.isTap], the rule the cards use, so chrome and cards agree on what a
 * tap is. A mouse is unchanged.
 */
fun Modifier.muClickable(
    enabled: Boolean = true,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    this
        .restingFingerFiresNothing()
        .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
}

/** The lift of a finger that rested rather than tapped is spent before anything below hears it (rec 23). */
fun Modifier.restingFingerFiresNothing(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!down.byFinger) return@awaitEachGesture
        val hold = DeskTouch.holdMs(viewConfiguration.longPressTimeoutMillis)
        var travel = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            travel = maxOf(travel, (change.position - down.position).getDistance())
            if (!change.pressed) {
                val tap = DeskTouch.isTap(
                    down.uptimeMillis, change.uptimeMillis, travel, viewConfiguration.touchSlop, hold,
                )
                if (!tap) change.consume()
                break
            }
        }
    }
}
