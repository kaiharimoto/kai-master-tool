package com.kaiharimoto.neue.shot

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import com.kaiharimoto.neue.cards.NameMask
import com.kaiharimoto.neue.theme.MuTheme
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws [model] to a PNG on the desk, in a scene of its own off any window. [images] holds the pictures by card id; a card
 * without one is drawn as the hatch. The tablet and phone record the same drawing out of sight in the window instead
 * (`DeckShotHost`, I.2).
 */
fun DeckShot.render(model: ShotModel, images: Map<Int, ImageBitmap>, masks: Map<Int, NameMask> = emptyMap()): ByteArray {
    val plan = ShotDesigns.plan(model)
    val density = ShotDesigns.density(model.style)
    val scene = ImageComposeScene((plan.width * density).toInt(), (plan.height * density).toInt(), Density(density)) {
        MuTheme(ink = model.ink) { ShotDesigns.Picture(plan, model, images, masks) }
    }
    try {
        val image = scene.render(0L)
        return image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("The picture could not be encoded")
    } finally {
        scene.close()
    }
}
