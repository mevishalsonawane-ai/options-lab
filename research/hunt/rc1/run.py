"""RC1 runner.

    IRAALGO_CACHE=<cache> python3 research/hunt/rc1/run.py design             # design + walk-forward, twins, BH -> results/design.*
    IRAALGO_CACHE=<cache> RC1_READ_HOLDOUT=1 python3 research/hunt/rc1/run.py holdout   # ONCE -> results/holdout.*

PREREG.md is the contract; config.py holds its numbers.
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import date, timedelta

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import config as K  # noqa: E402
import data as D  # noqa: E402
import engine as E  # noqa: E402
import strategies as S  # noqa: E402

RES = os.path.join(HERE, "results")
COINSETS = {"50/50": ("BTC", "ETH"), "BTC": ("BTC",), "ETH": ("ETH",)}


# ------------------------------------------------------------------------------------------------ markets and signals


class World:
    def __init__(self, split: str):
        raw = D.load(split)
        fx = raw["usdinr"]
        self.spot = E.build_market(raw["spot_1d"], fx)
        self.perp = E.build_market(raw["um_1d"], fx, raw["funding"])
        self.spot4 = E.build_market(raw["spot_1h"], fx, rule="4h")
        self.perp4 = E.build_market(raw["um_1h"], fx, raw["funding"], rule="4h")
        self.fund_end = min(f.t.max() for f in raw["funding"].values()).date()
        m = self.spot
        self.on = {r: {c: S.trend_on(r, m.c[c], m.h[c], m.l[c]) for c in m.c} for r in K.TREND_RULES}
        self.w_raw = {c: S.shift_weights(S.vol_weights(np.ones(m.n), m.c[c])) for c in m.c}
        # perp-day -> spot index (both daily, contiguous)
        sidx = {d: i for i, d in enumerate(m.d)}
        self.p2s = np.array([sidx[d] for d in self.perp.d])
        self.carry_on = {c: S.carry_state(self.perp.fund[c]) for c in self.perp.c}
        self.brk = {"spot": {c: S.breakout_spells(self.spot4.o[c], self.spot4.h[c], self.spot4.l[c], self.spot4.c[c])
                             for c in m.c},
                    "perp": {c: S.breakout_spells(self.perp4.o[c], self.perp4.h[c], self.perp4.l[c], self.perp4.c[c])
                             for c in m.c}}

    def mk(self, key):
        return {"spot": self.spot, "perp": self.perp, "spot4": self.spot4, "perp4": self.perp4}[key]

    def ab(self, key, start: date, end: date):
        m = self.mk(key)
        return m.index_of(start), m.index_of(end + timedelta(days=1))


def wf_rules(W: World, end: date, first_oos: date) -> list:
    """[(oos start, oos end, rule)] anchored yearly; the last item is the rule for everything after `end`."""
    out = []
    y0 = date(first_oos.year, first_oos.month, first_oos.day)
    starts = []
    s = y0
    while s <= end:
        starts.append(s)
        s = date(s.year + 1, s.month, s.day)
    for s in starts + [end + timedelta(days=1)]:
        best, best_v = None, -1e18
        for r in sorted(K.TREND_RULES, key=lambda r: K.SLOWNESS[r]):
            a, b = W.ab("spot", K.SPOT_FIRST_TRADE, s - timedelta(days=1))
            mask = {c: S.to_mask(W.on[r][c]) for c in ("BTC", "ETH")}
            v = E.simulate(W.spot, a, b, capital=K.PRIMARY_ACCOUNT, spells={c: E.mask_to_spells(mask[c]) for c in mask}).final
            if v > best_v + 1e-6:
                best, best_v = r, v
        out.append((s, best))
    return out


def wf_mask(W: World, rules: list) -> dict:
    """Stitched position mask: in each OOS year, the rule chosen before it (before the first OOS year: the first pick)."""
    n = W.spot.n
    mask = {c: np.zeros(n, dtype=int) for c in ("BTC", "ETH")}
    bounds = [W.spot.index_of(s) for s, _ in rules] + [n]
    for k, (s, r) in enumerate(rules):
        lo = 0 if k == 0 else bounds[k]
        for c in mask:
            mm = S.to_mask(W.on[r][c])
            mask[c][lo:bounds[k + 1]] = mm[lo:bounds[k + 1]]
    return mask


# ------------------------------------------------------------------------------------------------ plans


def month_firsts(m: E.Market, a: int, b: int) -> list:
    out, last = [], None
    for i in range(a, b):
        k = (m.d[i].year, m.d[i].month)
        if k != last:
            out.append(i)
            last = k
    return out


def plan(W: World, idea: str, var: dict, coins: tuple, a: int, b: int, cap: float, masks: dict | None = None) -> tuple:
    """-> (market key, simulate kwargs). masks: {'trend': mask per coin} for c / c-perp / d, or twin masks."""
    share = 1.0 / len(coins)
    if idea == "a1":
        m = W.spot
        buys = {c: np.zeros(m.n) for c in coins}
        for c in coins:
            buys[c][a] = cap * share
        return "spot", dict(buys=buys)
    if idea == "a2":
        m = W.spot
        buys = {c: np.zeros(m.n) for c in coins}
        if var["v"] == "sip":
            contrib = np.zeros(m.n)
            for i in month_firsts(m, a, b):
                contrib[i] = cap / 12 if i > a else 0
                for c in coins:
                    buys[c][i] = cap / 12 * share
            return "spot", dict(buys=buys, contrib=contrib, capital=cap / 12)
        if var["v"] == "monthly":
            days = month_firsts(m, a, b)[:12]
            k = 12
        else:
            days = [i for i in range(a, b) if m.d[i].weekday() == 0][:52]
            k = 52
        for i in days:
            for c in coins:
                buys[c][i] = cap / k * share
        return "spot", dict(buys=buys)
    if idea == "b":
        m = W.spot
        reb = np.zeros(m.n, dtype=bool)
        if var["v"] == "monthly":
            for i in month_firsts(m, a, b)[1:]:
                reb[i] = True
            return "spot", dict(rebal=reb)
        if var["v"] == "twin":
            reb[var["days"]] = True
            return "spot", dict(rebal=reb)
        return "spot", dict(rebal=reb, rebal_band=0.10)
    if idea in ("c", "c-perp"):
        mask = masks
        sleeve = 1.0 / len(coins)
        if idea == "c":
            return "spot", dict(spells={c: E.mask_to_spells(mask[c]) for c in coins}, sleeve=sleeve)
        pm = {c: mask[c][W.p2s] for c in coins}
        return "perp", dict(venue="perp", spells={c: E.mask_to_spells(pm[c]) for c in coins}, sleeve=sleeve,
                            lev=var.get("lev", 1), reading=var.get("reading", "A"))
    if idea == "d":
        k = 1.0 / (0.5 * len(coins))         # single coin: weights double, band too
        w = {c: masks[c] * W.w_raw[c] * k for c in coins}
        return "spot", dict(weights=w, band=K.VOL_BAND * k)
    if idea == "f":
        sp = {c: E.mask_to_spells(masks[c]) for c in coins}
        if var.get("reset"):          # post-hoc, descriptive only: close and reopen both legs at each month's first open
            firsts = set(month_firsts(W.perp, 0, W.perp.n))
            sp = {c: _split(v, firsts) for c, v in sp.items()}
        return "perp", dict(venue="carry", carry=sp, sleeve=1.0 / len(coins),
                            lev=var.get("lev", 1), reading=var.get("reading", "A"))
    if idea in ("g", "g-perp"):
        sp = masks
        sleeve = 1.0 / len(coins)
        if idea == "g":
            return "spot4", dict(spells={c: sp[c] for c in coins}, sleeve=sleeve)
        return "perp4", dict(venue="perp", spells={c: sp[c] for c in coins}, sleeve=sleeve,
                             lev=var.get("lev", 1), reading=var.get("reading", "A"))
    raise ValueError(idea)


def _split(spells: list, cuts: set) -> list:
    out = []
    for a0, e0 in spells:
        s0 = a0
        for i in range(a0 + 1, e0):
            if i in cuts:
                out.append((s0, i))
                s0 = i
        out.append((s0, e0))
    return out


def run_one(W: World, idea, var, coinset, start, end, cap, masks=None, version="after", case="base", key_ab=None):
    coins = COINSETS[coinset]
    mkey = {"a1": "spot", "a2": "spot", "b": "spot", "c": "spot", "d": "spot", "c-perp": "perp", "f": "perp",
            "g": "spot4", "g-perp": "perp4"}[idea]
    a, b = W.ab(mkey, start, end)
    mkey, kw = plan(W, idea, var, coins, a, b, cap, masks)
    kw.setdefault("capital", cap)
    tax = version in ("after", "after_notds")
    tds = version == "after"
    if version == "gross":
        case = "zero"
    r = E.simulate(W.mk(mkey), a, b, coins=coins, case=case, tax=tax, tds=tds, **kw)
    return r


# ------------------------------------------------------------------------------------------------ idea masks


def masks_for(W: World, idea: str, rules: list, start: date, end: date) -> dict:
    if idea in ("c", "c-perp", "d"):
        return wf_mask(W, rules)
    if idea == "f":
        return {c: S.to_mask(W.carry_on[c]) for c in ("BTC", "ETH")}
    if idea == "g":
        return W.brk["spot"]
    if idea == "g-perp":
        return W.brk["perp"]
    return None


def twin_masks(W: World, idea: str, masks: dict, start: date, end: date, rng) -> dict:
    """Shuffle the strategy's in-market spells inside [start, end] per coin."""
    if idea in ("c", "d"):
        a, b = W.ab("spot", start, end)
        out = {}
        for c, mm in masks.items():
            x = mm.copy()
            x[a:b] = S.shuffle_mask(mm[a:b], rng)
            out[c] = x
        return out
    if idea in ("c-perp",):
        a, b = W.ab("perp", start, end)
        out = {}
        for c, mm in masks.items():
            pm = mm[W.p2s]
            x = pm.copy()
            x[a:b] = S.shuffle_mask(pm[a:b], rng)
            full = mm.copy()                      # write back on the spot grid so plan() can map it again
            full[W.p2s] = x
            out[c] = full
        return out
    if idea == "f":
        a, b = W.ab("perp", start, end)
        out = {}
        for c, mm in masks.items():
            x = mm.copy()
            x[a:b] = S.shuffle_mask(mm[a:b], rng)
            out[c] = x
        return out
    if idea in ("g", "g-perp"):
        key = "spot4" if idea == "g" else "perp4"
        a, b = W.ab(key, start, end)
        n = W.mk(key).n
        out = {}
        m = W.mk(key)
        for c, sp in masks.items():
            mm = S.spells_to_mask(sp, n)
            x = np.zeros(n, dtype=int)
            x[a:b] = S.shuffle_mask(mm[a:b], rng)
            starts = [s for s, _ in E.mask_to_spells(x)]
            out[c] = S.breakout_exits(starts, m.o[c], m.h[c], m.l[c], m.c[c])
        return out
    raise ValueError(idea)


