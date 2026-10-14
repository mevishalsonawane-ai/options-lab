"""R45 study: reproduce the claimed NIFTY premium scalp, then apply the pre-registered fixes P1-P7.

    python3 research/hunt/r45/r45study.py repro      # step 0 on design sessions: which series / band reproduces the claim
    python3 research/hunt/r45/r45study.py design     # every variant on design sessions, random twins, BH
    python3 research/hunt/r45/r45study.py holdout    # the ONE locked-holdout read (refuses if results/holdout.json exists)

SIGNAL SOURCE: the OPTION'S OWN PRICE CHART (not the default kind of rule; see PREREG.md). Data: the Drive lake copied
to $IRAALGO_LAKE (r45data). Results go to research/hunt/r45/results/*.json (no raw data).
"""
from __future__ import annotations

import json
import os
import sys
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from datetime import date, datetime, timezone

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..")))

import r45cfg as K  # noqa: E402
import r45data as D  # noqa: E402
import r45sim as S  # noqa: E402
from research.obuy.config import LOT_SCHEDULE  # noqa: E402
from research.obuy.costs import Costs  # noqa: E402

COSTS = Costs("dated")
EPOCH = date(1970, 1, 1)


# ------------------------------------------------------------------------------------------------ helpers
def lot_on(und: str, d: date) -> int:
    lot = None
    for since, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            lot = n
    return lot


def in_part(d: date, part: str) -> bool:
    if part == "design":
        return d <= K.DESIGN_END
    if part == "holdout":
        return d >= K.HOLDOUT_START
    if part == "all":                    # design + holdout (never the embargo); only after the holdout read
        return d <= K.DESIGN_END or d >= K.HOLDOUT_START
    raise ValueError(part)


def net_of(entry: float, exit_: float, lot: int, d: date, cost: str, slip: float) -> float:
    buy, sell = entry * (1 + slip), exit_ * (1 - slip)
    if cost == "flat95":
        return (sell - buy) * lot - 95.0
    dn = np.array([(d - EPOCH).days])
    ch = COSTS.charge(True, np.array([buy]), np.array([lot]), dn)[0] + COSTS.charge(False, np.array([sell]), np.array([lot]), dn)[0]
    return float((sell - buy) * lot - ch)


def their_expiry(c) -> bool:
    """Their expiry filter: any of the session's last 5 five-minute closes < 1.0."""
    c = np.asarray(c, dtype=np.float64)
    return bool(len(c) and (c[-5:] < 1.0).any())


class Prep:
    """One session under one variant: signal grid, band and memoised trade bars."""

    def __init__(self, s: D.Session, v: dict, lower=None):
        self.s, self.v = s, v
        if v["signal"] == "k915":
            self.k = s.strike_at(D.OPEN_MIN)
            t, o, h, l, c = s.contract5(self.k) if self.k is not None else s.contract5(None)
            self.t, self.c = [int(x) for x in t], c
            self._own = [list(map(float, x)) for x in (o, h, l, c)]
        else:
            self.t, self.c = [int(x) for x in s.t], s.c
            self._own = [list(map(float, x)) for x in (s.o, s.h, s.l, s.c)]
        self.lower = S.bollinger_lower(self.c, ddof=v["ddof"]) if lower is None else lower
        self._memo = {}

    def bars(self, e: int):
        if self.v["signal"] == "k915" or self.v["price"] == "roll":
            return self._own
        k = self.s.strike_at(self.t[e])
        if k not in self._memo:
            t, o, h, l, c = self.s.contract5(k)
            self._memo[k] = D.on_grid(self.t, t, o, h, l, c)
        return self._memo[k]

    def strike_of(self, e: int):
        return self.k if self.v["signal"] == "k915" else self.s.strike_at(self.t[e])


def cont_lowers(sessions, v):
    """Bollinger band carried across sessions (a reproduction aid): one long series of the sessions' closes."""
    preps = [Prep(s, v, lower=np.array([])) for s in sessions]
    allc = np.concatenate([np.asarray(p.c, dtype=np.float64) for p in preps]) if preps else np.array([])
    lo = S.bollinger_lower(allc, ddof=v["ddof"])
    out, k = [], 0
    for p in preps:
        n = len(p.c)
        p.lower = lo[k:k + n]
        k += n
        out.append(p)
    return out


