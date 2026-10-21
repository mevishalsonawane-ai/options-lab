"""R97 step 1 (descriptive, DESIGN window only): what gold's daily "200-300 point" moves are.

    python -m research.hunt.r97_gold_reverse.step1

Writes results/step1.json. Full sessions only (>= MIN_MINUTES minute closes); the count of days dropped is reported.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from research.hunt.r97_gold_reverse import cfg as K
from research.hunt.r97_gold_reverse import data as D
from research.hunt.r97_gold_reverse import moves as M

MIN_MINUTES = 600


def clock_bucket(tod: int) -> str:
    for name, a, b in K.CLOCK:
        if a <= tod < b:
            return name
    return "other"


def event_minutes(cal: dict) -> dict:
    """{date: [(kind, release minute)]} from the calendar."""
    out = {}
    for k, dd in cal.items():
        for d, mnt in dd.items():
            out.setdefault(pd.Timestamp(d).date(), []).append((k, int(mnt)))
    return out


def at_event(day, tod: int, evm: dict) -> str | None:
    for k, mnt in evm.get(pd.Timestamp(day).date(), []):
        if K.EVENT_WINDOW[0] <= tod - mnt <= K.EVENT_WINDOW[1]:
            return k
    return None


def q(x, ps=(25, 50, 75, 90)):
    x = np.asarray(x, float)
    x = x[np.isfinite(x)]
    if not len(x):
        return {}
    return dict(n=int(len(x)), mean=float(x.mean()), **{f"p{p}": float(np.percentile(x, p)) for p in ps})


def run_book(name: str, cal: dict) -> dict:
    b = D.load_book(name)
    m = b.m
    evm = event_minutes(cal)
    days, legs, trig, dropped = [], [], [], 0
    for d in D.design_days(m):
        ix, F, tod = D.day_arrays(m, d)
        if len(ix) < MIN_MINUTES:
            dropped += 1
            continue
        s = M.day_stats(F, K.RETRACE, K.SWING_SIZES)
        s["day"] = str(pd.Timestamp(d).date())
        days.append(s)
        for X in K.RETRACE:
            for g in M.zigzag(F, X):
                end_px = F[g.ic] if g.ic >= 0 else F[-1]
                row = dict(day=s["day"], X=X, d=g.d, size=g.size, start_tod=int(tod[g.i0]), end_tod=int(tod[g.i1]),
                           minutes=int(tod[g.i1] - tod[g.i0]), confirmed=g.ic >= 0, range=s["range"],
                           event=at_event(d, int(tod[g.i0]), evm))
                for T in K.TRIGGERS:
                    row[f"perfect_{T}"] = max(g.size - T, 0.0)
                    # entry at the first close T past the pivot, exit at the close that confirmed the end (back X)
                    j = next((k for k in range(g.i0, g.i1 + 1) if g.d * (F[k] - F[g.i0]) >= T), None)
                    row[f"trail_{T}"] = float(g.d * (end_px - F[j])) if j is not None else np.nan
                legs.append(row)
        for T in K.TRIGGERS:
            for X in K.RETRACE:
                for t in M.triggers(F, T, X):
                    trig.append(dict(day=s["day"], T=T, X=X, d=t.d, tod=int(tod[t.i]), swing=t.swing,
                                     capture=t.capture, why=t.why, late=float(t.d * (t.entry - t.pivot)),
                                     event=at_event(d, int(tod[t.i]), evm)))
    DF, L, TR = pd.DataFrame(days), pd.DataFrame(legs), pd.DataFrame(trig)
    nd = len(DF)
    out = dict(book=name, design=[str(x) for x in K.DESIGN], days=nd, dropped_short_days=dropped,
               price=q(DF.close), range=q(DF.range), range_pct=q(100 * DF.range / DF.close),
               open_close=q(DF.oc.abs()), efficiency=q(DF.eff), round_trip=q(DF.round_trip),
               days_range_ge={S: int((DF.range >= S).sum()) for S in (200, 300, 500, 1000, 2000, 3000)})
    out["max_leg"] = {X: q(DF[f"max_leg_{X}"]) for X in K.RETRACE}
    out["max_leg_share"] = {X: q(DF[f"max_leg_share_{X}"]) for X in K.RETRACE}
    out["legs_per_day"] = {X: {S: q(DF[f"n_legs_{X}_{S}"]) for S in K.SWING_SIZES} for X in K.RETRACE}
    out["days_with_leg"] = {X: {S: int((DF[f"n_legs_{X}_{S}"] > 0).sum()) for S in K.SWING_SIZES}
                            for X in K.RETRACE}
    # legs >= 200 / 300 / 500: when they start, direction, duration, what a T-trigger entry could take
    lg = {}
    clock_minutes = {c[0]: c[2] - c[1] for c in K.CLOCK}
    for X in K.RETRACE:
        for S in (200, 300, 500):
            g = L[(L.X == X) & (L["size"] >= S)]
            if not len(g):
                continue
            cb = g.start_tod.map(clock_bucket).value_counts()
            r = dict(n=int(len(g)), per_day=float(len(g) / nd), up_share=float((g.d > 0).mean()),
                     size=q(g["size"]), minutes=q(g.minutes),
                     start_clock={k: dict(n=int(cb.get(k, 0)), per_hour_per_day=float(cb.get(k, 0) / nd /
                                                                                     (clock_minutes[k] / 60)))
                                  for k in clock_minutes},
                     at_event=int(g.event.notna().sum()),
                     at_event_by_kind={k: int(v) for k, v in g.event.value_counts().items()})
            for T in K.TRIGGERS:
                r[f"perfect_{T}"] = q(g[f"perfect_{T}"])
                r[f"trail_{T}"] = q(g[f"trail_{T}"])
                r[f"trail_{T}_share_of_size"] = float(np.nanmean(g[f"trail_{T}"] / g["size"]))
            lg[f"X{X}_S{S}"] = r
    out["legs"] = lg
    # event days vs other days (range)
    ev_days = {str(dd) for dd in evm}
    DF["ev"] = DF.day.isin(ev_days)
    out["range_event_days"] = q(DF.range[DF.ev])
    out["range_other_days"] = q(DF.range[~DF.ev])
    out["event_days_in_design"] = int(DF.ev.sum())
    # real-time triggers
    tr = {}
    for T in K.TRIGGERS:
        for X in K.RETRACE:
            g = TR[(TR["T"] == T) & (TR.X == X)]
            if not len(g):
                continue
            tr[f"T{T}_X{X}"] = dict(
                n=int(len(g)), per_day=float(len(g) / nd),
                failed=float((g.why == "failed").mean()), day_end=float((g.why == "day_end").mean()),
                swing_ge={S: float((g.swing >= S).mean()) for S in (200, 300, 500, 1000)},
                late=q(g.late), capture=q(g.capture), capture_sum_per_day=float(g.capture.sum() / nd),
                capture_win=float((g.capture > 0).mean()),
                capture_by_clock={k: dict(n=int((g.tod.map(clock_bucket) == k).sum()),
                                          mean=float(g.capture[g.tod.map(clock_bucket) == k].mean()))
                                  for k in clock_minutes})
    out["triggers"] = tr
    return out, DF, L, TR


def history_daily() -> dict:
    """Daily high-low range of the near-month GOLD / GOLDM continuous future, 2023-01-01 to the design start (the lake
    has no gold minute data before Aug 2025, so only the daily range can be checked further back)."""
    from research.hunt.r53.mcx import data as D53
    out = {}
    for sym in ("GOLD", "GOLDM"):
        d = D53.load_daily(sym)
        d = d[(d.index >= "2023-01-01") & (d.index < "2025-08-05")]
        r = d.high - d.low
        out[sym] = dict(days=int(len(d)), first=str(d.index.min().date()) if len(d) else None,
                        range=q(r), range_pct=q(100 * r / d.close),
                        by_year={str(y): q(g.high - g.low) for y, g in d.groupby(d.index.year)})
    return out


def main():
    cal = D.events()
    res = {}
    for name in ("GOLDM", "GOLD"):
        out, DF, L, TR = run_book(name, cal)
        res[name] = out
        DF.to_pickle(K.CACHE / f"step1_days_{name}.pkl")
        L.to_pickle(K.CACHE / f"step1_legs_{name}.pkl")
        TR.to_pickle(K.CACHE / f"step1_trig_{name}.pkl")
        print(name, out["days"], "range median", round(out["range"]["p50"]), flush=True)
    res["history_daily"] = history_daily()
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "step1.json").write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main()
