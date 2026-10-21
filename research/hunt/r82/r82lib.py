"""R82 shared harness (PREREG.md, 18 Oct 2026): data loaders (rupee proxies of the MCX GOLDM / SILVERM futures), the app's
MCX costs and paper slippage, indicators, the daily and hourly trade simulators, random twins, metrics and
Benjamini-Hochberg. Research only; nothing here touches the app.

Every price used by a decision at bar t comes from bars up to t (the look-ahead tests in test_r82.py check it).
"""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

DATA = Path(os.environ.get("R82_DATA", "/dev/shm/r82"))
HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
SEED = 82
N_TWINS = 500

# ------------------------------------------------------------------------------------------------ contracts and money
OZ_G = 31.1035
# metal -> (MCX contract, units per lot of the quoted price, grams per quoted unit)
LOT = {"gold": ("GOLDM", 10, 10.0), "silver": ("SILVERM", 5, 1000.0)}
DUTY_CUT = date(2024, 7, 23)          # Union Budget 2024: customs duty on gold / silver to 6%


def duty(d) -> float:
    return 1.10 if pd.Timestamp(d).date() < DUTY_CUT else 1.06


def rs_factor(metal: str, d, inr: float) -> float:
    """USD an ounce -> the MCX quote in rupees (GOLDM per 10 g, SILVERM per kg) on day d at USDINR inr."""
    grams = LOT[metal][2]
    return inr * duty(d) * grams / OZ_G


# ------------------------------------------------------------------------------------------------ costs (McxCosts.kt)
BROKERAGE_CAP, FUT_BROKERAGE_RATE, FUT_CTT, FUT_TXN = 20.0, 0.0003, 0.0001, 0.000021
SEBI_PER_RUPEE, FUT_STAMP, GST = 10.0 / 1_00_00_000, 0.00002, 0.18
SLIP, STOP_SLIP = 0.0003, 0.0010       # PaperSpread.MCX_FUTURE half-spread; desktop stop slippage 10 bps
CARRY = 0.06                            # a year, of the lot value: longs pay, shorts receive (daily book)


def leg_charge(buy: bool, turnover: float) -> float:
    """McxCosts.charge for one futures leg (rupees, unrounded)."""
    if not (turnover > 0):
        return 0.0
    brokerage = min(BROKERAGE_CAP, turnover * FUT_BROKERAGE_RATE)
    ctt = 0.0 if buy else turnover * FUT_CTT
    txn = turnover * FUT_TXN
    sebi = turnover * SEBI_PER_RUPEE
    stamp = turnover * FUT_STAMP if buy else 0.0
    return brokerage + ctt + txn + sebi + stamp + (brokerage + txn + sebi) * GST


def round_trip_charges(side: int, entry_rs: float, exit_rs: float, units: int) -> float:
    """Charges of a futures round trip: a long buys at entry and sells at exit, a short the reverse."""
    t_in, t_out = entry_rs * units, exit_rs * units
    if side > 0:
        return leg_charge(True, t_in) + leg_charge(False, t_out)
    return leg_charge(False, t_in) + leg_charge(True, t_out)


def fill(px: float, side_of_order: int, stop: bool = False) -> float:
    """A fill moved against the order: a buy pays more, a sell gets less."""
    s = STOP_SLIP if stop else SLIP
    return px * (1 + s * side_of_order)


# ------------------------------------------------------------------------------------------------ indicators
def wilder_atr(h, l, c, n):
    h, l, c = map(np.asarray, (h, l, c))
    pc = np.r_[np.nan, c[:-1]]
    tr = np.nanmax(np.vstack([h - l, np.abs(h - pc), np.abs(l - pc)]), axis=0)
    out = np.full(len(c), np.nan)
    if len(c) < n:
        return out
    out[n - 1] = np.mean(tr[:n])
    for i in range(n, len(c)):
        out[i] = (out[i - 1] * (n - 1) + tr[i]) / n
    return out


