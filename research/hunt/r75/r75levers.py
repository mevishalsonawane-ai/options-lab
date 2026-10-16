"""R75 levers (PREREG.md section 3) and the gate maths (section 5). Pure: numpy only, no data access.

Every lever decides with what is known at its decision moment:
  L1   the arm's trades CLOSED before the entry minute (exit row < entry row)
  L2   index nets on training days strictly before the period
  L3   the entry minute only
  L4   VIX closes to the previous session; the day's 1-minute candles up to 09:44 (only for entries at 09:45 or later);
       daily bars to the previous session
  L5   the strike rule (no information)
  L6   the expiry calendar; today's 09:15 open and daily bars to the previous session
  L7   daily closes to the previous session; the arm's own sized trades of previous days
test_r75.py's look-ahead guards prove each.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np

import r75cfg as R


# ================================================================================================ daily bars
def daily_bars(days: dict) -> dict:
    """{day: (open, high, low, close)} from a store's (4, 375) 1-minute candles (NaN-safe; a day with no candle is
    left out). Open = the first finite open, close = the last finite close."""
    out = {}
    for d in sorted(days):
        a = days[d].idx
        o, h, l, c = a[0], a[1], a[2], a[3]
        fo, fc = np.flatnonzero(np.isfinite(o)), np.flatnonzero(np.isfinite(c))
        if not len(fo) or not len(fc) or not np.isfinite(h).any():
            continue
        out[d] = (float(o[fo[0]]), float(np.nanmax(h)), float(np.nanmin(l)), float(c[fc[-1]]))
    return out


def atr_series(bars: dict, n: int = R.ATR_N) -> dict:
    """{day: Wilder ATR n of the daily bars UP TO AND INCLUDING that day} (NaN before n true ranges exist)."""
    ds = sorted(bars)
    out, atr, trs = {}, np.nan, []
    for i, d in enumerate(ds):
        _, h, l, c = bars[d]
        tr = h - l if i == 0 else max(h - l, abs(h - bars[ds[i - 1]][3]), abs(l - bars[ds[i - 1]][3]))
        if len(trs) < n:
            trs.append(tr)
            if len(trs) == n:
                atr = float(np.mean(trs))
        else:
            atr = (atr * (n - 1) + tr) / n
        out[d] = atr
    return out


def prev_of(ds: list) -> dict:
    """{day: the previous day in the sorted list} (the first has none)."""
    return {d: ds[i - 1] for i, d in enumerate(ds) if i}


# ================================================================================================ L1 daily loss cap
def l1_trigger(day_trades: list, mode: str, val: float) -> int | None:
    """The exit row after which the arm takes no new entry that day, or None. day_trades: the arm's trades of ONE day
    (all indices), each with er, xr, net. Losses in a row / the day's net are counted in exit order (stable: the
    list's order breaks ties)."""
    run, tot = 0, 0.0
    for t in sorted(day_trades, key=lambda z: z["xr"]):
        tot += t["net"]
        run = run + 1 if t["net"] <= 0 else 0
        if (mode == "k" and run >= val) or (mode == "x" and tot <= -val):
            return int(t["xr"])
    return None


def l1_apply(trades: list, mode: str, val: float) -> list:
    """The arm's trades with L1: a day's trades entered at a minute AFTER the trigger exit are dropped. Because only
    later entries are dropped, the trades before the trigger and the trigger itself are exactly the baseline's."""
    by = defaultdict(list)
    for t in trades:
        by[t["day"]].append(t)
    out = []
    for d in sorted(by):
        ts = by[d]
        trig = l1_trigger(ts, mode, val)
        out += [t for t in ts if trig is None or t["er"] <= trig]
    return out


# ================================================================================================ L2 index selection
def l2_drop(train: list, mode: str, unds) -> set:
    """Indices to drop, from the baseline's trades on TRAINING days only: NEG = every index with net < 0; WEAK = the one
    with the lowest net (an index with no training trade counts as 0; ties: the later index in `unds`)."""
    net = {u: 0.0 for u in unds}
    for t in train:
        net[t["und"]] += t["net"]
    if mode == "NEG":
        return {u for u in unds if net[u] < 0}
    if mode == "WEAK":
        lo = min(net.values())
        return {[u for u in unds if net[u] == lo][-1]}
    raise ValueError(mode)


# ================================================================================================ L3 time of day
def l3_ok(er: int, lever: str) -> bool:
    first, last = R.L3_SPEC[lever]
    return (first is None or er >= first) and (last is None or er <= last)


