package com.kaiharimoto.neue.ai.chessy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.kaiharimoto.mastertool.core.ai.chessy.CHESSY_NAME
import com.kaiharimoto.mastertool.core.ai.chessy.ChessyEars
import com.kaiharimoto.neue.theme.Mu

/**
 * Chessy's mark (kai: "a small silhouette of her ears if she's mentioned and you need a small monogram for her"): her
 * two ears, traced from her art (`ChessyEars`), filled in ink. It stands where Ai's mark stands while she is the
 * assistant: beside her name, and in a spot too small for her face. [size] is the height it is given; the ears take
 * eight tenths of it, and as much width as their shape needs.
 */
@Composable
fun ChessyMark(size: Dp, modifier: Modifier = Modifier, name: String = CHESSY_NAME) {
    val ink = Mu.colors.ink
    val tall = size * .8f
    Canvas(modifier.size(tall / ChessyEars.HEIGHT, size).semantics { contentDescription = name }) {
        val w = this.size.width
        withTransform({ translate(0f, (this@Canvas.size.height - w * ChessyEars.HEIGHT) / 2f); scale(w, w, androidx.compose.ui.geometry.Offset.Zero) }) {
            drawPath(Ears.left, ink)
            drawPath(Ears.right, ink)
        }
    }
}

private object Ears {
    val left = outline(ChessyEars.LEFT)
    val right = outline(ChessyEars.RIGHT)

    private fun outline(p: FloatArray) = Path().apply {
        moveTo(p[0], p[1])
        for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
        close()
    }
}
