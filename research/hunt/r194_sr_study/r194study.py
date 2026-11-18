"""R194 study (PREREG.md): does support / resistance help R72, R51 and Liquidity 15+5 (1 Nov 2024 - 13 Nov 2026)?

    python3 -I research/hunt/r194_sr_study/r194study.py sr R72      # baselines (repro) + families A, C, D-TGT for R72
    python3 -I research/hunt/r194_sr_study/r194study.py sr R51
    python3 -I research/hunt/r194_sr_study/r194study.py bfit R72    # family B: reproduce R151's model, retrain without S/R
    python3 -I research/hunt/r194_sr_study/r194study.py bfit R51
    python3 -I research/hunt/r194_sr_study/r194study.py bspan R72   # family B on the span (after sr and bfit)
    python3 -I research/hunt/r194_sr_study/r194study.py bspan R51
    python3 -I research/hunt/r194_sr_study/r194study.py liq         # Liquidity: repro + room / new-level tests
    python3 -I research/hunt/r194_sr_study/r194study.py decide      # BH over all 24 + the gate -> results/decision.json

Reuses R193's stack unchanged (r193study: R186's engine, R179's pricing of the arms' own exits, R176's units and walk,
R152's picker module = R151's app numbers and feature code). Pure parts in r194lib.py. Plain-English notes, 18 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r194cfg as K  # noqa: E402

import r193study as R3  # noqa: E402  (sets R193's environment and imports the stack)
import numpy as np  # noqa: E402

import r193lib as G  # noqa: E402
import r194lib as L  # noqa: E402

S6, R7, R9, Z6 = R3.S6, R3.R7, R3.R9, R3.Z6


# ================================================================================================ plumbing
def boot(window=K.STORE) -> dict:
    M = R7.mods()
    k = M["r51data"].K
    k.WARM_START, k.START, k.END = window
    M["C"].open_final()
    return M


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def flag(name: str) -> None:
    """PREREG 9.5: a family / arm is read once. A crash before its results file may be re-run unchanged."""
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    f, res = K.RESULTS / f"READ_{name}", K.RESULTS / f"span_{name}.json"
    if f.exists() and res.exists():
        raise SystemExit(f"R194 {name} already read once: {f.read_text().strip()}")
    if not f.exists():
        f.write_text(now() + "\n")


def save(name: str, obj) -> None:
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def load(name: str):
    return pickle.loads((K.OUT / f"{name}.pkl").read_bytes())


def write(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def nets(trades) -> float:
    return float(sum(t["net"] for t in trades))


def picker_mods():
    P = S6.picker()                                    # r152picker: R151's study as P.T, pick.py as P.PK
    return P, P.T, P.PK


def nums_of(arm: str) -> dict:
    P, T, _ = picker_mods()
    nums = json.loads((P.K.R151 / "results" / K.MODEL_FILE[arm]).read_text())
    assert abs(nums["threshold"] - K.PICK_THR[arm]) < 1e-12 and nums["feats"] == T.FEATS
    return nums


def score_rows(nums: dict, keys: list, fx: dict, ablate: bool = False) -> dict:
    _, T, PK = picker_mods()
    feats = nums["feats"]
    out = {}
    for k in keys:
        row = [fx[k].get(f, np.nan) for f in feats]
        if ablate:
            row = L.ablate(nums, row)
        out[k] = PK.score_from_numbers(nums, np.array(row, float))
    return out


def keep_mask(walked: list, sc: dict, thr: float) -> np.ndarray:
    """The app's rule (r152picker.kept): taken unless scored below the threshold; unscored is taken."""
    _, T, _ = picker_mods()
    out = []
    for t in walked:
        s = sc.get(T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]))
        out.append(s is None or not (s < thr))
    return np.array(out, bool)


def ckey(t) -> tuple:
    _, T, _ = picker_mods()
    return T.cand_key(t["und"], t["day"], t["er_sig"], t["side"])


def windows(trades: list) -> dict:
    return G.by_windows(trades)


