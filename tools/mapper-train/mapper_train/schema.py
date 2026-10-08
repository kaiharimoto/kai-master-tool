"""The data contract between the app and the trainer.

The app writes a run directory:

* ``vocab.json``   {"version": 1, "cards": [passcode, ...]}; index 0 is PAD, 1 is UNKNOWN.
* ``heads.json``   {"version": 1, "traits": [name, ...]}; the value head order.
* ``records-*.jsonl``  one position per line (see ``validate_record``).

vocab.json and heads.json only ever grow at the end (append-only), so a model trained on an
older run can carry on with a newer one: see ``extends``.

This module reads and checks those files and pads records into numpy arrays. It does not
import torch, so the app's contract tests can run without it.
"""

from __future__ import annotations

import glob
import hashlib
import json
import math
import os
from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence

import numpy as np

SCHEMA_VERSION = 1

PAD = 0
UNKNOWN = 1

ZONES = ("HAND", "DECK", "EXTRA", "GY", "BANISHED", "MONSTER", "SPELL", "FIELD", "EMZ", "MATERIAL")
ZONE_DECK = ZONES.index("DECK")
MOVE_KINDS = ("ACTIVATE", "NORMAL_SUMMON", "SET_MONSTER", "PROCEDURE", "PHASE_END", "PASS_OR_RESOLVE")
OWNERS = 2
FACES = 2
POSITIONS = 3  # none, attack, defence

MAX_TOKENS = 128
MAX_MOVES = 64
# Effect indices at or above this share the last embedding row (see model.py).
MAX_EFFECTS = 32

# Width of each token / move row as the app writes it.
TOKEN_FIELDS = 5  # card, zone, owner, face, pos
MOVE_FIELDS = 4  # kind, card, effect, zone (where the card is as the move is made; 0 if none)

# A head's spread is never taken as less than this, so a nearly constant trait is not blown up.
HEAD_STD_FLOOR = 1.0

POLICY_SUM_TOLERANCE = 1e-3


class SchemaError(ValueError):
    """A file or record does not follow the contract."""


@dataclass(frozen=True)
class Record:
    """One validated position, already truncated to MAX_TOKENS / MAX_MOVES."""

    hand: str
    step: int
    tokens: list[list[int]]
    moves: list[list[int]]
    policy: list[float]
    value: dict[str, float]


@dataclass
class RunData:
    """Everything the trainer needs from a run directory."""

    vocab: list[Any]
    vocab_hash: str
    heads: list[str]
    train: list[Record]
    val: list[Record]
    skipped: int = 0
    first_errors: list[str] = field(default_factory=list)

    @property
    def vocab_size(self) -> int:
        return len(self.vocab)


def vocab_hash(data: bytes) -> str:
    """sha256 of vocab.json's bytes, hex, first 16 characters (the app computes the same)."""
    return hashlib.sha256(data).hexdigest()[:16]


def load_vocab(run_dir: str) -> tuple[list[Any], str]:
    """Returns (the card list, PAD and UNKNOWN included; vocab hash)."""
    path = os.path.join(run_dir, "vocab.json")
    with open(path, "rb") as f:
        data = f.read()
    doc = json.loads(data)
    if not isinstance(doc, dict) or doc.get("version") != 1:
        raise SchemaError(f"{path}: expected version 1")
    cards = doc.get("cards")
    if not isinstance(cards, list) or len(cards) < 2:
        raise SchemaError(f"{path}: 'cards' must list PAD, UNKNOWN and the cards")
    return cards, vocab_hash(data)


def load_heads(run_dir: str) -> list[str]:
    path = os.path.join(run_dir, "heads.json")
    with open(path, "r", encoding="utf-8") as f:
        doc = json.load(f)
    if not isinstance(doc, dict) or doc.get("version") != 1:
        raise SchemaError(f"{path}: expected version 1")
    traits = doc.get("traits")
    if not isinstance(traits, list) or not traits or not all(isinstance(t, str) and t for t in traits):
        raise SchemaError(f"{path}: 'traits' must be a non-empty list of names")
    if len(set(traits)) != len(traits):
        raise SchemaError(f"{path}: duplicate trait names")
    return traits


def _int_row(row: Any, width: int, what: str) -> list[int]:
    if not isinstance(row, list) or len(row) != width:
        raise SchemaError(f"{what}: expected a list of {width} integers, got {row!r}")
    out = []
    for x in row:
        # bool is an int subclass in Python; the app never writes one.
        if isinstance(x, bool) or not isinstance(x, int):
            raise SchemaError(f"{what}: {x!r} is not an integer")
        out.append(x)
    return out


def _check_range(value: int, upper: int, what: str) -> None:
    if not 0 <= value < upper:
        raise SchemaError(f"{what}: {value} is outside 0..{upper - 1}")


