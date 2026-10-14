"""R26 engine (PREREG.md): the walls, the OI-wall-blast trigger, its alternatives, the exits E1 / E1b / E2 / E3, the
random-time twins and the extra metrics. Fills, charges, out-of-range prices and the base metrics are R25's
(research/hunt/r25/r25lib.py, r25rules.py), unchanged.

Grid: row t = the bar that STARTS at 09:15 + t and CLOSES at t + 1. A decision at row t reads rows <= t only and acts at
row t + 1 (the next bar's open). Times in rules are bar CLOSE times: R.close_row(13, 45) is the bar that starts 13:44.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace

import numpy as np

import r26cfg as K26
import r25cfg as K
import r25lib as R
import r25rules as RU
from r25data import C, N, OI, V, Day, minute

ANCHOR = R.close_row(13, 0)        # the wall is read here (P)
NOON = R.close_row(12, 0)          # the OI reference starts here
FIRST = R.close_row(13, 45)        # entry window: bar closes 13:45 .. 14:30
LAST = R.close_row(14, 30)
HOLD = 16                          # "stays above for more than 15 minutes": 16 consecutive 1-min closes
LOOK = 15                          # the 15-minute windows (volume, price and OI change)
VOL_MULT = 2.0
THETA = {"NIFTY": 0.15, "SENSEX": 0.15, "BANKNIFTY": 0.25, "FINNIFTY": 0.25}
BAND = (15.0, 30.0)                # the "premium Rs 15-30" filter
TARGET = 4.0                       # E2
EXIT_ROWS = {"E1": minute(15, 5), "E1b": minute(15, 20), "E2": minute(15, 5), "E3": minute(15, 5)}
LEVEL = 1000.0                     # A8: SENSEX round levels


# ------------------------------------------------------------------------------------------------ rules
@dataclass(frozen=True)
class Rule:
    id: str
    note: str
    wall: str = "anchor"           # anchor (read at 13:00) | rolling (read at t-16, fresh break)
    oiref: str = "peak"            # peak (highest OI 12:00..t) | noon (the 12:00 value)
    filt: bool = True              # the Rs 15-30 price filter
    exits: str = "E2"
    offset: int = 1                # strikes from ATM
    level: bool = False            # A8: the wall is the 1,000-point level
    use_oi: bool = True            # B1 drops the OI conditions
    unds: tuple = K26.UNDS
    budget: float = K26.BUDGET


P = Rule("P", "primary: wall at 13:00, wall OI down >= 15%/25% from its peak since 12:00, Rs 15-30, target 4x else 15:05")
RULES = [
    P,
    replace(P, id="A1", wall="rolling", note="A1: rolling wall (read at t-16, a fresh break held 16 closes)"),
    replace(P, id="A2", oiref="noon", note="A2: wall OI down from its 12:00 value"),
    replace(P, id="A3", filt=False, note="A3: no Rs 15-30 price filter"),
    replace(P, id="A4", exits="E1", note="A4: hold to 15:05"),
    replace(P, id="A5", exits="E1b", note="A5: hold to 15:20"),
    replace(P, id="A6", exits="E3", note="A6: half at 2x, step-lock the rest (2x->1x, 3x->2x, ...), else 15:05"),
    replace(P, id="A7", offset=2, unds=("BANKNIFTY",), note="A7: BANKNIFTY ATM+2 / ATM-2"),
    replace(P, id="A8", level=True, unds=("SENSEX",), note="A8: SENSEX, the wall is the 1,000-point level"),
    replace(P, id="B1", use_oi=False, note="B1 (baseline b): P without the OI conditions"),
]


@dataclass
class Signal:
    row: int
    side: int          # +1 CE (UP), -1 PE (DOWN)
    strike: float
    ref: float
    wall: float
    wall_edge: bool    # the wall was the outermost stored strike (the true wall may be outside the range)


# ------------------------------------------------------------------------------------------------ per-day arrays
class Chain:
    """Point-in-time arrays of one day: forward-filled OI per strike, the stored chain, the chain's volume. Row r of
    every array depends on rows <= r only."""

    def __init__(self, day: Day):
        self.day = day
        self.close = day.ix[:, 3].astype(float)
        self.k = {}
        self.oi = {}
        self.present = {}
        vol = np.zeros(N)
        for s in ("C", "P"):
            ks = sorted(k for k, sd in day.opts if sd == s)
            self.k[s] = np.array(ks, dtype=float)
            o = np.full((len(ks), N), np.nan)
            pr = np.zeros((len(ks), N), dtype=bool)
            for i, k in enumerate(ks):
                a = day.opts[(k, s)]
                x = np.where(np.nan_to_num(a[:, OI]) > 0, a[:, OI], np.nan)
                o[i] = _ffill(x)
                has = ~np.isnan(a[:, C])
                # a bar in rows r-4 .. r
                cs = np.cumsum(has)
                pr[i] = (cs - np.r_[np.zeros(5), cs[:-5]]) > 0
                vol += np.nan_to_num(a[:, V])
            self.oi[s] = o
            self.present[s] = pr
        self.vol = vol
        self.cvol = np.cumsum(vol)

    def oi_at(self, side: str, k: float, r: int) -> float:
        i = np.nonzero(self.k[side] == k)[0]
        return float(self.oi[side][i[0], r]) if len(i) and r >= 0 else math.nan

    def oi_path(self, side: str, k: float) -> np.ndarray:
        i = np.nonzero(self.k[side] == k)[0]
        return self.oi[side][i[0]] if len(i) else np.full(N, np.nan)

    def wall(self, side: str, r: int) -> tuple[float, bool]:
        """(strike, outermost?) of the highest-OI stored strike at row r; (NaN, False) when none."""
        if r < 0:
            return math.nan, False
        pr = self.present[side][:, r]
        oi = self.oi[side][:, r]
        ok = pr & ~np.isnan(oi)
        if not ok.any():
            return math.nan, False
        ks = self.k[side][ok]
        v = oi[ok]
        s = self.close[r]
        best = v.max()
        cand = ks[v == best]
        k = float(cand[np.argmin(np.abs(cand - s))]) if not math.isnan(s) else float(cand[0])
        stored = self.k[side][pr]
        edge = k == (stored.max() if side == "C" else stored.min())
        return k, bool(edge)

    def sum_oi_change(self, side: str, r: int, back: int = LOOK) -> float:
        """Summed OI at r minus at r-back, over strikes stored at both rows with OI at both."""
        if r - back < 0:
            return math.nan
        a, b = self.oi[side][:, r], self.oi[side][:, r - back]
        ok = self.present[side][:, r] & self.present[side][:, r - back] & ~np.isnan(a) & ~np.isnan(b)
        return float(a[ok].sum() - b[ok].sum()) if ok.any() else math.nan

    def vol_spike(self, r: int) -> bool:
        if r < LOOK:
            return False
        last = self.cvol[r] - self.cvol[r - LOOK]
        avg = self.cvol[r] * LOOK / (r + 1)
        return avg > 0 and last >= VOL_MULT * avg - 1e-9


def _ffill(x: np.ndarray) -> np.ndarray:
    idx = np.where(~np.isnan(x), np.arange(len(x)), -1)
    np.maximum.accumulate(idx, out=idx)
    out = np.where(idx >= 0, x[np.maximum(idx, 0)], np.nan)
    return out


def chain(day: Day) -> Chain:
    c = day.__dict__.get("_r26chain")
    if c is None:
        c = Chain(day)
        day.__dict__["_r26chain"] = c
    return c


def masked(day: Day, t: int) -> Day:
    """R25's masked copy (nothing after row t), without R26's per-day cache (it belongs to the full day)."""
    d = R.masked(day, t)
    d.__dict__.pop("_r26chain", None)
    return d


def poisoned(day: Day, t: int, value: float = 1e6) -> Day:
    d = R.poisoned(day, t, value)
    d.__dict__.pop("_r26chain", None)
    return d


# ------------------------------------------------------------------------------------------------ the trigger
def pick(day: Day, rule: Rule, sgn: int, t: int) -> tuple[float, float] | None:
    """(strike, signal price) of ATM+offset CE / ATM-offset PE at row t, traded in the last 5 bars and passing the
    price filter when the rule has it; None otherwise."""
    s = R.spot(day, t)
    if math.isnan(s):
        return None
    st = K.STEP[day.und]
    k = R.atm(day, s) + sgn * rule.offset * st
    a = day.opts.get((k, "C" if sgn > 0 else "P"))
    if a is None:
        return None
    p, row = R.ltp(a, t)
    if row < 0 or row < t - (R.TRADED_BARS - 1) or math.isnan(p) or p <= 0:
        return None
    if rule.filt and not (BAND[0] - R.EPS <= p <= BAND[1] + R.EPS):
        return None
    return k, p


def _wall(ch: Chain, day: Day, rule: Rule, sgn: int, r: int) -> tuple[float, bool]:
    side = "C" if sgn > 0 else "P"
    if rule.level:
        s = ch.close[r] if r >= 0 else math.nan
        if math.isnan(s):
            return math.nan, False
        lv = (math.floor(s / LEVEL) + 1) * LEVEL if sgn > 0 else (math.ceil(s / LEVEL) - 1) * LEVEL
        return lv, False
    return ch.wall(side, r)


def conditions(day: Day, rule: Rule, sgn: int, t: int, ch: Chain | None = None) -> dict:
    """Every condition of the trigger at row t for one direction (reads rows <= t only)."""
    ch = ch or chain(day)
    side = "C" if sgn > 0 else "P"
    c = ch.close
    out = {"wall": math.nan, "edge": False, "break": False, "oi_fall": False, "vol": False, "price": False,
           "sum_oi": False}
    if t - HOLD < 0:
        return out
    wr = t - HOLD if rule.wall == "rolling" else ANCHOR
    if t - HOLD < wr:
        return out
    w, edge = _wall(ch, day, rule, sgn, wr)
    out["wall"], out["edge"] = w, edge
    if math.isnan(w):
        return out
    run = c[t - HOLD + 1: t + 1]
    before = c[wr: t - HOLD + 1] if rule.wall == "anchor" else c[t - HOLD: t - HOLD + 1]
    if not np.isnan(run).any():
        beyond = (run > w).all() if sgn > 0 else (run < w).all()
        was = (before <= w).any() if sgn > 0 else (before >= w).any()
        out["break"] = bool(beyond and was)
    path = ch.oi_path(side, w)
    now = path[t]
    ref = np.nanmax(path[NOON: t + 1]) if np.isfinite(path[NOON: t + 1]).any() else math.nan
    if rule.oiref == "noon":
        ref = path[NOON]
    out["oi_fall"] = bool(not math.isnan(now) and not math.isnan(ref) and ref > 0
                          and now <= (1 - THETA[day.und]) * ref + 1e-9)
    out["oi_fall_pct"] = (1 - now / ref) * 100 if not math.isnan(now) and not math.isnan(ref) and ref > 0 else math.nan
    out["vol"] = ch.vol_spike(t)
    mv = c[t] - c[t - LOOK]
    out["price"] = bool(not math.isnan(mv) and mv * sgn > 0)
    d = ch.sum_oi_change(side, t)
    out["sum_oi"] = bool(not math.isnan(d) and d < 0)
    return out


def fires(cond: dict, rule: Rule) -> bool:
    base = cond["break"] and cond["vol"] and cond["price"]
    return base and (not rule.use_oi or (cond["oi_fall"] and cond["sum_oi"]))


def signal(day: Day, rule: Rule) -> Signal | None:
    """The first minute in 13:45 .. 14:30 at which the trigger (UP first, then DOWN) and the price filter hold."""
    if day.und not in rule.unds:
        return None
    ch = chain(day)
    for t in range(FIRST, LAST + 1):
        for sgn in (1, -1):
            cd = conditions(day, rule, sgn, t, ch)
            if not fires(cd, rule):
                continue
            pk = pick(day, rule, sgn, t)
            if pk is None:
                continue
            return Signal(t, sgn, pk[0], pk[1], cd["wall"], cd["edge"])
    return None


# ------------------------------------------------------------------------------------------------ the trade
def simulate(day: Day, k: float, sgn: int, sig_row: int, ref: float, exits: str, budget: float | None = K26.BUDGET,
             mult: float = 1.0, oor_zero: bool = False, rule: str = "", arm: str = "high") -> R.Trade | None:
    """One ticket with R25's entry, fills, charges and out-of-range prices. Lots = floor(budget / (fill x lot)), at
    least 1 (the real risk is then above the budget; `over_budget`). exits: E1 | E1b | E2 | E3."""
    side = "C" if sgn > 0 else "P"
    f = R.enter(day, k, side, sig_row, ref, mult)
    if f is None or day.lot <= 0:
        return None
    fr, e = f
    lots = 1 if budget is None else max(1, int(math.floor(budget / (e * day.lot) + 1e-9)))
    tr = R.Trade(day.und, day.day, rule, sgn, k, sig_row, fr, e, lots, lots * day.lot)
    tr.over_budget = budget is not None and e * day.lot > budget
    a = day.opts[(k, side)]
    exit_row = EXIT_ROWS[exits]
    held = lots
    t2 = R.floor_tick(2.0 * e)
    t4 = R.floor_tick(TARGET * e)
    half_sold = False
    best_hi = e
    lock = -math.inf
    conf_peak = e

    def sell(r, n, px, why):
        tr.exits.append((r, n * day.lot, px, why))

    r = fr
    while r < exit_row and held > 0:
        o, hi, c, px = R.path_px(day, k, side, r, oor_zero)
        tr.proxy |= px
        if not math.isnan(hi):
            conf = R.confirmed(a, r)
            if not px and not math.isnan(conf):
                conf_peak = max(conf_peak, min(hi, conf))
            if exits == "E2" and hi >= t4 + R.TICK - R.EPS and conf >= t4 - R.EPS and not px:
                sell(r, held, t4, "4x")
                held = 0
                break
            if exits == "E3" and not half_sold and hi >= t2 + R.TICK - R.EPS and conf >= t2 - R.EPS and not px:
                n = held if held == 1 else min(held, max(1, int(round(held * 0.5))))
                sell(r, n, t2, "2x")
                held -= n
                half_sold = True
                if held == 0:
                    break
        why = None
        if exits == "E3" and fr < r <= exit_row - 2 and not math.isnan(c) and c <= lock + R.EPS:
            why = "lock"
        if not math.isnan(hi):
            if arm == "confirmed":
                cf = R.confirmed(a, r)
                if not math.isnan(cf) and not px:
                    best_hi = max(best_hi, min(hi, cf))
            else:
                best_hi = max(best_hi, hi)
        if exits == "E3" and best_hi >= 2 * e - R.EPS:      # takes effect from the next minute's close
            m = math.floor(best_hi / e + 1e-9)
            lock = max(lock, (m - 1) * e)
        if why:
            x = R._next_open(day, k, side, r + 1, oor_zero)
            if x is not None:
                rr, oo, pxx = x
                tr.proxy |= pxx
                sell(rr, held, R.sell_px(oo, R.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), why)
                held = 0
                break
        r += 1
    # the confirmed peak up to the exit (for the >= 2x / >= 4x shares), also after an early sale
    for rr in range(r, exit_row):
        o, hi, c, px = R.path_px(day, k, side, rr, oor_zero)
        if not math.isnan(hi) and not px:
            conf = R.confirmed(a, rr)
            if not math.isnan(conf):
                conf_peak = max(conf_peak, min(hi, conf))
    if held > 0:
        x = R._next_open(day, k, side, exit_row, oor_zero)
        if x is None:
            p_, row = R.ltp(a, exit_row - 1)
            rr, oo, pxx = row, (0.0 if math.isnan(p_) else p_), False
        else:
            rr, oo, pxx = x
        tr.proxy |= pxx
        sell(rr, held, R.sell_px(oo, R.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), exits)
    tr.peak_mult = conf_peak / e
    R._settle(tr)
    return tr


def trade(day: Day, rule: Rule, sig: Signal, **kw) -> R.Trade | None:
    return simulate(day, sig.strike, sig.side, sig.row, sig.ref, kw.pop("exits", rule.exits), rule=rule.id, **kw)


def run(days: list[Day], rule: Rule, **kw) -> tuple[list, list]:
    """(trades, signals) of the rule over the days."""
    trades, sigs = [], []
    for d in days:
        s = signal(d, rule)
        if s is None:
            continue
        sigs.append((d, s))
        t = trade(d, rule, s, **dict(kw))
        if t is not None:
            trades.append(t)
    return trades, sigs


# ------------------------------------------------------------------------------------------------ random-time twins
def twin_signal(day: Day, rule: Rule, sig: Signal, rng: np.random.Generator) -> Signal | None:
    for _ in range(20):
        t = int(rng.integers(FIRST, LAST + 1))
        pk = pick(day, rule, sig.side, t)
        if pk is not None:
            return Signal(t, sig.side, pk[0], pk[1], math.nan, False)
    return None


def twins(sigs: list, rule: Rule, n: int = K26.TWINS, seed: int = K26.SEED) -> np.ndarray:
    """Net of n twin sets: every signal replaced by a random-time twin (same expiry, direction, strike rule, filter,
    size and exit)."""
    rng = np.random.default_rng(seed)
    out = np.zeros(n)
    cache: dict = {}
    for i in range(n):
        tot = 0.0
        for d, s in sigs:
            tw = twin_signal(d, rule, s, rng)
            if tw is None:
                continue
            key = (d.und, d.day, tw.row, tw.side, tw.strike)
            if key not in cache:
                tr = trade(d, rule, tw)
                cache[key] = tr.net if tr else 0.0
            tot += cache[key]
        out[i] = tot
    return out


def twin_p(net: float, tw: np.ndarray) -> float:
    return float((tw >= net - 1e-9).mean()) if len(tw) else 1.0


# ------------------------------------------------------------------------------------------------ metrics
def metrics(trades: list, days: list[Day], rule: Rule) -> dict:
    m = RU.metrics(trades, days, rule)
    n = len(trades)
    m["trigger_expiries"] = n
    m["x2_pct"] = round(100 * sum(t.peak_mult >= 2 - 1e-9 for t in trades) / n, 1) if n else 0.0
    m["x4_pct"] = round(100 * sum(t.peak_mult >= 4 - 1e-9 for t in trades) / n, 1) if n else 0.0
    m["over_budget"] = int(sum(getattr(t, "over_budget", False) for t in trades))
    m["max_risk"] = round(max((t.risked for t in trades), default=0.0), 0)
    m["median_risk"] = round(float(np.median([t.risked for t in trades])), 0) if n else 0.0
    m["up"] = int(sum(t.side > 0 for t in trades))
    m["down"] = n - m["up"]
    return m
