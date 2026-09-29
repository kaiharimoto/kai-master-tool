package com.kaiharimoto.mastertool.core.ydk

/**
 * Base45 (RFC 9285): bytes as the 45 characters a QR code's alphanumeric mode
 * holds in 5.5 bits each — two bytes in three characters, so a QR code carries
 * ~97% of what it would as raw bytes, and no scanner guesses a character set
 * for it. The EU's COVID certificates travel the same way: zlib, then Base45.
 */
object Base45 {
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    fun encode(bytes: ByteArray): String = buildString((bytes.size + 1) / 2 * 3) {
        var i = 0
        while (i + 1 < bytes.size) {
            val n = (bytes[i].toInt() and 0xFF) * 256 + (bytes[i + 1].toInt() and 0xFF)
            append(ALPHABET[n % 45]).append(ALPHABET[n / 45 % 45]).append(ALPHABET[n / 2025])
            i += 2
        }
        if (i < bytes.size) {
            val n = bytes[i].toInt() and 0xFF
            append(ALPHABET[n % 45]).append(ALPHABET[n / 45])
        }
    }

    /** The bytes, or null when [text] is not Base45. */
    fun decode(text: String): ByteArray? {
        if (text.length % 3 == 1) return null
        val digits = IntArray(text.length) { ALPHABET.indexOf(text[it]).also { d -> if (d < 0) return null } }
        val out = ByteArray(text.length / 3 * 2 + if (text.length % 3 == 2) 1 else 0)
        var o = 0
        var i = 0
        while (i + 2 < digits.size) {
            val n = digits[i] + digits[i + 1] * 45 + digits[i + 2] * 2025
            if (n > 0xFFFF) return null
            out[o++] = (n shr 8).toByte()
            out[o++] = n.toByte()
            i += 3
        }
        if (i < digits.size) {
            val n = digits[i] + digits[i + 1] * 45
            if (n > 0xFF) return null
            out[o] = n.toByte()
        }
        return out
    }
}
