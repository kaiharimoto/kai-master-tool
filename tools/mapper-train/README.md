# mapper-train

The training helper for Gameplay Mapper. The app runs the game engine and the search, writes
what it saw as JSON Lines, and runs the trained network through ONNX Runtime. This helper is
the part in between: a Python process the app starts on the person's computer — only when they
switch it on — to train the network, on the GPU if there is one, and export it to ONNX.

Dependencies: `torch`, `numpy`, `onnx`, `onnxruntime` (all permissively licensed; the app is
MIT). Nothing else. Python 3.10 or newer.

## How the app calls it

Each command prints JSON Lines on stdout, one event per line; an `{"event":"error","message":…}`
line and exit code 1 mean it failed.

| Command | What it does |
|---|---|
| `python -m mapper_train.hardware` | One JSON object: `device` (`cuda`, `rocm`, `mps`, `cpu`), `device_name`, `memory_bytes` (VRAM, else system RAM), `cpu_cores`, `torch`, `steps_per_s` (tier S, measured for ~3 s). |
| `python -m mapper_train.train --data <run> --out <dir> --tier S\|M\|L [--config <json>] [--resume]` | Trains. Events: `start`, `step`, `eval`, `done`. Writes `<dir>/checkpoint.pt`. `--resume` carries on from it, also onto a run whose vocabulary or traits grew (below). |
| `python -m mapper_train.pbt --data <run> --out <dir> --tier S --population 4 --rounds 3 [--config <json>]` | Population-based training; members in `<dir>/member-<i>/`, the best copied to `<dir>/checkpoint.pt`. |
| `python -m mapper_train.export --checkpoint <pt> --out <file.onnx> [--int8]` | Exports ONNX and checks it against PyTorch; prints one JSON result. |
| `python -m mapper_train.selftest` | Synthetic end-to-end check; prints `PASS` or `FAIL`. |

Run them from this directory (or with it on `PYTHONPATH`).

**Pausing.** The app creates a file named `stop` in `--out`. The trainer notices it before the
next step, saves `checkpoint.pt`, deletes `stop`, emits `done` with `"stopped": true` and exits 0.
Running the same command with `--resume` carries on exactly where it stopped (same batches,
same random state). `pbt` resumes by running it again with the same `--out`
(`pbt_state.json` keeps its round and members).

**Config** (`--config` takes inline JSON or a path to a JSON file; every key optional, an
unknown key is an error): `lr` 3e-4, `batch` 64, `epochs` 10, `weight_decay` 0.01,
`warmup_steps` 100, `value_weight` 0.5, `policy_weight` 1.0, `seed` 0, `max_minutes` 0 (no limit),
`val_fraction` 0.1, plus `dropout` 0.1, `log_every` 20 (steps between `step` events) and
`device` (null = the best here).

Training is AdamW, linear warmup then cosine decay, gradient clipping at 1.0, mixed precision on
CUDA (bf16 where supported, else fp16 with loss scaling), and deterministic seeding (a paused and
resumed run ends with the same weights as an uninterrupted one). The loss is soft-target
cross-entropy over the legal moves plus MSE on each trait the record carries, on **standardised**
values: each trait's mean and spread over the training split (spread floored at 1), so a trait
counted in tens (the GY) does not drown one counted in ones (negates). The statistics are kept in
the checkpoint and applied inside the exported graph; every MAE reported is in raw units.

