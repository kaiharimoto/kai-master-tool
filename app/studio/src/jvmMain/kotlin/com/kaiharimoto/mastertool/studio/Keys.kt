package com.kaiharimoto.mastertool.studio

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType

/**
 * Typing at a screen that has no window.
 *
 * The play stage's `ShortcutHost` takes focus on launch and reads
 * `onPreviewKeyEvent`, so a synthesised event reaches it exactly as a real one
 * would. That is the point of doing it this way: the studio chooses a seat by
 * pressing `2` and deals by pressing `n`, which means it can only ever
 * photograph a state a user could also reach. A harness that set the camera
 * directly could quietly document a view the app does not offer.
 *
 * There is no AWT event behind these. Compose's bridge from
 * `java.awt.event.KeyEvent` is internal to its own modules, and the host only
 * ever asks an event for its [Key] and its modifiers — so the public
 * constructor says everything that is actually read, without a component
 * nobody will ever show.
 */
internal object Keys {

    /** A press and its release, which is what a chord is made of. */
    fun press(scene: ImageComposeScene, key: Key) {
        scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
        scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
    }

    /**
     * The subset of the shortcut table a script can name.
     *
     * Deliberately only keys something binds: a mapping that can name keys
     * nothing listens for is a mapping whose script fails silently, and a silent
     * failure in a harness is a picture of the wrong thing. `x` was exactly that
     * — no scope has ever bound it — and `i`, the deck check, was missing, which
     * is the one builder panel a shot could not reach.
     *
     * The digits and `f`/`r`/`t`/space are the play stage's; `s`, `b`, `n`, `g`,
     * `d`, `c` and `i` are the builder's. A key is inert on a screen that does
     * not bind it rather than an error, because `--screen` decides which.
     */
    fun of(char: Char): Key? = when (char.lowercaseChar()) {
        '1' -> Key.One
        '2' -> Key.Two
        '3' -> Key.Three
        '4' -> Key.Four
        'b' -> Key.B
        'c' -> Key.C
        'd' -> Key.D
        'f' -> Key.F
        'g' -> Key.G
        'i' -> Key.I
        'n' -> Key.N
        'r' -> Key.R
        's' -> Key.S
        't' -> Key.T
        ' ' -> Key.Spacebar
        else -> null
    }
}
