"""R83 daily book (PREREG.md section 4: E5 OPEC+ follow, E8 Donchian-20 with agreement, E9 NG seasonality, E11 12-month
trend exits, E12 rollover week). Research only.

Conventions (NOTES_daily.md):
- Series: MCX near-month continuous daily futures (lake fut_daily_continuous, not back-adjusted) for CRUDE (CRUDEOILM,
  10 bbl) and NG (NATGASMINI, 250 mmBtu); COPPER = COMEX HG=F (Yahoo) x 2.20462 x previous USD/INR, 2,500 kg (proxy).
- Rolls: per calendar month, the row (day of month >= 10 crude / >= 15 NG) with the largest open-interest jump, if the
  jump is > 1.15x, is the first row of the next contract. A held position is closed at the previous close and re-opened at
  that row's open: the roll gap is not earned and each roll pays a full round trip (common.fut_net per segment).
- Decisions at a daily close (only data up to that close); fills at the next day's open. Stops are checked on daily
  closes against a level fixed from data up to the previous close, and fill at that close with stop slippage.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r83 import common as C

ROLL_DOM = {"CRUDE": 10, "NG": 15}
ROLL_MIN = 1.15


# ------------------------------------------------------------------------------------------------ market series
@dataclass
class Mkt:
    key: str                        # CRUDE / NG / COPPER (cost key)
    dates: np.ndarray               # datetime.date objects
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    roll: np.ndarray                # True: first row of a new contract
    lot: float
    atr14: np.ndarray = field(default=None)

    def __post_init__(self):
        if self.atr14 is None:
            self.atr14 = atr_roll(self.h, self.l, self.c, self.roll, 14)

    def idx(self, d) -> int:
        """Index of the first row on or after d."""
        return int(np.searchsorted(np.array(self.dates, dtype="datetime64[D]"), np.datetime64(d, "D")))


def atr_roll(h, l, c, roll, n=14):
    """Simple mean true range over n rows ending at each row; on a roll row the true range is high - low only."""
    pc = np.concatenate([[np.nan], c[:-1]])
    tr = np.maximum.reduce([h - l, np.abs(h - pc), np.abs(l - pc)])
    tr = np.where(roll | np.isnan(pc), h - l, tr)
    return pd.Series(tr).rolling(n, min_periods=n).mean().values


def mark_rolls(df: pd.DataFrame, key: str) -> np.ndarray:
    r = (df.oi.astype(float) / df.oi.astype(float).shift(1)).values
    dt = pd.to_datetime(df.date)
    mo = dt.dt.to_period("M").values
    dom = dt.dt.day.values
    out = np.zeros(len(df), dtype=bool)
    best = {}
    for i in range(1, len(df)):
        if dom[i] >= ROLL_DOM[key] and np.isfinite(r[i]) and r[i] > ROLL_MIN:
            if mo[i] not in best or r[i] > r[best[mo[i]]]:
                best[mo[i]] = i
    out[list(best.values())] = True
    return out


def mcx_mkt(key: str) -> Mkt:
    sym = {"CRUDE": "CRUDEOIL", "NG": "NATURALGAS"}[key]
    d = C.mcx_daily(sym)
    d = d[(d.close > 0) & (d.open > 0)].reset_index(drop=True)
    return Mkt(key, d.date.values, d.open.values.astype(float), d.high.values.astype(float), d.low.values.astype(float),
               d.close.values.astype(float), mark_rolls(d, key), C.LOT[key])


def copper_mkt() -> Mkt:
    y = C.yahoo_daily("HG=F")
    y = y[y.date >= date(2004, 1, 1)].dropna().reset_index(drop=True)      # USD/INR from Dec 2003
    inr = C.yahoo_daily("INR=X")
    inr = inr[(inr.close > 30) & (inr.close < 150)]
    s = pd.Series(inr.close.values, index=pd.to_datetime(inr.date))
    prev = s.reindex(pd.to_datetime(y.date) - pd.Timedelta(days=1), method="ffill").values   # strictly before the day
    f = 2.20462 * prev
    return Mkt("COPPER", y.date.values, y.open.values * f, y.high.values * f, y.low.values * f, y.close.values * f,
               np.zeros(len(y), dtype=bool), C.LOT["COPPER"])


# ------------------------------------------------------------------------------------------------ one position
def spell(m: Mkt, a: int, side: int, b: int, exit_at_open: bool, stop: bool = False, why: str = "") -> dict | None:
    """A position filled at open[a], closed at close[b] (or open[b] when exit_at_open), rolls split into segments."""
    if a >= len(m.c) or b < a or (exit_at_open and b == a):
        return None
    gross = net = 0.0
    start = m.o[a]
    for k in range(a + 1, b + 1):
        if m.roll[k]:
            g, n = C.fut_net(m.key, side, start, m.c[k - 1], start * m.lot)
            gross += g; net += n
            if exit_at_open and k == b:
                start = None
                break
            start = m.o[k]
    if start is not None:
        px = m.o[b] if exit_at_open else m.c[b]
        g, n = C.fut_net(m.key, side, start, px, start * m.lot, stop=stop)
        gross += g; net += n
    return {"entry_ts": pd.Timestamp(m.dates[a]), "exit_ts": pd.Timestamp(m.dates[b]), "side": int(side),
            "gross": float(gross), "net": float(net), "why": why, "a": int(a), "b": int(b)}


def run_stop(m: Mkt, a: int, side: int, init_mult: float | None, trail_mult: float | None, max_b: int | None,
             end_open: bool = False, atr_at: int | None = None) -> dict | None:
    """Fill at open[a]; stop level fixed from data to the previous close: initial = entry -/+ init_mult x ATR14 (ATR of
    row atr_at, default a-1), chandelier = best close since entry -/+ trail_mult x ATR14 of the previous row (never
    loosening). Exit at the first close beyond the stop (stop), else at max_b (close, or open when end_open)."""
    n = len(m.c)
    if a >= n:
        return None
    ai = a - 1 if atr_at is None else atr_at
    A0 = m.atr14[ai] if ai >= 0 else np.nan
    if not np.isfinite(A0):
        return None
    entry = m.o[a]
    lvl = entry - side * init_mult * A0 if init_mult is not None else (-np.inf if side > 0 else np.inf)
    best = None
    last = n - 1 if max_b is None else min(max_b, n - 1)
    for k in range(a, last + 1):
        if end_open and k == last and k > a:
            return spell(m, a, side, k, True, why="time")
        if side * (m.c[k] - lvl) <= 0:
            return spell(m, a, side, k, False, stop=True, why="stop")
        best = m.c[k] if best is None else (max(best, m.c[k]) if side > 0 else min(best, m.c[k]))
        if trail_mult is not None and np.isfinite(m.atr14[k]):
            t = best - side * trail_mult * m.atr14[k]
            lvl = max(lvl, t) if side > 0 else min(lvl, t)
    return spell(m, a, side, last, False, why="time" if max_b is not None else "data_end")


# ------------------------------------------------------------------------------------------------ indicators
def supertrend_dir(h, l, c, n=10, mult=3.0) -> np.ndarray:
    """Supertrend(10, 3) direction at each close (+1 / -1; 0 before it is defined). Wilder ATR."""
    pc = np.concatenate([[c[0]], c[:-1]])
    tr = np.maximum.reduce([h - l, np.abs(h - pc), np.abs(l - pc)])
    atr = np.full(len(c), np.nan)
    if len(c) > n:
        atr[n - 1] = tr[:n].mean()
        for i in range(n, len(c)):
            atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    hl2 = (h + l) / 2
    up, dn = hl2 - mult * atr, hl2 + mult * atr
    d = np.zeros(len(c), dtype=int)
    fu, fd = np.nan, np.nan
    for i in range(len(c)):
        if not np.isfinite(atr[i]):
            continue
        if not np.isfinite(fu):
            fu, fd, d[i] = up[i], dn[i], 1
            continue
        fu = max(up[i], fu) if c[i - 1] > fu else up[i]
        fd = min(dn[i], fd) if c[i - 1] < fd else dn[i]
        prev = d[i - 1] if d[i - 1] != 0 else 1
        if prev > 0 and c[i] < fu:
            d[i] = -1
        elif prev < 0 and c[i] > fd:
            d[i] = 1
        else:
            d[i] = prev
    return d


def trend50_asof(other: Mkt, d, strict: bool) -> int:
    """Sign of other's close minus its close 50 rows earlier, on the last row before d (strict) or on/before d."""
    ds = np.array(other.dates, dtype="datetime64[D]")
    k = int(np.searchsorted(ds, np.datetime64(d, "D"), side="left" if strict else "right")) - 1
    if k < 50:
        return 0
    return int(np.sign(other.c[k] - other.c[k - 50]))


