"""R131 report tables from results/wf.json and results/holdout.json -> results/report.md (pasted into the write-up)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r131cfg as K  # noqa: E402


def pct(x):
    return "-" if x is None or not np.isfinite(x) else f"{100 * x:.1f}%"


def rs(x):
    return "-" if x is None or not np.isfinite(x) else f"{x:+,.0f}"


def main():
    H = json.loads((K.RESULTS / "holdout.json").read_text())
    res = H["results"]
    rows = []
    for r in res:
        h = r.get("holdout") or {}
        g = r.get("gate") or {}
        rows.append(dict(H=r["H"], set=r["set"], model=r["model"], k=r["k"], n=r["n"], days=r["days"], hit=r["hit"],
                         p0=r["p0"], q=r["q"], auc=r["auc"], hit_big=r["hit_big"], n_big=r["n_big"],
                         up=r["up_calls"], **{f: r["folds"].get(f, {}).get("hit", np.nan) for f in K.FOLDS},
                         **{"n" + f: r["folds"].get(f, {}).get("n", 0) for f in K.FOLDS},
                         h_n=h.get("n", 0), h_hit=h.get("hit", np.nan), h_p0=h.get("p0", np.nan),
                         h_big=h.get("hit_big", np.nan), h_nbig=h.get("n_big", 0),
                         wf_pass=all(v for kk, v in r["gate_wf"].items() if kk not in ("holdout", "pass")),
                         passed=bool(g.get("pass")), twin=g.get("twin_pass"), counts=bool(g.get("counts"))))
    d = pd.DataFrame(rows)
    out = []
    out.append(f"tests: {len(d)}; walk-forward gate passes: {int(d.wf_pass.sum())}; all gates incl. holdout: "
               f"{int(d.passed.sum())}; counting (tradeable + twin rule): {int(d.counts.sum())}\n")
    out.append("| horizon | tests | WF passes | + holdout | counts |\n|---|---|---|---|---|")
    for h in K.HORIZONS:
        x = d[d.H == h]
        out.append(f"| {h} | {len(x)} | {int(x.wf_pass.sum())} | {int(x.passed.sum())} | {int(x.counts.sum())} |")
    out.append("\n### Base rates and baselines (F1-F4 pooled; holdout)\n")
    out.append("| horizon | up share basis p0 | B0 majority | B1 follow | B2 fade | holdout p0 | holdout B1 |"
               "\n|---|---|---|---|---|---|---|")
    for h in K.HORIZONS:
        b = d[(d.H == h) & (d.set == "BASE")].set_index("model")
        out.append(f"| {h} | {pct(b.p0.iloc[0])} (n {b.n.max():,}) | {pct(b.loc['B0', 'hit'])} | "
                   f"{pct(b.loc['B1', 'hit'])} | {pct(b.loc['B2', 'hit'])} | {pct(b.h_p0.iloc[0])} (n {b.h_n.max():,}) | "
                   f"{pct(b.loc['B1', 'h_hit'])} |")
    out.append("\n### Best model per horizon, all calls (k = 100%): AUC and hit (F1-F4 pooled; holdout)\n")
    out.append("| horizon | best AUC test | AUC | hit | p0 | q | holdout hit (n) |\n|---|---|---|---|---|---|---|")
    for h in K.HORIZONS:
        x = d[(d.H == h) & (d.k == 1.0) & (d.set != "BASE")].sort_values("auc", ascending=False).iloc[0]
        out.append(f"| {h} | {x.set} {x.model} | {x.auc:.3f} | {pct(x.hit)} (n {x.n:,}) | {pct(x.p0)} | {x.q:.3f} | "
                   f"{pct(x.h_hit)} ({x.h_n:,}) |")
    out.append("\n### Most selective calls: best F1-F4 hit per horizon at 10% and 20% coverage (n >= 100)\n")
    out.append("| horizon | k | test | n (days) | hit | p0 | q | F1 / F2 / F3 / F4 | hit excl. tiny (n) | holdout hit (n) |"
               " holdout excl. tiny (n) | up calls |\n|---|---|---|---|---|---|---|---|---|---|---|---|")
    for h in K.HORIZONS:
        for k in (0.2, 0.1):
            x = d[(d.H == h) & (d.k == k) & (d.n >= 100)]
            if x.empty:
                out.append(f"| {h} | {int(k * 100)}% | (no test with n >= 100) | | | | | | | | | |")
                continue
            x = x.sort_values("hit", ascending=False).iloc[0]
            out.append(f"| {h} | {int(k * 100)}% | {x.set} {x.model} | {x.n:,} ({x.days:,}) | {pct(x.hit)} | {pct(x.p0)} | "
                       f"{x.q:.3f} | {' / '.join(pct(x[f]) for f in K.FOLDS)} | {pct(x.hit_big)} ({x.n_big:,}) | "
                       f"{pct(x.h_hit)} ({x.h_n:,}) | {pct(x.h_big)} ({x.h_nbig:,}) | {pct(x.up)} |")
    out.append("\n### What each source added over IDX (mean over the 3 models; F1-F4 pooled)\n")
    out.append("AUC at k = 100% and hit at k = 20%, as differences from the IDX set, in percentage points.\n")
    srcs = [s for s in K.SETS if s != "IDX"]
    out.append("| horizon | " + " | ".join(srcs) + " |\n|---|" + "---|" * len(srcs))
    for h in K.HORIZONS:
        x = d[d.H == h]
        base_a = x[(x.set == "IDX") & (x.k == 1.0)].auc.mean()
        base_h = x[(x.set == "IDX") & (x.k == 0.2)].hit.mean()
        cells = []
        for s in srcs:
            a = x[(x.set == s) & (x.k == 1.0)].auc.mean() - base_a
            hh = x[(x.set == s) & (x.k == 0.2)].hit.mean() - base_h
            cells.append(f"{100 * a:+.1f} / {100 * hh:+.1f}")
        out.append(f"| {h} | " + " | ".join(cells) + " |")
    out.append(f"\n(IDX alone, mean of 3 models: " + "; ".join(
        f"{h} AUC {d[(d.H == h) & (d.set == 'IDX') & (d.k == 1.0)].auc.mean():.3f}" for h in K.HORIZONS) + ")")
    p = d[d.passed].sort_values(["H", "hit"], ascending=[True, False])
    out.append(f"\n### Every test that passed all gates incl. the holdout ({len(p)})\n")
    out.append("| horizon | test | k | n | hit | p0 | q | F1 / F2 / F3 / F4 | holdout hit (n) | hold. p0 | twin passes |"
               "\n|---|---|---|---|---|---|---|---|---|---|---|")
    for x in p.itertuples():
        out.append(f"| {x.H} | {x.set} {x.model} | {int(x.k * 100)}% | {x.n:,} | {pct(x.hit)} | {pct(x.p0)} | {x.q:.4f} | "
                   f"{' / '.join(pct(getattr(x, f)) for f in K.FOLDS)} | {pct(x.h_hit)} ({x.h_n:,}) | {pct(x.h_p0)} | "
                   f"{'-' if x.twin is None else ('yes' if x.twin else 'no')} |")
    out.append("\n### Money check (1 lot, real charges and fills)\n")
    out.append("| test | part | trades | skipped | win | mean Rs | total Rs | worst month Rs |\n|---|---|---|---|---|---|---|---|")
    for key in sorted(H["money"]["wf"]):
        for part in ("wf", "holdout"):
            m = H["money"][part].get(key, {})
            a = m.get("all", {})
            out.append(f"| {key} | {part} | {a.get('trades', 0):,} | {m.get('skipped', 0):,} | {pct(a.get('win'))} | "
                       f"{rs(a.get('mean'))} | {rs(a.get('total'))} | {rs(a.get('worst_month'))} |")
    (K.RESULTS / "report.md").write_text("\n".join(out) + "\n")
    print("\n".join(out))


if __name__ == "__main__":
    main()
