"""R52 family LIQ: the REJECTED variants and index applications of the Liquidity level-break rule, re-tested on Nov 2024+.

Written 15 Oct 2026 for R52 (PREREG.md, FAMILY_BRIEF.md). Signal source of every rule here: the INDEX itself (its
minute bars folded into the chart's candles; liquidity swings and pools are drawn on those candles). Nothing reads an
option's own price chart; the option is only bought (strike rule) and its premium only carries stops / locks.
The adopted app books (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5 with the arm's exits) are NOT tested.

Two signal engines, both reusing the original studies' own code
    ARM   the app's Liquidity arm as research/hunt/h4/comps.py ports it (pool-on-swing break on a 10-day history folded
          into tf-minute bars, lookback 20, confirm 10, entries on bar closes 09:20-14:00, room filter, index stop =
          the broken level -/+ the index's stop, next level = idx_target, first failed break / first new level =
          exit_at, expiry days skipped). comps.fold / swing_zones / pool_zones / L / IDX_STOP_EXT are imported
          unchanged; `_arm_raw` is research/hunt/h11/sig.py `raw_signals` for one (index, timeframe) with the 14:00
          window and the room filter left to the caller (so the window can move for LIQ-22) and the failed-break and
          new-level bars kept apart (LIQ-19). With the 09:20-14:00 window and room 1 it gives raw_signals' rows
          exactly (tests). Exits = the arm's: -15% premium stop, out after 20 minutes unless the premium is >= +5%,
          index stop, next level, failed break, new level, 15:10 (obuy.strategies.liquidity.ARM_EXITS).
    BREAK the older research engine research/liquidity_break.py `simulate` and its copies (liquidity_more.py,
          liquidity_wait5.py, liquidity_nifty_plus.py, liquidity_sensex_plus.py, liquidity_finnifty_plus.py): levels
          from indicator/liquidity.py (imported unchanged: swing_zones(length, 'full') + pool_zones(2, 5, confirm)) on
          the chart's candles, CONTINUOUS across all sessions (as those studies' charts were); a bar CLOSING through a
          level of the chosen source (swing / pool / either / both = a pool broken where an active same-side swing
          zone overlaps) buys at the next minute; out at the next active level ahead (idx_target), a new level on the
          trade's side (exit_at), optionally a close back through the broken level (failed-break stop, exit_at), the
          study's premium stop if any, 15:10. One position at a time per chart (obuy book). The studies' extra
          filters are ported in `break_signals` (first-hour range vs its 20-day median, day's TWAP side, EMA slopes,
          range so far, skip expiry day / skip when the option expires within a day, index breakeven exit).
    Deviations (all forced by "no look-ahead" or by obuy's fills; none chooses a side or a minute):
      * the next level ahead is measured from the breaking bar's CLOSE; the old studies used the entry minute's
        OPEN (one minute later). The arm itself already used the close.
      * the old studies filled at the open + Rs 0.5 and paid Rs 40 a round trip; R52 uses its own fills / charges.
      * a book that exits on a bar's close may re-enter on a break of that same bar in the old engine; obuy's book is
        flat from the minute after the exit, so that rare same-bar re-entry is dropped.
      * candles are built from the minutes present (a missing minute is skipped, an empty candle is dropped); the
        old studies' files had no gaps.

Keys (original setting first; the grid is the original study's own grid around it, <= 23 overrides)
    H9-1   extra books with the arm's rules unchanged: BANKNIFTY 10-min + MIDCPNIFTY 30-min (H9's pre-registered keep
           rule's two books), ITM1. Grid: each of H9's 12 extra books alone (BN 3/10/30/60, FIN 3/10/15/60,
           MIDCP 3/10/30/60). Index stops a priori as h4: BN 30, FIN 15, MIDCP 8.
    H11-1  NIFTY: H11's pre-registered choice 30m+5m, 1x stop (15 pts), room >= 2 stops, ITM1, nearest weekly.
           Grid: the rest of H11's 24-cell grid (books 15+5/30+5 x stop 1x/2x x room 1/2 x ATM/ITM1 weekly; monthly x
           books x stop x strike at room 1).
    H11-2  SENSEX: H11's choice 15m+5m, 1x stop (50 pts), room 2, ITM1, weekly. Same grid.
    LIQ-1  BANKNIFTY 1-min / 3-min charts (LIQUIDITY.md): original = the catalogue's best cell, 3-min 'either', no
           failed-break stop; ATM, no premium stop, entries 09:20-14:30, 15:10. Grid: the other 15 cells
           (1/3-min x swing/pool/either/both x stop or not).
    LIQ-2  BANKNIFTY 5/15-min non-adopted levels: original = 15-min 'both' with NO failed-break stop (the catalogue's
           CLOSE cell). Grid: liquidity_break's other 5/15-min cells (15) and liquidity_more's 15-min lookback /
           confirm cells for 'both' and 'either' without the stop (8).
    LIQ-3  BANKNIFTY 60-min chart: original = pool, no stop, HELD OVERNIGHT on the same contract (the catalogue's best,
           +53k / -16k). Grid: the other 15 60-min cells (4 sources x stop x intraday / overnight). Multi-day holds use
           obuy multiday (exit_day / exit_min); the one-at-a-time sequence is replayed here because every exit of
           this rule is an index event. Overnight carry only while the near contract does not expire that day.
           The old run kept a carried trade to 15:29 on its last day; R52 caps the square-off at 15:15 (PREREG).
    LIQ-4  BANKNIFTY two-chart combos (liquidity_more.py): original = 15(lookback 20, confirm 10) + 5(10, 10),
           'both', failed-break stop, ATM, no premium stop. Grid: 15(10,10)+5(10,10), 15(20,10)+3(20,10).
    LIQ-5  'wait 5 candles' (liquidity_wait5.py): original = 15-min, level FOUND, 'both', colour reader (the
           catalogue's named cell); buy ATM at the next candle; out at the next level / a new level / 15:10.
           Grid: the other 15-min 'found' cells (11), 15-min 'break' cells for pool / both (8), and 3/5-min found
           both/pool colour (4).
    LIQ-6  FINNIFTY 15m+5m books (liquidity_finnifty_plus.py BASE: 'both', failed-break stop, 15% premium stop, ATM,
           09:20-14:30). = LIQ-7 (the 'plus' variants are this rule's grid). Grid: the study's A-pick (books 5+30,
           confirm 15) and B-pick (books 10+15, lookback 10, range-so-far >= 0.5x), single changes (books 5 / 15 /
           3+15 / 5+30 / 10+15, lookback 10/15/30, confirm 5/15, vol 0.3/0.5, TWAP side, skip expiry, 1 a day) and
           its option knobs (premium stop none/10/20/25%, ITM1, locks +30%->+10%, +50%->0). Not run (cap 23): the
           entry-window, EMA / open trend, 2-a-day and index-breakeven single changes.
    LIQ-9  NIFTY A-pick (liquidity_nifty_plus.py): 10-min book only, entries 10:15-14:30 if the 09:15-10:14 range >=
           the median of the previous 20 days, skip when the option expires within 1 day, ATM, 15% stop, trail once
           +30%: stop at max(paid, high x 0.85), level exits incl. failed break, 15:10. = LIQ-10 (the B-pick: 30-min,
           no failed-break stop, TWAP side, same lock, skip DTE<=1) which is in the grid with the study's 4
           robustness charts (5, 15, 30, 15+5 under the same stack) and its single-factor changes on the A-pick.
    LIQ-12 SENSEX B-pick (liquidity_sensex_plus.py): books 15m+3m, entries 10:15-14:30 only if the first-hour range
           >= 0.8x its 20-day median, ATM, 15% premium stop (REAL options now; the study had to model them), level
           exits incl. failed break, 15:10. Grid: the study's VARIANTS applied to the pick (books, lookback,
           confirm, no failed-break stop, premium stop none/10/20/25%, locks, TWAP / EMA trend, vol 1.0 / none,
           skip expiry, 2 a day).
    LIQ-19 overlay on the arm (BANKNIFTY 15+5, FINNIFTY 30+5 entries unchanged): no stop, no target, hold to a level.
           Original = N3 (next level + failed break, 15:10). Grid: N1 next level only, N2 new level only, N12 either.
           (Boss's ask, 7 Oct; the study ran it post hoc.)
    LIQ-22 overlay on the arm (BANKNIFTY 15+5): last new entry later than the adopted 14:00. Original = 14:30
           (entry_cutoff.py's first rejected cut-off); grid 14:55, 15:00, 15:05.
    Merged: none of these keys is the same rule as another (H11-1 / H11-2 are one rule on two indices with different
    pre-registered picks, so two SPECs). Catalogue merges kept: LIQ-6 = LIQ-7, LIQ-9 = LIQ-10.

LEFT
    none of the 13 keys asked for. Not carried over: H9's 'existing six books' (adopted), the SENSEX option MODEL of
    LIQ-12 (real SENSEX options exist now, so the rule is priced on them).
"""
from __future__ import annotations