**Selection metric.** `val_loss` weighs the value loss by the run's own `value_weight`;
`val_objective` = `val_policy_loss + w·val_value_loss` with `w` fixed when training began (the
first config's `value_weight`, kept in the checkpoint). Compare runs on `val_objective`: PBT ranks
its members on it with `w` the base config's, since ranking on a member's own `val_loss` would
reward it for shrinking `value_weight`.

Events:

```
{"event":"start","tier":"S","device":"cuda","records_train":…,"records_val":…,"skipped":…,"total_steps":…,…}
{"event":"step","step":20,"epoch":0,"loss":…,"policy_loss":…,"value_loss":…,"lr":…,"steps_per_s":…}
{"event":"eval","step":…,"split":"val","val_loss":…,"val_objective":…,"val_policy_loss":…,"val_value_loss":…,
 "val_policy_top1":…,"val_value_mae":{"interruptions":…},"val_value_baseline_mae":{"interruptions":…}}
{"event":"done","checkpoint":"<dir>/checkpoint.pt","step":…,"finished":true,"stopped":false}
```

`eval` comes after every epoch and when training ends; `val_value_baseline_mae` is the MAE of
always predicting the trait's training mean. `split` is `train` only when nothing is held out.
`start` also says `head_mean`, `head_std`, `objective_value_weight` and, after a resume onto a
grown run, `grew` (`cards_from`, `cards_to`, `new_heads`). `pbt` tags each member's events with
`member` and `round` (a member's own start and done are `member_start` / `member_done`) and adds
`round` (with each member's `val_objective`) and `exploit` events; its own `start` and `done`
have no `member`.

## Data contract (the app writes this)

A run directory holds:

- `vocab.json` — `{"version":1,"cards":[<canonical passcode>, …]}`. Index 0 is PAD, 1 is UNKNOWN;
  a card's vocab index is its position in the list. `vocab_hash` = sha256 of the file's bytes,
  hex, first 16 characters. **Append-only**: new cards go at the end, no index ever moves.
- `heads.json` — `{"version":1,"traits":["interruptions","negates",…,"through:ash"]}`: the
  value head order. **Append-only** too.
- `records-*.jsonl` — one position per line:

```
{"v":1,
 "hand":"<the starting hand: sorted card list + seat order, no seed>",
 "step":3,
 "tokens":[[card,zone,owner,face,pos], …],
 "moves":[[kind,card,effect,zone], …],
 "policy":[0.0,0.7,0.3, …],
 "value":{"interruptions":2.0,"negates":1.0, …},
 "prior":"<fingerprint of the move prior that ordered the search, or none>",
 "pos":"<position hash>"}
```

- zone 0–9: HAND, DECK, EXTRA, GY, BANISHED, MONSTER, SPELL, FIELD, EMZ, MATERIAL;
  owner 0 you / 1 them; face 0 down / 1 up; pos 0 none / 1 attack / 2 defence.
- kind 0–5: ACTIVATE, NORMAL_SUMMON, SET_MONSTER, PROCEDURE, PHASE_END, PASS_OR_RESOLVE;
  card 0 when the move has none; effect a small integer (32 and above share one embedding);
  zone (same 0–9 enum) is where the card is as the move is made, 0 when there is none.
- `policy` has one entry per move, non-negative, summing to 1 (±0.001; renormalised).
- `value` holds any subset of the traits in `heads.json`; missing traits are masked out of the
  loss. A trait not in `heads.json` makes the record invalid.
- `prior` and `pos` are optional and not used for training; any field the contract does not name
  is accepted and ignored.

A record that breaks the contract is skipped and counted (`skipped` and `skipped_examples` in
the `start` event). Positions are cut to 128 tokens — every non-DECK token is kept before any
DECK token — and to 64 moves, keeping the moves with the most target mass (renormalised, in the
app's order). The validation split is by a hash of `hand`, so a starting hand is never on both
sides.

**Growth.** `--resume` accepts a run whose `vocab.json` and `heads.json` are the checkpoint's
with entries appended: old cards and traits keep their weights (and their standardisation
statistics), new card rows and trait outputs start fresh, and AdamW's moments get zero rows. A
vocabulary or trait list changed anywhere but its end is refused with an `error` event — start a
new model then.

## Model contract (the app reads this)

`PolicyValueNet`: a pre-norm transformer encoder over the position's tokens (card + zone +
owner + face + position embeddings) with a learned `[CLS]` token carrying `log1p(step)`; each
move (kind + card + effect + zone, the card embedding shared with the tokens) attends to the encoded
position once and is scored against `[CLS]`; a value MLP on `[CLS]` gives one number per trait.

| Tier | d | layers | heads |
|---|---|---|---|
| S | 128 | 2 | 4 |
| M | 256 | 4 | 8 |
| L | 512 | 8 | 8 |

ONNX (opset 17; batch, token and move axes dynamic):

| | name | type | shape |
|---|---|---|---|
| in | `tokens` | int64 | [B,T,5] |
| in | `token_mask` | bool | [B,T] (true = real) |
| in | `step` | float32 | [B] |
| in | `moves` | int64 | [B,M,4] |
| in | `move_mask` | bool | [B,M] |
| out | `policy_logits` | float32 | [B,M], masked moves exactly -1e9 |
| out | `value` | float32 | [B,K], K = traits in `heads.json` order, raw units |

Metadata props: `tier`, `vocab_hash` (of the full vocabulary the model was last trained on),
`heads` (comma-joined), `head_mean` and `head_std` (comma-joined; already applied in the graph,
there for reference), `schema_version`. The app must refuse a model whose `vocab_hash` or
`heads` differ from the run it is used with. `--int8`
quantizes the weights dynamically (for phones); its difference from PyTorch is reported, while
the fp32 export must match PyTorch within 1e-3 or the command fails.

The checkpoint (`checkpoint.pt`, loadable with `torch.load(weights_only=True)`) holds the model,
optimizer and schedule position, random state, the config, tier, the vocabulary and its hash,
the heads with their mean and spread, the objective's value weight and the last eval.

## Self-test

```
cd tools/mapper-train
python -m mapper_train.selftest            # ~1.5 min on CPU; PASS or FAIL
python -m unittest discover -s tests       # schema checks; needs numpy only
```

The self-test writes a synthetic run that follows the contract (with a learnable toy rule, an
invalid line, over-long positions, extra fields), checks the stop file, trains tier S for a
minute on CPU, requires policy top-1 well above chance and every trait's MAE below 0.75× the
predict-the-mean baseline, exports fp32 and int8 ONNX and checks parity, resumes onto a run with
cards and a trait appended (and exports that), and checks that a reordered vocabulary is refused.
