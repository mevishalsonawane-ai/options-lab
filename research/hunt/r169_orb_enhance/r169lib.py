"""R169 pure functions (PREREG.md): the ORB family's walk with the pre-registered changes, the features, filters, exits,
strike by delta, scoring, twins and BH. No file, network or lake access here: r169study.py feeds it.

Built on R3's engine (research/hunt/r3orb/r3engine.py, reproduced to the rupee: PREREG section 1). Grid: row t = the
1-minute bar that STARTS at 09:15 + t (375 rows). 5-minute bar b covers rows 5b .. 5b+4; its decision is known at the end
of row 5b+4 and an entry fills at the next minute's open. Nothing here reads a row after the decision it makes
(test_r169.py: the look-ahead guard). Plain-English notes, 13 Nov 2026.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace
from datetime import date, datetime, time, timedelta

import numpy as np

import r169cfg as K
import r3engine as E
from r3data import C, H, L, N, O, V
from obuy.costs import Costs

try:  # optional speed-up; the pure-Python path gives the same numbers (test_r169.py)
    from numba import njit
except Exception:  # pragma: no cover
    def njit(*a, **k):
        def deco(f):
            return f
        return deco if not (a and callable(a[0])) else a[0]

TICK = 0.05
EPS = 1e-9
DATED = Costs("dated")
RATE = 0.065
FILTERS = ("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9")
EXITS = ("X1", "X2", "X3", "X4", "X5", "X6")
STRUCT = ("S1", "S2", "S3", "W1", "L1")
SINGLES = FILTERS + EXITS + STRUCT
WHY = ("stop", "lock", "target", "time", "square_off", "g3", "srt", "fail", "stale")


@dataclass(frozen=True)
class Cfg:
    """One arm x change x lots. `changes`: the ids of PREREG section 3 applied together (empty = B0)."""
    arm: str                       # orb | orb_fresh | orb_sweep
    lots: int = 1
    changes: tuple = ()
    costs: str = "dated"           # 'dated' (the study) | 'app' (R3 parity)

    @property
    def base(self) -> E.Variant:
        return E.baseline(self.arm)

    def has(self, cid: str) -> bool:
        return cid in self.changes

    @property
    def filters(self) -> tuple:
        return tuple(c for c in self.changes if c in FILTERS)


def with_changes(cfg: Cfg, changes) -> Cfg:
    return replace(cfg, changes=tuple(changes))


# ================================================================================================ day measurements
def bars5(ix: np.ndarray) -> np.ndarray:
    return E.bars5(ix)


def atr5(prev_b5: np.ndarray | None, b5: np.ndarray, n: int = 14) -> np.ndarray:
    """(75,) the 14-bar ATR of the 5-minute index bars at each bar of today (yesterday's bars warm it up); NaN when < n
    true ranges exist. Reads bars <= b for bar b."""
    allb = b5 if prev_b5 is None else np.vstack([prev_b5, b5])
    off = 0 if prev_b5 is None else len(prev_b5)
    tr = np.full(len(allb), np.nan)
    pc = np.nan
    for i in range(len(allb)):
        o, h, l, c = allb[i]
        if not np.isfinite(c):
            continue
        tr[i] = (h - l) if not np.isfinite(pc) else max(h - l, abs(h - pc), abs(l - pc))
        pc = c
    out = np.full(len(b5), np.nan)
    for b in range(len(b5)):
        w = tr[:off + b + 1]
        w = w[np.isfinite(w)][-n:]
        if len(w) >= n:
            out[b] = float(w.mean())
    return out


def busy_median(history: list, b: int, need: int = 10) -> float:
    """Median of the previous sessions' ATR at bar b (each entry an atr5 array; the caller passes the 20 before)."""
    x = np.array([h[b] for h in history if h is not None and np.isfinite(h[b])])
    return float(np.median(x)) if len(x) >= need else np.nan


def last_close(a: np.ndarray, row: int) -> float:
    c = a[:row + 1, C]
    c = c[np.isfinite(c)]
    return float(c[-1]) if len(c) else np.nan


def features(s, side: int, row: int, hi: float, lo: float, b5: np.ndarray, k: float) -> dict:
    """F1's inputs at a signal whose bar's last minute is `row` (reads rows <= row only)."""
    f = E.features(s, side, row, hi, lo)
    atr = s.atr14 if s.atr14 and np.isfinite(s.atr14) and s.atr14 > 0 else np.nan
    b = row // 5
    bo, bh, bl, bc = b5[b]
    f["bar_atr"] = (bh - bl) / atr
    c0 = b5[b - 3, 3] if b >= 3 else np.nan
    f["push"] = side * (bc - c0) / atr
    lvl = s.prev[1] if side > 0 else s.prev[2]
    f["pdhl"] = side * (bc - lvl) / atr
    a = s.opts.get((k, "C" if side > 0 else "P"))
    p = last_close(a, row) if a is not None else np.nan
    f["logprem"] = math.log(p) if np.isfinite(p) and p > 0 else np.nan
    return f


PICK_FEATS = ("vix_prev", "vix_chg", "gap_dir", "width_atr", "t_sig", "expiry", "twap_dir", "bar_atr", "push", "pdhl",
              "logprem")


# ================================================================================================ filters (skip-only)
def gap_pct(s) -> float:
    o = s.ix[np.isfinite(s.ix[:, O]), O]
    pc = s.prev[3]
    return float((o[0] / pc - 1) * 100) if len(o) and np.isfinite(pc) and pc > 0 else np.nan


def edge_in_first15(ix: np.ndarray, upper: bool) -> bool | None:
    """Was the opening range's high (upper) / low set in 09:15-09:29 (rows 0..14)? None if unknown."""
    a = ix[:50, H if upper else L]
    if not np.isfinite(a).any():
        return None
    ext = np.nanmax(a) if upper else np.nanmin(a)
    first = ix[:15, H if upper else L]
    if not np.isfinite(first).any():
        return None
    f = np.nanmax(first) if upper else np.nanmin(first)
    return bool(f >= ext - EPS) if upper else bool(f <= ext + EPS)


def skip_reason(fid: str, s, arm: str, side: int, row: int, hi: float, lo: float, b5: np.ndarray, ctx: dict) -> bool:
    """True when filter `fid` skips this break. Unknown never skips. ctx: per-session extras (atr5, busy median, the
    network view, the picker's verdict)."""
    b = row // 5
    if fid == "F1":
        v = ctx.get("pick")
        return v is not None and not v
    if fid == "F2":
        v = ctx.get("net")
        return v is not None and not v
    if fid == "F3":
        a, m = ctx.get("atr5"), ctx.get("busy_med")
        if a is None or m is None:
            return False
        x, med = a[b], m[b]
        return bool(np.isfinite(x) and np.isfinite(med) and x < med)
    if fid == "F4":
        if row < 60:
            ref = s.ix[np.isfinite(s.ix[:, O]), O]
            ref = ref[0] if len(ref) else np.nan
        else:
            ref = last_close(s.ix, row - 60) if np.isfinite(s.ix[:row - 59, C]).any() else np.nan
        c = last_close(s.ix, row)
        return bool(np.isfinite(ref) and np.isfinite(c) and side * (c - ref) <= 0)
    if fid == "F5":
        g = gap_pct(s)
        if not np.isfinite(g) or g == 0:
            return False
        gs = 1 if g > 0 else -1
        if abs(g) >= K.BIG_GAP and side != gs:
            return True
        return bool(abs(g) < K.SMALL_GAP and side == gs)
    if fid == "F6":
        if arm == "orb_sweep":
            swept_upper = side < 0                     # a PE reverses a sweep of the high
            v = edge_in_first15(s.ix, swept_upper)
        else:
            v = edge_in_first15(s.ix, side < 0)        # the OPPOSITE edge: a call needs the low set early
        return v is not None and not v
    if fid == "F7":
        if b < 2 or not (np.isfinite(b5[b - 2]).all() and np.isfinite(b5[b]).all()):
            return False
        return not (b5[b - 2, 1] < b5[b, 2] if side > 0 else b5[b - 2, 2] > b5[b, 1])
    if fid == "F8":
        c = b5[b, 3]
        lvl = s.prev[1] if side > 0 else s.prev[2]
        if not (np.isfinite(c) and np.isfinite(lvl)):
            return False
        d = side * (lvl - c)
        return bool(0 < d <= K.ROOM_PTS)
    if fid == "F9":
        return bool(s.expiry)
    raise KeyError(fid)


# ================================================================================================ strike by delta
def _ncdf(x: float) -> float:
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def bs_price(S, Kk, T, r, sig, call: bool) -> float:
    if T <= 0 or sig <= 0:
        return max(0.0, (S - Kk) if call else (Kk - S))
    d1 = (math.log(S / Kk) + (r + 0.5 * sig * sig) * T) / (sig * math.sqrt(T))
    d2 = d1 - sig * math.sqrt(T)
    if call:
        return S * _ncdf(d1) - Kk * math.exp(-r * T) * _ncdf(d2)
    return Kk * math.exp(-r * T) * _ncdf(-d2) - S * _ncdf(-d1)


def implied_vol(p, S, Kk, T, r, call: bool) -> float:
    """Bisection on [0.01, 5]; NaN when the price is below intrinsic or not finite."""
    if not (np.isfinite(p) and np.isfinite(S) and p > 0 and T > 0):
        return np.nan
    lo_, hi_ = 0.01, 5.0
    if p < bs_price(S, Kk, T, r, lo_, call) - 1e-6 or p > bs_price(S, Kk, T, r, hi_, call):
        return np.nan
    for _ in range(80):
        m = 0.5 * (lo_ + hi_)
        if bs_price(S, Kk, T, r, m, call) > p:
            hi_ = m
        else:
            lo_ = m
    return 0.5 * (lo_ + hi_)


def bs_delta(S, Kk, T, r, sig, call: bool) -> float:
    if not (T > 0 and sig > 0):
        return np.nan
    d1 = (math.log(S / Kk) + (r + 0.5 * sig * sig) * T) / (sig * math.sqrt(T))
    return _ncdf(d1) if call else _ncdf(d1) - 1.0


def years_to_expiry(day: date, row: int, expiry: date | None) -> float:
    """Calendar time from the end of `row` to 15:30 on the expiry day, in years (NaN without an expiry)."""
    if expiry is None:
        return np.nan
    now = datetime.combine(day, time(9, 15)) + timedelta(minutes=row + 1)
    end = datetime.combine(expiry, time(15, 30))
    return max((end - now).total_seconds(), 60.0) / (365.0 * 86400.0)


def strike_by_delta(s, side: int, row: int, target: float, expiry: date | None) -> float:
    """The stored strike of the side whose |delta| (own IV from its close at `row`) is nearest `target`; the day's ATM
    when no strike can be valued."""
    S = last_close(s.ix, row)
    T = years_to_expiry(s.day, row, expiry)
    if not (np.isfinite(S) and np.isfinite(T)):
        return s.atm
    rt = "C" if side > 0 else "P"
    best, bd = s.atm, np.inf
    for (k, r_), a in sorted(s.opts.items()):
        if r_ != rt:
            continue
        p = last_close(a, row)
        iv = implied_vol(p, S, k, T, RATE, side > 0)
        if not np.isfinite(iv):
            continue
        d = abs(bs_delta(S, k, T, RATE, iv, side > 0))
        if abs(d - target) < bd - 1e-12:
            best, bd = k, abs(d - target)
    return best


# ================================================================================================ the exit walk
@njit(cache=True)
def walk(o, h, l, c, ixh, ixl, ixc, c0, e, trig, tgt, lad_t, be, be10, ts_row, ts_need, g3_need, side,
         srt_lvl, fail_mode, rhi, rlo, sq):
    """1-minute rows c0 .. sq-1. Returns (row, price, why): why 0 stop, 1 lock (stop sales at min(level, open)),
    2 target, 3 time, 5 g3, 6 srt, 7 fail (sold at the next open), 4 square-off. The peak used for locks is the best high
    BEFORE the current minute (R3). fail_mode: 0 none, 1 ORB (bar closes back inside), 2 Sweep (bar closes back beyond the
    swept edge)."""
    peak = e
    reached = False
    for r in range(c0, sq):
        if not np.isfinite(c[r]):
            continue
        op = o[r] if np.isfinite(o[r]) else c[r]
        lock = -1.0
        if lad_t > 0:
            for i in range(3):
                reach = (0.25, 0.50, 0.75)[i]
                keep = (0.0, 0.25, 0.50)[i]
                if peak >= e + reach * lad_t - 1e-9:
                    lv = max(e + keep * lad_t, be)
                    if lv < peak:
                        lock = max(lock, lv)
        if be10 > 0 and peak >= e * (1.0 + be10) - 1e-9:
            if be < peak:
                lock = max(lock, be)
        rest = max(trig, lock)
        if rest > 0.05 and l[r] <= rest:
            return r, min(rest, op), (0 if trig >= lock else 1)
        if tgt > 0 and r <= sq - 2 and h[r] >= e + tgt:
            return r + 1, np.nan, 2
        if ts_row > 0 and r >= ts_row and not reached and r <= sq - 2:
            return r + 1, np.nan, 3
        peak = max(peak, h[r])
        if ts_need > 0 and peak >= e + ts_need - 1e-9:
            reached = True
        if r <= sq - 2:
            # index-based exits, decided at the close of row r on the index's own data
            if g3_need > 0 and peak >= e + g3_need - 1e-9 and r >= 3 and np.isfinite(ixc[r]):
                if side > 0:
                    m = min(ixl[r - 1], ixl[r - 2], ixl[r - 3])
                    if np.isfinite(m) and ixc[r] < m:
                        return r + 1, np.nan, 5
                else:
                    m = max(ixh[r - 1], ixh[r - 2], ixh[r - 3])
                    if np.isfinite(m) and ixc[r] > m:
                        return r + 1, np.nan, 5
            if srt_lvl > 0:
                if (side > 0 and np.isfinite(ixh[r]) and ixh[r] >= srt_lvl) or (side < 0 and np.isfinite(ixl[r]) and ixl[r] <= srt_lvl):
                    return r + 1, np.nan, 6
            if fail_mode > 0 and (r + 1) % 5 == 0 and np.isfinite(ixc[r]):
                x = ixc[r]
                if fail_mode == 1:
                    if (side > 0 and x <= rhi) or (side < 0 and x >= rlo):
                        return r + 1, np.nan, 7
                else:
                    if (side < 0 and x > rhi) or (side > 0 and x < rlo):
                        return r + 1, np.nan, 7
    return sq, np.nan, 4


# ================================================================================================ fills and charges
def thin_lots(a: np.ndarray, row: int, lot: int, lots: int) -> float:
    """R17's thin test, sized to the order: the contract's volume in the 5 minutes before `row`, in multiples of the
    order (lot x lots). For 1 lot it is R3's vol5_lots."""
    return E.vol5_lots(a, row, lot * max(1, lots))


def charge(cfg: Cfg, buy: bool, px: float, qty: int, und: str, day: date) -> float:
    if cfg.costs == "app":
        return E.charge(buy, px, qty, und)
    dn = np.array([(day - date(1970, 1, 1)).days])
    return float(DATED.charge(buy, np.array([px]), np.array([float(qty)]), dn, bse=und in K.K3.BSE)[0])


def be_unit(cfg: Cfg, e: float, qty: int, und: str, day: date) -> float:
    if cfg.costs == "app":
        return E.be_per_unit(e, qty, und)
    dn = np.array([(day - date(1970, 1, 1)).days])
    return float(DATED.rt_per_unit(np.array([e]), np.array([float(qty)]), dn, bse=und in K.K3.BSE)[0])


# ================================================================================================ one trade
def simulate(s, cfg: Cfg, side: int, sig_row: int, k: float, P: float, hi: float, lo: float) -> dict | None:
    """One trade: the signal bar's last minute is sig_row; fill at the next open (R3's 3 tries); `cfg`'s exits."""
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if a is None:
        return None
    a = a.astype(np.float64)
    c0, px = E.next_open(a, sig_row + 1, sig_row + 1 + E.FILL_TRIES)
    v = cfg.base
    liqx = cfg.has("L1")
    if c0 < 0 or (not liqx and px <= P) or not px > 0:
        return None
    lots = max(1, cfg.lots)
    qty = s.lot * lots
    e = float(E.FILLS.buy(np.array([px]), np.array([thin_lots(a, c0, s.lot, lots)]))[0])
    if liqx:
        trig = E.floor_tick(e * (1 - K.LIQ_STOP))
        tgt, lad_t = 0.0, 0.0
    else:
        trig = E.floor_tick(e - P)
        tgt = v.tgt_mult * P
        lad_t = v.tgt_mult * P if v.ladder else 0.0
    be = e + be_unit(cfg, e, qty, s.und, s.day)
    ts_row, ts_need = -1, 0.0
    tsm = K.LIQ_TIME if liqx else (K.T20 if cfg.has("X3") else (K.T40 if cfg.has("X2") else 0))
    if tsm:
        ts_row = c0 + tsm - 1
        ts_need = K.TS_GAIN * e
    g3_need = K.G3_SHARE * tgt if (cfg.has("X4") and tgt > 0) else 0.0
    srt = 0.0
    if cfg.has("X5"):
        lvl = s.prev[1] if side > 0 else s.prev[2]
        ref = last_close(s.ix, c0 - 1)
        if np.isfinite(lvl) and np.isfinite(ref) and side * (lvl - ref) > 0:
            srt = float(lvl)
    fail = (2 if cfg.arm == "orb_sweep" else 1) if cfg.has("X6") else 0
    sq = E.SQ_EXPIRY if s.expiry else E.SQ_ROW
    ix = s.ix.astype(np.float64)
    r, p, why = walk(a[:, O], a[:, H], a[:, L], a[:, C], ix[:, H], ix[:, L], ix[:, C], c0, e, trig, tgt, lad_t, be,
                     K.BE10 if cfg.has("X1") else 0.0, ts_row, ts_need, g3_need, side, srt, fail, hi, lo, sq)
    stale = False
    if why in (0, 1):
        x = float(E.FILLS.sell(np.array([p]), np.array([thin_lots(a, r, s.lot, lots)]), stop=True)[0])
    else:
        rr, p2 = E.next_open(a, r)
        if rr < 0:
            fin = np.where(np.isfinite(a[:r, C]))[0]
            rr = int(fin[-1]) if len(fin) else c0
            p2 = float(a[rr, C])
            stale = True
        r = rr
        x = float(E.FILLS.sell(np.array([p2]), np.array([thin_lots(a, rr, s.lot, lots)]))[0])
    ch = charge(cfg, True, e, qty, s.und, s.day) + charge(cfg, False, x, qty, s.und, s.day)
    gross = (x - e) * qty
    return dict(und=s.und, day=s.day, side=side, sig_row=sig_row, k=k, entry_row=c0, exit_row=r, entry=e, exit=x,
                why=WHY[8] if stale else WHY[why], qty=qty, lots=lots, gross=gross, charges=ch, net=gross - ch,
                expiry=bool(s.expiry))


def choose_strike(s, cfg: Cfg, side: int, row: int, expiry: date | None) -> float:
    if cfg.has("S1"):
        return strike_by_delta(s, side, row, 0.60, expiry)
    if cfg.has("S2"):
        return strike_by_delta(s, side, row, 0.50, expiry)
    if cfg.has("S3"):
        return E.strike_for(s, side, 1)
    return s.atm


def confirm(arm: str, b5: np.ndarray, b: int, side: int, hi: float, lo: float) -> bool:
    """W1: the next bar (b) confirms a signal of bar b-1."""
    c = b5[b, 3]
    if not np.isfinite(c):
        return False
    if arm == "orb_sweep":
        return bool(c < hi) if side < 0 else bool(c > lo)
    return bool(c > hi) if side > 0 else bool(c < lo)


# ================================================================================================ the arm on one session
def run_session(s, cfg: Cfg, ctx: dict | None = None, keep_fn=None) -> list:
    """The arm's trades on one session under `cfg`. Filtered breaks are kept in the list with skipped=True: they hold
    their side, count in the caps and block the arm until their would-be exit (the app's rule). keep_fn(s, side, row,
    feats) -> dict of extra verdicts {'pick': bool|None, 'net': bool|None} for F1 / F2."""
    ctx = ctx or {}
    b5 = bars5(s.ix)
    hi, lo = E.opening_range(b5)
    if not (np.isfinite(hi) and np.isfinite(lo)) or not np.isfinite(s.atm):
        return []
    v = cfg.base
    P = K.K3.PTS[s.und]
    first, last = v.win
    out, per_side, n, free_bar = [], {1: 0, -1: 0}, 0, -1
    b = first
    while b <= last:
        if b <= free_bar:
            b += 1
            continue
        if n >= v.max_entries:
            break
        side = E.signal(v.arm, b5, b, hi, lo)
        if not side or (v.side_cap and per_side[side] >= v.side_cap):
            b += 1
            continue
        sb = b
        if cfg.has("W1"):
            if b + 1 > last or not confirm(v.arm, b5, b + 1, side, hi, lo):
                b += 1
                continue
            sb = b + 1
        row = 5 * sb + 4
        k = choose_strike(s, cfg, side, row, ctx.get("expiry_date"))
        f = features(s, side, row, hi, lo, b5, k)
        t = simulate(s, cfg, side, row, k, P, hi, lo)
        if t is None:
            b += 1
            continue
        extra = keep_fn(s, side, row, f) if keep_fn is not None else {}
        c2 = dict(ctx, **extra)
        skipped = [fid for fid in cfg.filters if skip_reason(fid, s, v.arm, side, row, hi, lo, b5, c2)]
        t["feats"] = f
        t["skipped"] = bool(skipped)
        t["skip_by"] = skipped
        t["bar"] = sb
        out.append(t)
        per_side[side] += 1
        n += 1
        free_bar = t["exit_row"] // 5
        b = sb + 1
    return out


def taken(trades: list) -> list:
    return [t for t in trades if not t.get("skipped")]


# ================================================================================================ scoring
def score(trades: list, days: list) -> dict:
    """trades: taken trades; days: every session day of the period (a day without a trade counts 0)."""
    by_day = {d: 0.0 for d in days}
    for t in trades:
        by_day[t["day"]] = by_day.get(t["day"], 0.0) + t["net"]
    ds = sorted(by_day)
    dn = np.array([by_day[d] for d in ds]) if ds else np.zeros(0)
    eq = np.concatenate([[0.0], np.cumsum(dn)])
    dd = float((eq - np.maximum.accumulate(eq)).min()) if len(dn) else 0.0
    months: dict = {}
    for d in ds:
        months[(d.year, d.month)] = months.get((d.year, d.month), 0.0) + by_day[d]
    nets = np.array([t["net"] for t in trades]) if trades else np.zeros(0)
    wins, losses = nets[nets > 0], nets[nets <= 0]
    aw = float(wins.mean()) if len(wins) else 0.0
    al = float(losses.mean()) if len(losses) else 0.0
    traded = [by_day[d] for d in {t["day"] for t in trades}]
    srt = np.sort(nets)[::-1]
    return dict(
        trades=int(len(nets)), days=len(ds), win=float((nets > 0).mean()) if len(nets) else 0.0,
        be_win=float(abs(al) / (aw + abs(al))) if (aw + abs(al)) > 0 else 0.0, avg_win=aw, avg_loss=al,
        net=float(nets.sum()), per_session=float(nets.sum() / len(ds)) if ds else 0.0,
        worst_day=float(min(traded)) if traded else 0.0, worst_month=float(min(months.values())) if months else 0.0,
        max_dd=dd, net_wo5=float(srt[5:].sum()) if len(srt) > 5 else 0.0,
    )


def gate1(v: dict, b: dict) -> tuple:
    """(passes, branch) of PREREG gate 1 for one period: 'net' (net up, DD not worse) or 'win' (+5 points, net not lower)."""
    if v["net"] > b["net"] + 1e-9 and v["max_dd"] >= b["max_dd"] - 1e-9:
        return True, "net"
    if v["win"] >= b["win"] + K.WIN_UP - 1e-12 and v["net"] >= b["net"] - 1e-9:
        return True, "win"
    return False, None


def within_limits(s: dict) -> bool:
    return s["max_dd"] >= -K.DD_LIMIT and s["worst_month"] >= -K.MONTH_LIMIT


def bh(ps: list) -> list:
    return E.bh(ps) if ps else []


def twin_p(real: float, twins: np.ndarray) -> float:
    """One-sided: share of twins at least as good as the real value ((1 + count) / (1 + n))."""
    twins = np.asarray(twins, float)
    return float((1 + np.sum(twins >= real - 1e-9)) / (1 + len(twins)))


def bh_total(p, m_total: int) -> np.ndarray:
    """Benjamini-Hochberg q with m_total tests in the family; the earlier tests are taken as p = 1 (as R167's bh)."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)
