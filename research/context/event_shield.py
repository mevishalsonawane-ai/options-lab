"""I6 Event shield: BANKNIFTY and NIFTY around RBI, Budget, US CPI / FOMC and heavyweight results (PREREG.md, I6).

    python -m research.context.event_shield     -> results/event_shield.json, prints the tables for EVENT_SHIELD.md
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats

from . import config as C, data as D, lib as L

METRICS = {"range_pct": "day range", "abs_gap_pct": "|gap|", "abs_oc_pct": "|open-close|", "fh_range_pct": "first-hour range"}
GROUPS = ("india_policy", "us_macro", "results")
TESTED = {"rbi": "RBI", "india_policy": "India policy (RBI + Budget)", "us_macro": "US macro (CPI + FOMC)", "results": "heavyweight results"}


def table(und: str):
    ss = D.load(und)
    d = D.daily(ss).dropna(subset=["prev_close"]).reset_index(drop=True)
    days = list(d.day)
    ev = L.event_sessions(und, [s["day"] for s in ss])
    d["kinds"] = d.day.map(ev.groupby("session").kind.apply(set)).apply(lambda x: x if isinstance(x, set) else set())
    d["groups"] = d.day.map(ev.groupby("session").group.apply(set)).apply(lambda x: x if isinstance(x, set) else set())
    nxt = {days[i]: days[i + 1] for i in range(len(days) - 1)}
    has_event = dict(zip(d.day, d.groups.apply(len) > 0))
    before = {x for x in days if x in nxt and has_event[nxt[x]]}
    d["eve"] = d.day.isin(before)
    d["normal"] = (d.groups.apply(len) == 0) & ~d.is_expiry & ~d.eve
    hold, h0 = L.split(d)
    d["holdout"] = hold
    return d, ev, h0


def run():
    out, ps = {}, {}
    arm = L.arm_days(D.load(C.ARM_UND))
    for und in C.UNDS:
        d, ev, h0 = table(und)
        res = dict(holdout_from=str(h0), sessions=len(d), normal=int(d.normal.sum()), events=len(ev),
                   unverified_event_sessions=int((~ev.verified).sum()) if len(ev) else 0, kinds={}, eve={})
        norm = d[d.normal]
        for key in ("rbi", "budget", "india_policy", "us_macro", "fomc", "us_cpi", "results"):
            mask = d.kinds.apply(lambda k: key in k) if key in ("rbi", "budget", "fomc", "us_cpi") else d.groups.apply(lambda g: key in g)
            g = d[mask]
            row = dict(n=len(g), n_design=int((~g.holdout).sum()), n_holdout=int(g.holdout.sum()))
            for m in METRICS:
                row[m] = dict(event_median=round(float(g[m].median()), 3) if len(g) else None,
                              normal_median=round(float(norm[m].median()), 3),
                              ratio=round(float(g[m].median() / norm[m].median()), 2) if len(g) else None)
            if key in TESTED:
                for part, sel in (("design", ~d.holdout), ("holdout", d.holdout)):
                    a, b = d[mask & sel].range_pct, d[d.normal & sel].range_pct
                    p = float(stats.mannwhitneyu(a, b, alternative="greater").pvalue) if len(a) >= 3 else float("nan")
                    row[f"p_{part}"] = p
                    row[f"ratio_{part}"] = round(float(a.median() / b.median()), 2) if len(a) else None
                ps[f"{und}:{key}"] = row["p_design"]
            res["kinds"][key] = row
        # the session before an event
        e = d[d.eve]
        res["eve"] = dict(n=len(e), **{m: dict(eve_median=round(float(e[m].median()), 3), normal_median=round(float(norm[m].median()), 3))
                                     for m in METRICS})
        out[und] = res
    # E5: the arms on event days vs normal days, and the pause counterfactual (BANKNIFTY)
    d, ev, h0 = table(C.ARM_UND)
    a = arm.merge(d[["day", "normal", "groups", "holdout"]], on="day")
    a["event"] = a.groups.apply(len) > 0
    arms = {}
    for name, g in a.groupby("arm"):
        r = {}
        for part, sel in (("design", ~g.holdout), ("holdout", g.holdout)):
            x = g[sel]
            evn, nrm = x[x.event].net, x[x.normal].net
            p = float(stats.ttest_ind(nrm, evn, equal_var=False, alternative="greater").pvalue) if len(evn) > 2 else float("nan")
            r[part] = dict(event_days=len(evn), event_net=round(float(evn.sum())), event_mean=round(float(evn.mean())),
                           normal_days=len(nrm), normal_mean=round(float(nrm.mean())), all_net=round(float(x.net.sum())),
                           paused_net=round(float(x[~x.event].net.sum())), saved=round(float(-evn.sum())), p=p)
        ps[f"arm:{name}"] = r["design"]["p"]
        arms[name] = r
    out["arms"] = arms
    q = L.bh(ps)
    out["bh"] = {k: dict(p=ps[k], q=q[k]) for k in ps}
    # holdout confirmation for design passes (same test, p < 0.10)
    conf = {}
    for k, v in out["bh"].items():
        if v["q"] < C.BH_Q:
            if k.startswith("arm:"):
                conf[k] = out["arms"][k[4:]]["holdout"]["p"] < 0.10
            else:
                u, key = k.split(":")
                conf[k] = out[u]["kinds"][key]["p_holdout"] < 0.10
    out["confirmed"] = conf
    L.save("event_shield.json", out)
    return out


if __name__ == "__main__":
    import json
    print(json.dumps(run(), indent=1, default=str)[:6000])
