# Phase M: Gameplay Mapper

**kai's brief** (2026-10):
- "A new mode, called Gameplay Mapper."
- "Solving lines through algorithmic calculation and runs, machine learning."
- "Learn combos through machine learning."
- "Stress test against disruption (going first against handtraps or going into a board with disruptions)."
- "Eventually it'll be able to solve gamestates like puzzles."

**kai's decisions on the learning** (2026-10-08, in the Machine Learning Feature project):
- "The most advanced machine learning training techniques available, with weights adjustable based on the user's setup.
  Training will be guided and run by Ai/Chessy." The setup is the **hardware**: the weights are sized to the machine,
  never to a playstyle.
- Training runs in **a PyTorch helper on the desktop** (kai's pick over pure Kotlin).
- **No ranking given in advance.** "How would it or I know which endboards are the most optimal? It assumes too much and
  makes the training skewed and biased if we give it the wrong direction." So nothing the network learns from is anyone's
  opinion: every target is a measurement.
- **Start from starters**, and judge a board by how many interruptions it has and how many handtraps it plays through,
  with Chessy/Ai's guidance beside the numbers.
- **"A range of boards based on what the user wants, like a filter system with adjustable weights, and it would find
  optimized endboards from a library that it found during runs. Each sequence is replayable."**

**What it builds on.** Phase D's goldfish already does the hard part, for one question:
- `FxEngine` is a deterministic forward model. `moves(table, seat)` lists what a seat may start, and `play(...)` makes a
  move, its choices answered by a `Chooser`. It holds the chain, priority, SEGOC and once-per-turn, and it already runs
  **both** seats (`FxChain.next`), although the goldfish uses only one (`DuelState.solo`).
- `GoldfishSearch` runs depth-first over those moves, with a transposition table (`TableKey`), collapsed choices, move
  ordering and a budget. It answers **"can this hand reach this `EndBoard`"** and **stops at the first line**.
- `Goldfish.run` deals seeded hands (`DuelRandom.forRoll(seed, k)`), reduces them to their engine part, and gives the same
  counts on any device with any number of threads.
- `FxTrust` decides which scripts are used. A card with no trusted script is **inert**, so every number is a lower bound
  and says so. Effects are written only for the cards the person asks for (D.md §3.1, `FxAsks`).
- The engine runs at about 10,500 moves a second warm, on one core, against a target of 20,000. Phase D handed the
  remaining gap to this phase: it needs a deeper engine change (§9).

**The design in one paragraph.**
- **Gameplay Mapper** is page `10`. A deck goes in, and **a board library** comes out: every end board the trusted
  effects can reach, each measured (interruptions by kind, bodies, cards kept, what it plays through) and each with the
  lines that reach it, replayable on the Duel page.
- **Starters first.** Every engine card alone and every pair of them is mapped. That is the library's first content and
  the deck's starter table.
- **Stress tests** put interruptions in the other seat's hand and let the engine play them at the worst moment for you.
  What a board keeps through each one is another measurement.
- **The person chooses** from the library with filters and weights, at query time. A Pareto front shows the real trade-offs.
  Nothing the person chooses reaches the training.
- **Learning makes the search better, never the verdict.** A policy-value network, trained by self-play in a PyTorch
  helper on the desktop, orders the search and predicts each trait; MAP-Elites sends exploration to the kinds of board the
  library lacks. A new network replaces the old only through a gate measured on hands it never saw.
- **Ai/Chessy runs the training** through tools with hard limits: it plans, tunes, audits and explains, and it can never
  switch the helper on, promote a network that failed the gate, or change what the person chose.
- **Puzzles** are positions with a goal, solved by the same search with both seats known.

This is the front half of the roadmap's Phase E ("Search: Ai plays"): the solver and the network that E's player then uses
at the table.

---

## 1. What "done" means

| kai's words | Exactly |
|---|---|
| "Solving lines through algorithmic calculation and runs" | For a deck, a hand and a seat order, `MapSearch` returns every distinct end board the trusted effects can reach in one turn, each with its best line. It is exhaustive within its bounds and says when it was not. |
| "Machine learning" | A policy-value network that cuts the engine moves needed to fill the library, measured on held-out hands, and never changes a complete map's end boards (a test holds it). Its trait predictions are calibrated against the engine's measure, with the error shown. |
| "Weights adjustable based on the user's setup" | The model's size, batch and the engine's workers are chosen from a probe of the machine (`HardwarePlan`), and the person may override them. |
| "Guided and run by Ai/Chessy" | Ai plans, starts, tunes, audits and reports on runs through `train_*` tools, inside limits it cannot change. |
| "Learn combos" | Routes mined from the library: named skeletons, the cards each needs, and the starter table. Each is offered as a `Combo` and saved only on kai's confirmation. |
| "Stress test against disruption" | For a hand and a set of interruptions held by the other seat, `StressSearch` returns the most you can guarantee and the line that guarantees it. It is recorded on each board as what it plays through. |
| "A filter system with adjustable weights … from a library … each sequence replayable" | `BoardLibrary`, `BoardQuery` (filters, weights, presets, the Pareto front) and `MapReplay`. |
| "Solve gamestates like puzzles" | A `Position` and a `Goal` solved by the same search with both seats known: a solution line that opens as a replay, or "no solution" when the search covered everything. |

Every number carries its proof into the guide, as the goldfish's numbers already do.

---

## 2. The map and the library (`core/duel/mapper`)

### 2.1 What it answers

The goldfish asks "can it". The mapper asks **"what are all the places this hand can go, and how"**.

- **Input:** a deck, a hand (`MapDeal`: chosen cards on top of the deck, the rest riffled by a seed), going first or second,
  the bounds.
- **Output:** `MapSearch.Mapped`: the distinct end boards in key order, each with its traits and best line, whether the map
  is complete, the engine moves spent and the positions visited.

### 2.2 The search (`MapSearch`, built)

It is `GoldfishSearch` without the early stop and without a target.
- **Every open table may end the turn.** The End Phase is tried from each, so every board on the way is an end board too.
- **A graph, not a tree.** A table reached again no deeper is merged through the goldfish's transposition table.
- **End boards are deduplicated** by `BoardKey`: the board's cards by identity and place kind, its life points and its
  counted interruptions, hashed (FNV-1a, 64 bits). Copies and zone indexes do not matter; the price is that a Link arrow's
  aim is not part of a board's identity. The interruptions are in it because the same cards with a once-per-Duel effect
  spent and kept are two boards. Tokens are named, and Xyz materials kept per monster. What the key holds is
  `BoardKey.VERSION`, written in every library; a library keyed by another version is keyed again when read.
- **The fodder is no part of a board** (§2.4): left out of its hand, GY and banished cards and its counts.
- **The best line** to each end is the shortest, and among lines as short, the first in a fixed order of their text.
- **Before the End Phase** every Trap and Quick-Play Spell the engine knows is Set from the hand, as a player would and as
  a replay does, and the End Phase is played from there, so its triggers see the hand the player would have. With more of
  them than free zones, every distinct choice by card is its own end (at most 16; past it the map is incomplete), so which
  cards are Set never depends on the hand's order.
- **Bounds:** 60 engine moves a line, 100,000 a map. A map that ran out is **incomplete**, never "these are all". A table
  cut at the depth and searched again from nearer the start no longer counts against it; any cut at all still makes the
  map's training records inexact (`reachExact`, §4.3).
- **`MovePrior` may only reorder.** An order that is not the same moves is refused and the search's own is used
  (`Mapped.priorRefused` counts them). `MapSearchTest` shuffles the order and holds the ends, their traits and their best
  lines' lengths equal.
- **Each end remembers when it was first found** (`End.at`, the engine moves spent): the gate's front recall reads it.
- **Trace mode** keeps every decision table, in the order the search first met it, with the ends below each of its moves
  (sorted arrays of end indexes): the training data's source (§4.3). A table reached again adds nothing.
- **The deal never depends on the decklist's order**: the rest of the Main Deck and the Extra Deck are sorted before the
  riffle, so a deck reordered in the builder plays its kept lines the same.

### 2.3 What a board measures (`BoardTraits`, built)

No ranking is given in advance (kai, above). A board is **measured**, and the person weighs the measurements.
- **Counted from the trusted scripts:** interruptions (one per once-per-turn group, as the goldfish counts them), split
  into **negates** and **removal**, **less the ones the board could not use**: a once-per-Duel effect already spent, or a
  cost the board cannot pay as it stands (an Xyz with no materials, a discard with an empty hand).
- **Hand traps kept** (`handInterruptions`): answers usable from the hand with their cost payable, counted apart, so a line
  that pitched one as a cost shows what it gave up.
- **Counted off the table:** face-up monsters (`bodies`), set Spells and Traps, cards in hand, GY and banished.
- **Measured by the stress tests (§3):** `through`, from an interruption set's key ("ash", "ash+imperm") to the
  interruptions the board's line still guarantees with those cards in the other seat's hand. A board not yet tested has
  no value there, never zero.
- These are the network's value heads, in `BoardTraits.HEADS` order, and the axes the filters read.
- **Where more is plainly better** (`MORE_IS_BETTER`: interruptions, negates, removal, hand traps kept, and every stress
  key) is what a front with no weights, the training's policy target and the gate read. Bodies, cards kept and the GY are
  trade-offs, left to the person's weights.

### 2.4 Starters (`StarterTable`, built)

The question players ask first is "which cards start the deck".
- **Engine cards** are the ones with a trusted script that do more than answer: a card whose every effect answers from the
  hand or a Spell & Trap Zone (a hand trap, a Trap, a board breaker) starts nothing. Every engine card alone, and every
  pair (a card with itself only when the deck holds two), is mapped.
- **Beside the deck's fodder**: the opening hand's other places are filled with the deck's cards that have no trusted script
  and are not Normal Monsters, lowest passcode first, so a cost that discards is payable as it is in a real hand. Fodder is
  dealt, never part of the starter. A deck with fewer such cards deals fewer, and the row says so.
- **A deck whose order a script reads** (a draw, a mill) maps each starter over three deck orders, its row every order's
  boards together: one order's draws are one sample, not the card.
- Each row gives the boards reached, whether the map was complete, its cost, and **the chance of opening it** (the
  multivariate hypergeometric `HandOdds` computes).
- For a pair, **the boards neither card reaches alone** (`together`), as it is or with the other card left in hand: the
  pairs that need each other are the deck's real extenders. The fodder is left out of every board, or no pair's board
  could ever equal a single card's.
- The run is sequential in a fixed order, so the library it grows is the same however often and wherever it is run.

### 2.5 Over many hands (`Mapper`, built in M1)

`Mapper.run` is `Goldfish.run`'s twin: seeded five- and six-card hands dealt by `GoldfishHands`, the same reduction and the
same thread-independent counting (`MapPlan`/`MapWork`: the answers come back in deal order whatever the workers). A hand's
engine part is its cards with a trusted script and its fodder the rest, so hands that differ only in bricks are one map.
- **Hands are counted by kind**: the trait vectors (`BoardTraits`) of the boards each hand reaches, kept in the run
  (`MapperRun.traits`, `parts`). A share is the hands reaching a kind that passes the filters, or "at least this much" on
  the traits where more is plainly better (`MapperRun.atLeast`), with a Wilson 95 % range. A count never depends on which
  boards the library kept.
- The run is kept beside the library (`run.json`), stamped with the deck's and the scripts' fingerprints: a share is shown
  only beside the library it was counted on.
- **The training learns mostly from these hands**, as they are dealt: the starter table's hands are a starter and fodder, a
  shape no real opening has, so they are a share of the records, never all of them.

### 2.6 The board library (`BoardLibrary`, built)

kai: "it would find optimized endboards from a library that it found during runs".
- **Every distinct end board any run has found**, with its cards, traits, its cheapest lines (three at most) and every
  starter that reaches it.
- **Runs only add.** A known board gains a cheaper line or a new starter; stress results are kept when a board is mapped
  again. Lines are kept cheapest first, then by their text, so the same runs merged in any order keep the same lines.
- **Going first and going second are two libraries**; a deal on the other side is refused.
- **A deck or script change** marks every board **stale** until a run reaches it again, and forgets its stress results
  (measured with the old scripts). Stale boards are kept, never deleted, and left out of queries unless asked for.
- **Every line carries the deck it was found on.** Uids are the deal's, so a line from another version of the deck may not
  play again: a run keeps only the current deck's lines, and **revalidate** replays every line on the deck as it is,
  keeping the ones that still land on their board and making a board live again without a run.
- **Every line replays** (`MapLine`, `MapReplay`): the deal and each move by uid, played through the engine and committed
  to a duel the Duel page opens. `BoardLibraryTest` replays every kept line and checks it lands on its board. A replay
  never throws: a hand that cannot be dealt, a move a newer build wrote or one the engine refuses is its problem, in words.
- **Each field's best board** (M1): a map's boards are nearly all distinct by what was left in hand and in the GY, so the
  library takes, per **field** (the board with the hand, the GY, banished cards and LP left aside: what a player sees),
  the boards no other board of that field beats on what a field is judged by (the traits where more is plainly better,
  then cards kept in hand, then LP), the cheapest line's of equal ones (`BoardLibrary.admits`). On the bench deck's starter
  table: 8,157 boards reached, 2,021 kept (1.8 MB). A board beaten later by another hand's stays: runs only add. A board
  already known always gains the line and the starter.
- **Two devices' libraries merge** (`merged`): a sync keeps the newer file, so the app puts the file and what it holds
  together when it reads again, and writes the union back. A board only the other device has, from another version of the
  deck or its scripts, comes in stale.
- Kept in `<data>/effects/mapper/<deck>/library.json`, read forgivingly; an unreadable file reads as nothing, and is never
  written over.

### 2.7 Choosing from it (`BoardQuery`, built)

kai: "a range of boards based on what the user wants, like a filter system with adjustable weights".
- **Filters** bound a trait ("at least 2 negates", "plays through Ash with 1 left"), and **cards** may be required or
  refused on the board or its starter. A trait not measured never passes a filter.
- **Weights** rank what passes. Each trait is scaled to the most any board in the library has, before the filters, so a
  slider means the same on every trait and tightening a filter never changes a kept board's score. A negative weight
  prefers less.
- **The Pareto front** marks the boards nothing beats on every weighted trait at once: the real trade-offs. With no
  weights it reads the traits where more is plainly better, so a board that kept its hand and did nothing is not on it.
- **Presets** are saved weights and filters. One Ai suggests is marked as Ai's, with its reason, and shows its weights
  like any other.
- Ties go to the cheaper line. A query reads the library and changes nothing.

---

## 3. Stress tests (`core/duel/mapper/stress`, step M2)

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
- At each response window, the other seat chooses: pass, or one of its legal activations with each of its choices. The
  branching is small, because an interruption is legal only where its condition holds.
- **What is maximised is a measured trait**, chosen per test (interruptions by default), never a blended score.
- **Alpha-beta pruning** with the transposition table, keyed by the table and the other seat's unused cards.
- **The other seat knows your hand** in this test (worst case). The realistic version, where it guesses, is Phase E's
  IS-MCTS.

### 3.3 What it reports

For one hand against one set of interruptions:
- **The guarantee:** the most you can guarantee, and **the line that guarantees it**. This is often not the unopposed best
  line. Leading with a bait, or keeping a card until Ash is gone, shows up here.
- **The choke points:** where the opponent's best play spends each interruption ("Ash on Aluber's search").
- **The fallbacks:** for each choke point, where your best continuation ends.
- **The cost of playing around it:** what you give up by playing around it when it was not there.

Each tested board records its guarantee in `BoardTraits.through`, which the filters, the weights, the Pareto front and the
network's heads then read.

### 3.4 Which interruptions

- **The suite:** Ash, Imperm, Veiler, Nibiru, Droll …, alone and in pairs. Chessy/Ai proposes it from what the field plays
  (`DeckWeb`, `FieldBuilder`), and kai confirms every change (`suite_edit`).
- **From the field:** the other seat's hand dealt from a field deck's list, so a result can be **weighted by the field**.
- **Going second:** an end board from a field deck's library, or a board kai sets up on the Duel page.

### 3.5 What the vocabulary must grow (D.md §2.6)

| Card | Needs | In the first cut? |
|---|---|---|
| Ash Blossom, Called by the Grave and other "negate the activation of an effect that includes …" | `Cond.Newest(includes = …)` and `Op.Negate` | Yes |
| Effect Veiler, Infinite Impermanence | negating a face-up monster's effects until the end of the turn | Lingering negation, first in M2 |
| Nibiru | a count of the turn's Special Summons, and tributing the other seat's monsters | After lingering negation |
| Droll & Lock Bird | a restriction on the other seat | To check (`Op.Restrict`'s seat) |
| Maxx "C" | a trigger on the other seat's Special Summon that draws | A tax, not a stop: measured as cards the other seat gained |
| Battle (OTK, lethal puzzles) | the Battle Phase and damage | Waits for the puzzles that need it |

The vocabulary grows a family at a time in `:core` with tests, never by a JavaScript callback.

---

## 4. Learning

**The rule over all of it: a model may change how fast an answer comes. It may never change whether a hand reaches a
board, or what a board measures.** Correctness comes from the engine and the search. A truncated search says
"incomplete", never "no line".

**And nothing the model learns from is an opinion.** kai's rankings, Ai's suggestions and the person's weights never
become a training target. Every target below is something the engine measured.

### 4.1 The network

- **One policy-value network**, AlphaZero's shape. A transformer encoder reads the position as tokens, one a card: the
  card (a learned embedding over the deck's vocabulary), its zone, whose it is, face-up or down, and its battle position,
  with a summary token and the step in the turn.
- **Policy head:** each legal move (its kind, card and effect) is scored against the encoded position.
- **Value heads:** one per trait (`BoardTraits.HEADS`, then each stress key), predicting the most of that trait still
  reachable from the position. Each head is its own maximum (the most interruptions and the most cards kept may be two
  boards), so **a value head guides where to look and never ranks a board**: a weighted sum of heads is not any board's
  score, and nothing is ever ruled out by one. Each head is standardised in training, the statistics stored in the
  checkpoint and the ONNX file, and reported in its own units against predicting its mean.
- **Tiers:** S (d=128, 2 layers), M (d=256, 4 layers), L (d=512, 8 layers), chosen by the machine (§4.4).
- **Where it is used:** as the search's `MovePrior` (ordering only), and as the explorer's guide (§4.2). Later, the stress
  search's and Phase E's player.

### 4.2 Exploration: MAP-Elites (`EliteArchive`, built)

Quality-diversity search keeps, for every cell of a grid of board traits, the best board found there, and spends the next
runs on the cells that are empty or weak. That keeps the library **wide** instead of collapsing onto one "best" board.
- **The grid** is counted traits: interruptions (0–5+), negates (0–3+), bodies (0–5+), cards kept (0–3+) by default, with a
  stress key added as an axis once tested. The axes are shown, and changing them is the person's call. Ai may suggest an
  axis, never set one. The grid only chooses where to look next: it is never a training target (§4.3).
- **Cells no board can fill** (more negates than interruptions, below the last bin) are neither counted in coverage nor
  offered as somewhere to look.
- **Inside a cell** the elite is the cheapest board to reach: fewest starting cards, then fewest moves.
- **The frontier** is the empty cells next to a filled one, cheapest neighbour first, each with the starters that reached
  its neighbour: where the next run maps from.

### 4.3 Training data (`TrainingExport`, built; the contract in `tools/mapper-train/README.md`)

The Kotlin side owns the game; the trainer only ever sees these files.
- `vocab.json`: index 0 padding, 1 unknown (a token, the other seat's card), then the deck's passcodes. **Only ever
  appended to**: a card added to the deck takes the next index and none moves, so the weights carry over a deck change
  (the trainer copies the old rows and starts the new ones).
- `heads.json`: the value heads in order, appended to the same way.
- `records-*.jsonl`: one decision table of a traced map a line: its tokens, its legal moves (`[kind, card, effect, zone]`:
  the same effect from the hand and from the GY are two moves), and the two targets:
  - **value**: for each trait, the most any end board reachable from here has;
  - **policy**: for each move, the share of the position's own **Pareto front** its ends reach, on the traits where more is
    plainly better. The best board for any weights a player could set without preferring less of a good thing lies on that
    front, so the target needs no weights and no grid: it was "the share of MAP-Elites cells" until the red team showed
    the grid's own cut-offs became the policy's taste.
- **An incomplete map writes nothing**: its tables are the subtrees the order it was searched in reached first, and a prior
  trained on them would learn its own taste back. Nor does a complete one whose search ever cut a table at the depth
  (`Mapped.reachExact`): the tables above the cut counted their ends without it.
- Each record carries a hand id (the starter, its fodder and the seat order, never the seed), which the trainer hashes to
  keep a hand wholly on one side of its train/validation split; the table's identity (`pos`); and the order it was mapped
  in (`prior`, "none" for the hand-written one). A fixed share of every round's maps is made with no prior, so the network
  never trains only on its own echo.

### 4.4 The trainer (`tools/mapper-train`) and the machine (`HardwarePlan`, built)

- **A PyTorch helper process on the desktop**, off until the person switches it on (like Python in Ai World, `WorldPrefs`:
  device-only, and Ai can never turn it on). It uses the person's own Python with torch, onnx and onnxruntime, all
  permissively licensed.
- **The probe** (`python -m mapper_train.hardware`) reports the device (CUDA, ROCm, Apple's MPS or the CPU), its memory,
  the cores and measured steps a second. `HardwarePlan.of` turns it into the tier, the batch, the engine's workers and a
  round's minutes: on a CUDA or ROCm GPU, L from 12 GiB and M from 6; on Apple's unified memory, L from 64 GiB and M from
  32; on the CPU, S; a GPU that measured slow stays S. The person's override is never replaced by a new probe or by Ai.
- **Training** (`mapper_train.train`): AdamW with warmup and a cosine schedule, mixed precision on CUDA, gradient clipping,
  soft-target cross-entropy for the policy and a masked error per value head. It reports JSON Lines (`TrainEvent`), which
  the page draws and Ai reads; a `stop` file pauses it cleanly.
- **Population-based training** (`mapper_train.pbt`) tunes the settings by exploit-and-explore over a small population,
  deterministically from a seed. Members are ranked, and the best checkpoint kept, on one fixed objective (validation
  policy loss plus the base value weight times validation value loss), never on a loss whose weight the search itself
  tunes. `TrainConfig.bounded` holds every setting inside fixed limits.
- **Export** (`mapper_train.export`): ONNX with the vocabulary hash, tier and heads stamped in, checked against PyTorch
  before it is kept; 8-bit for the phone, **gated on its own** (the 8-bit file must pass the gate's front recall against the
  full one before it syncs). The app runs it through ONNX Runtime on the desk and the phone. A phone never trains: it gets
  the desk's weights through sync.

### 4.5 The gate (`TrainGate`, built)

A new network replaces the one in use only when, on **held-out hands** neither was trained on:
- **it finds the front sooner** (`FrontRecall`): each gate hand is mapped once exhaustively, with no prior, for its Pareto
  front; each network then maps it at the gate set's one fixed budget, and scores the share of that front it found,
  weighted by how early (the area under its recall curve, from `End.at`). "More MAP-Elites cells", the first measure, could
  be won by finding many middling boards and missing the ones nothing beats;
- it wins on more hands than it loses by a **one-sided sign test** on the decisive hands (ties left out), below 5 %
  shared among every candidate tried since the last promotion — trying many candidates spends the chance of passing by luck;
- it still finds **every board the person confirmed** (kept lines, saved combos);
- **each value head** predicts no worse than the network in use (within 5 %); against version 0, which predicts nothing,
  each head must beat predicting its mean;
- and it was judged on at least 30 hands.

Each version is rated on a ladder of **search ratings**: how much sooner its maps find the front, never how well it plays.
The hand-written order is version 0 at 1,000, and each promoted version's rating is its parent's plus its measured Elo,
with its range. A version that fails stays a file; the one in use does not change.

### 4.6 Ai/Chessy runs the training (step M3)

Tools, as instruments (`Evidence.judge` traces every number they give):
- **See:** `train_status` (curves, the device, games a second, library size and coverage), `library_coverage` (which kinds of
  board are missing or weak), `engine_coverage` (lines that stopped at a card with no script: the cards to ask the person
  about).
- **Steer, inside limits:** `train_start`, `train_pause`, `train_budget`, `train_tune` (population-based training within
  `TrainConfig`'s bounds), `explore_focus` (point the next rounds at a region of the grid or at starters), `suite_edit`
  (the person approves).
- **Check:** `eval_heldout`, `eval_calibration` (does "60 % plays through Ash" happen 60 % of the time), `audit_net` (the
  network against the engine, worst misses first), `audit_data` (leaks between training and held-out hands, duplicates,
  one starter crowding out the rest), `ablate`, `regress`, `gate`.
- **Explain:** `library_diff`, `board_explain`, `run_report`.
- **Held out means held out from Ai too.** The evaluation tools return aggregates over the gate hands, never a hand or a
  board of them; `explore_focus` refuses a starter that is a gate hand's; every record carries where it came from, so an
  audit can prove no gate hand was trained on.
- **Never:** switch the helper on, delete library entries, change the person's weights or override, promote a version
  that failed the gate, or state a number a tool did not compute. Every action Ai takes in a run is logged with the run.
- Chessy's copies stand beside a run while it works (`ChessyCrew`), and the skill `gameplay-mapper` holds how to read it.

### 4.7 Combos, learned (routes and starters)

"Learn combos" means **mining the library for the lines the deck keeps using**:
- **Routes:** skeletons counted across the library's lines, merged into a prefix tree so shared openings and their
  branches show.
- **What a route needs:** the smallest starters that reach it, from the starter table.
- **Offered, never saved by itself:** a route becomes a `Combo` only when kai confirms it. A route that reaches a kind of
  board no recorded combo reaches is flagged **new**; a recorded combo the engine cannot play is flagged too, which is
  usually a wrong script, so the flag offers **Repair it**.

---

## 5. Puzzles (`core/duel/mapper/puzzle`, step M4)

- **A position:** both seats' hands, fields, GYs, banished cards and Extra Decks, life points, the phase, and once-per-turn
  flags spent. The C.md puzzles' `PuzzleSetup` grows into it, so the 17 existing puzzles still load.
- **A goal:** a condition on the boards ("their field is empty", "they have no negates left"), and later life points.
- **Solved** by `StressSearch` with both seats' cards known. The answer is a solution line, opened as an unsaved replay
  (`Duels.openGame`), or **"no solution"**, which is a proof when the search was complete.
- **Where puzzles come from:** "Solve from here" on the Duel page or in a replay; a DuelingBook replay at a chosen move
  (`DbConvert`); set up by hand; the existing puzzle set, then new ones with effect monsters.
- **Hidden information** is out of scope here. Real play under uncertainty is Phase E's IS-MCTS.

---

## 6. The page: `10` Gameplay Mapper

**Built in M1** (`neue/mapper/MapperPage.kt`, the holder `Mappers`): the header's Library | Starters and going first |
second; a run bar (hands, seed, Re-roll, Map hands, Map the starters; while one runs its progress and Stop); on the left
the query (presets, Ai's marked with its reason, a weight slider per trait from "less is better" to "more is better", "at
least" bounds, cards with or without, stale boards); in the middle the library ranked; on the right the inspector (the
board's zones as art, every trait, its share, which as a filter, its lines each played on the Duel page, its starters). The
Starters tab lists every starter (opened, boards, only together) with its best boards by the weights on screen. **The
library is drawn three ways for kai to choose from** (`MapperLook`: a gallery of boards as art, a table of numbers beside a
strip of art, a map of two traits), photographed by `tools/shoot.sh --page=mapper --mapper=demo --mapper-look=…`; the two
not chosen are deleted. On a phone the gallery alone, the query and the inspector in dialogs. Keys: `L`, `S`, `G`, `↑`/`↓`,
`Enter`, `R`, `Shift R`, `Ctrl .`, `Ctrl Shift M` from anywhere; mouse and finger in `MapperMouse`/`MapperTouch`.

Ai has the same library (`mapper_library`, `mapper_starters`, `mapper_map`, `mapper_preset`): the page's runs and words
(`MapperReport`, `MapperWords`), Ai's presets kept as its own and never the person's deleted.

The sketch it was built from:
- **Left: the starter table.** One-card and two-card starters, the boards each reaches and the odds of opening each.
- **Middle: the library.** Boards as card art, ranked by the current weights, the Pareto front marked. Filters and weight
  sliders over it, and the presets.
- **Right: the inspector.** The chosen board, its traits, the lines that reach it (each opens as a replay), and what it
  keeps through each interruption.
- **Tabs:** Library, Starters, Stress (the suite), Training (the run, its curves, the ladder, Chessy), Routes, Puzzle.
- Master UI: paper and ink, no new colour exception. Keys in `DeskShortcuts` (`DeskScope.MAPPER`), the mouse and finger
  tables beside them, and the phone at 360 dp. The Training tab is the desk's alone.
- **Runs are off the frame thread** and cancellable, with progress shown.

---

## 7. Stored data

- `<data>/effects/mapper/<deck>/`: `library.json` and `library-2nd.json` (the board libraries going first and second),
  `run.json`/`run-2nd.json` (the last run's counts), `starters.json`/`starters-2nd.json` (the starter table),
  `presets.json` (M1, `MapperPaths`; each shape in `OldDataTest`), and later the suite, the run log, the ladder, and
  the promoted network (`net-<version>.onnx`, with its vocabulary hash). Synced, backed up, and deleted with the deck. The
  phone's 8-bit network is made from the synced one.
- `<data>/effects/mapper/<deck>/train/`: records, checkpoints and the trainer's logs. **Never synced or backed up**: they are
  large, and remade from the library.
- The trainer's settings and the hardware plan are device-only (`SyncedPrefs.DEVICE`), described to Ai in `AiSettings`; the
  helper's switch is internal (`AiSettings.INTERNAL`), so Ai can never turn it on.
- Any new stored shape goes into `OldDataTest` in the release that writes it.

---

## 8. Budget

- The engine runs at about 10,500 moves a second warm, on one core (Phase D's bench).
- A starter table on a deck of 15 engine cards is 15 + 120 maps. Small hands make small maps, so most of the budget goes
  to the pairs that do the most. `MapperBenchTest` measures it on kai's deck in step M1.
- **The engine's speed is now this phase's.** Phase D left the gap to 20,000 moves a second, which needs a deeper change to
  the engine (fewer table copies a move). It belongs in M1, before the network, because every number below scales with it.
- **Measured in M1 (2026-10, JFR on the bench's pass).** A changed card is one array copied (`CardMap`), the place index
  and the book's and facts' memos are keyed by `Int` without boxing (`IntTable`, `IntMemo`), triggers are looked for only
  after an event some trigger waits for, and a card's place is asked once where it was asked twice. No answer changed:
  `FxSpeedMemoTest` holds each change to the code it replaced, and every answer on 420 seeded walks and 60 goldfish hands
  matched the old engine's. Same machine, before → after: about 21,500 → 29,000 moves a second once the JIT has settled
  (30 seconds of the bench's pass), so the 20,000 assumption is met warm. The bench's own passes are mostly the JIT
  warming up and move by a third, so they read about the same: 6,100–6,400 → 5,700–7,900 on the single pass,
  12,400–13,500 → 8,300–13,100 warm. The goldfish bench went from 15.2 to 18.0 hands a second on one worker.
- The network's job is to need fewer of those moves. Its gain is reported as a number, on held-out hands.

---

## 9. Decisions

| # | Decision | Settled |
|---|---|---|
| 1 | Phase M or Phase E | **Phase M, the front half of E.** Ai choosing its moves by search at the table comes after, on this solver and network. |
| 2 | Its own page | **Page `10`**, since kai called it a mode. |
| 3 | The look | Mockups, made with the studio, for kai to pick from before the page is built. |
| 4 | How the opponent plays its interruptions | **Worst case** first, field-weighted beside it. |
| 5 | What ranks end boards | **Nothing in advance** (kai, 2026-10-08). Boards are measured; the person filters and weighs at query time. |
| 6 | Where training runs | **A PyTorch helper on the desktop** (kai). The phone runs the desk's weights. |
| 7 | "The user's setup" | **The hardware** (kai). |
| 8 | Writing the interruptions' effects | One **Write the suite** button listing the cards and their cost, on kai's go. |
| 9 | Learned routes | Offered, saved as `Combo`s only on kai's confirmation. |

---

## 10. The steps

One shipped release each, on both tracks (`:core` and the page reach the tablet).

### Step M0: the foundation (built)
- `MapSearch`, `MapDeal`, `BoardCards`/`BoardKey`/`BoardTraits`, `StarterTable`, `BoardLibrary`, `MapLine`/`MapReplay`,
  `BoardQuery`, `EliteArchive`, `TrainingExport`, `HardwarePlan`, `TrainGate`, `TrainConfig`/`TrainEvent`, and the trainer in
  `tools/mapper-train` with its self-test. All in `:core` with tests; nothing is drawn yet, so it needs no release.
- Red-teamed twice before it shipped, on the learning and on the engineering: `M-REDTEAM.md` has every finding and what was
  done about it.

### Step M1: the page, the engine's speed and many hands
- The page's first form (Library and Starters), `Mapper.run` over seeded hands, the engine's speed (§8), `MapperBenchTest`,
  and Ai's `mapper_map`/`mapper_starters`/`mapper_library`.
- Built: `Mapper`, per-field admission and merging (§2.5, §2.6), the files (§7), the page in three looks (§6), Ai's four
  tools. Waiting on kai's choice of look (Decision 3).

### Step M2: stress tests
- Two-seat tables, `StressSearch` with alpha-beta, choke points and fallbacks, the suite, lingering negation (§3.5), the
  Stress tab, `mapper_stress`. Done when "through Ash, N interruptions guaranteed" reads on kai's deck's boards.

### Step M3: training
- The desktop's helper (`neue` jvmMain: launch, stop file, events), ONNX Runtime on the desk and the phone, the self-play
  loop (map with the network, export, train, gate), the Training tab, the ladder, and Ai's `train_*` tools. Done when a
  promoted network's gain is a number in the release notes.

### Step M4: puzzles
- `Position` and `Goal`, "Solve from here", DuelingBook positions, the Puzzle tab, `mapper_solve`.

### Later
- Battle in the vocabulary, then lethal puzzles.
- A board's value measured in won games, once Phase E's player can play them.
- Hidden information in puzzles, and Ai playing by search at the table: Phase E.

### Out of scope for Phase M
- Choosing Ai's moves at a live table by search (Phase E).
- Writing effects for cards nobody asked for.
- Any engine or scripts from outside the app (EDOPro and others), as the roadmap decided.
- A ranking of boards given to the training by anyone.
