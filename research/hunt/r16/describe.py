"""R16 post-hoc DESCRIPTIVES (not tests; nothing here changes a verdict). Run after `run.py hold`.

    python3 -I research/hunt/r16/describe.py <scratch>
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from run import HOLD_INTRA, VOL_F, LOT, HS  # noqa: E402


def main():
    scr = sys.argv[1]
    out = {}
    for sym in ("BANKNIFTY", "NIFTY"):
        A = pd.read_parquet(os.path.join(scr, f"rows_{sym}_intra.parquet"))
        A["day"] = pd.to_datetime(A.day).dt.date
        z = np.load(os.path.join(scr, f"{sym}.npz"))
        px = float(np.median(z["ix"][-120:, :, 3]))
        for name, h in (("a1", 30), ("a2", 60)):
            p = pd.read_parquet(os.path.join(scr, f"preds_hold_{name}_{sym}.parquet"))
            ratio = np.exp(p[f"v{h}"] - p.pred)
            rb = np.exp(p[f"v{h}"] - p.base)
            pts_pred = np.exp(p.pred) * px
            out[f"{name}_{sym}_hold"] = dict(
                median_pred_pts=float(np.median(pts_pred)), p10_pred_pts=float(np.quantile(pts_pred, .1)),
                p90_pred_pts=float(np.quantile(pts_pred, .9)),
                actual_over_pred_q10_50_90=[float(x) for x in np.quantile(ratio, [.1, .5, .9])],
                actual_over_baseline_q10_50_90=[float(x) for x in np.quantile(rb, [.1, .5, .9])],
                corr_log=float(np.corrcoef(p[f"v{h}"], p.pred)[0, 1]), corr_log_base=float(np.corrcoef(p[f"v{h}"], p.base)[0, 1]))
            # 'bigger than usual' signal: top-quintile forecasts vs bottom-quintile, realised range ratio
            q = pd.qcut(p.pred, 5, labels=False)
            out[f"{name}_{sym}_hold"]["realised_pts_by_forecast_quintile"] = [float(np.median(np.exp(p[f"v{h}"][q == k]) * px)) for k in range(5)]
        # standardised linear coefficients for a1 (DESIGN data only), for porting a simple formula to the app
        from sklearn.linear_model import Ridge
        d = A[(A.t >= 30) & (A.t <= 330) & A.v30.notna() & (A.day < HOLD_INTRA[sym])]
        f = VOL_F + ["btod30"]
        X = d[f].astype(float)
        X = X.fillna(X.median())
        Xs = (X - X.mean()) / X.std().replace(0, 1)
        m = Ridge(alpha=1.0).fit(Xs, d.v30)
        co = pd.Series(m.coef_, index=f).sort_values(key=np.abs, ascending=False)
        out[f"a1_{sym}_linear_top_coefs"] = co.head(8).round(3).to_dict()
        # the same model with only 4 inputs (time-of-day mean, last 30 and 60 min range, VIX): how much is lost
        small = ["btod30", "lr30", "lr60", "lvix"]
        from lib import walk
        dd = A[(A.t >= 30) & (A.t <= 330) & A.v30.notna()].reset_index(drop=True)
        pr, _ = walk(dd, small, "v30", "linear", "reg")
        ok = pr.notna() & dd.btod30.notna()
        hold = ok & (dd.day >= HOLD_INTRA[sym])
        des = ok & (dd.day < HOLD_INTRA[sym])
        mae = lambda s, x: float(np.mean(np.abs(x[s] - dd.v30[s])))
        out[f"a1_{sym}_4input_linear"] = dict(design_skill_vs_btod=1 - mae(des, pr) / mae(des, dd.btod30),
                                              hold_skill_vs_btod=1 - mae(hold, pr) / mae(hold, dd.btod30))
        # expiry-day vs other days full-session range (all intraday sessions)
        S = pd.read_parquet(os.path.join(scr, f"rows_{sym}_day.parquet"))
        ix = z["ix"]
        rng = (ix[:, :, 1].max(1) - ix[:, :, 2].min(1)) / ix[:, -1, 3] * 100
        # (the release files hold the NEXT expiry's chain on expiry days, so the calendar comes from the bhavcopy)
        ex = pd.read_csv(os.path.join(scr, "expiries.csv"))
        ex = set(ex[(ex.sym == sym) & ex.max_pain.notna()].expiry.astype(str))
        ise = np.array([str(d) in ex for d in z["days"]])
        out[f"{sym}_range_pct_expiry_vs_other"] = dict(expiry=float(np.median(rng[ise])), other=float(np.median(rng[~ise])),
                                                      n_expiry=int(ise.sum()))
        # round-trip cost of an ATM option trade (spread + charges), Rs per lot, on the b15 decision rows
        from obuy.costs import Costs
        c = Costs()
        o = A[A.ce_in15.notna()]
        e = o.ce_in15.values
        cost = e * 2 * HS * LOT[sym] + np.array([c.charge(True, x, LOT[sym]) + c.charge(False, x, LOT[sym]) for x in e])
        out[f"{sym}_atm_roundtrip_cost"] = dict(median_rs=float(np.median(cost)), median_premium=float(np.median(e)),
                                                index_pts_to_cover_at_delta_half=float(np.median(cost) / LOT[sym] / 0.5))
        out[f"{sym}_index_px_recent"] = px
    # a portable 4-input formula (fitted on DESIGN rows only, raw units) for the app to shadow-test
    form = {}
    for sym in ("BANKNIFTY", "NIFTY"):
        A = pd.read_parquet(os.path.join(scr, f"rows_{sym}_intra.parquet"))
        A["day"] = pd.to_datetime(A.day).dt.date
        for h in (30, 60):
            d = A[(A.t >= 30) & (A.t <= 360 - h) & A[f"v{h}"].notna() & A[f"btod{h}"].notna() & (A.day < HOLD_INTRA[sym])]
            X = d[[f"btod{h}", "lr30", "lr60", "lvix"]].astype(float)
            Xa = np.c_[np.ones(len(X)), X.values]
            b = np.linalg.lstsq(Xa, d[f"v{h}"].values, rcond=None)[0]
            form[f"{sym}_next{h}"] = dict(zip(["const", f"btod{h}", "lr30", "lr60", "lvix"], [float(x) for x in b]))
    out["vol_formula_log_range"] = form
    # daily VIX-implied next-day range in points (holdout)
    for sym in ("BANKNIFTY", "NIFTY"):
        p = pd.read_parquet(os.path.join(scr, f"preds_hold_a3_{sym}.parquet"))
        r = np.exp(p.y - p.base)
        out[f"a3_{sym}_vix_baseline_actual_over_pred_q10_50_90"] = [float(x) for x in np.quantile(r, [.1, .5, .9])]
        out[f"a3_{sym}_median_pred_range_pct"] = float(np.median(np.exp(p.base)) * 100)
    json.dump(out, open(os.path.join(scr, "describe.json"), "w"), indent=1)
    print(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
