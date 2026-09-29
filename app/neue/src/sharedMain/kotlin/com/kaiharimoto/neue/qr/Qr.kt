package com.kaiharimoto.neue.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code's modules, [size] a side, dark or light (1.0.30): the deck's YDKe
 * code drawn on the desk for a phone or a tablet to scan. ZXing makes it; the
 * dialog draws it, with the quiet zone round it that a scanner needs.
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        /** The light border a scanner needs, in modules: the standard's four. */
        const val QUIET = 4

        /**
         * [text] as a QR code, or null when it is too long for one. Level M: a
         * code on a screen is not scuffed like a printed one, and the lower level
         * keeps a full deck's modules large enough to read from across a desk.
         */
        fun of(text: String): QrMatrix? = runCatching {
            val m = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
            QrMatrix(m.width, BooleanArray(m.width * m.height) { i -> m.get(i % m.width, i / m.width).toInt() == 1 })
        }.getOrNull()
    }
}

/** Reading a QR code back: out of a picture's pixels, on the tablet or the phone. */
object QrReader {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * The text of the QR code in [source], or null when there is none. Both of
     * ZXing's binarizers, and the picture inverted — a screenshot of a code on a
     * dark theme is light on dark.
     */
    fun read(source: LuminanceSource): String? {
        for (pixels in listOf(source, source.invert())) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(pixels)), BinaryBitmap(GlobalHistogramBinarizer(pixels)))) {
                try {
                    return QRCodeReader().decode(bitmap, hints).text
                } catch (_: ReaderException) {
                    // Not found, or not read this way: the next way.
                }
            }
        }
        return null
    }
}
