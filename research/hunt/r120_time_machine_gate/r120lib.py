"""R120 library (PREREG.md): sessions from the lake, the verdict per trade, the gates, random-skip twins, BH, calibration.

A trade (dict) carries: arm, und, day (date), entry_ts (datetime, the fill minute), side (+1 the underlying must rise,
-1 fall), net (Rs, after charges), stop_pts / target_pts (underlying points, as the app's TimeMachineHost.points gives
them; None = none), exit_at (time or None), bar_minutes. 28 Oct 2026.
"""
from __future__ import annotations

import math
import re
from collections import defaultdict
from datetime import date, datetime, time
from pathlib import Path

import numpy as np

import r120cfg as K
import tm

# ---------------------------------------------------------------- sessions (the underlying's own past bars)


def sessions_from_frame(df, bar_minutes: int, lo: time = time(9, 15), hi: time = time(15, 30)) -> dict:
    """{date: tm.Session} from a 1-minute frame (ts tz-aware or naive IST, open/high/low/close), minutes in [lo, hi),
    folded to bar_minutes as the app folds them. A minute with no close is left out (the phone keeps none)."""
    out = {}
    ts = df["ts"]
    if getattr(ts.dt, "tz", None) is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    o, h, l, c = (df[k].to_numpy(float) for k in ("open", "high", "low", "close"))
    by = defaultdict(list)
    for i, t in enumerate(ts.dt.to_pydatetime()):
        if not (lo <= t.time() < hi) or not math.isfinite(c[i]):
            continue
        oo = o[i] if math.isfinite(o[i]) else c[i]
        hh = h[i] if math.isfinite(h[i]) else c[i]
        ll = l[i] if math.isfinite(l[i]) else c[i]
        by[t.date()].append(tm.Bar(t, oo, hh, ll, c[i]))
    for d, ones in by.items():
        out[d] = tm.Session(d, tm.fold(ones, bar_minutes))
    return out


def before(sessions: dict, day: date, keep: int = tm.KEEP_SESSIONS) -> list:
    """The look-ahead guard: only sessions dated strictly before the entry day, the newest keep (the app's usable()
    applies the same cut again)."""
    ds = sorted(d for d in sessions if d < day)[-keep:]
    return [sessions[d] for d in ds]


def ref_of(t: dict) -> str:
    return f"r120|{t['arm']}|{t['und']}|{t['entry_ts']:%Y-%m-%d %H:%M}"


def verdict(t: dict, sessions: dict) -> tm.Odds:
    """The app's check for trade t, from the underlying's sessions before its day only."""
    bm = int(t.get("bar_minutes") or 5)
    stop, target = t.get("stop_pts"), t.get("target_pts")
    ask = tm.Ask(int(t["side"]), tm.slot_of(t["entry_ts"], bm), float("nan") if stop is None else float(stop),
                 float("nan") if target is None else float(target), bm, t.get("exit_at"), seed=tm.java_hash(ref_of(t)))
    return tm.odds(before(sessions, t["day"]), ask, t["day"])


# ---------------------------------------------------------------- metrics


def daily(trades: list) -> list:
    by = defaultdict(float)
    for t in trades:
        by[t["day"]] += t["net"]
    return [by[d] for d in sorted(by)]


def max_drawdown(values) -> float:
    """The worst fall of the cumulative sum from its running peak (peak starts at 0), as a positive number."""
    peak = cum = 0.0
    worst = 0.0
    for v in values:
        cum += v
        peak = max(peak, cum)
        worst = max(worst, peak - cum)
    return worst


def metrics(trades: list, kept: list | None = None) -> dict:
    """trades: the arm's trades; kept: a bool per trade (None: all kept). Skipped trades earn 0 (their day still exists)."""
    kept = [True] * len(trades) if kept is None else list(kept)
    k = [t for t, x in zip(trades, kept) if x]
    by = defaultdict(float)
    for t in trades:
        by[t["day"]] += 0.0
    for t in k:
        by[t["day"]] += t["net"]
    dd = max_drawdown([by[d] for d in sorted(by)])
    wins = sum(1 for t in k if t["net"] > 0)
    return dict(trades=len(trades), kept=len(k), skipped=len(trades) - len(k), net=round(sum(t["net"] for t in k), 2),
                win=(round(wins / len(k), 4) if k else None), max_dd=round(dd, 2))