import importlib.util
import math
import sys
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1]
ROOT = RES.parent
for _p in (ROOT, RES):
    if str(_p) not in sys.path:
        sys.path.insert(0, str(_p))

from indicator.liquidity import pool_zones as ind_pool_zones, swing_zones as ind_swing_zones  # noqa: E402

OPEN_M = 9 * 60 + 15
W = 375
SQ10 = 15 * 60 + 10
MD_SQ = 15 * 60 + 30                  # obuy.multiday.MD_SQ (the composite path's end)
COLS = ["und", "day", "sig_min", "side", "book", "idx_stop", "idx_target", "exit_at", "tag"]


def _load(name, path):
    """Import a research module under a private name (its own name, e.g. 'comps', is too generic)."""
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def _comps():
    return _load("r52_liq_comps", RES / "hunt" / "h4" / "comps.py")


# ====================================================================================== ARM engine (h4 / h11 port)
_ARM = {}


def _arm_raw(mk, und, tf):
    """research/hunt/h11/sig.py raw_signals for ONE (index, tf), without the entry window and without the room filter.
    Rows: day, done (bar end = clock minute of the fill), side, close, level, target (nan = none), fb / nl (bar end of
    the first failed-break close / of the first new same-side level after the break; nan = none)."""
    key = (id(mk), und, tf)
    if key in _ARM:
        return _ARM[key]
    cp = _comps()
    fold, swing_zones, pool_zones, L = cp.fold, cp.swing_zones, cp.pool_zones, cp.L
    rows = []
    ix = mk.index(und)
    hist = []
    for d in ix.days:
        x = ix.d[d]
        real_today = bool(x["real"])
        m = np.asarray(x["m"]).astype(int)
        sel = (m >= 555) & (m <= 929)
        today1 = [(d, int(mm), float(o), float(h), float(lo), float(c))
                  for mm, o, h, lo, c in zip(m[sel], x["o"][sel], x["h"][sel], x["l"][sel], x["c"][sel])]
        hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
        real = real_today and hist and all(hh[1] for hh in hist)
        if real and not x["exp"]:
            first = {}
            for b in [b for hh in hist for b in hh[2]] + today1:
                first.setdefault((b[0], b[1]), b)
            ones = sorted(first.values(), key=lambda b: (b[0], b[1]))
            bars = fold(ones, tf)
            if len(bars) >= 2 * L + 2:
                O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
                Lo = np.array([b[4] for b in bars]); Cl = np.array([b[5] for b in bars])
                sw_, po = swing_zones(H, Lo, Cl), pool_zones(O, H, Lo, Cl)
                zones = sw_ + po
                n = len(bars)
                is_today = [b[0] == d for b in bars]
                bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
                by_break = {}
                for p in po:
                    if p.broken >= 0:
                        by_break.setdefault(p.broken, []).append(p)
                for i in range(n):
                    if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
                        continue
                    pool = None
                    for p in by_break[i]:
                        if p.known > i:
                            continue
                        if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                               and (s.broken < 0 or s.broken >= i) for s in sw_):
                            pool = p; break
                    if pool is None:
                        continue
                    side, close = pool.side, Cl[i]
                    ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                             and side * (q.edge - close) > 0]
                    target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
                    level = pool.edge
                    fb = next((bar_end[k] for k in range(i + 1, n) if side * (Cl[k] - level) < 0), None)
                    nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
                    nl = min(nl) if nl else None
                    rows.append(dict(day=d, done=int(bar_end[i]), side=int(side), close=float(close), level=float(level),
                                     target=np.nan if target is None else float(target),
                                     fb=np.nan if fb is None else float(fb), nl=np.nan if nl is None else float(nl)))
        hist.append((d, real_today, today1))
    out = pd.DataFrame(rows, columns=["day", "done", "side", "close", "level", "target", "fb", "nl"])
    _ARM[key] = out
    return out


