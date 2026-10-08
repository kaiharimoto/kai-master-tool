"""Trains PolicyValueNet on a run directory.

    python -m mapper_train.train --data <run dir> --out <dir> --tier S|M|L [--config <json or file>] [--resume]

Progress is JSON Lines on stdout, one object per event: start, step, eval, done (and error).
A file named `stop` in --out is checked every step: the trainer saves `checkpoint.pt`, removes
the file and exits cleanly; `--resume` carries on from that checkpoint, step for step, and
accepts a run whose vocab.json / heads.json grew at the end since.

Value heads are trained on standardised targets (each trait's training mean and spread, the
spread floored at 1); every reported MAE is in the trait's own units.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import math
import os
import random
import sys
import time
from typing import Any, Callable, Sequence

import numpy as np
import torch

from . import hardware
from . import model as model_lib
from . import schema

Emit = Callable[[dict[str, Any]], None]

CHECKPOINT = "checkpoint.pt"
STOP_FILE = "stop"
CHECKPOINT_FORMAT = "mapper-train/1"

DEFAULTS: dict[str, Any] = {
    "lr": 3e-4,
    "batch": 64,
    "epochs": 10,
    "weight_decay": 0.01,
    "warmup_steps": 100,
    "value_weight": 0.5,
    "policy_weight": 1.0,
    "seed": 0,
    "max_minutes": 0,  # 0 = no limit
    "val_fraction": 0.1,
    "dropout": 0.1,
    "log_every": 20,  # steps between "step" events
    "device": None,  # None = the best one here; else "cuda", "rocm", "mps" or "cpu"
}


def print_event(event: dict[str, Any]) -> None:
    print(json.dumps(event), flush=True)


def resolve_config(user: dict[str, Any] | None) -> dict[str, Any]:
    """Defaults overlaid with the caller's keys; an unknown key is an error, not a silent typo."""
    user = user or {}
    unknown = sorted(set(user) - set(DEFAULTS))
    if unknown:
        raise ValueError(f"unknown config keys: {', '.join(unknown)}")
    cfg = {**DEFAULTS, **user}
    if cfg["batch"] < 1 or cfg["epochs"] < 1:
        raise ValueError("batch and epochs must be at least 1")
    if not 0 <= cfg["val_fraction"] < 1:
        raise ValueError("val_fraction must be in [0, 1)")
    return cfg


def parse_config_arg(text: str | None) -> dict[str, Any]:
    """--config takes inline JSON or a path to a JSON file."""
    if not text:
        return {}
    if os.path.isfile(text):
        with open(text, "r", encoding="utf-8") as f:
            return json.load(f)
    return json.loads(text)


def lr_at(step: int, total: int, cfg: dict[str, Any]) -> float:
    """Linear warmup, then cosine decay to zero at `total`."""
    warmup = int(cfg["warmup_steps"])
    if warmup > 0 and step < warmup:
        return cfg["lr"] * (step + 1) / warmup
    span = max(1, total - warmup)
    progress = min(1.0, (step - warmup) / span)
    return cfg["lr"] * 0.5 * (1.0 + math.cos(math.pi * progress))


def seed_everything(seed: int) -> None:
    # Must be set before cuBLAS starts for deterministic matrix products on cuda.
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.use_deterministic_algorithms(True, warn_only=True)
    torch.backends.cudnn.benchmark = False


def to_device(batch: dict[str, np.ndarray], device: torch.device) -> dict[str, torch.Tensor]:
    return {k: torch.from_numpy(v).to(device, non_blocking=True) for k, v in batch.items()}


def forward(net: model_lib.PolicyValueNet, b: dict[str, torch.Tensor]) -> tuple[torch.Tensor, torch.Tensor]:
    return net(b["tokens"], b["token_mask"], b["step"], b["moves"], b["move_mask"])


