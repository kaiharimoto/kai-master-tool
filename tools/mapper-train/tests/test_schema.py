"""Schema validation and padding. Plain unittest; needs numpy, not torch.

    cd tools/mapper-train && python -m unittest discover -s tests
"""

from __future__ import annotations

import copy
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mapper_train import schema  # noqa: E402

HEADS = ["interruptions", "negates", "through:ash"]
VOCAB = 10


def good() -> dict:
    return {
        "v": 1,
        "hand": "h1",
        "step": 2,
        "tokens": [[2, 0, 0, 0, 0], [3, 5, 0, 1, 1]],
        "moves": [[0, 2, 0, 0], [4, 0, 0, 0]],
        "policy": [0.25, 0.75],
        "value": {"interruptions": 2.0, "through:ash": 1},
    }


class ValidateTest(unittest.TestCase):
    def test_good_record(self) -> None:
        r = schema.validate_record(good(), VOCAB, HEADS)
        self.assertEqual(r.step, 2)
        self.assertEqual(r.value, {"interruptions": 2.0, "through:ash": 1.0})

    def bad(self, mutate) -> None:
        rec = copy.deepcopy(good())
        mutate(rec)
        with self.assertRaises(schema.SchemaError):
            schema.validate_record(rec, VOCAB, HEADS)

    def test_rejections(self) -> None:
        self.bad(lambda r: r.update(v=2))
        self.bad(lambda r: r.update(hand=""))
        self.bad(lambda r: r.update(step=-1))
        self.bad(lambda r: r.update(step=True))
        self.bad(lambda r: r["tokens"].append([VOCAB, 0, 0, 0, 0]))  # card outside the vocabulary
        self.bad(lambda r: r["tokens"].append([2, 10, 0, 0, 0]))  # zone 10
        self.bad(lambda r: r["tokens"].append([2, 0, 2, 0, 0]))  # owner 2
        self.bad(lambda r: r["tokens"].append([2, 0, 0, 0, 3]))  # position 3
        self.bad(lambda r: r["tokens"].append([2, 0, 0, 0]))  # too short
        self.bad(lambda r: r["tokens"].append([2.0, 0, 0, 0, 0]))  # not an integer
        self.bad(lambda r: r.update(moves=[], policy=[]))
        self.bad(lambda r: r["moves"].__setitem__(0, [6, 2, 0, 0]))  # kind 6
        self.bad(lambda r: r["moves"].__setitem__(0, [0, 2, -1, 0]))  # negative effect
        self.bad(lambda r: r["moves"].__setitem__(0, [0, 2, 0, 10]))  # zone 10
        self.bad(lambda r: r["moves"].__setitem__(0, [0, 2, 0]))  # the old three-field row
        self.bad(lambda r: r.update(policy=[0.5]))  # wrong length
        self.bad(lambda r: r.update(policy=[0.5, 0.6]))  # does not sum to 1
        self.bad(lambda r: r.update(policy=[-0.5, 1.5]))
        self.bad(lambda r: r.update(policy=[float("nan"), 1.0]))
        self.bad(lambda r: r["value"].update(bodies=1.0))  # not in heads.json
        self.bad(lambda r: r["value"].update(negates="1"))

    def test_unknown_fields_are_ignored(self) -> None:
        rec = good()
        rec.update(prior="none", pos="9f2c", something_new=[1, 2])
        self.assertEqual(schema.validate_record(rec, VOCAB, HEADS).hand, "h1")

    def test_empty_position_and_value_are_fine(self) -> None:
        rec = good()
        rec["tokens"] = []
        rec["value"] = {}
        r = schema.validate_record(rec, VOCAB, HEADS)
        self.assertEqual(r.tokens, [])
        self.assertEqual(r.value, {})

    def test_policy_within_tolerance_is_renormalised(self) -> None:
        rec = good()
        rec["policy"] = [0.25, 0.7505]
        r = schema.validate_record(rec, VOCAB, HEADS)
        self.assertAlmostEqual(sum(r.policy), 1.0, places=9)


class TruncateTest(unittest.TestCase):
    def test_deck_tokens_go_first(self) -> None:
        deck = [[2, schema.ZONE_DECK, 0, 0, 0]] * 200
        others = [[3, z, 0, 1, 0] for z in (0, 3, 5)] * 5  # 15 non-deck tokens
        tokens = deck[:100] + others + deck[100:]
        kept = schema.truncate_tokens(tokens)
        self.assertEqual(len(kept), schema.MAX_TOKENS)
        self.assertEqual(kept[:15], others)  # every non-deck token, in order, first
        self.assertTrue(all(t[1] == schema.ZONE_DECK for t in kept[15:]))

    def test_short_positions_are_untouched(self) -> None:
        tokens = [[2, schema.ZONE_DECK, 0, 0, 0], [3, 0, 0, 0, 0]]
        self.assertEqual(schema.truncate_tokens(tokens), tokens)

    def test_moves_keep_the_target_mass(self) -> None:
        n = schema.MAX_MOVES + 6
        moves = [[0, 2, i, 0] for i in range(n)]
        policy = [0.0] * n
        policy[n - 1] = 0.6  # the best move is past the limit
        policy[3] = 0.4
        rec = {"v": 1, "hand": "h", "step": 0, "tokens": [], "moves": moves, "policy": policy}
        r = schema.validate_record(rec, VOCAB, HEADS)
        self.assertEqual(len(r.moves), schema.MAX_MOVES)
        self.assertIn([0, 2, n - 1, 0], r.moves)
        self.assertAlmostEqual(sum(r.policy), 1.0)
        effects = [m[2] for m in r.moves]
        self.assertEqual(effects, sorted(effects))  # the app's order is kept


