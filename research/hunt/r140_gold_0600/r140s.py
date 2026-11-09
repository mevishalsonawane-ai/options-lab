"""R140 Amendment M (PREREG.md, commit a052a4a): Boss's Pine v5 "XAUUSD 6:30 AM IST Breakout (2R)" ported as written
onto XAUUSD (information only; not tradable via Zerodha). Signal source: XAUUSD spot candles.

The range is the chart candle that opens at 06:30 IST; the first close beyond it (the script's cross rule) is the
trade, one a day; SL = the previous candle's low / high, TP = 2R; closed at the next 06:30 IST if neither is hit.

    python -m research.hunt.r140_gold_0600.r140s design
    python -m research.hunt.r140_gold_0600.r140s holdout     # 1 Apr - 30 Oct 2026, one read, passing rules only
"""
from __future__ import annotations

import json
import sys
from datetime import datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140g as G
from research.hunt.r140_gold_0600 import r140p as P

IST = pd.Timedelta(hours=5, minutes=30)
RANGE_H, RANGE_M = 6, 30
RULES = {"S1": (60, 2.0, False, 1.0), "S2": (60, 2.0, True, 1.0), "S3": (15, 2.0, False, 1.0),
         "S4": (15, 2.0, True, 1.0), "S5": (60, 1.0, True, 0.5)}          # (chart, R, lock, lock after xR)
MIN_TRADES = 100
N_TWIN_DAYS = 40


def breakout_signals(b: pd.DataFrame, minutes: int, rr: float = 2.0) -> list[dict]:
    """The script, candle by candle. b is indexed by candle OPEN time (UTC); Pine's `time` is the open."""
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    ist = b.index + IST
    is_rng = (ist.hour == RANGE_H) & (ist.minute == RANGE_M)
    dom = ist.day
    rh = rl = np.nan
    active_day = None
    taken = False
    out = []
    for i in range(len(b)):
        if is_rng[i] and (active_day is None or active_day != dom[i]):
            active_day, rh, rl, taken = dom[i], h[i], l[i], False
        if is_rng[i] and active_day == dom[i]:
            rh, rl = max(rh, h[i]), min(rl, l[i])
        can = (not is_rng[i]) and np.isfinite(rh) and np.isfinite(rl) and not taken
        if not can or i == 0:
            continue
        long_ = c[i] > rh and (o[i] <= rh or c[i - 1] <= rh)
        short = c[i] < rl and (o[i] >= rl or c[i - 1] >= rl)
        t_close = b.index[i] + pd.Timedelta(minutes=minutes)
        if long_:
            taken = True
            risk = c[i] - l[i - 1]
            if risk > 0:
                out.append(dict(t=t_close, side=1, sl=float(l[i - 1]), tp=float(c[i] + rr * risk), close=float(c[i])))
        elif short:
            taken = True
            risk = h[i - 1] - c[i]
            if risk > 0:
                out.append(dict(t=t_close, side=-1, sl=float(h[i - 1]), tp=float(c[i] - rr * risk), close=float(c[i])))
    return out


def next_range_open(t_utc: pd.Timestamp) -> pd.Timestamp:
    """The next 06:30 IST (01:00 UTC) strictly after t: the script's day boundary, used as the time exit."""
    d = t_utc.normalize() + pd.Timedelta(hours=1)
    return d if d > t_utc else d + pd.Timedelta(days=1)


def run(X, sigs, lock, lock_r, period):
    out, busy_until = [], None
    for s in sigs:
        d = P.ist_date(s["t"])
        if not R.in_p(d, period) or (busy_until is not None and s["t"] < busy_until):
            continue
        i_in = X.ix_at_or_after(s["t"])
        if i_in >= len(X.ts) or X.ts[i_in] - np.datetime64(s["t"]) > np.timedelta64(5, "m"):
            continue
        i_cap = X.ix_at_or_after(next_range_open(s["t"]))
        if i_cap >= len(X.ts) or i_cap <= i_in:
            continue
        r = P.sim_fixed(X, i_in, i_cap, s["side"], s["sl"], s["tp"], lock, lock_r)
        if r is None:
            continue
        px, why, j = r
        e = X.o[i_in]
        g = s["side"] * (px - e) * R.XAU_LOT_OZ
        out.append(dict(day=d, t_in=s["t"], side=s["side"], gross=g, net=g - R.XAU_COST, net2=g - 2 * R.XAU_COST,
                        why=why, entry=float(e), stop_usd=float(abs(e - s["sl"])), tp_usd=float(abs(s["tp"] - e))))
        busy_until = pd.Timestamp(X.ts[j]) + pd.Timedelta(minutes=1)
    return out


