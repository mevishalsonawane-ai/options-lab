"""R197 section 6 (descriptive, not a test): every option-selling rule tested so far on one win-rate vs money table.

R103 (its published design numbers, as pre-registered there: stale legs NOT repriced, so its BANKNIFTY non-expiry rows
are flattered), R195's 23 tests, R196's 14, R197's 45-test grid. For each rule: design trades, win rate with its Wilson
95% interval, net, average win a, average loss b, b / a, and the break-even win rate W* = b / (a + b).

    python3 -I research/hunt/r197_winrate80/r197frontier.py      # -> results/frontier.md, results/frontier.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r196_selling_plus"):
    sys.path.insert(0, str(p))
import r195rules as RU  # noqa: E402
import r195study as S5  # noqa: E402
import r196study as S6  # noqa: E402
import r197study as S7  # noqa: E402


def row(study, rid, n, win, net, a, b):
    k = round(win * n)
    return dict(study=study, id=rid, n=n, win=round(win, 3), wilson=S7.wilson(k, n), net=round(net), avg_win=round(a),
                avg_loss=round(b), loss_to_win=round(b / a, 2) if a else None,
                breakeven_win=round(b / (a + b), 3) if a + b else None)


def main():
    rows = []
    r103 = json.loads((HERE.parent / "r103_sell" / "results" / "design.json").read_text())
    for t in r103["tests"]:
        d = t["design"]
        rows.append(row("R103", t["id"], d["n"], d["win"], d["net"], d["avg_win"], -d["avg_loss"]))
    for cid in RU.TESTS:
        r = S7.ratio(tr := S5._load(f"wf_{cid}.pkl"))
        s = S5.stats(tr)
        rows.append(row("R195", cid, s["n"], s["win"], s["net"], r["avg_win"], r["avg_loss"]))
    for cid in S6.CANDIDATES:
        tr = S6.load(f"wf_{cid}.pkl")
        r, s = S7.ratio(tr), S5.stats(tr)
        rows.append(row("R196", cid, s["n"], s["win"], s["net"], r["avg_win"], r["avg_loss"]))
    for cid in S7.GRID:
        tr = S7.load(S7.fname("wf", cid))
        r, s = S7.ratio(tr), S5.stats(tr)
        rows.append(row("R197", cid, s["n"], s["win"], s["net"], r["avg_win"], r["avg_loss"]))
    rows.sort(key=lambda x: -x["win"])
    S7.RESULTS.mkdir(parents=True, exist_ok=True)
    (S7.RESULTS / "frontier.json").write_text(json.dumps(rows, indent=1) + "\n")
    hi = [r for r in rows if r["win"] >= 0.80]
    L = ["# Every option-selling rule tested (R103, R195, R196, R197), design period, sorted by win rate", "",
         f"{len(rows)} rules. Rules at or above 80% wins: {len(hi)}"
         + (" (" + ", ".join(f"{r['study']} {r['id']} {r['win']:.0%}, net Rs {r['net']:,}" for r in hi) + ")" if hi else "")
         + ". W* = the win rate needed to break even with that rule's own average win and loss. R103's numbers are its "
           "pre-registered ones (stale legs not repriced; its BANKNIFTY non-expiry rows lose when repriced).", "",
         "| study | rule | trades | win (Wilson 95%) | net | avg win | avg loss | loss / win | W* |", "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        L.append(f"| {r['study']} | {r['id']} | {r['n']} | {r['win']:.1%} ({r['wilson'][0]:.1%}-{r['wilson'][1]:.1%}) | "
                 f"Rs {r['net']:,} | Rs {r['avg_win']:,} | Rs {r['avg_loss']:,} | {r['loss_to_win']} | "
                 f"{r['breakeven_win'] if r['breakeven_win'] is None else format(r['breakeven_win'], '.1%')} |")
    (S7.RESULTS / "frontier.md").write_text("\n".join(L) + "\n")
    print(len(rows), "rules;", len(hi), "at >= 80%; top:", [(r["study"], r["id"], r["win"], r["net"]) for r in rows[:6]])


if __name__ == "__main__":
    main()
