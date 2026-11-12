"""R164: order-flow reads at a Donchian entry and in the trade, from the phone's tick zone (12 Nov 2026).

Frozen by research/hunt/r164_donchian_orderflow/PREREG.md (sections 3 and 4). Pure: reads only what it is given.

Input is the tick zone's per-second file (``ticks.jsonl.gz``, written by android/ira-core TickZone.kt): "I" rows hold the
watch-list, "T" rows hold one instrument's second (s, k, ltp, v cumulative volume, oi, b/a five [price, qty, orders]
levels). The phone seals that file inside ``live-YYYY-MM-DD.irlive``. Only Boss can open it on their own PC
(research/data/decrypt_live.py); this module never reads a sealed file.

Look-ahead guard: every read at second t uses rows with ``s <= t`` only. Every exit decided at second t sells at the next
recorded second's best bid. tests/test_r164.py checks both.
"""
from __future__ import annotations

import gzip
import json
from bisect import bisect_right
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

import numpy as np

# ---- the pre-registered constants (PREREG 3 and 4); never tuned ---------------------------------------------------------
E1_WIN, E1_CUT = 60, -0.20      # option top-5 depth imbalance, mean over (t0-60, t0]
E2_WIN, E2_CUT = 60, -0.20      # future top-5 depth imbalance x dir
E3_WIN, E3_CUT = 60, -0.20      # future tick-rule delta / volume x dir, 1 minute
E4_WIN, E4_CUT = 300, -0.10     # the same over 5 minutes
E5_WIN, E5_CUT = 300, -2        # future large prints, net signed count x dir
E6_WIN, E6_CUT = 900, -0.001    # future OI change (fraction)
LARGE_Q, LARGE_MIN = 0.99, 200  # the app's large-print rule (TickZone.LARGE_Q, LARGE_MIN_SAMPLES)

X_START = 60                    # exits are checked from entry + 60 s
X1_WIN, X1_CUT, X1_HOLD = 60, -0.30, 30
X2_CUT, X2_HOLD = -0.30, 30
X3_HOLD = 15

ENTRY_RULES = ("E1", "E2", "E3", "E4", "E5", "E6")
EXIT_RULES = ("X1", "X2", "X3")
#: Rules that read the bought option's own book (option-chart rules: labelled and reported apart, CLAUDE.md).
OPTION_CHART_RULES = frozenset({"E1", "X2", "X3"})


