"""R146 study driver (PREREG.md). Run from the repo root:

    python -m research.hunt.r146_gap_btst.mdata                # minute stores from the release files
    python -m research.hunt.r146_gap_btst.yahoo fetch          # daily bars
    python -m research.hunt.r146_gap_btst.study build          # nights tables (features, targets, priced legs)
    python -m research.hunt.r146_gap_btst.study design         # design walk-forward: results/design.json
    python -m research.hunt.r146_gap_btst.study holdout        # the ONE holdout read: results/holdout.json + flag
    python -m research.hunt.r146_gap_btst.study report         # results/report.md

`design` never sees a night on or after the holdout start (rows are cut before any model or statistic). `holdout`
refuses to run twice (results/HOLDOUT_READ.flag).
"""
from __future__ import annotations

import hashlib
import json
import pickle
import sys
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

from . import cfg as K
from . import features as F
from . import mdata
from . import models as M
from . import trade as T
from . import yahoo

EVENTS = K.ROOT / "research" / "hunt" / "r98" / "events.csv"
STRATS = ("T0", "T1", "T2", "T3", "T4", "T5")
STRAT_NAME = {"T0": "Night (R3) proxy", "T1": "always the call", "T2": "strong close (R17)", "T3": "logistic",
              "T4": "gradient trees", "T5": "last 30 minutes"}


def prereg_sha() -> str:
    return hashlib.sha256(K.PREREG.read_bytes()).hexdigest()


# ================================================================================================ build
def events() -> tuple[set, set]:
    ev = pd.read_csv(EVENTS)
    ev["session"] = pd.to_datetime(ev["session"]).dt.date
    us = set(ev.loc[ev.category.isin(["FOMC", "USCPI"]), "session"])
    ind = set(ev.loc[ev.category.isin(["RBI", "BUDGET", "ELECTION"]), "session"])
    return us, ind


def _daily(name: str) -> pd.DataFrame | None:
    try:
        df = yahoo.load(name)
    except FileNotFoundError:
        return None
    return df[df.index <= K.D_END]


def _valid_map(m: dict) -> dict:
    return {d: a for d, a in m.items() if F.valid_day(a)}


def build_m(und: str, ixs: dict, asia: dict, spx, us_ev: set, in_ev: set) -> tuple[pd.DataFrame, dict]:
    days = mdata.load(und)
    idx = _valid_map({d: dy.idx for d, dy in days.items()})
    if und == "NIFTY":                                     # extra_series NIFTY starts 2 months earlier
        for d, a in _valid_map(ixs["NIFTY_X"]).items():
            idx.setdefault(d, a)
    nifty = dict(idx) if und == "NIFTY" else _valid_map(ixs["NIFTY_X"])
    others_src = {"NIFTY": nifty, "FINNIFTY": _valid_map(ixs["FINNIFTY"]), "SENSEX": _valid_map(ixs["SENSEX"])}
    if und != "BANKNIFTY":
        others_src["BANKNIFTY"] = _valid_map({d: dy.idx for d, dy in mdata.load("BANKNIFTY").items()})
    vix = _valid_map(ixs["INDIAVIX"])
    expiries = set(ixs["EXPIRIES"][und])
    sessions = sorted(idx)
    prevs = {u: sorted(v) for u, v in others_src.items()}
    vprev = sorted(vix)

    def prev_close(src: dict, keys: list, d: date) -> float:
        i = int(np.searchsorted(np.array(keys, dtype=object), d)) - 1
        return F.last_close(src[keys[i]]) if i >= 0 else np.nan

    rows, legs, drops = [], {}, {}
    bu_hist: list = []
    for i in range(1, len(sessions) - 1):
        d, nd, pd_ = sessions[i], sessions[i + 1], sessions[i - 1]
        dy = days.get(d)
        if dy is None or not dy.opt:
            continue
        a = idx[d]
        others = {u: (src.get(d), prev_close(src, prevs[u], d)) for u, src in others_src.items()}
        f = F.m_features(und, d, nd, a, F.last_close(idx[pd_]), dy.opt, others, vix.get(d),
                         prev_close(vix, vprev, d), bu_hist, sessions, expiries, us_ev, in_ev, asia, spx)
        bu_hist.append(f["m_bu"])
        if d > K.M_LAST_ENTRY:
            continue
        f.update(F.m_targets(a, idx[nd], f["x"]))
        f.update({"und": und, "d": d, "nd": nd, "ov_spx": F.day_ret(spx, d)})
        ndy = days.get(nd)
        why = T.tradable(d, nd, dy.expiry, expiries)
        if not why and (ndy is None or ndy.expiry != dy.expiry):
            why = "next_day_other_series"
        if not why and not np.isfinite(f["x"]):
            why = "no_1519_price"
        if not why:
            pair = {}
            for side in (1, -1):
                lg, w = T.leg(und, d, side, f["x"], dy.opt, ndy.opt)
                if lg is None:
                    why = w
                    break
                pair[side] = lg
            if not why:
                legs[d] = pair
        f["tradable"] = not why
        f["why"] = why or "ok"
        drops[why or "ok"] = drops.get(why or "ok", 0) + 1
        rows.append(f)
    df = pd.DataFrame(rows)
    for side, nm in ((1, "c"), (-1, "p")):
        for xid in T.EXITS:
            df[f"net_{nm}_{xid}"] = [T.net(legs[d][side], xid) if d in legs else np.nan for d in df.d]
        df[f"net_{nm}_X16_stress"] = [T.net(legs[d][side], "X16", K.STRESS) if d in legs else np.nan for d in df.d]
        df[f"prem_{nm}"] = [legs[d][side].ef * legs[d][side].qty if d in legs else np.nan for d in df.d]
        df[f"why_{nm}_S30"] = [legs[d][side].why["S30"] if d in legs else "" for d in df.d]
        df[f"late_{nm}_X16"] = [legs[d][side].why["X16"] != "X16" if d in legs else False for d in df.d]
    return df, drops


