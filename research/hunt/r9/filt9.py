"""R9 shared filter machinery: the arms' trade lists (no re-simulation) and kept-vs-skipped statistics."""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

H = os.path.join(R.SCR, "hunt")
ARM_UND = {"orb": "BANKNIFTY", "orb_fresh": "BANKNIFTY", "orb_sweep": "BANKNIFTY", "range_fade": "BANKNIFTY",
           "liq_bn": "BANKNIFTY", "liq_fin": "FINNIFTY", "liq_midcp": "MIDCPNIFTY"}
TREND = ["orb", "orb_fresh", "liq_bn", "liq_fin", "liq_midcp"]
RANGE = ["range_fade", "orb_sweep"]
HSP = {"BANKNIFTY": 0.0016, "FINNIFTY": 0.0042}


def load_arms():
    a = pd.read_parquet(f"{H}/h19/trades.parquet")
    a = a[a.arm.isin(["orb", "orb_fresh", "orb_sweep", "range_fade", "liq_fin"])].copy()
    hs = a.und.map(HSP).values
    sp = hs * (a.entry.values + a.exit.values) * a.lot.values
    a["net_app"] = a.net
    a["net"] = a.net_app - sp
    a["net15"] = a.net_app - 1.5 * sp
    a = a[["arm", "und", "day", "entry_min", "side", "net", "net15", "gross"]]
    b = pd.read_parquet(f"{H}/h24/trades24.parquet")
    b = b[b.kappa == 0.02]
    key = ["und", "book", "day", "entry_min", "side", "cand"]
    x1 = b[b.model == "flat_x1"].set_index(key)
    x15 = b[b.model == "flat_x1.5"].set_index(key)
    x1 = x1[x1.lots > 0].copy()
    x1["net15"] = x15.net.reindex(x1.index).values
    x1 = x1.reset_index()
    x1["arm"] = np.where(x1.und == "BANKNIFTY", "liq_bn", "liq_midcp")
    x1 = x1[["arm", "und", "day", "entry_min", "side", "net", "net15", "gross"]]
    T = pd.concat([a, x1], ignore_index=True)
    T["day"] = pd.to_datetime(T.day).dt.date
    T["side"] = np.sign(T.side).astype(int)        # +1 CE / -1 PE (h19 & h24 both use +1/-1)
    return T.reset_index(drop=True)


def sessions(arm, T, period):
    P = R.panel(ARM_UND[arm])
    t = T[T.arm == arm]
    d0, d1 = t.day.min(), t.day.max()
    m = P["isdes"] if period == "design" else P["ishold"]
    return [d for d, ok, mm in zip(P["days"], P["ok"], m) if ok and mm and d0 <= d <= d1]


def stats(t, keep, ses, B=2000, seed=11):
    """t: trades of one arm in one period; keep: bool array. Returns dict + daily improvement series (by session)."""
    n = len(t)
    nses = max(len(ses), 1)
    k, s = t[keep], t[~keep]
    d = dict(trades=n, kept=int(keep.sum()), skipped=int((~keep).sum()),
             all_trade=t.net.mean() if n else np.nan, kept_trade=k.net.mean() if len(k) else np.nan,
             skip_trade=s.net.mean() if len(s) else np.nan,
             all_win=(t.net > 0).mean() if n else np.nan, kept_win=(k.net > 0).mean() if len(k) else np.nan,
             skip_win=(s.net > 0).mean() if len(s) else np.nan,
             all_day=t.net.sum() / nses, kept_day=k.net.sum() / nses, kept_day15=k.net15.sum() / nses,
             all_day15=t.net15.sum() / nses)
    d["improve_day"] = d["kept_day"] - d["all_day"]
    if len(k) >= 3 and len(s) >= 3:
        d["diff_trade"], d["p"] = R.clboot_diff(k.net.values, k.day.values, s.net.values, s.day.values, B=B, seed=seed)
    else:
        d["diff_trade"], d["p"] = np.nan, np.nan
    yrs = pd.Series(-s.net.values).groupby([x.year for x in s.day]).sum() if len(s) else pd.Series(dtype=float)
    allyrs = sorted({x.year for x in ses})
    pos = sum(1 for y in allyrs if yrs.get(y, 0.0) > 0)
    d["yrs_pos"] = f"{pos}/{len(allyrs)}"
    d["yrs_ok"] = pos > len(allyrs) / 2
    sk = pd.Series(-s.net.values).groupby(s.day.values).sum()
    daily = pd.Series(0.0, index=pd.Index(ses)).add(sk, fill_value=0.0).reindex(ses).fillna(0.0)
    return d, daily


def reality_check(D, B=1000, seed=3):
    """White RC (studentised max) over columns of D (sessions x filters) of daily improvements. Returns p per column."""
    M = D.values
    n = M.shape[0]
    mu = M.mean(0)
    sd = M.std(0, ddof=1) + 1e-9
    t = mu / (sd / np.sqrt(n))
    idx = R.L.stationary_boot_idx(n, B, 5, seed=seed)
    mx = np.empty(B)
    for b in range(B):
        mx[b] = np.max((M[idx[b]].mean(0) - mu) / (sd / np.sqrt(n)))
    return pd.Series([(mx >= x).mean() for x in t], index=D.columns)


def gate(df):
    return (df.q < 0.05) & (df.kept >= 100) & (df.improve_day > 0) & df.yrs_ok
