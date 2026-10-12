package com.kaiharimoto.mastertool.core.duel.lounge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Lounge's door against guessing (the red team, 2026-10): whose address, how many guesses, how many sessions. */
class LoungeGateTest {

    @Test
    fun theTunnelsHeaderIsBelievedOnlyFromThisComputer() {
        // From cloudflared on this computer: the visitor it names.
        assertEquals("203.0.113.7", LoungeAddress.of("127.0.0.1", "203.0.113.7"))
        assertEquals("203.0.113.7", LoungeAddress.of("0:0:0:0:0:0:0:1", " 203.0.113.7 "))
        assertEquals("203.0.113.7", LoungeAddress.of("::ffff:127.0.0.1", "203.0.113.7"))
        // From a device on the LAN: its own address, whatever header it wrote.
        assertEquals("192.168.1.20", LoungeAddress.of("192.168.1.20", "203.0.113.7"))
        assertEquals("192.168.1.20", LoungeAddress.of("192.168.1.20", null))
        // A header that is not an address is not believed: the socket's own is counted.
        for (junk in listOf("evil", "203.0.113.7, 10.0.0.1", "300.1.1.1", "1.2.3", "", "::g")) {
            assertEquals("127.0.0.1", LoungeAddress.of("127.0.0.1", junk), junk)
        }
        assertEquals("127.0.0.1", LoungeAddress.of("127.0.0.1", null))
        // A socket that is no address at all is counted as it reads.
        assertEquals("unknown", LoungeAddress.of("unknown", "203.0.113.7"))
    }

    @Test
    fun anIpv6VisitorCountsByItsBlockAndEveryWritingIsOneKey() {
        assertEquals("2001:db8:1:2::/64", LoungeAddress.of("127.0.0.1", "2001:db8:1:2::abcd"))
        assertEquals("2001:db8:1:2::/64", LoungeAddress.of("::1", "2001:0DB8:0001:0002:ffff:0:0:1"))
        assertEquals("2001:db8:1:2::/64", LoungeAddress.of("::1", "[2001:db8:1:2:3:4:5:6]"))
        // IPv4 written as IPv6 is IPv4.
        assertEquals("203.0.113.7", LoungeAddress.of("127.0.0.1", "::ffff:203.0.113.7"))
        // A socket on the LAN is its whole address, never a block: the household's devices are each their own.
        assertEquals("fe80:0:0:0:1:2:3:4", LoungeAddress.of("fe80::1:2:3:4%eth0", null))
        assertEquals("fe80:0:0:0:1:2:3:5", LoungeAddress.of("fe80::1:2:3:5", null))
    }

    @Test
    fun addressesAreReadStrictly() {
        for (no in listOf("", "1.2.3", "1.2.3.4.5", "1.2.3.256", "1.2.3.-4", "１.２.３.４", "1:2:3", "1::2::3", ":::", "12345::", "g::1", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "::1.2.3")) {
            assertNull(LoungeAddress.parse(no), no)
        }
        assertEquals(4, LoungeAddress.parse("8.8.8.8")?.size)
        for (yes in listOf("::", "::1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:1.2.3.4", "1::", "[::1]", "fe80::1%2")) {
            assertEquals(16, LoungeAddress.parse(yes)?.size, yes)
        }
        assertTrue(LoungeAddress.loopback(assertNotNull(LoungeAddress.parse("127.8.9.10"))))
        assertFalse(LoungeAddress.loopback(assertNotNull(LoungeAddress.parse("128.0.0.1"))))
        assertTrue(LoungeAddress.loopback(assertNotNull(LoungeAddress.parse("::1"))))
        assertFalse(LoungeAddress.loopback(assertNotNull(LoungeAddress.parse("::2"))))
        assertFalse(LoungeAddress.loopback(assertNotNull(LoungeAddress.parse("::ffff:10.0.0.1"))))
    }

    @Test
    fun forgedAddressesCannotOutguessEveryonesLimit() {
        val gate = PasscodeGate()
        var t = 0L
        var checked = 0
        // A thousand addresses, one wrong guess each, as fast as the door will check them.
        repeat(1_000) { i ->
            val who = "10.0.${i / 256}.${i % 256}"
            if (gate.waitMs(who, t) == 0L) {
                gate.failed(who, t)
                checked++
            }
            t += 10
        }
        assertEquals(PasscodeGate.FAILURES, checked)
        // Everyone waits, a fresh address too, until the oldest wrong one is a window old.
        assertTrue(gate.waitMs("198.51.100.1", t) > 0)
        assertEquals(PasscodeGate.WINDOW_MS - t, gate.waitMs("198.51.100.1", t))
        assertEquals(0L, gate.waitMs("198.51.100.1", PasscodeGate.WINDOW_MS))

        // A day of it, a guess every tenth of a second from a new address: thirty a minute, and no more.
        val day = PasscodeGate()
        var guesses = 0
        var now = 0L
        var i = 0
        while (now < 24 * 60 * 60_000L) {
            val who = "a${i++}"
            if (day.waitMs(who, now) == 0L) {
                day.failed(who, now)
                guesses++
            }
            now += 100
        }
        assertTrue(guesses <= PasscodeGate.FAILURES * 24 * 60, "guesses in a day: $guesses")
        assertTrue(day.addressesKept <= Lockouts.CAP, "addresses kept: ${day.addressesKept}")
    }

    @Test
    fun oneAddressStillWaitsOnItsOwn() {
        val gate = PasscodeGate()
        repeat(Lockout.FREE) { gate.failed("a", 0L) }
        assertEquals(0L, gate.waitMs("a", 0L))
        gate.failed("a", 0L)
        assertEquals(Lockout.FIRST_WAIT_MS, gate.waitMs("a", 0L))
        assertEquals(0L, gate.waitMs("b", 0L))
        gate.passed("a")
        assertEquals(0L, gate.waitMs("a", 0L))
    }

    @Test
    fun theAddressesKeptAreBounded() {
        // Not made to wait: let go first.
        val loose = Lockouts(cap = 100)
        repeat(10_000) { loose.failed("x$it", it.toLong()) }
        assertTrue(loose.size <= 100, "kept: ${loose.size}")
        // All made to wait: those whose wait ends soonest go, the latest stay.
        val held = Lockouts(free = 0, cap = 100)
        repeat(1_000) { held.failed("y$it", it.toLong()) }
        assertTrue(held.size <= 100, "kept: ${held.size}")
        assertTrue(held.waitMs("y999", 999L) > 0)
        assertEquals(0L, held.waitMs("y0", 999L))
    }

    @Test
    fun sessionsAreBoundedAndAge() {
        val s = LoungeSessions(max = 3, maxAgeMs = 1_000L)
        listOf("a", "b", "c", "d").forEach { s.add(it, 0L) }
        assertEquals(3, s.size)
        assertFalse(s.has("a", 1L), "the oldest let go")
        assertTrue(s.has("d", 1L))
        assertFalse(s.has("b", 1_000L), "past the cookie's age")
        // A passcode given ten thousand times keeps the newest few hundred.
        val many = LoungeSessions()
        repeat(10_000) { many.add("h$it", 0L) }
        assertEquals(LoungeSessions.MAX, many.size)
        assertTrue(many.has("h9999", 1L))
        assertFalse(many.has("h0", 1L))
    }
}
