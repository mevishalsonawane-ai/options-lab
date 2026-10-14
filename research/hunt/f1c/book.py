"""F1C trade book: rupees per trade after Delta India fees + GST, slippage, funding and India's crypto tax; TDS cash lock;
one position per rule and coin at a time; the 3%-a-day loss cap (PREREG section 4).

Tax reading A (the primary): 31.2% of each profitable trade's gain on the fill prices (fees not deductible), losses
never set off. Reading B (business income): 31.2% of each Indian financial year's (Apr-Mar) net after costs, losses
carried forward. TDS: 1% of every sell's value, refunded TDS_REFUND_DAYS later (a credit, not a cost).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import f1ccfg as C


def perp_costs(sym: str, stress: float = 1.0) -> tuple[float, float]:
    """(fee incl. GST, slippage) per side, fractions of notional."""
    return C.PERP_FEE * (1 + C.GST), C.PERP_SLIP[sym] * stress


def size_perp(sym: str, price_usd: np.ndarray, stop_frac: np.ndarray, account: float) -> np.ndarray:
    """Coins per trade: 1% of the account at the stop, notional <= 3x the account, whole Delta lots (0 = too small)."""
    notional_rs = np.minimum(C.RISK_PER_TRADE * account / stop_frac, C.MAX_LEVERAGE * account)
    lot = C.LOT_COIN[sym]
    with np.errstate(invalid="ignore", divide="ignore"):
        q = np.floor(notional_rs / C.INR_PER_USD / (price_usd * lot) + 1e-9) * lot
    return np.where(np.isfinite(q), q, 0.0)


def settlements(entry_ts, exit_ts) -> np.ndarray:
    """Funding settlements (00/08/16 UTC) in (entry, exit]."""
    a = pd.DatetimeIndex(entry_ts).values.astype("datetime64[m]").astype(np.int64)
    b = pd.DatetimeIndex(exit_ts).values.astype("datetime64[m]").astype(np.int64)
    return (np.floor_divide(b, 480) - np.floor_divide(a, 480)).clip(min=0).astype(float)


def perp_book(sym: str, ts: pd.DatetimeIndex, i0, w: dict, side, stop_frac, funding_at_entry,
              account: float = C.ACCOUNT, stress: float = 1.0) -> pd.DataFrame:
    """One row per simulated trade (perp.walk output w for entries at bars i0), before the one-at-a-time / day-cap
    selection."""
    side = np.asarray(side, float)
    fee, slip = perp_costs(sym, stress)
    ok = np.isfinite(w["entry"]) & np.isfinite(w["exit"])
    ix = np.where(ok, w["idx"], 0).astype(int)
    e_fill = w["entry"] * (1 + side * slip)
    x_fill = w["exit"] * (1 - side * slip)
    q = size_perp(sym, w["entry"], np.asarray(stop_frac, float), account)
    t_in = ts[np.asarray(i0, int)]
    t_out = ts[ix]
    gross = side * (x_fill - e_fill) * q * C.INR_PER_USD                     # rupees on the fills
    fees = fee * (np.abs(e_fill) + np.abs(x_fill)) * q * C.INR_PER_USD
    fund = side * np.nan_to_num(np.asarray(funding_at_entry, float)) * settlements(t_in, t_out) * w["entry"] * q \
        * C.INR_PER_USD
    net = gross - fees - fund
    tax_a = C.TAX * np.maximum(gross, 0.0)
    sell_val = np.where(side > 0, x_fill, e_fill) * q * C.INR_PER_USD
    B = pd.DataFrame(dict(t_in=t_in, t_out=t_out, side=side, entry=w["entry"], exit=w["exit"], qty=q,
                          notional=np.abs(e_fill) * q * C.INR_PER_USD, gross=gross, fees=fees, funding=fund, net=net,
                          tax_a=tax_a, net_a=net - tax_a, tds=C.TDS * np.abs(sell_val), reason=w["reason"],
                          peak_r=w["peak_r"], after_r=w["after_r"], stop_frac=stop_frac))
    return B[ok & (q > 0)].reset_index(drop=True)


def select(B: pd.DataFrame, account: float = C.ACCOUNT, max_per_day: int | None = None) -> pd.DataFrame:
    """Keep trades in time order: one open at a time (a signal while a trade is open is skipped), and none after the
    IST day's net loss reaches DAY_LOSS_CAP x account, nor beyond max_per_day a day."""
    if B.empty:
        return B
    B = B.sort_values("t_in", kind="stable").reset_index(drop=True)
    keep = np.zeros(len(B), bool)
    busy = pd.Timestamp.min
    day, day_pnl, day_n = None, 0.0, 0
    ist_day = (B.t_in + C.IST).dt.date.to_numpy()
    for i, (t0, t1, pnl) in enumerate(zip(B.t_in, B.t_out, B.net)):
        if ist_day[i] != day:
            day, day_pnl, day_n = ist_day[i], 0.0, 0
        if t0 < busy or day_pnl <= -C.DAY_LOSS_CAP * account or (max_per_day and day_n >= max_per_day):
            continue
        keep[i] = True
        busy = t1
        day_pnl += pnl
        day_n += 1
    return B[keep].reset_index(drop=True)