@dataclass
class Series:
    """One instrument's recorded seconds, oldest first. Depth sums are NaN where the row had no levels."""
    s: np.ndarray
    ltp: np.ndarray
    vol: np.ndarray       # cumulative volume (NaN: none)
    oi: np.ndarray
    bidq: np.ndarray      # sum of the 5 bid quantities
    askq: np.ndarray
    bid: np.ndarray       # best bid price (NaN: none)
    ask: np.ndarray
    side: np.ndarray = field(init=False)   # tick rule per row: +1 / -1 / 0
    dv: np.ndarray = field(init=False)     # volume traded since the previous recorded row (>= 0)
    size: np.ndarray = field(init=False)   # dv when the previous row was the second before, else NaN (not a "size")

    def __post_init__(self):
        n = len(self.s)
        side = np.zeros(n, dtype=np.int8)
        prev_side, prev_ltp = 0, None
        for i in range(n):
            p = self.ltp[i]
            if np.isfinite(p) and p > 0:
                if prev_ltp is not None:
                    prev_side = 1 if p > prev_ltp else (-1 if p < prev_ltp else prev_side)
                prev_ltp = p
            side[i] = prev_side
        self.side = side
        v = self.vol
        dv = np.zeros(n)
        if n > 1:
            d = np.diff(v)
            d[~np.isfinite(d) | (d < 0)] = 0.0
            dv[1:] = d
        self.dv = dv
        size = np.full(n, np.nan)
        if n > 1:
            consec = np.diff(self.s) == 1
            size[1:][consec] = dv[1:][consec]
        self.size = size
        self._cum_sdv = np.concatenate([[0.0], np.cumsum(self.side * self.dv)])
        self._cum_dv = np.concatenate([[0.0], np.cumsum(self.dv)])

    # rows with lo < s <= hi, as an index slice
    def _span(self, lo: float, hi: float) -> tuple[int, int]:
        return int(np.searchsorted(self.s, lo, side="right")), int(np.searchsorted(self.s, hi, side="right"))

    def at_or_before(self, t: float) -> int | None:
        i = int(np.searchsorted(self.s, t, side="right")) - 1
        return i if i >= 0 else None

    def depth_imbalance(self, t: float, win: int) -> float | None:
        a, b = self._span(t - win, t)
        bq, aq = self.bidq[a:b], self.askq[a:b]
        tot = bq + aq
        ok = np.isfinite(tot) & (tot > 0)
        if not ok.any():
            return None
        return float(np.mean((bq[ok] - aq[ok]) / tot[ok]))

    def delta_ratio(self, t: float, win: int) -> float | None:
        a, b = self._span(t - win, t)
        if b <= a:
            return None
        # row i's dv is the volume traded since row i-1, so it happened by second s[i]: rows a..b-1 are (t-win, t].
        vol = self._cum_dv[b] - self._cum_dv[a]
        if vol <= 0:
            return None
        return float((self._cum_sdv[b] - self._cum_sdv[a]) / vol)

    def oi_change(self, t: float, win: int) -> float | None:
        i, j = self.at_or_before(t - win), self.at_or_before(t)
        if i is None or j is None:
            return None
        o0, o1 = self.oi[i], self.oi[j]
        if not (np.isfinite(o0) and np.isfinite(o1) and o0 > 0):
            return None
        return float(o1 / o0 - 1.0)

    def large_net(self, t: float, win: int) -> int | None:
        """Net count of large prints (tick-rule signed) in (t-win, t]. A print is large when its size is above the p99 of
        the day's sizes in EARLIER minutes (the app's point-in-time rule), once those hold >= LARGE_MIN sizes."""
        a, b = self._span(t - win, t)
        if b <= a:
            return None
        minute = self.s - np.mod(self.s, 60)
        known = False
        net = 0
        for i in range(a, b):
            prior = self.size[(minute < minute[i]) & np.isfinite(self.size)]
            if len(prior) < LARGE_MIN:
                continue
            known = True
            # nearest-rank quantile, as TickZone.quantile
            srt = np.sort(prior)
            thr = srt[min(len(srt), max(1, int(np.ceil(LARGE_Q * len(srt))))) - 1]
            if np.isfinite(self.size[i]) and self.size[i] > thr:
                net += int(self.side[i])
        return net if known else None

    def bid_after(self, t: float, before: float) -> tuple[float, float] | None:
        """The first recorded row strictly after t (and before ``before``) with a best bid: (second, bid)."""
        i = int(np.searchsorted(self.s, t, side="right"))
        while i < len(self.s) and self.s[i] < before:
            if np.isfinite(self.bid[i]) and self.bid[i] > 0:
                return float(self.s[i]), float(self.bid[i])
            i += 1
        return None


# ---- reading the tick file ----------------------------------------------------------------------------------------------
def _levels(x) -> tuple[float, float]:
    if not x:
        return np.nan, np.nan
    return float(sum(l[1] for l in x[:5])), float(x[0][0]) if x[0][0] else np.nan


def series_from_rows(rows: Iterable[dict]) -> tuple[dict[int, Series], dict[int, list]]:
    """Build each instrument's Series from tick-zone rows (already parsed JSON). Returns (series by token, instruments by
    token as [token, symbol, und, kind, strike, expiry])."""
    cols: dict[int, list[list]] = {}
    inst: dict[int, list] = {}
    for r in rows:
        t = r.get("t")
        if t == "I":
            for x in r.get("list") or []:
                inst[int(x[0])] = x
        elif t == "T":
            bq, bb = _levels(r.get("b"))
            aq, ba = _levels(r.get("a"))
            f = lambda v: np.nan if v is None else float(v)
            cols.setdefault(int(r["k"]), []).append([float(r["s"]), f(r.get("ltp")), f(r.get("v")), f(r.get("oi")), bq, aq, bb, ba])
    out = {}
    for k, rr in cols.items():
        a = np.array(sorted(rr, key=lambda z: z[0]), dtype=float)
        # one row a second: keep the last row of a repeated second
        keep = np.r_[a[1:, 0] != a[:-1, 0], True] if len(a) else np.array([], bool)
        a = a[keep]
        out[k] = Series(a[:, 0], a[:, 1], a[:, 2], a[:, 3], a[:, 4], a[:, 5], a[:, 6], a[:, 7])
    return out, inst


