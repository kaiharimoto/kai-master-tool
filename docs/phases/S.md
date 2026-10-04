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

**Needs:** Phase B (alternate arts counted as one card, legality); the evidence ledger (1.0.98); F1's runner style for the simulation.
**Done when:** the simulation's recovery and calibration tests pass; a real session gives ratings with ranges; and every number opens its trials.
