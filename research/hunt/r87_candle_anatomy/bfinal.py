"""R87B: the holdout (15 Oct 2025 - 16 Oct 2026), read ONCE for R87B's cells (PREREG_B section 0 and 6).

    R87_OUT=~/.cache/iraalgo/r87/built_b python -m research.hunt.r87_candle_anatomy.bfinal

Reads: every B1 / B2 design-tested cell in the holdout (holdout-only baseline; clusters on the fixed design centres),
the out-of-sample check of the design findings (one-sided, BH over the findings), the exit overlays that passed and
the R87B rules that passed their design gates.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone

import numpy as np
import pandas as pd

from . import batlas as BA
from . import cfg
from . import exits as X
from . import rules as R
from . import stats as ST


def findings() -> pd.DataFrame:
    parts = []
    for fam in ("B1", "B2seq", "B2cl"):
        t = pd.read_csv(BA.RES / f"batlas_design_{fam}.csv.gz")
        t = t[t.tested & (t.q < cfg.Q)].copy()
        if t.empty:
            continue
        sg = np.sign(t.eff)
        f = t.fold_eff.apply(json.loads)
        t["fold_same"] = [int(sum(1 for x in v if x is not None and np.sign(x) == s)) for v, s in zip(f, sg)]
        parts.append(t[t.fold_same >= 3].assign(fam=fam))
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def main() -> None:
    if BA.FINAL_FLAG.exists():
        raise SystemExit(f"the R87B holdout was already read ({BA.FINAL_FLAG.read_text().strip()})")
    BA.FINAL_FLAG.write_text(f"read {datetime.now(timezone.utc).isoformat(timespec='seconds')}\n")
    out = BA.run_b1(cfg.FINAL_START, cfg.END, None, None, None)
    d2 = {}
    for fam in ("B2seq",):
        t = pd.read_csv(BA.RES / f"batlas_design_{fam}.csv.gz")
        for r in t[t.tested].itertuples():
            BA.KEEP_SEQ.setdefault((r.und, int(r.tf)), set()).add(r.family[4:])
    cent = {int(k): np.array(v) for k, v in json.loads((BA.RES / "clusters_design.json").read_text()).items()}
    o2, _ = BA.run_b2(cfg.FINAL_START, cfg.END, None, None, cent)
    out.update(o2)
    summ = BA.save(out, "final")
    f = findings()
    rows = []
    for r in f.itertuples(index=False):
        h = out[r.fam]
        m = h[(h.und == r.und) & (h.tf == r.tf) & (h.family == r.family)]
        if m.empty or not np.isfinite(m.iloc[0].z):
            rows.append(dict(fam=r.fam, und=r.und, tf=r.tf, family=r.family, d_eff=r.eff, d_n=r.n, h_n=0))
            continue
        x = m.iloc[0]
        same = np.sign(x.eff) == np.sign(r.eff)
        rows.append(dict(fam=r.fam, und=r.und, tf=r.tf, family=r.family, d_eff=r.eff, d_n=r.n, d_q=r.q, h_n=int(x.n),
                         h_eff=float(x.eff), h_p1=float(x.p / 2 if same else 1 - x.p / 2), h_move_pts=float(x.move_pts),
                         h_up_rate=float(x.up_rate), h_base_up_rate=float(x.base_up_rate)))
    o = pd.DataFrame(rows)
    if len(o) and "h_p1" in o:
        o["h_q"] = ST.bh(o.h_p1.to_numpy())
        o["holds"] = (np.sign(o.h_eff) == np.sign(o.d_eff)) & (o.h_q < cfg.Q)
    o.to_csv(BA.RES / "boos_findings.csv", index=False, float_format="%.6g")
    res = dict(atlas=summ, design_findings=int(len(o)), holds=int(o.get("holds", pd.Series(dtype=bool)).sum()))
    # exit overlays that passed
    ex = json.loads((BA.RES / "exits_design.json").read_text()) if (BA.RES / "exits_design.json").exists() else []
    passed = [(e["arm"], e["overlay"]) for e in ex if e.get("pass")]
    if passed:
        tr = X.collect(cfg.FINAL_START, cfg.END)
        tr.to_csv(BA.RES / "exits_final_trades.csv", index=False, float_format="%.4f")
        ev = [e for e in X.evaluate(tr, ((cfg.FINAL_START, cfg.END),)) if (e["arm"], e["overlay"]) in passed]
        for e in ev:
            e["final_pass"] = bool(e["delta"] > 0 and e["p"] < R.GATE_P and e["variant"]["max_dd"] <= e["base"]["max_dd"])
        res["exits"] = ev
    else:
        res["exits"] = "no overlay passed design: holdout not read for overlays"
    # R87B rules that passed design
    rd = json.loads((BA.RES / "brules_design.json").read_text()) if (BA.RES / "brules_design.json").exists() else []
    rr = []
    for d in rd:
        if d.get("r87_repriced"):
            continue
        if not d.get("design_pass"):
            rr.append(dict(id=d["rule"]["id"], read=False))
            continue
        rule, stop = d["rule"], d["frozen_stop"]
        mk = R.Market(rule["und"], "hybrid")
        tr = R.run(rule, mk, R.signals(rule, cfg.FINAL_START, cfg.END), stop)
        tw = R.twins(rule, mk, tr, mk.days(cfg.FINAL_START, cfg.END), stop)
        s = R.summary(tr)
        p = float((1 + (tw >= s["net"]).sum()) / (len(tw) + 1))
        tr.to_csv(BA.RES / f"brules_final_trades_{rule['id']}.csv", index=False, float_format="%.4f")
        rr.append(dict(id=rule["id"], read=True, stop=stop, **s, twin_mean=float(tw.mean()), twin_p=p,
                       real_entry_share=float(tr.real_entry.mean()) if len(tr) else None,
                       final_pass=bool(s["net"] > 0 and p < R.GATE_P and s["top5_removed"] > 0
                                       and s["trades"] >= R.MIN_TRADES)))
    res["rules"] = rr
    (BA.RES / "bfinal_summary.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    main()
