"""R131 report tables from results/design.json (and results/holdout.json once read) plus the trade lists in $R131_OUT.

    python research/hunt/r131_ict_ten/tables.py          # markdown to stdout, results/combined.json
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r131 as R  # noqa: E402

YEARS = (2022, 2023, 2024, 2025, 2026)


def rs(x: float) -> str:
    s = f"{abs(x):,.0f}"
    return ("-Rs " if x < 0 else "Rs ") + s


def lakh(x: float) -> str:
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x) / 1e5:.2f} lakh"


def load():
    des = json.loads((R.RESULTS / "design.json").read_text())
    hp = R.RESULTS / "holdout.json"
    hol = json.loads(hp.read_text()) if hp.exists() else None
    T = pd.read_parquet(R.OUT / "trades_design.parquet")
    if hol is not None and (R.OUT / "trades_holdout.parquet").exists():
        T = pd.concat([T, pd.read_parquet(R.OUT / "trades_holdout.parquet")], ignore_index=True)
    T["day"] = pd.to_datetime(T.day).dt.date
    return des, hol, T


def combined(T: pd.DataFrame) -> dict:
    out = {}
    for v in R.VARIANTS:
        X = T[T.variant == v]
        m = R.money(X) if len(X) else dict(n=0)
        yr = {y: float(X[[d.year == y for d in X.day]].net.sum()) for y in YEARS}
        m["years_pos"] = sum(1 for y in YEARS if yr[y] > 0)
        m["by_year_total"] = yr
        out[v] = m
    return out


def main():
    des, hol, T = load()
    comb = combined(T)
    print("| strategy / variant | trades | win | avg win / avg loss | net per trade (2x slip) | total 1 lot | max drawdown "
          "| years + (2022-26) | p vs twins | q (30) | pts / trade, R |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for v, s in des["variants"].items():
        if not s.get("n"):
            print(f"| {v} {s['label']} | 0 | | | | | | | | | |")
            continue
        c = comb[v]
        print(f"| **{v}** {s['label']} | {s['n']:,} | {s['win']:.0%} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
              f"{rs(s['avg_net'])} ({rs(s['avg_net_x2'])}) | {lakh(s['total'])} | {rs(s['max_dd'])} | "
              f"{c['years_pos']} of 5 | {s['p_twins']:.3f} | {s['q']:.3f} | {s['avg_pts']:+.1f}, {s['avg_R']:+.2f}R |")
    print()
    if hol is not None:
        print("| variant | holdout trades | win | net per trade | total | p vs twins | design + holdout drawdown "
              "| worst month |")
        print("|---|---|---|---|---|---|---|---|")
        for v, s in hol["variants"].items():
            c = comb[v]
            if not s.get("n"):
                print(f"| {v} | 0 | | | | | | |")
                continue
            print(f"| {v} | {s['n']} | {s['win']:.0%} | {rs(s['avg_net'])} | {rs(s['total'])} | {s['p_twins']:.3f} | "
                  f"{rs(c['max_dd'])} | {rs(c['worst_month'])} ({c['worst_month_name']}) |")
    (R.RESULTS / "combined.json").write_text(json.dumps(comb, indent=1, default=str))


if __name__ == "__main__":
    main()