# ------------------------------------------------------------------------------------------------ E8 Donchian
def donchian_trades(m: Mkt, filt: str = "none", other: Mkt | None = None, strict_other: bool = True, lo=None, hi=None,
                    n=20) -> list[dict]:
    """Close beyond the prior 20 closes' max/min -> next open, init stop 2 ATR, chandelier 3 ATR; one at a time."""
    st = supertrend_dir(m.h, m.l, m.c) if filt == "st" else None
    out, i = [], n
    N = len(m.c)
    while i < N - 1:
        d = m.dates[i]
        if lo is not None and d < lo:
            i += 1; continue
        if hi is not None and d > hi:
            break
        w = m.c[i - n:i]
        side = 1 if m.c[i] > w.max() else (-1 if m.c[i] < w.min() else 0)
        if side and filt == "st" and st[i] != side:
            side = 0
        if side and filt == "breadth" and trend50_asof(other, d, strict_other) != side:
            side = 0
        if side:
            t = run_stop(m, i + 1, side, 2.0, 3.0, None, atr_at=i)
            if t is not None:
                t["signal"] = str(d)
                out.append(t)
                i = t["b"] + 1
                continue
        i += 1
    return out


# ------------------------------------------------------------------------------------------------ E5 OPEC
OPEC = """2014-11-27 2016-09-28 2016-11-30 2016-12-10 2017-05-25 2017-11-30 2018-06-22 2018-12-07 2019-07-02 2019-12-06
2020-03-06 2020-04-12 2020-06-06 2020-12-03 2021-01-05 2021-03-04 2021-04-01 2021-07-18 2021-09-01 2021-10-04 2021-11-04
2021-12-02 2022-01-04 2022-02-02 2022-03-02 2022-03-31 2022-05-05 2022-06-02 2022-06-30 2022-08-03 2022-09-05 2022-10-05
2022-12-04 2023-04-02 2023-06-04 2023-11-30 2024-06-02 2024-12-05 2025-04-03 2025-05-31 2025-07-05 2025-08-03 2025-09-07
2025-10-05 2025-11-02 2025-11-30""".split()     # hunt/nn_crude/calendar_events.py (approximate, from memory)


