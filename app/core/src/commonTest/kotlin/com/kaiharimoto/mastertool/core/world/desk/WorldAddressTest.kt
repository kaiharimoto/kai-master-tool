package com.kaiharimoto.mastertool.core.world.desk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorldAddressTest {
    @Test
    fun parseAndFormatRoundTrip() {
        val all = listOf(
            WorldAddress.Home(),
            WorldAddress.Home("hand odds"),
            WorldAddress.Home("Ash & Joyous"),
            WorldAddress.Board("b3-9f2k"),
            WorldAddress.File("notes/plan.md"),
            WorldAddress.File("out/1a2b.log"),
            WorldAddress.Run(1_727_000_000_000L),
            WorldAddress.Instrument("card_web"),
            WorldAddress.App("hand-odds"),
        )
        all.forEach { a -> assertEquals(a, WorldAddress.parse(a.format()), a.format()) }
        assertEquals("world://home", WorldAddress.Home().format())
        assertEquals("world://home?q=hand", WorldAddress.Home("hand").format())
        assertEquals("world://runs/${1_727_000_000_000L.toString(36)}", WorldAddress.Run(1_727_000_000_000L).format())
    }

    @Test
    fun aPersonMayLeaveTheSchemeOff() {
        assertEquals(WorldAddress.Board("b3"), WorldAddress.parse("boards/b3"))
        assertEquals(WorldAddress.Home(), WorldAddress.parse(""))
        assertEquals(WorldAddress.Home(), WorldAddress.parse("  WORLD://home "))
    }

    @Test
    fun unsafePathsAreRefused() {
        listOf(
            "world://files/../secrets.txt",
            "world://files//etc/passwd",
            "world://files/C:/x",
            "world://files/.hidden",
            "world://files/a/%2E%2E/b",
            "world://apps/Hand_Odds",
            "world://apps/../x",
            "world://boards/<script>",
        ).forEach { assertIs<WorldAddress.Unknown>(WorldAddress.parse(it), it) }
    }

    @Test
    fun unknownAddressesSaySo() {
        assertIs<WorldAddress.Unknown>(WorldAddress.parse("https://example.com"))
        assertIs<WorldAddress.Unknown>(WorldAddress.parse("world://nowhere/x"))
        assertIs<WorldAddress.Unknown>(WorldAddress.parse("world://runs/!!"))
        assertIs<WorldAddress.Unknown>(WorldAddress.parse("world://home/deeper"))
        assertEquals("https://example.com", WorldAddress.parse("https://example.com").format())
    }

    @Test
    fun aQueryIsDecoded() {
        assertEquals(WorldAddress.Home("hand odds"), WorldAddress.parse("world://home?q=hand+odds"))
        assertEquals(WorldAddress.Home("café"), WorldAddress.parse("world://home?q=caf%C3%A9"))
        assertEquals(WorldAddress.Home("100%"), WorldAddress.parse(WorldAddress.Home("100%").format()))
    }
}
