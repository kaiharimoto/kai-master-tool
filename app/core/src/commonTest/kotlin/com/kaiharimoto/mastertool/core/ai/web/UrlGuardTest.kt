package com.kaiharimoto.mastertool.core.ai.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlGuardTest {
    private fun refused(url: String) = assertNotNull(UrlGuard.refusal(url), "should be refused: $url")
    private fun allowed(url: String) = assertNull(UrlGuard.refusal(url), "should be allowed: $url")

    @Test
    fun publicHttpsPagesAreRead() {
        allowed("https://yugipedia.com/wiki/Ash_Blossom_%26_Joyous_Spring")
        allowed("https://ygoprodeck.com/deck/12345?x=1#top")
        allowed("  https://www.example.com  ")
        allowed("HTTPS://Example.COM/Path")
        allowed("https://example.com:8443/")
        allowed("https://xn--n3h.example/")
        allowed("https://8.8.8.8/")
        allowed("https://1.1.1.1:443/dns")
        allowed("https://172.32.0.1/") // just past 172.16/12
        allowed("https://100.128.0.1/") // just past 100.64/10
        allowed("https://[2606:4700:4700::1111]/")
        allowed("https://[::ffff:8.8.8.8]/")
        allowed("https://example.com/redirect?to=http://127.0.0.1") // the path is not the host
        allowed("https://example.com#@127.0.0.1")
    }

    @Test
    fun onlyHttps() {
        listOf("http://example.com", "ftp://example.com", "file:///etc/passwd", "example.com", "", "javascript:alert(1)", "https:/example.com")
            .forEach { refused(it) }
        assertTrue(UrlGuard.refusal("http://example.com")!!.startsWith("Only https pages"))
    }

    @Test
    fun localNamesAreRefused() {
        listOf(
            "https://localhost/", "https://LOCALHOST:8080/", "https://localhost./", "https://app.localhost/",
            "https://printer.local/", "https://nas.lan/", "https://db.internal/", "https://router.home.arpa/",
            "https://box.localdomain/",
        ).forEach { refused(it) }
    }

    @Test
    fun privateIpv4IsRefused() {
        listOf(
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255", "127.0.0.1", "127.1.2.3",
            "169.254.169.254", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.255", "192.0.0.8", "198.18.0.1",
            "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
        ).forEach { refused("https://$it/") }
        refused("https://127.0.0.1:443/")
        refused("https://127.0.0.1./")
    }

    @Test
    fun ipv4InEveryResolverSpellingIsRead() {
        assertEquals(0x7F000001L, UrlGuard.ipv4("2130706433"))
        assertEquals(0x7F000001L, UrlGuard.ipv4("0x7f000001"))
        assertEquals(0x7F000001L, UrlGuard.ipv4("0x7f.1"))
        assertEquals(0x7F000001L, UrlGuard.ipv4("0177.0.0.1"))
        assertEquals(0x7F000001L, UrlGuard.ipv4("127.1"))
        assertEquals(0x7F000001L, UrlGuard.ipv4("127.0.1"))
        assertEquals(0x0A000001L, UrlGuard.ipv4("012.0.0.01"))
        assertEquals(0xC0A80001L, UrlGuard.ipv4("0xc0.0xa8.0.1"))
        assertNull(UrlGuard.ipv4("256.0.0.1"))
        assertNull(UrlGuard.ipv4("1.2.3.4.5"))
        assertNull(UrlGuard.ipv4("08.0.0.1"))
        assertNull(UrlGuard.ipv4("4294967296"))
        assertNull(UrlGuard.ipv4("example.com"))
        listOf("2130706433", "0x7f000001", "0x7f.1", "0177.0.0.1", "127.1", "0X7F.0.0.1", "3232235777", "0xA9FEA9FE", "017700000001")
            .forEach { refused("https://$it/") }
        // A host that ends in a number is an address to a resolver; one that does not read as one is refused.
        refused("https://1.2.3.256/")
        refused("https://example.123/")
        refused("https://99999999999999/")
        allowed("https://134744072/") // 8.8.8.8
    }

    @Test
    fun privateIpv6IsRefused() {
        listOf(
            "[::1]", "[0:0:0:0:0:0:0:1]", "[::]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "[FE80::abcd]", "[febf::1]",
            "[fec0::1]", "[ff02::1]", "[::ffff:127.0.0.1]", "[::ffff:7f00:1]", "[::FFFF:10.0.0.1]", "[::ffff:169.254.169.254]",
            "[::127.0.0.1]", "[64:ff9b::192.168.0.1]", "[2002:c0a8:0001::]", "[::1]:8443",
        ).forEach { refused("https://$it/") }
        refused("https://[fe80::1%25eth0]/")
        refused("https://[::1/")
        refused("https://[not:an:address]/")
        refused("https://[1:2:3:4:5:6:7:8:9]/")
        refused("https://[1::2::3]/")
        refused("https://[::1]junk/")
    }

    @Test
    fun ipv6IsReadIntoGroups() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 1), UrlGuard.ipv6("::1"))
        assertEquals(listOf(0, 0, 0, 0, 0, 0xFFFF, 0x7F00, 1), UrlGuard.ipv6("::ffff:127.0.0.1"))
        assertEquals(listOf(0x2606, 0x4700, 0x4700, 0, 0, 0, 0, 0x1111), UrlGuard.ipv6("2606:4700:4700::1111"))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), UrlGuard.ipv6("1:2:3:4:5:6:7:8"))
        assertNull(UrlGuard.ipv6("1:2:3"))
        assertNull(UrlGuard.ipv6("12345::"))
        assertNull(UrlGuard.ipv6("::ffff:1.2.3"))
    }

    @Test
    fun trickyAddressesAreRefused() {
        refused("https://evil@127.0.0.1/")
        refused("https://user:pass@example.com/")
        refused("https://example.com@127.0.0.1/")
        refused("https://example.com\\@127.0.0.1/")
        refused("https://127.0.0.1\\.example.com/")
        refused("https://127.0.0.%31/")
        refused("https://１２７.0.0.1/") // full-width digits fold to 127.0.0.1
        refused("https://exa mple.com/")
        refused("https://example.com:/")
        refused("https://example.com:80x/")
        refused("https:///127.0.0.1/")
        refused("https://./")
    }

    @Test
    fun theReasonIsPlainWords() {
        val why = UrlGuard.refusal("https://192.168.1.1/admin")!!
        assertTrue("this computer or its own network" in why, why)
        assertTrue("192.168.1.1" in why, why)
    }

    @Test
    fun aRedirectOntoTheNetworkIsRefused() = runTest {
        val asked = mutableListOf<String>()
        val engine = MockEngine { request ->
            asked += request.url.toString()
            when {
                request.url.host == "bounce.example" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://127.0.0.1/secret"))
                request.url.host == "hop.example" && request.url.encodedPath == "/start" ->
                    respond("", HttpStatusCode.MovedPermanently, headersOf(HttpHeaders.Location, "/landing?x=1"))
                request.url.host == "loop.example" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://loop.example/again"))
                else -> respond("the page", HttpStatusCode.OK)
            }
        }
        val client = HttpClient(engine) { followRedirects = false }

        val bounced = UrlGuard.get(client, "https://bounce.example/")
        assertTrue(bounced.isFailure)
        assertTrue("this computer or its own network" in bounced.exceptionOrNull()!!.message!!)
        assertEquals(listOf("https://bounce.example/"), asked, "the private address was never asked")

        asked.clear()
        val hopped = UrlGuard.get(client, "https://hop.example/start").getOrThrow()
        assertEquals("the page", hopped.bodyAsText())
        assertEquals(listOf("https://hop.example/start", "https://hop.example/landing?x=1"), asked)

        val looped = UrlGuard.get(client, "https://loop.example/", maxHops = 3)
        assertTrue("Too many redirects" in looped.exceptionOrNull()!!.message!!)

        asked.clear()
        assertTrue(UrlGuard.get(client, "https://10.0.0.1/").isFailure)
        assertTrue(asked.isEmpty(), "a refused address is never asked")
    }
}
