"""F1 tables: arm outcomes and index-move targets at the decision minutes, per index (PREREG 3).

    python research/hunt/f1/data.py build [--und NIFTY ...] [--part design|holdout]

-> <OUT>/outcomes/<part>/<UND>.parquet: one row per (day, decision row in RULE_ROWS) with, per arm (ce/pe x atm/itm1 x
   x1/x2): net_<arm> (Rs a lot, after costs), why_<arm> (exit reason code), cut_<arm> (1 = a big winner cut short),
   plus ret15/ret30/ret60 (index log return, %), abs30, is_expiry, dte.
Holdout days are built only with --part holdout, which the one-time holdout step (rules.py holdout) calls.
"""
from __future__ import annotations

import argparse
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402
from research.brain import cube  # noqa: E402
from sim import R_LOCK, R_TIME, net_rs, simulate  # noqa: E402

ARMS = [f"{s}_{k}_{x}" for s in K.SIDES for k in K.STRIKES for x in K.EXITS]


def contracts(ch: pd.DataFrame) -> dict:
    """(strike, side) -> (5, 375) array O H L C V, NaN where the contract did not trade that minute."""
    ch = ch[ch.flag == "ok"]
    out = {}
    rows = ((pd.to_datetime(ch.ts) - pd.to_datetime(ch.ts).dt.normalize()).dt.total_seconds().values // 60 - 555).astype(int)
    ch = ch.assign(r=rows)
    for (k, s), g in ch.groupby(["strike", "side"], sort=False):
        a = np.full((5, 375), np.nan)
        r = g.r.values
        ok = (r >= 0) & (r < 375)
        for i, c in enumerate(("open", "high", "low", "close", "volume")):
            a[i, r[ok]] = g[c].values[ok].astype(float)
        out[(float(k), s)] = a
    return out


def day_table(und: str, M: pd.DataFrame, ch: pd.DataFrame) -> pd.DataFrame:
    M = M.sort_values("ts").reset_index(drop=True)
    if len(M) != 375:
        return pd.DataFrame()
    lot, step, bse = K.LOT[und], K.STEP[und], und in K.BSE
    c = np.where(M.flag.isin(["ok", "stale"]), M.close.values.astype(float), np.nan)
    exp_day = bool(M.is_expiry_day.iloc[0])
    sq = K.ROW_1505 if exp_day else K.ROW_1510
    con = contracts(ch)
    atm = M.atm_strike.values.astype(float)
    rows = np.array(K.RULE_ROWS)
    out = dict(row=rows, is_expiry=np.full(len(rows), exp_day), dte=np.full(len(rows), float(M.dte_days.iloc[0])))
    for h in K.HORIZONS:
        fut = np.array([c[t + h] if t + h < 375 else np.nan for t in rows])
        out[f"ret{h}"] = np.log(fut / c[rows]) * 100
    out["abs30"] = np.abs(out["ret30"])
    for arm in ARMS:
        side, kind, ex = arm.split("_")
        sgn = K.SIDES[side]
        fill, xp, why, cut = (np.full(len(rows), np.nan) for _ in range(4))
        for i, t in enumerate(rows):
            if not np.isfinite(atm[t]):
                continue
            strike = atm[t] - sgn * K.STRIKES[kind] * step            # 1-ITM: call one step below, put one above
            a = con.get((strike, "C" if sgn > 0 else "P"))
            if a is None:
                continue
            f, x, r, e, xr, best = simulate(a[0], a[1], a[2], a[3], a[4], int(t), sq, float(lot), ex == "x1",
                                            K.STOP_PCT, K.LOCK_ACT, K.LOCK_GIVE, K.TIME_STOP_MIN, K.TIME_STOP_GAIN)
            fill[i], xp[i], why[i] = f, x, r
            if np.isfinite(f):
                cut[i] = float(r in (R_LOCK, R_TIME) and np.isfinite(best) and (best - x) * lot >= K.CUT_SHORT_RS)
        out[f"net_{arm}"] = net_rs(fill, xp, lot, bse)
        out[f"why_{arm}"] = why
        out[f"cut_{arm}"] = cut
    return pd.DataFrame(out)


def _one(args):
    und, d = args
    M, ch = cube.load(K.CACHE.root, und, d, d)
    if M.empty or ch is None or ch.empty or not (M.chain_ok_share.fillna(0) > 0).any():
        return None
    t = day_table(und, M, ch)
    if t.empty:
        return None
    t.insert(0, "day", pd.Timestamp(d))
    return t


def build(und: str, part: str, workers: int = 3) -> Path:
    if part == "holdout":
        assert K.HOLD_FLAG.exists(), "the holdout is built only by the one-time holdout step (rules.py holdout)"
    days = [d for d in cube.built_days(K.CACHE.root, und) if K.part(d) == part]
    with ProcessPoolExecutor(workers) as ex:
        parts = [t for t in ex.map(_one, [(und, d) for d in days], chunksize=8) if t is not None]
    q = K.OUT / "outcomes" / part / f"{und}.parquet"
    q.parent.mkdir(parents=True, exist_ok=True)
    T = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    T.to_parquet(q, compression="zstd", index=False)
    print(f"{und} {part}: {T.day.nunique() if len(T) else 0} days, {len(T)} rows -> {q}")
    return q


def load(und: str, part: str = "design") -> pd.DataFrame:
    if part == "holdout":
        assert K.HOLD_FLAG.exists(), "holdout not opened"
    return pd.read_parquet(K.OUT / "outcomes" / part / f"{und}.parquet")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["build"])
    ap.add_argument("--und", nargs="*", default=list(K.UNDS))
    ap.add_argument("--part", default="design", choices=["design", "holdout"])
    ap.add_argument("--workers", type=int, default=3)
    a = ap.parse_args(argv)
    for u in a.und:
        build(u, a.part, a.workers)


if __name__ == "__main__":
    main()
