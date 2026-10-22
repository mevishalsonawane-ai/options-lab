"""FT3 (FORWARD_TEST.md) as the app runs it: picked from the raw minute data, Black-76 IV, the app's LIMIT and 3-minute
entry window, and the app's 15:05 expiry square-off.

    python3 -I research/hunt/r98/ft3_app.py     # -> results/ft3_app.md, results/ft3_app.json

The research FT3 used the vendor's IV column and R98's decision rows. Those rows keep only options whose next minute has an
open: a look-ahead the app cannot make. It also held to 15:20. This replays FT3 the way android/engine orb/Ft3Rules.kt and
app data/Ft3Arm.kt run it, on the 2021-26 expiry days of the five indices:
  - at each 5-minute mark t (the bar that STARTS at 11:00 .. 12:55), from that minute's data only:
    - spot is the index close, forward-filled up to 5 minutes;
    - ATM is spot rounded to the index step, ties to even;
    - the straddle is the ATM call + put, each its last close within 3 minutes;
    - IV is Black-76 with forward = spot, r = 0 and calendar time from the mark to 15:30; the ATM IV is the mean of the ATM
      call's and put's.
  - Candidates are OTM options that:
    - closed at Rs 6-10 on the mark's bar;
    - traded in its last 3 bars;
    - lie within DIST_EM_MAX straddles;
    - have an IV ratio <= IV_REL_MAX.
    The first mark with one wins; at that mark the nearest in straddles, then the lower IV ratio, then the call.
  - Entry: the app's LIMIT is capped at the mark's price x 1.2 + 0.10 (on the tick). It fills at the first of the next 3
    bars' opens + 1 tick that is within the cap. None: no ticket that day (counted). A "late" variant starts one bar later,
    since the candle feed can lag by a minute or two.
  - Exit: the close of the bar starting 15:04 (the app sells at 15:05).
  - Rs 5,000 a ticket, R98 charges. Quantity is fractional as R98 (the app buys whole lots: a little less invested).
The cut-offs are the design-years (2021-23) 10th percentiles the research rule used, to 4 decimals.
"""
from __future__ import annotations

import json
import math
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parents[1]))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
from hero2 import md_table, net_legs, pct, rs  # noqa: E402

RES = HERE / "results"
IV_REL_MAX = 1.0333       # design 10th percentile of iv_rel (1.0333013)
DIST_EM_MAX = 1.5466      # design 10th percentile of f_dist_em (1.5466147)
PX_LO, PX_HI = 6.0, 10.0
T0, T1 = R.minute(11, 0), R.minute(12, 55)
MARKS = list(range(T0, T1 + 1, 5))
T_EXIT = R.minute(15, 4)
ENTRY_BARS = 3


def b76(f: float, k: float, t: float, sig: float, call: bool) -> float:
    d1 = (math.log(f / k) + 0.5 * sig * sig * t) / (sig * math.sqrt(t))
    d2 = d1 - sig * math.sqrt(t)
    n = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))  # noqa: E731
    return f * n(d1) - k * n(d2) if call else k * n(-d2) - f * n(-d1)


def iv(price: float, f: float, k: float, t: float, call: bool) -> float:
    """Black-76 implied vol by bisection; NaN when the price is not above intrinsic."""
    if not (price == price and f == f and price > 0 and f > 0):
        return np.nan
    intr = max(f - k, 0.0) if call else max(k - f, 0.0)
    if not price > intr + 1e-9:
        return np.nan
    lo, hi = 1e-4, 20.0
    for _ in range(80):
        mid = 0.5 * (lo + hi)
        if b76(f, k, t, mid, call) > price:
            hi = mid
        else:
            lo = mid
    return 0.5 * (lo + hi)


def years_to_1530(t: int) -> float:
    return max(375 - t, 1) / (365 * 24 * 60)        # minute t counts from 09:15; 15:30 is minute 375


def floor_tick(x: float) -> float:
    return math.floor(x / L.TICK + 1e-6) * L.TICK


def pick_at(day: R.Day, t: int, sp=None, cache=None):
    """The FT3 pick at mark t from data up to minute t only: (strike, side, price, dist_em, iv_ratio) or None."""
    sp = L.spot(day) if sp is None else sp
    s = sp[t]
    if not s == s:
        return None
    step = R.STEP[day.und]
    ka = float(round(s / step) * step)                 # Python's round: ties to even, as the app's Math.rint
    cache = {} if cache is None else cache

    def ff(key):
        if key not in cache:
            a = day.opts.get(key)
            cache[key] = L.ffill(a[:, R.C], 3) if a is not None else None
        return cache[key]
    c, p = ff((ka, "C")), ff((ka, "P"))
    if c is None or p is None:
        return None
    em = c[t] + p[t]
    if not em > 0:
        return None
    yt = years_to_1530(t)
    atm = [x for x in (iv(c[t], s, ka, yt, True), iv(p[t], s, ka, yt, False)) if x == x]
    if not atm:
        return None
    atm_iv = float(np.mean(atm))
    best = None
    for (k, side), a in day.opts.items():
        px = a[t, R.C]
        if not (px == px and PX_LO - 1e-9 <= px <= PX_HI + 1e-9) or not L.otm(k, side, s) or not L.traded(a, t):
            continue
        dist = abs(k - s) / em
        if dist > DIST_EM_MAX + 1e-9:
            continue
        o = iv(px, s, k, yt, side == "C")
        if not o == o:
            continue
        r = o / atm_iv
        if r > IV_REL_MAX + 1e-9:
            continue
        key = (dist, r, 0 if side == "C" else 1)
        if best is None or key < best[0]:
            best = (key, (float(k), side, float(px), float(dist), float(r)))
    return best[1] if best else None


