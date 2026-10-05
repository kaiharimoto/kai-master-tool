package com.kaiharimoto.neue.duel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.board.CardPosition
import com.kaiharimoto.mastertool.core.duel.text.PositionGlyphs
import com.kaiharimoto.neue.theme.Mu

/**
 * A card position as a small line drawing (D.md §5¾.9, kai: "small visual graphics"): Attack stands up, Defense and Set lie
 * across; the hollow window is face-up, the solid fill the back. One `Canvas` of rectangles on whole pixels
 * ([PositionGlyphs.pixels]), no image assets, no colour — ink, or paper on an inverted chip. A glyph never stands alone:
 * its word goes beside it.
 */
@Composable
internal fun PositionGlyph(pos: CardPosition, size: Dp = 20.dp, color: Color = Mu.colors.ink, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val px = kotlin.math.floor(this.size.minDimension)
        PositionGlyphs.pixels(pos, px).forEach { m -> drawRect(color, Offset(m.l, m.t), Size(m.w, m.h)) }
    }
}
