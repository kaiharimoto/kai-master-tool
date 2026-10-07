package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.sync.Sha256

/**
 * The Lounge's passcode (`docs/LOUNGE.md`): kai sets one and shares it; a friend's browser gives it once and is
 * given a session. What kai's computer keeps is never the passcode but PBKDF2-HMAC-SHA256 of it, salted
 * ([hash]), kept with the app's other secrets; and an address that keeps guessing is made to wait ([Lockout]).
 */
object LoungeAuth {
    const val ITERATIONS = 60_000
    private const val PREFIX = "pbkdf2-sha256"

    /** The shortest passcode the Lounge takes: it stands between the internet and kai's computer. */
    const val MIN_LENGTH = 8

    /** [passcode] kept as `pbkdf2-sha256$<iterations>$<salt hex>$<hash hex>`. */
    fun hash(passcode: String, salt: ByteArray, iterations: Int = ITERATIONS): String =
        "$PREFIX\$$iterations\$${hex(salt)}\$${hex(pbkdf2(passcode.encodeToByteArray(), salt, iterations, 32))}"

    /** Whether [passcode] is the one [stored] was made from; every byte compared, whatever the first wrong one. */
    fun verify(passcode: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = unhex(parts[2]) ?: return false
        val want = unhex(parts[3]) ?: return false
        val got = pbkdf2(passcode.encodeToByteArray(), salt, iterations, want.size)
        var diff = 0
        for (i in want.indices) diff = diff or (want[i].toInt() xor got[i].toInt())
        return diff == 0
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val block = 64
        val k = (if (key.size > block) Sha256.digest(key) else key).copyOf(block)
        val inner = ByteArray(block) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(block) { (k[it].toInt() xor 0x5c).toByte() }
        return Sha256.digest(outer + Sha256.digest(inner + message))
    }

    /** RFC 8018's PBKDF2 with HMAC-SHA256: [length] bytes from [password] and [salt]. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var block = 1
        var at = 0
        while (at < length) {
            val index = byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
            var u = hmacSha256(password, salt + index)
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = hmacSha256(password, u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, length - at)
            t.copyInto(out, at, 0, n)
            at += n
            block++
        }
        return out
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null
        return out
    }
}

/**
 * Wrong passcodes from one address: [FREE] tries, then a wait that doubles with each further wrong one, up to an
 * hour. A right one clears it. Pure; the server keeps one per address.
 */
data class Lockout(val failures: Int = 0, val until: Long = 0L) {
    fun waiting(now: Long): Boolean = now < until

    fun failed(now: Long): Lockout {
        val n = failures + 1
        val wait = if (n <= FREE) 0L else minOf(MAX_WAIT_MS, FIRST_WAIT_MS shl (n - FREE - 1).coerceAtMost(12))
        return Lockout(n, if (wait > 0) now + wait else 0L)
    }

    companion object {
        const val FREE = 5
        const val FIRST_WAIT_MS = 30_000L
        const val MAX_WAIT_MS = 60 * 60_000L
    }
}
