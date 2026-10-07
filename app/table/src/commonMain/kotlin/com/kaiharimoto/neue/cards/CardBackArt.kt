package com.kaiharimoto.neue.cards

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import com.kaiharimoto.neue.kit.LocalTilt
import com.kaiharimoto.neue.kit.onPointer
import com.kaiharimoto.table.res.Res
import com.kaiharimoto.table.res.card_back
import org.jetbrains.compose.resources.imageResource

/** The foil the card faces wear (`NeuePreferences.foil`), for the backs to wear on their border too (1.0.95). */
val LocalCardFoil = staticCompositionLocalOf { Foils.OFF }

/**
 * The back of a card: kai's own artwork (v1.2.42, the classic app; brought back in 1.0.88 — kai: "there was a cardback
 * art that was included in the program that was red ish"). A crimson field of speckle inside a white margin, a tall
 * black ellipse, 750×1050, committed at `composeResources/drawable/card_back.png` and drawn as it is — never a
 * recreation, and never Konami's back, which this public repository may not carry.
 *
 * Drawn as the classic app drew it: `FillBounds`, a four per cent squeeze against a card's 59×86 that nobody can see,
 * where `Crop` would take pixels off the margin kai confirmed is part of the card; `FilterQuality.Medium`, because a
 * seven-to-one minification of speckle is the worst case there is for a four-texel sample.
 *
 * Its outer border wears the faces' foil (kai, 1.0.95: "have the foiling for the outer border apply to the card back as
 * well"): [foil] over the margin's band — the band alone, a back has no art frame — lit as a face is, by the pointer
 * over it or the phone's tilt.
 */
@Composable
fun ClassicCardBack(modifier: Modifier = Modifier, foil: String = LocalCardFoil.current) {
    val bitmap = imageResource(Res.drawable.card_back)
    val painter = remember(bitmap) { BitmapPainter(bitmap, filterQuality = FilterQuality.Medium) }
    val foiled = if (foil == Foils.OFF) Modifier else {
        val tilt = LocalTilt.current
        var feel by remember { mutableStateOf<Offset?>(null) }
        val holo = remember { HoloCache() }
        Modifier
            .onPointer(PointerEventType.Move) { event ->
                val p = event.changes.first().position
                val w = size.width.coerceAtLeast(1)
                val h = size.height.coerceAtLeast(1)
                feel = Offset((p.x / w) * 2f - 1f, (p.y / h) * 2f - 1f)
            }
            .onPointer(PointerEventType.Exit) { feel = null }
            .drawWithContent {
                drawContent()
                val lit = feel ?: tilt?.value?.let { Offset(it.x, it.y) }
                drawFoil(foil, lit, null, holo)
            }
    }
    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier.then(foiled),
        contentScale = ContentScale.FillBounds,
    )
}
