package com.kaiharimoto.neue.cards

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import com.kaiharimoto.neue.res.Res
import com.kaiharimoto.neue.res.card_back
import org.jetbrains.compose.resources.imageResource

/**
 * The back of a card: kai's own artwork (v1.2.42, the classic app; brought back in 1.0.88 — kai: "there was a cardback
 * art that was included in the program that was red ish"). A crimson field of speckle inside a white margin, a tall
 * black ellipse, 750×1050, committed at `composeResources/drawable/card_back.png` and drawn as it is — never a
 * recreation, and never Konami's back, which this public repository may not carry.
 *
 * Drawn as the classic app drew it: `FillBounds`, a four per cent squeeze against a card's 59×86 that nobody can see,
 * where `Crop` would take pixels off the margin kai confirmed is part of the card; `FilterQuality.Medium`, because a
 * seven-to-one minification of speckle is the worst case there is for a four-texel sample. A back wears no foil.
 */
@Composable
fun ClassicCardBack(modifier: Modifier = Modifier) {
    val bitmap = imageResource(Res.drawable.card_back)
    Image(
        painter = BitmapPainter(bitmap, filterQuality = FilterQuality.Medium),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds,
    )
}
