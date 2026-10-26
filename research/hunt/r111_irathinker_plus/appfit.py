"""R111 app-fit check (26 Oct 2026, Boss: "do a plain test and find out if this works out for our application").

Descriptive, not a research gate: the strangle + stop (E1) and the condor + stop (the only form IraAlgo's account guard
can place, blockNakedShort) run week by week as IraAlgo's paper account would. It uses:
- the app's fills and charges (obuy 'app': +-5 bps, stops -10 bps; today's charges);
- Rs 1 lakh capital;
- the guard's default limits (AccountGuard.Limits: drawdown 10% of capital and of peak, 3 open positions);
- Boss's account stop rules (-Rs 13k a month: no new entry that month; -Rs 40k drawdown: stop).

It covers every week with real monthly prices (Nov 2024 - Feb 2026). The strangle's holdout is no longer clean (R111
PREREG note), so this is a check of fit, not a test of edge.

    python -I research/hunt/r111_irathinker_plus/appfit.py <release_dir> <bars_dir> <signals_dir> <out_dir>
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import r111study as E  # noqa: E402

R = E.R
from obuy.costs import Costs, Fills  # noqa: E402

CAPITAL = 100_000.0
GUARD_DD_PCT = 10.0             # AccountGuard.Limits.maxDrawdownPct default
GUARD_MAX_POSITIONS = 3         # AccountGuard.Limits.maxOpenPositions default
MONTH_STOP = -13_000.0          # CLAUDE.md account stop rule
TOTAL_STOP = -40_000.0
MARGIN_PCT = 0.025              # R103's short-leg margin estimate (per leg, of the strike's notional)


class AppFill(R.Fill):
    """IraAlgo's paper fills ('app': market +-5 bps, resting stops -10 bps)."""

    def __init__(self):
        self.f = Fills(mode="app")
        self.slip = 1.0


def margin(row: dict, lots: int, wings: bool) -> float:
    """Rough margin of one entry: naked - R103's estimate (2.5% of each short strike's notional); condor - the wing width
    x the lot (the defined maximum loss, before the credit)."""
    if wings:
        return E.WING * lots
    return MARGIN_PCT * (float(row["ce_strike"]) + float(row["pe_strike"])) * lots


def account(trades: pd.DataFrame, margins: dict, legs: int) -> dict:
    """Run the weeks in order through the guard defaults and Boss's stop rules."""
    eq, peak, month, m_pnl, halted = CAPITAL, CAPITAL, None, 0.0, None
    taken, refused, first = [], {}, None
    for t in trades.sort_values("day").to_dict("records"):
        mo = (t["day"].year, t["day"].month)
        if mo != month:
            month, m_pnl = mo, 0.0
        why = None
        if legs > GUARD_MAX_POSITIONS:
            why = f"guard: {legs} legs > {GUARD_MAX_POSITIONS} open positions"
        elif margins[t["day"]] > eq:
            why = "margin above equity"
        elif (CAPITAL - eq) / CAPITAL * 100 >= GUARD_DD_PCT or (peak - eq) / peak * 100 >= GUARD_DD_PCT:
            why = "guard: drawdown 10%"
        elif m_pnl <= MONTH_STOP:
            why = "Boss: -13k month stop"
        elif halted or eq - CAPITAL <= TOTAL_STOP or peak - eq >= -TOTAL_STOP:
            halted = halted or t["day"]
            why = "Boss: -40k drawdown stop"
        if why:
            refused[why] = refused.get(why, 0) + 1
            first = first or (str(t["day"]), why)
            continue
        eq += t["net"]
        m_pnl += t["net"]
        peak = max(peak, eq)
        taken.append(t)
    tk = pd.DataFrame(taken)
    return {"weeks_offered": int(len(trades)), "weeks_taken": int(len(tk)), "refused": refused,
            "net": round(float(tk["net"].sum()), 0) if len(tk) else 0.0, "end_equity": round(eq, 0),
            "max_drawdown": round(float(drawdown(tk)), 0) if len(tk) else 0.0,
            "first_refused": first}


def drawdown(tk: pd.DataFrame) -> float:
    e = CAPITAL + tk.sort_values("day")["net"].cumsum()
    return float((e - np.maximum.accumulate(np.r_[CAPITAL, e.values])[1:]).min())


def main(rel, bars, sig, out):
    os.makedirs(out, exist_ok=True)
    R.COSTS = Costs("app")                         # today's charges (STT 0.15% on sells), as the app's paper book
    mk = R.Market("BANKNIFTY", bars, rel)
    st = pd.read_csv(os.path.join(sig, "strangle_BANKNIFTY.csv"))
    res = {}
    for name, kw, legs in (("strangle_stop", {"stop": True}, 2), ("condor_stop", {"stop": True, "wings": True}, 4),
                           ("condor_stop_skip_expiry", {"stop": True, "wings": True, "skip_expiry_week": True}, 4)):
        t, _ = E.run_strangle(mk, st, AppFill(), **kw)
        t["day"] = pd.to_datetime(t["day"]).dt.date
        rows = {r["monday"]: r for r in st.to_dict("records")}
        mg = {d: margin(rows[d.isoformat()], R.lot("BANKNIFTY", d), kw.get("wings", False)) for d in t["day"]}
        res[name] = {
            "weeks": int(len(t)), "win": round(float((t["net"] > 0).mean()) * 100, 1),
            "mean": round(float(t["net"].mean()), 0), "total": round(float(t["net"].sum()), 0),
            "worst_week": round(float(t["net"].min()), 0),
            "worst_month": round(float(t.groupby(t["day"].map(lambda d: (d.year, d.month)))["net"].sum().min()), 0),
            "weeks_losing_over_10k": int((t["net"] <= -10_000).sum()),
            "margin_median": round(float(np.median(list(mg.values()))), 0),
            "app_default_guard": account(t, mg, legs),
            "guard_max_positions_raised_to_4": account(t, mg, min(legs, GUARD_MAX_POSITIONS)),
        }
        t.to_csv(os.path.join(out, f"appfit_{name}.csv"), index=False)
        print(name, json.dumps(res[name]), flush=True)
    json.dump(res, open(os.path.join(out, "appfit.json"), "w"), indent=1, default=str)


if __name__ == "__main__":
    main(*sys.argv[1:5])
