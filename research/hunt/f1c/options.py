"""F1C options: Delta Exchange India BTC/ETH daily options from the options lake's 1-minute trade candles.

    python -m research.hunt.f1c.options prep      -> <cache>/f1c/opt/<COIN>/<YYYY-MM-DD>.parquet (never git)

A daily option settles at 12:00 UTC (17:30 IST). Boss's window for IST date D is 18:30 UTC on D-1 to 03:30 UTC on D,
so the option that expires at 12:00 UTC on D has 8.5-17.5 hours left during the window. `prep` keeps, per expiry day,
the minutes from 18:00 UTC on D-1 (30 minutes before the window, for the "traded in the last 30 minutes" check) to
03:40 UTC on D, strikes within 6% of spot, and each contract's first candle (its listing, for the 15-minute rule).

Candles are trades: a minute without a trade repeats the last price with volume 0 (OPTIONS_README.md). Fills use the
candle close as the mid and RC2's spread model on top (rc2.costs); the exits are RC2's (rc2.exits, stop + lock).
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from research.brain.crypto import cube
from research.hunt.rc2 import config as RC
from research.hunt.rc2 import costs as rc2costs
from research.hunt.rc2.exits import DIRECTIONAL, run as run_exit

from . import f1ccfg as C

COIN = {"BTCUSDT": "BTC", "ETHUSDT": "ETH"}
EXPIRY_H = 12
KEEP_FROM = pd.Timedelta(hours=18)          # expiry - 18h = 18:00 UTC the day before
KEEP_TO = pd.Timedelta(hours=8, minutes=20)  # expiry - 8h20 = 03:40 UTC
STRIKE_BAND = 0.06
LISTING_WAIT = pd.Timedelta(minutes=15)     # RC2 amendment 4: no entry in a strike's first 15 minutes
TRADED_WITHIN = pd.Timedelta(minutes=30)
JUMP_LIMIT = 2.0                            # nor on a candle more than 2x / under 0.5x the previous close
EXITS = DIRECTIONAL                          # s40_step, s40_trail, s25_step, s25_trail
# Entry fill. As run (PREREG results): the signal minute's candle close. STRICT (review, 14 Oct 2026, post hoc): the
# next minute's close, and no entry when that option's last trade is older than STRICT_STALE_MIN (a design-only check).
ENTRY = {"strict": False}
STRICT_STALE_MIN = 2


def opt_dir(sym: str) -> Path:
    return C.OUT / "opt" / COIN[sym]


def prep(sym: str, frm: date, to: date) -> int:
    coin = COIN[sym]
    src = C.CACHE.options / "delta_india" / "candles" / coin
    spot = cube.load(C.CACHE.root, sym, frm - pd.Timedelta(days=1), to)[["ts", "close"]].set_index("ts").close
    n = 0
    for f in sorted(src.glob("*/*.parquet")):
        d = date.fromisoformat(f.stem)
        if not (frm <= d <= to):
            continue
        out = opt_dir(sym) / f"{d}.parquet"
        if out.exists():
            n += 1
            continue
        E = pd.Timestamp(d) + pd.Timedelta(hours=EXPIRY_H)
        D = pd.read_parquet(f, columns=["timestamp", "symbol", "option_type", "strike", "expiry_ts", "open", "high",
                                        "low", "close", "volume"])
        if D.empty:
            continue
        D = D[D.expiry_ts == int(E.value // 10**6)]
        first = D.groupby("symbol").timestamp.min().rename("listed")
        lo, hi = int((E - KEEP_FROM).value // 10**6), int((E - KEEP_TO).value // 10**6)
        D = D[(D.timestamp >= lo) & (D.timestamp <= hi)]
        s_mid = spot.get(E - pd.Timedelta(hours=13), np.nan)
        if not np.isfinite(s_mid) or D.empty:
            continue
        D = D[(D.strike / s_mid - 1).abs() <= STRIKE_BAND].merge(first, left_on="symbol", right_index=True)
        out.parent.mkdir(parents=True, exist_ok=True)
        D.to_parquet(out, compression="zstd", index=False)
        n += 1
    return n


class Day:
    """One expiry day's window candles: per (type, strike) minute arrays on the window grid."""

    def __init__(self, sym: str, d: date):
        self.sym, self.coin, self.d = sym, COIN[sym], d
        self.E = pd.Timestamp(d) + pd.Timedelta(hours=EXPIRY_H)
        self.grid = pd.date_range(self.E - KEEP_FROM, self.E - KEEP_TO, freq="min")
        p = opt_dir(sym) / f"{d}.parquet"
        self.ok = p.exists()
        self.arr, self.listed, self.strikes = {}, {}, {"C": [], "P": []}
        if not self.ok:
            return
        D = pd.read_parquet(p)
        D["ts"] = pd.to_datetime(D.timestamp, unit="ms")
        for (typ, K), g in D.groupby(["option_type", "strike"]):
            g = g.drop_duplicates("ts").set_index("ts").reindex(self.grid)
            self.arr[(typ, K)] = g[["open", "high", "low", "close", "volume"]].to_numpy(float)
            self.listed[(typ, K)] = pd.to_datetime(g.listed.dropna().iloc[0], unit="ms") if g.listed.notna().any() \
                else self.E
            self.strikes[typ].append(K)
        for t in self.strikes:
            self.strikes[t].sort()

    def pos(self, T: pd.Timestamp) -> int:
        return int(self.grid.get_indexer([T])[0])

    def tradable(self, key, i: int, T: pd.Timestamp) -> bool:
        a = self.arr[key]
        if T - self.listed[key] < LISTING_WAIT:
            return False
        c = a[i, 3]
        if not np.isfinite(c) or c <= 0:
            return False
        w = int(TRADED_WITHIN / pd.Timedelta(minutes=1))
        v = a[max(0, i - w):i + 1, 4]
        if not (np.nan_to_num(v) > 0).any():
            return False
        prev = a[i - 1, 3] if i > 0 else np.nan
        if np.isfinite(prev) and prev > 0 and not (1 / JUMP_LIMIT <= c / prev <= JUMP_LIMIT):
            return False
        return True

    def atm(self, typ: str, spot: float, i: int, T: pd.Timestamp):
        best = None
        for K in self.strikes[typ]:
            if self.tradable((typ, K), i, T) and (best is None or abs(K - spot) < abs(best - spot)):
                best = K
        if best is None or abs(best - spot) / spot > 0.01:
            return None
        return best