def build() -> None:
    K.OUT.mkdir(parents=True, exist_ok=True)
    ixs = mdata.load_index()
    asia = {k: _daily(k) for k in ("N225", "HSI", "KS11")}
    spx, fx, vixd = _daily("GSPC"), _daily("USDINR"), _daily("INDIAVIX")
    us_ev, in_ev = events()
    info = {"prereg_sha256": prereg_sha(), "built": datetime.now().isoformat(timespec="seconds"), "M": {}, "D": {}}
    for und in K.M_UNDS:
        df, drops = build_m(und, ixs, asia, spx, us_ev, in_ev)
        df.to_pickle(K.OUT / f"nights_M_{und}.pkl")
        info["M"][und] = {"nights": int(len(df)), "first": str(df.d.min()), "last": str(df.d.max()),
                          "tradable": int(df.tradable.sum()), "reasons": drops,
                          "design_nights": int((df.d < K.HOLD_START).sum()),
                          "holdout_nights": int((df.d >= K.HOLD_START).sum())}
        print(und, info["M"][und], flush=True)
    for und in K.D_UNDS:
        raw = _daily(und)
        if raw is None:
            info["D"][und] = {"missing": "Yahoo has no daily history for this symbol (NOTES A1)"}
            continue
        ix, chk = F.clean_daily(raw)
        df = F.d_frame(ix, vixd, asia, spx, fx)
        df = df[df.d >= K.D_START]
        df.to_pickle(K.OUT / f"nights_D_{und}.pkl")
        info["D"][und] = {"rows": int(len(df)), "first": str(df.d.min()), "last": str(df.d.max()), **chk,
                          "design_rows": int((df.d < K.HOLD_START).sum()), "holdout_rows": int((df.d >= K.HOLD_START).sum())}
        print(und, {k: v for k, v in info["D"][und].items() if k != "stale_share_by_year"}, flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "build.json").write_text(json.dumps(info, indent=1, default=str))


# ================================================================================================ evaluation pieces
def load_m(und: str) -> pd.DataFrame:
    return pd.read_pickle(K.OUT / f"nights_M_{und}.pkl")


def load_d(und: str) -> pd.DataFrame | None:
    p = K.OUT / f"nights_D_{und}.pkl"
    return pd.read_pickle(p) if p.exists() else None


def cut(df: pd.DataFrame, part: str) -> pd.DataFrame:
    """The ONLY place rows are split: design = entry before the holdout start; holdout = on or after it."""
    return df[df.d < K.HOLD_START] if part == "design" else df[df.d >= K.HOLD_START]


def m_rule_sides(t: pd.DataFrame) -> dict:
    return {"R_mom": M.r_mom(t.m_move), "R_loc": M.r_loc(t.m_loc), "R_l30": M.r_l30(t.m_l30),
            "R_r17": M.r_r17(t.m_loc, t.m_move, t.m_brd), "R_r3p": M.r_r3p(t.m_loc, t.m_move, t.m_brd, t.m_buz),
            "R_asia": M.r_asia(t.m_hsi)}


