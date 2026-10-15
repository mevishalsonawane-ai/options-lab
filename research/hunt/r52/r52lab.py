"""R52 harness: run an old rule's own signal function through research/obuy's engine on the Nov 2024+ lake, with R52's
measurement, split, random-entry twins and gates (PREREG.md).

    measurement  one held contract per trade (obuy Chain re-keys the rolling series by strike), Fills("liq") = R17's
                 spread model (app bps + 1/2/4-tick half-spread by 5-minute volume, stops x2), Costs("dated") = the
                 charges in force on the trade date, lot = the contract's own lot that day (obuy lot_mode 'data', read
                 from the OI moves = the lot in force), next-minute fills, stops gapped through fill at the open.
    split        trades kept only on sessions 20 Nov 2024 .. 31 Mar 2026 (design) or 1 Apr .. 13 Oct 2026 (final,
                 only after r52cfg.FINAL_FLAG is written by `r52study.py final`).
    twins        obuy random pool: per candidate POOL_K alternatives (same day, index and side, a random minute in the
                 rule's entry window, same strike rule and exit distances); 2000 twin draws (>= 500) -> one-sided p.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field, replace

import numpy as np
import pandas as pd

import r52cfg as K
from obuy import config as OC
from obuy.costs import Costs, Fills
from obuy.data import market
from obuy.engine import Execution, Exits, StrikeRule, positions, prepare_many
from obuy.overfit import random_baseline

POOL_K = 20
B_TWINS = 2000
FILLS = Fills("liq")
COSTS = Costs("dated")
KEEP = ["cand", "und", "book", "day", "year", "sig_min", "entry_min", "exit_min", "side", "strike", "qty", "entry",
        "exit", "why", "gross", "charges", "net"]


@dataclass
class Rule:
    """One rule (or one setting of it) to test."""
    key: str                         # catalogue key, e.g. OBUY_GB-4
    vid: str                         # variant id inside the rule ('orig', 'rt1', ...)
    name: str
    signal_fn: object                # (mk, **params) -> signals DataFrame (obuy engine format)
    params: dict = field(default_factory=dict)
    rule: StrikeRule = field(default_factory=StrikeRule)
    exits: Exits = field(default_factory=Exits)
    expiry: str = "skip"             # Execution.expiry
    pos: dict = field(default_factory=lambda: dict(one_at_a_time=True))
    window: tuple | None = None
    source: str = "index"            # signal source: index | index+chain | option-chart (NON-DEFAULT)
    unds: tuple = ()                 # restrict to these indices (empty = whatever the signals hold)

    @property
    def id(self):
        return f"{self.key}:{self.vid}"

    def exe(self) -> Execution:
        return Execution(fills=FILLS, costs=COSTS, lot_mode="data", expiry=self.expiry)


def in_part(days, part):
    days = pd.Series(pd.to_datetime(pd.Series(days)).dt.date.values)
    if part == "design":
        return ((days >= K.FIRST_TRADE) & (days <= K.DESIGN_END)).values
    if part == "final":
        if not K.FINAL_FLAG.exists():
            raise PermissionError("final test not unlocked: only `r52study.py final` reads it, once")
        return ((days >= K.FINAL_START) & (days <= K.LAST_DAY)).values
    raise ValueError(part)


_SIG = {}


def signals(r: Rule) -> pd.DataFrame:
    key = (r.signal_fn.__module__, r.signal_fn.__name__, repr(sorted(r.params.items())))
    if key not in _SIG:
        s = r.signal_fn(market(), **r.params)
        market().release()
        _SIG[key] = s if s is not None else pd.DataFrame()
    s = _SIG[key]
    if len(s) and r.unds:
        s = s[s.und.isin(r.unds)]
    return s


def sessions(unds, part) -> list:
    """(und, day) of every tradable session of these indices in the part (a day without a trade counts 0)."""
    out = []
    for u in unds:
        ds = market().index(u).days
        m = in_part(ds, part)
        out += [(u, d) for d, ok in zip(ds, m) if ok]
    return out


def run(rules: list, part: str, twins: bool = True, seed: int = K.SEED) -> dict:
    """{rule id: dict(trades, pool trades or None, sessions)} for the part. One data pass for all rules."""
    jobs, meta = [], []
    for r in rules:
        s = signals(r)
        if len(s):
            keep = in_part(s.day, part)
            if "exit_day" in s.columns:          # a multi-day hold must also end inside the part
                keep &= in_part(s.exit_day, part)
            s = s[keep].reset_index(drop=True)
        jobs.append((s, r.rule, r.exe(), POOL_K if twins else 0, r.window, True))
        meta.append(r)
    packs = prepare_many([j for j in jobs if len(j[0])], seed=seed) if any(len(j[0]) for j in jobs) else []
    it = iter(packs)
    out = {}
    for (s, *_), r in zip(jobs, meta):
        unds = r.unds or (tuple(sorted(s.und.unique())) if len(s) else ())
        if not len(s):
            out[r.id] = dict(trades=pd.DataFrame(columns=KEEP), pool=None, sessions=sessions(unds, part), rule=r)
            continue
        pk, pool = next(it)
        tr = pk.run(r.exits, r.exe()) if len(pk) else pd.DataFrame(columns=KEEP)
        tr = positions(tr, **r.pos) if len(tr) else tr
        tr = tr[KEEP] if len(tr) else pd.DataFrame(columns=KEEP)
        pt = pool.run(r.exits, r.exe()) if (twins and pool is not None and len(pool)) else None
        out[r.id] = dict(trades=tr.reset_index(drop=True), pool=pt, sessions=sessions(unds, part), rule=r)
    return out


def score(res: dict, B: int = B_TWINS) -> dict:
    tr, ss = res["trades"], res["sessions"]
    days = sorted({d for _, d in ss})
    by_day = pd.Series(0.0, index=pd.Index(days, dtype=object))
    if len(tr):
        g = tr.groupby("day").net.sum()
        by_day = by_day.add(g, fill_value=0.0)
    nets = tr.net.values.astype(float) if len(tr) else np.zeros(0)
    wins, losses = nets[nets > 0], nets[nets <= 0]
    eq = np.cumsum(by_day.values) if len(by_day) else np.zeros(1)
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(by_day) else 0.0
    net = float(nets.sum())
    top5 = float(np.sort(nets)[::-1][:5].sum()) if len(nets) else 0.0
    rb = random_baseline(tr, res["pool"], B=B, seed=K.SEED) if (res["pool"] is not None and len(tr)) else dict(p=1.0, null_mean=np.nan, n_used=0)
    traded = by_day[by_day.index.isin(set(tr.day))] if len(tr) else by_day.iloc[:0]
    return dict(
        trades=int(len(nets)), days=len(days), win_rate=float((nets > 0).mean()) if len(nets) else 0.0,
        net=net, per_trade=float(nets.mean()) if len(nets) else 0.0, per_day=net / len(days) if days else 0.0,
        avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
        worst_day=float(traded.min()) if len(traded) else 0.0, max_dd=dd,
        top5_share=(top5 / net) if net > 0 else float("nan"),
        p=float(rb["p"]), twin_mean=float(rb.get("null_mean", np.nan)), twins_used=int(rb.get("n_used", 0)),
        by_und={u: float(x) for u, x in tr.groupby("und").net.sum().items()} if len(tr) else {},
        by_fold=fold_nets(tr), exits={str(w): int(n) for w, n in tr.why.value_counts().items()} if len(tr) else {},
    )


def fold_nets(tr: pd.DataFrame) -> list:
    out = []
    for a, b in K.FOLDS:
        if len(tr):
            m = (tr.day >= a) & (tr.day <= b)
            out.append(float(tr.net[m].sum()))
        else:
            out.append(0.0)
    return out


def walk_forward(variants: dict) -> dict:
    """variants: {vid: trades (design)}. Expanding window: before each fold, pick the variant with the best net on the
    design sessions before it (20 Nov 2024 .. fold start); the fold's net of that pick counts. Returns picks + total."""
    picks, tot = [], 0.0
    for (a, b) in K.FOLDS:
        best, bv = None, -np.inf
        for vid, tr in variants.items():
            x = float(tr.net[tr.day < a].sum()) if len(tr) else 0.0
            if x > bv:
                best, bv = vid, x
        tr = variants[best]
        f = float(tr.net[(tr.day >= a) & (tr.day <= b)].sum()) if len(tr) else 0.0
        picks.append(dict(fold=f"{a}..{b}", pick=best, train_net=bv, test_net=f))
        tot += f
    return dict(picks=picks, net=tot)


def bh(ps):
    from obuy.overfit import bh as _bh
    return list(_bh(np.asarray(ps, dtype=float)))


def gates(fin: dict, wf_net: float, q: float) -> dict:
    g = dict(
        final_net=fin["net"] > 0,
        p=fin["p"] < K.P_MAX,
        q=q <= K.BH_Q,
        trades=fin["trades"] >= K.MIN_FINAL_TRADES,
        top5=(fin["net"] > 0 and fin["top5_share"] < 1.0),
        design_wf=wf_net > 0,
    )
    g["pass"] = all(g.values())
    g["verdict"] = ("PASS" if g["pass"] else "too few trades to judge" if not g["trades"] else "FAIL")
    return g


def env_check():
    """The lake laid out for obuy must be present (lake2obuy.py)."""
    p = os.path.join(OC.DATA, "options")
    if not os.path.isdir(p):
        raise FileNotFoundError(f"obuy data not found at {OC.DATA}: run lake2obuy.py and set OBUY_DATA")


def with_(r: Rule, **kw) -> Rule:
    return replace(r, **kw)
