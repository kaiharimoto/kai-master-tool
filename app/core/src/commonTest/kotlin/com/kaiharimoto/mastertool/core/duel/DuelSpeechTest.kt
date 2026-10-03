package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.duel.CommandFixtures.battle
import com.kaiharimoto.mastertool.core.duel.CommandFixtures.catalog
import com.kaiharimoto.mastertool.core.duel.ai.AiCue
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand
import com.kaiharimoto.mastertool.core.duel.text.DuelCommand.QueryKind
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech
import com.kaiharimoto.mastertool.core.duel.voice.DuelSpeech.Spoken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The phrase corpus (1.0.87): what people say at a table, as a transcriber writes it, to the Line's exact words. */
class DuelSpeechTest {
    private val s = battle()

    /** Heard → the normalized line. */
    private val corpus = listOf(
        // Summons and sets.
        "Summon Ash Blossom to monster three." to "summon ash blossom to m3",
        "summon ash blossom to monster zone three" to "summon ash blossom to m3",
        "Summon h2 to m3" to "summon h2 to m3",
        "I'll summon blue eyes to monster zone two" to "summon blue eyes to m2",
        "Normal summon Blue-Eyes White Dragon to my third monster zone" to "summon blue eyes white dragon to m3",
        "summon hand one to monster four" to "summon h1 to m4",
        "summon the first card in my hand to monster five" to "summon h1 to m5",
        "Special summon Zeus to the left extra monster zone" to "ss zeus to e1",
        "special summon from my graveyard droll to monster one" to "ss from gy droll to m1",
        "Set pot of prosperity in spell two" to "set pot of prosperity in s2",
        "set the fourth card in my hand in back row three" to "set h4 in s3",
        "Set hand three to spell/trap zone 4." to "set h3 to s4",
        "um, set mirror force face down" to "set mirror force",
        "summon battle fader in defense position" to "summon battle fader def",
        "Uh, summon droll and lockbird to monster to" to "summon droll and lockbird to m2",
        "summon kuriboh to monster for" to "summon kuriboh to m4",
        // Activations and the chain.
        "Activate pot of prosperity" to "activate pot of prosperity",
        "activate my spell one" to "activate s1",
        "Activate the card in spell zone one." to "activate card in s1",
        "Chain Ash Blossom." to "chain ash blossom",
        "respond with ash blossom" to "chain ash blossom",
        "I activate ash blossom in response" to "activate ash blossom",
        "Resolve." to "resolve",
        "resolve the chain" to "resolve",
        "resolve it" to "resolve",
        // Battle.
        "Attack their monster one with my monster three" to "attack om1 with m3",
        "attack the opponent's monster zone one with monster three" to "attack om1 with m3",
        "Monster three attacks their monster one." to "m3 attacks om1",
        "my monster one attacks directly" to "m1 attacks direct",
        "Attack directly with monster one" to "attack direct with m1",
        "With monster two attack their monster three" to "attack om3 with m2",
        "m3 attack om2" to "m3 attacks om2",
        "attack their m1 using m2" to "attack om1 with m2",
        // Phases and the turn.
        "Go to battle." to "bp",
        "Battle phase" to "bp",
        "go to the battle phase" to "bp",
        "Battle." to "bp",
        "Main phase two" to "m2",
        "main two" to "m2",
        "second main phase" to "m2",
        "go to main phase 1" to "m1",
        "End phase." to "ep",
        "End turn." to "end",
        "end my turn" to "end",
        "Pass the turn" to "end",
        "I end my turn" to "end",
        "Next phase" to "next",
        "go to the next phase please" to "next",
        "standby phase" to "sp",
        // Cards around the table.
        "Send their monster one to the graveyard" to "send om1 to gy",
        "destroy their spell two" to "destroy os2",
        "destroy the opponent's back row one" to "destroy os1",
        "banish the top card of my graveyard" to "banish top card of gy",
        "Banish Droll from my graveyard" to "banish droll from gy",
        "Add Ash Blossom to my hand" to "add ash blossom to hand",
        "return their monster one to their hand" to "return om1 to their hand",
        "Target their spell one" to "target os1",
        "target their monster one with my spell one" to "target om1 with s1",
        "Banish ash face down" to "bfd ash",
        "switch monster one to defense position" to "pos m1",
        "change the battle position of monster one" to "pos m1",
        "move monster one to monster four" to "move m1 to m4",
        "Draw a card." to "draw",
        "Draw two cards" to "draw 2",
        "draw for turn" to "draw",
        // Life points.
        "Deal three thousand damage" to "lp o -3000",
        "inflict eighteen hundred damage to them" to "lp o -1800",
        "Take five hundred damage" to "lp -500",
        "I lose one thousand life points" to "lp -1000",
        "gain one thousand" to "lp +1000",
        "their life points minus two thousand five hundred" to "lp o -2500",
        "halve their life points" to "lp o /2",
        // Questions.
        "What's on their field?" to "their field",
        "Read my hand" to "read my hand",
        "What's in their graveyard?" to "ogy",
        "what is in my graveyard" to "gy",
        "What's the chain?" to "chain",
        "how many life points do I have" to "lp",
        "Life points." to "lp",
        "what is monster three" to "?m3",
        "what's in their monster zone one" to "?om1",
        "how many cards are in their hand" to "their hand",
        "whose turn is it" to "turn",
        "look at their spell one" to "read os1",
        "Open their graveyard" to "open ogy",
        "open the extra deck" to "open ex",
        // Whisper-isms and the short answers.
        "Monster zone 3." to "m3",
        "Yes." to "yes",
        "No response." to "no response",
        "Your move." to "your move",
        "Over to you." to "over to you",
        "Undo that" to "undo that",
        "OK, summon h1 to m3" to "summon h1 to m3",
        "Summon H1 to M3 and then go to battle" to "summon h1 to m3; bp",
        "A.I., what should I play?" to "ai what should i play",
        "Hmm, banish their graveyard two" to "banish ogy2",
        "summon h 2 to m 3" to "summon h2 to m3",
    )

