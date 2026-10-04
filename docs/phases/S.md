# Phase S: Shootout, rebuilt on evidence

**kai's brief:**
- "A data proven rating for each card/card pair in a deck, gathered by comparing hands."
- The hands shown, and the cards in them, chosen smartly, "reactive, and adapting to the user as the runs are conducted".
- Insights, data collection and presentation "equally well thought and engineered".
- Ai a part of it.

**What it replaces.** The legacy tool (`legacy/kai master tool.html`, `ShootoutManager`):
- dealt random hands to you and the opponent;
- recorded win, tie or loss for a number of trials;
- summarised the result.

Random hands spend most trials on what is already obvious, and the result is a win rate, not ratings.

---

## 1. Trials

| Kind | You see | You answer | What it measures |
|---|---|---|---|
| **Matchup** | your hand and theirs, who goes first, game 1 or after siding | clear win · lean win · coin flip · lean loss · clear loss (keys 1–5, a swipe on a phone) | the hand's chance against that opponent |
| **Comparison** | two of your hands, the same opponent and turn | which you would rather open (←/→) | the difference between the two hands |

- **Comparisons usually differ by one card.** That isolates the card's effect, like a controlled experiment, and people judge it faster and more consistently than a hand alone.
- **Optional, never needed for a rating:**
  - a tap on the card that decided it;
  - a reason tag: bricked, interrupted, out-resourced, opponent bricked.

  They explain a rating; they do not make it.

## 1½. Two ratings, and four ways to start a game (kai's decision)

kai: "Two separate ratings, one for the deck on its own, and matchup dependent ratings differ for each matchup, split
also into going first, going second, and post side going first, post side going second."

**1. The deck on its own.** How good a hand is with no particular opponent: does it make its play, does it brick.
- Trials show your hand and the turn, with no opponent's hand.
- The answer is how strong the hand is (the same five points, read as "how often this hand does what the deck wants").
- It is the rating to build a deck by: ratios, staples, bricks. It needs no Format web.

**2. Per matchup.** Each opponent in your Format web gets its own ratings, kept apart, because a card's worth changes
with what it faces (Droll is a different card against a deck that searches).

Each matchup's rating is split four ways, **the strata**:

| Stratum | Your deck | Their deck | Who goes first |
|---|---|---|---|
| **G1 · first** | as built | as built | you |
| **G1 · second** | as built | as built | them |
| **Sided · first** | after your siding plan | after their siding plan | you |
| **Sided · second** | after your siding plan | after their siding plan | them |

**What is shared and what is not.**
- A card is one card across all the strata: a card's ratings in the four are four parameters, but they are **pooled**.
  Each stratum's value starts from the matchup's average for that card and moves away only as the trials say (a
  hierarchical prior). So a few sided trials borrow strength from many game-one trials, and a card whose worth really
  flips between first and second is allowed to.
- The deck-alone rating is a separate model, never averaged into a matchup's. It can seed a matchup's priors, shown as such.
- The picker spends trials where the uncertainty that matters is largest, so a stratum the person cares about but has
  barely tested is the one it fills. The person can also pin a session to one stratum.

**Post-side trials need a siding plan for both decks.**
- **Yours:** the plan for this matchup and turn from your deck's siding (`SidingCodec`'s plans, Format, 1.0.35). When the
  deck has none, the legacy `sidingPatterns` it carries are offered, read only.
- **Theirs:** the opponent's plan for the answering turn (Siding's "how they side against you").
- **No plan yet:** the session offers to make one. A siding tool for both decks side by side (kai: "not built") is the
  better door; until it exists, Siding's editor is it, and a stratum without plans is shown as waiting, never filled
  with game-one hands.
- **The plan is part of the data.** Every sided trial stores the plans' fingerprints (which cards out, which in). When a
  plan changes, the trials under the old plan stay, labelled; the model treats each plan as its own deck for the cards
  that differ and pools the rest. A rating always says which plan it is for.