# ------------------------------------------------------------------------------------------------ the study


ACTIVE = ["b-monthly", "b-band", "c", "c-perp", "d", "f", "g", "g-perp"]
VAR = {"b-monthly": ("b", {"v": "monthly"}), "b-band": ("b", {"v": "band"}), "c": ("c", {}),
       "c-perp": ("c-perp", {"lev": 1, "reading": "A"}), "d": ("d", {}), "f": ("f", {"lev": 1, "reading": "A"}),
       "g": ("g", {}), "g-perp": ("g-perp", {"lev": 1, "reading": "A"})}


def twin_p(W, name, masks, start, end, real_final, n=K.N_TWINS, seed=K.SEED) -> tuple:
    idea, var = VAR[name]
    rng = np.random.default_rng(seed)
    vals = []
    if idea == "b":
        a, b = W.ab("spot", start, end)
        real = run_one(W, idea, var, "50/50", start, end, K.PRIMARY_ACCOUNT)
        k = real.events
        for _ in range(n):
            days = rng.choice(np.arange(a + 1, b), size=k, replace=False)
            vals.append(run_one(W, "b", {"v": "twin", "days": days}, "50/50", start, end, K.PRIMARY_ACCOUNT).final)
    else:
        for _ in range(n):
            tm = twin_masks(W, idea, masks, start, end, rng)
            vals.append(run_one(W, idea, var, "50/50", start, end, K.PRIMARY_ACCOUNT, tm).final)
    vals = np.array(vals)
    p = (1 + (vals >= real_final).sum()) / (n + 1)
    return float(p), float(np.median(vals)), vals


