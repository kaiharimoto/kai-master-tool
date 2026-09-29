package com.kaiharimoto.neue.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog

/** A deck shown as a QR code (1.0.30): its name for the title, and the code the QR carries. */
data class QrShown(val name: String, val deck: Deck, val code: String)

/**
 * The deck as a QR code to scan (1.0.30): the desk's way to hand a deck to a
 * phone or a tablet, which read it with Import → Scan a QR code. It carries the
 * `ydke://` code — the cards, which EDOPro and the deck sites read too — not the
 * groups or the name.
 *
 * The code is black on white in both themes: most scanners read nothing else,
 * and Master UI's two fills are those two.
 */
@Composable
fun QrDialog(shown: QrShown, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val matrix = remember(shown.code) { QrMatrix.of(shown.code) }
    val deck = shown.deck
    MuDialog(
        title = shown.name,
        onDismiss = onDismiss,
        width = 520.dp,
        description = "Main ${deck.main.size} · Extra ${deck.extra.size} · Side ${deck.side.size}. " +
            "Scan it with Neue Master Tool on a phone or tablet: Import, then Scan a QR code.",
        footer = {
            MuButton("Copy the code", onCopy, variant = BtnVariant.GHOST)
            MuButton("Done", onDismiss, variant = BtnVariant.PRIMARY)
        },
    ) {
        if (matrix == null) {
            Help("This deck is too long for one QR code. Copy its YDKe code instead.")
        } else {
            QrPicture(matrix, Modifier.fillMaxWidth().aspectRatio(1f))
            Help("The code carries the cards, not the groups or the deck's name.", Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * The modules on white, each a whole number of pixels so no edge is blurred by
 * antialiasing, with the quiet zone round them, centred in the square it is given.
 */
@Composable
fun QrPicture(matrix: QrMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Color.White)
        val span = matrix.size + QrMatrix.QUIET * 2
        val module = kotlin.math.floor(minOf(size.width, size.height) / span).coerceAtLeast(1f)
        val left = kotlin.math.floor((size.width - module * span) / 2) + module * QrMatrix.QUIET
        val top = kotlin.math.floor((size.height - module * span) / 2) + module * QrMatrix.QUIET
        for (y in 0 until matrix.size) {
            var x = 0
            while (x < matrix.size) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                // A run of dark modules along the row is one rectangle.
                val start = x
                while (x < matrix.size && matrix[x, y]) x++
                drawRect(Color.Black, Offset(left + start * module, top + y * module), Size((x - start) * module, module))
            }
        }
    }
}