def trade(day: Day, T_sig: pd.Timestamp, side: int, spot: float, deadline: pd.Timestamp, ex, account: float,
          spread_mult: float = 1.0, fee_rate: float = RC.FEE_RATE, smodel=None) -> dict | None:
    """Buy the at-the-money call (side +1) or put (-1) at the close of the signal minute T_sig (the last trade price,
    the mid proxy, plus half the modelled spread); walk the minutes after it with RC2's stop + lock exit until the
    deadline (09:00 IST); sell at the level less half the spread, a tick and 1%. Returns one trade row, or None."""
    smodel = smodel or rc2costs.spread_model()
    typ = "C" if side > 0 else "P"
    i = day.pos(T_sig)
    if i < 0:
        return None
    if ENTRY["strict"]:
        i += 1
        T_sig = T_sig + pd.Timedelta(minutes=1)
        if i >= len(day.grid):
            return None
    K = day.atm(typ, spot, i, T_sig)
    if K is None:
        return None
    a = day.arr[(typ, K)]
    mid = a[i, 3]
    if ENTRY["strict"]:
        v = np.nan_to_num(a[max(0, i - STRICT_STALE_MIN):i + 1, 4])
        if not (v > 0).any():
            return None
    buy = rc2costs.buy_fill(day.coin, mid, spot, smodel, spread_mult)
    lots = int(np.floor(C.OPT_PREMIUM_FRAC * account / C.INR_PER_USD / (buy * RC.LOT[day.coin])))
    if lots < 1:
        return None
    j = day.pos(deadline)
    seg = a[i + 1:]
    o, h, l, c = seg[:, 0].copy(), seg[:, 1].copy(), seg[:, 2].copy(), seg[:, 3].copy()
    # a minute with no candle at all keeps the last price (Delta's own convention for minutes without trades)
    for arr in (o, h, l, c):
        s = pd.Series(arr).ffill().fillna(mid)
        arr[:] = s.to_numpy()
    dl = (j - (i + 1)) if j >= 0 else len(o)
    lvl, why, k, peak = run_exit(ex, mid, o, h, l, c, dl)
    sell = rc2costs.sell_fill(day.coin, lvl, spot, smodel, spread_mult, market=True)
    q = lots * RC.LOT[day.coin]
    fee_in = rc2costs.fee(day.coin, buy, spot, lots, fee_rate)
    fee_out = rc2costs.fee(day.coin, sell, spot, lots, fee_rate)
    gross = (sell - buy) * q * C.INR_PER_USD
    fees = (fee_in + fee_out) * C.INR_PER_USD
    net = gross - fees
    # after a lock exit: did the option go on to 2x the exit price before the deadline?
    after = np.nanmax(h[k + 1:dl]) if why == "lock" and k + 1 < dl else np.nan
    t_out = day.grid[min(i + 1 + k, len(day.grid) - 1)]
    return dict(t_in=T_sig if ENTRY["strict"] else T_sig + pd.Timedelta(minutes=1), t_out=t_out, side=float(side), strike=K, mid=mid, buy=buy,
                sell=sell, lots=lots, gross=gross, fees=fees, funding=0.0, net=net, tax_a=C.TAX * max(gross, 0.0),
                net_a=net - C.TAX * max(gross, 0.0), tds=C.TDS * sell * q * C.INR_PER_USD,
                reason={"stop": 0.0, "lock": 1.0, "time": 2.0, "data_end": 3.0}[why], peak_r=peak,
                after_r=(after / lvl) if np.isfinite(after) and lvl > 0 else np.nan, notional=buy * q * C.INR_PER_USD)


def main(argv=None):
    a = argv or sys.argv[1:]
    if a and a[0] == "prep":
        for s in C.OPTION_COINS:
            n = prep(s, date(2024, 1, 1), date(2026, 3, 31))
            print(f"{s}: {n} expiry days prepared", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