def describe(r: E.Run) -> dict:
    x = E.metrics(r)
    return {k: (None if (isinstance(v, float) and not np.isfinite(v)) else (round(v, 6) if isinstance(v, float) else v))
            for k, v in x.items()}


def full_table(W, rules, windows: dict, accounts=K.ACCOUNTS) -> list:
    """Every idea x variant x coin set x account x version on each window; cost cases on 50/50."""
    rows = []
    masks_cache = {}
    ideas = [("a1", {}, ["BTC", "ETH", "50/50"]),
             ("a2", {"v": "monthly"}, ["BTC", "ETH", "50/50"]), ("a2", {"v": "weekly"}, ["BTC", "ETH", "50/50"]),
             ("a2", {"v": "sip"}, ["BTC", "ETH", "50/50"]),
             ("b", {"v": "monthly"}, ["50/50"]), ("b", {"v": "band"}, ["50/50"]),
             ("c", {}, ["BTC", "ETH", "50/50"]), ("d", {}, ["BTC", "ETH", "50/50"]),
             ("g", {}, ["BTC", "ETH", "50/50"])]
    for lev in (1, 3):
        for rd in ("A", "B"):
            ideas += [("c-perp", {"lev": lev, "reading": rd}, ["BTC", "ETH", "50/50"]),
                      ("f", {"lev": lev, "reading": rd}, ["BTC", "ETH", "50/50"]),
                      ("g-perp", {"lev": lev, "reading": rd}, ["BTC", "ETH", "50/50"]),
                      ("f", {"lev": lev, "reading": rd, "reset": True}, ["50/50"])]
    for wname, (ws, we, wperp) in windows.items():
        for idea, var, sets in ideas:
            perpish = idea in ("c-perp", "f", "g-perp")
            start = max(ws, K.PERP_START) if perpish else ws
            end = min(we, wperp) if perpish else we
            masks = masks_cache.setdefault(idea, masks_for(W, idea, rules, start, end))
            for cs in sets:
                for cap in accounts:
                    versions = ["gross", "pretax", "after", "after_notds"]
                    for ver in versions:
                        cases = ["base"] + (["low", "high"] if ver == "after" and cs == "50/50" else [])
                        for case in cases:
                            r = run_one(W, idea, var, cs, start, end, cap, masks, ver, case)
                            rows.append(dict(window=wname, idea=idea, var=json.dumps(var), coins=cs, account=cap,
                                             version=ver, case=case, start=str(start), end=str(end), **describe(r)))
    return rows