def day_move_sign(m: Mkt, t: int) -> int:
    ref = m.o[t] if m.roll[t] else m.c[t - 1]
    return int(np.sign(m.c[t] - ref))


def opec_signal_rows(m: Mkt) -> list[int]:
    ds = np.array(m.dates, dtype="datetime64[D]")
    rows = []
    for s in OPEC:
        t = int(np.searchsorted(ds, np.datetime64(s, "D"), side="right"))     # first MCX day strictly after
        if 1 <= t < len(ds) - 1:
            rows.append(t)
    return sorted(set(rows))


def hold_trade(m: Mkt, t: int, side: int, hold: int) -> dict | None:
    """Signal at close t; fill open t+1; exit at the close of the hold-th held day or a 2 x ATR14 close stop."""
    if side == 0:
        return None
    tr = run_stop(m, t + 1, side, 2.0, None, t + hold, atr_at=t)
    if tr is not None:
        tr["signal"] = str(m.dates[t])
    return tr


def opec_trades(m: Mkt, hold: int) -> list[dict]:
    return [x for x in (hold_trade(m, t, day_move_sign(m, t), hold) for t in opec_signal_rows(m)) if x]


# ------------------------------------------------------------------------------------------------ E9 seasonality
def hh_monthly() -> pd.Series:
    y = C.yahoo_daily("NG=F")
    s = pd.Series(y.close.values, index=pd.to_datetime(y.date))
    mc = s.groupby(s.index.to_period("M")).last()
    return (mc / mc.shift(1) - 1).dropna()


