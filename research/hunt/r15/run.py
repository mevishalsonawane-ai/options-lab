"""R15 (PREREG.md): daily pre-open filters on Liquidity 15+5 BANKNIFTY, real costs.

    python3 research/hunt/r15/run.py trades <scratch>   # base trades from the two release files (no split by feature)
    python3 research/hunt/r15/run.py design <scratch>
    python3 research/hunt/r15/run.py hold <scratch>     # ONCE: writes HOLDOUT_RUN.flag, refuses a second run
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, RES)
sys.path.insert(0, os.path.dirname(RES))

HOLD = pd.Timestamp("2025-10-01").date()
SPLIT = pd.Timestamp("2025-01-01").date()
HS, STOPX, LOT = 0.0016, 0.0005, 30
FILES = ["banknifty_prev_year_wide.parquet", "banknifty_year.parquet"]
SKIPS = ["F1_QUIET", "F2_LOWVIX", "F3_PCR_EXT", "F4_FUTOI_UP"]
B = 5000


def charges(entry, exit_, qty):
    from obuy.costs import Costs
    c = Costs()
    return c.charge(True, entry, qty) + c.charge(False, exit_, qty)


def build(scr):
    import liquidity_break as LB
    from sell_levels import load
    from indicator.liquidity import pool_zones, swing_zones
    LB.LAST_M = 285                       # entries 09:20 .. 14:00, the app's window
    out = []
    seen = set()
    for f in FILES:
        days = load(os.path.join(scr, "..", "data", f))
        days = [d for d in days if d["day"] not in seen]
        seen |= {d["day"] for d in days}
        for tf in (15, 5):
            b = LB.bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            t = LB.simulate(days, b, zones, "both", True, prem_stop=0.15, ix_buffer=30, time_stop=(20, 0.05))
            t["book"] = f"{tf}m"
            out.append(t)
        print(f, len(days), "sessions", days[0]["day"], days[-1]["day"], flush=True)
        pd.Series(sorted(d["day"] for d in days)).to_csv(os.path.join(scr, f"sessions_{f}.csv"), index=False)
    t = pd.concat(out, ignore_index=True)
    t = t[t.key.notna()].copy()
    t["entry_raw"] = t.px - LB.SLIP
    t["exit_raw"] = (t.rs + LB.CHG) / LB.LOT + LB.SLIP + t.px
    t = t.drop(columns=["key"])
    t.to_parquet(os.path.join(scr, "trades.parquet"))
    print(len(t), "trades", flush=True)


def costed(t, mult=1.0):
    hs = HS * mult
    stop = t.why.isin(["premium stop", "index stop", "time stop", "failed break"]).values
    e = t.entry_raw.values * (1 + hs)
    x = t.exit_raw.values * (1 - hs - np.where(stop, STOPX, 0.0))
    ch = np.array([charges(a, b, LOT) for a, b in zip(e, x)])
    return (x - e) * LOT - ch


def sessions(scr):
    s = pd.concat([pd.read_csv(os.path.join(scr, f"sessions_{f}.csv")).iloc[:, 0] for f in FILES])
    return sorted(set(pd.to_datetime(s).dt.date))


def load_all(scr):
    t = pd.read_parquet(os.path.join(scr, "trades.parquet"))
    t["day"] = pd.to_datetime(t.day).dt.date
    t["net"] = costed(t)
    t["net15"] = costed(t, 1.5)
    D = pd.read_csv(os.path.join(scr, "days.csv"))
    D["day"] = pd.to_datetime(D.day).dt.date
    t = t.merge(D, on="day", how="left")
    return t, D, sessions(scr)


def daily(t, days):
    s = t.groupby("day").net.sum()
    return pd.Series([s.get(d, 0.0) for d in days], index=days)


def stats(t, days):
    x = daily(t, days)
    eq = x.cumsum()
    dd = (eq - eq.cummax()).min()
    mo = x.groupby([str(d)[:7] for d in x.index]).sum()
    tt = x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))
    return dict(trades=len(t), sessions=len(days), rs_day=x.mean(), t=tt, net=x.sum(), maxdd=dd, green=f"{(mo > 0).sum()}/{len(mo)}",
                win=(t.net > 0).mean() if len(t) else np.nan, per_trade=t.net.mean() if len(t) else np.nan)


def bh(p):
    p = np.asarray(p)
    n = len(p)
    o = np.argsort(p)
    q = np.empty(n)
    q[o] = np.minimum.accumulate((p[o] * n / np.arange(1, n + 1))[::-1])[::-1]
    return np.minimum(q, 1)


def test(t, days, seed=15):
    rng = np.random.default_rng(seed)
    x = daily(t, days)
    xv = x.values
    nd = len(days)
    rows = []
    for f in SKIPS:
        flag = t.groupby("day")[f].first()
        fd = [d for d in days if flag.get(d, np.nan) == 1.0]
        known = [d for d in days if not np.isnan(flag.get(d, np.nan))]
        sk = t[t.day.isin(fd)]
        gain = -sk.net.sum() / nd
        gain15 = -sk.net15.sum() / nd
        h1 = [d for d in fd if d < SPLIT]
        h2 = [d for d in fd if d >= SPLIT]
        n1 = sum(d < SPLIT for d in days)
        g1 = -t[t.day.isin(h1)].net.sum() / max(n1, 1)
        g2 = -t[t.day.isin(h2)].net.sum() / max(nd - n1, 1)
        k = len(fd)
        draws = np.array([-xv[rng.choice(nd, k, replace=False)].sum() / nd for _ in range(B)]) if k else np.zeros(B)
        p = (1 + (draws >= gain).sum()) / (B + 1)
        rows.append(dict(filter=f, flagged_days=k, known_days=len(known), skipped_trades=len(sk), skipped_net=sk.net.sum(),
                         skipped_per_trade=sk.net.mean() if len(sk) else np.nan, gain_day=gain, gain_day_15x=gain15,
                         gain_h1=g1, gain_h2=g2, p=p))
    # F5: per-trade lift on after-drop days, permutation of day labels
    fl = t.groupby("day").F5_AFTERDROP.first()
    dd = [d for d in days if fl.get(d, np.nan) == 1.0]
    on = t.day.isin(dd).values
    lift = t.net[on].mean() - t.net[~on].mean() if on.any() else np.nan
    tday = t.day.values
    ud = np.array(days)
    perm = []
    for _ in range(B):
        pick = set(ud[rng.choice(nd, len(dd), replace=False)])
        m = np.array([d in pick for d in tday])
        perm.append(t.net[m].mean() - t.net[~m].mean() if m.any() and (~m).any() else 0)
    p5 = (1 + (np.array(perm) >= lift).sum()) / (B + 1)
    rows.append(dict(filter="F5_AFTERDROP (lift/trade)", flagged_days=len(dd), skipped_trades=int(on.sum()),
                     skipped_per_trade=t.net[on].mean() if on.any() else np.nan, gain_day=lift, p=p5,
                     gain_h1=t.net[on & (tday < SPLIT)].mean() - t.net[~on & (tday < SPLIT)].mean(),
                     gain_h2=t.net[on & (tday >= SPLIT)].mean() - t.net[~on & (tday >= SPLIT)].mean()))
    R = pd.DataFrame(rows)
    R["bh_q"] = bh(R.p.values)
    R["PASS"] = [(r.bh_q <= 0.10 and r.gain_h1 > 0 and r.gain_h2 > 0 and (r.filter.startswith("F5") or (r.gain_day_15x > 0 and r.skipped_trades >= 30)))
                 for r in R.itertuples()]
    return R


def main():
    mode, scr = sys.argv[1], sys.argv[2]
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 30)
    if mode == "trades":
        return build(scr)
    t, D, alld = load_all(scr)
    if mode == "design":
        days = [d for d in alld if d < HOLD]
        tt = t[t.day < HOLD]
        print("BASE design", stats(tt, days))
        for b in ("15m", "5m"):
            print("  book", b, stats(tt[tt.book == b], days))
        print("  1.5x spread net/day", daily(tt.assign(net=tt.net15), days).mean())
        R = test(tt, days)
        print(R.to_string())
        R.to_csv(os.path.join(scr, "design.csv"), index=False)
    elif mode == "hold":
        flag = os.path.join(scr, "HOLDOUT_RUN.flag")
        if os.path.exists(flag):
            sys.exit("holdout already run once: " + open(flag).read())
        open(flag, "w").write(pd.Timestamp.now().isoformat())
        days = [d for d in alld if d >= HOLD]
        tt = t[t.day >= HOLD]
        print("BASE holdout", stats(tt, days))
        R = test(tt, days)
        print(R.to_string())
        R.to_csv(os.path.join(scr, "hold.csv"), index=False)


if __name__ == "__main__":
    main()
