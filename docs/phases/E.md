# Phase E: Search — Ai plays

**The roadmap's goal:** Ai chooses its moves by searching, not by guessing. It is done when:
- Ai plays a whole turn by search;
- its rating rises version over version;
- it beats its own pre-search version over a fixed set of seeds.

**Why now.** Phase G part one is shipped (1.1.61–1.1.69). The Mapper's foundation is built (M0, M1): a solver over one
seat's lines, its library of end boards, and the engine at about 29,000 moves a second warm (M.md §8). The Mapper's stress
tests (M2) are the first search with an opponent in it. `M.md` calls itself "the front half of E", so this phase opens
with M2.

**Progress.** E.1 (the stress tests) is built: `core/duel/mapper/stress`, lingering negation, the Mapper's Stress tab and
`mapper_stress`; M.md's step M2 records where it went another way (a proof a threshold at a time rather than alpha-beta;
a board's number is its kept lines'). E.2 (a whole turn) is built: `core/duel/search` (`BattlePlan`, `TurnSearch`,
`TurnValue`, `TurnLine`, `TurnSolve`), `PuzzleSolver`, Solve this turn on the Duel page and in a replay, and `duel_solve`;
§4.2 ends with where it went another way. E.3 (the player) is built: `Seen`, `Worlds`, `Regret`, `Carry`, `SearchPlayer`
and the seat's door, Search as a seat in Ai vs Ai; §4.4 ends with where it went another way. Search at kai's own table
moved to a release of its own (E.3b), and the ladder and play-around each moved one patch on.

**What is not changing.** Correctness comes from the engine; search only chooses. Cards play only by the scripts the person
asked for (D.md §3.1). Nothing ranks an end board in advance (M.md, Decision 5).

---

## 1. What "done" means

| The roadmap's words | Exactly |
|---|---|
| "Determinised search over the opponent's interruptions" | For a hand and a set of interruptions in the other seat's hand, `StressSearch` returns the most you can guarantee and the line that guarantees it (E.1). For a table seen through one seat's eyes, `Worlds` samples the other seat's hidden cards from a prior, and the player chooses by regret over those worlds (E.3). |
| "Sampled from the field and weighted by what the opponent has shown" | The prior is the opponent's list where the seat knows it, else the field's lists by share (`FieldProfile`). A world is weighted down when the other seat passed where the card it would hold could have answered (E.3). |
| "Within a phone's budget of about one CPU second" | A decision is bounded in **engine moves**, never in time, so the same table gives the same move on every device: Fast, Strong and Max are 5,000, 25,000 and 150,000 moves. On the desk that is about 0.2, 1 and 5 seconds; on a phone, about five times as long (E.3). |
| "IS-MCTS for play-around decisions" | Where the worlds disagree on the best move, one tree over what the seat can know, each pass through it a world. This makes "lead with the bait" a decision the search can reach (E.5). |
| "The model inside the search" | The value weights are tuned by matches the search plays against itself, and only won games count (E.5). Ai (the language model) calls the search as a tool, names the opponent's likely deck as the prior, and may propose value weights, which are kept only if the ladder's gate passes. It never picks moves alone. |
| "A rating per Ai version" | The ladder: every player plays a fixed set of seeds from both seats. A Bradley–Terry fit gives each an Elo with its range, anchored on the greedy player. A version is promoted only when it beats its parent by a sign test (E.4). |
| "Plays a whole turn by search" | `TurnSearch`: Main Phase 1 by the engine, battle by `BattlePlan`, Main Phase 2 and the End Phase, valued at the turn's end (E.2). |
| "Beats its pre-search version" | On the ladder's seeds, the search player beats the player without search (greedy, then search v0, which assumes the other seat holds nothing), with the interval in the release notes. A language model's pre-search player needs a key to measure. Where releases are built there is none, and that is said, as in G.9. |

---

## 2. What it stands on, and what is missing

**Built and usable as it is:**
- **The forward model.** `FxEngine.moves(t, seat)`, `play(t, seat, move, chooser)` and `next(t)`.
  - `FxTable` is immutable, so a fork is free: keep the old reference.
  - Every choice inside a move goes to the `Chooser` as a `Decision`, and the ones the other seat makes say so
    (`Decision.Cards.by`, `YesNo.by`, `Order.by`).
