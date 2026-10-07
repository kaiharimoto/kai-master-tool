package com.kaiharimoto.neue.cursor

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * The browser has no blank pointer icon to hand Compose, so the family cursor is not drawn in the Lounge's
 * browser table (its host gives no `LocalCursor`) and this is only the arrow, as the kit keeps it when its
 * script never loads.
 */
internal actual fun blankPointerIcon(): PointerIcon = PointerIcon.Default
