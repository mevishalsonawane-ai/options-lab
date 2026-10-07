"""Parity: liqcash.signals_up (whole-history zones) vs the per-day 10-day-window reference (h4/comps.py fold /
swing_zones / pool_zones + the liq_ext_signals loop, side +1), on a few instruments and days.

    python3 -I research/hunt/h8/parity.py
"""
import os
import sys
from datetime import timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path[:0] = [HERE, os.path.dirname(os.path.dirname(HERE)), os.path.join(os.path.dirname(HERE), "h4")]
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import liqcash as LC  # noqa: E402
import comps as CP  # noqa: E402


def reference(df, istop_by_day, real_by_day, tf, days_to_check, room=1.0):
    rows = []
    g = {d: x for d, x in df.groupby("day")}
    alldays = sorted(g)
    for d in days_to_check:
        if d not in istop_by_day:
            continue
        hist_days = [x for x in alldays if d - timedelta(days=10) <= x < d]
        if not hist_days or not all(real_by_day[x] for x in hist_days + [d]):
            continue
        ones = []
        for x in hist_days + [d]:
            t = g[x]
            ones += [(x, int(m), o, h, lo, c) for m, o, h, lo, c in zip(t.m, t.open, t.high, t.low, t.close)]
        bars = CP.fold(ones, tf)
        if len(bars) < 2 * CP.L + 2:
            continue
        O = np.array([b[2] for b in bars], float); H = np.array([b[3] for b in bars], float)
        Lo = np.array([b[4] for b in bars], float); Cl = np.array([b[5] for b in bars], float)
        sw_ = [z for z in CP.swing_zones(H, Lo, Cl) if z.side > 0]
        po = [z for z in CP.pool_zones(O, H, Lo, Cl) if z.side > 0]
        zones = sw_ + po
        n = len(bars)
        is_today = [b[0] == d for b in bars]
        bar_end = [b[1] + tf for b in bars]
        istop = istop_by_day[d]
        by_break = {}
        for p in po:
            if p.broken >= 0:
                by_break.setdefault(p.broken, []).append(p)
        for i in range(n):
            if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
                continue
            pool = None
            for p in by_break[i]:
                if p.known > i:
                    continue
                if any(s.bottom <= p.top and p.bottom <= s.top and s.known <= i and (s.broken < 0 or s.broken >= i) for s in sw_):
                    pool = p; break
            if pool is None:
                continue
            close = Cl[i]
            ahead = [q.edge for q in zones if q.known <= i and (q.broken < 0 or q.broken > i) and q.edge - close > 0]
            target = min(ahead) if ahead else None
            level = pool.edge
            fb = next((bar_end[k] for k in range(i + 1, n) if Cl[k] - level < 0), None)
            nl = [bar_end[z.known] for z in zones if z.known >= i + 1]
            nl = min(nl) if nl else None
            done = bar_end[i]
            if done < LC.WIN_FROM or done > LC.WIN_TO:
                continue
            if target is not None and target - close < room * istop:
                continue
            xa = [v for v in (fb, nl) if v is not None]
            rows.append(dict(day=d, done=done, level=round(level, 2), target=np.nan if target is None else round(target, 2),
                             exit_at=float(min(xa)) if xa else np.nan))
    return pd.DataFrame(rows)


def check(kind, name, ist_fn, ndays=60, tf=5):
    df = LC.load_minutes(kind, name)
    days, A, cnt = LC.dense(df)
    atr, adv, pc = LC.daily_stats(days, A)
    real = {pd.Timestamp(d): bool(c >= 200) for d, c in zip(days, cnt)}
    ist = {pd.Timestamp(d): v for q, d in enumerate(days) if (v := ist_fn(q, atr[q], pc[q])) is not None}
    rng = np.random.default_rng(1)
    pick = sorted(pd.Timestamp(x) for x in rng.choice(days[30:], ndays, replace=False))
    sub = {d: ist[d] for d in pick if d in ist}
    fast = LC.signals_up(df, sub, real, tf)
    ref = reference(df, sub, real, tf, pick)
    if len(fast):
        fast = fast.assign(level=fast.level.round(2), target=fast.target.round(2))[["day", "done", "level", "target", "exit_at"]]
    k = ["day", "done"]
    m = fast.merge(ref, on=k, how="outer", suffixes=("_f", "_r"), indicator=True) if len(fast) and len(ref) else None
    same = 0 if m is None else ((m._merge == "both") & np.isclose(m.level_f, m.level_r) &
                                (np.isclose(m.target_f, m.target_r) | (m.target_f.isna() & m.target_r.isna())) &
                                (np.isclose(m.exit_at_f, m.exit_at_r) | (m.exit_at_f.isna() & m.exit_at_r.isna()))).sum()
    print(f"{name} tf={tf}: fast {len(fast)} ref {len(ref)} identical {same}", flush=True)
    if m is not None and same != max(len(fast), len(ref)):
        print(m[(m._merge != "both") | ~np.isclose(m.level_f, m.level_r)].head(10).to_string())
        print(m.head(10).to_string())


if __name__ == "__main__":
    for nm in ("RELIANCE", "TATASTEEL", "DIXON"):
        for tf in (15, 5):
            check("NSE_EQ", nm, LC.stock_istop, tf=tf)
    check("IDX_I", "BANKNIFTY", lambda q, a, p: 30.0, tf=15)
    check("IDX_I", "BANKNIFTY", lambda q, a, p: 30.0, tf=5)
