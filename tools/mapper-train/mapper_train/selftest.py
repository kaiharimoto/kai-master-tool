"""End-to-end check on CPU: synthetic run -> train tier S -> export fp32 and int8 -> parity.

    python -m mapper_train.selftest [--keep <dir>]

The synthetic data follows the real contract and a toy rule the network can learn:
a move's worth is its card's fixed "power", plus a bonus when a copy of that card is in the
GY (so the network has to look at the position, not only the move), and ending the phase is
worth a constant. Trait values are counts over zones of the position, on different scales
(the GY count runs to 15) so the per-trait standardisation is exercised. It also checks the stop file,
resuming onto a run whose vocabulary and traits grew, and refusing one whose did not grow at
the end. Prints PASS or FAIL last and exits 0 or 1. About a minute and a half on four cores.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import random
import shutil
import sys
import tempfile
import time
from typing import Any

from . import export as export_lib
from . import schema
from .train import STOP_FILE, resolve_config, train

CARDS = 60
HEADS = ["interruptions", "negates", "gy", "through:ash"]
TRAIN_MINUTES = 1.0


def _power(card: int) -> int:
    return (card * 37) % 11


def _record(rng: random.Random, cards: list[int], step: int) -> dict[str, Any]:
    hand = rng.sample(cards, 5)
    field = rng.sample(cards, rng.randint(0, 3))
    gy = [rng.choice(cards) for _ in range(rng.randint(0, 15))]
    backrow = rng.sample(cards, rng.randint(0, 3))  # set face-down in the spell & trap zone
    banished = rng.sample(cards, rng.choice([0, 0, 1, 2]))
    deck_size = 110 if rng.random() < 0.01 else 8  # sometimes past MAX_TOKENS: truncation runs
    tokens = (
        [[c, 0, 0, 0, 0] for c in hand]
        + [[rng.choice(cards), 1, 0, 0, 0] for _ in range(deck_size)]
        + [[c, 5, 0, 1, 1] for c in field]
        + [[c, 3, rng.randint(0, 1), 1, 0] for c in gy]
        + [[c, 6, 0, 0, 0] for c in backrow]
        + [[c, 4, 0, 1, 0] for c in banished]
    )
    rng.shuffle(tokens)
    in_gy = set(gy)

    moves: list[list[int]] = []
    scores: list[float] = []
    for c in hand:
        kind = rng.choice([0, 1, 2])  # activate, normal summon, set
        moves.append([kind, c, 0, 0])  # from the hand
        scores.append(_power(c) + (4 if c in in_gy else 0) - (2 if kind == 2 else 0))
    for c in field:
        moves.append([0, c, 1, 5])  # from the monster zone
        scores.append(_power(c) + (4 if c in in_gy else 0))
    moves.append([4, 0, 0, 0])  # end the phase
    scores.append(5.0)
    order = list(range(len(moves)))
    rng.shuffle(order)
    moves = [moves[i] for i in order]
    scores = [scores[i] for i in order]
    best = max(range(len(scores)), key=lambda i: scores[i])
    rest = 0.15 / (len(moves) - 1)
    policy = [0.85 if i == best else rest for i in range(len(moves))]

    value = {
        "interruptions": float(sum(1 for c in field if _power(c) >= 6)),
        "negates": float(len(backrow)),
        "gy": float(len(gy)),
        "through:ash": 1.0 if banished else 0.0,
    }
    value = {k: v for k, v in value.items() if rng.random() < 0.8}  # records carry any subset
    return {
        "v": 1,
        # As the app writes it: the sorted hand and the seat order, no seed.
        "hand": ",".join(map(str, sorted(hand))) + "|0",
        "step": step,
        "tokens": tokens,
        "moves": moves,
        "policy": policy,
        "value": value,
        "prior": "none",  # fields the trainer does not use are accepted and ignored
        "pos": f"{rng.getrandbits(64):016x}",
    }


def _write_json(path: str, doc: Any) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f)


def write_synthetic_run(run_dir: str, positions: int = 1800, seed: int = 7) -> int:
    """Writes vocab.json, heads.json and two records files; returns the number of records."""
    rng = random.Random(seed)
    os.makedirs(run_dir, exist_ok=True)
    _write_json(os.path.join(run_dir, "vocab.json"), {"version": 1, "cards": [0, 0] + [10_000_000 + i for i in range(CARDS)]})
    _write_json(os.path.join(run_dir, "heads.json"), {"version": 1, "traits": HEADS})
    cards = list(range(2, CARDS + 2))  # vocab indices after PAD and UNKNOWN
    with open(os.path.join(run_dir, "records-000.jsonl"), "w", encoding="utf-8") as a, open(
        os.path.join(run_dir, "records-001.jsonl"), "w", encoding="utf-8"
    ) as b:
        for n in range(positions):
            (a if n % 2 else b).write(json.dumps(_record(rng, cards, rng.randint(0, 3))) + "\n")
        a.write("{not json}\n")  # one bad line: it must be skipped and counted
    return positions


def grow_run(old_dir: str, new_dir: str, seed: int = 8) -> None:
    """A copy of the run with three cards and one trait appended, and records using them."""
    shutil.copytree(old_dir, new_dir)
    with open(os.path.join(old_dir, "vocab.json"), encoding="utf-8") as f:
        cards = json.load(f)["cards"]
    _write_json(os.path.join(new_dir, "vocab.json"), {"version": 1, "cards": cards + [20_000_001, 20_000_002, 20_000_003]})
    _write_json(os.path.join(new_dir, "heads.json"), {"version": 1, "traits": HEADS + ["banished"]})
    rng = random.Random(seed)
    new_cards = list(range(2, len(cards) + 3))
    with open(os.path.join(new_dir, "records-002.jsonl"), "w", encoding="utf-8") as f:
        for _ in range(200):
            rec = _record(rng, new_cards, 0)
            rec["value"]["banished"] = float(rng.randint(0, 6))
            f.write(json.dumps(rec) + "\n")


def run_selftest(work: str) -> list[str]:
    """Returns the failures (empty = pass)."""
    failures: list[str] = []
    run_dir = os.path.join(work, "run")
    out_dir = os.path.join(work, "out")
    n = write_synthetic_run(run_dir)
    cfg = resolve_config(
        {"lr": 1e-3, "batch": 32, "epochs": 200, "warmup_steps": 50, "max_minutes": TRAIN_MINUTES,
         "val_fraction": 0.2, "log_every": 100, "device": "cpu", "seed": 1}
    )
    run = schema.load_run(run_dir, cfg["val_fraction"])
    print(json.dumps({"records": n, "train": len(run.train), "val": len(run.val), "skipped": run.skipped}))
    if run.skipped != 1:
        failures.append(f"expected 1 skipped line, got {run.skipped}")
    if {r.hand for r in run.train} & {r.hand for r in run.val}:
        failures.append("a hand is on both sides of the split")

    # A stop file before the first step: saves, removes the file, reports stopped.
    os.makedirs(out_dir, exist_ok=True)
    open(os.path.join(out_dir, STOP_FILE), "w").close()
    paused = train(run, out_dir, "S", cfg, emit=lambda e: None)
    if not paused["stopped"] or paused["step"] != 0 or os.path.exists(os.path.join(out_dir, STOP_FILE)):
        failures.append("the stop file did not pause training cleanly")

    def emit(event: dict[str, Any]) -> None:
        if event["event"] in ("eval", "done"):
            print(json.dumps(event))

    first = time.monotonic()
    result = train(run, out_dir, "S", cfg, emit=emit, resume=True)
    print(json.dumps({"trained_seconds": round(time.monotonic() - first, 1), "steps": result["step"]}))

    chance = sum(1 / len(r.moves) for r in run.val) / len(run.val)
    top1 = result["val_policy_top1"]
    print(json.dumps({"val_policy_top1": top1, "chance": chance}))
    if top1 < max(2 * chance, chance + 0.25):
        failures.append(f"policy top-1 {top1:.3f} is not clearly above chance {chance:.3f}")
    mae, baseline = result["val_value_mae"], result["val_value_baseline_mae"]
    for h in run.heads:
        if mae[h] > 0.75 * baseline[h]:
            failures.append(f"value MAE for {h} ({mae[h]:.3f}) is not below 0.75 x the baseline {baseline[h]:.3f}")

    for int8 in (False, True):
        path = os.path.join(work, "model-int8.onnx" if int8 else "model.onnx")
        exported = export_lib.export(result["checkpoint"], path, int8=int8)
        print(json.dumps(exported))
        if not exported["fp32_parity"]:
            failures.append(f"fp32 ONNX differs from PyTorch by {exported['fp32_max_abs_diff']}")
        if int8 and exported["int8_top1_agreement"] < 0.6:
            failures.append(f"int8 model agrees on top-1 only {exported['int8_top1_agreement']:.2f}")

    # The app appends cards and traits; a resume carries the model over and grows it.
    grown_dir = os.path.join(work, "run-grown")
    grow_run(run_dir, grown_dir)
    grown_out = os.path.join(work, "out-grown")
    shutil.copytree(out_dir, grown_out)
    grown = schema.load_run(grown_dir, cfg["val_fraction"])
    starts: list[dict[str, Any]] = []
    more = train(grown, grown_out, "S", cfg, emit=lambda e: starts.append(e) if e["event"] == "start" else None,
                 resume=True, until_step=result["step"] + 10)
    print(json.dumps({"grew": starts[0]["grew"], "step": more["step"], "val_value_mae": more["val_value_mae"]}))
    if starts[0]["grew"].get("new_heads") != ["banished"] or more["step"] != result["step"] + 10:
        failures.append("resuming onto a grown vocabulary and head list did not carry on")
    exported = export_lib.export(more["checkpoint"], os.path.join(work, "model-grown.onnx"))
    if not exported["fp32_parity"] or exported["heads"] != ",".join(HEADS + ["banished"]):
        failures.append("the grown model did not export cleanly")

    # A vocabulary that changed anywhere but its end is refused.
    shuffled_dir = os.path.join(work, "run-reordered")
    shutil.copytree(run_dir, shuffled_dir)
    with open(os.path.join(run_dir, "vocab.json"), encoding="utf-8") as f:
        cards = json.load(f)["cards"]
    cards[2], cards[3] = cards[3], cards[2]
    _write_json(os.path.join(shuffled_dir, "vocab.json"), {"version": 1, "cards": cards})
    try:
        train(schema.load_run(shuffled_dir, cfg["val_fraction"]), grown_out, "S", cfg, emit=lambda e: None, resume=True)
        failures.append("a reordered vocabulary was accepted")
    except ValueError as e:
        print(json.dumps({"refused": str(e)}))
    for leftover in glob.glob(os.path.join(work, "*", "*.tmp")):
        failures.append(f"a temporary checkpoint was left behind: {leftover}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(prog="python -m mapper_train.selftest", description="End-to-end self-test.")
    parser.add_argument("--keep", help="work in this directory and leave it behind")
    args = parser.parse_args()
    work = args.keep or tempfile.mkdtemp(prefix="mapper-selftest-")
    try:
        failures = run_selftest(work)
    finally:
        if not args.keep:
            shutil.rmtree(work, ignore_errors=True)
    for f in failures:
        print(f"FAIL: {f}")
    print("FAIL" if failures else "PASS", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
