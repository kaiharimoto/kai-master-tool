"""PolicyValueNet: a transformer over a position's cards that scores its legal moves.

Inputs (the ONNX contract, see export.py):
    tokens      int64 [B,T,5]  card, zone, owner, face, pos
    token_mask  bool  [B,T]    True where a token is real
    step        float32 [B]    moves made in the turn so far
    moves       int64 [B,M,4]  kind, card, effect, zone
    move_mask   bool  [B,M]    True where a move is real
Outputs of forward():
    policy_logits float32 [B,M]  masked moves are exactly -1e9
    value_z       float32 [B,K]  each trait standardised by the training split's mean and spread;
                                 raw_value() turns it back into counts (the exported graph does)

Attention is written out by hand rather than with nn.MultiheadAttention or
nn.TransformerEncoder: their inference fast paths (nested tensors, fused kernels) change
behaviour between train and eval and do not trace cleanly into ONNX.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import torch
from torch import nn
from torch.nn import functional as F

from . import schema

MASKED = -1e9


@dataclass(frozen=True)
class Tier:
    d: int
    layers: int
    heads: int


TIERS: dict[str, Tier] = {
    "S": Tier(d=128, layers=2, heads=4),
    "M": Tier(d=256, layers=4, heads=8),
    "L": Tier(d=512, layers=8, heads=8),
}


def _attention_bias(mask: torch.Tensor) -> torch.Tensor:
    """[B,T] bool -> [B,1,1,T] additive bias: 0 where kept, MASKED where padded."""
    zero = torch.zeros((), dtype=torch.float32, device=mask.device)
    big = torch.full((), MASKED, dtype=torch.float32, device=mask.device)
    return torch.where(mask, zero, big)[:, None, None, :]


class Attention(nn.Module):
    """Multi-head attention of `x` over `context`, scores kept in float32 under autocast."""

    def __init__(self, d: int, heads: int, dropout: float) -> None:
        super().__init__()
        if d % heads:
            raise ValueError("d must divide by heads")
        self.heads = heads
        self.head_dim = d // heads
        self.scale = 1.0 / math.sqrt(self.head_dim)
        self.q = nn.Linear(d, d)
        self.kv = nn.Linear(d, 2 * d)
        self.out = nn.Linear(d, d)
        self.dropout = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, context: torch.Tensor, bias: torch.Tensor) -> torch.Tensor:
        b, n, d = x.shape
        t = context.shape[1]
        h, dh = self.heads, self.head_dim
        q = self.q(x).view(b, n, h, dh).transpose(1, 2)
        k, v = self.kv(context).view(b, t, 2, h, dh).permute(2, 0, 3, 1, 4).unbind(0)
        scores = torch.matmul(q, k.transpose(-1, -2)).float() * self.scale + bias
        weights = self.dropout(torch.softmax(scores, dim=-1)).to(v.dtype)
        mixed = torch.matmul(weights, v).transpose(1, 2).reshape(b, n, d)
        return self.out(mixed)


class Block(nn.Module):
    """Pre-norm attention + feed-forward block."""

    def __init__(self, d: int, heads: int, dropout: float) -> None:
        super().__init__()
        self.norm1 = nn.LayerNorm(d)
        self.attn = Attention(d, heads, dropout)
        self.norm2 = nn.LayerNorm(d)
        self.ff = nn.Sequential(nn.Linear(d, 4 * d), nn.GELU(), nn.Dropout(dropout), nn.Linear(4 * d, d))
        self.drop = nn.Dropout(dropout)

    def forward(self, x: torch.Tensor, context: torch.Tensor | None, bias: torch.Tensor) -> torch.Tensor:
        """Self-attention when `context` is None, else cross-attention over it."""
        n = self.norm1(x)
        x = x + self.drop(self.attn(n, n if context is None else context, bias))
        return x + self.drop(self.ff(self.norm2(x)))


class PolicyValueNet(nn.Module):
    def __init__(self, vocab_size: int, num_heads_out: int, tier: str = "S", dropout: float = 0.1) -> None:
        super().__init__()
        cfg = TIERS[tier]
        d = cfg.d
        self.d = d
        # Shared by position tokens and moves: a card means the same thing in both.
        self.card = nn.Embedding(vocab_size, d, padding_idx=schema.PAD)
        self.zone = nn.Embedding(len(schema.ZONES), d)
        self.owner = nn.Embedding(schema.OWNERS, d)
        self.face = nn.Embedding(schema.FACES, d)
        self.pos = nn.Embedding(schema.POSITIONS, d)
        self.cls = nn.Parameter(torch.zeros(1, 1, d))
        # The step count enters as log1p(step) so long turns do not swamp the [CLS] token.
        self.step_embed = nn.Linear(1, d)
        self.encoder = nn.ModuleList(Block(d, cfg.heads, dropout) for _ in range(cfg.layers))
        self.encoder_norm = nn.LayerNorm(d)

        self.kind = nn.Embedding(len(schema.MOVE_KINDS), d)
        self.effect = nn.Embedding(schema.MAX_EFFECTS, d)
        # Its own table, not the tokens' zone: "where the card is moved from" is another role.
        self.move_zone = nn.Embedding(len(schema.ZONES), d)
        # Each move reads the encoded position once before it is scored.
        self.move_block = Block(d, cfg.heads, dropout)
        self.move_norm = nn.LayerNorm(d)
        self.query = nn.Linear(d, d)  # [CLS] -> what a good move looks like here
        self.move_bias = nn.Linear(d, 1)  # a move's score on its own

        self.value_head = nn.Sequential(nn.Linear(d, d), nn.GELU(), nn.Linear(d, num_heads_out))
        # The training split's per-trait mean and spread (schema.head_stats), saved with the weights.
        self.register_buffer("head_mean", torch.zeros(num_heads_out))
        self.register_buffer("head_std", torch.ones(num_heads_out))

        nn.init.normal_(self.cls, std=0.02)
        for emb in (self.card, self.zone, self.owner, self.face, self.pos, self.kind, self.effect, self.move_zone):
            nn.init.normal_(emb.weight, std=0.02)
        with torch.no_grad():
            self.card.weight[schema.PAD].zero_()

    def forward(
        self,
        tokens: torch.Tensor,
        token_mask: torch.Tensor,
        step: torch.Tensor,
        moves: torch.Tensor,
        move_mask: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        b = tokens.shape[0]
        # Clamps keep a stray index from crashing a phone; validation already refuses them.
        card = tokens[..., 0].clamp(0, self.card.num_embeddings - 1)
        x = (
            self.card(card)
            + self.zone(tokens[..., 1].clamp(0, len(schema.ZONES) - 1))
            + self.owner(tokens[..., 2].clamp(0, schema.OWNERS - 1))
            + self.face(tokens[..., 3].clamp(0, schema.FACES - 1))
            + self.pos(tokens[..., 4].clamp(0, schema.POSITIONS - 1))
        )
        cls = self.cls.expand(b, -1, -1) + self.step_embed(torch.log1p(step.clamp(min=0)).unsqueeze(-1)).unsqueeze(1)
        x = torch.cat([cls, x], dim=1)
        keep = torch.cat([torch.ones_like(token_mask[:, :1]), token_mask], dim=1)
        bias = _attention_bias(keep)
        for block in self.encoder:
            x = block(x, None, bias)
        x = self.encoder_norm(x)
        pooled = x[:, 0]

        m = (
            self.kind(moves[..., 0].clamp(0, len(schema.MOVE_KINDS) - 1))
            + self.card(moves[..., 1].clamp(0, self.card.num_embeddings - 1))
            + self.effect(moves[..., 2].clamp(0, schema.MAX_EFFECTS - 1))
            + self.move_zone(moves[..., 3].clamp(0, len(schema.ZONES) - 1))
        )
        m = self.move_norm(self.move_block(m, x, bias))
        query = self.query(pooled).float()
        scores = torch.einsum("bmd,bd->bm", m.float(), query) / math.sqrt(self.d)
        scores = scores + self.move_bias(m).float().squeeze(-1)
        policy_logits = torch.where(move_mask, scores, torch.full_like(scores, MASKED))

        value_z = self.value_head(pooled).float()
        return policy_logits, value_z

    def set_head_stats(self, mean: list[float], std: list[float]) -> None:
        with torch.no_grad():
            self.head_mean.copy_(torch.tensor(mean))
            self.head_std.copy_(torch.tensor(std))

    def standardise(self, value: torch.Tensor) -> torch.Tensor:
        return (value - self.head_mean) / self.head_std

    def raw_value(self, value_z: torch.Tensor) -> torch.Tensor:
        return value_z * self.head_std + self.head_mean


class RawValueNet(nn.Module):
    """The exported graph: PolicyValueNet with `value` back in raw units."""

    def __init__(self, net: PolicyValueNet) -> None:
        super().__init__()
        self.net = net

    def forward(
        self,
        tokens: torch.Tensor,
        token_mask: torch.Tensor,
        step: torch.Tensor,
        moves: torch.Tensor,
        move_mask: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        policy_logits, value_z = self.net(tokens, token_mask, step, moves, move_mask)
        return policy_logits, self.net.raw_value(value_z)


def grow_state(state: dict[str, torch.Tensor], fresh: dict[str, torch.Tensor]) -> dict[str, torch.Tensor]:
    """An old model's weights fitted into a model with more cards and/or traits.

    vocab.json and heads.json only grow at the end, so every tensor whose first dimension
    grew (the card table, the last value layer, the head statistics) keeps its old rows and
    takes the new rows from `fresh` (a newly initialised model's state). Anything else must
    match exactly.
    """
    out = {}
    for name, new in fresh.items():
        old = state[name]
        if old.shape == new.shape:
            out[name] = old
        elif old.dim() == new.dim() and old.shape[1:] == new.shape[1:] and old.shape[0] < new.shape[0]:
            out[name] = torch.cat([old, new[old.shape[0] :]], dim=0)
        else:
            raise ValueError(f"{name}: cannot grow {tuple(old.shape)} into {tuple(new.shape)}")
    return out


def build(vocab_size: int, num_heads_out: int, tier: str, dropout: float = 0.1) -> PolicyValueNet:
    if tier not in TIERS:
        raise ValueError(f"unknown tier {tier!r}; expected one of {sorted(TIERS)}")
    return PolicyValueNet(vocab_size, num_heads_out, tier, dropout)


def losses(
    policy_logits: torch.Tensor,
    value: torch.Tensor,
    policy_target: torch.Tensor,
    value_target: torch.Tensor,
    value_mask: torch.Tensor,
) -> tuple[torch.Tensor, torch.Tensor]:
    """Soft-target cross-entropy over legal moves, and MSE over the traits a record carries.

    `value` and `value_target` are both standardised (see PolicyValueNet.standardise).
    """
    log_p = F.log_softmax(policy_logits.float(), dim=-1)
    # Padded moves have target 0 and a finite (very negative) log-probability: no NaN.
    policy_loss = -(policy_target * log_p).sum(dim=-1).mean()
    mask = value_mask.float()
    value_loss = (((value.float() - value_target) ** 2) * mask).sum() / mask.sum().clamp(min=1.0)
    return policy_loss, value_loss
