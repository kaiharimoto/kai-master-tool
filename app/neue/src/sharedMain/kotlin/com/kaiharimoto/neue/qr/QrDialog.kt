package com.kaiharimoto.neue.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import com.kaiharimoto.mastertool.core.ydk.DeckQr
import com.kaiharimoto.mastertool.core.ydk.DeckQrCode
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog

/**
 * A deck shown as a QR code (1.0.30): its name for the title, its counts, the
 * code ([DeckQr]: the whole deck; null only for a deck no code could hold), and
 * the `ydke://` line the Copy button gives, for the simulators.
 */
data class QrShown(val name: String, val deck: Deck, val code: DeckQrCode?, val ydke: String)

/**
 * The deck as a QR code to scan (1.0.30; the whole deck, 1.0.31, kai: "carry
 * as much information as possible, including groups"): the desk's way to hand a
 * deck to a phone or a tablet, which read it with Import → Scan a QR code. It
 * carries the deck's `.ydkx` — cards, groups, goals, notes — with its name and
 * covers, and says so under the code; a deck too large for one code says what
 * it left behind.
 *
 * The code is black on white in both themes: most scanners read nothing else,
 * and Master UI's two fills are those two. It is as large as the window allows,
 * so a dense code still has modules a camera can see.
 */
@Composable
fun QrDialog(shown: QrShown, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val matrix = remember(shown.code) { shown.code?.let { QrMatrix.of(it.text) } }
    val deck = shown.deck
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The square, and the title, the words and the buttons round it (about 300dp), inside the window.
        val side = minOf(maxWidth - 32.dp, maxHeight - 300.dp).coerceIn(280.dp, 880.dp)
        MuDialog(
            title = shown.name,
            onDismiss = onDismiss,
            width = side + 48.dp,
            description = "Main ${deck.main.size} · Extra ${deck.extra.size} · Side ${deck.side.size}. " +
                "Scan it with Neue Master Tool on a phone or tablet: Import, then Scan a QR code.",
            footer = {
                MuButton("Copy the YDKe code", onCopy, variant = BtnVariant.GHOST)
                MuButton("Done", onDismiss, variant = BtnVariant.PRIMARY)
            },
        ) {
            val code = shown.code
            if (code == null || matrix == null) {
                Help("This deck is too large for one QR code. Share its .ydkx file instead.")
            } else {
                QrPicture(matrix, Modifier.fillMaxWidth().aspectRatio(1f))
                Help("Carries ${inWords(code.carries)}.", Modifier.padding(top = 12.dp))
                if (code.leftOut.isNotEmpty()) {
                    Help("Too much for one code: ${inWords(code.leftOut)} stayed behind. The .ydkx file carries everything.", Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** "a, b and c". */
private fun inWords(items: List<String>): String =
    if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

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
