package com.kaiharimoto.mastertool.core.duel.mapper

import com.kaiharimoto.mastertool.core.duel.effects.FxPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The mapper's files (M.md §7): where they live, which travel, and that each reads back forgivingly. */
class MapperFilesTest {
    @Test
    fun aDecksFilesTravelWithTheEffectsAndItsTrainingNever() {
        assertEquals("mapper/d1/library.json", MapperPaths.library("d1", true))
        assertEquals("mapper/d1/library-2nd.json", MapperPaths.library("d1", false))
        assertEquals("mapper/d1/run-2nd.json", MapperPaths.run("d1", false))
        assertEquals("mapper/d1/starters.json", MapperPaths.starters("d1", true))
        assertEquals("mapper/d1/presets.json", MapperPaths.presets("d1"))
        listOf(MapperPaths.library("d1", true), MapperPaths.run("d1", false), MapperPaths.starters("d1", true), MapperPaths.presets("d1")).forEach {
            assertTrue(FxPaths.syncs(it), it)
        }
        assertFalse(FxPaths.syncs("mapper/d1/train/records.jsonl"))
        assertFalse(FxPaths.syncs("mapper/d1/train/x.json"))
        assertFalse(FxPaths.syncs("mapper/d1/.library.json.tmp"))
        assertFalse(FxPaths.syncs("mapper/d1/net-3.onnx"))
        assertFalse(FxPaths.syncs("other/d1/library.json"))
        // A deck id is made safe for a path.
        assertFalse('/' in MapperPaths.deck("../x").removePrefix("mapper/"))
    }

    @Test
    fun aStarterTableAndPresetsReadBackAndAnUnreadableFileIsNull() {
        val t = StarterRun(
            deckId = "d1", deck = "fp", library = "lib", rows = listOf(StarterTable.Row(listOf(1, 2), listOf("k"), true, 40, 0.25, listOf("k"), listOf(9), 3)),
        )
        assertEquals(t, StarterRun.decode(t.encode()))
        assertTrue(t.encode().contains("\"keys\":${BoardKey.VERSION}"))
        assertFalse(t.stale("fp", "lib"))
        assertTrue(t.stale("other", "lib"))
        assertNull(StarterRun.decode("{oops"))
        val p = MapperPresets().put(BoardPreset("p1", "Negates", weights = mapOf("negates" to 2.0))).copy(chosen = "p1")
        assertEquals(p, MapperPresets.decode(p.encode()))
        assertEquals(listOf(BoardPreset.DEFAULT.id, "p1"), p.all.map { it.id })
        assertEquals("", p.remove("p1").chosen)
        assertNull(MapperPresets.decode("[]"))
    }
}