    @Test
    fun theCorpusNormalizesToTheLine() {
        assertTrue(corpus.size >= 80, "${corpus.size} phrasings")
        val wrong = corpus.mapNotNull { (heard, line) -> DuelSpeech.normalize(heard).takeIf { it != line }?.let { "“$heard” → “$it”, wanted “$line”" } }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    private fun classify(heard: String, ai: Boolean = false) = DuelSpeech.classify(DuelSpeech.normalize(heard), s, 0, catalog, aiAtTable = ai)

    @Test
    fun whatIsSaidIsSorted() {
        assertEquals(Spoken.Confirm, classify("Yes."))
        assertEquals(Spoken.Confirm, classify("do it"))
        assertEquals(Spoken.Cancel, classify("No."))
        assertEquals(Spoken.Cancel, classify("scratch that"))
        assertEquals(Spoken.Undo, classify("take that back"))
        assertEquals(Spoken.Undo, classify("undo"))
        // At a table "pass" lets it resolve; it never ends the turn by voice (the red team).
        assertEquals(Spoken.Cue(AiCue.NO_RESPONSE), classify("Pass."))
        assertEquals(Spoken.Cue(AiCue.NO_RESPONSE), classify("no response"))
        assertEquals(Spoken.Cue(AiCue.YOUR_MOVE), classify("your move"))
        assertEquals(Spoken.Cue(AiCue.YOUR_MOVE), classify("go ahead"))
        assertEquals(Spoken.Cue(AiCue.PASS), classify("over to you"))
        assertEquals(Spoken.Command("summon h1 to m3"), classify("Summon hand one to monster three"))
        assertEquals(Spoken.Command("end"), classify("end my turn"))
        assertEquals(Spoken.Command("attack om1 with m1"), classify("Attack their monster one with my monster one"))
        assertEquals(Spoken.Command("catch up"), classify("catch up"))
        val hand = classify("Read my hand")
        assertIs<Spoken.Query>(hand)
        assertEquals(QueryKind.HAND, hand.kind)
        val field = classify("What's on their field?")
        assertIs<Spoken.Query>(field)
        assertEquals(DuelCommand.Parsed.Query(QueryKind.FIELD, theirs = true), field.query)
        assertEquals(Spoken.ToAi("what should i play"), classify("A.I., what should I play?"))
        assertEquals(Spoken.ToAi("nice play"), classify("Nice play!", ai = true))
        assertEquals(Spoken.Unknown("nice play"), classify("nice play"))
        // A move with a problem is still the Line's, so the preview can say why.
        assertEquals(Spoken.Command("summon h1 to m1"), classify("summon hand one to monster one"))
        // A name heard wrong is the Line's too: it asks "Did you mean".
        assertEquals(Spoken.Command("summon blossum joy"), classify("summon blossum joy"))
    }

    @Test
    fun theHintsAreTheTableYouSee() {
        val h = DuelSpeech.hints(s, 0, catalog)
        assertTrue("Blue-Eyes White Dragon" in h && "Dark Magician" in h && "monster zone" in h, h)
        // Their face-down Mirror Force is never a hint from their hidden cards — only Kai's own copy puts the name there.
        val rival = DuelSpeech.hints(s, 1, catalog)
        assertFalse("Pot of Prosperity" in rival, rival)
        assertFalse("Battle Fader" in rival, rival)
    }
}
