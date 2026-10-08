# Phase M: Gameplay Mapper

**kai's brief** (2026-10):
- "A new mode, called Gameplay Mapper."
- "Solving lines through algorithmic calculation and runs, machine learning."
- "Learn combos through machine learning."
- "Stress test against disruption (going first against handtraps or going into a board with disruptions)."
- "Eventually it'll be able to solve gamestates like puzzles."

**What it builds on.** Phase D's goldfish already does the hard part, for one question:
- `FxEngine` is a deterministic forward model. `moves(table, seat)` lists what a seat may start, and `play(...)` makes a
  move, its choices answered by a `Chooser`. It holds the chain, priority, SEGOC and once-per-turn, and it already runs
  **both** seats (`FxChain.next`), although the goldfish uses only one (`DuelState.solo`).
- `GoldfishSearch` runs depth-first over those moves, with a transposition table (`TableKey`), collapsed choices, move
  ordering and a budget. It answers **"can this hand reach this `EndBoard`"** and **stops at the first line**.
- `Goldfish.run` deals seeded hands (`DuelRandom.forRoll(seed, k)`), reduces them to their engine part, and gives the same
  counts on any device with any number of threads. `GoldfishResult` carries its proof into the ledger
  (`Proof.library`, `Evidence.lineClaims`).
- `FxTrust` decides which scripts are used. A card with no trusted script is **inert**, so every number is a lower bound
  and says so.
- Effects are written only for the cards the person asks for (D.md §3.1, `FxAsks`).
- The duel puzzles (`core/ai/eval/Puzzles.kt`, C.md §5) are positions with a goal, a referee and a baseline. They are
  vanilla-only today because there was no effect engine.
- The Shootout's model (`core/shootout/model`, `math/Logistic.kt`) is a tested, on-device fit of ratings with ranges from
  judged comparisons. It is the pattern for the small models this phase needs.

**What is missing.**
- The goldfish stops at one line. Nothing maps **every** line from a hand, or says which cards start the deck.
- Nothing plays the other seat. "Through an Ash" is out of Phase D's scope (D.md, "Out of scope").
- Nothing ranks one end board above another. A target is met or not.
- Nothing learns. The move ordering is hand-written, and combos are only what a person records.
- Puzzles cannot hold effect monsters.

**The design in one paragraph.**
- **Gameplay Mapper** is page `10`: a deck, a hand or a position goes in, and **a map** comes out. The map is every line
  the trusted effects can play, merged where lines meet into a graph of positions, ending in end boards that are
  ranked.
- **Stress tests** put interruptions in the other seat's hand and let the engine play them at the worst moment for you.
  That is a minimax over response windows. It reports what each line falls back to, where the choke points are, and the
  line that holds up best.
- **Learning** happens in two places, and neither is ever allowed to change a verdict. A **move prior** learned from the
  maps makes the search faster. A **board value** learned from kai's rankings and from played duels ranks end boards.
  **Combos are mined** from the maps as named routes and starter sets, and are saved as `Combo`s only when kai confirms
  them.
- **Puzzles** are positions with a goal. The same solver plays both seats with full information, so "no solution" is a
  proof when the search is complete.
- It is all `:core`, pure Kotlin, deterministic and tested. The page and Ai's tools follow.

This is the front half of the roadmap's Phase E ("Search: Ai plays"). It builds the solver that Phase E's player then
uses at the table. Whether to rename Phase E or keep M beside it is decision 1 (§10).

---

## 1. What "done" means

| kai's words | Exactly |
|---|---|
| "Solving lines through algorithmic calculation and runs" | For a deck, a hand and a seat order, `MapSearch` returns every distinct end board the trusted effects can reach in one turn, each with its lines. It is exhaustive within its bounds and says when it was not. Run over seeded hands, it gives the share of hands reaching each board tier, with Wilson ranges, the same on every device. |
| "Machine learning" | A move prior that cuts the median engine moves needed to map a hand by at least half on held-out seeds, and never changes a map's set of end boards (a test holds it). A board value that agrees with kai's own rankings on held-out pairs, with its range shown. |
| "Learn combos" | Routes mined from the maps: named skeletons, the cards each needs, and the starter table (every one- and two-card engine hand that reaches each tier). Each is offered as a `Combo` and saved only on kai's confirmation. |
| "Stress test against disruption" | For a hand and a set of interruptions held by the other seat, `StressSearch` returns the best end board **you can guarantee**, the line that guarantees it, and where the opponent should spend each card. Over seeded hands: "through Ash, 64 % still reach tier 2". |
| "Going into a board with disruptions" | Going second, the other seat's end board is a position whose face-up and set cards play their trusted scripts. The question is the same: what can you guarantee. |
| "Solve gamestates like puzzles" | A `Position` and a `Goal` solved by the same search with both seats known. The answer is a solution line that opens as a replay, or "no solution" when the search covered everything. |