def fill(a: np.ndarray, t: int, px: float, start: int = 1):
    """The app's LIMIT: the first of the bars t+start .. t+start+2 whose open + 1 tick is within the cap (mark price x 1.2 +
    0.10, on the tick). (fill price, bar) or None."""
    cap = floor_tick(px * 1.2 + 0.10)
    for k in range(t + start, min(t + start + ENTRY_BARS, R.N)):
        o = a[k, R.O]
        if o == o and o + L.TICK <= cap + 1e-9:
            return float(o + L.TICK), k
    return None


def run_und(und):
    out = []
    for day in R.load(und):
        sp, cache = L.spot(day), {}
        for t in MARKS:
            p = pick_at(day, t, sp, cache)
            if p is None:
                continue
            k, side, px, dist, r = p
            a = day.opts[(k, side)]
            row = dict(und=und, day=day.day, t=t, strike=k, side=side, px=px, dist_em=dist, iv_ratio=r)
            for lab, start in (("app", 1), ("late", 2)):
                f = fill(a, t, px, start)
                if f is None:
                    row[lab] = np.nan
                    continue
                e, kb = f
                c = a[kb:T_EXIT + 1, R.C]
                ok = ~np.isnan(c)
                row[lab] = net_legs(und, e, [(1.0, float(c[ok][-1]) if ok.any() else 0.0)])
                row[f"{lab}_entry"] = e
            out.append(row)
            break                                      # one ticket per index-day: the first mark that met the rule
    print(und, len(out), flush=True)
    return out


def research_version():
    """The research FT3 (vendor IV, R98 rows, held to 15:20) per index-day, for the comparison."""
    import search_e as SE
    df = SE.load()
    m = (df.iv_rel <= IV_REL_MAX) & (df.f_dist_em <= DIST_EM_MAX) & (df.t >= T0) & (df.t <= T1) & (df.f_px >= PX_LO) & (df.f_px < PX_HI + 1e-4)
    pos = np.nonzero(m.values)[0]
    _, first = np.unique(df.g.values[pos], return_index=True)
    return df.iloc[pos[first]]


def stats(v: np.ndarray, g: pd.DataFrame, col: str) -> dict:
    by = g.groupby(["und", "day"])[col].sum().sort_values(ascending=False)
    yr = g.groupby(pd.to_datetime(g.day).dt.year)[col].sum()
    return {"tickets": len(v), "win": pct((v > 0).mean(), 1), "mean Rs": rs(v.mean()), "total Rs": rs(v.sum()),
            "w/o 5 best days": rs((v.sum() - by.head(5).sum()) / len(v)), "years > 0": f"{int((yr > 0).sum())}/{len(yr)}",
            **{str(int(k)): rs(x) for k, x in yr.items()}}


def main():
    with Pool(4) as p:
        parts = p.map(run_und, R.UNDS)
    df = pd.DataFrame([r for part in parts for r in part])
    rows = []
    for lab, col in (("app (LIMIT, 3-minute window, out 15:05)", "app"), ("app, filled a bar later (feed lag)", "late")):
        g = df[df[col].notna()]
        rows.append({"version": lab, "signals": len(df), "unfilled": int(df[col].isna().sum()), **stats(g[col].values, g, col)})
    rv = research_version()
    rows.append({"version": "research (vendor IV, R98 rows, held to 15:20)", "signals": len(rv), "unfilled": 0,
                 **stats(rv.x_inf_0.values, rv.assign(day=rv.day), "x_inf_0")})
    same = len(set(zip(df.und, df.day)) & set(zip(rv.und, rv.day)))
    g = df[df.app.notna()]
    by_idx = g.groupby("und").app.agg(["size", "mean"]).reset_index()
    by_idx.columns = ["index", "tickets", "mean Rs"]
    by_idx["mean Rs"] = by_idx["mean Rs"].map(rs)
    t = pd.DataFrame(rows)
    md = ["# FT3 as the app runs it (generated by ft3_app.py)\n",
          f"- Cut-offs (design 2021-23 10th percentiles, to 4 decimals): IV ratio ≤ {IV_REL_MAX}, distance ≤ {DIST_EM_MAX} "
          f"ATM straddles, price Rs {PX_LO:g}-{PX_HI:g}, marks 11:00-12:55; picked from the raw minute data (no look-ahead).",
          f"- Index-days with a signal: {len(df)}; the same index-day as the research version: {same} of {len(rv)}.", "",
          md_table(t), "", "## By index (the app version)\n", md_table(by_idx), ""]
    RES.mkdir(exist_ok=True)
    (RES / "ft3_app.md").write_text("\n".join(md) + "\n")
    (RES / "ft3_app.json").write_text(json.dumps(dict(same=same, table=rows, by_index=by_idx.to_dict(orient="records")),
                                                 indent=1, default=str))
    df.to_parquet(R.OUT / "ft3_app.parquet")
    print("\n".join(md))


if __name__ == "__main__":
    main()
