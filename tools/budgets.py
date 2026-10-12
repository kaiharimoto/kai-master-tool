#!/usr/bin/env python3
"""The studio's frame and memory budgets (Phase I, I.7; F5).

    tools/budgets.py <shots_dir> <budgets.json>

Reads every <name>.measure.json the studio wrote with --measure (30 whole repaints
timed once the page has settled, whether it still asked for frames with nothing
moving, and the heap after a collection) and holds each to its page's budget in
budgets.json. Prints a Markdown table for the job's summary. Exits 1 when a page
is over a budget, asks for frames while idle where its budget says it must not, or
was budgeted and not measured; 2 on a usage error.
"""
import json
import pathlib
import sys

CHECKS = (("medianMs", "median", "ms"), ("p95Ms", "p95", "ms"), ("heapMb", "heap", "MB"))


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    shots, budgets = pathlib.Path(argv[1]), json.loads(pathlib.Path(argv[2]).read_text())
    pages = budgets.get("pages", {})
    measured = {p.name[: -len(".measure.json")]: json.loads(p.read_text()) for p in sorted(shots.glob("*.measure.json"))}
    rows, breaches = [], []
    for name, budget in pages.items():
        m = measured.get(name)
        if m is None:
            breaches.append(f"{name}: budgeted, not measured")
            rows.append(f"| {name} | — | — | — | — | not measured |")
            continue
        over = []
        for key, word, unit in CHECKS:
            if key in budget and m.get(key, 0) > budget[key]:
                over.append(f"{word} {m[key]} {unit} over {budget[key]}")
        if budget.get("idle") and not m.get("idle", False):
            over.append("asks for frames with nothing moving")
        breaches += [f"{name}: {o}" for o in over]
        cells = [f"{m.get(k, '—')} / {budget.get(k, '—')}" for k, _, _ in CHECKS]
        idle = "quiet" if m.get("idle") else "asks for frames"
        rows.append(f"| {name} | {' | '.join(cells)} | {idle} | {'over: ' + '; '.join(over) if over else 'within'} |")
    for name in measured:
        if name not in pages:
            rows.append(f"| {name} | {measured[name].get('medianMs')} | {measured[name].get('p95Ms')} | {measured[name].get('heapMb')} | — | no budget |")
    print("### Frame and memory budgets\n")
    print(f"Measured at {next(iter(measured.values()), {}).get('width', '?')}×{next(iter(measured.values()), {}).get('height', '?')}, software Skia: measured / budget.\n")
    print("| page | median ms | p95 ms | heap MB | when still | verdict |")
    print("|---|---|---|---|---|---|")
    print("\n".join(rows))
    if breaches:
        print("\n**Over budget:** " + "; ".join(breaches))
        for b in breaches:
            print(f"::error title=Over budget::{b}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
