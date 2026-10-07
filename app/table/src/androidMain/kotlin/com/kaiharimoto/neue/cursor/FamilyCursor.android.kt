package com.kaiharimoto.neue.cursor

import androidx.compose.ui.input.pointer.PointerIcon

// On a tablet the family cursor is drawn only for a mouse; the system's own stays.
internal actual fun blankPointerIcon(): PointerIcon = PointerIcon.Default