def season_sign(ret: pd.Series, year: int, month: int, tmin: float | None) -> int:
    """Mean of month-of-year returns over years 2000..year-1 only; sign if |t| >= tmin (None: always)."""
    x = ret[(ret.index.month == month) & (ret.index.year < year) & (ret.index.year >= 2000)].values
    if len(x) < 5:
        return 0
    mu, sd = x.mean(), x.std(ddof=1)
    t = mu / (sd / np.sqrt(len(x))) if sd > 0 else 0.0
    if tmin is not None and abs(t) < tmin:
        return 0
    return int(np.sign(mu))


def month_rows(m: Mkt):
    """(first row, last row) of each calendar month."""
    per = pd.to_datetime(pd.Series(m.dates)).dt.to_period("M").values
    out = []
    i = 0
    while i < len(per):
        j = i
        while j + 1 < len(per) and per[j + 1] == per[i]:
            j += 1
        out.append((per[i], i, j))
        i = j + 1
    return out


def season_trades(m: Mkt, ret: pd.Series, tmin, fixed_side: int | None = None) -> list[dict]:
    out = []
    for p, a, b in month_rows(m):
        if a < 1:
            continue
        side = fixed_side if fixed_side is not None else season_sign(ret, p.year, p.month, tmin)
        if side:
            t = run_stop(m, a, side, 2.5, None, b, atr_at=a - 1)
            if t:
                t["signal"] = str(p)
                out.append(t)
    return out


# ------------------------------------------------------------------------------------------------ E11 trend
def tsm_signal(m: Mkt, k: int, lb: int = 252) -> int:
    return int(np.sign(m.c[k] - m.c[k - lb])) if k >= lb else 0


def period_last_rows(m: Mkt, freq: str) -> list[int]:
    p = pd.to_datetime(pd.Series(m.dates)).dt.to_period(freq).values
    return [i for i in range(len(p)) if i + 1 == len(p) or p[i + 1] != p[i]]


def trend_trades(m: Mkt, freq: str = "M", chand: float | None = None, start=None) -> list[dict]:
    """App TSM252: sign read on each period's last row, acted on at the next open; a flip or 0 closes. With chand, a
    3 x ATR chandelier stop (on closes) flattens the leg until the next period-end signal re-opens it. Spells = trades."""
    out = []
    ends = period_last_rows(m, freq)
    pos, a, best, lvl = 0, None, None, None
    ei = 0
    N = len(m.c)
    for k in range(N):
        if start is not None and m.dates[k] < start and pos == 0:
            if ei < len(ends) and ends[ei] == k:
                ei += 1
            continue
        if pos != 0 and chand is not None and k >= a:
            if side_hit(pos, m.c[k], lvl):
                t = spell(m, a, pos, k, False, stop=True, why="chandelier")
                if t: out.append(t)
                pos = 0
            else:
                best = m.c[k] if best is None else (max(best, m.c[k]) if pos > 0 else min(best, m.c[k]))
                if np.isfinite(m.atr14[k]):
                    tl = best - pos * chand * m.atr14[k]
                    lvl = tl if lvl is None else (max(lvl, tl) if pos > 0 else min(lvl, tl))
        if ei < len(ends) and ends[ei] == k:
            ei += 1
            s = tsm_signal(m, k)
            if k + 1 >= N:
                break
            if pos != 0 and s != pos:
                t = spell(m, a, pos, k + 1, True, why="flip")
                if t: out.append(t)
                pos = 0
            if pos == 0 and s != 0:
                pos, a, best = s, k + 1, None
                lvl = None
    if pos != 0:
        t = spell(m, a, pos, N - 1, False, why="data_end")
        if t: out.append(t)
    return out


