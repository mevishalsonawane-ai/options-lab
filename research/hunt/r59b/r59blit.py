"""R59b steps 1-2: their Bollinger rule, literally, on the ROLLING 15-minute ATM series, and where its profit comes from.

  bands()          Bollinger(20, 2) of the rolling close (pandas rolling mean / std, ddof 1, window includes bar t)
  their_trades()   close <= lower band and close >= 5; entry close[t], exit close[t+4] (can be the next session);
                   Rs 10,000 notional, fractional contracts, Rs 95; every signal trades (overlaps allowed)
  run_start()      first row of the run of rows on the current contract (expiry + strike)
  claim_checks()   their other claims: max drawdown, win % per quarter and per stock, trades a day
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r59bcfg as K
import r59lit as LT


def bands(close: pd.Series, n: int = K.BB_N, k: float = K.BB_K, ddof: int = K.BB_DDOF):
    mid = close.rolling(n).mean()
    sd = close.rolling(n).std(ddof=ddof)
    return mid - k * sd, mid, mid + k * sd


def run_start(b: pd.DataFrame) -> np.ndarray:
    """For each row, the index where the current unbroken run of rows on the same contract (expiry, strike) began."""
    e, s = b.expiry.to_numpy(), b.strike.to_numpy(float)
    new = np.r_[True, (e[1:] != e[:-1]) | (s[1:] != s[:-1])]
    idx = np.where(new, np.arange(len(b)), 0)
    return np.maximum.accumulate(idx)


def their_trades(b: pd.DataFrame, step: int = K.THEIR_STEP, ddof: int = K.BB_DDOF) -> pd.DataFrame:
    c = b.close.to_numpy(float)
    lower = bands(b.close, ddof=ddof)[0].to_numpy(float)
    sig = np.flatnonzero(np.isfinite(lower) & (c <= lower) & (c >= K.MIN_PREM))
    sig = sig[sig + step < len(b)]
    x = sig + step
    pnl = K.THEIR_NOTIONAL / c[sig] * (c[x] - c[sig]) - K.THEIR_COST
    rs = run_start(b)
    d = b.d.to_numpy()
    window_mixed = rs[sig] > sig - (K.BB_N - 1)
    return pd.DataFrame(dict(
        ts=b.ts.to_numpy()[sig], i=sig, entry=c[sig], exit=c[x], lower=lower[sig], pnl=pnl,
        drop_cat=LT.classify(b, sig, sig - 1), rec_cat=LT.classify(b, sig, x), window_mixed=window_mixed,
        one_contract=(~window_mixed) & (rs[x] <= sig), overnight_exit=d[x] != d[sig]))


def summary(T: pd.DataFrame) -> dict:
    if not len(T):
        return dict(trades=0)
    p = T.pnl
    tot = float(p.sum())
    out = dict(trades=int(len(T)), win=round(float((p > 0).mean()) * 100, 1), per_trade=round(float(p.mean()), 0),
               median=round(float(p.median()), 0), total=round(tot, 0))
    one = T.one_contract
    cats = {}
    for name, m in (("one_contract", one), ("window_mixed", T.window_mixed),
                    ("break_switch", T.drop_cat == "switch"), ("break_roll", T.drop_cat == "roll"),
                    ("rec_switch", T.rec_cat == "switch"), ("rec_roll", T.rec_cat == "roll"),
                    ("any_switch_or_roll", ~one), ("overnight_exit", T.overnight_exit)):
        s = float(p[m].sum())
        cats[name] = dict(trades=int(m.sum()), share_trades=round(float(m.mean()) * 100, 1),
                          profit=round(s, 0), share_profit=round(s / tot * 100, 1) if tot else None,
                          per_trade=round(float(p[m].mean()), 0) if m.any() else None,
                          win=round(float((p[m] > 0).mean()) * 100, 1) if m.any() else None)
    out["by_category"] = cats
    if "held" in T:
        h = T.held.dropna()
        out["held_contract_diag"] = dict(trades=int(len(h)), win=round(float((h > 0).mean()) * 100, 1) if len(h) else None,
                                         per_trade=round(float(h.mean()), 0) if len(h) else None,
                                         total=round(float(h.sum()), 0))
    return out


def max_drawdown(pnl_in_time_order: np.ndarray) -> float:
    cum = np.cumsum(pnl_in_time_order)
    peak = np.maximum.accumulate(np.r_[0.0, cum])[1:]
    return float(np.min(np.r_[0.0, cum - peak]))


def claim_checks(A: pd.DataFrame, n_dates: int) -> dict:
    A = A.sort_values("ts", kind="mergesort")
    q = pd.to_datetime(A.ts).dt.to_period("Q").astype(str)
    win_q = (A.pnl > 0).groupby(q).agg(["size", "mean"])
    win_s = (A.pnl > 0).groupby(A.sym).agg(["size", "mean"])
    return dict(max_drawdown=round(max_drawdown(A.pnl.to_numpy(float)), 0),
                trades_per_day=round(len(A) / n_dates, 1) if n_dates else None, dates=int(n_dates),
                win_by_quarter={k: dict(trades=int(v["size"]), win=round(float(v["mean"]) * 100, 1))
                                for k, v in win_q.iterrows()},
                win_by_stock={k: dict(trades=int(v["size"]), win=round(float(v["mean"]) * 100, 1))
                              for k, v in win_s.iterrows()})
