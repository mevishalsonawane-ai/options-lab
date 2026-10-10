"""h1 report numbers: chosen variant on the walk-forward (pre-holdout) and on the locked holdout.

python3 -I research/hunt/h1/report.py   -> prints markdown fragments used in research/HUNT_H1.md, writes report.json
"""
from __future__ import annotations

import json
import math
import os
import pickle
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obuy import config as _C  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import model as Mo  # noqa: E402
from obuy import data as D  # noqa: E402
from obuy import stats as ST  # noqa: E402

OUT = Mo.OUT
TARGET = 5000.0


def block(t, days, label):
    """Stats of a trade table over `days` (all trading days in the period) at 1 lot, and at the size for Rs 5k/day."""
    res = {}
    t = t.copy()
    t["month"] = pd.to_datetime(t.day).dt.to_period("M")
    for col in ("gross", "net", "nets"):
        d = t.groupby("day")[col].sum().reindex(days, fill_value=0.0)
        mon = d.groupby(pd.to_datetime(pd.Series(d.index)).dt.to_period("M").values).sum()
        eq = d.cumsum().values
        per_day = float(d.mean())
        lots = math.ceil(TARGET / per_day) if per_day > 0 else None
        rng = np.random.default_rng(3)
        sims = rng.choice(d.values, size=(20000, 21), replace=True).sum(axis=1)
        res[col] = dict(total=float(d.sum()), per_day=per_day, per_trade=float(t[col].mean()) if len(t) else np.nan,
                        win=float((t[col] > 0).mean()) if len(t) else np.nan, max_dd=ST.max_dd(eq),
                        worst_day=float(d.min()), worst_month=float(mon.min()), worst_month_at=str(mon.idxmin()),
                        months_neg=int((mon < 0).sum()), months=int(len(mon)),
                        p_losing_month=float((sims < 0).mean()), lots_for_5k=lots,
                        per_year={int(k): float(v) for k, v in t.groupby("year")[col].sum().items()})
    res["trades"] = len(t)
    res["days"] = len(days)
    res["trades_per_day"] = len(t) / max(len(days), 1)
    res["prem_p95"] = float(np.percentile(t.prem, 95)) if len(t) else np.nan
    res["prem_max"] = float(t.prem.max()) if len(t) else np.nan
    res["charges_per_trade"] = float(t.charges.mean()) if len(t) else np.nan
    res["by_und"] = t.groupby("und")[["gross", "net"]].sum().rename(index=Mo.UNAME).round(0).to_dict()
    res["by_side"] = t.groupby("side")[["gross", "net"]].sum().round(0).to_dict()
    res["by_hour"] = t.groupby(t.s // 60)[["gross", "net"]].sum().round(0).to_dict()
    return res


def main():
    R, X, pnl = Mo.load()
    sel = json.load(open(os.path.join(OUT, "select.json")))
    ch = sel["choice"]
    trades = pickle.load(open(os.path.join(OUT, "wf_trades.pkl"), "rb"))
    allres = pickle.load(open(os.path.join(OUT, "wf.pkl"), "rb"))
    te_all = np.concatenate([x["te"] for x in allres.values()])
    wf_days = sorted({D.ddate(x) for x in np.unique(R.day.values[te_all])})
    t = Mo.trades_df(R, pnl, ch["label"], trades[ch["variant"]])
    out = dict(choice=ch, wf=block(t, wf_days, ch["label"]))
    hp = os.path.join(OUT, "holdout_trades.csv")
    if os.path.exists(hp):
        h = pd.read_csv(hp, parse_dates=["day"])
        h["day"] = h.day.dt.date
        hdays = sorted({D.ddate(x) for x in np.unique(R.day.values[R.day.values >= Mo.HOLD])})
        out["holdout"] = block(h, hdays, ch["label"])
        out["holdout_meta"] = json.load(open(os.path.join(OUT, "holdout.json")))
    # all-variant summary on the walk-forward
    V = pd.read_csv(os.path.join(OUT, "variants_scored.csv"))
    out["by_model"] = V.groupby("model")[["gross", "net", "nets"]].median().round(0).to_dict()
    out["by_label"] = V.groupby("label")[["gross", "net", "nets"]].median().round(0).to_dict()
    out["top_gross"] = V.sort_values("gross", ascending=False).head(5)[["variant", "trades", "gross", "net", "p_rand_gross", "bh_q_gross"]].to_dict("records")
    json.dump(out, open(os.path.join(OUT, "report.json"), "w"), indent=1, default=str)
    print(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
