package com.kaiharimoto.neue

import com.google.zxing.RGBLuminanceSource
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Deck
import com.kaiharimoto.mastertool.core.ydk.DeckCodes
import com.kaiharimoto.mastertool.core.ydk.YdkeCodec
import com.kaiharimoto.neue.qr.QrMatrix
import com.kaiharimoto.neue.qr.QrReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desk's QR code and the phone's reading of it (1.0.30, v1.3.7), end to end:
 * a deck's code drawn as the dialog draws it, read back as a picture of it is.
 */
class QrTest {

    /** The largest deck there is: sixty, fifteen and fifteen, every passcode different. */
    private val fullest = Deck(
        main = List(60) { CardId(10_000_000 + it * 1_234_567) },
        extra = List(15) { CardId(20_000_000 + it * 987_654) },
        side = List(15) { CardId(30_000_000 + it * 55_555) },
    )

    /** [matrix] as pixels, [scale] to a module, with the quiet zone; [invert] for light on dark. */
    private fun picture(matrix: QrMatrix, scale: Int = 4, invert: Boolean = false): RGBLuminanceSource {
        val span = (matrix.size + QrMatrix.QUIET * 2) * scale
        val pixels = IntArray(span * span) { i ->
            val x = i % span / scale - QrMatrix.QUIET
            val y = i / span / scale - QrMatrix.QUIET
            val dark = x in 0 until matrix.size && y in 0 until matrix.size && matrix[x, y]
            if (dark != invert) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return RGBLuminanceSource(span, span, pixels)
    }

    @Test
    fun theFullestDeckFitsOneCodeAndReadsBack() {
        val code = YdkeCodec.encode(fullest)
        val matrix = assertNotNull(QrMatrix.of(code))
        // Version 17, 85 modules a side: five pixels each in the dialog at 1x, read from across a desk.
        assertTrue(matrix.size <= 85, "a ${matrix.size}-module code")
        val read = assertNotNull(QrReader.read(picture(matrix)))
        assertEquals(code, read)
        assertEquals(fullest, DeckCodes.read(read)?.document?.deck)
    }

    @Test
    fun aCodeLightOnDarkReadsToo() {
        val code = YdkeCodec.encode(Deck(main = listOf(CardId(89631139), CardId(14558127))))
        val matrix = assertNotNull(QrMatrix.of(code))
        assertEquals(code, QrReader.read(picture(matrix, invert = true)))
    }

    @Test
    fun aPictureWithNoCodeIsNothing() {
        assertNull(QrReader.read(RGBLuminanceSource(64, 64, IntArray(64 * 64) { 0xFFFFFFFF.toInt() })))
    }
}
