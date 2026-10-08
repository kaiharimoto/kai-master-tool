"""What this machine can train on, and how fast.

`python -m mapper_train.hardware` prints one JSON object:
    {"device": "cuda"|"rocm"|"mps"|"cpu", "device_name": ..., "memory_bytes": ...,
     "cpu_cores": ..., "torch": ..., "steps_per_s": ..., "probe_batch": ..., "probe_seconds": ...}
`memory_bytes` is the GPU's memory on cuda/rocm, else the system's RAM (MPS shares it).
`steps_per_s` is measured: tier S training steps on random data for about three seconds.
"""

from __future__ import annotations

import ctypes
import json
import os
import platform
import sys
import time
from typing import Any

import torch

from . import model as model_lib
from . import schema

PROBE_BATCH = 64
PROBE_TOKENS = 96
PROBE_MOVES = 24
PROBE_VOCAB = 2048
PROBE_TRAITS = 9


def device_kind() -> str:
    if torch.cuda.is_available():
        # ROCm builds of torch answer through the cuda API; torch.version.hip tells them apart.
        return "rocm" if getattr(torch.version, "hip", None) else "cuda"
    if getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        return "mps"
    return "cpu"


def pick_device(requested: str | None = None) -> torch.device:
    """The best device here, or the one asked for ("cuda", "rocm", "mps" or "cpu")."""
    kind = requested or device_kind()
    if kind in ("cuda", "rocm"):
        return torch.device("cuda")
    return torch.device(kind)


def _system_memory() -> int:
    if sys.platform == "win32":

        class MemoryStatus(ctypes.Structure):
            _fields_ = [
                ("dwLength", ctypes.c_ulong),
                ("dwMemoryLoad", ctypes.c_ulong),
                ("ullTotalPhys", ctypes.c_ulonglong),
                ("ullAvailPhys", ctypes.c_ulonglong),
                ("ullTotalPageFile", ctypes.c_ulonglong),
                ("ullAvailPageFile", ctypes.c_ulonglong),
                ("ullTotalVirtual", ctypes.c_ulonglong),
                ("ullAvailVirtual", ctypes.c_ulonglong),
                ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
            ]

        status = MemoryStatus()
        status.dwLength = ctypes.sizeof(MemoryStatus)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))  # type: ignore[attr-defined]
        return int(status.ullTotalPhys)
    try:
        return int(os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"))
    except (ValueError, OSError, AttributeError):
        return 0


def _device_name(kind: str) -> str:
    if kind in ("cuda", "rocm"):
        return torch.cuda.get_device_name(0)
    if kind == "mps":
        return f"Apple GPU ({platform.machine()})"
    return platform.processor() or platform.machine() or "cpu"


def _random_batch(device: torch.device, gen: torch.Generator) -> dict[str, torch.Tensor]:
    b, t, m = PROBE_BATCH, PROBE_TOKENS, PROBE_MOVES

    def ints(high: int, *shape: int) -> torch.Tensor:
        return torch.randint(0, high, shape, generator=gen)

    tokens = torch.stack(
        [ints(PROBE_VOCAB, b, t), ints(len(schema.ZONES), b, t), ints(2, b, t), ints(2, b, t), ints(3, b, t)], dim=-1
    )
    moves = torch.stack(
        [ints(len(schema.MOVE_KINDS), b, m), ints(PROBE_VOCAB, b, m), ints(4, b, m), ints(len(schema.ZONES), b, m)],
        dim=-1,
    )
    policy = torch.softmax(torch.randn(b, m, generator=gen), dim=-1)
    batch = {
        "tokens": tokens,
        "token_mask": torch.ones(b, t, dtype=torch.bool),
        "step": torch.rand(b, generator=gen) * 10,
        "moves": moves,
        "move_mask": torch.ones(b, m, dtype=torch.bool),
        "policy": policy,
        "value": torch.rand(b, PROBE_TRAITS, generator=gen) * 3,
        "value_mask": torch.ones(b, PROBE_TRAITS, dtype=torch.bool),
    }
    return {k: v.to(device) for k, v in batch.items()}


def _sync(device: torch.device) -> None:
    if device.type == "cuda":
        torch.cuda.synchronize()
    elif device.type == "mps":
        torch.mps.synchronize()


def measure_throughput(device: torch.device, seconds: float = 3.0) -> float:
    """Tier S optimizer steps per second on random data, after two warm-up steps."""
    gen = torch.Generator().manual_seed(0)
    torch.manual_seed(0)
    net = model_lib.build(PROBE_VOCAB, PROBE_TRAITS, "S").to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=1e-4)
    batch = _random_batch(device, gen)
    # Mixed precision on CUDA, as train.py uses it (bf16 here keeps the probe free of loss scaling).
    use_amp = device.type == "cuda" and torch.cuda.is_bf16_supported()

    def one_step() -> None:
        with torch.autocast("cuda", dtype=torch.bfloat16, enabled=use_amp):
            logits, value = net(batch["tokens"], batch["token_mask"], batch["step"], batch["moves"], batch["move_mask"])
        pl, vl = model_lib.losses(logits, value, batch["policy"], batch["value"], batch["value_mask"])
        opt.zero_grad(set_to_none=True)
        (pl + vl).backward()
        opt.step()

    for _ in range(2):
        one_step()
    _sync(device)
    steps = 0
    start = time.perf_counter()
    while time.perf_counter() - start < seconds:
        one_step()
        steps += 1
        if steps % 4 == 0:
            _sync(device)
    _sync(device)
    return steps / (time.perf_counter() - start)


def probe(seconds: float = 3.0) -> dict[str, Any]:
    kind = device_kind()
    device = pick_device(kind)
    memory = torch.cuda.get_device_properties(0).total_memory if kind in ("cuda", "rocm") else _system_memory()
    return {
        "device": kind,
        "device_name": _device_name(kind),
        "memory_bytes": int(memory),
        "cpu_cores": os.cpu_count() or 1,
        "torch": torch.__version__,
        "steps_per_s": round(measure_throughput(device, seconds), 3),
        "probe_batch": PROBE_BATCH,
        "probe_seconds": seconds,
    }


if __name__ == "__main__":
    print(json.dumps(probe()), flush=True)
