"""R27 K rules: Boss's "expiry day special" late range breakout (14 Oct 2026), on every NIFTY expiry (PREREG.md).

Mark the high and low of the 15-minute range 14:30-14:45 (bars closing 14:31 .. 14:45). From the next minute, the
first 1-minute CLOSE above the high buys a call, below the low buys a put; the break must come within the next 15
minutes (by the 15:00 close). The same for the ranges 14:45-15:00 (breaks by 15:15) and 15:00-15:15 (breaks by 15:20,
so the 15:25 exit comes after the fill).
Nothing is fitted, so every expiry is out-of-sample.

Strike: the nearest OTM (ATM+1 CE / ATM-1 PE) at the break. Size Rs 2,000 (whole lots, at least 1). R26's engine.
Exits: X25 = hold to the 15:25 bar's open (the text gives none; research only - the app squares off at 15:05);
E1 = the app's 15:05 square-off (only window 1 can use it); KS = X25 with a stop at the other side of the range
(a 1-minute index close back beyond the opposite edge sells at the next open).
"""
from __future__ import annotations

import math

import numpy as np

import r27cfg as K27
import r25cfg as K
import r25lib as R
import r26lib as L26
from r25data import N, minute

L26.EXIT_ROWS["X25"] = minute(15, 25)
# R25's entry works the order only while the bar is before its EXIT_ROW (15:05). The K rules enter as late as 15:21, so
# their entry may run to 15:25. (Found 14 Oct: without it K3 had 0 trades and K2 only breaks before 15:04.) The value is
# set only inside trade_at and restored after, so no other module (R25's simulator, the census) ever sees it (review,
# 14 Oct). E1 trades still need the signal before 15:01 (trade_at).
K_ENTRY_END = minute(15, 25)
L26.EXIT_ROWS["KS"] = minute(15, 25)
WINDOWS = {1: (R.close_row(14, 31), R.close_row(14, 45), R.close_row(15, 0)),
           2: (R.close_row(14, 46), R.close_row(15, 0), R.close_row(15, 15)),
           3: (R.close_row(15, 1), R.close_row(15, 15), R.close_row(15, 20))}   # breaks by 15:20: the 15:25 exit stays after the fill


def rng(day, w: int) -> tuple[float, float]:
    a, b, _ = WINDOWS[w]
    hi = np.nanmax(day.ix[a: b + 1, 1]) if np.isfinite(day.ix[a: b + 1, 1]).any() else math.nan
    lo = np.nanmin(day.ix[a: b + 1, 2]) if np.isfinite(day.ix[a: b + 1, 2]).any() else math.nan
    return float(hi), float(lo)


def signal(day, w: int, offset: int = 1):
    """(row, side, strike, ref, hi, lo) of the first break of window w's range; None when there is none."""
    a, b, last = WINDOWS[w]
    hi, lo = rng(day, w)
    if math.isnan(hi) or math.isnan(lo):
        return None
    for t in range(b + 1, last + 1):
        c = day.ix[t, 3]
        if math.isnan(c):
            continue
        sgn = 1 if c > hi else (-1 if c < lo else 0)
        if not sgn:
            continue
        k = R.atm(day, c) + sgn * offset * K.STEP[day.und]
        arr = day.opts.get((k, "C" if sgn > 0 else "P"))
        if arr is None:
            return None
        p, row = R.ltp(arr, t)
        if row < t - 4 or not p or math.isnan(p) or p <= 0:
            return None
        return t, sgn, k, p, hi, lo
    return None


def trade_at(day, w: int, t: int, sgn: int, ex: str, mult: float = 1.0, offset: int = 1):
    """Buy the ATM+-offset option at row t in direction sgn with exit ex (KS: X25 plus the range stop)."""
    saved = R.EXIT_ROW
    R.EXIT_ROW = K_ENTRY_END
    try:
        return _trade_at(day, w, t, sgn, ex, mult, offset)
    finally:
        R.EXIT_ROW = saved


