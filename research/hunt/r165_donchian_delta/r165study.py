"""R165 study (PREREG.md): delta on the Donchian arms. Option delta (filter, strike, exit, picker features) and an
order-flow delta PROXY (the bought option's tick-rule signed volume), 1-3 lots, against today's rules.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build                       # R51 clean stores (as R152 / R153)
    python research/hunt/r165_donchian_delta/r165study.py design                # design + walk-forward, B0 parity
    python research/hunt/r165_donchian_delta/r165study.py design_r151           # descriptive: R151's picker, same folds
    python research/hunt/r165_donchian_delta/r165study.py final                 # the ONE final read, twins, R151 picker
    python research/hunt/r165_donchian_delta/r165study.py decide                # BH + gates, results/pvalues.csv

Units, walk, B0 parity and pricing are R152's (r152study / r152engine); Greeks, rules and gates are r165lib.
12 Nov 2026.
"""
from __future__ import annotations

import csv
import dataclasses
import json
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r152_donchian_2k"))
sys.path.insert(0, str(HERE))
import r152cfg as K2  # noqa: E402
import r152study as R  # noqa: E402
import r165lib as L  # noqa: E402

RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
OUT = K2.OUT.parent / "r165"
ARMS = K2.ARMS
LOTS = K2.LOTS
FILTERS = ("DB1", "DB2", "DB3", "PKD", "OF1", "OF2")
STRIKES = tuple(L.TARGETS)
EXITS = ("XI1", "XI2", "OF3")
RULES = ("DB1", "DB2", "DB3", "SD50", "SD60", "SD70", "SD80", "XI1", "XI2", "PKD", "OF1", "OF2", "OF3")
LABEL = {
    "B0": "today's rules (1-ITM, -15% stop, ladder + L25, 30 min, 15:15)",
    "DB1": "take the break only when the 1-ITM option's delta is 0.45-0.55",
    "DB2": "take the break only when the 1-ITM option's delta is 0.55-0.65",
    "DB3": "take the break only when the 1-ITM option's delta is 0.65-0.80",
    "SD50": "buy the strike nearest delta 0.50", "SD60": "buy the strike nearest delta 0.60",
    "SD70": "buy the strike nearest delta 0.70", "SD80": "buy the strike nearest delta 0.80",
    "XI1": "index stop of 0.15 x premium / delta points REPLACES the -15% premium stop",
    "XI2": "index stop of 0.15 x premium / delta points ADDED to the -15% premium stop",
    "PKD": "R151's logistic picker + delta, gamma, theta, IV features (same keep share as R151's app picker)",
    "OF1": "PROXY order flow: skip when the option's 3-min tick-rule delta is negative",
    "OF2": "PROXY order flow: skip a breakout when the option's 15-min tick-rule cumulative delta is negative (divergence)",
    "OF3": "PROXY order flow: exit when the option's cumulative tick-rule delta since entry is below -its 5-min volume",
}
TESTS = tuple((a, r, n) for a in ARMS for r in RULES for n in LOTS)          # 78
PRIOR_M = 2164                                                              # R160's m (PREREG section 4)
M_TOTAL = len(TESTS) + PRIOR_M
TWINS, SEED = 2000, 165
GREEK_FEATS = ["g_delta", "g_gamma_rel", "g_theta_frac", "g_iv", "g_iv_atm"]
R151 = HERE.parent / "r151_donchian_winrate60"
R151_THR = {"R72": 0.4656, "R51": 0.4469}


# ================================================================================================ per-candidate facts
_EXP: dict = {}


def expiries(und: str) -> list:
    if und not in _EXP:
        _EXP[und] = sorted(d for d, D in R.mods()["r51data"].load(und).items() if D.expiry)
    return _EXP[und]


def ckey(und, day, er, side) -> tuple:
    return (und, day, int(er), side)