def d_rule_sides(t: pd.DataFrame) -> dict:
    return {"R_mom": M.r_mom(t.d_ret), "R_loc": M.r_loc(t.d_loc), "R_asia": M.r_asia(t.d_hsi)}


def predict_block(df: pd.DataFrame, cols: list, kind: str, first: date, last: date, rules) -> pd.DataFrame:
    wf = M.walk_forward(df, cols, kind, first, last)
    t = df.merge(wf, on="d", how="inner").sort_values("d").reset_index(drop=True)
    for k, v in rules(t).items():
        t[k] = v
    return t


def prediction_rows(t: pd.DataFrame, part_name: str, und: str, ideas: list, rng) -> list:
    out = []
    p0 = t.p0_side.to_numpy(int)
    g = t.g.to_numpy(float)
    for idea in ideas:
        if idea in ("LR", "GB"):
            s = M.auc_stats(t[f"p_{idea.lower()}"].to_numpy(), g, p0, rng)
        else:
            s = M.hit_stats(t[idea].to_numpy(int), g, p0, rng)
        out.append({"part": part_name, "und": und, "idea": idea, **s})
    return out


def size_stats(t: pd.DataFrame) -> dict:
    t = t[t.g.notna()]                       # NOTES A4: a night without a next open (fixed after the holdout read)
    g = t.g.to_numpy(float)
    a = np.abs(g)
    res = {}
    for k in ("g_ridge", "g_gb"):
        p = t[k].to_numpy(float)
        res[k] = {"r2_vs_train_mean": float(1 - ((g - p) ** 2).sum() / ((g - t.gm_train) ** 2).sum()),
                  "corr": float(np.corrcoef(p, g)[0, 1])}
    for k in ("a_ridge", "a_gb"):
        p = t[k].to_numpy(float)
        res[k] = {"r2_vs_train_mean": float(1 - ((a - p) ** 2).sum() / ((a - t.am_train) ** 2).sum()),
                  "corr": float(np.corrcoef(p, a)[0, 1])}
    return res


def gap_facts(t: pd.DataFrame) -> dict:
    g = t.g.dropna().to_numpy()
    return {"n": int(len(g)), "mean_bps": float(g.mean() * 1e4), "sd_bps": float(g.std(ddof=1) * 1e4),
            "share_up": float((g > 0).mean()), "share_abs_ge_0p25pct": float((np.abs(g) >= 0.0025).mean()),
            "share_abs_ge_0p5pct": float((np.abs(g) >= 0.005).mean())}


# ---------------------------------------------------------------------------------------------------- trading
def strat_sides(t: pd.DataFrame) -> dict:
    return {"T0": t.R_r3p.to_numpy(int), "T1": np.ones(len(t), int), "T2": t.R_r17.to_numpy(int),
            "T3": M.side_from_p(t.p_lr, K.P_EDGE), "T4": M.side_from_p(t.p_gb, K.P_EDGE),
            "T5": M.r_l30(t.m_l30, K.L30_MIN)}


def pick(t: pd.DataFrame, side: np.ndarray, col: str) -> np.ndarray:
    """Per-night net of the chosen side for exit column suffix `col` (NaN where no trade)."""
    c, p = t[f"net_c_{col}"].to_numpy(float), t[f"net_p_{col}"].to_numpy(float)
    return np.where(side > 0, c, np.where(side < 0, p, np.nan))


def max_dd(x: np.ndarray) -> float:
    if not len(x):
        return 0.0
    c = np.concatenate([[0.0], np.cumsum(x)])
    return float((c - np.maximum.accumulate(c)).min())


def trade_metrics(t: pd.DataFrame, side: np.ndarray, col: str, sessions: int) -> dict:
    m = (side != 0) & t.tradable.to_numpy(bool)
    if not m.any():
        return {"trades": 0}
    x = pick(t, side, col)[m]
    prem = np.where(side > 0, t.prem_c, t.prem_p)[m]
    dts = t.d.to_numpy()[m]
    mon = pd.Series(x, index=[f"{d.year}-{d.month:02d}" for d in dts]).groupby(level=0).sum()
    return {"trades": int(m.sum()), "calls": int((side[m] > 0).sum()), "net_per_trade": float(x.mean()),
            "win_rate": float((x > 0).mean()), "total": float(x.sum()), "per_session": float(x.sum() / sessions),
            "worst_month": float(mon.min()), "worst_month_id": str(mon.idxmin()), "months_positive": int((mon > 0).sum()),
            "months": int(len(mon)), "max_dd": max_dd(x), "worst_trade": float(x.min()), "best_trade": float(x.max()),
            "share_loss_gt_30pct_premium": float((x < -0.3 * prem).mean())}


