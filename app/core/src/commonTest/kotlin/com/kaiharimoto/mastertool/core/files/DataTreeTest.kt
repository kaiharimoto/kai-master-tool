package com.kaiharimoto.mastertool.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase I, I.2: the data folder lent to the Files app, read only — never its secrets, never past its edge. */
class DataTreeTest {

    @Test
    fun theFolderIsShownButItsSecretsAreNot() {
        listOf("", "decks", "duel/replays/r1.json", "ai/SOUL.md", "ai/guides/d.md", "ai/traces/2026-10-12.jsonl", "banlists/tcg.json")
            .forEach { assertTrue(DataTree.visible(it), it) }
        listOf("secrets", "secrets/credentials.bin", "cli-run/x", "browser/Default/Cookies", "ai/credentials.json", "ai/run/token")
            .forEach { assertFalse(DataTree.visible(it), it) }
    }

    @Test
    fun anIdNeverClimbsOutOfTheFolder() {
        assertEquals("", DataTree.relOf(DataTree.ROOT))
        assertEquals("duel/replays", DataTree.relOf(DataTree.idOf("duel/replays")))
        listOf("data/../x", "data/..", "data/./x", "data//x", "data/x/", "../data", "/etc/passwd", "datax", "data/.hidden", "data/a\\..\\b", "data/secrets/k")
            .forEach { assertNull(DataTree.relOf(it), it) }
    }
}
