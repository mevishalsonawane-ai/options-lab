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
from . import model as MO
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
    """An option buyer's round trip in INDEX points (PREREG 8.1 with A1's modelled premium): the median MODELLED 1-ITM
    call premium P at 10:30 over the days, cost per unit = 2 x 5 bps x P + 2 ticks + charges(P in, P out) / lot;
    points = cost / delta 0.6."""
    mk = Market(und, "model")
    ps = []
    for d in mk.days(frm, to):
        c, atm, con, _ = mk.day(d)
        if not np.isfinite(c[COST_ROW]):
            continue
        k = S.strike_1itm(und, np.nan, c[COST_ROW], True)
        A = con.get(("C", k))
        if A is not None and np.isfinite(A[3, COST_ROW]):
            ps.append(A[3, COST_ROW])
    P = float(np.median(ps))
    qty = cfg.lot(und, to)
    unit = 2 * S.BPS_MKT * P + 2 * S.TICK + S.charges(und, P, P, qty, to) / qty
    return dict(und=und, premium=P, days=int(len(ps)), lot=qty, unit_cost=unit, points=unit / COST_DELTA,
                pricing="modelled premium (A1)")


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
            picked.append(dict(id=f"R87-{len(picked) + 1}", und=r.und, tf=int(r.tf), family=r.family,
                               cond=r.cond if isinstance(r.cond, str) else "",
                               src=r.src, long=bool(r.eff > 0), eff=float(r.eff), z=float(r.z), q=float(r.q),
                               n=int(r.n), fold_same=int(r.fold_same), rw_n=int(r.rw_n),
                               dir_pts=float(r.dir_pts), cost_pts=float(r.cost_pts), screen=label))
    return picked


# ------------------------------------------------------------------------------------------------ market of one instrument
class _ModelCon:
    """dict-like: (side, strike) -> modelled (5, 375) contract, built on first use (model.py)."""

    def __init__(self, d, ohlc, vix, scale, exp):
        self.d, self.ohlc, self.vix, self.scale, self.exp, self.c = d, ohlc, vix, scale, exp, {}

    def get(self, key):
        if self.exp is None:
            return None
        if key not in self.c:
            self.c[key] = MO.contract(key[0], key[1], self.d, self.ohlc, self.vix, self.scale, self.exp)
        return self.c[key]


class _HybridCon:
    """R87B: the REAL option minutes (ATM-1..+1) where the held contract printed; minutes it did not print (it moved
    out of ATM-1..+1, or no trade) are filled with the modelled premium x (real / model) at the last minute where
    both existed (point in time; 1 before the first real print of the day). Filled minutes carry no volume, so a
    fill there pays 4 ticks. A contract with no real print that day is the model alone."""

    def __init__(self, real: dict, model: "_ModelCon"):
        self.real, self.model, self.c = real, model, {}

    def is_real(self, side, k, row):
        A = self.real.get((side, float(k)))
        return A is not None and 0 <= row < cfg.NROWS and np.isfinite(A[0, row])

    def get(self, key):
        if key in self.c:
            return self.c[key]
        B = self.model.get(key)
        A = self.real.get((key[0], float(key[1])))
        if B is None:
            out = A
        elif A is None:
            out = B
        else:
            ok = np.isfinite(A[3]) & np.isfinite(B[3]) & (B[3] > 0)
            ratio = pd.Series(np.where(ok, A[3] / np.where(ok, B[3], 1.0), np.nan)).ffill().fillna(1.0).to_numpy()
            out = A.copy()
            miss = ~np.isfinite(A[0])
            for j in range(4):
                out[j, miss] = B[j, miss] * ratio[miss]
            out[4, miss] = 0.0
        self.c[key] = out
        return out