def run_variant(v: dict, part: str, twins: int = 0, seed: int = K.SEED):
    """Trades of one variant on one part, plus `twins` random-entry twins (mean net/trade of each)."""
    r = S.Rules(**v["rules"])
    trades = []
    twin_rows = []                               # per session: (Prep, n trades) for the twins
    for series in v["series"]:
        ss = [s for s in D.load(v["und"], series) if in_part(s.day, part)]
        exp = D.expiry_days(v["und"], series)
        preps = cont_lowers(ss, v) if v["boll"] == "cont" else [Prep(s, v) for s in ss]
        for p in preps:
            d = p.s.day
            if len(p.t) < 25:
                continue
            is_exp = d in exp
            if v["expiry"] == "theirs" and their_expiry(p.c):
                continue
            if v["expiry"] == "real" and is_exp:
                continue
            tr = S.simulate_session(p.t, p.c, p.lower, p.bars, r)
            lot = 75 if v["lot"] == "75" else lot_on(v["und"], d)
            for x in tr:
                chg = p.s.strikes_between(p.t[x.e], p.t[x.x] + 4) > 1
                k_in = p.strike_of(x.e)
                trades.append(dict(day=d.isoformat(), series=series, sig=p.t[x.sig], e=p.t[x.e], x=p.t[x.x], entry=round(x.entry, 2),
                                   exit=round(x.exit, 2), why=x.why, lot=lot, expiry=is_exp, roll_change=bool(chg),
                                   strike=k_in, net=round(net_of(x.entry, x.exit, lot, d, v["cost"], v["slip"]), 2)))
            if tr and twins:
                twin_rows.append((p, len(tr), lot))
    twin_npt = []
    if twins:
        rng = np.random.default_rng(seed)
        for _ in range(twins):
            tot, n = 0.0, 0
            for p, k, lot in twin_rows:
                for x in S.twin_session(p.t, p.bars, r, k, rng):
                    tot += net_of(x.entry, x.exit, lot, p.s.day, v["cost"], v["slip"])
                    n += 1
            twin_npt.append(tot / n if n else 0.0)
    return trades, twin_npt


def metrics(trades: list[dict]) -> dict:
    if not trades:
        return dict(trades=0)
    tr = sorted(trades, key=lambda x: (x["day"], x["e"]))
    net = np.array([x["net"] for x in tr])
    eq = np.cumsum(net)
    dd = float((np.maximum.accumulate(np.r_[0.0, eq]) - np.r_[0.0, eq]).max())
    month = defaultdict(float)
    year = defaultdict(list)
    for x in tr:
        month[x["day"][:7]] += x["net"]
        year[x["day"][:4]].append(x["net"])
    gw, gl = net[net > 0].sum(), -net[net < 0].sum()
    days = len({x["day"] for x in tr})
    return dict(trades=len(tr), days=days, per_day=round(len(tr) / days, 2), win=round(100 * float((net > 0).mean()), 1),
                npt=round(float(net.mean()), 1), net=round(float(net.sum())), pf=round(float(gw / gl), 2) if gl > 0 else None,
                green=f"{sum(1 for m in month.values() if m > 0)}/{len(month)}", max_dd=round(dd),
                worst_month=round(min(month.values())), worst_month_at=min(month, key=month.get),
                years={y: dict(n=len(a), npt=round(float(np.mean(a)), 1), net=round(float(np.sum(a)))) for y, a in sorted(year.items())},
                why={w: sum(1 for x in tr if x["why"] == w) for w in ("stop", "lock", "time", "sq", "eod")},
                roll_change_pct=round(100 * float(np.mean([x["roll_change"] for x in tr])), 1),
                roll_change_npt=round(float(np.mean([x["net"] for x in tr if x["roll_change"]])), 1) if any(x["roll_change"] for x in tr) else None,
                no_change_npt=round(float(np.mean([x["net"] for x in tr if not x["roll_change"]])), 1) if not all(x["roll_change"] for x in tr) else None,
                expiry_trades=sum(1 for x in tr if x["expiry"]),
                expiry_npt=round(float(np.mean([x["net"] for x in tr if x["expiry"]])), 1) if any(x["expiry"] for x in tr) else None)


