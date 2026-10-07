package com.kaiharimoto.neue.lounge

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.kaiharimoto.mastertool.core.input.DeskAction
import com.kaiharimoto.mastertool.core.input.DeskContext
import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import com.kaiharimoto.neue.DeskKeys
import com.kaiharimoto.neue.duel.TableHost
import com.kaiharimoto.neue.duel.dismissDuel
import com.kaiharimoto.neue.duel.runDuel

/**
 * The duel's keys where the table is the whole page (the Lounge's browser table): Neue's own table
 * (`DeskShortcuts`, `DeskScope.DUEL`) read the way Neue's window reads it (`NeueKeys`), so a friend in a browser
 * plays by the same keys kai does — a letter that is no key opens the Spotlight holding it, Esc closes one layer at a
 * time. Only the duel's keys: the pages, the palette and Ai's panel are the desk's.
 */
class TableKeys(private val host: TableHost) {
    private val held = HashSet<Key>()

    /** [event] taken by the table; false when it is not the table's. */
    fun onKey(event: KeyEvent, textFocused: Boolean, overlayOpen: Boolean): Boolean {
        if (event.type == KeyEventType.KeyUp) { held.remove(event.key); return false }
        if (event.type != KeyEventType.KeyDown) return false
        val repeat = !held.add(event.key)
        val chord = DeskKeys.chord(event) ?: return false
        val duel = host.duel
        // Typed fast after the Spotlight opened, the letters go into it, never to the duel's keys.
        if (duel.spotlight != null && !duel.spotlightTyping && !textFocused && !overlayOpen && !chord.ctrl && !chord.alt) {
            val typed = when {
                chord.key.length == 1 && (chord.key[0] in 'a'..'z' || chord.key[0] in '0'..'9') -> if (chord.shift) chord.key.uppercase() else chord.key
                chord.key == "space" -> " "
                else -> null
            }
            if (typed != null) { duel.typeIntoSpotlight(typed); return true }
        }
        val context = DeskContext(
            textInputFocused = textFocused,
            overlayOpen = overlayOpen,
            onBuilder = false,
            ai = host.ai != null,
            onDuel = true,
            replaying = duel.replay != null,
            choosing = duel.choosing,
        )
        val shortcut = DeskShortcuts.resolveShortcut(chord, context)
        if (shortcut == null) {
            // A letter that is no key, at the table: the Spotlight opens holding it.
            if (textFocused || overlayOpen || chord.ctrl || chord.alt || chord.key.length != 1 || chord.key[0] !in 'a'..'z') return false
            if (duel.shown == null || duel.replay != null) return false
            if (duel.choosing) return duel.shortcutPart.type(chord.key)
            duel.openSpotlight(chord.key)
            return true
        }
        if (repeat && !shortcut.repeatable) return true
        when (shortcut.action) {
            DeskAction.DISMISS -> return dismissDuel(host)
            // The voice is held (hold M); a page with no voice lets M be a letter.
            DeskAction.DUEL_VOICE -> return false
            // Undo and redo at the table are the duel's (a room's asks the other player to let it go back).
            DeskAction.UNDO -> duel.undo()
            DeskAction.REDO -> duel.redo()
            else -> if (shortcut.action.name.startsWith("DUEL_")) runDuel(host, shortcut.action) else return false
        }
        return true
    }

    /** The page lost the keyboard: the keys down now are forgotten, or the next press reads as a repeat. */
    fun lost() = held.clear()
}
