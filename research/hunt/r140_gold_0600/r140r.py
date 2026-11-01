"""R140 recent look (Boss, 31 Oct 2026): the high win-rate XAUUSD versions (E7, E8, L1-L3; all REJECTED in design,
Amendment B) replayed on the last 2 months, 1 Sep - 30 Oct 2026, sized for a $200 account (0.01 lot = 1 oz).

This is not a test and changes no verdict: these months sit inside the locked holdout, and reading them here marks
the holdout as SEEN for these rules (results/recent_look.lock). Same code, exits and costs as r140e (the model of E8
trained on the design period only). Information only: Boss cannot trade spot gold through Zerodha.

    python -m research.hunt.r140_gold_0600.r140r
"""
from __future__ import annotations

import json
from datetime import date, datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140e as E

PERIOD = (date(2026, 9, 1), date(2026, 10, 30))
RULES = ("E7", "E8", "L1", "L2", "L3")
LOT = 0.01                 # 1 oz
CAPITAL = 200.0


def scale(trades, lot=LOT):
    k = lot / 1.0          # the engine prices 1 standard lot (100 oz); 0.01 lot = 1/100
    return [dict(t, net=t["net"] * k, net2=t["net2"] * k, gross=t["gross"] * k) for t in trades]


def account(trades, capital=CAPITAL):
    net = np.array([t["net"] for t in trades], dtype=float)
    eq = capital + np.cumsum(net) if len(net) else np.array([capital])
    peak = np.maximum.accumulate(np.concatenate([[capital], eq]))
    dd = float(np.min(np.concatenate([[capital], eq]) - peak))
    return dict(trades=len(net), wins=int((net > 0).sum()), win=float((net > 0).mean()) if len(net) else 0.0,
                total=float(net.sum()), end=float(eq[-1]), ret=float(net.sum() / capital), max_dd=dd,
                avg_win=float(net[net > 0].mean()) if (net > 0).any() else 0.0,
                avg_loss=float(net[net <= 0].mean()) if (net <= 0).any() else 0.0,
                worst=float(net.min()) if len(net) else 0.0, best=float(net.max()) if len(net) else 0.0,
                total_2x=float(sum(t["net2"] for t in trades)))


def main():
    trades, _ = E.run(PERIOD, R.MCX_HOLD, (PERIOD,), (R.MCX_HOLD,), holdout=True)
    out = {}
    for r in RULES:
        ts = sorted(scale(trades[r]), key=lambda t: t["day"])
        a = account(ts)
        a["by_month"] = {m: float(sum(t["net"] for t in ts if f"{t['day'].year}-{t['day'].month:02d}" == m))
                         for m in ("2026-09", "2026-10")}
        a["exits"] = {k: sum(t["why"] == k for t in ts) for k in ("target", "lock", "stop", "time")}
        out[r] = a
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    (R.RESULTS / "recent_look.lock").write_text(
        f"read {datetime.now().isoformat(timespec='seconds')}: 1 Sep - 30 Oct 2026 replayed for {list(RULES)} at Boss's "
        "request (rules already rejected in design); the holdout is SEEN for these rules\n")
    (R.RESULTS / "recent_look.json").write_text(json.dumps(R._clean(out), indent=1))
    for r, a in out.items():
        print(r, json.dumps(R._clean(a)))


if __name__ == "__main__":
    main()
