"""R78 engine: "Gautam Jha's Daily High/Low Reversal" on XAUUSD 1-minute BID bars (PREREG.md, 18 Oct 2026).

Signal source: XAUUSD itself. Everything here is pure: arrays in, trades out.

The short side is the mirror of the long side. Prices are moved into "direction space" (long: +price; short: -price), so
one routine finds both setups and one routine walks both exits:
  * signal space  = the BID chart (long: bid; short: -bid with high and low swapped);
  * entry fill    = signal-space open + E_ADD (long buys at the ask = bid + 0.30; short sells at the bid);
  * exit space    = the price the position is closed at (long: bid; short: -ask = -(bid + 0.30)), i.e. signal space
                    minus X_SUB (0 long, 0.30 short).
So a level read on the BID chart (stop, swing target) is moved into exit space by subtracting X_SUB, which is the
pre-registered "triggers on the BID level, fills at the ask" for a short.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

try:  # numba only makes the 1,000-twin runs fast; the logic is identical without it (tests run either way)
    from numba import njit
except ImportError:  # pragma: no cover
    def njit(*a, **k):
        if a and callable(a[0]):
            return a[0]
        return lambda f: f

SPREAD = 0.30      # ask = bid + 0.30 (the gold studies' spread)
COMM = 0.07        # $7 a 100 oz lot round trip, an ounce
SLIP = 0.05        # every stop-triggered fill (stop exits, V8's stop-order entry)
STOP_PAD = 0.10    # "just below the swing low"
MIN_R = 0.50       # a stop closer than this is spread-sized: not taken
LOCK_BE = 0.10     # the lock's first step: entry + $0.10
LOT = 100          # ounces in a standard lot
W0, W1 = 7 * 60, 18 * 60 + 59   # break bar start window (UTC minutes)
LAST_ENTRY = 19 * 60            # the entry bar must start by 19:00 UTC
CUT = 20 * 60 + 40              # time exit at the open of the 20:40 UTC bar
SWING_N = 60
MIN_PREV_MINUTES = 600

# exit kinds
EX_SWING, EX_R = 0, 1
# exit reasons
R_STOP, R_TARGET, R_TIME, R_EOD = 0, 1, 2, 3
REASONS = {R_STOP: "stop", R_TARGET: "target", R_TIME: "time 20:40", R_EOD: "data end"}


@dataclass(frozen=True)
class Variant:
    vid: str
    label: str
    margin: float = 0.0       # X as a fraction of the level (0.0003 = 0.03%)
    k: int = 3                # green candle within K bars of the break
    entry: str = "close"      # "close": next open after the confirming close; "stop": buy-stop at the green high
    exit_kind: int = EX_SWING
    r_mult: float = 0.0       # for EX_R
    lock: bool = False


VARIANTS = [
    Variant("V1", "Boss as written: close break, K 3, swing target"),
    Variant("V2", "green candle right after (K 1)", k=1),
    Variant("V3", "green candle within 10 bars (K 10)", k=10),
    Variant("V4", "break by 0.03% beyond the level", margin=0.0003),
    Variant("V5", "1.5 R target", exit_kind=EX_R, r_mult=1.5),
    Variant("V6", "2 R target", exit_kind=EX_R, r_mult=2.0),
    Variant("V7", "swing target + step profit lock", lock=True),
    Variant("V8", "buy-stop entry at the green high (no close needed)", entry="stop"),
]


# ----------------------------------------------------------------------------------------------------------------- data
class Bars:
    """Weekday BID minutes as arrays. `day` = UTC date as int days since epoch; `mod` = UTC minute of day."""

    def __init__(self, bid: pd.DataFrame):
        bid = bid[bid.index.dayofweek < 5].sort_index()
        bid = bid[~bid.index.duplicated()]
        self.idx = bid.index
        self.t = bid.index.values.astype("datetime64[m]")
        self.o, self.h, self.l, self.c = (bid[k].values.astype(float) for k in ("open", "high", "low", "close"))
        self.day = self.t.astype("datetime64[D]").astype(np.int64)
        self.mod = (bid.index.hour * 60 + bid.index.minute).values.astype(np.int64)
        self.n = len(self.t)


def read_bid(path) -> pd.DataFrame:
    df = pd.read_csv(path)
    ts = pd.to_datetime(df["timestamp"], unit="ms", utc=True).dt.tz_localize(None)
    df = df.assign(ts=ts).set_index("ts")[["open", "high", "low", "close"]].sort_index()
    return df[~df.index.duplicated()]


def prev_weekday(d: int) -> int:
    """Int day -> the previous weekday's int day (Monday -> Friday). 1970-01-01 was a Thursday (weekday 3)."""
    wd = (d + 3) % 7
    return d - 3 if wd == 0 else d - 1