def supertrend_dir(h, l, c, n=10, mult=3.0):
    """+1 / -1 Supertrend direction at each bar's close (R73's definition: Wilder ATR, multiplier 3); 0 before warm-up."""
    h, l, c = map(np.asarray, (h, l, c))
    atr = wilder_atr(h, l, c, n)
    hl2 = (h + l) / 2
    up, dn = hl2 - mult * atr, hl2 + mult * atr
    d = np.zeros(len(c), dtype=int)
    fu, fd = np.nan, np.nan
    for i in range(len(c)):
        if np.isnan(atr[i]):
            continue
        if np.isnan(fu):
            fu, fd, d[i] = up[i], dn[i], 1
            continue
        fu = max(up[i], fu) if c[i - 1] > fu else up[i]
        fd = min(dn[i], fd) if c[i - 1] < fd else dn[i]
        prev = d[i - 1] if d[i - 1] != 0 else 1
        if prev < 0 and c[i] > fd:
            d[i] = 1
        elif prev > 0 and c[i] < fu:
            d[i] = -1
        else:
            d[i] = prev
    return d


def donchian(h, l, n):
    """Highest high / lowest low of the n bars BEFORE each bar (NaN until n earlier bars exist)."""
    hh = pd.Series(h).rolling(n).max().shift(1).values
    ll = pd.Series(l).rolling(n).min().shift(1).values
    return hh, ll


def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


