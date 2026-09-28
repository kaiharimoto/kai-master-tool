package com.kaiharimoto.neue.cursor

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

// A one-pixel transparent image. Where the toolkit cannot make one (headless, as
// the studio runs), the normal arrow — as the kit keeps it when its script never loads.
internal actual fun blankPointerIcon(): PointerIcon = runCatching {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "master-ui-cursor"))
}.getOrDefault(PointerIcon.Default)