def read_ticks(path: str | Path) -> tuple[dict[int, Series], dict[int, list]]:
    """Read an OPENED day's ticks.jsonl.gz (never a sealed .irlive)."""
    p = Path(path)
    if p.suffix == ".irlive" or p.name.endswith(".vault"):
        raise ValueError("sealed file: open it on Boss's own PC with research/data/decrypt_live.py first")
    with gzip.open(p, "rt", encoding="utf-8") as fh:
        return series_from_rows(json.loads(line) for line in fh if line.strip())


# ---- the entry reads and filters (PREREG 3) -----------------------------------------------------------------------------
def entry_reads(fut: Series | None, opt: Series | None, t0: float, direction: int) -> dict[str, float | None]:
    """Each pre-registered read at the signal second t0 (rows with s <= t0 only)."""
    m = lambda v, k=1: None if v is None else v * k
    return {
        "E1": opt.depth_imbalance(t0, E1_WIN) if opt is not None else None,
        "E2": m(fut.depth_imbalance(t0, E2_WIN), direction) if fut is not None else None,
        "E3": m(fut.delta_ratio(t0, E3_WIN), direction) if fut is not None else None,
        "E4": m(fut.delta_ratio(t0, E4_WIN), direction) if fut is not None else None,
        "E5": m(fut.large_net(t0, E5_WIN), direction) if fut is not None else None,
        "E6": fut.oi_change(t0, E6_WIN) if fut is not None else None,
    }


CUTS = {"E1": E1_CUT, "E2": E2_CUT, "E3": E3_CUT, "E4": E4_CUT, "E5": E5_CUT, "E6": E6_CUT}


def entry_skips(reads: dict[str, float | None]) -> dict[str, bool]:
    """True = the rule skips the entry. A read that is missing keeps the trade (PREREG 3)."""
    return {k: (reads.get(k) is not None and reads[k] <= CUTS[k] + 1e-12) for k in ENTRY_RULES}


# ---- the in-trade exits (PREREG 4) --------------------------------------------------------------------------------------
def _x1(fut: Series, t: float, direction: int) -> bool:
    d = fut.delta_ratio(t, X1_WIN)
    return d is not None and d * direction <= X1_CUT + 1e-12


def _x2(opt: Series, t: float) -> bool:
    i = opt.at_or_before(t)
    if i is None or opt.s[i] != t:
        return False
    tot = opt.bidq[i] + opt.askq[i]
    return bool(np.isfinite(tot) and tot > 0 and (opt.bidq[i] - opt.askq[i]) / tot <= X2_CUT + 1e-12)


def flip_exit(rule: str, fut: Series | None, opt: Series | None, entry_s: float, exit_s: float, direction: int):
    """The rule's early exit for one trade: (decided at second, sold at second, best bid) or None (today's exit stands).

    Checked on the option's recorded seconds from entry + X_START up to (not including) the trade's own exit. A gap in the
    option's seconds restarts the count. The sale is the next recorded second's best bid, before the trade's own exit.
    """
    if opt is None or (rule in ("X1", "X3") and fut is None):
        return None
    need = {"X1": X1_HOLD, "X2": X2_HOLD, "X3": X3_HOLD}[rule]
    a, b = opt._span(entry_s + X_START - 1, exit_s - 1)
    run, prev = 0, None
    for i in range(a, b):
        t = float(opt.s[i])
        if prev is not None and t != prev + 1:
            run = 0
        prev = t
        if rule == "X1":
            ok = _x1(fut, t, direction)
        elif rule == "X2":
            ok = _x2(opt, t)
        else:
            ok = _x1(fut, t, direction) and _x2(opt, t)
        run = run + 1 if ok else 0
        if run >= need:
            sale = opt.bid_after(t, exit_s)
            if sale is None:
                return None
            return t, sale[0], sale[1]
    return None
