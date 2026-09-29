package com.kaiharimoto.mastertool.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackChainTest {

    private val everything = BackFlags(
        updateDialog = true, overlay = true, top = true, coverPicker = true, goal = true, draft = true,
        focus = true, palettes = true, isolation = true, selection = true, immersive = true, offBuilder = true,
    )

    @Test
    fun escAndBackUnwindTheSameLayersInTheSameOrder() {
        var flags = everything
        val escOrder = mutableListOf<Unwind>()
        while (true) {
            val step = BackChain.esc(flags) ?: break
            escOrder += step
            flags = flags.without(step)
        }
        assertEquals(
            listOf(
                Unwind.UPDATE_DIALOG, Unwind.OVERLAY, Unwind.TOP, Unwind.COVER_PICKER, Unwind.GOAL, Unwind.DRAFT,
                Unwind.FOCUS, Unwind.PALETTES, Unwind.ISOLATION, Unwind.SELECTION, Unwind.IMMERSIVE,
            ),
            escOrder,
        )
    }

    @Test
    fun backNeverDropsFocusOrTheSelection() {
        val flags = BackFlags(focus = true, isolation = true, selection = true)
        assertNull(BackChain.back(flags))
        var all = everything
        while (true) {
            val step = BackChain.back(all) ?: break
            check(step != Unwind.FOCUS && step != Unwind.SELECTION && step != Unwind.ISOLATION) { "Back unwound $step" }
            all = all.without(step)
        }
    }

    @Test
    fun backCatchesWhatUsedToSendTheAppHome() {
        assertEquals(Unwind.GOAL, BackChain.back(BackFlags(goal = true)))
        assertEquals(Unwind.DRAFT, BackChain.back(BackFlags(draft = true)))
        assertEquals(Unwind.COVER_PICKER, BackChain.back(BackFlags(coverPicker = true)))
        // The Groups panel's palettes, left out, are something open: Back puts them away.
        assertEquals(Unwind.PALETTES, BackChain.back(BackFlags(palettes = true)))
        // Deep zen is inside immersive: Back leaves full screen before it leaves the app.
        assertEquals(Unwind.IMMERSIVE, BackChain.back(BackFlags(immersive = true, offBuilder = true)))
        assertEquals(Unwind.TO_BUILDER, BackChain.back(BackFlags(offBuilder = true)))
        assertNull(BackChain.back(BackFlags()))
        // Esc does not change page.
        assertNull(BackChain.esc(BackFlags(offBuilder = true)))
    }

    private fun BackFlags.without(step: Unwind) = when (step) {
        Unwind.UPDATE_DIALOG -> copy(updateDialog = false)
        Unwind.OVERLAY -> copy(overlay = false)
        Unwind.TOP -> copy(top = false)
        Unwind.COVER_PICKER -> copy(coverPicker = false)
        Unwind.GOAL -> copy(goal = false)
        Unwind.DRAFT -> copy(draft = false)
        Unwind.FOCUS -> copy(focus = false)
        Unwind.PALETTES -> copy(palettes = false)
        Unwind.ISOLATION -> copy(isolation = false)
        Unwind.SELECTION -> copy(selection = false)
        Unwind.IMMERSIVE -> copy(immersive = false)
        Unwind.TO_BUILDER -> copy(offBuilder = false)
    }
}
