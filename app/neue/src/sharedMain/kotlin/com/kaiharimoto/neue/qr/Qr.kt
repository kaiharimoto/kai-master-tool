package com.kaiharimoto.neue.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code's modules, [size] a side, dark or light (1.0.30): a deck drawn on
 * the desk for a phone or a tablet to scan (`DeckQr`, the whole deck, 1.0.31). ZXing makes it; the
 * dialog draws it, with the quiet zone round it that a scanner needs.
 */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        /** The light border a scanner needs, in modules: the standard's four. */
        const val QUIET = 4

        /**
         * [text] as a QR code, or null when it is too long for one. Level M while
         * that keeps the code at version 25 or under (117 modules); past that, level
         * L, whose smaller code a camera reads more easily off a screen, which is
         * not scuffed like paper. A deck with its groups is usually version 20 or so.
         */
        fun of(text: String): QrMatrix? {
            val m = encode(text, ErrorCorrectionLevel.M)
            return if (m != null && m.size <= 117) m else encode(text, ErrorCorrectionLevel.L) ?: m
        }

        private fun encode(text: String, level: ErrorCorrectionLevel): QrMatrix? = runCatching {
            val m = Encoder.encode(text, level).matrix
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

    /**
     * Every QR code in [source] (1.0.32): a screenshot of a split deck's grid holds
     * all its parts, and one picture brings them all. ZXing's multi reader, both
     * binarizers and inverted, then the single reader for a lone code at an angle
     * the multi reader's finder misses.
     */
    fun readAll(source: LuminanceSource): List<String> {
        val found = LinkedHashSet<String>()
        for (pixels in listOf(source, source.invert())) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(pixels)), BinaryBitmap(GlobalHistogramBinarizer(pixels)))) {
                try {
                    QRCodeMultiReader().decodeMultiple(bitmap, hints).forEach { found += it.text }
                } catch (_: ReaderException) {
                    // None found this way.
                }
            }
        }
        if (found.isEmpty()) read(source)?.let { found += it }
        return found.toList()
    }
}
