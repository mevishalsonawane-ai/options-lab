"""R104 -> research/knowledge/book_facts.yaml: a book card whose rule R104 tested now carries the result.

Each tested rule is matched to the one card of its own book whose words overlap most with the rule's title (cards that
already carry an earlier study's result are left as they are). The card's verdict becomes negative (lost after costs),
weak (made money in design but failed a gate) or positive (passed every gate); tested_src is HUNT_R104_BOOKS.md and
tested_text quotes numbers that appear in that report's table.

    python research/hunt/r104_books/r104cards.py        # after HUNT_R104_BOOKS.md is written
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
from r104entries import pct, rs  # noqa: E402

ROOT = HERE.parents[2]
CARDS = ROOT / "research" / "knowledge" / "book_facts.yaml"
TR = ROOT / "research" / "books" / "TESTABLE_RULES.md"
STOP = set("the a an of on in at to and or for with by is be when its it as after before from into than then only one "
           "two three bar bars buy sell long short trade rule".split())


def words(s):
    return {w for w in re.findall(r"[a-z]+", s.lower()) if len(w) > 2 and w not in STOP}


def rule_books():
    """{rule: (book short name, title)} from TESTABLE_RULES.md."""
    out, cur = {}, None
    for line in TR.read_text().splitlines():
        m = re.search(r"\(\[write-up\]\(([a-z0-9-]+)\.md\)\)", line)
        if line.startswith("## ") and m:
            cur = m.group(1)
        m2 = re.match(r"### `([^`]+)` (.+)", line)
        if m2:
            out[m2.group(1)] = (cur, m2.group(2))
    return out


def tested_text(rows):
    parts = []
    for r in rows:
        h = r.get("holdout") or {}
        if r["market"] in ("ARM",):
            parts.append(f"on the arms {rs(r['net'])} vs {rs(r['b0_net'])} without it")
        elif r["market"] == "MM":
            parts.append(f"drawdown {rs(r['maxdd'])} vs {rs(r['b0_maxdd'])} on the arms")
        else:
            parts.append(f"{r['market']} {r['n']} trades, win {pct(r.get('win'))}, net {rs(r['net'])} in design")
    ok = any(r["final_pass"] for r in rows)
    return "R104 tested this rule as written, after charges and slippage: " + "; ".join(parts) + \
        (". It passed every gate." if ok else ". It did not pass the gates.")


def main():
    final = json.loads((K.RESULTS / "final.json").read_text())
    by_rule = {}
    for r in final:
        if r["rule"].startswith("book-"):
            by_rule.setdefault(r["rule"], []).append(r)
    rb = rule_books()
    data = yaml.safe_load(CARDS.read_text())
    cards = data["cards"]
    # re-runs start clean: a card that carries an R104 result from an earlier run goes back to context first
    for c in cards:
        if c.get("tested_src") == "research/HUNT_R104_BOOKS.md":
            c["verdict"] = "context"
            c.pop("tested_src", None)
            c.pop("tested_text", None)
    used = set()
    n = 0
    for rid, rows in by_rule.items():
        short, title = rb.get(rid, (None, ""))
        cand = [c for c in cards if c["book"] == short and c.get("verdict", "context") == "context" and c["id"] not in used]
        if not cand:
            continue
        tw = words(title)
        best = max(cand, key=lambda c: len(tw & words(c["title"] + " " + c["text"])) / (len(tw) or 1))
        if len(tw & words(best["title"] + " " + best["text"])) < 2:
            continue
        used.add(best["id"])
        ok = any(r["final_pass"] for r in rows)
        pos = any((r.get("net") or 0) > 0 for r in rows if r["market"] not in ("ARM", "MM"))
        best["verdict"] = "positive" if ok else ("weak" if pos else "negative")
        best["tested_src"] = "research/HUNT_R104_BOOKS.md"
        best["tested_text"] = tested_text(rows)
        n += 1
    head = "".join(CARDS.read_text().splitlines(keepends=True)[:4])
    body = yaml.safe_dump(data, sort_keys=False, allow_unicode=True, width=120)
    CARDS.write_text(head + body)
    print(n, "cards now carry an R104 result")


if __name__ == "__main__":
    main()