# ================================================================================================ L4 chop
def vix_low_days(vix_close: dict, look: int = R.VIX_LOOK, q: float = R.VIX_Q) -> set:
    """Days d whose PREVIOUS session's VIX close x is <= the q-quantile of the `look` closes ending at that session
    (inclusive). vix_close: {day: close}. Needs `look` closes; else no skip."""
    ds = sorted(vix_close)
    cl = np.array([vix_close[d] for d in ds], float)
    out = set()
    for i in range(look, len(ds)):
        ref = cl[i - look:i]                      # the look closes ending at session i-1
        if cl[i - 1] <= np.quantile(ref, q):
            out.add(ds[i])
    return out


def or_narrow(idx: np.ndarray, atr_prev: float, rows: int = R.OR_ROWS, k: float = R.OR_ATR) -> bool:
    """L4-OR: the day's 09:15..09:44 range (rows 0..rows-1) < k x the previous day's ATR (NaN-safe; unknown -> False)."""
    h, l = idx[1, :rows], idx[2, :rows]
    if not np.isfinite(atr_prev) or not np.isfinite(h).any() or not np.isfinite(l).any():
        return False
    return bool(np.nanmax(h) - np.nanmin(l) < k * atr_prev)


def l4_or_ok(er: int, narrow: bool, rows: int = R.OR_ROWS) -> bool:
    """Entries before 09:45 are untouched (the range is not known yet); later ones are skipped on a narrow day."""
    return er < rows or not narrow


def pd_narrow_days(bars: dict, atr: dict, k: float = R.PD_ATR) -> set:
    """L4-PD: days d whose PREVIOUS day's range < k x the ATR of the days before that previous day."""
    ds = sorted(bars)
    out = set()
    for i in range(2, len(ds)):
        p, pp = ds[i - 1], ds[i - 2]
        a = atr.get(pp, np.nan)
        if np.isfinite(a) and bars[p][1] - bars[p][2] < k * a:
            out.add(ds[i])
    return out


# ================================================================================================ L6 expiry and gaps
def gap_days(bars: dict, atr: dict, k: float = R.GAP_ATR) -> set:
    """L6-GAP: days whose open is more than k x the previous day's ATR away from the previous close."""
    ds = sorted(bars)
    out = set()
    for i in range(1, len(ds)):
        p = ds[i - 1]
        a = atr.get(p, np.nan)
        if np.isfinite(a) and abs(bars[ds[i]][0] - bars[p][3]) > k * a:
            out.add(ds[i])
    return out


# ================================================================================================ L7 sizing
def high_vol_days(bars: dict, n: int = R.VOL_N, look: int = R.VOL_LOOK, x: float = R.VOL_X) -> set:
    """L7-VOL: days d where the n-session realised vol (std of daily log returns) ending at the PREVIOUS session is
    > x times the median of that vol over the `look` sessions ending at the previous session."""
    ds = sorted(bars)
    c = np.array([bars[d][3] for d in ds], float)
    r = np.diff(np.log(c))                          # r[j] = return of session j+1
    rv = np.full(len(ds), np.nan)                   # rv[i] = vol of the n returns ending at session i
    for i in range(n, len(ds)):
        rv[i] = r[i - n:i].std(ddof=1)
    out = set()
    for i in range(1, len(ds)):
        ref = rv[max(0, i - look):i]
        ref = ref[np.isfinite(ref)]
        if len(ref) >= look and np.isfinite(rv[i - 1]) and rv[i - 1] > x * np.median(ref):
            out.add(ds[i])
    return out


def dd_weights(trades: list, cut: float, half: float = R.HALF) -> dict:
    """L7-DD: {day: weight}: half on days that open with the arm's own SIZED closed equity >= cut below its peak (days
    before are sized first, in order). trades need net and net_half. Pure."""
    by = defaultdict(list)
    for t in trades:
        by[t["day"]].append(t)
    eq = pk = 0.0
    w = {}
    for d in sorted(by):
        w[d] = half if pk - eq >= cut else 1.0
        eq += sum((t["net_half"] if w[d] != 1.0 else t["net"]) for t in by[d])
        pk = max(pk, eq)
    return w


def sized(trades: list, half_of) -> list:
    """Copies of the trades with net replaced by net_half where half_of(t) is True (and w = 0.5 recorded)."""
    out = []
    for t in trades:
        z = dict(t)
        if half_of(t):
            z["net"], z["w"] = t["net_half"], R.HALF
        else:
            z["w"] = 1.0
        out.append(z)
    return out


