# Roadmap

The plan for the whole program from 1.0.98 on, in phases, each built on the one before.
- `docs/AI-INTELLIGENCE.md` is the research and red team this draws on.
- `docs/NEUE.md` is the authority on what the app is today.
- This file is the authority on what comes next and in what order.

---

## 1. Where it stands (1.1.83 / v1.4.59, 2026-10-11)

Rewritten after Phase G part one; brought up to date after Phases E, F and G part two. Section 9 has every phase's status;
this is the picture.

**The size of it:**

| Part | Kotlin | What it holds |
|---|---|---|
| `:core` | 641 files, 120k lines | every rule, model and calculation; 448 test files |
| `:neue` | 302 files, 85k lines | every screen, on desktop, tablet and phone |
| `:table` | 85 files, 16k lines | the duel table and Master UI's kit, shared with the browser (the Lounge) |
| `:builder`, `:androidApp`, `:studio`, `:guest` | 10k lines | the builder's state, the APK shell, the headless renderer, the Lounge's page |

**Ten pages:** Builder, Decks, Siding, Format, Prep, Present, Duel, Ai World, Shootout and Gameplay Mapper. Ai is docked
beside every one. Friends duel at kai's tables from a browser through the Lounge.

**Two release tracks:** the desktop (`neue-v*`, now 1.1.83) and the APK (`v*`, now v1.4.59).

**What is strong:**
- The rules of the app live in `:core` and are tested. Stored data survives every version, and `OldDataTest` holds that.
- Every gesture exists for keyboard, mouse and finger.
- **Ai is measured** (Phase A): Test scores holds seven sets — hand odds, rulings, decklists, planted errors for the
  fact-checker, card truth, duel puzzles and the optimization set — each graded by code, each run kept per connection.
  A number in a guide, a book or a proposal carries its proof.
- **Cards are true** (Phase B): counted by card, legal by region, date and Genesys points, banlists by date.
- **The duel is a measured record** (Phase C): every move says who made it, Ai is held to a guest's sight, and Ai vs Ai is
  two sessions, refereed.
- **The engine plays card text** (Phase D): effects as code for the cards the person asks for, the goldfish over seeded
  hands, and the Gameplay Mapper's library of end boards.
- **Hands are judged** (Phase S): Shootout rates every card with its range, and Ai learns to judge under a trust gate.
- **A change to a deck is measured** (Phase G part one): two versions on the same hands, deck versions and one ledger of
  results, the field's interaction, Side Deck coverage, and Ai proposing a change with its proof.
- **Ai plays by search** (Phase E): stress tests, a whole turn solved, a player over sampled worlds, a ladder of versions.
- **Ai coaches** (Phase F): replays reviewed for misplays, predictions scored, drills from your own mistakes.
- **Ai builds against the field** (Phase G part two): changes played out by simulated matches with their ranges, the Side
  Deck chosen from the evidence, the ratios searched through the field's interruptions, and every applied suggestion held to
  the games after it.

**What is thin:**

1. **Background work could end the app** until 1.1.84: twenty-nine app-lifetime scopes launched work with no handler, and the
   banlist refresh closed the APK that way until 1.1.82. H.1 put every one behind `Background.scope`.
