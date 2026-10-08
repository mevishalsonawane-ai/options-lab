"""h40 Part C: positioning features as a FILTER on the app's Liquidity 15+5 trades (rules unchanged). PREREG.md.

    python3 -I research/hunt/h40/liqfilter.py            # PRE (< 2025-10-01)
    python3 -I research/hunt/h40/liqfilter.py --holdout  # survivors only (or info: the best PRE one), once

Trades: h24 trades24.parquet (flat_x1 = real spread, kappa 0.02) for BANKNIFTY / MIDCPNIFTY; h23 trades.parquet
net_0.02 for NIFTY / FINNIFTY / SENSEX (as h28). Variants: 16 features x thr 0.5 x {skip-opposed, only-agreeing} x 5.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.overfit import bh  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h40")
HOLD = date(2025, 10, 1)
UNDS = ("BANKNIFTY", "MIDCPNIFTY", "NIFTY", "FINNIFTY", "SENSEX")
FEATS = ["S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11", "S12", "S12L", "S13", "S14",
         "S15"]
B = 2000


def load_trades():
    t24 = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h24/trades24.parquet"))
    t24 = t24[(t24.model == "flat_x1") & (t24.kappa == 0.02) & (t24.lots > 0)][["und", "day", "side", "net", "gross"]]
    t23 = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h23/trades.parquet"))
    t23 = t23[t23.und.isin(["NIFTY", "FINNIFTY", "SENSEX"]) & (t23["lots_0.02"] > 0)]
    t23 = t23.rename(columns={"net_0.02": "net", "gross_0.02": "gross"})[["und", "day", "side", "net", "gross"]]
    t = pd.concat([t24, t23], ignore_index=True)
    t["day"] = pd.to_datetime(t.day).dt.date
    return t


def main(holdout=False):
    f = pd.read_parquet(os.path.join(OUT, "feat.parquet"))
    f["day"] = pd.to_datetime(f.day).dt.date
    tr = load_trades().merge(f, on=["und", "day"], how="left")
    rng = np.random.default_rng(11)
    rows = []
    for und in UNDS:
        t = tr[tr.und == und]
        t = t[(t.day >= HOLD) if holdout else (t.day < HOLD)]
        if t.empty:
            continue
        sess = f[(f.und == und) & (f.day >= t.day.min()) & (f.day <= t.day.max())].day.nunique()
        nv = t.net.values
        yrs = np.array([d.year for d in t.day])
        for F in FEATS:
            x = t[F].fillna(0).values
            sg = np.where(x >= .5, 1, np.where(x <= -.5, -1, 0))
            agree = sg == t.side.values
            oppose = sg == -t.side.values
            for mode, keep in (("skip_opp", ~oppose), ("only_agree", agree)):
                k = int(keep.sum())
                r = dict(und=und, feat=F, mode=mode, sessions=sess, trades=len(nv), kept=k,
                         base_day=nv.sum() / sess, kept_day=nv[keep].sum() / sess,
                         kept_gross_day=t.gross.values[keep].sum() / sess, gain_day=(nv[keep].sum() - nv.sum()) / sess,
                         mean_kept=nv[keep].mean() if k else np.nan, mean_drop=nv[~keep].mean() if k < len(nv) else np.nan)
                if 10 <= k <= len(nv) - 10:
                    obs = nv[keep].sum()
                    perm = np.array([nv[rng.choice(len(nv), k, replace=False)].sum() for _ in range(B)])
                    r["p"] = (1 + (perm >= obs).sum()) / (B + 1)
                else:
                    r["p"] = 1.0
                yk = pd.Series(nv * keep, index=yrs).groupby(level=0).sum()
                ya = pd.Series(nv, index=yrs).groupby(level=0).sum()
                r["years_better"] = f"{int(((yk - ya) > 0).sum())}/{len(ya)}"
                r["yb_frac"] = float(((yk - ya) > 0).mean())
                rows.append(r)
    df = pd.DataFrame(rows)
    pd.set_option("display.width", 250)
    if not holdout:
        df["q"] = bh(df.p.values)
        df["surv"] = (df.q < .05) & (df.gain_day > 20) & (df.yb_frac >= .6)
        df.to_csv(os.path.join(OUT, "liqfilter_pre.csv"), index=False)
        print("variants:", len(df), "| min q", round(df.q.min(), 3), "| survivors", df.surv.sum())
        print(df.sort_values("p").head(20).round(3).to_string())
        print("baseline Rs/day by index:", df.groupby("und").base_day.first().round(0).to_dict())
        ch = df[df.surv][["und", "feat", "mode"]].to_dict("records") or \
            [df.sort_values(["p", "gain_day"], ascending=[True, False]).iloc[0][["und", "feat", "mode"]].to_dict()]
        json.dump(dict(choice=ch, survivors=int(df.surv.sum())), open(os.path.join(OUT, "liqfilter_choice.json"), "w"),
                  default=str)
    else:
        ch = json.load(open(os.path.join(OUT, "liqfilter_choice.json")))["choice"]
        keep = [(c["und"], c["feat"], c["mode"]) for c in ch]
        df = df[[(u, a, m) in keep for u, a, m in zip(df.und, df.feat, df["mode"])]]
        df.to_csv(os.path.join(OUT, "liqfilter_holdout.csv"), index=False)
        print(df.round(3).to_string())


if __name__ == "__main__":
    main("--holdout" in sys.argv)