- **Siding plans compared** (§5) is then the same model asked a new question: the worth of the hands each plan leaves you
  with, in the sided strata.

## 2. The model (`core/shootout/model`)

**One model explains every answer.** A hand's value is the sum of:
- the stratum (game one or sided, first or second; §1½), and for the deck alone just first or second;
- each card's value, with each copy beyond the first worth a declining fraction (a third copy is not a first);
- for each pair of cards, any extra value from the two together, positive for a combo and negative for redundancy;
- minus the opponent's cards' values against it.

The answer depends on that value:
- **Matchup:** the 5-point answer is read as a win chance that rises with the hand's value. Fitted cut-off points turn that one value into the five answers.
- **Comparison:** the chance the left hand is preferred rises with the difference between the two hands' values.

**Priors (where the ratings start):**
- Each card's value starts near the average for its role. Roles come from the deck's groups and Ai's reading of the cards.
- Pair effects start at zero and are held there tightly, so a pair effect must earn its place.

Priors are weak, and they are shown to the person.

**The fit (`core/shootout`, plain Kotlin, no new library):**
- Newton's method finds the most likely ratings given the priors.
- A Laplace approximation (the curvature at that point) gives the uncertainty around each one.
- About 25 cards plus the pair terms in play fit in milliseconds, and it refits after every answer.

**Reported values:**
- A card: the change in win chance from one more copy in hand, averaged over the hands it really appears in, with an 80 % and a 95 % range.
- A pair: the extra chance from holding both, beyond the two cards' own. Shown only once its 95 % range excludes zero.

## 3. Choosing the next trial (`core/shootout/select`)

1. **Show the trial that would teach the most.** For each candidate trial, estimate how much it would shrink the uncertainty on the ratings that matter. Weight each rating by how often its card actually appears in an opening hand (the real draw odds), so rare cards do not soak up trials.
2. **Candidates:**
   - opening hands drawn as a real shuffle would, a few hundred per step;
   - one-card variants of hands already judged (the comparison pairs);
   - opponent hands drawn from their deck in your Format web.
3. **Skip what is already known.** A hand the model calls with near certainty, inside a tight range, is not shown, except as a calibration check.
4. **Keep it honest:**
   - **Random hands:** about one trial in six is a plain random opening hand, so the model is tested against reality, not only against its own choices.
   - **Real-world rates:** reported win rates are corrected back to how often each hand really occurs, so choosing hands deliberately does not skew them.
   - **Balance:** first and second, and the opponent's interruptions, are kept in balance.
5. **Adapt to the person:**
   - **Repeats:** a few hands come back unannounced. The answers measure the person's own noise, which is fitted with the rest.
   - **Fatigue:** rising answer times and falling consistency mean tiredness. The trials switch to quicker comparisons, the noise model widens, and stopping is suggested.
   - **Disagreement:** an answer far from the model's prediction is kept, and offered for one question of why (see Ai, below).
