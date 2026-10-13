#!/usr/bin/env python3
"""R23 Step 1: the anatomy of good-break and fakeout mornings (PREREG.md part A2). Design data only.

    python3 research/hunt/r23/r23anatomy.py          # -> results/anatomy.md|json; per-break rows in <cache>/r23
    R23_SOURCE=lake python3 research/hunt/r23/r23anatomy.py     # the same on the lake's DESIGN sessions (results/lake/)

13 Oct 2026.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd
from scipy import stats as SS

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r23cfg as CFG  # noqa: E402
import r23data as DATA  # noqa: E402
import r23features as F  # noqa: E402
import r23ranges as R  # noqa: E402

REFS = {"ORB": lambda ix: R.time_range(ix, 50), "R30": lambda ix: R.time_range(ix, 30)}
Q_MAX, AUC_MIN = 0.10, 0.05


def break_rows(und: str) -> list[dict]:
    rows = []
    for s in DATA.sessions(und):
        if CFG.period_of(s.day) != "design":
            continue
        for ref, mk in REFS.items():
            rg = mk(s.ix)
            b = R.first_break_any(s.ix, rg, 5)
            base = dict(und=und, day=s.day, ref=ref)
            if b is None:
                rows.append(dict(base, cls="NONE"))
                continue
            side, k, row = b
            out = F.outcome(s.ix, rg, side, row, s.atr)
            rows.append(dict(base, side=side, row=row, **out, **F.features(s, rg, side, row)))
    return rows


def auc_p(a: np.ndarray, b: np.ndarray):
    a, b = a[np.isfinite(a)], b[np.isfinite(b)]
    if len(a) < 5 or len(b) < 5:
        return np.nan, np.nan, len(a), len(b)
    u, p = SS.mannwhitneyu(a, b, alternative="two-sided")
    return float(u / (len(a) * len(b))), float(p), len(a), len(b)


def bh_q(p: np.ndarray) -> np.ndarray:
    p = np.asarray(p, float)
    ok = np.isfinite(p)
    q = np.full(len(p), np.nan)
    ps = p[ok]
    m = len(ps)
    if m == 0:
        return q
    o = np.argsort(ps)
    qq = np.minimum.accumulate((ps[o] * m / np.arange(1, m + 1))[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(qq, 1.0)
    q[ok] = out
    return q


def feature_table(ev: pd.DataFrame, ref: str) -> pd.DataFrame:
    e = ev[(ev.ref == ref) & ev.cls.isin(["RUN", "FAKE"])]
    days = sorted(e.day.unique())
    half = days[len(days) // 2] if days else None
    rows = []
    for f in F.FEATURES:
        run, fake = e[e.cls == "RUN"][f].astype(float).values, e[e.cls == "FAKE"][f].astype(float).values
        auc, p, n1, n2 = auc_p(run, fake)
        r = dict(feature=f, n_run=n1, n_fake=n2, med_run=np.nanmedian(run) if n1 else np.nan,
                 med_fake=np.nanmedian(fake) if n2 else np.nan, auc=auc, p=p)
        for lab, sub in (("h1", e[e.day < half]), ("h2", e[e.day >= half]), ("BANKNIFTY", e[e.und == "BANKNIFTY"]),
                         ("NIFTY", e[e.und == "NIFTY"]), ("FINNIFTY", e[e.und == "FINNIFTY"])):
            a, _, _, _ = auc_p(sub[sub.cls == "RUN"][f].astype(float).values, sub[sub.cls == "FAKE"][f].astype(float).values)
            r[f"auc_{lab}"] = a
        rows.append(r)
    t = pd.DataFrame(rows)
    t["q"] = bh_q(t.p.values)
    return t


def qualify(t_orb: pd.DataFrame, t_r30: pd.DataFrame) -> pd.DataFrame:
    """Part A4's rule: q < 0.10; the same side of 0.5 in both halves, on BANKNIFTY and on NIFTY, and on R30; |AUC-0.5| >= 0.05."""
    t = t_orb.copy()
    r30 = t_r30.set_index("feature").auc
    ok = []
    for _, r in t.iterrows():
        s = np.sign(r.auc - 0.5)
        same = all(np.isfinite(r[c]) and np.sign(r[c] - 0.5) == s for c in ("auc_h1", "auc_h2", "auc_BANKNIFTY", "auc_NIFTY"))
        same = same and np.isfinite(r30.get(r.feature, np.nan)) and np.sign(r30[r.feature] - 0.5) == s
        ok.append(bool(np.isfinite(r.q) and r.q < Q_MAX and same and abs(r.auc - 0.5) >= AUC_MIN))
    t["auc_R30"] = t.feature.map(r30)
    t["qualifies"] = ok
    return t


def class_table(ev: pd.DataFrame) -> list[dict]:
    out = []
    for (ref, und), g in ev.groupby(["ref", "und"]):
        b = g[g.cls != "NONE"]
        out.append(dict(ref=ref, und=und, sessions=len(g), breaks=len(b), none=int((g.cls == "NONE").sum()),
                        RUN=int((b.cls == "RUN").sum()), FAKE=int((b.cls == "FAKE").sum()), CHOP=int((b.cls == "CHOP").sum()),
                        r10=int(b.r10.sum()), r15=int(b.r15.sum()), up=int((b.side > 0).sum())))
    return out


