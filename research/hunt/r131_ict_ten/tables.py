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


def _read(name):
    p = R.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def load():
    """The 30 and (when run) the 9 wick-only variants together; BH q over all of them (PREREG N4)."""
    des, hol = _read("design.json"), _read("holdout.json")
    dw, hw = _read("design_w.json"), _read("holdout_w.json")
    if dw:
        des["variants"].update(dw["variants"])
    if hol is not None and hw:
        hol["variants"].update(hw["variants"])
    ps = [des["variants"][v].get("p_twins", 1.0) for v in des["variants"]]
    for v, q in zip(des["variants"], R.R3.bh(ps)):
        des["variants"][v]["q_all"] = q
    parts = ["trades_design.parquet", "trades_design_w.parquet"]
    if hol is not None:
        parts += ["trades_holdout.parquet", "trades_holdout_w.parquet"]
    T = pd.concat([pd.read_parquet(R.OUT / f) for f in parts if (R.OUT / f).exists()], ignore_index=True)
    T["day"] = pd.to_datetime(T.day).dt.date
    return des, hol, T


def combined(T: pd.DataFrame) -> dict:
    out = {}
    for v in R.ALL_VARIANTS:
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
          "| years + (2022-26) | p vs twins | q (all) | pts / trade, R |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for v, s in des["variants"].items():
        if not s.get("n"):
            print(f"| {v} {s['label']} | 0 | | | | | | | | | |")
            continue
        c = comb[v]
        print(f"| **{v}** {s['label']} | {s['n']:,} | {s['win']:.0%} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
              f"{rs(s['avg_net'])} ({rs(s['avg_net_x2'])}) | {lakh(s['total'])} | {rs(s['max_dd'])} | "
              f"{c['years_pos']} of 5 | {s['p_twins']:.3f} | {s['q_all']:.3f} | {s['avg_pts']:+.1f}, {s['avg_R']:+.2f}R |")
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