def _number(x: Any, what: str) -> float:
    if isinstance(x, bool) or not isinstance(x, (int, float)) or not math.isfinite(x):
        raise SchemaError(f"{what}: {x!r} is not a finite number")
    return float(x)


def truncate_tokens(tokens: Sequence[list[int]], limit: int = MAX_TOKENS) -> list[list[int]]:
    """Keeps every non-DECK token (in order) before any DECK token, then cuts at `limit`.

    The Deck is the least informative zone (its order is hidden and it is usually the
    longest), so it is what gets dropped when a position is too big.
    """
    if len(tokens) <= limit:
        return list(tokens)
    kept = [t for t in tokens if t[1] != ZONE_DECK] + [t for t in tokens if t[1] == ZONE_DECK]
    return kept[:limit]


def truncate_moves(
    moves: Sequence[list[int]], policy: Sequence[float], limit: int = MAX_MOVES
) -> tuple[list[list[int]], list[float]]:
    """Keeps the `limit` moves with the most target mass (ties: original order), renormalised."""
    if len(moves) <= limit:
        return list(moves), list(policy)
    order = sorted(range(len(moves)), key=lambda i: -policy[i])[:limit]
    order.sort()  # keep the app's move order among the survivors
    kept_moves = [moves[i] for i in order]
    kept_policy = [policy[i] for i in order]
    total = sum(kept_policy)
    if total > 0:
        kept_policy = [p / total for p in kept_policy]
    else:
        kept_policy = [1.0 / len(kept_policy)] * len(kept_policy)
    return kept_moves, kept_policy


def validate_record(obj: Any, vocab_size: int, heads: Iterable[str]) -> Record:
    """Checks one decoded JSON line against the contract and returns it truncated.

    Fields the contract does not name (such as "prior" and "pos") are accepted and ignored,
    so the app can add to a record without breaking an older trainer.
    Raises SchemaError naming the first thing wrong.
    """
    if not isinstance(obj, dict):
        raise SchemaError("record is not an object")
    if obj.get("v") != SCHEMA_VERSION:
        raise SchemaError(f"unsupported record version {obj.get('v')!r}")
    hand = obj.get("hand")
    if not isinstance(hand, str) or not hand:
        raise SchemaError("'hand' must be a non-empty string")
    step = obj.get("step")
    if isinstance(step, bool) or not isinstance(step, int) or step < 0:
        raise SchemaError(f"'step' must be a non-negative integer, got {step!r}")

    raw_tokens = obj.get("tokens")
    if not isinstance(raw_tokens, list):
        raise SchemaError("'tokens' must be a list")
    tokens = []
    for i, row in enumerate(raw_tokens):
        t = _int_row(row, TOKEN_FIELDS, f"tokens[{i}]")
        _check_range(t[0], vocab_size, f"tokens[{i}].card")
        _check_range(t[1], len(ZONES), f"tokens[{i}].zone")
        _check_range(t[2], OWNERS, f"tokens[{i}].owner")
        _check_range(t[3], FACES, f"tokens[{i}].face")
        _check_range(t[4], POSITIONS, f"tokens[{i}].pos")
        tokens.append(t)

    raw_moves = obj.get("moves")
    if not isinstance(raw_moves, list) or not raw_moves:
        raise SchemaError("'moves' must be a non-empty list")
    moves = []
    for i, row in enumerate(raw_moves):
        m = _int_row(row, MOVE_FIELDS, f"moves[{i}]")
        _check_range(m[0], len(MOVE_KINDS), f"moves[{i}].kind")
        _check_range(m[1], vocab_size, f"moves[{i}].card")
        if m[2] < 0:
            raise SchemaError(f"moves[{i}].effect: {m[2]} is negative")
        _check_range(m[3], len(ZONES), f"moves[{i}].zone")
        moves.append(m)

    raw_policy = obj.get("policy")
    if not isinstance(raw_policy, list) or len(raw_policy) != len(moves):
        raise SchemaError("'policy' must have one entry per move")
    policy = [_number(p, f"policy[{i}]") for i, p in enumerate(raw_policy)]
    if any(p < 0 for p in policy):
        raise SchemaError("'policy' has a negative entry")
    total = sum(policy)
    if abs(total - 1.0) > POLICY_SUM_TOLERANCE:
        raise SchemaError(f"'policy' sums to {total}, not 1")
    policy = [p / total for p in policy]

    raw_value = obj.get("value", {})
    if not isinstance(raw_value, dict):
        raise SchemaError("'value' must be an object")
    known = set(heads)
    value = {}
    for k, v in raw_value.items():
        if k not in known:
            raise SchemaError(f"'value' has trait {k!r}, which heads.json does not list")
        value[k] = _number(v, f"value.{k}")

    moves, policy = truncate_moves(moves, policy)
    return Record(hand=hand, step=step, tokens=truncate_tokens(tokens), moves=moves, policy=policy, value=value)