2. **Search can see only what is scripted.** Effects are written for the cards the person asks for (D's decision), so a
   deck's coverage is the ceiling of everything that plays it out.
3. **The engine is fast enough once warm:** 22,750 moves a second in the steady state (H.2's reading, D §5.7), above the
   20,000 the plans assumed; a short search still pays the cold first pass, about 7,000.
4. **The big holders are split** (H.3): `Duels` 1,271 → 1,023 lines (`DuelFocusState`, `DuelLine`, `DuelCombos`), `AiState`
   1,131 → 919 (`AiRun.kt`), `AiHost` 1,691 → 1,130, `NeueApp.kt` 1,438 → 785.
5. **F4's permission tiers** are built (H.4): changes, deleting and reaching outside the app, each Allow, Ask or Off, and a
   limit per connection that only lowers them.
6. **Promised features not yet shipped:**
   - duels across the internet without the Lounge's tunnel (the relay, R5);
   - the Mapper's training (M3; its stress tests, M2, shipped in E.1, and its puzzles, M4, in H.7);
   - the optimization set's baseline per connection (no connection runs where releases are built).

---

## 2. The foundation: rules every phase keeps

The project already follows these. Writing them down makes them the contract for everything ahead.

1. **Ground truth before intelligence.** A feature that makes Ai cleverer ships after the check that can tell whether it did. Code computes, the model proposes.
2. **One log, many views.** State that matters is a fold over an append-only log: the duel, Ai World, sessions, takes. Undo, replay, sync, redaction and audit then all come from the log.
3. **`:core` is the program; `:neue` is its face.** Logic, formats and decisions sit in `:core` with commonTest tests. A screen holds state and draws.
4. **Stored data outlives versions.** Every new stored shape gets an `OldDataTest` case in the same commit. A document format is versioned before it is shared.
5. **Every gesture three ways:** keyboard, mouse and finger, held to each other by tests.
6. **Measure before and after.** Ai changes are scored on the evaluation sets. Speed changes keep the same pixels and prove it. Layout changes keep their studio shots.
7. **Ship every phase.** A phase is one to three releases, never a branch that waits.
8. **Licences stay clean.** The app is MIT. No GPL or AGPL engine or card scripts are bundled or linked. No scraping where the terms forbid it.

---

## 3. Foundation tracks

Six tracks run underneath every phase. Each phase below names the track work it needs first.

### F1. Verification and evaluation
- **Evaluation sets** in `core/ai/eval`. Each item has an input, an expected answer and a grader. Graders are code wherever possible: exact numbers, rulings with known answers, decklists read from pictures, puzzle positions.
- **A runner** that plays a set against a connection and reports:
  - pass@1 (how often one try passes) and pass^k (how often all k tries pass);
  - cost and time;
  - a regression set that must stay near 100 %, kept apart from the capability set.
- **Planted errors** for the fact-checker: answers with known mistakes, scored by how many it catches.
- **Prediction scoring**: every probability Ai states about a game is logged, then scored against what happened.
- **CI** runs the code-graded sets against a recorded model (fixtures) on every push. Live runs happen on demand.

### F2. Architecture health
- **Tools register themselves.** Split `AiHost` into one handler per tool group (deck, memory, meta, duel, present, world, prep), all behind one `ToolRegistry`. `AiToolsTest` keeps checking that every tool is offered and answered.
- **Thin the big holders.** `NeueApp`'s page routing and window wiring go to their own files, and `Duels` and `AiState` keep moving parts out. This follows the 1.0.91 pattern: owned classes, with forwarders only where outside code needs them.
- **One list per fact**, everywhere it is not yet. Lists of modes, lists of deck tools, and rules about which folders are private each live once and are read by everything.
- **A dependency check in CI**: `:core` must never import `:neue`, and `neue/duel` must never reach into `neue/ai` except through declared seams.

### F3. Data and formats
- **Versioned documents.** Every JSON document the app writes carries a `version`, and every reader is forgiving. `OldDataTest` grows one case per shape.
- **Card identity.** Every passcode maps to a canonical card, so alternate arts count as one card. The card also carries its Konami ID, TCG and OCG release dates, its format (TCG, OCG, Genesys, OCG-only) and its banlist history with dates. This is Phase B.
- **Exchange formats get specifications**: `.ydkx`, `.ydkw`, `.nmtbackup`, the replay format and the QR parts. A specification is a short document plus a round-trip test, so other tools and future versions can read them.

### F4. Security and privacy
- **A threat model**, `docs/SECURITY.md`. It lists:
  - what Ai may do unattended, with confirmation, or never;
  - what outside text can reach;
  - what each sandbox (Rhino, Python, the CLIs) contains.
- **Permission tiers for Ai's tools**: read, write, destructive and outward. The setting chooses tiers, not single tools. Safeguards stay the person's (`AiSettings.GUARDS`).
- **CLIs run in an empty working folder**, keys move out of `<data>/ai`, and the MCP token is per launch, in memory only, with an exact Origin check.
- **A red-team pass before each phase ships**, as in 1.0.97 and 1.0.98. Its findings are held in tests.

### F5. Observability
- **A local trace of every Ai run**: rounds, tool calls, cost, time, retries and refusals. Kept for days and shown in the Context panel. This is the source for evaluation reports and bug reports.
- **A diagnostics bundle** in Settings. It holds the crash file, recent traces with conversations removed, versions and device. One file to send when something goes wrong.
- **Frame and memory budgets** checked in CI on studio runs of the heaviest pages.

### F6. Release engineering
- **The emulator walk checks every page**, Ai World's JavaScript included, and compares its screenshot.
- **Studio shots in CI** for the builder, duel, world and present pages, compared to the last release, so a layout change is seen before it ships.
- **Mac signing and notarisation: not done, on kai's word (2026-10-11).** The Mac builds stay unsigned; the switch stays
  built and off.
- **Version runway.** Neue is at 1.1.69 and the APK at v1.4.45. Neue's patch reaches 100 in 31 releases, which would
  open 1.2.0; the APK's in 55. A minor bump before that is kai's call.

---

## 4. The phases

Every phase lists:
- **Goal:** what it is for;
- **Builds:** what it adds;
- **Needs:** the track work and phases it depends on;
- **Done when:** a test you can check;
- **Size:** in releases.

### Phase A: Trust (1.0.99)
- **Goal:** know how much to trust Ai, and close the last confirmed gaps.
- **Builds:**
  - **F1's first sets:**
    - 40 hand-odds questions with exact answers;
    - 40 rulings questions with known answers, from YGOrg Q&A and house rulings;
    - 20 decklist pictures;
    - planted errors for the fact-checker.
  - **A Trust page in Settings** (now **Test scores**): each connection's scores, cost per question, and when it was last tested.
  - **F4's three fixes:** an empty working folder for the CLIs, keys moved out, and the MCP token in memory.
  - The reader's guide marks itself stale when the deck changes. The rulings cache keeps only replies it could read.
- **Needs:** nothing; it starts the foundation.
- **Done when:** every connection has a score; the fact-checker's catch rate is a number; the security findings are closed and held by tests.
- **Size:** 1 release.

### Phase B: Card truth (1.1.0–1.1.2)
- **Goal:** the card data everything else stands on is correct.
- **Builds:**
  - **`CardIdentity` in core:** passcode to canonical card, alternate arts as one card, and the Konami ID (YGOPRODeck's `misc_info.konami_id`).
  - **Formats and dates:** TCG and OCG release dates, OCG-only marked, Genesys points. Legality checks a card's release as well as its banlist status.
  - **Banlist history by date**, from Yugipedia's lists template. Any past format can be replayed: the builder, odds, the field and Ai World all accept "as of" a date.
  - **The field cleaned up:**
    - lists illegal under the current banlist dropped;
    - the tier cap made honest;
    - clustering that keeps hybrid decks apart.
  - Copy limits, `hand_odds` and the field all count by card, not passcode.
- **Needs:** F3 (versioned documents), Phase A's evaluation runner (a regression set for legality and odds).
- **Done when:**
  - an alternate-art Ash counts against the three-copy limit;
  - an OCG-only card is illegal in TCG;
  - "odds as of the March list" works;
  - every red-team "real-world data" lead is closed.
- **Size:** 2 to 3 releases.

### Phase C: The measured duel (1.1.3–1.1.5)
- **Goal:** every duel is a record you can measure, and Ai at the table sees and does only what a player would.
- **Builds:**
  - **Provenance on every move:** who moved, which seat Ai holds, what it could see, and the opening roll. Prep logs going first or second from the roll.
  - **The duel leads, verified and fixed:**
    - Ai's own moves must never reveal or move your hidden cards;
    - the guest's seat on a network table is the guest's;
    - combo records name no hidden card;
    - `at` cannot re-deal draws.
  - **The table in full for Ai:**
    - phase, every public zone, stats and the Extra Deck;
    - the history;
    - a menu of legal moves from `DuelVerbs`;
    - its guide at the table.
  - **Puzzles:** positions with a known best line, checked by `DuelRules`, as an evaluation set.
  - **Ai vs Ai:** two independent Ai sessions, one a seat, each seeing only its own, refereed and watched; Ai World's duel tables a sandbox for scripts (a seed, who goes first, a fork of the live position).
- **Needs:** F1 (puzzle set), F4 (Ai's permissions at the table), Phase B (correct cards).
- **Done when:** "Ai won N of M against kai, with these settings" can be read from the records; the puzzle set has a baseline score.
- **Size:** 2 to 3 releases.

### Phase D: Effects as code (1.2.x): the main lever
- **Goal:** the app can play out a line with the deck's own cards.
- **Builds:**
  - **An effect model in `core/duel/effects`.** A small, typed vocabulary over `DuelRules`:
    - costs and targets;
    - search, summon, send, banish, negate;
    - once-per-turn and conditions;
    - chains and their timing.

    Our own design, never Konami's text or another engine's scripts.
  - **Effects written by Ai, per deck, in Ai World** (`lib/effects/<passcode>.js`), against that vocabulary. Each one is checked by a legality pass.
  - **Each card's effect tested against the deck's record.** Every saved combo (`ComboRunner`) and every replay is a test. A card is *verified* when its tests pass.
  - **Coverage per deck:** how many cards are verified, which are missing, and which tests fail. Shown on the deck's guide and in Ai World.
  - **The goldfish simulator:** thousands of seeded opening hands played out by the verified effects. It reports how often each line gets there and the end boards it reaches.
- **Needs:** Phase C (provenance, replays), F1 (each card's tests become a regression set), F2 (a duel engine split into parts that can take effects).
- **Done when:**
  - a deck's main engine is verified;
  - the goldfish simulator reports "this line gets there N % of the time" with its seed;
  - every number carries its proof into the guide.
- **Size:** the largest phase, 4 to 6 releases. It ships in steps: vocabulary, then authoring, then tests, then goldfish.

### Phase E: Search: Ai plays (done, 1.1.70–1.1.76; `docs/phases/E.md`)
- **Goal:** Ai chooses moves by searching, not by guessing.
- **Planned in five releases** (`docs/phases/E.md` §5): the stress tests (M2), a whole turn, the player over sampled
  worlds, the ladder, then play-around and the model inside the search.
- **Builds:**
  - **Determinised search over the opponent's interruptions**, sampled from the field (Phase B) and weighted by what the opponent has shown. Lines are scored by regret, within a phone's budget of about one CPU second per decision.
  - **IS-MCTS (search over what Ai can actually know)** for play-around decisions, such as which card to lead into a possible Ash.
  - **The model inside the search:** it proposes moves, models the opponent, and writes and tunes the value function from Ai vs Ai matches.
  - **A rating per Ai version:** Elo from Ai vs Ai matches and puzzles, so each change is a number.
- **Needs:** Phase D (a forward model), Phase C (Ai vs Ai matches, provenance).
- **Done when:** Ai plays a whole turn by search; its rating rises version over version; it beats its own pre-search version over a fixed set of seeds.
- **Size:** 5 releases (E.1–E.5).

### Phase F: The coach (done; `docs/phases/F.md`)
- **Goal:** Ai makes you better, and proves it.
- **Planned in three releases** (`docs/phases/F.md` §4): the misplay finder, predictions scored and coaching checked, then
  drills from your own mistakes.
- **Builds:**
  - **The misplay finder:** each decision in a replay scored by how much value it lost, on Lichess-style thresholds, with the better line shown on the board.
  - **Coaching split into small claims**, each checked by an instrument, the rules or the search. Unchecked advice is labelled as opinion.
  - **Drills from your own mistakes**, on the siding drills' Leitner boxes.
  - **Predictions scored:** expected match win checked against logged rounds; Brier scores per matchup.
- **Needs:** Phase E (values to measure a misplay by), F1 (prediction scoring).
- **Done when:** a replay comes back annotated; your measured misplay rate over a month is a chart.
- **Size:** 2 to 3 releases.

### Phase G: The builder (done: part one 1.1.61–1.1.69, part two 1.1.80–1.1.83; `docs/phases/G.md`, `docs/phases/G2.md`)
- **Split on 2026-10-10** by two red teams (`docs/phases/G-REDTEAM.md`, `docs/phases/G-VISUAL-REDTEAM.md`):
  - **Part one, the measured builder,** needed no search. `docs/phases/G.md` planned it in nine releases, all shipped
    (1.1.61 / v1.4.37 to 1.1.69 / v1.4.45).
  - **Part two** is below, planned in `docs/phases/G2.md` in four releases, all shipped (1.1.80 / v1.4.56 to 1.1.83 /
    v1.4.59): simulated series, the side deck chosen, suggestions scored and past formats, the ratios searched.
- **Goal:** Ai builds and sides decks better than the field.
- **Builds:**
  - `optimize` against the field: ratios chosen by goldfish end boards and by the field's interruptions.
  - A side deck chosen from the matchup matrix.
  - Tech choices compared by simulated match win.
  - Any past format replayed for testing.
- **Needs:** Phases B, D and E.
- **Done when:** a change Ai suggests comes with a simulated gain and a confidence interval, and the suggestions are scored against your results.
- **Size:** 2 releases.

### Phase H: Sturdy, and the promises kept (proposed 2026-10-11, after Phase G)
- **Why:** every lettered phase is shipped. On 2026-10-11 the emulator walk showed the APK closing at the banlist refresh —
  a pattern the JDK accepts and Android's ICU refuses, in a background job nothing caught — red on `main` for a day of
  releases because the walk did not run on the working branch. What is left is the app's sturdiness and the features
  promised but not shipped (§1's list).
- **Builds, a release each, in this order unless kai reorders them:**
  1. **H.1 Nothing in the background ends the app.** One way to make an app-lifetime scope (a supervisor, a handler that
     writes the failure to `DiagnosticLog` and says it quietly), a law test that refuses a bare `CoroutineScope(` in shared
     code, and the emulator walk on the working branch before every release. (The regex law, `SharedPortabilityTest`'s
     `everyPatternCompilesOnAndroid`, shipped with the fix in 1.1.82.)
  2. **H.2 The engine at speed:** measured first. The bench now reads the steady state (ten seconds warm): 22,750 a
     second, above the 20,000 assumed — three passes had read 12,800 (D §5.7). The cold pass (about 7,000) and the place
     index rebuilt for every new table are what is left, each behind the memo-against-old test when it is taken on.
  3. **H.3 The big holders split** (F2): `AiHost`, `NeueApp`, `Duels`, `AiState` into owned parts, nothing changed. Begun:
     `AiHost` 1,691 → 1,130 lines (`AiWebs`, `AiMemoryTools`); `NeueApp.kt` 1,438 → 785 (`NeueHolders.kt`, `NeueEffects.kt`);
     `Duels` 1,271 → 1,023 and `AiState` 1,131 → 919 (1.1.88). Built.
  4. **H.4 Permission tiers** (F4): a tier per tool (read, change, delete, outside), each Allow, Ask or Off in Settings ›
     Ai, the person's alone (`ToolTiers`), and a limit per connection that only lowers them (1.1.87). Built.
  5. **H.5 The relay** (R5): duels across the internet without the Lounge's tunnel.
  6. **H.6 The reader's guide:** HTML from the same drawings (`BookHtml`, `SvgInk`) and the person's notes and highlights
     (`BookNotes`), built; the interactive book (puzzle hands answered and graded, checklists kept, odds going first or
     second) in 1.1.87.
  7. **H.7 The Mapper's training and puzzles** (M3, M4). Puzzles built: a position saved from the Duel page as the duel's
     own log, a goal on the boards, solved by the turn search against every card of theirs the solver could see — solved,
     no solution (a proof within what the search plays) or not found; the Puzzles tab, `mapper_solve`. The duel puzzle set
     loads as positions and the goal search solves 15 of 17 (the other two need a change of position or a Flip Summon, not
     among the engine's moves yet). Training (M3) is left: it needs the PyTorch helper on a desktop with a GPU.
  8. **H.8 What happened, written down** (F5): a trace of every run of Ai — rounds, tools, time, retries, refusals, tokens
     and cost, never a word of the conversation — kept two weeks on the device and listed in the Context panel, and a
     diagnostics file in Settings (the log, a caught crash, the traces). Built; Ai vs Ai's and the Lounge's seats traced in 1.1.87.
- **Done when:** each release's own test; for H.1, a job that throws in every app-lifetime scope leaves the app running
  with the failure in the log.
- **Size:** one release each.

### Phase I: The roadmap finished (kai, 2026-10-12)
- **Why:** kai: "start the next on the roadmap that you can do until you finish the roadmap", online duels last. An audit
  of every foundation and product track against the code (2026-10-12) found what each promised and what is left; this is
  that list, in order, a release each. The goldfish as a Shootout judge was dropped the same day (§8).
- **Builds, in this order:**
  1. **I.1 The engine at speed** (H.2's rest): the place index carried from a table to the next instead of rebuilt, the
     chain's refusal check and the `feasible` dry run made cheaper, `withCard` without copying the card map, and the cold
     first pass; every one behind the memo-against-old tests (`FxMemoTest`, `FxSpeedMemoTest`), the bench read before and after.
  2. **I.2 The tablet and the phone:** the deck picture on Android (it says "comes to the tablet in the next build") and
     opening the data folder there.
  3. **I.3 Accessibility:** screen-reader labels and roles for the chrome (`IconButton`, `MuIcon`, `muClickable`), a contrast
     check in `MasterUiLawTest`, and an audit that each page's controls are reached by the keyboard.
  4. **I.4 The formats written down** (F3): a specification for `.ydkx`, `.ydkw`, `.nmtbackup`, replays and the QR parts, each
     with its round-trip test (`.nmtbackup` has none yet), and a `version` on the documents still stored without one.
  5. **I.5 Architecture** (F2): tools registered in one place with a test that every offered tool is answered, the deck
     tools out of `AiHost`, a test of the modules' seams (`neue/duel` reaching `neue/ai` only through declared ones), and one
     list per fact (`AiTools.PAGES` held to `Page`, a mode's facts in one table).
  6. **I.6 Verification** (F1): the regression set kept apart with its near-100 % bar, every set run in CI on a scripted model,
     the probabilities Ai states logged and scored against what happened, and decklists read from pictures.
  7. **I.7 Release engineering** (F5, F6): the emulator walk over every page (Ai World's JavaScript run), studio shots of the
     builder, duel, world and present pages in CI compared with the last release, and frame and memory budgets on them.
  8. **I.8 Security** (F4): `SECURITY.md` for the Lounge and network duels, and the red team on Phases F, G part two, S and H,
     its findings held in tests.
  9. **I.9 Present recording on Android** (`docs/present/RECORDING.md`, "Android: the seam"): CameraX and MediaCodec.
  10. **I.10 The Mapper's training, the app's side** (M3): the helper launched and stopped from the desk, ONNX Runtime as the
      search's move prior, the self-play loop, the gate, the Training tab and Ai's tools, all proved on the CPU. The run that
      trains a network worth promoting needs a GPU and is kai's.
  11. **Last, H.5 the relay**, once kai decides where it runs.
- **Done when:** each release's own test, and nothing on §1's thin list left but what needs kai.
- **Size:** about ten releases.

### Phase S: Shootout, rebuilt on evidence (after B)
- **Goal:** a rating for every card and card pair in a deck, proven by comparing hands. kai: "data proven … smart, reactive,
  and adapting to the user".
- **Builds:** see `docs/phases/S.md`.
  - **Two ratings, kept apart** (kai's decision): **the deck on its own**, and **per matchup**, each matchup split into
    four strata: game one going first, game one going second, sided going first, sided going second. Sided trials use a
    siding plan for both decks (Siding's plans or the deck's preset patterns now; a two-deck siding tool later). The
    strata share a card's value through a pooled prior, so few sided trials still borrow from many game-one ones.
  - **Two trial kinds:**
    - matchup trials on a 5-point scale;
    - comparison trials, where two hands differ by one card.
  - **One model:** per-card values, copies counted with diminishing returns, pair effects, and the person's own noise.
  - **The hand picker:**
    - picks what teaches the most, weighted by real draw odds;
    - mixes in random hands as a check, and corrects results back to real odds;
    - adapts to fatigue and inconsistency, and stops when the ratings are known.
  - **Results:** ratings with ranges, a pair grid, the next copy's worth, and opening patterns; every number opens its trials.
  - **Ai's parts:**
    - starting guesses from the cards;
    - **Ai learns to judge**, through four ways to teach:
      - a calibration set;
      - an interview;
      - apprentice mode (it watches and asks);
      - supervised runs (you correct it).

      What it learns is a rubric, an example bank and the model's prediction. Your notes go into both.
    - **A confidence score per kind of hand**, measured only on hands Ai never learned from. Ai runs alone only where the
      bottom of that range clears your bar, with blind audits, and its answers are weighted by its measured accuracy, so
      they never dilute yours;
    - the write-up, from the numbers.
- **Needs:** Phase B (cards counted by card), the evidence ledger, F1.
- **Done when:** a simulation with known true values shows the picker recovers them in far fewer trials than random hands, and
  its ranges are calibrated; then a real session gives ratings with ranges.
- **Size:** 3 to 4 releases. It is also the first source of positions judged by a person, which Phases E–G are checked
  against.

---

## 5. Product tracks beside the phases

These come in alongside the phases, one at a time, between Ai releases. Each can ship on its own.

| Track | What | When |
|---|---|---|
| **Duel online** | The relay (R5): the same `Wire` over websockets, `DuelHost` on the relay. Spectators. | After Phase C (provenance makes online records trustworthy). |
| **Present recording** | Desktop takes with JavaCV/FFmpeg (LGPL, kept dynamic). The take timeline in `core/present/record` already exists. Android next. | Any time; it needs no Ai phase. |
| **The reader's guide** | Notes and highlights; the interactive book; HTML export from the same drawings. | After Phase A (its numbers are checked first). |
| **Tablet and phone** | The deck picture, a touch form of tooltips, the data folder. | Any time; small. |
| **Mac** | Unsigned, on kai's word (2026-10-11): no signing or notarisation. The switch stays built and off. | Not planned. |
| **Accessibility** | Screen-reader labels for chrome (cards are already named), keyboard reach audited with the help dialog, a contrast check in the law test. | Steadily, a page at a time. |
| **The 3DS** | Stays on its own track, frozen by its golden vectors; it moves only when `:core`'s play-stage packages do. | No plan to change. |

---

## 6. Order and dependencies

```
A Trust ──► B Card truth ──► S Shootout · C Measured duel ──► D Effects as code ──► E Search ──► F Coach
   │              │                 │                    │                 │
   │              └─────────────────┴────────────────────┴────────► G Builder
   └─ F1 evaluation, F4 security (A) · F3 card identity (B) · F2 engine split (C→D) · F5 traces (A→)
Product tracks: Present recording · reader's guide (after A) · Duel online (after C)
```

**One release at a time, in this order:**

1. **Phase A:** 1.0.99 / v1.3.77.
2. **Phase B:** from 1.1.0, interleaved with one product track (Present recording).
3. **Phase S:** its simulation study first, then the model, the screens and Ai's parts.
4. **Phase C**, then Duel online.
5. **Phase D** in its four steps. The reader's guide's notes fit between them.
6. **Phase G, part one** (`docs/phases/G.md`): nine releases, 1.1.61 / v1.4.37 to 1.1.69 / v1.4.45. Shipped.
7. **Phase E** (`docs/phases/E.md`), opening with the Mapper's stress tests (M2), which are its first search.
8. **Phases F and G part two.** Shipped (F: 1.1.77–1.1.79; G part two: 1.1.80–1.1.83).
9. **Phase H** (§4): the app made sturdy, then the promises kept, a release at a time.
10. **Phase I** (§4): the roadmap finished, I.1 to I.10, online duels (H.5) last.

---

## 7. How each phase is worked

1. **A design note first**, in `docs/` (`docs/phases/<letter>.md`). It sets out the problem, the shape of the data, what is stored, the tests that define "done", and what is out of scope.
2. **`:core` first.** The model, the rules and the tests, failing first. `:neue` follows.
3. **A red team before it ships**, through the lenses that fit: security, data, learning, the duel. Its findings are held in tests.
4. **Measured.** The phase's evaluation set is run before and after, and the numbers go in the release notes.
5. **Shipped**, through the rules in `CLAUDE.md`. Stored-data changes are named in the notes.
6. **Written up.** `NEUE.md` describes what is; this file moves the phase to "shipped" and the next to "now".

**Multi-agent work** is used where a phase splits cleanly, as in 1.0.97–1.0.98: one agent per independent piece in its own worktree, plus a red team in parallel.

---

## 8. Decisions that are kai's

These change what gets built. Each has a default the plan assumes until you say otherwise.

| Decision | Default assumed |
|---|---|
| Should Ai ever play people online, or only you and itself? | Only you and itself, until Phase E's rating is known. |
| Which formats matter: Advanced TCG only, or OCG and Genesys too? | Advanced TCG first; OCG and Genesys carried in the data (Phase B). |
| How much a model may cost per evaluation run | Code-graded sets run free on recorded fixtures; live runs only when you ask. |
| Effects as code: Ai writes every card, or you review each card? | Ai writes them, tests verify them, you see coverage; nothing unverified is used by search. |
| A user-installed open engine (EDOPro) on the desktop as an optional oracle, run in a separate process | No. The app's own engine only, for licence clarity. |
| Present recording: desktop only, or Android too in the first release? | Desktop first. |
| Shootout ratings | **Decided:** the deck alone, and per matchup in four strata (G1 first, G1 second, sided first, sided second). |
| Mac signing and notarisation | **Decided** (2026-10-11): not done. The Mac builds stay unsigned (right-click, Open the first time). |
| The goldfish as one more Shootout judge | **Decided** (2026-10-12): not built. Shootout's judges are the person and Ai. |

---

## 9. Status

| Phase | Status |
|---|---|
| 0 Foundation (Ai World, instruments, red team) | Shipped 1.0.97 |
| Evidence ledger, YGOrg rulings, harness fixes | Shipped 1.0.98 |
| A Trust | Shipped 1.0.99 / v1.3.77 |
| B Card truth | **Done**: 1.1.0 / v1.3.78 (card identity, release data, banlists by date, the field read honestly) and 1.1.1 / v1.3.79 (the builder's dated legality and Genesys, the field as of a date, the Card truth set in Trust) |
| S Shootout | **Done**, stages 1–4 (`docs/phases/S.md` §7, §8): the simulation study, model and picker; page `09` (1.1.3: sessions, storage, results); Ai learns to judge — the rubric, example bank and prediction, the calibration set and exam, apprentice, supervised and the interview, Ai's answers as their own judge, the per-kind confidence score, the gate and audits, the trust panel. **Stage 4** (S.md §8, kai's choice 2026-10-11): S4.1 why, in your words (1.1.89: reasons and the deciding card on the hand, the surprise question, fatigue, your reasons counted); S4.2 what the hands say (1.1.90: opening patterns and siding plans compared); S4.3 Ai reads the deck first (1.1.91: Ai's priors, the write-up into the guide); S4.4 is it better? (1.1.92: the ablation runner, judging on a plan's CLI); S4.5 each plan its own deck (1.1.93). Stage 4 done, and with it the phase: the goldfish as one more judge was dropped on kai's word (2026-10-12) |
| C The measured duel | **Done** (`docs/phases/C.md`): stage 1 — provenance on every move, results and "Ai won N of M" (`duel_records`), Prep's first or second, the four duel leads; stage 2 — the table in full for Ai, `duel_moves`, its guide at the table; stage 3 — the duel puzzle set in Trust with its baseline (nothing 0, battle-only greedy 2, solutions 17 of 17), Ai vs Ai — two sessions, one a seat, refereed and watched, its records counted apart ("Ai vs Ai: … beat … N of M") — with Ai World's duel tables a sandbox for scripts, the red team on the phase |
| M Gameplay Mapper | **Steps M0, M1, M2 and M4 shipped** (`docs/phases/M.md`): the foundation, page `10`, `Mapper.run` over seeded hands, the library, Ai's `mapper_*` tools; M2 stress tests as E.1; M4 puzzles as H.7 (the Puzzles tab, `mapper_solve`). Left: M3 training |
| D Effects as code | **Done** (`docs/phases/D.md`): step 1 the vocabulary and engine; step 2 authoring on the person's go, the Effects app and Shortcut at the table; step 3 dropped on kai's word (§11); step 4 the goldfish, trusting what `FxTrust` trusts; then cards that play themselves (`DuelPrefs.autoEffects`) and the engine's bench made honest (about 10,500 moves a second warm, short of the 20,000 assumption: §5.7) |
| G part one, the measured builder | **Done** (`docs/phases/G.md`), nine releases from two red teams (`G-REDTEAM.md`, `G-VISUAL-REDTEAM.md`): G.1 true numbers (1.1.61 / v1.4.37); G.2 same hands (1.1.62); G.3 the builder decides (1.1.63); G.4 Shootout you can read (1.1.64); G.5 the event (1.1.65); G.6 siding with evidence (1.1.66); G.7 finding the replacement (1.1.67); G.8 versions and the loop (1.1.68); G.9 Ai proposes (1.1.69 / v1.4.45). Left: the optimization set's baseline per connection |
| E Search: Ai plays | **Done** (`docs/phases/E.md`): E.1 stress tests (1.1.70), E.2 a whole turn (1.1.71), E.3 the player and Search in Ai vs Ai (1.1.72), E.3b Search at your own table (1.1.73), E.4 the ladder (1.1.74: Search · Fast 1,210 against Greedy's 1,000, clearly ahead of it; not yet clearly ahead of its pre-search version), E.5a play-around (1.1.75: search v1, the other seat's answers as chance, 1,228 — ahead of v0 on 6 deals to 1, p 0.062, so not promoted), E.5b the model inside (1.1.76: search versions as values, Tune, `duel_search`, `duel_opponent`, `value_propose`; the sweep from v0 has passed no step yet, so v0 is still the search in use) |
| F The coach | **Done** (`docs/phases/F.md`): F.1 the misplay finder (1.1.77: Review in the replay bar, marks ?! ? ?? on Lichess's thresholds, the better line, your misplays by month, `duel_review`; win chance fitted on 2,757 decisions, Brier 0.228 against a coin's 0.250), F.2 predictions scored and coaching checked (1.1.78: every logged round scored against what the games before it said; Ai's coaching at the table checked by the instruments, the rules and the search, opinion said), F.3 drills from your own mistakes (1.1.79: each mistake and blunder a drill on the Leitner boxes, its choices the search's own ways) |
| G part two | **Done** (`docs/phases/G2.md`): G2.1 simulated series (1.1.80: two versions of a deck in best-of-three matches against the web's field, paired by seed, the difference in match win with its range, `match_request`, `match_results`), G2.2 the side deck chosen and suggestions measured (1.1.81: Choose for the field from Shootout's hands and your games, played out by series; a proposal's expectation filled from its run), G2.3 suggestions scored and past formats (1.1.82: Ai's applied suggestions held to the games since — inside its range, and Brier of "it helps"; every field read kept by its day; with the APK's banlist crash fixed), G2.4 the ratios searched (1.1.83: ±1 swaps of the engine on the same hands through the field's interruptions at their shares, called by Holm, `deck_ratios`) |
| H Sturdy, and the promises kept | **Done but H.5** (proposed 2026-10-11): H.1 nothing in the background ends the app (1.1.84: every app-lifetime scope `Background.scope`, a failure logged and said, `BackgroundScopeLawTest`; the emulator walk before every APK); H.2 the engine measured at its steady state (22,750 moves a second); H.3 the holders split (`AiHost` 1,130 lines, `NeueApp.kt` 785; `Duels` 1,023 and `AiState` 919, 1.1.88); H.4 permission tiers (1.1.85: changes, deleting and outside the app, each Allow, Ask or Off; a limit per connection, 1.1.87); H.6 the reader's guide as one HTML page, and your notes and highlights in it (1.1.85), and interactive (1.1.87); H.7 the Mapper's puzzles (1.1.85: positions saved with a goal, solved by search against what you saw of their cards, `mapper_solve`); H.8 every run of Ai traced and a diagnostics file (1.1.86; Ai vs Ai and the Lounge, 1.1.87); the rest carried into Phase I |
| I The roadmap finished | **Now** (2026-10-12): I.1 the engine at speed next |
