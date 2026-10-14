"""R30 data: one decision table per index (grid rows x features + labels), built from the lake and R29's tables.

    python3 research/hunt/r30/r30data.py chain [UND ...]     # option-chain features per minute (OI / IV / PCR ...)
    python3 research/hunt/r30/r30data.py feats [UND ...]     # 164 brain features + chain + calendar at grid rows
    python3 research/hunt/r30/r30data.py labels [UND ...]    # CE / PE nets (ATM X1, ATM X4, debit spread) per grid row
    python3 research/hunt/r30/r30data.py table               # joins + cross-index context -> <OUT>/table.parquet

F1's locked holdout (1 Jan 2023 - 30 Jun 2024) is dropped as soon as rows are read (index minutes, option rows) and
`check_day` refuses it anywhere a grid row or label is made. The brain's EMAs therefore run across that gap like
across a long holiday.
"""
from __future__ import annotations

import sys
from datetime import date

import numpy as np
import pandas as pd

import r30cfg as K
import r29data as D29
import sim as S29
import engine as EN29

from research.brain import calendar as CAL
from research.brain.features import REGISTRY, compute, load_all

AVAILABLE = {"minutes.open", "minutes.high", "minutes.low", "minutes.close", "minutes.vix", "minutes.day",
             "minutes.minute", "minutes.dte_days", "minutes.is_expiry_day", "minutes.events", "minutes.expiry"}


def brain_names() -> list[str]:
    load_all()
    return sorted(n for n, f in REGISTRY.items() if set(f.inputs) <= AVAILABLE)


def _keep(df: pd.DataFrame, day_col="day") -> pd.DataFrame:
    d = pd.to_datetime(df[day_col]).dt.date
    return df[d.map(K.allowed).values]


# ------------------------------------------------------------------------------------------------ minutes
def minutes(und: str, op) -> pd.DataFrame:
    """Cube-like minutes of the allowed periods: OHLC + VIX + days to expiry / expiry day / events."""
    M = _keep(D29.load_index(und))
    V = D29.index_minutes("INDIAVIX")[["ts", "close"]].rename(columns={"close": "vix"})
    M = M.merge(V, on="ts", how="left")
    M["vix_flag"] = np.where(np.isfinite(M.vix), "ok", "gap")
    days = sorted(pd.to_datetime(M.day.unique()).date)
    exps = sorted(date.fromordinal(d) for d in op.expiry) if op is not None else []
    nxt = {}
    j = 0
    for d in days:
        while j < len(exps) and exps[j] < d:
            j += 1
        nxt[d] = exps[j] if j < len(exps) else None
    dd = pd.to_datetime(M.day).dt.date
    e = dd.map(nxt)
    M["dte_days"] = [CAL.dte_days(a, b) for a, b in zip(dd, e)]
    M["is_expiry_day"] = (dd == e).values
    M["expiry"] = pd.to_datetime([pd.Timestamp(x) + pd.Timedelta(hours=15, minutes=30) if x else pd.NaT for x in e])
    ev = {d: ",".join(CAL.events_on(und, d)) for d in days}
    M["events"] = dd.map(ev).values
    return M.reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ chain features
CHAIN = ["atm_iv", "atm_iv_chg15", "iv_skew2", "pcr_oi", "pcr_oi_chg30", "oi_ce_chg30_pct", "oi_pe_chg30_pct",
         "oi_ce_atm_chg30_pct", "oi_pe_atm_chg30_pct", "straddle_pct", "straddle_vs_0920", "straddle_chg15_pct",
         "vol_ce_15", "vol_pe_15", "vol_ratio_15", "maxoi_call_dist_pct", "maxoi_put_dist_pct"]


def _shift(a: np.ndarray, k: int) -> np.ndarray:
    """(days, 375) -> value k minutes earlier in the same session (NaN before the open)."""
    out = np.full_like(a, np.nan)
    out[:, k:] = a[:, :-k]
    return out


