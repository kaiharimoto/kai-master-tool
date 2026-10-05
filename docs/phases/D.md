# Phase D: Effects as code (1.2.x)

**Goal:** the app can play out a line with the deck's own cards.

**Done when:**
- a deck's main engine is verified: every effect its recorded lines use passes its tests, and coverage says so;
- the goldfish simulator reports "this line gets there N % of the time" with its seed, and the same seed gives the same
  N on the desk and on a phone;
- every number it gives carries its proof into the guide, and goes stale when a card's effect changes, as it already
  does when the deck changes.

**What it builds on.**
- **The duel is a log.** `DuelGame` folds `DuelAction`s, with randomness stamped on commit (`DuelRandom.forRoll`), and
  each move carries who made it (`DuelEntry.by`, `Provenance`).
- **The table is physics only.** `DuelRules` refuses what the table cannot hold and never reads card text.
- **One list of verbs.** `DuelVerbs` turns a gesture into ordinary actions, and `DuelVerbs.resolve` sends a chain's
  Normal Spells and Traps to the GY.
- **What a player sees is redacted per seat:** `DuelView`, `DuelSight` and `Secrets`.
- **Lines are recorded.** A combo is steps that name cards (`Combo`, `ComboRunner.plan`, `ComboRecorder`); replays are
  logs (`Replays`, `<data>/duel/replays/`).
- **Ai World runs Ai's code shut in:** `JsRuntime` (Rhino 1.7.15, interpreted, budgeted), one door (`WorldApi`), the
  `ygo.*` prelude, `lib/` files loaded with `ygo.use`, and instruments engineered to a standard (`Instruments`).
- **Numbers carry their proof** (`Evidence.judge`, `Ledger`).
- **A card is its canonical passcode** (`CardIdentity`).
- **One referee already stands over the table.** `PuzzleReferee` (`core/ai/eval/PuzzleTable.kt`) holds a turn's
  summon rules, and `PuzzleEffect` writes five Spells as table moves.

**What is missing:** nothing knows what a card does. A line can be recorded and played back, but never played out
from a new hand.

