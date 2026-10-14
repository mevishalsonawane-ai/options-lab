"""R43 engine: strike rules, the hero exits, the outcome table, rule trades, random twins, metrics and BH.

Fills, charges, the held option's path and the out-of-range proxy are R25's (r25lib; PREREG.md "Costs and fills").
A signal read at the close of 5-minute bar j has sig_row = 5j + 4 (the bar's last minute). The entry is R25's: a LIMIT at
the ask, capped at the signal-time price x 1.2 + 0.10, worked over rows 5j + 5 .. 5j + 7 (the NEXT 5-minute bar's open
and its first 3 minutes), never chased.

Exits (every one has the -50% stop; there is no fixed target):
  hold   a minute close <= 50% of the fill sells at the next minute's open; else the 15:05 square-off;
  trail  the stop, plus: once the best close >= 3x the fill, a close <= 50% of the best close sells;
  time   the stop and the trail, plus: no close >= 2x the fill by the close 60 minutes after the fill row -> sell;
  step   (variant 1b) the stop, plus a ratchet on the best close: >= 1.25x locks 1.00x, 1.5x -> 1.25x, 1.75x -> 1.5x,
         2x -> 1.75x, then from 3x up each whole multiple m locks (m - 1)x; a close <= the lock sells.
Locks and trails move on a minute's close and act from the NEXT minute's close; every sale is at the next minute's open.
"""
from __future__ import annotations

import math

import numpy as np

import r43cfg as K
import r25lib as L
from r25lib import C, H, EPS, EXIT_ROW, LAST_STOP_ROW, Trade  # noqa: F401


def sig_row(j: int) -> int:
    return 5 * j + 4


# ------------------------------------------------------------------------------------------------ strike rules
def pick_strike(day, sgn: int, t: int, rule: str):
    """(strike, reference price) chosen at row t, or None.
    cheap: the nearest-the-money OTM strike in the signal's direction whose last traded price is Rs 5-30, traded in the
           last 5 minutes, passing the app's stale-slack check (R25 `eligible`);
    otm2:  the second OTM strike (ATM+2 CE / ATM-2 PE: the second stored strike beyond spot), traded in the last 5
           minutes."""
    if rule == "cheap":
        el = L.eligible(day, sgn, t, K.CHEAP_BAND)
        return (el[0][0], el[0][1]) if el else None
    if rule == "otm2":
        s = L.spot(day, t)
        if math.isnan(s):
            return None
        side = "C" if sgn > 0 else "P"
        ks = sorted((k for (k, sd) in day.opts if sd == side and ((k > s + EPS) if sgn > 0 else (k < s - EPS))),
                    key=lambda k: abs(k - s))
        if len(ks) < 2:
            return None
        k = ks[1]
        p, row = L.ltp(day.opts[(k, side)], t)
        if math.isnan(p) or row < t - (L.TRADED_BARS - 1) or p <= 0:
            return None
        return k, p
    raise ValueError(rule)


# ------------------------------------------------------------------------------------------------ the exits
def step_lock(mult: float) -> float:
    """The 1b lock (a multiple of the fill) for a best close of `mult` x the fill (-inf: none)."""
    if mult >= 3.0 - EPS:
        return math.floor(mult + EPS) - 1.0
    for m, lk in ((2.0, 1.75), (1.75, 1.5), (1.5, 1.25), (1.25, 1.0)):
        if mult >= m - EPS:
            return lk
    return -math.inf


