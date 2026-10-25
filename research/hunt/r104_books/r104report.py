"""R104 tables for HUNT_R104_BOOKS.md: every rule (book, signal source, trades, win rate, net after costs, q, verdict).

    python research/hunt/r104_books/r104report.py     # results/table_all.md, results/table_covered.md, results/table_lack.md
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
import r104rules as RR  # noqa: E402
from r104entries import SIGNAL, pct, rs  # noqa: E402

TR = HERE.parents[1] / "books" / "TESTABLE_RULES.md"


def book_of():
    t = TR.read_text()
    out = {}
    cur = None
    for line in t.splitlines():
        m = re.match(r"## (.+?) - (.+?) \(\[write-up\]", line)
        if m:
            cur = m.group(2).strip()
        m = re.match(r"### `([^`]+)`", line)
        if m:
            out[m.group(1)] = cur
    return out


def main():
    final = json.loads((K.RESULTS / "final.json").read_text())
    bk = book_of()
    rows = ["| rule | book | market (signal source) | variant | design trades | win | design net | holdout net "
            "(trades) | q | verdict |", "|---|---|---|---|---|---|---|---|---|---|"]
    for r in sorted(final, key=lambda x: (x["rule"].startswith("new"), x["rule"], x["market"])):
        h = r.get("holdout") or {}
        mk = r["market"]
        if mk == "ARM":
            dn = f"{rs(r['net'])} (arm {rs(r['b0_net'])}, delta {rs(r['delta'])})"
            hn = f"delta {rs(h.get('delta'))}" if h else "-"
            win = f"{pct(r.get('win'))} (arm {pct(r.get('b0_win'))})"
            n = r.get("n")
        elif mk == "MM":
            dn = f"{rs(r['net'])} vs {rs(r['b0_net'])}; DD {rs(r['maxdd'])} vs {rs(r['b0_maxdd'])}"
            hn = (f"DD cut {h['dd_cut']:.0%}, net cut {h['net_cut']:.0%}" if h else "-")
            win, n = "-", "-"
        else:
            dn = rs(r["net"])
            if r.get("cmp"):
                dn += f" (host {rs(r['cmp']['host_net'])})"
            hn = f"{rs(h.get('net'))} ({h.get('n')})" if h else "-"
            win, n = pct(r.get("win")), r.get("n")
        v = "**PASS**" if r["final_pass"] else ("design pass, holdout fail" if r.get("design_pass") else "fail")
        rows.append(f"| `{r['rule']}` | {bk.get(r['rule'], 'Amendment 1')} | {mk} ({SIGNAL[mk]}) | {r.get('variant')} | "
                    f"{n} | {win} | {dn} | {hn} | {r['q']:.2f} | {v} |")
    (K.RESULTS / "table_all.md").write_text("\n".join(rows) + "\n")
    cov = ["| rule | book | covered by (earlier result) |", "|---|---|---|"]
    lack = ["| rule | book | what is missing |", "|---|---|---|"]
    for rid, (st, fam, mk, why) in RR.R.items():
        if st == "covered":
            cov.append(f"| `{rid}` | {bk.get(rid)} | {why} |")
        elif st in ("lack",):
            lack.append(f"| `{rid}` | {bk.get(rid)} | {why} |")
    (K.RESULTS / "table_covered.md").write_text("\n".join(cov) + "\n")
    (K.RESULTS / "table_lack.md").write_text("\n".join(lack) + "\n")
    print(len(final), "rows")


if __name__ == "__main__":
    main()