def chain_features(und: str) -> pd.DataFrame:
    """Per (day, minute) chain features from the lake's rows of that minute and earlier minutes of the same day."""
    out = []
    for p in sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet")):
        yr = int(p.stem.split("_")[-1])
        if yr == 2023:
            continue                                                     # inside F1's holdout entirely
        o = D29._read(p, ["ts", "offset", "side", "strike", "spot", "close", "volume", "oi", "iv"])
        o["day"] = o.ts.dt.date
        o = o[o.day.map(K.allowed).values]
        if not len(o):
            continue
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= 12 * K.STEP[und]].drop_duplicates(["day", "m", "side", "strike"])
        days = sorted(o.day.unique())
        di = {d: i for i, d in enumerate(days)}
        o["di"] = o.day.map(di).astype(int)
        nd = len(days)

        def grid(mask, col, how="sum"):
            g = o[mask].groupby(["di", "m"])[col]
            s = getattr(g, how)()
            a = np.full((nd, K29N), np.nan)
            a[s.index.get_level_values(0), s.index.get_level_values(1)] = s.values
            return a
        C, P = o.side == "C", o.side == "P"
        spot = grid(o.offset == 0, "spot", "first")
        ivc, ivp = grid(C & (o.offset == 0), "iv", "mean"), grid(P & (o.offset == 0), "iv", "mean")
        atm_iv = np.nanmean(np.stack([ivc, ivp]), axis=0)
        skew = grid(P & (o.offset == -2), "iv", "mean") - grid(C & (o.offset == 2), "iv", "mean")
        oic, oip = grid(C, "oi"), grid(P, "oi")
        near = o.offset.abs() <= 2
        oica, oipa = grid(C & near, "oi"), grid(P & near, "oi")
        stc, stp = grid(C & (o.offset == 0), "close", "first"), grid(P & (o.offset == 0), "close", "first")
        strad = (stc + stp) / spot * 100
        vc, vp = grid(C, "volume"), grid(P, "volume")
        # max-OI strikes per minute
        mo = o.loc[o.groupby(["di", "m", "side"]).oi.idxmax().dropna().astype(int)]
        mxc, mxp = np.full((nd, K29N), np.nan), np.full((nd, K29N), np.nan)
        for side, arr in (("C", mxc), ("P", mxp)):
            s = mo[mo.side == side]
            arr[s.di.values, s.m.values] = s.strike.values
        with np.errstate(invalid="ignore", divide="ignore"):
            pcr = oip / oic
            vc15 = pd.DataFrame(vc).T.rolling(15, min_periods=1).sum().T.values
            vp15 = pd.DataFrame(vp).T.rolling(15, min_periods=1).sum().T.values
            feats = dict(
                atm_iv=atm_iv, atm_iv_chg15=atm_iv - _shift(atm_iv, 15), iv_skew2=skew,
                pcr_oi=pcr, pcr_oi_chg30=pcr - _shift(pcr, 30),
                oi_ce_chg30_pct=(oic / _shift(oic, 30) - 1) * 100, oi_pe_chg30_pct=(oip / _shift(oip, 30) - 1) * 100,
                oi_ce_atm_chg30_pct=(oica / _shift(oica, 30) - 1) * 100,
                oi_pe_atm_chg30_pct=(oipa / _shift(oipa, 30) - 1) * 100,
                straddle_pct=strad, straddle_vs_0920=strad / strad[:, [5]] - 1,
                straddle_chg15_pct=(strad / _shift(strad, 15) - 1) * 100,
                vol_ce_15=np.log1p(vc15), vol_pe_15=np.log1p(vp15), vol_ratio_15=np.log((vc15 + 1) / (vp15 + 1)),
                maxoi_call_dist_pct=(mxc / spot - 1) * 100, maxoi_put_dist_pct=(mxp / spot - 1) * 100)
        for i, d in enumerate(days):
            for m in K.GRID:
                out.append(dict(day=d, m=m, **{k: float(v[i, m]) for k, v in feats.items()}))
    return pd.DataFrame(out)


