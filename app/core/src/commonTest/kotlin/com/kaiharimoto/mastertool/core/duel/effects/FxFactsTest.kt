package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.ai.eval.PuzzleCards
import com.kaiharimoto.mastertool.core.duel.CardInst
import com.kaiharimoto.mastertool.core.duel.CardKind
import com.kaiharimoto.mastertool.core.duel.DuelCardInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/** Card facts come from the pool, never the script (D.md §2.2), and every printing reads one script (§2.5). */
class FxFactsTest {
    private val f = FxRef.facts

    @Test
    fun factsAreThePoolsByFrame() {
        val scout = assertNotNull(f[FxRef.SCOUT])
        assertEquals(CardType.MONSTER, scout.type)
        assertEquals(setOf(CardFrame.EFFECT), scout.frames)
        assertEquals(4, scout.level)
        assertEquals(CardAttribute.LIGHT, scout.attribute)
        assertEquals("Warrior", scout.race)
        assertEquals(1600 to 1000, scout.atk to scout.def)
        assertFalse(scout.extraDeck)

        val lamp = f[FxRef.LAMP]!!
        assertTrue(lamp.tuner && CardFrame.EFFECT in lamp.frames)
        val sprite = f[FxRef.SPRITE]!!
        assertTrue(sprite.normal && sprite.tuner, "a Normal Tuner")
        assertTrue(f[FxRef.PAWN]!!.normal)
        assertFalse(scout.normal)

        val regent = f[FxRef.REGENT]!!
        assertEquals(null, regent.level, "an Xyz Monster has no Level")
        assertEquals(4, regent.rank)
        assertTrue(regent.extraDeck)
        assertEquals(ProcKind.XYZ, regent.frameProc)

        val arch = f[FxRef.ARCH]!!
        assertEquals(3, arch.link)
        assertEquals(null, arch.level)
        assertEquals(null, arch.def, "a Link Monster has no DEF")
        assertEquals(setOf(LinkArrow.TOP, LinkArrow.LEFT, LinkArrow.RIGHT), arch.arrows)
        assertEquals(setOf(LinkArrow.BOTTOM_LEFT, LinkArrow.BOTTOM_RIGHT), f[FxRef.BRIDGE]!!.arrows)

        assertTrue(f[FxRef.PALADIN]!!.let { CardFrame.SYNCHRO in it.frames && it.level == 7 && it.extraDeck })
        assertTrue(f[FxRef.CHIMERA]!!.let { it.frameProc == ProcKind.FUSION })
        assertTrue(f[FxRef.ORACLE]!!.let { CardFrame.RITUAL in it.frames && !it.extraDeck && it.frameProc == ProcKind.RITUAL })
        assertTrue(f[FxRef.SWING]!!.let { it.pendulum && it.scale == 4 && !it.extraDeck })

        val flash = f[FxRef.FLASH]!!
        assertEquals(CardType.SPELL, flash.type)
        assertTrue(flash.isSpellSub("quick-play"))
        assertEquals(null, flash.level)
        assertEquals(null, flash.attribute)
        assertTrue(f[FxRef.DENIAL]!!.let { it.type == CardType.TRAP && it.isSpellSub("Counter") })
    }

    @Test
    fun anAlternateArtworkIsTheSameCardAndReadsTheSameScript() {
        assertEquals(FxRef.SCOUT, f.canonical(FxRef.SCOUT_ALT))
        assertEquals(FxRef.SCOUT, f[FxRef.SCOUT_ALT]!!.code)
        val book = FxRef.book
        assertEquals(book.script(FxRef.SCOUT), book.script(FxRef.SCOUT_ALT))
        assertNotNull(book.effect(FxRef.SCOUT_ALT, "e2"))
        assertNull(book.script(FxRef.PAWN), "a Normal Monster has no script")
        assertNull(f[123], "an unknown passcode has no facts")
        // A script written under the alternate's passcode is still the card's.
        val alt = ScriptBook.all(listOf(CardScript(FxRef.SCOUT_ALT, name = "Example Scout")), f::canonical)
        assertNotNull(alt.script(FxRef.SCOUT))
        assertEquals(setOf(FxRef.SCOUT), alt.cards)
        // The first of two scripts for one card is kept.
        val two = ScriptBook.all(listOf(CardScript(FxRef.SCOUT, name = "first"), CardScript(FxRef.SCOUT_ALT, name = "second")), f::canonical)
        assertEquals("first", two.script(FxRef.SCOUT_ALT)!!.name)
    }

