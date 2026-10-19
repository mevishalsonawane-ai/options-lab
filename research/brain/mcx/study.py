"""MB1 study driver (PREREG.md). Design work reads design sessions only; `holdout` is the one read.

    python -m research.brain.mcx.study tensors         # columnar + binary stores (tensors.py) for every symbol
    python -m research.brain.mcx.study design          # L1-L3 walk-forward, L1-D/E, BH, L4 design gates
    python -m research.brain.mcx.study holdout         # ONE read: refit on all design sessions, score the holdout
    python -m research.brain.mcx.study cards           # L5 knowledge cards (l5.py)

Outputs: <cache>/mcx/study/ (forecast tables, models) and research/brain/mcx/results/*.json (committed summaries).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from . import config, data as D, features as F, holdout, l4, levels as LV, session_event as SE, tensors

RESULTS = Path(__file__).resolve().parent / "results"
MODEL_SYMS = list(config.OPTION_SYMBOLS)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def _json(name: str, obj) -> Path:
    RESULTS.mkdir(parents=True, exist_ok=True)
    p = RESULTS / name
    p.write_text(json.dumps(_clean(obj), indent=1, default=str) + "\n")
    return p


def _clean(o):
    if isinstance(o, dict):
        return {str(k): _clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_clean(v) for v in o]
    if isinstance(o, (float, np.floating)):
        return None if not np.isfinite(o) else round(float(o), 6)
    if isinstance(o, np.integer):
        return int(o)
    return o


# ================================================================================================= tensors
def cmd_tensors(root):
    P = config.paths(root)
    out = P.tensors
    out.mkdir(parents=True, exist_ok=True)
    names = F.names()
    frames, info = {}, {}
    for s in config.SPECS:
        S = D.load_symbol(root, s)
        info[s] = tensors.write_symbol(out, s, S.feats, names)
        info[s].update(tensors.write_windows(out, s, S.cube, S.feats, np.flatnonzero(S.dec)))
        frames[s] = S.feats
        log(s, info[s])
    info["joint"] = tensors.write_joint(out, frames, names)
    man = tensors.manifest(out, {"signature": F.signature(), "features": names, "symbols": info})
    _json("tensors_manifest.json", {k: v for k, v in man.items()})
    log("tensors done", sum(f["bytes"] for f in man["files"].values()) / 1e6, "MB")


# ================================================================================================= metrics
def size_metrics(o: pd.DataFrame) -> dict:
    a = o[np.isfinite(o.y_logrange_60m)]
    y = a.y_logrange_60m.to_numpy()
    err = {k: np.abs(y - a[k].to_numpy()) for k in ("A_hgb", "A_ridge", "A_hgbS", "A_hgbP", "size_comm", "b_tod",
                                                     "b_last", "b_iv")}
    mae = {k: float(np.nanmean(v)) for k, v in err.items()}
    best = min(("b_tod", "b_last", "b_iv"), key=lambda k: mae[k])
    sess = (a.sym + a.day.astype(str)).to_numpy()
    res = dict(rows=int(len(a)), sessions=int(a.day.nunique()), mae=mae, best_baseline=best,
               skill=1 - mae["A_hgb"] / mae[best], skill_ridge=1 - mae["A_ridge"] / mae[best],
               p=D.boot_p(err[best] - err["A_hgb"], sess),
               enc_gain=1 - mae["A_hgbS"] / mae["A_hgb"], pca_gain=1 - mae["A_hgbP"] / mae["A_hgb"],
               p_enc=D.boot_p(err["A_hgb"] - err["A_hgbS"], sess))
    lo, hi = a.band_lo.to_numpy(), a.band_hi.to_numpy()
    okb = np.isfinite(lo)
    keep = ~a.size_abstain.to_numpy(bool)
    res["committee"] = dict(mae_all=mae["size_comm"], mae_kept=float(np.nanmean(err["size_comm"][keep])),
                            kept_share=float(keep.mean()),
                            band80_coverage=float(np.mean((y[okb] >= lo[okb]) & (y[okb] <= hi[okb]))))
    # calibration table: forecast deciles -> mean forecast and mean actual range (%)
    q = pd.qcut(a.A_hgb, 10, labels=False, duplicates="drop")
    res["deciles"] = [dict(decile=int(k), n=int(len(g)), forecast_pct=float(np.exp(g.A_hgb).mean()),
                           actual_pct=float(g.y_range_60m.mean())) for k, g in a.groupby(q)]
    return res


def class_metrics(y, p, base, sess) -> dict:
    ok = np.isfinite(p) & np.isfinite(base)
    y, p, base, sess = y[ok], p[ok], base[ok], sess[ok]
    if len(y) < 50 or not 0 < y.mean() < 1:
        return dict(rows=int(len(y)))
    llm, llb = D.logloss_rows(y, p), D.logloss_rows(y, base)
    return dict(rows=int(len(y)), rate=float(y.mean()), auc=float(roc_auc_score(y, p)), ll_model=float(llm.mean()),
                ll_base=float(llb.mean()), p=D.boot_p(llb - llm, sess))


def dir_metrics(o: pd.DataFrame) -> dict:
    sess = (o.sym + o.day.astype(str)).to_numpy()
    yr = o.y_ret_60m.to_numpy(float)
    r = o.ret_60m.to_numpy(float)
    okB = np.isfinite(yr) & np.isfinite(r) & (yr != 0) & (r != 0)
    yB = (np.sign(yr) == np.sign(r)).astype(float)
    B = class_metrics(yB[okB], o.B_hgb.to_numpy()[okB], o.B_rate.to_numpy()[okB], sess[okB])
    B["auc_lin"] = float(roc_auc_score(yB[okB], o.B_lin.to_numpy()[okB])) if B.get("auc") else None
    okC = np.isfinite(yr) & (yr != 0)
    yC = (yr > 0).astype(float)
    C = class_metrics(yC[okC], o.C_hgb.to_numpy()[okC], o.C_rate.to_numpy()[okC], sess[okC])
    if C.get("auc"):
        C["auc_lin"] = float(roc_auc_score(yC[okC], o.C_lin.to_numpy()[okC]))
        C["auc_enc"] = float(roc_auc_score(yC[okC], o.C_hgbS.to_numpy()[okC]))
        C["auc_pca"] = float(roc_auc_score(yC[okC], o.C_hgbP.to_numpy()[okC]))
        C["enc_gain"] = C["auc_enc"] - C["auc"]
        llm = D.logloss_rows(yC[okC], o.C_hgb.to_numpy()[okC])
        lls = D.logloss_rows(yC[okC], o.C_hgbS.to_numpy()[okC])
        C["p_enc"] = D.boot_p(llm - lls, sess[okC])
    act = o.act.to_numpy()
    acted = okC & (act != 0)
    hit = ((act[acted] > 0) & (yC[acted] == 1)) | ((act[acted] < 0) & (yC[acted] == 0))
    k, n = int(hit.sum()), int(acted.sum())
    comm = dict(acted=n, coverage=float(acted.sum() / max(okC.sum(), 1)), hit_rate=float(k / n) if n else None,
                p=D.binom_p(k, n) if n else None)
    return dict(B=B, C=C, committee=comm)


def metrics(oos: pd.DataFrame) -> dict:
    out = {}
    for s, o in oos.groupby("sym"):
        out[s] = dict(size=size_metrics(o), **dir_metrics(o), sessions=int(o.day.nunique()))
    return out


def verdicts(M: dict, SD: dict, EV: dict) -> tuple[dict, dict]:
    """The pre-registered primary tests, one BH family (PREREG section 4)."""
    p = {}
    for s, m in M.items():
        p[f"L1A:{s}"] = m["size"]["p"]
        p[f"L1B:{s}"] = m["B"].get("p")
        p[f"L1C:{s}"] = m["C"].get("p")
        p[f"L2A:{s}"] = m["size"]["p_enc"]
        p[f"L2C:{s}"] = m["C"].get("p_enc")
        p[f"L3C:{s}"] = m["committee"]["p"]
    for s, r in SD.items():
        p[f"L1Di:{s}"] = r["size"]["p"]
        p[f"L1Dii:{s}"] = r["continue"]["p"]
    for g, r in EV.items():
        p[f"L1Ei:{g}"] = r["p_bigger"]
        p[f"L1Eii:{g}"] = r["p_follow"]
    q = D.bh({k: v for k, v in p.items() if v is not None and np.isfinite(v)})
    v = {}
    for k in p:
        lvl, key = k.split(":", 1)
        qq = q.get(k)
        sig = qq is not None and qq <= 0.05
        if lvl == "L1A":
            sk = M[key]["size"]["skill"]
            v[k] = "pass" if sig and sk >= 0.05 else ("weak" if sig and sk >= 0.01 else "fail")
        elif lvl in ("L1B", "L1C"):
            m = M[key][lvl[-1]]
            v[k] = "pass" if sig and m.get("auc", 0) >= 0.55 and m["ll_model"] < m["ll_base"] else "fail"
        elif lvl == "L2A":
            v[k] = "pass" if sig and M[key]["size"]["enc_gain"] >= 0.01 else "fail"
        elif lvl == "L2C":
            v[k] = "pass" if sig and (M[key]["C"].get("enc_gain") or 0) >= 0.01 else "fail"
        elif lvl == "L3C":
            c = M[key]["committee"]
            v[k] = "pass" if sig and (c["hit_rate"] or 0) >= 0.53 and c["acted"] >= 300 else "fail"
        elif lvl == "L1Di":
            v[k] = "pass" if sig and SD[key]["size"]["skill"] >= 0.05 else "fail"
        elif lvl == "L1Dii":
            r = SD[key]["continue"]
            v[k] = "pass" if sig and r["auc"] >= 0.55 and r["ll_model"] < r["ll_base"] else "fail"
        elif lvl == "L1Ei":
            v[k] = "pass" if sig and EV[key]["mean_log_ratio"] > 0 else "fail"
        elif lvl == "L1Eii":
            v[k] = "pass" if sig else "fail"
    return {"p": p, "q": q}, v


# ================================================================================================= design
def load_all(root, with_holdout_labels=False):
    syms = {s: D.load_symbol(root, s, with_holdout_labels) for s in MODEL_SYMS}
    frames = {s: LV.decision_frame(S) for s, S in syms.items()}
    enc_in = {s: LV.encoder_inputs(syms[s], frames[s]) for s in syms}
    return syms, frames, enc_in


def cmd_design(root):
    P = config.paths(root)
    P.study.mkdir(parents=True, exist_ok=True)
    syms, frames, enc_in = load_all(root)
    for s in frames:
        holdout.assert_design(frames[s][frames[s].y_logrange_60m.notna()])
        frames[s] = frames[s][frames[s].day <= config.DESIGN_END].reset_index(drop=True)
        enc_in[s] = enc_in[s][: len(frames[s])]
    days = sorted(set().union(*[set(f.day) for f in frames.values()]))
    folds = D.folds(days)
    log("design sessions", len(days), "folds", [(str(f["test_lo"]), str(f["test_hi"])) for f in folds])
    parts, finfo = [], []
    for fo in folds:
        train = {d for d in days if d < fo["test_lo"]}
        test = {d for d in days if fo["test_lo"] <= d <= fo["test_hi"]}
        log(f"fold {fo['k']}: train {len(train)} sessions, test {len(test)}")
        o, info = LV.run_fold(frames, enc_in, train, test, log)
        o["block"] = fo["k"]
        parts.append(o)
        info.pop("encoder", None)
        finfo.append({**info, "test": [str(fo["test_lo"]), str(fo["test_hi"])], "train_sessions": len(train)})
    oos = pd.concat(parts, ignore_index=True)
    holdout.assert_design(oos)
    oos.to_parquet(P.study / "oos_design.parquet", index=False)
    M = metrics(oos)
    log("L1-D / L1-E")
    SD = {s: SE.run_session(syms[s], folds) for s in MODEL_SYMS}
    EV = SE.run_events(syms)
    pq, v = verdicts(M, SD, EV)
    _json("design_metrics.json", dict(folds=finfo, specialists=M, session=SD, events=EV, bh=pq, verdicts=v))
    log("verdicts", {k: x for k, x in v.items()})
    cmd_l4(root, oos)


def cmd_l4(root, oos=None):
    P = config.paths(root)
    if oos is None:
        oos = pd.read_parquet(P.study / "oos_design.parquet")
    holdout.assert_design(oos)
    res, pvals, trades_all = {}, {}, []
    for sym in config.TRADE:
        S = D.load_symbol(root, sym)
        Pth = l4.Path_(S.cube)
        book = l4.Book(sym, P.lake)
        o = oos[oos.sym == sym].sort_values("ts").reset_index(drop=True)
        for rule, variants in l4.RULES.items():
            vt = []
            for vi, var in enumerate(variants):
                tr = l4.run_variant(rule, var, sym, o, Pth, book if rule == "B4" else None)
                seed = l4.TWIN_SEED + 97 * vi + 7 * list(l4.RULES).index(rule) + list(config.TRADE).index(sym)
                p, nets = l4.twin_p(rule, var, sym, o, Pth, book if rule == "B4" else None, tr, seed)
                key = f"{rule}:{sym}:{vi}"
                pvals[key] = p
                res[key] = dict(rule=rule, sym=sym, variant=var, twin_p=p,
                                twin_mean=float(np.mean(nets)) if nets else None, **l4.summarize(tr))
                vt.append(tr)
                trades_all += [{**t, "key": key} for t in tr]
                log(key, res[key]["trades"], round(res[key]["net"]), "p", p)
            wf = l4.walk_forward(vt)
            res[f"{rule}:{sym}:wf"] = wf
    q = D.bh({k: v for k, v in pvals.items() if v is not None and np.isfinite(v)})
    gates = {}
    for rule in l4.RULES:
        for sym in config.TRADE:
            wf = res[f"{rule}:{sym}:wf"]
            fk = f"{rule}:{sym}:{wf['frozen']}"
            r = res[fk]
            g = {"net_pos": r["net"] > 0, "wf_pos": wf["wf_net"] > 0, "q<=0.10": (q.get(fk) or 1) <= 0.10,
                 "trades>=30": r["trades"] >= 30, "top5_removed_pos": r.get("top5_removed", -1) > 0}
            gates[f"{rule}:{sym}"] = dict(frozen=fk, q=q.get(fk), gates=g, verdict="PASS" if all(g.values()) else "fail")
    _json("l4_design.json", dict(variants=res, bh_q=q, gates=gates))
    pd.DataFrame(trades_all).to_parquet(P.study / "l4_design_trades.parquet", index=False)
    log("L4 gates", {k: v["verdict"] for k, v in gates.items()})


# ================================================================================================= holdout (one read)
def cmd_holdout(root):
    P = config.paths(root)
    holdout.read_holdout("mb1-predictions", root)
    syms, frames, enc_in = load_all(root, with_holdout_labels=True)
    design = {d for f in frames.values() for d in f.day if d <= config.DESIGN_END}
    hold = {d for f in frames.values() for d in f.day if d >= config.HOLDOUT_START}
    log("holdout: train", len(design), "sessions, test", len(hold))
    o, info = LV.run_fold(frames, enc_in, design, hold, log)
    o["block"] = 0
    o.to_parquet(P.study / "oos_holdout.parquet", index=False)
    import pickle
    with open(P.study / "encoder_design.pkl", "wb") as fh:          # the design-fitted encoder (uploaded, not committed)
        pickle.dump(info.pop("encoder", None), fh)
    M = metrics(o)
    out = dict(train_sessions=len(design), test_sessions=len(hold), info=info, specialists=M)
    # rules: only those that passed the design gate (PREREG section 7)
    g = json.loads((RESULTS / "l4_design.json").read_text())["gates"]
    passed = [k for k, v in g.items() if v["verdict"] == "PASS"]
    out["l4_passed_design"] = passed
    if passed:
        holdout.read_holdout("mb1-rules", root)
        rr = {}
        for k in passed:
            rule, sym = k.split(":")
            vi = int(g[k]["frozen"].rsplit(":", 1)[1])
            S = D.load_symbol(root, sym, True)
            Pth = l4.Path_(S.cube)
            book = l4.Book(sym, P.lake) if rule == "B4" else None
            oo = o[o.sym == sym].sort_values("ts").reset_index(drop=True)
            tr = l4.run_variant(rule, l4.RULES[rule][vi], sym, oo, Pth, book)
            p, _ = l4.twin_p(rule, l4.RULES[rule][vi], sym, oo, Pth, book, tr, l4.TWIN_SEED)
            s = l4.summarize(tr)
            gg = {"net_pos": s["net"] > 0, "twin_p<0.10": (p or 1) < 0.10, "trades>=20": s["trades"] >= 20,
                  "top5_removed_pos": s.get("top5_removed", -1) > 0, "worst_month>-13000": s.get("worst_month", -1e9) > -13000}
            rr[k] = dict(summary=s, twin_p=p, gates=gg, verdict="PASS" if all(gg.values()) else "fail")
        out["l4_holdout"] = rr
    _json("holdout_metrics.json", out)
    log("holdout done")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.mcx.study")
    ap.add_argument("cmd", choices=["tensors", "design", "l4", "holdout", "cards"])
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    if a.cmd == "tensors":
        cmd_tensors(a.cache)
    elif a.cmd == "design":
        cmd_design(a.cache)
    elif a.cmd == "l4":
        cmd_l4(a.cache)
    elif a.cmd == "holdout":
        cmd_holdout(a.cache)
    else:
        from . import l5
        l5.build(a.cache)
    return 0


if __name__ == "__main__":
    sys.exit(main())