K29N = 375


# ------------------------------------------------------------------------------------------------ features at grid
def features(und: str) -> pd.DataFrame:
    op = D29.load_options(und)
    M = minutes(und, op)
    F = compute(M, None, und, brain_names())
    F["day"] = M.day.values
    F["m"] = M.minute.values
    F = F[F.m.isin(K.GRID)].copy()
    F["day"] = pd.to_datetime(F.day).dt.date
    assert not F.day.map(lambda d: K.F1_HOLD[0] <= d <= K.F1_HOLD[1]).any()
    return F.drop(columns=["ts"])


# ------------------------------------------------------------------------------------------------ labels
def _px(P, e, r):
    """The open of row r, else the last close before it (a missing print)."""
    if r < P.shape[1] and np.isfinite(P[0, r]):
        return P[0, r]
    c = P[3, e:r]
    c = c[np.isfinite(c)]
    return c[-1] if len(c) else np.nan


def _spread_trade(op, paths, und, d_ord, t, side, qty, sq, mult=1.0):
    """Debit spread: buy ATM, sell the strike 2 steps OTM; exits on the spread's closes (stop -40% of the debit,
    target +60%, 45 minutes), market orders at the next open, each leg with its own spread; square-off at sq."""
    k = S29.strike_for(op, d_ord, t, side, 0, K.STEP[und])
    if k is None:
        return np.nan, np.nan
    k2 = k + 2 * K.STEP[und] if side == "C" else k - 2 * K.STEP[und]
    A, B = paths.get(d_ord, side, k), paths.get(d_ord, side, k2)
    if A is None or B is None:
        return np.nan, np.nan
    e = t + 1
    if e >= sq or not (np.isfinite(A[0, e]) and np.isfinite(B[0, e])):
        return np.nan, np.nan

    def tk(P, r):
        v = np.nansum(P[4, max(0, r - 4):r + 1]) / qty
        return (1.0 if v >= 20 else 2.0 if v > 0 else 4.0) * mult * S29.TICK
    buy = round(A[0, e] * 1.0005, 2) + tk(A, t)
    sell = max(round(B[0, e] * 0.9995, 2) - tk(B, t), 0.0)
    debit = buy - sell
    if debit <= 0:
        return np.nan, np.nan
    xa, xb = A[0, e], B[0, e]
    r = sq
    for m in range(e, sq):
        if not (np.isfinite(A[3, m]) and np.isfinite(B[3, m])):
            continue
        val = A[3, m] - B[3, m]
        if val <= debit * 0.6 or val >= debit * 1.6 or m - e + 1 >= 45:
            r = m + 1
            break
    r = min(r, sq)
    xa, xb = _px(A, e, r), _px(B, e, r)
    if not (np.isfinite(xa) and np.isfinite(xb)):
        return np.nan, np.nan
    sa = max(round(xa * 0.9995, 2) - tk(A, r - 1), 0.0)
    bb = round(xb * 1.0005, 2) + tk(B, r - 1)
    gross = (sa - bb - debit) * qty
    bse = und in K.BSE
    ch = (S29.COSTS.charge(True, np.array([buy]), np.array([qty]), bse=bse)[0]
          + S29.COSTS.charge(False, np.array([sell]), np.array([qty]), bse=bse)[0]
          + S29.COSTS.charge(False, np.array([sa]), np.array([qty]), bse=bse)[0]
          + S29.COSTS.charge(True, np.array([bb]), np.array([qty]), bse=bse)[0])
    return gross - ch, debit * qty