def arm_signals(mk, books=(("BANKNIFTY", 15), ("BANKNIFTY", 5)), stop_mult=1.0, room=1.0, win_to=14 * 60,
                mode="arm"):
    """The arm's entries on the given (index, tf) books.
    stop_mult x comps.IDX_STOP_EXT[index] = the index stop beyond the broken level; room = the least distance to the
    next level, in index stops (H11); win_to = the last bar end that may enter (14:00 in the app; LIQ-22 moves it).
    mode: 'arm' (index stop + next level + failed break / new level), or LIQ-19's no-stop exits
    'N1' (next level), 'N2' (new level), 'N12' (either), 'N3' (next level + failed break)."""
    cp = _comps()
    rows = []
    for und, tf in books:
        raw = _arm_raw(mk, und, tf)
        if raw.empty:
            continue
        istop = cp.IDX_STOP_EXT[und] * stop_mult
        r = raw[(raw.done >= cp.WIN_FROM) & (raw.done <= win_to) & (raw.done <= 930)]
        tgt = r.target.values
        dist = np.where(np.isfinite(tgt), r.side.values * (tgt - r.close.values), np.inf)
        r = r[dist >= room * istop - 1e-9]
        for x in r.itertuples(index=False):
            fb, nl, tg = x.fb, x.nl, x.target
            if mode == "arm":
                ist, it, xa = x.level - x.side * istop, tg, np.fmin(fb, nl)
            elif mode == "N1":
                ist, it, xa = np.nan, tg, np.nan
            elif mode == "N2":
                ist, it, xa = np.nan, np.nan, nl
            elif mode == "N12":
                ist, it, xa = np.nan, tg, nl
            elif mode == "N3":
                ist, it, xa = np.nan, tg, fb
            else:
                raise ValueError(mode)
            rows.append(dict(und=und, day=x.day, sig_min=int(x.done) - 1, gate=int(x.done), side=int(x.side),
                             book=f"liq{tf}_{und}", ref_spot=float(x.close), idx_stop=float(ist), idx_target=float(it),
                             exit_at=float(xa), tag=f"lvl={x.level:.2f}"))
    return pd.DataFrame(rows, columns=COLS[:4] + ["gate", "book", "ref_spot", "idx_stop", "idx_target", "exit_at", "tag"])


