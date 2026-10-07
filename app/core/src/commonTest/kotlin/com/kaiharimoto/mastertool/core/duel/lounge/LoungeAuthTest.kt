package com.kaiharimoto.mastertool.core.duel.lounge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoungeAuthTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun hmacMatchesRfc4231() {
        // RFC 4231, test case 2.
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(LoungeAuth.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
        )
        // RFC 4231, test case 6: a key longer than a block is hashed first.
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hex(LoungeAuth.hmacSha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray())),
        )
    }

    @Test
    fun pbkdf2MatchesRfc7914() {
        // RFC 7914 §11, PBKDF2-HMAC-SHA256: P = "passwd", S = "salt", c = 1, dkLen = 64 (two blocks).
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(LoungeAuth.pbkdf2("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64)),
        )
    }

    @Test
    fun aPasscodeIsKeptAsItsHashAndCheckedAgainstIt() {
        val salt = ByteArray(16) { it.toByte() }
        val kept = LoungeAuth.hash("labrynth-night", salt, iterations = 1_000)
        assertFalse("labrynth-night" in kept)
        assertTrue(LoungeAuth.verify("labrynth-night", kept))
        assertFalse(LoungeAuth.verify("labrynth-nighT", kept))
        assertFalse(LoungeAuth.verify("", kept))
        assertFalse(LoungeAuth.verify("labrynth-night", "plain"))
        assertFalse(LoungeAuth.verify("labrynth-night", "pbkdf2-sha256\$x\$00\$00"))
    }

    @Test
    fun guessesWaitLongerEachTime() {
        var l = Lockout()
        repeat(Lockout.FREE) { l = l.failed(0) }
        assertFalse(l.waiting(0))
        l = l.failed(1_000)
        assertTrue(l.waiting(1_000 + Lockout.FIRST_WAIT_MS - 1))
        assertFalse(l.waiting(1_000 + Lockout.FIRST_WAIT_MS))
        val next = l.failed(100_000)
        assertEquals(100_000 + Lockout.FIRST_WAIT_MS * 2, next.until)
        var many = next
        repeat(40) { many = many.failed(0) }
        assertEquals(Lockout.MAX_WAIT_MS, many.until)
    }
}
