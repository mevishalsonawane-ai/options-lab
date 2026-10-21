"""OF1 mark-to-market maths (PREREG §3). Pure functions on tables; no I/O.

The participant files give each group's open contracts by product, not by underlying, expiry or strike. So a group's
day P&L is its net contracts times the market POOL's average MTM per contract:

    pool MTM per contract (t+1) = sum_c OIqty_c(t) * (settle_c(t+1) - settle_c(t)) / total contracts(t)
    group P&L (t+1)             = net_g(t) * pool MTM per contract (t+1)

OI in the bhavcopy is in units (index "shares"), so OIqty * price change is rupees and the lot never has to be known.
The sum of the four groups' nets is zero, so the four P&Ls sum to zero (a zero-sum check before costs).
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

KEY = ["und", "kind", "expiry", "strike"]
NEAR = 0.02                    # options bucket (b): |K/S - 1| <= 2%; (c) beyond


def spot_proxy(day: pd.DataFrame) -> dict[str, float]:
    """Each underlying's price at the close: the settle of its nearest-expiry future (the bhavcopy carries no spot before
    July 2024). None of the analysis reads it for anything but the options' moneyness bucket."""
    f = day[(day.kind == "FUT") & (day.settle > 0)]
    if f.empty:
        return {}
    f = f.sort_values("expiry").groupby("und").head(1)
    return dict(zip(f.und, f.settle.astype(float)))


def pool_step(prev: pd.DataFrame, cur: pd.DataFrame, d_prev: date) -> dict[str, float]:
    """Pool sums for the move from day `d_prev` (rows `prev`) to the next session (rows `cur`).

    Contracts that expired on `d_prev` (expiry <= d_prev) are settled and left out. A contract alive on `d_prev` with
    OI but absent the next day is counted in `lost_oi` (its MTM is unknown; it should not happen). Returns rupee sums:

      fut_oi_dS            sum OIqty(t) * dsettle over index futures
      fut_oi               sum OIqty(t) (units)
      fut_oi_dS_<UND>      the same per underlying (NIFTY, BANKNIFTY)
      opt_<CE|PE>_<all|near|far>_oi_dS, ..._oi    the same for options, by moneyness bucket at t
      fut_typ              sum OIqty(t+1) * (settle(t+1) - typical(t+1)), typical = (H+L+C)/3 of the day (traded rows)
      opt_<CE|PE>_typ      the same for options
      lost_oi              OI units with no next-day row
    """
    p = prev[(prev.expiry > d_prev) & (prev.oi_qty > 0)]
    j = p.merge(cur[KEY + ["settle"]].drop_duplicates(KEY), on=KEY, how="left", suffixes=("", "_n"), validate="many_to_one")
    if j.settle_n.isna().any():
        j = rematch(j, prev, cur)
    lost = j.settle_n.isna()
    out: dict[str, float] = {"lost_oi": float(j.loc[lost, "oi_qty"].sum())}
    j = j[~lost]
    dS = (j.settle_n - j.settle).astype(float)
    w = j.oi_qty.astype(float)
    fut = (j.kind == "FUT").values
    out["fut_oi_dS"] = float((w[fut] * dS[fut]).sum())
    out["fut_oi"] = float(w[fut].sum())
    for u in ("NIFTY", "BANKNIFTY"):
        m = fut & (j.und == u).values
        out[f"fut_oi_dS_{u}"] = float((w[m] * dS[m]).sum())
        out[f"fut_oi_{u}"] = float(w[m].sum())
    spot = spot_proxy(prev)
    s = j.und.map(spot).astype(float)
    mny = (j.strike / s - 1.0).abs()
    for k in ("CE", "PE"):
        base = (j.kind == k).values
        for b, m in (("all", base), ("near", base & (mny <= NEAR).values), ("far", base & (mny > NEAR).values)):
            out[f"opt_{k}_{b}_oi_dS"] = float((w[m] * dS[m]).sum())
            out[f"opt_{k}_{b}_oi"] = float(w[m].sum())
    c = cur[(cur.oi_qty > 0)]
    traded = c.contracts.fillna(0) > 0
    typ = (c.high + c.low + c.close) / 3.0
    diff = ((c.settle - typ) * c.oi_qty).where(traded, 0.0)
    out["fut_typ"] = float(diff[c.kind == "FUT"].sum())
    for k in ("CE", "PE"):
        out[f"opt_{k}_typ"] = float(diff[c.kind == k].sum())
    return out