def keep_mask(verdicts: list, rule: str) -> list:
    take = K.RULES[rule]["take"]
    return [v in take for v in verdicts]


def twins(nets: np.ndarray, k: int, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Nets after skipping k trades chosen uniformly at random, n draws."""
    nets = np.asarray(nets, float)
    total = nets.sum()
    if k <= 0 or len(nets) == 0:
        return np.full(n, total)
    rng = np.random.default_rng(seed)
    out = np.empty(n)
    for i in range(n):
        out[i] = total - nets[rng.choice(len(nets), size=min(k, len(nets)), replace=False)].sum()
    return out


def twin_p(gate_net: float, tw: np.ndarray) -> float:
    return float((1 + np.sum(tw >= gate_net - 1e-9)) / (1 + len(tw)))


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q of the given p within m_total tests (the other tests taken as p = 1)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(n, int(m_total or n))
    if n == 0:
        return p
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


def count_prior(entries_dir=K.ENTRIES, last_day: str = K.ENTRIES_LAST_DAY) -> int:
    """R115-R119's count: every family logged up to last_day, each the larger of its entry count and its summed
    variants_tried (family = the id's leading 'r<N>' or its first word); R120's own entries are never counted."""
    import yaml
    fam, var = defaultdict(int), defaultdict(int)
    for f in sorted(Path(entries_dir).glob("*.yaml")):
        y = yaml.safe_load(f.read_text()) or {}
        if str(y.get("date", "")) > last_day:
            continue
        i = str(y.get("id", ""))
        if i.startswith("r120"):
            continue
        m = re.match(r"r(\d+)", i)
        key = ("R" + m.group(1)) if m else re.split(r"[-_]", i)[0]
        fam[key] += 1
        try:
            v = int(y.get("variants_tried") or 1)
        except (TypeError, ValueError):
            v = 1
        var[key] += max(v, 1)
    return sum(max(fam[k], var[k]) for k in fam)


def gates(rule_m: dict, base_m: dict, p: float, q: float) -> dict:
    """PREREG section 5. rule_m / base_m: {'design', 'wf', 'final'} -> metrics."""
    fails = []
    for per in ("design", "wf", "final"):
        if not (rule_m[per]["net"] > base_m[per]["net"]):
            fails.append(f"{per}: net not above the arm's own")
    if not (p <= K.GATE_P):
        fails.append(f"final: not above the random twin's 95th percentile (p {p:.3f})")
    if not (q <= K.GATE_Q):
        fails.append(f"BH q {q:.3f} > {K.GATE_Q}")
    if rule_m["final"]["kept"] < K.MIN_FINAL:
        fails.append(f"final: {rule_m['final']['kept']} trades kept < {K.MIN_FINAL}")
    if rule_m["final"]["max_dd"] > base_m["final"]["max_dd"]:
        fails.append("final: drawdown worse")
    return dict(passed=not fails, fails=fails)


# ---------------------------------------------------------------- calibration


def calibration(pred: list, won: list, base_rate: float | None) -> dict:
    """Brier of the predicted win rate vs the outcome, against a constant forecast (base_rate); ten bins with n."""
    pr = np.asarray(pred, float)
    y = np.asarray(won, float)
    ok = np.isfinite(pr)
    pr, y = pr[ok], y[ok]
    if len(pr) == 0:
        return dict(n=0)
    br = float(np.mean((pr - y) ** 2))
    b0 = float(np.mean((base_rate - y) ** 2)) if base_rate is not None else None
    bins = []
    for j in range(10):
        lo, hi = j / 10, (j + 1) / 10
        sel = (pr >= lo) & ((pr < hi) if j < 9 else (pr <= hi))
        if sel.any():
            bins.append(dict(bin=f"{int(lo * 100)}-{int(hi * 100)}%", n=int(sel.sum()),
                             predicted=round(float(pr[sel].mean()), 3), actual=round(float(y[sel].mean()), 3)))
    corr = float(np.corrcoef(pr, y)[0, 1]) if len(pr) > 2 and pr.std() > 0 and y.std() > 0 else None
    return dict(n=int(len(pr)), brier=round(br, 4), brier_base=(None if b0 is None else round(b0, 4)),
                base_rate=base_rate, corr=(None if corr is None else round(corr, 4)), bins=bins)
