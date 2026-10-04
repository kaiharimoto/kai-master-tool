# Roadmap

The plan for the whole program from 1.0.98 on, in phases, each built on the one before.
- `docs/AI-INTELLIGENCE.md` is the research and red team this draws on.
- `docs/NEUE.md` is the authority on what the app is today.
- This file is the authority on what comes next and in what order.

---

## 1. Where it stands (1.0.98 / v1.3.76)

**The size of it:**

| Part | Kotlin | What it holds |
|---|---|---|
| `:core` | 339 files, 65k lines | every rule, model and calculation; 267 test files |
| `:neue` | 216 files, 56k lines | every screen, on desktop, tablet and phone |
| `:builder`, `:androidApp`, `:studio` | 7k lines | the builder's state, the APK shell, the headless renderer |

**Eight pages:** Builder, Decks, Siding, Format, Prep, Present, Duel and Ai World. Ai is docked beside every one.

**Two release tracks:** the desktop (`neue-v*`) and the APK (`v*`).

**What is strong:**
- The rules of the app live in `:core` and are tested.
- Stored data survives every version, and `OldDataTest` holds that.
- Every gesture exists for keyboard, mouse and finger.
- The duel is a log of moves, with views redacted per seat.
- Ai has one harness, its tools are complete (`AiToolsTest`), and outside text arrives marked as outside text.
- Since 1.0.98, a number in Ai's guide carries its proof.

**What is thin:**

1. **Nothing measures how good Ai is.** There is no set of questions with known answers, no score per model, and no figure for how many of Ai's mistakes the fact-checker catches.
2. **The card data is shallow.** An alternate artwork has its own passcode and is counted as a different card. There is no release date or format data, and no banlist history.
3. **The duel engine checks physics, never card text.** Nothing can play a line out, so nothing can search one, and Ai cannot really play.
4. **Four holders have grown past what one file should hold:** `AiHost` (1,249 lines), `NeueApp` (1,118), `Duels` (1,103) and `AiState` (945).
5. **Security gaps remain.**
   - The Codex connection's shell can read Ai's key file.
   - The MCP token sits in a plain file.
   - Ai's permissions are all-or-nothing per tool.
6. **Some promised features never shipped:**
   - recording a presentation;
   - duels over the internet (the relay);
   - notes in the reader's guide;
   - the tablet's deck picture;
   - Mac signing;
   - the siding guide's Shootout mode.

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
- **Mac signing and notarisation** once the Apple secrets are added. The switch is already built.
- **Version runway.** Neue is at 1.0.98. The patch reaches 100 in two releases, so 1.1.0 opens Phase B. The APK stays on 1.3.x.

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
  - **A Trust page in Settings:** each connection's scores, cost per question, and when it was last tested.
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
  - **Self-play tables in Ai World:** a seed, who goes first, and a fork of the live position.
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

### Phase E: Search: Ai plays (1.3.x)
- **Goal:** Ai chooses moves by searching, not by guessing.
- **Builds:**
  - **Determinised search over the opponent's interruptions**, sampled from the field (Phase B) and weighted by what the opponent has shown. Lines are scored by regret, within a phone's budget of about one CPU second per decision.
  - **IS-MCTS (search over what Ai can actually know)** for play-around decisions, such as which card to lead into a possible Ash.
  - **The model inside the search:** it proposes moves, models the opponent, and writes and tunes the value function from self-play.
  - **A rating per Ai version:** Elo from self-play and puzzles, so each change is a number.
- **Needs:** Phase D (a forward model), Phase C (self-play tables, provenance).
- **Done when:** Ai plays a whole turn by search; its rating rises version over version; it beats its own pre-search version over a fixed set of seeds.
- **Size:** 3 to 4 releases.

### Phase F: The coach (1.4.x)
- **Goal:** Ai makes you better, and proves it.
- **Builds:**
  - **The misplay finder:** each decision in a replay scored by how much value it lost, on Lichess-style thresholds, with the better line shown on the board.
  - **Coaching split into small claims**, each checked by an instrument, the rules or the search. Unchecked advice is labelled as opinion.
  - **Drills from your own mistakes**, on the siding drills' Leitner boxes.
  - **Predictions scored:** expected match win checked against logged rounds; Brier scores per matchup.
- **Needs:** Phase E (values to measure a misplay by), F1 (prediction scoring).
- **Done when:** a replay comes back annotated; your measured misplay rate over a month is a chart.
- **Size:** 2 to 3 releases.

### Phase G: The builder (1.5.x)
- **Goal:** Ai builds and sides decks better than the field.
- **Builds:**
  - `optimize` against the field: ratios chosen by goldfish end boards and by the field's interruptions.
  - A side deck chosen from the matchup matrix.
  - Tech choices compared by simulated match win.
  - Any past format replayed for testing.
- **Needs:** Phases B, D and E.
- **Done when:** a change Ai suggests comes with a simulated gain and a confidence interval, and the suggestions are scored against your results.
- **Size:** 2 releases.

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
| **Mac** | Signing and notarisation. | When the secrets are added. |
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
6. **Phases E, F and G.**

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

---

## 9. Status

| Phase | Status |
|---|---|
| 0 Foundation (Ai World, instruments, red team) | Shipped 1.0.97 |
| Evidence ledger, YGOrg rulings, harness fixes | Shipped 1.0.98 |
| A Trust | Shipped 1.0.99 / v1.3.77 |
| B Card truth | **Done**: 1.1.0 / v1.3.78 (card identity, release data, banlists by date, the field read honestly) and 1.1.1 / v1.3.79 (the builder's dated legality and Genesys, the field as of a date, the Card truth set in Trust) |
| S Shootout | **Started**: the simulation study, model and picker in core (`docs/phases/S.md`) |
| C The measured duel | **Started**: stage 1 — provenance on every move, results and "Ai won N of M" (`duel_records`), Prep's first or second, the four duel leads (`docs/phases/C.md`) |
| D to G | Planned |
