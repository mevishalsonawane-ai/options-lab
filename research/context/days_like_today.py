"""I11 Days like today: the nearest past sessions by simple 09:20 features, and whether they say more about the rest of the
day than all past sessions pooled (PREREG.md, I11). Expected: no (R16 e). Also writes the small table the app ships
(results/days_<UND>.csv: one row per session - features and what happened next - so the phone can name past days like
today beyond its own few months of candles).

    python -m research.context.days_like_today   -> results/days_like_today.json, results/days_<UND>.csv
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats

from . import config as C, data as D, lib as L
from .event_shield import table

FEATS = ["gap_pct", "vix_0920", "prev_range_pct", "is_expiry", "event"]
K = 10
MIN_PAST = 40


def frame(und: str) -> pd.DataFrame:
    d, ev, _ = table(und)
    d["vix_0920"] = d.vix_open.fillna(d.prev_vix_close)
    d["event"] = (d.groups.apply(len) > 0).astype(float)
    d["is_expiry"] = d.is_expiry.astype(float)
    d["rest_range_pct"] = (np.maximum(d.high, d.c0920) - np.minimum(d.low, d.c0920)) / d.c0920 * 100   # see note below
    d["rest_ret_pct"] = (d.close - d.c0920) / d.c0920 * 100
    return d.dropna(subset=["gap_pct", "vix_0920", "prev_range_pct", "c0920"]).reset_index(drop=True)


def neighbours(past: pd.DataFrame, today: pd.Series, k: int = K) -> pd.DataFrame:
    x = past[FEATS].astype(float)
    t = today[FEATS].astype(float)
    sd = x[FEATS[:3]].std().replace(0, 1)
    z = (x[FEATS[:3]] - t[FEATS[:3]]) / sd
    dist = np.sqrt((z ** 2).sum(axis=1).to_numpy() + (x.is_expiry - t.is_expiry).to_numpy() ** 2 + (x.event - t.event).to_numpy() ** 2)
    return past.assign(dist=dist).nsmallest(k, "dist")


def run():
    out, ps = {}, {}
    arm = L.arm_days(D.load(C.ARM_UND))
    for und in C.UNDS:
        d = frame(und)
        # The rest-of-day range from 09:20 must use only bars after 09:20; the day's high/low include 09:15-09:20, so the
        # exact value is rebuilt from the minutes here.
        ss = {s["day"]: s for s in D.load(und)}
        d["rest_range_pct"] = [(np.nanmax(ss[x]["h"][6:]) - np.nanmin(ss[x]["l"][6:])) / c * 100 for x, c in zip(d.day, d.c0920)]
        rows = []
        for i in range(MIN_PAST, len(d)):
            past, t = d.iloc[:i], d.iloc[i]
            nb = neighbours(past, t)
            for tgt in ("rest_range_pct", "rest_ret_pct"):
                rows.append(dict(day=t.day, holdout=bool(t.holdout), target=tgt, actual=t[tgt],
                                 analog=nb[tgt].mean(), pooled=past[tgt].mean()))
        r = pd.DataFrame(rows)
        r["e_analog"] = (r.actual - r.analog).abs()
        r["e_pooled"] = (r.actual - r.pooled).abs()
        res = {}
        for tgt, g0 in r.groupby("target"):
            x = {}
            for part, sel in (("design", ~g0.holdout), ("holdout", g0.holdout)):
                g = g0[sel]
                diff = g.e_pooled - g.e_analog                     # > 0: analogs better
                p = float(stats.ttest_1samp(diff, 0, alternative="greater").pvalue)
                x[part] = dict(n=len(g), mae_analog=round(float(g.e_analog.mean()), 4), mae_pooled=round(float(g.e_pooled.mean()), 4),
                               skill_pct=round(float((1 - g.e_analog.mean() / g.e_pooled.mean()) * 100), 1), p=p)
            res[tgt] = x
            ps[f"{und}:{tgt}"] = x["design"]["p"]
        out[und] = res
        # the phone's table (small: ~500 rows)
        t = d[["day"] + FEATS + ["rest_range_pct", "rest_ret_pct", "range_pct", "cc_pct"]].copy()
        if und == C.ARM_UND:
            pv = arm.pivot_table(index="day", columns="arm", values="net", aggfunc="sum").rename(columns=lambda c: "net_" + c.replace(" ", "_"))
            t = t.merge(pv, left_on="day", right_index=True, how="left")
        t.round(3).to_csv(C.RESULTS / f"days_{und}.csv", index=False)
    q = L.bh(ps)
    out["bh"] = {k: dict(p=ps[k], q=q[k]) for k in ps}
    out["confirmed"] = {k: out[k.split(":")[0]][k.split(":")[1]]["holdout"]["p"] < 0.10 for k, v in out["bh"].items() if v["q"] < C.BH_Q}
    L.save("days_like_today.json", out)
    return out


if __name__ == "__main__":
    import json
    C.RESULTS.mkdir(exist_ok=True)
    print(json.dumps(run(), indent=1, default=str))
