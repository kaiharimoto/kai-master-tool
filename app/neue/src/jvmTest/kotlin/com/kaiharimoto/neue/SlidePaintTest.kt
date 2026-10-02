package com.kaiharimoto.neue

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import com.kaiharimoto.mastertool.core.present.Element
import com.kaiharimoto.mastertool.core.present.Para
import com.kaiharimoto.mastertool.core.present.Presentation
import com.kaiharimoto.mastertool.core.present.Slide
import com.kaiharimoto.mastertool.core.present.Stat
import com.kaiharimoto.mastertool.core.present.stage.WebcamLayout
import com.kaiharimoto.neue.present.paint.SlideContext
import com.kaiharimoto.neue.present.paint.SlideFontSet
import com.kaiharimoto.neue.present.paint.SlideView
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The slide painter never throws on a size (1.0.73). kai's 1.0.72 crash: a text box about 900,000
 * canvas units wide (canvas numbers read as stage fractions) asked Compose for a width its
 * `Constraints` cannot hold, and the window went down. Whatever the data says, a slide draws.
 */
class SlidePaintTest {
    @Test
    fun impossibleBoxesDrawWithoutThrowing() {
        fun text(id: String, anchor: String, x: Float, y: Float, w: Float, h: Float) =
            Element(id, Element.TEXT, x, y, w, h, anchor = anchor, paras = listOf(Para.of("Big words in a box far too wide")))
        val slide = Slide(
            "s",
            elements = listOf(
                // Canvas units mistaken for stage fractions: 500 × the stage's width.
                text("stage", Element.ANCHOR_STAGE, 0.1f, 0.1f, 500f, 0.2f),
                // Absurd in canvas units too, and no height at all.
                text("canvas", Element.ANCHOR_CANVAS, 0f, 0f, 1_000_000f, 0f),
                text("negative", Element.ANCHOR_CANVAS, 10f, 10f, -40f, -40f),
                Element("table", Element.TABLE, 0f, 0f, 900f, 0.3f, anchor = Element.ANCHOR_STAGE, table = listOf(listOf("Opponent", "1st"), listOf("Snake-Eye", "60%"))),
                Element("stat", Element.STAT, 0f, 0f, 2_000_000f, 300f, stat = Stat("54%", "match win to expect", "against the field")),
            ),
        )
        val p = Presentation("p", "Broken", slides = listOf(slide))
        val ctx = SlideContext(p, SlideFontSet(emptyMap()), { null }, { null })
        val scene = ImageComposeScene(960, 540)
        scene.setContent { SlideView(ctx, slide, null, WebcamLayout.stage(null), Modifier.fillMaxSize()) }
        var t = 0L
        repeat(3) { scene.render(t); t += 16_000_000L }
        val image = scene.render(t)
        assertEquals(960, image.width)
        scene.close()
    }
}
