"""R140 Amendment D (PREREG.md, commit 54d9619): bigger profit per trade on US gold (hold winners longer, busy days,
a looser vote) and sizing for a $1,000 account. XAUUSD only; information only (not tradable via Zerodha).

Signal source: XAUUSD spot 15-minute candles completed by 06:00 IST (the underlying). Every rule has a stop-loss and
a profit lock.

    python -m research.hunt.r140_gold_0600.r140g design
    python -m research.hunt.r140_gold_0600.r140g holdout     # 1 Apr - 31 Aug 2026, one read, passing rules only
    python -m research.hunt.r140_gold_0600.r140g recent      # 1 Sep - 30 Oct 2026 (labelled already seen)
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140e as E

RULES = ("G1", "G2", "G3", "G4", "G5", "G6")
HOLD = (date(2026, 4, 1), date(2026, 8, 31))
RECENT = (date(2026, 9, 1), date(2026, 10, 30))
# (vote, stop ATR, lock trigger ATR, keep, time exit in UTC minutes, busy-day filter)
IST_0800, IST_1230, IST_1730 = R.W_END_UTC, 7 * 60, 12 * 60
CFG = {"G1": ("4", 0.5, 0.5, 0.60, IST_1230, False), "G2": ("4", 0.5, 1.0, 0.60, IST_1230, False),
       "G3": ("4", 0.5, 0.5, 0.50, IST_1730, False), "G4": ("4", 0.5, 0.5, 0.60, IST_0800, True),
       "G5": ("3", 0.5, 0.5, 0.60, IST_0800, False), "G6": ("3", 0.5, 0.5, 0.60, IST_1230, False)}
MIN_TRADES = {"G1": 50, "G2": 50, "G3": 50, "G4": 50, "G5": 100, "G6": 100}
CAPITAL, CAP_LEV = 1000.0, 20.0


def votes(f: pd.DataFrame) -> pd.DataFrame:
    """vote 4/4 (= E5) and vote 3/4 (at least 3 of the same 4 signs agree; the majority side)."""
    v = np.vstack([np.sign(f.ema_spread), np.sign(f.rsi - 50), np.sign(f.macd_hist), np.sign(f.C3)]).T
    v = np.nan_to_num(v)
    up, dn = (v == 1).sum(axis=1), (v == -1).sum(axis=1)
    return pd.DataFrame({"4": np.where(up == 4, 1, np.where(dn == 4, -1, 0)),
                         "3": np.where(up >= 3, 1, np.where(dn >= 3, -1, 0))}, index=f.index)


def trade(X, b15, d, side, stop, trig, keep, t_out_min):
    """One trade: in at the 06:00 IST minute's open, out at t_out (UTC minutes of the day) or by the stop / lock."""
    t_in = pd.Timestamp(d) + pd.Timedelta(minutes=R.W_START_UTC)
    t_out = pd.Timestamp(d) + pd.Timedelta(minutes=t_out_min)
    i_in, i_out = X.ix_at_or_after(t_in), X.ix_at_or_after(t_out)
    if i_out >= len(X.ts) or i_out <= i_in or X.ts[i_in] - np.datetime64(t_in) > np.timedelta64(5, "m"):
        return None
    if X.ts[i_out] - np.datetime64(t_out) > np.timedelta64(30, "m"):
        return None                                        # no price near the time exit (e.g. Friday close)
    atr = R.atr15_before(b15, t_in)
    if not np.isfinite(atr) or atr <= 0:
        return None
    px, why = E.sim_xau_t(X, i_in, i_out, side, stop * atr, trig * atr, keep, None)
    e = X.o[i_in]
    g = side * (px - e) * R.XAU_LOT_OZ
    return dict(day=d, t_in=t_in, side=side, gross=g, net=g - R.XAU_COST, net2=g - 2 * R.XAU_COST, why=why,
                entry=float(e), stop_usd=float(stop * atr), atr=float(atr))


def busy_flags(X, b15, days) -> pd.Series:
    """True when ATR15 at 06:00 is >= the median ATR15 of all EARLIER window days."""
    a = pd.Series({d: R.atr15_before(b15, pd.Timestamp(d) + pd.Timedelta(minutes=R.W_START_UTC)) for d in days})
    med = a.expanding().median().shift(1)
    return (a >= med) & med.notna()


def run(period):
    X, b15, wt = R._load()
    pr, f = E.build(X, b15, wt)
    vt = votes(f)
    busy = busy_flags(X, b15, list(f.index))
    out = {k: [] for k in RULES}
    for d in f.index:
        if not R.in_p(d, period):
            continue
        for r, (vote, st, tg, kp, tout, need_busy) in CFG.items():
            s = int(vt.at[d, vote])
            if s == 0 or (need_busy and not busy.get(d, False)):
                continue
            t = trade(X, b15, d, s, st, tg, kp, tout)
            if t:
                out[r].append(t)
    return out, (X, b15, wt, [d for d in f.index if R.in_p(d, period)])


