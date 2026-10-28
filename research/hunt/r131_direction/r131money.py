"""R131 money check (PREREG section 7): 1 lot the call's way, real charges and fills.

Intraday: buy the ATM option of the called side at the decision bar's close and sell it at the horizon's end
(Fills('liq'), Costs('dated') through R123's helpers). Overnight: the near-month index future (bhavcopy)."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

import r131cfg as K

_STT_FUT = ((date(2023, 4, 1), 0.0001), (date(2024, 10, 1), 0.000125), (date(9999, 1, 1), 0.0002))


def stt_fut(d: date) -> float:
    for until, r in _STT_FUT:
        if d < until:
            return r
    return _STT_FUT[-1][1]


def fut_charges(entry: float, exit_: float, lot: int, direction: int, d_in: date, d_out: date) -> float:
    """All charges of one futures round trip (Rs). direction +1 long (buy then sell), -1 short (sell then buy)."""
    buy_px, sell_px = (entry, exit_) if direction > 0 else (exit_, entry)
    sell_day = d_out if direction > 0 else d_in
    bv, sv = buy_px * lot, sell_px * lot
    brok = 20.0 * 2
    exch = (bv + sv) * K.FUT_EXCH
    sebi = (bv + sv) * 10.0 / 1e7
    stt = sv * stt_fut(sell_day)
    stamp = bv * 0.00002
    return brok + exch + sebi + stt + stamp + 0.18 * (brok + exch + sebi)


def fut_trade(entry: float, exit_: float, lot: int, direction: int, d_in: date, d_out: date) -> float | None:
    if not (np.isfinite(entry) and np.isfinite(exit_) and entry > 0 and exit_ > 0):
        return None
    gross = direction * (exit_ - entry) * lot
    slip = (entry + exit_) * K.FUT_SLIP * lot
    return float(gross - slip - fut_charges(entry, exit_, lot, direction, d_in, d_out))


def fut_contract(fut: pd.DataFrame, und: str, d: pd.Timestamp, tday: pd.Timestamp):
    """Entry close on d and exit open/close on tday of the nearest futures expiry after tday."""
    f = fut[(fut.und == und) & (fut.expiry > tday)]
    a = f[f.date == d]
    if a.empty:
        return None
    e = a.expiry.min()
    a = a[a.expiry == e].iloc[0]
    b = f[(f.date == tday) & (f.expiry == e)]
    if b.empty:
        return None
    b = b.iloc[0]
    px = lambda r, k: float(r[k]) if r[k] > 0 else float(r["settle"])  # noqa: E731
    return {"entry": px(a, "close"), "open": float(b["open"]) if b["open"] > 0 else np.nan, "close": px(b, "close")}


def opt_trade(a: np.ndarray, t: int, end: int, lot: int, d: date, und: str, fills, charge) -> dict | None:
    """a: one contract's (N, 5) bars [open, high, low, close, volume], NaN where no traded print. Buy at bar t's close,
    sell at bar end's close; None (skipped) when either minute has no traded print."""
    if a is None or end <= t or end >= a.shape[0]:
        return None
    pi, pe = a[t, 3], a[end, 3]
    if not (np.isfinite(pi) and pi > 0 and np.isfinite(pe) and pe > 0):
        return None
    v_in = np.nansum(a[max(t - 4, 0):t + 1, 4]) / lot
    v_out = np.nansum(a[max(end - 4, 0):end + 1, 4]) / lot
    b = float(np.asarray(fills.buy(pi, v_in)).reshape(-1)[0])
    s = float(np.asarray(fills.sell(pe, v_out)).reshape(-1)[0])
    net = (s - b) * lot - charge(True, b, lot, d, und) - charge(False, s, lot, d, und)
    return {"buy": b, "sell": s, "net": float(net)}


def summarise(tr: pd.DataFrame) -> dict:
    if tr.empty:
        return {"trades": 0}
    m = tr.groupby(pd.to_datetime(tr.day).dt.to_period("M")).net.sum()
    return {"trades": int(len(tr)), "win": float((tr.net > 0).mean()), "mean": float(tr.net.mean()),
            "total": float(tr.net.sum()), "worst_month": float(m.min()), "skipped": int(tr.attrs.get("skipped", 0))}