def simulate(day, k: float, sgn: int, srow: int, ref: float, exit_: str, budget: float = K.BUDGET,
             mult: float = 1.0, oor_zero: bool = False, rule: str = "") -> Trade | None:
    side = "C" if sgn > 0 else "P"
    f = L.enter(day, k, side, srow, ref, mult)
    if f is None or day.lot <= 0:
        return None
    fr, e = f
    if e <= 0:
        return None
    lots = int(math.floor(budget / (e * day.lot) + 1e-9))
    if lots <= 0:
        return None
    tr = Trade(day.und, day.day, rule, sgn, k, srow, fr, e, lots, lots * day.lot)
    a = day.opts[(k, side)]
    best_c = e
    peak = e
    armed = False
    lock = -math.inf
    hit2 = False
    why = None
    r = fr
    while r < EXIT_ROW:
        o, hi, c, px = L.path_px(day, k, side, r, oor_zero)
        tr.proxy |= px
        if not math.isnan(hi) and not px:
            peak = max(peak, hi)
        if not math.isnan(c):
            if c >= K.TIME_MULT * e - EPS:
                hit2 = True
            if r > fr and r <= LAST_STOP_ROW:
                if c <= K.STOP_FRAC * e + EPS:
                    why = "stop"
                elif exit_ in ("trail", "time") and armed and c <= K.TRAIL_KEEP * best_c + EPS:
                    why = "trail"
                elif exit_ == "step" and c <= lock * e + EPS:
                    why = "lock"
                elif exit_ == "time" and r >= fr + K.TIME_N and not hit2:
                    why = "time"
            best_c = max(best_c, c)
            if best_c >= K.TRAIL_ARM * e - EPS:
                armed = True
            lock = max(lock, step_lock(best_c / e))
        if why:
            x = L._next_open(day, k, side, r + 1, oor_zero)
            if x is not None:
                rr, oo, pxx = x
                tr.proxy |= pxx
                tr.exits.append((rr, tr.qty, L.sell_px(oo, L.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), why))
                break
            why = None
        r += 1
    if not tr.exits:
        x = L._next_open(day, k, side, EXIT_ROW, oor_zero)
        if x is None:
            p_, row = L.ltp(a, EXIT_ROW - 1)
            rr, oo, pxx = row, (0.0 if math.isnan(p_) else p_), False
        else:
            rr, oo, pxx = x
        tr.proxy |= pxx
        tr.exits.append((rr, tr.qty, L.sell_px(oo, L.vol5_lots(a, rr, day.lot) if not pxx else 0.0, mult), "1505"))
    tr.peak_mult = peak / e
    L._settle(tr)
    return tr


def exit_mult(tr: Trade) -> float:
    q = sum(x[1] for x in tr.exits)
    return sum(x[1] * x[2] for x in tr.exits) / q / tr.entry if q and tr.entry > 0 else 0.0


# ------------------------------------------------------------------------------------------------ the outcome table
TABLE_EXITS = K.EXITS + (K.LOCK_EXIT,)


def day_table(day, js=range(0, 66)) -> list[dict]:
    """Every (side, strike rule, bar j, exit) outcome of one expiry day: the trade a signal at the close of bar j would
    have made. Rule trades and random twins are read from this table."""
    rows = []
    for sgn in (1, -1):
        for sr in K.STRIKES:
            for j in js:
                t = sig_row(j)
                pk = pick_strike(day, sgn, t, sr)
                if pk is None:
                    continue
                for ex in TABLE_EXITS:
                    tr = simulate(day, pk[0], sgn, t, pk[1], ex)
                    if tr is None:
                        break                       # no fill / no lot: the same for every exit
                    rows.append(dict(und=day.und, d=day.day, side=sgn, strike_rule=sr, j=j, exit=ex, strike=pk[0],
                                     ref=pk[1], fill_row=tr.fill_row, entry=tr.entry, lots=tr.lots, qty=tr.qty,
                                     net=tr.net, risked=tr.risked, peak=tr.peak_mult, xmult=exit_mult(tr),
                                     why=tr.exits[-1][3], proxy=tr.proxy))
    return rows


# ------------------------------------------------------------------------------------------------ rule trades
def rule_trades(table, sigs: dict, rule) -> list[dict]:
    """sigs: {(und, d): {family: {j: +-1}}}. table: DataFrame indexed by (und, d, side, strike_rule, j, exit).
    One trade per index-day at most: the FIRST signal in the window that has a trade (an eligible strike and a fill);
    baselines ('base') take the CE and the PE at the window's fixed bar."""
    fam, sr, w, ex = rule
    j0, j1 = K.WINDOWS[w]
    out = []
    for (u, d), fs in sigs.items():
        if fam == "base":
            jb = K.BASE_BAR[w]
            for sgn in (1, -1):
                key = (u, d, sgn, sr, jb, ex)
                if key in table:
                    out.append(dict(table[key], j=jb, side=sgn, und=u, d=d))
            continue
        for j in sorted(fs.get(fam, {})):
            if j < j0 or j > j1:
                continue
            sgn = fs[fam][j]
            key = (u, d, sgn, sr, j, ex)
            if key in table:
                out.append(dict(table[key], j=j, side=sgn, und=u, d=d))
                break
    out.sort(key=lambda x: (x["d"], x["und"], x["j"]))
    return out


