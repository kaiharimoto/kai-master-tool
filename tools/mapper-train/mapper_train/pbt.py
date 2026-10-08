"""Population-based training: a population of trainers, the weak copying the strong.

    python -m mapper_train.pbt --data <dir> --out <dir> --tier S --population 4 --rounds 3 [--config <json>]

Each member starts from the base config with lr, weight_decay and value_weight drawn around
it, and lives in `<out>/member-<i>/`. A round trains every member in turn for an equal slice
of the schedule and ranks them on a fixed objective, val_policy_loss + w * val_value_loss with
w the starting config's value_weight (never a member's own, which it could lower to look
better). Then the bottom quarter (at least one) copies the weights and optimizer of a
member from the top quarter (exploit) and takes its hyperparameters, each multiplied by 0.8 or
1.2 (explore). After the last round the best member's checkpoint is copied to
`<out>/checkpoint.pt`. Everything, including the perturbations, follows from the config's seed.

A `stop` file in <out> pauses it; running it again with the same --out resumes it
(`pbt_state.json` keeps its round, configs and random stream). `max_minutes` bounds the whole
population: it ends after the round that runs past it.

Events are train.py's, each tagged with "member" and "round" (a member's own start and done
are "member_start" and "member_done"), plus {"event": "round"} and {"event": "exploit"}; the
population's own "start" and "done" carry no "member".
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import sys
import time
from typing import Any, Sequence

import numpy as np

from . import model as model_lib
from . import schema
from .train import CHECKPOINT, STOP_FILE, Emit, parse_config_arg, print_event, resolve_config, train

STATE_FILE = "pbt_state.json"
SEARCHED = ("lr", "weight_decay", "value_weight")
# Initial spread: each searched value is the base times a log-uniform factor in [1/x, x].
INITIAL_SPREAD = {"lr": 3.0, "weight_decay": 3.0, "value_weight": 2.0}
EXPLORE_FACTORS = (0.8, 1.2)


def _member_dir(out: str, i: int) -> str:
    return os.path.join(out, f"member-{i}")


def initial_configs(base: dict[str, Any], population: int, rng: np.random.Generator) -> list[dict[str, Any]]:
    """Member 0 keeps the base config; the others are spread around it, each with its own seed."""
    configs = [dict(base)]
    for i in range(1, population):
        cfg = dict(base, seed=int(base["seed"]) + i)
        for key in SEARCHED:
            spread = math.log(INITIAL_SPREAD[key])
            cfg[key] = base[key] * math.exp(rng.uniform(-spread, spread))
        configs.append(cfg)
    return configs


def explore(cfg: dict[str, Any], rng: np.random.Generator) -> dict[str, Any]:
    out = dict(cfg)
    for key in SEARCHED:
        out[key] = cfg[key] * float(rng.choice(EXPLORE_FACTORS))
    return out


def run_pbt(
    run: schema.RunData,
    out: str,
    tier: str,
    base: dict[str, Any],
    population: int,
    rounds: int,
    emit: Emit = print_event,
) -> dict[str, Any]:
    if population < 2 or rounds < 1:
        raise ValueError("population must be at least 2 and rounds at least 1")
    os.makedirs(out, exist_ok=True)
    state_path = os.path.join(out, STATE_FILE)
    rng = np.random.default_rng(int(base["seed"]))
    configs = initial_configs(base, population, rng)
    first_round = 0
    if os.path.exists(state_path):
        # A paused population carries on where it was: same round, configs and random stream.
        with open(state_path, "r", encoding="utf-8") as f:
            saved = json.load(f)
        if saved["population"] != population or saved["rounds"] != rounds or saved["tier"] != tier:
            raise ValueError(f"{state_path} belongs to another population; use a fresh --out")
        first_round, configs = saved["next_round"], saved["configs"]
        rng.bit_generator.state = saved["rng"]

    def save_state(next_round: int) -> None:
        state = {"population": population, "rounds": rounds, "tier": tier, "next_round": next_round,
                 "configs": configs, "rng": rng.bit_generator.state}
        with open(state_path + ".tmp", "w", encoding="utf-8") as f:
            json.dump(state, f)
        os.replace(state_path + ".tmp", state_path)

    total_steps = math.ceil(len(run.train) / int(base["batch"])) * int(base["epochs"])
    slice_steps = math.ceil(total_steps / rounds)
    n_swap = max(1, population // 4)
    scores: list[float] = [math.inf] * population
    stop_path = os.path.join(out, STOP_FILE)
    # Members are ranked on val_policy_loss + w * val_value_loss with w the BASE value_weight.
    # Ranking on each member's own weighted loss would reward shrinking value_weight itself.
    objective_weight = float(base["value_weight"])
    # max_minutes bounds the whole population, checked between rounds, not each member's slice.
    deadline = time.monotonic() + 60 * float(base["max_minutes"]) if base["max_minutes"] else None
    configs = [dict(c, max_minutes=0) for c in configs]

    emit({"event": "start", "mode": "pbt", "tier": tier, "population": population, "rounds": rounds,
          "total_steps": total_steps, "resumed_at_round": first_round,
          "selection_metric": f"val_policy_loss + {objective_weight} * val_value_loss",
          "configs": [{k: c[k] for k in SEARCHED} for c in configs]})

    for r in range(first_round, rounds):
        until = min(total_steps, (r + 1) * slice_steps)
        for i in range(population):

            def tagged(event: dict[str, Any], member: int = i) -> None:
                # A member's own start and done are not the population's.
                kind = event["event"]
                name = f"member_{kind}" if kind in ("start", "done") else kind
                emit({**event, "event": name, "member": member, "round": r})

            # Each member trains from its own checkpoint (or a copied winner's) with its own config.
            result = train(run, _member_dir(out, i), tier, configs[i], emit=tagged, resume=True,
                           until_step=until, stop_path=stop_path, objective_value_weight=objective_weight)
            scores[i] = float(result["val_objective"])
            if result["stopped"]:
                emit({"event": "done", "checkpoint": None, "stopped": True, "round": r})
                return {"stopped": True}

        ranked = sorted(range(population), key=lambda i: scores[i])
        emit({"event": "round", "round": r, "step": until, "val_objective": scores, "ranking": ranked})
        if r == rounds - 1 or (deadline is not None and time.monotonic() >= deadline):
            break
        for loser in ranked[-n_swap:]:
            winner = ranked[int(rng.integers(0, n_swap))]
            shutil.copyfile(
                os.path.join(_member_dir(out, winner), CHECKPOINT), os.path.join(_member_dir(out, loser), CHECKPOINT)
            )
            configs[loser] = explore(configs[winner], rng)
            emit({"event": "exploit", "round": r, "member": loser, "from": winner,
                  "config": {k: configs[loser][k] for k in SEARCHED}})
        save_state(r + 1)

    best = min(range(population), key=lambda i: scores[i])
    final = os.path.join(out, CHECKPOINT)
    shutil.copyfile(os.path.join(_member_dir(out, best), CHECKPOINT), final)
    emit({"event": "done", "checkpoint": final, "best_member": best, "val_objective": scores[best],
          "config": {k: configs[best][k] for k in SEARCHED}})
    return {"checkpoint": final, "best_member": best, "val_objective": scores[best], "stopped": False}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m mapper_train.pbt", description="Population-based training.")
    parser.add_argument("--data", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--tier", default="S", choices=sorted(model_lib.TIERS))
    parser.add_argument("--population", type=int, default=4)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--config", help="inline JSON or a JSON file")
    args = parser.parse_args(argv)
    try:
        base = resolve_config(parse_config_arg(args.config))
        run = schema.load_run(args.data, base["val_fraction"])
        run_pbt(run, args.out, args.tier, base, args.population, args.rounds)
    except (ValueError, OSError, schema.SchemaError, json.JSONDecodeError) as e:
        print_event({"event": "error", "message": str(e)})
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
