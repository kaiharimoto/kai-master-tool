package com.kaiharimoto.neue

import com.kaiharimoto.mastertool.core.input.DeskShortcuts
import kotlin.test.Test
import kotlin.test.assertTrue

class DeskKeysTest {

    @Test
    fun everyKeyInTheTableCanBePressed() {
        // A binding whose key the window never names is a shortcut the help page
        // lists and the keyboard cannot reach.
        val named = DeskKeys.names.values.toSet()
        val missing = DeskShortcuts.all.map { it.chord.key }.filterNot { it in named }.distinct()
        assertTrue(missing.isEmpty(), "Keys in DeskShortcuts with no mapping in DeskKeys: $missing")
    }
}