**The design in one paragraph.**
- A card's effects are **data** in our own small vocabulary (`CardScript`).
- Ai writes them in Ai World as `lib/effects/<passcode>.js`, a file that **builds** that data, **only for the cards the
  person asks for** (kai's decision, §3.1). The card pool is never written whole. A card with no effect is played by
  hand at the table and is unknown to the goldfish.
- A Kotlin engine in `:core` reads the data and turns each activation into **ordinary `DuelAction`s**, so the log
  stays the duel.
- The deck's own record is replayed through the engine as **tests**: the combos and replays a person made, never Ai's
  own play. An effect that passes is **verified**.
- The **goldfish** searches seeded opening hands using verified effects only. Its numbers enter the ledger with the
  seed and a fingerprint of the scripts they used.

---

## 1. The problem, and what "done" means

The roadmap's three tests, made exact:

| Roadmap | Exactly |
|---|---|
| "A deck's main engine is verified" | The deck's **engine** is every card named in its combos (needs and steps), plus any card the person pins. For each engine card, every effect is `VERIFIED` (§4.4). `FxCoverage` reports this as "the engine 11 of 11". |
| "This line gets there N % of the time, with its seed" | `GoldfishResult` (§5.6) names the deck, target, seat order, number of hands, seed, budget, the library fingerprint and the engine version. Run again with the same inputs, it gives the same counts on every device and with any number of threads. |
| "Every number carries its proof into the guide" | A goldfish number in the guide comes through the `goldfish` instrument (`world_tool`), so `Evidence.judge` traces it. Its `Proof` also carries the library fingerprint. `Ledger.staleAgainst` marks the number stale when a script it used changes or an effect loses its verification. |

---

## 2. The effect vocabulary (`core/duel/effects`)

### 2.1 Where it sits

**Decision: the engine is a referee above the table, and everything it does is ordinary `DuelAction`s.**

An activation becomes the same actions a hand makes: moves (with `how` words such as "search", "cost" or "destroy"),
chain links, negations, counters, tokens, life points and locks. They are committed through `DuelGame.act`, so
provenance and chance are stamped as they always are, and `DuelRules` checks them as physics.

**Why:**
- **The log stays the duel** (foundation rule 2). Replays, undo, `DuelView`'s redaction, `DuelBrief`, `ComboRecorder`
  and `DuelHost.lines` all read engine-made moves without knowing an engine made them. Every guarantee from Phase C
  holds unchanged.
- **Two layers, two jobs.** The engine refuses what card text and the game's rules forbid; `DuelRules` refuses what
  the table cannot hold. This is how `PuzzleReferee` already works, and it is why `DuelRules` stays physics only.
- **A verified line can be shown.** The goldfish's line for any hand opens as a replay on the Duel page, every move a
  real one.
- **The manual table stays manual.** A drag, a right-click, Default and the default key do what they did. The engine
  acts at the table only when the person picks **Shortcut** (§5½), and what it does there is ordinary moves too.

**Rejected:**
- *A second state machine with its own state.* Replay, redaction, sync and provenance would all have to be rebuilt
  for it.
- *Effects inside `DuelRules`.* Every manual move would then be judged by card text. Kai wanted a manual table on
  purpose, as on DuelingBook.

**What the table does not hold, the engine folds beside it.** `FxState` holds:
- the Normal Summons used this turn;
- once-per-turn uses;
- this turn's summons, sends and sets;
- restrictions in force;
- for each chain link, its effect, targets and bindings (kept in step with `DuelState.chain`);
- triggers waiting to go on a chain;
- Level changes that are still in effect.

`FxState` is a fold over the same entries (`FxFold`) and is **never stored**.

Entries the engine makes carry one new optional field, **`DuelEntry.fx`**, the way `by` was added. With it, the fold
of an engine-made log is exact. A log made by hand has no tags, so its `FxState` is inferred (§4.2).

```kotlin
@Serializable
data class FxTag(
    val uid: Int,            // the card whose effect or procedure it is
    val effect: String,      // "e1"…, "proc", or "rule" (a Normal Summon, the chain's own Spells to the GY)
    val part: String,        // "cost", "activate", "resolve", "proc", "rule"
    val link: Int? = null,   // its chain link, 1-based
    val script: String = "", // the first 12 hex digits of the compiled script's hash
    val verified: Boolean = false, // the effect was VERIFIED when it was used (§4.4); the table offers unverified ones too
)
```

### 2.2 The shapes

These sketches are our own words for the game's ideas. Nothing is taken from Konami's text or from another engine.

```kotlin
@Serializable
data class CardScript(
    val card: Int,                          // canonical passcode (CardIdentity): every printing reads this one
    val vocab: Int = FxVocab.VERSION,       // a newer vocabulary is kept unread, as DuelAction.Unknown is
    val name: String,                       // as the pool has it, for reading
    val text: String = "",                  // 12 hex of the SHA-256 of the printed text it was written from
    val alsoNamed: List<String> = emptyList(), // "always treated as an X card"
    val summon: SummonRule? = null,
    val effects: List<Effect> = emptyList(),
    val unsupported: List<String> = emptyList(), // what the vocabulary cannot say, in Ai's words
    val notes: String = "",
)

@Serializable
data class Effect(
    val id: String,                         // "e1"…, stable: once-per-turn, tests and tags name it
    val label: String = "",                 // a short name for the table's list: "Search", "Bounce", "Revive"
    val kind: Kind,
    val from: Set<Where>,                   // HAND, MONSTER_ZONE, SPELL_ZONE, FIELD_ZONE, GY, BANISHED
    val trigger: Trigger? = null,           // TRIGGER only
    val respond: Respond? = null,           // QUICK: what it answers (an activation that includes a search…)
    val opt: Opt? = null,
    val condition: Cond? = null,
    val cost: List<Step> = emptyList(),
    val targets: List<Pick> = emptyList(),  // chosen as it is activated, checked again as it resolves
    val does: List<Step> = emptyList(),
    val leaves: List<Restriction> = emptyList(), // "you cannot … the turn you activate this": conditions, from the activation (§2.3½)
    val sameTurn: Boolean = false,          // a Trap or set Quick-Play that may be activated the turn it was Set
)

enum class Kind {
    IGNITION,   // spell speed 1, your Main Phase, nothing pending
    TRIGGER,    // spell speed 1, after an event (Trigger)
    QUICK,      // spell speed 2, any time its condition allows
    ACTIVATION, // a Spell's or Trap's own activation: speed from its kind (Quick-Play 2, Trap 2, Counter Trap 3, others 1)
    CONTINUOUS, // not activated; in the first cut only the restrictions it applies while it is face-up
}

@Serializable
data class Trigger(
    val on: On,                             // what happened, to this card or to cards that `about` matches
    val self: Boolean = true,
    val about: Filter? = null,
    val timing: Timing = Timing.IF,         // IF never misses the timing; an optional WHEN can
    val optional: Boolean = true,           // "you can"
)

@Serializable
data class On(
    val event: Event,
    val from: Where? = null,
    val cause: Cause? = null,               // COST, EFFECT, MATERIAL, BATTLE (the last is out of the first cut)
    val summon: ProcKind? = null,           // for MATERIAL and SUMMONED: which kind
)

enum class Event { SUMMONED, NORMAL_SUMMONED, SPECIAL_SUMMONED, FLIPPED, SENT_TO_GY, DESTROYED, BANISHED,
    ADDED_TO_HAND, DISCARDED, DETACHED, MATERIAL, LEFT_FIELD, DRAWN, STANDBY, END_PHASE, ACTIVATED }

@Serializable
sealed interface Opt {
    /** "Only once per turn by name": every copy and every printing; [group] shares one use between effects. */
    /** [activate]: "you can only *activate*" wording (as the group "card" always is); "use" wording when false. */
    @Serializable @SerialName("name") data class ByName(val times: Int = 1, val group: String? = null, val activate: Boolean = false) : Opt
    /** "Once per turn": this copy, while it stays where it is (a new instance once it leaves and comes back). */
    @Serializable @SerialName("copy") data object PerCopy : Opt
    /** "Once per Duel", by name. */
    @Serializable @SerialName("duel") data object PerDuel : Opt
}
```

**Once-per-turn is counted at activation**, and what a negated activation keeps depends on the wording (YGOrg,
"Demystifying Rulings, Part 10: Negation"; the red team, §2.3½): "you can only **use**" (and "Once per turn", `PerCopy`)
stays counted; "you can only **activate**" (`ByName.activate`, and the group `"card"`) is given back, since a negated
activation is as if it was never activated. A negated *effect* (the activation stands) keeps every count. "You can only
activate 1 X per turn" is `ByName` with the group `"card"`. "You can only Special Summon X once per turn" is
`SummonRule.oncePerTurn`.

**Choosing cards and saying which cards.** A `Pick` says how many, from where, of what, and who chooses.
- `bind` names the picked cards so later steps can refer to them ("the sent monster's Level").
- `ref` points at an earlier binding: `"self"`, `"targets"` or a bound name.

```kotlin
@Serializable
data class Pick(
    val n: Int = 1,
    val upTo: Boolean = false,
    val from: List<Spot> = emptyList(),     // (YOU | THEM | ANY) × (HAND, DECK, EXTRA, GY, BANISHED, MONSTERS, SPELLS, FIELD)
    val where: Filter = Filter.Any,
    val who: Rel = Rel.YOU,                 // who chooses
    val ref: String? = null,                // "self", "targets", or a name bound earlier
    val bind: String? = null,
    val top: Boolean = false,               // the top n of the Deck (a mill), not a choice
)

@Serializable
sealed interface Filter {
    // Any, Self, NotSelf, Name(card), NameHas(word) (an archetype by name, alsoNamed honoured),
    // Kind(MONSTER | SPELL | TRAP, sub), Frame(NORMAL | EFFECT | FUSION | SYNCHRO | XYZ | LINK | RITUAL | PENDULUM | TUNER | TOKEN),
    // Attribute(set), Race(set), Level(range), Rank(range), LinkRating(range), Atk(range), Def(range),
    // FaceUp, FaceDown, Controller(rel), Same(stat, ref) ("with the same name as x"), Lowest(stat), Highest(stat),
    // All(list), AnyOf(list), Not(f)
}

@Serializable
data class Step(val op: Op, val link: Join = Join.AND)

/**
 * How a step joins the one before it (YGOrg, "Demystifying Rulings, Part 5: Conjunctions"):
 * AND "and" — at the same time, both or neither; AND_IF_YOU_DO — at the same time, only if the one before happened;
 * THEN — afterwards, only if the one before happened; ALSO "also, after that" — afterwards, needing nothing;
 * WITH "also" — at the same time, needing nothing.
 */
enum class Join { AND, AND_IF_YOU_DO, THEN, ALSO, WITH }

@Serializable
sealed interface Op {
    // Move(pick, to, face): the general move; the rest are its common shapes, each with its own `how` and event:
    // Add(pick)                 to the hand (from the Deck a search, from the GY or banished a salvage)
    // Send(pick)                to the GY;  Discard(pick);  Destroy(pick);  Banish(pick, faceDown);  Tribute(pick)
    // Return(pick, to)          HAND | DECK_TOP | DECK_BOTTOM | DECK_SHUFFLED | EXTRA
    // Draw(n);  Shuffle(rel, pile);  Reveal(pick)
    // SpecialSummon(pick, pos)  from any place, into a zone the rules allow
    // FusionSummon(fusion: Filter, materialsFrom: List<Spot>);  RitualSummon(ritual: Filter, tributesFrom, levels)
    // SynchroSummon(f), XyzSummon(f), LinkSummon(f)   ("…then Synchro Summon using…")
    // Attach(pick, to: ref);  Detach(n, from: ref)
    // Token(name, attribute, race, level, atk, def, n, pos)
    // Negate(what: ACTIVATION | EFFECT, link: LinkRef)  a link on the chain (by default the one answered)
    // ChangeLevel(pick, to | by, until)   held in FxState: Synchro and Xyz read it
    // Lp(rel, delta);  PayLp(n) (a cost);  Counter(pick, kind, delta)
    // NormalSummonAgain(filter)   "you can Normal Summon 1 more"
    // Choose(options: List<List<Step>>, who);  If(cond, then: List<Step>, otherwise: List<Step>)
    // Declare(kind: NAME | TYPE | ATTRIBUTE | LEVEL, among: Filter?, bind)   the answer bound for later steps
    // Restrict(Restriction)
}

@Serializable
data class Restriction(
    val ban: Ban,                           // SPECIAL_SUMMON, SPECIAL_SUMMON_FROM_EXTRA, NORMAL_SUMMON, ACTIVATE
    val except: Filter? = null,             // "except DARK monsters"
    val seat: Rel = Rel.YOU,
    val until: String = Lock.UNTIL_TURN,
)

@Serializable
data class SummonRule(
    val normal: Boolean = true,             // false: "cannot be Normal Summoned/Set", and every Extra Deck monster
    val mustFirstBe: ProcKind? = null,      // "must first be … Summoned"
    val procs: List<Proc> = emptyList(),
    val oncePerTurn: Boolean = false,
)

@Serializable
sealed interface Proc {
    // Fusion(materials: List<Mat>)              made by a Fusion effect (FusionSummon); not a procedure of its own
    // Synchro(tuner: Mat, others: Mat)           Level sum = the card's Level
    // Xyz(n, each: Filter)                       each of Level = the card's Rank
    // Link(min, max, each: Filter, also: Filter?) rating sum = the card's Link rating (a Link counts 1 or its rating)
    // Ritual                                     made by a Ritual Spell (RitualSummon)
    // Inherent(from: Where, condition, cost, opt) "you can Special Summon this card from your hand if …": no chain link
}

@Serializable
data class Mat(val n: Int = 1, val where: Filter, val upTo: Boolean = false)
```

`Cond` covers:
- controls (a filter, a count, a seat), controls no monsters;
- the count in a place;
- the phase, whose turn it is;
- the chain's newest link (whose it is, a filter, what it includes);
- this card summoned or sent this turn;
- life points;
- `All`, `AnyOf` and `Not`.

Its numbers are constants, counts or a bound card's stat. **There are no loops and no arithmetic beyond that.**

**Card facts come from the pool, never from the script** (`FxFacts.of(Card)`): name, frame, Tuner, Level, Rank, Link
rating, the Link arrows (`Card.linkMarkers`), Attribute, Type, ATK and DEF. A script cannot claim a Level the card does
not have.

### 2.3 Timing: speeds, the chain and triggers

These are the game's rules, held once in the engine (`FxChain`):

- **Open state.** With the chain empty and nothing pending, the turn player may:
  - Normal Summon or Set;
  - use a summoning procedure;
  - use spell-speed-1 effects in their Main Phase (ignitions, Spell activations);
  - use speed-2 and speed-3 effects whenever their conditions allow.
- **Adding a link.** A link joins the chain only at spell speed 2 or more, and never below the speed of the newest link
  (speed 3 answers only speed 3).
  - The other seat gets the first chance to respond, then priority alternates. After a chain of triggers is built, the
    seat that did not make its newest link responds first (TCG Rulebook v10).
  - When both pass, the newest link resolves.
  - **Costs** are paid and **targets** chosen at activation. At resolution a target that left its place or no longer
    matches is dropped.
  - A negated link resolves doing nothing (`DuelAction.Negate`, `ChainLink.negated` exist already).
  - The chain's own Spells and Traps go to the GY through `DuelVerbs.resolve`, the one list for that.
- **Batches and timing.**
  - Steps joined by `AND`, `AND_IF_YOU_DO` or `WITH` are one batch: they happen at the same time.
  - `THEN` and `ALSO` start a new batch. A step that depends on the one before (`AND_IF_YOU_DO`, `THEN`) happens only
    if that step happened in full.
  - **A run of steps joined by `AND` happens both or neither**: if one of them cannot happen at resolution, none does.
    An effect may be activated only when its cost can be paid, its targets found, and its first part — the first step
    and every step joined to it by `AND` — can happen.
  - An event is **last** when nothing happened after it in its resolution, **and no later link has resolved since**:
    when a chain resolves, the last thing to happen is Chain Link 1's resolution, whatever it did.
  - An optional `WHEN` trigger whose event was not last **misses the timing**. `IF` triggers and mandatory triggers
    never do.
- **Simultaneous triggers (SEGOC).** Triggers that a batch, a summon or a chain's resolution set off wait until the
  chain or action is over. Then they form a new chain in this order (TCG Rulebook v10):
  1. the turn player's mandatory triggers;
  2. the other player's mandatory triggers;
  3. the turn player's optional triggers;
  4. the other player's optional triggers.

  Each player orders their own (a `Decision`, which says whose it is). A trigger on an activation (`ACTIVATED`) is
  spell speed 1 like any other: it waits for that chain to be over, and never answers it.
- **Summons start no chain.** A Normal Summon, or a Special Summon by procedure (Link, Synchro, Xyz, Inherent), starts
  no chain. Its triggers gather afterwards.

The step-1 rulings set (§8, part A) settles the exact cases. Each case is a test with a known answer from YGOrg's Q&A,
or from a house ruling named as one. Where Konami's rules are subtle — `AND` when one half cannot happen, or a cost as
the triggering event — the test is the authority and this section follows it.

### 2.3½ Rulings the red team settled (step 1)

The engine's agents listed decisions they were unsure of; the red team looked each up. Each row is held by the named
test. "Fetched" means the page was read for this; nothing here is copied card text.

| Case | The engine had | Settled as | Source | Test |
|---|---|---|---|---|
| SEGOC order | turn player's mandatory, their optional, then the other's | both mandatory groups (turn player's first), then both optional groups | TCG Rulebook v10, quoted by Yugipedia "Simultaneous Effects" (fetched) | `FxRulingsTest.a09`, `FxChainTest` |
| Priority after a chain of triggers | the other seat of the newest link (house ruling) | the same, now a rule: "the turn player has the first opportunity" when the opponent made the newest link | the same | `FxRulingsTest.h01` |
| "A and B" with one half impossible | the half that could happen, happened | **neither** happens; the activation needs the whole "and" run able | YGOrg, "Demystifying Rulings, Part 5: Conjunctions" (fetched): "you have to be able to do both A and B at resolution, otherwise you do nothing" | `FxRedTeamTest.andIsBothOrNeither…`, `FxVocabularyTest` |
| "Also" | no word for it (`ALSO` was "also, after that") | `Join.WITH`: same time, independent | the same article | `FxVocabularyTest` |
| What is "last" | nothing "happening" after it in the resolution | also: a later link's resolution, whatever it did | Problem-Solving Card Text Part 7, quoted by Yugipedia "If… You Can VS When… You Can" (fetched): "the last thing to happen is the resolution of the effect at Chain Link 1" | `FxRulingsTest.a14b` |
| A "when" trigger sent as a cost | misses (house ruling) | misses: a rule | TCG Rulebook v10's Jinzo - Returner / Lightning Vortex example, quoted on the same page | `FxRulingsTest.a14` |
| A negated activation and once-per-turn | always counted | "use" wording and "Once per turn" counted; "you can only **activate**" given back | YGOrg, "Demystifying Rulings, Part 10: Negation" (fetched): Pot of Duality against Nekroz Mirror; Infernoid Patrulea | `FxRulingsTest.a07`, `FxChainTest` |
| "The turn you activate this" restrictions (`Effect.leaves`) | applied at resolution, skipped when negated | a **condition**: binds from the activation, lifted when the *activation* is negated, kept when only the *effect* is; the card cannot be activated after its seat did the forbidden thing that turn. A lock that starts "after this effect resolves" is an `Op.Restrict` in `does`, not applied when the activation or effect is negated | OCG FAQ on Rage with Eyes of Blue (YGOrg, "[OCG] April 2025 Rulings Update", fetched); FAQ 23140, Nadir Servant (YGOrg, "OCG 11/10/20 rulings update", fetched) | `FxRulingsTest.a07`–`a07c` |
| A trigger on an activation (`ACTIVATED`) | put on the chain at once, as a response (even under a Counter Trap) | spell speed 1: waits for the chain to be over | Yugipedia "Spell Speed" (fetched): two speed-1 effects share a chain only when they go off at the same time | `FxRedTeamTest.aTriggerOnAnActivation…` |
| Name declarations | only names the seat had seen | any card that exists, never a Token; the window searches the pool (`Decision.Declare.open`, `Chooser.name`) | Yugipedia "Declare" (fetched), citing the OCG Perfect Rulebook 2015 p. 47 and Konami FAQ 12551 | `FxRedTeamTest.aNameDeclaration…`, `ShortcutRedTeamTest` |
| A Pendulum Monster leaving the field for the GY | only when destroyed face-up | whenever it would be sent from the field to the GY, face-up or face-down, by any cause: face-up to the Extra Deck | Yugipedia "Pendulum Monster" (fetched), citing the rulebook | `FxRedTeamTest.aPendulumMonster…` |
| Destroying outside the field | the hand only | the hand, the Main Deck or the Extra Deck when the pick reaches there; never the GY or banished | Yugipedia "Destroy" (fetched) | `FxRedTeamTest.aCardInTheDeck…` |
| A Field Spell over your own | the old one to the GY at activation | kept: sent by the game's own rule, not a cost or an effect | Yugipedia "Field Spell Card" (fetched) | `FxRedTeamTest.aFieldSpellOverYourOwn…` |

**Still open** (house rulings until a source is cited): "up to n" is 1 to n, never 0 (no Q&A found; `FxFilters.bounds`
now agrees with the executor); the other seat's Quick Effects in an open state (`FxRulingsTest.h02`); a "the turn you
activate this" condition looks back over Normal and Special Summons and activations, but not over what is not in
`FxState.deeds` (attacks, a Set Spell); the choices the other seat makes in a move (its optional triggers, its order,
a pick `who = THEM`) are put to the moving seat's chooser, which must route them by `by` — the table's window must
(step 2), the goldfish answers both seats itself.

### 2.4 Game rules the engine holds (`FxRules`)

- One Normal Summon or Set a turn, plus any `NormalSummonAgain`.
- Tributes: one for Level 5–6, two for Level 7 and above, unless the script's `SummonRule` says otherwise.
- A Trap, or a set Quick-Play Spell, cannot be activated the turn it was set, unless the script's flag allows it.
- A Quick-Play Spell is activated from the hand only on your own turn.
- A Link or Pendulum monster from the Extra Deck goes to an Extra Monster Zone or a zone a Link points to.
- Materials go to the GY; Xyz materials go under the monster. A Pendulum Monster that would go from the field to the
  GY — destroyed, Tributed, a material or sent, face-up or face-down — goes face-up to the Extra Deck instead.
- "Cannot be Normal Summoned" is never Normal Summoned, and "must first be" is honoured.
- Restrictions in force bind, and once-per-turn is counted.
- The phases only go forward.

**One list.** From step 1, `PuzzleReferee` reads its summon and Tribute rules from `FxRules`. The puzzle set's five
Spells become reference scripts and `PuzzleEffect` is deleted. The puzzle baselines must stay exactly 0, 2 and 17 of
17 (`PuzzleTest`). Battle remains the referee's (`DuelBattle`).

### 2.5 Choices

**The engine never guesses: every choice is a `Decision` put to a `Chooser`.**

```kotlin
/** What a card choice is for: what a window says, and how it draws the picked cards' way. Worked out by the engine from the step. */
enum class Purpose { TARGET, COST, SUMMON, ADD, SEND, BANISH, DESTROY, RETURN, ATTACH, MATERIAL, TRIBUTE, DISCARD, REVEAL, OTHER }

/** The effect a decision is for: its card, the effect's id ("e1", "proc", "rule"), and its short name ("Revive"). */
data class FxSource(val uid: Int, val effect: String, val label: String = "")

/** Where a step takes the cards it picks: [dest] on [seat]'s side, and the positions it allows on the field. */
data class Landing(val dest: Dest, val seat: Int, val positions: List<CardPosition> = emptyList())

sealed interface Decision {
    /**
     * min–max of [among] (uids). [purpose] (a cost's own pick is COST; a target is TARGET, never read off [why]); [to]: where
     * and in which positions; [from]: each candidate's place, in step with [among]; [effect]; [step]: "2 of 3" within the
     * effect; [hidden]: some lie where only the chooser may look (its Deck); [looked]: the places the pick looked in, so a
     * window can show a place with nothing legal; [by]: the seat that chooses when it is not the user ("your opponent chooses").
     */
    data class Cards(
        val why: String, val among: List<Int>, val min: Int, val max: Int,
        val purpose: Purpose = Purpose.OTHER, val to: Landing? = null, val from: List<Place?> = emptyList(),
        val effect: FxSource? = null, val step: String? = null, val hidden: Boolean = false,
        val looked: List<Spot> = emptyList(), val by: Int? = null,
    ) : Decision
    /** Which of [among] (the legal, free zones) [card] goes to, and the [positions] it may take there. */
    data class Zone(val among: List<Place.Zone>, val card: Int? = null, val positions: List<CardPosition> = emptyList(), val effect: FxSource? = null) : Decision
    /** Which of [among] [card] is summoned in: face-up Attack or Defense, or face-down Defense where it is Set. */
    data class Position(val card: Int, val among: List<CardPosition>, val effect: FxSource? = null) : Decision
    /** A seat's own simultaneous triggers in chain order (a permutation); [labels] never name a card hidden from the mover. */
    data class Order(val triggers: List<Pending>, val labels: List<String> = emptyList(), val by: Int? = null) : Decision
    /** An optional trigger, a "you can …"; [by]: whose choice it is. */
    data class YesNo(val why: String, val effect: FxSource? = null, val by: Int? = null) : Decision
    /** One of [among]: a Choose's options, and which of a card's Shortcuts. */
    data class Option(val among: List<String>, val effect: FxSource? = null) : Decision
    /** A card name, Type, Attribute or Level. [open]: a name — any card that exists, [among] only a start (§2.3½). */
    data class Declare(val kind: DeclareKind, val among: List<String>, val effect: FxSource? = null, val open: Boolean = false) : Decision
}

fun interface Chooser {
    /** Indexes into the decision's own list ([YesNo]: [1] yes, [0] no); out of bounds, or CANCEL, cancels the whole use. */
    fun choose(d: Decision): List<Int>
    /** An open name declaration answered by any card of the pool: its passcode (checked by the engine), or null. */
    fun name(d: Decision.Declare): Int? = null
    /** A decision with one legal answer is never put; the chooser is told it, so a line's answers stay in step. */
    fun told(d: Decision, answer: List<Int>) {}
}

object FxEngine {
    /** Everything [seat] may start now: activations (card, effect), Normal Summon or Set, procedures, phases, pass, resolve. */
    fun moves(t: FxTable, seat: Int): List<FxMove>
    /** [move] made: the tagged actions to commit and the table after, or refused with the rule that forbids it. */
    fun play(t: FxTable, seat: Int, move: FxMove, chooser: Chooser): FxPlay
    /** Why [seat] may not activate [uid]'s [effect] now, in words: what a Shortcut shows greyed. */
    fun refusal(t: FxTable, seat: Int, uid: Int, effect: String): String?
}
data class FxTable(val state: DuelState, val fx: FxState, val book: ScriptBook, val facts: FxFacts, val seed: Long = 0L)
```

**Left for step 2** (the Shortcut window's review asked for them; none falls out of step 1 cleanly): `Cards.refused`,
each candidate a place held that the pick refused, with its reason (the filter evaluator says only yes or no);
`Zone.closed`, each zone the rules close and why (taken, no Link points there, one Extra Monster Zone a seat); and a
pure `FxEngine.stillLegal(d, picked)` for rules over a whole set ("different names", a Level total), which needs the
material sets carried on the decision.

**The choosers:**
- the test's `RecordMatcher` (§4.2), which answers from the record;
- the goldfish search, which tries each answer in turn;
- a combo's `PlanChooser`, which answers by the names its steps give, either copy, as `anyCopy` does;
- the table's `Chooser` for **Shortcut** (§5½), which asks the person on the page, or reads Ai's choices from
  its op.

For the search, a choice of a subset is split into a chain of yes/no choices, the shape the research found works
under a small budget (`docs/AI-INTELLIGENCE.md` §2).

`ScriptBook` resolves any printing through `CardIdentity.canonical`. It is built either over all scripts (for tests)
or over verified effects only (for the goldfish). **The engine is deterministic:** the same table, scripts and answers
always give the same actions.

### 2.5½ An example

An example of what Ai writes in `lib/effects/<passcode>.js`, through the prelude's builder. The card is fictional,
made up for this note; real cards are written from their own meaning.

```js
fx.card(900000001, {
  effects: [
    fx.trigger('e1', { on: fx.on.summoned('normal', 'special'), optional: true, opt: fx.opt.byName(),
      does: [ fx.add({ from: 'your deck', where: fx.all(fx.nameHas('Example'), fx.monster(), fx.level(1, 4)) }) ] }),
    fx.quick('e2', { from: ['gy'], opt: fx.opt.byName(), condition: fx.cond.theirTurn(),
      cost: [ fx.banish({ ref: 'self' }) ],
      targets: [ fx.pick({ from: 'their monsters', where: fx.faceUp(), bind: 't' }) ],
      does: [ fx.returnTo('hand', { ref: 't' }) ] }),
  ],
})
```

It compiles to the `CardScript` above, as JSON. The person reads it in words (§3.5):
- **Effect 1** — when this card is Normal or Special Summoned, you can add an "Example" monster of Level 1–4 from
  your Deck to your hand. Once per turn, by name.
- **Effect 2** — a Quick Effect from the GY on their turn: banish this card, target a face-up monster they control,
  and return it to the hand. Once per turn, by name.

### 2.6 Left out of the first cut

The first cut is what a **turn-one combo** needs. Battle comes later (Phase E).

Left out:
- **Battle:** the Battle Phase and the Damage Step, battle triggers, ATK and DEF changes, damage.
- **Ongoing effects:**
  - continuous effects other than restrictions;
  - protection, "unaffected", the opponent's floodgates;
  - lingering negation of a face-up card;
  - equips.
- **Summoning mechanics:**
  - Pendulum Summons (placing a Scale is already a table move);
  - Gemini, Union, Spirit and Toon monsters.
- **Chance:** coins, dice, random picks and excavation. The engine never guesses, so the card is `UNSUPPORTED` for that
  part.
- **Information effects:** looking at or revealing the opponent's hand.

A card the first cut cannot fully say still gets a script: `unsupported` names the parts it cannot say. The effects
the script can say may still be verified and used.

The vocabulary grows in `:core` with tests, a family of cards at a time, as the instruments did. **It never grows a
JavaScript callback.**

---

## 3. Authoring

### 3.1 On demand: the cards the person asks for (kai's decision)

kai: "I think effects as code should be done by the Ai for cards the user wants because otherwise the whole cardbase
would need to be done and it would take too long."

So **authoring is on demand.**
- **There is never a pass over the card pool**, nor over a whole deck unless the person asks for one.
- **Ai writes a card's effect only when the person has asked for it.**
- **Everything else is unknown.** The Duel page plays an unknown card by hand, exactly as today. The goldfish treats
  it as unknown (§5.5).

**How a person asks.** They ask for one of four things:
- one card;
- a deck's main engine (§1: the cards its combos use, plus pinned cards);
- one of the deck's groups (`DeckGroups`, the Groups panel's roles: "Starters", "Extenders");
- the cards in one combo.

| Where | What it offers |
|---|---|
| **The card viewer** (`CardViewer`, the card held large with every action beside it) and **the inspector** (builder and Duel page) | **Write its effect** on a card with none; its status and its effects in words on a card that has them |
| **The deck's guide** (the coverage line at its head, §4.5) | **Write the engine's**, **Write a group's…**, and the suggested cards (below) |
| **Ai World**, the Effects pane (§3.5) | choose cards, the open deck, a group or a combo, then **Write these**; a combo's row on the Duel page's combo list offers **Write this combo's cards** too |
| **Ai's chat** | the person says it in words ("write the effects for my Branded engine"); Ai answers with `fx_request` (below) |

**The go is always a click**, wherever the ask starts.
- **In a place on screen,** the button the person presses is the go.
- **In the chat,** Ai cannot write on its own reading of the conversation. `fx_request` puts a card into the chat
  listing the cards and the cost, with **Write** and **Not now**. Ai cannot tell its own idea from the person's ask in
  a way core could hold, so the click is the line.
- **Recorded.** A go adds the cards to the person's **asked list** (`FxAsks`, `<data>/effects/asked.json`): each card,
  when, from where, and the request it belongs to.
- **Enforced in core.**
  - Ai's `world_write` to `lib/effects/<passcode>.js` for a card not on the list is refused: "not asked for; offer it
    with fx_request".
  - A card on the list may be written and repaired as often as its tests need.
  - The person's own saves in the editor need no list: the person is the one asking.

**Suggestions, never actions.** `FxSuggest` (core, tested) ranks what to write next:
1. the cards the deck's saved combos use that have no verified effect, by how many combos use each;
2. the cards of the groups the person marked as the engine;
3. the most-played cards of the Main Deck, by copies;
4. scripts that are failing or warned, to repair.

The guide's coverage line and the Effects pane show the top suggestions with one **Write these** button. Ai may
suggest in the chat, but only through `fx_request`'s card. **Nothing is written without the person's go.**

**Written once, used by every deck.**
- A card's effect lives in the one library (§3.3), keyed by `CardIdentity`.
- Once written and verified, it is reused by every deck that holds the card, in any printing.
- A request lists the cards that already have a verified script as **already done (reused)**, at no cost. Cards with a
  failing or warned script are listed as **to repair**.
- **Verification is per card.** The card's tests come from every deck's record.
- **Coverage is counted per deck** (§4.5).

**Cost, said before and after.**
- **Before the go,** the request card says the cost, per card and in all: "4 cards to write, about 30,000 tokens each,
  ≈ $0.90 at list prices, Oct 2026".
  - The tokens per card are the connection's own measured figure from Test scores (§8, part B), or else an assumed
    30,000, said to be assumed.
  - The money is `Prices.estimate` at the connection's `Prices.of(provider, model)`, written by `Prices.words`.
  - A plan's command-line app has no price, so the card says so in `Prices`' own words rather than inventing a sum.
- **After writing,** each card keeps what it really cost: the session's rounds (`AgentEvent.Round`, `Usage`) between
  starting the card and its `fx_check`, priced by `Prices.cost`. It is shown on the card's row ("written for 31,000
  tokens, ≈ $0.24") and kept in `asked.json`.

### 3.2 A script is JavaScript that builds data

**Decision:** Ai writes `lib/effects/<passcode>.js` with the prelude's builder (`ygo.fx`, alias `fx`). Each builder
function returns a plain object and checks its own arguments, so a mistake fails at the line that made it. The file
returns one `CardScript`.

The compiled JSON, not the JavaScript, is what the engine reads and what is verified.

**Why:**
- **Speed.** The engine is Kotlin in `commonMain`. It runs on the phone and in `commonTest`, at the app's speed. Rhino
  stays off the hot path: interpreted, it needs a context and a thread for every run (`JsRuntime.run`). Even a goldfish
  that makes millions of moves would never call it.
- **Data can be checked before it runs, which code cannot.** It is checked before use (§3.4), fingerprinted, read back
  in words (§3.5) and compared with the card's text (§3.4).
- **Ai's code never runs during play, a test or a search.** It runs once, shut in, to build data.

**Rejected:** effects as JavaScript functions called during play (Code World Models taken literally). They would be
slow, impossible to check before running, impossible to show in words, and Ai's code would sit inside every duel the
engine plays.

**Shared helpers** live in `lib/effects/_*.js` and are loaded with `ygo.use`. Verification follows the compiled data,
so a helper's change re-verifies every card whose data it changed, by itself.

**Compiling** (`FxCompile`, jvmMain beside `JsRuntime`):
1. The script runs in Rhino on a small budget (5 seconds, the prelude's guards).
2. Its output is decoded leniently by `FxCodec`.
3. The legality pass runs (§3.4).
4. The result is written to `<data>/effects/<passcode>.json` with the hash of its source.

It runs when a file under `lib/effects/` is saved — by Ai's `world_write` or by the person in the editor — and on
`fx_check`.

**Python never writes effects.** It runs only on the desk and is off by default; the library must compile on the
phone.

### 3.3 One library, by card

**Decision:** one library per person, at `<data>/effects/`, keyed by canonical passcode. Every world mounts it at
`lib/effects/`. Writes there land in the library (`Worlds` routes the prefix), and no world keeps a copy of its own.

**Why:**
- **A card's effect does not depend on the deck.** Two decks that play Ash must not disagree about it.
- **One script per card.** `CardIdentity` makes an alternate artwork read the same script.
- **Tests add up.** Every deck's record that plays a card adds tests to its one script.

The roadmap's "per deck" survives where it matters:
- authoring starts from what the person asks for, most often a deck's engine, a group or a combo (§3.1);
- tests come from a deck's record;
- coverage is reported per deck.

### 3.4 The legality pass (`FxCheck`)

**Four layers.** Errors make the script `BROKEN`; warnings block verification until they are fixed, or accepted by the
person.

1. **Shape.** The vocabulary version, known words, and bounds: at most 8 effects, steps nested at most 6 deep, picks
   of at most 60 cards, a script at most 64 KB.
2. **References.**
   - Effect ids are unique.
   - Every `ref` is bound before it is used, and every once-per-turn group is named.
   - Costs never target, and a trigger has an event.
   - Each kind is used from places that fit it: an `IGNITION` never from the hand; a Trap's `ACTIVATION` from the
     Spell & Trap Zone.
3. **The card.**
   - `card` is the canonical passcode in the pool.
   - The script's kinds fit the card: a Spell has no `TRIGGER` from a Monster Zone.
   - An Extra Deck monster has its frame's procedure, with numbers that agree with its facts (Link rating, Rank, Level,
     Tuner).
   - A Normal Monster has no effects.
4. **The text** (`FxLints`): lints that read the printed text for its obvious markers and compare them with the script.

| In the printed text | Expected in the script |
|---|---|
| "only use this effect of "X" once per turn" | `ByName` on that effect |
| "only use each effect of "X" once per turn" | `ByName` on every effect |
| "only use 1 "X" effect per turn" | `ByName` with one shared group |
| "Once per turn" | `PerCopy` |
| "(Quick Effect)" | `QUICK` |
| an effect opening "If …:" or "When …:" | `Trigger.timing` `IF` or `WHEN` |
| "You can …" | `optional` |
| a clause between ":" and ";" | cost steps |
| "target" | targets |
| "Cannot be Normal Summoned/Set", "Must first be … Summoned" | `SummonRule` |
| quoted names | names that a filter or a step uses |
| "Level N or lower", Attribute and Type words in a search | the pick's filter carries them, as `card_web` reads them since R12 |
| the number of activated effects | the number of effects (a warning only) |

**The lints are reads for the obvious, like `DuelCardInfo.handCost`.** They never parse the text into effects. The
text itself stays in the pool. A script keeps only the text's hash, so **errata shows**: when the pool's text no longer
matches, the card warns "the card's text changed since this was written" until it is looked at again.

### 3.5 Seeing one, and correcting it

**The Effects pane** in Ai World (`Alt 7`, a seventh pane beside Files…Activity; a tab on a phone) lists the
library, or the deck in scope. For each card it shows its status (§4.4) and its effects **in words** (`FxWords`, our
own plain sentences, as in §2.5½) next to the card's printed text. It also lists its tests, each failure in words,
and its open warnings.

The card's inspector on the Duel page shows the same words under "Effects".

**Correcting one.** Either:
- ask Ai: the pane's row opens the Ai panel with the card in context;
- or edit the `.js` in the editor. The person's save compiles and checks the script the same way (`WorldEvent.YOU`).

**Only the person may:**
- **accept a warning**, with why;
- **set a test aside**, with why ("this record is a misplay").

Core refuses either when it comes from Ai (§7). Rows open by click, tap or Enter; the keys go into `DeskShortcuts`
(`DeskScope.WORLD`), and the mouse and the finger match through the existing tables.

### 3.6 Ai's tools and skill

**Writing** is `world_write` to `lib/effects/<passcode>.js`.

**New tools,** in a handler group of their own (`neue/ai/AiEffects.kt`, F2's pattern: not more of `AiHost`):
- **`fx_request`** — offer cards to write: by card, a deck's engine, a group or a combo. It answers with what is
  already done, what is to write and to repair, and the cost, as the chat's request card with **Write** and
  **Not now**. Only the person's click puts cards on the asked list (§3.1).
- **`fx_state`** — read only. A deck's coverage; a card's script in words, its tests, failures and warnings; the deck's
  targets.
- **`fx_check`** — compile and check one card, run its tests, and return its verdict.
- **`fx_target`** — name or edit a deck's end board (§5.2). Ai's targets are marked as Ai's.
- **`goldfish`** — an instrument (`Instruments.goldfish`), run with `world_tool` or `ygo.tools.goldfish`. Its numbers
  reach the ledger the way `openings` does.

`AiToolsTest` holds that the new tools are offered and answered.

**The `effects-author` skill** tells Ai to:
- read the card through `card_info`, and rulings through the existing tools (which already wrap outside text);
- write only the cards on the asked list, one at a time, in the order of the request; offer any other card through
  `fx_request` and wait for the go;
- write from the card's meaning;
- run `fx_check` and fix from the failures;
- mark what it cannot say as `unsupported`;
- never make a test pass by making the card do less or more than it says;
- never write from another engine's scripts, recalled or found.

**A go starts the work.** Wherever it comes from (§3.1), the person's go starts a world session (`MODE_WORLD`, the
skill, the request in scope, the deck as its context). The person watches the session in Ai World as any other.
Stop ends it, and cards it did not reach stay on the asked list, marked not started.

---

## 4. Verification

### 4.1 What the record is

**Sources:**
- the deck's combos (`<data>/duel/combos/<deck>.json`);
- its replays (`<data>/duel/replays/`): every replay where a seat played the deck, by `SeatSetup.deckId`, or by the
  deck's fingerprint when no id was kept.

**Only people's moves are ground truth.**
- Spans whose moves carry `by` person count, and the guest's count for the guest's own deck.
- Moves with no provenance (written before Phase C) count as the person's, as they were.
- **Ai's moves are never tests.** Ai writes the effects, so its own play cannot vouch for them. This covers Ai at the
  table, Ai vs Ai, and Ai World's script tables.

**Combos gain two fields:**
- `by` (`person` or `ai`);
- `confirmed` (a click by the person: "this line is right").

A combo Ai recorded counts once the person confirms it. A combo saved before this release has no `by`. It counts, and
coverage marks it "author unknown" so it can be set aside with one click (§11, decision 3).

**Test units:**
- each combo is one test;
- each turn of the deck's seat in a replay is one test, with the other seat's moves in it applied as written.

### 4.2 What a test is

```kotlin
@Serializable
data class FxTest(
    val id: String,          // "combo:<id>", "replay:<id>:t<turn>"
    val deck: String,
    val seat: Int,
    val source: String,      // combo | replay
    val author: String,      // person | guest | unknown | confirmed
)
```

**A run** (`FxTestRun`, core, pure):

1. **The start.**
   - For a combo: the deck is dealt by a fixed seed with the combo's `needs` in hand, and `ComboRunner.plan` turns its
     steps into the recorded actions.
   - For a replay: the log is folded to the turn's start (`DuelCheckpoints`), and `FxState` is inferred up to it:
     - Normal Summons from `how` "normal" or "set" from the hand;
     - this turn's summons from `how`;
     - once-per-turn uses from the episodes already matched.
2. **Episodes** (`FxEpisodes`).
   - The span is cut at each thing the engine starts:
     - a Normal Summon or Set from the hand;
     - a `ChainAdd`;
     - a Special Summon from the Extra Deck, or an inherent one (a monster to a zone with `how` "special" and no link
       before it);
     - a chain link resolving;
     - a phase.
   - Each episode holds the recorded moves up to the next start, with talk dropped.
   - A link is matched where it resolves. Its moves may come before or after the person pressed resolve; both are read.
3. **Matching.**
   - The engine lists its legal moves at the current table. The move whose card and kind match the episode's start is
     made, with the `RecordMatcher` answering every decision. It picks the option whose moves the episode holds: the
     same card by uid where the record knows it, else the same card by identity.
   - For a card with several effects, the matcher tries each legal effect. Exactly one explaining the moves is a match;
     more than one is **ambiguous**.
   - Compared: what the record moved against what the engine moved, each counted as (card identity, from, to, face).
     Zone indexes count only where a rule reads them (Extra Monster Zones, linked zones). `how` words and the order
     inside an episode are ignored.
4. **Re-synchronised.** After every episode both are set back to the recorded table and its `FxState`, so one wrong
   effect fails its own episodes and never cascades into the rest of the line.
5. **The end table** is compared (`TableShape`: each zone's card and face, every pile as a count of cards), as a last
   check that the re-synchronising hid nothing.

**Each episode ends in one of these outcomes:**

| Outcome | Means |
|---|---|
| `PASS(card, effect)` | the engine made exactly what the record did |
| `FAIL(card, effect, why)` | "the record also added X; the script does not", "the script also draws 1", "not legal by the script: once per turn, used" |
| `AMBIGUOUS` | more than one effect explains it: neither passes nor fails |
| `UNEXPLAINED` | moves no start explains, and no single legal activation does either (an inferred episode is tried first); the test fails, blamed on no card |
| `BLOCKED(card)` | the card has no script; the episode is applied as written |
| `FOREIGN` | the other seat's card; applied as written, never judged |

**A test passes when no episode fails or is unexplained.** Its passing episodes vouch for the effects they used.
Failures are written through the tested seat's eyes (`DuelSight`), so text Ai reads never names a card that seat
could not see.

### 4.3 Refusal tests: the rules and the text, never the script

A record shows only what a card *did*. Each passing episode also yields mutants whose answer is fixed by the game's
rules or the text's markers. **None is derived from the script itself**, so a script cannot make its own refusals pass.

| Mutant | Must be refused when |
|---|---|
| **again** — the same effect again that turn (the other copy for by-name) | the text has a once-per-turn marker |
| **too broad** — a card from the same place, offered in place of the recorded pick | the text's markers reject it (another Level, Attribute, Type or name word) |
| **no cost** — the cost's fodder removed | the text marks a cost |
| **wrong place** — the same activation from a place the text does not name | the text names where it is used from |

A mutant is made only where the lint read the marker. Where the text is silent, there is nothing to test.

### 4.4 Verdicts are per effect

```kotlin
enum class FxStatus { VERIFIED, UNTESTED, FAILING, WARNED, BROKEN, UNSUPPORTED, MISSING, NONE /* a Normal Monster */ }

@Serializable
data class EffectVerdict(
    val effect: String,          // "e1"…, or "proc" for its summoning procedure
    val status: FxStatus,
    val passed: List<String>,    // test ids
    val failed: List<String>,
    val why: String = "",
)

@Serializable
data class CardVerdict(
    val card: Int,
    val script: String,          // the compiled script's hash
    val text: String,            // the printed text's hash it was checked against
    val engine: Int,             // FxVocab.ENGINE: the engine's version
    val effects: List<EffectVerdict>,
    val at: Long,
)
```

**An effect is `VERIFIED` when all of these hold:**
1. the script passes the legality pass;
2. no warning on the effect is open (each is fixed, or accepted by the person);
3. at least one passing episode from a person's record used it;
4. no episode that used it fails;
5. its refusal tests pass.

A card is verified when all its effects are. A card with some effects verified lists which ones.

**The goldfish uses verified effects only** (kai: "nothing unverified is used by search"). A partly verified card's
other effects are simply not offered.

**A verdict is bound to its inputs:** the script's hash, the text's hash, the engine version, and the tests' sources.
Any change re-runs the tests, which is cheap: Kotlin, off the frame thread.

**Verdicts are a cache, never a record.** Each device recomputes them (§6), so "verified" can never arrive from
outside.

**This is F1's regression set.** Every deck's tests re-run on any change to a script or to the engine. The fixture
deck's tests run in CI (`FxFixtureTest`, §8), so an engine change that breaks a verified card fails the build.

### 4.5 Coverage per deck (`FxCoverage`, core)

For each distinct card of the Main and Extra Deck (the Side Deck on request):
- its status;
- how many of its effects are verified;
- its tests.

For **the engine** (§1): whether each card is verified.

**What next:** `FxSuggest`'s suggestions (§3.1), with one **Write these** button. The button is the person's go;
coverage itself never starts anything.

**Unknown cards are named** with what they cost the goldfish (§5.5): "4 cards have no verified effect; the goldfish
plays them as inert".

**Shown in:**
- the Effects pane;
- **one computed line at the head of the deck's guide**, wherever the guide is read (the guide pane, the reader,
  Ai's prompt through `guideForPrompt`): "Effects: 18 of 24 cards verified; the engine 11 of 11; missing: …". It is
  computed when read, never an entry Ai writes;
- `fx_state`;
- a table board Ai may pin in a world.

---

## 5. The goldfish simulator (`core/duel/effects/goldfish`)

### 5.1 What it answers

The setting: a deck, a target, and a seat order — going first (5 cards, no draw) or going second (6 cards, an empty
field across the table). There is **no opponent**, which is what a goldfish is.

Two questions:
1. **The search.** Of N hands dealt from a seed, in how many can the verified effects reach the target in one turn,
   and by which lines?
2. **A recorded line.** In how many hands does that line, played as recorded, get there?

### 5.2 Targets: end boards

```kotlin
@Serializable
data class EndBoard(
    val id: String,
    val name: String,                // "Mirrorjade + one negate"
    val deck: String,
    val all: List<BoardCond>,
    val by: String = "person",       // or "ai": Ai may name one, and the pane says whose it is
)

@Serializable
sealed interface BoardCond {
    // Controls(where: Filter, n = 1)   face-up on your field once the turn is over
    // SetCards(n)                      set Spells and Traps
    // Holds(where, n)                  in your hand (a hand trap kept)
    // InGy(where, n), Banished(where, n)
    // Interruptions(n)                 counted from the scripts, below
    // AnyOf(list)
}
```

**Interruptions are counted from verified scripts, never guessed.** An effect counts when all of these hold:
- a card on your field, set, or in your GY could use it on the other player's turn: a `QUICK`, a set Trap's or
  Quick-Play's `ACTIVATION`, or a `TRIGGER` on their activation;
- its steps negate, destroy, banish or return a card.

They are counted **one per once-per-turn group**. The count says how many answers the board holds, not how good they
are. The board is read **after the End Phase**, with the turn's own End Phase triggers resolved.

### 5.3 Hands and seeds

- **Hand k of a run** is dealt by `DuelRandom.riffle` keyed with `DuelRandom.forRoll(seed, k)`: the duel's own
  Fisher–Yates, which gives the same order on every platform. The Deck's order is kept for draws within the turn.
- **Reproducible.** The same seed gives the same hands everywhere. Workers take hands by index and results are put
  together in index order, so the counts never depend on how many threads ran.
- **Sizes.** By default 2,000 hands on the desk and 500 on a phone; up to 20,000 when asked.
- **Reduced hands.**
  - A card reads as a blank when **both** hold:
    - no verified effect in play could pick it from the hand or the Deck (checked against every filter of the scripts
      in play, costs included);
    - no target names it.

    An unknown card a discard cost could take is **not** a blank: it is fodder (§5.5).
  - A hand is reduced to its **engine part**. Each distinct reduced hand is searched once, with its result shared by
    every hand that reduces to it.
  - This holds only while the scripts in play never read the Deck's order (no `Draw`, no top-of-Deck pick). This is
    `orderFree`, decided from the scripts; without it, every hand is searched on its own.
  - A test holds the reduction honest: memoised and plain runs agree hand for hand on the fixture deck.

### 5.4 Lines: a recorded plan, or a search

**A recorded plan.** A combo's steps are played through the engine with the `PlanChooser`. It gets there when:
- its needs are in hand;
- every step is legal by the verified scripts;
- the board meets the target.

This is "this line".

**The search** (`GoldfishSearch`) is depth-first over `FxEngine.moves`, with:
- **A transposition table**, keyed by a canonical hash of the table and `FxState`. Cards count by identity per zone and
  pile, so two copies are one.
- **Choices collapsed where they cannot matter:**
  - zones by symmetry, unless a rule reads them (Link arrows, Extra Monster Zones);
  - copies of one card are one option;
  - the End Phase only when nothing else is legal or the target is met.
- **Ordering:** moves that meet a target condition first, then moves that add to the field or the hand.
- **Bounds:** at most 60 engine moves in the turn, and a budget of moves per hand (default 20,000).
- **It stops at the first line that meets the target.** The question is "can it", not "which line is best"
  (that is Phase E).

**Each hand ends one of three ways:**
- **reached**, with its line;
- **no line** — the search covered everything within its bounds: the verified effects cannot get there from this hand;
- **undecided** — the budget ran out.

**Lines named.** Each line found is summarised as its **skeleton**: the activations and summons in order, by card
name ("Aluber → Branded Fusion → Mirrorjade"). Skeletons are grouped and counted: "the search found 4 lines; the
commonest in 41 % of hands".

### 5.5 Cards with no effect yet

Authoring is on demand (§3.1), so most decks hold cards the library does not know. **Three states:**

| State | Means |
|---|---|
| **Known** | every effect, and its procedure, is `VERIFIED`; or it has no effect (`NONE`: a Normal Monster) |
| **Partly known** | some effects verified: those are used and the rest are not offered |
| **Unknown** | no script, or nothing in it verified |

**At the table nothing changes for them.** The Duel page plays every card by hand, as today, and offers no **Use the
effect** on a card with no written effect (§5½). Unknown cards are unknown to the engine only.

**In the goldfish, an unknown card is inert.**
- It is never activated, and its effects never trigger.
- It is never Normal Summoned, Set or Special Summoned, by any procedure or effect.
- It is never used as material, as a Tribute or for its own cost.

Its summoning conditions and what it would do are not known, so assuming any of it could overstate the number.

**Known effects may still move it as an object.** It can be searched to the hand, sent from the Deck, or discarded or
banished as another card's cost, because those steps belong to the known card.

**When a found line moves an unknown card,** the line says so ("touches Aluber, unknown"). Its own triggers, mandatory
ones included, were not played. The headline counts those hands apart (below).

**What is and is not computable:**

| Run | When an unknown card is involved |
|---|---|
| **A target that names an unknown card** in `Controls`, `InGy` or `Banished`, so its procedure or a step of its own would be needed | **Not computable.** The run is refused before it starts, naming the cards, with **Write these** (the person's go, §3.1). `Holds` may name an unknown card: holding one needs no effect. |
| **A recorded line** (a combo) in which an unknown card activates, is summoned, or is used as material or a Tribute | **Not computable** for that line, naming the cards. It is never reported as 0 %. The search still runs. |
| **The search** | **Always computable, and a lower bound.** Unknown cards are inert, so every line found uses verified effects only. The sentence says how many hands held an unknown card, and how many reached lines touched one. |

**The headline with unknown cards:**

> Gets there in at least 58.2 % of 2,000 hands (95 %: 56.0–60.3), seed 7, going first; no line in 33.1 %; undecided in
> 8.7 %. 21.4 % of hands held a card with no verified effect (Aluber ×2, Branded Fusion ×1), played as inert: the
> true number may be higher. 3.0 % of hands reached the board through a line that moves an unknown card.

The deck's unknown cards are listed under the result in `FxSuggest`'s order, with **Write these**. **The goldfish
never writes anything itself.**

### 5.6 The numbers, and their proof

The headline reads:

> Gets there in 63.1 % of 2,000 hands (95 %: 61.0–65.2), seed 7, going first; no line in 30.4 %; undecided in 6.5 %.

- It is reached hands out of N, with Wilson's interval as the instruments give it.
- It is a **lower bound**: undecided hands count as not reached, and the sentence says how many there were.
- When the deck holds unknown cards, it says "at least" and adds the two shares from §5.5.

```kotlin
@Serializable
data class GoldfishResult(
    val version: Int = 1,
    val deck: String,                 // Ledger.fingerprint
    val library: String,              // fingerprint of the verified scripts used: (card, script hash) sorted, and the engine version
    val target: EndBoard,
    val first: Boolean,
    val hands: Int,
    val seed: Long,
    val budget: Int,
    val reached: Int,
    val noLine: Int,
    val undecided: Int,
    val unknown: List<Int> = emptyList(),   // the deck's cards played as inert (canonical passcodes)
    val heldUnknown: Int = 0,         // hands holding at least one of them
    val touchedUnknown: Int = 0,      // reached hands whose line moved one
    val notComputable: List<NotComputable> = emptyList(), // recorded lines (and why) that need an unknown card
    val lines: List<LineCount>,       // skeleton and count, and a combo's id when the line is a recorded one
    val outcomes: List<HandOutcome>,  // each hand: index, reduced hand, outcome, line: every number opens its hands
    val ms: Long,
)
```

- **Every number opens its hands**, and **any hand opens as a replay.** The hand is made again from the seed and its
  index through `DuelGame.act`, with tags, and shown on the Duel page. It is stored only if the person saves it.
- **The proof.** The goldfish is an instrument, so its answer comes through `world_tool` and `Evidence.judge` traces
  the number to it.
  - `Proof` gains `library` (optional). `Ledger.staleAgainst` marks the number stale when the library fingerprint no
    longer matches, as it already does for the deck.
  - `world_tool` is already re-runnable (`Evidence.RERUNNABLE`). A stale number is recomputed with the same seed, and
    the entry is then checked or contradicted.
- **Only the goldfish can vouch for a line** (step 4, `Evidence.lineClaims`). A guide entry claiming that a line or a
  hand "gets there", "goes off" or "makes the board" with a percentage is held to a `goldfish` source. Ai's own
  `world_run` simulation of play cannot vouch for it: it did not use verified effects.

### 5.7 Budget

- **Off the frame thread:** on `Dispatchers.Default` with one worker per core, less one. Cancellable, with progress
  shown and its rate in hands a second.
- **The plan's assumptions,** measured by `FxBenchTest` in step 1 and `GoldfishBenchTest` in step 4, with the numbers
  in the release notes:
  - at least 20,000 engine moves a second per core on the desk, and a fifth of that on a mid-range phone;
  - a reached hand costs hundreds of moves;
  - a no-line hand costs its whole space, which bricks keep small;
  - reduced hands cut the distinct searches to a fraction.
- **Targets:** 2,000 hands in under a minute on the desk; 500 in under a minute on a mid-range phone. If the targets
  are missed, the default hand counts come down, and the run says so in the sentence.
- **If copying `DuelState` dominates,** a search-only mutable table may stand in for it — only behind a test that it
  agrees with `DuelRules` on every action (the memo-against-old rule from 1.0.92).
- **Measured in step 1** (`FxBenchTest`, the walker's full reference table, one core of the sandbox shared with other
  builds, so the numbers move by a third between runs). The engine as agents (b) and (c) left it: about 4,900 a second
  on the bench's single pass (a 40-seed warm-up), about 9,200 warm. After the red team, same harness, same machine:
  about 5,600–5,900 on the single pass and 12,000–15,000 warm (median of five passes), with the rules fixes' own costs
  in it. `FxChain.moves` dominated; its fixes, each held by `FxMemoTest` to the same answers as worked out afresh: a
  book's unread effects worked out once (`ScriptBook.unread`), a table's restrictions and refusals worked out once
  (`FxTable.inForce`, `FxTable.refusal`), a book's passcodes resolved once (`ScriptBook.canonical`), the moves' material
  searches stopping at the first set that leaves a zone (`FxProcs.open`, `rituals`/`fusions` with `any`), and a material
  search bounded at `FxProcs.MOST_TRIED` sets (it walked billions when none fit). The 20,000 assumption is not yet met
  on the single pass; the next lever is `FxChain.play`'s per-action fold (`FxScribe.emit`: `DuelRules.apply` and
  `FxFold.read` for every action), roughly half of what is left.

---

## 5½. At the table: Shortcut (kai's decision)

kai: "the default key staying. Shortcut should be a dedicated choice when interacting with a card if it has one set."

**1. The default never changes.**
- A right-click, **Default** and the default key (Space, Enter on the focus, a double-tap) do exactly what they do
  today: `DuelVerbs.default`, untouched, whether or not the card has a written effect.
- Every manual verb stays as it is. A written effect never runs because a card was dragged or activated by hand.

**2. A verb of its own: "Shortcut"** (`DuelVerb.SHORTCUT`, label "Shortcut").
- **Named Shortcut, not "Shortcut"** (kai: "To differentiate between activate effect and to use a shortcut, let's rename it to just Shortcut"): Activate is the manual verb that puts a card's effect on the chain by hand; Shortcut is the written effect doing its moves for you. The two words never meet.
- **Offered only on a card that has a written effect** in the library (any status but `BROKEN`), by `DuelVerbs.offered`.
  Because it lives in `DuelVerbs`, every surface reads it from the one list:
  - the verb strip beside the selected card (`VerbStrip`);
  - the Enter menu on the focus;
  - the inspector's verbs;
  - a finger's verb strip and the held card's menu;
  - the command line;
  - Ai.
- **Its key is `U`** (`DeskAction.DUEL_SHORTCUT`, `DeskScope.DUEL`).
  - `U` is free in that scope today; `ctrl U` is Present's underline, in another scope.
  - `U` alone now runs the verb instead of opening Command mode, as any free letter did. Command mode keeps `/`,
    `Ctrl L` and every other free letter.
- **Its letter is `u`** (`DuelLetters.ROWS`: `Letter("u", DuelVerb.SHORTCUT, DeskAction.DUEL_SHORTCUT, "Shortcut")`),
  typed as `u h2` or `u h2 e2` (an effect by its id or its short name), with the typed word `shortcut`.
  - **The word `use` keeps its meaning.** `DuelCommand` already reads `use` as Activate, and saved combos' steps may
    hold it. A stored step must never change meaning.
- **The tests that hold it:**
  - `DuelLettersTest` (the letter against `DeskShortcuts`);
  - `DuelCoverage` (its typed form for each gesture);
  - `DuelVocabularySnapshotTest` (the tables it feeds, the snapshot updated in the same commit);
  - `CommandHelp` (F1).
- **Several effects** are listed by short name (`Effect.label`, else "Effect 1", "Effect 2").
  - What the engine finds legal now is offered.
  - What it does not is shown greyed with the rule ("once per turn: used"). The person can still do it by hand.
- **The person makes the choices.** A table `Chooser` asks each `Decision` (§2.5) on the page:
  - cards, by a picking strip like the ordering strip, from the cards the engine allows;
  - a zone, by the zone numbers;
  - yes or no, and which option.

  Esc cancels the whole use, and nothing is committed.
- **The choice window and targeting** (kai: "sometimes there are multiple shortcuts to do, and sometimes you'll need to
  designate a target(s) or declare an effect before resolving the effect, so a choice window or targeting system will be
  needed. Targeting should be able to include card(s) in the GY, banishment and field"). Every `Decision` the engine puts
  (§2.5) is answered in one place, **the choice window**, a strip over the table like the ordering strip, which names
  what is being chosen and why ("Target 1 face-up monster they control · for Bounce"), counts it ("1 of 2"), and
  confirms with Enter or its button. Esc steps back one choice, then cancels the whole Shortcut with nothing committed.
  - **Which Shortcut.** A card with several written effects asks first which, by short name, each with what it needs
    ("Search · no target", "Revive · 1 target in your GY") and greyed with its rule where it cannot be used now.
    Several cards with Shortcuts waiting at once (triggers that went off together, §2.3) are put as one ordered list:
    which to use, in which chain order, each optional one with Skip.
  - **Targeting reaches every place a target can be:** the field (both seats' Monster, Spell & Trap, Field and Extra
    Monster Zones), **the GY and banishment** (both seats', face-up banished only unless the effect says otherwise),
    the hand where an effect names it, and Xyz materials. The engine lists the legal cards (`Pick.from` × `Filter`, D.md
    §2.2); only those are lit and every other card is dimmed. A GY or banished pile holding a legal target opens as a
    row over the field (`DuelFrames.stripGrid`, as an open pile does today), its legal cards lit, so a target in a pile
    is chosen as directly as one on the field. Each chosen target wears its order number ("1", "2") and the existing
    target arrow (`DuelAction.Target`) from the activating card, so both players see what is targeted while the link
    stands.
  - **Declarations.** "Declare a card name / a Type / an Attribute / a Level" is a `Decision.Declare(kind, among)` (and an
    `Op.Declare` binding the answer for later steps): a searchable list for a name, a short list for the rest. **A name
    may be any card that exists** (§2.3½): the decision is `open`, its `among` only the names on the table the seat can
    see, and the window searches the whole pool, answering through `Chooser.name`; the engine accepts any pool card the
    effect lets be named (never a Token). Ai's `declare=` takes any pool card by its exact name (`Shortcuts.names`), and a
    name that is more than one card's, or a near miss, is asked back with its choices, never guessed.
  - **Every input reaches it**, as every duel gesture does (`DuelInput`, `DuelCoverage`): a click or a tap on a lit card;
    the arrows walking only the lit cards and Space choosing; typing its place in the table's notation (`gy3`, `ob2`,
    `om1`); a finger's tap; and Ai answering in the op (`pick=` / `target=gy3,ob2`) or asked back with the options listed.
  - **Targets are chosen at activation and checked again at resolution** (§2.3): a target that left or stopped matching
    is dropped, and the window says so in the log, never silently.
- **One undo group.** The cost, the targets and the chain link are one group. When nothing can respond, the
  resolution joins that same group, so one undo takes back the whole effect. That is a one-player table, or response
  windows off with the link resolved at once. When the other seat may respond, the resolution is its own group, made
  by **Resolve by Shortcut** (3).
- **Ordinary moves, marked as made by code.**
  - The moves land in the log as ordinary `DuelAction`s, committed through `DuelGame.act`.
  - Provenance keeps `by`, so the results still count players: the player who chose to use it, whether the person or
    Ai.
  - Each entry carries `DuelEntry.fx` with the card, the effect, the script's hash and `verified`. The log's line
    reads "Albaz · Search (Shortcut)", and "(unverified)" where it was.
- **Unverified effects are offered too, marked unverified** on every surface that lists them. The person decides at
  the table. Only the goldfish's search is limited to verified effects.
- **`FxState` at a hand-made table** is inferred from the log as tests infer it (§4.2). A use the inference cannot
  judge is offered with its rule's doubt said, never refused silently.

**3. The chain: Resolve by Shortcut.**
- When the newest link's card has a written effect, the chain menu (`ChainMenu`, Enter on a link) offers **Resolve as
  written** beside **Resolve** (by hand, as today, and still first).
- `Shift Q` (`DUEL_RESOLVE_ALL`) does exactly what it does today when no link has a written effect. When one does, it
  opens a two-choice strip:
  - **By hand** — Enter, as today;
  - **By Shortcut** — `U`, which resolves each written link through the engine and the others by hand, in order.
- `resolve by shortcut` and `resolve all by shortcut` are the typed forms.

**4. Later: cards that play themselves.**
- An optional duel setting, **"Cards with written effects play themselves"** (`DuelPrefs.autoEffects`, off by
  default, **verified effects only**).
- A card's activation by its default would then use its written effect.
- **Not part of the first cut:** it is the step after the goldfish (§10, later).

**5. Ai and the typed line use the same verb.**
- **Through `DuelVerbs`, the one list:**
  - `duel_moves` lists `u h2 e1` for Ai's own cards with written effects, beside the physical verbs;
  - `duel_act` and combos take `u …`;
  - `ComboRecorder` writes an engine-tagged use back as `u <card> <effect>`.
- **Ai's choices** are given in the op (`u h2 e1 pick=Albaz zone=m3`) or asked back with the options listed. Ai is held
  to `DuelReach` and its knowledge as for every move. Searching its own Deck shows the matching cards, as a player sees
  them in a search.
- **Not at a networked table in Phase D.** The verb is refused there in words: the guest's own engine would need cards
  its view does not hold.

**The stored shape** is `DuelEntry.fx` (§2.1), now with `verified`. **The settings** are none in the first cut; the
later switch is one new `DuelPrefs` field, described in `AiSettings` and sorted in `SyncedPrefs` when it comes.

---

## 6. Stored data

| Where | What | Sync | Backup | Held by |
|---|---|---|---|---|
| `<data>/effects/<passcode>.js` | Ai's source (mounted at `lib/effects/` in every world) | yes, newer | yes | — |
| `<data>/effects/<passcode>.json` | the compiled `CardScript`: `vocab`, its source's hash, the text's hash | yes, newer | yes | `OldDataTest`: a vocabulary-1 script reads; a newer one is kept unread and untouched |
| `<data>/effects/<passcode>.review.json` | the person's accepted warnings, with why | yes | yes | `OldDataTest` |
| `<data>/effects/asked.json` | the asked list (`FxAsks`): each card asked for, when, from where, its request, and what writing it cost (tokens, ≈ $) | yes, newer | yes | `OldDataTest` |
| `<data>/effects/decks/<deck>.tests.json` | the person's tests set aside (with why), pinned engine cards | yes | yes; deleted with the deck | `OldDataTest` |
| `<data>/effects/goldfish/<deck>.json` | the deck's targets and its kept `GoldfishResult`s (versioned) | yes | yes; deleted with the deck | `OldDataTest` |
| `<data>/fxcache/` | verdicts and test runs, recomputed | **never** (`InboundPath.DEVICE_FOLDERS`) | no | `SyncTest` |
| `DuelEntry.fx` | engine-made entries only: Shortcut, Resolve by Shortcut, the goldfish's lines (`verified` among it) | with the duel | with the duel | `OldDataTest`: an entry with and without it |
| `Combo.by`, `Combo.confirmed` | who wrote a combo; the person's confirmation | as combos | as combos | `OldDataTest`: a combo without them |
| `Proof.library` | the library fingerprint of a goldfish number | as the ledger | as the ledger | `OldDataTest`: a proof without it |

There is **no preference and no schema change.** The goldfish's settings are a run's inputs, kept with its result.
An older build reads every document and skips the new keys. Release notes name each step's changes (§10).

---

## 7. Security

- **Ai's code runs only inside Rhino**, under `JsRuntime`'s limits and a 5-second compile budget, and only to build
  data. Play, tests and the goldfish read that data through Kotlin. Nothing Ai wrote runs in them.
- **Data is untrusted input**, whether a script made it or it arrived by sync.
  - It is decoded leniently and capped: 64 KB a script, 2,000 scripts, and every count clamped.
  - It is legality-checked before use.
  - The engine has its own bounds: 1,000 actions a move, a chain of at most 32 links, at most 64 triggers gathered at
    once, steps nested at most 16 deep and filters and conditions 32 (`FxSteps.MOST_DEPTH`, `FxWalk.DEEPEST`: a script
    nested deeper is never read, and is checked without recursing past that), at most `FxProcs.MOST_TRIED` (20,000)
    candidate sets in one material search, picks clamped to 60, life points and Levels never wrapping round
    (`FxRedTeamTest`).
  - A loop detector stops a turn once the same table and `FxState` recur through triggers alone. A script that
    triggers itself forever ends with the verdict "loop", never a hang. **Step 1 bounds each move** (two cards that set
    each other off forever make one bounded move at a time, `FxRedTeamTest.triggersThatSetEachOtherOff…`); the
    detector itself is the goldfish's driver's, in step 4.
- **Ai writes only what the person asked for** (§3.1).
  - Core refuses Ai's write to `lib/effects/` for a card not on the asked list.
  - Only the person's click adds to that list: `fx_request` offers, and never adds.
  - Whatever a session costs is said before the go.
- **Verdicts never travel.** A planted "verified" cannot arrive by sync or in a backup; each device recomputes.
- **Only the person sets tests aside and accepts warnings.** Core refuses either when Ai is the author (as
  `AiSettings.GUARDS` keeps safeguards the person's), and no tool offers them.
- **Ai's own moves are never tests** (§4.1). Its records count only once the person confirms them.
- **Outside text.**
  - Card text is the pool's.
  - Rulings Ai reads while writing come through `Untrusted.wrap`, as now.
  - A script's `notes` and `unsupported` are Ai's own words, read back to Ai as its own memory is.
  - Phase D imports no scripts from outside: there is no format for sharing them.
- **Hidden information.**
  - Tests run on whole logs, locally.
  - Everything shown to Ai (failures, `fx_state`) is written through the tested seat's eyes.
  - A networked duel's replay gives tests only for the host's own library decks; the guest's hidden cards stay
    unnamed.
  - The engine plays at no networked table in Phase D: Shortcut is refused there (§5½).
  - Ai's use of the verb is held to `DuelReach` and its knowledge, like any of its moves.
- **Licence.**
  - No ygopro or EDOPro scripts are bundled, linked or consulted, and the vocabulary uses none of their identifiers.
  - The skill tells Ai to write from the card's meaning and rulings, never from scripts it recalls.
  - Fixtures commit facts (names, stats) and our own scripts, never card text.

---

## 8. The evaluation set

Measured before and after each step. Those numbers go in the release notes.

| Part | What | Graded by | Runs |
|---|---|---|---|
| **A. Rulings** | about 40 cases with known answers on fictional reference cards: spell speeds, SEGOC order, missing the timing, costs against effects, targets gone at resolution, once-per-turn by name across copies and printings, a negated activation still using it, Extra Monster Zone and linked zones, inherent summons and "must first be" | code: the table and `FxState` after the case | CI, every push |
| **B. Authoring** | Ai writes scripts for the fixture deck's engine cards from their text, `fx_check` in hand, at most 3 repair rounds | verification against the committed record: cards verified on the first write, after repairs, tokens per card | Test scores, per connection, on request; baselines are an empty library (0 %) and the reference scripts (100 %) |
| **C. Planted errors** | reference scripts, each with one mistake planted: once-per-turn missing, a filter too broad, `IF` for `WHEN`, a cost missing, a step too many, the wrong place, the wrong speed | how many verification catches, and how many correct scripts it wrongly fails | CI; the catch rate is a number, as the fact-checker's is |
| **D. The goldfish** | toy decks whose reach is exact by hypergeometry (a starter that searches the second piece: reach is "either card in hand"), and the fixture deck's runs | toy decks: inside the 99.9 % interval of the exact number, the instruments' "check ok"; the fixture deck: its numbers recorded as the baseline later changes are measured against | CI |

**The fixture deck** is committed:
- the cards' facts;
- our reference scripts;
- its record: about six combos and two replays, made by a person.

It is the deck CI verifies end to end (`FxFixtureTest`). For kai's real deck, see §11, decision 2.

---

## 9. The red team

Before each step ships, through these lenses. Each finding is held by a test.

| Lens | The attack | Held by |
|---|---|---|
| **Gaming the verifier** | a script that passes by doing more than the card does (broad filters, no once-per-turn); tests narrowed or set aside; records from Ai's own play | refusal tests from the text (§4.3); person-only set-asides; provenance; part C's catch rate; at least one passing episode per effect |
| **The rules** | speeds, SEGOC, missing the timing, costs against effects, targets at resolution, once-per-turn under negation, Extra Deck zones | part A; any case found wrong becomes a new case |
| **The record** | sloppy manual logs (a chain never resolved, a search dragged with no activation, moves before resolve), logs with no provenance or tags, alternate artworks, errata | episode tests on hand-made fixture logs: such logs read as unexplained or ambiguous, never as a pass |
| **Statistics** | seeds across threads and devices; a reduced hand that was not equivalent; an interval that is too narrow; the undecided share hidden; skeletons miscounted | toy decks against exact odds; memo against no memo; the same seed on the JVM and in an Android unit test; the sentence always states undecided |
| **The table's default** | a written effect running where the person meant a manual move: right-click, Default, Space, `Shift Q`, or a saved combo's `use` step | `DuelShortcutVerbTest`'s rows for every card in every place; `use` pinned to Activate; `Shift Q` unchanged with no written link |
| **Writing without a go** | Ai writing a card nobody asked for: through `world_write`, a helper file, a request it answers itself, or by reading a chat message as a go | the asked list enforced in core (`FxAsksTest`); `fx_request` cannot add; helpers (`_*.js`) compile into no card of their own |
| **Unknown cards** | a number that quietly counts an unknown card as working, or as a brick; a recorded line that needs one reported as 0 % | the inert rules (§5.5) tested on a deck with unknown cards; not-computable runs refused by name; the headline's "at least" and its two shares |
| **Performance and denial** | a trigger loop; a pick of a whole Deck; a phone's heat; a run that will not stop | bounds tests; the loop verdict; cancellation tests; the benchmarks |
| **Hidden information** | failure text naming a hidden card; a guest's decklist reached through a networked replay | failures read through each seat (`DuelSight`); library decks only |
| **Evidence** | a goldfish number written without its run; a `world_run` passed off as one; a number still marked checked after a script changed | `Evidence` tests for `lineClaims`; `Ledger.staleAgainst` with the library fingerprint |

---

## 10. The steps

Four steps, as the roadmap orders them: vocabulary, authoring, tests, goldfish. Each step is one or two releases,
shipped on both tracks (`:core` and the world are on the tablet too). The release numbers are the roadmap's 1.2.x.

Each step splits across agents in worktrees, with a red team beside them, as in 1.0.97–1.0.98.

### Step 1: the vocabulary and the engine (1.2.0)

**Builds:**
- `core/duel/effects`: the model and codec (§2.2), `FxRules`, `FxChain`, the steps, `FxState` and `FxFold`, `FxFacts`,
  `ScriptBook`, choices (§2.5);
- `DuelEntry.fx`;
- reference scripts in commonTest, for fictional cards in the reserved passcode range 900000000–900000999 (`FxVocab.RESERVED`: nine digits, so no Konami passcode can land there);
- `PuzzleReferee` reading its summon rules from `FxRules`, and the puzzle Spells as scripts (`PuzzleEffect` deleted);
- the verb's core (§5½), never offered yet because no library exists:
  - `DuelVerb.SHORTCUT` in `DuelVerbs.offered` and `actions` with a `Chooser`;
  - the letter `u` and the word `shortcut` in `DuelLetters` and `DuelCommand`, with `use` still Activate;
  - `DeskAction.DUEL_SHORTCUT` on `U`;
  - `resolve by shortcut` and `resolve all by shortcut`;
  - `ComboRecorder` writing `u …`;
  - `DuelMoves` listing it;
- part A.

**Nothing new is visible yet.** The puzzle Spells now run on scripts.

**Tests:**
- `FxVocabularyTest`: each step's operation, against the table;
- `FxChainTest`: speeds, SEGOC, missing the timing, targets at resolution, a negated link;
- `FxOptTest`;
- `FxProcTest`: Link with its rating sum and arrows, Synchro, Xyz, Fusion and Ritual by effect, inherent summons,
  "must first be";
- `FxRestrictionTest`;
- `FxDeterminismTest`;
- `FxPhysicsTest`: random walks over the reference scripts; every action list the engine emits is accepted by
  `DuelRules.applyAll`;
- `FxFoldTest`: a tagged log's fold equals the engine's own `FxState`;
- `PuzzleTest`: unchanged baselines;
- `DuelShortcutVerbTest`:
  - the default verb of every card in every place is unchanged, with or without a script;
  - `use` still parses as Activate;
  - `u` uses a reference card's effect as one undo group, with `fx` and provenance on every entry;
  - an illegal use is listed with its rule;
- `DuelLettersTest`, `DuelCoverage` and `DuelVocabularySnapshotTest`, updated with the verb;
- `OldDataTest`;
- `FxBenchTest`.

**Done when:** all of these hold, and part A passes in full.

**Agents:**
- (a) the model, codec, `FxRules` and procedures;
- (b) the chain, triggers and timing;
- (c) the steps, choices and the fold;
- (d) the verb's core in `DuelVerbs`, `DuelLetters`, `DuelCommand` and `DuelMoves`, against (c)'s `Chooser`;
- a red team on the rules.

(a) lands its types first, in half a day; (b) and (c) meet at the `Step` executor's interface.

### Step 2: authoring (1.2.1)

**Builds:**
- `ygo.fx` in the prelude, with a test that every vocabulary word has a builder (as R17 holds `ygo.tools`);
- `FxCompile`, `FxCheck`, `FxLints`, `FxWords`;
- the library and its mount at `lib/effects/`;
- sync, backups and `fxcache` kept on the device;
- the `Effects` holder (`neue/effects`, lazy, `h.effects`, like `Shootouts`) and the Effects pane;
- `fx_state` and `fx_check` (checking only, at this step);
- **asking** (§3.1):
  - `FxAsks` and its enforcement;
  - `fx_request` and the chat's request card;
  - **Write its effect** in the card viewer and the inspector;
  - **Write these** in the Effects pane, and **Write this combo's cards**;
  - `FxSuggest`;
  - the cost before (`Prices.estimate`) and after (`Prices.cost`);
- **at the table** (§5½), on every surface, as soon as a card has a written effect (marked unverified until step 3):
  - **Shortcut** in the verb strip, the Enter menu, the inspector and a finger's menus, and on `U`;
  - the table `Chooser`'s prompts: **the choice window** (which Shortcut, several waiting in order, cards, targets on
    the field and in both seats' GY and banishment with the piles opened as rows, a zone, yes/no, an option, a
    declaration), by mouse, keys, typing, finger and Ai;
  - **Resolve by Shortcut** in `ChainMenu` and on `Shift Q`'s strip;
  - the log's "(Shortcut)";
  - the verb refused at a networked table;
- `AiEffects` and the `effects-author` skill.

**Tests:**
- `FxCompileTest` (jvmTest, real Rhino): the builder's output decodes; a function placed in the data is refused; the
  budget holds;
- `FxCheckTest`: each error and each lint;
- `FxWordsTest`;
- `EffectsTest` (neue: ask → go → write → compile → library → pane);
- `FxAsksTest`: a write without a go is refused; `fx_request` adds nothing; a go from each place adds its cards;
- `FxSuggestTest`; `FxCostTest` (the estimate and the actual, an unpriced connection said in words);
- `DuelEffectTableTest` (neue): `U` and the strip use a written effect with the person's choices, Esc commits
  nothing, one undo takes it back, Default and right-click unchanged, `Shift Q` unchanged with no written link;
  studio shots of the strip and the prompts at desk and phone width;
- `OldDataTest`, `SyncTest`, `AiToolsTest`.

**Done when:**
- asked for the fixture deck's engine, Ai writes exactly those cards, and each passes the legality pass;
- a card verified for one deck is shown as reused, at no cost, when another deck asks for it;
- an alternate artwork reads the same script;
- a script synced in is checked again;
- the person reads each one in words;
- at the table, a written card's effect is used by key, mouse and finger, and its default is what it was.

**Agents:**
- (a) the builder, compiler and checker (core and jvm);
- (b) the library, sync, backups and pane (neue);
- (c) the lints and the words;
- (d) asking, suggestions and cost: the four places in the interface, `FxAsks`, `fx_request`, `FxSuggest`;
- (e) the table: Shortcut's surfaces, the `Chooser` prompts, Resolve by Shortcut.

### Step 3: tests and coverage (1.2.2, and 1.2.3 if needed)

**Builds:**
- sources and provenance (§4.1), `Combo.by` and `confirmed`;
- `FxEpisodes`, `RecordMatcher`, `FxTestRun`, the mutants, verdicts and `FxCoverage`;
- coverage on the pane, the guide's line and `fx_state`;
- the person's set-asides and acceptances;
- the fixture record and `FxFixtureTest` in CI;
- part C.

**Tests:**
- episode tests on hand-made sloppy logs;
- matcher tests (ambiguous, unexplained, foreign, blocked);
- verdict-binding tests (a script, the text, or the engine changed → re-run);
- refusal tests;
- `OldDataTest`.

**Done when:**
- the fixture deck's engine is verified against its committed record;
- part C catches at least 90 % of planted errors and wrongly fails no correct script;
- coverage of kai's own main deck is shown.

**Agents:**
- (a) episodes, matcher and runner;
- (b) mutants and part C;
- (c) coverage, the guide line and the person's choices.

### Step 4: the goldfish (1.2.4, and 1.2.5 if needed)

**Builds:**
- `EndBoard` and `fx_target`;
- the hands and reduced hands, `GoldfishSearch` and `GoldfishResult`;
- unknown cards (§5.5): inert in the search, not-computable targets and lines, the "at least" headline;
- the `goldfish` instrument;
- `Proof.library`, staleness and `Evidence.lineClaims`;
- hands opened as replays;
- the goldfish on the Effects pane and as a world board;
- part D.

**Tests:**
- `GoldfishTest`: toy decks against exact odds; memo against no memo; threads do not change the counts;
- `GoldfishUnknownTest`:
  - an unknown card is never activated, summoned or used as material;
  - known effects still move it;
  - a target or a recorded line that needs one is not computable;
  - a toy deck with one unknown starter reads "at least", with the exact share of hands holding it;
- `GoldfishSeedTest`: the JVM and an Android unit test agree;
- `GoldfishBenchTest`;
- `EvidenceTest` (`lineClaims`, library staleness);
- `OldDataTest`;
- the red team on the whole phase.

**Done when:**
- "this line gets there N % of the time", with its seed, reads for the fixture deck and for kai's deck;
- the number is in the guide with its proof, and goes stale when a script it used changes;
- the benchmarks are in the release notes.

**Agents:**
- (a) the search, the reduction and the benchmark;
- (b) targets, results, the ledger and the instrument;
- (c) the pane and the replay opening.

**Stored-data changes, named in each step's notes:**
- step 1: `DuelEntry.fx`;
- step 2: `<data>/effects/` (the asked list among it) and the device-only `<data>/fxcache/`;
- step 3: `Combo.by` and `Combo.confirmed`, `effects/decks/`;
- step 4: `effects/goldfish/` and `Proof.library`.

None of them changes a preference or the schema.

### Later: cards that play themselves (after step 4)

**Builds:** `DuelPrefs.autoEffects` (off by default; described in `AiSettings`, sorted in `SyncedPrefs`). With it on,
a card's activation by its default uses its written effect, **verified effects only**. Everything else stays as it
is.

**Done when:**
- with the switch off, `DuelShortcutVerbTest`'s default rows are unchanged;
- with it on, only verified effects play themselves, and an unverified one is offered by **Shortcut** alone.

### Out of scope for Phase D

- Everything §2.6 leaves out of the vocabulary.
- **The opponent.** The goldfish has none. "Through an Ash" belongs to Phase E, though tests already run the other
  seat's scripted cards.
- **The engine beyond Shortcut.**
  - At a networked table.
  - Choosing Ai's moves by search: Ai uses the verb as a player does, and never searches with it.
  - The "play themselves" switch (§5½ 4), which is the step after the goldfish.

  These are Phase E's, or the later step.
- **Puzzles with effect monsters:** Phase E's evaluation set, built on this engine.
- **Which line is better,** and scoring end boards: Phases E and G. The goldfish answers "can it" for a target.
- **Sharing scripts with others,** a public library, and Python effects.

---

## 11. Decisions for kai

**Decided.** **Who writes effects, and for which cards.**
- The roadmap's default was "Ai writes them, tests verify them, you see coverage", with Ai writing every card.
- kai: "I think effects as code should be done by the Ai for cards the user wants because otherwise the whole
  cardbase would need to be done and it would take too long."
- So **Ai writes a card's effect only on the person's go**, for a card, a deck's main engine, a group or a combo
  (§3.1). There is never a pass over the pool.
- The app may suggest, and never writes without the go.
- A card verified once serves every deck that holds it. Coverage is per deck, and the cost is said per card, in
  tokens and in dollars.
- What the roadmap's default kept still stands: tests verify, the person sees coverage, and nothing unverified is
  used by the search (unknown cards are inert, §5.5).

**Decided.** **How a written effect is used at the table** (§5½).
- kai: "the default key staying. Shortcut should be a dedicated choice when interacting with a card if it has one
  set."
- The default action never changes: right-click, Default and the default key do what they do today.
- A dedicated verb, **Shortcut** (`U`, the letter `u`), is offered only on a card with a written effect:
  - unverified effects are offered too, marked so;
  - the person makes the choices;
  - the whole effect is one undo group, logged as ordinary moves tagged as made by code.
- Resolving a written link offers **Resolve by Shortcut** in `ChainMenu` and on `Shift Q`.
- Ai and the typed line reach the same verb through `DuelVerbs`.
- **Cards that play themselves** (a switch, off by default, verified effects only) is a later step.

This replaces the question this note first asked kai: whether the Duel page should resolve effects for you in
Phase D.

**Still open.** Each has the default this plan assumes until kai says otherwise.

| # | Decision | Default assumed |
|---|---|---|
| 1 | **One library by card, or a library per deck?** The roadmap says per deck. | **One, by card** (§3.3): a card's effect is the same in every deck, an alternate artwork shares it, and every deck's record tests it. Authoring, tests and coverage stay per deck. |
| 2 | **Which deck proves "a deck's main engine is verified"?** It needs a record a person made: about six lines and a couple of duels. | **Kai's main deck** (the library's default) for the live result, its lines recorded by kai or recorded by Ai and confirmed by kai. A fixture deck of our own for CI. |
| 3 | **Lines Ai recorded, and combos saved before this release (which have no author):** do they count as tests? | **Ai's lines count only once kai confirms each** (one click). Older combos count, labelled "author unknown", and can be set aside with one click. |
