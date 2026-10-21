"""Cost check (Boss, 21 Oct 2026: "Commodities doesn't cost this much, India commodities cost less than NIFTY").

    python -m research.hunt.r98_catch100.costcheck        # writes results/cost_check.json

1. Zerodha's published rates (zerodha.com/charges, read 21 Oct 2026), as R53's mcx_opt_charges / mcx_fut_charges use.
2. R97 / R98 trades re-priced with charges only (no spread): an optimistic bound, since a market order pays the spread.
3. The GOLDM option spread measured from real prints (Roll 1984: full spread = 2 sqrt(-cov(dp_t, dp_t-1)) on
   consecutive printed minutes of the same contract), design window, by clock and moneyness.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C53
from research.hunt.r97_gold_reverse import cfg as K97
from research.hunt.r97_gold_reverse import data as D97
from research.hunt.r98_catch100 import cfg as K

RATES = {
    "source": "https://zerodha.com/charges (read 21 Oct 2026)",
    "commodity_futures": "CTT 0.01% sell (non-agri); MCX txn 0.0021%; brokerage 0.03% or Rs 20 an order; stamp 0.002% buy",
    "commodity_options": "CTT 0.05% sell premium; MCX txn 0.0418%; Rs 20 an order; stamp 0.003% buy",
    "index_futures": "STT 0.05% sell; NSE txn 0.00183%; 0.03% or Rs 20 an order; stamp 0.002% buy",
    "index_options": "STT 0.15% sell premium; NSE txn 0.03553%; Rs 20 an order; stamp 0.003% buy",
    "all": "GST 18% on brokerage + SEBI + txn; SEBI Rs 10 a crore",
}


def _first(x):
    return float(str(x).split(",")[0])


def reprice(path, opt_rules) -> dict:
    t = pd.read_csv(path)
    out = {}
    for k, g in t.groupby("rule"):
        f, x = g.fill.map(_first), g.exit.map(_first)
        fn = C53.mcx_opt_charges if (k in opt_rules) else C53.mcx_fut_charges
        ch = np.array([fn(a * 10, b * 10) for a, b in zip(f, x)])
        out[k] = dict(trades=int(len(g)), gross=float(g.gross.sum()), charges=float(ch.sum()),
                      charges_per_trade=float(ch.mean()), net_charges_only=float(g.gross.sum() - ch.sum()),
                      net_as_tested=float(g.net.sum()))
    return out


def roll_spread() -> dict:
    m = D97.load_book("GOLDM").m
    rows = []
    for (day, cp, k), (ii, V) in m.legs.items():
        if not D97.in_period(day, K97.DESIGN):
            continue
        c, cons = V[:, 3], np.diff(ii) == 1
        dp = np.diff(c)
        for j in range(1, len(dp)):
            if cons[j] and cons[j - 1]:
                rows.append((m.tod[ii[j + 1]] >= 1020, dp[j], dp[j - 1], c[j + 1], abs(m.F[ii[j + 1]] - k) <= 150))
    x = pd.DataFrame(rows, columns=["eve", "d1", "d0", "p", "atm"])
    out = {}
    for (eve, atm), g in x.groupby(["eve", "atm"]):
        cov = float(np.cov(g.d1, g.d0)[0, 1])
        s = 2 * np.sqrt(-cov) if cov < 0 else float("nan")
        p = float(g.p.median())
        out[f"{'from' if eve else 'before'}_17_{'near_atm' if atm else 'other'}"] = dict(
            pairs=int(len(g)), median_premium=p, full_spread_pts=s, full_spread_pct=100 * s / p)
    return out


def main():
    res = dict(rates=RATES,
               r97=reprice(K97.RESULTS / "trades_design.csv", set("R97" + c for c in "ABCDEFG")),
               r98=reprice(K.RESULTS / "trades_design.csv", {"R98E"}),
               goldm_option_spread_roll=roll_spread())
    (K.RESULTS / "cost_check.json").write_text(json.dumps(res, indent=1))
    return res


if __name__ == "__main__":
    print(json.dumps(main()["goldm_option_spread_roll"], indent=1))