    @Test
    fun aVerifiedBookHoldsOnlyVerifiedEffectsAndProcedures() {
        val verified = ScriptBook.verified(
            FxRef.scripts,
            mapOf(FxRef.SCOUT_ALT to setOf("e1"), FxRef.BRIDGE to setOf(FxTag.PROC), FxRef.WARDEN to setOf("e9"), FxRef.LAMP to setOf("e1")),
            f::canonical,
        )
        assertTrue(verified.verifiedOnly)
        assertEquals(listOf("e1"), verified.script(FxRef.SCOUT)!!.effects.map { it.id }, "the alternate's verdict is the card's")
        assertEquals(1, verified.script(FxRef.BRIDGE)!!.summon!!.procs.size)
        assertNull(verified.script(FxRef.WARDEN), "nothing verified: unknown, inert")
        assertNull(verified.script(FxRef.COLOSSUS))
        val lamp = verified.script(FxRef.LAMP)!!
        assertEquals(emptyList(), lamp.summon!!.procs, "its procedure is not verified")
        assertEquals(listOf("e1"), lamp.effects.map { it.id })
        // What only restricts a card stays.
        val warden = ScriptBook.verified(FxRef.scripts, mapOf(FxRef.WARDEN to setOf("x", FxTag.PROC)), f::canonical).script(FxRef.WARDEN)!!
        assertFalse(warden.summon!!.normal)
        assertFalse(FxRef.book.verifiedOnly)
    }

    @Test
    fun theCatalogsViewAndTokens() {
        val raigeki = FxFacts.of(PuzzleCards.RAIGEKI, PuzzleCards.info.getValue(PuzzleCards.RAIGEKI))
        assertEquals(CardType.SPELL, raigeki.type)
        assertTrue(raigeki.isSpellSub("Normal"))
        val skull = FxFacts.of(PuzzleCards.SUMMONED_SKULL, PuzzleCards.info.getValue(PuzzleCards.SUMMONED_SKULL))
        assertEquals(6, skull.level)
        assertTrue(skull.normal)
        val xyz = FxFacts.of(1, DuelCardInfo("X", CardKind.EXTRA_MONSTER, level = 4, xyz = true, typeLine = "Xyz Effect Monster"))
        assertEquals(null to 4, xyz.level to xyz.rank)
        assertTrue(CardFrame.XYZ in xyz.frames && xyz.extraDeck)
        val link = FxFacts.of(2, DuelCardInfo("L", CardKind.EXTRA_MONSTER, link = true, linkRating = 2, typeLine = "Link Effect Monster"))
        assertEquals(2, link.link)
        val tuner = FxFacts.of(3, DuelCardInfo("T", CardKind.MONSTER, level = 3, typeLine = "Tuner Effect Monster"))
        assertTrue(tuner.tuner && !tuner.normal)
        val token = FxFacts.NONE.of(CardInst(100_000, 0, 0, token = true, name = "Sheep Token", atk = 0, def = 0))!!
        assertEquals("Sheep Token", token.name)
        assertTrue(CardFrame.TOKEN in token.frames && token.monster)
    }

    @Test
    fun theReferenceCardsSitInTheReservedRange() {
        assertTrue(FxVocab.RESERVED.first >= 100_000_000, "nine digits: never a Konami passcode")
        FxRef.cards.flatMap { it.passcodes }.forEach { assertTrue(FxVocab.reserved(it.value), "$it") }
        FxRef.scripts.forEach { assertTrue(FxVocab.reserved(it.card)) }
        assertFalse(FxVocab.reserved(90_000_001))
        assertEquals(FxRef.cards.size, FxRef.cards.map { it.id }.toSet().size)
        // Every reference script is for a card with facts, and every reference card's facts read.
        FxRef.scripts.forEach { assertNotNull(f[it.card], it.name) }
    }
}