def _trade_at(day, w: int, t: int, sgn: int, ex: str, mult: float = 1.0, offset: int = 1):
    c = day.ix[t, 3]
    if math.isnan(c) or (ex == "E1" and t >= minute(15, 1)):     # E1: the fill (within 3 bars) must precede 15:05
        return None
    k = R.atm(day, c) + sgn * offset * K.STEP[day.und]
    arr = day.opts.get((k, "C" if sgn > 0 else "P"))
    if arr is None:
        return None
    p, row = R.ltp(arr, t)
    if row < t - 4 or not p or math.isnan(p) or p <= 0:
        return None
    if ex != "KS":
        return L26.simulate(day, k, sgn, t, p, ex, K27.BUDGET, mult)
    tr = L26.simulate(day, k, sgn, t, p, "X25", K27.BUDGET, mult)
    if tr is None:
        return None
    hi, lo = rng(day, w)
    stop_edge = lo if sgn > 0 else hi
    for r in range(tr.fill_row, minute(15, 24)):
        cc = day.ix[r, 3]
        if not math.isnan(cc) and (cc - stop_edge) * sgn < 0:
            L26.EXIT_ROWS["_ks"] = r + 1                 # the next bar's open after the stop close
            return L26.simulate(day, k, sgn, t, p, "_ks", K27.BUDGET, mult)
    return tr


def trade(day, w: int, ex: str, mult: float = 1.0, offset: int = 1):
    s = signal(day, w, offset)
    if s is None:
        return None
    return trade_at(day, w, s[0], s[1], ex, mult, offset)


# ------------------------------------------------------------------------------------------------ the K rules and their run
class _Rule:
    unds = ("NIFTY",)


K_RULES = {"K1": ([1], "X25"), "K2": ([2], "X25"), "K3": ([3], "X25"), "K1e": ([1], "E1"), "KS": ([1, 2, 3], "KS")}


def run_k(days: list, rid: str, mult: float = 1.0) -> list:
    ws, ex = K_RULES[rid]
    out = []
    for d in days:
        for w in ws:
            tr = trade(d, w, ex, mult)
            if tr is not None:
                tr.w = w
                out.append(tr)
    return out


def twins_k(days: list, rid: str, n: int = K27.TWINS, seed: int = K27.SEED) -> np.ndarray:
    """Each trade re-entered at a uniformly random minute of the same break window, same direction, same strike rule,
    size and exit (the stop variant keeps its stop edge)."""
    ws, ex = K_RULES[rid]
    rng_ = np.random.default_rng(seed)
    base = []
    for d in days:
        for w in ws:
            s = signal(d, w)
            if s is not None:
                base.append((d, w, s))
    cache: dict = {}
    out = np.zeros(n)
    for i in range(n):
        tot = 0.0
        for d, w, s in base:
            a, b, last = WINDOWS[w]
            t = int(rng_.integers(b + 1, last + 1))
            key = (d.day, w, t)
            if key not in cache:
                tr = trade_at(d, w, t, s[1], ex)
                cache[key] = tr.net if tr else 0.0
            tot += cache[key]
        out[i] = tot
    return out


def main():
    import json
    import r25data as D
    import r25rules as RU
    days = sorted(D.load(K27.UND, "all"), key=lambda d: d.day)
    res = {}
    for rid in K_RULES:
        tr = run_k(days, rid)
        m = RU.metrics(tr, days, _Rule())
        n = len(tr)
        m["x2_pct"] = round(100 * sum(t.peak_mult >= 2 for t in tr) / n, 1) if n else 0.0
        m["x4_pct"] = round(100 * sum(t.peak_mult >= 4 for t in tr) / n, 1) if n else 0.0
        m["x10_pct"] = round(100 * sum(t.peak_mult >= 10 for t in tr) / n, 1) if n else 0.0
        m["up"] = sum(t.side > 0 for t in tr)
        tw = twins_k(days, rid)
        x2 = sum(t.net for t in run_k(days, rid, mult=2.0))
        years = {}
        for y in sorted({d.day.year for d in days}):
            years[str(y)] = RU.metrics([t for t in tr if t.day.year == y], [d for d in days if d.day.year == y], _Rule())
        blocks = {"2021-22": RU.metrics([t for t in tr if t.day.year <= 2022], [d for d in days if d.day.year <= 2022], _Rule()),
                  "2023-26": RU.metrics([t for t in tr if t.day.year >= 2023], [d for d in days if d.day.year >= 2023], _Rule())}
        res[rid] = {"m": m, "years": years, "blocks": blocks, "x2_spread": round(x2, 0),
                    "twins": {"p": round(float((tw >= m["net"] - 1e-9).mean()), 4), "mean": round(float(tw.mean()), 0)},
                    "trades_list": [dict(day=str(t.day), w=t.w, side="CE" if t.side > 0 else "PE", strike=t.strike, entry=t.entry,
                                         lots=t.lots, peak=round(t.peak_mult, 2), net=round(t.net, 0)) for t in tr]}
        print(rid, m["trades"], m["net"], "p", res[rid]["twins"]["p"], flush=True)
    (K27.RESULTS / "k_results.json").write_text(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main()
