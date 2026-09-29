package com.kaiharimoto.mastertool.core.ydk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Base45Test {

    @Test
    fun theRfcsExamples() {
        // RFC 9285 §4.3.
        assertEquals("BB8", Base45.encode("AB".encodeToByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".encodeToByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".encodeToByteArray()))
        assertEquals("ietf!", Base45.decode("QED8WEX0")?.decodeToString())
    }

    @Test
    fun everyByteRoundTrips() {
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, Base45.decode(Base45.encode(all)))
        assertContentEquals(all.copyOf(255), Base45.decode(Base45.encode(all.copyOf(255))))
        assertContentEquals(ByteArray(0), Base45.decode(""))
    }

    @Test
    fun somethingElseIsNot() {
        assertNull(Base45.decode("GGW"), "65535 + 1 is past two bytes")
        assertNull(Base45.decode("abc"), "lower case is not in the alphabet")
        assertNull(Base45.decode("A"), "one character is no byte")
    }
}
