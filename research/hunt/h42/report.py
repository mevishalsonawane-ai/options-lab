"""h42: answers_raw.csv -> answers.csv (rounded, with a plain-language answer per question)."""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import HERE  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402


def unit(q):
    o, k = q.outcome, q.kind
    if k == "bin":
        return "share"
    if "strad" in o:
        return "%"
    if o.startswith(("abs(f", "f", "frng")):
        return "bps"
    if "j" in o and "d" in o:
        return "net jump share"
    return "%"


def fmt(x, u):
    if not np.isfinite(x):
        return "n/a"
    return f"{x * 100:.1f}%" if u == "share" else (f"{x:.3f}" if u == "net jump share" else f"{x:.2f}{'' if u == '%' else ' '}{u}")


def main():
    Q = pd.read_csv(os.path.join(HERE, "questions.csv"), dtype=str, keep_default_na=False)
    A = pd.read_csv(os.path.join(HERE, "answers_raw.csv"))
    A = A.merge(Q[["id", "outcome", "kind", "ref", "side"]], on="id")
    txt = []
    for _, r in A.iterrows():
        u = unit(r)
        if not np.isfinite(r.get("effect", np.nan)):
            txt.append(f"too few cases (n={int(r.n_cond) if np.isfinite(r.n_cond) else 0})")
            continue
        other = "reference" if r.ref.startswith("const") else "otherwise"
        sig = "YES (significant after BH)" if r.q < 0.05 else ("weak (raw p<0.05, not after BH)" if r.p < 0.05 else "no clear difference")
        s = (f"{sig}: {fmt(r.cond_mean, u)} vs {fmt(r.comp_mean, u)} {other} (n={int(r.n_cond)}, {int(r.n_cond_days)} days); "
             f"diff {fmt(r.effect, u if u != 'share' else 'share')} [95% CI {fmt(r.ci_lo, u)} .. {fmt(r.ci_hi, u)}], p={r.p:.2g}, q={r.q:.2g}")
        if r.side == "none":
            s += ". Option: descriptive only."
        elif np.isfinite(r.get("best_net", np.nan)):
            s += (f". Option ({r.side_used}): own-horizon exit net Rs {r.prim_net:.0f}/trade (gross {r.prim_gross:.0f}); "
                  f"best of 38 exits {r.best_exit} net Rs {r.best_net:.0f} (gross {r.best_gross:.0f}), vs same-minute random "
                  f"{r.best_edge:+.0f}, q_opt={r.best_q_opt:.2g} -> {'PROFITABLE' if r.candidate_strict else 'not a profitable buy'}")
        else:
            s += ". Option: too few trades."
        txt.append(s)
    A["answer"] = txt
    keep = ["id", "category", "und", "question", "answer", "n_cond", "n_cond_days", "n_comp", "base_all", "cond_mean", "comp_mean",
            "effect", "ci_lo", "ci_hi", "p", "q", "eff_h1", "eff_h2", "stable", "side_used", "trade_n", "prim_gross", "prim_net",
            "prim_p", "best_exit", "best_gross", "best_net", "best_p", "best_q_opt", "best_edge", "best_edge_p", "candidate_strict",
            "candidate_loose"]
    B = A[[c for c in keep if c in A.columns]].copy()
    for c in B.columns:
        if B[c].dtype == float:
            B[c] = B[c].map(lambda x: float(f"{x:.4g}") if np.isfinite(x) else x)
    B.to_csv(os.path.join(HERE, "answers.csv"), index=False)
    print("answers.csv", len(B), os.path.getsize(os.path.join(HERE, "answers.csv")) // 1024, "KB")


if __name__ == "__main__":
    main()
