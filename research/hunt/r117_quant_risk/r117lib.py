"""R117 library (PREREG sections 2-5), pure: the six risk rules on top of R108's book, the day flags they read (VIX,
realised volatility, the book's and arms' own past), the walk that carries them, Part A's quant measures, the twins and
the gates. test_r117.py covers every function, including the look-ahead guard.

Every rule only REFUSES entries that CAP2 would take or count; it never adds one, sizes up, or touches a stop or exit.
Every input a rule reads on day d is known before d's first entry (09:20): the VIX open of 09:15-09:19, daily closes to
d-1, the book's closed net of earlier sessions, the arms' research trades closed on earlier days, and (DAYLOCK only) the
book's trades already closed today.
"""
from __future__ import annotations

import math
from collections import defaultdict, deque
from datetime import date

import numpy as np

import r108book as B


# ------------------------------------------------------------------------------------------------ the rules
class Cap2Plus(B.Rule):
    """CAP2, with cap 1 on a day the walk marks (ctx.prior['cap1']), arms sitting out (ctx.prior['paused']) and an
    optional day lock (no new entry once the day's closed net reaches `lock_at`)."""
    keeps_r72 = True

    def __init__(self, name: str, lock_at: float | None = None):
        self.name, self.lock_at = name, lock_at

    def refuse(self, ctx, c):
        pr = ctx.prior
        if c["arm"] in pr.get("paused", ()):
            return "arm sits out (its last trades are below 0)"
        if self.lock_at is not None and ctx.closed_nets(c["er"])[0] >= self.lock_at:
            return "day locked (good day)"
        cap = 1 if pr.get("cap1") else 2
        d = B.direction(c["side"])
        same = [p for p in ctx.open_real(c["er"]) if p["dir"] == d]
        why = B.count_refusal(same, cap, c, ctx, True)
        if why is not None and cap == 1 and B.count_refusal(same, 2, c, ctx, True) is None:
            return "cap 1 today (" + pr.get("cap1_why", "risk") + ")"
        return why


def make_rule(name: str) -> B.Rule:
    if name == "CAP2":
        return Cap2Plus("CAP2")
    if name == "DAYLOCK":
        return Cap2Plus("DAYLOCK", lock_at=6_500.0)
    if name in ("VIXCAP", "RVCAP", "EQ10", "ARMEQ", "COMBO"):
        return Cap2Plus(name)
    raise KeyError(name)


# ------------------------------------------------------------------------------------------------ day flags
def percentile(values, q: float) -> float:
    v = np.asarray([x for x in values if x is not None and np.isfinite(x)], float)
    if not len(v):
        raise ValueError("no values")
    return float(np.percentile(v, q))


def vix_open(rows_by_day: dict, latest_row: int = 4) -> dict:
    """{day: the open of the first VIX minute at or before `latest_row` (row 0 = 09:15)}; a day without one is left out.
    rows_by_day[d] = list of (row, open)."""
    out = {}
    for d, rows in rows_by_day.items():
        ok = sorted((r, o) for r, o in rows if 0 <= r <= latest_row and o is not None and np.isfinite(o) and o > 0)
        if ok:
            out[d] = float(ok[0][1])
    return out


def realised_vol(closes: dict, sessions: list, window: int = 20) -> dict:
    """{day: stdev (ddof 1) of the last `window` daily log returns of `closes` ENDING YESTERDAY}; closes[d] = close of d.
    Needs window+1 closes before d; else the day is left out."""
    days = sorted(closes)
    pos = {d: i for i, d in enumerate(days)}
    lr = np.diff(np.log(np.asarray([closes[d] for d in days], float)))     # lr[i] = return of days[i+1]
    out = {}
    for d in sessions:
        # the last return ending yesterday: the return into the last close day < d
        prev = [x for x in days if x < d]
        if len(prev) < window + 1:
            continue
        i = pos[prev[-1]]                       # index of yesterday in days; its return is lr[i-1]
        seg = lr[i - window:i]
        if len(seg) == window:
            out[d] = float(np.std(seg, ddof=1))
    return out


def arm_paused(alone: dict, sessions: list, window: int = 20) -> dict:
    """{day: set of arms whose last `window` research trades closed on EARLIER days sum below 0}. alone[arm] = trades
    with 'day' and 'net'. An arm with fewer than `window` earlier trades sums what it has; none = allowed."""
    by_arm = {a: sorted(alone[a], key=lambda t: (t["day"], t.get("xr", 0))) for a in alone}
    out = {}
    for d in sessions:
        s = set()
        for a, tr in by_arm.items():
            past = [t["net"] for t in tr if t["day"] < d][-window:]
            if past and sum(past) < 0:
                s.add(a)
        out[d] = s
    return out


