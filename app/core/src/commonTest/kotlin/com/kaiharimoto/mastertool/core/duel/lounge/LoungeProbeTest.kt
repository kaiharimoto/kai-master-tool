package com.kaiharimoto.mastertool.core.duel.lounge

import com.kaiharimoto.mastertool.core.duel.lounge.LoungeProbe.Verdict
import com.kaiharimoto.mastertool.core.update.DesktopOs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoungeProbeTest {
    private val address = "duel.labrynth.info"

    private fun read(status: Int?, body: String? = null, error: String? = null, nonce: String = "abc123", door: String? = "door-1") =
        LoungeProbe.read(status, body, error, nonce, door, 47390, address)

    @Test
    fun theAddressIsCheckedAtItsHostWhateverKaiTyped() {
        assertEquals("https://duel.labrynth.info/api/ping?n=x", LoungeProbe.url("duel.labrynth.info", "x"))
        assertEquals("https://duel.labrynth.info/api/ping?n=x", LoungeProbe.url(" https://duel.labrynth.info/ ", "x"))
        assertEquals("http://192.168.1.4:47390/api/ping?n=x", LoungeProbe.url("http://192.168.1.4:47390/lobby?a=b", "x"))
        assertNull(LoungeProbe.url("  ", "x"))
        assertNull(LoungeProbe.url("https://", "x"))
    }

    @Test
    fun itsOwnDoorAnsweringTheNonceIsReached() {
        val body = LoungeProbe.answer("abc123", "door-1")
        val p = read(200, body)
        assertEquals(Verdict.OK, p.verdict)
        assertTrue("duel.labrynth.info" in p.words)
    }

    @Test
    fun theAnswerCarriesNothingButTheNonceAndTheDoorAndNoQuotesSlipIn() {
        val body = LoungeProbe.answer("ab\"c,\"evil\":1", "door-1")
        assertEquals("""{"lounge":1,"n":"abcevil1","door":"door-1"}""", body)
        assertEquals("""{"lounge":1,"n":"${"a".repeat(64)}","door":"door-1"}""", LoungeProbe.answer("a".repeat(500), "door-1"))
    }

    @Test
    fun anotherDoorOrAnotherNonceOrAnotherServerIsNotReached() {
        assertEquals(Verdict.FAIL, read(200, LoungeProbe.answer("abc123", "door-2")).verdict)
        assertTrue("Another computer" in read(200, LoungeProbe.answer("abc123", "door-2")).words)
        assertEquals(Verdict.FAIL, read(200, LoungeProbe.answer("old", "door-1")).verdict)
        assertTrue("not a Lounge" in read(200, "<html>Welcome to nginx</html>").words)
        assertTrue("localhost:47390" in read(404, "Not found").words)
        // With no door open (a check before the door knew its id), the nonce alone decides.
        assertEquals(Verdict.OK, read(200, LoungeProbe.answer("abc123", "door-9"), door = null).verdict)
    }

    @Test
    fun cloudflaresOwnFailuresSayWhatToFix() {
        assertTrue("tunnel is not connected" in read(530, "error code: 1033").words)
        assertTrue("tunnel is not connected" in read(503, "Error 1033 Argo Tunnel error").words)
        assertTrue("http://localhost:47390" in read(502, "Bad gateway").words)
        assertEquals(Verdict.FAIL, read(504).verdict)
        assertEquals(Verdict.WARN, read(403, "<title>Sign in ・ Cloudflare Access</title>").verdict)
        assertEquals(Verdict.WARN, read(302).verdict)
        assertEquals(Verdict.WARN, read(429).verdict)
        assertEquals(Verdict.FAIL, read(500).verdict)
    }

    @Test
    fun whatTheNetworkSaidIsReadIntoItsCause() {
        assertTrue("nameservers" in read(null, error = "UnknownHostException: duel.labrynth.info: Name or service not known").words)
        assertTrue("nameservers" in read(null, error = "UnknownHostException: No such host is known (duel.labrynth.info)").words)
        assertTrue("certificate" in read(null, error = "SSLHandshakeException: PKIX path building failed").words)
        assertTrue("in time" in read(null, error = "HttpRequestTimeoutException: Request timeout has expired").words)
        assertTrue("in time" in read(null, error = "ConnectTimeoutException: Connect timeout has expired").words)
        assertTrue("refused" in read(null, error = "ConnectException: Connection refused").words)
        assertEquals(Verdict.FAIL, read(null, error = "IOException: something else").verdict)
        assertEquals(Verdict.FAIL, read(null).verdict)
    }

    @Test
    fun cloudflaredHasAnInstallLineForEachComputer() {
        assertTrue("winget" in LoungeProbe.installLine(DesktopOs.WINDOWS)!!)
        assertTrue("brew" in LoungeProbe.installLine(DesktopOs.MAC)!!)
        assertTrue(".deb" in LoungeProbe.installLine(DesktopOs.LINUX)!!)
    }
}
