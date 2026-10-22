"""R98 predictive part (PREREG sections 5-6): walk-forward models and the 75% test.

    python3 -I research/hunt/r98/model.py     # writes results/model.json and $R98_OUT/scores.parquet
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.impute import SimpleImputer

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r98data as R  # noqa: E402
from rows import EXIT_COLS, EXITS  # noqa: E402

RESULTS = Path(__file__).resolve().parent / "results"
TEST_YEARS = [2023, 2024, 2025, 2026]
LABELS = ["y2", "y5", "y10"]
HOLD = "x_inf_0"          # no target, no stop: out at 15:20


def hgb():
    return HistGradientBoostingClassifier(max_iter=300, learning_rate=0.05, max_leaf_nodes=31, l2_regularization=1.0,
                                          early_stopping=False, random_state=98)


def logit():
    return make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), LogisticRegression(max_iter=2000, C=1.0))


def walk_forward(df: pd.DataFrame, feats: list[str], label: str, make) -> pd.Series:
    score = pd.Series(np.nan, index=df.index)
    yr = pd.to_datetime(df.day).dt.year
    for y in TEST_YEARS:
        tr, te = df[yr < y], df[yr == y]
        if te.empty or tr[label].nunique() < 2:
            continue
        m = make()
        m.fit(tr[feats].values, tr[label].values.astype(int))
        score.loc[te.index] = m.predict_proba(te[feats].values)[:, 1]
    return score


def precision_table(df, score, label):
    """Out-of-sample precision of the top q of each test year's rows, pooled; and the best precision over all score
    thresholds with >= 100 picks on >= 30 days (PREREG 6.2)."""
    d = df.assign(s=score).dropna(subset=["s"])
    d["yr"] = pd.to_datetime(d.day).dt.year
    out = {}
    for q in (0.001, 0.005, 0.01, 0.02, 0.05, 0.10, 0.20):
        picks = d[d.s >= d.groupby("yr").s.transform(lambda x: x.quantile(1 - q))]
        out[f"top{q:g}"] = dict(picks=len(picks), days=int(picks.groupby(["und", "day"]).ngroups),
                                precision=float(picks[label].mean()), mean_net_hold=float(picks[HOLD].mean()),
                                win_hold=float((picks[HOLD] > 0).mean()))
    srt = d.sort_values("s", ascending=False)
    cum = srt[label].astype(float).cumsum().values / np.arange(1, len(srt) + 1)
    dkey = (srt.und + srt.day.astype(str)).values
    seen, ndays = set(), np.empty(len(srt), dtype=int)
    for i, k in enumerate(dkey):
        seen.add(k)
        ndays[i] = len(seen)
    ok = (np.arange(1, len(srt) + 1) >= 100) & (ndays >= 30)
    j = int(np.argmax(np.where(ok, cum, -1)))
    out["best_threshold"] = dict(picks=j + 1, days=int(ndays[j]), precision=float(cum[j]),
                                 mean_net_hold=float(srt[HOLD].iloc[:j + 1].mean()))
    out["auc_pooled"] = float(roc_auc_score(d[label].astype(int), d.s))
    out["auc_by_year"] = {int(y): float(roc_auc_score(g[label].astype(int), g.s)) for y, g in d.groupby("yr")
                          if g[label].nunique() == 2}
    out["base_rate_test"] = float(d[label].mean())
    # one ticket per index-day: the top-scored row
    top = d.loc[d.groupby(["und", "day"]).s.idxmax()]
    out["one_per_day"] = dict(picks=len(top), precision=float(top[label].mean()), mean_net_hold=float(top[HOLD].mean()),
                              win_hold=float((top[HOLD] > 0).mean()),
                              best_exit=max(((c, float(top[c].mean()), float((top[c] > 0).mean())) for c in EXIT_COLS),
                                            key=lambda z: z[1]))
    return out


def frontier(d: pd.DataFrame) -> list[dict]:
    rows = []
    for (tg, st), c in zip(EXITS, EXIT_COLS):
        rows.append(dict(target=tg if np.isfinite(tg) else None, stop=st if st > 0 else None,
                         win=float((d[c] > 0).mean()), mean_net=float(d[c].mean()), median_net=float(d[c].median()),
                         p05=float(d[c].quantile(0.05))))
    return rows


def day_level(df: pd.DataFrame, feats_day: list[str]) -> dict:
    """PREREG 6(c): at 13:00, will any cheap option run 10x by 15:20? Day features = the 13:00 rows' day-wide features
    (the same for every option of the day except the side-signed ones, which are taken from the call side)."""
    why = pd.read_parquet(R.OUT / "why.parquet")
    w = why[why.tau == "13:00"].copy()
    w["y"] = w[["best_call", "best_put"]].max(axis=1) >= 10
    r = df[(df.t == R.minute(13, 0)) & (df.side == "C")].sort_values("f_strikes").groupby(["und", "day"]).head(1)
    x = r[["und", "day"] + feats_day].merge(w[["und", "day", "y"]], on=["und", "day"])
    out = {}
    for name, make in (("hgb", hgb), ("logit", logit)):
        s = walk_forward(x, feats_day, "y", make)
        d = x.assign(s=s).dropna(subset=["s"])
        res = dict(days=len(d), base=float(d.y.mean()), auc=float(roc_auc_score(d.y.astype(int), d.s)))
        for q in (0.05, 0.10, 0.20, 0.30):
            p = d[d.s >= d.s.quantile(1 - q)]
            res[f"top{q:g}"] = dict(days=len(p), precision=float(p.y.mean()))
        out[name] = res
    return out


def main():
    df = pd.read_parquet(R.OUT / "rows.parquet")
    feats = [c for c in df.columns if c.startswith("f_")]
    yr = pd.to_datetime(df.day).dt.year
    res = dict(rows=len(df), days=int(df.groupby(["und", "day"]).ngroups), features=feats)
    # base rates, all rows
    res["base"] = {u: dict(rows=len(g), y2=float(g.y2.mean()), y5=float(g.y5.mean()), y10=float(g.y10.mean()),
                           mean_net_hold=float(g[HOLD].mean()), win_hold=float((g[HOLD] > 0).mean()))
                   for u, g in list(df.groupby("und")) + [("ALL", df)]}
    scores = pd.DataFrame(index=df.index)
    for lab in LABELS:
        for name, make in (("hgb", hgb), ("logit", logit)):
            s = walk_forward(df, feats, lab, make)
            scores[f"{name}_{lab}"] = s
            res[f"{name}_{lab}"] = precision_table(df, s, lab)
            print(name, lab, json.dumps({k: res[f"{name}_{lab}"][k] for k in ("auc_pooled", "best_threshold")}), flush=True)
    scores.to_parquet(R.OUT / "scores.parquet")
    test = df[yr.isin(TEST_YEARS)]
    res["frontier_all_test_rows"] = frontier(test)
    s5 = scores.loc[test.index, "hgb_y5"]
    res["frontier_top10_hgb_y5"] = frontier(test[s5 >= s5.groupby(yr[test.index]).transform(lambda x: x.quantile(0.9))])
    s2 = scores.loc[test.index, "hgb_y2"]
    res["frontier_top10_hgb_y2"] = frontier(test[s2 >= s2.groupby(yr[test.index]).transform(lambda x: x.quantile(0.9))])
    feats_day = ["f_vix", "f_vix_chg", "f_vix_chg30", "f_r15", "f_r30", "f_r60", "f_rday", "f_gap", "f_em_pct",
                 "f_rng_prev", "f_stx", "f_wd", "f_monthly"] + [f"f_und_{u}" for u in R.UNDS]
    res["day_level"] = day_level(df, feats_day)
    # the zero side: share of cheap options worth < 10% of entry (or under Rs 0.10) at their 15:20 exit
    res["zero_side"] = {u: dict(rows=len(g), to_zero=float(((g.end_px < 0.1 * g.entry) | (g.end_px < 0.10)).mean()),
                                below_entry=float((g.end_px < g.entry).mean()))
                        for u, g in list(df.groupby("und")) + [("ALL", df)]}
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "model.json").write_text(json.dumps(res, indent=1, default=str))
    print("written", RESULTS / "model.json")


if __name__ == "__main__":
    main()
