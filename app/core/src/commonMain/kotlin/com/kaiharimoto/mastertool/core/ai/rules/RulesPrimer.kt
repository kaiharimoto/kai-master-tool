package com.kaiharimoto.mastertool.core.ai.rules

/**
 * The rules of the TCG in brief, always in Ai's system prompt, so every answer about
 * a deck, a line or a siding plan stands on the real game. Written in our own words
 * (Konami's terms forbid reproducing the rulebook), checked against the rulebook,
 * the Master Rule revisions of April 2020 and the 2021 rules update, the
 * Problem-Solving Card Text articles and Yugipedia. Card-specific rulings are not
 * here: they come from the `rulings` tool. The edge cases are the `game-rules` skill
 * ([GameRulesSkill]).
 */
object RulesPrimer {
    const val OFFICIAL = "https://www.yugioh-card.com/en/rulebook/"

    const val TEXT: String = """# Yu-Gi-Oh! TCG rules primer

## Decks
- Main Deck 40–60 cards, Extra Deck 0–15, Side Deck 0–15; at most 3 copies of a name across all three.
- Forbidden 0, Limited 1, Semi-Limited 2. TCG and OCG lists differ.
- Genesys, a separate TCG format: no banlist; strong cards cost points and all three decks together fit the event's cap (usually 100); no Link or Pendulum monsters.

## Winning
- Opponent's LP from 8000 to 0 (both at once: a draw). Having to draw from an empty Deck loses. A few cards win outright (Exodia).

## The turn
- Draw, Standby, Main 1, Battle (Start, Battle, Damage and End Steps), Main 2, End. The player going first skips the first draw and cannot attack that turn.

## The field (Master Rule, April 2020 revision)
- Each player: 5 Main Monster Zones, 5 Spell & Trap Zones (the outer two are the Pendulum Zones), a Field Zone. 2 shared Extra Monster Zones: each player may occupy only one.
- Fusion, Synchro and Xyz monsters from the Extra Deck may go to any Main Monster Zone. Link monsters, and face-up Pendulums from the Extra Deck, need an Extra Monster Zone or a Main Monster Zone a Link Arrow points to (either player's).

## Summoning
- 1 Normal Summon or Set per turn. Level 5–6 needs 1 Tribute, Level 7+ needs 2. Flip Summon: not the turn the monster was Set.
- Inherent Special Summons (Synchro, Xyz, Link, Pendulum, a card's own procedure) do not use the chain; Summons by an effect (Fusion and Ritual Spells, revival) do.
- Extra Deck monsters and ones that cannot be Normal Summoned must be properly Summoned before they can be revived.
- Synchro: 1 Tuner + non-Tuners, face-up, Levels adding up exactly. Xyz: monsters of the Rank's Level become overlay units (not on the field); detaching sends one to the GY. Rank is not Level. Link: materials equal to the Link Rating, a Link material counting as 1 or its rating; Link monsters have no DEF and stay in Attack Position.
- Pendulum Summon: once per turn with both Pendulum Zones filled, any number of monsters with Levels strictly between the Scales, from the hand and face-up from the Extra Deck. A Pendulum monster that would go from the field to the GY goes face-up to the Extra Deck instead.
- Summon negation hits only Summons that do not use the chain; an effect's Summon is stopped by negating its activation or effect. A negated Summon never happened: not properly Summoned, not counted by Summon limits.

## Spell Speed
- 1: Ignition and Trigger effects, every Spell but Quick-Play. Only ever Chain Link 1.
- 2: Quick Effects, Normal and Continuous Traps, Quick-Play Spells. 3: Counter Traps; only Spell Speed 3 answers them.
- Traps are Set first; a Trap or Set Quick-Play cannot be activated the turn it was Set unless a card says so. A Quick-Play from the hand: your own turn only.

## Chains
- A response needs Spell Speed at least equal to the last link's (and at least 2). The turn player has priority first.
- Links resolve last to first, each doing as much as it can. Triggers from a chain's resolution wait until it ends, then form a new chain (SEGOC): turn player's mandatory, opponent's mandatory, turn player's optional, opponent's optional. Nothing is added mid-chain.
- Continuous effects are never activated and cannot be chained to.

## Reading card text (PSCT)
- Before a colon: the condition or timing. Before a semicolon: costs (paid on activation, kept even if negated) and targets. After: what happens on resolution.
- It targets only if it says "target".
- "When … you can": misses the timing unless its event was the last thing to happen. "If … you can" and mandatory triggers never miss.
- "A, and if you do, B": together, B needs A. "A, then B": B after A, only if A happened. "A, also B": together, independent. "A and B": both or neither.

## Once per turn
- "Once per turn": per copy, used even if negated; it resets if the card leaves the field or turns face-down.
- "You can only use this effect of X once per turn": all copies of the name; a negated activation still counts. The "activate" wordings ("You can only activate 1 X per turn") may be tried again when the activation was negated.
- Locks such as "you cannot Special Summon, except …" last the rest of the turn; "the turn you activate this" also looks back at what you already did.

## Negation and protection
- Negating the activation: it never happened (the cost stays paid). Negating the effect: activated, but does nothing.
- Negating a face-up monster's effects (Effect Veiler, Infinite Impermanence) also stops its effect on the chain while it stays face-up. Called by the Grave banishes a GY monster and negates that name's effects. Ash Blossom answers an effect that adds from the Deck to the hand, Special Summons from the Deck, or sends from the Deck to the GY.
- "Cannot be targeted" stops no non-targeting effect; "cannot be destroyed" does not stop banish, bounce, send or Tribute; "unaffected" does not stop costs, battle or a Summon procedure that Tributes (Kaiju).

## Removal
- Destroy, banish (face-up or face-down), send to the GY, return to hand or Deck (Extra Deck monsters go to the Extra Deck), Tribute: each triggers "leaves the field"; taking control does not.

## Damage Step
- Only Counter Traps and negations of activation, effects that directly change ATK/DEF (until damage calculation), effects written for the Damage Step, and mandatory triggers. Other optional triggers wait unless they belong there (destroyed by battle). A FLIP effect set off by an attack waits until after damage calculation.

## Play
- Going first: an end board of negates. Going second: hand traps in their combo, then board breakers (Evenly Matched, Harpie's Feather Duster).
- A card leaving the field goes to its owner's hand, Deck or GY, whoever controlled it.
- Public: LP, card counts, GYs, face-up cards. Private: hands, face-down cards, Deck order.

Rulings for specific cards: check them with the `rulings` tool, never from memory. The official rulebook: ${OFFICIAL}
"""
}
