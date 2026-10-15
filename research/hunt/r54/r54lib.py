"""R54 engine (PREREG.md): the Rs 1,000 hero ticket, any entry time, any end time, Boss's 25% trail or hold.

Built from R25 / R50's pieces: R25's eligible strikes, held-contract path (`path_px`), next-minute fills capped at
ref x 1.2 + 0.10 and the liquidity spread (`buy_px` / `sell_px`, 1 / 2 / 4 ticks by 5-minute volume); R50's dated
charges and its tr25 trail. R54 adds:
  - the end time as a parameter (R25 / R50 fix it at 15:05): stops read closes of bars closing <= end - 1 minute, and
    whatever is held sells at the open of the bar that STARTS at the end time;
  - the late-minute multiplier on the tick count (results/liquidity.json, measured on design days before any P&L);
  - R47's stray-print guard on every open used for a fill and on the highs that count as "reached 2x / 5x / 10x";
  - exactly 1 lot, skipped when the fill x lot is over Rs 1,000.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t; a decision at row t reads rows <= t and acts from row t + 1.
"""
from __future__ import annotations

import json
import math

import numpy as np

import r54cfg as K
import r25lib as R
import r50lib as L50
from r25data import C, H, L, O, V, minute

EPS = 1e-9
TICK = R.TICK
Trade = L50.Trade
settle = L50._settle


def bar_close(s: str) -> int:
    """Row of the bar that CLOSES at hhmm."""
    return R.close_row(*K.hm(s))


def bar_start(s: str) -> int:
    """Row of the bar that STARTS at hhmm."""
    return minute(*K.hm(s))


ENTRY_ROW = {e: bar_close(e) for e in K.ENTRIES}
END_ROW = {e: bar_start(e) for e in K.ENDS}
TWIN_WINDOW = (bar_close(K.TWIN_WIN[0]), bar_close(K.TWIN_WIN[1]))

# ------------------------------------------------------------------------------------------------ late-minute spread
LATE: dict = {}          # first row of a 5-minute bucket -> multiplier (>= 1); empty = 1 everywhere


def load_late(path=None) -> dict:
    p = path or (K.RESULTS / "liquidity.json")
    LATE.clear()
    if p.exists():
        LATE.update({int(k): float(v) for k, v in json.loads(p.read_text())["late_mult"].items()})
    return LATE


def late_mult(r: int) -> float:
    if r < bar_start(K.LATE_FROM):
        return 1.0
    return LATE.get(r - r % 5, 1.0)


def round_up_quarter(x: float) -> float:
    return math.ceil(x * 4 - 1e-9) / 4


# ------------------------------------------------------------------------------------------------ stray-print guard
def ref_close(a: np.ndarray, r: int) -> float:
    """Median of the contract's closes on rows r-1, r, r+1 (those that exist and are not NaN); NaN if none."""
    xs = [a[j, C] for j in (r - 1, r, r + 1) if 0 <= j < len(a) and not math.isnan(a[j, C])]
    return float(np.median(xs)) if xs else math.nan


GUARD = True             # False only for the write-up's informational "without the guard" row (not a gate input)


def stray(p: float, ref: float) -> bool:
    if not GUARD or math.isnan(ref) or math.isnan(p):
        return False
    return abs(p - ref) > 2 * TICK + EPS and (ref <= 0 or abs(p / ref - 1.0) > K.BAD_PRINT)


def guard_buy(a: np.ndarray, r: int, p: float) -> float:
    ref = ref_close(a, r)
    return max(p, ref) if stray(p, ref) else p


def guard_sell(a: np.ndarray, r: int, p: float) -> float:
    ref = ref_close(a, r)
    return min(p, ref) if stray(p, ref) else p


def guard_high(a: np.ndarray, r: int, hi: float) -> float:
    ref = ref_close(a, r)
    return hi if (not GUARD or math.isnan(ref)) else min(hi, ref * (1.0 + K.BAD_PRINT))


# ------------------------------------------------------------------------------------------------ the strike
def band(day) -> tuple[float, float]:
    return K.BAND_LO, min(K.BAND_HI, K.BUDGET / day.lot) if day.lot > 0 else K.BAND_LO


def pick(day, sgn: int, t: int):
    """R50 'cheap': the nearest-the-money eligible OTM strike priced Rs 5 - min(30, 1000 / lot) at row t."""
    if day.lot <= 0:
        return None
    lo, hi = band(day)
    if hi < lo:
        return None
    el = R.eligible(day, sgn, t, (lo, hi))
    return (el[0][0], el[0][1]) if el else None