6. **Stop when it is known.** A session shows what it has settled, for example "21 of 24 cards known within ±5 points" (the 95 % range; ±2 is out of reach for a judge as noisy as the simulation's, see below). It can end at any moment with every answer kept. Ratings carry over between sessions per deck and matchup.

## 4. Proving the method first (`core/shootout/sim`, commonTest)

Before any screen is built, a simulation:
- a synthetic deck with known true card and pair values;
- a simulated judge with noise, a 5-point scale, fatigue and occasional slips.

It must show:
- **Recovery:** the adaptive picker recovers the true ratings within a target error in far fewer trials than random hands.
- **Calibration:** the reported ranges contain the truth as often as they say (80 % ranges about 80 % of the time).
- **No bias:** the reported win rates are not skewed by choosing hands deliberately.
- **Pairs:** pair effects are found when they exist and not invented when they do not.

The same simulation tunes the settings: the share of random hands, how tightly pair effects are held at zero, and when to stop. The tests then hold those numbers so a later change cannot quietly make the method worse.

## 4½. Simulation results (stage 1)

Stage 1 is built in `core/shootout` (pure Kotlin, no new library, no screens):
- `math/`: the matrix and Cholesky solve/inverse with jitter, the logistic curve;
- `model/`: hands and decks, the trials, the hand's value, the priors as one Gaussian, the likelihoods with exact
  derivatives, the fit (`Fitter`), and the reported numbers (`Reporter`, `Ratings`);
- `select/`: the picker, its targets, the real-world check (`RealWorld`) and the stop rule;
- `sim/`: the synthetic matchup (`SyntheticDeck`), the simulated judge (`SimJudge`) and the study harness (`Study`).

Its tests are `ShootoutMathTest`, `ShootoutModelTest`, `ShootoutPickerTest` and `ShootoutSimulationTest`.

### The set-up

- **The matchup:** 40 cards, 24 different (six three-ofs, four two-ofs, fourteen one-ofs) in four roles, against a
  40-card deck of 17 different cards; game one first and second (two strata). True values are drawn from the model's
  own priors around role averages the model is not told. Three of twelve named pairs are real: two combos (+0.9 and
  +0.7 log-odds, worth 10–16 points) and a redundancy (−0.6).
- **The judge** is harder than the model assumes:
  - noise in two parts, misreading the situation and misreading the hand, together a logistic of scale 0.6: a hand
    that wins 60 % is read, two times in three, as anywhere from 34 % to 82 %;
  - 3 % of answers are random keys;
  - fatigue: by trial 300 the noise is 30 % larger and the bands have drifted 0.1 toward calling hands worse.
- **The baseline** is the same model fed plain shuffled hands, as the legacy shootout dealt them, so the two arms
  differ only in how hands are chosen.

### What the simulation changed in the design

1. **The person's bands anchor the scale.** With every judge's cut-offs fitted, the scale was not identified, and the
   priors shrank it: the cut-offs collapsed toward each other and the card values came out a third of the truth. The
   person's blind answers are now the reference judge: their cut-offs are the bands' edges (20/40/60/80 %), only their
   noise is fitted, and other judges (Ai, the person after seeing Ai) get cut-offs of their own measured against them.
2. **The noise is fitted on what the fit does not yet know.** At the joint mode a young session (fewer answers than
   parameters) explains every answer exactly: the fitted noise fell to a tenth of the truth by trial 80, and the 80 %
   ranges held the truth half the time. The noise is now fitted, in turn with the values, to the answers' likelihood
   averaged over each hand's remaining uncertainty (variational EM), and coverage came back to about 80 %.
3. **No slip term.** A "this answer, or a random key" mixture let the fit call the judge noiseless and every miss a
   slip (the fitted noise fell to a twentieth). It stays as a seam for a judge with fixed noise, off by default.
4. **The picker builds hands.** Choosing the best of a few hundred dealt hands gained little (about 1.1×). Improving
   the best one a card at a time (each card of either hand swapped for any card left in its deck, six rounds) is
   where the gain comes from: the hand shown is any hand the decks can deal, and the reports average over real odds.
5. **Comparisons are not chosen on merit, in this judge.** A rating informs about eleven parameters at once (five or
   six cards, the opponent's six); a one-card comparison informs two. Even with comparisons judged as steadily as
   ratings, the picker rarely prefers them, and making one trial in five a comparison did worse than one in ten (4.57
   against 4.35 points after 200 trials). §1's claim that people judge comparisons more consistently is therefore a question for real
   sessions: one trial in twenty is a comparison, so the person's comparison noise is measured, and the picker uses
   them as soon as they are worth it.

### The tuned settings

| Setting | Value | Why |
|---|---|---|
| Plain shuffled hands | 1 in 6 | The real-world check needs them. 1 in 10–12 made no measurable difference to the card error. |
| Repeats | 1 in 20, at least 8 trials old | They measure the person's noise. |
| Comparisons | 1 in 20, beyond that on merit | See 5 above. |
| Candidates | 240 dealt hands, 40 comparisons, 6 rounds of one-card improvement | 400 dealt hands or 12 rounds were no better. |
| Pair prior | ±0.5 log-odds | ±0.3 found none of 36 real pair-strata; ±0.5 found 12 with no false one of 108; ±0.8 found 10 with 6 false. |
| Pairs in the picker's aim | weight 1 (by how often both are drawn) | Weight 0 gave cards 13 % less error after 300 trials but found 4 of 48 real pairs, against 24 of 48. kai's brief is cards *and* pairs. |
| Stop rule | 21 of 24 cards within ±5 points (95 %) | What a few sessions reach at this noise (below). |

### The numbers

The test (`ShootoutSimulationTest`, four matchups: adaptive 200 trials, plain 300, deterministic) and a wider sweep (eight
matchups, 300 trials each arm):

- **Recovery** (draw-weighted root-mean-square error of the cards' worth, in points):
  - trials to reach 5 points: the test's adaptive picker 90 on average, plain 168: **1.86×**. The sweep: 111 against
    158 (**1.42×**); to 6 points 75 against 94 (1.25×); to 4 points 210 against more than 240 (at least 1.14×).
  - mean error after 100 trials 5.05 against 5.90; after 200, 4.05 against 4.43; after 300, 3.57 against 3.58.
  - **The gain is early.** Later the picker spends trials on the pairs (it finds twice as many), so on cards alone
    plain hands catch up by 300. The test holds 1.4× and 0.92× the error at 200 trials.
- **Calibration:** the 80 % ranges held the truth 79.2 % of the time and the 95 % ranges 94.8 % (the test's eight runs,
  384 ratings). The sweep: 75 % for the adaptive picker, 82 % for plain hands. Held at 72–88 % and 88–99 %.
- **No bias:** the matchup wins 65 % of real hands. The plain average of the answers to the chosen hands reads
  **4.8 points low** (5.5 in the sweep; every stratum low), because the picker shows close calls. The model's rate,
  averaged over real hands by their real odds, is off by −0.1 points on average (−0.6 in the sweep), and the plain
  hands' check (`RealWorld`) by −0.5. Held at ±2, ±2.5, and the naive average at least 2.5 low.
- **Pairs:** the adaptive picker found 10 of 24 real pair-strata in the test and 24 of 48 in the sweep (plain hands 4
  and 14), and showed 2 of 72 and 4 of 144 null ones: a false-discovery rate of 12.5 % and 14 %. Held at 25 %, with
  at least a quarter of real pairs found.
- **The stop rule keeps its word:** after 200 adaptive trials 6–10 of 24 cards were known within ±7 points, and 77 of
  those 79 really were (97 %). Held at 90 %.
- **Runtime:** the whole study test takes about 18 s on the JVM; a fit plus a choice costs about 12 ms per trial at
  200 trials, so the model is refitted after every answer as §2 planned.

### What it means for the next stages

- **Sessions add up.** At this judge's noise, 21 of 24 cards within ±5 points needs several hundred trials: three to
  five ten-minute sessions per matchup, both turns. Ratings must carry over (§5's storage), and the session's
  "settled" line must show progress, not promise an end.
- **A steadier person needs fewer.** Error falls as the noise does; the person's measured noise (repeats) should be
  shown, and is the first number to watch on real sessions.
- **Comparisons** are kept honest by measurement, not assumed better (5 above).

**Left for stage 2:** the trial screens (keyboard, mouse, finger); storage (`<data>/shootout/…`, versioned, with its
`OldDataTest` case); card identity through `CardIdentity` and roles from the deck's groups; siding-plan fingerprints
and per-plan cards for the sided strata; the opponent's cards' per-stratum deviations; fatigue detection from answer
times; the reason tags and decisive card; Ai's parts (§6, §6½), for which `ModelSpec.judges` and the reference judge
are the seam.

## 5. Results and how they are shown

- **Cards:**
  - each card's rating per copy, with its range;
  - how often it is drawn, and how often it is dead in hand;
  - a verdict only where the range supports one ("cut one copy: the range is wholly below zero").
- **Pairs:** a grid, in ink, of only the pairs whose range excludes zero, each with how many trials back it.
- **Counts:** the ratings combined with the real draw odds give the next copy's worth, for example "a third Ash is worth +0.8 points; a second Veiler +0.3".
- **Opening patterns:** which kinds of hand win the matchup, first and second.
- **Two views, never mixed:** the deck alone, and each matchup with its four strata side by side (a card's value as
  G1 first · G1 second · sided first · sided second, each with its range and its trial count).
- **Siding:** plans compared through the same model, on the hands they leave you with, in the sided strata.
- **Every number opens its trials.** Saved into the deck's guide, a number carries its proof (evidence ledger, `Proof(tool = "shootout", …)`).
- **Storage:**
  - `<data>/shootout/<deck>/alone.json` (the deck on its own) and `<data>/shootout/<deck>/<matchup>.json` (one per
    opponent, its four strata inside): the trials, the append-only log, and the fit read from it. Synced and backed up.
  - Every trial stores its stratum and, when sided, both plans' fingerprints.
  - A versioned format with an `OldDataTest` case.

## 6. Ai's parts (the person stays the judge)

1. **Priors from the cards.** Ai reads the deck (the card web, roles, combos) and proposes role groups and plausible pairs. These narrow the priors; they are shown and can be overridden.
2. **A second judge, earned.**
   - Ai answers each trial only after the person has, so it cannot anchor them.
   - Agreement is measured per kind of hand.
   - Where Ai agrees with the person most of the time, it may take the easy trials, and the picker sends the person the hard ones.
   - Its answers are stored apart, labelled, and given a weight from its measured agreement.
3. **Asking why.** On an answer the model did not expect, one short question; the answer becomes a tag and, where it holds, a guide entry with its proof.
4. **Writing it up**, from the computed numbers only, held by the evidence ledger.
5. **Later, a simulated judge (after Phase D):** the goldfish simulator plays the hand out as one more judge, weighted by its own measured agreement.

## 6½. Teaching Ai to run it (kai, after the first draft)

kai's ideas:
- teach Ai with a preset data set, an interview and written notes;
- a supervised run where the person corrects Ai as it goes;
- an apprentice mode where Ai watches and asks questions to check its own knowledge;
- a confidence score, so the person knows when Ai can run alone "without muddying the data with misjudgements".

**What Ai learns, and where it is kept.** No model weights change; three things do:
1. **The rubric:** how this person judges this matchup, in words. "A hand with a starter and a hand trap beats their turn
   one unless it is Ash-only."
   - It is written from the interview and the person's notes.
   - It is kept beside the guide (`shootout/<deck>/<matchup>.rubric.md`) and reviewed like the guide.
   - Under the evidence ledger, any number in it carries its proof.
2. **The example bank:** every trial the person judged. The most similar ones (by cards, roles, turn and the opponent's
   interaction) are shown to Ai with each new hand.
3. **The model's own prediction:** the card ratings so far, given to Ai as one input among the others.

The person's note on a trial ("this only wins if they have no Imperm") goes with that trial into the example bank. The
recurring ones are offered for the rubric.

**The four ways to teach**, all feeding the same three things:

| Mode | The person | Ai | What the data is marked |
|---|---|---|---|
| **Calibration set** (the preset) | judges a fixed set of 24–40 hands, chosen by the picker to cover the matchup's kinds of hand (with and without a starter, interaction or none, first and second) | answers the same set blind afterwards, as its first exam | the person's, blind |
| **Interview** | answers Ai's questions about how they judge, with chips and words | writes the rubric as it goes; reviewed on Finish | rubric only, no trials |
| **Apprentice** | judges as normal, never shown Ai's answer first | predicts silently. Where it disagreed or was unsure it may ask one question after the person answers, at most one every few trials | the person's, blind; Ai's prediction kept apart |
| **Supervised** | sees Ai's verdict and reason, and accepts with one key or corrects | judges first, explaining in one line | the person's, **seen** (they saw Ai's answer, so it may be anchored) |

**Keeping the data clean.**
- **Every answer is labelled:** by the person blind, by the person after seeing Ai, or by Ai alone.
- **Each judge has its own measured accuracy.** The rating model treats each kind as its own judge, with its own noise and
  its own lean toward win or loss, estimated from where they overlap (Dawid–Skene). So:
  - an Ai answer counts only as much as Ai has shown it can be trusted;
  - a consistent lean (always a little optimistic, say) is measured and corrected, not averaged into the ratings.
- **Supervised answers are kept, but marked.** Their trust is measured against the person's blind answers on the same
  kinds of hand. If seeing Ai's verdict first moves the person's answers, it shows and they count for less.

**The confidence score: when Ai may run alone.**
1. **Agreement is measured only on hands Ai never learned from.** Each blind answer by the person is compared with
   what Ai would have said, using only the examples and rubric it had before. A score from its own examples would
   flatter it.
2. **Per kind of hand, not one number.** Agreement is counted per kind (first or second, starter or none, the
   opponent's interaction), each with a range from its count. For example:
   - "Agrees with you 93 % (87–97 %) on going-first hands with a starter."
   - "Not yet on bricks into interaction: 64 %, 25 hands."
3. **Ai's own certainty is scored too.** Ai states how sure it is, and that is checked against the person's answers. A
   well-calibrated Ai can be routed by its certainty: it keeps what it is sure of and sends the rest to the person.
4. **The gate.** Ai runs a kind of hand alone only when the bottom of its agreement range is above the person's chosen
   bar (90 % by default) and it is itself sure. Everything else goes to the person.
5. **Audits keep the gate honest.**
   - A share of Ai's solo hands (one in ten, more early on) come back to the person blind.
   - Two misses beyond the range close that kind again until it is earned back.
   - When the deck or the opponent's deck changes, every kind is re-earned from a short calibration set.
6. **What the person sees:** a trust panel per matchup.
   - **The open kinds:** each one Ai may judge, with its agreement range.
   - **The audit record.**
   - **How much of the data is Ai's:** "38 % of trials judged by Ai, weighted to 27 % by its accuracy".
   - **What Ai's answers moved:** how much each card's rating would change if they were taken out. A large change is
     flagged for a look.

**Is it better?** Every change to how Ai learns (the rubric, the example bank, the model's prediction) is measured on the
person's held-out blind trials, by agreement and calibration, with and without each piece (ablation). So "exponentially
better" is a number, the same discipline as Trust (Phase A), whose runner it reuses.

## 7. Order of work

1. This note, then the simulation study (core, tests): the method proven and tuned.
2. The model and the picker in core, held by the simulation's tests.
3. The trial screens: keyboard, mouse and finger; sessions of about ten minutes; Present-quality card art.
4. Results, the guide link, siding.
5. Ai's parts: the calibration set and apprentice mode first (they make the blind data the trust score needs), then the
   interview and notes, then supervised runs, then the gate and audits that let Ai run alone.

**Needs:** Phase B (alternate arts counted as one card, legality); the evidence ledger (1.0.98); F1's runner style for the simulation; siding plans for both decks for the sided strata (Siding today, the two-deck siding tool when it is built).
**Done when:** the simulation's recovery and calibration tests pass; a real session gives ratings with ranges; and every number opens its trials.