def twin_pool(table, sr: str, ex: str, w: str) -> dict:
    """{(und, d, side): array of nets of every bar in the window that has a trade} (the random twin's choices)."""
    j0, j1 = K.WINDOWS[w]
    pool: dict = {}
    for (u, d, sgn, s, j, e), v in table.items():
        if s == sr and e == ex and j0 <= j <= j1:
            pool.setdefault((u, d, sgn), []).append(v["net"])
    return {k: np.array(v) for k, v in pool.items()}


def twins(trades: list[dict], pool: dict, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """n twin-set totals: each trade re-entered at a uniformly random bar of the same window (same day, side, strike
    rule, budget and exit)."""
    rng = np.random.default_rng(seed)
    tot = np.zeros(n)
    for t in trades:
        v = pool[(t["und"], t["d"], t["side"])]
        tot += v[rng.integers(0, len(v), n)]
    return tot


def p_value(net: float, tw: np.ndarray) -> float:
    return float((1 + np.sum(tw >= net - 1e-9)) / (1 + len(tw)))


def bh(ps: list[float]) -> list[float]:
    """Benjamini-Hochberg q-values (step-up, monotone)."""
    p = np.asarray(ps, float)
    m = len(p)
    if m == 0:
        return []
    o = np.argsort(p)
    q = np.empty(m)
    run = 1.0
    for rank in range(m, 0, -1):
        i = o[rank - 1]
        run = min(run, p[i] * m / rank)
        q[i] = run
    return [float(min(x, 1.0)) for x in q]


# ------------------------------------------------------------------------------------------------ metrics
def metrics(trades: list[dict], n_days: int) -> dict:
    """n_days: index-expiry-days in the period (the denominator of 'net a day')."""
    n = len(trades)
    nets = np.array([t["net"] for t in trades], float)
    net = float(nets.sum()) if n else 0.0
    by_day: dict = {}
    for t in trades:
        by_day[t["d"]] = by_day.get(t["d"], 0.0) + t["net"]
    cum = np.cumsum(nets) if n else np.array([0.0])
    dd = float(np.min(cum - np.maximum.accumulate(np.concatenate([[0.0], cum]))[1:])) if n else 0.0
    top5 = float(np.sort(nets)[::-1][:5].sum()) if n else 0.0
    risked = float(sum(t["risked"] for t in trades))
    return dict(
        trades=n, win_pct=round(100 * float((nets > 0).mean()), 1) if n else 0.0,
        peak5=int(sum(t["peak"] >= 5 - EPS for t in trades)), peak10=int(sum(t["peak"] >= 10 - EPS for t in trades)),
        banked5=int(sum(t["xmult"] >= 5 - EPS for t in trades)), banked10=int(sum(t["xmult"] >= 10 - EPS for t in trades)),
        net=round(net, 0), net_per_day=round(net / n_days, 1) if n_days else 0.0,
        per_1000=round(net / risked * 1000, 1) if risked > 0 else 0.0,
        worst_day=round(min(by_day.values()), 0) if by_day else 0.0, max_dd=round(min(dd, 0.0), 0),
        top5=round(top5, 0), top5_share=round(top5 / net * 100, 1) if net > 0 else None,
        net_wo_top5=round(net - top5, 0), proxy=int(sum(bool(t["proxy"]) for t in trades)),
        ce=int(sum(t["side"] > 0 for t in trades)), pe=int(sum(t["side"] < 0 for t in trades)))


def by_year(trades: list[dict]) -> dict:
    out: dict = {}
    for t in trades:
        y = str(t["d"].year)
        a = out.setdefault(y, [0, 0.0])
        a[0] += 1
        a[1] += t["net"]
    return {y: [a[0], round(a[1], 0)] for y, a in sorted(out.items())}


def by_index(trades: list[dict]) -> dict:
    out: dict = {}
    for t in trades:
        a = out.setdefault(t["und"], [0, 0.0])
        a[0] += 1
        a[1] += t["net"]
    return {u: [a[0], round(a[1], 0)] for u, a in sorted(out.items())}
