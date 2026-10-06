package com.kaiharimoto.mastertool.core.ai.chessy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChessyTypeTest {
    @Test
    fun anEmoticonIsOneUnit() {
        val u = ChessyType.units("so cozy ♡(˶ᵔ ᵕ ᵔ˶) ok")
        assertTrue("♡(˶ᵔ ᵕ ᵔ˶)" in u)
        assertEquals("so cozy ".length + 1 + " ok".length, u.size)
        assertTrue("ฅ^•ﻌ•^ฅ" in ChessyType.units("there we go ฅ^•ﻌ•^ฅ"))
        assertTrue("≽^•⩊•^≼" in ChessyType.units("nyasty ≽^•⩊•^≼"))
        assertTrue("(≖‿≖)♡" in ChessyType.units("I ate it (≖‿≖)♡"))
        // plain brackets are words, not faces
        assertFalse(ChessyType.units("(a note)").any { it.length > 1 })
    }

    @Test
    fun nothingInsideOrJustBeforeAnEmoticonCanBreak() {
        for (line in ChessyAmie.LINES.values.flatten() + listOf("Your decks are sooo cozy ♡(˶ᵔ ᵕ ᵔ˶)", "Fine, fine… I'll be good. Probably ♡")) {
            val laid = ChessyType.layout(line)
            val u = ChessyType.units(line)
            for (i in u.indices) if (ChessyType.isKaomoji(u[i])) {
                val piece = laid.text.substring(laid.ends[i], laid.ends[i + 1])
                assertFalse(' ' in piece, "$line: a plain space inside ${u[i]}")
                // the space before it, if any, does not break
                val before = laid.ends[i] - 1
                if (before >= 0) assertTrue(laid.text[before] != ' ', "$line: a breaking space before ${u[i]}")
            }
        }
    }

    @Test
    fun theWholeLineIsLaidOutAndTypingOnlyMovesTheEnd() {
        val line = "Hehe… I'm all warm now (っ˘ω˘ς )"
        val laid = ChessyType.layout(line)
        assertEquals(ChessyType.units(line).size, laid.units)
        assertEquals(0, laid.typedTo(0))
        assertEquals(laid.text.length, laid.typedTo(laid.units))
        for (k in 1..laid.units) assertTrue(laid.ends[k] > laid.ends[k - 1])
        // what is shown reads as the line, with only no-break characters added
        assertEquals(line, laid.text.replace("⁠", "").replace(' ', ' '))
    }
}
