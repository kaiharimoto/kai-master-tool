package com.kaiharimoto.mastertool.core.duel.net

/**
 * How a guest finds a host on the same network (1.0.77): the host's IPv4 address, its port and a
 * 16-bit secret — exactly 64 bits — packed into thirteen characters to read out or type, `K7Q2-M9XA-3FBCP`,
 * and the same code in a QR to scan. The secret keeps a stranger on the same Wi-Fi from sitting down.
 *
 * Crockford's base 32: no I, L, O or U, and a typed I, L or O reads as 1, 1 or 0, so a code read aloud
 * survives the reading.
 */
object PairCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val DIGITS = 13

    data class Table(val host: String, val port: Int, val secret: Int)

    fun encode(t: Table): String? {
        val parts = t.host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (parts.size != 4 || t.port !in 1..65535) return null
        var bits = 0uL
        parts.forEach { bits = (bits shl 8) or it.toULong() }
        bits = (bits shl 16) or t.port.toULong()
        bits = (bits shl 16) or (t.secret.toULong() and 0xFFFFuL)
        val digits = CharArray(DIGITS)
        var v = bits
        for (i in DIGITS - 1 downTo 0) {
            digits[i] = ALPHABET[(v and 31uL).toInt()]
            v = v shr 5
        }
        val s = String(digits)
        return "${s.substring(0, 4)}-${s.substring(4, 8)}-${s.substring(8)}"
    }

    fun decode(code: String): Table? {
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
            .replace('I', '1').replace('L', '1').replace('O', '0')
        if (clean.length != DIGITS || clean.any { it !in ALPHABET }) return null
        // Thirteen digits are 65 bits; the first may carry only the top four.
        if (ALPHABET.indexOf(clean[0]) > 15) return null
        var v = 0uL
        clean.forEach { v = (v shl 5) or ALPHABET.indexOf(it).toULong() }
        val secret = (v and 0xFFFFuL).toInt()
        val port = ((v shr 16) and 0xFFFFuL).toInt()
        val ip = (v shr 32)
        if (port == 0) return null
        val a = ((ip shr 24) and 255uL).toInt()
        val b = ((ip shr 16) and 255uL).toInt()
        val c = ((ip shr 8) and 255uL).toInt()
        val d = (ip and 255uL).toInt()
        return Table("$a.$b.$c.$d", port, secret)
    }

    /** A code's QR text, so the duel's scanner knows it for one. */
    fun qr(code: String): String = "NMTDUEL:$code"

    fun fromQr(text: String): Table? = text.trim().removePrefix("NMTDUEL:").let(::decode)
}