# ------------------------------------------------------------------------------------------------ the walk
def walk_period(days: list, day_cands: dict, name: str, vix_high: dict | None = None, rv_high: dict | None = None,
                paused: dict | None = None, eq_window: int = 10) -> dict:
    """R108's walk with R117's day inputs. Each day: cap1 / paused set from what is known before its first entry
    (vix_high[d], rv_high[d], the book's own net over its previous `eq_window` sessions, paused[d]). Returns {day: recs}."""
    rule = make_rule(name)
    vix_high, rv_high, paused = vix_high or {}, rv_high or {}, paused or {}
    out = {}
    week, month, wk, mo = 0.0, 0.0, None, None
    recent = deque(maxlen=eq_window)
    for d in days:
        if B.week_key(d) != wk:
            wk, week = B.week_key(d), 0.0
        if (d.year, d.month) != mo:
            mo, month = (d.year, d.month), 0.0
        cap1, why = False, ""
        if name in ("VIXCAP", "COMBO") and vix_high.get(d, False):
            cap1, why = True, "VIX high"
        if name == "RVCAP" and rv_high.get(d, False):
            cap1, why = True, "realised volatility high"
        if name in ("EQ10", "COMBO") and len(recent) == eq_window and sum(recent) < 0:
            cap1, why = True, (why + ", " if why else "") + "book's last 10 sessions below 0"
        pz = set(paused.get(d, ())) if name == "ARMEQ" else set()
        prior = {"week": week, "month": month, "cap1": cap1, "cap1_why": why, "paused": pz}
        cands, priced = day_cands.get(d, ([], []))
        recs = B.walk_day(cands, priced, rule, prior, d)
        net = sum(x["r"]["net"] for x in recs if x["out"] == B.REAL)
        week += net
        month += net
        recent.append(net)
        out[d] = recs
    return out


# ------------------------------------------------------------------------------------------------ Part A measures
def trade_stats(nets) -> dict:
    x = np.asarray(nets, float)
    if not len(x):
        return dict(n=0)
    w, l = x[x > 0], x[x <= 0]
    aw = float(w.mean()) if len(w) else 0.0
    al = float(-l.mean()) if len(l) else 0.0
    win = len(w) / len(x)
    payoff = aw / al if al > 0 else math.inf
    be = al / (aw + al) if (aw + al) > 0 else None
    kelly = (win - (1 - win) / payoff) if payoff not in (0, math.inf) else (1.0 if payoff == math.inf else -1.0)
    return dict(n=len(x), win=round(win, 4), avg_win=round(aw), avg_loss=round(al), payoff=round(payoff, 3),
                breakeven_win=None if be is None else round(be, 4), expectancy=round(float(x.mean()), 1),
                kelly_trade=round(kelly, 4))


def daily_stats(v) -> dict:
    v = np.asarray(v, float)
    if len(v) < 3:
        return dict(n=len(v))
    sd = float(v.std(ddof=1))
    down = v[v < 0]
    dsd = float(np.sqrt((down ** 2).sum() / len(v))) if len(down) else 0.0
    ac1 = float(np.corrcoef(v[:-1], v[1:])[0, 1]) if sd > 0 else None
    return dict(n=len(v), mean=round(float(v.mean()), 1), sd=round(sd, 1),
                sharpe=round(float(v.mean()) / sd * math.sqrt(250), 3) if sd > 0 else None,
                sortino=round(float(v.mean()) / dsd * math.sqrt(250), 3) if dsd > 0 else None,
                kelly_daily_per_rupee=float(v.mean()) / (sd ** 2) if sd > 0 else None,
                ac1=None if ac1 is None else round(ac1, 4), ac10=round(block_autocorr(v, 10), 4))


def block_autocorr(v, k: int) -> float:
    """Correlation between the sums of consecutive non-overlapping k-session blocks (does a bad fortnight follow a bad
    one?). nan when fewer than 4 blocks."""
    v = np.asarray(v, float)
    n = len(v) // k
    if n < 4:
        return float("nan")
    s = v[: n * k].reshape(n, k).sum(axis=1)
    if s[:-1].std() == 0 or s[1:].std() == 0:
        return float("nan")
    return float(np.corrcoef(s[:-1], s[1:])[0, 1])