def side_hit(side, close, lvl) -> bool:
    return lvl is not None and side * (close - lvl) <= 0


def month_pieces(m: Mkt, t: dict) -> list[dict]:
    """Split a long spell into calendar-month pieces (leg-months) so walk-forward folds and the final window get the P&L
    of the months they cover: daily marks (roll rows: open-to-close only), costs split half to the entry month and half
    to the exit month. Each piece keeps a, b (its rows) for twins."""
    a, b, sd = t["a"], t["b"], t["side"]
    exit_open = t["why"] == "flip"
    inc = {}
    for k in range(a, b + 1):
        if k == a:
            g = m.c[k] - m.o[k] if not (exit_open and k == b) else 0.0
        elif exit_open and k == b:
            g = 0.0 if m.roll[k] else m.o[k] - m.c[k - 1]
        elif m.roll[k]:
            g = m.c[k] - m.o[k]
        else:
            g = m.c[k] - m.c[k - 1]
        mo = (m.dates[k].year, m.dates[k].month)
        r = inc.setdefault(mo, [k, k, 0.0])
        r[1] = k
        r[2] += sd * g * m.lot
    keys = sorted(inc)
    tot_g = sum(v[2] for v in inc.values())
    cost = t["gross"] - t["net"]
    scale = t["gross"] / tot_g if abs(tot_g) > 1e-9 else 1.0      # tiny fill-convention gaps; keeps sums exact
    out = []
    for i, mo in enumerate(keys):
        k0, k1, g = inc[mo]
        c = (cost / 2 if i == 0 else 0.0) + (cost / 2 if i == len(keys) - 1 else 0.0)
        out.append(dict(t, entry_ts=pd.Timestamp(m.dates[k0]), exit_ts=pd.Timestamp(m.dates[k1]), a=int(k0), b=int(k1),
                        gross=float(g * scale), net=float(g * scale - c), piece_of=str(m.dates[a])))
    return out


# ------------------------------------------------------------------------------------------------ E12 rollover week
def roll_rows(m: Mkt) -> list[int]:
    return [i for i in range(len(m.roll)) if m.roll[i]]


def rollweek_trades(m: Mkt, side: int) -> list[dict]:
    """Expiry = the row before a roll row r; hold from open[r-5] to close[r-1], 2 x ATR14 close stop (ATR of r-6)."""
    out = []
    for r in roll_rows(m):
        if r - 6 < 0:
            continue
        t = run_stop(m, r - 5, side, 2.0, None, r - 1, atr_at=r - 6)
        if t:
            t["signal"] = str(m.dates[r - 6])
            out.append(t)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_totals(m: Mkt, trades: list[dict], kind: str, rng: np.random.Generator, n: int = C.N_TWINS,
                exclude: set | None = None, **kw) -> np.ndarray:
    """Re-run each trade's side and holding rule from a random row of the same calendar year (not in exclude).
    kind: 'stop2trail3' (Donchian), 'hold' (fixed max rows with 2 ATR, kw stop=), 'spell' (same length, no stop)."""
    years = np.array([d.year for d in m.dates])
    tot = np.zeros(n)
    for t in trades:
        a, b = t["a"], t["b"]
        L = max(b - a, 0)
        y = m.dates[a].year
        pool = np.nonzero(years == y)[0]
        pool = pool[(pool >= 21) & (pool + L < len(m.c))]
        if exclude:
            pool = np.array([p for p in pool if p not in exclude])
        if len(pool) == 0:
            continue
        picks = rng.choice(pool, size=n, replace=True)
        for j, p in enumerate(picks):
            if kind == "stop2trail3":
                x = run_stop(m, p, t["side"], 2.0, 3.0, None, atr_at=p - 1)
            elif kind == "hold":
                x = run_stop(m, p, t["side"], kw.get("init", 2.0), None, p + L, atr_at=p - 1)
            else:
                x = spell(m, p, t["side"], p + L, False) if L > 0 else None
            tot[j] += x["net"] if x else 0.0
    return tot