def twin_p(t: pd.DataFrame, side: np.ndarray, real_total: float, rng, B: int = K.TWINS) -> float:
    m = (side != 0) & t.tradable.to_numpy(bool)
    c, p = t.net_c_X16.to_numpy(float)[m], t.net_p_X16.to_numpy(float)[m]
    if not len(c):
        return np.nan
    flips = rng.random((B, len(c))) < 0.5
    tot = np.where(flips, c, p).sum(axis=1)
    return float((1 + int((tot >= real_total - 1e-9).sum())) / (1 + B))


def trading_block(t: pd.DataFrame, und: str, part_name: str, rng) -> list:
    sides = strat_sides(t)
    sessions = len(t)
    out = []
    for sid in STRATS:
        s = sides[sid]
        rec = {"part": part_name, "und": und, "strategy": sid, "name": STRAT_NAME[sid], "sessions": sessions}
        for xid in T.EXITS:
            rec[xid] = trade_metrics(t, s, xid, sessions)
        rec["X16_stress"] = trade_metrics(t, s, "X16_stress", sessions)
        rec["twin_p"] = twin_p(t, s, rec["X16"].get("total", 0.0), rng) if rec["X16"].get("trades") else np.nan
        sw = np.where(s > 0, t.why_c_S30, t.why_p_S30)[(s != 0) & t.tradable.to_numpy(bool)]
        rec["S30_reasons"] = pd.Series(sw).value_counts().to_dict()
        out.append(rec)
    return out


def trade_gate(recs: list, design: bool) -> None:
    """PREREG 6 gate (design or holdout), in place."""
    by = {(r["und"], r["strategy"]): r for r in recs}
    tests = [r for r in recs if r["strategy"] != "T0"]
    if design:
        q = M.bh([r["twin_p"] for r in tests])
        for r, qq in zip(tests, q):
            r["bh_q"] = qq
    for r in tests:
        x, st, base = r["X16"], r["X16_stress"], by[(r["und"], "T0")]["X16"]
        n = x.get("trades", 0)
        conds = {"trades_ok": n >= 30, "net_pos": n > 0 and x["net_per_trade"] > 0,
                 "beats_night_per_session": n > 0 and x["per_session"] >= base.get("per_session", 0.0),
                 "twin_p_ok": bool(np.isfinite(r["twin_p"]) and r["twin_p"] <= 0.05)}
        if design:
            conds["stress_pos"] = n > 0 and st["net_per_trade"] > 0
            conds["bh_ok"] = bool(np.isfinite(r.get("bh_q", np.nan)) and r["bh_q"] <= 0.10)
        r["gate"] = conds
        r["pass"] = all(conds.values())


def pred_gate(rows: list, design: bool) -> None:
    if design:
        q = M.bh([r.get("p_perm", np.nan) for r in rows])
        for r, qq in zip(rows, q):
            r["bh_q"] = qq
    for r in rows:
        ok = r.get("n", 0) >= (100 if design else 30) and r.get("hit", 0) > r.get("hit_p0", 1) and \
            r.get("p_perm", 1) <= 0.05
        if design:
            ok = ok and np.isfinite(r.get("bh_q", np.nan)) and r["bh_q"] <= 0.10
        r["pass"] = bool(ok)


def straddle_overnight(t: pd.DataFrame) -> dict:
    """Both legs bought at 15:20 and sold 09:16: the overnight cost of carrying options regardless of direction."""
    m = t.tradable.to_numpy(bool)
    s = (t.net_c_X16 + t.net_p_X16).to_numpy(float)[m]
    prem = (t.prem_c + t.prem_p).to_numpy(float)[m]
    return {"nights": int(m.sum()), "mean_net_both_legs": float(s.mean()) if m.any() else np.nan,
            "mean_net_pct_of_premium": float((s / prem).mean() * 100) if m.any() else np.nan}


