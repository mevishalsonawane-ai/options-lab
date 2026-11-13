"""R171 strategies (PREREG.md section 1) on the release data, plus each trade's pre-entry gate inputs (section 3).

Every function here reads only rows BEFORE a trade's fill minute for its gate inputs (the look-ahead guard in
test_r171.py checks that changing anything at or after the fill minute leaves them unchanged).
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", HERE):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import r171data as D  # noqa: E402
from obuy.costs import Costs  # noqa: E402

from options_lab import costs as OC  # noqa: E402
from options_lab.features import gates as G  # noqa: E402

COSTS = Costs("dated")
REGIMES = ("roll", "quoted", "stress")
N = D.N
O, H, L, C, V = D.O, D.H, D.L, D.C, D.V

# R51 / R72 (DonchianArm.kt, APP_SPEC.md)
LOOKBACK = 20
FIRST_ENTRY_ROW, LAST_ENTRY_ROW = 5, 355          # 09:20 .. 15:10
SQ_ROW = 360                                      # 15:15 square-off (exit at that minute's open)
FILL_SLACK = 3                                    # the fill minute or up to 2 minutes later
MAX_PER_SIDE = 2
STOP = 0.15
TMAX = 30
LADDER = ((0.30, None), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05))   # None = breakeven + charges (R64); L25 last
ST_N, ST_MULT = 10, 3.0
WARMUP_SESSIONS = 2
MIN_BREADTH = 4                                   # of 5; MIDCPNIFTY has no data and counts as not breaking


# ================================================================================================ 5-minute bars
def bars5(days) -> np.ndarray:
    """(n_days * 75, 4) continuous 09:15-anchored 5-minute O H L C (NaN when the bar has no minute)."""
    out = np.full((len(days) * 75, 4), np.nan)
    for di, d in enumerate(days):
        ix = d.ix
        for b in range(75):
            seg = ix[5 * b:5 * b + 5]
            ok = np.isfinite(seg[:, 3])
            if not ok.any():
                continue
            s = seg[ok]
            out[di * 75 + b] = (s[0, 0], np.nanmax(s[:, 1]), np.nanmin(s[:, 2]), s[-1, 3])
    return out


def donchian_side(b: np.ndarray, i: int, n: int = LOOKBACK) -> int:
    """DonchianArm.sideAt: the close of bar i vs the highest high / lowest low of bars i-n .. i-1 (a missing price among
    them, or fewer than n bars: 0)."""
    if i < n or i >= len(b):
        return 0
    w = b[i - n:i]
    if not np.isfinite(w[:, 1]).all() or not np.isfinite(w[:, 2]).all():
        return 0
    c = b[i, 3]
    if not math.isfinite(c):
        return 0
    if c > w[:, 1].max():
        return 1
    if c < w[:, 2].min():
        return -1
    return 0


def supertrend(b: np.ndarray, n: int = ST_N, mult: float = ST_MULT) -> np.ndarray:
    """DonchianArm.supertrendLines' direction, line for line (Wilder ATR seeded with the mean of the first n true ranges;
    a bar with a missing price is left out and gets 0). Bar i's value uses bars <= i only."""
    out = np.zeros(len(b), dtype=np.int8)
    ok = np.flatnonzero(np.isfinite(b[:, 1]) & np.isfinite(b[:, 2]) & np.isfinite(b[:, 3]))
    m = len(ok)
    if m == 0:
        return out
    h, lo, c = b[ok, 1], b[ok, 2], b[ok, 3]
    tr = np.empty(m)
    tr[0] = h[0] - lo[0]
    tr[1:] = np.maximum.reduce([h[1:] - lo[1:], np.abs(h[1:] - c[:-1]), np.abs(lo[1:] - c[:-1])])
    atr = np.full(m, np.nan)
    if m >= n:
        atr[n - 1] = tr[:n].mean()
        for i in range(n, m):
            atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    up = dn = math.nan
    cur = 0
    for i in range(m):
        if not math.isfinite(atr[i]):
            continue
        mid = (h[i] + lo[i]) / 2.0
        bu, bd = mid - mult * atr[i], mid + mult * atr[i]
        if cur == 0:
            up, dn, cur = bu, bd, (1 if c[i] >= mid else -1)
        else:
            up = max(bu, up) if c[i - 1] > up else bu
            dn = min(bd, dn) if c[i - 1] < dn else bd
            if cur == -1 and c[i] > dn:
                cur = 1
            elif cur == 1 and c[i] < up:
                cur = -1
        out[ok[i]] = cur
    return out


