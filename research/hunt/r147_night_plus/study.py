"""R147 study (PREREG.md). From the repo root:

    python -m research.hunt.r147_night_plus.data build        # nights_<UND>.pkl from the lake
    python -m research.hunt.r147_night_plus.study design      # DESIGN years only: results/design.json, atlas (design)
    python -m research.hunt.r147_night_plus.study holdout     # the ONE holdout read: results/holdout.json (+ flag)
    python -m research.hunt.r147_night_plus.study report      # results/report.md

`design` never touches a night with part != 'design' (the cut is `nights(und, part)`).
"""
from __future__ import annotations

import hashlib
import json
import pickle
import sys
from datetime import datetime

import numpy as np
import pandas as pd

from research.obuy.costs import Costs, Fills

from . import cfg as K

COST, FILL = Costs("app"), Fills("app")
FILTERS = ("F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8")
FILTER_TXT = {"F1": "skip weekend / holiday nights", "F2": "skip nights before FOMC / US CPI / RBI / Budget / election",
              "F3": "skip when |day move| >= 1.5%", "F4": "skip when the last 60 minutes moved against the side",
              "F5": "skip when the Hang Seng's day disagrees", "F6": "skip when India VIX >= 18",
              "F7": "skip unless the close is at the extreme (>= 0.8 / <= 0.2)",
              "F8": "skip when the ATM straddle is >= 1.2x its 60-session median"}
GAP_CLASSES = ("big down", "down", "flat", "up", "big up")
PROFILE = ["move", "loc", "l30", "l60", "gap0", "rng", "breadth", "buz", "vix", "dvix", "hsi", "wkend", "us_ev", "in_ev",
           "exp_today", "ov_spx"]


def prereg_sha() -> str:
    return hashlib.sha256(K.PREREG.read_bytes()).hexdigest()


# ================================================================================================ loading
def nights(und: str, part: str) -> pd.DataFrame:
    """THE cut: only nights of `part` ('design' or 'hold') leave this function."""
    recs = pickle.loads((K.OUT / f"nights_{und}.pkl").read_bytes())
    df = pd.DataFrame([r for r in recs if r["part"] == part])
    if df.empty:
        return df
    for side, nm in ((1, "c"), (-1, "p")):
        for itm in (0, 1, 2):
            df[f"net_{nm}{itm}"] = [leg_net(und, r, side, itm, 1.0) for r in df.itertuples()]
        df[f"net_{nm}1_stress"] = [leg_net(und, r, side, 1, K.STRESS) for r in df.itertuples()]
        df[f"prem_{nm}1"] = [prem(r, side) for r in df.itertuples()]
    return df


def leg_net(und: str, r, side: int, itm: int, mult: float) -> float:
    lg = r.legs.get((side, itm)) if isinstance(r.legs, dict) else None
    if lg is None or lg[3] != "ok":
        return np.nan
    _, e, x, _ = lg
    q = r.qty
    ef, xf = float(FILL.buy(e)), float(FILL.sell(x))
    bse = und in K.BSE
    sp = K.HALF_SPREAD[und] * (e + x) * q * mult
    return float((xf - ef) * q - COST.charge(True, ef, q, None, bse) - COST.charge(False, xf, q, None, bse) - sp)


def prem(r, side: int) -> float:
    lg = r.legs.get((side, 1)) if isinstance(r.legs, dict) else None
    return float(FILL.buy(lg[1]) * r.qty) if lg is not None and lg[3] == "ok" else np.nan


def priced(df: pd.DataFrame) -> np.ndarray:
    """Both 1-ITM legs priced (so the random-side twin is exact)."""
    return (df.net_c1.notna() & df.net_p1.notna()).to_numpy()


def side_net(df: pd.DataFrame, side: np.ndarray, col: str = "1") -> np.ndarray:
    return np.where(side > 0, df[f"net_c{col}"], np.where(side < 0, df[f"net_p{col}"], np.nan))