# ------------------------------------------------------------------------------------------------ data
def _yahoo(name: str) -> pd.DataFrame:
    j = json.loads((DATA / "yahoo" / f"{name}.json").read_text())["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    df = pd.DataFrame({k: q[k] for k in ("open", "high", "low", "close")}, index=pd.to_datetime(j["timestamp"], unit="s"))
    return df.dropna()


def daily_close(name: str) -> pd.Series:
    """A Yahoo daily close by its own trading date (INR=X stamps 23:00 / 00:00 London, the others 04:00 / 05:00 UTC)."""
    df = _yahoo(name)
    d = (df.index + pd.Timedelta(hours=2)).normalize()
    s = pd.Series(df.close.values, index=d)
    return s[~s.index.duplicated(keep="last")].sort_index()


def inr_before(days: pd.DatetimeIndex) -> np.ndarray:
    """USDINR known before each day's open: the last INR=X close strictly before that date."""
    inr = daily_close("INR=X")
    pos = inr.index.searchsorted(days, side="left") - 1
    out = np.where(pos >= 0, inr.values[np.clip(pos, 0, None)], np.nan)
    return out


def load_daily(metal: str) -> pd.DataFrame:
    """Daily rupee proxy OHLC of the MCX quote (UTC day, weekdays): Dukascopy spot x USDINR (previous close) x duty."""
    sym = "xau" if metal == "gold" else "xag"
    parts = [pd.read_csv(DATA / f"{sym}_d1.csv")]
    p26 = DATA / f"{sym}_d1_2026.csv"
    if p26.exists():
        parts.append(pd.read_csv(p26))
    df = pd.concat(parts)
    df["date"] = pd.to_datetime(df["date"])
    df = df.drop_duplicates("date").sort_values("date").set_index("date")
    df = df[df.volume > 0]
    inr = inr_before(df.index)
    f = np.array([rs_factor(metal, d, x) for d, x in zip(df.index, inr)])
    out = pd.DataFrame({"usd_close": df.close.values}, index=df.index)
    for k in ("open", "high", "low", "close"):
        out[k] = df[k].values * f
    out["inr"] = inr
    return out.dropna()


def us_dst(ts) -> bool:
    """US daylight time on this date (2nd Sunday of March to 1st Sunday of November)."""
    d = pd.Timestamp(ts)
    y = d.year
    mar = pd.Timestamp(y, 3, 1)
    s2 = mar + pd.Timedelta(days=(6 - mar.weekday()) % 7 + 7)
    nov = pd.Timestamp(y, 11, 1)
    s1 = nov + pd.Timedelta(days=(6 - nov.weekday()) % 7)
    return bool(s2 <= d.normalize() < s1)


def flat_hour(day) -> int:
    """UTC hour whose bar OPEN is the MCX flat time: 17 (22:30 IST, US summer), 18 (23:30 IST, US winter)."""
    return 17 if us_dst(day) else 18


ENTRY_FIRST_HOUR = 4                    # 09:30 IST


def load_hourly(metal: str) -> pd.DataFrame:
    """Yahoo 1-hour COMEX front month (GC=F / SI=F), UTC bar starts, in rupees per MCX quote (previous day's USDINR)."""
    df = _yahoo("GC=F_1h" if metal == "gold" else "SI=F_1h")
    df = df[df.index.minute == 0]
    df = df[~df.index.duplicated(keep="last")].sort_index()
    days = df.index.normalize()
    inr = inr_before(days)
    f = np.array([rs_factor(metal, d, x) for d, x in zip(days, inr)])
    out = pd.DataFrame({k: df[k].values * f for k in ("open", "high", "low", "close")}, index=df.index)
    out["usd_open"], out["usd_close"] = df.open.values, df.close.values
    return out.dropna()


# ------------------------------------------------------------------------------------------------ trades and metrics
@dataclass
class Trade:
    metal: str
    side: int
    entry_t: pd.Timestamp
    exit_t: pd.Timestamp
    entry: float
    exit: float
    why: str
    units: int
    carry: float = 0.0
    bars: int = 0
    stop_dist: float = 0.0            # intraday: entry stop distance as a fraction of the entry price
    plan: int = 0                     # daily twins: the holding length in bars

    @property
    def gross(self) -> float:
        return self.side * (self.exit - self.entry) * self.units

    @property
    def charges(self) -> float:
        return round_trip_charges(self.side, self.entry, self.exit, self.units)

    @property
    def net(self) -> float:
        return self.gross - self.charges + self.carry


def carry_rs(side: int, entry_rs: float, units: int, t0, t1) -> float:
    days = max((pd.Timestamp(t1) - pd.Timestamp(t0)).days, 0)
    return -side * CARRY * entry_rs * units * days / 365.0


def summary(trades: list[Trade]) -> dict:
    n = len(trades)
    if n == 0:
        return dict(trades=0, net=0.0, win=None, avg_win=None, avg_loss=None, max_dd=0.0, worst=None, by_metal={})
    tr = sorted(trades, key=lambda t: t.exit_t)
    net = np.array([t.net for t in tr])
    eq = np.cumsum(net)
    peak = np.maximum.accumulate(np.r_[0.0, eq])[1:]
    dd = float(np.min(np.minimum(eq - peak, 0)))
    w, l = net[net > 0], net[net <= 0]
    by = {}
    for m in ("gold", "silver"):
        x = [t.net for t in tr if t.metal == m]
        by[m] = dict(trades=len(x), net=round(float(np.sum(x)), 0))
    longs = [t.net for t in tr if t.side > 0]
    shorts = [t.net for t in tr if t.side < 0]
    whys = {}
    for t in tr:
        whys[t.why] = whys.get(t.why, 0) + 1
    return dict(trades=n, net=round(float(net.sum()), 0), win=round(float((net > 0).mean()), 3),
                avg_win=round(float(w.mean()), 0) if len(w) else None, avg_loss=round(float(l.mean()), 0) if len(l) else None,
                max_dd=round(dd, 0), worst=round(float(net.min()), 0), by_metal=by,
                longs=dict(trades=len(longs), net=round(float(np.sum(longs)), 0)),
                shorts=dict(trades=len(shorts), net=round(float(np.sum(shorts)), 0)), exits=whys)


def top5_removed(trades: list[Trade]) -> float:
    net = sorted((t.net for t in trades), reverse=True)
    return float(sum(net[5:]))


def twin_p(real: float, twins: np.ndarray) -> float:
    return float((1 + np.sum(twins >= real)) / (len(twins) + 1))


def bh(pvals: dict) -> dict:
    """Benjamini-Hochberg q-values for {test: p}."""
    keys = sorted(pvals, key=lambda k: pvals[k])
    m = len(keys)
    q = {}
    prev = 1.0
    for rank in range(m, 0, -1):
        k = keys[rank - 1]
        prev = min(prev, pvals[k] * m / rank)
        q[k] = prev
    return q
