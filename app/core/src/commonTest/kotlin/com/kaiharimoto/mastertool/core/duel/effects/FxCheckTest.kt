package com.kaiharimoto.mastertool.core.duel.effects

import com.kaiharimoto.mastertool.core.model.Card
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * The legality pass (Phase D step 2, D.md §3.4): each error of its three layers and each of the text's lints, on FxRef's
 * fictional cards. The printed texts here are our own words for fictional cards, written the way card text reads.
 */
class FxCheckTest {
    private val canon = FxRef.facts::canonical

    private fun check(s: CardScript, card: Card? = FxRef.cards.firstOrNull { it.id.value == s.card }, expected: Int? = s.card, bytes: Int? = null): FxReport =
        if (bytes == null) FxCheck.check(s, card, canon, expected) else FxCheck.check(s, card, canon, expected, bytes)

    private fun codes(r: FxReport, level: FxLevel = FxLevel.ERROR) = r.findings.filter { it.level == level }.map { it.code }.toSet()

    private fun assertError(code: String, r: FxReport) = assertTrue(code in codes(r), "expected $code in ${r.words()}")

    private val scout = FxRef.script(FxRef.SCOUT)
    private val e1 = scout.effects[0]
    private val e2 = scout.effects[1]

    @Test
    fun everyReferenceScriptChecksWithoutAnError() {
        (FxRef.book.cards).forEach { code ->
            val s = FxRef.book.script(code)!!
            val r = check(s)
            assertTrue(r.errors.isEmpty(), "${s.name}: ${r.words()}")
        }
    }

    @Test
    fun theShapeLayer() {
        assertError("vocab", check(scout.copy(vocab = FxVocab.VERSION + 1)))
        assertError("size", check(scout, bytes = FxCodec.MAX_BYTES + 1))
        assertError("effects", check(scout.copy(effects = (1..9).map { e1.copy(id = "e$it") })))
        assertError("id", check(scout.copy(effects = listOf(e1.copy(id = "first effect")))))
        assertError("unknown-word", check(scout.copy(effects = listOf(e1.copy(does = listOf(Step(Op.Unknown(JsonObject(emptyMap())))))))))
        var nested: List<Step> = listOf(Step(Op.Draw(1)))
        repeat(7) { nested = listOf(Step(Op.If(Cond.ChainEmpty, then = nested))) }
        assertError("depth", check(scout.copy(effects = listOf(e1.copy(does = nested)))))
        val tooMany = Step(Op.Add(Pick(n = 61, from = listOf(Spot(Rel.YOU, Area.DECK)))))
        assertError("pick-size", check(scout.copy(effects = listOf(e1.copy(does = listOf(tooMany))))))
        assertError("from", check(scout.copy(effects = listOf(e1.copy(from = emptySet())))))
    }