Every number carries its proof into the guide, as the goldfish's numbers already do.

---

## 2. The map (`core/duel/mapper`)

### 2.1 What it answers

The goldfish asks "can it". The mapper asks **"what are all the places this hand can go, and how"**.

- **Input:** a deck, a hand (or a seed and a hand count), going first or second, the targets ranked into tiers (§2.3),
  the bounds.
- **Output:** a `LineMap`.

```kotlin
@Serializable
data class LineMap(
    val version: Int = 1,
    val deck: String,              // Ledger.fingerprint
    val library: String,           // FxTrust's fingerprint of the scripts used
    val prior: String?,            // the move prior's fingerprint, when one ordered the search (§4.1)
    val hand: List<Int>,           // canonical passcodes, reduced (D.md §5.3)
    val first: Boolean,
    val nodes: List<MapNode>,      // positions, merged by TableKey
    val edges: List<MapEdge>,      // a move and its answers, from one node to the next
    val ends: List<MapEnd>,        // the distinct end boards reached (read after the End Phase), each with its tier and value
    val complete: Boolean,         // false: the budget ran out, and the map says how much it covered
    val moves: Int,                // engine moves spent
)
```

### 2.2 The search (`MapSearch`)

It is `GoldfishSearch` without the early stop.
- **Depth first with the same transposition table.** A position reached again is a merge, not a new subtree, so the map
  is a graph (a DAG within one turn), not a tree. This is what keeps it small: most lines are reorderings of each other.
- **End boards are deduplicated** by a board key. That is `TableKey` read only over what an end board is: field, set
  cards, hand, GY and banished, by card identity, ignoring order and uids.
- **Dominance pruning.** A position whose resources are a subset of another explored position's, at the same point in
  the turn and with the same once-per-turn flags spent or fewer, is skipped. This is safe only where the scripts are
  monotone in resources, so it is opt-in per run and a test compares it against the unpruned map.
- **Bounds:** the goldfish's 60 moves and a budget per hand, which is larger by default (a map costs more than a first
  line). A run that hits its budget is **incomplete**, never "no line".
- **The best line to each end** is kept, which is the shortest, then the one that keeps more in hand. The map's other
  edges are kept for the page and for learning.

### 2.3 Ranking end boards

A target today is met or not. The mapper needs an order.

- **Tiers.** The deck's `EndBoard`s, ranked by kai: tier 1 is the full board, tier 2 the fallback, and so on. An end board
  takes the tier of the best target it meets. Nothing new is stored: tiers are an order over the existing targets
  (`effects/goldfish/` keeps them), and a deck with no targets gets a starting set proposed by Ai and marked as Ai's
  (`EndBoard.by`).
- **Within a tier,** boards are ordered by a **board value** (§4.2). Until it is learned, the value is the interruption
  count (`BoardCond.Interruptions`, counted from the scripts), then cards kept in hand.
- **"Best"** always means tier first, then value. A line's worth is its end board's.

### 2.4 Over many hands

`Mapper.run` is `Goldfish.run`'s twin: the same dealing, the same reduced hands (D.md §5.3) and the same thread-independent
counting.

> Of 2,000 hands (seed 7, going first): tier 1 in at least 41.2 % (39.1–43.4), tier 2 in at least 22.0 %, nothing ranked in
> 30.9 %, incomplete in 5.9 %. 18 % of hands held a card with no trusted effect, played as inert.

### 2.5 Starters (the deck's map, not one hand's)