def p_value(npt: float, twins: list[float]) -> float:
    return round((1 + sum(1 for t in twins if t >= npt)) / (1 + len(twins)), 4)


def bh(ps: dict) -> dict:
    """Benjamini-Hochberg q-values."""
    items = sorted(ps.items(), key=lambda kv: kv[1])
    m, q, prev = len(items), {}, 1.0
    for rank in range(m, 0, -1):
        k, p = items[rank - 1]
        prev = min(prev, p * m / rank)
        q[k] = round(prev, 4)
    return q


def _save(name, obj):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def _n_sessions(und="NIFTY", series="WEEK"):
    return len(D.load(und, series))


# ------------------------------------------------------------------------------------------------ steps
def step_repro():
    """Step 0, design sessions only: which series / band reproduces the claim (per-year net/trade 2021-2025 and the
    trade rate; the claimed 3,726 trades are scaled by the share of the lake's sessions that are design sessions)."""
    out = {}
    allw = D.load("NIFTY", "WEEK")
    share = sum(1 for s in allw if s.day <= K.DESIGN_END) / len(allw)
    want_trades = K.CLAIM["trades"] * share
    for name, over in K.REPRO.items():
        v = K.variant(K.T0, over)
        tr, _ = run_variant(v, "design")
        m = metrics(tr)
        err = [m["years"].get(str(y), {}).get("npt", 0) - K.CLAIM["years"][y] for y in range(2021, 2026)]
        m["year_rms_err"] = round(float(np.sqrt(np.mean(np.square(err)))), 1)
        m["trade_ratio"] = round(m["trades"] / want_trades, 3)
        m["score"] = round(abs(m["trade_ratio"] - 1) + m["year_rms_err"] / K.CLAIM["npt"], 3)
        out[name] = dict(v=v, m=m)
        print(name, m["trades"], m["per_day"], m["npt"], m["win"], m["pf"], m["year_rms_err"], m["trade_ratio"], m["score"])
    best = min(out, key=lambda k: out[k]["m"]["score"])
    res = dict(when=datetime.now(timezone.utc).isoformat(), design_share=round(share, 3), claimed_trades_scaled=round(want_trades),
               best=best, base=out[best]["v"], runs=out)
    _save("repro.json", res)
    print("best:", best)


def _one(args):
    name, v, part, twins = args
    tr, tw = run_variant(v, part, twins)
    m = metrics(tr)
    if twins:
        m["twin_mean_npt"] = round(float(np.mean(tw)), 1)
        m["twin_sd_npt"] = round(float(np.std(tw)), 1)
        m["p"] = p_value(m.get("npt", -1e9), tw)
    return name, m, tr


def _all(part: str, twins: int):
    base = json.loads((K.RESULTS / "repro.json").read_text())["base"]
    jobs = [(n, K.variant(base, o), part, twins) for n, o in K.VARIANTS.items()]
    out, trades = {}, {}
    with ProcessPoolExecutor(int(os.environ.get("R45_PROCS", "4"))) as ex:
        for name, m, tr in ex.map(_one, jobs):
            out[name] = dict(v=dict(jobs[[j[0] for j in jobs].index(name)][1]), m=m)
            trades[name] = tr
            print(name, m.get("trades"), m.get("npt"), m.get("p"), flush=True)
    return out, trades


def gates(design: dict, holdout: dict | None) -> dict:
    g = {}
    for c in K.CANDIDATES:
        m = design[c]["m"]
        yrs = [m["years"].get(str(y), {}).get("npt", -1) for y in range(2022, 2027)]
        g[c] = dict(G1_design_npt_pos=m.get("npt", -1) > 0, G2_years_pos=f"{sum(1 for y in yrs if y > 0)}/5",
                    G2=sum(1 for y in yrs if y > 0) >= 4, G3_q=design[c].get("q"), G3=(design[c].get("q") or 1) < 0.05,
                    G5_slip1_pos=design[f"{c}-slip1"]["m"].get("npt", -1) > 0)
        if holdout is not None:
            g[c]["G4_holdout_npt"] = holdout[c]["m"].get("npt")
            g[c]["G4"] = (holdout[c]["m"].get("npt") or -1) > 0
        g[c]["survives"] = all(g[c][k] for k in ("G1_design_npt_pos", "G2", "G3", "G5_slip1_pos")) and \
            (g[c].get("G4", False) if holdout is not None else None)
    return g