def daily_levels(B: Bars):
    """Per bar: the previous UTC day's high and low (NaN when that weekday is missing or has < 600 minutes).
    Uses only bars of earlier days, so a day's levels are known only after it closed."""
    days, start = np.unique(B.day, return_index=True)
    end = np.r_[start[1:], B.n]
    hi = {int(d): float(B.h[s:e].max()) for d, s, e in zip(days, start, end) if e - s >= MIN_PREV_MINUTES}
    lo = {int(d): float(B.l[s:e].min()) for d, s, e in zip(days, start, end) if e - s >= MIN_PREV_MINUTES}
    pdh, pdl = np.full(B.n, np.nan), np.full(B.n, np.nan)
    for d, s, e in zip(days, start, end):
        p = prev_weekday(int(d))
        if p in hi:
            pdh[s:e], pdl[s:e] = hi[p], lo[p]
    return pdh, pdl


def side_arrays(B: Bars, side: int):
    """Signal-space OHLC (long: bid; short: -bid with high/low swapped)."""
    if side > 0:
        return B.o, B.h, B.l, B.c
    return -B.o, -B.l, -B.h, -B.c


# -------------------------------------------------------------------------------------------------------------- setups
@njit(cache=True)
def _find_setups(o, h, l, c, day, mod, level, k, stop_entry):
    """Long-space setups. Returns rows (break i, green g, entry bar e, entry price in signal space, swing low over i..g).
    One taken trade per day is decided later (R and room checks), so every confirmed setup in time order is returned,
    each scan resuming after the previous setup died or confirmed."""
    n = len(o)
    out = np.empty((0, 5))
    rows = []
    i = 1
    while i < n:
        lv = level[i]
        if (not np.isnan(lv)) and mod[i] >= 420 and mod[i] <= 1139 and c[i] < lv and c[i - 1] >= lv:
            found = False
            g = i + 1
            while g < n and g <= i + k and day[g] == day[i]:
                if c[g] > o[g]:
                    cf = g + 1
                    if cf < n and day[cf] == day[i]:
                        if not stop_entry:
                            if c[cf] > h[g]:
                                e = cf + 1
                                if e < n and day[e] == day[i] and mod[e] <= 1140:
                                    sl = l[i]
                                    for j in range(i, g + 1):
                                        if l[j] < sl:
                                            sl = l[j]
                                    rows.append((float(i), float(g), float(e), o[e], sl))
                                found = True
                        else:
                            if h[cf] > h[g] and mod[cf] <= 1140:
                                px = o[cf] if o[cf] > h[g] else h[g]
                                sl = l[i]
                                for j in range(i, g + 1):
                                    if l[j] < sl:
                                        sl = l[j]
                                rows.append((float(i), float(g), float(cf), px, sl))
                                found = True
                if found:
                    break
                g += 1
        i += 1
    if len(rows) == 0:
        return out
    res = np.empty((len(rows), 5))
    for r in range(len(rows)):
        for q in range(5):
            res[r, q] = rows[r][q]
    return res


