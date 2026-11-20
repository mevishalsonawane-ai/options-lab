"""R202 / R203 pure parts (PREREG.md): bars, indicators, the trend-turn exit rows, the skip rules, statistics, twins, BH
and the gate. No data and no engine here (test_r202.py). Every function is causal: a value at bar k reads bars <= k only.
Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import math
from collections import defaultdict
from datetime import date

import numpy as np

import r202cfg as K

N = K.NROWS


# ================================================================================================ bars and indicators
def bars(idx: np.ndarray, n: int = 5) -> np.ndarray:
    """(4, 375 // n) O H L C from a day's (4, 375) index minutes. A bar with no minute is NaN; open = its first known
    open, close = its last known close (a gap inside a bar is skipped)."""
    nb = N // n
    out = np.full((4, nb), np.nan)
    o, h, l, c = (np.asarray(idx[i], float) for i in range(4))
    for k in range(nb):
        s = slice(k * n, k * n + n)
        ok = np.isfinite(c[s])
        if not ok.any():
            continue
        oo = o[s][np.isfinite(o[s])]
        out[0, k] = oo[0] if len(oo) else c[s][ok][0]
        out[1, k] = np.nanmax(h[s]) if np.isfinite(h[s]).any() else np.nanmax(c[s])
        out[2, k] = np.nanmin(l[s]) if np.isfinite(l[s]).any() else np.nanmin(c[s])
        out[3, k] = c[s][ok][-1]
    return out


def twap(idx: np.ndarray) -> np.ndarray:
    """(375,) the session's average price so far: the equal-weight mean of the 1-minute typical prices (H + L + C) / 3
    up to and including each minute (the index has no volume; R63's 'VWAP'). NaN before the first known minute."""
    tp = (np.asarray(idx[1], float) + np.asarray(idx[2], float) + np.asarray(idx[3], float)) / 3.0
    ok = np.isfinite(tp)
    cnt = np.cumsum(ok)
    tot = np.cumsum(np.where(ok, tp, 0.0))
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(cnt > 0, tot / np.maximum(cnt, 1), np.nan)


def ema(x: np.ndarray, n: int) -> np.ndarray:
    """EMA(n) carried over NaN (a missing bar keeps the last value); seeded with the first known value."""
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    a = 2.0 / (n + 1.0)
    e = math.nan
    for i, v in enumerate(x):
        if v == v:
            e = v if e != e else e + a * (v - e)
        out[i] = e
    return out


def supertrend_dir(h: np.ndarray, l: np.ndarray, c: np.ndarray, n: int = K.ST_N, mult: float = K.ST_MULT) -> np.ndarray:
    """Supertrend direction per bar: +1 up, -1 down, 0 unknown (fewer than n known bars). ATR = Wilder's average of the
    true range; a missing bar carries the last direction."""
    h, l, c = (np.asarray(a, float) for a in (h, l, c))
    m = len(c)
    out = np.zeros(m, np.int8)
    atr = math.nan
    seed = []
    pc = math.nan
    ub = lb = math.nan
    d = 0
    for i in range(m):
        if not (c[i] == c[i] and h[i] == h[i] and l[i] == l[i]):
            out[i] = d
            continue
        tr = h[i] - l[i] if pc != pc else max(h[i] - l[i], abs(h[i] - pc), abs(l[i] - pc))
        if atr != atr:
            seed.append(tr)
            if len(seed) >= n:
                atr = float(np.mean(seed))
        else:
            atr = (atr * (n - 1) + tr) / n
        if atr == atr:
            mid = (h[i] + l[i]) / 2.0
            bu, bl = mid + mult * atr, mid - mult * atr
            if ub != ub:
                ub, lb = bu, bl
                d = 1 if c[i] >= mid else -1
            else:
                ub = bu if (bu < ub or pc > ub) else ub
                lb = bl if (bl > lb or pc < lb) else lb
                if d == 1 and c[i] < lb:
                    d = -1
                elif d == -1 and c[i] > ub:
                    d = 1
        pc = c[i]
        out[i] = d
    return out


# ================================================================================================ exit rows
def first_bar(er: int, n: int = 5) -> int:
    """The first bar that begins at or after the entry minute (PREREG 3)."""
    return int(-(-int(er) // n))


def row_after(k: int, n: int = 5) -> int:
    """The minute after bar k closes (the sale's minute); -1 when the session has no such minute."""
    r = (int(k) + 1) * n
    return r if 0 <= r < N else -1


def earlier(a, b) -> int:
    """The earlier of two exit rows; a negative (or None) row means none."""
    a = -1 if a is None else int(a)
    b = -1 if b is None else int(b)
    if a < 0:
        return b
    if b < 0:
        return a
    return min(a, b)


def wrong(value, level, side: str):
    """Wrong side of a level for the trade: below for a call, above for a put. NaN is never wrong."""
    v, lv = np.asarray(value, float), np.asarray(level, float)
    with np.errstate(invalid="ignore"):
        return (v < lv) if side == "C" else (v > lv)


def two_wrong_bar(w: np.ndarray, k0: int) -> int:
    """The first bar k with w[k] and w[k - 1] both true and both >= k0; -1 if none."""
    w = np.asarray(w, bool)
    for k in range(max(k0 + 1, 1), len(w)):
        if w[k] and w[k - 1]:
            return k
    return -1


def entry_level(idx: np.ndarray, er: int) -> float:
    """X4's level: the index's open at the entry minute; if that minute is missing, the last known close before it."""
    er = int(er)
    if 0 <= er < N and idx[0][er] == idx[0][er]:
        return float(idx[0][er])
    c = np.asarray(idx[3], float)[:max(er, 0)]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else math.nan


def x4_row(idx: np.ndarray, b5: np.ndarray, er: int, side: str) -> int:
    lv = entry_level(idx, er)
    if lv != lv:
        return -1
    k = two_wrong_bar(wrong(b5[3], lv, side), first_bar(er))
    return row_after(k) if k >= 0 else -1


def two_wrong_row(close5: np.ndarray, level5: np.ndarray, er: int, side: str) -> int:
    """X5 / X6: two 5-minute closes in a row on the wrong side of a per-bar level (EMA20 / the day's average price)."""
    k = two_wrong_bar(wrong(close5, level5, side), first_bar(er))
    return row_after(k) if k >= 0 else -1


def flip_row(dirs: np.ndarray, prev_dir: int, er: int, side: str) -> int:
    """X7: the first bar from first_bar(er) whose Supertrend direction is against the trade while the bar before it was
    with the trade (a flip after entry). dirs = the day's bars; prev_dir = the last bar of the session before."""
    want = 1 if side == "C" else -1
    for k in range(first_bar(er), len(dirs)):
        before = dirs[k - 1] if k > 0 else prev_dir
        if dirs[k] == -want and before == want:
            return row_after(k)
    return -1


def breadth_row(wrong_by_und: list, er: int, need: int = K.BREADTH_MIN) -> int:
    """X8: wrong_by_und = one bool array a day per index (already for the trade's side); two bars in a row with at
    least `need` indices wrong."""
    cnt = np.sum(np.asarray(wrong_by_und, bool), axis=0)
    k = two_wrong_bar(cnt >= need, first_bar(er))
    return row_after(k) if k >= 0 else -1


def market_row(close15: np.ndarray, ema15: np.ndarray, er: int, side: str) -> int:
    """X9: the first 15-minute bar closing at least 5 minutes after the entry minute (close row >= er + 4) with NIFTY's
    close on the wrong side of its EMA20."""
    w = wrong(close15, ema15, side)
    for j in range(len(w)):
        if 15 * j + 14 >= int(er) + 4 and w[j]:
            return row_after(j, 15)
    return -1


def opposite_row(signals: list, er: int, side: str) -> int:
    """X1 - X3: signals = [(entry minute, side)] of one index and day; the first one on the other side after `er`."""
    rows = [int(r) for r, s in signals if s != side and int(r) > int(er)]
    return min(rows) if rows else -1


# ================================================================================================ buckets (R203)
def b_hour(t: dict):
    m = 9 * 60 + 15 + int(t["er_sig"])
    return min(max(m // 60, 9), 14)


def b_vix(t: dict):
    v = t.get("f_vixreg")
    return v if v in ("LO", "HI") else None


def _num(v):
    try:
        v = float(v)
    except (TypeError, ValueError):
        return math.nan
    return v


def b_gap(t: dict):
    g = abs(_num(t.get("f_gap")))
    if g != g:
        return None
    return "under 0.25%" if g < 0.0025 else ("0.25-0.75%" if g < 0.0075 else "0.75% or more")


def b_dow(t: dict):
    return t["day"].weekday()


def b_rv(t: dict):
    r = _num(t.get("f_rv"))
    if r != r:
        return None
    return "low" if r < 1 / 3 else ("mid" if r < 2 / 3 else "high")


def b_und(t: dict):
    return t["und"]


def b_trend(t: dict):
    x = _num(t.get("f_trend"))
    if x != x:
        return None
    return "under 0" if x < 0 else ("0-0.3%" if x < 0.003 else ("0.3-0.6%" if x < 0.006 else "0.6% or more"))


BUCKET = {"W-hour": b_hour, "W-vix": b_vix, "W-gap": b_gap, "W-dow": b_dow, "W-rv": b_rv, "W-und": b_und,
          "W-trend": b_trend}


def trend_strength(idx: np.ndarray, er: int, side: str) -> float:
    """The index's move from the day's first known open to the last known close before the entry minute, in the trade's
    direction (fraction); NaN if unknown. Reads minutes < er only."""
    o = np.asarray(idx[0], float)
    c = np.asarray(idx[3], float)[:max(int(er), 0)]
    fo = np.flatnonzero(np.isfinite(o[:max(int(er), 0)]))
    fc = np.flatnonzero(np.isfinite(c))
    if not len(fo) or not len(fc) or o[fo[0]] <= 0:
        return math.nan
    return float((c[fc[-1]] / o[fo[0]] - 1.0) * (1.0 if side == "C" else -1.0))


def quarter_of(d: date, quarters: tuple = K.QUARTERS):
    """The latest quarter start at or before d; None before the first one."""
    q = None
    for s in quarters:
        if s <= d:
            q = s
    return q


def wf_plan(base: list, fn, quarters: tuple = K.QUARTERS, min_n: int = K.WF_MIN_N) -> dict:
    """{quarter start: the bucket to skip, or None}. Training = baseline trades on days BEFORE the quarter's first day; a
    bucket is bad with >= min_n trades and a mean net below 0; the worst bad bucket (lowest mean) is skipped. Unknown
    (None) buckets are never skipped."""
    plan = {}
    for q in quarters:
        agg = defaultdict(list)
        for t in base:
            if t["day"] < q:
                b = fn(t)
                if b is not None:
                    agg[b].append(t["net"])
        bad = [(float(np.mean(v)), str(b), b) for b, v in agg.items() if len(v) >= min_n and np.mean(v) < 0]
        plan[q] = min(bad)[2] if bad else None
    return plan


def wf_keep(trades: list, plan: dict, fn, quarters: tuple = K.QUARTERS) -> np.ndarray:
    keep = np.ones(len(trades), bool)
    for i, t in enumerate(trades):
        q = quarter_of(t["day"], quarters)
        if q is None or plan.get(q) is None:
            continue
        b = fn(t)
        keep[i] = not (b is not None and b == plan[q])
    return keep


def day_cap_keep(trades: list, cap: float) -> np.ndarray:
    """DC: per day, in entry order: a trade is removed when the arm's kept trades already closed (exit minute < this
    trade's signal minute) total <= -cap. A removed trade does not count toward the cap."""
    keep = np.ones(len(trades), bool)
    by_day = defaultdict(list)
    for i, t in enumerate(trades):
        by_day[t["day"]].append(i)
    for ids in by_day.values():
        ids = sorted(ids, key=lambda i: (int(trades[i]["er_sig"]), trades[i]["und"], trades[i]["side"]))
        done = []
        for i in ids:
            er = int(trades[i]["er_sig"])
            closed = sum(n for x, n in done if x < er)
            if closed <= -cap:
                keep[i] = False
            else:
                done.append((int(trades[i]["xr"]), float(trades[i]["net"])))
    return keep


def two_losers_keep(trades: list) -> np.ndarray:
    """L2: per day and index, in entry order: a trade is removed when the arm's last two kept trades on that index that
    had already closed (exit minute < this trade's signal minute; ordered by exit minute) both lost (net <= 0)."""
    keep = np.ones(len(trades), bool)
    grp = defaultdict(list)
    for i, t in enumerate(trades):
        grp[(t["day"], t["und"])].append(i)
    for ids in grp.values():
        ids = sorted(ids, key=lambda i: (int(trades[i]["er_sig"]), trades[i]["side"]))
        done = []
        for i in ids:
            er = int(trades[i]["er_sig"])
            closed = sorted((x, n) for x, n in done if x < er)
            if len(closed) >= 2 and closed[-1][1] <= 0 and closed[-2][1] <= 0:
                keep[i] = False
            else:
                done.append((int(trades[i]["xr"]), float(trades[i]["net"])))
    return keep


# ================================================================================================ statistics
def in_window(trades: list, lo: date, hi: date) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def months_between(lo: date, hi: date) -> tuple:
    out, y, m = [], lo.year, lo.month
    while (y, m) <= (hi.year, hi.month):
        out.append(f"{y:04d}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return tuple(out)


def stats(trades: list, lo: date, hi: date) -> dict:
    """Trades, win %, net, average win / loss, max drawdown on the daily closed-trade equity, worst month (a month with
    no trade is Rs 0), worst day and the account-stop check (R193's `stats`)."""
    months = months_between(lo, hi)
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0, avg_win=None, avg_loss=None, max_dd=0, worst_month=dict(month=None, net=0),
                    worst_day=None, losers=0, loss_total=0, account_stop_ok=True)
    nets = np.array([t["net"] for t in trades], float)
    by_day = defaultdict(float)
    by_month = {m: 0.0 for m in months}
    for t in trades:
        by_day[t["day"]] += t["net"]
        k = f"{t['day'].year:04d}-{t['day'].month:02d}"
        by_month[k] = by_month.get(k, 0.0) + t["net"]
    seq = np.array([by_day[d] for d in sorted(by_day)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mdd = float((np.maximum.accumulate(eq) - eq).max())
    wins, losses = nets[nets > 0], nets[nets <= 0]
    wm = min(by_month, key=lambda m: by_month[m])
    return dict(trades=n, win=round(float((nets > 0).mean()) * 100, 2), net=round(float(nets.sum())),
                avg_win=round(float(wins.mean())) if len(wins) else None,
                avg_loss=round(float(losses.mean())) if len(losses) else None,
                max_dd=round(mdd), worst_month=dict(month=wm, net=round(by_month[wm])), worst_day=round(float(seq.min())),
                losers=int(len(losses)), loss_total=round(float(losses.sum())),
                account_stop_ok=bool(mdd <= K.ACC_DD and by_month[wm] >= K.MONTH_CAP))


# ================================================================================================ twins, BH, gate
def twin_p(actual: float, twins: np.ndarray) -> float:
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-9).sum())) / (len(t) + 1))


def normal_p(actual: float, twins: np.ndarray) -> float:
    """One-sided normal-tail p of the actual total against the twins (R190 / R193's BH input)."""
    t = np.asarray(twins, float)
    sd = float(t.std(ddof=1)) if len(t) > 1 else 0.0
    if sd <= 0:
        return 0.0 if actual > float(t.mean()) + 1e-9 else 1.0
    z = (actual - float(t.mean())) / sd
    return float(0.5 * math.erfc(z / math.sqrt(2.0)))


def p_for_bh(changed: int, p_normal: float) -> float:
    """Amendment A: a rule that changed or removed no trade in the window has no twin test, so its p is 1 (its twins all
    equal the rule; float rounding must not turn that into p = 0)."""
    return 1.0 if int(changed) <= 0 else float(p_normal)


def skip_twins(nets: np.ndarray, k: int, n: int, rng) -> np.ndarray:
    """Totals of n twins: k of the trades removed at random (the skip twin)."""
    nets = np.asarray(nets, float)
    m = len(nets)
    k = int(min(max(k, 0), m))
    tot = np.full(n, float(nets.sum()))
    if k == 0:
        return tot
    for j in range(n):
        tot[j] -= float(nets[rng.choice(m, size=k, replace=False)].sum())
    return tot


def bh_total(p, m_total: int = K.M_TOTAL) -> list:
    """Benjamini-Hochberg q with m_total tests; the earlier entries count as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]


def better(v: dict, b: dict) -> bool:
    """PREREG 6 'better than B' (R106's rule): net above B's; or net within 5% of B's (B's net > 0) with max drawdown and
    worst month each at least 20% smaller."""
    if v["net"] > b["net"]:
        return True
    if b["net"] <= 0 or v["net"] < b["net"] * (1.0 - K.NEAR):
        return False
    dd_ok = v["max_dd"] <= b["max_dd"] * (1.0 - K.SMALLER)
    bw, vw = min(b["worst_month"]["net"], 0), min(v["worst_month"]["net"], 0)
    wm_ok = bw < 0 and abs(vw) <= abs(bw) * (1.0 - K.SMALLER)
    return bool(dd_ok and wm_ok)


def gate(v: dict, b: dict, folds_v: list, folds_b: list, changed: dict, p_hold, q) -> dict:
    """PREREG section 6. v / b: {design, holdout: stats}; folds_*: the four design quarters' stats; changed: trades
    changed or removed per window. Returns passes + every failed item."""
    fails = []
    for w in ("design", "holdout"):
        if not better(v[w], b[w]):
            fails.append(f"{w}: net {v[w]['net']:,} not better than B's {b[w]['net']:,}")
        if v[w]["max_dd"] > b[w]["max_dd"]:
            fails.append(f"{w}: max drawdown {v[w]['max_dd']:,} above B's {b[w]['max_dd']:,}")
        if changed[w] < K.MIN_CHANGED:
            fails.append(f"{w}: {changed[w]} trades changed / removed < {K.MIN_CHANGED}")
    ok = sum(1 for fv, fb in zip(folds_v, folds_b) if better(fv, fb))
    if ok < K.FOLDS_NEEDED:
        fails.append(f"walk-forward: better in {ok} of {len(folds_v)} design quarters < {K.FOLDS_NEEDED}")
    if p_hold is None or not p_hold < K.GATE_P:
        fails.append("holdout twin p " + ("n/a" if p_hold is None else f"{p_hold:.4f}") + f" >= {K.GATE_P}")
    if q is None or not q <= K.GATE_Q:
        fails.append("BH q " + ("n/a" if q is None else f"{q:.3g}") + f" > {K.GATE_Q}")
    return dict(passes=not fails, fails=fails, folds_better=ok, twin_p=p_hold, q=q)


# ================================================================================================ diagnosis (r202diag.py)
def contract_key(t: dict) -> tuple:
    """The same trade in two arms: the same option (index, day, side, strike) bought at the same fill minute."""
    return (t["und"], t["day"], t["side"], float(t["strike"]), int(t["er"]))


def split_twins(a: list, b: list) -> tuple:
    """(trades of `a` that `b` also took (same option, same minute), trades only `a` took)."""
    kb = {contract_key(t) for t in b}
    return [t for t in a if contract_key(t) in kb], [t for t in a if contract_key(t) not in kb]


def dedupe(by_arm: dict, order: tuple) -> list:
    """One position per option and minute across the arms: the first arm in `order` keeps a shared trade (how the paper
    book treats two arms that want the same option)."""
    seen, out = set(), []
    for arm in order:
        for t in by_arm[arm]:
            k = contract_key(t)
            if k not in seen:
                seen.add(k)
                out.append(t)
    return out


def peak_use(trades: list) -> tuple:
    """(most positions open at once, most premium in use at once) from fill minute to exit minute (exit minute excluded)."""
    ev = []
    for t in trades:
        cost = float(t["rE"]) * float(t["qty"])
        ev.append((t["day"], int(t["er"]), 1, cost))
        ev.append((t["day"], int(t["xr"]), -1, -cost))
    ev.sort(key=lambda e: (e[0], e[1], e[2]))                # at the same minute an exit (-1) frees its place first
    n = c = mx_n = 0
    mx_c = 0.0
    for _, _, dn, dc in ev:
        n += dn
        c += dc
        mx_n, mx_c = max(mx_n, n), max(mx_c, c)
    return mx_n, mx_c
