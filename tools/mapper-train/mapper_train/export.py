"""Exports a checkpoint to ONNX for the app's ONNX Runtime.

    python -m mapper_train.export --checkpoint <pt> --out <file.onnx> [--int8]

Inputs:  tokens int64 [B,T,5], token_mask bool [B,T], step float32 [B],
         moves int64 [B,M,4], move_mask bool [B,M]   (B, T and M are dynamic)
Outputs: policy_logits float32 [B,M], value float32 [B,K] in raw units (de-standardised in the graph)
Metadata: tier, vocab_hash, heads (comma-joined), head_mean and head_std (comma-joined, the
statistics the graph already applied), schema_version.

The exported graph is run once with ONNX Runtime against PyTorch on a batch of a different
shape than the one traced; the result is printed as one JSON object. `--int8` quantizes the
weights dynamically (smaller and faster on phones); its parity is reported, not required.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from typing import Any, Sequence

import numpy as np
import onnx
import onnxruntime as ort
import torch

from . import model as model_lib
from . import schema
from .train import load_checkpoint

OPSET = 17
INPUTS = ["tokens", "token_mask", "step", "moves", "move_mask"]
OUTPUTS = ["policy_logits", "value"]
FP32_TOLERANCE = 1e-3


def sample_batch(vocab_size: int, b: int, t: int, m: int, seed: int) -> dict[str, np.ndarray]:
    """A valid random batch with some padding in it, so masking is exercised too."""
    rng = np.random.default_rng(seed)
    tokens = np.stack(
        [
            rng.integers(0, vocab_size, (b, t)),
            rng.integers(0, len(schema.ZONES), (b, t)),
            rng.integers(0, schema.OWNERS, (b, t)),
            rng.integers(0, schema.FACES, (b, t)),
            rng.integers(0, schema.POSITIONS, (b, t)),
        ],
        axis=-1,
    ).astype(np.int64)
    moves = np.stack(
        [
            rng.integers(0, len(schema.MOVE_KINDS), (b, m)),
            rng.integers(0, vocab_size, (b, m)),
            rng.integers(0, 4, (b, m)),
            rng.integers(0, len(schema.ZONES), (b, m)),
        ],
        axis=-1,
    ).astype(np.int64)
    # Row i keeps its first n tokens / moves; at least one move per row.
    token_mask = np.arange(t)[None, :] < rng.integers(0, t + 1, (b, 1))
    move_mask = np.arange(m)[None, :] < rng.integers(1, m + 1, (b, 1))
    tokens[~token_mask] = 0
    moves[~move_mask] = 0
    return {
        "tokens": tokens,
        "token_mask": token_mask,
        "step": rng.integers(0, 12, b).astype(np.float32),
        "moves": moves,
        "move_mask": move_mask,
    }


def _torch_outputs(net: torch.nn.Module, batch: dict[str, np.ndarray]) -> list[np.ndarray]:
    with torch.no_grad():
        outs = net(*(torch.from_numpy(batch[k]) for k in INPUTS))
    return [o.numpy() for o in outs]


def _ort_outputs(path: str, batch: dict[str, np.ndarray]) -> list[np.ndarray]:
    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    return session.run(OUTPUTS, {k: batch[k] for k in INPUTS})


def _set_metadata(path: str, props: dict[str, str]) -> None:
    proto = onnx.load(path)
    del proto.metadata_props[:]
    for key, value in props.items():
        entry = proto.metadata_props.add()
        entry.key, entry.value = key, value
    onnx.save(proto, path)


def _trace(net: torch.nn.Module, path: str, batch: dict[str, np.ndarray]) -> None:
    args = tuple(torch.from_numpy(batch[k]) for k in INPUTS)
    dynamic_axes = {
        "tokens": {0: "batch", 1: "tokens"},
        "token_mask": {0: "batch", 1: "tokens"},
        "step": {0: "batch"},
        "moves": {0: "batch", 1: "moves"},
        "move_mask": {0: "batch", 1: "moves"},
        "policy_logits": {0: "batch", 1: "moves"},
        "value": {0: "batch"},
    }
    kwargs: dict[str, Any] = dict(
        input_names=INPUTS, output_names=OUTPUTS, dynamic_axes=dynamic_axes, opset_version=OPSET
    )
    try:
        # The TorchScript exporter: no onnxscript dependency, and it honours dynamic_axes.
        torch.onnx.export(net, args, path, dynamo=False, **kwargs)
    except TypeError:  # torch older than 2.5 has no `dynamo` argument
        torch.onnx.export(net, args, path, **kwargs)


def export(checkpoint: str, out: str, int8: bool = False) -> dict[str, Any]:
    ckpt = load_checkpoint(checkpoint)
    heads: list[str] = ckpt["heads"]
    inner = model_lib.build(ckpt["vocab_size"], len(heads), ckpt["tier"], dropout=0.0)
    inner.load_state_dict(ckpt["model"])
    net = model_lib.RawValueNet(inner).eval()

    metadata = {
        "tier": ckpt["tier"],
        "vocab_hash": ckpt["vocab_hash"],
        "heads": ",".join(heads),
        "head_mean": ",".join(f"{x:.9g}" for x in ckpt["head_mean"]),
        "head_std": ",".join(f"{x:.9g}" for x in ckpt["head_std"]),
        "schema_version": str(schema.SCHEMA_VERSION),
    }
    trace_batch = sample_batch(ckpt["vocab_size"], b=2, t=11, m=5, seed=1)
    # Checked on a different shape than traced, so a frozen axis fails here, not in the app.
    check_batch = sample_batch(ckpt["vocab_size"], b=3, t=23, m=9, seed=2)
    expected = _torch_outputs(net, check_batch)

    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        fp32_path = out if not int8 else os.path.join(tmp, "fp32.onnx")
        _trace(net, fp32_path, trace_batch)
        _set_metadata(fp32_path, metadata)
        onnx.checker.check_model(fp32_path)
        got = _ort_outputs(fp32_path, check_batch)
        fp32_diff = max(float(np.max(np.abs(g - e))) for g, e in zip(got, expected))
        result: dict[str, Any] = {
            "event": "exported",
            "out": out,
            "opset": OPSET,
            **metadata,
            "fp32_max_abs_diff": fp32_diff,
            "fp32_parity": fp32_diff <= FP32_TOLERANCE,
        }
        if int8:
            from onnxruntime.quantization import QuantType, quantize_dynamic

            quantize_dynamic(fp32_path, out, weight_type=QuantType.QInt8)
            _set_metadata(out, metadata)  # quantization writes a new model without our props
            got8 = _ort_outputs(out, check_batch)
            mask = check_batch["move_mask"]
            agree = (got8[0].argmax(-1) == expected[0].argmax(-1)).mean()
            result.update(
                int8=True,
                int8_policy_max_abs_diff=float(np.max(np.abs(got8[0] - expected[0])[mask])),
                int8_value_max_abs_diff=float(np.max(np.abs(got8[1] - expected[1]))),
                int8_top1_agreement=float(agree),
            )
    result["bytes"] = os.path.getsize(out)
    result["ok"] = result["fp32_parity"]
    return result


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m mapper_train.export", description="Export a checkpoint to ONNX.")
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--int8", action="store_true", help="dynamic int8 quantization (for phones)")
    args = parser.parse_args(argv)
    try:
        result = export(args.checkpoint, args.out, args.int8)
    except (ValueError, OSError) as e:
        print(json.dumps({"event": "error", "message": str(e)}), flush=True)
        return 1
    print(json.dumps(result), flush=True)
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