def atm_strike(spot: float, step: float) -> float:
    return math.floor(spot / step + 0.5) * step


def itm_strike(side: int, spot: float, step: float, money: int = 1) -> float:
    k = atm_strike(spot, step)
    return k - money * step if side > 0 else k + money * step


# ================================================================================================ costs
def half_spread(premium, regime: str) -> float:
    return OC.spread_per_unit(premium=float(max(premium, 0.0)), regime=regime) / 2.0


def net_rs(entry_raw: float, exit_raw: float, qty: int, d: date, regime: str) -> float:
    """Bought option, 1 round trip: half the regime's spread crossed each way, dated statutory charges on both orders."""
    buy = entry_raw + half_spread(entry_raw, regime)
    sell = max(exit_raw - half_spread(exit_raw, regime), 0.0)
    dn = np.array([(d - date(1970, 1, 1)).days])
    ch = (COSTS.charge(True, np.array([buy]), np.array([qty]), dn)[0]
          + COSTS.charge(False, np.array([sell]), np.array([qty]), dn)[0])
    return (sell - buy) * qty - ch


def charges_per_unit(px: float, qty: int, d: date) -> float:
    dn = np.array([(d - date(1970, 1, 1)).days])
    return float(COSTS.charge(True, np.array([px]), np.array([qty]), dn)[0]
                 + COSTS.charge(False, np.array([px]), np.array([qty]), dn)[0]) / qty


# ================================================================================================ XL exits
def simulate_xl(A: np.ndarray, e: int, qty: int, d: date, sq: int = SQ_ROW):
    """The arm's XL exits on one contract's (375, 5) minutes, as research/hunt/r51/engine.simulate (raw prices).
    Returns (fill_row, entry_raw, exit_row, exit_raw, reason) or None when there is no fill within the slack."""
    o, h, lo, c = A[:, O], A[:, H], A[:, L], A[:, C]
    ef = -1
    for j in range(e, min(e + FILL_SLACK, sq)):
        if math.isfinite(o[j]) and o[j] > 0:
            ef = j
            break
    if ef < 0:
        return None
    E = float(o[ef])
    stop = E * (1 - STOP)
    lock = -1.0
    peak = E
    last = E
    tend = ef + TMAX - 1
    be = E + charges_per_unit(E, qty, d)
    m = ef
    while m < sq:
        if not math.isfinite(lo[m]):
            if m == tend:
                return ef, E, m, last, "time"
            m += 1
            continue
        lvl = max(stop, lock)
        if lo[m] <= lvl:
            px = o[m] if (math.isfinite(o[m]) and o[m] < lvl) else lvl
            return ef, E, m, float(px), ("lock" if lock >= stop else "stop")
        last = float(c[m])
        peak = max(peak, h[m])
        g = peak / E - 1.0
        for trig, keep in LADDER:
            if g >= trig - 1e-12:
                lv = be if keep is None else E * (1 + keep)
                lock = max(lock, lv)
        if m == tend:
            return ef, E, m, last, "time"
        m += 1
    px = o[sq] if sq < N and math.isfinite(o[sq]) else math.nan
    if not math.isfinite(px):
        for j in range(sq + 1, min(sq + 4, N)):
            if math.isfinite(o[j]):
                px = o[j]
                break
    if not math.isfinite(px):
        px = last
    return ef, E, sq, float(px), "squareoff"


# ================================================================================================ trades
@dataclass
class Trade:
    strat: str
    und: str
    day: date
    side: int
    entry_row: int          # the intended fill minute (the decision's next minute); gate windows end before it
    fill_row: int
    exit_row: int
    strike: float
    right: str
    exp: date | None
    lot: int
    entry_raw: float
    exit_raw: float
    reason: str
    net: dict               # regime -> Rs
    feat: dict | None = None


def _caps_walk(cands, sim):
    """One position a side, at most 2 entries a side a day, per index (DonchianArm.mayTake), in time order. `sim(c)`
    returns a Trade or None (no contract / no fill: nothing was bought, so nothing is held or counted)."""
    out = []
    held: dict = {}
    taken: dict = {}
    for c in sorted(cands, key=lambda x: (x["day"], x["row"], x["und"], x["side"])):
        key = (c["und"], c["day"], c["side"])
        if held.get(key, -1) >= c["row"]:
            continue
        if taken.get(key, 0) >= MAX_PER_SIDE:
            continue
        t = sim(c)
        if t is None:
            continue
        held[key] = t.exit_row
        taken[key] = taken.get(key, 0) + 1
        out.append(t)
    return out