def labels(und: str) -> pd.DataFrame:
    op = D29.load_options(und)
    if op is None:
        return pd.DataFrame()
    M = _keep(D29.load_index(und))
    grid = EN29.IndexGrid(M)
    paths = EN29.Paths(op)
    rec = []
    bse = und in K.BSE
    for d in sorted(grid.pos):
        if not K.allowed(d) or d.toordinal() not in op.atm:
            continue
        K.check_day(d)
        d_ord, qty, sq = d.toordinal(), K.lot(und, d), S29.sq_row(op, d.toordinal())
        for m in K.GRID:
            r = dict(day=d, m=m, qty=qty)
            lab = grid.labels(d, m, 1)
            r["mv30"], r["absmv30"] = lab["mv30"], abs(lab["mv30"]) if np.isfinite(lab["mv30"]) else np.nan
            for side, dirn in (("C", 1), ("P", -1)):
                res = EN29.trade_one(paths, op, und, d_ord, m, dirn, "ATM", ("X1", "X4"), qty)
                for x in ("X1", "X4"):
                    e, xp, xr, why, ph = res[x] if res else (np.nan, np.nan, -1, 0, np.nan)
                    r[f"{side}_{x}"] = float(S29.net(np.array([e]), np.array([xp]), np.array([qty]), bse)[0])
                    r[f"{side}_{x}_row"], r[f"{side}_{x}_post"], r[f"{side}_{x}_exit"] = xr, ph, xp
                    r[f"{side}_{x}_entry"] = e
                r[f"{side}_SPR"], r[f"{side}_SPR_debit"] = _spread_trade(op, paths, und, d_ord, m, side, qty, sq)
                r2 = EN29.trade_one(paths, op, und, d_ord, m, dirn, "ATM", ("X1",), qty, 2.0)
                r[f"{side}_X1_2x"] = (float(S29.net(np.array([r2["X1"][0]]), np.array([r2["X1"][1]]),
                                                    np.array([qty]), bse)[0]) if r2 else np.nan)
            rec.append(r)
    return pd.DataFrame(rec)


# ------------------------------------------------------------------------------------------------ table
def table() -> pd.DataFrame:
    parts = []
    ctx = {}
    for u in K.UNDS:
        F = pd.read_parquet(K.OUT / f"feats_{u}.parquet")
        ctx[u] = F[["day", "m", "ret_15m", "ret_day", "rib5_stack"]].rename(
            columns={"ret_15m": "x_ret_15m", "ret_day": "x_ret_day", "rib5_stack": "x_rib5_stack"})
    for i, u in enumerate(K.UNDS):
        F = pd.read_parquet(K.OUT / f"feats_{u}.parquet")
        Ch = pd.read_parquet(K.OUT / f"chain_{u}.parquet")
        L = pd.read_parquet(K.OUT / f"labels_{u}.parquet")
        for X in (F, Ch, L):
            X["day"] = pd.to_datetime(X.day).dt.date
        X = F.merge(Ch, on=["day", "m"], how="left").merge(L, on=["day", "m"], how="inner")
        other = "BANKNIFTY" if u == "NIFTY" else "NIFTY"
        c = ctx[other].copy()
        c["day"] = pd.to_datetime(c.day).dt.date
        X = X.merge(c, on=["day", "m"], how="left")
        X["und"] = u
        X["und_id"] = i
        parts.append(X)
    T = pd.concat(parts, ignore_index=True)
    assert not T.day.map(lambda d: K.F1_HOLD[0] <= d <= K.F1_HOLD[1]).any()
    T.to_parquet(K.OUT / "table.parquet")
    return T


if __name__ == "__main__":
    K.OUT.mkdir(parents=True, exist_ok=True)
    cmd, unds = sys.argv[1], sys.argv[2:] or K.UNDS
    if cmd == "table":
        T = table()
        print(T.shape, T.groupby("und").size().to_dict())
    for u in unds if cmd != "table" else []:
        f = {"chain": chain_features, "feats": features, "labels": labels}[cmd]
        X = f(u)
        X.to_parquet(K.OUT / f"{cmd}_{u}.parquet")
        print(cmd, u, X.shape, flush=True)
