package com.kaiharimoto.mastertool.core.ai.rules

/**
 * The `game-rules` skill: the edge cases the always-present [RulesPrimer] has no room
 * for — the Damage Step step by step, missing the timing and SEGOC worked through,
 * material subtleties, Tokens, battle, replays — and what KDE-US Tournament Policy
 * v2.5 means at the table. Read with `skill_view` when a question turns on one of
 * them. Our own words throughout, checked against the rulebook, Yugipedia and the
 * policy document; card-specific rulings stay with the `rulings` tool.
 */
object GameRulesSkill {
    const val NAME = "game-rules"

    const val DESCRIPTION =
        "Rules edge cases (Damage Step, missing the timing, SEGOC, materials, Pendulum, Tokens, battle, replays) and tournament policy at the table."

    const val BODY: String = """# Game rules: the edge cases

For questions on timing, battle, materials or tournament procedure. The primer in your prompt has the basics; this is the detail.

**Never invent a ruling.** When the answer depends on one card's text or an official ruling, call `rulings` for that card and say what it returned. If it returns nothing clear, say plainly "I'm not sure; this is how the general rule reads" and name the rule. A confident wrong ruling costs the person a game at the table.

**Which game a ruling is from.** `rulings` gives Konami's official OCG FAQ and Q&A first (translated by YGOrganization), then Yugipedia's TCG and OCG sections. Cite each as OCG or TCG. They usually agree; where a TCG ruling differs, it stands for TCG play, and any entry marked with a TCG caveat must be said as such. With two cards in the question, pass the second as "with" to read the Q&As about both.

## The Damage Step, step by step
The Damage Step begins after an attack is not stopped in the Battle Step. Its five parts:
1. **Start of the Damage Step.** Effects that say "at the start of the Damage Step" (for example, banishing the monster it battles) and Spells, Traps and Quick Effects that directly change ATK or DEF.
2. **Before damage calculation.** A face-down Defense Position monster that was attacked is turned face-up now. Its continuous effects apply at once, but a FLIP effect does not activate yet. Effects that say "before damage calculation", and ATK/DEF changers, may still be activated.
3. **During damage calculation.** Only effects that say "during damage calculation" (and those that apply only then). ATK/DEF changers can no longer be activated. Battle damage is dealt and the loser of the battle is marked as destroyed by battle.
4. **After damage calculation.** Effects that trigger on battle damage, on a monster being flipped by the attack (FLIP effects activate here, even if the monster was destroyed), and effects that say "after damage calculation". A monster destroyed by battle is still on the field but cannot be targeted, moved, used as a cost or have its stats changed.
5. **End of the Damage Step.** Monsters destroyed by battle go to the GY (a Pendulum to the face-up Extra Deck), and "destroyed by battle and sent to the GY" effects trigger.

Throughout the Damage Step only these may be activated: Counter Traps; Quick Effects that negate an activation; cards that directly change ATK/DEF (until damage calculation begins); effects that name a Damage Step timing; and mandatory triggers. An optional trigger whose condition happens in the Damage Step (for example "If this card is Special Summoned", when it is Summoned mid-battle) cannot be activated unless its text allows it. There are no replays in the Damage Step.

## Missing the timing, worked through
Only optional triggers written "When … you can" can miss the timing. They need their event to be the **last thing that happened** when the chance to activate comes.
- **A cost on the chain.** A monster with "When this card is sent to the GY: You can …" is discarded as the cost of Chain Link 1. Chain Link 1 then resolves, and the last thing to happen was its resolution, not the sending. The effect misses. With "If this card is sent to the GY: You can …" it activates in a new chain once the first one ends.
- **Mid-chain.** A monster is destroyed by Chain Link 2; Chain Link 1 then resolves. "When … destroyed: you can" misses; "If … destroyed" waits and activates.
- **Two actions in one effect.** "Destroy that target, then draw 1 card": the draw happened after the destruction, so a "When … destroyed: you can" on the destroyed card misses. With "and if you do" the two actions are simultaneous, so a trigger on the first is not missed because of the second.
- **Last thing, not only thing.** When the event is Chain Link 1's resolution and nothing resolves after it, the "When" trigger can activate.
Mandatory triggers ("When … : do X" with no "you can") never miss the timing.

## SEGOC, worked through
When several triggers become ready at once (simultaneously, or during one chain's resolution), a new chain is built in this order: the turn player's mandatory, the opponent's mandatory, the turn player's optional, the opponent's optional. Each player orders their own triggers inside each group.
Example: on your turn you resolve an effect that Special Summons a monster and sends a card from your Deck to the GY. Your Summoned monster has an optional "If Summoned" effect, the sent card an optional "If sent to the GY" effect, and your opponent controls a card with a mandatory effect that triggers on your Summon. Chain Link 1 is your opponent's mandatory effect (you have no mandatory ones); Chain Links 2 and 3 are your two optional effects, in the order you choose; then either player may respond with Quick Effects. The chain resolves from its highest link down, so your effects resolve before the opponent's mandatory one.
Triggers that happen during a chain's resolution wait until it ends, and are never added to the chain that caused them.

## Costs
- Everything before the semicolon is paid on activation. You cannot activate an effect whose full cost you cannot pay, and a negated effect keeps its cost paid.
- Several things paid together as one cost (two cards discarded, a Tribute and LP) happen at the same time; anything they trigger waits for the chain to end and goes into SEGOC.
- Detaching an Xyz material is almost always a cost, so the material is gone even if the effect is negated.

## Xyz materials
- Overlay units are not on the field: they are not monsters you control, their effects do not apply, and "leaves the field" does not trigger when one is detached. A detached material goes to the GY, which does count as being sent to the GY.
- Materials must be face-up monsters with the right Level. A Rank is not a Level, so Xyz monsters cannot be material for another Xyz Summon unless a card allows it (Rank-Up-Magic, or an Xyz monster "you can also Xyz Summon … by using …"). In that case the old monster's materials move under the new one.
- Tokens cannot be Xyz material. Monsters with no Level (Link, Xyz) cannot either, unless a card says so.
- When an Xyz monster leaves the field, its materials go to the GY at the same time.

## Link materials
- Materials are face-up monsters on the field that match the listed requirement. A Link monster used as material counts as 1 or as its Link Rating, the player's choice. Tokens can be Link material unless their text says otherwise.
- Link monsters have no DEF, so they cannot be Set, change position, or be flipped face-down; effects that would do so fail on them.

## Synchro and Fusion notes
- A Fusion Summon uses the materials and method a Fusion card or effect names. A monster that says "Must be Special Summoned from your Extra Deck by sending …" without a Fusion card (a contact fusion) is not Fusion Summoned unless its text says so.

## Pendulum Summons
- Once per turn in the Main Phase, with a card in each Pendulum Zone: Summon any number of Pendulum or other monsters with Levels strictly between the two Scales, from the hand and/or face-up from the Extra Deck, as one Summon. It does not use the chain, so it can be met with Summon negation.
- Monsters from the Extra Deck go only to an Extra Monster Zone or linked Main Monster Zones; those from the hand go to any Main Monster Zone.
- Placing a Pendulum monster in a Pendulum Zone is activating a Spell Card, which can be responded to. Its Pendulum Effect is a Spell effect.
- A Pendulum monster that would go from the field (monster zone or Pendulum Zone) to the GY is placed face-up in the Extra Deck instead; destroyed, Tributed, or used as Fusion, Synchro or Link material all count. Banished, returned to hand or detached as Xyz material, it goes there as usual.

## "Cannot" beats "can"
When one effect lets you do something and another says you cannot, the prohibition wins. A card that "cannot be Special Summoned" stays in the GY against any revival; "neither player can add cards from the Deck" stops every search. When an effect cannot be carried out, do as much of it as the rest of the text allows.

## Continuous and lingering effects
- A continuous effect (on a face-up card, not activated) applies the moment the card is face-up and stops when it leaves, is turned face-down or is negated. It does not start a chain and cannot be responded to. A Continuous Spell or Trap is activated like any card, and its continuous effect starts once the activation resolves.
- A lingering effect is what an activated effect leaves behind for a time ("until the end of this turn", "for the rest of this turn"). It stays even if its source leaves the field; negating the source later does not end it. Summon locks are lingering.
- A lock written "the turn you activate this" also forbids activating the card if you already did the forbidden thing earlier in the turn; "for the rest of this turn" only reaches forward.

## Face-down cards and FLIP effects
- A monster Set face-down was not Summoned: "If Normal Summoned" does not trigger on a Set, and a Flip Summon is not a Normal Summon. Face-down monsters have no effects that apply, and cannot be Synchro, Xyz or Link material.
- A monster cannot be Flip Summoned, or changed by hand from face-down Defense to face-up Attack, the turn it was Set. Each monster may change position by hand once per turn, not the turn it arrived and not after it attacked.
- A FLIP effect activates whenever the monster is turned face-up, by a Flip Summon, an attack or an effect. It is a trigger effect and starts or joins a chain like one.
- You may look at your own face-down cards at any time. A face-down Spell or Trap that is destroyed is revealed as it goes to the GY; if it is legal to activate it in response, you may.

## Tokens
- A Token is a monster Special Summoned by an effect; it counts toward "Special Summon" counts (Nibiru's five, for example). It is never in the hand, Deck or GY: when it leaves the field it simply stops existing, so "sent to the GY" never triggers for it.
- Tokens cannot be Xyz material; they can be Tributed, used as Synchro, Link or Fusion material from the field, and taken over, unless their text says otherwise. A Token cannot be turned face-down.

## "Special Summoned from the Extra Deck"
- A lock such as "you cannot Special Summon from the Extra Deck, except …" covers every monster that comes out of the Extra Deck: Fusion, Synchro, Xyz, Link, and face-up Pendulums, Summoned by a procedure or by an effect.
- Placement is separate from the lock: Link monsters and face-up Pendulums need an Extra Monster Zone or a linked Main Monster Zone; the others may use any Main Monster Zone.
- An Extra Deck monster sent straight from the Extra Deck to the GY or banishment by an effect was never properly Summoned, so an ordinary revival cannot bring it back.

## Battle
- Each monster in Attack Position may declare one attack per Battle Phase. Monsters in Defense Position cannot attack. A direct attack is allowed only when the opponent controls no monsters (unless a card lets it).
- Attack against Attack Position: the lower ATK is destroyed and its controller takes the difference. Equal ATK: both are destroyed, no damage (two monsters with 0 ATK are not destroyed).
- Attack against Defense Position: ATK above DEF destroys the defender and deals no damage; ATK below DEF destroys nothing and the attacker's controller takes the difference; equal, nothing happens.
- **Piercing**: a card that says so makes the defender's controller take the difference when ATK beats DEF.
- **Replays**: if the monsters the opponent controls change after an attack is declared (before the Damage Step), or the target can no longer be attacked, the attacker may pick a new target, attack directly if the field is now empty, or cancel. A canceled attack still counts as declared, so that monster cannot attack again that turn. A direct attack becomes a replay if a monster appears.

## Tournament play (KDE-US Tournament Policy v2.5)
- **Matches are best of three.** The first player to win two duels wins. A drawn duel does not count, so a fourth duel is possible when time allows.
- **Going first**: before Duel 1 a random method (a die, rock-paper-scissors) picks a player, who chooses who goes first before any cards are drawn. For Duels 2 and 3, the loser of the previous duel chooses.
- **Time**: Swiss rounds are 50 minutes. When time is called the match ends at once: no extra turns, no actions. A match without a player at two wins is a **double loss**, a loss for both. Only the event's final match is untimed, unless the event's FAQ says otherwise. Play at pace; a lost duel is often better conceded early.
- **Siding**: only between duels, never before Duel 1. Swap cards 1-for-1 between the Side Deck and the Main Deck or the Side Deck and the Extra Deck; never move a card between Main and Extra. Count the Side Deck out in view of the opponent to show it is still the size it was. Take under 3 minutes. The deck stays legal because the swaps are 1-for-1 from the registered list.
- **No outside notes**, during a match or between its duels. A siding sheet cannot be looked at, so siding plans must be memorized; that is what `drill` is for. Allowed notes are written ones for LP, mandatory effects, turn counts and the game state, and they are public.
- **After each round**, take out every Side Deck card and restore the deck to its registered list. A copy of your decklist may be checked between matches, not between duels.
- **Results**: the winner reports within 5 minutes of the round's end; a late report is a game loss next round and the match is recorded as a double loss.
- **Decklists**: most Tier 1 events (locals) need none unless announced; Tier 2 and up (Regional Qualifiers, YCS, WCQs) require one. How it is submitted depends on the event: a printed official form or NEURON list for most Regional Qualifiers, online registration for events like YCS, with a deadline after which the list is final. Check the event's FAQ, and record the deadline with the event.

## When you are not sure
Say which part is the general rule and which part is a card's own ruling. Call `rulings` for the card; if the result is thin, say so and suggest the person ask the head judge at the event. Never present a guess as a ruling.
"""
}