# ------------------------------------------------------------------------------------------------ fills
def enter(day, k: float, side: str, sig_row: int, ref: float, end_row: int, mult: float = 1.0):
    """R25 `enter` (limit at the ask, capped at ref x 1.2 + 0.10, 3 minutes, never chases) with the stray guard and the
    late multiplier."""
    a = day.opts[(k, side)]
    cap = R.floor_tick(ref * 1.2 + 0.10)
    for r in range(sig_row + 1, min(sig_row + 1 + R.FILL_TRIES, end_row)):
        o = a[r, O]
        if math.isnan(o):
            continue
        v5 = R.vol5_lots(a, r, day.lot)
        m = mult * late_mult(r)
        ask = R.buy_px(guard_buy(a, r, o), v5, m)
        if ask <= cap + EPS:
            return r, ask
        lo = a[r, L]
        if not math.isnan(lo) and R.buy_px(guard_buy(a, r, lo), v5, m) <= cap + EPS:
            return r, cap
    return None


def sell_at(day, k: float, side: str, r: int, mult: float):
    """Sell the held contract at the open of the first bar at or after row r (guarded; proxy rows have no volume, so
    the widest ticks); if none is left, at the last traded price. Returns (row, price, proxy)."""
    a = day.opts[(k, side)]
    x = R._next_open(day, k, side, r, False)
    if x is None:
        p_, row = R.ltp(a, len(a) - 1)
        return row, (0.0 if math.isnan(p_) else max(R.sell_px(p_, 0.0, mult * late_mult(max(row, 0))), 0.0)), False
    rr, oo, px = x
    if not px:
        oo = guard_sell(a, rr, oo)
    v5 = R.vol5_lots(a, rr, day.lot) if not px else 0.0
    return rr, R.sell_px(oo, v5, mult * late_mult(rr)), px


# ------------------------------------------------------------------------------------------------ one ticket
def simulate(day, k: float, sgn: int, sig_row: int, ref: float, end: str, exit_: str, mult: float = 1.0,
             rule: str = "") -> Trade | None:
    """One 1-lot ticket on the HELD contract. exit_: tr25 (a close <= 0.75 x the best close since the fill sells at the
    next open) or hold. end: '1505' / '1510' / '1515'."""
    side = "C" if sgn > 0 else "P"
    if (k, side) not in day.opts or day.lot <= 0:
        return None
    end_row = END_ROW[end]
    f = enter(day, k, side, sig_row, ref, end_row, mult)
    if f is None:
        return None
    fr, e = f
    if e * day.lot > K.BUDGET + EPS:
        return None                                         # the lot costs more than Rs 1,000: no ticket
    tr = Trade(day.und, day.day, rule, sgn, k, sig_row, fr, e, 1, day.lot)
    a = day.opts[(k, side)]
    last_stop = end_row - 2                                 # closes of bars closing <= end - 1 minute
    best_c = e
    peak = e
    r = fr
    sold = False
    while r < end_row:
        o, hi, c, px = R.path_px(day, k, side, r)
        tr.proxy |= px
        if not math.isnan(hi) and not px:
            conf = R.confirmed(a, r)
            if not math.isnan(conf):
                peak = max(peak, min(guard_high(a, r, hi), conf))
        why = None
        if exit_ == "tr25" and fr < r <= last_stop and not math.isnan(c) and c <= (1 - K.TRAIL) * max(best_c, c) + EPS:
            why = "trail"
        if not math.isnan(c) and not px:
            best_c = max(best_c, c)
        if why:
            rr, p, pxx = sell_at(day, k, side, r + 1, mult)
            tr.proxy |= pxx
            tr.exits.append((rr, tr.qty, p, why))
            sold = True
            break
        r += 1
    if not sold:
        rr, p, pxx = sell_at(day, k, side, end_row, mult)
        tr.proxy |= pxx
        tr.exits.append((rr, tr.qty, p, "end" + end))
    tr.peak_mult = peak / e
    settle(tr)
    return tr


def run_cell(days: list, entry: str, end: str, exit_: str, mult: float = 1.0) -> list[Trade]:
    t = ENTRY_ROW[entry]
    out = []
    for d in days:
        for sgn in (1, -1):
            pk = pick(d, sgn, t)
            if pk is None:
                continue
            tr = simulate(d, pk[0], sgn, t, pk[1], end, exit_, mult, f"{entry}-{end}-{exit_}")
            if tr is not None:
                out.append(tr)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_table(day, end: str, exit_: str) -> dict:
    """{(sgn, t): net or None} for every minute t of the twin window (one day; the pool maps over days)."""
    a, b = TWIN_WINDOW
    out = {}
    for sgn in (1, -1):
        for t in range(a, b + 1):
            pk = pick(day, sgn, t)
            tr = simulate(day, pk[0], sgn, t, pk[1], end, exit_) if pk else None
            out[(sgn, t)] = None if tr is None else tr.net
    return out


