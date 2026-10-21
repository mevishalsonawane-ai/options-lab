"""R85 Part 2: the 4 frozen option-buying rules of PREREG amendment A1 (19 Oct 2026).

    python3 -m research.hunt.r85.part2 design     # all variants on design, walk-forward, design twins, frozen pick
    python3 -m research.hunt.r85.part2 final      # ONE read of the final for all 4 rules (writes results/FINAL_READ)

Signal source: the MCX future (lake `spot`, close-only candles; research/hunt/r85/candles.py). Trade: BUY 1 lot of the
1-ITM call / put (strategy.sim_buy). The futures move is a diagnostic only (Boss, 19 Oct: options buying only).
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r85 import anatomy as A
from research.hunt.r85 import strategy as S

VARIANTS = {"X10_L10": (0.10, 0.10), "X10_L05": (0.10, 0.05), "X05_L10": (0.05, 0.10), "X05_L05": (0.05, 0.05)}
PRIMARY = "X10_L10"

# rule -> (signal instrument, timeframe minutes, mask function on featured candles, side, hold candles, bucket)
RULES = {
    "R1": dict(inst="SILVER", tf=5, bucket="LATE", side=+1, desc="SILVER 5m big red body + option activity >= 2x, "
               "LATE -> SILVERM call", mask=lambda c: (c.colour == -1) & (c.body_atr >= 1.0) & (c.act_ratio >= 2.0)),
    "R2": dict(inst="GOLDM", tf=5, bucket="LULL", side=+1, desc="GOLDM 5m red candle, high-volatility regime, LULL "
               "-> GOLDM call", mask=lambda c: (c.colour == -1) & (c.vol_reg > 1.3)),
    "R3": dict(inst="SILVERM", tf=15, bucket="PREUS", side=-1, desc="SILVERM 15m red outside bar, PRE-US -> SILVERM "
               "put", mask=lambda c: (c.colour == -1) & c.s_outside.astype(bool)),
    "R4": dict(inst="SILVERM", tf=5, bucket="USOPEN", side=+1, desc="SILVERM 5m red hammer, US-OPEN -> SILVERM call",
               mask=lambda c: (c.colour == -1) & c.s_hammer.astype(bool)),
}
HOLD_CANDLES = 3


def signals(rule: str, upto: date | None = None) -> list[dict]:
    """Every signal of a rule (all dates up to `upto`): j = grid minute of the candle's last minute in the signal
    instrument's Market, side, hold minutes, stopF (candle low for a call, high for a put)."""
    r = RULES[rule]
    cd = A.instrument_candles(r["inst"], r["tf"], upto=upto)
    with np.errstate(invalid="ignore"):
        m = r["mask"](cd).fillna(False).values.astype(bool) & (cd.bucket.values == r["bucket"])
    out = []
    for x in cd[m].itertuples():
        out.append(dict(j=int(x.gi), side=r["side"], hold=HOLD_CANDLES * r["tf"],
                        stopF=float(x.l if r["side"] > 0 else x.h), day=x.day))
    return out


def markets(rule: str):
    A.lake_ready()
    from research.hunt.r53.mcx import data as D
    r = RULES[rule]
    book, units = S.OPTION_BOOK[r["inst"]]
    fm = D.load_market(r["inst"])
    om = fm if book == r["inst"] else D.load_market(book)
    return fm, om, book, units, S.FUT_UNITS[r["inst"]]


def run_trades(rule: str, sigs: list[dict], X: float, L: float, mk=None) -> tuple[list[dict], list[dict]]:
    """Trades of one rule and variant, one open trade at a time. Returns (trades, the signals that traded)."""
    fm, om, book, units, fu = mk or markets(rule)
    trades, used = [], []
    busy_until = None
    for s in sigs:
        ts = pd.Timestamp(fm.ts[s["j"]])
        if busy_until is not None and ts < busy_until:
            continue
        t = S.sim_buy(om, fm, s["j"], s["side"], s["hold"], X, L, s["stopF"], book, units, fu)
        if t is None:
            continue
        t["rule"] = rule
        trades.append(t)
        used.append(s)
        busy_until = t["exit_ts"]
    return trades, used


