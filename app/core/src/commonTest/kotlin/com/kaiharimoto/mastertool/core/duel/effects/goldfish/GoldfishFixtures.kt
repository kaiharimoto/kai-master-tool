package com.kaiharimoto.mastertool.core.duel.effects.goldfish

import com.kaiharimoto.mastertool.core.duel.effects.Area
import com.kaiharimoto.mastertool.core.duel.effects.CardScript
import com.kaiharimoto.mastertool.core.duel.effects.CardType
import com.kaiharimoto.mastertool.core.duel.effects.Effect
import com.kaiharimoto.mastertool.core.duel.effects.Filter
import com.kaiharimoto.mastertool.core.duel.effects.FxEntry
import com.kaiharimoto.mastertool.core.duel.effects.FxFinding
import com.kaiharimoto.mastertool.core.duel.effects.FxLevel
import com.kaiharimoto.mastertool.core.duel.effects.FxPlayed
import com.kaiharimoto.mastertool.core.duel.effects.FxRead
import com.kaiharimoto.mastertool.core.duel.effects.FxRef
import com.kaiharimoto.mastertool.core.duel.effects.FxReport
import com.kaiharimoto.mastertool.core.duel.effects.FxTrust
import com.kaiharimoto.mastertool.core.duel.effects.Kind
import com.kaiharimoto.mastertool.core.duel.effects.NegWhat
import com.kaiharimoto.mastertool.core.duel.effects.Op
import com.kaiharimoto.mastertool.core.duel.effects.Opt
import com.kaiharimoto.mastertool.core.duel.effects.Pick
import com.kaiharimoto.mastertool.core.duel.effects.Rel
import com.kaiharimoto.mastertool.core.duel.effects.Spot
import com.kaiharimoto.mastertool.core.duel.effects.Step
import com.kaiharimoto.mastertool.core.duel.effects.Where
import com.kaiharimoto.mastertool.core.model.Card
import com.kaiharimoto.mastertool.core.model.CardId
import com.kaiharimoto.mastertool.core.model.Attribute as CardAttribute

/**
 * The goldfish's toy cards (Phase D step 4): fictional, in the reserved passcode range (900000600–900000699, apart from the
 * step-1 reference cards and the Shortcut window's samples), with our own scripts — nothing here is a real card's text. Each
 * toy deck's odds can be worked out by hand, so the goldfish is held to them hand for hand.
 */
object GoldfishFixtures {
    const val FROG = 900_000_600
    const val CALLER = 900_000_601
    const val STONE = 900_000_602
    const val ELDER = 900_000_603
    const val NET = 900_000_604
    const val WALL = 900_000_605
    const val SAGE = 900_000_606
    const val WELL = 900_000_607

    private fun monster(id: Int, name: String, frame: String, type: String, level: Int) =
        Card(CardId(id), name, type, frame, race = "Aqua", attribute = CardAttribute.WATER, atk = 1000, def = 1000, level = level)

    val cards: List<Card> = listOf(
        monster(FROG, "Pond Frog", "normal", "Normal Monster", 3),
        Card(CardId(CALLER), "Pond Caller", "Spell Card", "spell", race = "Normal"),
        monster(STONE, "Dry Stone", "effect", "Effect Monster", 4),
        monster(ELDER, "Pond Elder", "effect", "Effect Monster", 4),
        Card(CardId(NET), "Pond Net", "Spell Card", "spell", race = "Normal"),
        Card(CardId(WALL), "Pond Wall", "Trap Card", "trap", race = "Normal"),
        monster(SAGE, "Pond Sage", "effect", "Effect Monster", 4),
        Card(CardId(WELL), "Pond Well", "Spell Card", "spell", race = "Normal"),
    )

    val pond: Filter = Filter.NameHas("Pond")
    val pondMonster: Filter = Filter.All(listOf(pond, Filter.Kind(CardType.MONSTER)))
    private val handOrSpell = setOf(Where.HAND, Where.SPELL_ZONE)

    val scripts: List<CardScript> = listOf(
        // Special Summon 1 "Pond" monster from your Deck.
        CardScript(
            CALLER, name = "Pond Caller",
            effects = listOf(Effect("e1", "Call", Kind.ACTIVATION, from = handOrSpell, does = listOf(Step(Op.SpecialSummon(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), where = pondMonster)))))),
        ),
        // Add 1 "Pond" card from your Deck to your hand.
        CardScript(
            NET, name = "Pond Net",
            effects = listOf(Effect("e1", "Net", Kind.ACTIVATION, from = handOrSpell, does = listOf(Step(Op.Add(Pick(from = listOf(Spot(Rel.YOU, Area.DECK)), where = pond)))))),
        ),
        // Target 1 monster your opponent controls; destroy it. A Normal Trap: an interruption once Set.
        CardScript(
            WALL, name = "Pond Wall",
            effects = listOf(
                Effect(
                    "e1", "Wall", Kind.ACTIVATION, from = setOf(Where.SPELL_ZONE),
                    targets = listOf(Pick(from = listOf(Spot(Rel.THEM, Area.MONSTERS)), bind = "t")),
                    does = listOf(Step(Op.Destroy(Pick(ref = "t")))),
                ),
            ),
        ),
        // A Quick Effect on the field, once per turn by name: negate the activation it answers.
        CardScript(
            SAGE, name = "Pond Sage",
            effects = listOf(Effect("e1", "Hush", Kind.QUICK, from = setOf(Where.MONSTER_ZONE), opt = Opt.ByName(), does = listOf(Step(Op.Negate(NegWhat.ACTIVATION))))),
        ),
        // Draw 1: the Deck's order is read.
        CardScript(WELL, name = "Pond Well", effects = listOf(Effect("e1", "Well", Kind.ACTIVATION, from = handOrSpell, does = listOf(Step(Op.Draw(1)))))),
    )

    /** A library entry for [s], compiled and checked: UNTESTED, or WARNED with [warnings] open. */
    fun entry(s: CardScript, warnings: Int = 0): FxEntry = FxEntry(
        s.card, compiled = FxRead.Script(s),
        report = FxReport(s.card, (0 until warnings).map { FxFinding(FxLevel.WARNING, "lint-$it", "a warning", "e1") }),
    )

    /** What the goldfish trusts over [scripts] ([warned]: cards with an open warning), and the [played] marks. */
    fun trust(scripts: List<CardScript> = this.scripts, warned: Set<Int> = emptySet(), played: FxPlayed = FxPlayed()): FxTrust {
        val all = cards + FxRef.cards
        val byId = HashMap<Int, Card>()
        all.forEach { c -> c.passcodes.forEach { byId[it.value] = c } }
        return FxTrust(scripts.associate { it.card to entry(it, if (it.card in warned) 1 else 0) }, played) { code -> byId[code]?.id?.value ?: code }
    }

    fun kit(trust: FxTrust = trust()): GoldfishKit = GoldfishKit.of(trust, cards + FxRef.cards)

    /** [n] copies of each, in order. */
    fun deck(vararg parts: Pair<Int, Int>): List<Int> = parts.flatMap { (code, n) -> List(n) { code } }

    fun target(vararg conds: BoardCond, name: String = "toy"): EndBoard = EndBoard("t-$name", name, "toy", conds.toList())

    /** The binomial coefficient, exactly as a double. */
    fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        for (i in 1..k) r = r * (n - k + i) / i
        return r
    }
}