The question players actually ask is "which cards start the combo". The **starter table** answers it directly.
- For every engine card alone, and for every pair of engine cards, the rest of the hand blanks: which tier does it reach?
- Engine cards are the ones `FxTrust` uses and the reduction does not blank. With 15 engine cards that is 15 + 105 maps,
  each small because the hand is small.
- Output: "1-card starters: A, B (tier 1), C (tier 2). 2-card: D + E reaches tier 1, neither alone." Pairs that need
  each other are the deck's real **extenders**.
- Combined with the deck's ratios, this gives the exact chance of opening a starter (the hypergeometric sum `hand_odds`
  already computes), which is checked against the sampled rate in §2.4.

---

## 3. Stress tests (`core/duel/mapper/stress`)

### 3.1 The other seat

The table becomes two seats (`solo = false`). The other seat holds:
- **going first:** a hand of interruptions, for example Ash Blossom, Effect Veiler and Nibiru. The rest of its hand is
  blanks, and its field is empty;
- **going second:** an end board, its own or one from the field (§3.4).

Its cards play **their trusted scripts only**. An interruption with no trusted script cannot be used in a test, and the
run says which ones are missing, with **Write these**. That is kai's rule from D.md §3.1, and the app never writes them by
itself.

### 3.2 The search (`StressSearch`)

A two-player game over one turn: **you maximise, they minimise.**
- At each of your moves, `FxEngine.moves(t, you)`.
- At each response window (the chain's priority, a trigger's chance), the other seat chooses: pass, or one of its legal
  activations with each of its choices. The branching is small, because an interruption is legal only where its condition
  holds (Ash only on a link that `Includes` a search, a Deck send or a Special Summon).
- The value of a leaf is its end board's (§2.3).
- **Alpha-beta pruning** with the transposition table, keyed by the table and the other seat's unused cards.
- **The other seat knows your hand** in this test (worst case). The realistic version, where it guesses, is Phase E's
  IS-MCTS and is out of this phase.

### 3.3 What it reports

For one hand against one set of interruptions:
- **The guarantee:** the best tier you can guarantee, and **the line that guarantees it**. This is often not the
  unopposed best line. Leading with a bait, or keeping a card until Ash is gone, shows up here.
- **The choke points:** where the opponent's best play spends each interruption ("Ash on Aluber's search").
- **The fallbacks:** for each choke point, where your best continuation ends.
- **The cost of playing around it:** the unopposed best against the guaranteed line's unopposed result, which is what
  you give up by playing around it.

Over many hands, for each interruption and each pair of them:

> Through Ash (going first, 2,000 hands, seed 7): tier 1 guaranteed in at least 18.4 %, tier 2 in 37.0 %. Ash is best
> used on Aluber in 52 % of the hands it stops. The line that plays around it costs tier 1 in 6 % of hands where it was
> not needed.

### 3.4 Which interruptions, and how often

- **Named:** kai picks the cards (Ash, Imperm, Nibiru, Droll …), one or two at a time. This is the default view.
- **From the field:** the Format web's decks (`DeckWeb`, `FieldBuilder`'s strategies) say which interruptions the field
  plays and how many. The other seat's hand is dealt from a field deck's list, so the result is **weighted by the field**:
  "against the expected field, tier 1 survives 47 % of the time". This is the roadmap's "determinised search sampled from
  the field".
- **Going second:** an end board from the field deck's own map (its tier 1, if its scripts are written), or a board kai
  sets up on the Duel page, becomes the other seat's position.

### 3.5 What the vocabulary must grow (D.md §2.6)

The common interruptions need these, checked one card at a time in this step's first commit:

