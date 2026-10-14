"""F1 report tables (markdown) from results/: the screen by feature, survivors, and every rule (design, folds,
holdout). Writes results/tables.md, which the write-up quotes. The full screen (every test) is results/screen.csv.gz.

    python research/hunt/f1/report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import f1cfg as K  # noqa: E402
import screen as S  # noqa: E402

TG = {"ret15": "15m", "ret30": "30m", "ret60": "60m", "net_ce_atm_x1": "CE x1", "net_pe_atm_x1": "PE x1",
      "net_ce_atm_x2": "CE x2", "net_pe_atm_x2": "PE x2", "abs30": "size30"}


def rs(x):
    return "" if x is None or not np.isfinite(x) else f"{x:,.0f}"


def by_feature(R: pd.DataFrame) -> pd.DataFrame:
    """One row per feature: pooled selection-split IC per target (direction-signed), the best q anywhere, survivors."""
    s = R[R.kind == "single"].copy()
    s["fname"] = s.feature
    pooled = s[s.scope == "POOLED"]
    rows = []
    for f, g in s.groupby("fname"):
        pg = pooled[pooled.fname == f]
        row = dict(feature=f, family=S_FAMILY.get(f, ""))
        for t, lab in TG.items():
            x = pg[pg.target == t]
            # levels: the strongest level
            x = x.loc[x.t_sel.abs().idxmax()] if len(x) and x.t_sel.notna().any() else None
            row[lab] = np.nan if x is None else float(x.ic_sel)      # already in the fit split's direction
        row["best_q"] = float(g.q.min()) if g.q.notna().any() else np.nan
        row["survivors"] = int(g.survivor.sum())
        row["no_data_in"] = ",".join(sorted(set(g[g.get("no_data", False) == True].scope)))  # noqa: E712
        rows.append(row)
    return pd.DataFrame(rows).sort_values(["survivors", "best_q"], ascending=[False, True])


S_FAMILY: dict = {}


def md_table(df: pd.DataFrame, cols, fmt=None) -> str:
    fmt = fmt or {}
    out = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for _, r in df.iterrows():
        cells = []
        for c in cols:
            v = r[c]
            f = fmt.get(c)
            if f:
                cells.append(f(v))
            elif isinstance(v, float):
                cells.append("" if not np.isfinite(v) else f"{v:.3f}")
            else:
                cells.append(str(v))
        out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out)


def rule_rows(design, hold):
    H = {h["id"]: h for h in hold} if hold else {}
    rows = []
    for d in design:
        r, m = d["rule"], d["design"]
        h = H.get(r["id"], {})
        hm = h.get("holdout", {})
        rows.append(dict(
            id=r["id"], rule=r["name"], scope="all 5" if len(r["scope"]) == 5 else ",".join(r["scope"]),
            d_trades=m["trades"], d_days=m["days"], d_tpd=m["trades_per_day"], d_win=m["win_rate"],
            d_avgw=m["avg_win"], d_avgl=m["avg_loss"], d_pnl_day=m["pnl_per_day"], d_green=m["green_days_pct"],
            d_worst=m["worst_day"], d_dd=m["max_dd"], d_cut=m["cut_short"], d_net=m["net"], d_p=m["p_twin"],
            folds=" / ".join(rs(d["folds"][f]["net"]) for f in K.FOLDS), folds_pos=d["gate"]["folds_pos"],
            d_pass=d["gate"]["pass"],
            h_trades=hm.get("trades"), h_days=hm.get("days"), h_tpd=hm.get("trades_per_day"), h_win=hm.get("win_rate"),
            h_avgw=hm.get("avg_win"), h_avgl=hm.get("avg_loss"), h_pnl_day=hm.get("pnl_per_day"),
            h_green=hm.get("green_days_pct"), h_worst=hm.get("worst_day"), h_dd=hm.get("max_dd"),
            h_cut=hm.get("cut_short"), h_net=hm.get("net"), h_p=hm.get("p_twin"), h_q=h.get("q_bh"),
            h_pass=h.get("gate", {}).get("pass")))
    return pd.DataFrame(rows)


def main():
    from research.brain.features import REGISTRY, load_all
    load_all()
    S_FAMILY.update({n: f.family for n, f in REGISTRY.items()})
    R = pd.read_csv(K.RESULTS / "screen.csv.gz") if (K.RESULTS / "screen.csv.gz").exists() else pd.read_csv(
        K.RESULTS / "screen.csv")
    out = []
    summ = json.loads((K.RESULTS / "screen_summary.json").read_text())
    out.append(f"Screen: {summ['tests']:,} tests, {summ['survivors']} survivors (BH q <= {K.BH_Q}, |IC| >= {K.MIN_IC}, "
               f"same sign). Design days with a chain: {summ['days']}.\n")
    BF = by_feature(R)
    BF.to_csv(K.RESULTS / "screen_by_feature.csv", index=False, float_format="%.4g")
    sv = R[R.survivor].sort_values("t_sel", ascending=False)
    sv = sv.assign(lvl=sv.level.map(lambda v: "" if pd.isna(v) else f"{v:g}"), tgt=sv.target.map(TG))
    out.append("### Survivors\n")
    out.append(md_table(sv, ["scope", "tgt", "feature", "lvl", "ic_fit", "ic_sel", "t_sel", "q", "n_rows", "n_days"],
                        dict(t_sel=lambda v: f"{v:.2f}", q=lambda v: f"{v:.4f}", n_rows=lambda v: f"{int(v):,}",
                             n_days=lambda v: f"{int(v)}")) if len(sv) else "(none)")
    out.append("\n### Every feature (pooled over the five indices; selection-split IC in the fit split's direction; "
               "best q over every scope and target; survivors anywhere)\n")
    out.append(md_table(BF, ["feature", "family"] + list(TG.values()) + ["best_q", "survivors"],
                        {**{v: (lambda x: "" if not np.isfinite(x) else f"{x:+.3f}") for v in TG.values()},
                         "best_q": lambda x: "" if not np.isfinite(x) else f"{x:.3f}"}))
    design = json.loads((K.RESULTS / "design.json").read_text())
    hold = json.loads((K.RESULTS / "holdout.json").read_text()) if (K.RESULTS / "holdout.json").exists() else None
    RR = rule_rows(design, hold)
    RR.to_csv(K.RESULTS / "rules_table.csv", index=False, float_format="%.4g")
    f = {c: rs for c in RR.columns if c.endswith(("avgw", "avgl", "pnl_day", "worst", "dd", "net"))}
    f.update({c: (lambda v: "" if v is None or not np.isfinite(v) else f"{v:.2f}") for c in ("d_tpd", "h_tpd")})
    f.update({c: (lambda v: "" if v is None or not np.isfinite(v) else f"{v * 100:.0f}%") for c in ("d_win", "h_win")})
    f.update({c: (lambda v: "" if v is None or not np.isfinite(v) else f"{v:.0f}%") for c in ("d_green", "h_green")})
    f.update({c: (lambda v: "" if v is None or not np.isfinite(v) else f"{v:.3f}") for c in ("d_p", "h_p", "h_q")})
    out.append("\n### Rules: design (walk-forward folds 2022 / 2024H2 / 2025 / 2026 together)\n")
    out.append(md_table(RR, ["id", "rule", "scope", "d_trades", "d_days", "d_tpd", "d_win", "d_avgw", "d_avgl",
                             "d_pnl_day", "d_green", "d_worst", "d_dd", "d_cut", "d_net", "folds", "d_p", "d_pass"], f))
    if hold:
        out.append("\n### Rules: holdout (1 Jan 2023 - 30 Jun 2024, read once)\n")
        out.append(md_table(RR, ["id", "h_trades", "h_days", "h_tpd", "h_win", "h_avgw", "h_avgl", "h_pnl_day",
                                 "h_green", "h_worst", "h_dd", "h_cut", "h_net", "h_p", "h_q", "h_pass"], f))
    (K.RESULTS / "tables.md").write_text("\n".join(out) + "\n")
    print(f"wrote {K.RESULTS / 'tables.md'}")


if __name__ == "__main__":
    main()