def cand_facts(und: str, D, c: dict) -> dict:
    """Decision-minute Greeks of today's strike and of every strike of the side, the ATM IV, the index stop row and the
    order-flow proxy features. Only the in-trade exits (index stop, OF3) read minutes from the entry row on."""
    er, side, k0 = int(c["er"]), c["side"], float(c["strike"])
    exp = L.next_expiry(D.d, expiries(und))
    ic = D.idx[3]
    deltas, g0 = {}, None
    for (s, k), arr in D.con.items():
        if s != side:
            continue
        g = L.contract_greeks(side, k, arr[3], ic, er, D.d, exp)
        deltas[float(k)] = g["delta"]
        if float(k) == k0:
            g0 = g
    if g0 is None:
        g0 = dict(S=L.last_close(ic, er - 1), P=float("nan"), T=float("nan"), iv=float("nan"), delta=float("nan"),
                  gamma=float("nan"), theta=float("nan"), vega=float("nan"))
    atm = D.atm[er - 1] if D.atm is not None and er >= 1 else np.nan
    iv_atm = float("nan")
    if np.isfinite(atm) and (side, float(atm)) in D.con:
        iv_atm = L.contract_greeks(side, float(atm), D.con[(side, float(atm))][3], ic, er, D.d, exp)["iv"]
    pts = L.index_stop_points(g0["P"], g0["delta"])
    lvl = g0["S"] - pts if side == "C" else g0["S"] + pts
    con = D.con.get((side, k0))
    f3 = f15 = float("nan")
    of3 = -1
    if con is not None:
        sv = L.tick_signed_volume(con[3], con[4])
        f3, f15 = L.flow_before(sv, er, 3), L.flow_before(sv, er, 15)
        of3 = L.flow_exit_row(sv, con[4], er, er, len(ic))
    dl = g0["delta"]
    return dict(delta=dl, iv=g0["iv"], P=g0["P"], S=g0["S"], T=g0["T"], deltas=deltas, iv_atm=iv_atm,
                xi_row=L.index_stop_row(ic, side, lvl, er, len(ic)), of3_row=of3, f3=f3, f15=f15,
                g_delta=dl,
                g_gamma_rel=(g0["gamma"] * g0["S"] / 1000.0 / dl) if np.isfinite(dl) and dl > 0 else np.nan,
                g_theta_frac=(g0["theta"] / g0["P"]) if np.isfinite(g0["P"]) and g0["P"] > 0 else np.nan,
                g_iv=g0["iv"], g_iv_atm=(g0["iv"] / iv_atm) if np.isfinite(iv_atm) and iv_atm > 0 else np.nan)


def facts_of(us: list) -> dict:
    data = R.mods()["r51data"]
    out = {}
    for key, cands, _, _ in us:
        D = data.load(key[0])[key[1]]
        for c in cands:
            out[ckey(key[0], key[1], c["er"], c["side"])] = cand_facts(key[0], D, c)
    return out


# ================================================================================================ pricing variants
def _fx(c: dict, row: int) -> int:
    a = int(c.get("fx", -1) if c.get("fx") is not None else -1)
    if row < 0:
        return a
    return row if a < 0 else min(a, row)


def variant_cands(cands: list, und, day, fx: dict, rule: str, strike_of=None) -> list:
    out = []
    for c in cands:
        f = fx[ckey(und, day, c["er"], c["side"])]
        c2 = dict(c)
        if rule in L.TARGETS:
            c2["strike"] = L.choose_strike(float(c["strike"]), f["deltas"], L.TARGETS[rule])
        elif rule == "TWIN" and strike_of is not None:
            c2["strike"] = strike_of(c)
        elif rule in ("XI1", "XI2"):
            c2["fx"] = _fx(c, f["xi_row"])
        elif rule == "OF3":
            c2["fx"] = _fx(c, f["of3_row"])
        out.append(c2)
    return out


def priced(us: list, fx: dict, rule: str, lots: int) -> list:
    M = R.mods()
    ex = R.exits()
    if rule == "XI1":
        ex = dataclasses.replace(ex, stop=-1.0)
    return [M["Y"].price(key[0], M["r51data"].load(key[0])[key[1]],
                         variant_cands(cands, key[0], key[1], fx, rule), ex, None, None, lots)
            for key, cands, _, _ in us]


def subset(us: list, pr: list, keep) -> tuple:
    """Filters: dropped candidates are removed before the walk (R151's research filters)."""
    u2, p2 = [], []
    for (key, cands, res, how), row in zip(us, pr):
        idx = [j for j, c in enumerate(cands) if keep(key, c)]
        if idx:
            u2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
            p2.append([row[j] for j in idx])
    return u2, p2