- **Two seats in a chain.** Off `solo`, priority passes to the other seat after each link, and two passes resolve the newest.
  - The other seat may answer with what it holds: `Respond(seat, about, includes)` and `Op.Negate` make an Ash-like card.
  - `FxRef.WARD` is one: from the hand, negating a search.
  - `FxChainTest` plays it.
- **A live game's engine state.** `FxFold.fold(header, played, …)` folds a duel's log into the engine's state (`Shortcuts.at`).
  - A search can start from any table in play.
  - A result commits as one group with its tags (`ShortcutResult.commit`).
- **The Mapper.** `MapSearch` (the graph search over one seat's lines with a transposition table), `BoardTraits` with its
  `through` map waiting for stress results, `BoardLibrary.through`, and the query, merge and training code that already
  read `through`.
- **Seats.** `MatchPlayer` is the seat interface, and non-language players already implement it (`Bot` in the tests,
  `Script` in the studio). The referee (`MatchReferee`), its law, its bounds and its records are in place.
- **A seat's eyes.** `DuelView.of(s, viewer, secret)` and `DuelSight.sees`. `DuelFork.table` rebuilds a game from one
  seat's view, filling that seat's own unseen cards from its decklist and shuffling both decks by the fork's seed.
- **The field.** `FieldProfile` gives each strategy's share, its hand traps and negates with their share of lists and
  their mean copies, and its Side Deck.
- **Ratings and tests.** `TrainGate`'s `Elo`, sign test and Wilson range; `PairedMath`'s Newcombe and McNemar; the 17 duel
  puzzles with their referee and baselines (nothing 0, greedy 2, solutions 17).

**Missing, and where each is built:**

| Gap | Built in |
|---|---|
| The engine opens no window in the open state: summons start no chain, so `next` never points at the other seat there. Effect Veiler is answered only when asked. | E.1: the stress search asks the other seat after each of your moves that leaves the table open. This is the worst case, and it is the engine's own house ruling for Quick Effects. |
| No lingering negation (Effect Veiler, Infinite Impermanence), no count of the turn's Special Summons (Nibiru), no "add from the Deck" ban (Droll), no floating draw trigger (Maxx "C"). | E.1: lingering negation. The others follow in later releases, each one a family in `:core` with tests (M.md §3.5). |
| No battle in the engine: no attacks or damage, and no check for 0 LP. Nor does the engine ever end the turn. | E.2: `BattlePlan` on `DuelBattle`'s physics under the puzzle referee's rules. Battle effects stay inert, and a result says so. |
| The match table refuses engine moves (`DuelHost.NO_SHORTCUTS`), and Ai's menu (`DuelMoves.menu`) is physics only. | E.3: a local match table commits engine moves for a search seat. A networked table still refuses them. |
| Nothing samples the other seat's hidden cards: `DuelFork` leaves them as code 0. | E.3: `Worlds`. |
| Players are not rated: `TrainGate` rates search networks by front recall, and `DuelResults` counts wins. | E.4: the ladder. |

**The ceiling, said everywhere.** Only trusted scripts play (`FxTrust`). A card with none is inert: never activated or
summoned by its effect. On a deck with a quarter of its cards scripted, the search plays a quarter of the deck. Every
search result, match and rating carries the coverage of both decks (`FxCoverage`). Effects are still written only for the
cards the person asks for.

---

## 3. Rules this phase keeps

1. **The engine decides what is legal; the search only chooses.** A search that ran out of budget says "incomplete" and gives
   a lower bound, never "no line" (M.md §4).
2. **Hidden is hidden.** A search for a seat starts from that seat's `DuelView` and nothing else. The other seat's cards are
   sampled, never read. Each release's red team reads every input a search was handed, as the Ai vs Ai red team read every
   request (C.md §7b).
3. **Deterministic.** The same table, seed and budget give the same move on any device and any number of threads. Worlds
   are seeded by `DuelRandom.forRoll(seed, k)`, and parallel work is merged in a fixed order. Budgets are counted in engine
   moves. A time limit may only stop a search, and the result says it was stopped.
4. **Paired seeds.** A rating comes from the same seeds played from both seats (G's rule 1). Two separate runs are never
   compared by eye.
5. **A choice is made by whoever the engine says makes it.** Inside a move, the mover's own choices are maximised and the
   other seat's (`Decision.by`) are minimised, never maximised for the mover.
6. **No ranking given in advance.** The Mapper's boards are still ranked by the person's weights. The player's value is
   tuned to won games, which are a measurement (E.5). Its starting weights are written down, version 0 on the ladder.
7. **A number carries its proof.** Stress guarantees, regrets and ratings are instruments: ranges and n where they are
   rates, coverage beside them, and through `Evidence.judge` in Ai's words.
8. **Stored data outlives versions.** Each new shape goes into `OldDataTest` in the release that writes it.

---

## 4. The pieces

### 4.1 Stress tests (`core/duel/mapper/stress`, E.1)

M.md §3 is the design. What it needs to be built:

- **The deal** (`StressDeal`): `MapDeal`'s hand for you, and for the other seat a hand of the interruptions under test plus
  blanks (`GoldfishKit.BLANK`), with an empty field. The table is **not** `solo`, so the chain hands priority across.
  Going second, the other seat holds an end board from a library or the field instead; that comes after going first.
- **The game** (`StressSearch`): one turn between two players. You maximise one measured trait (interruptions by
  default); they minimise it.
  - **Your nodes** are `FxEngine.moves(t, you)`, distinct as `MapSearch` makes them. The End Phase is offered at every
    open table, after the Traps and Quick-Plays in hand are Set as `MapSearch` Sets them.
  - **Their nodes** come in two kinds:
    - In a chain, where `next(t)` is theirs: Pass, or an activation.
    - **In the open state**, after each of your moves that leaves the table open: Pass, or a Quick Effect from their
      hand.
    The second kind is the worst case. A hand trap with no legal use offers no node, so a suite of Ash-likes against a
    deck that never searches costs one Pass a window.
  - **Choices inside a move:** the move is replayed once per answer, as `MapSearch`'s tape does. Each decision is
    maximised or minimised by whoever makes it (rule 5).
  - **Alpha-beta** with a transposition table keyed by `TableKey`, which holds both hands, the chain, priority and passes.
    Entries are exact, a lower bound or an upper bound. An entry made under a depth cut or a stopped budget is marked
    inexact.
  - **Move ordering:** the library's best line first (from the unopposed map), then the search's own order, then
    `MovePrior`. Ordering may only reorder, as in the Mapper.
  - **Bounds:** 60 moves a line and 100,000 engine moves a test. Past either, the guarantee is a lower bound and says so.
- **What it reports** (`StressResult`):
  - the guarantee, with the line that guarantees it;
  - its **strategy**: your moves on the principal variation, with their best responses;
  - the **choke points**, each a node where their best play is an activation ("Ward on Scout's search"), with the
    **fallback** below it;
  - the **cost of playing around it**: the unopposed best, less what the guarantee's strategy reaches if they pass
    everywhere.
- **The suite** (`StressSuite`, `suite.json` beside the library): interruption sets such as "ash" and "ash+veiler".
  - Proposed from `FieldProfile.interaction()`, by share. Ai may propose a suite; the person confirms each change.
  - A card with no trusted script is listed with **Write these**, which goes through `FxAsks`. Nothing is written by
    itself.
- **Into the library:** each tested board gains `through[key]`. The filters, weights, Pareto front and training records
  already read it. A deck or script change forgets it, as `rebased` already does.
- **The vocabulary grows by one family:** **lingering negation** ("negate the effects of a face-up monster until the end
  of this turn"). This is an op and a state in `FxState`. A negated card's effects on the field cannot be activated, and
  its continuous effects stop applying, until the turn ends. Held by `FxChainTest`-style tests, like every family before it.

### 4.2 A whole turn (`core/duel/search/TurnSearch`, E.2)

- **Main Phase 1** by the engine: `MapSearch`'s ends, or `StressSearch`'s against a known hand.
- **Battle** by `BattlePlan`: every order of attackers against every target, under the puzzle referee's rules. It covers:
  - one attack per monster, from face-up Attack Position;
  - no direct attack while they control a monster;
  - damage and destruction from `DuelBattle`;
  - their LP reaching 0 as a win.
  Attackers that are copies collapse, and the order is pruned once their field is clear. There are no battle effects in
  the engine yet: such a card fights by its printed numbers, and the result says so.
- **Main Phase 2** by the engine again (Sets, and anything a battle made possible), then the End Phase.
- **The value at the turn's end** (`TurnValue`): a vector of your LP, their LP, cards held, the board's traits and its
  stress guarantees, weighed by `ValueWeights`. Version 0's weights are written in `SearchV0` and tuned in E.5. A won game
  (their LP at 0) is worth more than any vector.
- **Puzzles are its first measurement.** The search solves the 17 duel puzzles (C.md §5) with their goal as its value, and
  `PuzzleBaselines.search` joins the bounds: nothing 0, greedy 2, search n, solutions 17. A complete search that finds no
  line is a **proof** that the puzzle's wrong line has no fix.
  - **Positions with effect monsters** (M.md §5, M4) are set up the same way. **Solve from here**, on the Duel page or in a
    replay, opens the solution as an unsaved replay (`Duels.openGame`).
- **Ai asks it** (`duel_solve`, read-only): the best turn from the table as Ai's seat sees it. Their hidden cards are
  blanks until E.3, and the result says "assuming they hold nothing".

**As built (E.2).** Where the build went another way, and why:
- **Main Phase 1 is searched by `TurnSearch` itself**, on `MapMoves` (the mapper's distinct moves and Set choices, now
  shared), not by reading `MapSearch`'s ends: a turn needs the table each end is reached on, with the other seat's
  priority passed between the moves. A table reached again is not searched again (`TableKey`).
- **Main Phase 2 Sets the backrow and ends the turn**; its engine moves are not searched yet. A line that needs a summon
  after battle is not found, and the docs say so.
- **The weights are `ValueWeights` v0 in `TurnValue.kt`**: interruptions (3), negates (1), bodies (1), Set cards (½), cards
  kept (½), hand traps (1), damage dealt (1 a thousand), life points spent (½ a thousand), their monsters (−1½) and their
  face-up interruptions (−3). The stress guarantee is not in v0: it needs their hand, which is E.3's.
- **`BattlePlan` remembers who attacked**, because the table forgets an attack once its target leaves the field. A face-down
  monster attacked is turned face-up first. There are no battle effects, so a card fights by its printed numbers.
- **The puzzles are solved by `PuzzleSolver`**: iterative deepening over the referee's own moves, with a memo of the tables
  that failed at each depth. It does not use `TurnSearch`, because a puzzle's referee admits fewer moves than the engine.
  All 17 are solved, each by a line no longer than the recorded one, and 14 of the tempting wrong lines are proved to have
  no fix. `PuzzleBaselines.searched` joins the bounds, and Test scores says what the search scores.
- **Solve this turn** (`Alt S`, Table ▾, the replay bar's Solve) searches a fork of the table as the turn player sees it
  (`DuelFork`). The line opens as an unsaved replay of that fork, so it never draws a real card or shows a hidden one. A
  fork's header now keeps the table it begins from (`DuelHeader.start`, null for every dealt duel), so a replay of it folds
  from there. It is refused at a networked table, during an Ai vs Ai match, and for Ai's seat.

### 4.3 Hidden cards (`core/duel/search/Worlds`, E.3)

A world is one guess at everything the seat cannot see, made from that seat's view (`DuelFork.source`):
- **Your own unseen cards:** your deck's order, from your decklist less what you have seen (`DuelFork`'s rule).
- **Their unseen cards:** their hand, their face-down cards and their deck, from a **prior**, chosen in this order:
  1. their decklist, where the seat knows it: full knowledge, a deck the person named as the opponent (the web,
     `OpponentGuess`), or a Lounge room's checked deck shown to both;
  2. else the field's lists by share (`FieldProfile`'s strategies). The strategy is chosen first, then a list of it;
  3. else the field's interaction (`FieldProfile.interaction()`), its share as each card's chance, around blanks.
  Cards they have revealed, and cards they have played, are fixed in every world.
- **Weighted by what they did.** Each time they passed where a card in this world could have answered, the world's weight
  is multiplied by `HOLD_BACK`, the chance a player keeps a live answer. Version 0 sets it to 0.3; E.5 tunes it on won
  games. A world is never discarded, only weighted, so a bluff stays possible.
- **Seeded:** world k is dealt by `DuelRandom.forRoll(seed, k)`, so the worlds are the same on every device.

### 4.4 The player (`core/duel/search/SearchPlayer`, E.3)

- **A `MatchPlayer` with no connection.** Beside the cue's text, it is handed **a seat's door** (`SeatDoor`). The door
  holds:
  - the seat's `DuelView`;
  - its own decklist;
  - the scripts for its own cards;
  - a commit that `DuelReach` and the match's law hold, as they hold `duel_act`.
  Nothing else is handed to it, and the red team reads every input (rule 2).
- **A decision:**
  1. List the candidates: the engine's moves, plus what the engine does not make — the phase changes, battle by
     `BattlePlan`, and the turn's end.
  2. Deal K worlds.
  3. Value each candidate in each world by `TurnSearch` or `StressSearch` against that world's hidden cards.
  4. Choose the candidate with the least expected regret: in each world, the best candidate's value less this one's,
     averaged over the worlds' weights.
  Ties go to a fixed order. The budget is shared across the worlds, and a world left unsearched counts as unknown, never
  as zero.
- **Its responses.** On the other seat's turn it decides when to spend a hand trap. This is the same search from the
  defender's side: the attacker's hand is sampled, and each live answer is weighed against holding it for a later link.
- **Its strength:** `DuelPrefs.aiStrength` (Fast / Strong / Max) sets the budget: 5,000, 25,000 or 150,000 engine moves a
  decision, and K = 8, 16 or 32 worlds. The setting already exists and means effort for a language model. For the search
  it means moves, said in the same place.
- **Committing.** A search seat's engine moves are committed at a local match table through `ShortcutResult.commit`, with
  `Provenance(ai, aiSeat, aiKnows = self, engine = "search-vN")`. The network keeps `NO_SHORTCUTS`.
  - A choice the other seat makes inside a move goes to that seat's player (`MatchPlayer.decide`).
  - A player without a `decide` gets the engine's first legal answer, said in the log.
- **Where it plays:**
  - **Ai vs Ai:** a seat's connection may be **Search**, which needs no key.
  - **At kai's own table:** Search is chosen beside the connections. kai answers Search's choices in the Shortcut window.
  - **The Lounge:** after the ladder has rated it.
- **Its records:** `ResultSeat.engine = "search-vN"`, with its strength in `model`, so the ladder can count it.

**As built (E.3).** Where the build went another way, and why:
- **A search is handed `Seen`, never the duel** (rule 2): the seat's `DuelView`, its own decklist, the engine's state cut to
  the cards the seat can see (`Seen.cut`: what was summoned, sent, Set or used, instances, Levels, negations and waiting
  triggers kept only for those), and the table as it saw it at each of the other seat's passes this turn. The duel's seed
  is its secret (it keys the veils and the shuffles), so the worlds are seeded by **the view's fingerprint** instead. A
  test deals worlds, and decides, from two duels that differ only in a hidden hand, and gets the same both times.
- **`HOLD_BACK` is read off the passes of this turn only**, each from the view at that moment: a hand's veils hold through
  a turn, so a guessed card is put back where the seat saw a card then, and the engine is asked whether it had a move.
- **The other seat answers in the turn's search itself** (`TurnSearch` with `answers`): at its priority, and with its Quick
  Effects at every open table after a move, the worst for the seat, as the stress search plays it. A decision's search is
  **soft**: past its share of the budget an open table is valued as if the turn ended there (a lower bound, since the
  seat may always end it), and each way on gets an equal share, so a small budget still values every way.
- **A choice is carried by meaning** (`Carry`): a world's cards are not the table's, so the choices inside the chosen move
  are made again on the real table by the cards' codes and places, zones and positions by value. The seat is told the
  codes of the cards offered that it may see — its own Deck's in a search of it — and never another's.
- **A turn is played on within a cue**, a decision a move: the search decides again after each move until a window waits on
  the other seat, a chain stands, or its turn is over (the referee ends a turn whose player stops five times in it, a
  rule written for models). It walks the Draw and Standby Phases to Main Phase 1 first; on its own link the other has
  passed on, it makes the engine's pass, which resolves the link with its effect's moves.
- **The other seat's choices inside a search seat's move** are the first the effect allows, said in the log: a language
  model's seat has no way yet to be asked mid-move.
- **Records name the search by connection and model** — "Search", "search-v0 · Strong" — since `ResultSeat.engine` already
  names a seat's player; each of its moves carries `Provenance.engine`.
- **In Ai vs Ai the prior is the field's interaction** as last read, around blanks (or blanks): neither seat is shown the
  other's list, a search seat no more than a model.
- **Strength** is the table's own setting (`DuelPrefs.aiStrength`), as the plan said.

### 4.5 The ladder (`core/duel/search/Ladder`, E.4)

- **Players:** each `search-vN` at each strength; the greedy player (the battle-only greedy of the puzzle baselines, as a
  `MatchPlayer`); each language model's connection; and later, model plus search.
- **Matches:** a fixed seed set (`LadderSeeds`), each seed played from both seats, on the **bench decks**: decks whose
  cards are scripted, so the engine plays all of them. Their coverage is stated, and kai's own decks join with theirs.
- **Ratings:** a Bradley–Terry fit by maximum likelihood over every kept result, drawn games as halves. Elo is anchored on
  greedy at 1,000, each with a 95 % range by the fit's curvature. Puzzles give each version a second number beside it,
  never mixed into the Elo.
- **Promotion:** a new search version replaces the one in use only when it beats its parent on the ladder's seeds by a
  one-sided sign test on the decisive seeds, its 5 % shared among every candidate since the last promotion (`TrainGate`'s
  rule). A version that fails stays on the ladder as a number.
- **Kept:** `<data>/duel/ladder.json` (synced and backed up); results stay `DuelResult`s of kind `ai-vs-ai`.
- **Shown:** a Ladder section in Test scores (Trust), and `duel_ladder` for Ai.

### 4.6 IS-MCTS and the model inside the search (E.5)

- **IS-MCTS where the worlds disagree.** If the best candidate differs between worlds and the regret's spread passes a
  bound, the decision is searched again as one tree over the seat's information sets (single-observer IS-MCTS, after
  Cowling, Powley and Whitehouse 2012):
  - each pass through the tree deals a world;
  - their nodes offer only the answers that world holds;
  - nodes are chosen by UCT;
  - leaves are valued by `TurnValue` after a short completion.
  It is held to a set of **play-around positions** with known answers: a starter that Ash stops beside a bait that draws
  Ash out, and a line that keeps a card until the answer is spent.
- **The value function, tuned on won games.**
  - `ValueWeights` and `HOLD_BACK` are tuned by matches of the search against itself on the ladder's seeds. The tuning is
    coordinate ascent with a fixed seed, its steps bounded.
  - A tuned set becomes a new version only through the ladder's gate.
- **The language model inside:**
  - **`duel_search`**: Ai at the table asks the search for its best moves with their numbers ("lead with Scout: through
    Ward in 14 of 16 worlds"), and plays or explains from that.
  - **The opponent's likely deck:** Ai names it, and it becomes the prior's first source.
  - **`value_propose`**: proposed weights, kept only when the gate passes.
  None of it lets Ai pick a move unchecked. Measuring the model's part needs a key, which is said in the notes.

---

## 5. The releases

One at a time, each shipped on both tracks: everything here is `:core` and shared screens. The numbers are the next
patches as of today (Neue 1.1.69, APK v1.4.45).

| # | Neue / APK | What it does | Stored data | Size |
|---|---|---|---|---|
| E.1 | 1.1.70 / v1.4.46 | **Stress tests** (M2): `StressDeal`, `StressSearch`, `StressResult` (guarantee, strategy, choke points, fallbacks, cost), lingering negation, the suite, `through` written, the Mapper's Stress tab, `mapper_stress` | `suite.json`; `through` filled | L |
| E.2 | 1.1.71 / v1.4.47 | **A whole turn**: `BattlePlan`, `TurnSearch`, `TurnValue` v0, the puzzles solved (`PuzzleBaselines.search`), Solve from here, `duel_solve` | none | M |
| E.3 | 1.1.72 / v1.4.48 | **The player**: `Worlds`, `SearchPlayer` (decisions by regret, responses, strength as moves), the door, engine moves at a local match table, Search as a seat in Ai vs Ai | `engine` on a move's provenance | L |
| E.3b | 1.1.73 / v1.4.49 | **Search at kai's table**: the door over the duel in play, Search chosen beside the connections, kai answering its choices in the Shortcut window | a `DuelPrefs` field | M |
| E.4 | 1.1.74 / v1.4.50 | **The ladder**: `LadderSeeds`, the bench decks, Bradley–Terry with ranges, greedy as anchor, the gate, Test scores' Ladder, `duel_ladder`. Search v1 against v0 and greedy, measured | `ladder.json` | M |
| E.5 | 1.1.75 / v1.4.51 | **Play-around and the model inside**: IS-MCTS where worlds disagree, the play-around set, tuning on won games, `duel_search`, the opponent's prior from Ai, `value_propose` | value versions under `<data>/duel/search/` | L |

**Done when, release by release:**
- **E.1:** "through Ward, N interruptions guaranteed" reads on the bench deck's boards. A test holds a hand whose
  guarantee line is not its unopposed best line (bait, or holding a card). A complete stress test, mapped again with any
  move order, gives the same guarantee.
- **E.2:** the search solves the 17 puzzles within their budgets and proves the wrong lines have no fix. A turn with a
  battle is planned and replays to the same table.
- **E.3:** two search seats play a match to the end; the red team finds no input outside each seat's view; and the same
  seed gives the same game on one thread or many.
- **E.3b:** Search plays kai's own duel as Ai's seat, a move at a time, and kai answers the choices its moves put to kai.
- **E.4:** the ladder shows search v1 above greedy and v0 with ranges that do not overlap, or says honestly that they do.
- **E.5:** IS-MCTS solves the play-around set, which determinised search does not, and a tuned value version passes the
  gate. If it does not, the release says so.

---

## 6. Budget

- The engine runs at about 29,000 moves a second warm on one desk core (M.md §8), and the plans assume a fifth of that on
  a phone.
- A stress test of a starter against one Ash-like card is the starter's map times the windows where Ash is live, plus
  alpha-beta's savings. `StressBenchTest` measures it on the bench deck in E.1, and the numbers go in the release notes.
- **A decision's budget** is counted in moves (§4.4). Worlds are searched on `Dispatchers.Default`, one worker per core
  less one, and merged in world order.
- **A whole match** between two search seats at Strong is about 25,000 moves a decision and about 20 decisions a turn. On
  the desk, a 12-turn match takes minutes, so the ladder runs off the frame thread with progress and Stop, like the
  Mapper's runs.

---

## 7. Stored data

- `<data>/effects/mapper/<deck>/suite.json` (E.1): the stress suite. Synced, backed up and deleted with the deck, like the
  library beside it. Stress results go into the library's existing `through` field.
- `ResultSeat.engine` (E.3): already a field. It names `search-vN` for a search seat, so nothing new is stored.
- A `DuelPrefs` field for the player chosen at kai's table (E.3). It trails, with a default, and it is sorted into
  `SyncedPrefs` and described in `AiSettings`.
- `<data>/duel/ladder.json` (E.4): synced and backed up.
- `<data>/duel/search/value-<n>.json` (E.5): the tuned weights, each with the ladder verdict that promoted it.
- Every shape goes into `OldDataTest` in the release that writes it.

---

## 8. Decisions (the defaults this plan assumes)

| # | Decision | Default |
|---|---|---|
| 1 | Worst case or expected? | **Both, in their places.** Stress tests are the worst case, as M.md decided. The player chooses by expected regret over its worlds. |
| 2 | May the other seat answer in the open state? | **Yes, in stress tests**, as the engine's house ruling allows Quick Effects when asked. At a real table the opponent's own player decides. |
| 3 | Battle in the engine? | **Not yet.** `BattlePlan` reads printed numbers through `DuelBattle`, and battle effects stay inert until the vocabulary grows a battle family. |
| 4 | Search at kai's table | **Offered beside the connections**, chosen by kai. It needs no key. |
| 5 | Version 0's value weights | **Written down and rated** as version 0, then replaced only by tuning that passes the gate. |
| 6 | Which decks the ladder plays | **The bench decks** (fully scripted) first, then kai's decks with their coverage stated. |

---

## 9. Out of scope

- Training a network to play. M3's network is a move prior; the search uses it when one is promoted and never needs it.
- Writing effects for cards no one asked for.
- Engine moves at a networked table: `NO_SHORTCUTS` stays.
- ReBeL, Student of Games and other cluster-sized methods (`AI-INTELLIGENCE.md`).