def tercile_table(ev: pd.DataFrame, ref: str = "ORB") -> list[dict]:
    e = ev[(ev.ref == ref) & (ev.cls != "NONE")]
    out = []
    for f in F.FEATURES:
        x = e[f].astype(float)
        ok = x.notna()
        if ok.sum() < 30:
            continue
        if f in F.BOOL or x[ok].nunique() <= 3:
            groups = [(f"={v:g}", e[ok & (x == v)]) for v in sorted(x[ok].unique())]
        else:
            c1, c2 = np.nanquantile(x, [1 / 3, 2 / 3])
            groups = [(f"<{c1:.3g}", e[ok & (x < c1)]), (f"{c1:.3g}-{c2:.3g}", e[ok & (x >= c1) & (x < c2)]),
                      (f">={c2:.3g}", e[ok & (x >= c2)])]
        for lab, g in groups:
            out.append(dict(feature=f, bucket=lab, n=len(g), run=float((g.cls == "RUN").mean()) if len(g) else np.nan,
                            fake=float((g.cls == "FAKE").mean()) if len(g) else np.nan,
                            mfe=float(g.mfe.mean()) if len(g) else np.nan))
    return out


def fmt(v, d=3):
    if isinstance(v, (bool, np.bool_)):
        return "yes" if v else "no"
    if isinstance(v, (int, np.integer)):
        return f"{v:,}"
    if isinstance(v, (float, np.floating)):
        return "n/a" if not np.isfinite(v) else f"{v:.{d}f}"
    return str(v)


def md(rows, cols):
    out = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)]
    for r in rows:
        out.append("| " + " | ".join(fmt(r.get(c)) for c in cols) + " |")
    return "\n".join(out)


def main():
    rows = []
    for u in CFG.EVENT_UNDS:
        rows += break_rows(u)
        print(u, len(rows), flush=True)
    ev = pd.DataFrame(rows)
    os.makedirs(CFG.OUT, exist_ok=True)
    ev.to_pickle(os.path.join(CFG.OUT, "anatomy_rows.pkl"))
    t_orb, t_r30 = feature_table(ev, "ORB"), feature_table(ev, "R30")
    tq = qualify(t_orb, t_r30)
    cls = class_table(ev)
    ter = tercile_table(ev)
    q = tq[tq.qualifies].assign(dev=lambda x: (x.auc - 0.5).abs()).sort_values("dev", ascending=False)
    picks = []
    for _, r in q.head(2).iterrows():
        e = ev[(ev.ref == "ORB") & (ev.cls != "NONE")]
        if r.feature in F.BOOL:
            sh = {v: (e[e[r.feature] == v].cls == "RUN").mean() for v in (0.0, 1.0)}
            picks.append(dict(feature=r.feature, kind="bool", good=max(sh, key=sh.get), auc=r.auc))
        else:
            picks.append(dict(feature=r.feature, kind="median", good="above" if r.auc > 0.5 else "below",
                              median=float(np.nanmedian(e[r.feature].astype(float))), auc=r.auc))
    res = dict(classes=cls, features_orb=tq.to_dict("records"), features_r30=t_r30.to_dict("records"), terciles=ter,
               picks=picks)
    import r22study as S22
    os.makedirs(CFG.RESULTS, exist_ok=True)
    json.dump(S22.jsonable(res), open(os.path.join(CFG.RESULTS, "anatomy.json"), "w"), indent=1)
    cols_f = ["feature", "n_run", "n_fake", "med_run", "med_fake", "auc", "p", "q", "auc_h1", "auc_h2", "auc_BANKNIFTY",
              "auc_NIFTY", "auc_FINNIFTY", "auc_R30", "qualifies"]
    txt = ["# R23 Step 1: morning anatomy (design data only)", "",
           "RUN = MFE >= 0.5 ATR14 by 14:59; FAKE = a 5-min close back inside within 30 min and MFE < 0.25 ATR14; CHOP = the "
           "rest. r10 / r15 = MFE >= 1.0 / 1.5 ATR14. AUC = P(a RUN's value > a FAKE's value).", "",
           "## Classes", "", md(cls, ["ref", "und", "sessions", "breaks", "none", "RUN", "FAKE", "CHOP", "r10", "r15", "up"]),
           "", "## Features, ORB range (RUN vs FAKE)", "", md(tq.to_dict("records"), cols_f), "",
           "## Features, R30 range (RUN vs FAKE)", "", md(t_r30.to_dict("records"), cols_f[:-2]), "",
           "## RUN / FAKE share by feature bucket (ORB range, all breaks)", "",
           md(ter, ["feature", "bucket", "n", "run", "fake", "mfe"]), "", "## Part B picks", "", json.dumps(S22.jsonable(picks))]
    open(os.path.join(CFG.RESULTS, "anatomy.md"), "w").write("\n".join(txt) + "\n")
    print(json.dumps(S22.jsonable(picks)))
    return res


if __name__ == "__main__":
    main()