# ================================================================================================ metrics
def max_dd(x) -> float:
    x = np.asarray(x, float)
    if not len(x):
        return 0.0
    c = np.concatenate([[0.0], np.cumsum(x)])
    return float((c - np.maximum.accumulate(c)).min())


def metrics(dates, x, prem_=None) -> dict:
    x = np.asarray(x, float)
    if not len(x):
        return {"trades": 0}
    mon = pd.Series(x, index=[f"{d.year}-{d.month:02d}" for d in dates]).groupby(level=0).sum()
    yr = pd.Series(x, index=[d.year for d in dates]).groupby(level=0).sum()
    out = {"trades": int(len(x)), "net_per_trade": float(x.mean()), "win_rate": float((x > 0).mean()),
           "total": float(x.sum()), "worst_month": float(mon.min()), "worst_month_id": str(mon.idxmin()),
           "months_positive": int((mon > 0).sum()), "months": int(len(mon)), "max_dd": max_dd(x),
           "worst_trade": float(x.min()), "by_year": {int(k): float(v) for k, v in yr.items()}}
    if prem_ is not None:
        out["share_loss_gt_30pct_premium"] = float((x < -0.3 * np.asarray(prem_, float)).mean())
    return out


def twin_side_p(c: np.ndarray, p: np.ndarray, real_total: float, rng, B: int = K.TWINS) -> float:
    if not len(c):
        return np.nan
    tot = np.where(rng.random((B, len(c))) < 0.5, c, p).sum(axis=1)
    return float((1 + int((tot >= real_total - 1e-9).sum())) / (1 + B))


def skip_twin_p(nets: np.ndarray, skip: np.ndarray, rng, B: int = K.TWINS) -> float:
    """Random skips of the same number of trades: p = share of twins whose difference (kept - all) >= the real one."""
    k = int(skip.sum())
    if k == 0 or k == len(nets):
        return np.nan
    real = -float(nets[skip].sum())
    sims = np.array([-nets[rng.choice(len(nets), k, replace=False)].sum() for _ in range(B)])
    return float((1 + int((sims >= real - 1e-9).sum())) / (1 + B))


def signflip_p(diff: np.ndarray, rng, B: int = K.TWINS) -> float:
    if not len(diff):
        return np.nan
    real = float(diff.sum())
    sims = (np.where(rng.random((B, len(diff))) < 0.5, 1, -1) * diff).sum(axis=1)
    return float((1 + int((sims >= real - 1e-9).sum())) / (1 + B))


def bh(p) -> list:
    p = np.asarray(p, float)
    ok = np.flatnonzero(np.isfinite(p))
    q = np.full(len(p), np.nan)
    if not ok.size:
        return q.tolist()
    pv = p[ok]
    o = np.argsort(pv)
    run, qq = 1.0, np.empty(len(pv))
    for i in range(len(pv) - 1, -1, -1):
        run = min(run, pv[o[i]] * len(pv) / (i + 1))
        qq[o[i]] = run
    q[ok] = qq
    return q.tolist()


# ================================================================================================ rules
def night_side(df: pd.DataFrame, rule: str = "r3") -> np.ndarray:
    s = df[rule].to_numpy(int)
    return np.where(df.tradable.to_numpy(bool), s, 0)


def filter_skip(df: pd.DataFrame, side: np.ndarray, f: str) -> np.ndarray:
    """True where filter f skips a Night trade (only where side != 0)."""
    s = side
    if f == "F1":
        m = df.wkend.to_numpy(bool)
    elif f == "F2":
        m = (df.us_ev | df.in_ev).to_numpy(bool)
    elif f == "F3":
        m = (df.move.abs() >= K.F3_MOVE).to_numpy(bool)
    elif f == "F4":
        m = (s * df.l60.fillna(0).to_numpy()) < 0
    elif f == "F5":
        m = (s * df.hsi.fillna(0).to_numpy()) < 0
    elif f == "F6":
        m = (df.vix >= K.F6_VIX).fillna(False).to_numpy(bool)
    elif f == "F7":
        loc = df["loc"].to_numpy(float)
        m = ((s > 0) & (loc < K.F7_UP)) | ((s < 0) & (loc > K.F7_DOWN))
    elif f == "F8":
        m = (df.strad_ratio >= K.F8_RATIO).fillna(False).to_numpy(bool)
    else:
        raise ValueError(f)
    return m & (s != 0)


