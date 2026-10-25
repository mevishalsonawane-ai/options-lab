"""Writes PREREG_RULES.md from r104rules.py (the register as committed with PREREG.md).

    python research/hunt/r104_books/make_prereg_table.py
"""
from __future__ import annotations

import re
from pathlib import Path

import r104rules as R

HERE = Path(__file__).resolve().parent
TR = HERE.parents[1] / "books" / "TESTABLE_RULES.md"


def titles() -> dict:
    t = TR.read_text()
    return {m.group(1): m.group(2).strip() for m in re.finditer(r"\n### `([^`]+)` (.+)", t)}


def table() -> str:
    ti = titles()
    out = ["# R104 rule register (generated from `r104rules.py`; part of PREREG.md)", "",
           "Mechanics: `research/books/TESTABLE_RULES.md`, rule by rule. Markets: see PREREG.md section 5.", "",
           "| rule | title | status | family | markets (tests) | R104 note |", "|---|---|---|---|---|---|"]
    for rid, (st, fam, mk, why) in R.R.items():
        out.append(f"| `{rid}` | {ti[rid]} | {st} | {fam} | {', '.join(mk) or '-'} | {why or '-'} |")
    n = len(R.tests())
    out += ["", f"{len(R.R)} rules; {len(R.by_status('test'))} tested in {n} tests; "
            f"{len(R.by_status('covered'))} covered; {len(R.by_status('lack'))} lack data; {len(R.by_status('desc'))} descriptive.", ""]
    return "\n".join(out)


if __name__ == "__main__":
    (HERE / "PREREG_RULES.md").write_text(table())
    print("PREREG_RULES.md written")