def twins(X, trades, period, lock, lock_r, rng, n_sets=R.N_TWINS):
    if not trades:
        return 1.0, 0.0
    days = pd.date_range(period[0], period[1], freq="B")
    L = np.full((len(trades), N_TWIN_DAYS), np.nan)
    S = np.full((len(trades), N_TWIN_DAYS), np.nan)
    for a, t in enumerate(trades):
        clock = t["t_in"] - t["t_in"].normalize()
        k = 0
        for p in rng.choice(len(days), size=N_TWIN_DAYS * 2, replace=False):
            if k >= N_TWIN_DAYS:
                break
            tt = days[p] + clock
            i_in = X.ix_at_or_after(tt)
            if i_in >= len(X.ts) or X.ts[i_in] - np.datetime64(tt) > np.timedelta64(5, "m"):
                continue
            i_cap = X.ix_at_or_after(next_range_open(tt))
            if i_cap >= len(X.ts) or i_cap <= i_in:
                continue
            e = X.o[i_in]
            vals = []
            for side in (1, -1):
                r = P.sim_fixed(X, i_in, i_cap, side, e - side * t["stop_usd"], e + side * t["tp_usd"], lock, lock_r)
                vals.append(np.nan if r is None else side * (r[0] - e) * R.XAU_LOT_OZ - R.XAU_COST)
            if np.isfinite(vals).all():
                L[a, k], S[a, k] = vals
                k += 1
    share = float(np.mean([t["side"] > 0 for t in trades]))
    total = float(sum(t["net"] for t in trades))
    n = len(trades)
    tot = np.empty(n_sets)
    for s in range(n_sets):
        col = rng.integers(0, N_TWIN_DAYS, size=n)
        lg = rng.random(n) < share
        tot[s] = np.nansum(np.where(lg, L[np.arange(n), col], S[np.arange(n), col]))
    return (1 + int((tot >= total).sum())) / (n_sets + 1), float(np.median(tot))


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X = R.Xau(R.load_xau())
    bars = {m: P.bars(X.df, m) for m in (60, 15)}
    sig = {k: breakout_signals(bars[m], m, rr) for k, (m, rr, _, _) in RULES.items()}
    if mode == "design":
        rng = np.random.default_rng(R.SEED + 13)
        prior = {}
        for fn in ("design.json", "amendA_design.json", "amendE_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text())["variants"].items()})
        for fn in ("amendB_design.json", "amendD_design.json", "amendF_design.json", "amendG_design.json",
                   "amendH_design.json", "amendI_design.json", "amendJ_design.json", "amendK_design.json"):
            prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / fn).read_text()).items()})
        prior.update({"EN" + k[1:]: x["twin_p"] for k, x in json.loads((R.RESULTS / "amendL_design.json").read_text()).items()})
        res, p = {}, {}
        for k, (m, rr, lock, lr) in RULES.items():
            ts = run(X, sig[k], lock, lr, R.XAU_DESIGN)
            s = R.summarise(k, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = twins(X, ts, R.XAU_DESIGN, lock, lr, rng)
            s["sizing_1000"] = G.report(ts) if ts else {}
            s["exits"] = {w: sum(t["why"] == w for t in ts) for w in ("tp", "sl", "lock", "time")}
            s["long_net"] = float(sum(t["net"] for t in ts if t["side"] > 0))
            s["short_net"] = float(sum(t["net"] for t in ts if t["side"] < 0))
            s["avg_stop_usd"] = float(np.mean([t["stop_usd"] for t in ts])) if ts else 0.0
            s["entry_hours_ist"] = pd.Series([(t["t_in"] + IST).hour for t in ts]).value_counts().sort_index().to_dict() if ts else {}
            p[k] = s["twin_p"]
            res[k] = s
        q = R.bh({**prior, **p})                                 # BH over 136
        for k, s in res.items():
            s["q"] = q[k]
            g = R.gates(s, s["twin_p"], q[k], "A4")
            g["trades"] = s["n"] >= MIN_TRADES
            g["all"] = all(v for kk, v in g.items() if kk != "all")
            s["gates"] = g
        (R.RESULTS / "amendM_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendM_design.md").write_text("# R140 Amendment M design results (Boss's 6:30 AM IST breakout)\n\n"
                                                    + R.table_md(res) + "\n")
        print(R.table_md(res))
        for k, s in res.items():
            print(k, s["exits"], round(s["avg_stop_usd"], 2), f"long ${s['long_net']:+,.0f} short ${s['short_net']:+,.0f}",
                  json.dumps(R._clean(s["sizing_1000"].get("5x", {}))))
    elif mode == "holdout":
        lock_f = R.RESULTS / "amendM_holdout.lock"
        if lock_f.exists():
            raise SystemExit("Amendment M holdout already read: " + lock_f.read_text())
        dz = json.loads((R.RESULTS / "amendM_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock_f.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment M rule passed the design gates; the holdout is not read")
            return
        res = {}
        for k in passing:
            m, rr, lock, lr = RULES[k]
            ts = run(X, sig[k], lock, lr, P.HOLD)
            res[k] = R.summarise(k, ts, (P.HOLD,), False) | {"sizing_1000": G.report(ts)}
        (R.RESULTS / "amendM_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
