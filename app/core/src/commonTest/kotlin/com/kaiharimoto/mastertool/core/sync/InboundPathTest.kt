package com.kaiharimoto.mastertool.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The red team's paths (1.0.97): nothing from another device or a backup lands outside its folder or in Ai's own. */
class InboundPathTest {
    @Test
    fun ordinaryPathsPass() {
        listOf("decks/abc.json", "ai/MEMORY.md", "ai/guides/d1.md", "world/w1/files/lib/odds.js", "duel/replays/r.json", "custom-art/12.png")
            .forEach { assertEquals(it, InboundPath.safe(it)) }
    }

    @Test
    fun aPathThatLeavesItsFolderIsRefused() {
        listOf(
            "ai/..\\..\\evil.dll", "present\\..\\..\\x", "ai/../x", "ai/./credentials.json", "ai//run/x", "/etc/passwd",
            "C:/Windows/x", "duel/a:stream", "", "ai/x\u0000y",
        ).forEach { assertNull(InboundPath.safe(it), it) }
    }

    @Test
    fun hiddenFilesAndAisOwnFolderAreRefused() {
        listOf("ai/run/.claude/settings.json", "ai/run/CLAUDE.md", "ai/credentials.json", "ai/cache/x", "world/w/.py/ygo.py", "art/.hidden")
            .forEach { assertNull(InboundPath.safe(it), it) }
    }
}
