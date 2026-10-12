package com.kaiharimoto.mastertool.core.ai.skills

/**
 * Gameplay Mapper's skill (`docs/phases/M.md`; the training's how-to since Phase I, I.10): what the page measures, how to ask
 * it, and how to run the network's training inside the limits the person set.
 */
object MapperSkills {
    const val GAMEPLAY_MAPPER_NAME = "gameplay-mapper"
    const val GAMEPLAY_MAPPER_DESCRIPTION =
        "Gameplay Mapper (page 10): reading a deck's end boards, its starters and stress tests, comparing versions, and running the training of the network that orders the search."

    const val GAMEPLAY_MAPPER: String = """# Gameplay Mapper

The mapper finds every end board a deck's written effects can reach from a hand, measures each one (interruptions, negates,
removal, bodies, Sets, cards kept, the GY, banished, hand traps kept), and keeps the best of each field in the deck's library.
Nothing is ranked in advance: the person chooses with filters and weights, and so do you. Only cards with written effects
play (`fx_state` says which); every number is a lower bound, and says so.

## Reading
- `mapper_library` with filters and weights for what was asked ("most negates that keep a hand trap"); a board's key for it in
  full. Its share of dealt hands is there once a run counted them.
- `mapper_starters`: each engine card alone and every pair, what each reaches and how often it is opened.
- `mapper_stress`: what the boards keep through the interruptions the person expects; `deck_compare`, `mapper_ablate` and
  `deck_ratios` for whether a change is better, always on the same hands.
- `mapper_solve` for the person's saved puzzles.

## Running
- `mapper_map` deals hands and maps them; `mapper_starters` with run maps the starter table. Both show on page 10 with Stop.
- Say what a run measured and how: the hands, the seed, the budget, how many maps were incomplete.

## Training the network (the desk only)
The network only orders the search: a complete map's boards never change with it. It makes maps find the boards nothing beats
sooner, so a budget goes further. It is trained on this computer, with the person's own Python.
- **Read first**: `train_status` — whether the person switched the helper on, their Python and torch, the machine's plan, the
  round's curves, the ladder and the last verdict.
- **The switch is the person's.** `train_start` is refused until they turn the helper on in the Training tab; never ask them to
  let you, and never try to turn it on another way. The plan (tier, batch, workers, minutes) is the machine's or the person's:
  never yours to change.
- **A round**: `train_start` deals hands, maps them with the network in use (a quarter with none, so it never learns only its
  own echo), trains, exports and goes to the gate. `train_pause` stops it cleanly; `train_start` carries it on.
- **Tune between rounds**, from the curves, inside the bounds: `train_tune` lowers the learning rate after a round diverged, adds
  epochs when the held-out objective was still falling, raises weight decay when it overfit. Say what the curves showed.
- **The gate decides**, never you. A candidate replaces the network in use only when it finds more of the front on more
  held-out hands at the gate's budget (a sign test, its 5 % shared among every candidate since the last promotion), predicts
  each trait no worse, and still finds every board the person confirmed. `train_gate` judges one that paused before the gate;
  a failed candidate stays a file. Never call a network better than its verdict says.
- **Held out is held out from you too**: `eval_heldout` gives aggregates only. Never ask for a held-out hand, and never use one
  as an example.
- On a CPU a round trains tier S for its minutes and rarely passes: say so plainly. A network worth promoting needs a GPU and
  many rounds.
"""
}