# ====================================================================================== BREAK engine (liquidity_break)
class Chart:
    """tf-minute candles of one index over ALL its sessions (continuous, as the old studies' charts) + the liquidity
    levels of indicator/liquidity.py on them, with per-bar lookups."""

    def __init__(self, mk, und, tf, length=20, confirm=10):
        ix = mk.index(und)
        M = ix.mat()
        self.und, self.tf, self.days = und, tf, list(ix.days)
        di, S, E, O, H, Lo, Cl = [], [], [], [], [], [], []
        for r in range(len(self.days)):
            o, h, l, c = M["o"][r], M["h"][r], M["l"][r], M["c"][r]
            for s in range(0, W, tf):
                e = min(s + tf, W)
                ok = ~np.isnan(c[s:e])
                if not ok.any():
                    continue
                k = np.nonzero(ok)[0]
                di.append(r); S.append(s); E.append(e)
                O.append(o[s + k[0]]); H.append(np.nanmax(h[s:e])); Lo.append(np.nanmin(l[s:e])); Cl.append(c[s + k[-1]])
        self.di, self.S, self.E = np.array(di, int), np.array(S, int), np.array(E, int)
        self.O, self.H, self.L, self.C = (np.array(a, float) for a in (O, H, Lo, Cl))
        n = len(self.C)
        df = pd.DataFrame(dict(open=self.O, high=self.H, low=self.L, close=self.C))
        self.zones = (ind_swing_zones(df, length, "full") + ind_pool_zones(df, 2, 5, confirm)) if n else []
        z = self.zones
        self.zkind = np.array([q.kind == "pool" for q in z], bool)      # True = pool
        self.zside = np.array([q.side for q in z], int)
        self.ztop = np.array([q.top for q in z], float)
        self.zbot = np.array([q.bottom for q in z], float)
        self.zedge = np.array([q.edge for q in z], float)
        self.zknown = np.array([q.known for q in z], int)
        self.zbroken = np.array([q.broken for q in z], int)
        self.breaks = {}
        for k, q in enumerate(z):
            if q.broken >= 0:
                self.breaks.setdefault(q.broken, []).append(k)
        # known[side] = sorted bars at which a level of that side becomes known
        self.known = {s: np.sort(self.zknown[self.zside == s]) for s in (1, -1)}
        # the last bar of each bar's session
        self.day_end = np.zeros(n, int)
        if n:
            last = np.r_[np.nonzero(np.diff(self.di))[0], n - 1]
            start = 0
            for e_ in last:
                self.day_end[start:e_ + 1] = e_
                start = e_ + 1
        self.ema20 = pd.Series(self.C).ewm(span=20, adjust=False).mean().values if n else np.zeros(0)

    def active_swing_overlap(self, k, i):
        """liquidity_break 'both': an active (known by i, not broken before i) swing zone of zone k's side overlaps it."""
        m = (~self.zkind) & (self.zside == self.zside[k]) & (self.zbot <= self.ztop[k]) & (self.zbot[k] <= self.ztop) \
            & (self.zknown <= i) & ((self.zbroken < 0) | (self.zbroken >= i))
        return bool(m.any())

    def cand(self, i, source):
        """The zone a bar-i close takes for this source (first in the study's zone order), or None."""
        brk = self.breaks.get(i)
        if not brk:
            return None
        if source == "swing":
            c = [k for k in brk if not self.zkind[k]]
        elif source == "pool":
            c = [k for k in brk if self.zkind[k]]
        elif source == "either":
            c = brk
        elif source == "both":
            c = [k for k in brk if self.zkind[k] and self.active_swing_overlap(k, i)]
        else:
            raise ValueError(source)
        return c[0] if c else None

    def target(self, i, side, ref):
        """The nearest level of this side known by bar i, not taken by bar i, beyond ref (None if none)."""
        m = (self.zside == side) & (self.zknown <= i) & ((self.zbroken < 0) | (self.zbroken > i)) & (side * (self.zedge - ref) > 0)
        if not m.any():
            return None
        return float(self.zedge[m].min() if side > 0 else self.zedge[m].max())

    def first_fb(self, i, side, level):
        """First bar after i in the same session whose close is back through the level (None if none)."""
        e = self.day_end[i]
        if e <= i:
            return None
        hit = np.nonzero(side * (self.C[i + 1:e + 1] - level) < 0)[0]
        return int(i + 1 + hit[0]) if len(hit) else None

    def first_nl(self, i, side):
        """First bar after i in the same session at which a new level of this side becomes known (None if none)."""
        kn = self.known[side]
        j = np.searchsorted(kn, i + 1)
        if j < len(kn) and kn[j] <= self.day_end[i]:
            return int(kn[j])
        return None

    def clock_end(self, k):
        return OPEN_M + int(self.E[k])


