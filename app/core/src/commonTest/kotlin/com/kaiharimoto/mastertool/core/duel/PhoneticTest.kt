package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.ai.vision.NameMatch
import com.kaiharimoto.mastertool.core.duel.text.Phonetic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneticTest {
    private fun sure(heard: String, name: String) =
        assertTrue(Phonetic.score(heard, name) >= NameMatch.SURE, "“$heard” should sound like $name (${Phonetic.score(heard, name)})")

    private fun unsure(heard: String, name: String) =
        assertTrue(Phonetic.score(heard, name) < NameMatch.SURE, "“$heard” should not be sure of $name (${Phonetic.score(heard, name)})")

    @Test
    fun wordsKeyBySound() {
        assertEquals(Phonetic.word("zeus"), Phonetic.word("zoos"))
        assertEquals(Phonetic.word("phone"), Phonetic.word("fone"))
        assertEquals(Phonetic.word("knight"), Phonetic.word("nite"))
        assertEquals(Phonetic.word("lockbird"), Phonetic.word("lock") + Phonetic.word("bird"))
        assertEquals(Phonetic.key("1 for 1"), Phonetic.key("One for One"))
        assertEquals("", Phonetic.key("and the of"))
    }

    @Test
    fun whatWasHeardFindsTheCardItSoundsLike() {
        sure("zoos", "Divine Arsenal AA-ZEUS - Sky Thunder")
        sure("ash blossom", "Ash Blossom & Joyous Spring")
        sure("droll and lockbird", "Droll & Lock Bird")
        sure("drawl and lock bird", "Droll & Lock Bird")
        sure("effect vailer", "Effect Veiler")
        sure("blue eyes white dragon", "Blue-Eyes White Dragon")
        unsure("kuriboh", "Dark Magician")
        unsure("mirror", "Pot of Prosperity")
        assertEquals(0.0, Phonetic.score("", "Kuriboh"))
    }
}