def gap_class(g: float) -> str:
    if not np.isfinite(g):
        return "n/a"
    if g <= -0.005:
        return "big down"
    if g <= -0.001:
        return "down"
    if g < 0.001:
        return "flat"
    if g < 0.005:
        return "up"
    return "big up"


# ================================================================================================ blocks
def index_block(df: pd.DataFrame, und: str, rule: str, rng) -> dict:
    side = night_side(df, rule)
    m = (side != 0) & priced(df)
    x = side_net(df, side)[m]
    st = side_net(df, side, "1_stress")[m]
    pr = np.where(side > 0, df.prem_c1, df.prem_p1)[m]
    dts = df.d.to_numpy()[m]
    rec = {"und": und, "rule": rule, "sessions": int(len(df)), "signals": int((side != 0).sum()),
           "unpriced_signals": int(((side != 0) & ~priced(df)).sum()), **metrics(dts, x, pr),
           "stress_net_per_trade": float(st.mean()) if len(st) else None,
           "calls": int((side[m] > 0).sum())}
    rec["twin_p"] = twin_side_p(df.net_c1.to_numpy()[m], df.net_p1.to_numpy()[m], float(x.sum()), rng)
    return rec


def filter_block(dfs: dict, f: str, rng) -> dict:
    """Paired: Night (r3) on NIFTY + BANKNIFTY pooled, with and without the skip; also per index for information."""
    nets, skips, dts = [], [], []
    per = {}
    for und, df in dfs.items():
        side = night_side(df, "r3")
        m = (side != 0) & priced(df)
        x = side_net(df, side)[m]
        sk = filter_skip(df, side, f)[m]
        per[und] = {"trades": int(m.sum()), "skipped": int(sk.sum()), "diff": float(-x[sk].sum()),
                    "skipped_net_per_trade": float(x[sk].mean()) if sk.any() else None}
        if und in K.BASE_UNDS:
            nets.append(x)
            skips.append(sk)
            dts.append(df.d.to_numpy()[m])
    x, sk, dt = np.concatenate(nets), np.concatenate(skips), np.concatenate(dts)
    o = np.argsort(dt, kind="stable")
    x, sk, dt = x[o], sk[o], dt[o]
    yr = pd.Series(np.where(sk, -x, 0.0), index=[d.year for d in dt]).groupby(level=0).sum()
    return {"id": f, "rule": FILTER_TXT[f], "trades": int(len(x)), "skipped": int(sk.sum()),
            "diff_total": float(-x[sk].sum()), "diff_by_year": {int(k): float(v) for k, v in yr.items()},
            "skipped_net_per_trade": float(x[sk].mean()) if sk.any() else None,
            "max_dd_base": max_dd(x), "max_dd_filtered": max_dd(x[~sk]), "p": skip_twin_p(x, sk, rng),
            "per_index": per}