# ---------------------------------------------------------------------------------------------------------------- exits
@njit(cache=True)
def walk(xo, xh, xl, xc, day, mod, e, entry_fill, stop, target, lock, r, first_bar_stop_only):
    """Exit-space walk from bar e (the entry bar). Returns (exit fill, exit bar, reason). Stop before target on the same
    bar; lock moves apply from the next bar; time exit at the 20:40 bar's open; data end -> last bar's close."""
    n = len(xo)
    d = day[e]
    j = e
    st = stop
    best_step = -1.0
    while j < n and day[j] == d:
        if mod[j] >= 1240:
            return xo[j], j, 2
        if xl[j] <= st:
            px = xo[j] if xo[j] < st else st
            return px - 0.05, j, 0
        if xh[j] >= target and not (first_bar_stop_only and j == e and xc[j] < target):
            return target, j, 1
        if lock:
            # step lock, known at this bar's close, used from the next bar: +1 R reached -> entry + $0.10; each further
            # 0.5 R -> the stop rises 0.5 R (1.5 R -> +0.5 R, 2 R -> +1 R, ...)
            fav = xh[j] - entry_fill
            if fav >= r:
                steps = np.floor((fav - r) / (0.5 * r) + 1e-9)
                if steps > best_step:
                    best_step = steps
                    ns = entry_fill + steps * 0.5 * r if steps >= 1 else entry_fill + 0.10
                    if ns > st:
                        st = ns
        j += 1
    return xc[j - 1], j - 1, 3


def exit_space(B: Bars, side: int):
    """Exit-space OHLC: long closes at the bid; a short buys back at the ask, i.e. -(bid + 0.30)."""
    if side > 0:
        return B.o, B.h, B.l, B.c
    return -(B.o + SPREAD), -(B.l + SPREAD), -(B.h + SPREAD), -(B.c + SPREAD)


def run_variant(B: Bars, v: Variant, side: int, pdh=None, pdl=None) -> pd.DataFrame:
    """All trades of one variant on one side (one per side per day)."""
    if pdh is None:
        pdh, pdl = daily_levels(B)
    so, sh, sl_, sc = side_arrays(B, side)
    base = pdl if side > 0 else -pdh
    level = base - v.margin * np.abs(base)
    setups = _find_setups(so, sh, sl_, sc, B.day, B.mod, level, v.k, v.entry == "stop")
    xo, xh, xl, xc = exit_space(B, side)
    e_add = SPREAD if side > 0 else 0.0
    x_sub = 0.0 if side > 0 else SPREAD
    # swing extreme over the 60 bars ending at the break bar (signal space)
    swing = pd.Series(sh).rolling(SWING_N, min_periods=1).max().values
    rows, taken_days = [], set()
    for i, g, e, px, swl in setups:
        i, g, e = int(i), int(g), int(e)
        d = int(B.day[e])
        if d in taken_days:
            continue
        entry_fill = px + e_add + (SLIP if v.entry == "stop" else 0.0)
        stop = swl - STOP_PAD - x_sub
        r = entry_fill - stop
        if r < MIN_R:
            continue
        if v.exit_kind == EX_SWING:
            target = swing[i] - x_sub
            if target <= entry_fill:
                continue
        else:
            target = entry_fill + v.r_mult * r
        xfill, xb, why = walk(xo, xh, xl, xc, B.day, B.mod, e, entry_fill, stop, target, v.lock, r, v.entry == "stop")
        assert B.day[xb] == B.day[e], "a trade was held overnight"
        net_oz = xfill - entry_fill - COMM
        taken_days.add(d)
        rows.append(dict(
            side="long" if side > 0 else "short", day=pd.Timestamp(B.t[e].astype("datetime64[D]")),
            break_time=pd.Timestamp(B.t[i]), green_time=pd.Timestamp(B.t[g]), entry_time=pd.Timestamp(B.t[e]),
            exit_time=pd.Timestamp(B.t[xb]), entry_mod=int(B.mod[e]),
            entry_px=side * (entry_fill), stop_px=side * (stop), target_px=side * target, prev_level=side * base[e],
            r=r, tgt_dist=target - entry_fill, exit_px=side * xfill, reason=REASONS[why],
            net=net_oz * LOT, r_mult=(xfill - entry_fill) / r))
    cols = ["side", "day", "break_time", "green_time", "entry_time", "exit_time", "entry_mod", "entry_px", "stop_px",
            "target_px", "prev_level", "r", "tgt_dist", "exit_px", "reason", "net", "r_mult"]
    return pd.DataFrame(rows, columns=cols)