def _sim_arm(strat: str, store: dict):
    def sim(c):
        d = c["d"]
        right = "C" if c["side"] > 0 else "P"
        A = d.opts.get((c["strike"], right))
        if A is None:
            return None
        r = simulate_xl(A, c["row"], d.lot, d.day)
        if r is None:
            return None
        ef, E, xr, X, why = r
        return Trade(strat, c["und"], d.day, c["side"], c["row"], ef, xr, c["strike"], right, d.exp, d.lot, E, X, why,
                     {g: net_rs(E, X, d.lot, d.day, g) for g in REGIMES})
    return sim


def donchian_candidates(store: dict, unds=D.OPTION_UNDS, use_st: bool = True, breadth: bool = False) -> list:
    """R51 (use_st, no breadth) or R72 (breadth, no Supertrend) candidates on the traded indices."""
    series = {}
    for u in (D.INDEX_UNDS if breadth else unds):
        days = store[u]
        b = bars5(days)
        side = np.array([donchian_side(b, i) for i in range(len(b))], dtype=np.int8)
        series[u] = dict(days=days, b=b, side=side, st=supertrend(b) if use_st else None,
                         pos={d.day: k for k, d in enumerate(days)})
    cands = []
    for u in unds:
        S = series[u]
        exp_days = D.expiry_days(S["days"])
        for di, d in enumerate(S["days"]):
            if di < WARMUP_SESSIONS or d.day in exp_days:
                continue
            for bi in range(75):
                row = 5 * (bi + 1)
                if not FIRST_ENTRY_ROW <= row <= LAST_ENTRY_ROW:
                    continue
                i = di * 75 + bi
                s = int(S["side"][i])
                if s == 0:
                    continue
                if use_st and int(S["st"][i]) != s:
                    continue
                if breadth:
                    n = 0
                    for v in D.INDEX_UNDS:
                        k = series[v]["pos"].get(d.day)
                        if k is not None and int(series[v]["side"][k * 75 + bi]) == s:
                            n += 1
                    if n < MIN_BREADTH:
                        continue
                spot = d.ix[row - 1, C]
                if not math.isfinite(spot):
                    spot = S["b"][i, 3]
                cands.append(dict(und=u, d=d, day=d.day, row=row, side=s,
                                  strike=itm_strike(s, spot, D.STEP[u])))
    return cands


def r51_trades(store: dict) -> list:
    return _caps_walk(donchian_candidates(store, use_st=True, breadth=False), _sim_arm("R51", store))


def r72_trades(store: dict) -> list:
    return _caps_walk(donchian_candidates(store, use_st=False, breadth=True), _sim_arm("R72", store))


# ================================================================================================ Liquidity 15+5
def _lb_day(d):
    c = d.ix[:, C].copy()
    s = np.where(np.isfinite(c), c, np.nan)
    # ml_long.grid: close forward / back filled; open / high / low default to the close
    cs = _ffill_bfill(s)
    I = {"close": cs}
    for k, j in (("open", O), ("high", H), ("low", L)):
        a = d.ix[:, j]
        I[k] = np.where(np.isfinite(a), a, cs)
    chain = {}
    for (k, r), A in d.opts.items():
        if np.isfinite(A[:, C]).sum() > 150:
            cc = _ffill_bfill(A[:, C])
            g = {"close": cc}
            for nm, j in (("open", O), ("high", H), ("low", L)):
                g[nm] = np.where(np.isfinite(A[:, j]), A[:, j], cc)
            chain[(k, "CE" if r == "C" else "PE")] = g
    return dict(day=d.day, exp=d.exp, I=I, chain=chain)


def _ffill_bfill(a: np.ndarray) -> np.ndarray:
    a = np.asarray(a, float).copy()
    ok = np.isfinite(a)
    if not ok.any():
        return a
    idx = np.where(ok, np.arange(len(a)), 0)
    np.maximum.accumulate(idx, out=idx)
    a = a[idx]
    first = np.flatnonzero(ok)[0]
    a[:first] = a[first]
    return a