def contract_block(dfs: dict, itm: int, rng) -> dict:
    diffs, dts, base, alt = [], [], [], []
    for und in K.BASE_UNDS:
        df = dfs[und]
        side = night_side(df, "r3")
        a = side_net(df, side, str(itm))
        b = side_net(df, side)
        m = (side != 0) & np.isfinite(a) & np.isfinite(b) & priced(df)
        diffs.append(a[m] - b[m])
        dts.append(df.d.to_numpy()[m])
        base.append(b[m])
        alt.append(a[m])
    d, dt, b, a = (np.concatenate(v) for v in (diffs, dts, base, alt))
    o = np.argsort(dt, kind="stable")
    d, dt, b, a = d[o], dt[o], b[o], a[o]
    yr = pd.Series(d, index=[x.year for x in dt]).groupby(level=0).sum()
    return {"id": f"K{1 if itm == 0 else 2}", "rule": "ATM instead of 1-ITM" if itm == 0 else "2-ITM instead of 1-ITM",
            "trades": int(len(d)), "diff_total": float(d.sum()), "diff_per_trade": float(d.mean()) if len(d) else None,
            "diff_by_year": {int(k): float(v) for k, v in yr.items()}, "max_dd_base": max_dd(b), "max_dd_alt": max_dd(a),
            "p": signflip_p(d, rng)}


def atlas(df: pd.DataFrame, und: str) -> dict:
    """Descriptive: yesterday's profile by gap class; Night's catches by gap class; Night's winners vs losers."""
    t = df.copy()
    t["cls"] = [gap_class(g) for g in t.g]
    prof = {}
    for c in GAP_CLASSES:
        s = t[t.cls == c]
        prof[c] = {"n": int(len(s)), **{k: float(pd.to_numeric(s[k], errors="coerce").mean()) for k in PROFILE}}
    side = night_side(t, "r3")
    catch = {}
    for c in GAP_CLASSES:
        msk = (t.cls == c).to_numpy()
        sg = np.sign(t.g.to_numpy())
        catch[c] = {"nights": int(msk.sum()), "night_right_side": int(((side * sg) > 0)[msk].sum()),
                    "night_wrong_side": int(((side * sg) < 0)[msk].sum()), "night_no_trade": int((side == 0)[msk].sum())}
    m = (side != 0) & priced(t)
    x = side_net(t, side)
    win, lose = t[m & (x > 0)], t[m & (x <= 0)]
    wl = {k: {"win": float(pd.to_numeric(win[k], errors="coerce").mean()) if len(win) else None,
              "lose": float(pd.to_numeric(lose[k], errors="coerce").mean()) if len(lose) else None} for k in PROFILE + ["g"]}
    sx = side[m]
    wl["side_signed_gap_bps"] = {"win": float((np.sign(sx) * t.g.to_numpy()[m])[x[m] > 0].mean() * 1e4) if (x[m] > 0).any() else None,
                                 "lose": float((np.sign(sx) * t.g.to_numpy()[m])[x[m] <= 0].mean() * 1e4) if (x[m] <= 0).any() else None}
    wl["n"] = {"win": int(len(win)), "lose": int(len(lose))}
    return {"und": und, "profile_by_gap_class": prof, "night_catches": catch, "night_win_vs_lose": wl}


def write_atlas_csv(df: pd.DataFrame, und: str, part: str) -> None:
    cols = ["d", "nd", "part", "x", "prev_close", "move", "loc", "l30", "l60", "gap0", "rng", "breadth", "buz", "vix",
            "dvix", "hsi", "ov_spx", "wkend", "us_ev", "in_ev", "exp_today", "s17", "r3", "tradable", "why", "g", "g29"]
    t = df[cols].copy()
    t["gap_class"] = [gap_class(g) for g in t.g]
    t["night_side"] = night_side(df, "r3")
    p = K.RESULTS / f"atlas_{und}.csv"
    t.round(6).to_csv(p, index=False, mode="a" if (part == "hold" and p.exists()) else "w",
                      header=not (part == "hold" and p.exists()))


