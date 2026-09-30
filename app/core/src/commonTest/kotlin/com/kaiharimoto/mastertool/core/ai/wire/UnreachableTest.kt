package com.kaiharimoto.mastertool.core.ai.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A service out of reach, said plainly (1.0.61). */
class UnreachableTest {
    private val url = "https://api.xiaomimimo.com/v1"

    @Test
    fun aNameThatCouldNotBeLookedUpIsSaidAsSuchOnEveryPlatform() {
        listOf(
            "Unable to resolve host \"api.xiaomimimo.com\": No address associated with hostname",
            "java.net.UnknownHostException: api.xiaomimimo.com: Name or service not known",
            "api.xiaomimimo.com: nodename nor servname provided, or not known",
            "No such host is known (api.xiaomimimo.com)",
        ).forEach { m ->
            val said = Unreachable.say(url, m)
            assertTrue(said.startsWith("This device could not look up api.xiaomimimo.com"), m)
            assertTrue("Private DNS" in said)
        }
    }

    @Test
    fun otherFailuresKeepTheirOwnWords() {
        assertTrue(Unreachable.say(url, "connect timed out").startsWith("Could not connect to api.xiaomimimo.com"))
        assertEquals("Could not reach $url: Broken pipe", Unreachable.say(url, "Broken pipe"))
        assertEquals("Could not reach $url: no answer", Unreachable.say(url, null))
        assertEquals("localhost", Unreachable.host("http://localhost:11434/v1"))
    }
}
