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

## 2. The model (`core/shootout/model`)

**One model explains every answer.** A hand's value is the sum of:
- the turn (first or second);
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
6. **Stop when it is known.** A session shows what it has settled, for example "21 of 24 cards known within ±2 points". It can end at any moment with every answer kept. Ratings carry over between sessions per deck and matchup.

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

## 5. Results and how they are shown

- **Cards:**
  - each card's rating per copy, with its range;
  - how often it is drawn, and how often it is dead in hand;
  - a verdict only where the range supports one ("cut one copy: the range is wholly below zero").
- **Pairs:** a grid, in ink, of only the pairs whose range excludes zero, each with how many trials back it.
- **Counts:** the ratings combined with the real draw odds give the next copy's worth, for example "a third Ash is worth +0.8 points; a second Veiler +0.3".
- **Opening patterns:** which kinds of hand win the matchup, first and second.
- **Siding:** plans compared through the same model, on the hands they leave you with.
- **Every number opens its trials.** Saved into the deck's guide, a number carries its proof (evidence ledger, `Proof(tool = "shootout", …)`).
- **Storage:**
  - `<data>/shootout/<deck>/<matchup>.json`: the trials, the append-only log, and the fit read from it. Synced and backed up.
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

## 7. Order of work

1. This note, then the simulation study (core, tests): the method proven and tuned.
2. The model and the picker in core, held by the simulation's tests.
3. The trial screens: keyboard, mouse and finger; sessions of about ten minutes; Present-quality card art.
4. Results, the guide link, siding.
5. Ai's parts.

**Needs:** Phase B (alternate arts counted as one card, legality); the evidence ledger (1.0.98); F1's runner style for the simulation.
**Done when:** the simulation's recovery and calibration tests pass; a real session gives ratings with ranges; and every number opens its trials.
