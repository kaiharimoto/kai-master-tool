package com.kaiharimoto.neue

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import com.kaiharimoto.mastertool.core.input.KeyChord

/**
 * A key event, as the chord `DeskShortcuts` is written in.
 *
 * Control and Command are folded together — no shortcut tells them apart — and
 * only keys something is bound to are named, so an unbound key is never
 * mistaken for a bound one. `DeskKeysTest` holds this list to the table.
 */
object DeskKeys {
    val names: Map<Key, String> = mapOf(
        Key.Escape to "escape",
        Key.Slash to "slash",
        Key.Comma to "comma",
        Key.Equals to "equals",
        Key.Plus to "equals",
        Key.NumPadAdd to "equals",
        Key.Minus to "minus",
        Key.NumPadSubtract to "minus",
        Key.DirectionUp to "up",
        Key.DirectionDown to "down",
        Key.Enter to "enter",
        Key.NumPadEnter to "enter",
        Key.Delete to "delete",
        Key.Backspace to "backspace",
        Key.Spacebar to "space",
        Key.F1 to "f1",
        Key.F11 to "f11",
        Key.Zero to "0",
        Key.One to "1",
        Key.Two to "2",
        Key.Three to "3",
        Key.Four to "4",
        Key.A to "a",
        Key.B to "b",
        Key.E to "e",
        Key.F to "f",
        Key.G to "g",
        Key.I to "i",
        Key.J to "j",
        Key.K to "k",
        Key.N to "n",
        Key.O to "o",
        Key.S to "s",
        Key.Y to "y",
        Key.Z to "z",
    )

    fun chord(event: KeyEvent): KeyChord? {
        val name = names[event.key] ?: return null
        return KeyChord(
            key = name,
            ctrl = event.isCtrlPressed || event.isMetaPressed,
            // `+` is Shift and `=` on most layouts; zoom means the same either way.
            shift = event.isShiftPressed && name != "equals",
            alt = event.isAltPressed,
        )
    }
}
