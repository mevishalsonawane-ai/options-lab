"""R97 step 2 (descriptive, DESIGN window only): what came just before gold's swings, compared with ordinary minutes.

    python -m research.hunt.r97_gold_reverse.step2

Two comparisons:
  A. real-time triggers (price T points off its running extreme): features at the trigger minute, split by what
     happened next (swing reached 500 points? capture of a trail-X exit on the future, before costs);
  B. hindsight swing starts (zig-zag X=200 legs of >= 500 points): features at the pivot minute vs random minutes.
Plus: does the MCX future lag XAUUSD? (cross-correlation and a lagged regression of 1-minute returns).
Writes results/step2.json.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from research.hunt.r97_gold_reverse import cfg as K
from research.hunt.r97_gold_reverse import data as D
from research.hunt.r97_gold_reverse import features as FE
from research.hunt.r97_gold_reverse import moves as M
from research.hunt.r97_gold_reverse.step1 import MIN_MINUTES, clock_bucket, event_minutes

T_MAIN, X_MAIN, BIG = 100, 200, 500
N_RANDOM = 20
NUM = ["rng30", "rng60", "ret15", "twap_dev", "gap", "vol_ratio", "mcx15_bp", "xau5_bp", "xau15_bp",
       "xau_lead15_bp", "ag15_bp"]
FLAGS = ["break_pd", "beyond_pd", "break_or", "break_round", "at_event", "new_day_ext"]


def session_rows(name: str):
    """Yield (day, ix, F, tod, ts, features) for each full design session of the book."""
    b = D.load_book(name)
    m = b.m
    vol = D.option_volume(name)
    xau = D.load_xau()
    ag = D.load_silver_F()
    ag.index = ag.index + pd.Timedelta(minutes=1)        # a SILVER close is known at bar start + 1 min
    evm = event_minutes(D.events())
    prev = None
    for d in m.days:
        ix, F, tod = D.day_arrays(m, d)
        ts = m.ts[ix]
        if D.in_period(d, K.DESIGN) and len(ix) >= MIN_MINUTES:
            day = pd.Timestamp(d).date()
            fe = FE.day_features(ts, F, tod, prev, vol, xau, ag, evm.get(day, []))
            yield d, ix, F, tod, ts, fe
        if len(ix):
            prev = dict(high=float(np.max(F)), low=float(np.min(F)), close=float(F[-1]))


def collect(name: str, seed: int = K.RANDOM_SEED):
    rng = np.random.default_rng(seed)
    trig, piv, rnd = [], [], []
    for d, ix, F, tod, ts, fe in session_rows(name):
        for t in M.triggers(F, T_MAIN, X_MAIN):
            r = FE.directional(fe, t.i, t.d)
            r.update(day=str(pd.Timestamp(d).date()), swing=t.swing, capture=t.capture, why=t.why,
                     big=t.swing >= BIG)
            trig.append(r)
        for g in M.zigzag(F, X_MAIN):
            if g.size >= BIG:
                r = FE.directional(fe, g.i0, g.d)
                r.update(day=str(pd.Timestamp(d).date()), size=g.size)
                piv.append(r)
        for i in rng.integers(30, len(F), N_RANDOM):
            r = FE.directional(fe, int(i), int(rng.choice([-1, 1])))
            r.update(day=str(pd.Timestamp(d).date()))
            rnd.append(r)
    return pd.DataFrame(trig), pd.DataFrame(piv), pd.DataFrame(rnd)


def bucket_table(df: pd.DataFrame, col: str, q=(0.2, 0.4, 0.6, 0.8)) -> list:
    x = df[col]
    ok = x.notna()
    if ok.sum() < 50:
        return [dict(bucket="missing", n=int(ok.sum()))]
    edges = np.unique(np.quantile(x[ok], q))
    lab = np.digitize(x[ok], edges)
    g = df[ok].assign(b=lab)
    out = []
    for k, h in g.groupby("b"):
        lo = -np.inf if k == 0 else edges[k - 1]
        hi = np.inf if k == len(edges) else edges[k]
        out.append(dict(bucket=f"{lo:.4g}..{hi:.4g}", n=int(len(h)), big_share=float(h.big.mean()),
                        capture_mean=float(h.capture.mean()), capture_win=float((h.capture > 0).mean())))
    return out


def flag_table(df: pd.DataFrame, col: str) -> list:
    out = []
    for v, h in df.groupby(df[col].astype(bool)):
        out.append(dict(bucket=str(bool(v)), n=int(len(h)), big_share=float(h.big.mean()),
                        capture_mean=float(h.capture.mean()), capture_win=float((h.capture > 0).mean())))
    return out


def compare(piv: pd.DataFrame, rnd: pd.DataFrame) -> dict:
    out = {}
    for c in NUM:
        a, b = piv[c].dropna(), rnd[c].dropna()
        out[c] = dict(swing_start_median=float(a.median()) if len(a) else None, n_swing=int(len(a)),
                      random_median=float(b.median()) if len(b) else None, n_random=int(len(b)))
    for c in FLAGS:
        out[c] = dict(swing_start_share=float(piv[c].mean()), random_share=float(rnd[c].mean()),
                      n_swing=int(len(piv)), n_random=int(len(rnd)))
    cb_p = piv.tod.map(clock_bucket).value_counts(normalize=True)
    cb_r = rnd.tod.map(clock_bucket).value_counts(normalize=True)
    out["clock"] = {k[0]: dict(swing_start_share=float(cb_p.get(k[0], 0)), random_share=float(cb_r.get(k[0], 0)))
                    for k in K.CLOCK}
    return out


def lag_study(name: str) -> dict:
    """Corr(MCX 1-min return at t, XAUUSD 1-min return at t - L) for L = -3..+5 minutes, on design minutes where both
    print; and the share of the MCX move explained by XAUUSD's previous minutes."""
    xau = D.load_xau()
    if xau is None:
        return dict(missing="XAUUSD minutes not fetched")
    b = D.load_book(name)
    m = b.m
    t = pd.DatetimeIndex(m.ts)
    sel = np.array([D.in_period(x, K.DESIGN) for x in m.day])
    known = t + pd.Timedelta(minutes=1)
    mc = pd.Series(np.log(m.F), index=known)[sel]
    xs = np.log(xau)
    grid = mc.index
    out = {}
    r_m = mc.diff()
    same = (pd.Series(mc.index, index=mc.index).diff() == pd.Timedelta(minutes=1)).values
    for L in range(-3, 6):
        xa = pd.Series(xs.reindex(grid - pd.Timedelta(minutes=L)).values, index=grid)
        xb = pd.Series(xs.reindex(grid - pd.Timedelta(minutes=L + 1)).values, index=grid)
        r_x = xa - xb
        ok = same & r_m.notna().values & r_x.notna().values
        out[f"lag_{L}"] = dict(corr=float(np.corrcoef(r_m.values[ok], r_x.values[ok])[0, 1]), n=int(ok.sum()))
    # 5-minute blocks: MCX return over the next 5 minutes vs XAUUSD's over the last 5 minutes beyond MCX's own
    x_now = xs.reindex(grid).values
    x_5 = xs.reindex(grid - pd.Timedelta(minutes=5)).values
    m_now = mc.values
    m_5 = pd.Series(mc.values, index=grid).reindex(grid - pd.Timedelta(minutes=5)).values
    m_f5 = pd.Series(mc.values, index=grid).reindex(grid + pd.Timedelta(minutes=5)).values
    gap = (x_now - x_5) - (m_now - m_5)
    fut = m_f5 - m_now
    ok = np.isfinite(gap) & np.isfinite(fut)
    if ok.sum() > 100:
        beta = float(np.polyfit(gap[ok], fut[ok], 1)[0])
        c = float(np.corrcoef(gap[ok], fut[ok])[0, 1])
        big = ok & (np.abs(gap) > np.nanpercentile(np.abs(gap[ok]), 95))
        out["gap5_next5"] = dict(n=int(ok.sum()), corr=c, beta=beta,
                                 top5pct_n=int(big.sum()),
                                 top5pct_same_sign_share=float(np.mean(np.sign(gap[big]) == np.sign(fut[big]))),
                                 top5pct_next5_bp_with_gap=float(1e4 * np.mean(np.sign(gap[big]) * fut[big])))
    return out