def filter_keep(rule: str, fx: dict, scores: dict | None = None, thr: float | None = None):
    def keep(key, c):
        f = fx[ckey(key[0], key[1], c["er"], c["side"])]
        if rule in L.BANDS:
            return L.in_band(f["delta"], rule)
        if rule == "OF1":
            return not (np.isfinite(f["f3"]) and f["f3"] < 0)
        if rule == "OF2":
            return not (np.isfinite(f["f15"]) and f["f15"] < 0)
        if rule == "PKD":
            s = scores.get(ckey(key[0], key[1], c["er"], c["side"]))
            return s is None or s >= thr
        raise ValueError(rule)
    return keep


# ================================================================================================ PKD (R151 + Greeks)
_T: dict = {}


def r151():
    """R151's study module (features, folds, model) imported as r152picker does."""
    if not _T:
        sys.path.insert(0, str(R151))
        argv = sys.argv
        sys.argv = [argv[0], "design"]
        import study as T  # noqa: E402  (R151; reads sys.argv at import)
        sys.argv = argv
        _T["T"] = T
    return _T["T"]


def feat_matrix(keys: list, f151: dict, fx: dict, greeks: bool) -> np.ndarray:
    T = r151()
    a = T.matrix(keys, f151)
    if not greeks:
        return a
    g = np.array([[fx[k].get(n, np.nan) for n in GREEK_FEATS] for k in keys], dtype=float).reshape(len(keys), -1)
    return np.hstack([a, g])