# ================================================================================================ stages
def run_part(part_name: str) -> dict:
    rng = np.random.default_rng(K.SEED + (0 if part_name == "design" else 1))
    res = {"prereg_sha256": prereg_sha(), "part": part_name, "run": datetime.now().isoformat(timespec="seconds"),
           "prediction": [], "size": {}, "decomposition": {}, "gap_facts": {}, "trading": [], "overnight_carry": {}}
    m_ideas = ["R_mom", "R_loc", "R_l30", "R_r17", "R_r3p", "R_asia", "LR", "GB"]
    d_ideas = ["R_mom", "R_loc", "R_asia", "LR", "GB"]
    # ---- D (daily, long)
    for und in K.D_UNDS:
        df = load_d(und)
        if df is None:
            continue
        if part_name == "design":
            src, first, last = cut(df, "design"), date(K.D_FIRST_TEST_YEAR, 1, 1), K.HOLD_START - timedelta(days=1)
        else:
            src, first, last = df, K.HOLD_START, K.D_END
        t = predict_block(src, F.D_COLS, "D", first, last, d_rule_sides)
        t = t[(t.d >= first) & (t.d <= last)]
        res["prediction"] += prediction_rows(t, part_name, f"D:{und}", d_ideas, rng)
        res["size"][f"D:{und}"] = size_stats(t)
        res["gap_facts"][f"D:{und}"] = gap_facts(t)
        res["decomposition"][f"D:{und}"] = {
            "known_at_close": M.wf_ridge_r2(src, F.D_COLS, "D", first, last),
            "overnight_spx_only": M.wf_ridge_r2(src, ["ov_spx"], "D", first, last),
            "known_plus_overnight": M.wf_ridge_r2(src, F.D_COLS + ["ov_spx"], "D", first, last),
            "corr_gap_ov_spx": float(t[["g", "ov_spx"]].corr().iloc[0, 1])}
        print(part_name, und, "D done", len(t), flush=True)
    # ---- M (minute, 15:19 truth) + trading
    for und in K.M_UNDS:
        df = load_m(und)
        if part_name == "design":
            src, first, last = cut(df, "design"), K.M_FIRST_TEST, K.HOLD_START - timedelta(days=1)
        else:
            src, first, last = df, K.HOLD_START, K.M_LAST_ENTRY
        t = predict_block(src, F.M_COLS, "M", first, last, m_rule_sides)
        t = t[(t.d >= first) & (t.d <= last)].reset_index(drop=True)
        res["prediction"] += prediction_rows(t, part_name, f"M:{und}", m_ideas, rng)
        res["size"][f"M:{und}"] = size_stats(t)
        res["gap_facts"][f"M:{und}"] = gap_facts(t)
        res["decomposition"][f"M:{und}"] = {
            "known_at_1519": M.wf_ridge_r2(src, F.M_COLS, "M", first, last),
            "overnight_spx_only": M.wf_ridge_r2(src, ["ov_spx"], "M", first, last),
            "known_plus_overnight": M.wf_ridge_r2(src, F.M_COLS + ["ov_spx"], "M", first, last),
            "corr_gap_ov_spx": float(t[["g", "ov_spx"]].corr().iloc[0, 1]),
            "corr_g_vs_g29": float(t[["g", "g29"]].corr().iloc[0, 1])}
        res["trading"] += trading_block(t, und, part_name, rng)
        res["overnight_carry"][und] = straddle_overnight(t)
        print(part_name, und, "M done", len(t), flush=True)
    pred_gate(res["prediction"], part_name == "design")
    trade_gate(res["trading"], part_name == "design")
    return res


def _dump(obj, path):
    def conv(o):
        if isinstance(o, (np.integer,)):
            return int(o)
        if isinstance(o, (np.floating,)):
            return None if not np.isfinite(o) else float(o)
        if isinstance(o, float) and not np.isfinite(o):
            return None
        return str(o)
    txt = json.dumps(obj, indent=1, default=conv, allow_nan=True).replace("NaN", "null")
    path.write_text(txt)


def design() -> None:
    res = run_part("design")
    _dump(res, K.RESULTS / "design.json")


def holdout() -> None:
    if K.FLAG.exists():
        sys.exit("holdout already read (results/HOLDOUT_READ.flag): a second read needs a new pre-registration")
    d = json.loads((K.RESULTS / "design.json").read_text())
    K.FLAG.write_text(f"read {datetime.now().isoformat(timespec='seconds')} prereg {prereg_sha()}\n")
    res = run_part("holdout")
    res["design_passers"] = {"prediction": [f"{r['und']} {r['idea']}" for r in d["prediction"] if r.get("pass")],
                             "trading": [f"{r['und']} {r['strategy']}" for r in d["trading"] if r.get("pass")]}
    _dump(res, K.RESULTS / "holdout.json")


# ================================================================================================ report
def _f(x, nd=0):
    return "-" if x is None else f"{x:,.{nd}f}"


