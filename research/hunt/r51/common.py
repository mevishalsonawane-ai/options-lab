"""R51 shared pieces: the period guard, the day iterator, sequential trade-taking, statistics, twins and BH.

The guard: nothing after DESIGN_END is ever simulated unless `study.py final` has opened the one final-test read
(`open_final()`), so no design step can see a final-test session.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from typing import Callable

import numpy as np
import pandas as pd

import engine as E
import r51cfg as K
import r51data

_FINAL_OPEN = False


def open_final():
    global _FINAL_OPEN
    _FINAL_OPEN = True


def final_open() -> bool:
    return _FINAL_OPEN


def check_range(lo: date, hi: date):
    if lo < K.START:
        raise PermissionError(f"R51 trades nothing before {K.START} (asked for {lo})")
    if hi > K.DESIGN_END and not _FINAL_OPEN:
        raise PermissionError(f"R51: {hi} is in the final test (from {K.FINAL_START}); read only by `study.py final`")


def days_in(und: str, lo: date, hi: date) -> list:
    """Tradable sessions of an index (option data present) in [lo, hi], after the guard."""
    check_range(lo, hi)
    days = r51data.load(und)
    return [d for d in sorted(days) if lo <= d <= hi and days[d].atm is not None]


def take(und: str, D, cands: list, ex: E.Exits, max_per_day: int = 99, scope: str = "side", max_per_side=None):
    """cands: dicts with er (entry row), side, strike, optional fx (forced exit row) and any extra keys, in time order.
    Simulates all, then walks them: one open position per `scope` ('side', 'all' or 'none'), at most `max_per_day`
    fills a day (and `max_per_side` a side). Returns the taken trades (dicts, extra keys kept)."""
    if not cands:
        return []
    res = E.run_day(und, D, [c["side"] for c in cands], [c["strike"] for c in cands], [c["er"] for c in cands], ex,
                    [c.get("fx", -1) for c in cands])
    out, busy, n_side = [], {}, {}
    for c, r in zip(cands, res):
        if r is None or len(out) >= max_per_day:
            continue
        key = c["side"] if scope == "side" else "all"
        if scope != "none" and c["er"] <= busy.get(key, -1):
            continue
        if max_per_side is not None and n_side.get(c["side"], 0) >= max_per_side:
            continue
        busy[key] = r["xr"]
        n_side[c["side"]] = n_side.get(c["side"], 0) + 1
        r.update({k: v for k, v in c.items() if k not in r and k not in ("fx",)})
        out.append(r)
    return out


# ------------------------------------------------------------------------------------------------ configs and twins
@dataclass
class TwinSpec:
    """How to draw random-entry twins for one index: the rule's entry window, strike rule and option exits."""
    exits: E.Exits
    first_row: int
    last_row: int
    money: int = 0
    forced_fn: Callable | None = None


@dataclass
class Config:
    """One runnable rule setting. run(lo, hi) -> trades frame; twin(und) -> TwinSpec (None = no twins)."""
    name: str
    run: Callable
    twin: Callable | None = None
    twin_fn: Callable | None = None          # custom twin totals (trades, n, rng) -> (n,) array


@dataclass
class Variant:
    name: str
    family: str
    source: str                  # signal source
    kind: str                    # 'orig' | 'retune' | 'ref'
    configs: list                # one Config for orig/ref; the grid for retune (configs[0] = the original)
    desc: str = ""
    old: str = ""
    note: str = ""
    extra: dict = field(default_factory=dict)


COLS = ["und", "day", "side", "strike", "er", "xr", "E", "X", "rE", "rX", "qty", "why", "net", "gross"]


def frame(rows: list) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame(columns=COLS)
    return pd.DataFrame(rows)


def max_dd(daily: np.ndarray) -> float:
    if not len(daily):
        return 0.0
    eq = np.concatenate([[0.0], np.cumsum(daily)])
    return float((np.maximum.accumulate(eq) - eq).max())


def n_sessions(unds, lo: date, hi: date) -> int:
    s = set()
    for u in unds:
        s.update(days_in(u, lo, hi))
    return len(s)


def stats(T: pd.DataFrame, sessions: int) -> dict:
    n = len(T)
    if not n:
        return dict(trades=0, win=None, net=0.0, per_day=0.0, avg_win=None, avg_loss=None, worst_day=0.0, max_dd=0.0,
                    ex_top5=0.0, top5_share=None, days=0)
    net = T.net.values.astype(float)
    daily = T.groupby("day").net.sum().sort_index()
    top5 = np.sort(net)[::-1][:5].sum()
    tot = float(net.sum())
    w, lo = net[net > 0], net[net <= 0]
    return dict(trades=int(n), win=round(float((net > 0).mean()), 4), net=round(tot, 0),
                per_day=round(tot / max(sessions, 1), 1),
                avg_win=round(float(w.mean()), 0) if len(w) else None,
                avg_loss=round(float(lo.mean()), 0) if len(lo) else None,
                worst_day=round(float(daily.min()), 0), max_dd=round(max_dd(daily.values), 0),
                ex_top5=round(tot - float(top5), 0),
                top5_share=round(float(top5) / tot, 3) if tot > 0 else None, days=int(len(daily)),
                why={k: int(v) for k, v in T.why.value_counts().items()})


def twin_p(T: pd.DataFrame, cfg: Config, n: int = K.TWINS, seed: int = K.SEED):
    """One-sided p of the rule's net against random-entry twins (same days, counts and sides). None if no twins."""
    if not len(T):
        return None, None
    rng = np.random.default_rng(seed)
    if cfg.twin_fn is not None:
        tot = cfg.twin_fn(T, n, rng)
    else:
        if cfg.twin is None:
            return None, None
        tot = np.zeros(n)
        for und, g in T.groupby("und"):
            spec = cfg.twin(und)
            if spec is None:
                return None, None
            days = r51data.load(und)
            plan = [(d, s, int(len(h)), spec.first_row, spec.last_row) for (d, s), h in g.groupby(["day", "side"])]
            tot += E.twin_totals(und, days, plan, spec.exits, n, rng, spec.money, spec.forced_fn)
    actual = float(T.net.sum())
    p = (1 + int((tot >= actual).sum())) / (n + 1)
    return round(p, 4), round(float(tot.mean()), 0)


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
