"""R87: the locked holdout (15 Oct 2025 - 16 Oct 2026), read ONCE (PREREG sections 2, 7, 8.6).

    python -m research.hunt.r87_candle_anatomy.final

Writes results/FINAL_READ first; a second run raises. Reads: the holdout atlas (every cell, holdout-only baseline),
the out-of-sample check of every design finding, and the rules that passed their design gates (if any).
"""
from __future__ import annotations

import json
import math
from datetime import datetime, timezone

import numpy as np
import pandas as pd

from . import atlas as AT
from . import cfg
from . import rules as R
from . import stats as ST


def design_findings(tag: str = "design") -> pd.DataFrame:
    parts = []
    for fam in ("P1", "P2", "P3"):
        t = pd.read_csv(cfg.RESULTS / f"atlas_{tag}_{fam}.csv.gz")
        t = t[t.tested & (t.q < cfg.Q)].copy()
        if t.empty:
            continue
        sg = np.sign(t.eff)
        folds = t.fold_eff.apply(json.loads)
        t["fold_same"] = [int(sum(1 for x in f if x is not None and np.sign(x) == s)) for f, s in zip(folds, sg)]
        t = t[t.fold_same >= 3]
        parts.append(t.assign(fam=fam))
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def oos(findings: pd.DataFrame, hold: dict) -> pd.DataFrame:
    """Each design finding re-measured in the holdout; one-sided p in the design direction; BH over the findings."""
    rows = []
    for r in findings.itertuples(index=False):
        h = hold[r.fam]
        m = h[(h.und == r.und) & (h.tf == r.tf) & (h.family == r.family) & (h.cond.fillna("") == (r.cond if
                                                                                               isinstance(r.cond, str) else ""))]
        if m.empty or not np.isfinite(m.iloc[0].z):
            rows.append(dict(fam=r.fam, und=r.und, tf=r.tf, family=r.family, cond=r.cond, d_eff=r.eff, d_n=r.n,
                             d_q=r.q, h_n=int(m.iloc[0].n) if len(m) else 0, h_eff=math.nan, h_p1=math.nan))
            continue
        x = m.iloc[0]
        same = np.sign(x.eff) == np.sign(r.eff)
        p1 = x.p / 2 if same else 1 - x.p / 2
        rows.append(dict(fam=r.fam, und=r.und, tf=r.tf, family=r.family, cond=r.cond, d_eff=r.eff, d_n=r.n, d_q=r.q,
                         d_move_pts=r.move_pts, h_n=int(x.n), h_eff=float(x.eff), h_z=float(x.z), h_p1=float(p1),
                         h_move_pts=float(x.move_pts), h_up_rate=float(x.up_rate), h_base_up_rate=float(x.base_up_rate)))
    t = pd.DataFrame(rows)
    if t.empty:
        return t
    t["h_q"] = ST.bh(t.h_p1.to_numpy())
    t["holds"] = (np.sign(t.h_eff) == np.sign(t.d_eff)) & (t.h_q < cfg.Q)
    return t


def main() -> None:
    if cfg.FINAL_FLAG.exists():
        raise SystemExit(f"the holdout was already read ({cfg.FINAL_FLAG.read_text().strip()}); a second read is refused")
    cfg.FINAL_FLAG.write_text(f"read {datetime.now(timezone.utc).isoformat(timespec='seconds')}\n")
    df = AT.load_all(cfg.FINAL_START, cfg.END)
    out = AT.atlas(df)
    AT.save(out, "final")
    pth = AT.paths(df, AT.FAMS, purge_after=None)
    (cfg.RESULTS / "paths_final.json").write_text(json.dumps(pth))
    f = design_findings()
    o = oos(f, out)
    o.to_csv(cfg.RESULTS / "oos_findings.csv", index=False, float_format="%.6g")
    res = dict(design_findings=int(len(o)), holds=int(o.holds.sum()) if len(o) else 0)
    # rules: only those that passed their design gates
    rd = json.loads((cfg.RESULTS / "rules_design.json").read_text()) if (cfg.RESULTS / "rules_design.json").exists() else []
    rr = []
    for d in rd:
        if not d.get("design_pass"):
            rr.append(dict(id=d["rule"]["id"], read=False, why="failed design gates: holdout not read for it"))
            continue
        rule, stop = d["rule"], d["frozen_stop"]
        mk = R.Market(rule["und"])
        sig = R.signals(rule, cfg.FINAL_START, cfg.END)
        tr = R.run(rule, mk, sig, stop)
        days = mk.days(cfg.FINAL_START, cfg.END)
        tw = R.twins(rule, mk, tr, days, stop)
        s = R.summary(tr)
        p = float((1 + (tw >= s["net"]).sum()) / (len(tw) + 1))
        ok = s["net"] > 0 and p < R.GATE_P and s["top5_removed"] > 0 and s["trades"] >= R.MIN_TRADES
        tr.to_csv(cfg.RESULTS / f"rules_final_trades_{rule['id']}.csv", index=False, float_format="%.4f")
        rr.append(dict(id=rule["id"], read=True, stop=stop, **s, twin_mean=float(tw.mean()), twin_p=p, final_pass=ok))
    res["rules"] = rr
    from . import futcheck, wickcheck
    try:
        res["futures_check"] = [futcheck.check(u) for u in cfg.UNDS]   # descriptive only (PREREG section 1)
    except Exception as e:                                             # a descriptive extra must not end the read
        res["futures_check"] = f"failed: {type(e).__name__}: {e}"
    try:
        (cfg.RESULTS / "wick_check_final.json").write_text(json.dumps(wickcheck.run(cfg.FINAL_START, cfg.END), indent=1))
    except Exception as e:
        res["wick_check"] = f"failed: {type(e).__name__}: {e}"
    (cfg.RESULTS / "final_summary.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main()