# ---------------------------------------------------------------------------------------------------------------- twins
@njit(cache=True)
def _twin_books(xo, xh, xl, xc, day, mod, cand_start, cand_len, cand_idx, t_mod, t_r, t_dist, e_add, slip_in, lock,
                n_twins, seed):
    """Twin book nets ($/lot). For each real trade: a random weekday (of the period) with a bar at the same minute of
    day; entry at that bar's open, same R and target distance (exit space), same lock, same costs."""
    np.random.seed(seed)
    out = np.zeros(n_twins)
    m = len(t_mod)
    for b in range(n_twins):
        tot = 0.0
        for q in range(m):
            mm = t_mod[q]
            ln = cand_len[mm]
            if ln == 0:
                continue
            e = cand_idx[cand_start[mm] + np.random.randint(0, ln)]
            ef = xo[e] + e_add + slip_in
            stop = ef - t_r[q]
            tgt = ef + t_dist[q]
            xf, xb, why = walk(xo, xh, xl, xc, day, mod, e, ef, stop, tgt, lock, t_r[q], False)
            tot += (xf - ef - 0.07) * 100.0
        out[b] = tot
    return out


def twin_nets(B: Bars, trades: pd.DataFrame, v: Variant, side: int, mask: np.ndarray, n_twins=1000, seed=78):
    """mask: bars allowed as twin entries (the period's weekdays). Entry exit-space open: long at the ask = bid open +
    0.30 (e_add 0.30); short at -bid open = exit-space open (-(bid+0.30)) + 0.30, so e_add is 0.30 on both sides."""
    xo, xh, xl, xc = exit_space(B, side)
    idx = np.nonzero(mask)[0]
    mods = B.mod[idx]
    order = np.argsort(mods, kind="stable")
    cand_idx = idx[order].astype(np.int64)
    cand_len = np.bincount(mods, minlength=1440).astype(np.int64)
    cand_start = np.r_[0, np.cumsum(cand_len)[:-1]].astype(np.int64)
    if len(trades) == 0:
        return np.zeros(n_twins)
    return _twin_books(xo, xh, xl, xc, B.day, B.mod, cand_start, cand_len, cand_idx,
                       trades.entry_mod.values.astype(np.int64), trades.r.values.astype(float),
                       trades.tgt_dist.values.astype(float), SPREAD, SLIP if v.entry == "stop" else 0.0, v.lock,
                       n_twins, seed)


# -------------------------------------------------------------------------------------------------------------- metrics
def stats(tr: pd.DataFrame, months: float) -> dict:
    n = len(tr)
    if n == 0:
        return dict(n=0, per_month=0.0, win=float("nan"), avg_win=float("nan"), avg_loss=float("nan"), pf=float("nan"),
                    net=0.0, per_trade=float("nan"), worst_day=0.0, max_dd=0.0, reasons={})
    x = tr.net.values
    w, lo = x[x > 0], x[x <= 0]
    daily = tr.groupby("day").net.sum().sort_index()
    eq = daily.cumsum()
    dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq.values])[1:]).min())
    return dict(n=n, per_month=n / months, win=float((x > 0).mean()), avg_win=float(w.mean()) if len(w) else 0.0,
                avg_loss=float(lo.mean()) if len(lo) else 0.0,
                pf=float(w.sum() / -lo.sum()) if lo.sum() < 0 else float("inf"),
                net=float(x.sum()), per_trade=float(x.mean()), worst_day=float(min(0.0, daily.min())),
                max_dd=min(0.0, dd), reasons=tr.reason.value_counts().to_dict())


def bh(p: np.ndarray) -> np.ndarray:
    """Benjamini-Hochberg q-values."""
    p = np.asarray(p, float)
    m = len(p)
    o = np.argsort(p)
    q = p[o] * m / np.arange(1, m + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(q, 1.0)
    return out