def row_of(trades: list, extra: dict | None = None) -> dict:
    d = dict(windows=windows(trades))
    if extra:
        d.update(extra)
    return d


# ================================================================================================ twins
def swap_twins(w: list, kv: np.ndarray, kb: np.ndarray, rng) -> tuple:
    """Picker vs picker (A, B) and filter vs base (C, n_add = 0): twin totals on the same walk."""
    x = np.array([t["net"] for t in w], float)
    n_drop = int((kb & ~kv).sum())
    n_add = int((kv & ~kb).sum())
    tot = np.empty(K.TWINS)
    for j in range(K.TWINS):
        tot[j] = float(x[L.swap_mask(kb, n_drop, n_add, rng)].sum())
    return tot, n_drop, n_add


def exit_twins(arm: str, trades: list, plain: dict, be_x: float, lock: str, rng) -> tuple:
    """R193's exit twin (PREREG 7): a trade the target closes >= 2 minutes before the baseline's exits would is sold at a
    random minute between its fill and the baseline's exit, with the baseline's exits (and its own index exit) active."""
    import r186engine as Z
    M = R7.mods()
    ex = S6.exits(arm)
    out = np.repeat(np.array([t["net"] for t in trades], float)[:, None], K.TWINS, axis=1)
    changed = 0
    for i, t in enumerate(trades):
        p = plain.get(R3.key_t(t))
        if p is None or not (t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2):
            continue
        changed += 1
        rows = M["S"].twin_rows(t["er"], p["xr"], K.TWINS, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        out[i] = Z.forced_nets(t["und"], D, t, ex, rows, 1, int(t.get("fx_arm", -1)), be_x=be_x, lock=lock)
    return out.sum(axis=0), changed


def twin_summary(stat: float, tw: np.ndarray, upper: bool = True, **extra) -> dict:
    p = L.p_upper(stat, tw) if upper else L.p_lower(stat, tw)
    return dict(p=round(p, 5), p_normal=L.normal_p(stat, tw, upper), stat=round(stat), tail="upper" if upper else "lower",
                mean=round(float(tw.mean())), sd=round(float(tw.std(ddof=1))), **extra)


# ================================================================================================ the S/R target (D)
def target_units(arm: str, usA: list, fx: dict | None = None) -> tuple:
    """usA with each candidate's 'fx' moved to the S/R target's row when that comes first (fx_arm, the arm's own index exit,
    kept). Returns (units, how many candidates got a target level, how many a target row)."""
    _, T, _ = picker_mods()
    M = R7.mods()
    E = M["E"]
    ex = S6.exits(arm)
    out, n_lvl, n_row = [], 0, 0
    bad = 0
    for key, cands, res, how in usA:
        und, d = key[0], key[1]
        D = M["r51data"].load(und)[d]
        x = T.B.lv(und)
        st = E.stack(und, D)
        sq = E.sq_row(D, ex)
        nc = []
        for c in cands:
            c2 = dict(c)
            er = int(c["er"])
            price = T._last(np.asarray(x.idx[d][3][:er], float))
            lvl = L.target_level(x.levels(d, er, 20), price, c["side"])
            if fx is not None:                         # the level must be the one behind R151's sr_ahead
                sa = fx.get(T.cand_key(und, d, er, c["side"]), {}).get("sr_ahead", np.nan)
                want = L.NO_LEVEL if lvl is None else abs(lvl / price - 1)
                if np.isfinite(sa) and abs(sa - want) > 1e-12:
                    bad += 1
            if lvl is not None:
                n_lvl += 1
                ci = st.idx(c["side"], c["strike"])
                ef = L_first_fill(st, ci, er, sq)
                tr = L.target_row(np.asarray(D.idx[1], float), np.asarray(D.idx[2], float), ef, c["side"], lvl, sq)
                if tr >= 0:
                    n_row += 1
                    c2["fx"] = L.earlier(int(c2.get("fx_arm", -1)), tr)
                    c2["tgt_level"] = float(lvl)
            nc.append(c2)
        out.append((key, nc, res, how))
    assert bad == 0, f"{bad} target levels differ from sr_ahead"
    return out, n_lvl, n_row


def L_first_fill(st, ci: int, er: int, sq: int) -> int:
    if ci < 0:
        return -1
    o = st.S[ci][0]
    for j in range(er, min(er + 3, sq)):
        if np.isfinite(o[j]) and o[j] > 0:
            return j
    return -1


# ================================================================================================ sr: baselines + A, C, D-TGT
def sr(arm: str) -> dict:
    flag(f"sr_{arm}")
    boot()
    lo, hi = K.SPAN
    rng = np.random.default_rng(K.SEED + (1 if arm == "R72" else 2))
    P, T, PK = picker_mods()
    nums = nums_of(arm)
    thr = K.PICK_THR[arm]
    us0, usA = R3.units(arm, lo, hi)
    print(arm, "units", len(us0), flush=True)
    fx = T.feats_of_units(us0)
    keys = list(fx)
    sc = score_rows(nums, keys, fx)
    sc_abl = score_rows(nums, keys, fx, ablate=True)
    print(arm, "features and scores", len(keys), flush=True)
    prA = R3.price(arm, usA, False, "", 1)
    w = R3.walk(arm, usA, prA, lo, hi)
    plainA = R3.lookup(usA, prA)
    print(arm, "A priced and walked", len(w), round(nets(w)), flush=True)
    prV = R3.price(arm, usA, True, K.LOCK, 1)
    wV = R3.walk(arm, usA, prV, lo, hi)
    plainV = R3.lookup(usA, prV)
    print(arm, "V5 priced and walked", len(wV), flush=True)
    save(f"sr_{arm}_base", dict(w=w, wV=wV, fx=fx, sc=sc, sc_abl=sc_abl))
    kp = keep_mask(w, sc, thr)
    ka = keep_mask(w, sc_abl, thr)
    kpV = keep_mask(wV, sc, thr)
    base = {"A": w, "P": [t for t, k in zip(w, kp) if k], "V5": [t for t, k in zip(wV, kpV) if k]}
    # ---- reproduction of R193's baselines (PREREG 3.1)
    pub = json.loads((K.R193 / "results" / "span.json").read_text())
    rep = {}
    for b, name in (("A", f"A:{arm}"), ("P", f"{arm}-P"), ("V5", f"{arm}-V5")):
        s = G.stats(base[b], lo, hi)
        p = pub[name]["windows"]["total"]
        rep[b] = dict(trades=s["trades"], net=s["net"], r193=[p["trades"], p["net"]], gap_trades=s["trades"] - p["trades"],
                      gap_net=s["net"] - p["net"], reproduced=s["trades"] == p["trades"] and abs(s["net"] - p["net"]) <= 1)
        print(arm, "repro", b, rep[b], flush=True)
    write(f"repro_{arm}.json", dict(written=now(), baselines=rep))
    out = {"_meta": dict(written=now(), arm=arm, twins=K.TWINS, seed=int(K.SEED), repro=rep)}
    for b in ("A", "P", "V5"):
        out[f"base:{b}"] = row_of(base[b])
    # ---- A: ablation
    pa = [t for t, k in zip(w, ka) if k]
    fl = {wn: dict(p_only=int(sum(1 for t, x, y in zip(w, kp, ka) if x and not y and lo_ <= t["day"] <= hi_)),
                   abl_only=int(sum(1 for t, x, y in zip(w, kp, ka) if y and not x and lo_ <= t["day"] <= hi_)))
          for wn, (lo_, hi_) in K.WINDOWS.items()}
    sc_flip = sum(1 for k in keys if (sc[k] < thr) != (sc_abl[k] < thr))
    tw, nd, na = swap_twins(w, kp, ka, rng)
    out["base:P-abl"] = row_of(pa)
    out[f"A-{arm}"] = row_of(base["P"], dict(twin=twin_summary(nets(base["P"]), tw, dropped=nd, added=na),
                                             flips_taken=fl, flips_candidates=int(sc_flip), candidates=len(keys)))
    print(arm, "A done", out[f"A-{arm}"]["twin"], flush=True)
    # ---- C: filters (app-style, on A and on P)
    for f in ("WALL15", "WALL30", "CROSS"):
        def skip(t, f=f):
            v = fx.get(ckey(t), {})
            return L.cross_skip(v.get("sr_crossed", np.nan)) if f == "CROSS" else L.wall_skip(v.get("sr_ahead", np.nan), L.WALL[f])
        kf = np.array([not skip(t) for t in w], bool)
        for b, kb in (("A", np.ones(len(w), bool)), ("P", kp)):
            kv = kb & kf
            tr = [t for t, k in zip(w, kv) if k]
            tw, nd, _ = swap_twins(w, kv, kb, rng)
            tid = f"C-{f}-{arm}-{b}"
            out[tid] = row_of(tr, dict(twin=twin_summary(nets(tr), tw, removed=nd)))
            print(tid, out[tid]["windows"]["total"]["net"], out[tid]["twin"]["p"], flush=True)
    write(f"span_sr_{arm}.json", out)                  # incremental: A and C are in
    # ---- D: the S/R target on P (own exits) and on V5
    usT, n_lvl, n_row = target_units(arm, usA, fx)
    print(arm, "target units", n_lvl, n_row, flush=True)
    for b in ("P", "V5"):
        prT = R3.price(arm, usT, b == "V5", K.LOCK if b == "V5" else "", 1)
        wT = R3.walk(arm, usT, prT, lo, hi)
        tr = [t for t, k in zip(wT, keep_mask(wT, sc, thr)) if k]
        tw, ch = exit_twins(arm, tr, plainV if b == "V5" else plainA, 0.10 if b == "V5" else -1.0,
                            K.LOCK if b == "V5" else "", rng)
        tid = f"D-TGT-{arm}-{b}"
        why = {}
        for t in tr:
            why[t["why"]] = why.get(t["why"], 0) + 1
        out[tid] = row_of(tr, dict(twin=twin_summary(nets(tr), tw, changed=ch), target_levels=n_lvl, target_rows=n_row,
                                   exits=why))
        print(tid, out[tid]["windows"]["total"]["net"], out[tid]["twin"], flush=True)
        write(f"span_sr_{arm}.json", out)
    return out


# ================================================================================================ B: retrain without S/R
def matrix(keys: list, fx: dict, feats: list) -> np.ndarray:
    return np.array([[fx[k].get(f, np.nan) for f in feats] for k in keys], dtype=float)


def oof_scores(T, PK, us, trades, fx, feats) -> dict:
    """R151's oof_scores (study.py) with the feature list as an argument; otherwise line for line."""
    sc = {}
    tkeys = [T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
    y = np.array([t["net"] > 0 for t in trades], int)
    tdays = np.array([t["day"] for t in trades], dtype=object)
    for f0, f1 in T.FOLDS:
        tr_m = PK.train_mask(tdays, f0)
        if tr_m.sum() < 200:
            continue
        mdl = T.make_model("M2").fit(matrix([k for k, m in zip(tkeys, tr_m) if m], fx, feats), y[tr_m])
        ck = [T.cand_key(key[0], key[1], c["er"], c["side"]) for key, cs, _, _ in us for c in cs if f0 <= key[1] <= f1]
        if ck:
            for k, p in zip(ck, mdl.predict_proba(matrix(ck, fx, feats))[:, 1]):
                sc[k] = float(p)
    return sc


def numbers(mdl, feats: list, thr) -> dict:
    imp, scl, lr = mdl.named_steps["simpleimputer"], mdl.named_steps["standardscaler"], mdl.named_steps["logisticregression"]
    return {"feats": list(feats), "median": [float(v) for v in imp.statistics_], "mean": [float(v) for v in scl.mean_],
            "scale": [float(v) for v in scl.scale_], "coef": [float(v) for v in lr.coef_[0]],
            "intercept": float(lr.intercept_[0]), "threshold": thr}


def bfit(arm: str) -> dict:
    """PREREG 3.2 and 6 B, in R151's own store window: reproduce the app's model, then retrain without the S/R features."""
    flag(f"bfit_{arm}")
    boot(K.R151_STORE)
    P, T, PK = picker_mods()
    params = K.R151_PARAMS[arm]
    us = T.units(arm, params, T.K.START, T.K.DESIGN_END)
    fx = T.feats_of_units(us)
    trades = T.S.walk(arm, us)
    tk = [T.cand_key(t["und"], t["day"], t["er_sig"], t["side"]) for t in trades]
    y = np.array([t["net"] > 0 for t in trades], int)
    pub = nums_of(arm)
    full = T.make_model("M2").fit(matrix(tk, fx, T.FEATS), y)
    nf = numbers(full, T.FEATS, pub["threshold"])
    diffs = {k: float(np.max(np.abs(np.asarray(nf[k], float) - np.asarray(pub[k], float))))
             for k in ("median", "mean", "scale", "coef", "intercept")}
    ok = all(v <= K.REPRO_TOL for v in diffs.values())
    out = {"_meta": dict(written=now(), arm=arm, design_trades=len(trades), positives=int(y.sum()), store=str(K.R151_STORE)),
           "repro": dict(max_abs_diff=diffs, reproduced=ok, tol=K.REPRO_TOL)}
    print(arm, "model repro", out["repro"], flush=True)
    base_wf = T.summ([t for t in trades if T.in_(t, *T.WF)])
    # the reproduced full model's own clean curve and the rule's threshold (descriptive + a check of the rule)
    sc_full = oof_scores(T, PK, us, trades, fx, T.FEATS)
    ch_full = T.choose_threshold(arm, us, sc_full, base_wf)
    thr_full, how_full = L.threshold_rule(ch_full["curve"], base_wf, PK.first_pass)
    out["full_curve_rule"] = dict(threshold=thr_full, rule=how_full, base_wf=base_wf)
    print(arm, "full model rule threshold", thr_full, how_full, flush=True)
    if not ok:
        out["stopped"] = "R151's model did not reproduce within 1e-6: family B stops for this arm (PREREG 3.2)"
        write(f"span_bfit_{arm}.json", out)
        return out
    feats = L.drop_feats(pub)
    sc = oof_scores(T, PK, us, trades, fx, feats)
    ch = T.choose_threshold(arm, us, sc, base_wf)
    thr, how = L.threshold_rule(ch["curve"], base_wf, PK.first_pass)
    mdl = T.make_model("M2").fit(matrix(tk, fx, feats), y)
    out["noSR"] = dict(threshold=thr, rule=how, model=numbers(mdl, feats, thr),
                       curve=[{k: v for k, v in c.items()} for c in ch["curve"]])
    print(arm, "noSR threshold", thr, how, flush=True)
    write(f"span_bfit_{arm}.json", out)
    return out


def bspan(arm: str) -> dict:
    """Family B on the span: P-noSR scored with its frozen numbers on the same features and walk as P (from `sr`)."""
    flag(f"b_{arm}")
    fit = json.loads((K.RESULTS / f"span_bfit_{arm}.json").read_text())
    out = {"_meta": dict(written=now(), arm=arm)}
    if "noSR" not in fit or fit["noSR"]["threshold"] is None:
        out["stopped"] = fit.get("stopped", "no threshold")
        write(f"span_b_{arm}.json", out)
        return out
    rng = np.random.default_rng(K.SEED + (3 if arm == "R72" else 4))
    b = load(f"sr_{arm}_base")
    w, fx, sc = b["w"], b["fx"], b["sc"]
    nums = fit["noSR"]["model"]
    thr = float(fit["noSR"]["threshold"])
    picker_mods()
    scn = score_rows(nums, list(fx), fx)
    kp = keep_mask(w, sc, K.PICK_THR[arm])
    kn = keep_mask(w, scn, thr)
    p_tr = [t for t, k in zip(w, kp) if k]
    n_tr = [t for t, k in zip(w, kn) if k]
    tw, nd, na = swap_twins(w, kp, kn, rng)
    out["base:P-noSR"] = row_of(n_tr, dict(threshold=thr))
    out[f"B-{arm}"] = row_of(p_tr, dict(twin=twin_summary(nets(p_tr), tw, dropped=nd, added=na)))
    print(arm, "B", out[f"B-{arm}"]["windows"]["total"]["net"], out["base:P-noSR"]["windows"]["total"]["net"],
          out[f"B-{arm}"]["twin"], flush=True)
    write(f"span_b_{arm}.json", out)
    return out


# ================================================================================================ Liquidity
LIQ_UNDS = R3.LIQ_UNDS


def liq_rows() -> dict:
    """{(und, tf): {day: [rows]}}: fam_liq.signals' loop with R194's row copy (room 0, both index exits kept apart)."""
    from datetime import timedelta
    M = boot()
    fam = M["S"].mods()["fam_liq"]
    rk = M["r51data"].K
    import r101cfg as K1
    out = {}
    for und, books in K1.LIQ_BOOKS.items():
        days = M["r51data"].load(und)
        step, istop = rk.STEP[und], fam.IDX_STOP[und]
        for tf in books:
            hist, per = [], {}
            for d in sorted(days):
                D = days[d]
                a = D.idx
                ok = np.isfinite(a[3])
                today1 = [(d, fam.OPEN_M + r, float(a[0, r]), float(a[1, r]), float(a[2, r]), float(a[3, r]))
                          for r in np.flatnonzero(ok)]
                hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
                if d >= rk.START and D.atm is not None and not D.expiry and hist:
                    ones = [bb for hh in hist for bb in hh[1]] + today1
                    bars = fam.H4.fold(ones, tf)
                    if len(bars) >= 2 * fam.H4.L + 2:
                        per[d] = L.liq_day_signals(fam.H4, d, bars, tf, istop, step, fam.OPEN_M + fam.FIRST,
                                                   fam.OPEN_M + fam.LAST)
                hist.append((d, today1))
            out[(und, tf)] = per
    return out


def liq_units(rows: dict, room: float, nl_on: bool) -> list:
    """r101arms.liq_units with R194's signals (no R101 features: nothing here reads them); then R177's A rule (fx_arm)."""
    M = R7.mods()
    fam, C = M["S"].mods()["fam_liq"], M["C"]
    lo, hi = K.SPAN
    out = []
    for (und, tf), per in rows.items():
        days = M["r51data"].load(und)
        for d in C.days_in(und, lo, hi):
            sel = L.liq_select(per.get(d, []), room, nl_on)
            if not sel:
                continue
            D = days[d]
            cands = [dict(s, fx=fam.forced_row(D, s), book=tf, und=und) for s in sel]
            out.append(((und, d, tf), cands, [None] * len(cands), "book"))
    us, _ = R7.apply_rule("LIQ", out, None)
    return us


def lkey(und, day, c) -> tuple:
    return (und, S6._d(day), int(c.get("book") or 0), int(c["er"]), c["side"])


def lkey_t(t) -> tuple:
    return (t["und"], S6._d(t["day"]), int(t.get("book") or 0), int(t["er_sig"]), t["side"])


def liq_walk(us: list, pr: list) -> list:
    lo, hi = K.SPAN
    return R3.walk("LIQ", us, pr, lo, hi)


def subset(us: list, pr: list, keep) -> tuple:
    u2, p2 = [], []
    for (key, cands, res, how), row in zip(us, pr):
        idx = [j for j, c in enumerate(cands) if keep(key, c)]
        if idx:
            u2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
            p2.append([row[j] for j in idx])
    return u2, p2


def room_twins(us: list, pr: list, k: int, rng) -> np.ndarray:
    """Remove k signals at random from (us, pr) before the walk, walk, total; K.TWINS times."""
    flat = [(g, j) for g, (_, cands, _, _) in enumerate(us) for j in range(len(cands))]
    tot = np.empty(K.TWINS)
    for i in range(K.TWINS):
        gone = set(rng.choice(len(flat), size=min(k, len(flat)), replace=False).tolist()) if k > 0 else set()
        drop = {flat[x] for x in gone}
        u2, p2 = [], []
        for g, ((key, cands, res, how), row) in enumerate(zip(us, pr)):
            idx = [j for j in range(len(cands)) if (g, j) not in drop]
            if idx:
                u2.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
                p2.append([row[j] for j in idx])
        tot[i] = nets(liq_walk(u2, p2))
    return tot


def liq() -> dict:
    flag("liq")
    rng = np.random.default_rng(K.SEED + 5)
    lo, hi = K.SPAN
    rows = liq_rows()
    print("LIQ rows", sum(len(v) for per in rows.values() for v in per.values()), flush=True)
    us_on = liq_units(rows, 0.0, True)                 # the superset (room 0), new-level exit on
    pr_on = R3.price("LIQ", us_on, False, "", 1)
    us_off = liq_units(rows, 1.0, False)               # room 1, new-level exit off
    pr_off = R3.price("LIQ", us_off, False, "", 1)
    room_of = {}
    for (und, tf), per in rows.items():
        for d, rr in per.items():
            for r in rr:
                room_of[(und, S6._d(d), tf, int(r["er"]), r["side"])] = (r["room"], r["istop"])

    def keep_room(rm):
        def f(key, c):
            x, st = room_of[(key[0], S6._d(key[1]), key[2], int(c["er"]), c["side"])]
            return not (x < rm * st)
        return f

    sets = {rm: subset(us_on, pr_on, keep_room(rm)) for rm in (0.0, 0.5, 1.0, 2.0)}
    walks = {rm: liq_walk(*sets[rm]) for rm in sets}
    w_nl0 = liq_walk(us_off, pr_off)
    base = walks[1.0]
    pub = json.loads((K.R193 / "results" / "span.json").read_text())["A:LIQ"]["windows"]["total"]
    s = G.stats(base, lo, hi)
    rep = dict(trades=s["trades"], net=s["net"], r193=[pub["trades"], pub["net"]], gap_trades=s["trades"] - pub["trades"],
               gap_net=s["net"] - pub["net"], reproduced=s["trades"] == pub["trades"] and abs(s["net"] - pub["net"]) <= 1)
    print("LIQ repro L1", rep, flush=True)
    write("repro_LIQ.json", dict(written=now(), L1=rep))
    n_sig = {rm: sum(len(c) for _, c, _, _ in sets[rm][0]) for rm in sets}
    out = {"_meta": dict(written=now(), twins=K.TWINS, seed=int(K.SEED), repro=rep, signals={str(k): v for k, v in n_sig.items()}),
           "base:L1": row_of(base)}
    write("span_liq.json", out)
    l1 = nets(base)
    for tid, rm in (("D-LQ-R0", 0.0), ("D-LQ-R05", 0.5)):
        k = n_sig[rm] - n_sig[1.0]
        tw = room_twins(*sets[rm], k, rng)
        out[tid] = row_of(walks[rm], dict(twin=twin_summary(l1, tw, upper=False, removed=k, note="stat = L1's net (lower tail)")))
        print(tid, out[tid]["windows"]["total"]["net"], out[tid]["twin"], flush=True)
        write("span_liq.json", out)
    k = n_sig[1.0] - n_sig[2.0]
    tw = room_twins(*sets[1.0], k, rng)
    out["D-LQ-R2"] = row_of(walks[2.0], dict(twin=twin_summary(nets(walks[2.0]), tw, removed=k)))
    print("D-LQ-R2", out["D-LQ-R2"]["windows"]["total"]["net"], out["D-LQ-R2"]["twin"], flush=True)
    write("span_liq.json", out)
    # NL0: on the variant's walk, the with-exit net of each trade the new-level exit closes >= 2 minutes earlier vs random
    import r186engine as Z
    M = R7.mods()
    ex = S6.exits("LIQ")
    plain = {lkey(key[0], key[1], c): t for (key, cands, _, _), row in zip(*sets[1.0]) for c, t in zip(cands, row)
             if t is not None}
    tot = np.repeat(np.array([t["net"] for t in w_nl0], float)[:, None], K.TWINS, axis=1)
    stat, ch = 0.0, 0
    for i, t in enumerate(w_nl0):
        p = plain.get(lkey_t(t))
        if p is None or not (p["xr"] < t["xr"] and t["xr"] - t["er"] >= 2):
            stat += t["net"]
            continue
        ch += 1
        stat += p["net"]
        rows_ = M["S"].twin_rows(t["er"], t["xr"], K.TWINS, rng)
        D = M["r51data"].load(t["und"])[t["day"]]
        tot[i] = Z.forced_nets(t["und"], D, t, ex, rows_, 1, int(t.get("fx_arm", -1)), be_x=-1.0, lock="")
    tw = tot.sum(axis=0)
    out["D-LQ-NL0"] = row_of(w_nl0, dict(twin=twin_summary(stat, tw, upper=False, changed=ch,
                                                           note="stat = the with-exit total on the variant's walk (lower tail)")))
    print("D-LQ-NL0", out["D-LQ-NL0"]["windows"]["total"]["net"], out["D-LQ-NL0"]["twin"], flush=True)
    write("span_liq.json", out)
    return out


# ================================================================================================ decide
def results() -> dict:
    r = {}
    for f in ("span_sr_R72", "span_sr_R51", "span_b_R72", "span_b_R51", "span_liq"):
        p = K.RESULTS / f"{f}.json"
        if p.exists():
            r[f] = json.loads(p.read_text())
    return r


def base_key(tid: str) -> str:
    fam, arm, _, b = K.TESTS[tid]
    return f"base:{b}"


def find(r: dict, tid: str) -> tuple:
    """(variant row, baseline row) for a test."""
    fam, arm, _, b = K.TESTS[tid]
    if arm == "LIQ":
        x = r.get("span_liq", {})
        return x.get(tid), x.get("base:L1")
    sr_ = r.get(f"span_sr_{arm}", {})
    if fam == "B":
        bb = r.get(f"span_b_{arm}", {})
        return bb.get(tid), bb.get("base:P-noSR")
    return sr_.get(tid), sr_.get(f"base:{b}")


def decide() -> dict:
    r = results()
    ids = list(K.TESTS)
    ps = []
    for tid in ids:
        v, _ = find(r, tid)
        ps.append(v["twin"]["p_normal"] if v else 1.0)
    qs = G.bh_total(ps, K.M_TOTAL)
    out = {"m_total": K.M_TOTAL, "tests": {}}
    for tid, q in zip(ids, qs):
        v, b = find(r, tid)
        if v is None or b is None:
            out["tests"][tid] = dict(helps=False, fails=["not run (stopped or missing)"], q=q)
            continue
        g = G.gate(v["windows"], b["windows"], v["twin"]["p"], q)
        g["fails"] = [f.replace("A's", "the baseline's") for f in g["fails"]]
        g["p_normal"] = v["twin"]["p_normal"]
        g["account_stop"] = {w: v["windows"][w]["account_stop_ok"] for w in K.WINDOWS}
        out["tests"][tid] = g
    out["helps"] = [t for t in ids if out["tests"][t]["helps"]]
    write("decision.json", out)
    return out


if __name__ == "__main__":
    cmd, arg = (sys.argv[1:] + ["", ""])[:2]
    fn = {"sr": lambda: sr(arg), "bfit": lambda: bfit(arg), "bspan": lambda: bspan(arg), "liq": liq, "decide": decide}
    fn.get(cmd, lambda: sys.exit(__doc__))()