# ---------------------------------------------------------------------------------------------- sizing
def size_oz(mode: str, bal: float, entry: float, stop_usd: float) -> float:
    if mode == "5x":
        return 5.0 * bal / entry
    risk = {"risk1": 0.01, "risk2": 0.02}[mode]
    return min(risk * bal / stop_usd, CAP_LEV * bal / entry)


def sized(trades, mode, capital=CAPITAL, cost_mult=1.0):
    bal, peak, dd_pct, months, rows = capital, capital, 0.0, {}, []
    for t in sorted(trades, key=lambda t: t["day"]):
        oz = size_oz(mode, bal, t["entry"], t["stop_usd"])
        per_oz = (t["net"] if cost_mult == 1.0 else t["net2"]) / R.XAU_LOT_OZ
        pnl = oz * per_oz
        bal += pnl
        peak = max(peak, bal)
        dd_pct = min(dd_pct, bal / peak - 1)
        k = f"{t['day'].year}-{t['day'].month:02d}"
        months[k] = months.get(k, 0.0) + pnl
        rows.append(pnl)
    rows = np.array(rows)
    return dict(end=bal, ret=bal / capital - 1, max_dd_pct=dd_pct, worst_month=min(months.values()) if months else 0.0,
                months_green=f"{sum(v > 0 for v in months.values())}/{len(months)}",
                avg_win=float(rows[rows > 0].mean()) if (rows > 0).any() else 0.0,
                avg_loss=float(rows[rows <= 0].mean()) if (rows <= 0).any() else 0.0)


def report(trades):
    return {m: sized(trades, m) for m in ("5x", "risk1", "risk2")} | {"risk2_2x_costs": sized(trades, "risk2", cost_mult=2.0)}


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    if mode == "design":
        tr, (X, b15, wt, days) = run(R.XAU_DESIGN)
        rng = np.random.default_rng(R.SEED + 4)
        prior = {}
        for fn in ("design.json", "amendA_design.json"):
            v = json.loads((R.RESULTS / fn).read_text())["variants"]
            prior.update({k: x["twin_p"] for k, x in v.items()})
        prior.update({k: x["twin_p"] for k, x in json.loads((R.RESULTS / "amendB_design.json").read_text()).items()})
        res, p = {}, {}
        for r, ts in tr.items():
            s = R.summarise(r, ts, R.XAU_FOLDS, False)
            vote, st, tg, kp, tout, _ = CFG[r]
            s["twin_p"], s["twin_median_total"] = E.twins(ts, days, lambda d, side: trade(X, b15, d, side, st, tg, kp, tout), rng)
            s["sizing_1000"] = report(ts)
            p[r] = s["twin_p"]
            res[r] = s
        q = R.bh({**prior, **p})                                 # BH over 42
        for r, s in res.items():
            s["q"] = q[r]
            g = R.gates(s, s["twin_p"], q[r], "A4")              # >= 4 of 5 folds positive
            g["trades"] = s["n"] >= MIN_TRADES[r]
            g["all"] = all(v for k, v in g.items() if k != "all")
            s["gates"] = g
        (R.RESULTS / "amendD_design.json").write_text(json.dumps(R._clean(res), indent=1))
        (R.RESULTS / "amendD_design.md").write_text("# R140 Amendment D design results\n\n" + R.table_md(res) + "\n")
        print(R.table_md(res))
        for r, s in res.items():
            print(r, json.dumps(R._clean(s["sizing_1000"])))
    elif mode == "holdout":
        lock = R.RESULTS / "amendD_holdout.lock"
        if lock.exists():
            raise SystemExit("Amendment D holdout already read: " + lock.read_text())
        dz = json.loads((R.RESULTS / "amendD_design.json").read_text())
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment D rule passed the design gates; the holdout is not read")
            return
        tr, _ = run(HOLD)
        res = {k: R.summarise(k, tr[k], (HOLD,), False) | {"sizing_1000": report(tr[k])} for k in passing}
        (R.RESULTS / "amendD_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(json.dumps(R._clean(res), indent=1))
    elif mode == "recent":
        tr, _ = run(RECENT)
        res = {k: R.summarise(k, tr[k], (RECENT,), False) | {"sizing_1000": report(tr[k])} for k in RULES}
        (R.RESULTS / "amendD_recent_seen.json").write_text(json.dumps(R._clean(res), indent=1))
        for k, s in res.items():
            print(k, s["n"], f"win {s['win']:.0%}", f"$/lot {s['total']:+,.0f}", json.dumps(R._clean(s["sizing_1000"])))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
