"""R85 Part 1 out-of-sample check, run only AFTER the Part 2 final read (results/FINAL_READ must exist), 19 Oct 2026.

    python3 -m research.hunt.r85.part1_final

Re-measures every design cell on the final period (1 Apr 2026 - newest lake day) and reports, for the cells that were
significant in design, whether the effect kept its sign and how big it was. Descriptive: nothing is chosen from it.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from research.hunt.r85 import anatomy as A
from research.hunt.r85 import candles as K
from research.hunt.r85 import strategy as S


def one(inst: str) -> list:
    rows = []
    for tf in A.TFS:
        cd = A.instrument_candles(inst, tf)
        cd = cd[np.array([d >= A.FINAL_START for d in cd.day])].reset_index(drop=True)
        conds = A.contexts(cd)
        for s in (A.SHAPES_1M if tf == 1 else A.SHAPES):
            conds[s] = cd[s].values.astype(bool)
        rows += A.cell_table(cd, inst, f"{tf}m", conds, ("ALL",) + K.BUCKETS, horizons=(A.PRIMARY_H,))
    return rows


def run():
    if not (S.RESULTS / "FINAL_READ").exists():
        raise SystemExit("run only after the Part 2 final read")
    A.lake_ready()
    from multiprocessing import Pool
    with Pool(4) as p:
        parts = p.map(one, A.INSTRUMENTS)
    F = pd.DataFrame([r for x in parts for r in x])
    T = pd.read_csv(A.RESULTS / "part1_cells.csv.gz")
    D = T[(~T.tf.str.startswith("day")) & (T.h == A.PRIMARY_H)]
    key = ["inst", "tf", "cond", "colour", "bucket"]
    M = D.merge(F[key + ["n", "effect", "effect_cm", "cont", "mean_s"]], on=key, how="left", suffixes=("", "_final"))
    out = {}
    for name, sel in (("pooled_q<0.10", M.q < A.Q_GATE), ("both_tests", (M.q < A.Q_GATE) & (M.q_cm < A.Q_GATE)),
                      ("all_tested", M.p.notna())):
        x = M[sel & M.n_final.fillna(0).ge(10)]
        out[name] = {"cells": int(sel.sum()), "with_final_n>=10": len(x),
                     "same_sign_effect": int((np.sign(x.effect) == np.sign(x.effect_final)).sum()),
                     "same_sign_colour_matched": int((np.sign(x.effect_cm) == np.sign(x.effect_cm_final)).sum()),
                     "median_design_effect": float(x.effect.abs().median()) if len(x) else None,
                     "median_final_effect_signed": float((np.sign(x.effect) * x.effect_final).median()) if len(x) else None}
    M[(M.q < A.Q_GATE)].to_csv(A.RESULTS / "part1_final_check.csv", index=False, float_format="%.4g")
    (A.RESULTS / "part1_final_check.json").write_text(json.dumps(out, indent=1))
    print(json.dumps(out, indent=1))
    return M


if __name__ == "__main__":
    run()