# ================================================================================================ stages
def run(part: str) -> dict:
    rng = np.random.default_rng(K.SEED + (0 if part == "design" else 1))
    dfs = {u: nights(u, part) for u in K.UNDS}
    res = {"prereg_sha256": prereg_sha(), "part": part, "run": datetime.now().isoformat(timespec="seconds"),
           "sessions": {u: int(len(df)) for u, df in dfs.items()}, "night": [], "new_index": [], "filters": [],
           "contracts": [], "atlas": {}}
    for u in K.UNDS:
        res["night"].append(index_block(dfs[u], u, "r3", rng))
        res["night"].append(index_block(dfs[u], u, "s17", rng))
        res["atlas"][u] = atlas(dfs[u], u)
        write_atlas_csv(dfs[u], u, part)
    tests = [r for r in res["night"] if r["und"] in K.NEW_UNDS]
    res["new_index"] = [f"{r['und']} {r['rule']}" for r in tests]
    res["filters"] = [filter_block(dfs, f, rng) for f in FILTERS]
    res["contracts"] = [contract_block(dfs, 0, rng), contract_block(dfs, 2, rng)]
    gate(res, part)
    return res


def gate(res: dict, part: str) -> None:
    tests = [r for r in res["night"] if r["und"] in K.NEW_UNDS]
    if part == "design":
        for r, q in zip(tests, bh([r.get("twin_p", np.nan) for r in tests])):
            r["bh_q"] = q
        fk = res["filters"] + res["contracts"]
        for r, q in zip(fk, bh([r["p"] for r in fk])):
            r["bh_q"] = q
    for r in tests:
        n = r.get("trades", 0)
        c = {"trades_ok": n >= (60 if part == "design" else 30), "net_pos_stress": n > 0 and (r["stress_net_per_trade"] or -1) > 0,
             "twin_ok": bool(np.isfinite(r.get("twin_p", np.nan)) and r["twin_p"] <= 0.05)}
        if part == "design":
            c["years_pos_2of3"] = sum(1 for y in K.DESIGN_YEARS if r.get("by_year", {}).get(y, 0) > 0) >= 2
            c["bh_ok"] = bool(np.isfinite(r.get("bh_q", np.nan)) and r["bh_q"] <= 0.10)
        r["gate"] = c
        r["pass"] = all(c.values())
    for r in res["filters"] + res["contracts"]:
        c = {"diff_pos": r["diff_total"] > 0, "p_ok": bool(np.isfinite(r["p"]) and r["p"] <= 0.05)}
        if part == "design":
            c["years_pos_2of3"] = sum(1 for y in K.DESIGN_YEARS if r["diff_by_year"].get(y, 0) > 0) >= 2
            c["bh_ok"] = bool(np.isfinite(r.get("bh_q", np.nan)) and r["bh_q"] <= 0.10)
            dd_alt = r.get("max_dd_filtered", r.get("max_dd_alt"))
            c["dd_not_worse"] = dd_alt >= r["max_dd_base"]
        r["gate"] = c
        r["pass"] = all(c.values())


def _dump(obj, path) -> None:
    def conv(o):
        if isinstance(o, np.integer):
            return int(o)
        if isinstance(o, (np.floating, float)):
            return None if not np.isfinite(o) else float(o)
        if isinstance(o, np.bool_):
            return bool(o)
        return str(o)
    path.write_text(json.dumps(obj, indent=1, default=conv).replace("NaN", "null"))


def design() -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    _dump(run("design"), K.RESULTS / "design.json")


def holdout() -> None:
    if K.FLAG.exists():
        sys.exit("holdout already read (results/HOLDOUT_READ.flag)")
    d = json.loads((K.RESULTS / "design.json").read_text())
    K.FLAG.write_text(f"read {datetime.now().isoformat(timespec='seconds')} prereg {prereg_sha()}\n")
    res = run("hold")
    res["design_passers"] = [f"{r['und']} {r['rule']}" for r in d["night"] if r.get("pass")] + \
        [r["id"] for r in d["filters"] + d["contracts"] if r.get("pass")]
    _dump(res, K.RESULTS / "holdout.json")


if __name__ == "__main__":
    {"design": design, "holdout": holdout}[sys.argv[1]]()
