"""R79 direction filters on Liquidity 15+5 (PREREG.md section 2). Signal source: the INDEX, every rule. 18 Oct 2026.

Liquidity itself is unchanged (hunt/r51/fam_liq.py); R79 only decides whether to TAKE each of its signals, and (for the
waits) when.

  S1 / S2   Supertrend(10, 3) on the index's own 5- / 15-minute bars agrees with the side.
  W10a / b  wait 10 one-minute / five-minute candles; the 10th closes beyond the signal close on the trade's side.
  X1 / X2   at least 2 / 3 of the OTHER indices have their own 5-minute Supertrend(10, 3) agreeing.

Every read at a signal's entry row `er` (the minute right after the breaking bar closed) uses only bars fully closed at
the start of row `er` (Supertrend), or the wait candles rows er .. er+N-1 (waits). test_r79.py's look-ahead guard
proves it. Pure: numpy only (Supertrend is hunt/r73/r73rules.py `supertrend_dir`, the function R73 T7 used).
"""
from __future__ import annotations

import sys
from bisect import bisect_right
from dataclasses import dataclass
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "r73"))
from r73rules import supertrend_dir  # noqa: E402

NROWS = 375                                 # 09:15 .. 15:29
ST_N, ST_MULT = 10, 3.0
WAIT_N = 10
OTHERS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
VARIANTS = ("S1", "S2", "W10a", "W10b", "X1", "X2")
NAMES = {
    "B0": "Liquidity 15+5 as now (plain rules)",
    "S1": "take only when Supertrend(10,3) on the index's 5-minute bars agrees with the side",
    "S2": "take only when Supertrend(10,3) on the index's 15-minute bars agrees with the side",
    "W10a": "wait 10 one-minute candles; enter only if the 10th closes beyond the signal close on the trade's side",
    "W10b": "wait 10 five-minute candles (50 min); the same, dropped if past 14:00",
    "X1": "take only when at least 2 other indices' 5-minute Supertrend(10,3) agree with the side",
    "X2": "take only when at least 3 other indices' 5-minute Supertrend(10,3) agree with the side",
}


def bars(days: dict, tf: int):
    """The continuous series of 09:15-anchored tf-minute index bars from {day: (4, 375) O H L C}: (day, slot, o, h, l,
    c) arrays, days in order. O = the slot's first minute's open, H / L = max / min, C = its last minute's close; a slot
    with no minute is skipped (fam_r44.bars5 logic for tf = 5)."""
    d_, s_, o_, h_, l_, c_ = [], [], [], [], [], []
    for d in sorted(days):
        a = days[d]
        for s in range(-(-NROWS // tf)):
            seg = a[:, s * tf:(s + 1) * tf]
            ok = np.isfinite(seg[3])
            if not ok.any():
                continue
            d_.append(d)
            s_.append(s)
            o_.append(seg[0][ok][0])
            h_.append(np.nanmax(seg[1][ok]))
            l_.append(np.nanmin(seg[2][ok]))
            c_.append(seg[3][ok][-1])
    return (np.array(d_, dtype=object), np.array(s_, dtype=np.int64), np.array(o_, float), np.array(h_, float),
            np.array(l_, float), np.array(c_, float))


@dataclass
class Trend:
    """Supertrend(10, 3) direction per bar of one index's tf-minute series, searchable by (day, row)."""
    tf: int
    day: np.ndarray
    slot: np.ndarray
    dir: np.ndarray
    keys: list

    @classmethod
    def build(cls, days: dict, tf: int) -> "Trend":
        d, s, _, h, l, c = bars(days, tf)
        st = supertrend_dir(h, l, c, ST_N, ST_MULT)
        keys = [x.toordinal() * 1000 + int(k) for x, k in zip(d, s)]
        return cls(tf, d, s, st, keys)

    def at(self, day, er: int, same_day: bool = False) -> int:
        """Direction of the last bar fully closed at the START of row `er` of `day` (same day with slot end <= er,
        else any earlier day; `same_day` requires that day). 0 when none (or warm-up)."""
        hi = day.toordinal() * 1000 + (er // self.tf) - 1          # slot end (slot + 1) * tf <= er
        j = bisect_right(self.keys, hi) - 1
        if j < 0:
            return 0
        if same_day and self.day[j] != day:
            return 0
        return int(self.dir[j])


def trend_agrees(tr: Trend, day, er: int, sgn: int) -> bool:
    """S1 / S2: the index's own Supertrend at the signal agrees with the side. Warm-up (0) fails."""
    return tr.at(day, er) == sgn


def breadth_count(trends: dict, und: str, day, er: int, sgn: int) -> int:
    """X1 / X2: how many OTHER indices' 5-minute Supertrend (their last bar closed before `er` on the same day) agree
    with the side. An index with no bar that day counts as not agreeing."""
    return sum(1 for u, tr in trends.items() if u != und and tr.at(day, er, same_day=True) == sgn)


def wait_entry(idx: np.ndarray, er: int, sgn: int, close: float, width: int, n: int = WAIT_N):
    """W10a (width 1) / W10b (width 5): the n candles of `width` minutes from row er (rows er .. er + n*width - 1)
    must each have at least one minute; the last non-missing close of the n-th must be beyond the signal close on the
    side (sgn * (c - close) > 0). Returns (entry row er + n*width, that close) or None. Reads only those rows."""
    span = n * width
    if er < 0 or er + span >= idx.shape[1]:
        return None
    cl = idx[3, er:er + span]
    seg = cl.reshape(n, width)
    ok = np.isfinite(seg)
    if not ok.any(axis=1).all():
        return None
    last = seg[-1][ok[-1]][-1]
    if not sgn * (last - close) > 0:
        return None
    return er + span, float(last)