class CollateTest(unittest.TestCase):
    def test_padding_and_masks(self) -> None:
        a = schema.validate_record(good(), VOCAB, HEADS)
        rec = good()
        rec.update(hand="h2", tokens=[], moves=[[1, 3, 0, 5]], policy=[1.0], value={"negates": 1.0})
        b = schema.validate_record(rec, VOCAB, HEADS)
        out = schema.collate([a, b], HEADS)
        self.assertEqual(out["tokens"].shape, (2, 2, 5))
        self.assertEqual(out["moves"].shape, (2, 2, 4))
        self.assertEqual(out["moves"][1, 0].tolist(), [1, 3, 0, 5])
        self.assertEqual(out["token_mask"].tolist(), [[True, True], [False, False]])
        self.assertEqual(out["move_mask"].tolist(), [[True, True], [True, False]])
        self.assertEqual(out["tokens"][1].sum(), 0)  # padding is PAD everywhere
        self.assertEqual(out["policy"][1].tolist(), [1.0, 0.0])
        self.assertEqual(out["value_mask"].tolist(), [[True, False, True], [False, True, False]])
        self.assertEqual(out["value"][0, 0], 2.0)
        self.assertEqual(str(out["tokens"].dtype), "int64")
        self.assertEqual(str(out["step"].dtype), "float32")

    def test_all_empty_positions_still_have_one_column(self) -> None:
        rec = good()
        rec["tokens"] = []
        out = schema.collate([schema.validate_record(rec, VOCAB, HEADS)], HEADS)
        self.assertEqual(out["tokens"].shape, (1, 1, 5))
        self.assertFalse(out["token_mask"].any())

    def test_empty_batch_is_an_error(self) -> None:
        with self.assertRaises(ValueError):
            schema.collate([], HEADS)


class GrowthAndStatsTest(unittest.TestCase):
    def test_extends(self) -> None:
        self.assertTrue(schema.extends([0, 0, 5], [0, 0, 5]))
        self.assertTrue(schema.extends([0, 0, 5], [0, 0, 5, 7]))
        self.assertFalse(schema.extends([0, 0, 5, 7], [0, 0, 5]))  # shrank
        self.assertFalse(schema.extends([0, 0, 5, 7], [0, 0, 7, 5]))  # moved
        self.assertTrue(schema.extends(["a"], ["a", "through:ash"]))

    def test_head_stats(self) -> None:
        recs = []
        for v in [0.0, 10.0, 20.0]:
            rec = good()
            rec["value"] = {"interruptions": v, "negates": 1.0}
            recs.append(schema.validate_record(rec, VOCAB, HEADS))
        mean, std = schema.head_stats(recs, HEADS)
        self.assertAlmostEqual(mean[0], 10.0)
        self.assertAlmostEqual(std[0], (200 / 3) ** 0.5)
        self.assertEqual((mean[1], std[1]), (1.0, schema.HEAD_STD_FLOOR))  # constant: floored
        self.assertEqual((mean[2], std[2]), (0.0, 1.0))  # carried by no record


class RunTest(unittest.TestCase):
    def test_load_run_splits_by_hand_and_skips_bad_lines(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            vocab = json.dumps({"version": 1, "cards": list(range(VOCAB))}).encode()
            with open(os.path.join(d, "vocab.json"), "wb") as f:
                f.write(vocab)
            with open(os.path.join(d, "heads.json"), "w") as f:
                json.dump({"version": 1, "traits": HEADS}, f)
            with open(os.path.join(d, "records-000.jsonl"), "w") as f:
                for i in range(200):
                    rec = good()
                    rec["hand"] = f"hand-{i // 4}"  # four positions per hand
                    f.write(json.dumps(rec) + "\n")
                f.write("\n")  # blank lines are ignored
                f.write("{broken\n")
                f.write(json.dumps({**good(), "v": 9}) + "\n")
            run = schema.load_run(d, 0.25)
            self.assertEqual(run.vocab_size, VOCAB)
            self.assertEqual(run.vocab_hash, schema.vocab_hash(vocab))
            self.assertEqual(len(run.vocab_hash), 16)
            self.assertEqual(run.skipped, 2)
            self.assertEqual(len(run.train) + len(run.val), 200)
            self.assertTrue(run.val and run.train)
            self.assertFalse({r.hand for r in run.train} & {r.hand for r in run.val})

    def test_heads_must_be_unique(self) -> None:
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "heads.json"), "w") as f:
                json.dump({"version": 1, "traits": ["a", "a"]}, f)
            with self.assertRaises(schema.SchemaError):
                schema.load_heads(d)


if __name__ == "__main__":
    unittest.main()
