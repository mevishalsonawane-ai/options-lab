"""STRAD-INDEX size forecasts: HAR-X (ridge), GBM, MLP, ensemble; anchored walk-forward by year (PREREG.md).

    flock <scratch>/obuy.lock python3 -I research/hunt/strad_index/models.py
Reads feat_<U>.parquet; writes fc.parquet (one row per (und, day, s): fold, fc_<h> for every horizon) and
model_skill.csv (out-of-sample R^2 / Spearman of each model, per fold and horizon).
"""
from __future__ import annotations

import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import spearmanr  # noqa: E402
from sklearn.ensemble import HistGradientBoostingRegressor  # noqa: E402
from sklearn.linear_model import Ridge  # noqa: E402
from sklearn.neural_network import MLPRegressor  # noqa: E402
from sklearn.preprocessing import StandardScaler  # noqa: E402
from obuy import config as C  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/strad_index")
HOLD = pd.Timestamp("2025-10-01")
HN = ["15", "30", "60", "120", "eod"]
BASE = ["lrv15", "lrv30", "lrv60", "lrv_day", "lrv_y", "lrv_5d", "lrange_tod", "ret_tod", "lrng_y", "gap", "sgap",
        "ret_y", "down1", "s", "wd", "dte", "expday", "monthly", "bn", "vix_l", "vix_chg", "B_lvl", "A_share",
        "rbi", "rbi_pre", "budget", "election", "post_cpi", "post_fomc"]
NANS = ["vix_chg", "B_lvl", "A_share"]


def load():
    F = pd.concat([pd.read_parquet(os.path.join(OUT, f"feat_{u}.parquet")) for u in ("NIFTY", "BANKNIFTY")],
                  ignore_index=True)
    F["day"] = pd.to_datetime(F["day"])
    F = F.sort_values(["und", "s", "day"]).reset_index(drop=True)
    lg = np.log(F["gtot"].where(F["gtot"] > 0))
    med = lg.groupby([F["und"], F["s"]]).transform(lambda x: x.rolling(20, min_periods=10).median().shift(1))
    F["B_lvl"] = lg - med
    F["dte"] = np.log1p(F["dte"])
    return F.sort_values(["und", "day", "s"]).reset_index(drop=True)


def design_matrix(X, slot_mean):
    Z = X[BASE].copy()
    for c in NANS:
        Z[c + "_na"] = Z[c].isna().astype(float)
        Z[c] = Z[c].fillna(0.0)
    Z["slot"] = slot_mean
    return Z.values.astype(float)


def folds(F):
    yrs = F.day.dt.year
    out = []
    for Y in (2022, 2023, 2024, 2025):
        tr = F.day < pd.Timestamp(f"{Y}-01-01")
        te = (yrs == Y) & (F.day < HOLD)
        seed = (yrs == Y - 1) if Y == 2022 else pd.Series(False, index=F.index)   # 2021 in-sample seed for thresholds
        out.append((str(Y), tr, te, seed))
    out.append(("hold", F.day < HOLD, F.day >= HOLD, pd.Series(False, index=F.index)))
    return out


def run():
    F = load()
    fc = pd.DataFrame({"und": F.und, "day": F.day, "s": F.s, "fold": ""})
    skill = []
    for name, tr, te, seed in folds(F):
        fc.loc[te, "fold"] = name
        fc.loc[seed & (fc.fold == ""), "fold"] = "seed2021"
        for h in HN:
            y = np.log(F[f"rv_{h}"].where(F[f"rv_{h}"] > 0))
            ok = y.notna()
            trm, pm = tr & ok, (te | seed) & F[f"rv_{h}"].notna()
            if pm.sum() == 0:
                continue
            sm = y[trm].groupby([F.und[trm], F.s[trm]]).mean()
            slot = pd.Series(list(zip(F.und, F.s))).map(sm).values
            Xtr, Xp = design_matrix(F[trm], slot[trm.values]), design_matrix(F[pm], slot[pm.values])
            ytr = y[trm].values
            sc = StandardScaler().fit(Xtr)
            preds_tr, preds = {}, {}
            m = Ridge(alpha=1.0).fit(sc.transform(Xtr), ytr)
            preds_tr["har"], preds["har"] = m.predict(sc.transform(Xtr)), m.predict(sc.transform(Xp))
            Xg = F.loc[trm, BASE].values.astype(float)
            g = HistGradientBoostingRegressor(max_iter=300, learning_rate=0.05, max_leaf_nodes=15,
                                              min_samples_leaf=200, l2_regularization=1.0, random_state=0)
            g.fit(Xg, ytr)
            preds_tr["gbm"], preds["gbm"] = g.predict(Xg), g.predict(F.loc[pm, BASE].values.astype(float))
            nn = MLPRegressor(hidden_layer_sizes=(32, 16), alpha=1e-3, early_stopping=True, random_state=0, max_iter=300)
            nn.fit(sc.transform(Xtr), ytr)
            preds_tr["mlp"], preds["mlp"] = nn.predict(sc.transform(Xtr)), nn.predict(sc.transform(Xp))
            ens_tr = np.mean([preds_tr[k] for k in preds_tr], axis=0)
            ens = np.mean([preds[k] for k in preds], axis=0)
            bias = np.log(np.exp(ytr).mean() / np.exp(ens_tr).mean())
            fc.loc[pm, f"fc_{h}"] = np.exp(ens + bias)
            for k in preds:
                fc.loc[pm, f"fc_{h}_{k}"] = np.exp(preds[k] + bias)   # descriptive only
            # out-of-sample skill (test rows, target known)
            tm = (te & ok)[pm].values
            yt = y[pm].values[tm]
            for k, v in list(preds.items()) + [("ens", ens), ("slot_only", slot[pm.values])]:
                v = v[tm]
                r2 = 1 - np.mean((yt - v - (yt - v).mean() * 0) ** 2) / np.var(yt)
                skill.append(dict(fold=name, h=h, model=k, n=int(tm.sum()), r2=r2,
                                  spearman=spearmanr(yt, v).correlation))
            if name != "hold":      # holdout skill is not looked at until the holdout step
                print(name, h, int(trm.sum()), int(pm.sum()),
                      {s["model"]: round(s["r2"], 3) for s in skill[-5:]}, flush=True)
    fc.to_parquet(os.path.join(OUT, "fc.parquet"))
    sk = pd.DataFrame(skill)
    sk[sk.fold != "hold"].to_csv(os.path.join(OUT, "model_skill.csv"), index=False)
    sk[sk.fold == "hold"].to_csv(os.path.join(OUT, "model_skill_hold.csv"), index=False)


if __name__ == "__main__":
    run()
