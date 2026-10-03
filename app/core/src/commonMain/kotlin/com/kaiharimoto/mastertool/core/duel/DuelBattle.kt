package com.kaiharimoto.mastertool.core.duel

import com.kaiharimoto.mastertool.core.board.DuelPhase
import com.kaiharimoto.mastertool.core.duel.text.DuelWords

/**
 * What an attack would come to by the printed numbers (1.0.86): [damage] to [damaged] (null: no one), and
 * the monsters battle would destroy, the attacker first. A suggestion for the players — the table never
 * enforces card text, so piercing, "cannot be destroyed by battle" and every changed ATK are theirs.
 */
data class BattleOutcome(
    val attack: Attack,
    val damaged: Int?,
    val damage: Int,
    val destroyed: List<Int>,
) {
    /** Battle would change nothing: no damage, nothing destroyed. */
    val nothing: Boolean get() = (damaged == null || damage <= 0) && destroyed.isEmpty()
}

/**
 * The battle chip's arithmetic (1.0.86): after an attack is declared, what battle damage and destruction
 * the printed ATK and DEF say, as one button the players may press or ignore. Unknown numbers — a
 * face-down monster attacked, a card the catalog cannot read, a token made without stats — give no
 * suggestion at all rather than a wrong one.
 *
 * - ATK against ATK: the difference to the weaker monster's controller, and the weaker destroyed; a tie
 *   destroys both and deals no damage (both at 0: neither is destroyed).
 * - ATK against DEF: the defender destroyed when the ATK is higher, no damage (piercing is not assumed);
 *   when the DEF is higher, the difference to the attacker's controller and nothing destroyed.
 * - Direct: the ATK to the other player.
 */
object DuelBattle {

    /** A card's ATK as the table knows it: a token's own number, else the printed one. */
    fun atk(card: CardInst, catalog: DuelCatalog): Int? = card.atk ?: catalog.info(card.code)?.atk

    fun def(card: CardInst, catalog: DuelCatalog): Int? = card.def ?: catalog.info(card.code)?.def

    fun outcome(s: DuelState, attack: Attack, catalog: DuelCatalog): BattleOutcome? {
        val a = s.cards[attack.attacker] ?: return null
        if (!onField(s, attack.attacker) || !a.faceUp || a.defense) return null
        val power = atk(a, catalog) ?: return null
        val tid = attack.target ?: return BattleOutcome(attack, 1 - attack.seat, power, emptyList())
        val t = s.cards[tid] ?: return null
        if (!onField(s, tid) || !t.faceUp) return null
        return if (!t.defense) {
            val other = atk(t, catalog) ?: return null
            when {
                power > other -> BattleOutcome(attack, t.controller, power - other, listOf(tid))
                power < other -> BattleOutcome(attack, a.controller, other - power, listOf(attack.attacker))
                power == 0 -> BattleOutcome(attack, null, 0, emptyList())
                else -> BattleOutcome(attack, null, 0, listOf(attack.attacker, tid))
            }
        } else {
            val guard = def(t, catalog) ?: return null
            when {
                power > guard -> BattleOutcome(attack, null, 0, listOf(tid))
                power < guard -> BattleOutcome(attack, a.controller, guard - power, emptyList())
                else -> BattleOutcome(attack, null, 0, emptyList())
            }
        }
    }

    /**
     * The suggestion standing now: the newest attack, when the last move that changed the table was its
     * declaration and the Battle Phase lasts, and battle would change something. Any other move — the
     * damage applied, a card activated in answer — puts it away.
     */
    fun pending(game: DuelGame, catalog: DuelCatalog): BattleOutcome? {
        val s = game.state
        if (s.phase != DuelPhase.BATTLE) return null
        val last = game.played.lastOrNull { !it.action.social }?.action as? DuelAction.Attack ?: return null
        val attack = s.attacks.lastOrNull()?.takeIf { it.attacker == last.attacker && it.target == last.target } ?: return null
        return outcome(s, attack, catalog)?.takeUnless { it.nothing }
    }

    /** The outcome as moves, one group: the life points, then each monster destroyed to its owner's GY. */
    fun actions(s: DuelState, o: BattleOutcome): List<DuelAction> = buildList {
        if (o.damaged != null && o.damage > 0) add(DuelAction.Lp(o.damaged, delta = -o.damage))
        o.destroyed.forEach { uid ->
            val card = s.cards[uid] ?: return@forEach
            add(DuelAction.Move(uid, Place.Pile(card.owner, PileKind.GY), how = HOW))
        }
    }

    /** The chip's words: "Apply 1800 to Kai", "Destroy Arias" — each a line. */
    fun words(s: DuelState, o: BattleOutcome, catalog: DuelCatalog): List<String> = buildList {
        if (o.damaged != null && o.damage > 0) add("Apply ${o.damage} to ${DuelWords.seatName(s, o.damaged)}")
        o.destroyed.forEach { uid -> s.cards[uid]?.let { add("Destroy ${catalog.nameOf(it)}") } }
    }

    /** A Move's `how` for a monster destroyed by battle, so the log says so. */
    const val HOW = "battle"

    private fun onField(s: DuelState, uid: Int): Boolean =
        s.placeOf(uid).let { it is Place.Zone && (it.kind == ZoneKind.MONSTER || it.kind == ZoneKind.EMZ) }
}
