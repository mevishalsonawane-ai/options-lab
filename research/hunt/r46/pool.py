"""R46 trade pools: the outcome of buying the ATM call (or put) at EVERY 5-minute entry minute, with every exit and
fill model. The rule's trades are rows of the pool picked by its signals; the random twins draw other rows of the same
IST days. Units: USD per 1 Delta lot (0.001 BTC); x CLAIM_CONTRACTS for the other AI's size.

Fill models (PREREG section 6):
  naive    the first real trade (volume > 0) of the strike in the 5 minutes from the entry minute: its candle close,
           no spread; the exit walk starts the minute after it
  real     MAIN: the option's price ONE MINUTE LATER (the close of the next minute) plus half the modelled spread;
           the walk starts the minute after that
  stress   as real with the spread x 1.5
Exits sell at the level (or the gap open) less half the spread, a tick and 1% (market orders), never below 0.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import data as D
from . import r46cfg as K
from .exits import Exit, walk

MODES = ("naive", "real", "stress")


def half_spread(mid, spot, mult=1.0):
    a, b = K.SPREAD_AB
    return np.maximum((a * spot + b * np.maximum(mid, 0.0)) * mult, K.TICK) / 2


def fee(price, spot, q, rule):
    """Delta India fee for one side in USD: min(rate x notional, cap x premium) x 1.18."""
    rate, cap = rule
    return np.minimum(rate * spot * q, cap * np.maximum(price, 0.0) * q) * (1 + K.GST)


def option_pool_day(day: D.OptDay, spot_open: pd.Series, typ: str, exits: dict[str, Exit]) -> pd.DataFrame:
    """Every entry minute that uses this expiry: pick, fill, walk, cost. One row per (entry, mode, exit)."""
    ent = D.entries_for_expiry(day.E)
    rows = []
    rule = D.fee_rule(day.E)
    q = K.LOT_BTC
    G = len(day.grid)
    so = spot_open.reindex(day.grid).to_numpy(float)
    for E in ent:
        i0 = day.idx(E)
        s = so[i0] if 0 <= i0 < G else np.nan
        if not np.isfinite(s):
            continue
        k, f = day.pick(typ, s, i0)
        if k is None:
            rows.append((E, np.nan, -1))
            continue
        rows.append((E, k, f))
    if not rows:
        return pd.DataFrame()
    R = pd.DataFrame(rows, columns=["entry", "strike", "fidx"])
    ok = R.strike.notna().to_numpy()
    out = []
    W = K.HOLD_MIN + 1
    for mode in MODES:
        shift = 0 if mode == "naive" else 1
        mult = K.STRESS_SPREAD if mode == "stress" else 1.0
        fi = R.fidx.to_numpy() + shift                       # the fill minute
        valid = ok & (fi + 1 < G)
        n = len(R)
        mid = np.full(n, np.nan)
        O = np.full((n, W), np.nan)
        H, L = O.copy(), O.copy()
        sp_path = np.full((n, W), np.nan)
        for r in np.flatnonzero(valid):
            px, _ = day.arr[typ][R.strike.iat[r]]
            mid[r] = px[fi[r], 3]
            a, b = fi[r] + 1, min(fi[r] + 1 + W, G)
            O[r, :b - a], H[r, :b - a], L[r, :b - a] = px[a:b, 0], px[a:b, 1], px[a:b, 2]
            sp_path[r, :b - a] = so[a:b]
        valid &= np.isfinite(mid) & (mid > 0)
        s_fill = np.where(valid, so[np.clip(fi, 0, G - 1)], np.nan)
        buy = mid + (0.0 if mode == "naive" else half_spread(mid, s_fill, mult))
        for name, ex in exits.items():
            m = valid.copy()
            px_out = np.full(n, np.nan)
            why = np.full(n, -1)
            col = np.full(n, -1)
            peak = np.full(n, np.nan)
            if m.any():
                p, w, c, pk = walk(ex, mid[m], O[m], H[m], L[m], K.HOLD_MIN)
                px_out[m], why[m], col[m], peak[m] = p, w, c, pk
            s_out = sp_path[np.arange(n), np.clip(col, 0, W - 1)]
            s_out = np.where(np.isfinite(s_out), s_out, s_fill)
            if mode == "naive":
                sell = px_out
            else:
                sell = np.maximum(px_out - half_spread(px_out, s_out, mult) - K.TICK - K.EXIT_SLIP_FRAC * px_out, 0.0)
            gross = (sell - buy) * q
            fees = fee(buy, s_fill, q, rule) + fee(sell, s_out, q, rule)
            net = gross - fees
            tax = K.TAX * np.maximum(gross, 0.0)
            exit_ts = day.grid[np.clip(fi + 1 + np.maximum(col, 0), 0, G - 1)]
            out.append(pd.DataFrame(dict(
                entry=R.entry, expiry=day.E, side=typ, mode=mode, exit=name, ok=m, strike=R.strike,
                fill_ts=day.grid[np.clip(fi, 0, G - 1)], exit_ts=exit_ts, mid=mid, buy=buy, sell=sell, spot=s_fill,
                spot_out=s_out,                     # amendment 1 (15 Oct): lets the costs be re-done per broker
                reason=why, peak=peak, gross=gross, fees=fees, net=net, tax=tax, net_after=net - tax,
                tds=K.TDS * sell * q)))
    return pd.concat(out, ignore_index=True)


def perp_pool(perp: pd.DataFrame, entries: pd.DatetimeIndex, side: int, ex: Exit) -> pd.DataFrame:
    """Long (+1) or short (-1) 1 BTC of the perp at the OPEN of the entry minute (plus slippage), the same stop + lock
    (fractions of the entry price) and the 60-minute time exit. Shorts walk the mirrored path (2 x entry - price)."""
    idx = perp.index
    o, h, l = (perp[c].to_numpy(float) for c in ("open", "high", "low"))
    pos = idx.get_indexer(entries)
    n, W = len(entries), K.HOLD_MIN + 1
    ref = np.full(n, np.nan)
    O = np.full((n, W), np.nan)
    H, L = O.copy(), O.copy()
    for r, p in enumerate(pos):
        if p < 0 or p + W > len(o):
            continue
        ref[r] = o[p]
        O[r], H[r], L[r] = o[p:p + W], h[p:p + W], l[p:p + W]
    ok = np.isfinite(ref)
    if side < 0:
        O, H, L = 2 * ref[:, None] - O, 2 * ref[:, None] - L, 2 * ref[:, None] - H
    # column 0 is the entry minute itself (filled at its open); the time exit is the open 60 minutes later
    px = np.full(n, np.nan)
    why = np.full(n, -1)
    col = np.full(n, -1)
    if ok.any():
        Om = O[ok].copy()
        Om[:, 0] = ref[ok]                                       # no gap check against the fill's own open
        p, w, c, _ = walk(ex, ref[ok], Om, H[ok], L[ok], K.HOLD_MIN)
        px[ok], why[ok], col[ok] = p, w, c
    exit_px = px if side > 0 else 2 * ref - px
    slip = K.PERP_SLIP
    buy_in = ref * (1 + side * slip)
    out_px = exit_px * (1 - side * slip)
    gross = side * (out_px - buy_in)
    fees = K.PERP_FEE * (1 + K.GST) * (buy_in + out_px)
    net = gross - fees
    tax = K.TAX * np.maximum(gross, 0.0)
    sale = np.where(side > 0, out_px, buy_in)
    exit_ts = entries + pd.to_timedelta(np.maximum(col, 0), unit="min")
    return pd.DataFrame(dict(entry=entries, side="L" if side > 0 else "S", mode="real", exit="P1", ok=ok,
                             exit_ts=exit_ts, buy=buy_in, sell=out_px, reason=why, gross=gross, fees=fees, net=net,
                             tax=tax, net_after=net - tax, tds=K.TDS * sale))