@torch.no_grad()
def evaluate(
    net: model_lib.PolicyValueNet,
    records: Sequence[schema.Record],
    heads: Sequence[str],
    cfg: dict[str, Any],
    device: torch.device,
    objective_value_weight: float,
) -> dict[str, Any]:
    """Losses, the fixed selection objective, policy top-1 and per-trait MAE in raw units.

    val_loss weighs value by this run's own value_weight; val_objective by a weight fixed when
    training began, so runs with different value_weight can be compared (PBT ranks on it).
    val_value_loss is the MSE of standardised values; val_value_mae and the predict-the-mean
    baseline val_value_baseline_mae are in the trait's own units.
    """
    was_training = net.training
    net.eval()
    policy_sum = 0.0
    top1 = 0
    sq_err = 0.0
    value_count = 0
    abs_err = np.zeros(len(heads))
    base_err = np.zeros(len(heads))
    counts = np.zeros(len(heads))
    size = int(cfg["batch"])
    for i in range(0, len(records), size):
        b = to_device(schema.collate(records[i : i + size], heads), device)
        logits, value_z = forward(net, b)
        log_p = torch.log_softmax(logits.float(), dim=-1)
        policy_sum += float(-(b["policy"] * log_p).sum())
        top1 += int((logits.argmax(-1) == b["policy"].argmax(-1)).sum())
        mask = b["value_mask"].float()
        sq_err += float((((value_z - net.standardise(b["value"])) * mask) ** 2).sum())
        value_count += int(mask.sum())
        abs_err += ((net.raw_value(value_z) - b["value"]).abs() * mask).sum(0).cpu().numpy()
        base_err += ((net.head_mean - b["value"]).abs() * mask).sum(0).cpu().numpy()
        counts += mask.sum(0).cpu().numpy()
    net.train(was_training)
    n = max(1, len(records))
    policy_loss = policy_sum / n
    value_loss = sq_err / max(1, value_count)
    seen = [j for j in range(len(heads)) if counts[j] > 0]
    return {
        "val_loss": cfg["policy_weight"] * policy_loss + cfg["value_weight"] * value_loss,
        "val_objective": policy_loss + objective_value_weight * value_loss,
        "val_policy_loss": policy_loss,
        "val_value_loss": value_loss,
        "val_policy_top1": top1 / n,
        "val_value_mae": {heads[j]: float(abs_err[j] / counts[j]) for j in seen},
        "val_value_baseline_mae": {heads[j]: float(base_err[j] / counts[j]) for j in seen},
    }


def _save(path: str, payload: dict[str, Any]) -> None:
    tmp = path + ".tmp"
    torch.save(payload, tmp)
    os.replace(tmp, path)  # never leave a half-written checkpoint where the app looks


def load_checkpoint(path: str) -> dict[str, Any]:
    ckpt = torch.load(path, map_location="cpu", weights_only=True)
    if ckpt.get("format") != CHECKPOINT_FORMAT:
        raise ValueError(f"{path} is not a mapper-train checkpoint")
    return ckpt


def _grow_optimizer_state(state: dict[str, Any], params: list[torch.nn.Parameter]) -> dict[str, Any]:
    """AdamW's moments for parameters that grew (new cards, new traits): zeros for the new rows."""
    for idx, slot in state["state"].items():
        p = params[int(idx)]
        for key in ("exp_avg", "exp_avg_sq"):
            t = slot.get(key)
            if t is not None and t.shape != p.shape:
                pad = torch.zeros((p.shape[0] - t.shape[0], *t.shape[1:]), dtype=t.dtype)
                slot[key] = torch.cat([t, pad], dim=0)
    return state


