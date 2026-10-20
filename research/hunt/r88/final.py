"""R88 holdout (PREREG sections 5-6), read ONCE: refuses to run when results/FINAL_READ exists.

    python -m research.hunt.r88.final
"""
from __future__ import annotations

import json
from datetime import datetime

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor

from . import candles as C
from . import cfg, data, run, stats


def main() -> None:
    if cfg.FINAL_FLAG.exists():
        raise SystemExit(f"the holdout was already read ({cfg.FINAL_FLAG.read_text().strip()}); it is read once")
    design = pd.read_csv(cfg.RESULTS / "partC_design.csv")
    cuts = json.load(open(cfg.RESULTS / "cuts.json"))
    cfg.FINAL_FLAG.write_text(datetime.now().isoformat(timespec="seconds") + " UTC holdout opened by final.py\n")
    ev = run.events()
    cen, conc, hold, models = [], [], [], []
    surv = design[design.design_pass]
    for s in cfg.ALL:
        exps = data.detect_expiries(pd.read_parquet(cfg.CACHE / f"{s}_chain.parquet"))
        for tf in ((1, *cfg.TFS) if s in cfg.INDICES else cfg.TFS):
            k = run.load(s, tf, "holdout")
            cen.append(run.census(k, s, tf, exps, ev if s in cfg.INDICES else None))
            if tf == 1:
                continue
            conc.append(run.concurrent_ols(k, s, tf))
            sv = surv[(surv.sym == s) & (surv.tf == tf)]
            if len(sv):
                cq = {f: np.array(v) for f, v in cuts[f"{s}|{tf}"].items()}
                cont = sorted(set(sv.factor) & set(C.CONT))
                cat = sorted(set(sv.factor) & set(C.CAT))
                rows, _ = stats.factor_tests(k, cont, cat, cuts=cq, min_n=30, min_sessions=5)
                got = {(r["factor"], r["level"], r["target"]): r for r in rows}
                for _, d in sv.iterrows():
                    r = got.get((d.factor, str(d.level), d.target))
                    if r is None:
                        hold.append(dict(sym=s, tf=tf, factor=d.factor, level=d.level, target=d.target,
                                         design_diff=d["diff"], design_q=d.q, note="too few holdout candles"))
                        continue
                    hold.append(dict(sym=s, tf=tf, factor=d.factor, level=d.level, target=d.target,
                                     design_diff=d["diff"], design_q=d.q, design_n=int(d.n1 + d.n0),
                                     hold_diff=r["diff"], hold_se=r["se"], hold_n=int(r["n1"] + r["n0"]),
                                     hold_sessions=r["sessions"], up_hi=r["up_hi"], up_lo=r["up_lo"],
                                     p_one=stats.one_sided(r["p"], r["t"], np.sign(d["diff"]))))
            models.append(holdout_model(s, tf))
            print(s, tf, "holdout done", flush=True)
    h = pd.DataFrame(hold)
    if len(h):
        h["q_hold"] = np.nan
        for y in ("S", "D"):
            m = (h.target == y) & h.p_one.notna()
            h.loc[m, "q_hold"] = stats.bh(h.loc[m, "p_one"])
        h["holds"] = (np.sign(h.hold_diff) == np.sign(h.design_diff)) & (h.q_hold < cfg.Q_HOLD)
    h.to_csv(cfg.RESULTS / "partC_holdout.csv", index=False)
    pd.DataFrame(cen).to_csv(cfg.RESULTS / "partA_census_holdout.csv", index=False)
    json.dump(conc, open(cfg.RESULTS / "partB_holdout.json", "w"), indent=1)
    json.dump(models, open(cfg.RESULTS / "partD_holdout.json", "w"), indent=1)
    print("survivors", len(h), "hold", int(h.holds.sum()) if len(h) else 0)


def holdout_model(s: str, tf: int) -> dict:
    """One model trained on the whole design period, scored once on the holdout (PREREG section 6)."""
    kd, kh = run.load(s, tf, "design"), run.load(s, tf, "holdout")
    Xd, Xh = run.features(kd), run.features(kh)
    sd = kd.S.notna().to_numpy()
    reg = HistGradientBoostingRegressor(max_iter=200, learning_rate=0.05, random_state=cfg.SEED).fit(Xd[sd], kd.S[sd])
    lin = sd & Xd.F02.notna().to_numpy() & Xd.F03.notna().to_numpy()
    A = np.column_stack([np.ones(lin.sum()), Xd.F02[lin], np.log(Xd.F03[lin].clip(lower=1e-6))])
    b, *_ = np.linalg.lstsq(A, kd.S[lin], rcond=None)
    ud = kd.up.notna().to_numpy()
    clf = HistGradientBoostingClassifier(max_iter=200, learning_rate=0.05, random_state=cfg.SEED).fit(
        Xd[ud], kd.up[ud].astype(int))
    ev = kh.S.notna().to_numpy() & Xh.F02.notna().to_numpy() & Xh.F03.notna().to_numpy()
    pl = np.column_stack([np.ones(ev.sum()), Xh.F02[ev], np.log(Xh.F03[ev].clip(lower=1e-6))]) @ b
    evd = kh.up.notna().to_numpy()
    return dict(sym=s, tf=tf,
                size=run.size_scores(kh.S[ev], reg.predict(Xh[ev]), pl, kh.day[ev].to_numpy()),
                direction=run.dir_scores(kh.up[evd], clf.predict_proba(Xh[evd])[:, 1], (kh.c - kh.o)[evd],
                                         kh.day[evd].to_numpy(), cfg.COST_POINTS.get(s)))


if __name__ == "__main__":
    main()