    @Test
    fun theReferencesLayer() {
        assertError("unique-ids", check(scout.copy(effects = listOf(e1, e2.copy(id = "e1")))))
        // A ref used before anything is bound to it.
        assertError("ref", check(scout.copy(effects = listOf(e2.copy(does = listOf(Step(Op.Return(Pick(ref = "u"), Dest.HAND))))))))
        assertError("ref", check(scout.copy(effects = listOf(e1.copy(does = listOf(Step(Op.Add(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), where = Filter.Same(Stat.NAME, "x"))))))))))
        // Bound by a step, read by the next: fine. Bound only in one branch of an If: not after it.
        val bindThenUse = listOf(Step(Op.Send(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), bind = "s"))), Step(Op.Add(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), where = Filter.Same(Stat.NAME, "s"))), Join.THEN))
        assertTrue("ref" !in codes(check(scout.copy(effects = listOf(e1.copy(does = bindThenUse))))))
        val oneBranch = listOf(Step(Op.If(Cond.ChainEmpty, then = listOf(Step(Op.Send(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), bind = "s")))))), Step(Op.Return(Pick(ref = "s"))))
        assertError("ref", check(scout.copy(effects = listOf(e1.copy(does = oneBranch)))))
        // Costs never target.
        assertError("cost-target", check(scout.copy(effects = listOf(e2.copy(cost = listOf(Step(Op.Banish(Pick(ref = "t")))))))))
        assertError("target-ref", check(scout.copy(effects = listOf(e2.copy(targets = listOf(Pick(ref = "x", bind = "t")))))))
        // A trigger has an event; nothing else does.
        assertError("trigger", check(scout.copy(effects = listOf(e1.copy(trigger = null)))))
        assertError("trigger", check(scout.copy(effects = listOf(e2.copy(trigger = e1.trigger)))))
        assertError("respond", check(scout.copy(effects = listOf(e1.copy(kind = Kind.IGNITION, trigger = null, respond = Respond())))))
        // Kinds from places that fit them.
        assertError("place", check(scout.copy(effects = listOf(e1.copy(kind = Kind.IGNITION, trigger = null, from = setOf(Where.HAND))))))
        assertError("continuous", check(scout.copy(effects = listOf(e1.copy(kind = Kind.CONTINUOUS, trigger = null)))))
        assertError("opt-group", check(scout.copy(effects = listOf(e1.copy(opt = Opt.ByName(group = " "))))))
    }

    @Test
    fun theCardLayer() {
        assertError("card", check(scout, expected = FxRef.LAMP))
        // An alternate artwork's passcode is not the card's: every printing reads the card's one script.
        assertError("canonical", check(scout.copy(card = FxRef.SCOUT_ALT), card = FxRef.card(FxRef.SCOUT_ALT), expected = FxRef.SCOUT_ALT))
        assertTrue("pool" in codes(check(scout.copy(card = 900_000_998), card = null), FxLevel.WARNING))
        assertError("normal", check(CardScript(FxRef.PAWN, name = "Example Pawn", effects = listOf(e1))))
        assertError("kind", check(scout.copy(effects = listOf(e1.copy(kind = Kind.ACTIVATION, trigger = null)))))
        val call = FxRef.script(FxRef.CALL)
        assertError("place", check(call.copy(effects = listOf(call.effects[0].copy(from = setOf(Where.MONSTER_ZONE))))))
        val snare = FxRef.script(FxRef.SNARE)
        assertError("place", check(snare.copy(effects = listOf(snare.effects[0].copy(from = setOf(Where.HAND))))))
        val grounds = FxRef.script(FxRef.GROUNDS)
        assertError("place", check(grounds.copy(effects = listOf(grounds.effects[0].copy(from = setOf(Where.HAND, Where.SPELL_ZONE))))))
        assertError("summon", check(call.copy(summon = SummonRule(normal = false))))
        // An Extra Deck monster has its frame's procedure, with numbers that agree with its facts.
        assertError("proc", check(CardScript(FxRef.BRIDGE, name = "Example Bridge")))
        assertError("proc", check(CardScript(FxRef.BRIDGE, name = "Example Bridge", summon = SummonRule(normal = false, procs = listOf(Proc.Link(2, 3))))))
        assertError("proc", check(CardScript(FxRef.REGENT, name = "Example Regent")))
        assertError("proc", check(CardScript(FxRef.PALADIN, name = "Example Paladin")))
        assertError("proc", check(CardScript(FxRef.CHIMERA, name = "Example Chimera")))
        assertError("proc", check(scout.copy(summon = SummonRule(procs = listOf(Proc.Link(1, 1))))))
        assertTrue("proc" in codes(check(CardScript(FxRef.ORACLE, name = "Example Oracle")), FxLevel.WARNING))
    }

    // ---- The text's lints ------------------------------------------------------------------------------------------------

    /** Example Scout's printed text, in our own words for a fictional card. */
    private val scoutText = "If this card is Normal or Special Summoned: You can add 1 Level 4 or lower \"Example\" monster from your Deck to your hand. " +
        "During your opponent's turn (Quick Effect): You can banish this card from your GY, then target 1 face-up monster your opponent controls; " +
        "return it to the hand. You can only use each effect of \"Example Scout\" once per turn."
    private val scoutCard = FxRef.card(FxRef.SCOUT).copy(description = scoutText)

    private fun lints(s: CardScript, card: Card = scoutCard): Set<String> = FxLints.lint(s, card) { code -> FxRef.cards.firstOrNull { it.id.value == code }?.name }.map { it.code }.toSet()

    @Test
    fun aScriptThatSaysWhatItsTextSaysHasNoLints() {
        assertEquals(emptySet(), lints(scout.copy(text = FxCodec.textOf(scoutText))))
        // The full pass runs the lints too.
        assertTrue(FxCheck.full(scout, scoutCard, canon, FxRef.SCOUT).findings.isEmpty())
    }

    @Test
    fun eachLintCatchesItsMarker() {
        // Errata: the text it was written from is not the pool's now.
        assertTrue(FxLints.TEXT_CHANGED in lints(scout.copy(text = FxCodec.textOf("an older wording"))))
        // "Only use each effect of … once per turn": every effect by name.
        assertTrue("lint-opt-each" in lints(scout.copy(effects = listOf(e1, e2.copy(opt = null)))))
        assertTrue("lint-opt-name" in lints(scout.copy(effects = listOf(e1.copy(opt = null), e2.copy(opt = null))), scoutCard.copy(description = scoutText.replace("each effect of", "this effect of"))))
        assertTrue("lint-opt-shared" in lints(scout, scoutCard.copy(description = scoutText.replace("You can only use each effect of \"Example Scout\" once per turn.", "You can only use 1 \"Example Scout\" effect per turn."))))
        assertTrue("lint-opt-card" in lints(FxRef.script(FxRef.CALL).let { it.copy(effects = listOf(it.effects[0].copy(opt = null))) },
            FxRef.card(FxRef.CALL).copy(description = "Special Summon 1 Level 4 or lower \"Example\" monster from your Deck. You can only activate 1 \"Example Call\" per turn.")))
        assertTrue("lint-opt-copy" in lints(scout, scoutCard.copy(description = scoutText + " Once per turn: You can draw 1 card.")))
        // "(Quick Effect)".
        assertTrue("lint-quick" in lints(scout.copy(effects = listOf(e1, e2.copy(kind = Kind.IGNITION)))))
        // "If …:" and "When …:".
        assertTrue("lint-timing-when" in lints(scout.copy(effects = listOf(e1.copy(trigger = e1.trigger!!.copy(timing = Timing.WHEN)), e2))))
        assertTrue("lint-timing-if" in lints(scout, scoutCard.copy(description = scoutText.replace("If this card is", "When this card is"))))
        // "You can".
        assertTrue("lint-optional" in lints(scout, scoutCard.copy(description = scoutText.replace("You can ", "").replace("You can only", "Only"))))
        // A cost between ":" and ";".
        assertTrue("lint-cost" in lints(scout, scoutCard.copy(description = scoutText.replace("; return", ", and return"))))
        assertTrue("lint-cost" in lints(scout.copy(effects = listOf(e1, e2.copy(cost = emptyList(), targets = emptyList(), does = listOf(Step(Op.Draw(1))))))))
        // "Target".
        assertTrue("lint-target" in lints(scout.copy(effects = listOf(e1, e2.copy(targets = emptyList(), does = listOf(Step(Op.Draw(1))))))))
        // Summoning rules.
        assertTrue("lint-summon-normal" in lints(scout, scoutCard.copy(description = "Cannot be Normal Summoned/Set. $scoutText")))
        assertTrue("lint-summon-first" in lints(scout, scoutCard.copy(description = "Must first be Special Summoned with its own effect. $scoutText")))
        // Quoted names the script never reads.
        assertTrue("lint-names" in lints(scout, scoutCard.copy(description = scoutText.replace("\"Example\" monster", "\"Gatekeeper\" monster"))))
        // "Level N or lower", Attributes and Types in a search.
        val e1Plain = e1.copy(does = listOf(Step(Op.Add(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), where = Filter.All(listOf(Filter.NameHas("Example"), Filter.Kind(CardType.MONSTER))))))))
        assertTrue("lint-level" in lints(scout.copy(effects = listOf(e1Plain, e2))))
        assertTrue("lint-attribute" in lints(scout, scoutCard.copy(description = scoutText.replace("Level 4 or lower \"Example\" monster", "Level 4 or lower LIGHT \"Example\" monster"))))
        assertTrue("lint-race" in lints(scout, scoutCard.copy(description = scoutText.replace("\"Example\" monster from", "\"Example\" Warrior monster from"))))
        // The number of activated effects.
        assertTrue("lint-count" in lints(scout.copy(effects = listOf(e1))))
        // Every lint is a warning: the person may accept it.
        assertTrue(FxLints.lint(scout.copy(effects = listOf(e1)), scoutCard).all { it.level == FxLevel.WARNING })
    }

    @Test
    fun aFindingIsAcceptedByItsOwnKeyAlone() {
        val r = FxCheck.full(scout.copy(effects = listOf(e1, e2.copy(opt = null))), scoutCard, canon, FxRef.SCOUT)
        val w = r.warnings.single { it.code == "lint-opt-each" }
        assertEquals("lint-opt-each@e2", w.key)
        assertTrue(r.open(setOf(w.key)).none { it.code == "lint-opt-each" })
        assertTrue(r.open(setOf("lint-opt-each@e1")).any { it.code == "lint-opt-each" })
        assertTrue("Accepted · e2" in r.words(setOf(w.key)), r.words(setOf(w.key)))
    }

    @Test
    fun aCardThePoolDoesNotKnowIsCheckedForWhatItSays() {
        val r = check(CardScript(900_000_997, name = "Unknown", effects = listOf(e1)), card = null)
        assertTrue(r.errors.isEmpty(), r.words())
        assertTrue(CardAttribute.LIGHT.name.isNotEmpty())
    }
}
