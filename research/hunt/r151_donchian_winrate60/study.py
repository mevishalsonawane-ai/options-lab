"""R151 (PREREG.md + Amendment A): R51 / R72 Donchian to 60%+ wins with profit: a trade picker, and a signal-parameter sweep.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/study.py design
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/study.py final
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/study.py old
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r151_donchian_winrate60/study.py design_w20   # Amendment B
    python research/hunt/r151_donchian_winrate60/study.py decide

Exits are the app's (R101 XL: -15%, the 30-60-100 lock + L25, 30 min, 15:15), priced by R101 / R106's engine on R51's clean
stores (parity with the published arms at today's parameters). Models trained in `design` are frozen to
~/.cache/iraalgo/r151/models.pkl and reused by `final` / `old`.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
CMD = (sys.argv[1:] or [""])[0]
# "old": study_b (imported below as "unseen") points R51_OUT at R143's old store before r51cfg is imported
sys.argv = [sys.argv[0], "unseen" if CMD == "old" else "design"] + sys.argv[2:]   # study_b reads its own CMD
sys.path.insert(0, str(HERE.parent / "r150_sr_levels"))
sys.path.insert(0, str(HERE.parents[2]))

import numpy as np  # noqa: E402
from sklearn.ensemble import HistGradientBoostingClassifier  # noqa: E402
from sklearn.impute import SimpleImputer  # noqa: E402
from sklearn.linear_model import LogisticRegression  # noqa: E402
from sklearn.pipeline import make_pipeline  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402

import study_b as B  # noqa: E402
import study_d as Dm  # noqa: E402

from research.hunt.r150_sr_levels import levels as V  # noqa: E402
from research.hunt.r151_donchian_winrate60 import pick as PK  # noqa: E402

R140, K, L, S = B.R140, B.K, B.L, B.S
RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r151"
SEED, TWINS = 151, 2000
ARMS = ("R51", "R72")
FOLDS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
         (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
WF = (FOLDS[0][0], FOLDS[-1][1])
OLD = (date(2022, 12, 1), date(2024, 11, 19))
OLD_WARM, OLD_START, OLD_END = date(2022, 9, 1), date(2022, 11, 1), date(2024, 12, 31)
GB = dict(max_depth=3, learning_rate=0.05, max_iter=200, min_samples_leaf=40, l2_regularization=1.0, random_state=151)
R51_GRID = [(n, m) for n in (10, 20, 30, 55) for m in (2.0, 3.0, 4.0)]
R72_GRID = [(n, m) for n in (10, 20, 30, 55) for m in (3, 4, 5)]
TODAY_PARAMS = {"R51": (20, 3.0), "R72": (20, 4)}
VIXREG = {"LO": 0.0, "MID": 1.0, "HI": 2.0}
FEATS = ["tod", "side", "day_move", "from_open", "range_ratio", "gap", "twap_dist", "ema_slope", "ema_dist", "bar_size",
         "break_size", "breaks_today", "ret5d", "sr_ahead", "sr_behind", "sr_touch_ahead", "sr_crossed", "piv_ahead",
         "vix", "dvix", "breadth5", "f_rv", "f_gap", "f_vixchg", "f_breadth", "f_dow", "f_count", "f_vixreg"]


# ================================================================================================ features (decision minute)
_VIX: dict = {}


def vix_days() -> dict:
    if not _VIX:
        from research.hunt.r147_night_plus import data as D7
        _VIX.update(D7.index_days("INDIAVIX"))        # {day: (375, 4)}
    return _VIX


def _last(arr: np.ndarray) -> float:
    ok = np.flatnonzero(np.isfinite(arr))
    return float(arr[ok[-1]]) if ok.size else np.nan


def features(und: str, day, er: int, side: str, cand: dict, earlier: int) -> dict:
    """Every R151 feature of one candidate, from facts known at the decision minute (er - 1). `earlier`: the arm's
    candidates on this index earlier today."""
    s = 1.0 if side == "C" else -1.0
    x = B.lv(und)
    i = x.pos[day]
    a = np.array(x.idx[day], dtype=float, copy=True)
    a[:, er:] = np.nan                                  # the look-ahead wall: nothing from minute er on
    price = _last(a[3])
    prev = _last(x.idx[x.dates[i - 1]][3]) if i > 0 else np.nan
    o0 = _last(a[0][: 5])
    hi, lo = np.nanmax(a[1]), np.nanmin(a[2])
    rngs = []
    for d in x.dates[max(0, i - 20): i]:
        z = x.idx[d]
        if np.isfinite(z[1]).any():
            rngs.append(np.nanmax(z[1]) - np.nanmin(z[2]))
    tp = (a[1] + a[2] + a[3]) / 3
    twap = float(np.nanmean(tp)) if np.isfinite(tp).any() else np.nan
    if und not in Dm._EMA:
        Dm._EMA[und] = Dm.ema(x.b["c"])
    closed = np.flatnonzero((x.b["day"] < i) | ((x.b["day"] == i) & (x.b["end"] < er)))
    f = {}
    if len(closed) >= 25:
        j = int(closed[-1])
        e, c = Dm._EMA[und], x.b["c"]
        f["ema_slope"] = s * (e[j] - e[j - 3]) / price
        f["ema_dist"] = s * (c[j] - e[j]) / price
        rng5 = x.b["h"][j - 20:j] - x.b["l"][j - 20:j]
        f["bar_size"] = (x.b["h"][j] - x.b["l"][j]) / max(float(np.mean(rng5)), 1e-9)
        f["break_size"] = (s * (c[j] - (x.b["h"][j - 20:j].max() if s > 0 else x.b["l"][j - 20:j].min())) / price)
    p5 = _last(x.idx[x.dates[i - 5]][3]) if i >= 5 else np.nan
    lv = x.levels(day, er, 20)
    ahead = [abs(l_ / price - 1) for l_, n in lv if n >= 3 and s * (l_ - price) > 0]
    behind = [abs(l_ / price - 1) for l_, n in lv if n >= 3 and s * (l_ - price) <= 0]
    na = [n for l_, n in lv if n >= 3 and s * (l_ - price) > 0 and abs(l_ / price - 1) == (min(ahead) if ahead else -1)]
    prev5 = x.close_at(day, er - 6)
    piv = Dm.pivots_of(und, day)
    piv_ahead = [abs(p / price - 1) for p in piv if s * (p - price) > 0]
    vd = vix_days()
    vt = vd.get(day)
    vnow = _last(vt[: er, 3]) if vt is not None else np.nan
    vprev_day = max((d for d in vd if d < day), default=None)
    vprev = _last(vd[vprev_day][:, 3]) if vprev_day is not None else np.nan
    ups = []
    for u in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"):
        try:
            y = B.lv(u)
        except Exception:  # noqa: BLE001  (an index missing in a store: left out)
            continue
        if day not in y.pos or y.pos[day] == 0:
            continue
        pc = _last(y.idx[y.dates[y.pos[day] - 1]][3])
        now = y.close_at(day, er - 1)
        if np.isfinite(pc) and np.isfinite(now):
            ups.append(float(now > pc))
    f.update({
        "tod": float(er), "side": s, "day_move": s * (price / prev - 1), "from_open": s * (price / o0 - 1),
        "range_ratio": (hi - lo) / float(np.mean(rngs)) if rngs else np.nan, "gap": s * (o0 / prev - 1),
        "twap_dist": s * (price / twap - 1), "breaks_today": float(earlier), "ret5d": s * (price / p5 - 1),
        "sr_ahead": min(ahead) if ahead else 0.05, "sr_behind": min(behind) if behind else 0.05,
        "sr_touch_ahead": float(na[0]) if na else 0.0,
        "sr_crossed": float(np.isfinite(prev5) and V.crossed(lv, prev5, price, side, 3)),
        "piv_ahead": min(piv_ahead) if piv_ahead else 0.05,
        "vix": vnow, "dvix": np.log(vnow / vprev) if np.isfinite(vnow) and np.isfinite(vprev) and vprev > 0 else np.nan,
        "breadth5": float(np.mean(ups)) if ups else np.nan,
        "f_rv": cand.get("f_rv", np.nan), "f_gap": cand.get("f_gap", np.nan), "f_vixchg": cand.get("f_vixchg", np.nan),
        "f_breadth": cand.get("f_breadth", np.nan), "f_dow": cand.get("f_dow", np.nan), "f_count": cand.get("f_count", np.nan),
        "f_vixreg": VIXREG.get(cand.get("f_vixreg"), np.nan)})
    return f


_FC: dict = {}


def cand_key(und, day, er, side):
    return (und, day, int(er), side)


def feats_of_units(us: list) -> dict:
    out = {}
    for key, cands, _, _ in us:
        und, day = key[0], key[1]
        for n, c in enumerate(sorted(cands, key=lambda c: int(c["er"]))):
            k = cand_key(und, day, c["er"], c["side"])
            if k not in _FC:
                _FC[k] = features(und, day, int(c["er"]), c["side"], c, n)
            out[k] = _FC[k]
    return out


def matrix(keys: list, fx: dict) -> np.ndarray:
    return np.array([[fx[k].get(f, np.nan) for f in FEATS] for k in keys], dtype=float)


# ================================================================================================ signals (Amendment A)
def r51_signals(n: int, mult: float) -> dict:
    A = S.mods()["A"]
    AM = A.mods()
    fam = AM["fam_r44"]
    S12 = S.mods()["S12"]
    P73 = S12.mods()["P73"]
    import r112lib as LL
    out = {}
    for u in A.K.UNDS:
        d, b, o, h, l_, c = fam.bars5(u)
        lo_, sh_ = fam.IND.signals("donchian", (n,), o, h, l_, c, warm=fam.R44K.WARM_BARS)
        raw = {}
        for side, m in (("C", lo_), ("P", sh_)):
            for i in np.flatnonzero(m):
                raw.setdefault(d[i], []).append((5 * (int(b[i]) + 1), side))
        raw = {k: sorted(v) for k, v in raw.items()}
        pos = {(d[i], int(b[i])): i for i in range(len(d))}
        st = P73.supertrend_dir(np.asarray(h, float), np.asarray(l_, float), np.asarray(c, float), 10, float(mult))
        out[u] = LL.supertrend_keep(raw, pos, st)
    return out


def r72_signals(n: int, m: int) -> dict:
    A = S.mods()["A"]
    AM = A.mods()
    P72, fam = AM["P72"], AM["fam_r44"]
    keep = P72.N_CH
    try:
        P72.N_CH = n
        panel = P72.Panel({u: fam.bars5(u) for u in A.K.UNDS})
        main = P72.breadth_breakout(panel, m)
        cnt = {k: P72.breadth_breakout(panel, k) for k in (3, 4, 5)}
    finally:
        P72.N_CH = keep
    out = {}
    for u in A.K.UNDS:
        sets = {k: {(i, s) for i, s, _ in v[u]} for k, v in cnt.items()}
        d, b = panel.f[u]["d"], panel.f[u]["b"]
        per = {}
        for i, s, _ in main[u]:
            fc = 5 if (i, s) in sets[5] else (4 if (i, s) in sets[4] else 3)
            per.setdefault(d[i], []).append((5 * (int(b[i]) + 1), "C" if s > 0 else "P", {"f_count": fc}))
        out[u] = {k: sorted(v, key=lambda x: (x[0], x[1])) for k, v in per.items()}
    return out


def units(arm: str, params, lo, hi) -> list:
    A = S.mods()["A"]
    if params == TODAY_PARAMS[arm]:
        return S.units(arm, lo, hi)
    sig = r51_signals(*params) if arm == "R51" else r72_signals(*params)
    return A._side2_units(sig, lo, hi, A._xl())


# ================================================================================================ metrics
def summ(tr: list) -> dict:
    if not tr:
        return {"trades": 0}
    net = np.array([t["net"] for t in tr], float)
    mon = {}
    for t in tr:
        k = f"{t['day'].year}-{t['day'].month:02d}"
        mon[k] = mon.get(k, 0.0) + float(t["net"])
    return {"trades": int(len(net)), "win": float((net > 0).mean()), "net": float(net.sum()), "per_trade": float(net.mean()),
            "worst_month": float(min(mon.values())), "max_dd": R140.L.stats(tr)["max_dd"]}


def in_(t, lo, hi):
    return lo <= t["day"] <= hi


def kept_walk(arm, us, score: dict, thr: float) -> list:
    return S.walk(arm, S.filtered(us, lambda g, j, key, c: score.get(cand_key(key[0], key[1], c["er"], c["side"]), -1) >= thr))


def twin_win_p(arm, us, k_keep: int, real_win: float, lo, hi, rng, n=TWINS) -> float:
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    k_drop = len(flat) - k_keep
    hits = 0
    for _ in range(n):
        gone = {flat[x] for x in L.random_drop(len(flat), k_drop, rng)}
        tr = [t for t in S.walk(arm, S.filtered(us, lambda g, j, key, c: (g, j) not in gone)) if in_(t, lo, hi)]
        w = float(np.mean([t["net"] > 0 for t in tr])) if tr else 0.0
        hits += w >= real_win - 1e-12
    return (1 + hits) / (1 + n)


def make_model(kind: str):
    if kind == "M1":
        return HistGradientBoostingClassifier(**GB)
    return make_pipeline(SimpleImputer(strategy="median", keep_empty_features=True), StandardScaler(), LogisticRegression(C=0.1, max_iter=3000))


# ================================================================================================ design
def oof_scores(arm, us, trades, fx, kind) -> dict:
    """Out-of-fold scores for the WF folds' candidates: a model trained on every design trade before the fold."""
    sc = {}
    tkeys = [cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
    y = np.array([t["net"] > 0 for t in trades], int)
    tdays = np.array([t["day"] for t in trades], dtype=object)
    for f0, f1 in FOLDS:
        tr_m = PK.train_mask(tdays, f0)
        if tr_m.sum() < 200:
            continue
        mdl = make_model(kind).fit(matrix([k for k, m in zip(tkeys, tr_m) if m], fx), y[tr_m])
        ck = [cand_key(key[0], key[1], c["er"], c["side"]) for key, cs, _, _ in us for c in cs if f0 <= key[1] <= f1]
        if ck:
            for k, p in zip(ck, mdl.predict_proba(matrix(ck, fx))[:, 1]):
                sc[k] = float(p)
    return sc


def choose_threshold(arm, us, sc, base_wf) -> dict:
    """PREREG section 1: the lowest score threshold whose kept WF trades win >= 60% and make >= the arm's per trade,
    keeping >= 25% of the arm's WF trades. Also the curve."""
    curve = []
    for q in PK.thresholds(sc.values()):
        m = summ([t for t in kept_walk(arm, us, sc, float(q)) if in_(t, *WF)])
        m["thr"] = float(q)
        curve.append(m)
    return {"curve": curve, "pick": PK.first_pass(curve, base_wf)}


def design() -> None:
    RESULTS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)
    out = {"_meta": dict(phase="design", written=datetime.now(timezone.utc).isoformat(), wf=[str(WF[0]), str(WF[1])])}
    models = {}
    for arm in ARMS:
        res = {"sweep": []}
        # ---- Part P: the parameter sweep on the full design period
        best = None
        base_design = None
        for params in (R51_GRID if arm == "R51" else R72_GRID):
            us_p = units(arm, params, K.START, K.DESIGN_END)
            tr_p = S.walk(arm, us_p)
            m = summ(tr_p)
            m["params"] = list(params)
            res["sweep"].append(m)
            if tuple(params) == TODAY_PARAMS[arm]:
                base_design = m
            print(arm, "sweep", params, m.get("trades"), round(m.get("win", 0), 3), round(m.get("net", 0)), flush=True)
        for m in res["sweep"]:
            if m["trades"] >= 300 and m["per_trade"] >= base_design["per_trade"] and (best is None or m["win"] > best["win"]):
                best = m
        res["chosen_params"] = best["params"] if best else None
        # ---- the picker, on today's parameters and (if its design win is higher) on the chosen variant
        variants = [tuple(TODAY_PARAMS[arm])]
        if best and tuple(best["params"]) != tuple(TODAY_PARAMS[arm]) and best["win"] > base_design["win"]:
            variants.append(tuple(best["params"]))
        res["pickers"] = {}
        for params in variants:
            us = units(arm, params, K.START, K.DESIGN_END)
            trades = S.walk(arm, us)
            fx = feats_of_units(us)
            base_wf = summ([t for t in trades if in_(t, *WF)])
            for kind in ("M1", "M2"):
                pid = f"{arm}-{params[0]}-{params[1]}-{kind}"
                sc = oof_scores(arm, us, trades, fx, kind)
                ch = choose_threshold(arm, us, sc, base_wf)
                tk = [cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
                y = np.array([t["net"] > 0 for t in trades], int)
                models[pid] = (make_model(kind).fit(matrix(tk, fx), y), list(params))
                res["pickers"][pid] = {"params": list(params), "model": kind, "base_wf": base_wf, **ch}
                pk = ch["pick"]
                print(arm, pid, "base wf", base_wf.get("trades"), round(base_wf.get("win", 0), 3), round(base_wf.get("per_trade", 0)),
                      "pick", None if pk is None else (pk["trades"], round(pk["win"], 3), round(pk["per_trade"]), round(pk["thr"], 3)), flush=True)
        out[arm] = res
    (CACHE / "models.pkl").write_bytes(pickle.dumps(models))
    (RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))