def main():
    res = {}
    for name in ("GOLDM", "GOLD"):
        trig, piv, rnd = collect(name)
        trig.to_pickle(K.CACHE / f"step2_trig_{name}.pkl")
        piv.to_pickle(K.CACHE / f"step2_piv_{name}.pkl")
        rnd.to_pickle(K.CACHE / f"step2_rnd_{name}.pkl")
        r = dict(trigger=dict(T=T_MAIN, X=X_MAIN, big=BIG, n=int(len(trig)), big_share=float(trig.big.mean()),
                              capture_mean=float(trig.capture.mean()), days=int(trig.day.nunique())))
        r["trigger_buckets"] = {c: bucket_table(trig, c) for c in NUM}
        r["trigger_flags"] = {c: flag_table(trig, c) for c in FLAGS}
        r["trigger_clock"] = {k[0]: dict(n=int((trig.tod.map(clock_bucket) == k[0]).sum()),
                                         big_share=float(trig.big[trig.tod.map(clock_bucket) == k[0]].mean()),
                                         capture_mean=float(trig.capture[trig.tod.map(clock_bucket) == k[0]].mean()))
                              for k in K.CLOCK}
        r["swing_start_vs_random"] = compare(piv, rnd)
        r["lag"] = lag_study(name)
        res[name] = r
        print(name, len(trig), len(piv), len(rnd), flush=True)
    (K.RESULTS / "step2.json").write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main()