def train(
    run: schema.RunData,
    out_dir: str,
    tier: str,
    cfg: dict[str, Any],
    emit: Emit = print_event,
    resume: bool = False,
    until_step: int | None = None,
    stop_path: str | None = None,
    objective_value_weight: float | None = None,
) -> dict[str, Any]:
    """Trains until the schedule ends, `until_step`, the time limit or a stop file.

    With `resume`, carries on from `out_dir/checkpoint.pt` (its weights, optimizer and
    position in the data); hyperparameters come from `cfg`, so PBT can change them. The run's
    vocab.json and heads.json may have grown since (append-only): the old cards and traits keep
    their weights and statistics, the new ones start fresh. Anything else is refused.
    `stop_path` defaults to `out_dir/stop`. `objective_value_weight` fixes val_objective's
    value weight (default: the checkpoint's, else cfg's value_weight at the start).
    Returns {"checkpoint", "step", "finished", "stopped", ...the last eval}.
    """
    if not run.train:
        raise ValueError("no training records (every record was invalid or held out)")
    os.makedirs(out_dir, exist_ok=True)
    ckpt_path = os.path.join(out_dir, CHECKPOINT)
    stop_path = stop_path or os.path.join(out_dir, STOP_FILE)
    heads = run.heads
    # With nothing held out, report on the training set rather than not at all.
    eval_records = run.val or run.train
    eval_split = "val" if run.val else "train"

    seed_everything(int(cfg["seed"]))
    device = hardware.pick_device(cfg["device"])
    net = model_lib.build(run.vocab_size, len(heads), tier, float(cfg["dropout"]))
    head_mean, head_std = schema.head_stats(run.train, heads)

    batch = int(cfg["batch"])
    steps_per_epoch = math.ceil(len(run.train) / batch)
    total_steps = steps_per_epoch * int(cfg["epochs"])
    step = epoch = batch_in_epoch = 0
    last_eval: dict[str, Any] = {}
    eval_step = -1  # the step last_eval belongs to
    grew: dict[str, Any] = {}
    ckpt: dict[str, Any] | None = None

    if resume and os.path.exists(ckpt_path):
        ckpt = load_checkpoint(ckpt_path)
        if ckpt["tier"] != tier:
            raise ValueError(f"the checkpoint is tier {ckpt['tier']}, not {tier}")
        if not schema.extends(ckpt["vocab"], run.vocab):
            raise ValueError("vocab.json is not the checkpoint's vocabulary with cards appended; start a new model")
        if not schema.extends(ckpt["heads"], heads):
            raise ValueError("heads.json is not the checkpoint's traits with traits appended; start a new model")
        old_k = len(ckpt["heads"])
        if len(run.vocab) > len(ckpt["vocab"]) or len(heads) > old_k:
            grew = {"cards_from": len(ckpt["vocab"]), "cards_to": len(run.vocab), "new_heads": heads[old_k:]}
        net.load_state_dict(model_lib.grow_state(ckpt["model"], net.state_dict()))
        # Old traits keep the statistics their weights were trained against; new ones get today's.
        head_mean[:old_k], head_std[:old_k] = ckpt["head_mean"], ckpt["head_std"]
        step, epoch, batch_in_epoch = ckpt["step"], ckpt["epoch"], ckpt["batch_in_epoch"]
        last_eval = ckpt.get("last_eval", {})
        eval_step = ckpt.get("eval_step", -1) if not grew else -1
        if objective_value_weight is None:
            objective_value_weight = ckpt["objective_value_weight"]
    if objective_value_weight is None:
        objective_value_weight = float(cfg["value_weight"])
    net.set_head_stats(head_mean, head_std)
    net.to(device)

    opt = torch.optim.AdamW(net.parameters(), lr=cfg["lr"], weight_decay=cfg["weight_decay"])
    amp_dtype: torch.dtype | None = None
    if device.type == "cuda":
        amp_dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    scaler = torch.amp.GradScaler("cuda", enabled=amp_dtype == torch.float16)
    if ckpt is not None:
        opt.load_state_dict(_grow_optimizer_state(ckpt["optimizer"], list(net.parameters())))
        scaler.load_state_dict(ckpt["scaler"])
        torch.set_rng_state(ckpt["rng_cpu"])
        if device.type == "cuda" and ckpt.get("rng_cuda"):
            torch.cuda.set_rng_state_all(ckpt["rng_cuda"])
    resumed_from = step

    def save() -> None:
        _save(
            ckpt_path,
            {
                "format": CHECKPOINT_FORMAT,
                "schema_version": schema.SCHEMA_VERSION,
                "tier": tier,
                "config": cfg,
                "vocab": run.vocab,
                "vocab_size": run.vocab_size,
                "vocab_hash": run.vocab_hash,
                "heads": list(heads),
                "head_mean": head_mean,
                "head_std": head_std,
                "objective_value_weight": objective_value_weight,
                "model": net.state_dict(),
                "optimizer": opt.state_dict(),
                "scaler": scaler.state_dict(),
                "step": step,
                "epoch": epoch,
                "batch_in_epoch": batch_in_epoch,
                "last_eval": last_eval,
                "eval_step": eval_step,
                "rng_cpu": torch.get_rng_state(),
                "rng_cuda": torch.cuda.get_rng_state_all() if device.type == "cuda" else [],
            },
        )

    def run_eval() -> None:
        nonlocal last_eval, eval_step
        last_eval = evaluate(net, eval_records, heads, cfg, device, objective_value_weight)
        eval_step = step
        emit({"event": "eval", "step": step, "split": eval_split, **last_eval})

    emit(
        {
            "event": "start",
            "tier": tier,
            "device": hardware.device_kind() if cfg["device"] is None else cfg["device"],
            "parameters": sum(p.numel() for p in net.parameters()),
            "records_train": len(run.train),
            "records_val": len(run.val),
            "skipped": run.skipped,
            "skipped_examples": run.first_errors,
            "vocab_size": run.vocab_size,
            "vocab_hash": run.vocab_hash,
            "heads": heads,
            "head_mean": head_mean,
            "head_std": head_std,
            "objective_value_weight": objective_value_weight,
            "total_steps": total_steps,
            "resumed_from_step": resumed_from,
            "grew": grew,
            "config": cfg,
        }
    )

    deadline = time.monotonic() + 60 * float(cfg["max_minutes"]) if cfg["max_minutes"] else None
    net.train()
    window_start = time.perf_counter()
    window_steps = 0
    sums = torch.zeros(3, device=device)  # loss, policy, value over the log window (no per-step sync)
    stopped = False
    order_epoch, order = -1, np.empty(0, dtype=np.int64)

    while step < total_steps:
        if os.path.exists(stop_path):
            os.remove(stop_path)  # honoured once, so a resume is not stopped at once
            stopped = True
            break
        if (deadline is not None and time.monotonic() >= deadline) or (until_step is not None and step >= until_step):
            break
        if batch_in_epoch >= steps_per_epoch:  # the data shrank or the batch grew since the checkpoint
            epoch, batch_in_epoch = epoch + 1, 0

        # Each epoch's order depends only on (seed, epoch): a resume sees the same batches.
        if order_epoch != epoch:
            order_epoch, order = epoch, np.random.default_rng(int(cfg["seed"]) + epoch).permutation(len(run.train))
        idx = order[batch_in_epoch * batch : (batch_in_epoch + 1) * batch]
        b = to_device(schema.collate([run.train[i] for i in idx], heads), device)

        lr = lr_at(step, total_steps, cfg)
        for group in opt.param_groups:
            group["lr"] = lr
            group["weight_decay"] = cfg["weight_decay"]

        autocast = torch.autocast("cuda", dtype=amp_dtype) if amp_dtype is not None else contextlib.nullcontext()
        with autocast:
            logits, value_z = forward(net, b)
        policy_loss, value_loss = model_lib.losses(
            logits, value_z, b["policy"], net.standardise(b["value"]), b["value_mask"]
        )
        loss = cfg["policy_weight"] * policy_loss + cfg["value_weight"] * value_loss
        opt.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.unscale_(opt)
        torch.nn.utils.clip_grad_norm_(net.parameters(), 1.0)
        scaler.step(opt)
        scaler.update()

        step += 1
        batch_in_epoch += 1
        window_steps += 1
        sums += torch.stack([loss, policy_loss, value_loss]).detach()
        if step % int(cfg["log_every"]) == 0 or step == total_steps:
            elapsed = time.perf_counter() - window_start
            loss_avg, pol_avg, val_avg = (sums / window_steps).tolist()
            emit(
                {
                    "event": "step",
                    "step": step,
                    "epoch": epoch,
                    "loss": loss_avg,
                    "policy_loss": pol_avg,
                    "value_loss": val_avg,
                    "lr": lr,
                    "steps_per_s": window_steps / max(elapsed, 1e-9),
                }
            )
            window_start, window_steps = time.perf_counter(), 0
            sums.zero_()

        if batch_in_epoch >= steps_per_epoch:
            epoch += 1
            batch_in_epoch = 0
            run_eval()
            save()

    finished = step >= total_steps
    if eval_step != step:
        run_eval()
    save()
    emit({"event": "done", "checkpoint": ckpt_path, "step": step, "finished": finished, "stopped": stopped})
    return {"checkpoint": ckpt_path, "step": step, "finished": finished, "stopped": stopped, **last_eval}


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m mapper_train.train", description=__doc__.splitlines()[0])
    parser.add_argument("--data", required=True, help="run directory written by the app")
    parser.add_argument("--out", required=True, help="where checkpoint.pt goes")
    parser.add_argument("--tier", default="S", choices=sorted(model_lib.TIERS))
    parser.add_argument("--config", help="inline JSON or a JSON file")
    parser.add_argument("--resume", action="store_true", help="carry on from --out/checkpoint.pt")
    args = parser.parse_args(argv)
    try:
        cfg = resolve_config(parse_config_arg(args.config))
        run = schema.load_run(args.data, cfg["val_fraction"])
        train(run, args.out, args.tier, cfg, resume=args.resume)
    except (ValueError, OSError, schema.SchemaError, json.JSONDecodeError) as e:
        print_event({"event": "error", "message": str(e)})
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
