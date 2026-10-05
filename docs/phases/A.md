# Phase A: Trust (1.0.99) — Settings shows it as **Test scores** since the design review (finding 8)

**Problem.** Nothing measures how good Ai is. A connection's model is trusted or not on impressions. Nobody knows how many
of Ai's mistakes the fact-checker catches, so a change to the harness, a skill or a prompt cannot be shown to help.

**What it builds:**
1. **`core/ai/eval`, pure and tested:**
   - `EvalItem`: a question, how its answer is graded, and the set it belongs to.
   - `Grader`: code, never a model. A number at a stated precision, a yes/no ruling, a decklist read back card by card,
     or words that must and must not appear.
   - `EvalSets`: the built-in sets.
   - `EvalScore`: pass@1, pass^k over k tries, cost and time.
   - `EvalLog`: every run kept per connection.
2. **The sets:**
   - **Hand odds (40).** Generated over a grid of deck size, copies, hand size and conditions. Each answer is computed by
     the app's own exact counter, so the key cannot be wrong.
   - **Rulings and rules (30).** Each question is answered yes or no and carries its source: the game's rules, or
     Konami's FAQ as YGOrganization translates it. Only rulings with a single settled answer are included.
   - **Decklists (20).** Decklist text with the mistakes people make (misspellings, nicknames, "x3" and "3x", sections
     unmarked), read back by card and count. Pictures follow when a picture set can be made reproducibly.
   - **Planted errors (24).** Answers with one known mistake and answers with none, given to the fact-checker.
     - Its catch rate: the share of mistakes it marks wrong.
     - Its false-alarm rate: the share of true claims it marks wrong.
3. **The runner** (`neue/ai/EvalRunner`). It runs a set against a connection with the look-up tools only (card text,
   rulings, calculate, hand_odds, resolve_cards), the way a person's question is answered, and grades each answer. It
   asks before it spends: the item count and an estimate of the cost.
4. **Trust** (Settings › Assistant). For each connection:
   - its last score per set;
   - pass^k where it was run k times;
   - cost and date;
   - the fact-checker's catch and false-alarm rates;
   - Run buttons.

**Stored:**
- `ai/evals/<connection>.json` (`EvalLog`): the runs, newest last, capped at the last 50.
- It is a new file; no older shape exists.

**Done when:**
- The graders and generated sets are tested in commonTest (a key can never disagree with the app's own arithmetic).
- A scripted fake model passes and fails exactly as expected through the real runner (neue jvmTest).
- Trust shows a connection's scores.

**Out of scope:**
- running sets on every push against a live model (cost);
- pictures for the decklist set;
- puzzles (Phase C).

**Added since:**
- **Card truth** (1.1.2, Phase B, `docs/phases/B.md`): 32 questions only the tools answer.
- **Duel puzzles** (Phase C stage 3, `docs/phases/C.md` §5): 17 positions played on tables of their own with the duel tools,
  under a referee, graded on the table (`Grader.Puzzle`, `PuzzleTable`); Trust shows the set's bounds — doing nothing 0, a
  battle-only greedy player 2, the recorded solutions 17.