class Market:
    """Index minutes and option contracts of one instrument by day (lazy, cached). mode 'model' (PREREG A1, the
    rules' pricing): modelled premiums for every day; 'real': the Dhan option minutes (ATM-1..+1) where they exist;
    'hybrid' (R87B): real where printed, the calibrated model elsewhere (_HybridCon)."""

    def __init__(self, und: str, mode: str = "model"):
        self.und, self.mode = und, mode
        m = D.index_minutes(und)
        self.r = (m.ts.dt.hour * 60 + m.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
        self.m = m.assign(d=m.ts.dt.date, r=self.r)
        self.mgrp = self.m.groupby("d").indices
        self.exps = MO.expiry_dates(und, cfg.START, cfg.END)
        self.exp = set(self.exps)
        if mode in ("real", "hybrid"):
            o = D.option_minutes(und)
            self.o = o.assign(d=o.ts.dt.date)
            self.ogrp = self.o.groupby("d").indices
        if mode in ("model", "hybrid"):
            v = D.index_minutes("INDIAVIX")
            self.vix = v.assign(d=v.ts.dt.date, r=(v.ts.dt.hour * 60 + v.ts.dt.minute - cfg.OPEN_MIN))
            self.vgrp = self.vix.groupby("d").indices
            self.daily = {u: D.index_minutes(u).assign(d=lambda x: x.ts.dt.date).groupby("d").c.last()
                          for u in {"NIFTY", und}}
            if mode == "hybrid":                  # every day the model can price; real prints where they exist
                self.rgrp = self.ogrp
            self.ogrp = {d: None for d in self.mgrp if d in self.vgrp}
        self._c = {}

    def days(self, frm: date, to: date) -> list[date]:
        return sorted(d for d in self.ogrp if frm <= d <= to and d in self.mgrp)

    def day(self, d: date):
        if d not in self._c:
            if len(self._c) > 400:
                self._c.clear()
            mi = self.m.iloc[self.mgrp[d]]
            ohlc = np.full((4, cfg.NROWS), np.nan)
            for j, col in enumerate("ohlc"):
                ohlc[j, mi.r.to_numpy()] = mi[col].to_numpy()
            c = ohlc[3]
            atm = np.full(cfg.NROWS, np.nan)
            if self.mode in ("real", "hybrid"):
                grp = self.rgrp if self.mode == "hybrid" else self.ogrp
                oi = self.o.iloc[grp[d]] if d in grp else self.o.iloc[:0]
                a = oi[(oi.offset == 0)].drop_duplicates("ts")
                ar = (a.ts.dt.hour * 60 + a.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
                atm[ar] = a.strike.to_numpy()
                atm = pd.Series(atm).ffill().to_numpy()
                con = D.option_day(oi)
            if self.mode in ("model", "hybrid") and d in self.vgrp:
                vi = self.vix.iloc[self.vgrp[d]]
                vx = np.full(cfg.NROWS, np.nan)
                ok = (vi.r >= 0) & (vi.r < cfg.NROWS)
                vx[vi.r[ok].to_numpy()] = vi.c[ok].to_numpy()
                vx = pd.Series(vx).ffill().bfill().to_numpy()      # bfill only fills 09:15 before VIX's first print
                mc = _ModelCon(d, ohlc, vx, MO.iv_scale(self.daily, self.und, d), MO.next_expiry(self.exps, d))
                con = _HybridCon(con, mc) if self.mode == "hybrid" else mc
            self._c[d] = (c, atm, con, d in self.exp)
        return self._c[d]


def family_mask(k: pd.DataFrame, fam: str) -> np.ndarray:
    """A family's flags on a candle table: a column, or an R87B sequence ('seq:GBN-RSL-DN') / cluster
    ('cluster5:07', centres from results/b/clusters_design.json)."""
    if fam.startswith("seq:"):
        from .batlas import seq3
        return (seq3(k) == fam[4:]).to_numpy()
    if fam.startswith("cluster"):
        from .batlas import RES, assign, seq_features
        tf, i = fam[len("cluster"):].split(":")
        C = np.array(json.loads((RES / "clusters_design.json").read_text())[tf])
        return assign(seq_features(k), C) == int(i)
    return k[fam].to_numpy(bool)


def hold_of(rule: dict) -> int:
    return int(rule.get("hold", 5 * rule["tf"]))


def signals(rule: dict, frm: date, to: date) -> pd.DataFrame:
    """Signal candles of a rule in [frm, to]: day, sig_row (the candle's last minute), long, und_stop."""
    k = B.load(rule["und"], rule["tf"], frm, to)
    m = family_mask(k, rule["family"])
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
    hold = hold_of(rule)
    have = set(mk.ogrp) & set(mk.mgrp)
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
    hold = hold_of(rule)
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


def model_check(und: str, frm: date, to: date) -> dict:
    """How close is the modelled premium to the real one? ATM call and put at 10:30 on days with real ATM minutes
    (both sides printed): median model / real ratio and median absolute % error (PREREG A1)."""
    mr, mm = Market(und, "real"), Market(und, "model")
    rows = []
    r0 = COST_ROW
    for d in mr.days(frm, to):
        if d not in mm.ogrp:
            continue
        c, atm, con, _ = mr.day(d)
        cm, _, conm, _ = mm.day(d)
        k = atm[r0]
        if not np.isfinite(k):
            continue
        for side in ("C", "P"):
            A = con.get((side, float(k)))
            B_ = conm.get((side, float(k)))
            if A is None or B_ is None or not np.isfinite(A[3, r0]) or not np.isfinite(B_[3, r0]) or A[3, r0] <= 0:
                continue
            rows.append(B_[3, r0] / A[3, r0])
    r = np.array(rows)
    return dict(und=und, n=int(len(r)), median_ratio=float(np.median(r)) if len(r) else None,
                median_abs_err_pct=float(np.median(np.abs(r - 1)) * 100) if len(r) else None)


def reprice_real(rule: dict, tr: pd.DataFrame, stop: float) -> dict:
    """The same trades re-priced on the real option minutes where the 1-ITM contract printed (A1 check)."""
    if tr.empty:
        return dict(n=0)
    mr = Market(rule["und"], "real")
    hold = hold_of(rule)
    out = []
    for t in tr.itertuples(index=False):
        if t.day not in mr.ogrp:
            continue
        c, atm, con, ex = mr.day(t.day)
        A = con.get((t.side, float(t.strike)))
        if A is None:
            continue
        sr = int(t.er) - 1
        q = S.price_trade(rule["und"], t.day, {(t.side, float(t.strike)): A}, c, np.full(cfg.NROWS, t.strike +
                          (cfg.STEP[rule["und"]] if t.side == "C" else -cfg.STEP[rule["und"]])), sr, t.side == "C",
                          float(t.und_stop), hold, stop, ex)
        if q is not None:
            out.append((t.net, q["net"]))
    a = np.array(out)
    return dict(n=int(len(a)), model_net=float(a[:, 0].sum()) if len(a) else 0.0,
                real_net=float(a[:, 1].sum()) if len(a) else 0.0)


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
                   twin_p=float((1 + (tw >= net).sum()) / (len(tw) + 1)),
                   real_check=reprice_real(r, tr, res["frozen_stop"]), pricing="modelled premium (PREREG A1)")
        tr.to_csv(cfg.RESULTS / f"rules_design_trades_{r['id']}.csv", index=False, float_format="%.4f")
        out.append(res)
        print(r["id"], res["wf_net"], net, res["twin_p"], flush=True)
    checks = [model_check(u, cfg.RULE_START, cfg.DESIGN_END) for u in cfg.UNDS]
    (cfg.RESULTS / "model_check_design.json").write_text(json.dumps(checks, indent=1))
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
