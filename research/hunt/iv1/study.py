"""IV1 (PREREG.md): option-market signals O1-O3 on R30's decision table, walk-forward (descriptive).

    python3 research/hunt/iv1/study.py run      # -> results/walkforward.json, results/table.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[0] / "r30"))

import r30cfg as K  # noqa: E402
import models as MO  # noqa: E402

RESULTS = HERE / "results"
OPT = ["atm_iv", "atm_iv_chg15", "iv_skew2", "pcr_oi", "pcr_oi_chg30", "oi_ce_chg30_pct", "oi_pe_chg30_pct",
       "oi_ce_atm_chg30_pct", "oi_pe_atm_chg30_pct", "straddle_pct", "straddle_vs_0920", "straddle_chg15_pct",
       "vol_ce_15", "vol_pe_15", "vol_ratio_15", "maxoi_call_dist_pct", "maxoi_put_dist_pct",
       "minute_of_day", "dte_days", "is_expiry_day", "vix", "und_id"]


def o3(te: pd.DataFrame) -> np.ndarray:
    """The fixed hand rule (nothing fitted)."""
    pe_w, ce_w = te.oi_pe_atm_chg30_pct.values, te.oi_ce_atm_chg30_pct.values
    pcr, st = te.pcr_oi_chg30.values, te.straddle_chg15_pct.values
    ce = (pe_w > 0) & (ce_w < 0) & (pcr > 0) & (st > 0)
    pe = (ce_w > 0) & (pe_w < 0) & (pcr < 0) & (st > 0)
    return np.where(ce & ~pe, "C", np.where(pe & ~ce, "P", ""))


def run():
    T = pd.read_parquet(K.OUT / "table.parquet")
    T["day"] = pd.to_datetime(T.day).dt.date
    T["year"] = [d.year for d in T.day]
    assert not T.day.map(lambda d: K.F1_HOLD[0] <= d <= K.F1_HOLD[1]).any()
    assert set(OPT) <= set(T.columns)
    allt, info = [], {}
    for name, tr_end, a, b in K.FOLDS:
        tr = T[T.day < tr_end].reset_index(drop=True)
        te = T[(T.day >= a) & (T.day <= b)].reset_index(drop=True)
        info[name] = {}
        for st in ("O1", "O2", "O3"):
            if st == "O1":
                side, mdl = MO.sig_m4(tr, te, OPT)
                imp = MO.importance(mdl, te, OPT, "M4")
            elif st == "O2":
                side, mdl = MO.sig_combo(tr, te, OPT, "C1")
                imp = MO.importance(mdl, te, OPT, "C1")
            else:
                side, imp = o3(te), None
            Tr = MO.trade(te, side, "X1")
            Tr["fold"], Tr["strategy"] = name, st
            allt.append(Tr)
            info[name][st] = dict(signals=int((side != "").sum()), trades=len(Tr), top=imp)
            print(name, st, len(Tr), round(Tr.net.sum()) if len(Tr) else 0, flush=True)
    A = pd.concat(allt, ignore_index=True)
    A.to_parquet(K.OUT / "iv1_trades.parquet")
    res = {}
    for st in ("O1", "O2", "O3"):
        for u in K.UNDS:
            G = A[(A.strategy == st) & (A.und == u)]
            td = {f: T[(T.und == u) & (T.day >= a) & (T.day <= b)].day.nunique() for f, _, a, b in K.FOLDS}
            r = MO.metrics(G, max(sum(td.values()), 1))
            r["folds"] = {f: MO.metrics(G[G.fold == f], max(td[f], 1)) for f in td if td[f]}
            te = T[(T.und == u) & T.day.map(lambda d: any(a <= d <= b for _, _, a, b in K.FOLDS))]
            r["p_twins"], r["twin_per_trade"] = MO.twin_p(G, te, "X1") if len(G) else (np.nan, np.nan)
            r["every_period_positive"] = bool(len(G)) and all(v.get("net", 0) > 0 for v in r["folds"].values()
                                                               if v.get("trades"))
            res[f"{st}|{u}"] = r
    keys = [k for k, v in res.items() if np.isfinite(v.get("p_twins", np.nan))]
    for k, q in zip(keys, MO.MI.bh(np.array([res[k]["p_twins"] for k in keys]))):
        res[k]["bh_q"] = float(q)
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "walkforward.json").write_text(json.dumps(dict(folds=info, results=res), indent=1, default=str))
    for k, v in res.items():
        print(k, {x: (round(y, 3) if isinstance(y, float) else y) for x, y in v.items()
                  if x in ("trades", "per_trade", "pnl_day", "p_twins", "bh_q", "every_period_positive")})


if __name__ == "__main__":
    run()
