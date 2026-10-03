package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.text.DuelComplete
import com.kaiharimoto.mastertool.core.duel.text.DuelComplete.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DuelCompleteTest {
    private val s = battle()

    private fun suggest(text: String, history: List<String> = emptyList()) =
        DuelComplete.suggest(text, text.length, s, 0, catalog, history)

    @Test
    fun theFirstWordOffersVerbsPhasesCuesQuestionsAndCards() {
        val su = suggest("su")
        assertEquals("summon", su.first().insert)
        assertEquals(Kind.VERB, su.first().kind)
        assertTrue(suggest("s").any { it.insert == "s" && it.kind == Kind.VERB })
        assertTrue(suggest("b").any { it.insert == "bp" && it.kind == Kind.PHASE })
        assertTrue(suggest("no").any { it.insert == "no response" && it.kind == Kind.CUE })
        assertTrue(suggest("og").any { it.insert == "ogy" && it.kind == Kind.QUERY })
        // A card by its name, offered by its coordinate.
        val ash = suggest("ash")
        assertTrue(ash.any { it.kind == Kind.CARD && it.insert == "h4" && it.label == "Ash Blossom & Joyous Spring · h4" }, ash.toString())
    }

    @Test
    fun afterAVerbItsCardsThenWhereTheyGo() {
        val cards = suggest("s ")
        assertTrue(cards.any { it.insert == "h1" && it.label.startsWith("Blue-Eyes") }, cards.toString())
        // Their hidden hand is never named.
        assertFalse(cards.any { it.label.contains("Dark Magician") && it.insert.startsWith("oh") })
        val zones = suggest("s h1 ")
        assertTrue(zones.all { it.kind == Kind.ZONE }, zones.toString())
        assertTrue(zones.any { it.insert == "m3" })
        assertFalse(zones.any { it.insert == "m1" }, "M1 is taken")
        val spells = suggest("e h3 ")
        assertTrue(spells.any { it.insert == "s2" } && spells.none { it.insert.startsWith("m") }, spells.toString())
        val hosts = suggest("o h1 ")
        assertTrue(hosts.any { it.insert == "m1" && it.label.startsWith("Blue-Eyes") }, hosts.toString())
    }

    @Test
    fun attacksOfferTheirMonstersAndDirect() {
        val at = suggest("attack m1 ")
        assertTrue(at.any { it.insert == "om1" && it.label.startsWith("Dark Magician") }, at.toString())
        assertTrue(at.any { it.insert == "direct" })
        assertTrue(suggest("attack ").any { it.insert == "m1" })
    }

    @Test
    fun historyAndApplying() {
        val h = suggest("s h", listOf("s h1 m3; bp", "draw"))
        assertEquals(Kind.HISTORY, h.first().kind)
        assertEquals("s h1 m3; bp" to 11, DuelComplete.apply("s h", 3, h.first()))
        val zone = suggest("s h1 m").first { it.insert == "m3" }
        assertEquals("s h1 m3 " to 8, DuelComplete.apply("s h1 m", 6, zone))
        // After a ";" a new move begins.
        assertTrue(suggest("s h1 m3; b").any { it.insert == "bp" })
    }
}