def twins(trades: list, table: dict, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """table: {(und, date): {(sgn, t): net}}. Each ticket is re-entered at a uniformly random minute of the window (same
    day, side, picker, end and exit); up to 20 draws for a minute that trades, else 0."""
    rng = np.random.default_rng(seed)
    a, b = TWIN_WINDOW
    out = np.zeros(n)
    for i in range(n):
        tot = 0.0
        for tr in trades:
            tb = table[(tr.und, tr.day)]
            for _ in range(20):
                x = tb[(tr.side, int(rng.integers(a, b + 1)))]
                if x is not None:
                    tot += x
                    break
        out[i] = tot
    return out


p_value = L50.p_value
bh = L50.bh
max_dd = L50.max_dd


# ------------------------------------------------------------------------------------------------ metrics
def daily(trades: list, days: list) -> np.ndarray:
    """Net per index-expiry-day of `days` (0 when no ticket), in date order."""
    by = {(d.und, d.day): 0.0 for d in days}
    for t in trades:
        by[(t.und, t.day)] += t.net
    return np.array([by[k] for k in sorted(by, key=lambda x: (x[1], x[0]))])


def metrics(trades: list, days: list) -> dict:
    nets = np.array([t.net for t in trades], dtype=float)
    out = dict(tickets=len(trades), n_days=len(days))
    if not len(nets):
        out.update(net=0.0, hit=0.0, x2=0, x5=0, x10=0, staked=0.0, per1000=0.0, worst_day=0.0, max_dd=0.0,
                   top1=None, top5=None, ex5=0.0, by_index={})
        return out
    dv = daily(trades, days)
    tot = float(nets.sum())
    staked = float(sum(t.risked for t in trades))
    srt = np.sort(nets)[::-1]
    out.update(
        net=round(tot, 0), hit=round(float((nets > 0).mean() * 100), 1),
        x2=int(sum(t.peak_mult >= 2 - EPS for t in trades)), x5=int(sum(t.peak_mult >= 5 - EPS for t in trades)),
        x10=int(sum(t.peak_mult >= 10 - EPS for t in trades)),
        staked=round(staked, 0), per1000=round(tot / staked * 1000, 0) if staked > 0 else 0.0,
        worst_day=round(float(dv.min()), 0), max_dd=round(max_dd(dv), 0),
        top1=round(float(srt[0]) / tot * 100, 0) if tot > 0 else None,
        top5=round(float(srt[:5].sum()) / tot * 100, 0) if tot > 0 else None,
        ex5=round(tot - float(srt[:5].sum()), 0),
    )
    bi: dict = {}
    for t in trades:
        x = bi.setdefault(t.und, [0, 0.0])
        x[0] += 1
        x[1] = round(x[1] + t.net, 2)
    out["by_index"] = bi
    return out


def paired(a: np.ndarray, b: np.ndarray, n: int = K.BOOT, seed: int = K.SEED) -> dict:
    """Mean of a - b per day with a 95% bootstrap CI over days."""
    d = np.asarray(a, float) - np.asarray(b, float)
    if not len(d):
        return dict(n=0, mean=0.0, lo=0.0, hi=0.0, total=0.0)
    rng = np.random.default_rng(seed)
    bs = d[rng.integers(0, len(d), size=(n, len(d)))].mean(axis=1)
    return dict(n=int(len(d)), mean=round(float(d.mean()), 1), lo=round(float(np.percentile(bs, 2.5)), 1),
                hi=round(float(np.percentile(bs, 97.5)), 1), total=round(float(d.sum()), 0))


def reading(des: dict, fin: dict | None) -> str:
    """PREREG: 'better' only if > 0 in design AND final AND the design CI excludes 0 ('worse' the same with < 0)."""
    if fin is None:
        return "final not read"
    if des["mean"] > 0 and fin["mean"] > 0 and des["lo"] > 0:
        return "better"
    if des["mean"] < 0 and fin["mean"] < 0 and des["hi"] < 0:
        return "worse"
    return "no clear difference"


def gates(fm: dict, p: float, q: float, design_net: float) -> dict:
    g = {"net>0": fm["net"] > 0, "p<0.05": p < K.GATE_P, "q<=0.10": q <= K.GATE_Q,
         "40+ tickets": fm["tickets"] >= K.GATE_TRADES, "net without top 5 > 0": fm["ex5"] > 0,
         "design net > 0": design_net > 0}
    g["pass"] = all(g.values())
    return g