def rematch(j: pd.DataFrame, prev: pd.DataFrame, cur: pd.DataFrame, days: int = 7) -> pd.DataFrame:
    """NSE sometimes re-dates live contracts (monthly expiry moved from Wednesday to Thursday on 1 Jan 2025, and to
    Tuesday from 1 Sep 2025): the same contract then appears under a new expiry date the next day. A contract with no
    next-day row takes the next-day settle of the contract with the same underlying, side and strike whose expiry is
    within `days` and did not exist the day before. Anything still unmatched stays NaN (counted as lost)."""
    miss = j.settle_n.isna()
    old = set(zip(prev.und, prev.kind, prev.expiry))
    new = cur[[(u, k, e) not in old for u, k, e in zip(cur.und, cur.kind, cur.expiry)]]
    if new.empty:
        return j
    look = {}
    for r in new[["und", "kind", "strike", "expiry", "settle"]].itertuples(index=False):
        look.setdefault((r.und, r.kind, r.strike), []).append((r.expiry, r.settle))
    vals = j.settle_n.to_numpy(copy=True)
    for i in np.flatnonzero(miss.to_numpy()):
        r = j.iloc[i]
        best = [(abs((e - r.expiry).days), s) for e, s in look.get((r.und, r.kind, r.strike), [])
                if abs((e - r.expiry).days) <= days]
        if best:
            vals[i] = min(best)[1]
    j = j.copy()
    j["settle_n"] = vals
    return j


def per_contract(pools: pd.DataFrame, wide: pd.DataFrame) -> pd.DataFrame:
    """Pool sums (index = the day the move ENDS, t+1; column `prev` = t) -> rupees per contract per product.

    total contracts at t come from the participant file's TOTAL row (`wide`, indexed by date). For the near/far buckets
    the per-contract value is the bucket's OI-weighted average move times the market's units per contract (PREREG §3).
    """
    t = wide.reindex(pd.to_datetime(pools["prev"]))
    tot_fut = t["TOTAL.fut_idx_long"].to_numpy(float)
    out = pd.DataFrame(index=pools.index)
    out["fut"] = pools.fut_oi_dS.to_numpy() / tot_fut
    t1 = wide.reindex(pools.index)
    out["fut_typ"] = pools.fut_typ.to_numpy() / t1["TOTAL.fut_idx_long"].to_numpy(float)
    for k, col in (("CE", "TOTAL.idx_call_long"), ("PE", "TOTAL.idx_put_long")):
        tot = t[col].to_numpy(float)
        upc = pools[f"opt_{k}_all_oi"].to_numpy() / tot              # units per contract, market average
        for b in ("all", "near", "far"):
            avg = pools[f"opt_{k}_{b}_oi_dS"].to_numpy() / pools[f"opt_{k}_{b}_oi"].replace(0, np.nan).to_numpy()
            out[f"{k}_{b}"] = avg * upc
        out[f"{k}_typ"] = pools[f"opt_{k}_typ"].to_numpy() / t1[col].to_numpy(float)
    return out


GROUPS = ("Client", "DII", "FII", "Pro")


def group_pnl(wide: pd.DataFrame, pc: pd.DataFrame, prev: pd.Series) -> pd.DataFrame:
    """Each group's rupee MTM on the day that ENDS at pc.index, from its positions at `prev` (the day before).

    Columns '<g>.fut', '<g>.fut2' (fut + the day's position change at settle minus typical price), '<g>.opt_<bucket>'
    (calls + puts), '<g>.call_<bucket>', '<g>.put_<bucket>'.
    """
    w0 = wide.reindex(pd.to_datetime(prev.values))
    w1 = wide.reindex(pc.index)
    out = pd.DataFrame(index=pc.index)
    for g in GROUPS:
        n0 = w0[f"{g}.fut_net"].to_numpy(float)
        n1 = w1[f"{g}.fut_net"].to_numpy(float)
        out[f"{g}.fut"] = n0 * pc.fut.to_numpy()
        out[f"{g}.fut2"] = out[f"{g}.fut"] + (n1 - n0) * pc.fut_typ.to_numpy()
        c0 = w0[f"{g}.call_net"].to_numpy(float)
        p0 = w0[f"{g}.put_net"].to_numpy(float)
        for b in ("all", "near", "far"):
            out[f"{g}.call_{b}"] = c0 * pc[f"CE_{b}"].to_numpy()
            out[f"{g}.put_{b}"] = p0 * pc[f"PE_{b}"].to_numpy()
            out[f"{g}.opt_{b}"] = out[f"{g}.call_{b}"] + out[f"{g}.put_{b}"]
    return out