def design():
    out = {}
    for rule in RULES:
        mk = markets(rule)
        sigs = [s for s in signals(rule, upto=A.DESIGN[1]) if A.DESIGN[0] <= s["day"] <= A.DESIGN[1]]
        vt, vs = {}, {}
        for v, (X, L) in VARIANTS.items():
            vt[v], vs[v] = run_trades(rule, sigs, X, L, mk)
        wf = S.walk_forward(vt, PRIMARY)
        fz = wf["frozen"]
        X, L = VARIANTS[fz]
        tw = S.twins(mk[1], mk[0], vs[fz], X, L, A.DESIGN, mk[2], mk[3], mk[4])
        net = sum(t["net"] for t in vt[fz])
        out[rule] = {"desc": RULES[rule]["desc"], "signals": len(sigs), "walk_forward": wf, "frozen": fz,
                     "design": {v: S.summary(tr) for v, tr in vt.items()},
                     "design_twin_p": S.twin_p(net, tw), "design_twin_mean": float(np.mean(tw))}
        print(rule, fz, len(vt[fz]), round(net), "wf", round(wf["wf_net"]), "p", out[rule]["design_twin_p"], flush=True)
    S.save("part2_design.json", out)
    return out


def final():
    lock = S.RESULTS / "FINAL_READ"
    if lock.exists():
        raise SystemExit(f"the final was already read ({lock.read_text().strip()}); a second read is not allowed")
    dz = json.loads((S.RESULTS / "part2_design.json").read_text())
    lock.write_text(f"read {pd.Timestamp.now(tz='UTC').isoformat()} for R1-R4\n")
    res, pv = {}, {}
    all_trades = {}
    for rule in RULES:
        mk = markets(rule)
        fz = dz[rule]["frozen"]
        X, L = VARIANTS[fz]
        sigs = [s for s in signals(rule) if s["day"] >= S.FINAL[0]]
        tr, used = run_trades(rule, sigs, X, L, mk)
        last = max((pd.Timestamp(d).date() for d in mk[1].days), default=S.FINAL[1])
        period = (S.FINAL[0], last)
        tw = S.twins(mk[1], mk[0], used, X, L, period, mk[2], mk[3], mk[4])
        net = sum(t["net"] for t in tr)
        p = S.twin_p(net, tw)
        pv[rule] = p
        sm = S.summary(tr)
        res[rule] = {"desc": RULES[rule]["desc"], "frozen": fz, "period": [str(x) for x in period], "final": sm,
                     "final_twin_p": p, "final_twin_mean": float(np.mean(tw)), "wf_net": dz[rule]["walk_forward"]["wf_net"],
                     "signals": len(sigs)}
        all_trades[rule] = tr
        print(rule, len(tr), round(net), "p", p, flush=True)
    q = A.bh(np.array([pv[r] for r in RULES]))
    for k, rule in enumerate(RULES):
        r = res[rule]
        sm = r["final"]
        g = {"wf_pos": r["wf_net"] > 0, "final_pos": sm["net"] > 0, "twin_p": r["final_twin_p"] < 0.10,
             "bh_q": bool(q[k] < 0.10), "top5": sm.get("top5_removed", float("nan")) > 0, "trades30": sm["trades"] >= 30}
        r.update(bh_q=float(q[k]), gates=g, verdict="PASS" if all(g.values()) else "fail")
    S.save("part2_final.json", res)
    rows = [dict(t, entry_ts=str(t["entry_ts"]), exit_ts=str(t["exit_ts"])) for tr in all_trades.values() for t in tr]
    pd.DataFrame(rows).to_csv(S.RESULTS / "part2_final_trades.csv", index=False, float_format="%.4g")
    return res


if __name__ == "__main__":
    {"design": design, "final": final}[sys.argv[1]]()