# ================================================================================================ metrics and gate
def daily(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t["day"]] += t["net"]
    return dict(out)


def max_dd(dv) -> float:
    if not len(dv):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(dv)])
    return float((np.maximum.accumulate(eq) - eq).max())


def breaches(trades: list, month_stop: float = R.MONTH_STOP, dd_stop: float = R.DD_STOP) -> dict:
    """Account stop: months with net < -month_stop; drawdown episodes deeper than dd_stop (an episode ends at a new
    equity peak; counted once)."""
    dl = daily(trades)
    mo = defaultdict(float)
    for d, v in dl.items():
        mo[(d.year, d.month)] += v
    eq = pk = 0.0
    ep, inside = 0, False
    for d in sorted(dl):
        eq += dl[d]
        if eq >= pk:
            pk, inside = eq, False
        elif pk - eq > dd_stop and not inside:
            ep, inside = ep + 1, True
    return dict(months_13k=sum(1 for v in mo.values() if v < -month_stop), dd_40k=ep, months=len(mo))


def better(lv: dict, bs: dict) -> bool:
    """PREREG section 5: (a) net >= base and max DD <= 1.10 x base; or (b) (max DD <= 0.95 x base or worst day better
    by 5% of its size) and net >= base - max(10% |base|, Rs 5,000). lv / bs: dicts with net, max_dd, worst_day."""
    n, b = lv.get("net", 0.0), bs.get("net", 0.0)
    dd, bdd = lv.get("max_dd", 0.0), bs.get("max_dd", 0.0)
    wd, bwd = lv.get("worst_day", 0.0), bs.get("worst_day", 0.0)
    if n >= b and dd <= R.DD_KEEP * bdd:
        return True
    risk = dd <= R.DD_CUT * bdd or wd >= bwd + R.WORST_CUT * abs(bwd)
    return bool(risk and n >= b - max(R.NET_GIVE * abs(b), R.NET_GIVE_RS))


def skip_twins(base_nets, k: int, n: int, rng) -> np.ndarray:
    """Each twin drops k random baseline trades (k <= 0: none). (n,) totals."""
    base_nets = np.asarray(base_nets, float)
    tot = np.full(n, base_nets.sum())
    if k <= 0 or not len(base_nets):
        return tot
    k = min(k, len(base_nets))
    for j in range(n):
        tot[j] -= base_nets[rng.choice(len(base_nets), size=k, replace=False)].sum()
    return tot


def half_twins(base_nets, base_half, m: int, n: int, rng) -> np.ndarray:
    """Each twin halves m random baseline trades (net -> net_half). (n,) totals."""
    a, h = np.asarray(base_nets, float), np.asarray(base_half, float)
    tot = np.full(n, a.sum())
    if m <= 0 or not len(a):
        return tot
    m = min(m, len(a))
    for j in range(n):
        ix = rng.choice(len(a), size=m, replace=False)
        tot[j] -= (a[ix] - h[ix]).sum()
    return tot


def signflip(diff, n: int, rng) -> np.ndarray:
    """Paired sign-flip of daily differences: (n,) sums."""
    diff = np.asarray(diff, float)
    if not len(diff):
        return np.zeros(n)
    s = rng.choice((-1.0, 1.0), size=(n, len(diff)))
    return (s * diff).sum(axis=1)


def p_of(tw, actual: float) -> float:
    return round((1 + int((np.asarray(tw) >= actual - 1e-9).sum())) / (len(tw) + 1), 4)


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


def gate(r: dict) -> list:
    """The failed parts of PREREG section 5 for one test's record r (design / wf_better / final / p / q / affected /
    slip2_better / slip2_net)."""
    fails = []
    if not r["design_better"]:
        fails.append("not better in design")
    if r["wf_better"] < R.WF_NEED:
        fails.append(f"better in {r['wf_better']} of 4 quarters")
    if not r["final_better"]:
        fails.append("not better in the final")
    if r["p"] is None or r["p"] >= R.GATE_P:
        fails.append("random twin p >= 0.05")
    if r["q"] is None or r["q"] > R.GATE_Q:
        fails.append("BH q > 0.10")
    if r["affected"] < R.GATE_AFFECTED:
        fails.append("fewer than 40 trades affected")
    if not r.get("slip2_better") or r.get("slip2_net", 0) <= 0:
        fails.append("fails at 2x slippage")
    return fails