def read_records(run_dir: str, vocab_size: int, heads: Sequence[str]) -> tuple[list[Record], int, list[str]]:
    """Reads every records-*.jsonl in name order. Bad lines are skipped and counted.

    Returns (records, skipped count, the first few error messages).
    """
    records: list[Record] = []
    skipped = 0
    errors: list[str] = []
    for path in sorted(glob.glob(os.path.join(run_dir, "records-*.jsonl"))):
        with open(path, "r", encoding="utf-8") as f:
            for n, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    records.append(validate_record(json.loads(line), vocab_size, heads))
                except (SchemaError, json.JSONDecodeError) as e:
                    skipped += 1
                    if len(errors) < 5:
                        errors.append(f"{os.path.basename(path)}:{n}: {e}")
    return records, skipped, errors


def is_validation(hand: str, val_fraction: float) -> bool:
    """Puts a hand on the validation side by its hash, so no hand is ever on both sides."""
    bucket = int(hashlib.sha256(hand.encode("utf-8")).hexdigest()[:8], 16) / 2**32
    return bucket < val_fraction


def load_run(run_dir: str, val_fraction: float) -> RunData:
    vocab, vhash = load_vocab(run_dir)
    heads = load_heads(run_dir)
    records, skipped, errors = read_records(run_dir, len(vocab), heads)
    train = [r for r in records if not is_validation(r.hand, val_fraction)]
    val = [r for r in records if is_validation(r.hand, val_fraction)]
    return RunData(vocab, vhash, heads, train, val, skipped, errors)


def extends(old: Sequence[Any], new: Sequence[Any]) -> bool:
    """True when `new` is `old` with entries appended (or the same): what append-only allows."""
    return len(new) >= len(old) and list(new[: len(old)]) == list(old)


def head_stats(records: Sequence[Record], heads: Sequence[str]) -> tuple[list[float], list[float]]:
    """Per-trait mean and spread over the records carrying it; the spread is floored at 1.

    Value heads are trained on (value - mean) / std so a trait counted in tens (the GY) does
    not drown one counted in ones (negates). A trait no record carries gets mean 0, std 1.
    """
    means, stds = [], []
    for h in heads:
        vals = np.array([r.value[h] for r in records if h in r.value], dtype=np.float64)
        if vals.size == 0:
            means.append(0.0)
            stds.append(HEAD_STD_FLOOR)
            continue
        means.append(float(vals.mean()))
        stds.append(max(HEAD_STD_FLOOR, float(vals.std())))
    return means, stds


def collate(records: Sequence[Record], heads: Sequence[str]) -> dict[str, np.ndarray]:
    """Pads a batch to its own longest position and move list (at least one of each).

    Returns numpy arrays named as the ONNX inputs, plus the training targets:
    tokens int64 [B,T,5], token_mask bool [B,T], step float32 [B], moves int64 [B,M,4],
    move_mask bool [B,M], policy float32 [B,M], value float32 [B,K], value_mask bool [B,K].
    Values are in raw units; the trainer standardises them.
    Padding rows are all zeros (card PAD) with their mask False.
    """
    if not records:
        raise ValueError("cannot collate an empty batch")
    b = len(records)
    t = max(1, max(len(r.tokens) for r in records))
    m = max(1, max(len(r.moves) for r in records))
    k = len(heads)
    head_index = {h: i for i, h in enumerate(heads)}

    out = {
        "tokens": np.zeros((b, t, TOKEN_FIELDS), dtype=np.int64),
        "token_mask": np.zeros((b, t), dtype=np.bool_),
        "step": np.zeros((b,), dtype=np.float32),
        "moves": np.zeros((b, m, MOVE_FIELDS), dtype=np.int64),
        "move_mask": np.zeros((b, m), dtype=np.bool_),
        "policy": np.zeros((b, m), dtype=np.float32),
        "value": np.zeros((b, k), dtype=np.float32),
        "value_mask": np.zeros((b, k), dtype=np.bool_),
    }
    for i, r in enumerate(records):
        if r.tokens:
            out["tokens"][i, : len(r.tokens)] = r.tokens
            out["token_mask"][i, : len(r.tokens)] = True
        out["step"][i] = r.step
        out["moves"][i, : len(r.moves)] = r.moves
        out["move_mask"][i, : len(r.moves)] = True
        out["policy"][i, : len(r.policy)] = r.policy
        for name, v in r.value.items():
            j = head_index[name]
            out["value"][i, j] = v
            out["value_mask"][i, j] = True
    return out