def step_design():
    out, trades = _all("design", K.N_TWINS)
    q = bh({n: x["m"]["p"] for n, x in out.items() if "p" in x["m"]})
    for n in out:
        out[n]["q"] = q.get(n)
    res = dict(when=datetime.now(timezone.utc).isoformat(), part="design", n_twins=K.N_TWINS, bh_family=len(q),
               variants=out, gates=gates(out, None), lookahead=lookahead_check())
    _save("design.json", res)
    _save("design_trades_summary.json", {n: _trade_tables(t) for n, t in trades.items()})


def _trade_tables(tr):
    """Small aggregate tables only (no trade list: keeps raw-ish data out of git)."""
    by = defaultdict(lambda: [0, 0.0])
    for x in tr:
        k = f"{x['why']}"
        by[k][0] += 1
        by[k][1] += x["net"]
    hours = defaultdict(lambda: [0, 0.0])
    for x in tr:
        h = f"{x['e'] // 60:02d}h"
        hours[h][0] += 1
        hours[h][1] += x["net"]
    ser = defaultdict(lambda: [0, 0.0])
    for x in tr:
        ser[x["series"]][0] += 1
        ser[x["series"]][1] += x["net"]
    f = lambda d: {k: dict(n=a[0], npt=round(a[1] / a[0], 1)) for k, a in sorted(d.items())}
    per_day = defaultdict(int)
    for x in tr:
        per_day[x["day"]] += 1
    return dict(by_exit=f(by), by_entry_hour=f(hours), by_series=f(ser),
                trades_per_day_hist={str(k): sum(1 for v in per_day.values() if v == k) for k in sorted(set(per_day.values()))})


def step_holdout():
    done = K.RESULTS / "holdout.json"
    if done.exists():
        raise SystemExit("refused: the R45 holdout was already read (results/holdout.json exists)")
    if not (K.RESULTS / "design.json").exists():
        raise SystemExit("refused: run the design step first")
    design = json.loads((K.RESULTS / "design.json").read_text())
    _save("holdout_started.json", dict(at=datetime.now(timezone.utc).isoformat()))
    out, trades = _all("holdout", K.N_TWINS)
    full, _ = _all_full()
    res = dict(when=datetime.now(timezone.utc).isoformat(), part="holdout", variants=out, gates=gates(design["variants"], out),
               full_span=full)
    _save("holdout.json", res)
    _save("holdout_trades_summary.json", {n: _trade_tables(t) for n, t in trades.items()})


def _all_full():
    """Design + holdout together (embargo excluded), no twins: the claim's full-span comparison, after the read."""
    return _all("all", 0)


def lookahead_check(n_sessions: int = 200) -> dict:
    """P7: signals and entries computed on every prefix of a session equal those of the full session."""
    ss = [s for s in D.load("NIFTY", "WEEK") if s.day <= K.DESIGN_END]
    rng = np.random.default_rng(7)
    pick = rng.choice(len(ss), size=min(n_sessions, len(ss)), replace=False)
    r = S.Rules()
    bad = checked = 0
    for k in pick:
        p = Prep(ss[int(k)], K.T0)
        full_sig = S.cross_below(p.c, p.lower)
        full_tr = S.simulate_session(p.t, p.c, p.lower, p.bars, r)
        for cut in range(2, len(p.t) + 1):
            c = np.asarray(p.c[:cut])
            lo = S.bollinger_lower(c)
            if not np.array_equal(S.cross_below(c, lo), full_sig[:cut]):
                bad += 1
            own = [x[:cut] for x in p._own]
            tr = S.simulate_session(p.t[:cut], c, lo, lambda e: own, r)
            ent_full = [(x.sig, x.e, x.entry) for x in full_tr if x.x < cut - 1]
            ent_cut = [(x.sig, x.e, x.entry) for x in tr if x.x < cut - 1]
            if ent_full != ent_cut:
                bad += 1
            checked += 1
    return dict(sessions=len(pick), prefixes=checked, mismatches=bad, ok=bad == 0)


if __name__ == "__main__":
    step = sys.argv[1] if len(sys.argv) > 1 else "design"
    dict(repro=step_repro, design=step_design, holdout=step_holdout)[step]()
