"""R87 Part 2: at most 3 candidate rules, picked MECHANICALLY from the design atlas (PREREG section 8), priced as
option buys with real costs, walk-forward, random-entry twins, Benjamini-Hochberg.

    python -m research.hunt.r87_candle_anatomy.rules select     # writes results/rules_selected.json (then A1)
    python -m research.hunt.r87_candle_anatomy.rules design     # design + walk-forward + twins (frozen rules only)

Signal source of every rule: the index's own candle (NIFTY / BANKNIFTY / SENSEX spot), its context columns (which may
include option-chain context: OI shift, gamma proxy, activity). Trade: BUY the 1-ITM call (up) or put (down).
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from . import build as B
from . import cfg
from . import data as D
from . import sim as S
from . import stats as ST

STOPS = (0.15, 0.25)                    # premium stop variants (walk-forward picks)
ENTRY_FIRST, ENTRY_LAST = cfg.row(9, 30), cfg.row(14, 30)
COST_DELTA = 0.6
COST_ROW = cfg.row(10, 30)
GATE_P = 0.10
MIN_TRADES = 30
SEL_FILE = cfg.RESULTS / "rules_selected.json"


# ------------------------------------------------------------------------------------------------ cost screen
def cost_points(und: str, frm: date, to: date) -> dict:
    """An option buyer's round trip in INDEX points (PREREG 8.1): median 1-ITM call premium P at 10:30 over the days,
    cost per unit = 2 x 5 bps x P + 2 ticks + charges(P in, P out) / lot; points = cost / delta 0.6."""
    o = D.option_minutes(und)
    o = o[(o.ts.dt.date >= frm) & (o.ts.dt.date <= to) & (o.offset == -1) & (o.side == "C")]
    r = (o.ts.dt.hour * 60 + o.ts.dt.minute - cfg.OPEN_MIN)
    p = o[r == COST_ROW].c
    P = float(p.median())
    qty = cfg.lot(und, to)
    unit = 2 * S.BPS_MKT * P + 2 * S.TICK + S.charges(und, P, P, qty, to) / qty
    return dict(und=und, premium=P, days=int(len(p)), lot=qty, unit_cost=unit, points=unit / COST_DELTA)


# ------------------------------------------------------------------------------------------------ selection
def select(p1: pd.DataFrame, p2: pd.DataFrame, cost: dict, k: int = 3) -> list[dict]:
    """PREREG 8.1, mechanical: eligible = tf 5/15, tested, q < 0.10, primary effect the same sign in >= 3 of 4 design
    folds, the same sign in the rule window (>= 30 events), and |mean move in the effect's direction| in the rule
    window > the option round trip in points. Ranked by |z|; one rule per candle family (cond ignored). If fewer than
    k pass, the slots are filled from cells failing only the cost screen (labelled)."""
    t = pd.concat([p1.assign(src="P1"), p2.assign(src="P2")], ignore_index=True)
    t = t[t.tf.isin([5, 15]) & t.tested & (t.q < cfg.Q)].copy()
    if t.empty:
        return []
    sg = np.sign(t.eff)
    folds = t.fold_eff.apply(lambda v: v if isinstance(v, list) else json.loads(v))
    t["fold_same"] = [int(sum(1 for x in f if x is not None and x == x and np.sign(x) == s)) for f, s in zip(folds, sg)]
    t["rw_same"] = (np.sign(t.rw_eff) == sg) & (t.rw_n >= 30)
    t["dir_pts"] = sg * t.rw_move_pts
    t["cost_pts"] = t.und.map(lambda u: cost[u]["points"])
    t["stable"] = (t.fold_same >= 3) & t.rw_same
    t["cost_ok"] = t.dir_pts > t.cost_pts
    t = t.assign(az=t.z.abs()).sort_values("az", ascending=False)
    picked, fams = [], set()
    for label, sub in (("passes every screen", t[t.stable & t.cost_ok]),
                       ("fails the cost screen (labelled)", t[t.stable & ~t.cost_ok])):
        for _, r in sub.iterrows():
            if len(picked) >= k:
                break
            if r.family in fams:
                continue
            fams.add(r.family)
            picked.append(dict(id=f"R87-{len(picked) + 1}", und=r.und, tf=int(r.tf), family=r.family, cond=r.cond,
                               src=r.src, long=bool(r.eff > 0), eff=float(r.eff), z=float(r.z), q=float(r.q),
                               n=int(r.n), fold_same=int(r.fold_same), rw_n=int(r.rw_n),
                               dir_pts=float(r.dir_pts), cost_pts=float(r.cost_pts), screen=label))
    return picked


# ------------------------------------------------------------------------------------------------ market of one instrument
class Market:
    """Index closes, ATM strike and option contracts of one instrument by day (lazy, cached)."""

    def __init__(self, und: str):
        self.und = und
        m = D.index_minutes(und)
        self.r = (m.ts.dt.hour * 60 + m.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
        self.m = m.assign(d=m.ts.dt.date, r=self.r)
        o = D.option_minutes(und)
        self.o = o.assign(d=o.ts.dt.date)
        self.ogrp = self.o.groupby("d").indices
        self.mgrp = self.m.groupby("d").indices
        from .context import expiry_set
        self.exp = expiry_set(und, cfg.START, cfg.END)
        self._c = {}

    def days(self, frm: date, to: date) -> list[date]:
        return sorted(d for d in self.ogrp if frm <= d <= to and d in self.mgrp)

    def day(self, d: date):
        if d not in self._c:
            mi = self.m.iloc[self.mgrp[d]]
            c = np.full(cfg.NROWS, np.nan)
            c[mi.r.to_numpy()] = mi.c.to_numpy()
            oi = self.o.iloc[self.ogrp[d]] if d in self.ogrp else self.o.iloc[:0]
            atm = np.full(cfg.NROWS, np.nan)
            a = oi[(oi.offset == 0)].drop_duplicates("ts")
            ar = (a.ts.dt.hour * 60 + a.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
            atm[ar] = a.strike.to_numpy()
            atm = pd.Series(atm).ffill().to_numpy()
            self._c[d] = (c, atm, D.option_day(oi), d in self.exp)
        return self._c[d]


def signals(rule: dict, frm: date, to: date) -> pd.DataFrame:
    """Signal candles of a rule in [frm, to]: day, sig_row (the candle's last minute), long, und_stop."""
    k = B.load(rule["und"], rule["tf"], frm, to)
    m = k[rule["family"]].to_numpy(bool)
    if rule.get("cond"):
        m &= k["c_" + rule["cond"]].to_numpy(bool)
    k = k[m]
    sig = (k.tl.dt.hour * 60 + k.tl.dt.minute - cfg.OPEN_MIN).to_numpy()
    ok = (sig + 1 >= ENTRY_FIRST) & (sig + 1 <= ENTRY_LAST)
    k, sig = k[ok], sig[ok]
    long = rule["long"]
    return pd.DataFrame(dict(day=k.day.dt.date.to_numpy(), sig_row=sig, long=long,
                             und_stop=(k.l if long else k.h).to_numpy(float)))


def run(rule: dict, mk: Market, sig: pd.DataFrame, stop: float) -> pd.DataFrame:
    """Walk the signals in time; one open trade per rule (a signal while a trade is open is skipped)."""
    out, busy_until = [], (None, -1)
    hold = 5 * rule["tf"]
    have = set(mk.ogrp)
    for s in sig.itertuples(index=False):
        if s.day not in have:
            continue
        if busy_until[0] == s.day and s.sig_row + 1 <= busy_until[1]:
            continue
        c, atm, con, ex = mk.day(s.day)
        t = S.price_trade(mk.und, s.day, con, c, atm, int(s.sig_row), bool(s.long), float(s.und_stop), hold, stop, ex)
        if t is None:
            continue
        out.append(t)
        busy_until = (s.day, t["xr"])
    return pd.DataFrame(out)


def summary(tr: pd.DataFrame) -> dict:
    if tr.empty:
        return dict(trades=0, net=0.0, win=np.nan, top5_removed=0.0, worst_day=0.0, max_dd=0.0, months_below_13k=0)
    net = tr.net.to_numpy()
    daily = tr.groupby("day").net.sum()
    eq = daily.cumsum()
    dd = float((eq.cummax().clip(lower=0) - eq).max())
    mon = tr.groupby(pd.to_datetime(tr.day).dt.to_period("M")).net.sum()
    return dict(trades=int(len(tr)), net=float(net.sum()), win=float((net > 0).mean()),
                top5_removed=float(np.sort(net)[:-5].sum()) if len(net) > 5 else 0.0, worst_day=float(daily.min()),
                max_dd=dd, months_below_13k=int((mon < -13000).sum()), avg=float(net.mean()),
                stale_exits=int(tr.stale.sum()), why=tr.why.value_counts().to_dict())


def twins(rule: dict, mk: Market, tr: pd.DataFrame, days: list[date], stop: float, n: int = cfg.TWINS,
          seed: int = cfg.SEED) -> np.ndarray:
    """Random-entry twins (PREREG 8.4): every trade moved to a random day of the same window (with option data), the
    same entry minute, side and exits; the underlying stop = the far end of that day's candle ending at the same
    minute. Returns the n twin nets."""
    rng = np.random.default_rng(seed)
    k = B.load(rule["und"], rule["tf"], days[0], days[-1])
    key = {(d, r): (lo, hi) for d, r, lo, hi in zip(k.day.dt.date, (k.tl.dt.hour * 60 + k.tl.dt.minute
                                                                       - cfg.OPEN_MIN), k.l, k.h)}
    hold = 5 * rule["tf"]
    rows = tr.er.to_numpy() - 1 if len(tr) else np.zeros(0, int)
    out = np.zeros(n)
    for i in range(n):
        tot = 0.0
        for sr in rows:
            d = days[rng.integers(len(days))]
            lh = key.get((d, int(sr)))
            if lh is None:
                continue
            c, atm, con, ex = mk.day(d)
            t = S.price_trade(mk.und, d, con, c, atm, int(sr), rule["long"], lh[0] if rule["long"] else lh[1],
                              hold, stop, ex)
            if t is not None:
                tot += t["net"]
        out[i] = tot
    return out


def walk_forward(rule: dict, mk: Market, sig: pd.DataFrame) -> dict:
    """Each fold uses the stop variant with the higher net on rule-window design days BEFORE the fold (ties: 15%)."""
    res = {}
    allt = {s: run(rule, mk, sig, s) for s in STOPS}
    for s in STOPS:
        res[f"design_{int(s * 100)}"] = summary(allt[s])
    wf_net, wf_trades, picks = 0.0, 0, []
    for a, b in cfg.RULE_FOLDS:
        before = {s: float(allt[s][allt[s].day < a].net.sum()) if len(allt[s]) else 0.0 for s in STOPS}
        pick = max(STOPS, key=lambda s: (before[s], -s))
        tt = allt[pick]
        f = tt[(tt.day >= a) & (tt.day <= b)] if len(tt) else tt
        wf_net += float(f.net.sum()) if len(f) else 0.0
        wf_trades += int(len(f))
        picks.append(dict(fold=f"{a}..{b}", stop=pick, net=float(f.net.sum()) if len(f) else 0.0, trades=int(len(f))))
    frozen = max(STOPS, key=lambda s: (res[f"design_{int(s * 100)}"]["net"], -s))
    res.update(wf_net=wf_net, wf_trades=wf_trades, wf_folds=picks, frozen_stop=frozen)
    return res, allt[frozen]


def design() -> None:
    sel = json.loads(SEL_FILE.read_text())
    rules = sel["rules"]
    out = []
    for r in rules:
        mk = Market(r["und"])
        sig = signals(r, cfg.RULE_START, cfg.DESIGN_END)
        res, tr = walk_forward(r, mk, sig)
        days = mk.days(cfg.RULE_START, cfg.DESIGN_END)
        tw = twins(r, mk, tr, days, res["frozen_stop"])
        net = res[f"design_{int(res['frozen_stop'] * 100)}"]["net"]
        res.update(rule=r, signals=int(len(sig)), twin_mean=float(tw.mean()),
                   twin_p=float((1 + (tw >= net).sum()) / (len(tw) + 1)))
        tr.to_csv(cfg.RESULTS / f"rules_design_trades_{r['id']}.csv", index=False, float_format="%.4f")
        out.append(res)
        print(r["id"], res["wf_net"], net, res["twin_p"], flush=True)
    q = ST.bh([o["twin_p"] for o in out])
    for o, qq in zip(out, q):
        d = o[f"design_{int(o['frozen_stop'] * 100)}"]
        o["bh_q"] = float(qq)
        why = []
        if not o["wf_net"] > 0:
            why.append("walk-forward net <= 0")
        if not (o["twin_p"] < GATE_P and qq < GATE_P):
            why.append("not better than random entries (twin p or BH q >= 0.10)")
        if not d["top5_removed"] > 0:
            why.append("carried by its 5 best trades")
        if d["trades"] < MIN_TRADES:
            why.append(f"fewer than {MIN_TRADES} trades")
        o["design_pass"] = not why
        o["fail_reasons"] = why
    (cfg.RESULTS / "rules_design.json").write_text(json.dumps(out, indent=1, default=str))


def select_main() -> None:
    p1 = pd.read_csv(cfg.RESULTS / "atlas_design_P1.csv.gz")
    p2 = pd.read_csv(cfg.RESULTS / "atlas_design_P2.csv.gz")
    cost = {u: cost_points(u, cfg.RULE_START, cfg.DESIGN_END) for u in cfg.UNDS}
    picked = select(p1, p2, cost)
    SEL_FILE.write_text(json.dumps(dict(cost=cost, rules=picked), indent=1))
    print(json.dumps(dict(cost=cost, rules=picked), indent=1))


if __name__ == "__main__":
    {"select": select_main, "design": design}[sys.argv[1]]()