def liq_trades(store: dict, unds=D.OPTION_UNDS) -> list:
    import liquidity_break as lb
    from indicator.liquidity import pool_zones, swing_zones
    lb.SLIP, lb.CHG, lb.LOT = 0.0, 0.0, 1          # raw prices out; costs are R171's own
    out = []
    for u in unds:
        days = [d for d in store[u] if d.exp is not None]
        ldays = [_lb_day(d) for d in days]
        byday = {d.day: d for d in days}
        warm = {d.day for d in days[:WARMUP_SESSIONS]}
        for tf in (15, 5):
            b = lb.bars(ldays, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            tr = lb.simulate(ldays, b, zones, "both", True)
            for r in tr.itertuples():
                if r.day in warm or r.key is None or not isinstance(r.key, tuple):
                    continue
                d = byday[r.day]
                k, right = r.key
                E = float(r.px)
                X = E + float(r.rs)
                m0 = int(r.m0)
                xr = min(m0 + int(r.held), N - 1)
                out.append(Trade(f"LIQ{tf}", u, d.day, int(r.sign), m0, m0, xr, float(k), "C" if right == "CE" else "P",
                                 d.exp, d.lot, E, X, str(r.why), {g: net_rs(E, X, d.lot, d.day, g) for g in REGIMES}))
    return out


# ================================================================================================ gate inputs
ROLL_WINDOWS = [(s, s + 30) for s in range(0, 360, 30)]       # 12 non-overlapping 30-minute windows, 09:15-based


def window_rolls(A: np.ndarray) -> list:
    """Relative Roll spread of each 30-minute window with >= 20 traded minutes (else None)."""
    out = []
    for s, e in ROLL_WINDOWS:
        v = np.nan_to_num(A[s:e, V], nan=0.0)
        if (v > 0).sum() < 20:
            out.append(None)
            continue
        out.append(G.roll_relative(A[s:e, C][v > 0]))
    return out


HISTORY_LOOKBACK = 10          # sessions searched back for the contract's previous 5 traded sessions


class RollHistory:
    """For each index: (expiry, strike, right) -> [(day, [window rolls])], built lazily, read only for EARLIER days."""

    def __init__(self, store: dict):
        self.store = store
        self.cache: dict = {}

    def normal(self, und: str, day: date, exp, strike: float, right: str, floor: float) -> float | None:
        days = self.store[und]
        pos = {d.day: k for k, d in enumerate(days)}
        k0 = pos.get(day)
        if k0 is None:
            return None
        vals, seen = [], 0
        for k in range(k0 - 1, max(k0 - 1 - HISTORY_LOOKBACK, -1), -1):
            d = days[k]
            if d.exp != exp:
                continue
            A = d.opts.get((strike, right))
            if A is None:
                continue
            key = (und, d.day, strike, right)
            if key not in self.cache:
                self.cache[key] = window_rolls(A)
            w = self.cache[key]
            if all(x is None for x in w):
                continue
            vals += w
            seen += 1
            if seen >= 5:
                break
        return G.roll_normal(vals, floor=floor)


def gate_inputs(A: np.ndarray, e: int, lot: int, days_to_exp: int | None, normal_fn=None) -> dict:
    """Everything the gates read for a trade whose fill minute is row e on contract minutes A. Only rows < e are read."""
    f = {}
    for W in (30, 60):
        sl = G.pre_entry_window(N, e, W)
        f[f"vol{W}"] = np.nan_to_num(A[sl, V], nan=0.0)
    sl30 = G.pre_entry_window(N, e, 30)
    closes = A[:e, C]
    ok = np.flatnonzero(np.isfinite(closes) & (np.nan_to_num(A[:e, V], nan=0.0) > 0))
    f["premium"] = float(closes[ok[-1]]) if ok.size else None
    f["median_lots30"] = G.median_lots_per_minute(A[sl30, V], lot)
    v30 = np.nan_to_num(A[sl30, V], nan=0.0)
    f["roll_rel"] = G.roll_relative(A[sl30, C][v30 > 0])
    w = A[sl30, C][v30 > 0]
    f["roll_pts"] = G.roll_spread(w)
    f["dte"] = days_to_exp
    f["roll_normal"] = None
    if normal_fn is not None and f["premium"]:
        f["roll_normal"] = normal_fn(OC.TICK_SIZE / f["premium"])
    return f


def attach_features(trades: list, store: dict, hist: RollHistory | None = None) -> None:
    hist = hist or RollHistory(store)
    byday = {u: {d.day: d for d in store[u]} for u in D.OPTION_UNDS}
    for t in trades:
        d = byday[t.und][t.day]
        A = d.opts[(t.strike, t.right)]
        dte = (t.exp - t.day).days if t.exp is not None else None
        t.feat = gate_inputs(A, t.entry_row, t.lot, dte,
                             normal_fn=lambda fl, t=t: hist.normal(t.und, t.day, t.exp, t.strike, t.right, fl))
