package com.kaiharimoto.neue.qr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.kaiharimoto.mastertool.core.ydk.DeckQrGrid
import com.kaiharimoto.neue.kit.BtnVariant
import com.kaiharimoto.neue.kit.Help
import com.kaiharimoto.neue.kit.Micro
import com.kaiharimoto.neue.kit.MuButton
import com.kaiharimoto.neue.kit.MuDialog

/**
 * A deck shown as a QR code (1.0.30): its name for the title, its counts, the
 * code ([DeckQr]: the whole deck, in one code or several; null only for a deck
 * no number of codes could hold), and the `ydke://` line the Copy button gives,
 * for the simulators.
 */
data class QrShown(val name: String, val deck: Deck, val code: DeckQrCode?, val ydke: String)

/**
 * The deck as a QR code to scan (1.0.30; the whole deck, 1.0.31, kai: "carry
 * as much information as possible, including groups"): the desk's way to hand a
 * deck to a phone or a tablet, which read it with Import → Scan a QR code. It
 * carries the deck's `.ydkx` — cards, groups, goals, notes — with its name and
 * covers, and says so under the code.
 *
 * **A deck past one code shows all its parts at once** (1.0.32, kai: "don't have
 * it show one at a time… the user would need to click every time"), in the grid
 * that makes each as large as the window allows (`DeckQrGrid`), numbered: the
 * phone's camera is swept across them and collects each, in any order, and a
 * screenshot of the grid holds them all.
 *
 * The codes are black on white in both themes: most scanners read nothing else,
 * and Master UI's two fills are those two.
 */
@Composable
fun QrDialog(shown: QrShown, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val matrices = remember(shown.code) { shown.code?.parts?.map { QrMatrix.of(it) }.orEmpty() }
    val deck = shown.deck
    val count = matrices.size
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The room for the codes: the window, less the dialog's margins, padding and
        // its title, words and buttons (about 290dp down).
        val roomAcross = (minOf(maxWidth, 1600.dp) - 32.dp - 48.dp).coerceAtLeast(240.dp)
        val roomDown = (maxHeight - 290.dp).coerceAtLeast(240.dp)
        val gap = 16.dp
        val label = if (count > 1) 24.dp else 0.dp
        val cols = DeckQrGrid.columns(count, roomAcross.value, roomDown.value, gap.value, label.value)
        val side = DeckQrGrid.side(count, cols, roomAcross.value, roomDown.value, gap.value, label.value).dp.coerceIn(200.dp, 880.dp)
        val gridWidth = side * cols + gap * (cols - 1).coerceAtLeast(0)
        MuDialog(
            title = shown.name,
            onDismiss = onDismiss,
            width = gridWidth.coerceAtLeast(320.dp) + 48.dp,
            description = "Main ${deck.main.size} · Extra ${deck.extra.size} · Side ${deck.side.size}. " + if (count > 1) {
                "The deck is in $count codes. Scan them with Neue Master Tool on a phone or tablet (Import, then Scan a QR code) " +
                    "by moving the camera across them, in any order. A screenshot of all of them imports too."
            } else {
                "Scan it with Neue Master Tool on a phone or tablet: Import, then Scan a QR code."
            },
            footer = {
                MuButton("Copy the YDKe code", onCopy, variant = BtnVariant.GHOST)
                MuButton("Done", onDismiss, variant = BtnVariant.PRIMARY)
            },
        ) {
            val code = shown.code
            if (code == null || matrices.isEmpty() || matrices.any { it == null }) {
                Help("This deck is too large to show as QR codes. Share its .ydkx file instead.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    matrices.chunked(cols).forEachIndexed { row, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            line.forEachIndexed { i, matrix ->
                                Column(Modifier.width(side)) {
                                    QrPicture(matrix!!, Modifier.size(side))
                                    if (count > 1) Micro("${row * cols + i + 1} of $count", Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                }
                Help("Carries ${inWords(code.carries)}.", Modifier.padding(top = 12.dp))
                if (code.leftOut.isNotEmpty()) {
                    Help("Too much even for ${DeckQr.MAX_PARTS} codes: ${inWords(code.leftOut)} stayed behind. The .ydkx file carries everything.", Modifier.padding(top = 4.dp))
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