# ================================================================================================ final / old
def w20_keep(g, j, key, c) -> bool:
    """Amendment B: R150 B's W20 (no 5-min 3+-turn level of the last 20 sessions within 0.25% ahead)."""
    return Dm.base_keep(g, j, key, c)


def design_w20() -> None:
    """Amendment B: the pickers on the W20-kept candidates; the bar stays the arm's TODAY (no W20)."""
    d = json.loads((RESULTS / "design.json").read_text())
    out = {"_meta": dict(phase="design_w20", written=datetime.now(timezone.utc).isoformat())}
    models = {}
    for arm in ARMS:
        today = tuple(TODAY_PARAMS[arm])
        base_wf = next(v["base_wf"] for v in d[arm]["pickers"].values() if tuple(v["params"]) == today)
        sets = []
        for v in d[arm]["pickers"].values():
            if tuple(v["params"]) not in sets:
                sets.append(tuple(v["params"]))
        res = {"pickers": {}, "w20_alone": {}}
        for params in sets:
            us = S.filtered(units(arm, params, K.START, K.DESIGN_END), w20_keep)
            trades = S.walk(arm, us)
            fx = feats_of_units(us)
            res["w20_alone"][f"{params[0]}-{params[1]}"] = summ([t for t in trades if in_(t, *WF)])
            for kind in ("M1", "M2"):
                pid = f"{arm}-{params[0]}-{params[1]}-{kind}-W20"
                sc = oof_scores(arm, us, trades, fx, kind)
                ch = choose_threshold(arm, us, sc, base_wf)
                tk = [cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
                y = np.array([t["net"] > 0 for t in trades], int)
                models[pid] = (make_model(kind).fit(matrix(tk, fx), y), list(params))
                res["pickers"][pid] = {"params": list(params), "model": kind, "w20": True, "base_wf": base_wf,
                                       "w20_wf": res["w20_alone"][f"{params[0]}-{params[1]}"], **ch}
                pk = ch["pick"]
                print(arm, pid, "w20 wf", res["w20_alone"][f"{params[0]}-{params[1]}"].get("trades"),
                      round(res["w20_alone"][f"{params[0]}-{params[1]}"].get("win", 0), 3),
                      "pick", None if pk is None else (pk["trades"], round(pk["win"], 3), round(pk["per_trade"]), round(pk["thr"], 3)),
                      "best win", round(max((m.get("win", 0) for m in ch["curve"] if m.get("trades", 0) >= 0.25 * base_wf["trades"]),
                                            default=0), 3), flush=True)
        out[arm] = res
    (CACHE / "models_w20.pkl").write_bytes(pickle.dumps(models))
    (RESULTS / "design_w20.json").write_text(json.dumps(out, indent=1, default=str))


def _pickers() -> tuple:
    """Every picker tried at design (sections 1-4, Amendment A, Amendment B) and its frozen model."""
    d = json.loads((RESULTS / "design.json").read_text())
    models = pickle.loads((CACHE / "models.pkl").read_bytes())
    allp = {arm: dict(d[arm]["pickers"]) for arm in ARMS}
    if (RESULTS / "design_w20.json").exists():
        dw = json.loads((RESULTS / "design_w20.json").read_text())
        models.update(pickle.loads((CACHE / "models_w20.pkl").read_bytes()))
        for arm in ARMS:
            allp[arm].update(dw[arm]["pickers"])
    return d, allp, models


def later(phase: str) -> None:
    d, allp, models = _pickers()
    rng = np.random.default_rng(SEED + (1 if phase == "final" else 2))
    if phase == "old":
        k = S.mods()["r51data"].K
        k.WARM_START, k.START, k.END = OLD_WARM, OLD_START, OLD_END
        lo, hi = OLD
    else:
        S.mods()["C"].open_final()
        lo, hi = K.FINAL_START, K.FINAL_END
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), written=datetime.now(timezone.utc).isoformat())}
    for arm in ARMS:
        res = {}
        base_us = units(arm, TODAY_PARAMS[arm], lo, hi)
        res["today"] = summ([t for t in S.walk(arm, base_us) if in_(t, lo, hi)])
        res["today_w20"] = summ([t for t in S.walk(arm, S.filtered(base_us, w20_keep)) if in_(t, lo, hi)])
        cp = d[arm]["chosen_params"]
        if cp and tuple(cp) != tuple(TODAY_PARAMS[arm]):
            res["chosen_variant"] = summ([t for t in S.walk(arm, units(arm, tuple(cp), lo, hi)) if in_(t, lo, hi)])
            res["chosen_variant"]["params"] = cp
        res["pickers"] = {}
        for pid, pk in allp[arm].items():
            if pk["pick"] is None:
                res["pickers"][pid] = {"design_failed": True}
                continue
            mdl, params = models[pid]
            us = units(arm, tuple(params), lo, hi)
            if pk.get("w20"):
                us = S.filtered(us, w20_keep)
            fx = feats_of_units(us)
            ck = [cand_key(key[0], key[1], c["er"], c["side"]) for key, cs, _, _ in us for c in cs]
            sc = dict(zip(ck, mdl.predict_proba(matrix(ck, fx))[:, 1])) if ck else {}
            tr = [t for t in kept_walk(arm, us, sc, pk["pick"]["thr"]) if in_(t, lo, hi)]
            m = summ(tr)
            kept = sum(1 for v in sc.values() if v >= pk["pick"]["thr"])
            m["twin_win_p"] = twin_win_p(arm, us, kept, m.get("win", 0.0), lo, hi, rng) if tr else None
            res["pickers"][pid] = m
            print(phase, arm, pid, m.get("trades"), round(m.get("win", 0), 3), round(m.get("net", 0)), m.get("twin_win_p"), flush=True)
        out[arm] = res
        print(phase, arm, "today", res["today"], "w20", res["today_w20"], flush=True)
    (RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    _, allp, _ = _pickers()
    f, o = (json.loads((RESULTS / f"{p}.json").read_text()) for p in ("final", "old"))
    rows, ps = [], []
    for arm in ARMS:
        for pid, pk in allp[arm].items():
            if pk["pick"] is None:
                rows.append({"id": pid, "pass": False, "fails": ["design: no threshold gave 60% with profit"]})
                ps.append(np.nan)
                continue
            fm, om = f[arm]["pickers"][pid], o[arm]["pickers"][pid]
            fails = []
            for nm, m, base, need in (("final", fm, f[arm]["today"], 60), ("old", om, o[arm]["today"], 100)):
                if m.get("trades", 0) < need:
                    fails.append(f"{nm}: < {need} trades")
                if m.get("win", 0) < 0.60:
                    fails.append(f"{nm}: win {m.get('win', 0):.3f} < 0.60")
                if not m.get("net", 0) > 0:
                    fails.append(f"{nm}: net <= 0")
                if m.get("per_trade", -1e9) < base.get("per_trade", 0):
                    fails.append(f"{nm}: per trade below the arm's")
                if (m.get("twin_win_p") or 1) > 0.05:
                    fails.append(f"{nm}: twin p {m.get('twin_win_p')}")
            rows.append({"id": pid, "pass": not fails, "fails": fails})
            ps.append(max(fm.get("twin_win_p") or 1, om.get("twin_win_p") or 1))
    qs = L.bh([p if np.isfinite(p) else 1.0 for p in ps])
    for r, q in zip(rows, qs):
        r["bh_q"] = float(q)
        if q > 0.10 and r["pass"]:
            r["pass"] = False
            r["fails"].append(f"BH q {q:.3f}")
    (RESULTS / "decision.json").write_text(json.dumps(rows, indent=1, default=str))
    return {"rows": rows}


if __name__ == "__main__":
    {"design": design, "design_w20": design_w20, "final": lambda: later("final"), "old": lambda: later("old"), "decide": lambda: print(json.dumps(decide(), indent=1))}[CMD]()
