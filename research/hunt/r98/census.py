"""R98 steps 1-3 (PREREG sections 2-3): census, anatomy, "why" (realised vs implied) and "when", all five indices.

    python3 -I research/hunt/r98/census.py        # writes $R98_OUT/events.parquet, $R98_OUT/why.parquet, results/census.json

Per option-day: the best hero multiple = max over entry minutes t (10:00-15:00, option cheap at t) of the best close in
(t, 15:20] / close[t]. Per day: the best multiple of any option. For every option-day reaching 5x: the anatomy row.
"Why": at fixed decision times, the realised excursion to 15:20 in units of the ATM straddle, and whether some option
cheap at that time ran 10x.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402

RESULTS = Path(__file__).resolve().parent / "results"
TAUS = [(11, 0), (12, 0), (13, 0), (13, 30), (14, 0), (14, 30)]


def hhmm(t: int) -> str:
    m = 9 * 60 + 15 + int(t)
    return f"{m // 60:02d}:{m % 60:02d}"


def day_rows(day: R.Day, monthly: bool):
    sp = L.spot(day)
    em, _ = L.straddle(day)
    hi, lo = day.ix[:, 1], day.ix[:, 2]
    step = R.STEP[day.und]
    ev = []
    best = dict(mult=0.0)
    # running day high / low (point in time)
    rh = np.fmax.accumulate(np.where(np.isnan(hi), -np.inf, hi))
    rl = np.fmin.accumulate(np.where(np.isnan(lo), np.inf, lo))
    o0 = day.ix[0, 0] if not np.isnan(day.ix[0, 0]) else sp[0]
    for (k, s), a in day.opts.items():
        c = a[:, R.C]
        fm = L.future_max(c)
        ts = np.arange(L.T_CHEAP0, L.T_CHEAP1 + 1)
        p = c[ts]
        vol3 = np.nansum(np.stack([a[ts - j, R.V] for j in range(3)]), axis=0)
        isotm = (k > sp[ts]) if s == "C" else (k < sp[ts])
        ok = (p >= L.LO) & (p <= L.HI) & isotm & (vol3 > 0) & ~np.isnan(fm[ts])
        if not ok.any():
            continue
        m = np.where(ok, fm[ts] / np.where(ok, p, 1.0), -1.0)
        j = int(np.argmax(m))
        bm, bt = float(m[j]), int(ts[j])
        t1 = L.future_argmax(c, bt)
        sign = 1 if s == "C" else -1
        rec = dict(und=day.und, day=day.day, strike=k, side=s, mult=bm, t0=bt, t1=t1, p0=c[bt], p1=c[t1],
                   p_end=c[L.T_END] if not np.isnan(c[L.T_END]) else np.nan)
        if bm > best["mult"]:
            best = rec
        if bm >= 5:
            s0, s1 = sp[bt], sp[t1]
            e0 = em[bt]
            rng0 = rh[bt] - rl[bt]
            rec.update(
                spot0=s0, spot1=s1, move_pts=sign * (s1 - s0), move_pct=sign * (s1 - s0) / s0 * 100,
                em0=e0, em0_pct=e0 / s0 * 100 if e0 == e0 else np.nan,
                move_em=sign * (s1 - s0) / e0 if e0 and e0 == e0 else np.nan,
                dist_pts=abs(k - s0), dist_strikes=abs(k - s0) / step, dist_em=abs(k - s0) / e0 if e0 and e0 == e0 else np.nan,
                mins_to_peak=t1 - bt, t0_hhmm=hhmm(bt), t1_hhmm=hhmm(t1),
                # where spot sat in the day's range at t0, signed to the option: 1 = at the extreme it needs to push,
                # 0 = at the opposite extreme (a reversal hero)
                pos0=((s0 - rl[bt]) / rng0 if sign > 0 else (rh[bt] - s0) / rng0) if rng0 > 0 else np.nan,
                day_move_pct=sign * (s0 - o0) / o0 * 100,
                gap_pct=sign * (o0 - day.prev_close) / day.prev_close * 100 if day.prev_close == day.prev_close else np.nan,
                vix0=day.vix[bt] if day.vix is not None else np.nan, vix_prev=day.vix_prev,
                giveback=(c[t1] - c[L.T_END]) / (c[t1] - c[bt]) if c[t1] > c[bt] and not np.isnan(c[L.T_END]) else np.nan,
                mins_half=int(np.nansum(c[bt:L.T_END + 1] >= c[bt] + 0.5 * (c[t1] - c[bt]))),
                monthly=monthly,
            )
            ev.append(rec)
    # "why": realised excursion vs implied at fixed times
    why = []
    for hh, mm in TAUS:
        t = R.minute(hh, mm)
        if np.isnan(sp[t]) or np.isnan(em[t]) or em[t] <= 0:
            continue
        up = np.nanmax(hi[t + 1:L.T_END + 1]) - sp[t]
        dn = sp[t] - np.nanmin(lo[t + 1:L.T_END + 1])
        term = sp[L.T_END] - sp[t] if not np.isnan(sp[L.T_END]) else np.nan
        hero = {"C": 0.0, "P": 0.0}
        for (k, s), a in day.opts.items():
            p = a[t, R.C]
            if np.isnan(p) or p < L.LO or p > L.HI or not L.otm(k, s, sp[t]):
                continue
            fm = np.nanmax(a[t + 1:L.T_END + 1, R.C]) if np.any(~np.isnan(a[t + 1:L.T_END + 1, R.C])) else np.nan
            if fm == fm:
                hero[s] = max(hero[s], fm / p)
        why.append(dict(und=day.und, day=day.day, tau=f"{hh:02d}:{mm:02d}", spot=sp[t], em=em[t], em_pct=em[t] / sp[t] * 100,
                        up_em=up / em[t], dn_em=dn / em[t], term_em=term / em[t] if term == term else np.nan,
                        best_call=hero["C"], best_put=hero["P"], vix=day.vix[t] if day.vix is not None else np.nan))
    dayrec = dict(und=day.und, day=day.day, best_mult=best["mult"], best_side=best.get("side"),
                  best_t0=best.get("t0", -1), best_t1=best.get("t1", -1), monthly=monthly,
                  vix_prev=day.vix_prev, gap_pct=(day.ix[0, 0] - day.prev_close) / day.prev_close * 100
                  if day.prev_close == day.prev_close and not np.isnan(day.ix[0, 0]) else np.nan,
                  day_range_pct=(np.nanmax(hi) - np.nanmin(lo)) / sp[0] * 100 if not np.isnan(sp[0]) else np.nan,
                  am_range_pct=(np.nanmax(hi[:R.minute(13, 0)]) - np.nanmin(lo[:R.minute(13, 0)])) / sp[0] * 100
                  if not np.isnan(sp[0]) else np.nan,
                  pm_range_pct=(np.nanmax(hi[R.minute(13, 0):L.T_END + 1]) - np.nanmin(lo[R.minute(13, 0):L.T_END + 1]))
                  / sp[0] * 100 if not np.isnan(sp[0]) else np.nan,
                  n5=sum(1 for e in ev if e["mult"] >= 5), n10=sum(1 for e in ev if e["mult"] >= 10))
    return ev, why, dayrec


def main():
    R.OUT.mkdir(parents=True, exist_ok=True)
    evs, whys, days = [], [], []
    for u in R.UNDS:
        ds = R.load(u)
        for d in ds:
            ev, why, dr = day_rows(d, L.is_monthly(d, ds))
            evs += ev
            whys += why
            days.append(dr)
        print(u, len(ds), "days", flush=True)
    ev = pd.DataFrame(evs)
    why = pd.DataFrame(whys)
    dd = pd.DataFrame(days)
    ev.to_parquet(R.OUT / "events.parquet")
    why.to_parquet(R.OUT / "why.parquet")
    dd.to_parquet(R.OUT / "days.parquet")
    print(len(ev), "events >= 5x;", len(why), "why rows;", len(dd), "days")


if __name__ == "__main__":
    main()