def oof(us: list, trades: list, f151: dict, fx: dict, greeks: bool) -> dict:
    """R151 section 3's walk-forward out-of-fold scores (expanding by quarter), with or without the Greek features."""
    T = r151()
    tk = [ckey(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
    y = np.array([t["net"] > 0 for t in trades], int)
    days = np.array([t["day"] for t in trades], dtype=object)
    sc = {}
    for f0, f1 in T.FOLDS:
        m = np.array([d < f0 for d in days], bool)
        if m.sum() < 200:
            continue
        mdl = T.make_model("M2").fit(feat_matrix([k for k, z in zip(tk, m) if z], f151, fx, greeks), y[m])
        ck = [ckey(key[0], key[1], c["er"], c["side"]) for key, cs, _, _ in us for c in cs if f0 <= key[1] <= f1]
        if ck:
            sc.update(zip(ck, (float(p) for p in mdl.predict_proba(feat_matrix(ck, f151, fx, greeks))[:, 1])))
    return sc


def keep_share_threshold(r151_sc: dict, thr151: float, pkd_sc: dict) -> tuple:
    """PREREG D: the PKD threshold keeping the same share of WF candidates as R151's picker at its app threshold."""
    v = np.array(list(r151_sc.values()), float)
    share = float((v >= thr151).mean()) if v.size else 1.0
    w = np.array(list(pkd_sc.values()), float)
    thr = float(np.quantile(w, 1.0 - share)) if w.size else 0.0
    return share, thr


# ================================================================================================ helpers
def by_period(tr: list, lo: date, hi: date) -> list:
    return [t for t in tr if lo <= t["day"] <= hi]


def tkey(t: dict) -> tuple:
    return ckey(t["und"], t["day"], t["er_sig"], t["side"])


def rule_trades(arm, us, fx, rule, lots, cache, pk=None) -> list:
    """Trades of one rule at `lots` (pk = (scores, threshold) for PKD)."""
    if rule in FILTERS:
        if ("B0", lots) not in cache:
            cache[("B0", lots)] = priced(us, fx, "B0", lots)
        u, p = subset(us, cache[("B0", lots)], filter_keep(rule, fx, *(pk or (None, None))))
        return R.walk(u, p)
    if (rule, lots) not in cache:
        cache[(rule, lots)] = priced(us, fx, rule, lots)
    return R.walk(us, cache[(rule, lots)])


def delta_buckets(tr: list, fx: dict) -> list:
    edges = [0.0, 0.45, 0.55, 0.65, 0.80, 1.01]
    out = []
    for a, b in zip(edges[:-1], edges[1:]):
        sel = [t for t in tr if np.isfinite(fx[tkey(t)]["delta"]) and a <= fx[tkey(t)]["delta"] < b]
        out.append(dict(band=f"{a:.2f}-{min(b, 1.0):.2f}", **L.stats(sel)))
    out.append(dict(band="unknown", **L.stats([t for t in tr if not np.isfinite(fx[tkey(t)]["delta"])])))
    return out


# ================================================================================================ twins (final)
def filter_twin(us, pr, k_drop, n, rng) -> np.ndarray:
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    out = np.zeros(n)
    for x in range(n):
        gone = set(map(tuple, np.array(flat)[rng.choice(len(flat), size=k_drop, replace=False)].tolist())) if k_drop else set()
        u2, p2 = [], []
        for g, ((key, cands, res, how), row) in enumerate(zip(us, pr)):
            idx = [j for j in range(len(cands)) if (g, j) not in gone]
            if idx:
                u2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
                p2.append([row[j] for j in idx])
        tr = R.walk(u2, p2)
        out[x] = np.mean([t["net"] > 0 for t in tr]) if tr else 0.0
    return out


def strike_twin(us, fx, lots, n, rng) -> np.ndarray:
    """Each candidate's strike drawn from its twin pool (deltas 0.40 - 0.85); every pool strike priced once."""
    M = R.mods()
    ex = R.exits()
    table = []                       # per unit: per candidate: list of priced trades (one per pool strike)
    for key, cands, _, _ in us:
        D = M["r51data"].load(key[0])[key[1]]
        rows = []
        for c in cands:
            pool = L.twin_strikes(float(c["strike"]), fx[ckey(key[0], key[1], c["er"], c["side"])]["deltas"])
            rows.append(M["Y"].price(key[0], D, [dict(c, strike=k) for k in pool], ex, None, None, lots))
        table.append(rows)
    out = np.zeros(n)
    for x in range(n):
        pr = [[opts[rng.integers(0, len(opts))] for opts in rows] for rows in table]
        tr = R.walk(us, pr)
        out[x] = np.mean([t["net"] > 0 for t in tr]) if tr else 0.0
    return out


def exit_twin(rule_tr, plain, lots, n, rng) -> tuple:
    """Each trade the rule closed at a different minute from B0 is sold at a random minute strictly between its fill and
    the later of the two exits (the arm's exits active before it); the win rate of each twin."""
    M = R.mods()
    ex = R.exits()
    fixed_w, draws = 0, []
    for t in rule_tr:
        p = plain.get(tkey(t))
        if p is None or int(p["xr"]) == int(t["xr"]):
            fixed_w += t["net"] > 0
            continue
        hi = max(int(p["xr"]), int(t["xr"]))
        rows = np.arange(int(t["er"]) + 1, hi, dtype=np.int64)
        if not rows.size:
            fixed_w += t["net"] > 0
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        draws.append(M["Y"].forced_exit_nets(t["und"], D, t, ex, rows, lots) > 0)
    out = np.full(n, float(fixed_w))
    for w in draws:
        out += w[rng.integers(0, len(w), n)]
    return out / max(len(rule_tr), 1), len(draws)


# ================================================================================================ phases
def evaluate(arm: str, phase: str, lo: date, hi: date) -> dict:
    us = R.units(arm, lo, hi)
    out = {"parity": R.parity(arm, phase, us)}
    fx = facts_of(us)
    n_c = len(fx)
    out["deltas"] = dict(candidates=n_c, unknown=sum(1 for f in fx.values() if not np.isfinite(f["delta"])),
                         q=[round(float(v), 3) for v in np.nanquantile([f["delta"] for f in fx.values()],
                                                                        [0.1, 0.25, 0.5, 0.75, 0.9])])
    pers = [("design", lo, hi), ("wf", K2.WF_START, hi)] if phase == "design" else [("final", lo, hi)]
    # ---- PKD
    T = r151()
    f151 = T.feats_of_units(us)
    if phase == "design":
        b0 = R.walk(us, priced(us, fx, "B0", 1))
        sc151 = oof(us, b0, f151, fx, False)
        scd = oof(us, b0, f151, fx, True)
        share, thr = keep_share_threshold(sc151, R151_THR[arm], scd)
        mdl = T.make_model("M2").fit(feat_matrix([tkey(t) for t in b0], f151, fx, True), np.array([t["net"] > 0 for t in b0], int))
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / f"pkd_{arm}.pkl").write_bytes(pickle.dumps(dict(model=mdl, thr=thr, share=share)))
        out["pkd"] = dict(share=round(share, 4), thr=round(thr, 4), wf_scored=len(scd))
        pk = (scd, thr)
    else:
        z = pickle.loads((OUT / f"pkd_{arm}.pkl").read_bytes())
        ck = list(fx)
        pk = (dict(zip(ck, (float(p) for p in z["model"].predict_proba(feat_matrix(ck, f151, fx, True))[:, 1]))), z["thr"])
        out["pkd"] = dict(share=z["share"], thr=z["thr"])
    print(arm, phase, "pkd", out["pkd"], flush=True)
    rng = np.random.default_rng(SEED)
    keep = {}
    for lots in LOTS:
        cache = {}
        b0 = rule_trades(arm, us, fx, "B0", lots, cache)
        keep[("B0", lots)] = b0
        out[f"B0@{lots}"] = {p: L.stats(by_period(b0, a, b)) for p, a, b in pers}
        if lots == 1:
            out["buckets"] = {p: delta_buckets(by_period(b0, a, b), fx) for p, a, b in pers}
        plain = {tkey(t): t for t in b0}
        for rule in RULES:
            tr = rule_trades(arm, us, fx, rule, lots, cache, pk if rule == "PKD" else None)
            keep[(rule, lots)] = tr
            r = {p: L.stats(by_period(tr, a, b)) for p, a, b in pers}
            if phase == "final":
                win = r["final"]["win"] or 0.0
                if rule in FILTERS:
                    u, _ = subset(us, cache[("B0", lots)], filter_keep(rule, fx, *(pk if rule == "PKD" else (None, None))))
                    k_drop = n_c - sum(len(x[1]) for x in u)
                    tw = filter_twin(us, cache[("B0", lots)], k_drop, TWINS, rng)
                    r["twin"] = dict(kind="random skips", dropped=k_drop, mean=round(float(tw.mean()), 4))
                elif rule in STRIKES:
                    if ("STW", lots) not in cache:
                        cache[("STW", lots)] = strike_twin(us, fx, lots, TWINS, rng)
                    tw = cache[("STW", lots)]
                    r["twin"] = dict(kind="random strikes (delta 0.40-0.85)", mean=round(float(tw.mean()), 4))
                else:
                    tw, nch = exit_twin(tr, plain, lots, TWINS, rng)
                    r["twin"] = dict(kind="random exits", changed=nch, mean=round(float(tw.mean()), 4))
                r["p"] = L.win_p(win, tw)
            out[f"{rule}@{lots}"] = r
            print(arm, phase, rule, lots, {p: (r[p]["trades"], r[p]["win"], r[p]["net"]) for p, _, _ in pers},
                  "B0", {p: (out[f"B0@{lots}"][p]["win"], out[f"B0@{lots}"][p]["net"]) for p, _, _ in pers},
                  r.get("p"), flush=True)
    if phase == "final":
        out["r151_picker"] = picker_baseline(arm, us)
        out["futures_proxy"] = futures_proxy(arm, keep[("B0", 1)])
    out["iv_check"] = iv_check(keep[("B0", 1)], fx)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{arm}_{phase}.pkl").write_bytes(pickle.dumps(dict(keep=keep, fx=fx), protocol=pickle.HIGHEST_PROTOCOL))
    return out


def picker_baseline(arm: str, us: list) -> dict:
    """Gate 3's baseline: R151's app picker (the app's numbers; a skipped break holds its side), final, 1-3 lots."""
    import r152picker as P
    sc, thr = P.scores(arm, us)
    out = {}
    for lots in LOTS:
        tr = [t for t in R.walk(us, R.priced(us, None, None, lots)) if P.kept(t, sc, thr)]
        out[f"@{lots}"] = L.stats(tr)
    # Parity with R152's app-style walk (results/picker.json B0@1: a skipped break holds its side). R151's published
    # 652 / 693 trades are its research walk (skipped breaks removed BEFORE the walk), a different number by design.
    ref = json.loads((K2.RESULTS / "picker.json").read_text())[arm]["B0@1"]
    out["r152_app_walk_1lot"] = [ref["trades"], ref["net"]]
    out["reproduced"] = out["@1"]["trades"] == ref["trades"] and abs(out["@1"]["net"] - ref["net"]) <= 1
    out["r151_research_walk_1lot"] = list(K2.PICKER[arm][2])
    print(arm, "R151 picker", out, flush=True)
    return out


def futures_proxy(arm: str, b0: list) -> dict:
    """Descriptive only: the index future's tick-rule delta over the 3 minutes before entry (29 Jul - 13 Oct 2026)."""
    import pandas as pd
    import r51cfg as RK
    p = RK.LAKE / "fut_1m_live_index.parquet"
    if not p.exists():
        return {"note": "fut_1m_live_index missing"}
    f = pd.read_parquet(p)
    f["ts"] = pd.to_datetime(f.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    f["day"] = f.ts.dt.date
    f["r"] = f.ts.dt.hour * 60 + f.ts.dt.minute - RK.OPEN_MIN
    f = f[(f.r >= 0) & (f.r < RK.NROWS)]
    # the near contract of each day: the one with the most volume that day
    vol = f.groupby(["symbol", "day", "expiry"]).volume.sum().reset_index()
    near = vol.loc[vol.groupby(["symbol", "day"]).volume.idxmax()][["symbol", "day", "expiry"]]
    f = f.merge(near, on=["symbol", "day", "expiry"])
    series = {}
    for (s, d), g in f.groupby(["symbol", "day"]):
        c = np.full(RK.NROWS, np.nan)
        v = np.zeros(RK.NROWS)
        c[g.r.values] = g.close.values
        v[g.r.values] = g.volume.values
        series[(s, d)] = L.tick_signed_volume(c, v)
    rows = {"with": [], "against": [], "flat": []}
    for t in b0:
        sv = series.get((t["und"], t["day"]))
        if sv is None:
            continue
        x = L.flow_before(sv, int(t["er_sig"]), 3) * (1 if t["side"] == "C" else -1)
        rows["with" if x > 0 else ("against" if x < 0 else "flat")].append(t)
    return {k: L.stats(v) for k, v in rows.items()} | {"period": "29 Jul - 13 Oct 2026 (futures 1-min only from 29 Jul)",
                                                        "arm": arm}


def iv_check(b0: list, fx: dict) -> dict:
    """Sanity: our IV vs the lake's own iv column (Dhan) at the decision minute, on up to 400 trades."""
    import pandas as pd
    import r51cfg as RK
    sample = [t for t in b0 if np.isfinite(fx[tkey(t)]["iv"])][:: max(1, len(b0) // 400)]
    pairs = []
    cache = {}
    for t in sample:
        y = t["day"].year
        k = (t["und"], y)
        if k not in cache:
            p = RK.LAKE / f"opt_{t['und']}_WEEK_{y}.parquet"
            if not p.exists():
                cache[k] = None
                continue
            df = pd.read_parquet(p, columns=["ts", "side", "strike", "iv"])
            df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
            df["side"] = df.side.astype(str).str[0].str.upper()
            cache[k] = df.drop_duplicates(["ts", "side", "strike"]).set_index(["ts", "side", "strike"]).iv
        s = cache[k]
        if s is None:
            continue
        r = int(t["er_sig"]) - 1
        ts = pd.Timestamp(datetime(t["day"].year, t["day"].month, t["day"].day)) + pd.Timedelta(minutes=RK.OPEN_MIN + r)
        v = s.get((ts, t["side"], float(t["strike"])))
        if v is not None and np.isfinite(v) and v > 0:
            pairs.append((fx[tkey(t)]["iv"] * 100, float(v)))
    if len(pairs) < 10:
        return {"pairs": len(pairs)}
    a = np.array(pairs)
    return dict(pairs=len(a), ours_median=round(float(np.median(a[:, 0])), 2), lake_median=round(float(np.median(a[:, 1])), 2),
                corr=round(float(np.corrcoef(a[:, 0], a[:, 1])[0, 1]), 3),
                median_abs_diff=round(float(np.median(np.abs(a[:, 0] - a[:, 1]))), 2))


def design_r151() -> dict:
    """Descriptive (design data only): R151's own logistic picker on the same walk-forward folds at its app threshold,
    walked as R165's PKD (dropped breaks removed before the walk), 1 lot, next to PKD."""
    T = r151()
    out = {}
    for arm in ARMS:
        us = R.units(arm, K2.START, K2.DESIGN_END)
        fx = facts_of(us)
        f151 = T.feats_of_units(us)
        pr = priced(us, fx, "B0", 1)
        b0 = R.walk(us, pr)
        sc = oof(us, b0, f151, fx, False)
        z = pickle.loads((OUT / f"pkd_{arm}.pkl").read_bytes())
        scd = oof(us, b0, f151, fx, True)
        row = {}
        for name, s, thr in (("R151_M2", sc, R151_THR[arm]), ("PKD", scd, z["thr"])):
            u, p = subset(us, pr, filter_keep("PKD", fx, s, thr))
            row[name] = L.stats(by_period(R.walk(u, p), K2.WF_START, K2.DESIGN_END))
        row["B0"] = L.stats(by_period(b0, K2.WF_START, K2.DESIGN_END))
        out[arm] = row
        print(arm, row, flush=True)
    (RESULTS / "design_r151.json").write_text(json.dumps(out, indent=1))
    return out


def run(phase: str):
    M = R.mods()
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if FINAL_FLAG.exists() and not (RESULTS / "final_open").exists():
            raise SystemExit("R165 final already read once: " + FINAL_FLAG.read_text())
        assert (RESULTS / "design.json").exists(), "design first"
        if not FINAL_FLAG.exists():
            FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
            (RESULTS / "final_open").write_text("arms in progress\n")
        M["C"].open_final()
        lo, hi = K2.FINAL_START, K2.FINAL_END
    else:
        lo, hi = K2.START, K2.DESIGN_END
    for arm in ARMS:
        done = RESULTS / f"{phase}_{arm}.json"
        if phase != "design" and done.exists():
            continue
        done.write_text(json.dumps(evaluate(arm, phase, lo, hi), indent=1, default=str))
    if all((RESULTS / f"{phase}_{a}.json").exists() for a in ARMS):
        (RESULTS / f"{phase}_open").unlink(missing_ok=True)
        (RESULTS / f"{phase}.json").write_text(json.dumps(
            {a: json.loads((RESULTS / f"{phase}_{a}.json").read_text()) for a in ARMS}, indent=1))
        for a in ARMS:
            (RESULTS / f"{phase}_{a}.json").unlink()


def decide() -> dict:
    d = json.loads((RESULTS / "design.json").read_text())
    f = json.loads((RESULTS / "final.json").read_text())
    ps = [f[a][f"{r}@{n}"]["p"] for a, r, n in TESTS]
    q_all = L.bh(ps, M_TOTAL)
    q_own = L.bh(ps)
    out, rows = {}, []
    for (a, r, n), p, qa, qo in zip(TESTS, ps, q_all, q_own):
        k, b = f"{r}@{n}", f"B0@{n}"
        rule = dict(wf=d[a][k]["wf"], final=f[a][k]["final"])
        base = dict(wf=d[a][b]["wf"], final=f[a][b]["final"])
        g = L.gates(rule, base, f[a]["r151_picker"][f"@{n}"]["net"], round(p, 5), round(float(qa), 5))
        g["q_r165"] = round(float(qo), 5)
        out[f"{a}:{k}"] = g
        rows.append(dict(study="R165", test=f"r165-{a.lower()}-{r.lower()}-{n}lot", arm=a, rule=r, lots=n,
                         family="order-flow proxy" if r.startswith("OF") else "option delta", p_raw=round(p, 6),
                         twin=f[a][k]["twin"]["kind"], final_trades=f[a][k]["final"]["trades"],
                         final_win=f[a][k]["final"]["win"], b0_final_win=f[a][b]["final"]["win"],
                         q_r165_alone=round(float(qo), 6), q_m2242=round(float(qa), 6), passed=g["passed"]))
    out["_m"] = dict(r165=len(TESTS), prior=PRIOR_M, total=M_TOTAL)
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    with open(RESULTS / "pvalues.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "design_r151":
        design_r151()
    elif cmd == "decide":
        print(json.dumps({k: v for k, v in decide().items() if k == "_m" or v.get("passed")}, indent=1))
    else:
        raise SystemExit(__doc__)