| Card | Needs | In the first cut? |
|---|---|---|
| Ash Blossom, Called by the Grave and other "negate the activation of an effect that includes …" | `Cond.Newest(includes = …)` and `Op.Negate` | Yes |
| Effect Veiler, Infinite Impermanence | negating a face-up monster's effects until the end of the turn | **No**: lingering negation is left out |
| Nibiru | a count of the turn's Special Summons, and tributing the other seat's monsters | **No** |
| Droll & Lock Bird | a restriction on the other seat | To check (`Op.Restrict`'s seat) |
| Maxx "C" | a trigger on the other seat's Special Summon that draws | Probably, but its effect is a tax, not a stop, so the value needs a word for cards the other seat gained (§4.2) |
| Battle (OTK, lethal puzzles) | the Battle Phase and damage | **No**: Phase D left battle out |

The vocabulary grows a family at a time in `:core` with tests, never by a JavaScript callback (D.md §2.6).

---

## 4. Learning

**The rule over all of it: a model may change how fast an answer comes, or how boards are ranked within a tier. It may
never change whether a hand reaches a target.** Correctness comes from the engine and the search. A pruned or truncated
search says "incomplete", never "no line".

Everything is small, on-device, pure Kotlin (`:core`, so it also runs on the phone and in the browser), deterministic
from a seed, and fingerprinted into the proof. No training framework, no network, and no language model inside the
search loop. This follows the Shootout's pattern.

### 4.1 The move prior (search faster)

- **What it predicts:** at a position, which moves lie on lines to the best end board.
- **Features:** the move's kind (activation, Normal Summon, procedure), its card, the effect's `Includes`, the cards in
  hand, field and GY as bags (hashed), and the step count. That is a few thousand sparse features per deck.
- **Model:** a per-deck linear softmax over the legal moves (multinomial logistic, extending `math/Logistic.kt`), trained
  by the same deterministic fit the Shootout uses.
- **Training data:** the maps themselves. Moves on a best line are positives, and their siblings at the same node are
  negatives. Data also comes from kai's own recorded combos and replays, and DuelingBook replays converted by `DbConvert`
  where the scripts can follow them.
- **The loop (expert iteration):** map hands with the current prior, refit the prior on what was found, and map again.
  Each round is measured on **held-out seeds**: engine moves to complete a map, and the share of hands left incomplete at
  a fixed budget.
- **Its only use is ordering**, so the first move tried is likely the right one, and so a budget covers more. It never
  prunes a complete search.

### 4.2 The board value (rank within a tier)

- **What it predicts:** how good an end board is to sit on, going into the other player's turn.
- **First version:** kai's own judgement, asked as comparisons, two boards side by side ("which would you rather pass
  with?"). This is the Shootout's comparison trial applied to boards, and is fitted the same way (pairwise logistic over
  board features: interruptions by kind, cards in hand, bodies, GY resources). It shows ranges, and every value opens the
  comparisons behind it.
- **Later:** outcomes. An Ai vs Ai match (`core/duel/match`), started from a mapped end board against a field deck, says
  how often the board won. This needs Phase E's player, so it comes after this phase.
- **Ai may propose rankings, never decide them.** Its comparisons are a judge of their own, as in the Shootout (S.md
  §6½), so they never move kai's numbers.

### 4.3 Combos, learned (routes and starters)