def prob_drawdown(v, limit: float, horizon: int, paths: int, block: int, rng) -> float:
    """Chance the drawdown reaches `limit` within `horizon` sessions, by a circular block bootstrap of the days `v`."""
    v = np.asarray(v, float)
    n = len(v)
    if n == 0:
        return 0.0
    nb = int(math.ceil(horizon / block))
    starts = rng.integers(0, n, size=(paths, nb))
    idx = (starts[:, :, None] + np.arange(block)[None, None, :]) % n
    idx = idx.reshape(paths, -1)[:, :horizon]
    m = v[idx]
    eq = np.concatenate([np.zeros((paths, 1)), np.cumsum(m, axis=1)], axis=1)
    dd = (np.maximum.accumulate(eq, axis=1) - eq).max(axis=1)
    return float((dd >= limit).mean())


def corr_matrix(series: dict) -> dict:
    keys = [k for k in series if np.asarray(series[k]).std() > 0]
    out = {}
    for i, a in enumerate(keys):
        for b in keys[i + 1:]:
            out[f"{a}~{b}"] = round(float(np.corrcoef(series[a], series[b])[0, 1]), 3)
    return out


# ------------------------------------------------------------------------------------------------ twins and gates
def twin_counts(rule_real_by_day: dict, base_real_by_day: dict, sessions: list) -> dict:
    """How many of the base's (CAP2's) real trades a twin removes each day: as many as the rule took fewer."""
    return {d: max(0, len(base_real_by_day.get(d, [])) - len(rule_real_by_day.get(d, []))) for d in sessions}


def net_floor(base_net: float, rs: float = 15_000.0, share: float = 0.15) -> float:
    return base_net - max(rs, share * abs(base_net))


def gate_period(v: dict, base: dict) -> list:
    f = []
    if not v["max_dd"] < base["max_dd"]:
        f.append(f"max drawdown not below CAP2's ({v['max_dd']} vs {base['max_dd']})")
    for k, w in (("days_below", "days at or below -Rs 13,000"), ("months_below", "months at or below -Rs 13,000"),
                 ("dd_breaches", "Rs 40,000 drawdowns")):
        if v[k] > base[k]:
            f.append(f"more {w} than CAP2 ({v[k]} vs {base[k]})")
    fl = net_floor(base["net"])
    if v["net"] < fl:
        f.append(f"net {v['net']} below the floor {round(fl)} (CAP2 {base['net']})")
    return f


def gates(per: dict, q_all: float, changed_final: int, periods=("design", "wf", "final"), q_max: float = 0.10,
          min_changed: int = 30) -> dict:
    fails = []
    for p in periods:
        fails += [f"{p}: {x}" for x in gate_period(*per[p])]
    if not q_all <= q_max:
        fails.append(f"twin BH q {q_all:.4f} > {q_max}")
    if changed_final < min_changed:
        fails.append(f"only {changed_final} final trades differ from CAP2's (< {min_changed})")
    return dict(passed=not fails, fails=fails, q_all=round(float(q_all), 4))


def paired_days(rule_v, base_v, n_boot: int, rng) -> dict:
    """Amendment 1: the day-by-day difference (rule - CAP2) on the days they differ, bootstrapped over those days.
    p = one-sided chance the mean difference is not above 0. A fair check for DAYLOCK, whose twin is tilted."""
    diff = np.asarray(rule_v, float) - np.asarray(base_v, float)
    x = diff[np.abs(diff) > 0.5]
    if len(x) < 2:
        return dict(days=int(len(x)), mean=round(float(x.mean()), 1) if len(x) else 0.0, p=1.0, lo=None, hi=None)
    means = x[rng.integers(0, len(x), size=(n_boot, len(x)))].mean(axis=1)
    return dict(days=int(len(x)), mean=round(float(x.mean()), 1), total=round(float(x.sum())),
                lo=round(float(np.percentile(means, 5)), 1), hi=round(float(np.percentile(means, 95)), 1),
                p=round(float((1 + (means <= 0).sum()) / (n_boot + 1)), 6))


def by_day(trades: list) -> dict:
    out = defaultdict(list)
    for t in trades:
        out[t["day"]].append(float(t["net"]))
    return dict(out)


def flags_high(values: dict, threshold: float) -> dict:
    return {d: bool(x >= threshold) for d, x in values.items()}


def iso(d: date) -> str:
    return d.isoformat()