def oos_years(W, rules, first: date, last_end: date) -> list:
    rows = []
    s = first
    while s <= last_end:
        e = min(date(s.year + 1, s.month, s.day) - timedelta(days=1), last_end)
        for idea, var in [("a1", {}), ("b", {"v": "monthly"}), ("b", {"v": "band"}), ("c", {}), ("d", {}), ("g", {}),
                          ("c-perp", {"lev": 1, "reading": "A"}), ("f", {"lev": 1, "reading": "A"}),
                          ("g-perp", {"lev": 1, "reading": "A"})]:
            if idea in ("c-perp", "f", "g-perp") and e < K.PERP_START:
                continue
            st = max(s, K.PERP_START) if idea in ("c-perp", "f", "g-perp") else s
            masks = masks_for(W, idea, rules, st, e)
            r = run_one(W, idea, var, "50/50", st, e, K.PRIMARY_ACCOUNT, masks)
            rows.append(dict(year=f"{st}..{e}", idea=idea, var=json.dumps(var), **describe(r)))
        s = date(s.year + 1, s.month, s.day)
    return rows


def gates_design(W, rules, wf_start, wf_end, perp_start):
    """Gate 1 (twins + BH), gate 2 and gate 4 on the walk-forward span."""
    out = {}
    hold = {}
    for key, st in (("spot", wf_start), ("perp", perp_start)):
        for case in ("base", "high"):
            r = run_one(W, "a1", {}, "50/50", st, wf_end, K.PRIMARY_ACCOUNT, case=case)
            hold[(key, case)] = E.metrics(r)
    pvals = []
    for name in ACTIVE:
        idea, var = VAR[name]
        perpish = idea in ("c-perp", "f", "g-perp")
        st = perp_start if perpish else wf_start
        masks = masks_for(W, idea, rules, st, wf_end)
        real = {case: run_one(W, idea, var, "50/50", st, wf_end, K.PRIMARY_ACCOUNT, masks, case=case)
                for case in ("base", "high")}
        mb, mh = E.metrics(real["base"]), E.metrics(real["high"])
        t0 = time.time()
        p, med, _ = twin_p(W, name, masks, st, wf_end, real["base"].final)
        hb, hh = hold[("perp" if perpish else "spot", "base")], hold[("perp" if perpish else "spot", "high")]
        if idea == "f":
            g2 = mb["cagr"] > 0.07 and mb["mdd"] > -0.10
            g4 = mh["cagr"] > 0.07 and mh["mdd"] > -0.10
        else:
            g2 = mb["calmar"] > hb["calmar"] and mb["mdd"] > hb["mdd"]
            g4 = mh["calmar"] > hh["calmar"] and mh["mdd"] > hh["mdd"]
        out[name] = dict(start=str(st), end=str(wf_end), final=mb["final"], cagr=mb["cagr"], mdd=mb["mdd"],
                         calmar=mb["calmar"], hold_cagr=hb["cagr"], hold_mdd=hb["mdd"], hold_calmar=hb["calmar"],
                         twin_p=p, twin_median=med, gate2=bool(g2), gate4=bool(g4), entries=real["base"].entries,
                         twin_seconds=round(time.time() - t0, 1))
        pvals.append(p)
        print(name, out[name], flush=True)
    q = S.bh(pvals)
    for name, qq in zip(ACTIVE, q):
        out[name]["bh_q"] = float(qq)
        out[name]["gate1"] = bool(qq < 0.05)
    return out