def report() -> None:
    """results/report.md: every table from design.json and holdout.json (no computation)."""
    parts = {p: json.loads((K.RESULTS / f"{p}.json").read_text()) for p in ("design", "holdout")
             if (K.RESULTS / f"{p}.json").exists()}
    L = ["# R146 results (generated by `study.py report` from design.json / holdout.json)", "",
         f"PREREG sha256 `{parts['design']['prereg_sha256']}`.", ""]
    for p, r in parts.items():
        note = ("" if p == "design" else " - `pass` here is the holdout criterion only; nothing passed design, so "
                "it is information, not a pass")
        L += [f"## {p}: prediction (hit = share of picked nights whose gap had the predicted sign){note}", "",
              "| set:index | idea | nights | hit | P0 hit (same nights) | AUC | perm p | BH q (design) | pass |",
              "|---|---|---|---|---|---|---|---|---|"]
        for x in r["prediction"]:
            L.append(f"| {x['und']} | {x['idea']} | {x.get('n')} | {_f(x.get('hit'), 3)} | {_f(x.get('hit_p0'), 3)} | "
                     f"{_f(x.get('auc'), 3)} | {_f(x.get('p_perm'), 3)} | {_f(x.get('bh_q'), 3)} | {x.get('pass')} |")
        L += ["", f"## {p}: what is known at the close vs what arrives overnight (walk-forward ridge, OOS R2 vs the "
                  "training mean)", "", "| set:index | known at close | S&P overnight only | both | corr(gap, S&P overnight) |",
              "|---|---|---|---|---|"]
        for k, v in r["decomposition"].items():
            kn = v.get("known_at_close", v.get("known_at_1519"))
            L.append(f"| {k} | {_f(kn.get('r2_vs_train_mean'), 3)} | {_f(v['overnight_spx_only'].get('r2_vs_train_mean'), 3)} | "
                     f"{_f(v['known_plus_overnight'].get('r2_vs_train_mean'), 3)} | {_f(v['corr_gap_ov_spx'], 3)} |")
        L += ["", f"## {p}: gap facts", "", "| set:index | nights | mean bps | sd bps | share up | abs >= 0.25% | abs >= 0.5% |",
              "|---|---|---|---|---|---|---|"]
        for k, v in r["gap_facts"].items():
            L.append(f"| {k} | {v['n']} | {_f(v['mean_bps'], 1)} | {_f(v['sd_bps'], 1)} | {_f(v['share_up'], 3)} | "
                     f"{_f(v['share_abs_ge_0p25pct'], 3)} | {_f(v['share_abs_ge_0p5pct'], 3)} |")
        L += ["", f"## {p}: BTST trades, 1 lot, 1-ITM, bought 15:20, sold 09:16 (X16), after app fills, app charges "
                  "and the 0.16% half-spread", "",
              "| index | strategy | trades (calls) | net / trade | win | total | Rs / session | worst month | months + | "
              "max DD | worst trade | loss > 30% prem | 1.5x spread / trade | twin p | BH q | pass | X15 / X20 / X30 / S30 per trade |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for t in r["trading"]:
            x = t["X16"]
            if not x.get("trades"):
                continue
            ex = " / ".join(_f(t[k].get("net_per_trade")) for k in ("X15", "X20", "X30", "S30"))
            L.append(f"| {t['und']} | {t['strategy']} {t['name']} | {x['trades']} ({x['calls']}) | {_f(x['net_per_trade'])} | "
                     f"{_f(x['win_rate'] * 100)}% | {_f(x['total'])} | {_f(x['per_session'])} | {_f(x['worst_month'])} | "
                     f"{x['months_positive']}/{x['months']} | {_f(x['max_dd'])} | {_f(x['worst_trade'])} | "
                     f"{_f(x['share_loss_gt_30pct_premium'] * 100)}% | {_f(t['X16_stress']['net_per_trade'])} | "
                     f"{_f(t['twin_p'], 3)} | {_f(t.get('bh_q'), 2)} | {t.get('pass', 'baseline')} | {ex} |")
        L += ["", f"Overnight carry of BOTH legs (call + put bought 15:20, sold 09:16), {p}: " +
              "; ".join(f"{u} {v['nights']} nights, Rs {_f(v['mean_net_both_legs'])} a night "
                        f"({_f(v['mean_net_pct_of_premium'], 1)}% of premium)" for u, v in r["overnight_carry"].items()), ""]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    {"build": build, "design": design, "holdout": holdout, "report": report}[sys.argv[1]]()