def tax_b(B: pd.DataFrame) -> float:
    """Reading B: 31.2% of each financial year's net (Apr-Mar), losses carried forward."""
    if B.empty:
        return 0.0
    fy = (B.t_out - pd.DateOffset(months=3)).dt.year
    carry, tax = 0.0, 0.0
    for _, v in B.groupby(fy).net.sum().sort_index().items():
        x = v + carry
        if x > 0:
            tax += C.TAX * x
            carry = 0.0
        else:
            carry = x
    return tax


def tds_locked(B: pd.DataFrame) -> dict:
    """Peak TDS cash locked (each withholding returns TDS_REFUND_DAYS later) and the first day it passes half the
    account."""
    if B.empty:
        return dict(peak=0.0, first_half_day=None)
    ev = pd.concat([pd.Series(B.tds.values, index=B.t_out.values),
                    pd.Series(-B.tds.values, index=(B.t_out + pd.Timedelta(days=C.TDS_REFUND_DAYS)).values)])
    lock = ev.sort_index().cumsum()
    half = lock[lock >= 0.5 * C.ACCOUNT]
    return dict(peak=float(lock.max()), first_half_day=str(half.index[0].date()) if len(half) else None)


def summary(B: pd.DataFrame, days_traded_span: int, account: float = C.ACCOUNT) -> dict:
    """Per-rule numbers for the report (PREREG step 4). days_traded_span = calendar days the rule could trade."""
    if B.empty:
        return dict(trades=0)
    ist = (B.t_in + C.IST).dt.date
    by_day = B.groupby(ist).net_a.sum()
    wins, losses = B.net[B.net > 0], B.net[B.net <= 0]
    lock_exits = B.reason == 1
    cut = lock_exits & (B.after_r >= 2.0)
    eq = by_day.cumsum()
    return dict(trades=int(len(B)), trades_per_day=len(B) / max(days_traded_span, 1), win_rate=float((B.net > 0).mean()),
                avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
                net_pre_tax=float(B.net.sum()), tax_a=float(B.tax_a.sum()), net_a=float(B.net_a.sum()),
                net_b=float(B.net.sum() - tax_b(B)), per_day_a=float(B.net_a.sum() / max(days_traded_span, 1)),
                per_trade_a=float(B.net_a.mean()), green_days=float((by_day > 0).mean()), days_with_trades=int(len(by_day)),
                worst_day=float(by_day.min()), max_dd=float((eq.cummax().clip(lower=0) - eq).max()),
                lock_exits=int(lock_exits.sum()), cut_short=int(cut.sum()), fees=float(B.fees.sum()),
                funding=float(B.funding.sum()), gross=float(B.gross.sum()), tds=tds_locked(B))