def design():
    os.makedirs(RES, exist_ok=True)
    W = World("design")
    rules = wf_rules(W, K.WF_END, K.WF_FIRST_OOS)
    print("walk-forward rules", rules, flush=True)
    # idea e, design only
    dow = []
    a, b = W.ab("spot", date(2017, 8, 17), K.WF_END)
    for c in ("BTC", "ETH"):
        dow += S.dow_tests(W.spot.d[a:b], W.spot.c[c][a:b], c)
    q = S.bh([r["p"] for r in dow])
    for r, qq in zip(dow, q):
        r["bh_q"] = float(qq)
    e_pass = any(r["bh_q"] < 0.05 for r in dow)
    print("day-of-week: any pass?", e_pass, flush=True)
    windows = {"walk-forward": (K.WF_FIRST_OOS, K.WF_END, K.WF_END),
               "design-full": (K.SPOT_FIRST_TRADE, K.WF_END, K.WF_END)}
    rows = full_table(W, rules, windows)
    years = oos_years(W, rules, K.WF_FIRST_OOS, K.WF_END)
    gates = gates_design(W, rules, K.WF_FIRST_OOS, K.WF_END, K.PERP_START)
    res = dict(rules=[(str(s), r) for s, r in rules], dow=dow, dow_any_pass=e_pass, rows=rows, years=years, gates=gates,
               data=dict(spot_days=W.spot.n, perp_days=W.perp.n, bars4h=W.spot4.n, funding_end=str(W.fund_end),
                         spot_first=str(W.spot.d[0]), spot_last=str(W.spot.d[-1]), perp_first=str(W.perp.d[0])))
    with open(os.path.join(RES, "design.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print("wrote results/design.json", flush=True)


def holdout():
    os.makedirs(RES, exist_ok=True)
    marker = os.path.join(RES, "holdout_read.json")
    if os.path.exists(marker):
        raise SystemExit("the RC1 holdout has already been read once (results/holdout_read.json)")
    # Nothing is printed until the end: a crash before then shows no holdout number, so it is not a read.
    W = World("holdout")
    rules = wf_rules(W, K.WF_END, K.WF_FIRST_OOS)        # same picks: the last one is chosen on all design data
    hs, he = K.HOLDOUT_START, min(K.HOLDOUT_END, W.spot.d[-1])
    he_perp = min(he, W.fund_end)
    windows = {"holdout": (hs, he, he_perp)}
    rows = full_table(W, rules, windows)
    gates = {}
    for name in ACTIVE:
        idea, var = VAR[name]
        perpish = idea in ("c-perp", "f", "g-perp")
        end = he_perp if perpish else he
        masks = masks_for(W, idea, rules, hs, end)
        real = run_one(W, idea, var, "50/50", hs, end, K.PRIMARY_ACCOUNT, masks)
        p, med, _ = twin_p(W, name, masks, hs, end, real.final)
        hold = run_one(W, "a1", {}, "50/50", hs, end, K.PRIMARY_ACCOUNT)
        gates[name] = dict(final=real.final, twin_median=med, twin_p=p, gate3=bool(real.final > med),
                           hold_final=hold.final, entries=real.entries, end=str(end))
    res = dict(rules=[(str(s), r) for s, r in rules], rows=rows, gates=gates, holdout=[str(hs), str(he), str(he_perp)],
               data=dict(spot_last=str(W.spot.d[-1]), funding_end=str(W.fund_end)))
    with open(marker, "w") as f:
        json.dump(dict(read_at=pd.Timestamp.utcnow().isoformat(), note="RC1 holdout read once"), f)
    with open(os.path.join(RES, "holdout.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print("wrote results/holdout.json", flush=True)


if __name__ == "__main__":
    {"design": design, "holdout": holdout}[sys.argv[1]]()
