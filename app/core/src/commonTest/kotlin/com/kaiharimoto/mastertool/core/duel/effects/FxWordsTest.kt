package com.kaiharimoto.mastertool.core.duel.effects

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A script read back in words (Phase D step 2, D.md §3.5): our own sentences from the data alone, as §2.5½ shows them.
 */
class FxWordsTest {
    private val nameOf: (Int) -> String? = { code -> FxRef.cards.firstOrNull { it.id.value == code }?.name }

    @Test
    fun theDocsExampleReadsAsItsSketch() {
        val lines = FxWords.of(FxRef.script(FxRef.SCOUT), nameOf)
        assertEquals(listOf("e1", "e2"), lines.map { it.id })
        assertEquals("Effect 1 · Search", lines[0].head)
        assertEquals(
            "Trigger effect from the Monster Zone. If this card is Normal or Special Summoned, you can add 1 “Example” monster of Level 1–4 " +
                "from your Deck to your hand. Once per turn, by name.",
            lines[0].text,
        )
        assertEquals(
            "Quick Effect from the GY. Only if it is their turn. Cost: banish this card. Target 1 face-up monster they control. " +
                "Return the target to the hand. Once per turn, by name.",
            lines[1].text,
        )
    }

    @Test
    fun everyReferenceScriptReadsInWordsWithoutAGap() {
        FxRef.book.cards.forEach { code ->
            val s = FxRef.book.script(code)!!
            val text = FxWords.text(s, nameOf)
            assertTrue("newer build" !in text, "${s.name}: $text")
            assertTrue(s.effects.isEmpty() && s.summon == null || text.isNotBlank(), s.name)
        }
    }

    @Test
    fun summoningRulesProceduresAndJoinsReadPlainly() {
        val warden = FxWords.text(FxRef.script(FxRef.WARDEN), nameOf)
        assertTrue("Cannot be Normal Summoned or Set" in warden && "Special Summon it from the hand if you control" in warden, warden)
        val paladin = FxWords.text(FxRef.script(FxRef.PALADIN), nameOf)
        assertTrue("Synchro: 1 Tuner monster + 1 or more non-Tuner monsters" in paladin, paladin)
        val denial = FxWords.text(FxRef.script(FxRef.DENIAL), nameOf)
        assertTrue("Cost: pay 1000 LP" in denial && "Negate the activation, and if you do, destroy that card" in denial, denial)
        val mill = FxWords.text(FxRef.script(FxRef.MILL), nameOf)
        assertTrue(", then you gain 100 LP" in mill, mill)
        val call = FxWords.text(FxRef.script(FxRef.CALL), nameOf)
        assertTrue("You cannot Special Summon from the Extra Deck except Synchro cards the turn you activate it" in call, call)
        assertTrue("You can activate only 1 of this card a turn" in call, call)
        val echo = FxWords.text(FxRef.script(FxRef.ECHO), nameOf)
        assertTrue("When this card is sent to the GY, you can draw 1 card" in echo && "It can miss the timing" in echo, echo)
        val chimera = FxWords.text(FxRef.script(FxRef.CHIMERA), nameOf)
        assertTrue("Fusion materials: 1 “Example Scout” monster + 1 LIGHT monster" in chimera, chimera)
    }

    @Test
    fun aWordThisBuildCannotReadSaysSo() {
        val s = CardScript(900_000_996, name = "Odd", effects = listOf(Effect("e1", kind = Kind.IGNITION, from = setOf(Where.MONSTER_ZONE), does = listOf(Step(Op.Unknown(JsonObject(emptyMap())))))))
        assertTrue("(a step a newer build wrote)" in FxWords.text(s), FxWords.text(s))
    }
}