"Learn combos" means **mining the maps for the lines the deck keeps using**:
- **Routes:** skeletons (the goldfish's "Aluber → Branded Fusion → Mirrorjade") counted across every hand's map. Common
  sub-sequences are merged into a prefix tree, so shared openings and the branches after them show.
- **What a route needs:** the smallest card sets that start it, read off the starter table (§2.5).
- **Named:** by its starter and end board ("Aluber into Mirrorjade"). Ai may suggest a better name.
- **Offered, never saved by itself:** a route becomes a `Combo` (`ComboRecorder`'s shape, steps by card name) only when
  kai confirms it. A confirmed combo is then a recorded plan the goldfish can test ("this line"), and a line Ai can play
  at the table (`ComboRunner`).
- **Compared with what kai already has:** a mined route that matches a recorded combo is marked as matching. A route that
  reaches a tier no recorded combo reaches is flagged as **new**, and a recorded combo the engine cannot play is flagged
  too. That last one is usually a wrong script, so the flag offers **Repair it**.

---

## 5. Puzzles (`core/duel/mapper/puzzle`)

- **A position:** both seats' hands, fields, GYs, banished cards and Extra Decks, life points, the phase, and once-per-turn
  flags spent. The C.md puzzles' `PuzzleSetup` grows into it, so the 17 existing puzzles still load.
- **A goal:** an `EndBoard`, or a condition on the other seat ("their field is empty", "they have no negates left"), and
  later life points, once battle is in the vocabulary.
- **Solved** by `StressSearch` with both seats' cards known: perfect information. The answer is a solution line, opened as
  an unsaved replay (`Duels.openGame`), or **"no solution"**, which is a proof when the search was complete.
- **Where puzzles come from:**
  - **"Solve from here"** on the Duel page or in a replay: the table at that moment becomes a position;
  - a DuelingBook replay at a chosen move (`DbConvert`);
  - set up by hand on the Duel page;
  - the existing puzzle set, then new ones with effect monsters, added to Test scores so Ai's own play is measured
    against the solver.
- **Hidden information** (their set cards unknown, their hand unknown) is out of scope here. A position with unknowns is
  determinised from the field (§3.4) and says so. Real play under uncertainty is Phase E's IS-MCTS.

---

## 6. The page: `10` Gameplay Mapper

A first sketch, to be replaced by mockups kai picks from (decision 3):
- **Left: the deck's starter table** (§2.5): one-card and two-card starters with the tier each reaches, and the odds of
  opening each.
- **Middle: the map.** A graph drawn in ink. Nodes are positions, shown as the card that moved; edges are moves; end
  boards are at the right, ranked by tier. A chosen line is drawn heavy. A choke point is marked where the stress test
  found one. Any node opens as a replay.
- **Right: the inspector.** The chosen end board as card art, its tier and value, the lines that reach it, and its
  fallbacks under each interruption.
- **Tabs over the map:** Map, Stress (pick interruptions, or "the field"), Routes (the mined combos to confirm), Puzzle.
- Master UI: paper and ink, no new colour exception. Keys in `DeskShortcuts` (`DeskScope.MAPPER`), the mouse and finger
  tables beside them, and the phone at 360 dp.
- **Runs are off the frame thread** and cancellable, with progress shown, like the goldfish's.

---

## 7. Ai's part

- **Tools, as instruments** (`Instruments`, so `Evidence.judge` traces every number): `mapper_map`, `mapper_starters`,
  `mapper_stress`, `mapper_routes`, `mapper_solve`.
- **Skills:** `gameplay-mapper` (reading a map, explaining a choke point in words) and `puzzle-solve`.
- **What Ai does:** proposes targets and tiers (marked as Ai's), names routes, explains a map, and writes effects for
  missing cards **only on kai's go**.
- **What Ai never does:** state a percentage the mapper did not compute, rank boards in kai's place, or save a route.
- At the table, `DuelGuide` may cite a mapped line for the position. Choosing Ai's moves by search is Phase E's.

---

## 8. Stored data

- `<data>/effects/mapper/<deck>/`: the tiers (as an order over the deck's `EndBoard` ids), the board comparisons, the
  fitted move prior and board value (small JSON, with their fingerprints), the confirmed routes' ids, and the latest
  results. Synced, backed up, and deleted with the deck. This follows D.md's rule for a new store of goldfish data.
- Maps themselves are **not kept**. They are recomputed from the seed, which is cheaper than storing a graph per hand.
  A result keeps its seed, its fingerprints and its counts.
- No preference, schema or `.ydkx` change is planned. A new `DuelPrefs`/`NeuePreferences` field, if one appears, goes into
  `SyncedPrefs` and `AiSettings`, and its old shape into `OldDataTest`.

---

## 9. Budget

- The engine runs at about 6,000 moves a second on the bench's single pass and 12,000–15,000 warm, on one core
  (D.md §5.7).
- A goldfish hand costs hundreds of moves. A map costs more, and the bench measures how much in step M1. The
  transposition merge and dominance pruning are what keep it small.
- A stress test multiplies by the other seat's choices. One interruption is cheap (it is legal at few windows), and two
  is the bench's real test.
- **Targets**, measured by `MapperBenchTest` and stated in the release notes: 2,000 hands mapped on the desk in a few
  minutes, 500 on a phone. Stress tests default to fewer hands, and the sentence says how many.
- The move prior (§4.1) is the main lever on cost after the merge. Its gain is reported as a number.

---

## 10. Decisions for kai

Each has the default this plan assumes until kai says otherwise.

| # | Decision | Default assumed |
|---|---|---|
| 1 | **Is this Phase E, or a phase of its own?** | **Phase M, the front half of E.** The solver is built here; Ai choosing its moves by search at the table (E's IS-MCTS and Elo) comes after, on this solver. |
| 2 | **Its own page, or a tab in the Effects app beside the Goldfish?** | **Its own page, `10`**, since kai called it a mode. The Goldfish tab stays and links to it. |
| 3 | **The look of the map.** | Three mockups, made with the studio, for kai to pick from before the page is built. |
| 4 | **How the opponent plays its interruptions.** | **Worst case** (it knows your hand) as the headline, with **field-weighted** as the second view. |
| 5 | **What ranks end boards.** | **kai's tiers over the existing targets**, then a value fitted to kai's own comparisons. Ai's comparisons kept as a judge of their own. |
| 6 | **Writing the interruptions' effects.** They are needed before any stress test. | One **Write the field's interruptions** button that lists the cards and their cost, on kai's go, as D.md §3.1 requires. |
| 7 | **Learned routes.** | Offered, saved as `Combo`s only on kai's confirmation. |
| 8 | **Growing the vocabulary for Veiler, Imperm and Nibiru** (§3.5), which Phase D left out. | Lingering negation first (Veiler and Imperm are the most played), then Nibiru. Battle waits for the puzzles that need it. |

---

## 11. The steps

One shipped release each, on both tracks (`:core` and the page reach the tablet).

### Step M1: the map and the starters
- **Builds:** `MapSearch`, `LineMap`, the board key, tiers over `EndBoard`s, `Mapper.run`, the starter table, the page's
  first form (the starter table and the map), and `mapper_map`/`mapper_starters`.
- **Tests:** `MapSearchTest` (a toy deck whose every end board is known; the map's ends equal a brute-force enumeration;
  merged nodes; dominance pruning against unpruned), `MapperSeedTest` (devices and threads agree), `StarterTableTest`
  (against hand-worked toy decks and `hand_odds`), `MapperBenchTest`.
- **Done when:** kai's deck's starter table and map read on the page, with the bench's numbers in the release notes.

### Step M2: stress tests
- **Builds:** two-seat tables, `StressSearch` with alpha-beta, choke points and fallbacks, named and field-weighted
  interruptions, the vocabulary for lingering negation (§3.5), the Stress tab, `mapper_stress`.
- **Tests:** `StressSearchTest` (a toy deck and a toy Ash where the guaranteed line is known and differs from the
  unopposed one), alpha-beta against plain minimax on every test case, `InterruptionVocabTest` (Ash, Called by, Veiler,
  Imperm against their rulings).
- **Done when:** "through Ash, N % still reach tier 2" reads for kai's deck with its choke points, and the guaranteed line
  opens as a replay.

### Step M3: learning
- **Builds:** the move prior and its expert-iteration loop, board comparisons and the fitted value, the route miner, the
  Routes tab with confirmation into `Combo`s, `mapper_routes`.
- **Tests:** `MovePriorTest` (a prior never changes a complete map's ends; the fit is deterministic from its seed),
  `PriorGainTest` (held-out seeds: at least half the median moves of the hand-written ordering), `BoardValueTest`
  (recovers a known ranking from simulated comparisons, the Shootout's simulation pattern), `RouteMinerTest`.
- **Done when:** the prior's gain and the value's agreement with kai are numbers in the release notes, and a mined route
  has been confirmed into a combo.

### Step M4: puzzles
- **Builds:** `Position` and `Goal`, "Solve from here" on the Duel page and in replays, DuelingBook positions, the
  Puzzle tab, effect-monster puzzles added to Test scores, `mapper_solve`.
- **Tests:** every C.md puzzle still solves, and its wrong line still fails; new puzzles with known solutions;
  `OldDataTest` for the grown `PuzzleSetup`.
- **Done when:** a position kai sets up on the Duel page is solved, or proved to have no solution, and opens as a replay.

### Later
- Battle in the vocabulary, then lethal puzzles.
- The board value learned from Ai vs Ai outcomes.
- Hidden information in puzzles, and Ai playing by search at the table: Phase E.

### Out of scope for Phase M
- Choosing Ai's moves at a live table by search (Phase E).
- Writing effects for cards nobody asked for.
- Any engine or scripts from outside the app (EDOPro and others), as the roadmap decided.
- A model trained off the device, or a language model inside the search.
