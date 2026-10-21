"""R46 amendment 3: "BTC options mean reversion: buy after a 25% drop" (another AI's claim), reproduced and diagnosed.

Their description: 15-minute bars of the BTC ATM call (and separately put) premium; buy at the bar close when the
premium fell >= 25% in that bar and is >= $50; sell 4 bars (60 min) later; $300 a trade; no stop; $5 cost. Their file
btc_atm_2y.parquet has 2.1 M rows, and their code does df.premium.pct_change() over the WHOLE frame and exits at row
idx + 4.

  literal()    their code on a frame like theirs: one row per minute per side (ATM call and ATM put interleaved,
               2 x ~1.1 M minutes), pct_change over the whole frame, exit at row idx + 4
  rolling15()  what their words describe: 15-minute closes of the ROLLING ATM series of one side, exit 4 bars later
               on the rolling series (still a different contract whenever the ATM strike or the expiry changed)
  classify()   for each signal: was the previous row the same contract? (other side = "mixed", other expiry =
               "roll", other strike = "switch", else "same"), and is the exit row the same contract as the entry row?

The honest re-test (held contract, real fills, stop + lock, costs, tax) uses the dip pools of panel.py.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import amend_cfg as A


def interleave(roll_c: pd.DataFrame, roll_p: pd.DataFrame, a: pd.Timestamp, b: pd.Timestamp) -> pd.DataFrame:
    """Their kind of file: both sides' rolling ATM rows, sorted by time (call before put within a minute), rows with
    no price left out."""
    df = pd.concat([roll_c, roll_p], ignore_index=True)
    df = df[(df.ts >= a) & (df.ts < b) & df.premium.notna()]
    return df.sort_values(["ts", "side"], kind="mergesort").reset_index(drop=True)


def classify(df: pd.DataFrame, i: np.ndarray, j: np.ndarray) -> np.ndarray:
    """Category of row j relative to row i (same length arrays): 'mixed' (other side), 'roll' (other expiry),
    'switch' (other strike), 'same' (one contract), 'none' (j out of range)."""
    n = len(df)
    s, e, k = df.side.to_numpy(), df.expiry.to_numpy(), df.strike.to_numpy(float)
    out = np.full(len(i), "none", dtype=object)
    ok = (j >= 0) & (j < n)
    ii, jj = i[ok], j[ok]
    cat = np.where(s[ii] != s[jj], "mixed", np.where(e[ii] != e[jj], "roll", np.where(k[ii] != k[jj], "switch",
                                                                                         "same")))
    out[ok] = cat
    return out


def their_trades(df: pd.DataFrame, side: str, step: int = A.DIP_HOLD_BARS) -> pd.DataFrame:
    """Their code on df (rows in their order): pct_change over the whole frame, signal on rows of `side` with a drop
    of 25%+ and premium >= $50, exit at row idx + step. No stop, $300 a trade, $5 cost. Every signal trades."""
    pr = df.premium.to_numpy(float)
    pct = df.premium.pct_change().to_numpy(float)
    sig = np.flatnonzero((df.side.to_numpy() == side) & (pct <= A.DIP_DROP) & (pr >= A.DIP_MIN_PREM))
    sig = sig[sig + step < len(df)]
    x = sig + step
    pnl = A.DIP_NOTIONAL * (pr[x] / pr[sig] - 1) - A.DIP_THEIR_COST
    ts = pd.DatetimeIndex(df.ts)
    return pd.DataFrame(dict(
        ts=ts[sig], entry=pr[sig], exit=pr[x], pnl=pnl, minutes=(ts[x] - ts[sig]) / pd.Timedelta(minutes=1),
        prev_cat=classify(df, sig, sig - 1), exit_cat=classify(df, sig, x)))


def literal(roll_c, roll_p, a, b) -> dict:
    df = interleave(roll_c, roll_p, a, b)
    return {"rows": int(len(df)), **{side: their_trades(df, side) for side in ("C", "P")}}


def rolling15(roll: pd.DataFrame, a, b) -> pd.DataFrame:
    """15-minute closes (the last minute of each bar) of one side's rolling series, then their rule on it."""
    r = roll[(roll.ts >= a) & (roll.ts < b)]
    r = r[r.ts.dt.minute % A.DIP_BAR_MIN == A.DIP_BAR_MIN - 1].sort_values("ts").reset_index(drop=True)
    r = r[r.premium.notna()].reset_index(drop=True)
    side = r.side.iloc[0] if len(r) else "C"
    return their_trades(r, side)


def summarise_their(T: pd.DataFrame) -> dict:
    """Their numbers (no stop, $5 cost), and how much comes from rows that were not one contract."""
    if not len(T):
        return dict(trades=0)
    clean = (T.prev_cat == "same") & (T.exit_cat == "same")
    eq = np.cumsum(T.pnl.to_numpy(float))
    dd = float((np.maximum.accumulate(np.r_[0.0, eq])[1:] - eq).max())
    out = dict(trades=int(len(T)), win=round(float((T.pnl > 0).mean()), 4), avg=round(float(T.pnl.mean()), 2),
               median=round(float(T.pnl.median()), 2), total=round(float(T.pnl.sum()), 2), max_dd=round(-dd, 2),
               minutes_to_exit=T.minutes.value_counts().sort_index().head(8).to_dict(),
               prev_cat={k: int(v) for k, v in T.prev_cat.value_counts().items()},
               exit_cat={k: int(v) for k, v in T.exit_cat.value_counts().items()},
               clean_trades=int(clean.sum()),
               clean_total=round(float(T.pnl[clean].sum()), 2),
               clean_avg=round(float(T.pnl[clean].mean()), 2) if clean.any() else None,
               clean_win=round(float((T.pnl[clean] > 0).mean()), 4) if clean.any() else None)
    tot = float(T.pnl.sum())
    out["share_of_total_not_clean"] = round(float(T.pnl[~clean].sum()) / tot, 4) if tot else None
    out["minutes_to_exit"] = {str(k): int(v) for k, v in out["minutes_to_exit"].items()}
    return out


def select_one_at_a_time(entries: pd.DatetimeIndex, pool: pd.DataFrame) -> pd.DataFrame:
    """One position at a time (per side and variant), no daily cap; a signal needs a fill (ok)."""
    P = pool[~pool.index.duplicated()]
    out, busy = [], pd.Timestamp.min
    for E in sorted(entries):
        if E not in P.index:
            continue
        r = P.loc[E]
        if not bool(r.ok) or E < busy:
            continue
        busy = r.exit_ts + pd.Timedelta(minutes=1)
        out.append(r)
    if not out:
        return pool.iloc[:0].copy()
    T = pd.DataFrame(out)
    T.index.name = "entry"
    return T


def size(pool: pd.DataFrame) -> pd.DataFrame:
    """$300 of premium a trade, in whole Delta lots (0.001 BTC)."""
    from . import r46cfg as K
    P = pool.copy()
    lots = np.floor(A.DIP_NOTIONAL / P.buy.to_numpy(float) / K.LOT_BTC + 1e-9)
    P["q"] = lots * K.LOT_BTC
    P["ok"] = P.filled.astype(bool) & (P.q > 0) & np.isfinite(P.sell.to_numpy(float))
    return P
