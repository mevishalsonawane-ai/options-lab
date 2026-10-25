#!/usr/bin/env python3
"""Validates the experiment log and counts the tries per family (for multiple-testing control, ARCHITECTURE.md L6 gate 4).

    python research/experiments/validate.py            # validate every entry, print the per-family count
    python research/experiments/validate.py --json     # the per-family count as JSON (for a later BH step)

Each experiment is one YAML file in research/experiments/entries/ (see README.md for the fields). The validator checks
the fields and their types, that `id` matches the file name and is unique, that the date is a real ISO date, that the
`doc` and `prereg` paths exist in the repo, and that `holdout_read` is said (true / false / null for "no holdout").
Exit status 1 on any error.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import date
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ENTRIES = Path(__file__).resolve().parent / "entries"

FAMILIES = {
    "liquidity", "orb", "ml", "patterns-indicators", "flow-oi", "overnight", "calendar-events", "sizing-execution",
    "option-structure", "outside-strategies", "trade-management", "daily-filters", "market-facts", "mcx-crypto",
    "stocks", "diagnosis",
}
VERDICTS = {"adopted", "paper-only", "shadow", "rejected", "inconclusive", "superseded", "not-a-test", "proposed"}
# Written down, never run (25 Oct 2026: the books' testable rules): no variants, no holdout read, not counted as a test.
UNRUN = {"not-a-test", "proposed"}
REQUIRED = {
    "id": str, "title": str, "family": str, "date": str, "doc": str, "hypothesis": str, "data_version": str,
    "period": dict, "instruments": list, "features": list, "model_or_rule": str, "settings": str,
    "prereg": (str, type(None)), "variants_tried": (int, type(None)), "results": dict, "holdout_read": (bool, type(None)),
    "verdict": str,
}
OPTIONAL = {"notes": str, "backfilled": bool}
RESULT_KEYS = {"summary": str, "costs": str}


def check(path: Path, seen: set[str]) -> list[str]:
    errs = []
    try:
        e = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as ex:
        return [f"{path.name}: not YAML ({ex})"]
    if not isinstance(e, dict):
        return [f"{path.name}: not a mapping"]
    for k, t in REQUIRED.items():
        if k not in e:
            errs.append(f"{path.name}: missing {k}")
        elif not isinstance(e[k], t) or (t is int and isinstance(e[k], bool)):
            errs.append(f"{path.name}: {k} has the wrong type ({type(e[k]).__name__})")
    for k in e:
        if k not in REQUIRED and k not in OPTIONAL:
            errs.append(f"{path.name}: unknown field {k}")
    if errs:
        return errs
    if e["id"] != path.stem:
        errs.append(f"{path.name}: id {e['id']} does not match the file name")
    if e["id"] in seen:
        errs.append(f"{path.name}: duplicate id {e['id']}")
    seen.add(e["id"])
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]*", e["id"]):
        errs.append(f"{path.name}: id must be lowercase letters, digits and dashes")
    if e["family"] not in FAMILIES:
        errs.append(f"{path.name}: family {e['family']} not in {sorted(FAMILIES)}")
    if e["verdict"] not in VERDICTS:
        errs.append(f"{path.name}: verdict {e['verdict']} not in {sorted(VERDICTS)}")
    try:
        date.fromisoformat(e["date"])
    except ValueError:
        errs.append(f"{path.name}: date {e['date']} is not YYYY-MM-DD")
    for k in ("doc", "prereg"):
        if e[k] and not (ROOT / e[k]).exists():
            errs.append(f"{path.name}: {k} {e[k]} not found in the repo")
    if "design" not in e["period"]:
        errs.append(f"{path.name}: period needs design (and holdout when there was one)")
    for k, t in RESULT_KEYS.items():
        if not isinstance(e["results"].get(k), t) or not e["results"].get(k).strip():
            errs.append(f"{path.name}: results.{k} missing")
    if e["holdout_read"] and not e["period"].get("holdout"):
        errs.append(f"{path.name}: holdout_read true but no period.holdout")
    if e["variants_tried"] is not None and e["variants_tried"] < 1:
        errs.append(f"{path.name}: variants_tried must be >= 1")
    if e["verdict"] in UNRUN and e["variants_tried"]:
        errs.append(f"{path.name}: a {e['verdict']} entry tries no variants")
    if e["verdict"] == "proposed" and e["holdout_read"]:
        errs.append(f"{path.name}: a proposed entry was never run, so it read no holdout")
    return errs


def load() -> list[dict]:
    return [yaml.safe_load(p.read_text(encoding="utf-8")) for p in sorted(ENTRIES.glob("*.yaml"))]


def tries(entries: list[dict]) -> dict[str, dict]:
    """Per family: experiments, those that tested anything, variants tried (known), and how many said nothing of it."""
    out: dict[str, dict] = {}
    for e in entries:
        f = out.setdefault(e["family"], {"experiments": 0, "tests": 0, "variants": 0, "variants_unknown": 0, "adopted": 0})
        f["experiments"] += 1
        if e["verdict"] not in UNRUN:
            f["tests"] += 1
            if e["variants_tried"] is None:
                f["variants_unknown"] += 1
            else:
                f["variants"] += e["variants_tried"]
        if e["verdict"] in ("adopted", "paper-only", "shadow"):
            f["adopted"] += 1
    return dict(sorted(out.items()))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    seen: set[str] = set()
    errs = [x for p in sorted(ENTRIES.glob("*.yaml")) for x in check(p, seen)]
    if errs:
        print("\n".join(errs), file=sys.stderr)
        return 1
    t = tries(load())
    if args.json:
        print(json.dumps(t, indent=2))
        return 0
    print(f"{len(seen)} experiments, all valid.\n")
    print(f"{'family':22} {'exps':>5} {'tests':>6} {'variants':>10} {'unknown':>8} {'kept':>5}")
    for fam, f in t.items():
        print(f"{fam:22} {f['experiments']:5} {f['tests']:6} {f['variants']:10,} {f['variants_unknown']:8} {f['adopted']:5}")
    tot = sum(f["variants"] for f in t.values())
    print(f"\n{tot:,} variants logged in all (lower bound: {sum(f['variants_unknown'] for f in t.values())} tests did not say).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