_CH = {}


def chart(mk, und, tf, length=20, confirm=10):
    key = (id(mk), und, tf, length, confirm)
    if key not in _CH:
        _CH[key] = Chart(mk, und, tf, length, confirm)
    return _CH[key]


class DayInfo:
    """Per-session extras of the 'plus' studies (all known before they are used)."""

    def __init__(self, mk, und):
        ix = mk.index(und)
        M = ix.mat()
        self.days = list(ix.days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        h, l, c = M["h"], M["l"], M["c"]
        n = len(self.days)
        with np.errstate(all="ignore"):
            self.fh = np.array([np.nanmax(h[r, :60]) - np.nanmin(l[r, :60]) if np.isfinite(c[r, :60]).any() else np.nan
                                for r in range(n)])
            self.rng = np.array([np.nanmax(h[r]) - np.nanmin(l[r]) if np.isfinite(c[r]).any() else np.nan for r in range(n)])
        # median of the previous 20 sessions (needs >= 5; liquidity_nifty_plus / sensex_plus / finnifty_plus)
        self.fh_med = np.array([np.median(self.fh[max(0, r - 20):r]) if r >= 5 else np.nan for r in range(n)])
        self.rng_med = np.array([np.median(self.rng[max(0, r - 20):r]) if r >= 5 else np.nan for r in range(n)])
        # cumulative TWAP of the typical price (the index has no volume; sell_levels.load / finnifty_plus.features)
        tp = (h + l + c) / 3
        ok = np.isfinite(tp)
        self.twap = np.cumsum(np.where(ok, tp, 0), axis=1) / np.maximum(np.cumsum(ok, axis=1), 1)
        self.hh = np.fmax.accumulate(np.where(np.isfinite(h), h, -np.inf), axis=1)
        self.ll = np.fmin.accumulate(np.where(np.isfinite(l), l, np.inf), axis=1)
        self.dopen = np.array([M["o"][r][np.isfinite(M["o"][r])][0] if np.isfinite(M["o"][r]).any() else np.nan
                               for r in range(n)])
        self.o, self.c, self.h, self.l = M["o"], c, h, l
        # 5-minute 50 EMA, continuous (liquidity_nifty_plus.load_days: closes I["close"][4::5], ewm span 50)
        c5 = pd.Series(c[:, 4::5].reshape(-1)).ffill().bfill()
        self.ema5 = c5.ewm(span=50, adjust=False).mean().values.reshape(n, -1) if n else np.zeros((0, 75))
        self.exp = np.array([bool(ix.d[d]["exp"]) for d in self.days])
        exps = [d for d, e in zip(self.days, self.exp) if e]
        import bisect
        self.dte = np.array([((exps[j] - d).days if (j := bisect.bisect_left(exps, d)) < len(exps) else 99)
                             for d in self.days])


_DI = {}


def dayinfo(mk, und):
    key = (id(mk), und)
    if key not in _DI:
        _DI[key] = DayInfo(mk, und)
    return _DI[key]


def break_signals(mk, unds=("BANKNIFTY",), books=((15, 20, 10),), source="both", stop=True, win=(5, 315),
                  skip=None, vol_fh=None, vol_rng=None, trend=None, be=None, name="lb"):
    """liquidity_break.simulate's entries (and the 'plus' studies' filters) as obuy signals, one book per chart.
    books: (tf, swing lookback, pool confirm) per chart. stop: failed-break exit. win: entry minutes after 09:15 of the
    fill (5..315 = 09:20..14:30). skip: None | 'expday' | 'dte1'. vol_fh: x -> enter from 10:15 only if the first-hour
    range >= x times its 20-day median. vol_rng: x -> skip if the range so far < x times the 20-day median range.
    trend: None | 'vwap' (close on the trade's side of the day's TWAP) | 'ema5x50' (5-min 50 EMA up/down over 30 min,
    nifty_plus) | 'ema20x3' (the chart's 20 EMA vs 3 bars back, sensex_plus) | 'ema20x1' (vs 1 bar back,
    finnifty_plus) | 'open' (close on the trade's side of the day's open). be: index points - once the trade has been
    that far in favour, out on a minute close back at the entry level (finnifty_plus 'be')."""
    rows = []
    for und in unds:
        X = dayinfo(mk, und)
        for tf, length, confirm in books:
            ch = chart(mk, und, tf, length, confirm)
            book = f"{name}{tf}_{und}"
            for i in sorted(ch.breaks):
                if i >= ch.day_end[i]:
                    continue                                   # the next candle must be in the same session
                m = int(ch.E[i])                               # the fill minute (offset from 09:15)
                if not (win[0] <= m <= win[1]):
                    continue
                k = ch.cand(i, source)
                if k is None:
                    continue
                side = int(ch.zside[k])
                r = int(ch.di[i])
                d = ch.days[r]
                if skip == "expday" and X.exp[r]:
                    continue
                if skip == "dte1" and X.dte[r] <= 1:
                    continue
                if vol_fh is not None and (m < 60 or not (X.fh[r] >= vol_fh * X.fh_med[r])):
                    continue
                if vol_rng is not None and np.isfinite(X.rng_med[r]) and \
                        X.hh[r, m - 1] - X.ll[r, m - 1] < vol_rng * X.rng_med[r]:
                    continue
                cl = ch.C[i]
                if trend == "vwap" and side * (cl - X.twap[r, m - 1]) <= 0:
                    continue
                if trend == "ema5x50":
                    q = m // 5 - 1
                    e_now = X.ema5[r, q]
                    e_prev = X.ema5[r, q - 6] if q >= 6 else (X.ema5[r - 1, q - 6] if r else e_now)
                    if side * (e_now - e_prev) <= 0:
                        continue
                if trend == "ema20x3" and (i < 3 or side * (ch.ema20[i] - ch.ema20[i - 3]) <= 0):
                    continue
                if trend == "ema20x1" and side * (ch.ema20[i] - (ch.ema20[i - 1] if i else ch.ema20[i])) <= 0:
                    continue
                if trend == "open" and side * (cl - X.dopen[r]) <= 0:
                    continue
                level = float(ch.zedge[k])
                tgt = ch.target(i, side, cl)
                xs = []
                nl = ch.first_nl(i, side)
                if nl is not None:
                    xs.append(ch.clock_end(nl))
                if stop:
                    fb = ch.first_fb(i, side, level)
                    if fb is not None:
                        xs.append(ch.clock_end(fb))
                if be is not None:
                    x = _be_exit(X, r, m, side, be)
                    if x is not None:
                        xs.append(x)
                rows.append(dict(und=und, day=d, sig_min=OPEN_M + m - 1, side=side, book=book, idx_stop=np.nan,
                                 idx_target=np.nan if tgt is None else tgt, exit_at=float(min(xs)) if xs else np.nan,
                                 tag=f"lvl={level:.2f}"))
    return pd.DataFrame(rows, columns=COLS)


def _be_exit(X, r, m, side, be):
    """finnifty_plus 'be': from the entry minute m, once the best index excursion in favour of the entry level (the
    entry minute's open, as the study's ix = I['open'][m]; known at the fill) is >= be points (updated AFTER each
    minute's check), out when a minute closes at or beyond the entry level. -> clock minute of the exit fill (the next
    minute's open) or None."""
    o = X.o[r, m] if np.isfinite(X.o[r, m]) else X.c[r, m - 1]
    mfe = 0.0
    for t in range(m, 355):
        c, h, l = X.c[r, t], X.h[r, t], X.l[r, t]
        if not np.isfinite(c):
            continue
        if mfe >= be and side * (c - o) <= 0:
            return OPEN_M + t + 1
        mfe = max(mfe, side * ((h if side > 0 else l) - o))
    return None
